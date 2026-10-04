#!/usr/bin/env python3
"""
new_harness 가 import 해서 빌려 쓰는 라이브러리 (직접 실행하지 않는다).

빌려 가는 것은 두 가지다.
  - 모델 호출 계층: OpenAI · Gemini · Anthropic 백엔드(make_backend), 환경변수 읽기,
    JSON 응답 파싱(extract_json · ParseFailure), 단가표로 비용 계산(cost_of · cost_text)
  - 캐릭터 시트 그림 생성: make_sheet_painter · charsheet_source · charsheet_unit_cost
    (이미지 프로바이더는 image_backend_ready 로 확인)

옛 독립 실행 파이프라인(P1 캐릭터시트 -> P2 프리미스 -> 게이트 -> 검수 -> 장면 생성,
대조군, 블라인드 평가 서버, 시트 CLI)은 #493 에서 걷어냈다. 그 내용은 이 파일의
이전 커밋에 있다.
"""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path

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


# 이 하네스만 쓰는 값의 앞자리.
#
# 운영 서버는 도메인 여럿이 한 기계를 나눠 쓴다. 거기서 `OPENAI_API_KEY` 같은
# 이름을 그대로 쓰면 누가 쓴 키인지 가릴 수가 없고, 남의 설정이 이쪽에 흘러
# 들어오기도 한다. 그래서 **앞자리를 붙인 이름을 먼저 본다.**
#
#     HAEUN_OPENAI_API_KEY=...     서버에는 이것만 둔다
#     OPENAI_API_KEY=...           없으면 이걸 본다 (내 컴퓨터의 .env)
#
# 옆 도메인(zzal)이 `ZZAL_OPENAI_API_KEY` 를 쓰는 것과 같은 규칙이다.
#
# ★ 앞자리 이름이 없으면 예전과 **한 글자도 다르지 않게** 동작한다. 그래서
#   지금 쓰던 .env 도, 하네스를 직접 돌리던 것도 그대로다.
ENV_PREFIX = "HAEUN_"


def env(key: str, default=None):
    value = os.environ.get(ENV_PREFIX + key)
    if value in (None, ""):
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
# 기본은 Gemini. .env 의 PROVIDER 로 바꾼다. (new_harness 는 llm.py 의 DEFAULT_PROVIDER 로 덮어쓴다.)

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

# ---------------------------------------------------------------- utilities

def now_stamp() -> str:
    return datetime.now().strftime("%Y%m%dT%H%M%S")


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


# ---------------------------------------------------------------- API

class TextRefused(Exception):
    """글 모델이 만들기를 거절했다(#626) — 성인물 같은 요청에 「죄송하지만…」으로 답한 것.

    JSON 을 받아야 할 자리라 파싱 실패로만 보이면 서버는 「이야기 후보를 만들지 못했습니다」만 말할 수
    있었다. 거절이면 run.py 가 failure.json 에 text_refusal 로 남겨 사람에게 무엇을 바꾸면 되는지 말한다.
    """


# 거절 문장의 머리. 정상 답은 길고 JSON 이라 이것들로 시작하는 짧은 답이면 거절로 본다.
_REFUSAL_HEAD = re.compile(
    r"^\s*(I['’]m sorry|I am sorry|Sorry,|I can['’]t help|I cannot help|I can['’]t assist|I cannot assist|"
    r"I can['’]t comply|I won['’]t|죄송하지만|죄송합니다|도와드릴 수 없|요청하신 내용은|그 요청은)", re.IGNORECASE)


def looks_refused(text: str, refusal: str | None = None) -> bool:
    """모델 답이 거절인가. OpenAI 가 refusal 칸을 채웠거나, 짧은 답이 거절 문장으로 시작하면."""
    if refusal:
        return True
    t = (text or "").strip()
    return bool(t) and len(t) < 400 and "{" not in t and bool(_REFUSAL_HEAD.match(t))


class ParseFailure(Exception):
    # raw 는 고를 수 있다 — 「JSON 객체가 아닙니다」처럼 이유 한 줄만 넘기는 곳이 일곱 군데다.
    # 꼭 받게 해 뒀더니 응답을 못 읽은 그 순간에 TypeError 로 바뀌어 원래 이유를 가렸다(#626).
    def __init__(self, stage, raw=""):
        super().__init__(f"{stage} JSON 파싱 실패")
        self.stage = stage
        self.raw = raw


