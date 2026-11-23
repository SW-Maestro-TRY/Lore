"""Codex 프록시 전환 시 생성 경로와 기록이 함께 바뀌는지 확인한다."""

from __future__ import annotations

import os
import subprocess
import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent


class CodexProxyTest(unittest.TestCase):
    def test_disabled_mode_uses_existing_stage_defaults(self):
        script = ('import llm; '
                  'assert llm.provider_for("SCENE") == "openrouter"; '
                  'assert llm.model_for("SCENE", "openrouter") == "google/gemini-3.8-flash"')
        env = {**os.environ, "OPENROUTER_API_KEY": "other-provider-key"}
        env.pop("WEBTOON_CODEX_PROXY", None)
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_generation_routes_and_subscription_usage(self):
        script = r'''
import base64
import os
import tempfile
from pathlib import Path
from unittest.mock import patch
import sys

sys.path.insert(0, str(Path.cwd().parent / "webtoon-harness"))

import llm
import runmeta
from providers.base import GenRequest
from providers.openai_images import OpenAIProvider

assert llm.provider_for("WORLD_MAKE") == "openai"
assert llm.provider_for("STORY") == "openai"
assert llm.provider_for("PAGE_IMAGE") == "openai"
assert llm.model_for("WORLD_MAKE", "openai") == "gpt-6-sol"
assert llm.model_for("STORY", "openai") == "gpt-6-sol"
assert llm.model_for("PAGE_IMAGE", "openai") == "gpt-image-2.5-flare"
# 장면 나누기와 검수 넷은 구독 경로를 켜도 OpenRouter 의 Gemini 그대로
assert llm.provider_for("SCENE") == "openrouter"
assert llm.model_for("SCENE", "openrouter") == "google/gemini-3.8-flash"
for review in ("STORY_REVIEW", "PAGE_REVIEW", "COVER_REVIEW", "FULL_REVIEW"):
    assert llm.provider_for(review) == "openrouter", review
    assert llm.model_for(review, "openrouter") == "google/gemini-3.5-flash", review
plan = {row["stage"]: (row["provider"], row["model"]) for row in llm.plan()}
assert plan["SCENE"] == ("openrouter", "google/gemini-3.8-flash")
assert plan["PAGE_REVIEW"] == ("openrouter", "google/gemini-3.5-flash")
assert plan["NARRATION"] == ("openai", "gpt-6-sol")

# OpenRouter 로 간 호출은 실제 청구액을 남기고, 프록시로 간 호출만 구독으로 적는다
class FakeBackend:
    def complete(self, model, prompt, temperature, limit, images=None):
        return "ok", {"input": 1000, "output": 100, "billed_usd": 0.0174}, "end"
for stage in ("SCENE", "STORY"):
    call = llm.Call(stage)
    call.backend = FakeBackend()
    _, meta = call("prompt")
    if stage == "SCENE":
        assert meta["provider"] == "openrouter"
        assert meta["cost"]["cost_basis"] == "billed", meta["cost"]
        assert meta["cost"]["total"] == 0.0174, meta["cost"]
    else:
        assert meta["provider"] == "openai"
        assert meta["cost"]["cost_basis"] == "codex_subscription"
        assert meta["cost"]["total"] == 0
assert os.environ["OPENAI_API_KEY"] == "proxy-test-key"
assert os.environ["OPENAI_BASE_URL"] == "http://127.0.0.1:8317/v1"

class FakeResponse:
    status_code = 200
    def json(self):
        return {"data": [{"b64_json": base64.b64encode(b"png").decode()}]}

provider = OpenAIProvider("gpt-image-2", "proxy-test-key")
with patch("providers.openai_images.requests.post", return_value=FakeResponse()) as post:
    provider.generate(GenRequest(prompt="blue crane"))
    assert post.call_args.args[0] == "http://127.0.0.1:8317/v1/images/generations"

with tempfile.TemporaryDirectory() as tmp:
    ref = Path(tmp) / "ref.png"
    ref.write_bytes(b"png")
    with patch("providers.openai_images.requests.post", return_value=FakeResponse()) as post:
        provider.generate(GenRequest(prompt="blue crane", images=[ref]))
        assert post.call_args.args[0] == "http://127.0.0.1:8317/v1/images/edits"

with tempfile.TemporaryDirectory() as tmp:
    runmeta.append_call(Path(tmp), {"stage": "WORLD_MAKE", "provider": "openai",
                                     "model": "gpt-6-sol", "usage": {"input": 12},
                                     "cost": {"total": 1.23, "total_krw": 1700}})
    cost = runmeta.load(Path(tmp))["calls"][0]["cost"]
    assert cost["total"] == 0 and cost["total_krw"] == 0
    assert cost["cost_basis"] == "codex_subscription"
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "OPENROUTER_API_KEY": "other-provider-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_proxy_without_openrouter_key_keeps_everything_on_proxy(self):
        script = ('import llm; '
                  'assert llm.provider_for("SCENE") == "openai"; '
                  'assert llm.model_for("SCENE", "openai") == "gpt-6-sol"; '
                  'assert llm.provider_for("PAGE_REVIEW") == "openai"')
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "OPENROUTER_API_KEY": ""}       # 빈 값 — new_harness/.env 가 있어도 setdefault 라 안 채운다
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_text_fallback_to_api_key_on_proxy_failure_and_circuit_breaker(self):
        script = r'''
