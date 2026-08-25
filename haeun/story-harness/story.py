#!/usr/bin/env python3
"""
텍스트 스토리 생성 파이프라인 반복 실행 하네스.

  P1 캐릭터시트 -> P2 프리미스 -> (코드 게이트) -> P3 구조검수 -> SCENE x N
  CONTROL 은 별도 1회 호출.

핵심 규칙
  - P3 는 항상 새 API 호출. P1/P2 의 대화 히스토리를 절대 넣지 않는다.
    (실제로 이 하네스는 어떤 단계에서도 대화 히스토리를 쌓지 않는다.
     매 호출이 user 메시지 1개짜리 독립 호출이다.)
  - P1/P2/SCENE/CONTROL temperature 0.9, P3 temperature 0.2
  - 모든 단계 출력은 JSON. 파싱 실패 시 1회만 재요청, 또 실패하면 실패 처리.

사용법
  python story.py --input inputs.csv --n 3          # 파이프라인만
  python story.py --input inputs.csv --n 3 --control  # 대조군만
  python story.py --input inputs.csv --n 3 --both     # 둘 다
  python story.py --build-read                      # runs/read.html 다시 만들기
  python story.py --serve                           # 블라인드 평가 서버
  python story.py --charsheet --run-id <id> --dry-run   # 시트 프롬프트만 (0원)
  python story.py --charsheet --run-id <id>         # 캐릭터 시트 1장 (OpenAI 이미지)
  python story.py --charsheet --run-id <id> --split # 4면도·표정·디테일 3장으로
  python story.py --charsheet --run-id <id> --pick  # 후보 채택 화면
  python story.py --check                           # API 호출 없이 프롬프트 변수 점검
"""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import html
import json
import os
import random
import re
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import samples

try:
    import openai
except ImportError:  # pragma: no cover
    openai = None

try:
    import anthropic
except ImportError:  # pragma: no cover
    anthropic = None

try:
    from google import genai as google_genai
    from google.genai import types as google_genai_types
except ImportError:  # pragma: no cover
    google_genai = None
    google_genai_types = None


ROOT = Path(__file__).resolve().parent
PROMPT_DIR = ROOT / "prompts"
RUNS_DIR = ROOT / "runs"


# ---------------------------------------------------------------- .env
#
# 우선순위: 명령줄 인자 > 이미 설정된 환경변수 > .env 파일 > 기본값

def load_dotenv(path: Path = None) -> dict:
    """.env 를 읽어 os.environ 에 넣는다. 이미 있는 환경변수는 덮어쓰지 않는다."""
    path = path or (ROOT / ".env")
    loaded = {}
    if not path.exists():
        return loaded
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:].strip()
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
            value = value[1:-1]
        if not value:
            # 빈 값은 내보내지 않는다. SDK 가 OPENAI_BASE_URL="" 를 읽으면
            # 프로토콜 없는 URL 로 요청해서 Connection error 가 난다.
            continue
        loaded[key] = value
        os.environ.setdefault(key, value)
    return loaded


load_dotenv()


def env(key: str, default=None):
    value = os.environ.get(key)
    return value if value not in (None, "") else default


def env_float(key: str, default: float) -> float:
    try:
        return float(env(key, default))
    except (TypeError, ValueError):
        return default


def env_int(key: str, default: int) -> int:
    try:
        return int(env(key, default))
    except (TypeError, ValueError):
        return default


def env_bool(key: str, default: bool = False) -> bool:
    value = env(key)
    if value is None:
        return default
    return str(value).strip().lower() in ("1", "true", "yes", "on", "y")


# ---------------------------------------------------------------- 프로바이더
#
# 기본은 Gemini. .env 의 PROVIDER 로 바꾸고, --provider 로 한 번만 덮어쓸 수도 있다.

DEFAULT_PROVIDER = env("PROVIDER", "gemini").strip().lower()

PROVIDERS = {
    "gemini": {
        "key_var": "GEMINI_API_KEY",
        "model_var": "GEMINI_MODEL",
        "judge_var": "GEMINI_JUDGE_MODEL",
        "base_url_var": "GEMINI_BASE_URL",
        "default_model": "gemini-3.5-flash",
        # Gemini 는 추론 모델도 temperature 를 받는다.
        "no_temperature": (),
    },
    "openai": {
        "key_var": "OPENAI_API_KEY",
        "model_var": "OPENAI_MODEL",
        "judge_var": "OPENAI_JUDGE_MODEL",
        "base_url_var": "OPENAI_BASE_URL",
        "default_model": "gpt-4.1",
        # 추론 계열은 temperature 를 받지 않는다 (기본값 1 고정).
        "no_temperature": ("o1", "o3", "o4", "gpt-5"),
    },
    "anthropic": {
        "key_var": "ANTHROPIC_API_KEY",
        "model_var": "ANTHROPIC_MODEL",
        "judge_var": "ANTHROPIC_JUDGE_MODEL",
        "base_url_var": "ANTHROPIC_BASE_URL",
        "default_model": "claude-opus-4-6",
        # Opus 5 / 4.8 / 4.7, Sonnet 5, Fable 5 는 temperature 가 제거되어 400 이 난다.
        "no_temperature": ("claude-opus-5", "claude-opus-4-8", "claude-opus-4-7",
                           "claude-sonnet-5", "claude-fable-5", "claude-mythos-5"),
    },
}


def provider_conf(provider: str) -> dict:
    conf = PROVIDERS.get((provider or "").strip().lower())
    if conf is None:
        raise SystemExit(
            f"알 수 없는 PROVIDER '{provider}'. {sorted(PROVIDERS)} 중 하나여야 합니다.")
    return conf


def default_model_for(provider: str) -> str:
    conf = provider_conf(provider)
    return env(conf["model_var"], conf["default_model"])


def default_judge_model_for(provider: str) -> str:
    conf = provider_conf(provider)
    return env(conf["judge_var"]) or default_model_for(provider)


# 요구사항: 창작 0.9 / 심사 0.2. .env 로 조정 가능하다.
TEMP_CREATIVE = env_float("TEMP_CREATIVE", 0.9)
TEMP_JUDGE = env_float("TEMP_JUDGE", 0.2)
DEFAULT_MAX_TOKENS = env_int("MAX_TOKENS", 8000)

# 온도를 못 받는 모델에도 그냥 보내고 싶을 때
FORCE_TEMPERATURE = env_bool("FORCE_TEMPERATURE", False)

# OpenAI 의 JSON 모드. 프롬프트에 'json' 이 들어 있을 때만 켠다 (없으면 API 가 400 을 낸다).
OPENAI_JSON_MODE = env_bool("OPENAI_JSON_MODE", True)

# Gemini 의 JSON 모드(response_mime_type). OpenAI 와 달리 프롬프트 조건은 없지만
# 같은 규칙으로 켜서 프로바이더를 바꿔도 동작이 달라지지 않게 한다.
GEMINI_JSON_MODE = env_bool("GEMINI_JSON_MODE", True)

# Gemini 는 기본으로 '사고'를 하고, 그 토큰이 max_output_tokens 를 갉아먹어 본문이
# 빈 채로 끊길 수 있다. 그래서 기본은 off.
#
# 문제는 세대마다 파라미터 이름이 다르다는 것이다:
#   Gemini 3.x → thinking_level ("minimal" "low" "medium" "high")
#   Gemini 2.x → thinking_budget (토큰 수 정수)
# 서로의 파라미터를 보내면 400 INVALID_ARGUMENT 가 난다. 그래서 여기서는
# off | auto | 레벨이름 | 정수 를 받아 모델 세대에 맞는 쪽으로 번역한다.
GEMINI_THINKING = env("GEMINI_THINKING", "off").strip().lower()

GEMINI_THINKING_LEVELS = ("minimal", "low", "medium", "high")

# 단계별 모델 덮어쓰기: .env 에 MODEL_P3=... 처럼 적으면 그 단계만 다른 모델로 돈다.
STAGE_MODEL_PREFIX = "MODEL_"

# ---------------------------------------------------------------- 단가표
#
# 단가는 코드가 아니라 prices.json 에 있다. 요금은 코드와 다른 속도로 바뀌고,
# 모델을 바꿀 때마다 파이썬을 고치게 하면 결국 아무도 안 고친다. 그러면 비용
# 표시가 조용히 거짓말을 한다 — 이미지 단가에서 쓰던 원칙과 같다.
#
# **모르는 단가는 0 으로 세지 않는다.** 0 은 "공짜"라는 뜻이라 거짓말이고,
# 합계를 조용히 낮춘다. 대신 그 모델을 unpriced 로 남기고 합계에 complete=false
# 를 붙인다. 숫자를 안 보여주는 것보다 나쁜 것은 틀린 숫자를 보여주는 것이다.
PRICES_FILE = env("PRICES_FILE", "prices.json")
_PRICES_CACHE = {}

# 날짜 스냅샷 접미사. gpt-4.1-2025-04-14 나 claude-haiku-4-5-20251001 처럼
# 같은 모델의 날짜 고정본은 기본 이름의 단가를 그대로 쓴다.
# 접미사가 **날짜일 때만** 인정한다 — 그냥 앞자리로 맞추면 gpt-4.1-mini 가
# gpt-4.1 단가($2)를 물려받아 5배 비싸게 계산된다.
_DATE_SUFFIX_RE = re.compile(r"^-(\d{8}|\d{4}-\d{2}-\d{2})$")

COST_FIELDS = ("input", "output", "cache_read", "cache_write")


def load_prices(path: str = None) -> dict:
    """prices.json 을 읽는다. 없거나 깨져 있으면 빈 표 — 게이트가 아니라 기록이다.

    단가를 못 읽는 것이 실행을 막을 이유는 없다. 토큰은 그대로 세고, 비용만
    '모른다'로 남는다.
    """
    key = str(path or PRICES_FILE)
    if key in _PRICES_CACHE:
        return _PRICES_CACHE[key]
    p = Path(key)
    if not p.is_absolute():
        p = Path(__file__).resolve().parent / key
    table = {}
    try:
        if p.exists():
            table = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        warn(f"단가표를 읽지 못했습니다 ({p.name}: {e}). 비용은 기록되지 않습니다.")
        table = {}
    _PRICES_CACHE[key] = table
    return table


def price_for(model: str, prices: dict = None) -> dict:
    """모델의 단가(100만 토큰당 USD). 모르면 None."""
    table = (prices if prices is not None else load_prices()).get("models") or {}
    name = str(model or "").strip()
    if not name:
        return None
    if name in table:
        return table[name]
    # 날짜 고정본 → 기본 이름. 가장 긴 것부터 본다.
    for base in sorted(table, key=len, reverse=True):
        if name.startswith(base) and _DATE_SUFFIX_RE.match(name[len(base):]):
            return table[base]
    return None


def cost_text(usd, note: str = "") -> str:
    """비용 한 조각. 부분 합계면 그렇다고 말한다.

    "$0.14" 와 "$0.14 (단가 없음: x)" 는 다른 뜻이다. 뒤엣것을 앞엣것처럼
    보여주면 합계를 믿고 예산을 잡았다가 틀린다.
    """
    if usd is None:
        return f"비용 미상 ({note})" if note else "비용 미상"
    return f"${usd:.4f}" + (f" (+{note})" if note else "")


def cost_of(model: str, tokens: dict, prices: dict = None) -> dict:
    """토큰 dict -> 항목별 USD. 단가를 모르면 None (0 이 아니다)."""
    rate = price_for(model, prices)
    if not rate:
        return None
    out = {}
    for f in COST_FIELDS:
        per_mtok = rate.get(f)
        n = int(tokens.get(f, 0) or 0)
        out[f] = round(n * float(per_mtok or 0.0) / 1_000_000, 8)
    out["total"] = round(sum(out[f] for f in COST_FIELDS), 8)
    return out

DEFAULT_MODEL = default_model_for(DEFAULT_PROVIDER)

STATUS_OK = "ok"
STATUS_HUMAN = "사람확인필요"
STATUS_PARSE_FAIL = "실패(파싱)"
STATUS_API_FAIL = "실패(API)"

REPARSE_NOTE = (
    "\n\n---\n"
    "직전 응답이 JSON 으로 파싱되지 않았다. "
    "설명, 인사말, 코드펜스 없이 JSON 객체 하나만 출력하라. "
    "여는 중괄호로 시작해서 닫는 중괄호로 끝나야 한다."
)

# P3 체크 중 "경고만" 으로 다룰 항목. 여기 든 항목은 no 여도 재생성을 부르지 않고
# 판정(맛있음/보통/별로)에도 반영되지 않는다. 로그·summary.csv 에만 남는다.
#
# sniping_verified 가 기본 경고인 이유:
#   저격 성립 여부의 구조적 부분(반의쌍 단어가 실제로 있는가)은 gate_p1 이
#   코드로 판정한다. P3 에 남은 것은 "그 두 단어가 반의어인가" 하나뿐이고,
#   이는 모델 간 편차가 큰 어휘 판단이라 게이트로 쓰기에 부적합하다.
#   .env 에 P3_ADVISORY_CHECKS= (빈 값) 을 넣으면 다시 게이트로 돌아간다.
P3_ADVISORY_CHECKS = {
    c.strip() for c in env("P3_ADVISORY_CHECKS", "sniping_verified").split(",")
    if c.strip()
}

# 장면 점검 항목 중, 재시도로도 안 풀리면 **사람이 봐야 하는** 것.
# 나머지는 메모로만 남기고 넘어간다.
#
# ── '설정 증발'을 여기서 뺀 이유 ─────────────────────────────────────────
# 이 항목은 "작가가 준 한 줄의 낱말 중 절반이 장면에 남았는가" 를 센다.
# 그 셈이 **입력이 길수록 불리해진다.** 작가가 세계관을 3000자로 정성껏 적으면
# 낱말이 676개가 되고, 그 절반인 338개가 장면 셋 안에 그대로 들어가야 통과다 —
# 그건 웹툰이 아니라 설정집 낭독이다. 실제로 그렇게 막혔다(3368자 입력).
#
# 즉 **잘 쓸수록 떨어지는 검사**였다. 이야기를 잘 만든다는 것은 준 것을 그대로
# 옮기는 일이 아니라 골라서 장면으로 바꾸는 일인데, 낱말 보존율은 정확히 그
# 반대를 요구한다. 창작물의 품질 검사가 아니라 복사율 검사다.
#
# 이 항목이 생긴 계기였던 사고 둘("여성"이 사라진 것 · 이름이 바뀐 것)은
# 지금은 각각 gate_gender · gate_name 이 **정확히** 막는다. 작가가 명시한
# 사실은 그 게이트들이 지키고, 여기서는 낱말을 세지 않는다.
#
# 검출은 그대로 한다 — 메모로 남아서 확인 화면에 뜬다. 다만 그것 하나로
# 사람을 세우지는 않는다.
SCENE_BLOCKING_CHECKS = {"출처 단일"}

# '설정 증발' 이 볼 낱말 수. 작가가 먼저 적은 것이 가장 원한 것이라 앞에서
# 끊는다 — 뒤에 붙는 세계관 설명까지 세면 길게 쓴 사람만 불리해진다.
IDEA_KEEP_TOKENS = 12

SOURCE_ALIASES = {
    "rule": "rule", "규칙": "rule", "룰": "rule",
    "cost": "cost", "대가": "cost", "비용": "cost", "희생": "cost",
    "irony": "irony", "아이러니": "irony", "역설": "irony", "반어": "irony",
}

SUMMARY_COLUMNS = [
    "run_id",            # 실행 ID
    "condition",         # pipeline | control
    "character",
    "genre",
    "iteration",         # --n 반복 회차
    "p3_verdict",        # 맛있음 | 보통 | 별로 (문서 10-3 판정 도출 규칙표)
    "p3_pass_count",     # P3 통과 항목 수
    "p3_failed_items",   # 탈락 항목명 (; 구분)
    "p3_warned_items",   # 경고 항목명 — no 지만 게이트에서 제외된 것 (P3_ADVISORY_CHECKS)
    "p3_skipped_items",  # 해당 없음 항목명 — 1인 완결형 선언 등
    "capture_test",
    "first_question",
    "cliche_check_present",  # 클리셰 감지 유무 (yes/no)
    "scene_check_failed",    # 장면 점검 8항목 중 걸린 것 (; 구분)
    "regen_count",       # 재생성 횟수
    "elapsed_sec",       # 총 소요시간
    "total_tokens",      # 총 토큰
    "cost_usd",          # 총 비용 — 단가를 모르는 모델이 섞이면 빈 칸
    "cost_note",         # 부분 합계인 이유 (단가를 모르는 모델 이름)
    "status",
]

BLIND_COLUMNS = [
    "timestamp", "run_id", "condition", "character", "genre",
    "display_order", "next_scene_curious", "note",
]


# ---------------------------------------------------------------- utilities

def now_stamp() -> str:
    return datetime.now().strftime("%Y%m%dT%H%M%S")


