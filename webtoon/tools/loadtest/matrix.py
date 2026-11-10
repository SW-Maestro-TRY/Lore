#!/usr/bin/env python3
"""설정(workers × page-workers)마다 서버를 새로 띄워 N 을 늘려 가며 drive.py 를 돌린다.

설정마다: DB 사본을 새로 만들고(알림 주소·푸시 구독은 사본에서 비운다) → serve.py 로
띄우고 → N 마다 drive.py → 서버를 내리고 사본을 지운다.

    python3 matrix.py --workers 2,3,4,6 --page-workers 3,4,6 --n 2,5,10,20
결과: results/matrix/<w>x<p>-n<N>.json, 요약 한 줄씩 results/matrix/summary.jsonl
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def sh(cmd: str) -> None:
    subprocess.run(cmd, shell=True, check=True)


def fresh_db(db: str) -> None:
    subprocess.run(f"dropdb -U mint --if-exists {db}", shell=True, check=True)
    sh(f"createdb -U mint {db}")
    sh(f"pg_dump -U mint lore | psql -q -U mint -d {db} >/dev/null 2>&1")
    # 사본에서 사람에게 나갈 수 있는 것을 끊는다 — StaleJobs·완성 알림이 메일·푸시를 보내지 않게
    sh(f"psql -q -U mint -d {db} -c \"update webtoon_job set notify_email=null; "
       f"delete from webtoon_push_subscription;\"")


def wait_port(port: int, proc: subprocess.Popen, limit: float = 120) -> None:
    t0 = time.time()
    while time.time() - t0 < limit:
        if proc.poll() is not None:
            raise SystemExit("서버가 뜨다 죽었습니다")
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(1)
    raise SystemExit("서버가 안 뜹니다")


def warm_up(port: int) -> None:
    """한 편을 만들었다 바로 취소한다 — 오늘 몫 줄(webtoon_guest_quota)을 먼저 만들어 둔다.

    그 줄이 없는 상태에서 게스트 만들기가 동시에 둘 이상 오면 하나가 500 으로 죽는다
    (uk_webtoon_guest_quota_day 중복 — GuestGate.useOrBlock 의 읽고-없으면-넣기 경합).
    그 버그를 따로 적고, 측정에는 섞지 않으려는 것이다.
    """
    sys.path.insert(0, str(HERE))
    import drive
    base = f"http://localhost:{port}"
    code, got, _ = drive.http(base, "POST", "/nh/create", dict(drive.CREATE, uid="lt-warmup"))
    if code == 200 and got.get("id"):
        drive.http(base, "POST", f"/nh/jobs/{got['id']}/cancel")
        time.sleep(2)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--workers", default="2,3,4,6")
    ap.add_argument("--page-workers", default="3,4,6")
    ap.add_argument("--n", default="2,5,10,20")
    ap.add_argument("--port", type=int, default=8097)
    ap.add_argument("--db", default="lore_lt")
    ap.add_argument("--tag", default="")
    a = ap.parse_args()
    out = HERE / "results" / "matrix"
    out.mkdir(parents=True, exist_ok=True)

    for w in a.workers.split(","):
        for p in a.page_workers.split(","):
            cfg = f"w{w}p{p}{a.tag}"
            fresh_db(a.db)
            srv_out = HERE / "results" / f"server-{cfg}"
            env = dict(os.environ, LT_DB=a.db, LT_PORT=str(a.port), LT_WORKERS=w,
                       LT_PAGE_WORKERS=p, LT_OUT=str(srv_out),
                       FAKE_LOG=str(srv_out / "fake-steps.jsonl"))
            log = open(HERE / "results" / f"server-{cfg}.log", "w")
            proc = subprocess.Popen([sys.executable, str(HERE / "serve.py")], env=env,
                                    stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                wait_port(a.port, proc)
                time.sleep(3)
                warm_up(a.port)
                for n in a.n.split(","):
                    res = out / f"{cfg}-n{n}.json"
                    subprocess.run([sys.executable, str(HERE / "drive.py"), "--n", n,
                                    "--pid", str(proc.pid), "--sample", "1",
                                    "--base", f"http://localhost:{a.port}",
                                    "--fake-log", str(srv_out / "fake-steps.jsonl"),
                                    "--label", cfg, "--out", str(res)],
                                   check=True, stdout=subprocess.DEVNULL)
                    summ = json.loads(res.read_text())["summary"]
                    summ.update({"workers": int(w), "page_workers": int(p)})
                    with open(out / "summary.jsonl", "a") as fh:
                        fh.write(json.dumps(summ, ensure_ascii=False) + "\n")
                    print(cfg, n, summ["completed"], summ["failed"], summ["wall_s"], flush=True)
            finally:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(30)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                log.close()
    subprocess.run(f"dropdb -U mint --if-exists {a.db}", shell=True)


if __name__ == "__main__":
    main()