import tempfile
from pathlib import Path
from unittest.mock import patch

import llm
import runmeta

assert llm.can_fallback_to_api_key()
assert not llm.is_codex_proxy_tripped()
assert llm.model_for("STORY", "openai") == "gpt-6-sol"

class FailingProxyBackend:
    calls = 0
    def supports_temperature(self, model):
        return True
    def complete(self, model, prompt, temperature, limit, images=None):
        FailingProxyBackend.calls += 1
        raise RuntimeError("Error code: 429 - usage_limit_reached")

direct_calls = []
class DirectOpenAIBackend:
    def __init__(self, api_key=None, base_url=None, max_retries=3):
        self.api_key = api_key
        self.base_url = base_url
        self.max_retries = max_retries
    def supports_temperature(self, model):
        return True
    def complete(self, model, prompt, temperature, limit, images=None):
        direct_calls.append((self.api_key, self.base_url, model))
        return "direct-ok", {"input": 1000, "output": 200, "cache_read": 0, "cache_write": 0}, "end"

with patch.object(llm.story, "OpenAIBackend", DirectOpenAIBackend):
    call = llm.Call("STORY")
    call.backend = FailingProxyBackend()
    text, meta = call("hello")
    assert text == "direct-ok"
    assert FailingProxyBackend.calls == 1
    assert direct_calls == [("real-openai-key", llm.OPENAI_OFFICIAL_BASE_URL, "gpt-5.1")]
    assert meta["model"] == "gpt-5.1"
    assert meta["codex_proxy_fallback"] is True
    assert "429" in meta["codex_proxy_fallback_reason"]
    assert meta["cost"].get("cost_basis") != "codex_subscription", meta["cost"]
    assert meta["cost"]["total"] > 0, meta["cost"]
    assert llm.is_codex_proxy_tripped()

    # runmeta 도 폴백 호출의 실제 비용을 0 으로 덮지 않아야 한다
    with tempfile.TemporaryDirectory() as tmp:
        runmeta.append_call(Path(tmp), meta)
        saved = runmeta.load(Path(tmp))["calls"][0]
        assert saved["codex_proxy_fallback"] is True
        assert saved["cost"].get("cost_basis") != "codex_subscription"
        assert saved["cost"]["total"] == meta["cost"]["total"]

    # 같은 프로세스의 다음 호출은 프록시를 다시 치지 않고 바로 공식 키로 간다
    call2 = llm.Call("NARRATION")
    text2, meta2 = call2("next")
    assert text2 == "direct-ok"
    assert FailingProxyBackend.calls == 1
    assert len(direct_calls) == 2
    assert direct_calls[1] == ("real-openai-key", llm.OPENAI_OFFICIAL_BASE_URL, "gpt-5.1")
    assert meta2["codex_proxy_fallback"] is True
    assert meta2["cost"]["total"] > 0
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "WEBTOON_API_KEY": "real-openai-key",
               "OPENROUTER_API_KEY": "other-provider-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_safety_refusal_does_not_fallback(self):
        script = r'''
import tempfile
from pathlib import Path
from unittest.mock import patch
import imagegen
import llm

class RefusingBackend:
    def supports_temperature(self, model):
        return True
    def complete(self, model, prompt, temperature, limit, images=None):
        raise llm.story.TextRefused("content_policy_violation")

called_direct = []
class DirectOpenAIBackend:
    def __init__(self, **kwargs):
        called_direct.append(kwargs)

with patch.object(llm.story, "OpenAIBackend", DirectOpenAIBackend):
    call = llm.Call("STORY")
    call.backend = RefusingBackend()
    try:
        call("blocked prompt")
        raise AssertionError("expected TextRefused")
    except llm.story.TextRefused:
        pass
assert not called_direct
assert not llm.is_codex_proxy_tripped()

# 이미지 안전 필터 거절도 폴백하지 않아야 한다
img_calls = []
def fake_make_sheet_painter(provider, model, quality, photos=None, *,
                            api_key=None, base_url=None, max_retries=None):
    img_calls.append(api_key)
    def refuse(prompt, kind):
        raise RuntimeError("Error code: 400 - moderation_blocked")
    return refuse, "openai"

with patch.object(imagegen.story, "make_sheet_painter", side_effect=fake_make_sheet_painter):
    with tempfile.TemporaryDirectory() as tmp:
        try:
            imagegen.paint("PAGE_IMAGE", "blocked", Path(tmp) / "p.png", kind=imagegen.PAGE_KIND)
            raise AssertionError("expected moderation_blocked")
        except RuntimeError as exc:
            assert "moderation_blocked" in str(exc)
assert img_calls == [None]
assert not llm.is_codex_proxy_tripped()
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "WEBTOON_API_KEY": "real-openai-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_fallback_can_be_disabled_explicitly(self):
        script = r'''
