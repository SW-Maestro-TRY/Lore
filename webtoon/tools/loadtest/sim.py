#!/usr/bin/env python3
"""JobRunner 의 줄 세우기를 그대로 흉내 낸 시뮬레이터 — 「N 명이 동시에 누르면 마지막
사람은 몇 분 기다리나」를 실제 단계 시간으로 계산한다.

흉내 내는 구조(webtoon/be/.../job/JobRunner.java):
  line  = 고정 스레드 W개(lore.webtoon.workers) — 이야기·고른 뒤·시트 확정 뒤·그림 걸음이 차례로 줄을 선다
  paint = 고정 스레드 P개(lore.webtoon.page-workers) — **서버 전체가 나눠 쓴다**. 그림 걸음의
          line 스레드는 자기 장들이 다 그려질 때까지 붙잡혀 기다린다
  side  = 제한 없음 — 시트는 이야기와 동시에 바로 시작
  1) line: 이야기(story)                         -> awaiting_pick
  2) line: pick-save + 먼저 띄운 시트가 끝날 때까지 기다림 -> awaiting_sheet
  3) line: 장면 나누기(scenes)                   -> awaiting_scenes
  4) line: 장 P개씩 paint 에 넣고 기다림 → 전체 검수 → 이어 붙이기 → 올리기 -> done
사람이 확인하는 자리마다 think 초 뒤에 누른다(기본 0).

    python3 sim.py --profile real --workers 2,3,4,6 --page-workers 3,4,6 --n 1,2,5,10,20
"""

from __future__ import annotations

import argparse
import heapq
import itertools
import json
from collections import deque
from pathlib import Path

HERE = Path(__file__).resolve().parent

# 템플릿 run(20261003T213320-5297d4)의 실제 시간 — meta.json 호출 시간 + 파일 mtime 으로 잰 것.
#   이야기 11s(21:33:21 → directions.json 21:33:32, 인물·페르소나·이야기 호출이 겹쳐 돈다)
#   시트 41s(21:33:21 → sheet.png 21:34:02, 사양 6.4s + 그림 34.5s)
#   장면 32s(scene_prompt 21:36:34 → scenes.json 21:37:06, SCENE 31.6s)
#   장마다 그림+검수: meta 의 PAGE_IMAGE + PAGE_REVIEW/COVER_REVIEW
#   전체 검수 20s(FULL_REVIEW 19.75s) + 그 앞 10s(21:39:35 마지막 장 → 21:39:45 검수 시작)
#   이어 붙이기·줄이기·올리기: Mac 실측 stitch 2.0s · prepare 0.4s · 올리기 ~0.5s.
#     t3.medium(vCPU 2, 버스트형)은 이보다 느리므로 3배로 잡는다(stitch 6s, 올리기 3s).
REAL = {"story": 11.0, "sheet": 41.0, "pick_save": 1.0, "scenes": 32.0,
        "pages": [43.07, 39.05, 38.90, 42.90, 38.56, 45.19, 41.96, 38.03],
        "pre_review": 10.0, "full_review": 20.0, "stitch": 6.0, "upload": 3.0}

# 가짜 하네스 FAKE_SPEED=0.1 로 잰 것과 맞춰 보는 용도(시뮬레이터 검증).
#   fakepy 는 이야기를 호출 합계(24s)로 자므로 2.4s, 시트 4.1s, 장면 3.2s, 장 ~4s,
#   전체 검수 2.0s, stitch 2.0s · prepare 0.4s · 올리기 0.4s(Mac 실측)
FAST = {"story": 2.4, "sheet": 4.1, "pick_save": 0.1, "scenes": 3.2,
        "pages": [4.31, 3.91, 3.89, 4.29, 3.86, 4.52, 4.20, 3.80],
        "pre_review": 0.0, "full_review": 2.0, "stitch": 2.0, "upload": 0.8}


def page_times_from_meta(meta: Path) -> list[float]:
    calls = json.loads(meta.read_text(encoding="utf-8"))["calls"]
    per: dict[int, float] = {}
    for c in calls:
        if c.get("stage") in ("PAGE_IMAGE", "PAGE_REVIEW", "COVER_REVIEW") and c.get("page"):
            per[c["page"]] = per.get(c["page"], 0) + float(c["seconds"])
    return [per[k] for k in sorted(per)]