def sha256_of(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def _safe(msg: str, stream) -> str:
    """콘솔이 못 그리는 글자를 지운다.

    한국어 윈도우 콘솔은 cp949 다. 게이트 실패 문구의 줄표(—) 하나 때문에
    실행 전체가 UnicodeEncodeError 로 죽는 일이 있었다. 진단 문구를 못 그린다고
    파이프라인이 멈출 이유는 없다 — 파일에는 UTF-8 로 온전히 남는다.
    """
    enc = getattr(stream, "encoding", None) or "utf-8"
    try:
        msg.encode(enc)
        return msg
    except (UnicodeEncodeError, LookupError):
        return msg.encode(enc, errors="replace").decode(enc, errors="replace")


def log(msg: str) -> None:
    print(_safe(str(msg), sys.stdout), flush=True)


def warn(msg: str) -> None:
    print(f"  !! {_safe(str(msg), sys.stderr)}", file=sys.stderr, flush=True)


VAR_RE = re.compile(r"\{([A-Za-z_][A-Za-z0-9_]*)\}")


def render(template: str, variables: dict) -> str:
    """{알려진변수} 만 치환한다. 나머지 중괄호는 그대로 둔다.

    프롬프트 안에 JSON 예시가 들어있어도 str.format 처럼 깨지지 않는다.
    """
    def sub(m: re.Match) -> str:
        key = m.group(1)
        if key in variables:
            return str(variables[key])
        return m.group(0)
    return VAR_RE.sub(sub, template)


def declared_vars(template: str) -> set:
    return set(VAR_RE.findall(template))


def extract_json(text: str):
    """모델 응답에서 JSON 객체를 뽑는다. 실패하면 None."""
    if not text:
        return None
    candidate = text.strip()

    fence = re.search(r"```(?:json|JSON)?\s*(.*?)```", candidate, re.S)
    if fence:
        candidate = fence.group(1).strip()

    try:
        obj = json.loads(candidate)
        if isinstance(obj, dict):
            return obj
    except Exception:
        pass

    # 앞뒤에 잡소리가 붙은 경우: 첫 { 부터 짝이 맞는 } 까지 스캔
    start = candidate.find("{")
    if start < 0:
        return None
    depth = 0
    in_str = False
    escaped = False
    for i in range(start, len(candidate)):
        ch = candidate[i]
        if in_str:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                chunk = candidate[start:i + 1]
                try:
                    obj = json.loads(chunk)
                    return obj if isinstance(obj, dict) else None
                except Exception:
                    return None
    return None


def supports_temperature(model: str, provider: str = None) -> bool:
    if FORCE_TEMPERATURE:
        return True
    conf = provider_conf(provider or DEFAULT_PROVIDER)
    name = (model or "").strip().lower()
    return not any(name.startswith(p) for p in conf["no_temperature"])


def feedback_block(text: str) -> str:
    if not text:
        return ""
    return (
        "[재생성 지시]\n"
        "직전 산출물은 아래 이유로 반려되었다. 같은 실수를 반복하지 마라.\n"
        f"{text}\n"
        "[/재생성 지시]"
    )


def author_block(text: str) -> str:
    """작가가 직접 적은 요청. 게이트가 만든 재생성 지시와는 무게가 다르다.

    게이트 지시는 "형식이 틀렸다"이고 이것은 "내가 원한 게 이게 아니다"다.
    앞엣것은 고치면 통과하지만 뒤엣것은 통과 기준이 사람에게 있다 — 그래서
    블록을 따로 두고, 먼저 읽히게 앞에 놓는다.
    """
    if not text:
        return ""
    return (
        "[작가 요청]\n"
        "작가가 직전 결과를 보고 아래를 고쳐 달라고 했다. 반드시 반영하라.\n"
        f"{text}\n"
        "[/작가 요청]"
    )


def feedback_slot(author_note: str, retry: str) -> str:
    """프롬프트의 {retry_feedback} 자리에 들어갈 글.

    작가 요청이 없으면(기본) 예전과 똑같이 feedback_block(retry) 하나만 나간다 —
    --author-note 를 안 준 실행은 프롬프트가 한 글자도 안 바뀐다.
    """
    return "\n\n".join(x for x in (author_block(author_note), feedback_block(retry)) if x)


# ---------------------------------------------------------------- prompts

@dataclass
class PromptSet:
    texts: dict = field(default_factory=dict)
    hashes: dict = field(default_factory=dict)
    banned_connectors: list = field(default_factory=list)

    @property
    def short_hashes(self) -> dict:
        return {k: v[:12] for k, v in self.hashes.items()}


PROMPT_CONTRACT = {
    "look": {"photo_note"},
    "seed": {"character_material", "genre_input", "world_input", "story_input",
             "genre_presets", "world_presets"},
    "p1": {"genre", "one_line_intro", "character_input", "card_json",
           "sample_cards", "retry_feedback", "world",
           "genre_template", "variation_axes", "user_memory"},
    "p2": {"genre", "character_sheet", "retry_feedback", "world",
           "genre_template", "story_template", "story_structure", "user_memory"},
    "p3": {"character_sheet", "premise_json", "sample_intros"},
    "scene": {"scene_count", "idea", "character_sheet_json", "premise_json",
              "fix_directive", "user_memory"},
    "control": {"genre", "character_input", "scene_count"},
    "lead_appearance": {"engine_card", "name", "gender", "appearance", "outfit",
                        "personality", "protagonist_appearance", "retry_feedback"},
}

# P3 에 절대 넣지 않는 변수 (자기채점 방지 · 히스토리 유입 방지).
# 심사자는 "누가 왜 만들었는지"를 몰라야 하므로 원본 입력과 재생성 지시를 차단한다.
P3_FORBIDDEN_VARS = {
    "character", "one_line", "character_input", "one_line_intro", "idea",
    "retry_feedback", "fix_directive", "previous_scenes", "user_memory",
}


def load_prompts(strict: bool = False, contract: dict = None) -> PromptSet:
    contract = contract or PROMPT_CONTRACT
    ps = PromptSet()
    missing = []
    for name in contract:
        path = PROMPT_DIR / f"{name}.txt"
        if not path.exists():
            missing.append(str(path))
            continue
        text = path.read_text(encoding="utf-8")
        ps.texts[name] = text
        ps.hashes[name] = sha256_of(text)
    if missing:
        raise SystemExit("프롬프트 파일이 없습니다:\n  " + "\n  ".join(missing))

    banned_path = PROMPT_DIR / "banned_connectors.txt"
    if banned_path.exists():
        for line in banned_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                ps.banned_connectors.append(line)
        ps.hashes["banned_connectors"] = sha256_of(
            banned_path.read_text(encoding="utf-8"))

    problems = check_prompt_vars(ps, contract)
    for p in problems:
        warn(p)
    if strict and problems:
        raise SystemExit("프롬프트 변수 점검 실패.")
    return ps


def check_prompt_vars(ps: PromptSet, contract: dict = None) -> list:
    contract = contract or PROMPT_CONTRACT
    problems = []
    for name, allowed in contract.items():
        found = declared_vars(ps.texts[name])
        unknown = found - allowed
        unused = allowed - found
        if unknown:
            problems.append(
                f"{name}.txt: 코드가 모르는 변수 {sorted(unknown)} — 치환되지 않고 그대로 남습니다.")
        if unused:
            problems.append(
                f"{name}.txt: 미사용 변수 {sorted(unused)} — 의도한 것인지 확인하세요.")
        if name in ("p3", "w6"):
            leaked = found & P3_FORBIDDEN_VARS
            if leaked:
                problems.append(
                    f"p3.txt: {sorted(leaked)} 는 P3 에 주입되지 않습니다(자기채점 방지). 제거하세요.")
    return problems


# ---------------------------------------------------------------- API

class ParseFailure(Exception):
    def __init__(self, stage, raw):
        super().__init__(f"{stage} JSON 파싱 실패")
        self.stage = stage
        self.raw = raw


class ApiFailure(Exception):
    def __init__(self, stage, err):
        super().__init__(f"{stage} API 실패: {err}")
        self.stage = stage


@dataclass
class Usage:
    """토큰과 비용을 **호출 단위로** 쌓는다.

    합계만 남기면 나중에 "왜 이렇게 비쌌지" 에 답할 수 없다. 한 실행 안에서도
    단계마다 모델이 다를 수 있고(.env 의 MODEL_P3 등, 심사 모델 분리), 재시도가
    몇 번 돌았는지에 따라 비용이 배로 뛴다. 그래서 records 에 **어떤 단계의
    어떤 요청이 어떤 모델로 돌아 얼마가 들었는지**를 한 줄씩 남기고, 합계는
    거기서 유도한다.

    모델이 섞이면 합계 토큰만으로는 비용을 계산할 수 없다 — 단가가 다르기
    때문이다. by_model 이 그래서 있다.
    """

    input_tokens: int = 0
    output_tokens: int = 0
    cache_read: int = 0
    cache_write: int = 0
    calls: int = 0
    by_model: dict = field(default_factory=dict)
    records: list = field(default_factory=list)

    def add(self, u: dict, model: str = "", stage: str = "",
            seconds: float = None, **extra) -> dict:
        """프로바이더별 usage 를 정규화한 dict 를 받는다. 남긴 기록을 돌려준다."""
        u = u or {}
        tok = {f: int(u.get(f, 0) or 0) for f in COST_FIELDS}
        tok["total"] = sum(tok[f] for f in COST_FIELDS)

        self.calls += 1
        self.input_tokens += tok["input"]
        self.output_tokens += tok["output"]
        self.cache_read += tok["cache_read"]
        self.cache_write += tok["cache_write"]

        name = str(model or "").strip() or "(모델 미상)"
        slot = self.by_model.setdefault(
            name, {"calls": 0, **{f: 0 for f in COST_FIELDS}, "total": 0})
        slot["calls"] += 1
        for f in COST_FIELDS:
            slot[f] += tok[f]
        slot["total"] += tok["total"]

        cost = cost_of(name, tok)
        rec = {
            "at": datetime.now().isoformat(timespec="seconds"),
            "stage": stage,
            "model": name,
            "tokens": tok,
            "cost_usd": cost,
        }
        if cost is None:
            rec["cost_note"] = f"{PRICES_FILE} 에 '{name}' 단가가 없습니다"
        if seconds is not None:
            rec["seconds"] = round(seconds, 1)
        rec.update({k: v for k, v in extra.items() if v is not None})
        self.records.append(rec)
        return rec

    @property
    def total(self) -> int:
        return self.input_tokens + self.output_tokens + self.cache_read + self.cache_write

    def cost(self) -> dict:
        """모델별 비용과 합계. 단가를 모르는 모델은 합계에서 빠지고 이름이 남는다.

        전부 모르면 usd 는 None 이다 — 0.0 은 "공짜"로 읽히기 때문이다.
        """
        by_model, unpriced, usd = {}, [], 0.0
        for name, tok in sorted(self.by_model.items()):
            c = cost_of(name, tok)
            if c is None:
                unpriced.append(name)
                by_model[name] = {"cost_usd": None, "tokens": tok["total"]}
                continue
            usd += c["total"]
            by_model[name] = {"cost_usd": c, "tokens": tok["total"]}
        priced = [n for n in by_model if n not in unpriced]
        prices = load_prices()
        return {
            "usd": round(usd, 6) if priced else None,
            "complete": not unpriced,
            "by_model": by_model,
            "unpriced_models": unpriced,
            "rates_as_of": prices.get("_as_of"),
            "rates_file": PRICES_FILE,
        }

    def as_dict(self) -> dict:
        return {
            "calls": self.calls,
            "input_tokens": self.input_tokens,
            "output_tokens": self.output_tokens,
            "cache_read_input_tokens": self.cache_read,
            "cache_creation_input_tokens": self.cache_write,
            "total_tokens": self.total,
            "by_model": self.by_model,
            "cost": self.cost(),
        }

    def cost_usd(self):
        """요약 표에 쓸 숫자 하나. 모르면 None."""
        return self.cost()["usd"]

    def cost_note(self) -> str:
        """합계가 불완전한 이유. 완전하면 빈 문자열.

        합계 옆에 이게 없으면 부분 합계가 전체 합계인 척한다.
        """
        c = self.cost()
        if c["complete"]:
            return ""
        return "단가 없음: " + ", ".join(c["unpriced_models"])

    def cost_line(self) -> str:
        """로그 한 줄. 모르는 단가를 숨기지 않는다."""
        return f"{self.total:,}토큰 · {cost_text(self.cost_usd(), self.cost_note())}"

    def write_calls(self, path: Path) -> None:
        """호출 원장. 한 줄에 한 호출 — 어떤 단계·모델·토큰·비용인지."""
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            for rec in self.records:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    def annotate(self, start: int, **fields) -> None:
        """start 번째 이후의 기록에 라벨을 붙인다.

        JSON 파싱 실패로 한 단계가 2번 호출될 수 있어서, 마지막 기록 하나가
        아니라 그 단계가 만든 **구간 전체**에 붙인다.
        """
        for rec in self.records[start:]:
            rec.update({k: v for k, v in fields.items() if v is not None})


# ---------------------------------------------------------------- 이미지 입력
#
# 캐릭터 사진을 읽는 단계(LOOK)에서만 쓴다. 나머지 단계는 예전 그대로 텍스트다.
# 사진은 프로바이더마다 담는 그릇이 다르지만(OpenAI 는 data URL, Anthropic 은
# base64 블록, Gemini 는 bytes Part) 파이프라인이 볼 것은 하나다: "이 프롬프트에
# 이 그림들을 붙여라". 그래서 여기서 한 번 읽고 각 백엔드가 자기 그릇에 담는다.

IMAGE_MIME = {
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".webp": "image/webp", ".gif": "image/gif",
}
# 요청 자체가 거부되는 크기(프로바이더별 5~20MB)에 걸리기 전에 우리가 먼저 막는다.
# API 400 보다 "이 파일이 큽니다" 가 고치기 쉽다.
MAX_IMAGE_MB = env_float("MAX_IMAGE_MB", 5.0)


def load_image(path) -> dict:
    """사진 한 장을 읽는다. {"mime", "data", "b64", "name"}."""
    p = Path(path)
    if not p.exists():
        raise SystemExit(f"사진 파일이 없습니다: {p}")
    mime = IMAGE_MIME.get(p.suffix.lower())
    if not mime:
        raise SystemExit(
            f"지원하지 않는 이미지 형식입니다: {p.name} "
            f"(가능: {', '.join(sorted(IMAGE_MIME))})")
    raw = p.read_bytes()
    mb = len(raw) / (1024 * 1024)
    if mb > MAX_IMAGE_MB:
        raise SystemExit(
            f"사진이 너무 큽니다: {p.name} ({mb:.1f}MB > {MAX_IMAGE_MB}MB). "
            "줄여서 다시 넣거나 .env 의 MAX_IMAGE_MB 를 올리세요.")
    return {"mime": mime, "data": raw,
            "b64": base64.b64encode(raw).decode("ascii"), "name": p.name}


# ---------------------------------------------------------------- 백엔드
#
# 프로바이더가 달라도 파이프라인 규칙은 같다:
#   user 메시지 1개짜리 단발 호출 · 히스토리 없음 · JSON 응답 · 온도 통제.

class Backend:
    name = "?"
    is_mock = False

    def supports_temperature(self, model: str) -> bool:
        return supports_temperature(model, self.name)

    def complete(self, model: str, prompt: str, temperature, max_tokens: int,
                 images: list = None):
        """(text, usage_dict, stop_reason) 을 돌려준다. images 는 load_image 결과."""
        raise NotImplementedError


class OpenAIBackend(Backend):
    name = "openai"

    def __init__(self, api_key: str = None, base_url: str = None, max_retries: int = 3):
        if openai is None:
            raise SystemExit("openai 패키지가 없습니다.  pip install openai")
        kwargs = {"max_retries": max_retries}
        if api_key:
            kwargs["api_key"] = api_key
        if base_url:
            kwargs["base_url"] = base_url
        self.client = openai.OpenAI(**kwargs)

    def complete(self, model, prompt, temperature, max_tokens, images=None):
        if images:
            content = [{"type": "text", "text": prompt}]
            for im in images:
                content.append({"type": "image_url", "image_url": {
                    "url": f"data:{im['mime']};base64,{im['b64']}"}})
        else:
            content = prompt
        kwargs = dict(
            model=model,
            messages=[{"role": "user", "content": content}],
            max_completion_tokens=max_tokens,
        )
        if temperature is not None:
            kwargs["temperature"] = temperature
        # 프롬프트에 'json' 이 없으면 JSON 모드를 켤 수 없다 (API 가 거부한다).
        if OPENAI_JSON_MODE and "json" in prompt.lower():
            kwargs["response_format"] = {"type": "json_object"}

        resp = self.client.chat.completions.create(**kwargs)
        choice = resp.choices[0]
        text = choice.message.content or ""
        u = resp.usage
        details = getattr(u, "prompt_tokens_details", None)
        usage = {
            "input": getattr(u, "prompt_tokens", 0) or 0,
            "output": getattr(u, "completion_tokens", 0) or 0,
            "cache_read": getattr(details, "cached_tokens", 0) or 0 if details else 0,
            "cache_write": 0,
        }
        stop = "max_tokens" if choice.finish_reason == "length" else choice.finish_reason
        return text, usage, stop


class AnthropicBackend(Backend):
    name = "anthropic"

    def __init__(self, api_key: str = None, base_url: str = None, max_retries: int = 3):
        if anthropic is None:
            raise SystemExit("anthropic 패키지가 없습니다.  pip install anthropic")
        kwargs = {"max_retries": max_retries}
        if api_key:
            kwargs["api_key"] = api_key
        if base_url:
            kwargs["base_url"] = base_url
        self.client = anthropic.Anthropic(**kwargs)

    def complete(self, model, prompt, temperature, max_tokens, images=None):
        # 그림을 글보다 먼저 넣는다 — Anthropic 이 권하는 순서다.
        content = [{"type": "image", "source": {
            "type": "base64", "media_type": im["mime"], "data": im["b64"]}}
            for im in (images or [])]
        content.append({"type": "text", "text": prompt})
        kwargs = dict(
            model=model,
            max_tokens=max_tokens,
            messages=[{"role": "user", "content": content}],
        )
        if temperature is not None:
            kwargs["temperature"] = temperature

        resp = self.client.messages.create(**kwargs)
        text = "".join(b.text for b in resp.content if b.type == "text")
        u = resp.usage
        usage = {
            "input": getattr(u, "input_tokens", 0) or 0,
            "output": getattr(u, "output_tokens", 0) or 0,
            "cache_read": getattr(u, "cache_read_input_tokens", 0) or 0,
            "cache_write": getattr(u, "cache_creation_input_tokens", 0) or 0,
        }
        return text, usage, resp.stop_reason


def gemini_uses_thinking_level(model: str) -> bool:
    """Gemini 3 세대인가. 3.x 는 thinking_level, 2.x 는 thinking_budget 을 받는다."""
    name = (model or "").strip().lower().replace("models/", "")
    if name.startswith("gemini-3"):
        return True
    # gemini-flash-latest / gemini-pro-latest 는 현재 3 세대를 가리킨다.
    return name.endswith("-latest")


def gemini_thinking_config(model: str):
    """GEMINI_THINKING 을 모델 세대에 맞는 ThinkingConfig 로 번역한다. None 이면 안 보낸다."""
    value = GEMINI_THINKING
    if value in ("", "auto", "default"):
        return None                      # 모델 기본값에 맡긴다
    level_model = gemini_uses_thinking_level(model)

    if value == "off":
        return google_genai_types.ThinkingConfig(
            thinking_level="minimal") if level_model else \
            google_genai_types.ThinkingConfig(thinking_budget=0)

    if value in GEMINI_THINKING_LEVELS:
        if level_model:
            return google_genai_types.ThinkingConfig(thinking_level=value)
        # 2.x 에 레벨 이름을 보내면 400 이다. 끄기만 안전하게 옮긴다.
        return google_genai_types.ThinkingConfig(thinking_budget=0) \
            if value == "minimal" else None

    try:
        budget = int(value)
    except ValueError:
        warn(f"GEMINI_THINKING='{GEMINI_THINKING}' 를 해석할 수 없습니다. 모델 기본값으로 둡니다.")
        return None
    if level_model:
        # 3.x 는 토큰 예산을 받지 않는다. 0 만 '끄기' 로 옮기고 나머지는 기본값.
        return google_genai_types.ThinkingConfig(thinking_level="minimal") if budget == 0 else None
    return google_genai_types.ThinkingConfig(thinking_budget=budget)


class GeminiBackend(Backend):
    name = "gemini"

    # SDK 가 아니라 여기서 재시도한다 (google-genai 는 max_retries 인자가 없다).
    RETRY_SLEEP = 2.0

    def __init__(self, api_key: str = None, base_url: str = None, max_retries: int = 3):
        if google_genai is None:
            raise SystemExit("google-genai 패키지가 없습니다.  pip install google-genai")
        kwargs = {}
        if api_key:
            kwargs["api_key"] = api_key
        if base_url:
            kwargs["http_options"] = {"base_url": base_url}
        self.client = google_genai.Client(**kwargs)
        self.max_retries = max(1, max_retries)

    def _config(self, model, prompt, temperature, max_tokens):
        cfg = {"max_output_tokens": max_tokens}
        if temperature is not None:
            cfg["temperature"] = temperature
        if GEMINI_JSON_MODE and "json" in prompt.lower():
            cfg["response_mime_type"] = "application/json"
        thinking = gemini_thinking_config(model)
        if thinking is not None:
            cfg["thinking_config"] = thinking
        return google_genai_types.GenerateContentConfig(**cfg)

    def complete(self, model, prompt, temperature, max_tokens, images=None):
        config = self._config(model, prompt, temperature, max_tokens)
        if images:
            contents = [google_genai_types.Part.from_bytes(
                data=im["data"], mime_type=im["mime"]) for im in images]
            contents.append(prompt)
        else:
            contents = prompt
        last_err = None
        for attempt in range(self.max_retries):
            try:
                resp = self.client.models.generate_content(
                    model=model, contents=contents, config=config)
                break
            except Exception as e:  # 429/5xx 만 다시, 나머지는 바로 올린다
                if attempt == self.max_retries - 1 or not _gemini_retryable(e):
                    raise
                last_err = e
                time.sleep(self.RETRY_SLEEP * (2 ** attempt))
        else:  # pragma: no cover
            raise last_err

        text = resp.text or ""
        u = getattr(resp, "usage_metadata", None)
        usage = {
            "input": getattr(u, "prompt_token_count", 0) or 0,
            "output": getattr(u, "candidates_token_count", 0) or 0,
            "cache_read": getattr(u, "cached_content_token_count", 0) or 0,
            "cache_write": 0,
        }
        # 사고 토큰도 청구되므로 output 에 함께 센다.
        usage["output"] += getattr(u, "thoughts_token_count", 0) or 0

        finish = ""
        cands = getattr(resp, "candidates", None) or []
        if cands:
            fr = getattr(cands[0], "finish_reason", None)
            finish = (getattr(fr, "name", None) or str(fr or "")).lower()
        stop = "max_tokens" if finish == "max_tokens" else (finish or "stop")
        return text, usage, stop


def _gemini_retryable(err: Exception) -> bool:
    code = getattr(err, "code", None) or getattr(err, "status_code", None)
    if isinstance(code, int):
        return code == 429 or code >= 500
    return any(s in str(err) for s in ("429", "500", "502", "503", "504", "RESOURCE_EXHAUSTED"))


BACKENDS = {
    "gemini": GeminiBackend,
    "openai": OpenAIBackend,
    "anthropic": AnthropicBackend,
}


def make_backend(provider: str, max_retries: int = 3) -> Backend:
    conf = provider_conf(provider)
    key = env(conf["key_var"])
    base_url = env(conf["base_url_var"])
    cls = BACKENDS[(provider or "").strip().lower()]
    return cls(api_key=key, base_url=base_url, max_retries=max_retries)


class Caller:
    """단발 호출만 한다. 대화 히스토리를 보관하지 않는다."""

    # 심사 단계는 낮은 온도로 돈다. 나머지는 창작 온도.
    JUDGE_STAGES = ("P3", "W6")

    def __init__(self, backend: Backend, model: str, judge_model: str, max_tokens: int):
        self.backend = backend
        self.provider = backend.name
        self.model = model
        self.judge_model = judge_model
        self.max_tokens = max_tokens
        self.temp_ok = backend.supports_temperature(model)
        self.judge_temp_ok = backend.supports_temperature(judge_model)
        self.is_mock = backend.is_mock
        self.transcript = []   # 감사용 기록 (다음 호출에 절대 사용하지 않음)

    def model_for(self, stage: str) -> str:
        """단계별 덮어쓰기(.env 의 MODEL_P3 등) > 심사/창작 모델."""
        override = env(STAGE_MODEL_PREFIX + stage.upper())
        if override:
            return override
        return self.judge_model if stage in self.JUDGE_STAGES else self.model

    def _raw_call(self, stage: str, prompt: str, temperature: float, usage: Usage,
                  images: list = None) -> str:
        model = self.model_for(stage)
        temp_ok = self.backend.supports_temperature(model)
        if hasattr(self.backend, "set_stage"):
            self.backend.set_stage(stage)

        t0 = time.monotonic()
        try:
            text, u, stop_reason = self.backend.complete(
                model, prompt, temperature if temp_ok else None, self.max_tokens,
                images=images)
        except Exception as e:  # SDK 가 429/5xx 는 이미 재시도한 뒤
            # 응답을 하나도 못 받았어도 무엇을 보냈고 왜 실패했는지는 남긴다 —
            # 안 남기면 이 호출이 있었다는 것 자체가 transcript.json 에서 사라진다.
            # meta.json 의 note 에는 str(e) 가 남지만, "무엇을 보냈길래" 는 거기
            # 없다.
            self.transcript.append({
                "stage": stage,
                "provider": self.provider,
                "model": model,
                "temperature": temperature if temp_ok else None,
                "prompt_chars": len(prompt),
                "prompt": prompt,
                "images": [im["name"] for im in (images or [])],
                "stop_reason": None,
                "raw": None,
                "error": str(e),
            })
            raise ApiFailure(stage, e)

        # 원장에 한 줄. 여기가 **유일하게** 모든 호출이 지나가는 자리라서,
        # 단계·모델·토큰·비용을 여기서 한 번에 남긴다.
        usage.add(u, model=model, stage=stage, seconds=time.monotonic() - t0,
                  provider=self.provider,
                  temperature=temperature if temp_ok else None,
                  prompt_chars=len(prompt),
                  images=len(images) if images else None,
                  stop_reason=stop_reason,
                  mock=True if self.is_mock else None)
        # 프롬프트 전문도 남긴다. 여기에는 응답(raw)만 있고 무엇을 보냈는지는
        # 글자 수(prompt_chars)뿐이었다. 그런데 결과가 이상할 때 알아야 하는 것은
        # "무엇을 보냈길래 이렇게 왔나" 다 — 그림 쪽 log.jsonl 은 프롬프트를 통째로
        # 남기고 있어서 원인을 거기서 찾을 수 있었는데, 여기서는 못 찾았다.
        #
        # 파일이 커지는 것은 감수한다. W5 프롬프트 하나가 만 자를 넘지만, 재현과
        # 디버깅에 그만한 값을 한다.
        self.transcript.append({
            "stage": stage,
            "provider": self.provider,
            "model": model,
            "temperature": temperature if temp_ok else None,
            "prompt_chars": len(prompt),
            "prompt": prompt,
            "images": [im["name"] for im in (images or [])],
            "stop_reason": stop_reason,
            "raw": text,
            "error": None,
        })
        if stop_reason == "max_tokens":
            warn(f"{stage}: max_tokens 로 잘렸습니다. --max-tokens 를 올리세요.")
        return text

    def json_call(self, stage: str, prompt: str, temperature: float, usage: Usage,
                  images: list = None):
        """JSON 을 받는다. 파싱 실패하면 1회만 재요청. 또 실패하면 ParseFailure."""
        attempt_prompt = prompt
        last_raw = ""
        for attempt in range(2):
            last_raw = self._raw_call(stage, attempt_prompt, temperature, usage,
                                      images=images)
            obj = extract_json(last_raw)
            if obj is not None:
                return obj, last_raw
            if attempt == 0:
                warn(f"{stage}: JSON 파싱 실패 — 1회 재요청")
                attempt_prompt = prompt + REPARSE_NOTE
        raise ParseFailure(stage, last_raw)


# ---------------------------------------------------------------- mock
#
# API 없이 게이트·판정·재생성 라우팅·산출물 경로를 점검하기 위한 가짜 클라이언트.
# 실제 품질과는 아무 관계가 없다. meta.json 에 mock=true 로 남는다.

class MockBackend(Backend):
    name = "mock"
    is_mock = True

    def __init__(self, provider: str = None):
        # 온도 적용 여부는 흉내내려는 프로바이더 규칙을 그대로 따른다
        self.mimic = (provider or DEFAULT_PROVIDER)

    def supports_temperature(self, model: str) -> bool:
        return supports_temperature(model, self.mimic)

    def complete(self, model, prompt, temperature, max_tokens, images=None):
        stage = self._stage
        text = json.dumps(mock_payload(stage, prompt), ensure_ascii=False)
        # 사진 토큰도 흉내낸다 — 모의 실행으로 비용 경로를 볼 때 그림이 공짜로
        # 보이면 안 된다. 실제 값이 아니라 자리를 채우는 숫자다.
        u = {"input": 1200 + 800 * len(images or []), "output": 800}
        return text, u, "end_turn"

    _stage = "?"

    def set_stage(self, stage: str) -> None:
        self._stage = stage


def _mock_idea(prompt: str) -> str:
    m = re.search(r"\[작가가 처음 준 한 줄[^\]]*\]\s*\n(.+)", prompt)
    return (m.group(1).strip() if m else "이름 없는 인물")


def mock_payload(stage: str, prompt: str) -> dict:
    if stage == "LOOK":
        # 사진을 실제로 본 것이 아니다. 경로만 점검한다.
        return {
            "appearance": {"hair": "검은색, 어깨 아래, 낮게 묶음",
                           "eyes": "짙은 갈색, 끝이 처진 눈",
                           "build": "170 언저리, 어깨가 좁다",
                           "clothing": "남색 근무복 상의", "impression": "입을 다물고 정면",
                           "element": "재"},
            "color_palette": {"hair": "ink black (#1B1B1F)",
                              "eyes": "deep brown (#4A2E1E)",
                              "skin": "pale apricot (#F2D6C2)",
                              "outfit_main": "navy (#1F2A44)",
                              "outfit_sub": "faded yellow (#D8C471)",
                              "accent": "signal orange (#E8622A)"},
            "design_details": ["왼쪽 눈썹을 가로지르는 짧은 흉터",
                               "목 뒤로 낮게 묶은 머리"],
            "age_look": "20대 중반",
            "mood": "정면을 보고 있지만 입을 다물어 말을 아끼는 인상",
            "story_seeds": [{"seed": "근무복인데 소매가 그을려 있다",
                             "evidence": "왼쪽 소매 끝의 검은 자국"}],
            "not_visible": ["하의", "신발"],
        }
    if stage == "SEED":
        return {
            "genre": "헌터 (각성·게이트)", "genre_key": "hunter",
            "world": ("게이트가 열린 지 20년. 각성자는 등급으로 분류되고 등급은 "
                      "국가가 매긴다. 재측정은 5년에 한 번뿐이라, 한 번 잘못 매겨진 "
                      "등급은 실력과 무관하게 평생을 따라다닌다."),
            "one_line": "모두를 구하는 사람이, 자기 얘기만은 못 한다",
            "story_direction": "처음 부딪칠 벽은 자기 등급을 스스로 신고해야 하는 자리다.",
            "filled": ["genre", "world", "one_line"],
            "why": "모의 실행",
        }
    if stage == "P1":
        return {
            "name": "모의인물 (활동명: 모의)",
            "gender": "여성",
            "intro": "잔업하던 말단 사원, 눈 떠보니 폐기 예정 기록보관소의 마지막 사서",
            "rank": "제3기록보관소 사서 (폐기 D-30)",
            "rank_irony": "폐기 D-30",
            "personality": "누구에게나 당당한데 한 사람 앞에서만 말끝을 흐린다",
            "quote": ("다들 내가 당당하다고들 하지. 그런데 있잖아, 아무도 안 볼 때 "
                      "나는 늘 문 앞에서 한참을 서 있어. 그걸 본 사람은 딱 하나뿐이야."),
            "one_line": "겉은 당당한데 속은 소심한 사람",
            "surface_attribute_a": "A",
            "expectation_ea": "혼자서도 밀고 나가는 당당함이 있을 것이다",
            "ea_key_word": "당당함", "b_trait_word": "소심함",
            "betrayal_attribute_b": "소심함 — 중요한 순간마다 말끝을 흐리고 결정을 미룬다",
            "sniping_reason": "B가 E(A)를 정면으로 부정한다",
            "coexistence_forced": "한 선택 안에서 A와 B가 서로를 밀어낸다",
            "want": "인정받는 것", "need": "용서받는 것",
            "trigger_situations": ["상황 하나", "상황 둘", "상황 셋"],
            "voice_notes": "말끝을 흐린다",
            "relational_gap": {
                "to_everyone": "누구 앞에서도 물러서지 않는 당당함",
                "to_one_only": "그 사람 앞에서만 말끝이 흐려지고 시선이 바닥으로 간다",
                "anchor": "하연 — 폐기 명령서에 서명한 감사관",
                "exception_reason": "십 년 전 같은 보관소에서 그가 내 이름을 지워 준 적이 있다",
                "solo": False, "solo_reason": None,
            },
            "supporting_cast": [
                {"name": "하연", "gender": "여성",
                 "relation": "폐기 명령서에 서명한 감사관",
                 "appearance": "짧게 친 검은 머리, 큰 키에 각진 어깨, "
                               "감사관 제복에 은테 안경",
                 "role": "주인공의 보관소를 닫으러 온다"},
            ],
            "wish_fulfillment": "아무도 몰랐던 내 자리가 마지막에 가장 중요해지는 것",
            "appearance": {"hair": "검은 단발", "eyes": "짙은 갈색 · 처진 눈매",
                           "build": "170 언저리, 마른 편에 어깨가 좁다",
                           "clothing": "회색 하이넥 코트에 검은 슬랙스",
                           "impression": "조용한데 눈은 안 피함", "element": "먼지"},
            "design_details": [
                "왼쪽 소매 끝이 해져 실밥이 나와 있다",
                "목 뒤로 낮게 묶은 잔머리",
                "오른손 검지에 낡은 은가락지",
            ],
            "color_palette": {"hair": "ink black (#22252A)",
                              "eyes": "deep brown (#4A2F1E)",
                              "skin": "pale apricot (#F3D6C0)",
                              "outfit_main": "grey (#8A8A8A)",
                              "outfit_sub": "black (#1C1B19)",
                              "accent": "faded yellow (#D9C879)"},
            "expression_set": [
                "기본 — 입을 다물고 시선만 정면에 둔다",
                "겁먹음 — 동공이 커지고 턱이 굳는다. 손이 소매 끝을 쥔다",
                "결의 — 눈썹이 내려가고 입술 선이 곧아진다",
                "안도 — 어깨가 내려가고 눈가가 풀린다",
                "당황 — 시선이 옆으로 흐르고 말끝에 입이 벌어진다",
                "체념 — 눈꺼풀이 반쯤 내려오고 고개가 살짝 기운다",
            ],
            "appearance_en": (
                "A young woman in her late twenties, short black hair with blunt "
                "bangs falling over the eyes, narrow dark eyes, lean tall frame, "
                "wears a grey high-collared coat with frayed cuffs, quiet air"),
            "visual_hook": "앞머리가 눈을 반쯤 덮은, 웃지 않는데 입꼬리만 올라간 사람",
            "visual_gap": "정면을 보는 외형은 압도적인데, 말을 걸면 소심함이 튀어나와 시선이 먼저 바닥으로 떨어진다",
            "fate_beats": [
                "당신은 아무도 찾지 않는 제3기록보관소의 마지막 사서입니다.",
                "그런데 오늘 아침, 30일 뒤 폐기한다는 명령서가 내려옵니다.",
                "명령서에 서명한 사람은 십 년 전 당신의 이름을 지워 준 그 감사관입니다.",
                "그리고 이 보관소에서 지워진 이름을 되살릴 수 있는 사람은 당신뿐입니다.",
            ],
        }
    if stage == "P2":
        ev = []
        for src in ("rule", "cost", "irony"):
            for i in range(1, 5):
                ev.append({"event": f"{src} 에서 나오는 사건 {i}", "source": src})
        return {
            "logline": "장르 배신이 담긴 한 줄",
            "genre_expectation": "장르 기대",
            "genre_promise": "이 장르를 고른 독자가 반드시 보고 싶어 하는 것",
            "subversion_axis": "위상",
            "genre_betrayal": "그 기대를 뒤집는 방식",
            "rule": {"statement": "이 세계만의 법칙", "retrigger_condition": "등장할 때마다"},
            "cost": {"statement": "얻을수록 잃는 것", "advance_mechanism": "전진이 상실을 만든다"},
            "irony": {"statement": "두 욕망의 정면충돌", "side_a": "사람", "side_b": "제국",
                      "no_exit_reason": "어느 쪽이 이겨도 무너진다"},
            "drop": "사적 감정 하나가 세계를 바꾼다",
            "engine_question": "그는 끝내 무엇을 택하는가",
            "generative_check": ev,
            "forbidden_subversion": ["사실 전부 꿈이었다", "죽은 줄 알았던 인물의 생환"],
        }
    if stage == "P3":
        keys = ["ea_stated", "sniping_verified", "coexistence_forced",
                "engine_installed", "auto_event_generation", "drop_present",
                "intro_hook", "relational_gap_exists", "fate_beats_turn"]
        return {
            "checks": {k: {"verdict": "yes", "evidence": "원문 인용", "reason": "근거"}
                       for k in keys},
            "capture_test": "yes",
            "capture_sentence": "한 사람이 자기 규칙에 걸려 넘어지는 이야기라서.",
            "first_question": "그 규칙은 왜 그에게만 걸리는가?",
            "cliche_detected": None,
            "regeneration_directive": None,
            "target_stage": None,
        }
    if stage in ("SCENE", "CONTROL"):
        idea = _mock_idea(prompt) if stage == "SCENE" else "모의 인물"
        n = 3
        m = re.search(r"장면\s*(\d+)\s*개", prompt)
        if m:
            n = int(m.group(1))
        srcs = ["rule", "cost", "irony", "want_need"]
        verbs = ["거절했다", "숨겼다", "거짓말했다", "붙잡았다", "모른 척했다"]
        scenes = []
        for i in range(n):
            scenes.append({
                "no": i + 1,
                "text": (f"{idea}\n"
                         f"그는 {verbs[i % len(verbs)]}. 문이 닫히는 소리가 복도를 타고 갔다.\n"
                         f"\"{i+1}번째로 묻는 겁니다.\" 상대가 말했다.\n"
                         f"그는 대답 대신 {'창밖' if i % 2 else '바닥'}을 오래 보았다. "
                         f"장면 {i+1} 에서만 벌어지는 일이 여기서 끝난다."),
                "one_line": f"{i+1}번 장면에서 그가 {verbs[i % len(verbs)]}",
                "choice": verbs[i % len(verbs)],
                "source": srcs[i % len(srcs)],
                "source_note": "설정에서 바로 나온다",
                "new_element": None,
            })
        return {"title": "모의 제목", "scenes": scenes}
    return mock_webtoon_payload(stage, prompt)


def mock_webtoon_payload(stage: str, prompt: str) -> dict:
    """webtoon.py 가 import 해서 쓴다. 웹툰 단계 4~7 의 가짜 응답."""
    if stage == "W4":
        types = ["전개", "반전", "상승", "반전", "해소"]
        elems = [["rule"], ["cost"], ["irony"], ["rule", "cost"], ["irony"]]
        return {"arcs": [{
            "order": i + 1, "title": f"Arc {i+1}", "arc_type": types[i],
            "premise_element_used": elems[i],
            "summary": f"{i+1}번째 덩어리에서 벌어지는 사건",
            "estimated_episode_count": 2,
            "opens": [f"Arc {i+1} 이 여는 질문"],
            "closes": [f"Arc {i+1} 이 닫는 질문"] if i else [],
        } for i in range(5)]}
    if stage == "W5":
        # 5단계는 **화 하나**를 낸다. id 는 코드가 붙이므로 여기엔 없다.
        no = 1
        m = re.search(r"이번은 (\d+)화입니다", prompt)
        if m:
            no = int(m.group(1))
        # 명부의 열린 질문 목록에서 하나를 닫는다. 문장을 그대로 옮겨 적는 방식을
        # 흉내내야 assign_ids 의 본문 매칭 경로가 실제로 검증된다.
        open_texts = re.findall(r'^  - "(.+?)" \(\d+화에 열림', prompt, re.M)
        closed = ([{"question_text": open_texts[0], "answer": "상환 내용",
                    "is_betrayal": True}] if open_texts else [])
        opened = [
            {"text": f"{no}화가 여는 질문: 결과가 어떻게 되는가", "type": "suspense"},
            {"text": f"{no}화가 여는 질문: 인물만 모르는 사실",
             "type": "dramatic_irony"},
        ]
        return {
            "title": f"{no}화",
            "summary": "인물이 무리한 선택을 하고 그 대가로 감춰진 것이 드러난다",
            # 무대가 없으면 컷 단계가 그릴 것이 얼굴밖에 없다 (gate_setting).
            # 칸을 하나라도 빼면 --mock 이 5단계에서 재시도를 소진하고 멈춘다 —
            # webtoon.SETTING_LABEL 에 칸이 늘면 여기도 같이 늘려야 한다.
            "setting": {
                "place": f"{no}화의 무대가 되는 실내 한 곳",
                "time": "해질녘",
                "weather": "비가 그친 직후, 젖은 창밖",
                "light": "창에서 들어오는 주황 역광, 실내는 어둡다",
                "props": ["탁자 위에 엎어 둔 컵", "벽에 걸린 낡은 시계"],
                "movement": "인물이 문 앞에서 들어와 창가로 걸어간다",
            },
            # 조연 카드는 네 칸을 다 채워야 통과한다 (gate_episodes_shape).
            # 비워 두면 --mock 이 5단계에서 재시도를 소진하고 멈춘다.
            "new_cast": ([{"name": "모의인물2", "note": "1화에서 처음 나온 사람",
                           "gender": "male",
                           "appearance": "큰 키에 마른 체형, 짧고 헝클어진 검은 머리, "
                                         "처진 눈매, 왼쪽 눈썹에 작은 흉터",
                           "outfit": "회색 티셔츠 위에 연청 데님 자켓, 검은 슬랙스에 "
                                     "흰 스니커즈, 작은 검은 백팩",
                           "personality": "반 박자 늦게 반응하고, 당황하면 뒷목을 만진다"}]
                         if no == 1 else []),
            "new_facts": [f"{no}화에서 확정된 설정"],
            "questions_opened": opened,
            "questions_closed": closed,
            "engine_fired": ["rule"] if no % 2 else ["cost"],
            "stinger": {"text": "다음 화를 부르는 훅",
                        "linked_question_text": opened[0]["text"]},
        }
    if stage == "W6":
        # 검수 대상(제출된 화 구성)만 읽는다. 엔진 카드·장부 스냅샷은 보지 않는다.
        body = prompt.split("[검수 대상", 1)[-1]
        eps = (extract_json(body) or {}).get("episodes") or []
        # 치명 위반 경로를 실제로 태워보기 위한 스위치 (검증용)
        force_eq = os.environ.get("STORY_MOCK_EQ_VIOLATION") == "1"
        per, types, closes, eq = [], [], [], []
        for e in eps:
            o = e.get("order")
            per.append({"order": o, "pass": True, "violations": []})
            eq.append({"episode_order": o, "violated": force_eq and o == 1,
                       "detail": "중심 질문이 이 화에서 해소되었다" if (force_eq and o == 1) else None})
            for i, q in enumerate(e.get("questions_opened") or []):
                types.append({"episode_order": o, "temp_id": q.get("temp_id"),
                              "type": "dramatic_irony" if i % 2 else "suspense"})
            for i, c in enumerate(e.get("questions_closed") or []):
                closes.append({"episode_order": o, "ledger_id": c.get("ledger_id"),
                               "is_betrayal": i == 0})
        return {"arc_order": 1, "per_episode": per or [{"order": 1, "pass": True,
                                                        "violations": []}],
                "verified_question_types": types, "verified_closures": closes,
                "eq_untouched": eq or [{"episode_order": 1, "violated": False,
                                        "detail": None}]}
    if stage == "W8":
        # 8단계는 확정된 컷을 받아 글자만 다시 낸다. 모의 응답은 프롬프트에
        # 실린 컷을 그대로 되돌려준다 — 글자를 지어내면 모의 실행이 게이트
        # (금지어·POV)에 자기가 걸린다.
        m = re.search(r"\[확정된 컷.*?\]\s*(\[.*?\])\s*\n\s*\[이 화의 시점",
                      prompt, re.S)
        cuts = json.loads(m.group(1)) if m else []
        return {"text_patch": [
            {k: c.get(k, "") for k in ("speaker", "speaker_side", "dialogue",
                                       "narration", "thought", "sfx",
                                       "screen_text", "bubble_zone")}
            | {"cut_number": c.get("cut_number")}
            for c in cuts]}
    if stage == "W7":
        # 화자는 프롬프트에 실린 엔진 카드에서 가져온다. 아무 이름이나 쓰면
        # "명부에 없는 이름이 말한다" 경고가 모의 실행마다 뜬다 — 진짜 경고가
        # 그 사이에 묻힌다.
        names = re.findall(r"^\[주인공\] (.+)$", prompt, re.M)
        names += [re.split(r"[—\-(]", x)[0].strip()
                  for x in re.findall(r"^  그 한 사람: (.+)$", prompt, re.M)]
        names = [n.strip() for n in names if n.strip()]
        return mock_cuts(10, speakers=names or None)
    return {"error": f"mock 미지원 단계: {stage}"}


# 모의 컷의 카메라 세 축. 게이트를 통과하도록 골라 둔 순환이다 —
# 얼굴 거리(바스트·클로즈업·익스트림)가 절반을 넘지 않고, 원경과 인서트가 8컷마다
# 한 번씩 돌아오며, 같은 값이 이어지지 않는다.
MOCK_SHOTS = ("원경", "중간", "바스트", "인서트", "전신", "클로즈업", "익스트림", "중간")
MOCK_ANGLES = ("수평", "부감", "수평", "앙각", "수평", "부감", "수평", "앙각")
# 첫 컷은 언제나 '장면'이다(앞에 아무것도 없다). 나머지가 이 순환을 돈다.
MOCK_TRANSITIONS = ("동작", "인물", "분위기", "순간", "동작", "인물", "장면", "분위기")


def mock_cuts(n: int = 10, speakers: list = None) -> dict:
    """게이트를 통과하는 컷 한 벌 — **모델이 내는 것만** 담는다.

    여백·화면 경계·시선은 webtoon.derive_layout 이 beat 에서 계산하므로 여기 없다.
    beat 는 setup → build → turn → release → hold 를 순환시킨다. 그러면 같은 beat 가
    이어지지 않고, hold·turn 이 컷 5개마다 한 번씩 나와 화면 경계가 잡힌다.
    크기는 beat 를 따라간다 — 뒤집히는 자리가 크고 상황을 놓는 자리가 납작한 것이
    세로 스크롤의 기본형이다.

    거리·앵글·전환도 함께 낸다. 모의 컷이 이 축을 비워 두면 --mock 실행이
    카메라 게이트를 한 번도 밟지 않고 통과해 버려서, 점검용으로 쓸 수 없다.
    """
    # 화자가 둘이어야 주고받는 모양이 나온다. 이름을 못 받으면 기본값을 쓴다.
    who = list(speakers or ())
    while len(who) < 2:
        who.append(("모의주인공", "모의인물2")[len(who)])

    cycle = ["setup", "build", "turn", "release", "hold"]
    beats = [cycle[i % len(cycle)] for i in range(n)]
    if n >= 2:
        beats[-1] = "hold"          # 마지막 컷은 스팅어다
    if "turn" not in beats:
        beats[max(0, n // 2)] = "turn"

    # impact 는 화당 2개까지다. 앞에서부터 turn 두 개에만 준다.
    impact_left = 2
    size_of = {"setup": "wide", "build": "normal", "release": "normal",
               "hold": "tall"}

    cuts = []
    zone_idx = 0
    for i in range(n):
        beat = beats[i]
        if beat == "turn" and impact_left > 0:
            size = "impact"
            impact_left -= 1
        else:
            size = size_of.get(beat, "normal")
        if i == n - 1:
            size = "impact" if impact_left > 0 else "tall"
        # SD 를 두 컷 넣는다. 게이트가 요구해서가 아니라(하한은 없다) SD 가 걸린
        # 경로 — 효과음 필수, 강등 수리, 상한 — 을 모의 컷에서도 밟아 보기
        # 위해서다. 자리는 SD_BEATS 안에서만 고르고 스팅어는 건너뛴다.
        # webtoon 은 story 를 import 하므로 여기서만 늦게 부른다 (순환 방지).
        import webtoon as _w
        sd_so_far = sum(1 for c in cuts if c["render_style"] == "sd")
        render = "sd" if (beat in _w.SD_BEATS and i != n - 1
                          and sd_so_far < min(2, _w.SD_MAX)) \
            else ("emphasis" if beat == "turn" else "normal")
        # 마지막 컷은 크게 끝나야 하므로(END_SIZES) 거리도 거기 맞춘다.
        shot = "클로즈업" if i == n - 1 else MOCK_SHOTS[i % len(MOCK_SHOTS)]
        transition = ("장면" if i == 0 else
                      MOCK_TRANSITIONS[(i - 1) % len(MOCK_TRANSITIONS)])
        # 말은 짝수 컷과 **장면이 건너뛴 컷**에 넣는다. 그러면 말 있는 컷이 절반을
        # 넘고, 말 없는 컷이 2연속을 넘지 않으며, 장면 전환 자리가 비지 않는다
        # (gate_text 의 네 조건). 모의 컷이 이걸 안 지키면 --mock 이 통과해 버려서
        # 점검용으로 쓸 수 없다.
        speaks = (i % 2 == 0) or transition == "장면"
        says = speaks and i % 4 != 1
        # 존은 3컷마다 바꾸되, **같은 자리를 뜻하는 전환(순간·동작) 자리에서는
        # 넘기지 않는다** — 그 둘 사이에 존이 바뀌면 한 컷 만에 순간이동한 것이
        # 되어 zone_warnings 에 걸린다. transition 을 대신 고치면 이번에는
        # 여백 계산(derive_layout)과 말 경고가 어긋난다. 모의 컷이 자기 경고에
        # 걸리면 진짜 경고가 그 사이에 묻힌다.
        if i and i % 3 == 0 and transition not in ("순간", "동작"):
            zone_idx += 1
        zone = f"z-mock-{zone_idx}"
        cuts.append({"cut_number": i + 1,
                     "shot": shot,
                     "angle": MOCK_ANGLES[i % len(MOCK_ANGLES)],
                     "transition": transition,
                     # 존도 게이트가 실제로 보므로(gate_zone) 모의 컷에도 채운다.
                     # 몇 컷마다 하나씩 바뀌게 해서 record_cut_zone 이 존을
                     # 여러 개 올리는 경로도 --mock 이 밟게 한다.
                     "zone": zone,
                     # 서술에는 카메라 낱말을 쓰지 않는다 — 그건 위 세 필드다.
                     "description": f"{i+1}번 컷에서 화면에 보이는 것",
                     # 화자를 번갈아 준다. 혼자 말하는 화는 독백이라 게이트가
                     # 되돌린다(gate_dialogue) — 모의 컷도 그 경로를 밟아야 한다.
                     "speaker": (who[i % len(who)] if says else ""),
                     # 말하는 사람이 화면 어느 쪽인가. 두 사람이 번갈아 말하므로
                     # 좌우도 번갈아 준다 — 대화 장면의 기본형이다.
                     "speaker_side": (("left", "right")[i % 2] if says else ""),
                     "dialogue": ("\"…\"" if says else ""),
                     # 나레이션은 **장면이 바뀔 때마다** 붙이는 칸이 아니다.
                     # 첫 컷에 시간·장소를 한 번 세우고, 그 뒤로는 장면 표시가
                     # 아닌 일(여기서는 1인칭 독백)을 하는 자리를 하나 둔다 —
                     # 모의 컷이 "장면마다 배경 설명" 을 시범 보이면 안 된다.
                     "narration": ("사흘 뒤, 같은 자리." if i == 0 else
                                   "나는 그때 이미 알고 있었다." if i == 4 else ""),
                     "thought": "…이건 아니야." if i == 5 else "",
                     # SD 컷에는 반드시 효과음이 있어야 한다 (prose_warnings).
                     "sfx": ("두근" if render == "sd" else
                             ("쿵" if beat == "turn" else "")),
                     "screen_text": "",
                     "reader_only": i == 2,
                     "size": size, "beat": beat, "render_style": render,
                     # 화면에 누가 있는가. 인서트 컷은 빈 배열이다 — 그것도
                     # 정보라서 모의 컷이 그 경로를 밟아야 한다.
                     # 속마음이 있는 컷(i==5)은 그 speaker 가 반드시 여기 있어야
                     # 한다 (gate_frame). 그 컷의 speaker 는 아래에서 채운다.
                     "characters_in_frame": ([] if shot == "인서트"
                                             else [who[i % len(who)]]),
                     "composition": ("over-the-shoulder" if i == 3 else "none"),
                     "composition_note": ("앞쪽에 상대의 어깨 너머로 본다"
                                          if i == 3 else ""),
                     # 글자가 있으면 자리를 비운다. 아래에서 실제 글자 유무를
                     # 보고 다시 맞춘다 — 여기서는 자리만 잡아 둔다.
                     "bubble_zone": "top",
                     "why": "모의 컷"})

    # 글자가 하나도 없는 컷을 2개 이상 남긴다 (prose_warnings 의 하한). 말 없는 컷
    # 중에서 sfx 도 없는 자리를 뒤에서부터 찾는다 — 말이 있는 컷을 비우면 위
    # gate_text 가 걸리므로 건드리지 않는다.
    def is_silent(c: dict) -> bool:
        return not any(str(c.get(k) or "").strip()
                       for k in ("dialogue", "narration", "thought", "sfx"))

    for c in reversed(cuts[:-1]):
        if sum(1 for x in cuts if is_silent(x)) >= 2:
            break
        if is_silent(c) or any(str(c.get(k) or "").strip()
                               for k in ("dialogue", "narration", "thought")):
            continue
        c["sfx"] = ""           # sfx 만 있던 컷을 완전 무음으로
        if c["render_style"] == "sd":
            # SD 컷은 효과음이 있어야 한다(prose_warnings). 효과음을 뺐으면
            # 그림체도 같이 내린다 — 강등은 언제나 안전하다.
            c["render_style"] = "normal"

    # 화면 관련 값을 실제 내용에 맞춘다. 위에서 글자를 지운 컷이 있으므로
    # 여기서 한 번에 맞춰야 gate_frame 과 어긋나지 않는다.
    for c in cuts:
        speaks = any(str(c.get(k) or "").strip()
                     for k in ("dialogue", "narration", "thought"))
        c["bubble_zone"] = "top" if speaks else "none"
        # 속마음이 있으면 그 사람이 화면에 있어야 하고 speaker 도 있어야 한다.
        if str(c.get("thought") or "").strip():
            owner = c.get("speaker") or who[0]
            c["speaker"] = owner
            if owner not in c["characters_in_frame"]:
                c["characters_in_frame"] = [owner]
        if any(str(c.get(k) or "").strip() for k in ("dialogue", "thought")):
            c["speaker_side"] = c.get("speaker_side") or "left"
        else:
            c["speaker_side"] = ""

    # 장면으로 나눈다 — 장면당 4컷을 기준으로 하되 개수를 2~5개로 맞춘다
    # (webtoon.gate_scenes 의 SCENE_COUNT / SCENE_MIN~MAX 를 만족해야 한다).
    count = max(2, min(5, -(-n // 4)))
    spans, left = [], n
    for i in range(count):
        take = -(-left // (count - i))          # 남은 것을 고르게 나눈다
        spans.append(take)
        left -= take
    # tone 도 게이트가 보므로(gate_scenes) 모의 장면에 채운다. 한 화가 전부
    # 같은 tone 이면 tone_warnings 가 찍히므로 섞어 둔다 — 모의 실행이 그
    # 경로까지 밟아야 점검이 된다. 첫 장면을 개그로 두면 repair_tone_lock 의
    # 강등 경로(긴장 장면의 sd)와 tone_warnings 의 개그 경로가 같이 걸린다.
    mock_tones = ("개그", "긴장", "일상", "감정", "일상")
    scenes, at = [], 0
    for i, span in enumerate(spans, 1):
        at += span
        scenes.append({
            "what": f"{i}번째 장면 — 여기서 벌어지는 일",
            "mood": "이 장면이 어떤 공기여야 하는지",
            "tone": mock_tones[(i - 1) % len(mock_tones)],
            "last_cut": at,
        })

    # 존 서술도 게이트가 본다(gate_zone) — 컷이 가리키는 존은 전부 여기 있어야
    # 하고, 사람 이름이 들어가면 탈락한다. 모의 컷이 이 경로를 밟아야 점검이 된다.
    zone_ids = sorted({c["zone"] for c in cuts if c.get("zone")})
    return {"arc_order": 1, "episode_order": 1,
            "scenes": scenes,
            "zones": [{"zone_id": z,
                       "description": f"{z} 구역 — 사람 없이 그 자리에 늘 있는 것"}
                      for z in zone_ids],
            "beat_sequence": " ".join(c["beat"] for c in cuts),
            "size_sequence": " ".join(c["size"] for c in cuts),
            "cuts": cuts,
            "engine_cut_refs": [{"element": "rule", "cut_number": 2},
                                {"element": "cost", "cut_number": 6}],
            "stinger_cut_number": n}


# ---------------------------------------------------------------- P2 게이트

def normalize_source(value) -> str:
    if not isinstance(value, str):
        return ""
    key = value.strip().lower()
    if key in SOURCE_ALIASES:
        return SOURCE_ALIASES[key]
    for alias, canon in SOURCE_ALIASES.items():
        if alias in key:
            return canon
    return key


def collect_connectors(p2: dict) -> list:
    out = []
    top = p2.get("connectors")
    if isinstance(top, list):
        out.extend([c for c in top if isinstance(c, str)])
    chains = p2.get("causal_chains")
    if isinstance(chains, list):
        for ch in chains:
            if isinstance(ch, dict) and isinstance(ch.get("connector"), str):
                out.append(ch["connector"])
    return out


# 사건 목록 필드는 프롬프트 판본에 따라 이름이 갈린다.
#   generative_check : 문서 10-2 원문 (최소 10개, source 별 3개 이상)
#   causal_chains    : 초기 판본 (최소 3개, source 별 1개 이상)
# 어느 쪽이 들어와도 그 판본의 기준으로 검사한다.
EVENT_FIELDS = [
    ("generative_check", 10, 3, "event"),
    ("causal_chains", 3, 1, "effect"),
]


def collect_events(p2: dict):
    """(필드명, 항목리스트, 최소총개수, source별최소개수) 를 돌려준다."""
    for name, min_total, min_each, _ in EVENT_FIELDS:
        value = p2.get(name)
        if isinstance(value, list):
            return name, value, min_total, min_each
    return None, None, 0, 0


def _stem(word: str) -> str:
    """'당당함' / '당당한' / '당당하다' 를 같은 것으로 보기 위한 거친 어간."""
    w = re.sub(r"[\s\-—·,.'\"]+", "", str(word or ""))
    for suffix in ("스럽다", "스러움", "하다", "되다", "함", "성", "감", "한", "적", "임", "움"):
        if len(w) > len(suffix) + 1 and w.endswith(suffix):
            return w[: -len(suffix)]
    return w


def gate_name(p1: dict, given_name: str = "") -> list:
    """작가가 준 이름이 카드에서 바뀌지 않게 한다.

    이 게이트가 생긴 이유: 편지지 같은 소품 텍스트 속 캐릭터 이름이 작가가
    입력한 이름(예: 초롱)과 다르게 나온 사고가 있었다. p1['name'] 은 엔진
    카드를 거쳐 4~8단계 프롬프트 전체의 출발점이라, 여기서부터 어긋나면
    뒤에서는 바로잡을 자리가 없다.

    given_name 이 비어 있으면(작가가 이름을 안 주고 파일명만 있으면) 검사하지
    않는다 — 그때는 모델이 이름을 짓는 것이 맞다. gate_gender 와 같은 이유로
    같은 자리에 둔다: 작가가 정한 사실은 창작 대상이 아니라 제약이다.
    """
    name = str(given_name or "").strip()
    if not name:
        return []
    card_name = str(p1.get("name") or "").strip()
    if not card_name:
        return [f"name 이 비어 있습니다. 작가가 이름을 '{name}' 으로 정했습니다. "
                "그대로 쓰세요."]
    if card_name != name:
        return [f"작가는 이름을 '{name}' 으로 정했는데 카드의 name 이 "
                f"'{card_name}' 입니다. 작가가 정한 이름은 바꾸지 않습니다."]
    return []


def gate_p1(p1: dict, character_input: str = "", given_name: str = "") -> list:
    """저격 구조를 코드로만 판정한다. 비어있으면 통과.

    character_input 은 작가가 준 원문이다. 카드가 그 사실(성별 등)을 버리지
    않았는지 대조하는 데 쓴다.

    모델에게 "B가 E(A)의 정확한 부정인가" 를 자연어로 묻지 않는다. 그 질문은
    판정 기준이 매 호출마다 달라져서 사실상 모든 입력을 탈락시킨다.
    대신 P1 이 ea_key_word / b_trait_word 를 명시적으로 내놓게 하고,
    여기서는 검증 가능한 것만 본다:
      - 두 단어가 실제로 있는가
      - 각각 E(A) 문장 / B 문장 안에 그대로 들어 있는가
      - 서로 같은 단어이거나 단순 부정형("성실함" vs "성실하지 않음")이 아닌가
      - B 문장에 트레이트 단어 말고 구체적 행동이 붙어 있는가
    "두 단어가 반의어인가" 라는 어휘 판단만 P3 에 남긴다.
    """
    failures = []

    ea_word = str(p1.get("ea_key_word") or "").strip()
    b_word = str(p1.get("b_trait_word") or "").strip()
    ea_sent = str(p1.get("expectation_ea") or "").strip()
    b_sent = str(p1.get("betrayal_attribute_b") or "").strip()

    # 성별·조연 명부는 저격 구조와 무관하다. 아래 조기 반환보다 먼저 본다 —
    # 저격이 깨졌다고 이것들을 안 보면, 재생성 한 번에 하나씩만 고치게 된다.
    failures += gate_gender(p1, character_input)
    failures += gate_supporting_cast(p1)
    failures += gate_name(p1, given_name)

    if not ea_word:
        failures.append("ea_key_word 가 비어있습니다. E(A)의 핵심을 한 단어로 쓰세요.")
    if not b_word:
        failures.append("b_trait_word 가 비어있습니다. ea_key_word 의 반의어를 한 단어로 쓰세요.")
    if not ea_word or not b_word:
        return failures

    # 한 단어여야 한다 — 구절을 넣으면 뒤의 포함 검사가 무의미해진다.
    for label, word in (("ea_key_word", ea_word), ("b_trait_word", b_word)):
        if len(word.split()) > 1 or len(word) > 12:
            failures.append(
                f"{label} 이 '{word}' 입니다. 구절이 아니라 한 단어여야 합니다.")

    ea_stem, b_stem = _stem(ea_word), _stem(b_word)

    if ea_stem and ea_stem == b_stem:
        failures.append(
            f"ea_key_word '{ea_word}' 와 b_trait_word '{b_word}' 가 같은 말입니다. "
            "부정형이 아니라 별도의 반의어를 쓰세요. (예: 성실함 → 게으름)")
    elif ea_stem and ea_stem in b_stem:
        failures.append(
            f"b_trait_word '{b_word}' 가 '{ea_word}' 의 단순 부정형입니다. "
            "독립된 반의어 한 단어로 바꾸세요. (예: 당당하지 않음 → 소심함)")

    if ea_stem and ea_stem not in _stem(ea_sent):
        failures.append(
            f"expectation_ea 안에 ea_key_word '{ea_word}' 가 그대로 들어 있지 않습니다. "
            "E(A) 문장을 그 단어로 다시 쓰세요.")
    if b_stem and b_stem not in _stem(b_sent):
        failures.append(
            f"betrayal_attribute_b 안에 b_trait_word '{b_word}' 가 그대로 들어 있지 "
            "않습니다. B 문장을 그 단어로 시작하세요.")

    # 트레이트 단어만 있고 행동이 없으면 캐릭터가 아니라 라벨이다.
    if b_stem and len(re.sub(r"[\s\-—·,.]+", "", b_sent)) - len(b_stem) < 10:
        failures.append(
            f"betrayal_attribute_b 가 '{b_sent}' 뿐입니다. "
            "트레이트 단어 뒤에 그것이 드러나는 구체적 행동을 붙이세요.")

    failures += gate_card(p1)
    failures += gate_visual(p1, b_word)
    return failures


# 작가가 성별을 적었는지 알아보는 표현들. 여기 없는 표현으로 적으면 못 잡지만,
# 못 잡는 쪽이 잘못 잡는 쪽보다 낫다 — 안 적은 사람에게 적으라고 하면 안 된다.
#
# 영문은 **낱말 경계**로 본다. 'woman' 안에 'man' 이 들어 있어서 그냥 포함으로
# 보면 여성 표기가 남녀 둘 다로 읽히고, 결국 "모르겠다"가 된다.
# 한글은 '성별: 남' 처럼 항목 형태로 적는 경우가 흔해서 그 꼴도 같이 본다
# (캐릭터 JSON 의 fields 가 그렇게 생겼다). 다만 '남'·'여' 한 글자만으로는
# 판정하지 않는다 — '남기다', '여기' 같은 말에 걸린다.
GENDER_PATTERNS = {
    "여": (r"여성|여자|여주(?:인공)?|성별[\"'\s]*[:：][\"'\s]*여(?![성자])"
           r"|\b(?:female|woman|women|girl|she|her)\b"),
    "남": (r"남성|남자|남주(?:인공)?|성별[\"'\s]*[:：][\"'\s]*남(?![성자])"
           r"|\b(?:male|man|men|boy|guy|he|him|his)\b"),
}
# appearance_en 은 이미지 생성기가 그대로 받는다. 여기 성별이 없으면 모델이
# 옷과 머리 길이로 짐작하고, 짧은 머리에 바지면 남자로 그린다.
EN_GENDER_WORDS = ("woman", "female", "girl", "lady", "man", "male", "boy", "guy")


def gender_of(text: str) -> str:
    """자유 서술에서 성별을 읽는다. 못 찾거나 둘 다 있으면 빈 문자열.

    둘 다 있으면 빈 값인 이유: "여성 주인공과 남자 후배" 처럼 두 인물이 한 줄에
    있으면 어느 쪽이 이 카드의 인물인지 코드가 알 수 없다. 짐작하면 틀린다.
    """
    low = str(text or "").lower()
    hit = {k for k, pat in GENDER_PATTERNS.items() if re.search(pat, low)}
    return hit.pop() if len(hit) == 1 else ""


def gate_supporting_cast(p1: dict) -> list:
    """조연이 매번 새로 만들어지지 않게 명부를 강제한다.

    같은 캐릭터 파일을 두 번 돌렸더니 후배 이름이 '하윤재' → '장지운' 으로
    바뀌었고, 그림에서는 남자 후배가 여자로 그려졌다. 조연은 캐릭터 시트가
    없어서 P1 에 적힌 것이 뒷단계가 가진 전부다. 여기서 비면 SCENE 도 W5 도
    그림도 각자 지어낸다.

    1인 완결형(solo)이면 조연이 없어도 된다.
    """
    failures = []
    rg = p1.get("relational_gap") or {}
    if rg.get("solo"):
        return failures

    cast = p1.get("supporting_cast")
    if not isinstance(cast, list) or not cast:
        return ["supporting_cast 가 비어 있습니다. 이 이야기에 나오는 주변 인물을 "
                "1~3명 적으세요 — 조연은 캐릭터 시트가 없어서 여기 적힌 것이 "
                "뒷단계가 가진 전부입니다. 비우면 장면마다 다른 사람이 됩니다."]

    for i, c in enumerate(cast, 1):
        if not isinstance(c, dict):
            failures.append(f"supporting_cast 의 {i}번째가 객체가 아닙니다.")
            continue
        for key, label in (("name", "이름"), ("gender", "성별"),
                           ("appearance", "외형")):
            if is_blank(c.get(key)):
                failures.append(
                    f"supporting_cast 의 {i}번째({c.get('name') or '이름 없음'})에 "
                    f"{label}({key})이 비어 있습니다. 그리는 쪽이 컷마다 "
                    "새로 정하게 됩니다.")
        if not is_blank(c.get("gender")) and not gender_of(str(c.get("gender"))):
            failures.append(
                f"supporting_cast 의 '{c.get('name')}' 의 gender 가 "
                f"'{c.get('gender')}' 입니다. 남성 또는 여성으로 쓰세요.")

    # anchor 로 지목한 사람이 명부에 없으면, 가장 중요한 조연이 빠진 것이다.
    anchor = str(rg.get("anchor") or "").strip()
    if anchor:
        names = [str(c.get("name") or "").strip()
                 for c in cast if isinstance(c, dict)]
        if not any(n and n in anchor for n in names):
            failures.append(
                f"relational_gap.anchor 가 '{anchor[:40]}' 인데 그 사람이 "
                f"supporting_cast 에 없습니다 (명부: {names}). 공동 주인공급이라고 "
                "해 놓고 명부에서 빠지면 뒷단계가 그 사람을 새로 만듭니다.")
    return failures


def gate_gender(p1: dict, character_input: str = "") -> list:
    """성별이 카드에서 사라지지 않게 한다.

    이 게이트가 생긴 이유: 작가가 "여성"이라고 적었는데 P1 카드에 성별 칸이
    없어서 그 사실이 통째로 증발했다. 뒷단계(P2·장면·컷·이미지)는 카드만 보므로
    아무도 그 인물이 여자인 줄 몰랐고, 이미지에는 남자가 그려졌다.

    작가가 적은 사실은 창작 대상이 아니라 제약이다. 프롬프트로만 부탁하면
    언젠가 또 빠지므로 코드가 막는다.
    """
    failures = []
    gender = str(p1.get("gender") or "").strip()

    if not gender:
        failures.append(
            "gender 가 비어 있습니다. 뒷단계는 이 카드만 보기 때문에 여기서 빠진 "
            "성별은 영원히 사라지고, 이미지 모델이 옷과 머리 길이로 짐작하게 "
            "됩니다. 작가가 적었으면 그 표현을 그대로, 안 적었으면 정해서 쓰세요.")

    # 작가가 적어 준 성별과 카드가 다르면, 카드가 틀린 것이다.
    given = gender_of(character_input)
    if given and gender:
        got = gender_of(gender)
        if got and got != given:
            want = "여성" if given == "여" else "남성"
            failures.append(
                f"작가는 이 인물을 '{want}'으로 적었는데 카드의 gender 가 "
                f"'{gender}' 입니다. 작가가 정한 사실은 바꾸지 않습니다.")

    # 이미지로 그대로 나가는 문장에 성별이 있어야 한다.
    en = str(p1.get("appearance_en") or "")
    low = re.sub(r"[^a-z ]", " ", en.lower())
    words = set(low.split())
    if en and not (words & set(EN_GENDER_WORDS)):
        failures.append(
            "appearance_en 에 성별이 없습니다. 이 문장은 이미지 생성기가 그대로 "
            "받는 자리라, 성별이 없으면 모델이 옷차림으로 짐작합니다. "
            "'A young woman with …' 처럼 성별로 시작하세요.")
    return failures


# intro 의 전환어. 이게 없으면 "평범한 기대 → 이례" 구조가 아니라 그냥 소개문이다.
INTRO_PIVOTS = [
    "그런데", "하필", "알고 보니", "눈 떠보니", "인 줄 알았", "줄 알았는데",
    "했는데", "인데", "만에", "직전", "그러나", "하지만", "정신을 차려보니",
    "깨어나 보니", "이번엔", "그리고 하필", "그랬더니", "라더니", "더니",
]

# ④ 비대칭 카드가 "각오"로 끝나면 정보가 아니다. 판이 안 뒤집힌다.
RESOLVE_WORDS = [
    "결심", "다짐", "각오", "마음먹", "노력하기로", "살아가기로", "지키기로",
    "시작하기로", "맹세", "다시 태어나기로",
]

BEAT_COUNT = 4
RELATION_FIELDS = ("to_everyone", "to_one_only", "anchor", "exception_reason")


def gate_card(p1: dict) -> list:
    """상업 훅 공식 게이트 — 목록에서 손가락이 멈추는 형태인지를 코드로만 본다.

    "이 카드가 재미있는가" 는 묻지 않는다. 그건 --card-mix 에서 사람이 판정한다.
    여기서 보는 것은 공식이 아예 성립하지 않는 경우들이다:
      - intro 에 전환어가 없다 (기대 → 이례 구조가 아니라 그냥 소개문이다)
      - rank 에 괄호가 없다 (직함일 뿐 시한폭탄이 없다)
      - 낙차의 예외가 "어떤 소녀" 다 (공동 주인공이 아니라 소품이다)
      - fate_beats 가 4개가 아니거나 ④가 각오다 (판이 안 뒤집힌다)
    """
    failures = []

    # ---- intro : 이 카드의 전부
    intro = str(p1.get("intro") or "").strip()
    if not intro:
        failures.append(
            "intro 가 비어있습니다. 목록에서 손가락을 멈추게 하는 한 줄이 없으면 "
            "이 카드는 없는 것입니다.")
    else:
        if len(re.sub(r"\s+", "", intro)) < 12:
            failures.append(f"intro 가 '{intro}' 로 너무 짧습니다. "
                            "[평범한 기대] 와 [이례] 가 둘 다 보여야 합니다.")
        if len(intro) > 70:
            failures.append(
                f"intro 가 {len(intro)}자입니다. 목록에서 한 줄로 잘리므로 "
                "70자 안에 들어와야 합니다.")
        if not any(w in intro for w in INTRO_PIVOTS):
            failures.append(
                f"intro 에 전환어가 없습니다: '{intro}'\n"
                "  '그런데 / 하필 / 알고 보니 / 눈 떠보니 / ~인 줄 알았는데' 같은 "
                "전환이 있어야 [평범한 기대] 에서 [이례] 로 꺾입니다. "
                "전환이 없으면 훅이 아니라 소개문입니다.")

    # ---- rank : 괄호가 시한폭탄을 건다
    rank = str(p1.get("rank") or "").strip()
    irony = str(p1.get("rank_irony") or "").strip()
    if not rank:
        failures.append("rank 가 비어있습니다. 직함 한 줄을 쓰세요.")
    elif not irony:
        failures.append(
            "rank_irony 가 비어있습니다. 직함만으로는 신분 소개입니다 — "
            "그 직함을 무너뜨리는 것을 괄호 안에 거세요. "
            "(예: 공작가의 장녀 (3권에서 사망 예정))")
    elif _stem(irony) not in _stem(rank):
        failures.append(
            f"rank '{rank}' 안에 rank_irony '{irony}' 가 들어 있지 않습니다. "
            "rank 는 '직함 (아이러니)' 형태의 완성된 한 줄이어야 합니다.")

    # ---- 낙차의 예외 — 이 공식에서 가장 오래 가는 장치
    rel = p1.get("relational_gap")
    if not isinstance(rel, dict):
        failures.append(
            "relational_gap 이 없습니다. 모두에게 X / 단 한 사람에게만 Y 구조를 "
            "쓰거나, 1인 완결형이면 solo: true 와 solo_reason 을 쓰세요.")
    elif rel.get("solo") is True:
        if is_blank(rel.get("solo_reason")):
            failures.append(
                "solo: true 인데 solo_reason 이 비어있습니다. 왜 관계형 낙차가 "
                "불가능한 설계인지 쓰세요 — 관계형이 가능하면 그쪽이 더 오래 갑니다.")
    else:
        empty = [k for k in RELATION_FIELDS if is_blank(rel.get(k))]
        if empty:
            failures.append(
                f"relational_gap 의 {empty} 가 비어있습니다. "
                "네 칸이 다 있어야 낙차가 재발동합니다.")
        anchor = str(rel.get("anchor") or "").strip()
        if anchor and len(re.sub(r"\s+", "", anchor)) < 4:
            failures.append(
                f"relational_gap.anchor 가 '{anchor}' 뿐입니다. "
                "그 한 사람은 공동 주인공급입니다 — 이름과 정체를 주세요.")
        reason = str(rel.get("exception_reason") or "").strip()
        if reason and len(re.sub(r"\s+", "", reason)) < 12:
            failures.append(
                f"exception_reason 이 '{reason}' 로 너무 짧습니다. "
                "'운명이라서' 는 이유가 아닙니다 — 구체적 사연 한 문장을 쓰세요.")

    if is_blank(p1.get("wish_fulfillment")):
        failures.append(
            "wish_fulfillment 가 비어있습니다. 독자가 이 카드를 골라서 무엇이 되는지 "
            "한 문장으로 쓰세요.")

    # ---- fate_beats : ④가 이 블록의 전부다
    beats = p1.get("fate_beats")
    if not isinstance(beats, list) or len(beats) != BEAT_COUNT:
        failures.append(
            f"fate_beats 가 {len(beats) if isinstance(beats, list) else 0}개입니다. "
            f"정확히 {BEAT_COUNT}개여야 합니다 (①상황 ②사건 ③위협 ④비대칭 카드).")
    else:
        short = [i for i, b in enumerate(beats, 1)
                 if len(re.sub(r"\s+", "", str(b or ""))) < 10]
        if short:
            failures.append(f"fate_beats {short} 번이 너무 짧습니다. 각 비트는 "
                            "무슨 일이 있었는지가 보이는 한 문장이어야 합니다.")
        last = str(beats[-1] or "")
        hit = [w for w in RESOLVE_WORDS if w in last]
        if hit:
            failures.append(
                f"fate_beats ④ 가 각오로 끝납니다({hit}): '{last}'\n"
                "  ④는 ③의 위협에 대해 나만 가진 것입니다. 각오는 정보가 아니라 "
                "태도이고, 판을 뒤집지 못합니다.")

    # ---- quote : 3단 공식
    quote = str(p1.get("quote") or "").strip()
    if not quote:
        failures.append("quote 가 비어있습니다. 3단 공식 대사를 쓰세요.")
    elif len(re.sub(r"\s+", "", quote)) < 30:
        failures.append(
            f"quote 가 '{quote}' 로 너무 짧습니다. 인정 → 전환 → 여운 세 단이 "
            "들어가려면 2~3문장은 됩니다.")

    if is_blank(p1.get("personality")):
        failures.append("personality 가 비어있습니다. 성격 한 줄을 쓰세요.")

    return failures


# 그림으로 그릴 수 없는 평가어. visual_hook 에 나오면 훅이 아니라 감상이다.
VISUAL_BANNED = [
    "잘생", "예쁘", "예쁜", "이쁘", "이쁜", "아름다", "멋있", "멋진", "매력적",
    "미남", "미녀", "훈남", "훈녀", "미모", "카리스마", "섹시", "handsome",
    "beautiful", "pretty", "gorgeous", "attractive", "charismatic",
]

# 첫 컷에서 눈이 걸리는 자리. 하나도 없으면 "그릴 수 있는 훅"이 아니다.
VISUAL_ANCHORS = [
    "눈", "눈동자", "눈매", "머리", "머리카락", "앞머리", "이마", "얼굴", "입",
    "입꼬리", "입술", "이빨", "송곳니", "턱", "뺨", "볼", "코", "귀", "목", "어깨",
    "등", "팔", "손", "손목", "손가락", "손톱", "다리", "발", "허리", "가슴",
    "피부", "흉터", "상처", "문신", "점", "비늘", "털", "수염", "안경", "옷",
    "코트", "재킷", "제복", "교복", "장갑", "붕대", "가면", "모자", "신발", "그림자",
]

# appearance 가 이미지 생성기에 바로 들어가려면 이 넷은 있어야 한다.
APPEARANCE_SLOTS = {
    "hair": ("hair", "bob", "ponytail", "braid", "bald", "bangs"),
    "eyes": ("eye", "eyes", "gaze", "pupil"),
    "build": ("build", "frame", "body", "tall", "short", "slim", "slender",
              "stocky", "lean", "broad", "petite", "athletic", "figure"),
    "clothing": ("wear", "wears", "wearing", "dressed", "coat", "jacket",
                 "uniform", "shirt", "dress", "robe", "suit", "outfit",
                 "hoodie", "armor", "cloak"),
}

HANGUL_RE = re.compile(r"[가-힣]")
# 통째로 한글인 이름. 성을 떼도 되는지 판단할 때 쓴다 — HANGUL_RE 는 한 글자만
# 보는 패턴이라 fullmatch 에 쓸 수 없다.
HANGUL_NAME_RE = re.compile(r"[가-힣]+")

# ---- 캐릭터 시트 (P1 외형 사양 -> 이미지)
#
# 시트를 story-harness 가 만드는 이유: 외형 사양(appearance_en·design_details·
# color_palette·expression_set)이 P1 에서 나오기 때문이다. 사양을 만든 쪽이 시트까지
# 만들고, webtoon-harness 는 완성된 시트를 받아 쓰기만 한다. 두 하네스가 각자 시트를
# 만들면 같은 인물이 두 벌 생긴다.
#
# 한 장에 4면도+표정+디테일을 다 넣지 않고 셋으로 나누는 이유: 이미지 모델은 한 장
# 안에 요구가 많을수록 각각을 뭉갠다. 4면도는 비율 일관성이, 표정은 얼굴 크기가,
# 디테일은 확대가 필요한데 셋의 요구가 서로 충돌한다.
# 기본은 **한 장**이다. 실제 캐릭터 시트가 그렇게 생겼고, 호출도 1/3 로 준다.
# 셋으로 쪼개는 --split 은 남겨 둔다: 한 장에 요구가 많을수록 이미지 모델이 각각을
# 뭉개기 때문에, 4면도의 비율이나 디테일 확대가 뭉개지면 쪼개서 다시 뽑을 수 있어야 한다.
UNIFIED_KIND = "sheet"
SPLIT_KINDS = ("turnaround", "expressions", "details")
CHARSHEET_KINDS = SPLIT_KINDS          # 옛 이름 (--split 에서 쓴다)
ALL_KINDS = (UNIFIED_KIND,) + SPLIT_KINDS
CHARSHEET_LABELS = {
    "sheet": "캐릭터 시트 (4면도 + 표정 + 디테일 + 색상 칩)",
    "turnaround": "4면도 (정면·3/4·측면·후면)",
    "expressions": "표정 6종",
    "details": "고정 요소 확대 + 색상 칩",
}
# 4면도와 표정은 가로로 늘어놓아야 해서 가로가 길어야 한다.
CHARSHEET_SIZES = {          # OpenAI 이미지 API 용 (픽셀)
    "sheet": "1536x1024",
    "turnaround": "1536x1024",
    "expressions": "1536x1024",
    "details": "1024x1024",
}
CHARSHEET_RATIOS = {         # Gemini 용 (비율). 시트는 가로로 넓어야 한다
    "sheet": "16:9",
    "turnaround": "16:9",
    "expressions": "16:9",
    "details": "1:1",
}

# 통합 시트는 webtoon-harness 가 읽는다 (charsheet.py 의 KINDS 에 "sheet",
# config.yaml 에 조건 S). 컷을 그릴 때 이렇게 이어진다:
#     python run.py --run-id <id> --episode 1 -c S
UNIFIED_HANDOFF_NOTE = (
    "이 시트는 webtoon-harness 의 조건 **S** 가 읽습니다 (sheets: [\"sheet\"]).\n"
    "  컷 그리기:  cd ../webtoon-harness && python run.py --run-id {run_id} "
    "--episode 1 -c S\n"
    "  세 장으로 쪼개 쓰려면 --split 으로 다시 뽑고 조건 C+ 를 쓰세요."
)
# 시트는 **컷과 같은 모델**로 뽑는다. 다른 모델로 뽑으면 같은 스타일 문구를 넣어도
# 그림체가 어긋나서, 그 시트를 레퍼런스로 쓰는 의미가 없어진다.
# (실제로 gpt-image-1 은 얼굴 비율이 크고 이목구비가 웹툰 양식이 아니었다.)
DEFAULT_IMAGE_PROVIDER = "gemini"
DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"
DEFAULT_OPENAI_IMAGE_MODEL = "gpt-image-2"      # gpt-image-1 은 2026-10-23 종료
IMAGE_PROVIDERS = ("gemini", "openai")
OPENAI_IMAGE_QUALITIES = ("low", "medium", "high")
DEFAULT_OPENAI_IMAGE_QUALITY = "medium"
# 1536x1024 medium 기준 장당 단가. 다른 품질은 요금표가 달라서 적지 않는다 —
# 모르는 숫자를 적어 두면 비용 표시가 거짓말이 된다. .env 로 덮어쓸 수 있다.
OPENAI_IMAGE_COST_USD = {"medium": 0.041}
# 후보 1장이 기본이다. 예전에는 2장을 뽑아 사람이 pick.html 에서 골랐는데,
# 지금 흐름은 "일단 한 장 보고 마음에 안 들면 다시 뽑는다" 에 가깝다.
# 시트 한 장이 곧 호출 한 번이라, 기본값이 2면 매번 두 배를 쓴다.
# 비교해서 고르고 싶으면 --candidates 2 를 붙이면 된다.
DEFAULT_CHARSHEET_CANDIDATES = 1

# 시트를 컷과 다른 모델로 뽑으면 같은 스타일 문구를 넣어도 그림체가 어긋난다.
# 기본(gemini)에서는 이 문제가 없고, --provider openai 를 고른 경우에만 경고한다.
CROSS_MODEL_WARNING = (
    "시트를 OpenAI 로, 컷은 Gemini 로 그리게 됩니다. 같은 스타일 문구를 넣어도 두 "
    "모델의 그림체는 다를 수 있습니다 — 레퍼런스 시트는 컷과 같은 모델로 뽑아야 "
    "의미가 있습니다.\n"
    "  비교 목적이 아니면 --provider gemini (기본) 를 쓰세요.")

# 스타일 문구는 **webtoon-harness 가 원본**이다. 여기서 하드코딩하지 않고 읽어 온다.
# 시트가 다른 그림체로 나오면 그걸 레퍼런스로 그리는 컷이 전부 어긋나기 때문이다.
# 아래 값은 읽기에 실패했을 때 대조하는 기준일 뿐, 프롬프트에 쓰는 값이 아니다.
WEBTOON_HARNESS_DIR = env("WEBTOON_HARNESS_DIR") or str(ROOT.parent / "webtoon-harness")
EXPECTED_STYLE_SUFFIX = ("Korean webtoon style, soft flat colors, thin clean lines, "
                         "minimal shading")


# 이미지 모델에 매번 같은 말로 넣는 공통 지시. 시트는 작품이 아니라 **자료**다.
SHEET_COMMON_EN = (
    "Character reference sheet for a Korean webtoon production. "
    "Flat even lighting, pure white background (#FFFFFF), no cast shadows, "
    "no props, no text, no labels, no watermark, no logo, no signature. "
    "Clean line art with flat colors. The character is identical in every "
    "figure on the sheet."
)

# 이미지 모델은 안 쓰인 칸을 컷마다 다르게 그린다. hair/eyes/build/clothing 은
# 그림을 고정하는 칸이고, impression/element 는 카드에 보이는 칸이다.
# element 를 빼지 않는 이유: 블라인드 카드 시험(card_view)이 샘플 카드와 이 칸을
# 나란히 놓고 비교한다. 여기가 비면 생성 카드만 빈칸이라 내용을 보기 전에 골라진다.
APPEARANCE_KEYS = ("hair", "eyes", "build", "clothing", "impression", "element")

# design_details — 매 컷에 유지될 물리적 특징. 그릴 수 없는 말이 섞이면 반려한다.
DESIGN_DETAIL_MIN, DESIGN_DETAIL_MAX = 3, 5
DESIGN_ABSTRACT = [
    "분위기", "느낌", "아우라", "기운", "인상", "무드", "이미지", "포스",
    "우아", "신비", "고급", "세련", "청순", "몽환", "카리스마", "아름다",
    "예쁘", "예쁜", "잘생", "멋있", "멋진", "매력", "매혹", "섹시",
    "차가운 눈빛", "따뜻한 눈빛",   # 눈빛은 표정이지 고정 요소가 아니다
    "aura", "vibe", "elegant", "mysterious", "charming",
]

# color_palette — 그리는 사람이 매번 같은 색을 쓰게 하는 여섯 칸.
#
# 값은 **영문 이름 + hex** 다: "bright gold (#F0C44C)".
# 한글 색이름("밝은 금색")으로 두면 두 가지가 깨진다.
#   - 이미지 모델이 정확히 해석한다는 보장이 없다. 프롬프트의 나머지는 전부 영문인데
#     색만 한글이면 그 자리만 모델의 짐작에 맡기는 것이다.
#   - webtoon-harness 는 p1.json 의 color_palette 를 **그대로** 프롬프트에 박는다
#     (charsheet.py 의 PALETTE_HEAD). 그래서 여기 형식이 곧 컷의 형식이다.
#     두 하네스가 다른 형식으로 색을 지정하면 같은 인물이 두 색으로 나온다.
PALETTE_KEYS = ("hair", "eyes", "skin", "outfit_main", "outfit_sub", "accent")
HEX_RE = re.compile(r"#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})\b")

# 한글 색이름 -> (영문, hex). P1 이 hex 를 내기 전에 만들어진 카드를 위한 것이다.
# 완전한 사전일 수 없다 — 못 찾으면 원문을 남기고 경고한다. 조용히 아무 색이나
# 집어넣는 것보다 사람이 보고 고치는 편이 낫다.
KO_COLOR_BASE = {
    "빨강": ("red", "#E5342B"), "빨간": ("red", "#E5342B"), "적색": ("red", "#E5342B"),
    "주황": ("orange", "#F08030"), "오렌지": ("orange", "#F08030"),
    "노랑": ("yellow", "#F2C744"), "노란": ("yellow", "#F2C744"),
    "금색": ("gold", "#D4AF37"), "황금": ("gold", "#D4AF37"),
    "초록": ("green", "#3FA34D"), "녹색": ("green", "#3FA34D"),
    "연두": ("yellow green", "#9ACD32"),
    "파랑": ("blue", "#2E6FDB"), "파란": ("blue", "#2E6FDB"), "청색": ("blue", "#2E6FDB"),
    "하늘": ("sky blue", "#7FB6E8"), "하늘색": ("sky blue", "#7FB6E8"),
    "남색": ("navy", "#1F3A6E"), "군청": ("navy", "#1F3A6E"),
    "청록": ("teal", "#2E8B8B"),
    "보라": ("purple", "#7B4EA8"), "자주": ("magenta", "#A6246E"),
    "분홍": ("pink", "#F3A6B8"), "핑크": ("pink", "#F3A6B8"),
    "갈색": ("brown", "#7A5230"), "밤색": ("brown", "#7A5230"), "고동": ("brown", "#5A3A22"),
    "검정": ("black", "#1C1B19"), "검은": ("black", "#1C1B19"),
    "흑색": ("black", "#1C1B19"), "먹색": ("ink black", "#22252A"),
    "하양": ("white", "#FFFFFF"), "흰": ("white", "#FFFFFF"), "백색": ("white", "#FFFFFF"),
    "회색": ("grey", "#8A8A8A"), "잿빛": ("ash grey", "#8E8B85"),
    "은색": ("silver", "#C8CCD0"), "은백": ("silver white", "#DCE0E4"),
    "살구": ("apricot", "#F1C6A7"), "베이지": ("beige", "#E4D5BF"),
    "카키": ("khaki", "#7A7A4E"), "크림": ("cream", "#F5EBD0"),
    "상아": ("ivory", "#F1E6D0"), "구리": ("copper", "#B87333"),
}
# 수식어 -> (영문 접두사, 밝기 배수)
KO_COLOR_MOD = {
    "밝은": ("bright", 1.25), "환한": ("bright", 1.25),
    "연한": ("light", 1.4), "옅은": ("pale", 1.5), "창백한": ("pale", 1.55),
    "짙은": ("deep", 0.7), "진한": ("deep", 0.7),
    "어두운": ("dark", 0.6), "깊은": ("dark", 0.65),
    "바랜": ("faded", 1.0), "빛바랜": ("faded", 1.0), "탁한": ("muted", 0.9),
}
FADED_MODS = ("바랜", "빛바랜", "탁한")

# expression_set — W7 컷 서술이 쓰는 표정 어휘. 6종으로 고정한다.
EXPRESSION_COUNT = 6
EXPRESSION_MIN_LEN = 6      # "불안" 두 글자짜리는 어휘가 아니라 라벨이다


def appearance_text(p1: dict) -> str:
    """appearance 를 영문 한 문단으로. 웹툰 하네스의 character_appearance 자리다."""
    text = str(p1.get("appearance_en") or "").strip()
    if text:
        return text
    ap = p1.get("appearance")
    if isinstance(ap, dict):    # 영문 칸이 없으면 네 칸을 이어 붙인다
        return ", ".join(str(ap.get(k) or "").strip()
                         for k in APPEARANCE_KEYS if str(ap.get(k) or "").strip())
    return str(ap or "").strip()


def gate_visual(p1: dict, b_word: str) -> list:
    """비주얼 훅 게이트 — 첫 컷에서 스크롤이 멈추는가를 코드로만 판정한다.

    "이 그림이 멋있는가" 는 묻지 않는다. 그건 사람이 그림을 봐야 아는 것이고,
    매 호출마다 기준이 달라진다. 여기서 보는 것은 셋뿐이다:
      - visual_hook 이 그릴 수 있는 말인가 (평가어가 아니라 이미지인가)
      - appearance_en 이 이미지 생성기에 그대로 들어갈 모양인가 (영문·네 항목)
      - visual_gap 이 B 와 실제로 묶여 있는가 (b_trait_word 가 그 안에 있는가)
    셋째가 핵심이다. 이것이 없으면 외형은 이야기와 무관한 장식이 된다.
    """
    failures = []

    hook = str(p1.get("visual_hook") or "").strip()
    appearance = str(p1.get("appearance_en") or "").strip()
    gap = str(p1.get("visual_gap") or "").strip()

    # ---- appearance (카드에 보이는 한국어 칸)
    ap = p1.get("appearance")
    if not isinstance(ap, dict):
        failures.append(
            "appearance 가 객체가 아닙니다. "
            f"{list(APPEARANCE_KEYS)} 를 각각 채우세요 (샘플 카드와 같은 형식).")
    else:
        empty = [k for k in APPEARANCE_KEYS if is_blank(ap.get(k))]
        if empty:
            failures.append(f"appearance 의 {empty} 칸이 비어있습니다.")

    # ---- design_details : 매 컷에 유지될 고정 요소
    details = p1.get("design_details")
    if not isinstance(details, list):
        details = []
    details = [str(d).strip() for d in details if str(d or "").strip()]
    if len(details) < DESIGN_DETAIL_MIN:
        failures.append(
            f"design_details 가 {len(details)}개입니다. {DESIGN_DETAIL_MIN}~"
            f"{DESIGN_DETAIL_MAX}개여야 합니다 — 고정 요소가 없으면 그리는 사람이 "
            "컷마다 다른 사람을 그립니다.")
    elif len(details) > DESIGN_DETAIL_MAX:
        failures.append(
            f"design_details 가 {len(details)}개입니다. {DESIGN_DETAIL_MAX}개 이하여야 "
            "합니다 — 다 지킬 수 없는 목록은 하나도 안 지켜집니다.")
    for d in details:
        hit = [w for w in DESIGN_ABSTRACT if w in d.lower()]
        if hit:
            failures.append(
                f"design_details 의 '{d}' 에 추상어 {hit} 가 있습니다. "
                "그릴 수 없는 것은 고정 요소가 아닙니다 — 위치·색·형태로 바꾸세요. "
                "(예: 우아한 분위기 → 왼쪽 소매의 노란 반사띠)")

    # ---- color_palette : 매번 같은 색이 나오게 하는 자리
    palette = p1.get("color_palette")
    if not isinstance(palette, dict):
        failures.append(
            f"color_palette 가 객체가 아닙니다. {list(PALETTE_KEYS)} 를 채우세요.")
    else:
        empty = [k for k in PALETTE_KEYS if is_blank(palette.get(k))]
        if empty:
            failures.append(
                f"color_palette 의 {empty} 칸이 비어있습니다 — 안 정한 색은 "
                "컷마다 달라집니다.")
        no_hex = [k for k in PALETTE_KEYS
                  if not is_blank(palette.get(k))
                  and not HEX_RE.search(str(palette.get(k)))]
        if no_hex:
            failures.append(
                f"color_palette 의 {no_hex} 에 hex 가 없습니다. "
                "'bright gold (#F0C44C)' 처럼 영문 이름과 #RRGGBB 를 함께 쓰세요 — "
                "색 이름만 있으면 이미지 모델마다 다른 색이 나오고, webtoon-harness 도 "
                "이 값을 그대로 컷 프롬프트에 박습니다.")

    # ---- expression_set : W7 컷 서술이 쓸 표정 어휘
    faces = p1.get("expression_set")
    if not isinstance(faces, list):
        faces = []
    faces = [str(f).strip() for f in faces if str(f or "").strip()]
    if len(faces) != EXPRESSION_COUNT:
        failures.append(
            f"expression_set 이 {len(faces)}개입니다. 정확히 {EXPRESSION_COUNT}개여야 "
            "합니다 — 컷 서술이 여기 적힌 표정만 씁니다.")
    thin = [f for f in faces if len(re.sub(r"\s+", "", f)) < EXPRESSION_MIN_LEN]
    if thin:
        failures.append(
            f"expression_set 의 {thin} 이 감정 이름뿐입니다. 무엇이 보이는지까지 "
            "쓰세요 — 이름만 있으면 컷 서술이 다시 추상어로 돌아갑니다. "
            "(예: 불안 → 겁먹음 — 동공이 커지고 턱이 굳는다)")

    # ---- visual_hook
    if not hook:
        failures.append(
            "visual_hook 이 비어있습니다. 첫 컷에서 독자가 멈추는 이유를 한 문장으로 쓰세요.")
    else:
        if len(re.sub(r"\s+", "", hook)) < 15:
            failures.append(
                f"visual_hook 이 '{hook}' 로 너무 짧습니다. "
                "무엇이 어떻게 보이는지가 들어간 한 문장이어야 합니다.")
        hit = [w for w in VISUAL_BANNED if w in hook.lower()]
        if hit:
            failures.append(
                f"visual_hook 에 평가어 {hit} 가 있습니다. 그것은 그림이 아니라 감상입니다. "
                "무엇이 어떻게 보이길래 그런지를 구체적 이미지로 바꾸세요. "
                "(예: 잘생긴 남자 → 은발이 눈을 반쯤 덮은, 웃지 않는데 입꼬리가 올라간 남자)")
        if not any(a in hook for a in VISUAL_ANCHORS):
            failures.append(
                "visual_hook 에 눈이 걸릴 자리가 없습니다. 신체 부위·복장·형태 중 "
                "최소 하나는 구체적으로 지목해야 그릴 수 있습니다.")

    # ---- appearance_en : 웹툰 하네스로 그대로 넘어가는 문단
    if not appearance:
        failures.append(
            "appearance_en 이 비어있습니다. 이미지 생성기에 그대로 넣을 영문 외형 문단이 "
            "여기서 나오지 않으면, 뒤 하네스가 사람 손으로 채워야 합니다.")
    else:
        if HANGUL_RE.search(appearance):
            failures.append(
                "appearance_en 에 한글이 섞여 있습니다. 이미지 생성기에 그대로 들어가는 "
                "문단이므로 영문으로만 쓰세요.")
        if len(appearance) < 80:
            failures.append(
                f"appearance_en 이 {len(appearance)}자입니다. 헤어·눈·체형·복장·분위기를 "
                "다 담으려면 그보다 깁니다.")
        low = appearance.lower()
        blank_slots = [name for name, words in APPEARANCE_SLOTS.items()
                       if not any(w in low for w in words)]
        if blank_slots:
            failures.append(
                f"appearance_en 에 {blank_slots} 가 없습니다. 이미지 모델은 안 쓰인 항목을 "
                "매번 다르게 그립니다 — 그 자리가 컷마다 흔들리는 자리입니다.")

    # ---- visual_gap : 여기가 그림과 이야기가 묶이는 지점이다
    if not gap:
        failures.append(
            "visual_gap 이 비어있습니다. 외형이 주는 첫인상을 B 가 어떻게 배신하는지 "
            "쓰지 않으면, 외형은 이야기와 무관한 장식이 됩니다.")
    else:
        if len(re.sub(r"\s+", "", gap)) < 20:
            failures.append(f"visual_gap 이 '{gap}' 로 너무 짧습니다. "
                            "외형의 첫인상과 그것이 깨지는 방식이 둘 다 보여야 합니다.")
        b_stem = _stem(b_word)
        if b_stem and b_stem not in _stem(gap):
            failures.append(
                f"visual_gap 안에 b_trait_word '{b_word}' 가 그대로 들어 있지 않습니다. "
                "외형의 낙차는 B 로 갚아야 합니다 — 아무 대비나 붙이면 그림과 이야기가 "
                "따로 놉니다.")

    return failures


def gate_p2(p2: dict, banned: list) -> list:
    """실패 사유 목록. 비어있으면 통과. P3 를 호출하기 전에 코드만으로 판정한다."""
    failures = []

    field, events, min_total, min_each = collect_events(p2)
    if field is None:
        failures.append(
            "사건 목록이 없습니다. generative_check(권장) 또는 causal_chains 배열이 필요합니다.")
        events = []

    if field and len(events) < min_total:
        failures.append(
            f"{field} 가 {len(events)}개입니다. {min_total}개 이상이어야 합니다.")

    counts = {"rule": 0, "cost": 0, "irony": 0}
    for item in events:
        if isinstance(item, dict):
            s = normalize_source(item.get("source"))
            if s in counts:
                counts[s] += 1
    if field:
        for required in ("rule", "cost", "irony"):
            if counts[required] < min_each:
                failures.append(
                    f"{field} 의 source '{required}' 가 {counts[required]}개입니다. "
                    f"{min_each}개 이상이어야 합니다. (현재 rule/cost/irony = "
                    f"{counts['rule']}/{counts['cost']}/{counts['irony']})")

    for conn in collect_connectors(p2):
        hit = [b for b in banned if b.lower() in conn.lower()]
        if hit:
            failures.append(
                f"connector '{conn}' 가 단순 나열 접속사({', '.join(hit)})입니다. "
                "인과 접속으로 바꾸세요.")

    fs = p2.get("forbidden_subversion")
    if fs is None:
        failures.append("forbidden_subversion 이 없습니다.")
    elif isinstance(fs, list):
        if not [x for x in fs if str(x).strip()]:
            failures.append("forbidden_subversion 이 비어있습니다.")
    elif isinstance(fs, str):
        if not fs.strip():
            failures.append("forbidden_subversion 이 비어있습니다.")
    else:
        failures.append("forbidden_subversion 의 형식이 배열/문자열이 아닙니다.")

    # 장르는 배신하되 트로프는 지킨다. 지킬 것을 안 적으면 장르 자체를 버리게 된다.
    if is_blank(p2.get("genre_promise")):
        failures.append(
            "genre_promise 가 비어있습니다. 독자가 이 장르를 골라서 보러 온 것 "
            "(빙의물이면 빙의, 헌터물이면 각성)을 적으세요. 그건 지켜야 합니다 — "
            "장르 자체를 배신하면 배신이 아니라 사기입니다.")

    axis = normalize_axis(p2.get("subversion_axis"))
    if not axis:
        failures.append(
            f"subversion_axis 가 '{p2.get('subversion_axis')}' 입니다. "
            f"{' | '.join(SUBVERSION_AXES)} 중 하나여야 합니다. "
            "축을 여러 개 섞으면 아무것도 안 남습니다.")

    return failures


# 반전 축 6종 — 샘플 카드 24장의 반전분(04~06)에서 실제로 쓰인 축들.
SUBVERSION_AXES = ("성별", "종족", "위상", "능력", "정체", "관계")

AXIS_ALIASES = {
    "성별": ("성별", "gender", "sex"),
    "종족": ("종족", "종", "race", "species"),
    "위상": ("위상", "신분", "지위", "위치", "status", "rank"),
    "능력": ("능력", "권능", "스킬", "power", "ability", "skill"),
    "정체": ("정체", "정체성", "소속", "identity", "side"),
    "관계": ("관계", "관계성", "포지션", "relation", "relationship"),
}


def normalize_axis(value) -> str:
    """자유롭게 적힌 축 이름 → 6종 중 하나. 못 맞추면 빈 문자열."""
    text = str(value or "").strip().lower()
    if not text:
        return ""
    for canon, words in AXIS_ALIASES.items():
        if any(w in text for w in words):
            return canon
    return ""


# ---------------------------------------------------------------- P3 판독

YES_TOKENS = {"yes", "y", "true", "pass", "통과", "예", "ok"}
NO_TOKENS = {"no", "n", "false", "fail", "아니오", "아니요", "탈락"}

# 모델이 null 대신 문자열을 뱉는 일이 잦다 (문서 10-3 각주).
EMPTY_TOKENS = {"", "null", "none", "n/a", "na", "-", "없음", "없다", "해당없음", "nil"}


def is_blank(value) -> bool:
    """null · "null" · "없음" 을 모두 빈 값으로 본다."""
    if value is None:
        return True
    if isinstance(value, str):
        return value.strip().lower() in EMPTY_TOKENS
    if isinstance(value, (list, tuple, dict)):
        return not value
    return False


def verdict_is_yes(value) -> bool:
    if value is True:
        return True
    if isinstance(value, dict):
        return verdict_is_yes(value.get("verdict", value.get("result")))
    return str(value).strip().lower() in YES_TOKENS


# "해당 없음" 은 통과도 탈락도 아니다 (1인 완결형 선언 등).
SKIP_TOKENS = {"skip", "n/a", "na", "해당없음", "해당 없음", "미해당", "생략"}


def verdict_is_skip(value) -> bool:
    if isinstance(value, dict):
        return verdict_is_skip(value.get("verdict", value.get("result")))
    return str(value).strip().lower() in SKIP_TOKENS


def summarize_p3(p3: dict) -> dict:
    """checks 를 정규화하고 문서 10-3 판정 도출 규칙표를 코드가 계산한다.

    | 체크에 no 1개 이상                                    | 별로   | 재생성 |
    | 전부 yes + capture=yes + first_question 있음 + 클리셰 없음 | 맛있음 | 진행   |
    | 전부 yes 지만 capture=no 또는 클리셰 감지               | 보통   | 사람 확인 |
    """
    items = []
    checks = p3.get("checks")

    if isinstance(checks, dict):
        for name, verdict in checks.items():
            items.append((str(name), verdict))
    elif isinstance(checks, list):
        for i, c in enumerate(checks):
            if isinstance(c, dict):
                name = str(c.get("name") or c.get("item") or f"check_{i+1}")
                verdict = c.get("verdict", c.get("result", c.get("pass")))
            else:
                name, verdict = f"check_{i+1}", c
            items.append((name, verdict))
    else:
        # checks 키가 없으면 최상위에서 yes/no 값을 가진 키를 긁는다
        for name, verdict in p3.items():
            if isinstance(verdict, str) and verdict.strip().lower() in YES_TOKENS | NO_TOKENS:
                items.append((str(name), verdict))

    passed, failed, warned, skipped = [], [], [], []
    for name, verdict in items:
        if verdict_is_skip(verdict):
            # 1인 완결형 선언(relational_gap.solo)처럼 애초에 해당 없는 항목.
            # 통과로 세지도, 탈락으로 세지도 않는다. 기록만 남긴다.
            skipped.append(name)
        elif verdict_is_yes(verdict):
            passed.append(name)
        elif name in P3_ADVISORY_CHECKS:
            # 경고 항목: 기록만 하고 재생성·판정에는 쓰지 않는다.
            warned.append(name)
        else:
            failed.append(name)

    capture_raw = p3.get("capture_test")
    capture_yes = verdict_is_yes(capture_raw)
    capture_sentence = p3.get("capture_sentence")
    if is_blank(capture_sentence):
        # capture_test 칸에 문장을 그대로 써버린 판본도 받아준다
        capture_sentence = "" if isinstance(capture_raw, bool) or \
            str(capture_raw).strip().lower() in YES_TOKENS | NO_TOKENS else str(capture_raw or "")
    capture_sentence = str(capture_sentence or "").strip()

    first_q = p3.get("first_question")
    has_first_q = not is_blank(first_q)

    cliche = p3.get("cliche_detected")
    if cliche is None and "cliche_check" in p3:      # 초기 판본 키
        cliche = p3.get("cliche_check")
    has_cliche = not is_blank(cliche)

    if failed:
        verdict_label = "별로"
    elif capture_yes and has_first_q and not has_cliche:
        verdict_label = "맛있음"
    else:
        verdict_label = "보통"

    target = str(p3.get("target_stage") or "").strip().upper()
    if target not in ("P1", "P2"):
        target = "P2"   # 미지정이면 프리미스부터 다시

    return {
        "total": len(items),
        "passed": passed,
        "failed": failed,
        "warned": warned,
        "skipped": skipped,
        "verdict": verdict_label,
        "capture_yes": capture_yes,
        "capture_test": "yes" if capture_yes else "no",
        "capture_sentence": capture_sentence,
        "first_question": "" if not has_first_q else str(first_q).strip(),
        "cliche_present": has_cliche,
        "cliche_detected": cliche if has_cliche else None,
        "directive": "" if is_blank(p3.get("regeneration_directive"))
                     else str(p3.get("regeneration_directive")).strip(),
        "target_stage": target,
    }


# ---------------------------------------------------------------- 장면 점검 8항목
#
# 문서 6장. 판정은 전부 코드가 한다 — 모델의 자기 신고를 믿지 않는다.
# 걸린 항목은 그대로 수정 지시({fix_directive})로 조립되어 SCENE 재생성에 들어간다.

LABEL_WORDS = [
    "우스꽝스러", "어설픈", "어설프", "웃긴", "묘한", "의미심장",
    "어색한 침묵", "개그를 던졌", "사이에서 갈등", "한 여정",
]

# "X를 발견했다 / X가 놓여 있었다" — 금지어가 아니라 문형 하나만 본다.
DISCOVERY_RE = [
    re.compile(r"([가-힣A-Za-z0-9]{2,10})\s*(?:을|를)\s*발견"),
    re.compile(r"([가-힣A-Za-z0-9]{2,10})\s*(?:이|가)\s*놓여\s*있"),
    re.compile(r"([가-힣A-Za-z0-9]{2,10})\s*(?:이|가)\s*눈에\s*(?:띄|들어)"),
    re.compile(r"([가-힣A-Za-z0-9]{2,10})\s*(?:이|가)\s*나타났"),
]

TOKEN_RE = re.compile(r"[가-힣A-Za-z0-9]{2,}")
QUOTE_CHARS = ('"', "“", "”", "「", "『", "“")


def tokens_of(text: str) -> list:
    return TOKEN_RE.findall(text or "")


def token_survives(token: str, haystack: str) -> bool:
    """조사가 붙어 형태가 달라져도 어간 2글자 이상이 남아 있으면 살아있다고 본다."""
    for n in range(len(token), 1, -1):
        if token[:n] in haystack:
            return True
    return False


def char_ngrams(text: str, n: int = 3) -> set:
    flat = re.sub(r"\s+", "", text or "")
    return {flat[i:i + n] for i in range(max(0, len(flat) - n + 1))}


def overlap_ratio(a: str, b: str) -> float:
    ga, gb = char_ngrams(a), char_ngrams(b)
    if not ga or not gb:
        return 0.0
    return len(ga & gb) / min(len(ga), len(gb))


def discovered_nouns(text: str) -> list:
    out = []
    for rx in DISCOVERY_RE:
        out.extend(rx.findall(text or ""))
    return out


# 회피는 선택처럼 보이지만 판을 그대로 둔다. 첫 장면이 이걸로 끝나면 독자에게는
# 아무 일도 안 일어난 것이다.
#
# 여기 없는 것들에 주의: 거절한다·숨긴다·거짓말한다·붙잡는다는 회피가 아니다 —
# 상대에게 무언가를 하는 행동이라 판이 움직인다. '선을 긋는다' 도 뺐다. 그건
# 상대를 향한 행동이고, 이야기에 따라서는 그게 사건 그 자체다.
#
# 어간으로 적는다. '피해'(손해)나 '넘긴다'(책장을) 같은 다른 뜻에 걸리지 않게
# 하기 위해서다. 다만 한국어는 '-ㄴ다' 가 어간에 붙어 버려서(피하 → 피한다,
# 두 → 둔다, 넘기 → 넘긴다) 어간만 적으면 정작 종결형을 못 잡는다. 활용형까지
# 같이 적는 이유다.
EVASION_STEMS = ("피하", "피한", "피했", "회피", "도망",
                 "물러서", "물러선", "물러섰", "물러나", "물러난",
                 "자리를 뜨", "자리를 뜬", "자리를 피",
                 "거리를 두", "거리를 둔", "거리를 뒀", "거리감을 두",
                 "무시하", "무시한", "무시했",
                 "모른 척", "모른척", "못 본 척", "못본척",
                 "웃어넘", "받아넘", "받아 넘", "웃으며 넘", "웃고 넘",
                 "장난으로 넘", "농담으로 넘", "적당히 넘")
# '피하지 않는다' 는 정반대다. 부정형까지 잡으면 잘 쓴 원고를 되돌리게 된다.
NEGATIONS = ("지 않", "지않", "지 못", "지못", "지 말")


def evasive_words(text: str) -> list:
    """첫 장면 choice 에서 회피로 읽히는 표현. 부정형은 빼고 본다."""
    text = str(text or "")
    found = []
    for stem in EVASION_STEMS:
        at = text.find(stem)
        while at >= 0:
            tail = text[at + len(stem):at + len(stem) + 4]
            if not any(n in tail for n in NEGATIONS):
                found.append(stem)
                break
            at = text.find(stem, at + 1)
    return found

# '알게 된다' 는 인식이지 사건이 아니다. 세 장면이 전부 이걸로 끝나면 이 화에서
# 되돌릴 수 없게 된 일이 하나도 없다는 뜻이다.
PERCEPTION_WORDS = ("알게 된다", "알게 됐", "깨닫", "느낀다", "느끼게",
                    "보게 된다", "확인하게 된다", "생각하게 된다", "궁금해",
                    "의심하게 된다", "눈치챈다", "인식하", "짐작")


def name_forms(name: str) -> list:
    """'민시하' → ['민시하', '시하']. 본문은 성을 떼고 부르는 쪽이 흔하다.

    성을 안 떼면 훅에 '시하' 라고 쓴 것을 '주인공이 없다' 고 잘못 잡는다.
    두 글자 이름(성 없음)은 그대로 둔다 — 한 글자만 남기면 아무 데나 걸린다.
    """
    name = str(name or "").strip()
    if not name:
        return []
    forms = [name]
    if len(name) >= 3 and HANGUL_NAME_RE.fullmatch(name):
        forms.append(name[1:])
    return forms


def first_index(text: str, name: str) -> int:
    """text 안에서 그 사람이 처음 불린 위치. 없으면 -1."""
    spots = [text.find(f) for f in name_forms(name)]
    spots = [s for s in spots if s >= 0]
    return min(spots) if spots else -1


def check_scenes(scenes: list, idea: str, setting_text: str,
                 p1: dict = None, scene_obj: dict = None) -> list:
    """걸린 항목 리스트. 각 항목은 {no, name, detail, directive}."""
    hits = []
    texts = [str(s.get("text") or "") for s in scenes]
    joined = "\n".join(texts)

    def hit(no, name, detail, directive):
        hits.append({"no": no, "name": name, "detail": detail, "directive": directive})

    # 1. 사건이 사람의 선택에서 시작한다
    opening = texts[0][:150] if texts else ""
    bad_choices = [i + 1 for i, s in enumerate(scenes)
                   if "발견" in str(s.get("choice") or "")]
    if discovered_nouns(opening) or bad_choices:
        detail = []
        if discovered_nouns(opening):
            detail.append("첫 장면이 무언가를 발견·등장시키는 것으로 열립니다")
        if bad_choices:
            detail.append(f"{bad_choices} 번 장면의 choice 가 '발견'입니다")
        hit(1, "선택에서 시작", " / ".join(detail),
            "물건이 아니라 사람으로 장면을 여세요. 인물이 무엇을 하기로 했는지가 먼저고, "
            "물건·현상은 그 행동의 결과로 드러나야 합니다. "
            "choice 에는 거절한다/숨긴다/거짓말한다 같은 행동을 쓰세요.")

    # 2. 내가 준 설정이 장면에 살아 있다 (경고 — SCENE_BLOCKING_CHECKS 주석 참고)
    #
    # **길게 쓴 사람이 불리해지면 안 된다.** 예전에는 작가가 준 글의 낱말을
    # 전부 세고 그 절반이 장면에 남기를 요구했다. 세계관을 3000자로 적으면
    # 낱말이 676개가 되고 338개를 장면 셋에 우겨넣어야 통과였다 — 정성껏 쓸수록
    # 떨어지는 셈이다. 입력이 길다는 것은 참고할 것이 많다는 뜻이지 지켜야 할
    # 할당량이 늘었다는 뜻이 아니다.
    #
    # 그래서 **개수가 아니라 앞자리**를 본다. 작가가 무엇을 먼저 적었는지가
    # 그 사람이 가장 원한 것이고, 뒤의 세계관 설명은 재료지 체크리스트가 아니다.
    # 앞쪽 낱말 IDEA_KEEP_TOKENS 개만 보고, 그것도 절반이 아니라 하나도 안
    # 남았을 때만 말한다 — "골라서 썼다" 와 "통째로 무시했다" 를 가르는 선이다.
    idea_tokens = [t for t in tokens_of(idea) if len(t) >= 2][:IDEA_KEEP_TOKENS]
    if idea_tokens:
        survived = [t for t in idea_tokens if token_survives(t, joined)]
        if not survived:
            hit(2, "설정 증발",
                f"작가가 준 글의 앞부분 낱말 {len(idea_tokens)}개가 장면에 "
                "하나도 안 남았습니다.",
                "[작가가 처음 준 글]의 첫 문장이 가리키는 것을 장면 안에서 "
                "눈에 보이게 움직이게 하세요.")

    # 3. 말과 행동을 실제로 썼다 (라벨 서술)
    used_labels = sorted({w for w in LABEL_WORDS if w in joined})
    if used_labels:
        hit(3, "라벨 서술", f"라벨 표현 사용: {used_labels}",
            f"{', '.join(used_labels)} 같은 이름표를 지우고, 인물이 실제로 한 동작과 "
            "입 밖으로 낸 말을 그대로 쓰세요.")

    # 4. 장면마다 다른 일이 벌어진다
    for i in range(len(texts)):
        for j in range(i + 1, len(texts)):
            r = overlap_ratio(texts[i], texts[j])
            if r >= 0.30:
                hit(4, "장면 반복",
                    f"{i+1}번과 {j+1}번 장면의 글이 {r:.0%} 겹칩니다",
                    f"{i+1}번과 {j+1}번 장면에서 인물이 대응하는 방식 / 강도 / 상대 "
                    "중 최소 하나를 바꾸세요.")
                break
        else:
            continue
        break

    # 5. 대사가 있다
    silent = [i + 1 for i, t in enumerate(texts)
              if not any(q in t for q in QUOTE_CHARS)]
    if silent:
        hit(5, "대사 없음", f"{silent} 번 장면에 대사가 없습니다",
            f"{silent} 번 장면에 인물이 입 밖으로 낸 말을 최소 하나 넣으세요.")

    # 6. 새로 만든 것이 이야기를 굴린다
    for s in scenes:
        ne = s.get("new_element")
        if is_blank(ne):
            continue
        name = str(ne).strip()
        appears = sum(1 for t in texts if token_survives(name, t))
        if appears < 2:
            hit(6, "1회성 신규요소",
                f"새로 만들었다고 신고한 '{name}' 이 {appears}개 장면에만 나옵니다",
                f"'{name}' 을 최소 두 장면에서 작동하게 하거나, 아예 빼고 "
                "설정에 이미 있는 rule·cost·irony 에서 사건을 뽑으세요.")

    # 7. 사건을 때우려고 꺼낸 물건이 없다  ← 이 목록의 핵심
    known = (setting_text or "") + "\n" + (idea or "")
    for i, t in enumerate(texts):
        for noun in discovered_nouns(t):
            if token_survives(noun, known):
                continue                     # 설정에 있는 것이면 통과
            elsewhere = sum(1 for k, other in enumerate(texts)
                            if k != i and token_survives(noun, other))
            if elsewhere == 0:
                hit(7, "소품 구동",
                    f"{i+1}번 장면에서 설정에 없는 '{noun}' 이 발견·등장으로 사건을 열고 "
                    "다시 나오지 않습니다",
                    f"'{noun}' 을 빼고, 인물의 선택 때문에 감춰져 있던 것이 드러나는 "
                    "순서로 그 장면을 다시 짜세요.")

    # 8. 사건이 한 곳에서만 나오지 않는다
    sources = [normalize_source(s.get("source")) or str(s.get("source") or "")
               for s in scenes]
    if len(scenes) >= 3 and len(set(sources)) == 1:
        hit(8, "출처 단일",
            f"장면 {len(scenes)}개의 source 가 전부 '{sources[0]}' 입니다",
            "최소 한 장면은 다른 출처(rule / cost / irony / want_need)에서 "
            "사건을 뽑으세요.")

    # ---- 9~11. 여기부터는 '하지 마라' 가 아니라 '해야 한다' 다.
    #
    # 1~8 은 전부 금지 항목이라, 다 지켜도 아무 일 없는 장면이 통과한다. 실제로
    # 8개를 통과한 원고가 세 장면 내내 같은 사람을 밀어내기만 하고 끝났다.
    # 프롬프트 맨 위에 적어 둔 세 가지 목표를 코드도 봐야 한다.

    # 9. 첫 장면의 선택이 회피면 판이 움직이지 않는다
    first_choice = str(scenes[0].get("choice") or "") if scenes else ""
    evaded = evasive_words(first_choice)
    if evaded:
        hit(9, "회피로 시작",
            f"첫 장면의 choice 가 '{first_choice.strip()[:40]}' 입니다 "
            f"({', '.join(evaded[:3])})",
            "회피는 선택처럼 보이지만 판을 그대로 둡니다. 첫 장면에서 인물이 "
            "상대나 상황에 **무언가를 해서** 판이 달라지게 하세요. "
            "물러서더라도 무언가를 하면서 물러서게 하세요.")

    # 10. 훅이 조연을 주어로 물으면 독자가 따라갈 사람이 사라진다
    hook = str((scene_obj or {}).get("hook") or "").strip()
    hero = str((p1 or {}).get("name") or "").strip()
    cast = (p1 or {}).get("supporting_cast")
    if hook and hero:
        at_hero = first_index(hook, hero)
        others = []
        for c in (cast if isinstance(cast, list) else []):
            nm = str((c or {}).get("name") or "").strip()
            if not nm or nm == hero:
                continue
            at = first_index(hook, nm)
            if at >= 0 and (at_hero < 0 or at < at_hero):
                others.append(nm)
        if at_hero < 0:
            hit(10, "훅의 주어",
                f"hook 에 주인공({hero})이 없습니다: '{hook[:50]}'",
                f"독자는 {hero} 를 따라 읽습니다. 다음 화를 궁금하게 만드는 "
                f"질문의 주어를 {hero} 로 바꾸세요.")
        elif others:
            hit(10, "훅의 주어",
                f"hook 이 조연({', '.join(others)})으로 시작합니다: '{hook[:50]}'",
                f"조연이 앞으로 뭘 할지가 아니라, **{hero} 가 무엇을 하게 될지**를 "
                f"묻는 질문으로 바꾸세요.")

    # 11. 되돌릴 수 없는 일이 하나도 없으면 이 화는 아무것도 안 한 것이다
    changes = [str(s.get("changed") or "").strip() for s in scenes]
    if any(changes):
        real = [c for c in changes
                if c and not any(w in c for w in PERCEPTION_WORDS)]
        if not real:
            hit(11, "인식만 바뀜",
                f"장면 {len(changes)}개의 changed 가 전부 누가 무엇을 "
                "알게·느끼게 됐다는 내용입니다",
                "최소 한 장면은 **일어난 일**로 끝나야 합니다. 관계·처지·"
                "가진 것 중 하나가 실제로 달라져서, 그 전으로 돌아갈 수 없게 "
                "만드세요. 알게 되는 것은 그 결과로 따라옵니다.")

    return hits


def scene_fix_block(hits: list) -> str:
    if not hits:
        return ""
    lines = ["[장면 점검 결과 — 아래 항목이 걸렸습니다. 이 지시를 반영해 다시 쓰세요]"]
    for h in hits:
        lines.append(f"- ({h['name']}) {h['detail']}\n  → {h['directive']}")
    lines.append("[/장면 점검 결과]")
    return "\n".join(lines)


def parse_scenes(obj: dict) -> list:
    """SCENE / CONTROL 응답에서 장면 배열을 뽑는다. 키 이름 판본 차이를 흡수한다."""
    raw = obj.get("scenes")
    if not isinstance(raw, list):
        return []
    out = []
    for i, s in enumerate(raw, 1):
        if isinstance(s, dict):
            text = str(s.get("text") or s.get("body") or s.get("scene") or "").strip()
            if not text:
                continue
            out.append({
                "no": s.get("no", s.get("scene_index", i)),
                "text": text,
                "one_line": str(s.get("one_line") or "").strip(),
                "choice": str(s.get("choice") or "").strip(),
                # 이 장면이 끝난 뒤 되돌릴 수 없게 된 것. 점검 11번이 본다 —
                # 여기서 흘리면 "무슨 일이 있었나" 를 검사할 수가 없다.
                "changed": str(s.get("changed") or "").strip(),
                "source": s.get("source"),
                "source_note": str(s.get("source_note") or "").strip(),
                "new_element": s.get("new_element"),
            })
        elif str(s).strip():
            out.append({"no": i, "text": str(s).strip(), "one_line": "",
                        "choice": "", "changed": "", "source": None,
                        "source_note": "", "new_element": None})
    return out


# ---------------------------------------------------------------- 실행

@dataclass
class RunResult:
    run_id: str
    condition: str
    character: str
    genre: str
    iteration: int
    status: str
    p3_verdict: str = ""
    p3_pass_count: str = ""
    p3_failed_items: str = ""
    p3_warned_items: str = ""
    p3_skipped_items: str = ""
    capture_test: str = ""
    first_question: str = ""
    cliche_present: str = ""
    scene_check_failed: str = ""
    regen_count: int = 0
    elapsed_sec: float = 0.0
    total_tokens: int = 0
    cost_usd: float = None      # 단가를 모르는 모델이면 None — 0.0 이 아니다
    cost_note: str = ""         # 합계가 부분 합계인 이유 (모르는 모델 이름)
    note: str = ""

    def as_row(self) -> dict:
        return {
            "run_id": self.run_id,
            "condition": self.condition,
            "character": self.character,
            "genre": self.genre,
            "iteration": self.iteration,
            "p3_verdict": self.p3_verdict,
            "p3_pass_count": self.p3_pass_count,
            "p3_failed_items": self.p3_failed_items,
            "p3_warned_items": self.p3_warned_items,
            "p3_skipped_items": self.p3_skipped_items,
            "capture_test": self.capture_test,
            "first_question": self.first_question,
            "cliche_check_present": self.cliche_present,
            "scene_check_failed": self.scene_check_failed,
            "regen_count": self.regen_count,
            "elapsed_sec": round(self.elapsed_sec, 1),
            "total_tokens": self.total_tokens,
            # 모르는 단가는 빈 칸으로 둔다. 0 을 적으면 합계가 조용히 낮아진다.
            "cost_usd": ("" if self.cost_usd is None
                         else f"{self.cost_usd:.6f}"),
            "cost_note": self.cost_note,
            "status": self.status,
        }


def new_run_id() -> str:
    return f"{now_stamp()}-{uuid.uuid4().hex[:6]}"


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_scenes_md(run_dir: Path, scenes: list) -> None:
    """조건/메타를 담지 않는다. 블라인드 화면이 이 파일 본문만 보여준다.

    one_line / choice / source 같은 설계 메타는 절대 넣지 않는다.
    사람은 본문만 읽고 재미를 판단해야 한다.
    """
    parts = []
    for i, s in enumerate(scenes, 1):
        parts.append(f"## {i}\n\n{str(s['text']).strip()}\n")
    (run_dir / "scenes.md").write_text("\n".join(parts), encoding="utf-8")


def call_p1(caller: Caller, ps: PromptSet, row: dict, max_retries: int,
            usage: Usage, sample_cards: str = None,
            genre_tpl: str = None, axes: dict = None,
            author_note: str = "", memory_text: str = "",
            retry_feedback: str = "") -> tuple:
    """P1 을 부르고, 카드 게이트를 통과할 때까지 재호출한다. (카드, 재생성 횟수).

    파이프라인과 --card-mix 가 **같은 함수**를 써야 한다. 시험지에 올라가는 카드가
    파이프라인이 만드는 카드와 다르면, 사람이 골라내지 못했다는 결과에 아무 의미가
    없다. 게이트는 P3 를 부르기 전에 돈다 — 검증 가능한 것은 여기서 코드가 잡고,
    P3 에는 판단이 필요한 것만 남긴다.
    """
    if sample_cards is None:
        key = samples.guess_genre(f"{row.get('genre', '')} {row.get('one_line', '')}")
        sample_cards = samples.exemplars(key) if key else samples.exemplars_all()

    # P1 은 원래 장르를 **문자열 한 줄**로만 받았다. 장르가 무엇인지 설명하는
    # 자료는 P2 에 가서야 들어오는데, 그때는 카드가 이미 만들어진 뒤다.
    # 그래서 P1 입장에서는 사실상 샘플 카드가 장르 정보의 전부였고 —
    # 전용 샘플이 없는 장르(판타지·일상·무협…)를 고르면 엉뚱한 장르의 카드를
    # 보고 썼다. 여기서 장르 문법을 같이 준다.
    if genre_tpl is None:
        genre_tpl = genre_template_block(resolve_genre_templates(row.get("genre") or ""))
    # 축은 매 호출마다 새로 뽑는 것이 목적이라 None 과 {} 를 구분한다 —
    # {} 는 "축 없이 간다"는 명시적 요청이다.
    if axes is None:
        axes = samples.pick_axes(row.get("genre") or "")

    card_input = row.get("card")
    # P3 가 "P1 부터 다시" 라고 판정했으면 그 사유가 여기로 온다. 예전에는
    # run_pipeline 이 p1_feedback 에 담아 두기만 하고 넘길 자리가 없어서 그대로
    # 버려졌다 — P1 은 무엇이 문제였는지 모른 채 같은 조건으로 다시 썼고,
    # 그래서 같은 이유로 또 떨어지는 일이 있었다(#114).
    # 안 넘기면 예전과 똑같이 빈 문자열로 시작한다.
    feedback = str(retry_feedback or "")

    def prompt() -> str:
        return render(ps.texts["p1"], {
            "genre": row["genre"],
            "one_line_intro": row.get("one_line") or "",
            "world": row.get("world") or "(없음)",
            "character_input": row.get("character") or "",
            "card_json": (json.dumps(card_input, ensure_ascii=False, separators=(",", ":"))
                          if card_input else
                          "(없음 — 한 줄 입력에서 카드를 새로 만든다)"),
            "sample_cards": sample_cards,
            "genre_template": genre_tpl or "(이 장르의 템플릿이 없습니다 — 장르명만 보고 씁니다)",
            "variation_axes": samples.axes_block(axes) or "(이번에는 이야기 변수 없이 씁니다)",
            "user_memory": memory_text,
            "retry_feedback": feedback_slot(author_note, feedback),
        })

    sheet, _ = caller.json_call("P1", prompt(), TEMP_CREATIVE, usage)
    for regens in range(max_retries):
        p1_gate = gate_p1(sheet, row.get("character") or "", row.get("_given_name") or "")
        if not p1_gate:
            return sheet, regens
        log(f"    P1 카드 게이트 실패 {len(p1_gate)}건 — P1 재실행")
        for f in p1_gate:
            log(f"      - {f.splitlines()[0]}")
        feedback = "\n".join(f"- {f}" for f in p1_gate)
        sheet, _ = caller.json_call("P1", prompt(), TEMP_CREATIVE, usage)
    return sheet, max_retries


def run_pipeline(caller: Caller, ps: PromptSet, row: dict, iteration: int,
                 scene_count: int, max_gate_retries: int, max_p3_retries: int,
                 max_scene_fixes: int, out_dir: Path,
                 author_note: str = "", memory: dict = None) -> RunResult:
    run_id = new_run_id()
    run_dir = out_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    # 작가 규칙 — P1·P2 는 아직 만들어진 이야기가 없으므로 keyword 를 맞춰 볼
    # 문맥이 입력(캐릭터·한 줄·장르·세계관)뿐이다. 그 안에 태그가 나오면 싣는다.
    memory_text = resolve_user_memory(
        memory or {}, row.get("character") or "", row.get("one_line") or "",
        row.get("genre") or "", row.get("world") or "")
    # 규칙 사본을 새 run 폴더에 남긴다 — 다시 만들기는 늘 새 run_id 를 만드므로,
    # 여기 안 남기면 콘티·그림 단계(webtoon.py·run.py)가 읽을 파일이 없다.
    if (memory or {}).get("always") or (memory or {}).get("keyword"):
        write_json(run_dir / MEMORY_FILE, memory)
    usage = Usage()
    t0 = time.monotonic()

    result = RunResult(run_id, "pipeline", row_label(row), row["genre"],
                       iteration, STATUS_OK)
    p1 = p2 = p3 = None
    p3_view = None
    scene_obj = None
    scene_hits = []
    gate_failures = []
    look = seed = None
    tpl_names, genre_tpl, story_tpl = [], "", ""
    axes = {}
    structure = {}
    regen_total = gate_regen = p3_regen = scene_regen = 0
    p1_feedback = p2_feedback = ""
    card_input = row.get("card")

    def generate_p1():
        nonlocal regen_total
        # p1_feedback 은 P3 가 "P1 부터 다시" 라고 할 때 채워진다. 첫 호출에서는
        # 비어 있어서 예전과 같은 프롬프트가 나간다.
        sheet, regens = call_p1(caller, ps, row, max_gate_retries, usage,
                                sample_cards=sample_cards,
                                genre_tpl=genre_tpl, axes=axes,
                                author_note=author_note,
                                memory_text=memory_text,
                                retry_feedback=p1_feedback)
        regen_total += regens
        return sheet

    try:
        # ---- LOOK · SEED : 캐릭터만 왔을 때 빈 칸을 채우는 자리
        #
        # 두 단계 모두 **줄 것이 있을 때만** 돈다. 사진이 없으면 LOOK 을 부르지
        # 않고, 장르·세계관·한 줄이 다 차 있으면 SEED 를 부르지 않는다 —
        # 안 부르면 그만큼 돈이 안 나간다.
        look = look_at_photos(caller, ps, row, usage)
        if look:
            write_json(run_dir / "look.json", look)
            seen = look_to_material(look)
            if seen:
                # 사진에서 읽은 것을 **맨 앞**에 둔다. 뒤에 오는 자유 서술보다
                # 세다 — 하나는 보인 것이고 하나는 적힌 것이다.
                row["character"] = (seen + "\n\n" + row["character"]).strip()

        seed = seed_missing(caller, ps, row, usage)
        if not seed.get("skipped"):
            write_json(run_dir / "seed.json", seed)
        if seed.get("filled"):
            log(f"    SEED: AI 가 정함 {seed['filled']} · 장르={row['genre']}")

        if not row.get("genre"):
            # 여기까지 왔는데 장르가 없으면 뒷단계가 전부 흔들린다. 빈 값으로
            # 밀고 나가면 샘플도 못 고르고 장르 약속도 못 세운다.
            raise ParseFailure("SEED", json.dumps(seed, ensure_ascii=False))

        result.genre = row["genre"]

        # 샘플은 "어느 장르 풀에 속하는가"로 고른다. 못 고르면 전 장르를 다 보여준다 —
        # 공식은 장르와 무관하고, 예시가 많은 쪽이 적은 쪽보다 낫다.
        # SEED 가 고른 프리셋 키가 있으면 그것을 먼저 믿는다.
        sample_key = (_clean(seed.get("genre_key"))
                      or samples.guess_genre(f"{row['genre']} {row['one_line']}"))
        if sample_key not in samples.GENRES:
            sample_key = samples.guess_genre(f"{row['genre']} {row['one_line']}")
        sample_cards = (samples.exemplars(sample_key) if sample_key
                        else samples.exemplars_all())

        # 장르에 걸리는 템플릿만 고른다. 전부 보내면 매 호출마다 장르 6종이
        # 따라다니고, 안 맞는 장르 문법은 캐릭터를 끌고 간다.
        tpl_names = resolve_genre_templates(row["genre"])
        genre_tpl = genre_template_block(tpl_names)
        story_tpl = story_template_block()
        if tpl_names:
            log(f"    템플릿: {' + '.join(tpl_names)} "
                f"({len(genre_tpl) + len(story_tpl):,}자)")
        else:
            warn(f"    템플릿: '{row['genre']}' 에 맞는 장르 템플릿이 없습니다 "
                 f"— 템플릿 없이 진행합니다 (samples/genre_template.json 의 "
                 f"_preset_map 에 추가할 수 있습니다)")

        # ---- 이야기 변수 축
        #
        # run 마다 여기서 한 번만 뽑는다. P1 게이트 재시도에서 다시 뽑으면
        # "무엇을 고쳐서 통과했는지"가 흐려지고, 재시도가 곧 재추첨이 되어
        # 게이트를 통과할 때까지 설정이 계속 바뀐다.
        # 최근 run 들이 쓴 조합은 피한다. 조합이 넓어도 **바로 직전과 같은 것**이
        # 나오면 "또 이거네"가 되고, 다양성을 넓힌 보람이 그 자리에서 사라진다.
        axes, structure, fresh = samples.pick_fresh(row["genre"], runs_dir=out_dir)
        if axes or structure:
            write_json(run_dir / "axes.json",
                       {"축": axes, "구조": structure})
        if axes:
            log(f"    이야기 변수: {samples.axes_summary(axes)}")
        if structure:
            log(f"    회차 구조: {samples.structure_summary(structure)}")
        if not fresh:
            # 장르 제약이 세서 뽑을 수 있는 조합이 좁을 때 여기로 온다.
            # 멈추지는 않는다 — 겹치는 것보다 안 만들어지는 것이 나쁘다.
            log("    (최근 생성물과 조합이 겹칩니다 — 고를 수 있는 폭이 좁습니다)")

        # ---- P1
        p1 = generate_p1()

        while True:
            # ---- P2
            p2, _ = caller.json_call(
                "P2",
                render(ps.texts["p2"], {
                    "genre": row["genre"],
                    "world": row.get("world") or "(없음)",
                    "genre_template": genre_tpl or "(이 장르의 템플릿이 없습니다)",
                    "story_template": story_tpl or "(스토리 템플릿이 없습니다)",
                    "story_structure": (samples.structure_block(structure)
                                        or "(이번에는 구조 지정 없이 씁니다)"),
                    "character_sheet": json.dumps(p1, ensure_ascii=False, separators=(",", ":")),
                    "user_memory": memory_text,
                    "retry_feedback": feedback_slot(author_note, p2_feedback),
                }),
                TEMP_CREATIVE, usage)

            # ---- 코드 게이트 (P3 호출 전)
            gate_failures = gate_p2(p2, ps.banned_connectors)

            # 템플릿에 실린 실제 작품이 결과물로 새어 나왔는지. 프롬프트에서
            # 뺐으니 원칙적으로는 안 나오지만, 모델은 자기가 아는 작품도 꺼낸다.
            borrowed = check_borrowed_titles(p2)
            if borrowed:
                gate_failures.append(
                    f"기존 작품 제목이 결과물에 들어갔습니다: {', '.join(borrowed)}. "
                    "템플릿은 장르 문법을 참고하라고 준 것이지 그 작품을 옮기라고 "
                    "준 것이 아닙니다. 해당 부분을 이 캐릭터와 세계관에서 다시 "
                    "만드세요.")
            if gate_failures:
                log(f"    게이트 실패 {len(gate_failures)}건 — P3 호출하지 않음")
                if gate_regen >= max_gate_retries:
                    result.status = STATUS_HUMAN
                    result.note = "게이트 재시도 소진"
                    result.p3_failed_items = "; ".join("GATE: " + f for f in gate_failures)
                    break
                gate_regen += 1
                regen_total += 1
                p2_feedback = "\n".join(f"- {f}" for f in gate_failures)
                continue

            # ---- P3 : 새 호출, 히스토리 없음, 산출물만 전달
            p3_prompt = render(ps.texts["p3"], {
                "character_sheet": json.dumps(p1, ensure_ascii=False, separators=(",", ":")),
                "premise_json": json.dumps(p2, ensure_ascii=False, separators=(",", ":")),
                # 샘플 intro 는 원본 입력이 아니라 시장 기준선이다. 심사자가
                # "이 줄이 저 줄들 사이에서 눌리는가"를 볼 근거가 된다.
                "sample_intros": (samples.intro_list(sample_key) if sample_key
                                  else samples.all_intros()),
            })
            p3, _ = caller.json_call("P3", p3_prompt, TEMP_JUDGE, usage)
            p3_view = summarize_p3(p3)
            log(f"    P3 [{p3_view['verdict']}] 통과 {len(p3_view['passed'])}/{p3_view['total']}"
                + (f" · 탈락 {p3_view['failed']}" if p3_view["failed"] else "")
                + (f" · 경고 {p3_view['warned']}" if p3_view["warned"] else ""))

            if not p3_view["failed"]:
                if p3_view["verdict"] == "보통":
                    # 전부 yes 지만 capture=no 또는 클리셰 감지 → 사람 확인 (문서 10-3)
                    result.status = STATUS_HUMAN
                    result.note = "P3 보통 — 전부 yes 지만 capture_test=no 또는 클리셰 감지"
                break

            if p3_regen >= max_p3_retries:
                result.status = STATUS_HUMAN
                result.note = f"P3 재생성 {max_p3_retries}회 소진"
                break

            p3_regen += 1
            regen_total += 1
            directive = p3_view["directive"] or (
                "탈락 항목: " + ", ".join(p3_view["failed"]))
            if p3_view["target_stage"] == "P1":
                log("    -> P1 재실행")
                p1_feedback = directive
                p2_feedback = ""
                p1 = generate_p1()
            else:
                log("    -> P2 재실행")
                p2_feedback = directive
            gate_regen = 0

        # ---- SCENE  (1회 호출로 장면 전체) + 장면 점검 8항목
        scenes = []
        if result.status == STATUS_OK:
            setting_text = json.dumps(p2, ensure_ascii=False) + \
                json.dumps(p1, ensure_ascii=False)
            fix_directive = ""
            while True:
                scene_obj, _ = caller.json_call(
                    "SCENE",
                    render(ps.texts["scene"], {
                        "scene_count": scene_count,
                        "idea": row["one_line"],
                        "character_sheet_json": json.dumps(p1, ensure_ascii=False, separators=(",", ":")),
                        "premise_json": json.dumps(p2, ensure_ascii=False, separators=(",", ":")),
                        "fix_directive": fix_directive,
                        "user_memory": resolve_user_memory(
                            memory or {},
                            json.dumps(p1, ensure_ascii=False),
                            json.dumps(p2, ensure_ascii=False),
                            row.get("one_line") or ""),
                    }),
                    TEMP_CREATIVE, usage)
                scenes = parse_scenes(scene_obj)
                if not scenes:
                    raise ParseFailure("SCENE", json.dumps(scene_obj, ensure_ascii=False))

                scene_hits = check_scenes(scenes, row["one_line"], setting_text,
                                          p1, scene_obj)
                if not scene_hits:
                    log(f"    장면 점검 통과 ({len(scenes)}장면)")
                    break
                names = [h["name"] for h in scene_hits]
                log(f"    장면 점검 걸림 {names}")
                if scene_regen >= max_scene_fixes:
                    result.note = (result.note + " / " if result.note else "") + \
                        f"장면 점검 미해결: {', '.join(names)}"
                    # 걸린 것을 알면서 ok 로 넘기지 않는다. 특히 '설정 증발'은
                    # 작가가 준 한 줄이 결과에서 사라졌다는 뜻이라, 그대로
                    # 통과시키면 사람이 안 본 채로 웹툰 단계까지 간다.
                    # 실제로 "여성"이 사라진 채 이미지까지 갔다.
                    if any(h["name"] in SCENE_BLOCKING_CHECKS for h in scene_hits):
                        result.status = STATUS_HUMAN
                        warn(f"    장면 점검 미해결 — 사람이 확인해야 합니다: "
                             f"{', '.join(names)}")
                    break
                scene_regen += 1
                regen_total += 1
                fix_directive = scene_fix_block(scene_hits)

            write_scenes_md(run_dir, scenes)

    except ParseFailure as e:
        result.status = STATUS_PARSE_FAIL
        result.note = f"{e.stage} 파싱 실패"
        (run_dir / "parse_failure.txt").write_text(e.raw or "", encoding="utf-8")
        warn(f"{e.stage} 파싱 2회 실패 — 실패 처리")
    except ApiFailure as e:
        result.status = STATUS_API_FAIL
        result.note = str(e)
        warn(str(e))

    # ---- 산출물
    if p1 is not None:
        write_json(run_dir / "p1.json", p1)
    if p2 is not None:
        write_json(run_dir / "p2.json", p2)
    if p3 is not None:
        write_json(run_dir / "p3.json", p3)
    if scene_obj is not None:
        write_json(run_dir / "scenes.json", scene_obj)   # 웹툰 단계가 이 파일을 읽는다
    if scene_hits:
        write_json(run_dir / "scene_check.json", scene_hits)

    result.elapsed_sec = time.monotonic() - t0
    result.total_tokens = usage.total
    result.cost_usd = usage.cost_usd()
    result.cost_note = usage.cost_note()
    result.regen_count = regen_total
    result.scene_check_failed = "; ".join(h["name"] for h in scene_hits)
    if p3_view:
        result.p3_verdict = p3_view["verdict"]
        result.p3_pass_count = f"{len(p3_view['passed'])}/{p3_view['total']}"
        if p3_view["failed"] and not result.p3_failed_items:
            result.p3_failed_items = "; ".join(p3_view["failed"])
        result.p3_warned_items = "; ".join(p3_view.get("warned", []))
        result.p3_skipped_items = "; ".join(p3_view.get("skipped", []))
        result.capture_test = p3_view["capture_test"]
        result.first_question = p3_view["first_question"]
        result.cliche_present = "yes" if p3_view["cliche_present"] else "no"

    write_json(run_dir / "meta.json", {
        "run_id": run_id,
        "condition": "pipeline",
        "iteration": iteration,
        "input": row,
        "status": result.status,
        "note": result.note,
        "mock": caller.is_mock,
        "provider": caller.provider,
        "model": caller.model,
        "stage_models": {s: caller.model_for(s) for s in
                         ("P1", "P2", "P3", "SCENE", "CONTROL")},
        "judge_model": caller.judge_model,
        "temperature": {"creative": TEMP_CREATIVE, "judge": TEMP_JUDGE},
        "temperature_applied": caller.temp_ok,
        "judge_temperature_applied": caller.judge_temp_ok,
        "p3_isolated": True,
        "p3_history_included": False,
        "prompt_sha256": ps.short_hashes,
        "regen": {"total": regen_total, "gate": gate_regen,
                  "p3": p3_regen, "scene": scene_regen},
        "gate_failures_last": gate_failures,
        # 작가가 준 것과 AI 가 정한 것을 갈라 둔다. 나중에 "이건 내가 안 정했는데"
        # 라는 질문에 답할 수 있어야 한다.
        "input_source": row.get("_source"),
        "photos": row.get("photos") or [],
        "world": row.get("world") or "",
        "world_preset": row.get("world_preset") or "",
        "ai_filled": (seed or {}).get("filled") or [],
        # 어떤 템플릿을 썼는지 남긴다. 나중에 결과를 보고 템플릿을 고칠 때,
        # 무엇이 이 결과에 영향을 줬는지 알아야 고칠 곳을 찾는다.
        "templates_used": {
            "genre": tpl_names,
            "story_sections": list(STORY_TEMPLATE_KEEP) if story_tpl else [],
            "genre_file": GENRE_TEMPLATE_FILE,
            "story_file": STORY_TEMPLATE_FILE,
            "dropped_fields": list(TEMPLATE_DROP_FIELDS),
            "chars_injected": len(genre_tpl) + len(story_tpl),
        },
        "borrowed_titles_last": check_borrowed_titles(p2) if p2 else [],
        "p3_summary": p3_view,
        "scene_check": scene_hits,
        "usage": usage.as_dict(),
        "elapsed_sec": round(result.elapsed_sec, 1),
        "finished_at": datetime.now().isoformat(timespec="seconds"),
    })
    write_json(run_dir / "transcript.json", caller.transcript)
    usage.write_calls(run_dir / "calls.jsonl")
    caller.transcript = []
    return result


def run_control(caller: Caller, ps: PromptSet, row: dict, iteration: int,
                scene_count: int, out_dir: Path) -> RunResult:
    run_id = new_run_id()
    run_dir = out_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    usage = Usage()
    t0 = time.monotonic()

    result = RunResult(run_id, "control", row_label(row), row["genre"],
                       iteration, STATUS_OK)
    try:
        obj, _ = caller.json_call(
            "CONTROL",
            render(ps.texts["control"], {
                "genre": row["genre"],
                "character_input": f"{row['character']} — {row['one_line']}",
                "scene_count": scene_count,
            }),
            TEMP_CREATIVE, usage)
        scenes = parse_scenes(obj)
        if len(scenes) < scene_count:
            raise ParseFailure("CONTROL", json.dumps(obj, ensure_ascii=False))
        write_scenes_md(run_dir, scenes[:scene_count])
        write_json(run_dir / "control.json", obj)
    except ParseFailure as e:
        result.status = STATUS_PARSE_FAIL
        result.note = "CONTROL 파싱/장면수 실패"
        (run_dir / "parse_failure.txt").write_text(e.raw or "", encoding="utf-8")
        warn("CONTROL 실패 처리")
    except ApiFailure as e:
        result.status = STATUS_API_FAIL
        result.note = str(e)
        warn(str(e))

    result.elapsed_sec = time.monotonic() - t0
    result.total_tokens = usage.total
    result.cost_usd = usage.cost_usd()
    result.cost_note = usage.cost_note()

    write_json(run_dir / "meta.json", {
        "run_id": run_id,
        "condition": "control",
        "iteration": iteration,
        "input": row,
        "status": result.status,
        "note": result.note,
        "mock": caller.is_mock,
        "provider": caller.provider,
        "model": caller.model,
        "stage_models": {s: caller.model_for(s) for s in
                         ("P1", "P2", "P3", "SCENE", "CONTROL")},
        "temperature": TEMP_CREATIVE,
        "temperature_applied": caller.temp_ok,
        "prompt_sha256": ps.short_hashes,
        "usage": usage.as_dict(),
        "elapsed_sec": round(result.elapsed_sec, 1),
        "finished_at": datetime.now().isoformat(timespec="seconds"),
    })
    write_json(run_dir / "transcript.json", caller.transcript)
    usage.write_calls(run_dir / "calls.jsonl")
    caller.transcript = []
    return result


# ---------------------------------------------------------------- 기록

def append_csv_row(path: Path, columns: list, row: dict) -> None:
    """요약 CSV 한 줄. **파일이 잠겨 있어도 실행을 죽이지 않는다.**

    이 함수는 파이프라인의 맨 마지막에 불린다. 여기서 예외가 올라가면 몇 분치
    API 호출과 몇 달러가 이미 나간 뒤에 프로세스가 죽는다. 산출물은 이미 디스크에
    다 있는데 요약 한 줄 때문에 실패로 보이는 것이다.

    Windows 에서 Excel 로 CSV 를 열어 두면 그 파일은 잠긴다. 사람이 결과를 보다가
    다음 실행을 돌리는 것은 아주 흔한 일이라, 이건 예외 상황이 아니라 일상이다.
    그래서 막히면 옆 파일(.pending.csv)에 적어 두고 넘어간다 — 줄은 잃지 않는다.
    """
    def write(target: Path, mode: str, header: bool) -> None:
        with open(target, mode, encoding="utf-8-sig" if header else "utf-8",
                  newline="") as f:
            w = csv.DictWriter(f, fieldnames=columns)
            if header:
                w.writeheader()
            w.writerow(row)

    fresh = not path.exists() or path.stat().st_size == 0
    try:
        write(path, "w" if fresh else "a", fresh)
        return
    except PermissionError:
        pass
    except OSError as e:
        warn(f"{path.name} 에 쓰지 못했습니다 ({e}).")

    spare = path.with_suffix(".pending.csv")
    try:
        spare_fresh = not spare.exists() or spare.stat().st_size == 0
        write(spare, "w" if spare_fresh else "a", spare_fresh)
        warn(f"{path.name} 이 잠겨 있어(엑셀 등에서 열려 있는 것 같습니다) "
             f"{spare.name} 에 적었습니다. 원본을 닫고 이어 붙이세요 — "
             f"결과물 자체는 전부 저장돼 있습니다.")
    except OSError as e:
        warn(f"요약 한 줄을 남기지 못했습니다 ({e}). "
             f"결과물은 저장돼 있으니 run 폴더의 meta.json 을 보세요.")


def append_summary(out_dir: Path, result: RunResult) -> None:
    append_csv_row(out_dir / "summary.csv", SUMMARY_COLUMNS, result.as_row())


def log_prompt_hashes(out_dir: Path, ps: PromptSet, model: str) -> None:
    """프롬프트 파일이 바뀌면 새 줄이 남는다. 어느 버전으로 돌렸는지 추적용."""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "prompt_log.jsonl"
    entry = {"hashes": ps.short_hashes, "model": model}
    if path.exists():
        for line in reversed(path.read_text(encoding="utf-8").splitlines()):
            if not line.strip():
                continue
            try:
                last = json.loads(line)
            except Exception:
                break
            if last.get("hashes") == entry["hashes"] and last.get("model") == entry["model"]:
                return   # 직전과 동일하면 중복 기록하지 않음
            break
    entry["at"] = datetime.now().isoformat(timespec="seconds")
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    log(f"프롬프트 버전: {entry['hashes']}")


# ---------------------------------------------------------------- read.html

MANIFEST_PATH_NAME = ".read_manifest.json"


def scenes_to_html(md_text: str) -> str:
    out = []
    for block in re.split(r"\n\s*\n", md_text.strip()):
        block = block.strip()
        if not block:
            continue
        m = re.match(r"^#{1,6}\s*(.*)$", block)
        if m:
            label = html.escape(m.group(1).strip())
            out.append(f'<div class="sep"><span>{label}</span></div>')
        else:
            out.append("<p>" + html.escape(block).replace("\n", "<br>") + "</p>")
    return "\n".join(out)


def build_read(out_dir: Path, seed: int | None = None) -> Path:
    items = []
    for run_dir in sorted(out_dir.iterdir()):
        if not run_dir.is_dir():
            continue
        scenes_md = run_dir / "scenes.md"
        meta_path = run_dir / "meta.json"
        if not scenes_md.exists() or not meta_path.exists():
            continue
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        items.append({
            "token": uuid.uuid4().hex[:12],
            "run_id": meta["run_id"],
            "condition": meta["condition"],
            "character": meta.get("input", {}).get("character", ""),
            "genre": meta.get("input", {}).get("genre", ""),
            "html": scenes_to_html(scenes_md.read_text(encoding="utf-8")),
        })

    if not items:
        raise SystemExit("runs/ 아래에 scenes.md 가 있는 실행이 없습니다.")

    rng = random.Random(seed)
    rng.shuffle(items)
    for i, it in enumerate(items, 1):
        it["display_order"] = i

    manifest = {
        "built_at": datetime.now().isoformat(timespec="seconds"),
        "seed": seed,
        "items": [{k: v for k, v in it.items() if k != "html"} for it in items],
    }
    write_json(out_dir / MANIFEST_PATH_NAME, manifest)

    cards = []
    for it in items:
        t = it["token"]
        cards.append(f"""
<article class="card" data-token="{t}">
  <div class="num">{it['display_order']}</div>
  <div class="body">{it['html']}</div>
  <div class="ask">
    <p class="q">다음 장면이 궁금한가?</p>
    <div class="choices">
      <button type="button" class="choice" data-v="예">예</button>
      <button type="button" class="choice" data-v="아니오">아니오</button>
    </div>
    <input class="note" type="text" maxlength="200" placeholder="한 줄 메모 (선택)">
    <button type="button" class="save">저장</button>
    <span class="state"></span>
  </div>
</article>""")

    page = READ_TEMPLATE.replace("__CARDS__", "\n".join(cards))
    page = page.replace("__COUNT__", str(len(items)))
    path = out_dir / "read.html"
    path.write_text(page, encoding="utf-8")
    log(f"read.html 생성: {path} ({len(items)}편, 무작위 배치)")
    return path


READ_TEMPLATE = """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>읽기</title>
<style>
  :root {
    --bg: #fbfaf8; --fg: #1c1b19; --muted: #6b6862;
    --line: #e2ded6; --card: #ffffff; --accent: #2f6f5e;
  }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#16171a; --fg:#e8e6e1; --muted:#9b978f; --line:#2c2e33; --card:#1d1f23; --accent:#6fbfa6; }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--fg);
    font-family: "Iowan Old Style", "Apple SD Gothic Neo", "Noto Serif KR", Georgia, serif;
    line-height: 1.85; font-size: 17px;
  }
  header { max-width: 40rem; margin: 0 auto; padding: 3rem 1.25rem 1rem; }
  header h1 { font-size: 1.1rem; font-weight: 600; letter-spacing: .02em; margin: 0 0 .4rem; }
  header p { margin: 0; color: var(--muted); font-size: .9rem; line-height: 1.6; }
  main { max-width: 40rem; margin: 0 auto; padding: 0 1.25rem 6rem; }
  .card {
    background: var(--card); border: 1px solid var(--line); border-radius: 10px;
    padding: 2rem 1.75rem; margin: 2.5rem 0;
  }
  .num { color: var(--muted); font-size: .8rem; letter-spacing: .18em; margin-bottom: 1.5rem; }
  .body p { margin: 0 0 1.15rem; overflow-wrap: break-word; }
  .sep { display: flex; align-items: center; gap: .75rem; margin: 2rem 0 1.25rem; color: var(--muted); }
  .sep span { font-size: .75rem; letter-spacing: .2em; }
  .sep::after { content: ""; flex: 1; height: 1px; background: var(--line); }
  .ask { margin-top: 2.25rem; padding-top: 1.5rem; border-top: 1px solid var(--line);
         font-family: system-ui, -apple-system, "Segoe UI", sans-serif; font-size: .92rem; }
  .q { margin: 0 0 .75rem; font-weight: 600; }
  .choices { display: flex; gap: .5rem; margin-bottom: .75rem; }
  button { font: inherit; cursor: pointer; border-radius: 7px; border: 1px solid var(--line);
           background: transparent; color: var(--fg); padding: .5rem 1.15rem; }
  button:hover { border-color: var(--accent); }
  .choice[aria-pressed="true"] { background: var(--accent); border-color: var(--accent); color: #fff; }
  .note { width: 100%; padding: .55rem .7rem; margin-bottom: .75rem; font: inherit;
          border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--fg); }
  .save { background: var(--fg); color: var(--bg); border-color: var(--fg); }
  .state { margin-left: .7rem; color: var(--muted); font-size: .85rem; }
  .card.done { opacity: .55; }
  #fallback { position: sticky; bottom: 0; background: var(--bg); border-top: 1px solid var(--line);
              padding: .9rem 1.25rem; display: none; font-family: system-ui, sans-serif; font-size: .9rem; }
  #fallback .inner { max-width: 40rem; margin: 0 auto; display: flex; gap: 1rem; align-items: center; }
</style>
</head>
<body>
<header>
  <h1>읽고, 다음이 궁금한지만 답해 주세요</h1>
  <p>총 __COUNT__편. 순서는 무작위입니다. 재미만 보시면 됩니다.</p>
</header>
<main>
__CARDS__
</main>
<div id="fallback"><div class="inner">
  <span>서버에 저장할 수 없어 브라우저에 임시 보관 중입니다.</span>
  <button type="button" id="dl">CSV 내려받기</button>
</div></div>
<script>
(function () {
  var pending = [];
  var offline = location.protocol === 'file:';
  if (offline) document.getElementById('fallback').style.display = 'block';

  document.querySelectorAll('.card').forEach(function (card) {
    var picked = null;
    card.querySelectorAll('.choice').forEach(function (b) {
      b.addEventListener('click', function () {
        picked = b.dataset.v;
        card.querySelectorAll('.choice').forEach(function (o) {
          o.setAttribute('aria-pressed', String(o === b));
        });
      });
    });
    card.querySelector('.save').addEventListener('click', function () {
      var state = card.querySelector('.state');
      if (!picked) { state.textContent = '예 / 아니오를 골라주세요'; return; }
      var payload = {
        token: card.dataset.token,
        answer: picked,
        note: card.querySelector('.note').value || ''
      };
      if (offline) {
        pending.push(payload);
        state.textContent = '임시 저장됨';
        card.classList.add('done');
        return;
      }
      state.textContent = '저장 중...';
      fetch('/submit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }).then(function (r) {
        if (!r.ok) throw new Error('bad status');
        state.textContent = '저장됨';
        card.classList.add('done');
      }).catch(function () {
        pending.push(payload);
        offline = true;
        document.getElementById('fallback').style.display = 'block';
        state.textContent = '임시 저장됨';
        card.classList.add('done');
      });
    });
  });

  document.getElementById('dl').addEventListener('click', function () {
    var rows = [['token', 'next_scene_curious', 'note']];
    pending.forEach(function (p) { rows.push([p.token, p.answer, p.note]); });
    var csv = rows.map(function (r) {
      return r.map(function (c) { return '"' + String(c).replace(/"/g, '""') + '"'; }).join(',');
    }).join('\\n');
    var blob = new Blob(['\\ufeff' + csv], { type: 'text/csv;charset=utf-8' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'blind_raw.csv';
    a.click();
  });
})();
</script>
</body>
</html>
"""


# ---------------------------------------------------------------- blind 저장

def append_blind(out_dir: Path, item: dict, answer: str, note: str) -> None:
    path = out_dir / "blind_result.csv"
    fresh = not path.exists() or path.stat().st_size == 0
    with open(path, "a" if not fresh else "w",
              encoding="utf-8-sig" if fresh else "utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=BLIND_COLUMNS)
        if fresh:
            w.writeheader()
        w.writerow({
            "timestamp": datetime.now().isoformat(timespec="seconds"),
            "run_id": item["run_id"],
            "condition": item["condition"],
            "character": item["character"],
            "genre": item["genre"],
            "display_order": item["display_order"],
            "next_scene_curious": answer,
            "note": note,
        })


def load_manifest(out_dir: Path) -> dict:
    path = out_dir / MANIFEST_PATH_NAME
    if not path.exists():
        raise SystemExit("read manifest 가 없습니다. 먼저 --build-read 를 실행하세요.")
    data = json.loads(path.read_text(encoding="utf-8"))
    return {it["token"]: it for it in data["items"]}


# ---------------------------------------------------------------- 캐릭터 시트

def _shift_hex(hexcode: str, factor: float, desaturate: bool = False) -> str:
    """hex 를 밝게/어둡게(그리고 필요하면 탁하게) 민다.

    밝게 할 때는 곱하지 않고 흰색 쪽으로 섞는다. 곱하면 밝은 색이 금방 255 에
    닿아서 '창백한 살구' 가 그냥 흰색이 된다 — 색이 아니라 빈칸이 되어 버린다.
    """
    h = hexcode.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    rgb = [int(h[i:i + 2], 16) for i in (0, 2, 4)]
    if factor > 1:
        t = min(0.8, factor - 1)
        rgb = [round(c + (255 - c) * t) for c in rgb]
    else:
        rgb = [round(c * factor) for c in rgb]
    rgb = [min(255, max(0, c)) for c in rgb]
    if desaturate:
        grey = round(sum(rgb) / 3)
        rgb = [round(c * 0.55 + grey * 0.45) for c in rgb]
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def korean_color_to_hex(text: str) -> tuple:
    """'밝은 금색' -> ('bright gold', '#F0C44C'). 못 찾으면 (None, None)."""
    body = str(text or "").strip()
    if not body:
        return None, None
    mod_en, factor, faded = "", 1.0, False
    for ko, (en, mult) in KO_COLOR_MOD.items():
        if ko in body:
            mod_en, factor = en, mult
            faded = ko in FADED_MODS
            break
    # 긴 이름부터 본다 ('하늘색' 이 '하늘' 보다 먼저)
    for ko in sorted(KO_COLOR_BASE, key=len, reverse=True):
        if ko in body:
            base_en, base_hex = KO_COLOR_BASE[ko]
            name = f"{mod_en} {base_en}".strip()
            code = _shift_hex(base_hex, factor, desaturate=faded or mod_en == "muted")
            return name, code
    return None, None


def normalize_color(value: str) -> tuple:
    """색 한 칸 -> (표시 문자열, 경고 또는 None).

    이미 hex 가 있으면 그대로 두고, 한글 이름뿐이면 영문+hex 로 바꾼다.
    바꾸지 못하면 원문을 남기고 경고한다 — 아무 색이나 집어넣는 것보다,
    사람이 보고 고치는 편이 낫다.
    """
    body = str(value or "").strip()
    if not body:
        return "", None

    m = HEX_RE.search(body)
    if m:
        code = "#" + m.group(1).upper()
        if len(code) == 4:      # #ABC -> #AABBCC
            code = "#" + "".join(c * 2 for c in code[1:])
        name = HEX_RE.sub("", body).strip(" ()[]·,").strip()
        if name and HANGUL_RE.search(name):
            en, _ = korean_color_to_hex(name)
            if en:
                name = en                      # hex 는 카드에 적힌 것을 살린다
        return (f"{name} ({code})" if name else code), None

    if HANGUL_RE.search(body):
        name, code = korean_color_to_hex(body)
        if name:
            return f"{name} ({code})", None
        return body, (
            f"색 '{body}' 를 영문+hex 로 바꾸지 못했습니다. 프롬프트에 한글로 나갑니다 — "
            "p1.json 의 color_palette 를 'bright gold (#F0C44C)' 형식으로 고치세요.")

    return body, (
        f"색 '{body}' 에 hex 가 없습니다. 이미지 모델마다 다르게 해석합니다 — "
        "'#RRGGBB' 를 같이 적어 주세요.")


def normalize_palette(palette: dict) -> tuple:
    """팔레트 전체 -> (정규화된 dict, 경고 목록)."""
    out, notes = {}, []
    for k in PALETTE_KEYS:
        text, note = normalize_color(palette.get(k))
        out[k] = text
        if note:
            notes.append(f"color_palette.{k}: {note}")
    return out, notes


def _yaml_scalar(text: str, key: str) -> str:
    """config.yaml 에서 스칼라 하나만 꺼낸다. pyyaml 없이도 돌아야 한다.

    따옴표 한 줄, 맨 한 줄, 블록(>- 또는 |) 세 형태를 받는다.
    설정 파일 전체를 해석하지 않는 이유: 필요한 건 문자열 하나뿐이고, 남의 하네스
    설정 스키마에 의존이 생기면 그쪽이 바뀔 때 이쪽이 깨진다.

    콜론 뒤는 `\\s*` 가 아니라 같은 줄의 공백만 먹어야 한다. `\\s` 에는 줄바꿈이
    들어 있어서, 값이 없는 키(= 아래가 표인 키)를 만나면 다음 줄을 그대로 값으로
    집어 왔다. `styles.romance` 가 `"normal: >-"` 라는 글자를 그림체 문구로
    돌려준 것이 그 때문이다.
    """
    m = re.search(rf"^{re.escape(key)}[ \t]*:[ \t]*(.*)$", text, re.M)
    if not m:
        return ""
    head = m.group(1).strip()
    if head and head[0] in "|>":
        lines, started = [], False
        for line in text[m.end():].splitlines():
            if not line.strip():
                if started:
                    break
                continue
            if not line[:1].isspace():
                break
            started = True
            lines.append(line.strip())
        return " ".join(lines).strip()
    if head[:1] in ("'", '"') and head[-1:] == head[:1] and len(head) >= 2:
        return head[1:-1]
    return head.split(" #")[0].strip()


def _yaml_nested_scalar(text: str, parent: str, key: str) -> str:
    """styles: 아래 한 칸 들여쓴 key 의 값. 블록 스칼라(>-)도 받는다.

    webtoon-harness 가 style_suffix 한 줄에서 styles 레지스트리로 바뀌었다.
    거기가 원본이므로 여기서 따라간다 — 시트와 컷이 다른 그림체로 나가면
    시트를 레퍼런스로 쓰는 의미가 없다.

    그쪽이 한 번 더 바뀌었다: styles.<이름> 이 문자열 하나가 아니라
    {normal, sd, emphasis} 표다. 표일 때는 normal 이 그 그림체의 대표값이고
    (webtoon-harness 의 select_style() 도 그 값을 쓴다), 시트 대조도 그 값으로
    한다. 이 갈래가 없을 때는 "normal: >-" 이라는 글자를 그림체 문구로 읽어
    갔다 — 시트가 사실상 그림체 지시 없이 뽑혔다.
    """
    m = re.search(rf"^{re.escape(parent)}\s*:\s*$", text, re.M)
    if not m:
        return ""
    body = []
    for line in text[m.end():].splitlines()[1:]:
        if line.strip() and not line[:1].isspace():
            break                       # 다음 최상위 키
        body.append(line)
    dedented = "\n".join(b[2:] if b[:2] == "  " else b for b in body)
    found = _yaml_scalar(dedented, key)
    if found:
        return found
    # 값이 표였다. 한 칸 더 내려가 normal 을 집는다.
    return _yaml_nested_scalar(dedented, key, "normal")


def read_style_suffix() -> tuple:
    """(스타일 문구, 출처, 경고 목록).

    webtoon-harness/config.yaml 을 읽는다. 못 읽으면 기준값으로 진행하되 경고한다 —
    스타일이 갈리면 시트를 레퍼런스로 그린 컷이 전부 어긋나므로 조용히 넘어가면 안 된다.
    """
    warnings = []
    path = Path(WEBTOON_HARNESS_DIR) / "config.yaml"
    if not path.exists():
        warnings.append(
            f"webtoon-harness 설정을 찾을 수 없습니다: {path}\n"
            "  기준값으로 진행합니다. 경로가 다르면 .env 에 "
            "WEBTOON_HARNESS_DIR=... 를 넣으세요.")
        return EXPECTED_STYLE_SUFFIX, "기준값(설정 파일 없음)", warnings

    try:
        text = path.read_text(encoding="utf-8")
    except Exception as e:
        warnings.append(f"{path} 를 읽지 못했습니다: {e}. 기준값으로 진행합니다.")
        return EXPECTED_STYLE_SUFFIX, "기준값(읽기 실패)", warnings

    # 새 판: styles 레지스트리에서 style_default 가 가리키는 값을 쓴다.
    # (webtoon-harness 가 --style <이름> 으로 고르는 그 값이다)
    name = _yaml_scalar(text, "style_default")
    if name:
        found = _yaml_nested_scalar(text, "styles", name)
        if found:
            return found, f"{path} · styles.{name}", warnings
        warnings.append(
            f"{path} 의 style_default 가 '{name}' 인데 styles 에 그 이름이 없습니다.")

    # 옛 판: style_suffix 한 줄.
    found = _yaml_scalar(text, "style_suffix")
    if not found:
        warnings.append(
            f"{path} 에서 스타일 문구를 찾지 못했습니다 (style_default/styles 도, "
            "style_suffix 도 없습니다). 기준값으로 진행합니다.")
        return EXPECTED_STYLE_SUFFIX, "기준값(항목 없음)", warnings

    if " ".join(found.split()) != " ".join(EXPECTED_STYLE_SUFFIX.split()):
        warnings.append(
            "webtoon-harness 의 style_suffix 가 이 하네스가 알고 있는 값과 다릅니다.\n"
            f"    webtoon-harness: {found}\n"
            f"    story-harness 기준값: {EXPECTED_STYLE_SUFFIX}\n"
            "  **webtoon-harness 값을 그대로 씁니다** (컷을 그리는 쪽이 원본입니다). "
            "의도한 변경이면 story.py 의 EXPECTED_STYLE_SUFFIX 도 맞추세요.")
    return found, str(path), warnings


def charsheet_source(p1: dict) -> dict:
    """P1 카드에서 시트에 필요한 것만 뽑는다."""
    ap = p1.get("appearance") if isinstance(p1.get("appearance"), dict) else {}
    palette = p1.get("color_palette") if isinstance(p1.get("color_palette"), dict) else {}
    details = p1.get("design_details")
    faces = p1.get("expression_set")
    return {
        "name": str(p1.get("name") or "").strip(),
        "appearance": ap,
        "appearance_en": str(p1.get("appearance_en") or "").strip(),
        "design_details": [str(d).strip() for d in (details or []) if str(d or "").strip()],
        # 프롬프트에 나가는 값은 정규화한 쪽이다 — 영문 이름 + hex.
        # 나머지가 전부 영문인데 색만 한글이면 그 자리만 모델의 짐작에 맡기게 된다.
        "color_palette": normalize_palette(palette)[0],
        "color_palette_raw": {k: str(palette.get(k) or "").strip()
                              for k in PALETTE_KEYS},
        "palette_notes": normalize_palette(palette)[1],
        "expression_set": [str(f).strip() for f in (faces or []) if str(f or "").strip()],
        # 지난 시트를 사람이 보고 "이게 틀렸다"고 한 것. 다시 뽑을 때만 채워지고
        # 첫 판에는 늘 비어 있다 — 비어 있으면 프롬프트가 예전과 한 글자도 안
        # 달라진다(_corrections_block 참고).
        #
        # design_details 에 섞지 않고 자리를 따로 둔 이유: 그쪽은 "고정 디자인
        # 요소" 라서 개수(n_details)가 region 3 의 인셋 개수를 정한다. 거기에
        # "얼굴이 사진과 다르다" 같은 줄을 넣으면 시트에 그 말의 확대 컷이
        # 하나 더 생긴다. 고쳐 달라는 말은 그릴 것이 아니라 지시다.
        "sheet_corrections": [str(c).strip()
                              for c in (p1.get("sheet_corrections") or [])
                              if str(c or "").strip()],
    }


def gate_charsheet_source(src: dict) -> list:
    """시트를 뽑기 전에 P1 사양이 실제로 있는지 본다.

    사양 없이 이미지를 부르면 모델이 빈칸을 학습 데이터 평균값으로 채운다.
    그렇게 나온 시트는 "컷마다 다른 사람"을 막지 못한다 — 돈만 쓰고 끝난다.
    그래서 호출 전에 세운다.
    """
    bad = []
    if not src["appearance_en"]:
        bad.append("p1.json 에 appearance_en 이 없습니다. 이미지 프롬프트의 본문입니다.")
    elif HANGUL_RE.search(src["appearance_en"]):
        bad.append("appearance_en 에 한글이 섞여 있습니다. 이미지 모델에 그대로 들어갑니다.")
    if len(src["design_details"]) < DESIGN_DETAIL_MIN:
        bad.append(
            f"design_details 가 {len(src['design_details'])}개입니다 "
            f"({DESIGN_DETAIL_MIN}개 이상). 고정 요소가 없으면 시트를 뽑아도 "
            "컷마다 다른 사람이 됩니다.")
    if len(src["expression_set"]) != EXPRESSION_COUNT:
        bad.append(
            f"expression_set 이 {len(src['expression_set'])}개입니다 "
            f"(정확히 {EXPRESSION_COUNT}개). 표정 시트는 이 목록을 그대로 그립니다.")
    empty_colors = [k for k in PALETTE_KEYS if not src["color_palette"].get(k)]
    if empty_colors:
        bad.append(f"color_palette 의 {empty_colors} 가 비어 있습니다.")
    return bad


def second_lead_name(card: str) -> str:
    """엔진 카드의 '그 한 사람' 이름만 뽑는다. webtoon.py known_speakers() 와 같은 규칙."""
    for line in str(card or "").splitlines():
        line = line.strip()
        if line.startswith("그 한 사람:"):
            value = line[len("그 한 사람:"):].strip()
            return re.split(r"[—\-(]", value)[0].strip()
    return ""


def find_cast_entry(cast: list, name: str):
    for c in cast or []:
        if isinstance(c, dict) and str(c.get("name") or "").strip() == name:
            return c
    return None


def solve_lead_appearance(caller: "Caller", ps: PromptSet, run_dir: Path,
                          usage, dry_run: bool = False) -> dict:
    """'그 한 사람'(두 번째 주연)의 캐릭터 시트 사양을 만든다.

    소스는 p1.json 이 아니라 webtoon/series.json 의 명부다 — 이 인물은 P1
    에서 이름과 한 줄 관계만 정해지고, 실제 gender/appearance/outfit/
    personality 는 그가 처음 등장하는 화의 W5 가 채운다(webtoon-harness
    README 의 "P1 의 「그 한 사람」" 처리와 같은 이유). 그래서 이 명령은
    최소 1화가 만들어진 뒤에만 쓸 수 있다.

    이미 만든 적이 있으면 그 파일을 그대로 쓴다 — 매번 새로 지으면 시트를
    다시 뽑을 때마다 조금씩 다른 사람이 된다. dry_run 이면 캐시가 없을 때
    호출 없이 프롬프트만 찍고 None 을 돌려준다 — --dry-run 이 정말 0원이어야
    하기 때문이다.
    """
    wt_dir = run_dir / "webtoon"
    card_path = wt_dir / "engine_card.txt"
    series_path = wt_dir / "series.json"
    if not card_path.exists() or not series_path.exists():
        raise SystemExit(
            f"{wt_dir} 에 engine_card.txt 나 series.json 이 없습니다. "
            "먼저 webtoon.py 로 최소 1화를 만드세요 — '그 한 사람' 의 외형은 "
            "거기서 처음 정해집니다.")
    card = card_path.read_text(encoding="utf-8")
    name = second_lead_name(card)
    if not name:
        raise SystemExit("엔진 카드에서 '그 한 사람' 을 찾지 못했습니다.")

    series = json.loads(series_path.read_text(encoding="utf-8"))
    row = find_cast_entry(series.get("cast") or [], name)
    if not row or not all(str(row.get(k) or "").strip()
                          for k in ("gender", "appearance", "outfit", "personality")):
        raise SystemExit(
            f"명부에 '{name}' 의 gender/appearance/outfit/personality 가 "
            "아직 다 채워지지 않았습니다. 이 인물이 처음 등장하는 화가 만들어진 "
            "뒤에 다시 시도하세요.")

    out_dir = run_dir / "charsheet_2nd"
    out_path = out_dir / "lead.json"
    if out_path.exists():
        log(f"  '{name}' 사양이 이미 있습니다 -> {out_path} (재사용)")
        return json.loads(out_path.read_text(encoding="utf-8"))

    p1_path = run_dir / "p1.json"
    p1 = json.loads(p1_path.read_text(encoding="utf-8")) if p1_path.exists() else {}
    protagonist_appearance = str(p1.get("appearance_en") or "(없음)")

    if dry_run:
        prompt = render(ps.texts["lead_appearance"], {
            "engine_card": card, "name": name,
            "gender": row.get("gender") or "", "appearance": row.get("appearance") or "",
            "outfit": row.get("outfit") or "", "personality": row.get("personality") or "",
            "protagonist_appearance": protagonist_appearance,
            "retry_feedback": "",
        })
        log(f"\n'{name}' 외형 사양 프롬프트 (LEAD)")
        log("API 를 부르지 않았습니다.")
        log(prompt)
        return None

    feedback, problems = "", []
    for attempt in range(2):
        prompt = render(ps.texts["lead_appearance"], {
            "engine_card": card,
            "name": name,
            "gender": row.get("gender") or "",
            "appearance": row.get("appearance") or "",
            "outfit": row.get("outfit") or "",
            "personality": row.get("personality") or "",
            "protagonist_appearance": protagonist_appearance,
            "retry_feedback": feedback_block(feedback),
        })
        obj, _ = caller.json_call("LEAD", prompt, TEMP_CREATIVE, usage)
        src = charsheet_source(obj if isinstance(obj, dict) else {})
        problems = gate_charsheet_source(src)
        if not problems:
            out_dir.mkdir(parents=True, exist_ok=True)
            write_json(out_path, obj)
            log(f"  '{name}' 시트 사양을 만들었습니다 -> {out_path}")
            return obj
        feedback = "\n".join(problems)
        warn(f"  LEAD 사양 미흡 — 재시도 ({attempt + 1}/2)")
    raise SystemExit("'그 한 사람' 외형 사양을 만들지 못했습니다:\n  " +
                     "\n  ".join(problems))


def _numbered(items: list) -> str:
    return "\n".join(f"  {i}. {x}" for i, x in enumerate(items, 1))


def _corrections_block(src: dict) -> str:
    """지난 시트에서 틀렸다고 한 것을 프롬프트 맨 끝에 붙인다.

    **비어 있으면 빈 문자열을 돌려준다** — 첫 판(그리고 이 값을 안 쓰는 예전
    run)의 프롬프트는 이 함수가 생기기 전과 한 글자도 같다.

    맨 끝에 두는 이유: 이미지 모델은 뒤에 온 지시를 더 세게 듣는다. 앞의
    CHARACTER·FIXED DESIGN ELEMENTS 와 부딪히라고 넣는 것이 아니라, 같은
    사양을 **어느 쪽으로 다시 읽어야 하는지**를 마지막에 못박는 자리다.
    """
    fixes = src.get("sheet_corrections") or []
    if not fixes:
        return ""
    return ("\nCORRECTIONS — the previous sheet was rejected by the author for the "
            "reasons below. Keep everything else the same and fix exactly these. "
            "Written in Korean; follow them literally:\n"
            f"{_numbered(fixes)}\n")


def charsheet_prompts(src: dict, style: str = None) -> dict:
    """P1 사양 -> 이미지 프롬프트 3개.

    한국어 사양(design_details·expression_set)은 번역하지 않고 그대로 싣는다.
    "왼쪽 소매의 노란 반사띠" 를 "yellow stripe" 로 옮기면 위치가 사라져서
    고정 요소가 고정이 아니게 된다. 뼈대 지시만 영문으로 준다.
    """
    who = src["appearance_en"]
    palette = src["color_palette"]
    color_line = " / ".join(
        f"{k}: {palette.get(k)}" for k in PALETTE_KEYS if palette.get(k))

    turnaround = (
        f"{SHEET_COMMON_EN}\n\n"
        "[TURNAROUND SHEET]\n"
        "Draw the SAME single character four times in one horizontal row:\n"
        "  (1) front view  (2) three-quarter view  (3) side view  (4) back view\n"
        "Full body, standing at attention, arms relaxed at the sides, feet together, "
        "neutral expression, camera at eye level.\n"
        "All four figures stand on one shared ground line and must have exactly the "
        "same height and the same proportions. Do not change the outfit, the hair "
        "length, or the body type between views.\n\n"
        f"CHARACTER\n{who}\n\n"
        f"COLOR PALETTE (use exactly these)\n{color_line}\n\n"
        "FIXED DESIGN ELEMENTS — visible and identical in every view. "
        "Written in Korean; follow them literally:\n"
        f"{_numbered(src['design_details'])}\n"
    )

    expressions = (
        f"{SHEET_COMMON_EN}\n\n"
        "[EXPRESSION SHEET]\n"
        f"Draw the SAME single character's head and shoulders {EXPRESSION_COUNT} times "
        "in one horizontal row, evenly spaced, all at the same size, all facing the "
        "camera at the same angle.\n"
        "Only the expression changes between them. Face shape, hairstyle and hair "
        "length are identical in all of them.\n\n"
        f"CHARACTER\n{who}\n\n"
        f"COLOR PALETTE (use exactly these)\n{color_line}\n\n"
        "EXPRESSIONS, left to right. Written in Korean; the part after the dash "
        "describes exactly what to draw:\n"
        f"{_numbered(src['expression_set'])}\n"
    )

    details = (
        f"{SHEET_COMMON_EN}\n\n"
        "[DETAIL SHEET]\n"
        "A detail sheet. No full body. Two parts in one image:\n"
        f"  TOP — {len(src['design_details'])} separate close-up insets, one per fixed "
        "design element listed below. Each inset shows only that element, enlarged, "
        "from the angle that reads best.\n"
        "  BOTTOM — one horizontal row of flat color swatch chips, one chip per "
        "palette entry, in the listed order.\n\n"
        f"CHARACTER (context only)\n{who}\n\n"
        "FIXED DESIGN ELEMENTS to enlarge — one inset each. Written in Korean:\n"
        f"{_numbered(src['design_details'])}\n\n"
        f"COLOR CHIPS, in this order\n{color_line}\n"
    )
    # 스타일 문구는 맨 끝에 붙인다 — webtoon-harness 의 prompt_template 이
    # {appearance} {scene} {style} {extra} 순으로 붙이는 것과 자리를 맞춘다.
    style = style if style is not None else read_style_suffix()[0]
    out = {"turnaround": turnaround, "expressions": expressions, "details": details}
    fixes = _corrections_block(src)          # 없으면 "" — 예전과 같은 문자열이다
    return {k: f"{v}\nSTYLE\n{style}\n{fixes}" for k, v in out.items()}


def charsheet_unified_prompt(src: dict, style: str = None) -> dict:
    """네 영역을 한 장에 담는 프롬프트. {"sheet": ...} 하나만 돌려준다.

    영역을 말로만 나누면 모델이 섞어 버린다. 그래서 위치(위·가운데·아래 왼쪽·아래
    오른쪽)와 "영역 사이에 여백" 을 못 박고, 각 영역의 개수까지 숫자로 준다.
    """
    who = src["appearance_en"]
    palette = src["color_palette"]
    color_line = " / ".join(
        f"{k}: {palette.get(k)}" for k in PALETTE_KEYS if palette.get(k))
    n_details = len(src["design_details"])

    body = (
        f"{SHEET_COMMON_EN}\n\n"
        "[CHARACTER SHEET — ONE PAGE, FOUR REGIONS]\n"
        "A single landscape sheet holding four separate regions, stacked with clear "
        "empty white space between them so each region reads as its own block. "
        "No frames, no borders, no captions, no labels.\n\n"
        "REGION 1 — TOP BAND: turnaround.\n"
        "  The SAME character four times in one horizontal row, left to right:\n"
        "  (1) front view  (2) three-quarter view  (3) side view  (4) back view.\n"
        "  Full body, standing at attention, arms relaxed at the sides, feet together, "
        "neutral expression, camera at eye level.\n"
        "  All four stand on one shared ground line with exactly the same height and "
        "the same proportions. The outfit, hair length and body type do not change "
        "between views.\n\n"
        f"REGION 2 — MIDDLE BAND: {EXPRESSION_COUNT} expressions.\n"
        f"  The SAME character's head and shoulders {EXPRESSION_COUNT} times in one "
        "horizontal row, evenly spaced, all the same size, all facing the camera at "
        "the same angle. Only the expression changes; face shape, hairstyle and hair "
        "length are identical in all of them.\n\n"
        f"REGION 3 — BOTTOM LEFT: {n_details} close-up insets, one per fixed design "
        "element, each showing only that element, enlarged.\n\n"
        "REGION 4 — BOTTOM RIGHT: one horizontal row of flat color swatch chips, one "
        "chip per palette entry, in the listed order.\n\n"
        f"CHARACTER\n{who}\n\n"
        f"COLOR PALETTE (use exactly these, and these are the chips in region 4)\n"
        f"{color_line}\n\n"
        "FIXED DESIGN ELEMENTS — visible and identical everywhere on the sheet, and "
        "one inset each in region 3. Written in Korean; follow them literally:\n"
        f"{_numbered(src['design_details'])}\n\n"
        f"EXPRESSIONS for region 2, left to right. Written in Korean; the part after "
        "the dash describes exactly what to draw:\n"
        f"{_numbered(src['expression_set'])}\n"
    )
    style = style if style is not None else read_style_suffix()[0]
    fixes = _corrections_block(src)          # 없으면 "" — 예전과 같은 문자열이다
    return {UNIFIED_KIND: f"{body}\nSTYLE\n{style}\n{fixes}"}


def build_sheet_prompts(src: dict, style: str = None, split: bool = False) -> dict:
    """모드에 맞는 프롬프트 묶음. 기본은 한 장이다."""
    return charsheet_prompts(src, style) if split else charsheet_unified_prompt(src, style)


def image_backend_ready(provider: str) -> tuple:
    """(쓸 수 있는가, 모델 이름, 안내문). 키가 없으면 --dry-run 만 가능하다."""
    if provider == "openai":
        model = env("OPENAI_IMAGE_MODEL", DEFAULT_OPENAI_IMAGE_MODEL)
        if openai is None:
            return False, model, "openai 패키지가 없습니다.  pip install openai"
        if not env("OPENAI_API_KEY"):
            return False, model, (
                "OPENAI_API_KEY 가 없습니다.\n"
                "  .env 에 아래를 넣으세요:\n"
                "    OPENAI_API_KEY=...\n"
                f"    OPENAI_IMAGE_MODEL={DEFAULT_OPENAI_IMAGE_MODEL}\n"
                "  키 없이 프롬프트만 뽑으려면 --dry-run 을 붙이세요.")
        return True, model, ""

    model = env("GEMINI_IMAGE_MODEL", DEFAULT_IMAGE_MODEL)
    if not env("GEMINI_API_KEY"):
        return False, model, (
            "GEMINI_API_KEY 가 없습니다. 시트는 컷과 같은 모델로 뽑습니다 "
            "(webtoon-harness 와 같은 키·모델).\n"
            "  .env 에 아래를 넣으세요:\n"
            "    GEMINI_API_KEY=...\n"
            f"    GEMINI_IMAGE_MODEL={DEFAULT_IMAGE_MODEL}\n"
            "  키 없이 프롬프트만 뽑으려면 --dry-run 을 붙이세요.")
    if not _gemini_image_available():
        return False, model, (
            "Gemini 이미지 생성 경로가 없습니다. 아래 중 하나가 필요합니다:\n"
            f"    - webtoon-harness 의 providers/ (지금 경로: {WEBTOON_HARNESS_DIR})\n"
            "    - google-genai 패키지  (pip install google-genai)")
    return True, model, ""


def _gemini_image_available() -> bool:
    return _load_webtoon_provider() is not None or google_genai is not None


def _load_webtoon_provider():
    """webtoon-harness 의 providers 를 빌려 쓴다. 없으면 None.

    컷을 그리는 코드와 **같은 코드**로 시트를 뽑는 것이 요점이다. 여기서 따로
    구현하면 재시도·응답 파싱이 조금씩 달라지고, 그 차이가 그림 차이로 나타난다.
    """
    root = str(Path(WEBTOON_HARNESS_DIR).resolve())
    if not (Path(root) / "providers" / "__init__.py").exists():
        return None
    try:
        if root not in sys.path:
            sys.path.insert(0, root)
        import providers as wh_providers       # noqa: PLC0415
        return wh_providers
    except Exception:
        return None


def _webtoon_provider_options() -> dict:
    """webtoon-harness config 의 provider.options 를 그대로 가져온다."""
    path = Path(WEBTOON_HARNESS_DIR) / "config.yaml"
    opts = {"response_modalities": ["TEXT", "IMAGE"], "timeout_sec": 300}
    if not path.exists():
        return opts
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return opts
    m = re.search(r"^\s*timeout_sec\s*:\s*([0-9.]+)", text, re.M)
    if m:
        opts["timeout_sec"] = float(m.group(1))
    return opts


def charsheet_reference_photos(run_dir: Path) -> list[Path]:
    """이 run 의 원본 사진 경로. 있으면 시트에 레퍼런스로 같이 첨부한다.

    meta.json 의 photos 는 LOOK 이 이미 한 번 읽은 파일과 같다 — 거기서 외형을
    글로 옮겨 적었지만, 사진 자체는 그 뒤로 아무 데도 안 쓰이고 있었다.
    (characters/_TEMPLATE.json 도 이걸 그대로 적어 뒀다: "사진 자체를 레퍼런스
    이미지로 넣는 기능은 아직 없습니다.")

    이제는 시트를 그릴 때 텍스트 사양(appearance_en·design_details·
    color_palette)과 사진을 **같이** 넣는다. 글로 못 옮기는 인상은 사진이,
    "왼쪽 소매에만 노란 반사띠 두 줄" 같은 정밀한 디테일은 글이 맡는다 —
    한쪽만으로는 서로가 놓치는 것을 다른 쪽이 채운다.
    """
    meta_path = run_dir / "meta.json"
    if not meta_path.exists():
        return []
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return []
    out = []
    for raw in meta.get("photos") or []:
        p = Path(raw)
        if p.exists() and p.suffix.lower() in IMAGE_MIME:
            out.append(p)
    return out


def make_sheet_painter(provider: str, model: str, quality: str,
                       photos: list[Path] | None = None):
    """(prompt, kind) -> (PNG 바이트, meta). 어느 provider 든 같은 모양으로 부른다.

    meta 는 성공이든 실패든 응답이 말해 준 것(finish_reason·텍스트 등)을 담는다
    — 호출부(run_charsheet)가 성공/실패 상관없이 charsheet_meta.json 에 그대로
    받아 적는다. photos 가 있으면 텍스트 사양과 나란히 첨부한다
    (charsheet_reference_photos).
    """
    photos = photos or []
    if provider == "openai":
        client = openai.OpenAI(api_key=env("OPENAI_API_KEY"),
                               base_url=env("OPENAI_BASE_URL") or None)

        def paint_openai(prompt: str, kind: str) -> tuple[bytes, dict]:
            return generate_sheet_image_openai(
                client, model, prompt, CHARSHEET_SIZES[kind], quality, photos)
        return paint_openai, "openai"

    wh = _load_webtoon_provider()
    if wh is not None:
        opts = _webtoon_provider_options()

        def paint_wh(prompt: str, kind: str) -> tuple[bytes, dict]:
            prov = wh.build_provider(
                "gemini", model=model, api_key=env("GEMINI_API_KEY"),
                options=dict(opts, aspect_ratio=CHARSHEET_RATIOS[kind]))
            # 실패하면 wh.ProviderError 가 이미 finish_reason·텍스트를 메시지에
            # 실어 던진다 (providers/gemini.py::_extract_image) — 여기서는
            # 성공했을 때의 meta 만 더 받아 적는다.
            result = prov.generate(wh.GenRequest(prompt=prompt, images=list(photos)))
            return result.image_bytes, (result.meta or {})
        return paint_wh, "gemini (webtoon-harness providers/)"

    client = google_genai.Client(api_key=env("GEMINI_API_KEY"))

    def paint_genai(prompt: str, kind: str) -> tuple[bytes, dict]:
        return generate_sheet_image_gemini(client, model, prompt, photos)
    return paint_genai, "gemini (google-genai)"


def generate_sheet_image_gemini(client, model: str, prompt: str,
                                photos: list[Path] | None = None) -> tuple[bytes, dict]:
    """webtoon-harness providers 를 못 쓸 때의 예비 경로. (이미지 바이트, meta) 를 돌려준다.

    photos 가 있으면 프롬프트 텍스트 뒤에 이미지 파트로 붙인다 — 순서는
    webtoon-harness/providers/gemini.py 의 GenRequest 조립과 같다(텍스트 먼저,
    그 뒤에 첨부 이미지).

    실패해도 응답이 말한 것(텍스트·finish_reason·block_reason)을 예외 메시지에
    그대로 남긴다 — 안 남기면 왜 이미지가 안 나왔는지 다시 호출하지 않고는
    영영 알 수 없다. webtoon-harness/providers/gemini.py 의 _extract_image 와
    같은 이유로 같은 것을 남긴다.
    """
    config = None
    if google_genai_types is not None:
        try:
            config = google_genai_types.GenerateContentConfig(
                response_modalities=["TEXT", "IMAGE"])
        except Exception:
            config = None
    if photos:
        parts = [prompt]
        for ph in photos:
            img = load_image(ph)
            if google_genai_types is not None:
                parts.append(google_genai_types.Part.from_bytes(
                    data=base64.b64decode(img["b64"]), mime_type=img["mime"]))
            else:
                parts.append({"inline_data": {"mime_type": img["mime"],
                                              "data": img["b64"]}})
        contents = parts
    else:
        contents = prompt
    kwargs = {"model": model, "contents": contents}
    if config is not None:
        kwargs["config"] = config
    try:
        resp = client.models.generate_content(**kwargs)
    except Exception:
        kwargs.pop("config", None)
        resp = client.models.generate_content(**kwargs)

    texts: list[str] = []
    finish = None
    for cand in getattr(resp, "candidates", None) or []:
        fr = getattr(cand, "finish_reason", None)
        finish = (getattr(fr, "name", None) or str(fr)) if fr else finish
        for part in getattr(getattr(cand, "content", None), "parts", None) or []:
            blob = getattr(part, "inline_data", None)
            if blob is not None and getattr(blob, "data", None):
                return blob.data, {
                    "finish_reason": finish,
                    "text": " ".join(texts)[:500] or None,
                }
            text = getattr(part, "text", None)
            if text:
                texts.append(text)

    feedback = getattr(resp, "prompt_feedback", None)
    block_reason = getattr(feedback, "block_reason", None)
    block_reason = (getattr(block_reason, "name", None) or str(block_reason)) \
        if block_reason else None
    detail = " ".join(texts)[:500] or "(텍스트도 없음)"
    reason = ", ".join(b for b in (
        f"finish_reason={finish}" if finish else None,
        f"block_reason={block_reason}" if block_reason else None) if b) or "이유 불명"
    raise RuntimeError(f"응답에 이미지가 없습니다 ({reason}): {detail}")


def generate_sheet_image_openai(client, model: str, prompt: str, size: str,
                                quality: str,
                                photos: list[Path] | None = None) -> tuple[bytes, dict]:
    """(이미지 바이트, meta). 못 받으면 RuntimeError.

    안전 필터에 걸리면 보통 OpenAI SDK 가 예외를 던지고 그 메시지에 이미 사유가
    실려 있어 그대로 위로 올라간다 (여기서 삼키지 않는다). item 이 비어 오는
    드문 경우에만 여기서 무엇이 왔는지를 붙여 남긴다.

    gpt-image-* 는 언제나 base64 로 돌려주고, dall-e-3 는 기본이 URL 이라
    response_format 을 따로 줘야 한다. 모델을 바꿔 끼울 수 있게 둘 다 받는다.

    photos 가 있으면 텍스트→이미지(images.generate) 대신 편집
    (images.edit) 을 부른다 — 그쪽만 원본 이미지를 레퍼런스로 받는다.
    사진과 텍스트 사양을 같이 주는 것이 목적이라, prompt 는 그대로 두고
    "그리기" 대신 "이 사진을 참고해 다시 그리기" 로 호출 방식만 바꾼다.
    """
    photos = photos or []
    if photos:
        files = [open(ph, "rb") for ph in photos]
        try:
            kwargs = {"model": model, "image": files if len(files) > 1 else files[0],
                      "prompt": prompt, "size": size, "n": 1}
            if str(model).startswith("gpt-image"):
                kwargs["quality"] = quality
            for drop in (None, "quality"):
                try:
                    resp = client.images.edit(**kwargs)
                    break
                except TypeError:
                    if drop is None or drop not in kwargs:
                        continue
                    kwargs.pop(drop, None)
            else:
                raise RuntimeError("이미지 편집 API 인자를 맞추지 못했습니다.")
        finally:
            for f in files:
                f.close()
    else:
        kwargs = {"model": model, "prompt": prompt, "size": size, "n": 1}
        if str(model).startswith("gpt-image"):
            kwargs["quality"] = quality
        else:
            kwargs["response_format"] = "b64_json"
        for drop in (None, "quality", "response_format"):
            try:
                resp = client.images.generate(**kwargs)
                break
            except TypeError:
                if drop is None or drop not in kwargs:
                    continue
                kwargs.pop(drop, None)
        else:
            raise RuntimeError("이미지 API 인자를 맞추지 못했습니다.")

    data_list = getattr(resp, "data", None) or []
    item = data_list[0] if data_list else None
    if item is None:
        raw = str(resp)[:500]
        raise RuntimeError(f"응답에 이미지가 없습니다: {raw}")
    meta = {
        "revised_prompt": getattr(item, "revised_prompt", None),
        "usage": getattr(resp, "usage", None) and str(getattr(resp, "usage")),
    }
    b64 = getattr(item, "b64_json", None)
    if b64:
        return base64.b64decode(b64), meta
    if getattr(item, "url", None):
        raise RuntimeError(
            "모델이 base64 대신 URL 을 돌려줬습니다. OPENAI_IMAGE_MODEL 을 "
            f"{DEFAULT_OPENAI_IMAGE_MODEL} 로 두세요 (URL 은 만료됩니다). "
            f"url={getattr(item, 'url')}")
    raise RuntimeError(f"응답에 이미지 데이터가 없습니다: {str(item)[:500]}")


def charsheet_unit_cost(provider: str, quality: str) -> tuple:
    """장당 단가와 그 근거. 모르면 (0.0, 사유).

    실행 전 예상과 실행 후 기록이 **같은 단가**를 써야 한다. 두 곳에서 따로
    계산하면 한쪽만 고쳐졌을 때 조용히 어긋난다.
    """
    unit = env_float("IMAGE_COST_USD", 0.0) or env_float("OPENAI_IMAGE_COST_USD", 0.0)
    if unit:
        return unit, ".env 의 IMAGE_COST_USD"
    if provider == "openai":
        unit = OPENAI_IMAGE_COST_USD.get(quality, 0.0)
        return unit, (f"1536x1024 {quality} 기준" if unit
                      else f"{quality} 품질의 단가를 모릅니다")
    unit = _webtoon_cost_per_image()
    return unit, ("webtoon-harness config.yaml 의 cost_per_image_usd" if unit
                  else "webtoon-harness config.yaml 에서 단가를 찾지 못했습니다")


def charsheet_cost_line(calls: int, provider: str, quality: str) -> str:
    """예상 비용 한 줄과 그 근거.

    모르는 단가를 적어 두면 비용 표시가 거짓말이 된다. 아는 것만 적고, 나머지는
    모른다고 말한 뒤 .env 로 받는다.
    """
    unit, source = charsheet_unit_cost(provider, quality)
    if unit > 0:
        return (f"  예상 비용: {calls}회 x ${unit:.3f} = 약 ${calls * unit:.2f} "
                f"({source})")
    return ("  예상 비용: 이 조합의 단가를 모릅니다. 요금표를 확인하세요.\n"
            "    .env 에 IMAGE_COST_USD=0.04 처럼 넣으면 여기에 합계가 표시됩니다.")


def _webtoon_cost_per_image() -> float:
    """컷 쪽이 쓰는 단가를 그대로 빌린다 — 같은 모델이므로 같은 값이다."""
    path = Path(WEBTOON_HARNESS_DIR) / "config.yaml"
    if not path.exists():
        return 0.0
    try:
        m = re.search(r"^\s*cost_per_image_usd\s*:\s*([0-9.]+)",
                      path.read_text(encoding="utf-8"), re.M)
        return float(m.group(1)) if m else 0.0
    except Exception:
        return 0.0


def show_appearance_spec(src: dict) -> None:
    """시트를 뽑기 전에 사람이 외형을 확인할 수 있게 펼쳐 보인다.

    P1 이 만든 외형이 마음에 안 드는 것은 게이트로 잡을 수 없다 — 규칙 위반이
    아니라 취향이기 때문이다. 이미지를 부르기 전에 사람 눈에 한 번 보이는 것이
    유일한 방법이고, 여기서 멈추는 편이 뽑고 나서 버리는 것보다 싸다.
    """
    log("")
    log("─" * 72)
    log(f"이 외형으로 시트를 뽑습니다 · {src['name'] or '(이름 없음)'}")
    log("─" * 72)
    log("[appearance_en] 이미지 생성기에 그대로 들어가는 문단")
    log(f"  {src['appearance_en']}")
    log("")
    log("[design_details] 매 컷에 유지될 고정 요소")
    for i, d in enumerate(src["design_details"], 1):
        log(f"  {i}. {d}")
    log("")
    log("[color_palette]")
    for k in PALETTE_KEYS:
        raw = src["color_palette_raw"].get(k, "")
        shown = src["color_palette"].get(k, "")
        suffix = f"   (p1.json: {raw})" if raw and raw != shown else ""
        log(f"  {k:<12} {shown}{suffix}")
    log("")
    log("[expression_set] 표정 시트에 그려질 6종")
    for i, f in enumerate(src["expression_set"], 1):
        log(f"  {i}. {f}")
    log("─" * 72)


def _read_json(path: Path):
    """읽히면 주고 아니면 None. 없어도 되는 기록을 읽을 때 쓴다."""
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def charsheet_paths(run_dir: Path, candidates: int, kinds=None,
                    sheet_dirname: str = "charsheet") -> list:
    sheet_dir = run_dir / sheet_dirname
    kinds = kinds or (UNIFIED_KIND,)
    return [(kind, k, sheet_dir / f"{kind}_c{k}.png")
            for kind in kinds for k in range(1, candidates + 1)]


def run_charsheet(run_dir: Path, dry_run: bool, candidates: int,
                  assume_yes: bool, split: bool = False,
                  provider: str = DEFAULT_IMAGE_PROVIDER,
                  quality: str = DEFAULT_OPENAI_IMAGE_QUALITY,
                  spec_path: Path = None, sheet_dirname: str = "charsheet",
                  meta_key: str = "charsheet") -> int:
    """외형 사양으로 캐릭터 시트를 뽑는다. 채택은 pick.html 에서 사람이 한다.

    spec_path/sheet_dirname 은 주인공이 아닌 다른 주연(그 한 사람)의 시트를
    뽑을 때만 바뀐다 — 소스는 p1.json 이 아니라 lead_appearance() 가 만든
    파일, 저장 위치는 run_dir/charsheet 옆의 다른 폴더라 주인공 시트와
    섞이지 않는다.
    """
    p1_path = spec_path or (run_dir / "p1.json")
    if not p1_path.exists():
        raise SystemExit(f"{p1_path} 가 없습니다. 먼저 story.py 를 돌리세요.")
    src = charsheet_source(json.loads(p1_path.read_text(encoding="utf-8")))

    # 사진 레퍼런스는 **주인공 시트에만** 붙는다. meta.json 의 photos 는 이
    # run(주인공)의 사진이고, "그 한 사람"(second_lead) 은 애초에 사진이 아니라
    # 명부의 글 넉 줄에서 외형이 나오므로 붙일 사진이 없다.
    photos = charsheet_reference_photos(run_dir) if sheet_dirname == "charsheet" else []

    problems = gate_charsheet_source(src)
    if problems:
        for x in problems:
            warn(f"  {x}")
        raise SystemExit(
            "외형 사양이 시트를 뽑을 만큼 갖춰지지 않았습니다.\n"
            "  이 run 은 외형 필드가 생기기 전에 만들어졌을 수 있습니다 — "
            "story.py 를 다시 돌려 새 카드를 뽑으세요.")

    style, style_source, style_warnings = read_style_suffix()
    for w in style_warnings:
        warn(f"  {w}")
    for w in src["palette_notes"]:
        warn(f"  {w}")
    prompts = build_sheet_prompts(src, style, split)
    kinds = tuple(prompts)
    sheet_dir = run_dir / sheet_dirname

    show_appearance_spec(src)

    if dry_run:
        log(f"\n캐릭터 시트 프롬프트 · {src['name'] or run_dir.name}")
        log("API 를 부르지 않았습니다. 아래를 이미지 생성기에 그대로 붙여 넣으면 됩니다.")
        if photos:
            log(f"  사진 {len(photos)}장이 텍스트 사양과 함께 레퍼런스로 첨부됩니다: "
                + ", ".join(p_.name for p_ in photos))
        else:
            log("  첨부할 사진이 없습니다 — 텍스트 사양만으로 그립니다 "
                "(이 run 에 사진을 넣지 않았거나 meta.json 에서 못 찾았습니다).")
        log(f"  스타일 문구 출처: {style_source}")
        if provider == "openai":
            warn(f"  {CROSS_MODEL_WARNING}")
        log("")
        if not split:
            log(f"  {UNIFIED_HANDOFF_NOTE}")
            log("")
        for kind in kinds:
            log("=" * 72)
            log(f"[{kind}] {CHARSHEET_LABELS[kind]}  ({CHARSHEET_SIZES[kind]})")
            log("=" * 72)
            log(prompts[kind])
            log("")
        return 0

    # 이미 뽑아 둔 시트가 있으면 다시 부르지 않는다. 캐릭터 시트는 --charsheet
    # 를 부를 때마다(예: --second-lead 를 그림 단계 실행 전에 매번 앞세우는
    # 워크플로) 새로 만들 이유가 없는 자산이다 — 한 번 만든 얼굴이 계속
    # 같아야 시트로서 의미가 있고, 다시 뽑으면 돈만 나가고 조금씩 다른
    # 사람이 된다. 다시 뽑고 싶으면 이 폴더를 사람이 직접 지운다.
    picks_path = sheet_dir / "charsheet_picks.json"
    meta_path = sheet_dir / "charsheet_meta.json"
    if picks_path.exists() or meta_path.exists():
        log(f"\n이미 만든 시트가 있어 다시 뽑지 않습니다: {sheet_dir}")
        log(f"  ({'채택 완료' if picks_path.exists() else '생성됨, 채택 전 — --pick 으로 고르세요'})")
        log(f"  다시 뽑으려면 이 폴더를 지우고 같은 명령을 다시 실행하세요.")
        return 0

    # 외형이 마음에 안 드는 것은 게이트가 잡을 수 없다 — 규칙 위반이 아니라 취향이다.
    # 이미지를 부르기 전에 사람 눈에 한 번 보이는 것이 유일한 방법이고,
    # 여기서 멈추는 편이 뽑고 나서 버리는 것보다 싸다.
    if not assume_yes:
        try:
            answer = input("이 외형으로 진행할까요? [y/N] ").strip().lower()
        except EOFError:
            answer = ""
        if answer not in ("y", "yes"):
            log("")
            log("중단했습니다. 외형을 고치려면 아래 파일을 직접 여세요:")
            log(f"  {p1_path}")
            log("    appearance_en   — 이미지 생성기에 그대로 들어가는 영문 문단")
            log("    design_details  — 매 컷에 유지될 고정 요소 3~5개")
            log("    color_palette   — 여섯 칸. 'bright gold (#F0C44C)' 형식")
            log("    expression_set  — 표정 6종")
            log("  고치고 다시 실행하세요. 카드 자체를 다시 뽑으려면 story.py 를 돌리세요.")
            return 1

    ready, model, guide = image_backend_ready(provider)
    if not ready:
        warn(guide)
        raise SystemExit("이미지 생성을 건너뜁니다.")

    painter, backend_name = make_sheet_painter(provider, model, quality, photos)

    calls = len(kinds) * candidates
    log(f"\n캐릭터 시트 · {src['name'] or run_dir.name}")
    log(f"  이미지: {model} · {backend_name}"
        + (f" · quality {quality}" if provider == "openai" else ""))
    if photos:
        log(f"  사진 {len(photos)}장을 텍스트 사양과 함께 레퍼런스로 첨부합니다: "
            + ", ".join(p_.name for p_ in photos))
        log("    (글로 못 옮기는 인상은 사진이, hex 색·고정 요소 같은 정밀한 값은 "
            "텍스트가 맡습니다)")
    else:
        log("  첨부할 사진이 없습니다 — 텍스트 사양만으로 그립니다.")
    log(f"  스타일 문구: {style}")
    log(f"    출처: {style_source}")
    if provider == "openai":
        warn(f"  {CROSS_MODEL_WARNING}")
    else:
        log("    (컷과 같은 모델입니다 — 레퍼런스 시트로 쓸 수 있습니다)")
    if split:
        log(f"  시트 {len(kinds)}종 x 후보 {candidates}장 = **{calls}회 호출** (분할 모드)")
        log("  이어 그리기: 조건 C+ (sheets: turnaround·expressions·details)")
    else:
        log(f"  통합 시트 1장 x 후보 {candidates}장 = **{calls}회 호출**")
        log(f"  {UNIFIED_HANDOFF_NOTE}")
    log(charsheet_cost_line(calls, provider, quality))
    log(f"  저장 위치: {sheet_dir}")
    if not assume_yes:
        try:
            answer = input("  진행할까요? [y/N] ").strip().lower()
        except EOFError:
            answer = ""
        if answer not in ("y", "yes"):
            log("  취소했습니다. 프롬프트만 보려면 --dry-run 을 붙이세요.")
            return 1

    sheet_dir.mkdir(parents=True, exist_ok=True)

    # 걸린 시간도 남긴다. 화면에는 찍고 있었지만 파일에는 완료 시각(made_at)만
    # 있어서, 나중에 "시트 뽑는 데 얼마나 걸렸나"를 물으면 답할 데가 없었다.
    # 실패한 호출의 시간도 센다 — 기다린 것은 같고, 재시도 비용을 볼 때 필요하다.
    made, failed, draw_times = [], [], []
    for kind, k, path in charsheet_paths(run_dir, candidates, kinds, sheet_dirname):
        t = time.monotonic()
        try:
            data, meta = painter(prompts[kind], kind)
        except Exception as e:
            # 실패해도 응답이 말한 것을 그대로 남긴다 — error 를 안 적으면
            # 나중에 "왜 안 됐는지"를 다시 호출해야만 알 수 있다. warn() 은
            # 화면/log.txt 로만 흘러가고 charsheet_meta.json 에는 안 남았었다.
            draw_times.append({"file": f"{kind}_c{k}.png", "ok": False,
                               "seconds": round(time.monotonic() - t, 1),
                               "error": str(e)})
            warn(f"  {kind} 후보 {k} 실패: {e}")
            failed.append(f"{kind}_c{k}")
            continue
        secs = time.monotonic() - t
        draw_times.append({"file": path.name, "ok": True, "seconds": round(secs, 1),
                           "meta": meta or None})
        path.write_bytes(data)
        made.append(path)
        log(f"  {kind} 후보 {k} · {len(data) // 1024}KB · "
            f"{secs:.0f}초 -> {path.name}")

    for kind in kinds:
        (sheet_dir / f"{kind}_prompt.txt").write_text(prompts[kind], encoding="utf-8")
    _sheet_unit, _sheet_src = charsheet_unit_cost(provider, quality)
    write_json(sheet_dir / "charsheet_meta.json", {
        "run_id": run_dir.name,
        # 사진을 실제로 첨부했는지 — 나중에 "이 시트가 사진 기반인가 텍스트만
        # 봤나"를 파일명 몇 개 뒤져 보지 않고 바로 알 수 있게 남긴다.
        "reference_photos": [p_.name for p_ in photos],
        "image_model": model,
        "image_provider": provider,
        "image_backend": backend_name,
        "image_quality": quality if provider == "openai" else None,
        "style_suffix": style,
        "style_source": style_source,
        "mode": "split" if split else "unified",
        "kinds": list(kinds),
        "cut_provider": "gemini (webtoon-harness)",
        "same_model_as_cuts": provider == "gemini",
        "candidates": candidates,
        "made": [pp.name for pp in made],
        "failed": failed,
        # 실제로 그린 장수 기준. 실패한 호출도 과금될 수 있지만 그것까지는
        # 알 수 없으므로 '성공한 장수' 라고 분명히 적어 둔다.
        "usage": {
            "images_made": len(made),
            "images_failed": len(failed),
            "unit_cost_usd": _sheet_unit or None,
            "unit_cost_source": _sheet_src,
            "cost_usd": (round(len(made) * _sheet_unit, 4) if _sheet_unit else None),
            "cost_basis": "성공한 장수 x 장당 단가 (실패 호출은 빠져 있습니다)",
            # 비용과 달리 시간은 실패한 호출도 포함한다 — 기다린 것은 같다.
            "seconds": round(sum(x["seconds"] for x in draw_times), 1),
            "seconds_each": draw_times,
        },
        "made_at": datetime.now().isoformat(timespec="seconds"),
    })

    if not made:
        raise SystemExit("한 장도 만들지 못했습니다. 위 오류를 보세요.")
    if failed:
        warn(f"  실패 {len(failed)}장: {failed}")

    # 후보가 1장이면 고른다는 말이 성립하지 않는다. 그런데도 --pick 을 따로
    # 돌려야 charsheet_picks.json 이 생겼고, 그걸 빼먹으면 그림 쪽이 "채택된
    # 시트가 없습니다" 로 멈췄다. 사람이 판단할 것이 없는 자리에 사람을 세우지
    # 않는다 — 후보가 여럿일 때만 pick.html 로 보낸다.
    if candidates == 1:
        try:
            save_charsheet_picks(run_dir, {k: f"{k}_c1.png" for k in kinds
                                           if (sheet_dir / f"{k}_c1.png").exists()},
                                 sheet_dirname, meta_key)
            log(f"  후보가 1장이라 자동 채택했습니다 -> charsheet_picks.json")
            log(f"\n이제 그림 단계로 넘어가면 됩니다 (--pick 불필요).")
            return 0
        except ValueError as e:
            warn(f"  자동 채택 실패: {e} — pick.html 로 직접 고르세요.")

    build_pick_html(run_dir, candidates, sheet_dirname)
    log(f"\n채택 화면: {sheet_dir / 'pick.html'}")
    log(f"  python story.py --charsheet --run-id {run_dir.name} --pick")
    return 0


def build_pick_html(run_dir: Path, candidates: int,
                    sheet_dirname: str = "charsheet") -> Path:
    """후보를 나란히 놓고 하나씩 고르는 화면.

    서버로 열면 [확정] 이 곧바로 저장되고, 파일로 열면 JSON 을 내려받는다.
    두 벌 중 하나 고르는 일에 서버가 꼭 떠 있어야 하면 안 쓰게 된다.
    """
    sheet_dir = run_dir / sheet_dirname
    sheet_dir.mkdir(parents=True, exist_ok=True)
    picks_path = sheet_dir / "charsheet_picks.json"
    chosen = {}
    if picks_path.exists():
        try:
            chosen = json.loads(picks_path.read_text(encoding="utf-8")).get("picks") or {}
        except Exception:
            chosen = {}

    rows = []
    for kind in ALL_KINDS:
        cards = []
        for k in range(1, candidates + 1):
            name = f"{kind}_c{k}.png"
            if not (sheet_dir / name).exists():
                continue
            mark = " checked" if chosen.get(kind) == name else ""
            cards.append(
                f'<label class="cand"><input type="radio" name="{html.escape(kind)}" '
                f'value="{html.escape(name)}"{mark}>'
                f'<img src="{html.escape(name)}" alt="{html.escape(name)}">'
                f'<span class="cap">후보 {k}</span></label>')
        if not cards:
            continue
        rows.append(
            f'<section><h2>{html.escape(CHARSHEET_LABELS[kind])}'
            f'<span class="key">{html.escape(kind)}</span></h2>'
            f'<div class="row">{"".join(cards)}</div></section>')

    page = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<title>캐릭터 시트 채택</title>
<style>
  :root { --bg:#fbfaf8; --fg:#1c1b19; --muted:#6b6862; --line:#e2ded6; --card:#fff;
          --accent:#2f6f5e; --warn:#b4462f; --warn-bg:#fdf1ec; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#16171a; --fg:#e8e6e1; --muted:#9b978f; --line:#2c2e33; --card:#1d1f23;
            --accent:#6fbfa6; --warn:#e2765c; --warn-bg:#2a1c17; }
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); line-height:1.7; font-size:15px;
         font-family:"Apple SD Gothic Neo","Noto Sans KR",system-ui,sans-serif; }
  main { max-width:72rem; margin:0 auto; padding:2rem 1.25rem 7rem; }
  h1 { font-size:1.25rem; margin:0 0 .3rem; }
  h2 { font-size:1rem; margin:2.2rem 0 .8rem; display:flex; gap:.6rem; align-items:baseline; }
  .key { color:var(--muted); font-size:.8rem; font-weight:400; }
  .lead { color:var(--muted); font-size:.9rem; margin:0 0 1rem; }
  .note { font-size:.9rem; background:var(--warn-bg); border-left:3px solid var(--warn);
          padding:.7rem .9rem; border-radius:0 6px 6px 0; margin:0 0 1.5rem; }
  code { font-size:.85em; background:var(--line); padding:.05rem .3rem; border-radius:3px; }
  .row { display:flex; gap:1rem; flex-wrap:wrap; }
  .cand { flex:1 1 22rem; background:var(--card); border:2px solid var(--line);
          border-radius:10px; padding:.6rem; cursor:pointer; position:relative; }
  .cand img { width:100%; height:auto; display:block; border-radius:6px; }
  .cand input { position:absolute; top:.9rem; left:.9rem; width:1.15rem; height:1.15rem; }
  .cand:has(input:checked) { border-color:var(--accent); }
  .cap { display:block; text-align:center; color:var(--muted); font-size:.85rem;
         margin-top:.4rem; }
  .bar { position:fixed; left:0; right:0; bottom:0; background:var(--card);
         border-top:1px solid var(--line); padding:.9rem 1.25rem; display:flex;
         gap:1rem; align-items:center; justify-content:center; }
  button { font:inherit; padding:.5rem 1.6rem; border-radius:8px;
           border:1px solid var(--accent); background:var(--accent); color:#fff;
           cursor:pointer; }
  #msg { color:var(--muted); font-size:.9rem; }
</style></head><body><main>
  <h1>캐릭터 시트 채택</h1>
  <p class="lead">시트마다 후보를 하나씩 고르고 [확정] 을 누르세요.
     확정된 경로가 meta.json 에 기록되고, webtoon-harness 가 그 경로를 그대로 참조합니다.</p>
  <p class="note">__PROVNOTE__</p>
  __ROWS__
</main>
<div class="bar"><button id="go">확정</button><span id="msg"></span></div>
<script>
document.getElementById('go').onclick = async () => {
  const picks = {};
  for (const kind of __KINDS__) {
    const el = document.querySelector('input[name="' + kind + '"]:checked');
    if (!el) { document.getElementById('msg').textContent = kind + ' 을 아직 안 골랐습니다.'; return; }
    picks[kind] = el.value;
  }
  const body = JSON.stringify({picks: picks}, null, 2);
  try {
    const r = await fetch('/charsheet-pick', {method:'POST', body: body});
    if (!r.ok) throw new Error(await r.text());
    document.getElementById('msg').textContent = '저장했습니다. 창을 닫아도 됩니다.';
  } catch (e) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([body], {type:'application/json'}));
    a.download = 'charsheet_picks.json';
    a.click();
    document.getElementById('msg').textContent =
      'charsheet_picks.json 을 내려받았습니다. charsheet/ 에 넣으세요.';
  }
};
</script></body></html>"""
    # 확정 대상은 **마지막 실행이 만든 종류**다. 통합으로 뽑았다가 --split 으로 다시
    # 뽑으면 폴더에 네 종류가 다 남는데, 그때 넷을 모두 고르라고 하면 [확정] 이
    # 눌리지 않는다. 기록이 없으면 파일로 알아본다.
    made = _read_json(sheet_dir / "charsheet_meta.json") or {}

    # 안내 문구는 **실제로 쓴 provider** 를 따라간다. 컷과 같은 모델로 뽑았는데
    # "그림체가 어긋날 수 있다"고 겁을 주면, 정작 진짜 경고를 읽지 않게 된다.
    used = str(made.get("image_provider") or DEFAULT_IMAGE_PROVIDER)
    model_txt = html.escape(str(made.get("image_model") or ""))
    if used == "gemini":
        prov_note = (
            "<b>컷과 같은 모델로 뽑았습니다.</b> 이 시트는 "
            f"<b>{model_txt or 'Gemini'}</b> 로 뽑았고 컷도 Gemini 가 그립니다 — "
            "같은 스타일 문구(<code>__STYLE__</code>)를 씁니다. "
            "그래도 후보를 고를 때 기준은 &quot;잘 그린 것&quot; 이 아니라 "
            "<b>컷으로 이어졌을 때 버틸 그림체</b>입니다.")
    else:
        prov_note = (
            "<b>그림체를 먼저 보세요.</b> 이 시트는 "
            f"<b>{model_txt or 'OpenAI'}</b>(OpenAI)로 뽑았고, 컷은 <b>Gemini</b> 가 "
            "그립니다. 같은 스타일 문구(<code>__STYLE__</code>)를 넣어도 두 모델의 "
            "그림체는 다를 수 있습니다. 어긋나면 <code>--provider gemini</code> 로 "
            "다시 뽑거나, webtoon-harness 의 <code>style_suffix</code> 를 조정해야 "
            "합니다. 후보를 고를 때 기준은 &quot;잘 그린 것&quot; 이 아니라 "
            "<b>컷으로 이어졌을 때 버틸 그림체</b>입니다.")

    have = [k for k in (made.get("kinds") or []) if k in ALL_KINDS]
    if not have:
        have = [k for k in ALL_KINDS
                if any((sheet_dir / f"{k}_c{i}.png").exists()
                       for i in range(1, candidates + 1))]
    page = (page.replace("__ROWS__", "\n  ".join(rows))
                .replace("__PROVNOTE__", prov_note)
                .replace("__STYLE__", html.escape(read_style_suffix()[0]))
                .replace("__KINDS__", json.dumps(have)))
    path = sheet_dir / "pick.html"
    path.write_text(page, encoding="utf-8")
    return path


def save_charsheet_picks(run_dir: Path, picks: dict,
                         sheet_dirname: str = "charsheet",
                         meta_key: str = "charsheet") -> dict:
    """채택 결과를 저장하고 meta.json 에 경로를 적는다.

    webtoon-harness 가 refs/ 로 **복사하지 않고** 이 경로를 그대로 참조한다.
    복사본이 생기면 시트를 다시 뽑았을 때 어느 쪽이 진짜인지 알 수 없게 된다.

    meta_key 는 주인공이면 "charsheet"(webtoon-harness 의 style_suffix 대조가
    보는 그 키), 그 한 사람이면 다른 키를 쓴다 — 같은 키를 쓰면 나중에 뽑은
    쪽이 먼저 것을 덮어써 주인공 시트를 찾지 못하게 된다.
    """
    sheet_dir = run_dir / sheet_dirname
    clean = {}
    for kind in ALL_KINDS:
        name = str(picks.get(kind) or "").strip()
        if not name:
            continue
        if "/" in name or "\\" in name or not (sheet_dir / name).exists():
            raise ValueError(f"{kind}: '{name}' 을 {sheet_dirname}/ 에서 찾을 수 없습니다.")
        clean[kind] = name
    if not clean:
        raise ValueError("고른 것이 없습니다.")

    payload = {
        "run_id": run_dir.name,
        "picked_at": datetime.now().isoformat(timespec="seconds"),
        "picks": clean,
    }
    write_json(sheet_dir / "charsheet_picks.json", payload)

    meta_path = run_dir / "meta.json"
    meta = {}
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    meta[meta_key] = {
        "dir": str(sheet_dir.resolve()),
        "mode": "unified" if UNIFIED_KIND in clean else "split",
        "picks": clean,
        "paths": {k: str((sheet_dir / v).resolve()) for k, v in clean.items()},
        "picked_at": payload["picked_at"],
    }
    # 어떤 그림체·어떤 모델로 뽑은 시트인지 같이 남긴다. 컷을 그릴 때
    # webtoon-harness 가 "시트와 컷이 같은 그림체인가" 를 대조하는데, 이게
    # 없으면 확인할 수 없다고 경고만 하고 그냥 지나간다.
    made = _read_json(sheet_dir / "charsheet_meta.json") or {}
    for key, src in (("style_suffix", "style_suffix"),
                     ("model", "image_model"), ("provider", "image_provider")):
        value = str(made.get(src) or "").strip()
        if value:
            meta[meta_key][key] = value
    # 스타일 이름은 출처 문자열에 "… · styles.<이름>" 으로 들어 있다.
    m = re.search(r"styles\.([\w\-]+)", str(made.get("style_source") or ""))
    if m:
        meta[meta_key]["style"] = m.group(1)
    write_json(meta_path, meta)
    if UNIFIED_KIND in clean:
        log(UNIFIED_HANDOFF_NOTE.replace("{run_id}", run_dir.name))
    return payload


def serve_charsheet(run_dir: Path, port: int) -> None:
    """pick.html 을 띄우고 [확정] 을 받아 저장한다."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    import webbrowser

    sheet_dir = (run_dir / "charsheet").resolve()
    if not (sheet_dir / "pick.html").exists():
        raise SystemExit(
            f"{sheet_dir / 'pick.html'} 이 없습니다. 먼저 --charsheet 로 시트를 뽑으세요.")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body: bytes, ctype="text/plain; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            name = self.path.lstrip("/").split("?")[0] or "pick.html"
            target = (sheet_dir / name).resolve()
            if target.parent != sheet_dir or not target.is_file():
                self._send(404, b"not found")
                return
            ctype = {"png": "image/png", "html": "text/html; charset=utf-8"}.get(
                target.suffix.lstrip("."), "text/plain; charset=utf-8")
            self._send(200, target.read_bytes(), ctype)

        def do_POST(self):
            if self.path != "/charsheet-pick":
                self._send(404, b"not found")
                return
            try:
                n = int(self.headers.get("Content-Length", 0))
                payload = json.loads(self.rfile.read(n).decode("utf-8"))
                saved = save_charsheet_picks(run_dir, payload.get("picks") or {})
                self._send(200, b'{"ok":true}', "application/json")
                log(f"  확정: {saved['picks']}")
                log("  meta.json 에 기록했습니다 — webtoon-harness 가 그대로 참조합니다.")
            except Exception as e:
                warn(f"확정 실패: {e}")
                self._send(400, str(e).encode("utf-8"))

    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    url = f"http://127.0.0.1:{port}/pick.html"
    log(f"\n캐릭터 시트 채택: {url}")
    log("  [확정] 을 누르면 charsheet_picks.json 과 meta.json 에 기록됩니다. "
        "Ctrl+C 로 종료.\n")
    try:
        webbrowser.open(url)
    except Exception:
        pass
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("종료.")
    finally:
        srv.server_close()


def serve(out_dir: Path, port: int) -> None:
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    read_path = out_dir / "read.html"
    if not read_path.exists():
        build_read(out_dir)
    tokens = load_manifest(out_dir)

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def _send(self, code, body: bytes, ctype="text/plain; charset=utf-8"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path in ("/", "/read.html", "/index.html"):
                self._send(200, read_path.read_bytes(), "text/html; charset=utf-8")
            else:
                self._send(404, b"not found")

        def do_POST(self):
            if self.path != "/submit":
                self._send(404, b"not found")
                return
            try:
                n = int(self.headers.get("Content-Length", 0))
                payload = json.loads(self.rfile.read(n).decode("utf-8"))
                item = tokens.get(payload.get("token"))
                if not item:
                    self._send(400, b"unknown token")
                    return
                answer = str(payload.get("answer", "")).strip()
                note = str(payload.get("note", "")).strip()
                append_blind(out_dir, item, answer, note)
                self._send(200, b'{"ok":true}', "application/json")
                log(f"  기록: {item['run_id']} [{item['condition']}] {answer}")
            except Exception as e:
                warn(f"submit 실패: {e}")
                self._send(500, b"error")

    srv = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    log(f"\n블라인드 평가 서버: http://127.0.0.1:{port}/")
    log(f"응답은 {out_dir / 'blind_result.csv'} 에 즉시 기록됩니다. Ctrl+C 로 종료.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("종료.")
    finally:
        srv.server_close()


def resolve_blind(out_dir: Path, raw_csv: Path) -> None:
    """file:// 로 열어 내려받은 blind_raw.csv(token 만 있음)를 조건과 결합."""
    tokens = load_manifest(out_dir)
    n = 0
    with open(raw_csv, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            item = tokens.get((row.get("token") or "").strip())
            if not item:
                warn(f"알 수 없는 token: {row.get('token')}")
                continue
            append_blind(out_dir, item,
                         (row.get("next_scene_curious") or "").strip(),
                         (row.get("note") or "").strip())
            n += 1
    log(f"{n}건을 blind_result.csv 에 병합했습니다.")


# ---------------------------------------------------------------- 설정 해석

def resolve_provider(args):
    """(provider, model, judge_model). 우선순위: 명령줄 > 환경변수/.env > 기본값."""
    provider = (getattr(args, "provider", None) or DEFAULT_PROVIDER).strip().lower()
    provider_conf(provider)                       # 이름 검증
    model = getattr(args, "model", None) or default_model_for(provider)
    judge_model = (getattr(args, "judge_model", None)
                   or (getattr(args, "model", None))
                   or default_judge_model_for(provider))
    return provider, model, judge_model


def describe_setup(provider: str, model: str, judge_model: str, mock: bool) -> None:
    """무엇으로 도는지 한 번에 보여주고, 온도·키 문제를 미리 경고한다."""
    if mock:
        log("*** MOCK 모드: API 를 호출하지 않습니다. 결과물은 검증용 더미입니다. ***")

    line = f"프로바이더 {provider} · 모델 {model}"
    if judge_model != model:
        line += f" · 심사 {judge_model}"
    overrides = {k[len(STAGE_MODEL_PREFIX):]: v for k, v in os.environ.items()
                 if k.startswith(STAGE_MODEL_PREFIX) and v}
    if overrides:
        line += " · 단계별 " + ", ".join(f"{k}={v}" for k, v in sorted(overrides.items()))
    log(line)

    conf = provider_conf(provider)
    if not mock and not env(conf["key_var"]):
        # 여기서 멈추지 않으면 입력 전체가 '실패(API)' 로 채워진다.
        raise SystemExit(
            f"{conf['key_var']} 가 비어 있습니다.\n"
            f"  .env 의 {conf['key_var']} 에 키를 넣으세요 (.env.example 참고).\n"
            f"  키 없이 경로만 점검하려면 --mock 을 붙이세요.")

    bad = [m for m in {model, judge_model} if not supports_temperature(m, provider)]
    if not mock and bad:
        warn("=" * 68)
        warn(f"{bad} 계열은 temperature 파라미터를 받지 않습니다.")
        warn(f"요청하신 온도 대비(창작 {TEMP_CREATIVE} / 심사 {TEMP_JUDGE})가 적용되지 않습니다.")
        hint = {
            "gemini": "온도 통제가 필요하면 GEMINI_MODEL=gemini-2.5-flash 를 쓰세요.",
            "openai": "온도 통제가 필요하면 OPENAI_MODEL=gpt-4.1 처럼 비추론 모델을 쓰세요.",
            "anthropic": "온도 통제가 필요하면 ANTHROPIC_MODEL=claude-opus-4-6 을 쓰세요.",
        }.get(provider)
        if hint:
            warn(hint)
        warn("그래도 보내려면 .env 에 FORCE_TEMPERATURE=1 을 넣으세요.")
        warn("meta.json 에 temperature_applied=false 로 기록됩니다.")
        warn("=" * 68)


# ---------------------------------------------------------------- CLI

# ------------------------------------------------- 캐릭터 파일 (--character)
#
# 제품 흐름은 "캐릭터 → 스토리 → 웹툰" 이다. 그래서 입력의 최소 단위는 행이
# 아니라 **캐릭터 한 명**이고, 파일 하나가 한 명이다.
#
# 필수는 하나뿐이다: 캐릭터를 알 수 있는 것이 하나라도 있을 것. 사진 한 장이든
# 자유 서술이든 항목 몇 개든 된다. 장르·세계관·스토리는 비어도 되고, 비면
# 코드가 아니라 **모델이** 캐릭터를 보고 정한 뒤 무엇을 정했는지 신고한다.

WORLDS_FILE = env("WORLDS_FILE", "worlds.json")
_WORLDS_CACHE = {}


def load_worlds(path: str = None) -> dict:
    """worlds.json. 없으면 빈 표 — 프리셋이 없을 뿐 실행은 막지 않는다."""
    key = str(path or WORLDS_FILE)
    if key in _WORLDS_CACHE:
        return _WORLDS_CACHE[key]
    p = Path(key)
    if not p.is_absolute():
        p = ROOT / key
    table = {}
    try:
        if p.exists():
            table = json.loads(p.read_text(encoding="utf-8"))
    except Exception as e:
        warn(f"세계관 프리셋을 읽지 못했습니다 ({p.name}: {e}).")
    _WORLDS_CACHE[key] = table
    return table


def world_preset_text(key: str) -> str:
    presets = (load_worlds().get("presets") or {})
    hit = presets.get(str(key or "").strip())
    if not hit:
        return ""
    return str(hit.get("text") or "").strip()


def world_presets_block() -> str:
    """seed 프롬프트에 넣을 프리셋 목록."""
    presets = (load_worlds().get("presets") or {})
    if not presets:
        return "(프리셋이 없습니다. 캐릭터에 맞게 직접 만드세요.)"
    out = []
    for key, v in presets.items():
        out.append(f"[{key}] {v.get('label') or ''}\n{v.get('text') or ''}")
    return "\n\n".join(out)


def genre_presets_block() -> str:
    return "\n".join(f"[{k}] {samples.genre_label(k)}"
                     for k in sorted(samples.GENRES))


def _clean(v) -> str:
    """자유 입력 한 칸. 리스트로 줘도 받아준다."""
    if isinstance(v, (list, tuple)):
        return "\n".join(str(x).strip() for x in v if str(x).strip())
    return str(v or "").strip()


# ------------------------------------------------- 장르·스토리 템플릿 주입
#
# 템플릿을 통째로 보내지 않는다. 캐릭터의 장르에 걸리는 것만 골라서, 그중에서도
# 필요한 칸만 보낸다. 이유는 두 가지다.
#
#  1) 토큰. 전부 보내면 장르 6종 + 스토리 4절이 매 호출마다 따라다닌다.
#  2) **저작권.** 이게 더 중요하다. '대표작'과 'examples' 칸에는 실제 작품의
#     제목과 줄거리가 들어 있다. 그걸 모델에게 재료로 주면 모델은 재료로 쓴다.
#     템플릿은 장르의 **문법**을 알려주려고 있는 것이지 남의 이야기를 옮기라고
#     있는 것이 아니다. 그래서 그 칸들은 프롬프트에 아예 도달하지 않는다.
#
# 문법(분위기·전개 패턴·체크리스트)은 저작 대상이 아니고, 그것만으로 충분하다.

GENRE_TEMPLATE_FILE = env("GENRE_TEMPLATE_FILE", "samples/genre_template.json")
STORY_TEMPLATE_FILE = env("STORY_TEMPLATE_FILE", "samples/story_templates.json")

# 프롬프트에 절대 넣지 않는 칸. 전부 특정 작품의 제목·줄거리이거나 URL 이다.
TEMPLATE_DROP_FIELDS = ("대표작", "참고자료", "examples", "visual")

# 장르 템플릿에서 실제로 보낼 칸. 여기 없는 칸은 안 보낸다 —
# 차단 목록(deny)이 아니라 허용 목록(allow)이라, 템플릿에 새 칸이 생겨도
# 저절로 새어 나가지 않는다.
GENRE_TEMPLATE_KEEP = ("장르명", "특징", "사례분석", "체크리스트")

# 스토리 템플릿에서 보낼 절(節). 에피소드·시즌 구성은 W4/W5 의 일이라 여기서는 뺀다.
STORY_TEMPLATE_KEEP = ("스토리 구조", "반전과 플롯 장치")

_TEMPLATE_CACHE = {}


def _load_json_file(path_str: str, label: str) -> dict:
    if path_str in _TEMPLATE_CACHE:
        return _TEMPLATE_CACHE[path_str]
    p = Path(path_str)
    if not p.is_absolute():
        p = ROOT / path_str
    data = {}
    try:
        if p.exists():
            data = json.loads(p.read_text(encoding="utf-8"))
        else:
            warn(f"{label}을 찾지 못했습니다: {p}. 템플릿 없이 진행합니다.")
    except Exception as e:
        warn(f"{label}을 읽지 못했습니다 ({p.name}: {e}). 템플릿 없이 진행합니다.")
    _TEMPLATE_CACHE[path_str] = data
    return data


def load_genre_templates() -> dict:
    return _load_json_file(GENRE_TEMPLATE_FILE, "장르 템플릿")


def load_story_templates() -> dict:
    return _load_json_file(STORY_TEMPLATE_FILE, "스토리 템플릿")


def resolve_genre_templates(genre: str) -> list:
    """장르 문자열 -> 쓸 템플릿 이름 목록. 못 찾으면 빈 목록.

    못 찾았을 때 아무거나 끼워 넣지 않는다. 안 맞는 장르 문법을 주는 것은
    안 주는 것보다 나쁘다 — 모델이 캐릭터가 아니라 그 문법을 따라간다.
    """
    table = load_genre_templates()
    names = [k for k in table if not str(k).startswith("_")]
    g = str(genre or "").strip()
    if not g:
        return []

    preset = (table.get("_preset_map") or {}).get(g.lower())
    if preset:
        return [k for k in preset if k in names]
    if g in names:
        return [g]
    # 자유 입력("로맨스 판타지", "헌터물 스릴러") 안에 장르명이 들어 있는 경우.
    hits = [k for k in names if k in g]
    return hits


# 연출 지식 조각(chunk) 저장소. story-harness/docs/*.md 의 웹툰 연출 리서치를
# 사람이 한 번 청크·태그로 나눠 둔 것 — 요약이 아니라 원문 그대로다. 벡터
# 검색이 아니라 resolve_genre_templates 와 같은 "태그 문자열이 겹치면 쓴다"
# 방식. 캐릭터/장면 설명에 액션·로맨스처럼 구체적인 상황이 언급될 때만 관련
# 조각을 골라 붙이고, 하나도 안 걸리면 빈 문자열을 준다 — 아무 것도 안 주는
# 편이 안 맞는 연출 지식을 우기는 것보다 낫다(resolve_genre_templates 와 같은
# 이유).
DIRECTING_KNOWLEDGE_DIR = env("DIRECTING_KNOWLEDGE_FILE", "knowledge/directing")
DIRECTING_NOTES_LIMIT = 3

_DIRECTING_CHUNKS_CACHE: list | None = None


def load_directing_chunks() -> list:
    """knowledge/directing/*.json 을 한 번만 읽어 합친다.

    각 파일은 [{"id", "tags": [...], "text"}, ...] 형태의 배열이다. 폴더가
    없거나 비어 있으면 빈 목록 — 조각이 없으면 그냥 아무것도 안 붙는다.
    """
    global _DIRECTING_CHUNKS_CACHE
    if _DIRECTING_CHUNKS_CACHE is not None:
        return _DIRECTING_CHUNKS_CACHE
    d = Path(DIRECTING_KNOWLEDGE_DIR)
    if not d.is_absolute():
        d = ROOT / d
    chunks = []
    if d.is_dir():
        for p in sorted(d.glob("*.json")):
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
            except Exception as e:
                warn(f"연출 지식을 읽지 못했습니다 ({p.name}: {e}). 건너뜁니다.")
                continue
            if isinstance(data, list):
                chunks.extend(c for c in data
                             if isinstance(c, dict) and c.get("text") and c.get("tags"))
    _DIRECTING_CHUNKS_CACHE = chunks
    return chunks


def resolve_directing_notes(*texts: str, limit: int = DIRECTING_NOTES_LIMIT) -> str:
    """texts 안에 태그가 등장하는 연출 지식 조각만 원문 그대로 이어 붙인다.

    아무 것도 안 걸리면 빈 문자열 — 호출부는 항상 이 값을 프롬프트에 넣는다.
    render() 는 매칭 안 되는 {token} 을 그대로 남겨두므로, 여기서 늘 문자열을
    돌려줘야 프롬프트에 "{directing_notes}" 가 글자 그대로 남는 사고를 막는다.
    """
    chunks = load_directing_chunks()
    if not chunks:
        return ""
    haystack = " ".join(t for t in texts if t)
    if not haystack:
        return ""
    hits = [c for c in chunks if any(tag in haystack for tag in c["tags"])]
    if not hits:
        return ""
    picked = hits[:limit]
    return "\n\n".join(f"[연출 참고 — {c['id']}]\n{c['text']}" for c in picked)


# --------------------------------------------------------------- user memory
#
# 작가가 직접 적어 두는 작품 규칙. resolve_directing_notes(위)와 같은
# "태그가 겹치면 원문 그대로 붙인다" 방식인데, 세 가지가 다르다:
#   · 조각을 우리가 아니라 **작가가** 쓴다 (runs/<run_id>/memory.json)
#   · always 칸은 태그 없이 **항상** 붙는다 — "초롱은 존댓말을 안 쓴다" 같은
#     작품 전체 규칙은 그 이름이 문맥에 없어도 지켜져야 한다
#   · 개수가 아니라 **글자수**로 자른다 — 조각 길이가 제각각이라 개수 제한은
#     실제 주입량을 말해 주지 못하고, 화면에 보여줄 숫자로도 글자수가 정직하다
#
# 기계가 쌓는 확정 사실(webtoon.Ledger 의 facts)과는 별개다 — 그쪽은 AI 가
# 진행 중 확정한 것, 이쪽은 사람이 선언한 것. 충돌하면 사람이 이긴다. 그래서
# 블록 머리말에 "다른 지시·설정과 충돌하면 이 규칙이 이긴다"를 박는다.

MEMORY_FILE = "memory.json"
MEMORY_ALWAYS_LIMIT = 500        # always 전체 글자수 상한
MEMORY_KEYWORD_LIMIT = 1500      # 이번 프롬프트에 실리는 keyword 조각 합계 상한

USER_MEMORY_HEAD = (
    "[작품 규칙 — 작가가 직접 정한 것. 아래의 다른 지시·설정과 충돌하면 "
    "항상 이 규칙이 이긴다]")


def load_user_memory(path) -> dict:
    """memory.json 하나를 읽는다. 없거나 못 읽으면 빈 구조.

    {"always": [{"text": ...}], "keyword": [{"tags": [...], "text": ...}]}
    빈 구조를 돌려주는 이유: 호출부가 None 검사 없이 resolve 로 바로 넘긴다.
    """
    empty = {"always": [], "keyword": []}
    p = Path(path)
    if not p.is_file():
        return empty
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        warn(f"작품 규칙을 읽지 못했습니다 ({p}). 이번 실행은 규칙 없이 갑니다.")
        return empty
    if not isinstance(data, dict):
        return empty
    out = {"always": [], "keyword": []}
    for e in (data.get("always") or []):
        if isinstance(e, dict) and str(e.get("text") or "").strip():
            out["always"].append({"text": str(e["text"]).strip()})
    for e in (data.get("keyword") or []):
        text = str((e or {}).get("text") or "").strip() if isinstance(e, dict) else ""
        tags = [str(t).strip() for t in ((e or {}).get("tags") or [])
                if str(t or "").strip()] if isinstance(e, dict) else []
        if text and tags:
            out["keyword"].append({"tags": tags, "text": text})
    return out


def resolve_user_memory(memory: dict, *texts: str,
                        always_limit: int = MEMORY_ALWAYS_LIMIT,
                        keyword_limit: int = MEMORY_KEYWORD_LIMIT) -> str:
    """작가 규칙 → 프롬프트에 넣을 블록. 규칙이 하나도 안 걸리면 빈 문자열.

    always 는 무조건, keyword 는 태그가 texts 어딘가에 나타날 때만. 글자수
    상한을 넘으면 **앞에서부터** 싣고 나머지는 자른다 — 작가가 위에 적은 것이
    더 중요하다는 뜻으로 읽는 것이 순서를 섞는 것보다 예측 가능하다.
    """
    memory = memory or {}
    lines: list[str] = []
    used = 0
    for e in memory.get("always") or []:
        t = str(e.get("text") or "").strip()
        if not t:
            continue
        if used + len(t) > always_limit:
            break
        lines.append(f"- {t}")
        used += len(t)
    haystack = " ".join(t for t in texts if t)
    used = 0
    for e in memory.get("keyword") or []:
        t = str(e.get("text") or "").strip()
        if not t or not any(tag in haystack for tag in (e.get("tags") or [])):
            continue
        if used + len(t) > keyword_limit:
            break
        lines.append(f"- {t}")
        used += len(t)
    if not lines:
        return ""
    return USER_MEMORY_HEAD + "\n" + "\n".join(lines)


def _strip_template(node):
    """저작권 민감 칸을 재귀적으로 걷어낸다.

    허용 목록으로 한 번 거르고 여기서 한 번 더 거른다. 중첩된 곳에 examples 가
    숨어 있어도 걸리게 하려는 이중 방어다.
    """
    if isinstance(node, dict):
        return {k: _strip_template(v) for k, v in node.items()
                if k not in TEMPLATE_DROP_FIELDS and not str(k).startswith("_")}
    if isinstance(node, list):
        return [_strip_template(v) for v in node]
    return node


def _render_template_node(node, depth: int = 0) -> list:
    """중첩 dict/list 를 읽기 좋은 줄로 편다."""
    pad = "  " * depth
    out = []
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(v, (dict, list)):
                out.append(f"{pad}{k}:")
                out.extend(_render_template_node(v, depth + 1))
            elif str(v).strip():
                out.append(f"{pad}{k}: {v}")
    elif isinstance(node, list):
        for v in node:
            if isinstance(v, (dict, list)):
                out.extend(_render_template_node(v, depth))
            elif str(v).strip():
                out.append(f"{pad}- {v}")
    return out


TITLE_REDACTION = "(작품명 생략)"


def redact_titles(text: str) -> str:
    """주입 직전, 남아 있는 실제 작품명을 지운다.

    칸을 골라 내는 것만으로는 부족하다. 템플릿의 **설명문 본문**에도 작품명이
    박혀 있다 ("『조명가게』에서는 … 『왕좌의 게임』 S1E9 에서는 …").
    허용 목록은 칸 단위라 문장 속까지 보지 못한다.

    지워도 배울 것은 남는다 — 필요한 것은 "어느 작품이 그랬다"가 아니라
    "그 자리에서 무엇을 했다"이기 때문이다.
    """
    out = str(text or "")
    # 긴 제목부터 지운다. 짧은 제목이 긴 제목의 일부일 때 반쪽만 남는 것을 막는다.
    for title in sorted(template_work_titles(), key=len, reverse=True):
        if title and title in out:
            out = out.replace(title, TITLE_REDACTION)
    return out


def genre_template_block(names: list) -> str:
    """고른 장르 템플릿만, 안전한 칸만 프롬프트 문자열로."""
    table = load_genre_templates()
    parts = []
    for name in names:
        node = table.get(name)
        if not isinstance(node, dict):
            continue
        kept = {k: node[k] for k in GENRE_TEMPLATE_KEEP if k in node}
        lines = _render_template_node(_strip_template(kept))
        if lines:
            parts.append(f"[{name}]\n" + "\n".join(lines))
    return redact_titles("\n\n".join(parts))


def story_template_block() -> str:
    table = load_story_templates()
    parts = []
    for section in STORY_TEMPLATE_KEEP:
        node = table.get(section)
        if not isinstance(node, dict):
            continue
        lines = _render_template_node(_strip_template(node))
        if lines:
            parts.append(f"[{section}]\n" + "\n".join(lines))
    return redact_titles("\n\n".join(parts))


def template_work_titles() -> set:
    """템플릿에 실린 실제 작품 제목. 생성물 검사에 쓴다."""
    titles = set()

    def walk(node):
        if isinstance(node, dict):
            for key in ("작품명", "제목"):
                v = str(node.get(key) or "").strip()
                if v:
                    titles.add(v)
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(load_genre_templates())
    walk(load_story_templates())
    return titles


def check_borrowed_titles(payload) -> list:
    """생성물에 기존 작품 제목이 그대로 들어갔는지.

    프롬프트에서 뺐으니 원칙적으로는 나올 수 없다. 그래도 검사한다 —
    모델은 자기가 원래 아는 작품도 꺼낼 수 있고, 템플릿은 사람이 고치는
    파일이라 언젠가 대표작이 다른 칸으로 옮겨 갈 수도 있다.
    구조적 차단과 결과 검사는 서로를 대신하지 못한다.
    """
    text = payload if isinstance(payload, str) else json.dumps(
        payload, ensure_ascii=False)
    return sorted(t for t in template_work_titles() if t and t in text)


def read_character(path: Path) -> dict:
    """캐릭터 파일 1개 -> 파이프라인 행. 빈 칸은 빈 칸으로 둔다(여기서 안 채운다).

    채우는 것은 모델의 일이다(SEED). 코드가 기본값을 넣어 버리면 작가가 준 것과
    코드가 지어낸 것이 섞여서, 나중에 무엇이 자기 것인지 알 수 없게 된다.
    """
    if not path.exists():
        raise SystemExit(f"캐릭터 파일이 없습니다: {path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        raise SystemExit(f"{path} 를 읽지 못했습니다 (JSON 형식 확인): {e}")
    if not isinstance(raw, dict):
        raise SystemExit(f"{path} 는 객체 하나여야 합니다.")

    # _ 로 시작하는 키는 전부 주석이다. 템플릿의 설명문이 모델에게 흘러가면
    # 모델이 그 설명을 캐릭터 설정으로 읽는다.
    data = {k: v for k, v in raw.items() if not str(k).startswith("_")}

    given_name = _clean(data.get("name"))
    name = given_name or path.stem      # 파일 이름은 **부르는 이름**일 뿐이다
    free = _clean(data.get("character"))
    fields = data.get("fields") or {}
    if not isinstance(fields, dict):
        raise SystemExit(f"{path} 의 fields 는 객체여야 합니다.")
    field_lines = [f"- {k}: {_clean(v)}" for k, v in fields.items()
                   if not str(k).startswith("_") and _clean(v)]

    photos = data.get("photo") or []
    if isinstance(photos, str):
        photos = [photos] if photos.strip() else []
    if not isinstance(photos, list):
        raise SystemExit(f"{path} 의 photo 는 문자열이거나 배열이어야 합니다.")
    # 경로는 문자열로 담는다. 이 행은 그대로 meta.json 에 실리는데,
    # Path 객체는 JSON 으로 나가지 않는다.
    photo_paths = []
    for ph in photos:
        s = _clean(ph)
        if not s:
            continue
        p = Path(s)
        photo_paths.append(str(p if p.is_absolute() else (path.parent / p)))

    world = data.get("world") or {}
    if isinstance(world, str):          # world: "직접 쓴 세계관" 도 받아준다
        world = {"preset": "", "text": world}
    if not isinstance(world, dict):
        raise SystemExit(f"{path} 의 world 는 문자열이거나 객체여야 합니다.")
    world_text = _clean(world.get("text"))
    world_preset = _clean(world.get("preset"))
    if world_preset and not world_text:
        world_text = world_preset_text(world_preset)
        if not world_text:
            keys = ", ".join(sorted((load_worlds().get("presets") or {})))
            raise SystemExit(
                f"{path} 의 world.preset '{world_preset}' 을 {WORLDS_FILE} 에서 "
                f"찾지 못했습니다. 가능한 값: {keys}")

    material = "\n\n".join(x for x in [
        f"이름: {name}" if name else "",
        "\n".join(field_lines),
        free,
    ] if x)

    # 파일 이름은 여기서 안 센다. 이름 없이 저장했다는 이유만으로 "캐릭터가
    # 있다"고 치면, 장르만 적힌 파일이 조용히 통과해 모델이 인물을 통째로
    # 지어낸다. 그건 작가의 캐릭터가 아니다.
    if not (given_name or free or field_lines or photo_paths):
        raise SystemExit(
            f"{path} 에 캐릭터를 알 수 있는 것이 없습니다. "
            "photo / character / fields / name 중 하나는 채워 주세요.")

    return {
        "character": material,
        "one_line": _clean(data.get("story")),   # 작가가 준 스토리 (있으면)
        "genre": _clean(data.get("genre")),
        "world": world_text,
        "world_preset": world_preset,
        "story": _clean(data.get("story")),
        "photos": photo_paths,
        "photo_note": _clean(data.get("photo_note")),
        "_name": name,
        "_given_name": given_name,
        "_source": str(path),
    }


def look_at_photos(caller: Caller, ps: PromptSet, row: dict,
                   usage: Usage) -> dict:
    """사진 -> 외형·색·분위기. 사진이 없으면 None.

    여기서 나온 것은 '보인 것' 이라 뒷단계가 지어낸 것보다 세다. 그래서 캐릭터
    재료의 맨 앞에 붙는다.
    """
    if not row.get("photos"):
        return None
    images = [load_image(p) for p in row["photos"]]
    log(f"    LOOK: 사진 {len(images)}장 읽는 중")
    obj, _ = caller.json_call(
        "LOOK",
        render(ps.texts["look"], {"photo_note": row.get("photo_note") or "(없음)"}),
        TEMP_JUDGE, usage, images=images)
    return obj


def look_to_material(look: dict) -> str:
    """LOOK 결과를 캐릭터 재료 문장으로. 빈 칸은 넣지 않는다."""
    if not look:
        return ""
    out = ["[사진에서 읽은 것 — 작가가 준 외형이므로 바꾸지 않는다]"]
    app = look.get("appearance") or {}
    labels = {"hair": "머리", "eyes": "눈", "build": "체형",
              "clothing": "복장", "impression": "첫인상", "element": "상징"}
    for k, ko in labels.items():
        if _clean(app.get(k)):
            out.append(f"- {ko}: {_clean(app[k])}")
    pal = look.get("color_palette") or {}
    pal_txt = ", ".join(f"{k}={_clean(v)}" for k, v in pal.items() if _clean(v))
    if pal_txt:
        out.append(f"- 색: {pal_txt}")
    details = [d for d in (look.get("design_details") or []) if _clean(d)]
    if details:
        out.append("- 고정 요소: " + " / ".join(_clean(d) for d in details))
    for key, ko in (("age_look", "겉보기 나이"), ("mood", "분위기")):
        if _clean(look.get(key)):
            out.append(f"- {ko}: {_clean(look[key])}")
    seeds = look.get("story_seeds") or []
    seed_txt = [f"{_clean(s.get('seed'))} (근거: {_clean(s.get('evidence'))})"
                for s in seeds if isinstance(s, dict) and _clean(s.get("seed"))]
    if seed_txt:
        out.append("- 사진이 준 이야기 실마리: " + " / ".join(seed_txt))
    unseen = [u for u in (look.get("not_visible") or []) if _clean(u)]
    if unseen:
        out.append("- 사진에 안 보여서 비워 둔 것(뒷단계가 정해도 됨): "
                   + ", ".join(_clean(u) for u in unseen))
    return "\n".join(out) if len(out) > 1 else ""


def seed_missing(caller: Caller, ps: PromptSet, row: dict, usage: Usage) -> dict:
    """빈 칸(장르·세계관·한 줄)을 채운다. 다 차 있으면 부르지 않는다.

    작가가 준 값은 절대 덮어쓰지 않는다 — 모델에게도 그렇게 시키고, 코드도
    여기서 한 번 더 막는다. 프롬프트만 믿으면 언젠가 덮어쓴다.
    """
    need = [k for k in ("genre", "world", "one_line") if not row.get(k)]
    if not need:
        return {"filled": [], "skipped": True}

    log(f"    SEED: 빈 칸 채우는 중 {need}")
    obj, _ = caller.json_call(
        "SEED",
        render(ps.texts["seed"], {
            "character_material": row["character"],
            "genre_input": row.get("genre") or "(비어 있음 — 정해 주세요)",
            "world_input": row.get("world") or "(비어 있음 — 정해 주세요)",
            "story_input": row.get("story") or "(비어 있음 — 정해 주세요)",
            "genre_presets": genre_presets_block(),
            "world_presets": world_presets_block(),
        }),
        TEMP_CREATIVE, usage)

    filled = []
    for key, src in (("genre", "genre"), ("world", "world"),
                     ("one_line", "one_line")):
        if row.get(key):
            continue                     # 작가가 준 것 — 손대지 않는다
        value = _clean(obj.get(src))
        if value:
            row[key] = value
            filled.append(key)
    row["story_direction"] = _clean(obj.get("story_direction"))
    obj["filled"] = filled               # 모델 신고가 아니라 실제로 바뀐 것
    return obj


def read_inputs(path: Path) -> list:
    if not path.exists():
        raise SystemExit(f"입력 파일이 없습니다: {path}")
    rows = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        required = {"character", "one_line", "genre"}
        got = set(reader.fieldnames or [])
        if not required.issubset(got):
            raise SystemExit(
                f"{path} 에 필요한 열이 없습니다. 필요: {sorted(required)} / 발견: {sorted(got)}")
        for r in reader:
            if not (r.get("character") or "").strip():
                continue
            rows.append({k: (r.get(k) or "").strip() for k in required})
    if not rows:
        raise SystemExit(f"{path} 에 유효한 행이 없습니다.")
    return rows


# ------------------------------------------------------- 블라인드 카드 믹싱

MIX_DIR = "mix"
MIX_RESULT = "mix_result.csv"
MIX_COLUMNS = ["at", "mix_id", "genre", "n_ai", "n_total", "picked",
               "hits", "misses", "chance", "verdict", "note"]


# 출력에 섞여 나오는 서식 흔적. 남겨 두면 시험이 내용이 아니라 형식으로 판가름난다.
MARKER_RE = re.compile(r"^\s*(?:[①②③④⑤⑥]|\(?\d+\)?[.)]|[-*·])\s*")
SPLIT_RE = re.compile(r"\s*/\s*(?=\S)")


def clean_card_text(text) -> str:
    """카드 한 칸을 시험지에 올릴 수 있는 형태로.

    번호(①)와 구분 기호(" / ")는 P1 이 설명용 표기를 그대로 베껴 쓸 때 나온다.
    서비스에 나간 샘플 카드에는 그런 게 없으므로, 남겨 두면 사람은 카드를 읽지도
    않고 서식만 보고 AI 를 골라낸다. 그건 이 시험이 재려던 것이 아니다.
    프롬프트에서도 막지만, 여기서 한 번 더 지운다 — 시험의 공정성은 코드가 보장한다.
    """
    out = MARKER_RE.sub("", str(text or "").strip())
    return SPLIT_RE.sub(" ", out).strip()


def card_view(card: dict) -> dict:
    """샘플 카드와 P1 카드를 **같은 모양**으로 만든다.

    이게 이 시험지의 전부다. 두 출처의 카드가 다른 필드 이름·다른 칸 수로 보이면
    사람은 내용이 아니라 형식을 보고 골라낸다. 그건 시험이 아니다.
    """
    ap = card.get("appearance")
    ap = ap if isinstance(ap, dict) else {}
    beats = card.get("fateBeats") or card.get("fate_beats") or []
    return {
        "intro": clean_card_text(card.get("intro")),
        "name": clean_card_text(card.get("name")),
        "rank": clean_card_text(card.get("rank")),
        "personality": clean_card_text(card.get("personality")),
        "quote": clean_card_text(card.get("quote")),
        "hair": clean_card_text(ap.get("hair")),
        "eyes": str(ap.get("eyes") or "").strip(),   # "보랏빛 · 서늘한 눈매" 의 · 는 서식이다
        "impression": clean_card_text(ap.get("impression")),
        "element": clean_card_text(ap.get("element")),
        "beats": [clean_card_text(b) for b in beats][:4],
    }


def run_card_mix(caller: Caller, ps: PromptSet, genre: str, n: int,
                 out_dir: Path, seed: int = None) -> Path:
    """새 P1 카드 n장을 같은 장르 샘플 카드와 섞어 시험지를 만든다.

    묻는 것은 "이 카드가 좋은가"가 아니라 **"사람이 골라낼 수 있는가"** 다.
    좋다/나쁘다는 기준이 사람마다 다르지만, 골라낼 수 있는지는 정답이 있다.
    골라내지 못하면 그 카드는 이미 시장에 나가 있는 것들과 같은 급이다.
    """
    try:
        pool = samples.load(genre)
    except samples.SampleError as exc:
        raise SystemExit(str(exc))

    usage = Usage()
    row = {
        "genre": samples.genre_label(genre),
        "one_line": "",
        "character": ("(재료 없음 — 장르만 주어졌다. 이 장르의 새 카드를 하나 만든다. "
                      "샘플 카드와 소재가 겹치지 않게 할 것)"),
        "card": None,
    }
    # 여기서는 일부만 보여주면 안 된다. 시험지에는 샘플 6장이 **전부** 올라가는데
    # P1 이 그중 셋만 봤다면, 못 본 카드와 소재가 겹쳐도 피할 수가 없다.
    # 그러면 사람이 골라낸 이유가 "AI 라서"가 아니라 "겹쳐서"가 되어 시험이 망가진다.
    sample_cards = samples.exemplars(genre, pick=0)

    base_material = row["character"]
    made = []
    for i in range(1, n + 1):
        log(f"  [{i}/{n}] 카드 생성...")
        # 같은 프롬프트를 n번 부르면 세 장이 서로 닮는다 — 헌터명이 셋 다 같은
        # 단어로 나오는 식이다. 그러면 사람은 "비슷한 세 장"을 통째로 짚는다.
        # 앞서 만든 것을 피하라고 넣어 주는 것이 이 시험을 공정하게 만든다.
        row["character"] = base_material + (
            ("\n\n[이번 묶음에서 이미 만든 카드]\n"
             + "\n".join(f"- {c.get('intro', '')} / {c.get('name', '')}" for c in made)
             + "\n소재·능력·이름이 위와 겹치면 안 된다. 특히 활동명(헌터명)은 위에 쓰인 "
               "단어를 하나도 재사용하지 마라 — 비슷한 세 장은 사람이 통째로 짚어낸다.")
            if made else "")
        card, regens = call_p1(caller, ps, row, 2, usage, sample_cards=sample_cards)
        card["_source"] = "ai"
        made.append(card)
        log(f"      {card.get('intro', '(intro 없음)')}"
            + (f"  (재생성 {regens}회)" if regens else ""))
    row["character"] = base_material

    for c in pool:
        c["_source"] = "sample"

    mix_id = now_stamp()
    rnd = random.Random(seed if seed is not None else mix_id)
    deck = made + pool
    rnd.shuffle(deck)

    mix_dir = out_dir / MIX_DIR / mix_id
    mix_dir.mkdir(parents=True, exist_ok=True)
    write_json(mix_dir / "cards.json", {"genre": genre, "cards": made})
    # 정답은 시험지 안에 두지 않는다. HTML 소스를 열면 보이는 답은 답이 아니다.
    write_json(mix_dir / "mix_key.json", {
        "mix_id": mix_id, "genre": genre, "seed": seed,
        "n_ai": len(made), "n_total": len(deck),
        "answer": [i for i, c in enumerate(deck, 1) if c.get("_source") == "ai"],
        "order": [str(c.get("id") or c.get("name") or "") for c in deck],
    })

    path = mix_dir / "mix.html"
    path.write_text(mix_html(deck, genre, mix_id, len(made)), encoding="utf-8")
    log(f"  {len(deck)}장 시험지 · AI {len(made)}장 · {usage.cost_line()}")
    usage.write_calls(mix_dir / "calls.jsonl")
    return path


def mix_html(deck: list, genre: str, mix_id: str, n_ai: int) -> str:
    cards = []
    for i, c in enumerate(deck, 1):
        v = card_view(c)
        ap = " · ".join(x for x in (v["hair"], v["eyes"], v["impression"]) if x)
        beats = "".join(f'<li>{_esc_html(b)}</li>' for b in v["beats"])
        cards.append(f"""
<article class="card" data-n="{i}">
  <label class="pick"><input type="checkbox" data-n="{i}"> AI</label>
  <div class="no">{i}</div>
  <p class="intro">{_esc_html(v['intro'])}</p>
  <h2>{_esc_html(v['name'])}</h2>
  <p class="rank">{_esc_html(v['rank'])}</p>
  <p class="pers">{_esc_html(v['personality'])}</p>
  <blockquote>{_esc_html(v['quote'])}</blockquote>
  <p class="ap">{_esc_html(ap)}{f" · 속성 {_esc_html(v['element'])}" if v['element'] else ""}</p>
  <ol class="beats">{beats}</ol>
</article>""")

    return MIX_TEMPLATE.format(
        genre=_esc_html(samples.genre_label(genre)),
        mix_id=_esc_html(mix_id),
        n_ai=n_ai,
        n_total=len(deck),
        cards="".join(cards),
    )


def _esc_html(x) -> str:
    return html.escape(str(x if x is not None else ""))


MIX_TEMPLATE = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>카드 믹스 {mix_id} — {genre}</title>
<style>
:root {{ --bg:#0f1115; --fg:#e9ebf1; --dim:#9aa2b4; --line:rgba(255,255,255,.12);
        --card:#171a21; --accent:#2f6fed; }}
* {{ box-sizing:border-box; }}
body {{ margin:0; padding:0 0 120px; background:var(--bg); color:var(--fg);
       font:15px/1.7 "Malgun Gothic", system-ui, sans-serif; }}
header {{ position:sticky; top:0; z-index:5; padding:14px 18px;
         background:rgba(15,17,21,.94); border-bottom:1px solid var(--line);
         backdrop-filter:blur(6px); }}
header h1 {{ margin:0 0 4px; font-size:16px; }}
header p {{ margin:0; font-size:13px; color:var(--dim); }}
main {{ max-width:760px; margin:0 auto; padding:22px 16px; }}
.card {{ position:relative; background:var(--card); border:1px solid var(--line);
        border-radius:14px; padding:20px 22px 18px; margin:0 0 18px; }}
.card.on {{ border-color:var(--accent); box-shadow:0 0 0 1px var(--accent) inset; }}
.no {{ position:absolute; top:14px; left:-2px; width:30px; text-align:center;
      font:700 12px/1 ui-monospace,Consolas,monospace; color:var(--dim); }}
.pick {{ position:absolute; top:12px; right:14px; display:flex; align-items:center;
        gap:6px; font-size:12px; color:var(--dim); cursor:pointer; user-select:none;
        padding:4px 9px; border:1px solid var(--line); border-radius:20px; }}
.card.on .pick {{ color:#fff; border-color:var(--accent); background:var(--accent); }}
.intro {{ margin:6px 30px 12px 0; font-size:17px; font-weight:700; line-height:1.5; }}
h2 {{ margin:0 0 2px; font-size:15px; }}
.rank {{ margin:0 0 10px; font-size:13px; color:var(--dim); }}
.pers {{ margin:0 0 12px; font-size:14px; }}
blockquote {{ margin:0 0 12px; padding:10px 14px; border-left:2px solid var(--line);
             color:#cfd4e0; font-size:14px; }}
.ap {{ margin:0 0 10px; font-size:12.5px; color:var(--dim); }}
.beats {{ margin:0; padding-left:20px; font-size:13.5px; color:#c9cfdc; }}
.beats li {{ margin-bottom:3px; }}
footer {{ position:fixed; left:0; right:0; bottom:0; padding:12px 18px;
         background:rgba(15,17,21,.96); border-top:1px solid var(--line);
         display:flex; align-items:center; gap:12px; flex-wrap:wrap; }}
button {{ font:inherit; font-weight:600; color:#fff; background:var(--accent);
         border:0; border-radius:8px; padding:9px 16px; cursor:pointer; }}
button:disabled {{ opacity:.4; cursor:default; }}
#out {{ font:13px/1.5 ui-monospace,Consolas,monospace; color:var(--dim);
       word-break:break-all; }}
#out b {{ color:#fff; }}
</style></head>
<body>
<header>
  <h1>이 {n_total}장 중 AI 가 만든 {n_ai}장을 골라내세요 — {genre}</h1>
  <p>나머지는 사람이 검수해서 실제로 서비스에 나간 카드입니다.
     정답은 이 페이지에 없습니다 (소스를 열어도 안 나옵니다).</p>
</header>
<main>{cards}</main>
<footer>
  <button id="done" disabled>고른 것 확인</button>
  <span id="out">카드를 {n_ai}장 고르세요.</span>
</footer>
<script>
const N_AI = {n_ai}, MIX = "{mix_id}";
const boxes = Array.from(document.querySelectorAll('input[type=checkbox]'));
const out = document.getElementById('out'), done = document.getElementById('done');

function picked() {{
  return boxes.filter(b => b.checked).map(b => Number(b.dataset.n)).sort((a,b)=>a-b);
}}
function sync() {{
  boxes.forEach(b => b.closest('.card').classList.toggle('on', b.checked));
  const p = picked();
  done.disabled = p.length !== N_AI;
  if (p.length !== N_AI) out.textContent = `${{p.length}}/${{N_AI}}장 골랐습니다.`;
}}
boxes.forEach(b => b.addEventListener('change', sync));
done.addEventListener('click', () => {{
  const p = picked().join(',');
  const cmd = `python story.py --card-mix-score "${{p}}"`;
  out.innerHTML = '고른 카드 <b>' + p + '</b> · 채점하려면 이 명령을 실행하세요:<br><b>'
                + cmd + '</b>';
  navigator.clipboard && navigator.clipboard.writeText(cmd);
}});
sync();
</script>
</body></html>
"""


def latest_mix(out_dir: Path) -> Path:
    root = out_dir / MIX_DIR
    dirs = sorted((d for d in root.iterdir() if (d / "mix_key.json").exists()),
                  key=lambda d: d.name) if root.is_dir() else []
    if not dirs:
        raise SystemExit(
            f"채점할 시험지가 없습니다: {root}\n"
            f"  먼저 python story.py --card-mix --genre <장르> 를 실행하세요.")
    return dirs[-1]


def score_card_mix(out_dir: Path, answer: str, mix_id: str = None) -> int:
    """뷰어가 고른 번호를 채점하고 mix_result.csv 에 남긴다.

    합격선은 **우연**이다. 9장 중 3장을 찍으면 평균 1장은 맞는다. 그러니
    1장 이하는 "못 골랐다"이고, 3장 전부는 "형식이든 문체든 티가 났다"이다.
    """
    mix_dir = (out_dir / MIX_DIR / mix_id) if mix_id else latest_mix(out_dir)
    key_path = mix_dir / "mix_key.json"
    if not key_path.exists():
        raise SystemExit(f"정답 파일이 없습니다: {key_path}")
    key = json.loads(key_path.read_text(encoding="utf-8"))

    picked = []
    for tok in re.split(r"[,\s]+", str(answer or "").strip()):
        if not tok:
            continue
        if not tok.isdigit():
            raise SystemExit(f"번호가 아닌 값이 있습니다: '{tok}' (예: \"2,5,9\")")
        picked.append(int(tok))
    picked = sorted(set(picked))

    n_total, n_ai = int(key["n_total"]), int(key["n_ai"])
    bad = [p for p in picked if not 1 <= p <= n_total]
    if bad:
        raise SystemExit(f"1~{n_total} 범위를 벗어난 번호입니다: {bad}")
    if len(picked) != n_ai:
        raise SystemExit(
            f"{n_ai}장을 골라야 합니다. {len(picked)}장을 받았습니다: {picked}")

    truth = set(int(x) for x in key["answer"])
    hits = sorted(truth & set(picked))
    misses = sorted(set(picked) - truth)
    chance = round(n_ai * n_ai / n_total, 2)     # 무작위로 찍었을 때의 기대 적중

    if len(hits) <= 1:
        verdict = "합격(못 고름)"
    elif len(hits) >= n_ai:
        verdict = "불합격(전부 골라냄)"
    else:
        verdict = "애매"

    row = {
        "at": datetime.now().isoformat(timespec="seconds"),
        "mix_id": key["mix_id"], "genre": key["genre"],
        "n_ai": n_ai, "n_total": n_total,
        "picked": " ".join(str(p) for p in picked),
        "hits": len(hits), "misses": len(misses),
        "chance": chance, "verdict": verdict,
        "note": f"정답 {sorted(truth)} / 맞힌 것 {hits}",
    }
    path = out_dir / MIX_RESULT
    exists = path.exists()
    with open(path, "a", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=MIX_COLUMNS)
        if not exists:
            w.writeheader()
        w.writerow(row)

    log(f"시험지 {key['mix_id']} · {samples.genre_label(key['genre'])}")
    log(f"  고른 것 {picked} / 정답 {sorted(truth)}")
    log(f"  맞힘 {len(hits)}장 (우연 기대치 {chance}장) · 헛짚음 {len(misses)}장")
    log(f"  판정: {verdict}")
    if verdict.startswith("합격"):
        log("  → 사람이 골라내지 못했습니다. 이 카드들은 기준선과 같은 급입니다.")
    elif verdict.startswith("불합격"):
        log("  → 전부 골라냈습니다. 무엇이 티가 났는지 보고 P1 을 고치세요 "
            "(대개 intro 의 전환이 약하거나, 문장이 다 같은 길이입니다).")
    log(f"기록: {path}")
    return 0


def row_label(row: dict) -> str:
    """요약 CSV·로그에 쓸 짧은 이름. 카드 입력은 재료 전체가 character 라서 길다."""
    return str(row.get("_name") or row.get("character") or "").splitlines()[0][:40]


CARD_TEXT_FIELDS = ("name", "rank", "personality", "quote", "intro")


def card_material(card: dict) -> str:
    """카드 → P1 이 읽을 캐릭터 재료 텍스트.

    한 줄 입력과 카드 입력이 같은 자리로 들어가야 프롬프트가 하나로 유지된다.
    카드 원본은 card_json 으로 따로 한 번 더 들어간다(이쪽은 요약이다).
    """
    lines = [f"{k}: {card[k]}" for k in CARD_TEXT_FIELDS
             if str(card.get(k) or "").strip()]
    ap = card.get("appearance")
    if isinstance(ap, dict):
        lines.append("외형: " + " / ".join(
            str(ap.get(k) or "") for k in ("hair", "eyes", "impression", "element")))
    for i, b in enumerate(card.get("fateBeats") or card.get("fate_beats") or [], 1):
        lines.append(f"운명 비트 {i}: {b}")
    return "\n".join(lines)


def read_cards(path: Path, genre: str = "") -> list:
    """cards.json → 파이프라인 입력 행. 카드는 row['card'] 로 그대로 실린다.

    장르는 카드의 genre 칸 → id 접두어("romance-01") → --genre 순으로 찾는다.
    셋 다 없으면 멈춘다. 장르를 모르면 어느 샘플을 보여줄지 고를 수 없다.
    """
    if not path.exists():
        raise SystemExit(f"카드 파일이 없습니다: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit(f"{path} 를 JSON 으로 읽지 못했습니다: {exc}")

    cards = data.get("cards") if isinstance(data, dict) else data
    if isinstance(cards, dict):
        cards = [cards]
    if not isinstance(cards, list) or not cards:
        raise SystemExit(
            f"{path} 에 카드가 없습니다. StoryCard 객체의 배열이거나 "
            f'{{"cards": [...]}} 형태여야 합니다.')

    rows = []
    for i, card in enumerate(cards, 1):
        if not isinstance(card, dict):
            raise SystemExit(f"{path} 의 {i}번째 항목이 객체가 아닙니다.")
        key = str(card.get("genre") or "").strip()
        if not key:
            prefix = str(card.get("id") or "").split("-")[0].strip().lower()
            key = prefix if prefix in samples.GENRES else ""
        key = key or genre
        if not key:
            raise SystemExit(
                f"{path} 의 {i}번째 카드에 장르가 없습니다. 카드에 \"genre\" 를 넣거나 "
                f"--genre 로 지정하세요 (샘플 장르: {', '.join(sorted(samples.GENRES))}).")
        name = str(card.get("name") or card.get("id") or f"카드{i}").strip()
        rows.append({
            "character": card_material(card) or name,
            "one_line": str(card.get("intro") or "").strip(),
            "genre": key,
            "card": card,
            "_name": name,
        })
    return rows


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="스토리 파이프라인 반복 실행 하네스",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--character", default=None,
                   help="캐릭터 파일 1개 (JSON). 캐릭터만 있으면 나머지는 AI 가 채웁니다. "
                        "예: --character characters/example_minimal.json")
    p.add_argument("--input", default="inputs.csv", help="character,one_line,genre 열을 가진 CSV")
    p.add_argument("--input-card", default=None,
                   help="StoryCard JSON 입력 (--input 대신). 카드를 P1 이 확장한다")
    p.add_argument("--genre", default=None,
                   help=f"--input-card / --card-mix 의 장르 "
                        f"({', '.join(sorted(samples.GENRES))})")
    p.add_argument("--card-mix", action="store_true",
                   help="새 P1 카드를 샘플 카드와 섞어 블라인드로 골라내는 시험지 생성")
    p.add_argument("--card-mix-score", default=None,
                   help='뷰어가 준 답안을 채점 (예: --card-mix-score "2,5,9")')
    p.add_argument("--n", type=int, default=1, help="같은 입력을 몇 번 반복할지 (재현성 확인용)")
    p.add_argument("--control", action="store_true", help="대조군만 실행")
    p.add_argument("--both", action="store_true", help="파이프라인과 대조군을 모두 실행")
    p.add_argument("--scenes", type=int, default=3, help="장면 수 (기본 3)")
    p.add_argument("--provider", default=None, choices=sorted(PROVIDERS),
                   help=f".env 의 PROVIDER 를 덮어씀 (현재 {DEFAULT_PROVIDER})")
    p.add_argument("--model", default=None,
                   help=f".env 의 모델을 덮어씀 (현재 {DEFAULT_MODEL})")
    p.add_argument("--judge-model", default=None, help="P3 전용 모델 (기본: --model 과 동일)")
    p.add_argument("--max-tokens", type=int, default=DEFAULT_MAX_TOKENS)
    p.add_argument("--max-gate-retries", type=int, default=2, help="P2 게이트 재생성 상한")
    p.add_argument("--max-p3-retries", type=int, default=2, help="P3 지시 재생성 상한 (기본 2)")
    # 점검이 8항목에서 11항목으로 늘었다. 한 번 고쳐서 11개를 동시에 맞추기는
    # 어렵고, SCENE 은 한 번에 5센트짜리 호출이라 한 번 더 주는 편이 싸다 —
    # 여기서 아끼면 사람이 약한 원고를 손보게 된다.
    p.add_argument("--scene-fix", type=int, default=2,
                   help="장면 점검 11항목에 걸렸을 때 SCENE 재생성 상한 (기본 2)")
    p.add_argument("--mock", action="store_true",
                   help="API 없이 가짜 응답으로 게이트·판정·산출물 경로만 점검")
    p.add_argument("--out", default=str(RUNS_DIR), help="출력 디렉터리 (기본 runs/)")
    p.add_argument("--seed", type=int, default=None, help="read.html 배치 셔플 시드")
    p.add_argument("--check", action="store_true", help="API 호출 없이 프롬프트 변수만 점검")
    p.add_argument("--build-read", action="store_true", help="read.html 만 다시 생성")
    p.add_argument("--serve", action="store_true", help="블라인드 평가 서버 실행")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--resolve-blind", default=None, help="내려받은 blind_raw.csv 를 병합")
    p.add_argument("--no-read", action="store_true", help="실행 후 read.html 자동 생성 안 함")

    # 캐릭터 시트 — P1 외형 사양으로 시트 이미지를 뽑는다 (이미지는 OpenAI)
    p.add_argument("--charsheet", action="store_true",
                   help="P1 외형 사양으로 캐릭터 시트를 생성 (--run-id 필요)")
    p.add_argument("--run-id", default=None, help="대상 run_id")
    p.add_argument("--dry-run", action="store_true",
                   help="--charsheet 를 0원으로: 프롬프트만 출력하고 API 를 안 부름")
    p.add_argument("--pick", action="store_true",
                   help="이미 뽑아 둔 시트의 채택 화면(pick.html)을 띄움")
    p.add_argument("--split", action="store_true",
                   help="--charsheet 를 3장 분할로 (기본은 1장 통합)")
    p.add_argument("--quality", default=DEFAULT_OPENAI_IMAGE_QUALITY,
                   choices=OPENAI_IMAGE_QUALITIES,
                   help=f"--provider openai 의 이미지 품질 "
                        f"(기본 {DEFAULT_OPENAI_IMAGE_QUALITY})")
    p.add_argument("--candidates", type=int, default=DEFAULT_CHARSHEET_CANDIDATES,
                   help=f"시트당 후보 장수 (기본 {DEFAULT_CHARSHEET_CANDIDATES})")
    p.add_argument("--with-charsheet", action="store_true",
                   help="P3 통과 직후 캐릭터 시트까지 이어서 뽑습니다 "
                        "(외형이 확정되는 자리입니다. 비용 확인은 그대로 뜹니다)")
    p.add_argument("--second-lead", action="store_true",
                   help="--charsheet 를 주인공이 아니라 '그 한 사람'(두 번째 "
                        "주연)으로 뽑음. webtoon/series.json 에 그 인물의 명부가 "
                        "채워진 뒤에만 쓸 수 있음 (charsheet_2nd/ 에 저장)")
    p.add_argument("--yes", action="store_true",
                   help="--charsheet 의 비용 확인 프롬프트를 건너뜀")
    # 작가가 결과를 보고 "이걸 고쳐 달라"고 적은 말. P1·P2 프롬프트의
    # {retry_feedback} 자리에 [작가 요청] 블록으로 들어간다. 안 주면 예전과
    # 똑같다. --charsheet 모드에는 안 먹는다 (시트는 p1.json 사양으로만 그린다).
    p.add_argument("--author-note", default="",
                   help="작가가 다시 만들며 요청한 것 (P1·P2 프롬프트에 실림)")
    # 작품 규칙 파일. 다시 만들기는 늘 **새 run_id** 를 만들므로(--character 를
    # 받으면 새 폴더), 이전 run 의 memory.json 을 이 플래그로 넘겨받는다.
    # 새 run 폴더에는 아래 main() 이 사본을 남긴다 — 규칙이 작품을 따라다닌다.
    p.add_argument("--memory-file", default="",
                   help="작품 규칙(memory.json) 경로 — P1·P2·SCENE 프롬프트에 실림")
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    out_dir = Path(args.out).resolve()
    # 작품 규칙. 없으면 빈 구조라 프롬프트가 한 글자도 안 바뀐다.
    user_memory = load_user_memory(args.memory_file) if args.memory_file \
        else {"always": [], "keyword": []}

    if args.resolve_blind:
        resolve_blind(out_dir, Path(args.resolve_blind))
        return 0
    if args.build_read:
        build_read(out_dir, args.seed)
        return 0
    if args.serve:
        serve(out_dir, args.port)
        return 0
    if args.card_mix_score:
        return score_card_mix(out_dir, args.card_mix_score)

    if args.charsheet or args.pick:
        if not args.run_id:
            raise SystemExit(
                "--run-id <run_id> 가 필요합니다.\n"
                "  python story.py --charsheet --run-id <run_id>")
        run_dir = out_dir / args.run_id
        if not run_dir.is_dir():
            raise SystemExit(f"실행 디렉터리가 없습니다: {run_dir}")
        if args.pick:
            serve_charsheet(run_dir, args.port)
            return 0
        # 시트 이미지는 기본이 gemini 다 (컷과 같은 모델). .env 의 PROVIDER 는
        # 텍스트 단계용이므로 여기 기본값으로 끌어오지 않는다 — --provider 로만 바꾼다.
        image_provider = (args.provider or DEFAULT_IMAGE_PROVIDER).strip().lower()
        if image_provider not in IMAGE_PROVIDERS:
            raise SystemExit(
                f"--charsheet 의 --provider 는 {' 또는 '.join(IMAGE_PROVIDERS)} 입니다 "
                f"(받은 값: {image_provider}).")
        if args.second_lead:
            # 이 인물의 외형 사양은 그림이 아니라 글로 먼저 만들어야 한다 —
            # p1.json 처럼 손으로 확정된 것이 없고, 명부의 네 줄(gender/
            # appearance/outfit/personality)뿐이다.
            text_provider, model, judge_model = resolve_provider(args)
            backend = MockBackend(text_provider) if args.mock else make_backend(text_provider)
            caller = Caller(backend, model, judge_model, args.max_tokens)
            usage = Usage()
            ps = load_prompts()
            spec = solve_lead_appearance(caller, ps, run_dir, usage, args.dry_run)
            if spec is None:      # dry-run, 캐시 없음 — 프롬프트만 보여주고 끝
                return 0
            if usage.records:
                log(f"  {usage.cost_line()}")
            return run_charsheet(run_dir, args.dry_run, max(1, args.candidates),
                                 args.yes, args.split, image_provider, args.quality,
                                 spec_path=run_dir / "charsheet_2nd" / "lead.json",
                                 sheet_dirname="charsheet_2nd",
                                 meta_key="charsheet_second_lead")
        return run_charsheet(run_dir, args.dry_run, max(1, args.candidates),
                             args.yes, args.split, image_provider, args.quality)

    ps = load_prompts()
    if args.check:
        log("프롬프트 변수 점검 완료. 위에 경고가 없으면 계약이 맞습니다.")
        log(f"해시: {json.dumps(ps.short_hashes, ensure_ascii=False, indent=2)}")
        return 0

    provider, model, judge_model = resolve_provider(args)
    backend = MockBackend(provider) if args.mock else make_backend(provider)
    describe_setup(provider, model, judge_model, args.mock)

    if args.card_mix:
        if not args.genre:
            raise SystemExit(
                "--card-mix 에는 --genre 가 필요합니다. "
                f"쓸 수 있는 장르: {', '.join(samples.available())}")
        out_dir.mkdir(parents=True, exist_ok=True)
        log_prompt_hashes(out_dir, ps, f"{provider}/{model}")
        caller = Caller(backend, model, judge_model, args.max_tokens)
        log(f"카드 믹싱 · {samples.genre_label(args.genre)} · 새 카드 {args.n}장")
        path = run_card_mix(caller, ps, args.genre, args.n, out_dir, args.seed)
        log(f"시험지: {path}")
        log("→ 브라우저로 열어 AI 가 만든 카드를 골라내세요. "
            "고르면 채점 명령이 나옵니다.")
        return 0

    if args.character:
        rows = [read_character(Path(args.character))]
        r = rows[0]
        if args.genre and not r["genre"]:
            r["genre"] = args.genre
        given = [k for k in ("genre", "world", "one_line") if r.get(k)]
        log(f"캐릭터 입력: {r['_name']}  ({Path(args.character)})")
        log(f"  사진 {len(r['photos'])}장 · 작가가 준 칸 {given or '없음 — AI 가 정합니다'}")
    elif args.input_card:
        rows = read_cards(Path(args.input_card), args.genre or "")
        log(f"카드 입력 {len(rows)}장: {Path(args.input_card)}")
    else:
        rows = read_inputs(Path(args.input))
    out_dir.mkdir(parents=True, exist_ok=True)
    log_prompt_hashes(out_dir, ps, f"{provider}/{model}")

    conditions = []
    if args.both:
        conditions = ["pipeline", "control"]
    elif args.control:
        conditions = ["control"]
    else:
        conditions = ["pipeline"]

    caller = Caller(backend, model, judge_model, args.max_tokens)

    total = len(rows) * args.n * len(conditions)
    done = 0
    tally = {}
    spent = 0.0     # 단가를 아는 실행만 더한다 (모르는 것은 0 이 아니라 제외)
    unpriced = set()    # 그래서 빠진 모델 — 합계 옆에 같이 적는다
    last_pipeline_run = ""      # 끝에 다음 순서를 안내할 때 쓴다
    t_start = time.monotonic()

    for row in rows:
        for i in range(1, args.n + 1):
            for cond in conditions:
                done += 1
                log(f"[{done}/{total}] {cond} · {row_label(row)} · 회차 {i}")
                if cond == "pipeline":
                    res = run_pipeline(caller, ps, row, i, args.scenes,
                                       args.max_gate_retries, args.max_p3_retries,
                                       args.scene_fix, out_dir,
                                       author_note=args.author_note,
                                       memory=user_memory)
                else:
                    res = run_control(caller, ps, row, i, args.scenes, out_dir)
                append_summary(out_dir, res)
                tally[res.status] = tally.get(res.status, 0) + 1
                spent += res.cost_usd or 0.0
                if res.cost_note:
                    unpriced.add(res.cost_note)
                log(f"    -> {res.status} · 재생성 {res.regen_count}회 · "
                    f"{res.total_tokens:,}토큰 · "
                    f"{cost_text(res.cost_usd, res.cost_note)} · "
                    f"{res.elapsed_sec:.0f}초 · {res.run_id}")

                # 캐릭터 시트는 **여기**가 제자리다. P3 를 통과했다는 것은 외형
                # 사양(appearance_en·design_details·color_palette)이 확정됐다는
                # 뜻이고, 그 뒤로는 아무도 그것을 바꾸지 않는다.
                #
                # P1 직후가 아닌 이유: P3 는 탈락 항목에 따라 P1 을 다시 뽑는다
                # (target_stage == "P1"). 거기서 시트를 뽑으면 방금 돈 주고 만든
                # 시트가 버려진 카드의 것이 된다.
                if cond == "pipeline":
                    last_pipeline_run = res.run_id
                if (args.with_charsheet and cond == "pipeline"
                        and res.status in (STATUS_OK, STATUS_HUMAN)):
                    try:
                        # --provider 는 **텍스트** 단계용이다. 그대로 넘기면
                        # openai 로 돌릴 때 시트까지 openai 가 그리게 되는데,
                        # 시트는 컷과 같은 모델로 뽑아야 레퍼런스로 쓸모가 있다.
                        run_charsheet(out_dir / res.run_id, args.dry_run,
                                      max(1, args.candidates), args.yes,
                                      args.split, DEFAULT_IMAGE_PROVIDER,
                                      args.quality)
                    except SystemExit as e:
                        warn(f"    캐릭터 시트를 건너뜁니다: {e}")

    log("\n" + "-" * 60)
    log(f"완료 {done}건 / {time.monotonic() - t_start:.0f}초")
    if spent or unpriced:
        log(f"  합계 비용: 약 {cost_text(spent, '; '.join(sorted(unpriced)))}")
    for k, v in sorted(tally.items()):
        log(f"  {k}: {v}")
    log(f"요약: {out_dir / 'summary.csv'}")

    if not args.no_read:
        try:
            build_read(out_dir, args.seed)
            log(f"블라인드 평가:  python story.py --serve")
        except SystemExit as e:
            warn(str(e))

    # 다음에 무엇을 할지 순서대로 적는다. 캐릭터 시트를 컷보다 **먼저** 두는
    # 이유: 시트는 외형이 확정된 직후에 뽑는 것이고, 컷을 다 뽑고 나서 시트를
    # 만들면 이미 그려진 컷과 시트가 다른 사람일 수 있다.
    if last_pipeline_run and not args.with_charsheet:
        log("")
        log("다음 순서:")
        log(f"  1. 캐릭터 시트   python story.py --charsheet --run-id {last_pipeline_run}")
        log(f"     채택          python story.py --charsheet --run-id {last_pipeline_run} --pick")
        log(f"  2. 컷 설계       python webtoon.py --run {last_pipeline_run}")
        log(f"  3. 그림          cd ../webtoon-harness && "
            f"python run.py --run-id {last_pipeline_run} --episode 1 -c S")
        log("  (시트를 story.py 안에서 바로 뽑으려면 --with-charsheet 를 붙이세요)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
