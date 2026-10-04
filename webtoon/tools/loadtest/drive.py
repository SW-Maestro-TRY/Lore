#!/usr/bin/env python3
"""웹툰 만들기 부하 시험 — N 편을 동시에, 게스트 N 명으로 끝까지 돌린다.

화면(webtoon/fe/lib/api.ts)과 같은 API 만 쓴다.

    POST /nh/create                        (uid 만 다르게, checkpoints=true —「확인하고 만들기」)
    GET  /nh/jobs/{id}                     0.8초마다(화면과 같다)
    POST /nh/jobs/{id}/cast                awaiting_cast   -> 1번(확인이면 0)
    POST /nh/jobs/{id}/pick                awaiting_pick   -> 첫 후보
    POST /nh/jobs/{id}/sheet-decision      awaiting_sheet  -> approve
    POST /nh/jobs/{id}/scenes-continue     awaiting_scenes -> 그대로 진행

사람이 확인하는 자리에서는 바로 누른다(생각하는 시간 0). 서버 JVM 의 RSS·CPU 와
하네스 자식 프로세스 수·CPU 를 몇 초마다 ps 로 같이 잰다.

    python3 drive.py --n 5 --base http://localhost:8097 --pid <java pid> --out results/n5.json
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

API = "/api/webtoon/v1"


def http(base: str, method: str, path: str, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(base + API + path, data=data, method=method,
                                 headers={"Content-Type": "application/json",
                                          "Accept": "application/json"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            raw = res.read()
            code = res.status
    except urllib.error.HTTPError as e:
        raw = e.read()
        code = e.code
    took = time.time() - t0
    try:
        got = json.loads(raw.decode("utf-8")) if raw else {}
    except ValueError:
        got = {"raw": raw[:300].decode("utf-8", "replace")}
    if isinstance(got, dict) and "data" in got and isinstance(got["data"], dict) and "status" not in got:
        got = got["data"]
    return code, got, took


CREATE = {
    "name": "부하시험",
    "character": "부하 시험용 입력 — 가짜 하네스가 템플릿을 되풀이한다",
    "photo_note": "", "fields": {}, "genre": "", "story": "",
    "style": "romance", "quality": "surf", "language": "ko",
    "photos_data": [], "agree_ip": True, "checkpoints": True,
}


class Job:
    def __init__(self, i: int):
        self.i = i
        self.uid = "lt-" + uuid.uuid4().hex[:12]
        self.id = None
        self.created = None
        self.ended = None
        self.status = None
        self.error = None
        self.first_run = None          # 처음 queued 를 벗어난 때
        self.time_in = {}              # status -> 초
        self.stage_time = {}           # running 중 stage -> 초
        self.confirms = []             # (status, 응답까지 걸린 초)
        self.polls = []                # GET 지연
        self.run_id = None
        self.pages_ok = None


def run_one(job: Job, base: str, poll: float, give_up: float, start_gate: threading.Event):
    start_gate.wait()
    job.created = time.time()
    code, got, _ = http(base, "POST", "/nh/create", dict(CREATE, uid=job.uid))
    if code != 200 or not got.get("id"):
        job.status, job.error, job.ended = "create_failed", f"{code} {got}", time.time()
        return
    job.id = got["id"]
    last_t, last_s, last_stage = time.time(), "queued", None
    acted = set()
    while True:
        if time.time() - job.created > give_up:
            job.status, job.error = "timeout", f"last={last_s}"
            break
        code, v, took = http(base, "GET", f"/nh/jobs/{job.id}")
        job.polls.append(took)
        now = time.time()
        if code != 200:
            job.error = f"poll {code} {v}"
            time.sleep(poll)
            continue
        s = v.get("status")
        job.time_in[last_s] = job.time_in.get(last_s, 0) + (now - last_t)
        if last_s == "running" and last_stage:
            job.stage_time[last_stage] = job.stage_time.get(last_stage, 0) + (now - last_t)
        last_t, last_s, last_stage = now, s, v.get("stage")
        job.run_id = v.get("run_id") or job.run_id
        if s != "queued" and job.first_run is None:
            job.first_run = now
        if s in ("done", "error"):
            job.status = s
            job.error = v.get("error")
            break
        key = (s, v.get("stage"), len(acted))
        if s.startswith("awaiting_") and (s, v.get("stage")) not in acted:
            acted.add((s, v.get("stage")))
            t0 = time.time()
            if s == "awaiting_pick":
                ds = v.get("directions") or []
                n = ds[0].get("n", 1) if ds else 1
                c, r, _ = http(base, "POST", f"/nh/jobs/{job.id}/pick", {"n": n})
            elif s == "awaiting_sheet":
                c, r, _ = http(base, "POST", f"/nh/jobs/{job.id}/sheet-decision", {"decision": "approve"})
            elif s == "awaiting_scenes":
                c, r, _ = http(base, "POST", f"/nh/jobs/{job.id}/scenes-continue")
            elif s == "awaiting_cast":
                n = 0 if v.get("castKind") == "confirm" else 1
                c, r, _ = http(base, "POST", f"/nh/jobs/{job.id}/cast", {"n": n})
            else:
                c, r = 0, {"unknown": s}
            job.confirms.append((s, round(time.time() - t0, 3), c))
            if c != 200:
                job.error = f"confirm {s} -> {c} {r}"
        time.sleep(poll)
    job.ended = time.time()
    if job.status == "done" and job.run_id:
        # 다 된 화면이 실제로 그림을 받는가 — 1장 원본 폭 주소(302 → 창고)를 따라간다
        ok = 0
        for no in (1, 2):
            try:
                with urllib.request.urlopen(f"{base}{API}/runs/{job.run_id}/page/{no}?w=320",
                                            timeout=20) as r:
                    if r.status == 200 and len(r.read()) > 1000:
                        ok += 1
            except Exception as e:                         # noqa: BLE001
                job.error = (job.error or "") + f" page{no}:{type(e).__name__}"
        job.pages_ok = ok


def sample(pid: int, stop: threading.Event, out: list, every: float):
    while not stop.is_set():
        try:
            rss_cpu = subprocess.run(["ps", "-o", "rss=,%cpu=", "-p", str(pid)],
                                     capture_output=True, text=True).stdout.split()
            kids = subprocess.run(["ps", "-A", "-o", "ppid=,pid=,rss=,%cpu=,command="],
                                  capture_output=True, text=True).stdout.splitlines()
            fake = real = 0
            kcpu = krss = 0.0
            for line in kids:
                parts = line.split(None, 4)
                if len(parts) < 5 or parts[0] != str(pid):
                    continue
                cmd = parts[4]
                kcpu += float(parts[3])
                krss += float(parts[2])
                if "fakepy" in cmd:
                    fake += 1
                else:
                    real += 1
            # 진짜 파이썬(stitch·s3_upload)은 fakepy 가 exec 로 바뀐 것이라 명령줄이 다르다
            allcpu = sum(float(x) for x in subprocess.run(["ps", "-A", "-o", "%cpu="],
                         capture_output=True, text=True).stdout.split())
            out.append({"t": time.time(), "machine_cpu": allcpu, "rss_mb": int(rss_cpu[0]) / 1024, "cpu": float(rss_cpu[1]),
                        "kids_fake": fake, "kids_real": real, "kids_cpu": kcpu,
                        "kids_rss_mb": krss / 1024})
        except Exception:                                   # noqa: BLE001
            pass
        stop.wait(every)


def cputime(pid: int) -> float:
    raw = subprocess.run(["ps", "-o", "time=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    parts = [float(x) for x in raw.replace("-", ":").split(":")]
    sec = 0.0
    for x in parts:
        sec = sec * 60 + x
    return sec


def pct(xs, q):
    if not xs:
        return None
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(q * (len(xs) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, required=True)
    ap.add_argument("--base", default="http://localhost:8097")
    ap.add_argument("--pid", type=int, required=True, help="서버 JVM pid")
    ap.add_argument("--poll", type=float, default=0.8)
    ap.add_argument("--give-up", type=float, default=3600)
    ap.add_argument("--sample", type=float, default=2.0)
    ap.add_argument("--out", required=True)
    ap.add_argument("--fake-log", help="fakepy 의 FAKE_LOG — stitch·s3_upload 실측 CPU 를 모은다")
    ap.add_argument("--label", default="")
    a = ap.parse_args()

    jobs = [Job(i) for i in range(a.n)]
    gate = threading.Event()
    threads = [threading.Thread(target=run_one, args=(j, a.base, a.poll, a.give_up, gate), daemon=True)
               for j in jobs]
    samples: list = []
    stop = threading.Event()
    sampler = threading.Thread(target=sample, args=(a.pid, stop, samples, a.sample), daemon=True)
    sampler.start()
    for t in threads:
        t.start()
    cpu0 = cputime(a.pid)
    t0 = time.time()
    gate.set()
    for t in threads:
        t.join()
    wall = time.time() - t0
    stop.set()
    sampler.join()
    java_cpu = cputime(a.pid) - cpu0
    py = {"stitch.py": [], "s3_upload.py": []}
    if a.fake_log:
        mine = {j.run_id for j in jobs if j.run_id}
        for line in open(a.fake_log, encoding="utf-8"):
            r = json.loads(line)
            if r.get("run_id") in mine and r.get("step") in py:
                py[r["step"]].append(r)

    done = [j for j in jobs if j.status == "done"]
    failed = [j for j in jobs if j.status != "done"]
    qwait_first = [j.first_run - j.created for j in jobs if j.first_run]
    qwait_total = [j.time_in.get("queued", 0) for j in jobs]
    totals = [j.ended - j.created for j in done]
    polls = [p for j in jobs for p in j.polls]
    stage_sum: dict = {}
    for j in done:
        for k, v in j.stage_time.items():
            stage_sum.setdefault(k, []).append(v)
    summary = {
        "n": a.n, "completed": len(done), "failed": len(failed), "wall_s": round(wall, 1),
        "queue_first_med": pct(qwait_first, .5), "queue_first_max": max(qwait_first, default=None),
        "queue_total_med": pct(qwait_total, .5), "queue_total_max": max(qwait_total, default=None),
        "total_med": pct(totals, .5), "total_max": max(totals, default=None),
        "jobs_per_min": round(len(done) / (wall / 60), 2) if wall else None,
        "running_med": pct([j.time_in.get("running", 0) for j in done], .5),
        "running_stage_med": {k: round(pct(v, .5), 1) for k, v in stage_sum.items()},
        "poll_p50_ms": round(pct(polls, .5) * 1000, 1) if polls else None,
        "poll_p95_ms": round(pct(polls, .95) * 1000, 1) if polls else None,
        "poll_max_ms": round(max(polls) * 1000, 1) if polls else None,
        "label": a.label,
        "java_cpu_s": round(java_cpu, 1),
        "java_cpu_avg_cores": round(java_cpu / wall, 2) if wall else None,
        "peak_machine_cpu": round(max((s.get("machine_cpu", 0) for s in samples), default=0)),
        "peak_java_plus_kids_cpu": round(max((s["cpu"] + s["kids_cpu"] for s in samples), default=0)),
        "stitch_cpu_s_med": pct([r["cpu"] for r in py["stitch.py"]], .5),
        "stitch_wall_s_med": pct([r["wall"] for r in py["stitch.py"]], .5),
        "stitch_maxrss_mb": max((r["maxrss_mb"] for r in py["stitch.py"]), default=None),
        "prepare_cpu_s_med": pct([r["cpu"] for r in py["s3_upload.py"]], .5),
        "prepare_wall_s_med": pct([r["wall"] for r in py["s3_upload.py"]], .5),
        "prepare_maxrss_mb": max((r["maxrss_mb"] for r in py["s3_upload.py"]), default=None),
        "peak_rss_mb": round(max((s["rss_mb"] for s in samples), default=0), 1),
        "peak_cpu": max((s["cpu"] for s in samples), default=0),
        "peak_kids": max((s["kids_fake"] + s["kids_real"] for s in samples), default=0),
        "peak_kids_real": max((s["kids_real"] for s in samples), default=0),
        "peak_kids_cpu": max((s["kids_cpu"] for s in samples), default=0),
        "peak_kids_rss_mb": round(max((s["kids_rss_mb"] for s in samples), default=0), 1),
        "pages_viewable": sum(1 for j in done if j.pages_ok == 2),
        "errors": [{"i": j.i, "id": j.id, "status": j.status, "error": j.error} for j in failed]
                  + [{"i": j.i, "id": j.id, "note": j.error} for j in done if j.error],
    }
    for k, v in list(summary.items()):
        if isinstance(v, float):
            summary[k] = round(v, 1)
    detail = [{"i": j.i, "id": j.id, "run_id": j.run_id, "status": j.status,
               "created": j.created, "first_run": j.first_run, "ended": j.ended,
               "time_in": {k: round(v, 2) for k, v in j.time_in.items()},
               "stage_time": {k: round(v, 2) for k, v in j.stage_time.items()},
               "confirms": j.confirms, "pages_ok": j.pages_ok} for j in jobs]
    with open(a.out, "w", encoding="utf-8") as fh:
        json.dump({"summary": summary, "jobs": detail, "samples": samples}, fh,
                  ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
