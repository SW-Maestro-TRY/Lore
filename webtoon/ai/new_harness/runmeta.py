#!/usr/bin/env python3
"""meta.json 에 호출 기록 한 줄을 더한다 — **여러 프로세스가 동시에 써도.**

장면을 동시에 그리기 시작하면서 생긴 자리다. 전에는 한 편이 파이썬 프로세스
하나였고 그 안에서 페이지를 차례로 그렸으니, `읽기 -> 한 줄 더하기 -> 쓰기`
가 겹칠 일이 없었다. 지금은 장면마다 프로세스가 따로 뜰 수 있어서, 그냥
쓰면 **같은 순간에 읽은 둘 중 나중에 쓴 쪽이 앞의 기록을 덮어쓴다** — 그림
값은 이미 나갔는데 정산에서만 사라진다.

그래서 파일 잠금(flock) 안에서 읽고 쓴다. 잠금은 같은 파일을 여는 모든
프로세스에 걸리므로, 누가 먼저 시작했는지와 상관없이 한 줄씩 쌓인다.
"""

from __future__ import annotations

import fcntl
import json
from pathlib import Path


def append_call(run_dir: Path, call_meta: dict) -> None:
    """meta.json 의 calls 에 한 줄 더한다. 파일이 없으면 만든다."""
    path = run_dir / "meta.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    # a+ 는 파일이 없으면 만들고, 있으면 안 지운다 — 잠금을 걸 대상이
    # 먼저 있어야 해서 이 모드로 연다(쓰기는 아래에서 따로 한다).
    with open(path, "a+", encoding="utf-8") as fh:
        fcntl.flock(fh.fileno(), fcntl.LOCK_EX)
        try:
            fh.seek(0)
            text = fh.read().strip()
            try:
                meta = json.loads(text) if text else {}
            except json.JSONDecodeError:
                # 읽을 수 없게 된 파일을 지우지는 않는다 — 그 안에 이미 나간
                # 돈의 기록이 있다. 옆에 남겨 두고 새로 시작한다.
                path.with_suffix(".json.broken").write_text(text, encoding="utf-8")
                meta = {}
            if not isinstance(meta, dict):
                meta = {}
            meta.setdefault("run_id", run_dir.name)
            calls = meta.get("calls")
            meta["calls"] = (calls if isinstance(calls, list) else []) + [call_meta]
            fh.seek(0)
            fh.truncate()
            fh.write(json.dumps(meta, ensure_ascii=False, indent=2))
        finally:
            fcntl.flock(fh.fileno(), fcntl.LOCK_UN)


def load(run_dir: Path) -> dict:
    path = run_dir / "meta.json"
    if not path.exists():
        return {"run_id": run_dir.name, "calls": []}
    try:
        got = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"run_id": run_dir.name, "calls": []}
    return got if isinstance(got, dict) else {"run_id": run_dir.name, "calls": []}
