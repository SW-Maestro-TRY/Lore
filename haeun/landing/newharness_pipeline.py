"""new_harness 연결 — 최소 실험 경로.

pipeline.py 의 5단계(story-harness+webtoon-harness) 흐름과는 **별도 경로**다.
new_harness 는 이야기·콘티·시트·그림을 run.py 하나 안에서 끝내는 단일
파이프라인이라 pipeline.py 의 STAGE_SPEC(look/seed/card/...)이 안 맞고,
산출물 단위(컷/장면이 아니라 페이지)도 달라서 같은 Job 을 못 쓴다.

## 왜 run.py 를 세 번 나눠 부르는가

`run.py --run-id <id> --pick <n> --all` 을 그대로 쓰면 안 된다 — run.py 의
main() 은 `new_run or args.all` 이면 무조건 이야기 단계(stage_story)부터 다시
돈다. run_id 로 이어 하는 중이라도 `--all` 을 주는 순간 이미 고른 방향과
무관하게 새 이야기 후보 4개를 다시 뽑는다(실제로 읽어서 확인함, 2026-08-31).
그래서 이어할 때는 `--all` 을 아예 안 쓰고, 세 번으로 나눈다:

    1. `--character <path>`           이야기 후보 4개 (사람이 고를 것)
    2. `--run-id <id> --pick <n>`      (--all 없이) 구체화 + 콘티만
    3. `--run-id <id> --sheet --pages` 시트 + 페이지 그림 (한 호출 안에서
                                       시트가 먼저 돌아 pages 가 참조로 쓴다)

이렇게 나누면 1번에서 보여준 후보 그대로 2번이 고르고, 이야기가 두 번
불리지 않는다.

## 지금 안 하는 것

전문/일반 모드, 이어 그리기·이어 만들기(다음 화), 편집실(overlay) 연동은
없다. new_harness 는 대사를 이미지에 직접 그려 넣어서(README 의 미해결
경고 참고) 편집실이 전제하는 "글자는 나중에 합성" 이 애초에 안 맞는다 —
이 상태를 바꾸는 것은 이번 범위가 아니라고 정했다(2026-08-31, 하은 확인).
캐릭터 입력 -> 후보 4개 -> 고름 -> 콘티+시트+그림 -> 이어붙인 한 장,
그것만 끝까지 되게 한다.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import pipeline as classic          # write_character() 재사용 — 폼 -> character.json

HERE = Path(__file__).resolve().parent
JOBS_DIR = HERE / "jobs_nh"
NEW_HARNESS = HERE.parent / "new_harness"

STATUS_QUEUED = "queued"
STATUS_RUNNING = "running"
STATUS_AWAITING_PICK = "awaiting_pick"
STATUS_DONE = "done"
STATUS_ERROR = "error"

# "[페이지 3/7] 컷 2개 · 참조 2장 …" — pageart.draw() 가 찍는 줄 (pageart.py:116).
RE_PAGE = re.compile(r"^\[페이지 (\d+)/(\d+)\]")
# "run: /.../runs/20260831T000249-10322f" — run.py:1141.
RE_RUN_ID = re.compile(r"^run: .*[/\\]([^/\\]+)\s*$")


@dataclass
class NHJob:
    id: str
    form: dict[str, Any]
    dir: Path
    status: str = STATUS_QUEUED
    run_id: str | None = None
    error: str | None = None
    directions: list[dict] = field(default_factory=list)
    pick: int | None = None
    art_done: int = 0
    art_total: int = 0
    log: list[str] = field(default_factory=list)
    started_at: float | None = None
    finished_at: float | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False, compare=False)
    _proc: subprocess.Popen | None = field(default=None, repr=False, compare=False)
    _cancel: bool = field(default=False, repr=False, compare=False)

    # ---- 로그·상태 ---------------------------------------------------------- #

    def add_log(self, line: str) -> None:
        with self._lock:
            self.log.append(line)
            if len(self.log) > 400:
                del self.log[:100]

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            art = None
            if self.art_total:
                art = {"done": self.art_done, "total": self.art_total}
            return {
                "id": self.id,
                "status": self.status,
                "run_id": self.run_id,
                "error": self.error,
                "directions": self.directions,
                "pick": self.pick,
                "art": art,
                "log": self.log[-60:],
                "elapsed": round((self.finished_at or time.time())
                                 - self.started_at, 1) if self.started_at else 0,
            }

    def save(self) -> None:
        try:
            (self.dir / "state.json").write_text(json.dumps({
                "id": self.id, "status": self.status, "run_id": self.run_id,
                "error": self.error, "form": self.form,
                "directions": self.directions, "pick": self.pick,
            }, ensure_ascii=False, indent=1), encoding="utf-8")
        except OSError:
            pass

    # ---- 실행 ---------------------------------------------------------------- #

    def _env(self) -> dict[str, str]:
        env = dict(os.environ)
        env["PYTHONIOENCODING"] = "utf-8"
        env["PYTHONUTF8"] = "1"
        return env

    def _run(self, args: list[str], on_line: Callable[[str], None]) -> int:
        display = " ".join(["python", "run.py", *args])
        self.add_log(f"$ {display}")
        with (self.dir / "log.txt").open("a", encoding="utf-8") as fh:
            fh.write(f"\n$ {display}\n")
        proc = subprocess.Popen(
            [sys.executable, "-u", "run.py", *args], cwd=str(NEW_HARNESS),
            env=self._env(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
        self._proc = proc
        with (self.dir / "log.txt").open("a", encoding="utf-8") as fh:
            for raw in proc.stdout:                    # type: ignore[union-attr]
                line = raw.rstrip("\n")
                fh.write(line + "\n")
                if line.strip():
                    self.add_log(line)
                    on_line(line)
        self._proc = None
        return proc.wait()

    def _stitch(self) -> tuple[bool, str]:
        result = subprocess.run(
            [sys.executable, "-u", "stitch.py", "--run-id", self.run_id],
            cwd=str(NEW_HARNESS), env=self._env(), capture_output=True,
            text=True, encoding="utf-8", errors="replace")
        out = (result.stdout or "").strip()
        if out:
            self.add_log(out)
        if result.returncode != 0:
            err = (result.stderr or "").strip()
            if err:
                self.add_log(err)
            return False, err or "이어 붙이기가 실패했습니다"
        return True, ""

    def cancel(self) -> None:
        self._cancel = True
        proc = self._proc
        if proc and proc.poll() is None:
            try:
                proc.terminate()
            except OSError:
                pass


def _on_page_line(job: NHJob, line: str) -> None:
    m = RE_PAGE.match(line)
    if m:
        job.art_done = int(m.group(1)) - 1
        job.art_total = int(m.group(2))


def _extract_run_id(job: NHJob) -> str | None:
    for line in job.log:
        m = RE_RUN_ID.match(line)
        if m:
            return m.group(1)
    return None


def _fail(job: NHJob, message: str) -> None:
    job.status = STATUS_ERROR
    job.error = message
    job.save()


def _run_story_phase(job: NHJob) -> None:
    """1단계 — 이야기 후보 4개. 사람이 고를 때까지 여기서 멈춘다."""
    job.status = STATUS_RUNNING
    job.started_at = time.time()
    job.save()
    try:
        char_path = job.dir / "character.json"
        code = job._run(["--character", str(char_path)], lambda _line: None)
        if job._cancel:
            return _fail(job, "취소되었습니다")
        if code != 0:
            return _fail(job, "이야기 후보를 만들지 못했습니다 — 로그를 확인하세요")

        run_id = _extract_run_id(job)
        if not run_id:
            return _fail(job, "run_id 를 읽지 못했습니다")
        job.run_id = run_id

        directions_path = NEW_HARNESS / "runs" / run_id / "directions.json"
        if not directions_path.exists():
            return _fail(job, "이야기 후보 파일이 없습니다")
        directions = json.loads(directions_path.read_text(encoding="utf-8"))
        if not directions:
            return _fail(job, "이야기 후보를 하나도 못 읽었습니다 — story.md 를 확인하세요")

        job.directions = directions
        job.status = STATUS_AWAITING_PICK
        job.save()
    except Exception as exc:                            # noqa: BLE001
        _fail(job, f"{type(exc).__name__}: {exc}")


def _run_rest_phase(job: NHJob) -> None:
    """2·3단계 — 고른 방향으로 구체화+콘티, 그다음 시트+페이지, 그리고 이어붙이기."""
    job.status = STATUS_RUNNING
    job.save()
    try:
        code = job._run(["--run-id", job.run_id, "--pick", str(job.pick)],
                        lambda _line: None)
        if job._cancel:
            return _fail(job, "취소되었습니다")
        if code != 0:
            return _fail(job, "콘티를 만들지 못했습니다 — 로그를 확인하세요")

        code = job._run(["--run-id", job.run_id, "--sheet", "--pages"],
                        lambda line: _on_page_line(job, line))
        if job._cancel:
            return _fail(job, "취소되었습니다")
        if code != 0:
            return _fail(job, "시트나 그림을 만들지 못했습니다 — 로그를 확인하세요")

        ok, err = job._stitch()
        if not ok:
            return _fail(job, err)

        job.art_done = job.art_total
        job.status = STATUS_DONE
        job.finished_at = time.time()
        job.save()
    except Exception as exc:                            # noqa: BLE001
        _fail(job, f"{type(exc).__name__}: {exc}")


class NHRunner:
    """job 저장소. 클래식 Runner 와 달리 큐를 안 둔다 — 실험 단계라 동시 실행
    제한(비용 폭주 방지)은 나중에 트래픽이 생기면 그때 넣는다."""

    def __init__(self) -> None:
        self.jobs: dict[str, NHJob] = {}
        JOBS_DIR.mkdir(parents=True, exist_ok=True)

    def get(self, job_id: str) -> NHJob | None:
        return self.jobs.get(job_id)

    def create(self, form: dict[str, Any], photos: list[bytes]) -> NHJob:
        job_id = uuid.uuid4().hex[:12]
        job_dir = JOBS_DIR / job_id
        job_dir.mkdir(parents=True, exist_ok=True)
        for i, raw in enumerate(photos, 1):
            (job_dir / f"photo{i}.png").write_bytes(raw)
        classic.write_character(job_dir, form)

        job = NHJob(id=job_id, form=form, dir=job_dir)
        self.jobs[job_id] = job
        job.save()
        threading.Thread(target=_run_story_phase, args=(job,), daemon=True).start()
        return job

    def pick(self, job_id: str, n: int) -> NHJob:
        job = self.jobs.get(job_id)
        if not job:
            raise KeyError(job_id)
        if job.status != STATUS_AWAITING_PICK:
            raise ValueError("지금은 방향을 고를 수 없습니다")
        if not any(d.get("n") == n for d in job.directions):
            raise ValueError(f"방향 {n} 이 없습니다")
        job.pick = n
        job.save()
        threading.Thread(target=_run_rest_phase, args=(job,), daemon=True).start()
        return job


def episode_path(job: NHJob) -> Path | None:
    if not job.run_id:
        return None
    path = NEW_HARNESS / "runs" / job.run_id / "episode.png"
    return path if path.exists() else None


def sheet_path(job: NHJob) -> Path | None:
    if not job.run_id:
        return None
    path = NEW_HARNESS / "runs" / job.run_id / "sheet.png"
    return path if path.exists() else None


def page_path(job: NHJob, n: int) -> Path | None:
    if not job.run_id:
        return None
    path = NEW_HARNESS / "runs" / job.run_id / "pages" / f"page{n:02d}.png"
    return path if path.exists() else None
