"""크레딧 잔액 + 프리토타이핑(가짜) 결제.

계정 시스템이 없어서(로그인이 없다) 브라우저가 만든 uid(localStorage,
`app.js`의 `getUid()`)로 사람을 구분한다. 잔액은 `data/credits.json` 하나에
`{uid: {"balance": N}}` 로 저장한다.

**CRUD·환불·내역 화면은 일부러 없다** — 잔액 표시 + 소진만으로 충분한
목업이다. 결제도 실제 PG 연동이 아니라, "충전하기" 를 누르고 카드사를
고르면 그 자리에서 크레딧을 지급한다(지불 의사가 있는지 확인하는 게
목적이라 진짜로 돈을 받을 필요가 없다). 카드번호 입력 화면은 아예 없다 —
실제 결제 정보를 받는 것처럼 보이면 안 되기 때문에 카드 고르기 딱 한 걸음
앞에서 멈춘다.

몇 명이 충전하기를 눌렀는지는 `data/credit_events.jsonl` 한 줄씩으로
남긴다 — 조회 화면은 없고, 필요할 때 파일을 그대로 읽는다.
"""

from __future__ import annotations

import json
import re
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
CREDITS_FILE = DATA / "credits.json"
EVENTS_FILE = DATA / "credit_events.jsonl"

# 아래 값들은 `Lore_비용모델.xlsx`(2026-08-24, 실제 API 호출 로그 기반)의
# "크레딧설계" 시트를 그대로 옮긴 것 — 1크레딧 = 이미지 1장(재생성) 원가
# 200원에 맞춘 값이다. 임의로 지어낸 숫자가 아니므로, 시트가 갱신되면 여기도
# 같이 고친다.

# 신규 가입 지급(1회) = 캐릭터 1개(3) + 1화(8) + 재생성 1회(1) = 첫 경험 1바퀴.
# "월 무료 리필 3크레딧"은 반복 지급(스케줄러)이 필요해 최소 기능 범위 밖 —
# 넣지 않는다(가입 지급만 있다).
START_BALANCE = 12

# 웹툰 1화 생성(12컷 · 이미지 4장, 프리미엄 등급) = 8크레딧. 미리보기는
# pipeline.py 의 --cuts 1-3 로 4장 중 1장만 그리므로 딱 1/4 — 옛 목업 값
# (240/60)의 비율과 같다. 화질 등급(스탠다드 0.6배)은 미실측 추정치라 아직
# 안 넣는다(시트에도 "구조만 잡아둔 상태"라고 적혀 있다).
CREDIT_FULL = 8
CREDIT_PREVIEW = 2
# 컷 모드(webtoon 레이아웃)는 컷 하나가 이미지 한 장이라 그림 호출이 3배다
# (4장→12장). 콘티(텍스트) 몫은 그대로인데 화 전체를 3배로 어림하는 건
# 근사치다 — 정확한 비례 배분은 최소 기능 범위 밖.
CREDIT_WEBTOON_MULT = 3

# 시트의 "이미지 1장 재생성"(1크레딧)·"+피드백"(2크레딧) 행은 여기 안 옮겼다
# — 장(scene) 다시 그리기는 결과 화면(app.js)과 편집실 샘플(editor.js)이
# 같은 서버 엔드포인트(/scenes/<n>/regen)를 같이 쓰는데, editor.js 쪽은
# 처음부터 "샘플" 목업이라 uid 도 안 보내고 자기만의 가짜 크레딧을 따로
# 센다. 이 상태에서 서버 쪽만 실소진을 걸면 결과 화면에서는 실제로 깎이고
# 편집실 샘플에서는 안 깎이는 게 갈라져서 헷갈린다 — 최소 기능 범위에서는
# "만들기" 한 곳만 실소진으로 걷고, 다시 그리기는 그대로 둔다.

# 충전 상품 — "D. 유료 플랜"의 충전 3종 그대로(구독 플랜은 별도 이슈 범위).
# PG 연동 없이 카드사를 고르면 그 자리에서 지급한다(프리토타이핑).
PACKAGES = [
    {"id": "c10", "label": "충전 10", "credits": 10, "price": 5_500},
    {"id": "c30", "label": "충전 30", "credits": 30, "price": 14_900,
     "badge": "가장 많이 골라요"},
    {"id": "c100", "label": "충전 100", "credits": 100, "price": 45_000},
]

_lock = threading.Lock()
_UID_RE = re.compile(r"[\w-]{1,64}")


def valid_uid(uid: str | None) -> bool:
    return bool(uid) and bool(_UID_RE.fullmatch(uid))


def package(package_id: str) -> dict | None:
    return next((p for p in PACKAGES if p["id"] == package_id), None)


def _load() -> dict:
    if not CREDITS_FILE.exists():
        return {}
    try:
        return json.loads(CREDITS_FILE.read_text("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError):
        return {}


def _save(data: dict) -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    tmp = CREDITS_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), "utf-8")
    tmp.replace(CREDITS_FILE)


def balance(uid: str) -> int:
    """잔액을 본다 — 처음 보는 uid 면 시작 잔액을 새로 만들어 준다."""
    if not valid_uid(uid):
        return 0
    with _lock:
        data = _load()
        row = data.get(uid)
        if row is None:
            data[uid] = {"balance": START_BALANCE}
            _save(data)
            return START_BALANCE
        return int(row.get("balance", 0))


def spend(uid: str, amount: int) -> tuple[bool, int]:
    """실제로 만들 때 크레딧을 뗀다. 모자라면 떼지 않고 (False, 지금 잔액)."""
    if not valid_uid(uid) or amount <= 0:
        return False, 0
    with _lock:
        data = _load()
        row = data.setdefault(uid, {"balance": START_BALANCE})
        bal = int(row.get("balance", 0))
        if bal < amount:
            return False, bal
        bal -= amount
        row["balance"] = bal
        _save(data)
        return True, bal


def charge(uid: str, package_id: str) -> tuple[dict | None, int]:
    """결제 완료 처리 — 상품의 크레딧을 그 자리에서 지급한다."""
    pkg = package(package_id)
    if not valid_uid(uid) or not pkg:
        return None, 0
    with _lock:
        data = _load()
        row = data.setdefault(uid, {"balance": START_BALANCE})
        bal = int(row.get("balance", 0)) + pkg["credits"]
        row["balance"] = bal
        _save(data)
        return pkg, bal


def log_event(event: str, uid: str, **extra) -> None:
    """충전하기 클릭 · 결제 완료 등을 한 줄씩 남긴다.

    "몇 명이 충전 버튼을 눌렀는지" 는 이 로그를 uid 기준으로 나중에 세어
    보면 된다 — 별도 집계·조회 화면은 안 만든다(내역 기능은 범위 밖)."""
    DATA.mkdir(parents=True, exist_ok=True)
    row = {"ts": time.time(), "event": event, "uid": uid, **extra}
    with _lock:
        with EVENTS_FILE.open("a", encoding="utf-8") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def creation_cost(preview: bool, layout_mode: str) -> int:
    base = CREDIT_PREVIEW if preview else CREDIT_FULL
    return base * CREDIT_WEBTOON_MULT if layout_mode == "webtoon" else base
