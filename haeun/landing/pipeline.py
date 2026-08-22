"""랜딩페이지 뒤에서 도는 오케스트레이터.

사용자가 보는 것은 **캐릭터 시트 한 장과 입력창 하나**뿐이다. 그 뒤에서는
story-harness 와 webtoon-harness 의 최종 파이프라인이 순서대로 돈다:

    1. story.py --character         이야기 설계 (LOOK·SEED·P1·P2·P3·SCENE)
    2. story.py --charsheet         캐릭터 시트 1장 (후보 1장 → 자동 채택)
    3. webtoon.py --run             회차 설계 · 콘티 (4~8단계)
    4. run.py --mode scene -c S+    한 장에 3컷씩 그림 (말풍선·대사 포함)
    5. (같은 실행)                  장을 세로로 이어 붙여 episode.png

## 하네스는 바깥에서 조종한다

두 하네스는 완성본이라 원칙적으로 건드리지 않는다. **바깥에서 주는 것**으로만
제품 동작을 만든다:

    --config <job>/config.yaml      원본 config.yaml 을 복사해 이 실행에만 쓸
                                    값을 덮어쓴다 (그림체 · 말풍선 · 인물 고정값)
    WEBTOON_HARNESS_DIR=<job>       story.py 의 --charsheet 가 그림체 문구를
                                    읽어 가는 곳. 이걸 job 폴더로 돌려야 시트와
                                    컷이 **같은 그림체**를 본다
    --skip-human-gate               블라인드 평가는 연구용 관문이라 제품에서는
                                    건너뛴다 (아래 참고)

예외가 하나 있다 — `scene.grouping` 은 바깥에서 줄 수 있는 값이 아예 없어서
webtoon-harness 에 스위치를 더했다 (기본값 `rhythm` = 예전 그대로). 한 장에
정확히 N컷을 넣으려면 연출의 리듬 경계를 꺼야 하는데, 상한만으로는 안 된다:
상한은 큰 묶음을 **고르게** 쪼개므로 4컷 묶음에 상한 3을 걸면 2+2 가 된다.

## 블라인드 평가를 건너뛰는 것에 대해

webtoon.py 는 원래 사람이 "다음 화가 궁금한가"에 답해야 컷으로 넘어간다.
재미 판정은 하네스에서 사람만 하는 일이기 때문이다. 랜딩페이지는 사람을 세울
자리가 없으므로 `--skip-human-gate` 로 지나간다. **재미가 검증됐다는 뜻이
아니다** — 그 관문이 없는 상태로 뽑은 결과라는 뜻이다.

## 진행 상황은 자식 프로세스의 stdout 에서 읽는다

하네스는 진행률 API 를 제공하지 않는다. 대신 사람이 보라고 찍는 줄들이 있고
(`P3 [통과] …`, `4단계 통과: Arc 3개`, `[3/4] scene_S+ / Scene3 / c1`), 그 줄들이 곧
단계 표시가 된다. 못 읽는 줄은 그냥 로그로 흘린다 — 표시가 한 칸 늦을 수는
있어도 틀린 단계를 보여주지는 않는다.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

HERE = Path(__file__).resolve().parent
JOBS_DIR = HERE / "jobs"
STORY = HERE.parent / "story-harness"
WEBTOON = HERE.parent / "webtoon-harness"

# 그림 조건. 합격본이 S+ 다 — 통합 시트 + 직전 컷을 붙여서, 조연이 같은
# 사람으로 이어지고 채색이 컷마다 갈리지 않는다. config.yaml 자체가
# "한 화를 통째로 뽑을 때는 S 가 아니라 S+" 라고 못박고 있다.
CONDITION = "S+"

# 한 장에 3컷. Gemini 는 호출당 이미지 1장이라 "3컷씩 생성"은 곧 "한 장에
# 3칸"이다 — 12컷짜리 한 화가 이미지 4장이 되고 호출도 비용도 1/3 이 된다.
#
# 대가는 픽셀이다. 캔버스 세로 2400px(9:16 · 2K)을 셋으로 나누므로 컷 하나가
# 800px 이 된다. 하네스 README 가 "얼굴이 작다는 느낌은 800px 아래에서 나오기
# 시작한다"고 적어 둔 그 경계이고, 4 로 올리면 격자(만화책 페이지)가 나오기
# 시작하는 지점이라 3 이 이음매와 격자 사이의 타협점이다.
#
# 컷 사이 여백도 모델이 정하게 된다 — 컷 모드에서는 콘티의 gap_after 를 보고
# 코드가 정했다. 되돌리려면 MODE 를 "cut" 으로 바꾸면 그 경로가 그대로 산다.
MODE = "scene"
CUTS_PER_SHEET = 3
# 비용 안내용 환율. webtoon-harness config.yaml 의 pricing.usd_to_krw 와 같은 값.
USD_TO_KRW = 1400

STYLES = {
    "lineart":   "선화 · 액션",
    "webtoon":   "일반 웹툰",
    "romance":   "로맨스 판타지",
    "cinematic": "시네마틱 반실사",
}

# 사용자에게 보이는 단계. 하네스의 내부 단계 이름(P1/W5/…)은 올리지 않는다 —
# 무엇을 하고 있는지가 보여야지, 어느 프롬프트가 도는지가 보일 필요는 없다.
STAGE_SPEC: list[dict[str, Any]] = [
    {
        "key": "story", "title": "이야기 설계",
        "desc": "캐릭터에서 이야기를 만듭니다",
        "steps": [
            ("look",    "사진에서 외형 읽기"),
            ("seed",    "장르·세계관 정하기"),
            ("card",    "캐릭터 카드 쓰기"),
            ("premise", "이야기 뼈대 세우기"),
            ("judge",   "구조 검수"),
            ("scene",   "첫 장면 쓰기"),
        ],
    },
    {
        "key": "sheet", "title": "캐릭터 시트",
        "desc": "컷마다 같은 얼굴이 나오도록 기준 그림을 만듭니다",
        "steps": [
            ("spec", "외형 사양 정리"),
            ("draw", "시트 그리기"),
            ("pick", "기준 시트 확정"),
        ],
    },
    {
        "key": "board", "title": "회차 설계 · 콘티",
        "desc": "1화를 컷으로 나누고 대사를 붙입니다",
        "steps": [
            ("arc",     "큰 줄거리"),
            ("episode", "1화 설계"),
            ("check",   "연출 검사"),
            ("cuts",    "컷 나누기"),
        ],
    },
    {
        "key": "art", "title": "그림 그리기",
        "desc": f"한 장에 {CUTS_PER_SHEET}컷씩 그립니다 — 말풍선과 대사가 함께 들어갑니다",
        "steps": [
            ("prompt", "장면 서술 옮기기"),
            ("group",  f"{CUTS_PER_SHEET}컷씩 묶기"),
            ("draw",   "장 그리기"),
        ],
    },
    {
        "key": "bind", "title": "한 편으로 잇기",
        "desc": "그린 장을 순서대로 세로로 이어 붙입니다",
        "steps": [("strip", "이어 붙이기")],
    },
]

TODO, ACTIVE, DONE, ERROR, SKIP = "todo", "active", "done", "error", "skip"


# --------------------------------------------------------------------------- #
# config 덮어쓰기
#
# 원본을 고치지 않고 복사본만 바꾼다. run.py 는 config 안의 상대경로를 자기
# ROOT 기준으로 푸므로(run.rel_path), 복사본이 다른 폴더에 있어도 그대로 돈다.
# --------------------------------------------------------------------------- #

def _replace_block(text: str, key: str, value: str) -> str:
    """최상위 키 하나를 통째로 바꾼다. 블록 스칼라(`>-` + 들여쓴 줄)도 지운다."""
    pattern = re.compile(rf"(?m)^{re.escape(key)}:.*(?:\n[ \t]+\S.*)*")
    if not pattern.search(text):
        raise RuntimeError(f"config.yaml 에서 '{key}' 를 찾지 못했습니다.")
    return pattern.sub(f"{key}: {value}", text, count=1)


def build_config(job_dir: Path, style: str) -> Path:
    """이 실행에만 쓸 config.yaml. 원본에서 딱 다섯 값만 바꾼다."""
    text = (WEBTOON / "config.yaml").read_text(encoding="utf-8")

    # 1. 그림체 — 시트(story.py)와 컷(run.py)이 같은 값을 봐야 한다.
    text = _replace_block(text, "style_default", style)

    # 2. 말풍선과 대사를 그림 안에 그린다. 하네스 기본값은 sfx_only(효과음만)
    #    인데, 그건 액션 컷에서 글자가 동작을 가리는 것을 막으려는 선택이다.
    #    제품에서는 대사가 보여야 하므로 in_image 로 되돌린다.
    text = re.sub(r"(?m)^  lettering:.*$", "  lettering: in_image", text, count=1)

    # 3~5. 인물 고정값은 실험용 run 의 주인공(청명)에 맞춰져 있다. 그대로 두면
    #      **누가 들어와도 그 사람의 도복을 입는다.** 전부 비운다 —
    #      기준은 이 실행의 p1.json 이고, 없으면 run.py 가 스스로 멈춘다.
    text = _replace_block(text, "character_appearance", '""')
    text = _replace_block(text, "character_gender", '""')
    text = _replace_block(text, "outfit_lock", '""')

    # 6. 한 장에 몇 컷을 묶을지 — **세 값을 다 바꿔야** 3컷씩이 된다.
    #
    #    grouping 을 fixed 로 두지 않으면 연출(W7.5)의 scene_break 가 경계를
    #    정하고, 상한(max_cuts_per_scene)은 큰 묶음을 **고르게** 쪼갤 뿐이다.
    #    실제로 4+4+2+2 짜리 한 화에 상한 3을 걸었더니 2,2,2,2,2,2 가 나왔다 —
    #    3은 한 번도 안 나온다. 3컷씩이 목적이면 리듬을 꺼야 한다.
    #
    #    대가: 경계가 이야기의 리듬과 무관하게 떨어진다. 설명하다 만 자리에서
    #    장이 넘어갈 수 있다. rhythm 으로 되돌리려면 이 한 줄만 지우면 된다.
    text = re.sub(r"(?m)^  grouping:.*$", "  grouping: fixed", text, count=1)
    text = re.sub(r"(?m)^  cuts_per_scene:.*$",
                  f"  cuts_per_scene: {CUTS_PER_SHEET}", text, count=1)
    text = re.sub(r"(?m)^  max_cuts_per_scene:.*$",
                  f"  max_cuts_per_scene: {CUTS_PER_SHEET}", text, count=1)

    out = job_dir / "config.yaml"
    out.write_text(text, encoding="utf-8")
    return out


# --------------------------------------------------------------------------- #
# 입력 → 캐릭터 JSON
# --------------------------------------------------------------------------- #

FIELD_KEYS = ("나이", "성별", "직업", "성격", "말투", "과거", "관계", "약점")


def write_character(job_dir: Path, form: dict[str, Any]) -> Path:
    """폼 입력을 story.py 가 읽는 캐릭터 파일로.

    빈 칸은 **빈 칸으로 둔다.** 코드가 기본값을 채우면 작가가 준 것과 코드가
    지어낸 것이 섞이고, 그건 하네스가 하지 않기로 한 일이다(read_character).
    """
    fields = {k: str(form.get("fields", {}).get(k, "")).strip() for k in FIELD_KEYS}
    doc: dict[str, Any] = {
        "name": str(form.get("name", "")).strip(),
        "character": str(form.get("character", "")).strip(),
        "fields": {k: v for k, v in fields.items() if v},
        "genre": str(form.get("genre", "")).strip(),
        "world": {"preset": "", "text": str(form.get("world", "")).strip()},
        "story": str(form.get("story", "")).strip(),
    }
    photo = job_dir / "photo.png"
    if photo.exists():
        doc["photo"] = str(photo)
        note = str(form.get("photo_note", "")).strip()
        if note:
            doc["photo_note"] = note

    path = job_dir / "character.json"
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


# --------------------------------------------------------------------------- #
# Job
# --------------------------------------------------------------------------- #

@dataclass
class Job:
    id: str
    form: dict[str, Any]
    dir: Path
    style: str
    preview: bool
    has_photo: bool

    # queued | running | awaiting_story_approval | awaiting_sheet_approval |
    # done | error | cancelled
    status: str = "queued"
    run_id: str | None = None
    error: str | None = None
    created_at: float = field(default_factory=time.time)
    started_at: float | None = None
    finished_at: float | None = None

    stages: list[dict[str, Any]] = field(default_factory=list)
    stage_i: int = 0
    log: list[str] = field(default_factory=list)

    art_total: int = 0
    art_done: int = 0
    art_seconds: list[float] = field(default_factory=list)
    ready_cuts: list[int] = field(default_factory=list)

    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _proc: subprocess.Popen | None = field(default=None, repr=False)
    _cancel: bool = field(default=False, repr=False)

    # awaiting_sheet_approval 동안 execute() 를 세워 두는 신호. state.json 에는
    # 안 남는다 — 서버가 재시작되면 execute() 를 돌리던 스레드 자체가 죽어서
    # 이 Event 를 아무도 못 깨우고, "running" 이 재시작 후 error 로 바뀌는 것과
    # 같은 이유로 load() 에서 error 로 바꾼다.
    sheet_approval: threading.Event = field(default_factory=threading.Event, repr=False)
    sheet_decision: str = field(default="", repr=False)

    def decide_sheet(self, decision: str) -> None:
        """승인 화면의 '이대로 진행'/'다시 만들기' 클릭이 여기로 온다."""
        with self._lock:
            self.sheet_decision = decision
        self.sheet_approval.set()

    # awaiting_story_approval 용 — sheet_approval 과 같은 이유, 같은 방식.
    # 스토리 단계가 STATUS_HUMAN(게이트 재시도 소진)으로 끝났을 때만 켜진다.
    story_approval: threading.Event = field(default_factory=threading.Event, repr=False)
    story_decision: str = field(default="", repr=False)

    def decide_story(self, decision: str) -> None:
        """스토리 확인 화면의 '이대로 진행'/'다시 만들기' 클릭이 여기로 온다."""
        with self._lock:
            self.story_decision = decision
        self.story_approval.set()

    # ---- 상태 -------------------------------------------------------------- #

    def build_stages(self) -> None:
        self.stages = []
        for spec in STAGE_SPEC:
            steps = []
            for key, label in spec["steps"]:
                state = SKIP if (key == "look" and not self.has_photo) else TODO
                steps.append({"key": key, "label": label, "state": state})
            self.stages.append({
                "key": spec["key"], "title": spec["title"], "desc": spec["desc"],
                "state": TODO, "note": "", "steps": steps,
                "started_at": None, "seconds": None,
            })

    @property
    def stage(self) -> dict[str, Any]:
        return self.stages[self.stage_i]

    def _step(self, key: str) -> dict[str, Any] | None:
        for s in self.stage["steps"]:
            if s["key"] == key:
                return s
        return None

    def mark(self, key: str, state: str) -> None:
        """이 단계의 하위 항목 하나를 표시하고, 그 앞의 것들은 끝난 것으로 본다.

        앞 항목을 같이 닫는 이유: 하네스가 모든 전환을 찍지는 않는다. P2 가
        시작됐다는 줄은 없고 P3 결과 줄만 있다. 뒤엣것이 보였다면 앞엣것은
        끝난 것이 맞다.
        """
        steps = self.stage["steps"]
        idx = next((i for i, s in enumerate(steps) if s["key"] == key), None)
        if idx is None:
            return
        with self._lock:
            for s in steps[:idx]:
                if s["state"] in (TODO, ACTIVE):
                    s["state"] = DONE
            steps[idx]["state"] = state

    def note(self, text: str) -> None:
        with self._lock:
            self.stage["note"] = text

    def add_log(self, line: str) -> None:
        with self._lock:
            self.log.append(line)
            if len(self.log) > 400:
                del self.log[:100]

    def stage_seconds(self) -> float:
        """단계에 실제로 쓴 시간의 합. 총 경과의 예비 경로다."""
        return sum(float(st.get("seconds") or 0) for st in self.stages)

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            now = time.time()
            if self.started_at:
                elapsed = (self.finished_at or now) - self.started_at
            else:
                # 지난 실행에서 복원했는데 시작 시각이 없는 경우. 0:00 으로
                # 보여주면 "안 걸렸다"로 읽히므로 단계 시간의 합으로 대신한다.
                elapsed = self.stage_seconds()
            art = None
            if self.art_total:
                avg = (sum(self.art_seconds) / len(self.art_seconds)
                       if self.art_seconds else None)
                left = self.art_total - self.art_done
                art = {
                    "done": self.art_done, "total": self.art_total,
                    "eta_sec": round(avg * left) if avg and left > 0 else None,
                }
            return {
                "id": self.id,
                "status": self.status,
                "run_id": self.run_id,
                "error": self.error,
                "elapsed": round(elapsed, 1),
                "stage_index": self.stage_i,
                "stages": json.loads(json.dumps(self.stages, ensure_ascii=False)),
                "art": art,
                "ready_cuts": list(self.ready_cuts),
                "log": self.log[-60:],
                "style": self.style,
                "style_label": STYLES.get(self.style, self.style),
                "preview": self.preview,
                "has_photo": self.has_photo,
            }

    # ---- 저장 · 복원 -------------------------------------------------------- #
    #
    # 서버를 껐다 켜면 만들어 둔 웹툰을 못 보게 되면 안 된다. 끝난 작업은
    # state.json 으로 남기고, 다음 실행이 그것을 읽어 결과 화면을 다시 연다.
    # (돌던 중이었다면 되살리지 않는다 — 하위 프로세스는 서버와 함께 죽었다.)

    def save(self) -> None:
        try:
            (self.dir / "state.json").write_text(json.dumps({
                "id": self.id, "status": self.status, "run_id": self.run_id,
                "error": self.error, "style": self.style, "preview": self.preview,
                "has_photo": self.has_photo, "form": self.form,
                "stages": self.stages, "stage_i": self.stage_i,
                "started_at": self.started_at, "finished_at": self.finished_at,
                "ready_cuts": self.ready_cuts,
            }, ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError:
            pass                      # 기록을 못 남겨도 이번 실행은 살아 있다

    @classmethod
    def load(cls, path: Path) -> "Job | None":
        try:
            d = json.loads((path / "state.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if d.get("status") in ("running", "awaiting_sheet_approval", "awaiting_story_approval"):
            d["status"] = "error"
            d["error"] = "서버가 다시 시작되어 이 작업은 끊겼습니다."
        job = cls(id=d["id"], form=d.get("form") or {}, dir=path,
                  style=d.get("style") or "webtoon", preview=bool(d.get("preview")),
                  has_photo=bool(d.get("has_photo")))
        job.status = d.get("status") or "error"
        job.run_id = d.get("run_id")
        job.error = d.get("error")
        job.stages = d.get("stages") or []
        job.stage_i = int(d.get("stage_i") or 0)
        job.started_at = d.get("started_at")
        job.finished_at = d.get("finished_at")
        job.ready_cuts = list(d.get("ready_cuts") or [])
        if not job.stages:
            job.build_stages()
        return job

    # ---- 실행 -------------------------------------------------------------- #

    def cancel(self) -> None:
        self._cancel = True
        proc = self._proc
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except OSError:
                pass
        # awaiting_*_approval 이면 프로세스가 없다 — execute() 가 해당
        # *_approval.wait() 에 걸려 있으므로 깨워야 _cancel 을 보고 멈춘다.
        self.sheet_approval.set()
        self.story_approval.set()

    def _env(self) -> dict[str, str]:
        env = dict(os.environ)
        # 한글이 깨지지 않게. 콘솔(cp949)로 나가는 것이 아니라 파이프로 받는다.
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        # story.py --charsheet 가 그림체 문구를 여기서 읽는다. job 폴더를
        # 가리켜야 시트와 컷이 같은 그림체를 본다.
        env["WEBTOON_HARNESS_DIR"] = str(self.dir)
        return env

    def _run(self, cmd: list[str], cwd: Path, on_line: Callable[[str], None]) -> int:
        display = " ".join(["python", *cmd])
        self.add_log(f"$ {display}")
        with (self.dir / "log.txt").open("a", encoding="utf-8") as fh:
            fh.write(f"\n$ {display}\n")
        proc = subprocess.Popen(
            [sys.executable, "-u", *cmd], cwd=str(cwd), env=self._env(),
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
        self._proc = proc
        with (self.dir / "log.txt").open("a", encoding="utf-8") as fh:
            for raw in proc.stdout:                     # type: ignore[union-attr]
                line = raw.rstrip("\n")
                fh.write(line + "\n")
                if line.strip():
                    self.add_log(line)
                    on_line(line)
        self._proc = None
        return proc.wait()


# --------------------------------------------------------------------------- #
# stdout 읽기 — 단계마다 무엇을 신호로 보는가
# --------------------------------------------------------------------------- #

RE_SHEET_MADE = re.compile(r"^\s+(\w+) 후보 (\d+) ·")
RE_JOB = re.compile(r"^\[(\d+)/(\d+)\]")
# Scene 모드는 scene3_c1.png, 컷 모드는 cut3_c1.png 로 떨어진다. MODE 를
# 되돌려도 진행 표시가 같이 살아 있어야 하므로 둘 다 받는다.
RE_OK_UNIT = re.compile(r"OK \(([\d.]+)s\).*?(?:cut|scene)(\d+)_c\d+\.png")
# "[scene_gen] 컷 12개 → Scene 4개 (묶음 3, 3, 3, 3 · 3개씩 고정)"
RE_GROUPED = re.compile(r"컷 (\d+)개 → Scene (\d+)개 \(묶음 ([\d,\s+]+?)\s*·")


def _story_line(job: Job, line: str) -> None:
    if "LOOK: 사진" in line:
        job.mark("look", ACTIVE)
        job.note("올려주신 사진에서 머리·눈·체형·옷을 읽는 중")
    elif line.lstrip().startswith("SEED:") or line.lstrip().startswith("템플릿:"):
        job.mark("seed", DONE)
        job.mark("card", ACTIVE)
        job.note("캐릭터 카드를 쓰는 중 — 1초 안에 손가락을 멈추게 하는 한 장")
    elif "카드 게이트 실패" in line:
        job.note("캐릭터 카드가 기준에 걸렸습니다 — 다시 쓰는 중")
    elif line.lstrip().startswith("P3 ["):
        job.mark("judge", DONE)
        job.mark("scene", ACTIVE)
        job.note("첫 장면을 쓰는 중")
    elif "-> P1 재실행" in line or "-> P2 재실행" in line:
        job.mark("premise", ACTIVE)
        job.note("구조 검수에서 되돌아왔습니다 — 다시 쓰는 중")
    elif "장면 점검 통과" in line:
        job.mark("scene", DONE)
        job.note(line.strip())
    elif "장면 점검 걸림" in line:
        job.note("장면 점검에 걸렸습니다 — 고쳐 쓰는 중")


def _sheet_line(job: Job, line: str) -> None:
    if "사양을 만들었습니다" in line or "사양이 이미 있습니다" in line:
        job.mark("spec", DONE)
        job.mark("draw", ACTIVE)
        job.note("4면도 · 표정 · 디테일 · 색을 한 장에 그리는 중 (1~2분)")
    elif "캐릭터 시트 ·" in line:
        job.mark("spec", DONE)
        job.mark("draw", ACTIVE)
    elif RE_SHEET_MADE.match(line):
        job.mark("draw", DONE)
    elif "자동 채택" in line:
        job.mark("pick", DONE)
        job.note("기준 시트 확정 — 이제 모든 컷이 이 얼굴을 따라갑니다")


def _board_line(job: Job, line: str) -> None:
    if "4단계 통과" in line:
        job.mark("arc", DONE)
        job.mark("episode", ACTIVE)
        job.note("1화에 무엇을 담을지 설계하는 중")
    elif "화 검사 통과" in line:
        job.mark("check", DONE)
        job.mark("cuts", ACTIVE)
        job.note("장면을 컷으로 나누고 대사를 붙이는 중")
    elif "형식 게이트 실패" in line or "검사 불합격" in line:
        job.mark("check", ACTIVE)
        job.note("1화 설계가 검사에 걸렸습니다 — 다시 쓰는 중")
    elif "화 통과 ·" in line:
        job.mark("cuts", DONE)
        job.note(line.strip().split("·", 1)[-1].strip())


def _art_line(job: Job, line: str) -> None:
    if line.startswith("[scene_gen]") or line.startswith("[prompt_gen]"):
        done = "완료" in line or "캐시" in line
        job.mark("prompt", DONE if done else ACTIVE)
        if not done:
            job.note("컷 서술을 그림이 알아듣는 말로 옮기는 중")
        m = RE_GROUPED.search(line)
        if m:
            # "묶음 3+3+3+3" — 마지막 묶음만 작아질 수 있으므로 그대로 보여준다.
            job.mark("group", DONE)
            job.note(f"컷 {m.group(1)}개를 {m.group(2)}장으로 묶었습니다 "
                     f"(묶음 {m.group(3)})")
        elif done:
            job.mark("group", ACTIVE)
        return
    m = RE_JOB.match(line)
    if m:
        i, n = int(m.group(1)), int(m.group(2))
        job.mark("group", DONE)
        job.mark("draw", ACTIVE)
        with job._lock:
            job.art_total = n
        job.note(f"{n}장 중 {i}번째 장을 그리는 중 (한 장에 {CUTS_PER_SHEET}컷)")
        return
    m = RE_OK_UNIT.search(line)
    if m:
        secs, unit = float(m.group(1)), int(m.group(2))
        with job._lock:
            job.art_seconds.append(secs)
            job.art_done += 1
            if unit not in job.ready_cuts:
                job.ready_cuts.append(unit)
                job.ready_cuts.sort()
    elif line.lstrip().startswith("실패 (시도"):
        job.note("한 장이 실패했습니다 — 다시 시도하는 중")


def _bind_line(job: Job, line: str) -> None:
    if line.startswith("episode.png"):
        job.mark("strip", DONE)
        job.note(line.strip())


# --------------------------------------------------------------------------- #
# 파이프라인
# --------------------------------------------------------------------------- #

# story.py/webtoon.py 의 STATUS_HUMAN 과 같은 문자열. 두 CLI 모두 게이트가
# 소진돼 사람 확인이 필요한 상태에서도 프로세스 종료 코드는 0 이라(각자의
# main() 이 항상 return 0) exit code 만으로는 못 잡고, 각 단계가 남긴
# meta.json 의 status 를 직접 읽어야 한다 — make_episode.py 의 stage_status()
# 와 같은 이유, 같은 방식.
STATUS_HUMAN = "사람확인필요"


def _meta_status(meta_path: Path) -> tuple[str | None, str]:
    """단계가 남긴 meta.json 의 (status, note). 없거나 못 읽으면 (None, "")."""
    if not meta_path.exists():
        return None, ""
    try:
        data = json.loads(meta_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None, ""
    return data.get("status"), data.get("note") or ""


def _latest_run(before: set[str]) -> str | None:
    runs = STORY / "runs"
    fresh = [d for d in runs.iterdir()
             if d.is_dir() and d.name not in before and (d / "p1.json").exists()]
    if not fresh:
        return None
    return max(fresh, key=lambda d: d.stat().st_mtime).name


def _enter(job: Job, index: int) -> None:
    with job._lock:
        job.stage_i = index
        job.stage["state"] = ACTIVE
        job.stage["started_at"] = time.time()


def _leave(job: Job, ok: bool = True) -> None:
    with job._lock:
        st = job.stage
        st["state"] = DONE if ok else ERROR
        if st["started_at"]:
            st["seconds"] = round(time.time() - st["started_at"], 1)
        if ok:
            for s in st["steps"]:
                if s["state"] in (TODO, ACTIVE):
                    s["state"] = DONE


class Failed(RuntimeError):
    pass


def execute(job: Job) -> None:
    job.status = "running"
    job.started_at = time.time()
    job.build_stages()
    job_dir = job.dir

    try:
        build_config(job_dir, job.style)
        char_path = write_character(job_dir, job.form)

        # ---- 1. 이야기 --------------------------------------------------- #
        _enter(job, 0)
        while True:
            job.note("캐릭터를 읽는 중")
            before = {d.name for d in (STORY / "runs").iterdir() if d.is_dir()}
            code = job._run(
                ["story.py", "--character", str(char_path), "--scenes", "3", "--no-read"],
                STORY, lambda ln: _story_line(job, ln))
            if job._cancel:
                raise Failed("취소됨")
            if code != 0:
                raise Failed("이야기를 만들지 못했습니다.")
            run_id = _latest_run(before)
            if not run_id:
                raise Failed("이야기는 돌았지만 결과 폴더를 찾지 못했습니다.")
            job.run_id = run_id
            (job_dir / "run_id.txt").write_text(run_id, encoding="utf-8")
            if not (STORY / "runs" / run_id / "scenes.json").exists():
                raise Failed("장면까지 나오지 못했습니다. 캐릭터 설명을 조금 더 "
                             "구체적으로 적고 다시 시도해 주세요.")

            # story.py 의 main() 은 게이트가 소진돼 사람 확인이 필요한
            # 상태(STATUS_HUMAN)에서도 종료 코드는 항상 0 을 낸다 — 그래서
            # exit code 만으로는 못 잡고 meta.json 을 직접 읽는다.
            status, note = _meta_status(STORY / "runs" / run_id / "meta.json")
            if status != STATUS_HUMAN:
                break                  # STATUS_OK(또는 알 수 없는 값) — 정상 진행

            job.status = "awaiting_story_approval"
            job.note("구조 검수에서 게이트 재시도가 소진됐습니다 — 확인해 주세요"
                      + (f" ({note})" if note else ""))
            job.save()
            job.story_approval.wait()
            job.story_approval.clear()
            with job._lock:
                decision = job.story_decision
                job.story_decision = ""
            job.status = "running"
            if job._cancel:
                raise Failed("취소됨")
            if decision != "retry":
                break                   # approve (또는 알 수 없는 값 — 진행 쪽이 안전)
            # 다시 만들기 — 시트처럼 같은 run_id 폴더를 지우고 재시도하는 방식이
            # 아니다. story.py 는 --character 를 받으면 항상 새 run_id 를
            # 만들므로, 같은 캐릭터 입력으로 통째로 한 번 더 돈다.
            job.note("캐릭터를 다시 읽는 중 — 이야기를 새로 만드는 중")
        _leave(job)

        # ---- 2. 캐릭터 시트 ---------------------------------------------- #
        _enter(job, 1)
        job.note("외형 사양을 정리하는 중")
        sheet_dir = STORY / "runs" / run_id / "charsheet"
        picks = sheet_dir / "charsheet_picks.json"
        while True:
            code = job._run(
                # 시트 이미지 기본값은 story.py 안에서 gemini 다 — 텍스트 단계용
                # --provider(.env PROVIDER=openai)는 여기 안 먹는다. 캐릭터 시트는
                # OpenAI(gpt-image-2)로 고정한다고 정했으므로 여기서 명시로 준다.
                ["story.py", "--charsheet", "--run-id", run_id,
                 "--provider", "openai", "--yes"],
                STORY, lambda ln: _sheet_line(job, ln))
            if job._cancel:
                raise Failed("취소됨")
            if code != 0 or not picks.exists():
                raise Failed("캐릭터 시트를 만들지 못했습니다. "
                             "조건 S+ 는 시트 없이는 돌 수 없습니다.")

            # 시트가 나온 뒤 사람이 보는 지점 — P0-1. story.py 의 --yes 와
            # 후보 1장 자동 채택(자체 게이트)은 그대로 두고, 그 다음 단계로
            # 넘어가기 전에 여기서 한 번 세운다. "아예 다른 사람이 됐다" 같은
            # 사고가 이 지점에서 멈춰야 뒤 컷 전부가 오염되지 않는다.
            job.status = "awaiting_sheet_approval"
            job.note("캐릭터 시트가 나왔습니다 — 확인해 주세요")
            job.save()
            job.sheet_approval.wait()
            job.sheet_approval.clear()
            with job._lock:
                decision = job.sheet_decision
                job.sheet_decision = ""
            job.status = "running"
            if job._cancel:
                raise Failed("취소됨")
            if decision != "retry":
                break                   # approve (또는 알 수 없는 값 — 진행 쪽이 안전)
            # 다시 만들기 — story.py 는 이 폴더가 있으면 재생성을 건너뛰므로
            # (story.py 의 "다시 뽑고 싶으면 이 폴더를 사람이 직접 지운다"와
            # 동일한 방식) 지우고 같은 루프를 한 번 더 돈다.
            shutil.rmtree(sheet_dir, ignore_errors=True)
            job.note("시트를 다시 만드는 중")
        _leave(job)

        # ---- 3. 콘티 ------------------------------------------------------ #
        _enter(job, 2)
        job.note("큰 줄거리를 세우는 중")
        code = job._run(
            ["webtoon.py", "--run", run_id, "--episodes", "1", "--skip-human-gate"],
            STORY, lambda ln: _board_line(job, ln))
        if job._cancel:
            raise Failed("취소됨")
        cuts_path = STORY / "runs" / run_id / "webtoon" / "ep01_cuts.json"
        if code != 0 or not cuts_path.exists():
            raise Failed("콘티(컷 설계)를 만들지 못했습니다.")
        _leave(job)

        # ---- 4·5. 그림 + 이어 붙이기 -------------------------------------- #
        # 한 번의 실행이 두 가지를 한다. 컷이 다 나오면 run.py 가 그대로
        # episode.png 까지 만든다.
        _enter(job, 3)
        job.note("컷 서술을 옮기는 중")
        cmd = ["run.py", "--run-id", run_id, "--episode", "1",
               "--mode", MODE, "-c", CONDITION, "--style", job.style,
               "--config", str(job_dir / "config.yaml"), "--yes"]
        if job.preview:
            # Scene 모드에서 --cuts 는 "그 컷이 들어 있는 장"을 고른다.
            # 1~3 이면 첫 장 하나 = 3컷.
            cmd += ["--cuts", f"1-{CUTS_PER_SHEET}"]

        def art_or_bind(line: str) -> None:
            # episode.png 줄이 보이는 순간 마지막 단계로 넘어간다.
            if job.stage_i == 3 and (line.startswith("episode.png")
                                     or line.startswith("완료:")):
                _leave(job)
                _enter(job, 4)
                job.note("그린 장을 순서대로 이어 붙이는 중")
            if job.stage_i == 3:
                _art_line(job, line)
            else:
                _bind_line(job, line)

        code = job._run(cmd, WEBTOON, art_or_bind)
        if job._cancel:
            raise Failed("취소됨")
        if job.stage_i == 3:
            _leave(job)
            _enter(job, 4)
        if code != 0:
            raise Failed("그림 생성이 실패했습니다.")

        out = WEBTOON / "outputs" / run_id / "ep1" / "episode.png"
        if not out.exists():
            raise Failed("그림은 나왔지만 한 편으로 잇지 못했습니다.")
        _leave(job)

        job.status = "done"
        job.finished_at = time.time()

    except Failed as exc:
        job.status = "cancelled" if job._cancel else "error"
        job.error = str(exc)
        job.finished_at = time.time()
        _leave(job, ok=False)
    except Exception as exc:                                   # noqa: BLE001
        job.status = "error"
        job.error = f"{type(exc).__name__}: {exc}"
        job.finished_at = time.time()
        _leave(job, ok=False)
    finally:
        job.save()


# --------------------------------------------------------------------------- #
# 결과 읽기
# --------------------------------------------------------------------------- #

def episode_dir(run_id: str) -> Path:
    return WEBTOON / "outputs" / run_id / "ep1"


def unit_image(run_id: str, no: int) -> Path | None:
    """장(Scene) 하나의 그림. MODE 를 컷으로 되돌려도 같은 함수로 찾는다.

    Scene 모드는 `scene_S+/scene3_c1.png`, 컷 모드는 `S+/cut3_c1.png` 다.
    조건은 S+ 가 기본이고, 시트가 없어 A 로 떨어진 옛 실행도 받아 준다.
    """
    ep = episode_dir(run_id)
    for cond in (CONDITION, "S", "A"):
        for folder, stem in ((f"scene_{cond}", "scene"), (cond, "cut")):
            p = ep / folder / f"{stem}{no}_c1.png"
            if p.exists():
                return p
    return None


def _read_json(base: Path, name: str) -> dict:
    """산출물 하나를 읽는다. 없거나 깨졌으면 빈 dict — 편집기는 일부만 있어도 열려야 한다."""
    try:
        return json.loads((base / name).read_text(encoding="utf-8-sig"))
    except Exception:                                          # noqa: BLE001
        return {}


def list_runs(limit: int = 60) -> list[dict[str, Any]]:
    """편집기 고르개에 뿌릴 run 목록. 그림이 하나라도 있는 것만.

    작업(Job)을 거치지 않는다 — 랜딩에서 만든 것이든 하네스를 직접 돌린 것이든
    똑같이 보여야 한다. 편집기는 "이미 그려진 것을 고치는 자리" 라서, 어떻게
    만들어졌는지는 상관이 없다.
    """
    out = []
    root = STORY / "runs"
    if not root.is_dir():
        return out
    for run_dir in sorted(root.glob("2026*"), reverse=True):
        if not (run_dir / "webtoon" / "ep01_cuts.json").exists():
            continue
        # 1번 장만 보지 않는다. 일부만 뽑아 둔 run 이 흔하고(3·4번만 뽑는 식),
        # 그런 run 도 그려진 장은 편집할 수 있어야 한다.
        if not any(unit_image(run_dir.name, n) for n in range(1, 13)):
            continue                       # 그림이 하나도 없으면 편집할 것이 없다
        p1 = _read_json(run_dir, "p1.json")
        eps = _read_json(run_dir / "webtoon", "arc1_episodes.json").get("episodes") or []
        out.append({
            "run_id": run_dir.name,
            "character": str(p1.get("name") or ""),
            "title": (eps[0].get("title") if eps else "") or "1화",
            "genre": str(_read_json(run_dir, "meta.json").get("input", {}).get("genre") or ""),
        })
        if len(out) >= limit:
            break
    return out


def editor_data(run_id: str) -> dict[str, Any]:
    """편집기 화면이 그대로 먹는 모양. mock.json 과 같은 구조다.

    result(job) 과 두 가지가 다르다:
      · **Job 이 아니라 run_id 로** 만든다. 하네스를 직접 돌린 run 도 열린다.
      · 장 그림의 주소를 같이 준다 (`image` · `w` · `h`). 편집기는 그림 위에
        말풍선을 얹으므로 원본 크기를 알아야 좌표를 퍼센트로 다룰 수 있다.
    """
    run_dir = STORY / "runs" / run_id
    data = _read_json(run_dir / "webtoon", "ep01_cuts.json")
    if not data:
        return {}
    p1 = _read_json(run_dir, "p1.json")
    p2 = _read_json(run_dir, "p2.json")

    def cut_card(c: dict[str, Any]) -> dict[str, Any]:
        return {"no": int(c.get("cut_number") or 0),
                "shot": str(c.get("shot") or ""),
                "beat": str(c.get("beat") or ""),
                "speaker": str(c.get("speaker") or ""),
                "dialogue": str(c.get("dialogue") or ""),
                "narration": str(c.get("narration") or ""),
                "thought": str(c.get("thought") or ""),
                "sfx": str(c.get("sfx") or ""),
                "description": str(c.get("description") or ""),
                # 한 컷에 말이 여러 줄일 수 있다(콘티 새 형식). 있으면 그대로 넘긴다 —
                # 편집기가 말풍선을 몇 개 얹어야 하는지는 이 값이 정한다.
                "lines": c.get("lines") or []}

    by_no = {int(c.get("cut_number") or 0): c for c in (data.get("cuts") or [])}
    ep_dir = episode_dir(run_id)
    grouping = _read_json(ep_dir, "scenes.json").get("scenes") or []
    if not grouping:
        grouping = [{"scene_number": n, "cut_numbers": [n]} for n in sorted(by_no)]

    scenes = []
    for sc in grouping:
        no = int(sc.get("scene_number") or 0)
        src = unit_image(run_id, no)
        if not src:
            continue
        w, h = _image_size(src)
        scenes.append({
            "no": no,
            "image": f"/api/runs/{run_id}/page/{no}",
            "w": w, "h": h,
            "cuts": [cut_card(by_no[n]) for n in (sc.get("cut_numbers") or [])
                     if n in by_no],
        })

    eps = _read_json(run_dir / "webtoon", "arc1_episodes.json").get("episodes") or []
    return {
        "run_id": run_id,
        "title": (eps[0].get("title") if eps else "") or "1화",
        "character": str(p1.get("name") or ""),
        "genre": str(_read_json(run_dir, "meta.json").get("input", {}).get("genre") or ""),
        "style_label": "",
        "logline": str(p2.get("logline") or ""),
        "cuts_per_sheet": str(CUTS_PER_SHEET),
        "scenes": scenes,
    }


def _image_size(path: Path) -> tuple[int, int]:
    """그림의 원본 크기. Pillow 가 없으면 어림값으로 떨어진다 (좌표는 퍼센트라
    비율만 맞으면 화면이 크게 어긋나지 않는다)."""
    try:
        from PIL import Image
        with Image.open(path) as im:
            return im.size
    except Exception:                                          # noqa: BLE001
        return (900, 1600)


def run_cost(run_id: str) -> dict[str, Any]:
    """작품 하나에 실제로 얼마가 들었나 — **세 원장을 한 줄로 합친다.**

    지금까지 비용이 세 군데에 흩어져 있었고, 어디에도 합계가 없었다:
      · 이야기      story-harness/runs/{id}/meta.json      usage.cost.usd
      · 캐릭터 시트 .../charsheet/charsheet_meta.json       usage.cost_usd
      · 그림·프롬프트 webtoon-harness/outputs/{id}/ep*/usage.json
    시트는 특히 어느 합계에도 안 섞여서, 세 곳을 사람이 더해야 "이 작품에 얼마"
    를 알 수 있었다.

    **어림값과 실측을 구분해서 돌려준다.** 텍스트는 실제 토큰 x 단가표라 정확하고,
    이미지는 단가표가 비어 있어 장당 고정값으로 센다. 둘을 한 숫자로 뭉치면
    "정확한 합계" 처럼 보이므로, estimated 에 어느 부분이 어림인지 남긴다.
    """
    run_dir = STORY / "runs" / run_id
    parts, estimated, notes = [], [], []
    seconds = 0.0

    meta = _read_json(run_dir, "meta.json")
    cost = ((meta.get("usage") or {}).get("cost") or {})
    if cost:
        parts.append({"part": "이야기", "usd": float(cost.get("usd") or 0.0),
                      "basis": f"실제 토큰 x 단가표 ({cost.get('rates_as_of') or '기준일 미상'})"})
        seconds += float(meta.get("elapsed_sec") or 0.0)
        if not cost.get("complete", True):
            estimated.append("이야기")
        for m in (cost.get("unpriced_models") or []):
            notes.append(f"단가 없는 모델: {m}")

    sheet = _read_json(run_dir / "charsheet", "charsheet_meta.json")
    su = sheet.get("usage") or {}
    if su.get("cost_usd") is not None:
        parts.append({"part": "캐릭터 시트",
                      "usd": float(su.get("cost_usd") or 0.0),
                      "basis": f"{su.get('images_made', 0)}장 x 장당 고정 "
                               f"({su.get('unit_cost_source') or '근거 미상'})"})
        estimated.append("캐릭터 시트")

    # 화가 여럿이면 ep1·ep2… 를 모두 더한다.
    ep_root = WEBTOON / "outputs" / run_id
    for ep_dir in sorted(ep_root.glob("ep*")) if ep_root.is_dir() else []:
        u = _read_json(ep_dir, "usage.json")
        tot = u.get("total") or {}
        if not tot:
            continue
        parts.append({"part": f"그림 ({ep_dir.name})",
                      "usd": float(tot.get("cost_usd") or 0.0),
                      "basis": f"호출 {tot.get('calls', 0)}회"
                               + (f" · {u.get('calls_priced_flat')}회는 고정 단가 어림"
                                  if u.get("calls_priced_flat") else "")})
        seconds += float(tot.get("seconds") or 0.0)
        if u.get("calls_priced_flat"):
            estimated.append(ep_dir.name)

    total = round(sum(p["usd"] for p in parts), 4)
    return {"run_id": run_id, "parts": parts, "total_usd": total,
            "total_krw": int(round(total * USD_TO_KRW)),
            "seconds": round(seconds, 1),
            "estimated": estimated, "notes": notes,
            "exact": not estimated}


def result(job: Job) -> dict[str, Any]:
    """완성본 한 편 + 그 안에 무엇이 담겼는지.

    결과물의 단위는 **장(Scene)** 이다 — 한 장에 컷이 3개씩 들어 있으므로,
    화면에도 장을 보여주고 그 장이 어느 컷들을 담고 있는지 같이 준다.
    대사 스크립트가 컷 단위여야 "이 말풍선이 몇 번 컷 것인가"를 볼 수 있다.
    """
    if not job.run_id:
        return {}
    run_dir = STORY / "runs" / job.run_id
    cuts_path = run_dir / "webtoon" / "ep01_cuts.json"
    if not cuts_path.exists():
        return {}
    data = json.loads(cuts_path.read_text(encoding="utf-8-sig"))

    def load(base: Path, name: str) -> dict:
        try:
            return json.loads((base / name).read_text(encoding="utf-8-sig"))
        except (OSError, json.JSONDecodeError):
            return {}

    p1, p2 = load(run_dir, "p1.json"), load(run_dir, "p2.json")
    eps = load(run_dir, "webtoon/arc1_episodes.json").get("episodes") or []
    title = (eps[0].get("title") if eps else "") or "1화"

    def cut_card(c: dict) -> dict:
        return {
            "no": int(c.get("cut_number") or 0),
            "speaker": str(c.get("speaker") or ""),
            "dialogue": str(c.get("dialogue") or ""),
            "narration": str(c.get("narration") or ""),
            "thought": str(c.get("thought") or ""),
            "sfx": str(c.get("sfx") or ""),
            "description": str(c.get("description") or ""),
            "shot": str(c.get("shot") or ""),
        }

    by_no = {int(c.get("cut_number") or 0): c for c in (data.get("cuts") or [])}

    # 어느 컷이 어느 장에 묶였는지는 그림 쪽이 안다 (scenes.json).
    ep_dir = episode_dir(job.run_id)
    grouping = load(ep_dir, "scenes.json").get("scenes") or []
    if not grouping:
        # 컷 모드로 되돌렸거나 아직 묶기 전 — 컷 하나를 한 장으로 본다.
        grouping = [{"scene_number": n, "cut_numbers": [n]} for n in sorted(by_no)]

    pages, drawn_cuts = [], 0
    for sc in grouping:
        no = int(sc.get("scene_number") or 0)
        if not unit_image(job.run_id, no):
            continue                       # 아직 안 그렸거나 미리보기로 빠진 장
        cards = [cut_card(by_no[n]) for n in (sc.get("cut_numbers") or [])
                 if n in by_no]
        drawn_cuts += len(cards)
        pages.append({"no": no, "cuts": cards})

    ep_png = ep_dir / "episode.png"
    return {
        "title": title,
        "character": str(p1.get("name") or ""),
        "intro": str(p1.get("intro") or ""),
        "logline": str(p2.get("logline") or ""),
        "genre": str(load(run_dir, "meta.json").get("input", {}).get("genre") or ""),
        "style_label": STYLES.get(job.style, job.style),
        "cuts_per_sheet": CUTS_PER_SHEET,
        # 얼마나 걸렸는가. 단계별로도 준다 — "어디서 오래 걸렸나"가 총 시간보다
        # 쓸모 있다 (그림이 대부분이고, 이야기가 길어지면 재생성이 돈 것이다).
        "seconds": round(
            (job.finished_at - job.started_at) if (job.started_at and job.finished_at)
            else job.stage_seconds(), 1),
        "stage_times": [{"title": st["title"], "seconds": st.get("seconds")}
                        for st in job.stages if st.get("seconds") is not None],
        "pages": pages,
        "page_count": len(pages),
        "cut_count": drawn_cuts,
        "planned_cuts": len(by_no),
        "planned_pages": len(grouping),
        "preview": job.preview,
        "has_episode_png": ep_png.exists(),
        "run_id": job.run_id,
    }


# --------------------------------------------------------------------------- #
# 큐 — 한 번에 한 편만
#
# 이미지 호출이 12회 나가는 일이라 동시에 여러 편을 돌리면 요금과 rate limit 이
# 같이 터진다. 뒤에 온 요청은 줄을 선다.
# --------------------------------------------------------------------------- #

class Runner:
    def __init__(self) -> None:
        self.jobs: dict[str, Job] = {}
        self.queue: list[str] = []
        self._lock = threading.Lock()
        self._worker: threading.Thread | None = None

    def restore(self) -> int:
        """지난 실행에서 끝난 작업들을 다시 읽어 온다 (결과 화면만 다시 열립니다)."""
        if not JOBS_DIR.exists():
            return 0
        found = 0
        for d in sorted(JOBS_DIR.iterdir()):
            if not d.is_dir() or d.name in self.jobs:
                continue
            job = Job.load(d)
            if job:
                self.jobs[job.id] = job
                found += 1
        return found

    def create(self, form: dict[str, Any], photo: bytes | None) -> Job:
        job_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:4]
        job_dir = JOBS_DIR / job_id
        job_dir.mkdir(parents=True, exist_ok=True)
        if photo:
            (job_dir / "photo.png").write_bytes(photo)
        style = str(form.get("style") or "webtoon")
        if style not in STYLES:
            style = "webtoon"
        job = Job(id=job_id, form=form, dir=job_dir, style=style,
                  preview=bool(form.get("preview")), has_photo=bool(photo))
        job.build_stages()
        (job_dir / "input.json").write_text(
            json.dumps(form, ensure_ascii=False, indent=2), encoding="utf-8")

        with self._lock:
            self.jobs[job_id] = job
            self.queue.append(job_id)
            if self._worker is None or not self._worker.is_alive():
                self._worker = threading.Thread(target=self._drain, daemon=True)
                self._worker.start()
        return job

    def position(self, job_id: str) -> int:
        with self._lock:
            return self.queue.index(job_id) if job_id in self.queue else 0

    def _drain(self) -> None:
        while True:
            with self._lock:
                if not self.queue:
                    self._worker = None
                    return
                job = self.jobs[self.queue[0]]
            try:
                execute(job)
            finally:
                with self._lock:
                    if self.queue and self.queue[0] == job.id:
                        self.queue.pop(0)

    def get(self, job_id: str) -> Job | None:
        return self.jobs.get(job_id)


# --------------------------------------------------------------------------- #
# CLI — 서버 없이 비용만 보고 싶을 때
#   python pipeline.py --cost <run_id>
#   python pipeline.py --cost            (편집 가능한 run 전부)
# --------------------------------------------------------------------------- #
if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser(description="작품 하나에 든 비용·시간")
    ap.add_argument("--cost", nargs="?", const="", metavar="RUN_ID",
                    help="run_id 하나, 또는 비우면 전부")
    a = ap.parse_args()

    if a.cost is None:
        ap.print_help()
        raise SystemExit(0)

    ids = [a.cost] if a.cost else [r["run_id"] for r in list_runs()]
    grand = 0.0
    for rid in ids:
        c = run_cost(rid)
        if not c["parts"]:
            print(f"{rid}  (원장 없음)")
            continue
        grand += c["total_usd"]
        mark = "" if c["exact"] else "  ~어림"
        print()
        print(f"{rid}   ${c['total_usd']:.4f} "
              f"({c['total_krw']:,}원) · {c['seconds']:.0f}초{mark}")
        for part in c["parts"]:
            print(f"   {part['part']:<16} ${part['usd']:>8.4f}   {part['basis']}")
        for note in c["notes"]:
            print(f"   ! {note}")
        if c["estimated"]:
            print(f"   어림값: {', '.join(c['estimated'])} "
                  f"— 실제 토큰이 아니라 고정 단가로 셌습니다")
    if len(ids) > 1:
        print()
        print(f"합계 ${grand:.4f} ({int(round(grand * USD_TO_KRW)):,}원)")
