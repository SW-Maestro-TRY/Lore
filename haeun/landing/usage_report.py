"""만든 값을 서버(스프링)에 올린다.

**왜 여기서 올리는가.** 하네스는 호출마다 아주 촘촘히 기록한다 — 단계·모델·
토큰·달러·원·걸린 초까지. 다만 그것이 작품 폴더 안의 파일(`meta.json`)이라,
한 작품씩 열어 보는 것 말고는 할 수 있는 것이 없다. "오늘 얼마 나갔나",
"그래서 지금 더 만들어도 되나" 를 물을 자리가 없고, 그 물음에 답할 수 없으면
지출 상한도 걸 수 없다.

**하네스는 안 고친다.** 기록하는 함수(`record`)가 다섯 파일에 복사돼 있고
(run·storycheck·pagecheck·episodecheck·detailart), story/webtoon-harness 는
"완성본" 으로 다루기로 한 폴더다. 그런데 다섯이 다 같은 파일 하나에 쓴다 —
즉 **파일이 이미 단일 창구**다. 그래서 제품 레이어인 여기서 그 파일을 보고
올린다.

**파일이 정본이고 서버는 사본이다.** 못 올려도 만들기는 안 멈춘다. 못 올린
것은 다음 번에 다시 올라간다 — 서버가 (run_id, 몇 번째) 로 겹치는 것을
걸러 주므로 같은 것을 두 번 보내도 한 줄만 남는다. 그래서 "어디까지
보냈는지" 를 잃어도 처음부터 다시 보내면 그만이다.
"""

from __future__ import annotations

import json
import os
import threading
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
NEW_HARNESS = HERE.parent / "new_harness"

# 어디로 올리는가. 로컬에서 스프링을 띄웠을 때의 주소가 기본값이다.
BASE = os.environ.get("LORE_API_BASE", "http://127.0.0.1:8080").rstrip("/")
TOKEN = os.environ.get("LORE_WEBTOON_INTERNAL_TOKEN", "").strip()
TIMEOUT = 5

# 이미 몇 번째까지 보냈는지. 잃어도 되는 값이라 메모리에만 둔다(위 머리말 참고).
_sent: dict[str, int] = {}
_lock = threading.Lock()


def _iso(at) -> str | None:
    """meta.json 의 `at`(에포크 초)을 서버가 읽는 모양으로."""
    try:
        return datetime.fromtimestamp(float(at), tz=timezone.utc).isoformat()
    except (TypeError, ValueError):
        return None


def _calls_of(run_id: str) -> list[dict]:
    path = NEW_HARNESS / "runs" / run_id / "meta.json"
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    calls = doc.get("calls")
    return calls if isinstance(calls, list) else []


def _shape(call: dict) -> dict:
    """meta.json 의 한 줄 -> 서버가 받는 모양."""
    cost = call.get("cost") or {}
    usage = call.get("usage") or {}
    return {
        "stage": str(call.get("stage") or ""),
        "provider": str(call.get("provider") or ""),
        "model": str(call.get("model") or ""),
        "inputTokens": int(usage.get("input") or 0),
        "outputTokens": int(usage.get("output") or 0),
        "costUsd": float(cost.get("total") or 0.0),
        "costKrw": int(cost.get("total_krw") or 0),
        "costBasis": cost.get("cost_basis") or call.get("cost_basis"),
        "error": call.get("error"),
        "calledAt": _iso(call.get("at")),
    }


def push(run_id: str, on_log=None) -> int:
    """이 작품의 새 호출을 올린다. -> 올린 줄 수 (실패하면 0).

    **예외를 밖으로 안 던진다.** 값을 올리는 일 때문에 만들기가 멈추면 안
    된다 — 이미 돈을 치른 작업이다.
    """
    if not run_id or not TOKEN:
        return 0
    calls = _calls_of(run_id)
    with _lock:
        already = _sent.get(run_id, 0)
        if len(calls) <= already:
            return 0

    body = json.dumps({"runId": run_id, "calls": [_shape(c) for c in calls]},
                      ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE}/api/webtoon/internal/usage", data=body, method="POST",
        headers={"Content-Type": "application/json", "X-Lore-Internal": TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            saved = (json.loads(res.read().decode("utf-8")).get("data") or {}).get("saved", 0)
    except (urllib.error.URLError, OSError, ValueError) as exc:
        # 조용히 넘어가지 않는다 — 안 올라가고 있다는 사실은 알아야 한다.
        if on_log:
            on_log(f"[비용] 못 올렸습니다 ({type(exc).__name__}) — 다음에 다시 보냅니다")
        return 0

    with _lock:
        _sent[run_id] = len(calls)
    if saved and on_log:
        on_log(f"[비용] {saved}건 올렸습니다")
    return int(saved)


def forget(run_id: str) -> None:
    """다음 번에 처음부터 다시 보내게 한다. 겹치는 것은 서버가 거른다."""
    with _lock:
        _sent.pop(run_id, None)
