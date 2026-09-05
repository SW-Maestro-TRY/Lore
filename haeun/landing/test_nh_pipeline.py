"""new_harness 실험 경로의 job 줄서기 — 동시 실행 제한(비용 폭주 방지).

두 하네스·overlay 테스트와 같은 방식이다 (pytest 아님, 마지막 줄에 ALL PASS).
호출을 안 하니 돈이 안 든다 — 큐·워커 로직만 가짜 작업으로 검사한다.

    cd landing && python test_nh_pipeline.py
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import newharness_pipeline as NP  # noqa: E402

fails = []


def ok(name, cond, extra="") -> None:
    print(("PASS  " if cond else "FAIL  ") + name + (f"   {extra}" if extra and not cond else ""))
    if not cond:
        fails.append(name)


def check(name, got, want) -> None:
    ok(name, got == want, f"나온 것: {got!r} / 바라던 것: {want!r}")


def make_runner(root: Path) -> NP.NHRunner:
    """jobs_nh 를 실제 경로 대신 임시 폴더로 돌린다 — 진짜 job 저장소를 안 건드린다."""
    NP.JOBS_DIR = root / "jobs_nh"
    return NP.NHRunner()


def make_job(root: Path, job_id: str) -> NP.NHJob:
    d = root / "jobs_nh" / job_id
    d.mkdir(parents=True, exist_ok=True)
    return NP.NHJob(id=job_id, form={}, dir=d)


def test_serial_execution() -> None:
    """줄에 넣은 일이 한 번에 하나씩만 돈다 — 두 개가 동시에 안 겹친다."""
    root = Path(tempfile.mkdtemp(prefix="nh-test-"))
    try:
        runner = make_runner(root)
        running = []
        max_concurrent = [0]
        lock = threading.Lock()

        def work(n):
            with lock:
                running.append(n)
                max_concurrent[0] = max(max_concurrent[0], len(running))
            time.sleep(0.05)
            with lock:
                running.remove(n)

        for i in range(5):
            runner._enqueue(f"job{i}", lambda i=i: work(i))

        deadline = time.time() + 5
        while runner._worker is not None and time.time() < deadline:
            time.sleep(0.01)

        check("한 번에 하나씩만 돌았다", max_concurrent[0], 1)
        check("큐가 다 비었다", runner.queue, [])
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_position() -> None:
    """줄에서 몇 번째인지 — 뒤에 온 job 일수록 순서가 크다."""
    root = Path(tempfile.mkdtemp(prefix="nh-test-"))
    try:
        runner = make_runner(root)
        gate = threading.Event()

        def blocked(_n):
            gate.wait(timeout=5)

        for i in range(3):
            runner._enqueue(f"job{i}", lambda i=i: blocked(i))
        time.sleep(0.05)  # 첫 job 이 워커를 잡을 시간

        check("맨 앞은 큐에서 이미 빠졌다(0)", runner.position("job0"), 0)
        check("두 번째는 줄의 0번째(다음 차례)", runner.position("job1"), 0)
        check("세 번째는 줄의 1번째", runner.position("job2"), 1)
        check("모르는 job 은 0", runner.position("job-없음"), 0)

        gate.set()
        deadline = time.time() + 5
        while runner._worker is not None and time.time() < deadline:
            time.sleep(0.01)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_cancel_while_queued() -> None:
    """줄에서 기다리는 동안 취소되면 아예 안 돈다 — subprocess 를 안 띄운다."""
    root = Path(tempfile.mkdtemp(prefix="nh-test-"))
    try:
        runner = make_runner(root)
        job_a = make_job(root, "a")
        job_b = make_job(root, "b")
        runner.jobs[job_a.id] = job_a
        runner.jobs[job_b.id] = job_b

        called = []
        gate = threading.Event()

        def hold(_n):
            gate.wait(timeout=5)

        def mark_b():
            called.append("b")

        runner._enqueue(job_a.id, lambda: hold("a"))    # 워커를 잡아 둔다
        runner._enqueue(job_b.id, mark_b)
        time.sleep(0.05)
        job_b.cancel()                                   # 아직 줄에서 기다리는 중
        gate.set()

        deadline = time.time() + 5
        while runner._worker is not None and time.time() < deadline:
            time.sleep(0.01)

        ok("취소된 job 의 일은 실행되지 않았다", "b" not in called)
        check("취소된 job 은 에러로 마무리된다", job_b.status, NP.STATUS_ERROR)
        check("사유가 남는다", job_b.error, "취소되었습니다")
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_pick_guards_against_double_queue() -> None:
    """pick() 이 두 번 불려도 같은 job 이 줄에 두 번 안 선다."""
    root = Path(tempfile.mkdtemp(prefix="nh-test-"))
    try:
        runner = make_runner(root)
        job = make_job(root, "p1")
        job.status = NP.STATUS_AWAITING_PICK
        job.directions = [{"n": 1}]
        runner.jobs[job.id] = job

        gate = threading.Event()
        calls = []

        def slow_board(job=job):
            calls.append(1)
            gate.wait(timeout=5)

        orig = NP._run_pick_then_pages_phase
        NP._run_pick_then_pages_phase = slow_board
        try:
            runner.pick(job.id, 1)
            ok("두 번째 pick 은 막힌다", _raises_value_error(lambda: runner.pick(job.id, 1)))
            time.sleep(0.05)
            gate.set()
            deadline = time.time() + 5
            while runner._worker is not None and time.time() < deadline:
                time.sleep(0.01)
            check("실제로는 한 번만 돌았다", len(calls), 1)
        finally:
            NP._run_pick_then_pages_phase = orig
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_review_order() -> None:
    """검수 순서 — **시트가 먼저, 이야기 고르기가 나중이다.**

    run.py 는 안 부른다(NHJob._run 을 가짜로 갈아 끼운다) — 각 단계가 남겨야
    할 파일만 흉내 내고, 상태가 어떤 순서로 넘어가는지만 본다. 호출이 없으니
    돈도 안 나간다.
    """
    root = Path(tempfile.mkdtemp(prefix="nh-test-"))
    orig_new_harness, orig_run, orig_stitch = NP.NEW_HARNESS, NP.NHJob._run, NP.NHJob._stitch
    try:
        runner = make_runner(root)
        NP.NEW_HARNESS = root / "new_harness"
        run_id = "20260903T000000-test"
        run_dir = NP.NEW_HARNESS / "runs" / run_id
        run_dir.mkdir(parents=True, exist_ok=True)
        directions = [{"n": i, "title": f"방향 {i}", "genre": "로맨스 판타지",
                       "plot": "줄거리", "scenes": ["장면1"]} for i in range(1, 5)]

        seen = []

        def fake_run(self, args, on_line):
            seen.append(" ".join(args))
            if "--character" in args:
                (run_dir / "directions.json").write_text(
                    json.dumps(directions, ensure_ascii=False), encoding="utf-8")
                self.add_log(f"run: {run_dir}")
            elif "--sheet" in args:
                (run_dir / "sheet.png").write_bytes(b"fake")
            elif "--pick-save" in args:
                (run_dir / "pick.json").write_text('{"n": 2}', encoding="utf-8")
            return 0

        NP.NHJob._run = fake_run
        NP.NHJob._stitch = lambda self: (True, "")

        job = runner.create({"name": "하은", "style": "webtoon"}, [])
        _settle(runner)

        check("첫 멈춤은 시트 확인이다", job.status, NP.STATUS_AWAITING_SHEET)
        check("이야기 -> 시트 순서로 불렀다",
              [a.split()[0] for a in seen], ["--character", "--run-id"])
        check("후보 4개는 고르기 전에 이미 받아 뒀다", len(job.directions), 4)
        ok("시트를 확인할 때는 아직 방향을 안 골랐다",
           not (run_dir / "pick.json").exists())

        runner.approve_sheet(job.id)
        check("시트 승인 다음은 이야기 고르기", job.status, NP.STATUS_AWAITING_PICK)
        check("승인만으로는 아무것도 다시 안 부른다", len(seen), 2)

        runner.pick(job.id, 2)
        _settle(runner)
        check("방향을 고른 다음은 완성까지 안 멈춘다", job.status, NP.STATUS_DONE)
        ok("마지막에 부른 것은 페이지 그림이다", "--detail-pages" in seen[-1])
        check("화면에 보여줄 단계도 시트가 먼저다",
              job.snapshot()["stages"], ["story", "sheet", "board", "pages"])
    finally:
        NP.NEW_HARNESS, NP.NHJob._run, NP.NHJob._stitch = \
            orig_new_harness, orig_run, orig_stitch
        shutil.rmtree(root, ignore_errors=True)


def _settle(runner: NP.NHRunner, timeout: float = 5.0) -> None:
    end = time.time() + timeout
    while runner._worker is not None and time.time() < end:
        time.sleep(0.01)


def _raises_value_error(fn) -> bool:
    try:
        fn()
    except ValueError:
        return True
    return False


def test_review_say() -> None:
    """검수가 도는 동안 화면에 띄우는 한 줄.

    사용자에게 보여주는 것은 **"지금 검수 중이다 / 걸려서 다시 그린다"**
    까지다. 어느 장면이 왜 걸렸는지는 만드는 쪽 사정이라 안 보여준다.
    그리고 검수는 단계(stage)를 안 바꾼다 — 걸음을 하나 더 만들면 진행
    막대가 뒤로 갔다 오는 것처럼 보인다.
    """
    root = Path(tempfile.mkdtemp())
    try:
        job = NP.NHJob(id="j", form={}, dir=root)
        job.stage = "pages"

        NP._on_rest_line(job, "[이야기 검수] openai:gpt-4.1 로 후보 4개를 독자의 눈으로 읽습니다…")
        check("이야기 검수 중", job.say, NP.SAY_REVIEW_STORY)

        NP._on_rest_line(job, "  [검수] openai:gpt-4.1 · 그림 2장 …")
        check("그림 검수 중", job.say, NP.SAY_REVIEW_PAGE)
        check("검수는 단계를 안 바꾼다", job.stage, "pages")

        NP._on_rest_line(job, "  [검수] 다시 그립니다 (1/1)")
        check("걸리면 다시 그린다고 말한다", job.say, NP.SAY_REDRAW)

        # 새 장을 그리기 시작하면 검수 문구는 사라진다 — 안 지우면 그리는
        # 내내 "검수하고 있어요" 가 남는다.
        NP._on_rest_line(job, "[장면 3/5] 참조 2장 …")
        check("새 장을 그리면 지워진다", job.say, "")
        check("장 수는 그대로 센다", (job.art_done, job.art_total), (2, 5))

        NP._on_rest_line(job, "[화 검수] openai:gpt-4.1 로 6장을 처음부터 읽습니다…")
        check("완성본 검수 중", job.say, NP.SAY_REVIEW_EPISODE)

        # 판정 세부(무엇이 몇 건)는 화면에 안 나간다
        ok("판정 세부는 문구에 없다",
           all("critical" not in t and "장면" not in t
               for t in (NP.SAY_REVIEW_STORY, NP.SAY_REVIEW_PAGE,
                         NP.SAY_REDRAW, NP.SAY_REVIEW_EPISODE)))
        ok("snapshot 이 실어 보낸다", "say" in job.snapshot())
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    for fn in (test_serial_execution, test_position, test_cancel_while_queued,
               test_pick_guards_against_double_queue, test_review_order,
               test_review_say):
        fn()
    if fails:
        print("FAILED:")
        for f in fails:
            print("  - " + f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