import llm
assert not llm.can_fallback_to_api_key()
class FailingProxyBackend:
    def supports_temperature(self, model):
        return True
    def complete(self, model, prompt, temperature, limit, images=None):
        raise RuntimeError("502 Bad Gateway")
call = llm.Call("STORY")
call.backend = FailingProxyBackend()
try:
    call("hello")
    raise AssertionError("expected RuntimeError")
except RuntimeError as exc:
    assert "502" in str(exc)
assert not llm.is_codex_proxy_tripped()
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "WEBTOON_CODEX_PROXY_FALLBACK": "false",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "WEBTOON_API_KEY": "real-openai-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_image_fallback_to_api_key_on_proxy_failure(self):
        script = r'''
import tempfile
from pathlib import Path
from unittest.mock import patch

import imagegen
import llm

painter_calls = []
def fake_make_sheet_painter(provider, model, quality, photos=None, *,
                            api_key=None, base_url=None, max_retries=None):
    painter_calls.append((provider, model, api_key, base_url, max_retries))
    if api_key is None:
        def fail_proxy(prompt, kind):
            raise ConnectionError("Connection refused to 127.0.0.1:8317")
        return fail_proxy, "openai"
    def ok_direct(prompt, kind):
        return b"PNGDATA", {"usage_dict": {"input_tokens": 100, "output_tokens": 1000}}
    return ok_direct, "openai"

with patch.object(imagegen.story, "make_sheet_painter", side_effect=fake_make_sheet_painter):
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "page.png"
        res = imagegen.paint("PAGE_IMAGE", "draw a scene", out, kind=imagegen.PAGE_KIND)
        assert out.read_bytes() == b"PNGDATA"
        assert len(painter_calls) == 2
        assert painter_calls[0][2] is None and painter_calls[0][3] is None
        assert painter_calls[1][2] == "real-openai-key" and painter_calls[1][3] == llm.OPENAI_OFFICIAL_BASE_URL
        assert res["codex_proxy_fallback"] is True
        assert res["cost"]["cost_basis"] != "codex_subscription", res["cost"]
        assert res["cost"]["total"] > 0, res["cost"]
        assert llm.is_codex_proxy_tripped()
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "WEBTOON_API_KEY": "real-openai-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_direct_backend_ignores_proxy_openai_base_url_env(self):
        script = r'''
import os
import llm

assert os.environ["OPENAI_BASE_URL"] == "http://127.0.0.1:8317/v1"
backend = llm.make_direct_openai_backend(max_retries=1)
assert str(backend.client.base_url).startswith("https://api.openai.com/v1"), backend.client.base_url
assert backend.client.api_key == "real-openai-key"
llm.mark_codex_proxy_tripped("400 model_not_found")
assert os.environ["OPENAI_API_KEY"] == "real-openai-key"
assert "OPENAI_BASE_URL" not in os.environ
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "proxy-test-key",
               "CODEX_PROXY_BASE_URL": "http://127.0.0.1:8317/v1",
               "WEBTOON_API_KEY": "real-openai-key"}
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_missing_proxy_config_falls_back_when_api_key_exists(self):
        script = r'''
import os
import llm

assert llm.is_codex_proxy_tripped()
assert llm.model_for("STORY", "openai") == "gpt-5.1"
assert os.environ["OPENAI_API_KEY"] == "real-openai-key"
assert "OPENAI_BASE_URL" not in os.environ
'''
        env = {**os.environ, "WEBTOON_CODEX_PROXY": "true",
               "CODEX_PROXY_API_KEY": "",
               "CODEX_PROXY_BASE_URL": "",
               "WEBTOON_API_KEY": "real-openai-key"}
        env.pop("OPENAI_BASE_URL", None)
        result = subprocess.run([sys.executable, "-c", script], cwd=HERE,
                                env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