# ---------------------------------------------------------------- 이미지 입력
#
# 프롬프트에 사진을 붙일 때 쓴다.
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
        if looks_refused(text, getattr(choice.message, "refusal", None)):
            raise TextRefused(text.strip()[:300] or "refused")
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


HANGUL_RE = re.compile(r"[가-힣]")
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

# 시트는 **컷과 같은 모델**로 뽑는다. 다른 모델로 뽑으면 같은 스타일 문구를 넣어도
# 그림체가 어긋나서, 그 시트를 레퍼런스로 쓰는 의미가 없어진다.
# (실제로 gpt-image-1 은 얼굴 비율이 크고 이목구비가 웹툰 양식이 아니었다.)
IMAGE_PROVIDERS = ("gemini", "openai")
DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"
DEFAULT_OPENAI_IMAGE_MODEL = "gpt-image-2"      # gpt-image-1 은 2026-10-23 종료
# 1536x1024 medium 기준 장당 단가. 다른 품질은 요금표가 달라서 적지 않는다 —
# 모르는 숫자를 적어 두면 비용 표시가 거짓말이 된다. .env 로 덮어쓸 수 있다.
OPENAI_IMAGE_COST_USD = {"medium": 0.041}
# 스타일 문구는 **webtoon-harness 가 원본**이다. 여기서 하드코딩하지 않고 읽어 온다.
# 시트가 다른 그림체로 나오면 그걸 레퍼런스로 그리는 컷이 전부 어긋나기 때문이다.
# 아래 값은 읽기에 실패했을 때 대조하는 기준일 뿐, 프롬프트에 쓰는 값이 아니다.
WEBTOON_HARNESS_DIR = env("WEBTOON_HARNESS_DIR") or str(ROOT.parent / "webtoon-harness")
# design_details — 매 컷에 유지될 물리적 특징. 그릴 수 없는 말이 섞이면 반려한다.
DESIGN_DETAIL_MIN, DESIGN_DETAIL_MAX = 3, 5
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
# ---------------------------------------------------------------- 실행

def new_run_id() -> str:
    return f"{now_stamp()}-{uuid.uuid4().hex[:6]}"


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
        # 달라진다.
        #
        # design_details 에 섞지 않고 자리를 따로 둔 이유: 그쪽은 "고정 디자인
        # 요소" 라서 개수(n_details)가 region 3 의 인셋 개수를 정한다. 거기에
        # "얼굴이 사진과 다르다" 같은 줄을 넣으면 시트에 그 말의 확대 컷이
        # 하나 더 생긴다. 고쳐 달라는 말은 그릴 것이 아니라 지시다.
        "sheet_corrections": [str(c).strip()
                              for c in (p1.get("sheet_corrections") or [])
                              if str(c or "").strip()],
    }


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


def make_sheet_painter(provider: str, model: str, quality: str,
                       photos: list[Path] | None = None):
    """(prompt, kind) -> (PNG 바이트, meta). 어느 provider 든 같은 모양으로 부른다.

    meta 는 성공이든 실패든 응답이 말해 준 것(finish_reason·텍스트 등)을 담는다
    — 호출부가 성공/실패 상관없이 charsheet_meta.json 에 그대로
    받아 적는다. photos 가 있으면 텍스트 사양과 나란히 첨부한다.
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

    # 순수 추가 — 기존에는 usage_metadata 를 아예 안 읽었다. 비용 계산용으로만
    # 옆에 더하고, 못 읽으면 None 으로 둔다(기존 동작에 영향 없음).
    usage_dict = None
    usage_meta = getattr(resp, "usage_metadata", None)
    if usage_meta is not None:
        for kwargs in ({"by_alias": True}, {}):
            try:
                usage_dict = usage_meta.model_dump(**kwargs)
                break
            except Exception:
                continue

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
                    "usage_dict": usage_dict,
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
    usage_obj = getattr(resp, "usage", None)
    usage_dict = None
    if usage_obj is not None:
        try:
            usage_dict = usage_obj.model_dump()
        except Exception:
            usage_dict = None
    meta = {
        "revised_prompt": getattr(item, "revised_prompt", None),
        "usage": usage_obj and str(usage_obj),
        # 순수 추가 필드. 기존 "usage"(문자열)는 그대로 두고, 비용 계산에
        # 쓸 구조화된 값만 옆에 더한다 — 예전 run 의 meta.json 을 다시 읽는
        # 코드가 있어도 깨지지 않는다.
        "usage_dict": usage_dict,
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
