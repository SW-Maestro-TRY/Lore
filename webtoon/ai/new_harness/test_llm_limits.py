"""장면 응답이 끊기면 상한을 올려 한 번 다시 부르고, 두 호출 값을 합친다 · OpenRouter 요청 본문(#832)."""
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import llm      # noqa: E402
import story    # noqa: E402


class FakeBackend:
    def __init__(self, stops):
        self.stops, self.limits = list(stops), []

    def supports_temperature(self, model):
        return False

    def complete(self, model, prompt, temperature, max_tokens, images=None):
        self.limits.append(max_tokens)
        return "{}", {"input": 10, "output": 5, "cache_read": 4}, self.stops.pop(0)


def call_with(stage, backend):
    c = llm.Call.__new__(llm.Call)
    c.stage, c.provider, c.model, c.backend, c.temp_ok = stage, "openai", "gpt-5.1", backend, False
    return c


class Limits(unittest.TestCase):
    def test_scene_gets_bigger_limit_and_retries_once(self):
        b = FakeBackend(["max_tokens", "stop"])
        _, meta = call_with("SCENE", b)("p")
        self.assertEqual(b.limits, [32000, 64000])
        self.assertEqual(meta["usage"]["input"], 20)
        self.assertEqual(meta["usage"]["cache_read"], 8)
        self.assertEqual(meta["stop"], "stop")

    def test_other_stage_keeps_default(self):
        b = FakeBackend(["stop"])
        call_with("STORY", b)("p")
        self.assertEqual(b.limits, [llm.DEFAULT_MAX_TOKENS])

    def test_no_retry_past_cap(self):
        b = FakeBackend(["max_tokens"])
        call_with("SCENE", b)("p", max_tokens=llm.RETRY_MAX_TOKENS)
        self.assertEqual(len(b.limits), 1)


class OpenRouterBody(unittest.TestCase):
    def test_pins_openai_and_session(self):
        os.environ["NH_SESSION_ID"] = "run-1"
        try:
            body = story.OpenRouterBackend.extra_body(None)
        finally:
            del os.environ["NH_SESSION_ID"]
        self.assertEqual(body["session_id"], "run-1")
        self.assertEqual(body["provider"], {"only": ["openai"]})

    def test_other_company_model_is_not_pinned_to_openai(self):
        """google/… 모델에 provider.only=openai 를 걸면 OpenRouter 가 404 를 낸다(2026-10-27 장면 단계)."""
        body = story.OpenRouterBackend.extra_body(None, "google/gemini-3.8-flash")
        self.assertNotIn("provider", body)
        self.assertIn("provider", story.OpenRouterBackend.extra_body(None, "openai/gpt-5.1"))

    def test_openai_direct_sends_nothing_extra(self):
        self.assertIsNone(story.OpenAIBackend.extra_body(None))


if __name__ == "__main__":
    unittest.main()