class Sim:
    def __init__(self, W: int, P: int, d: dict, think: float):
        self.t = 0.0
        self.ev = []
        self.seq = itertools.count()
        self.W, self.P, self.d, self.think = W, P, d, think
        self.line_free, self.paint_free = W, P
        self.line_q: deque = deque()
        self.paint_q: deque = deque()
        self.res = []

    def at(self, dt, fn):
        heapq.heappush(self.ev, (self.t + dt, next(self.seq), fn))

    # ---- pools
    def line(self, job, task):
        job["q_since"] = self.t
        self.line_q.append((job, task))
        self.kick_line()

    def kick_line(self):
        while self.line_free and self.line_q:
            job, task = self.line_q.popleft()
            job["queued"] += self.t - job["q_since"]
            if job.get("first_run") is None:
                job["first_run"] = self.t
            self.line_free -= 1
            task(job)

    def release_line(self):
        self.line_free += 1
        self.kick_line()

    def paint(self, dur, done):
        self.paint_q.append((dur, done))
        self.kick_paint()

    def kick_paint(self):
        while self.paint_free and self.paint_q:
            dur, done = self.paint_q.popleft()
            self.paint_free -= 1

            def fin(done=done):
                self.paint_free += 1
                done()
                self.kick_paint()
            self.at(dur, fin)

    # ---- one job
    def start(self, i):
        job = {"i": i, "created": self.t, "queued": 0.0, "first_run": None}
        job["sheet_done"] = self.t + self.d["sheet"]          # side 풀: 바로 시작
        self.line(job, self.story)

    def story(self, job):
        def end():
            self.release_line()
            self.at(self.think, lambda: self.line(job, self.pick))
        self.at(self.d["story"], end)

    def pick(self, job):
        wait = max(self.d["pick_save"], job["sheet_done"] - self.t)   # 시트를 기다리며 스레드를 붙잡는다

        def end():
            self.release_line()
            self.at(self.think, lambda: self.line(job, self.scenes))
        self.at(wait, end)

    def scenes(self, job):
        def end():
            self.release_line()
            self.at(self.think, lambda: self.line(job, self.pages))
        self.at(self.d["scenes"], end)

    def pages(self, job):
        left = [len(self.d["pages"])]

        def one_done():
            left[0] -= 1
            if left[0] == 0:
                tail = self.d["pre_review"] + self.d["full_review"] + self.d["stitch"] + self.d["upload"]
                self.at(tail, finish)

        def finish():
            job["ended"] = self.t
            self.res.append(job)
            self.release_line()
        for dur in self.d["pages"]:
            self.paint(dur, one_done)

    def run(self, n):
        for i in range(n):
            self.start(i)
        while self.ev:
            self.t, _, fn = heapq.heappop(self.ev)
            fn()
        return self.res


def summarize(res, n):
    tot = sorted(j["ended"] - j["created"] for j in res)
    q = sorted(j["queued"] for j in res)
    q1 = sorted(j["first_run"] - j["created"] for j in res)
    wall = max(j["ended"] for j in res)
    med = lambda xs: xs[len(xs) // 2]
    return {"n": n, "total_med": med(tot), "total_max": tot[-1], "queue_med": med(q), "queue_max": q[-1],
            "queue_first_max": q1[-1], "jobs_per_hour": n / wall * 3600}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", choices=["real", "fast"], default="real")
    ap.add_argument("--workers", default="2,3,4,6")
    ap.add_argument("--page-workers", default="3,4,6")
    ap.add_argument("--n", default="1,2,5,10,20")
    ap.add_argument("--think", type=float, default=0.0, help="확인 자리마다 사람이 누르기까지 초")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    d = REAL if a.profile == "real" else FAST
    rows = []
    for w in map(int, a.workers.split(",")):
        for p in map(int, a.page_workers.split(",")):
            for n in map(int, a.n.split(",")):
                s = summarize(Sim(w, p, d, a.think).run(n), n)
                s.update({"workers": w, "page_workers": p})
                rows.append(s)
    if a.json:
        print(json.dumps(rows, ensure_ascii=False))
        return
    unit = 60 if a.profile == "real" else 1
    lab = "분" if unit == 60 else "초"
    print(f"W P  N  전체중앙({lab}) 마지막사람({lab}) 줄대기중앙 줄대기최대 처리량(편/시)")
    for r in rows:
        print(f"{r['workers']} {r['page_workers']} {r['n']:>2}  {r['total_med']/unit:8.1f} {r['total_max']/unit:10.1f}"
              f" {r['queue_med']/unit:9.1f} {r['queue_max']/unit:9.1f} {r['jobs_per_hour']:9.1f}")


if __name__ == "__main__":
    main()
