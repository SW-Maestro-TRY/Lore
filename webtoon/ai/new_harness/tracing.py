"""모델 호출을 Pydantic Logfire 로 추적한다 (https://pydantic.dev/logfire).

**`LOGFIRE_TOKEN` 이 있을 때만 켠다.** 없거나 logfire 패키지가 없으면 아무것도
안 하고, 하네스는 지금과 똑같이 돈다. 토큰은 저장소 루트 `.env` 에 둔다 —
bootRun 이 그 값을 자바 환경에 넣고, 자바가 이 스크립트를 부를 때 그대로
물려준다.

켜면 보내는 것:
  - OpenAI SDK 호출 (글 · 시트 그림) — 프롬프트와 응답 내용 포함
  - Google GenAI SDK 호출 — 프롬프트와 응답 내용 포함
  - requests 로 직접 부르는 호출 (webtoon-harness 의 그림 제공자) — 주소·상태·시간만

★ 사용자가 적은 캐릭터 설명과 사진이 프롬프트에 들어가므로, 켜면 그 내용이
  Logfire 로 나간다. 서버에서 켤지는 개인정보 처리방침과 함께 정한다(#496).

★ 터미널 출력은 반드시 끈다. 자바가 이 프로세스의 stdout 을 한 줄씩 진행
  상황으로 읽고(HarnessProcess), 캐릭터 만들기는 stdout 의 **마지막 줄**을
  결과 JSON 으로 읽는다(CharacterMaker). 한 줄이라도 섞이면 거기서 깨진다.
"""

from __future__ import annotations

import os
import sys

_started = False


def start() -> bool:
    """켜졌으면 참. 여러 번 불러도 한 번만 켠다."""
    global _started
    if _started:
        return True
    if not os.environ.get("LOGFIRE_TOKEN"):
        return False
    try:
        import logfire
    except ImportError:
        print("  !! LOGFIRE_TOKEN 이 있지만 logfire 패키지가 없어 추적 없이 진행합니다",
              file=sys.stderr)
        return False

    # google-genai 는 이 값이 있어야 프롬프트·응답 내용을 담는다.
    # instrument_google_genai 보다 먼저 정해야 한다.
    os.environ.setdefault("OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "SPAN_ONLY")

    try:
        logfire.configure(
            service_name="webtoon-harness",
            environment=os.environ.get("LORE_ENV") or "local",
            console=False,
            send_to_logfire="if-token-present",
        )
        logfire.instrument_openai()
        logfire.instrument_google_genai()
        logfire.instrument_requests()
    except Exception as e:                                   # noqa: BLE001
        # 추적이 안 된다고 생성까지 멈추지 않는다.
        print(f"  !! Logfire 를 켜지 못해 추적 없이 진행합니다: {e}", file=sys.stderr)
        return False
    _started = True
    return True


def run_span(script: str, argv: list[str]):
    """스크립트 한 번 실행을 묶는 span. 켜져 있지 않으면 아무것도 안 하는 것을 준다.

    한 번 실행 안의 모델 호출들이 이 아래에 모여서, Logfire 화면에서 run 하나를
    한 덩어리로 볼 수 있다. run_id 와 단계는 인자에서 읽어 속성으로 단다.
    """
    import contextlib
    if not _started:
        return contextlib.nullcontext()
    import logfire
    run_id = ""
    if "--run-id" in argv:
        i = argv.index("--run-id")
        run_id = argv[i + 1] if i + 1 < len(argv) else ""
    stage = " ".join(a for a in argv if a.startswith("--") and a != "--run-id") or "(기본)"
    return logfire.span("{script} {stage}", script=script, stage=stage, run_id=run_id)
