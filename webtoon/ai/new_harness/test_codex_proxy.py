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
assert llm.provider_for("SCENE") == "openai"
assert llm.provider_for("PAGE_REVIEW") == "openai"
assert llm.provider_for("PAGE_IMAGE") == "openai"
assert llm.model_for("WORLD_MAKE", "openai") == "gpt-6-sol"
assert llm.model_for("PAGE_IMAGE", "openai") == "gpt-image-2.5-flare"
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


if __name__ == "__main__":
    unittest.main()
