"""장기 연재 시험 — 제품의 「다음 화」 흐름을 사람 대신 완결까지 반복한다(글만).

새 하네스가 아니다. 서버(SeriesService · SeriesDigestService · JobRunner)가 회차마다 부르는 것을 같은
순서 · 같은 모양으로 부른다.

  0. 원작 글 → 설정집 뽑기(lore_extract.extract, 「글에서 설정집 만들기」와 같은 함수)
  매 화 직전  회차 정하기 — 원작의 책갈피 뒤를 읽고 이번 화에 옮길 구간과 「이번 화 내용」을 정한다.
     몇 화가 될지는 미리 정하지 않는다. 원작 끝에 닿으면 완결.
  1화  own.run_own(sheet=False) → own.own_scenes        (= run.py --own, --own-scenes. 시트 그림만 뺌)
  N화 끝  run.py --series-digest                        (= 다음 화 버튼을 누르면 서버가 부르는 회차 정리)
  N+1화  run.py --character --continuation --own → --own-scenes  (= 서버가 다음 화를 만들 때)

사람이 하던 일 두 가지만 이 파일이 대신한다.
  - 「이번 화 내용」 적기 → 회차 정하기(plan_step). 끝을 원작 문장 인용으로 받고 코드가 원문에서 찾아 확인한다
  - 정리 결과 확인 → 그대로 다 받는다(사람이 틀린 줄을 빼는 단계를 건너뛴다. 그래서 틀린 기억이 그대로
    쌓이는지가 보인다)

그리고 시험용 검사 하나를 붙인다 — 매 화 본문을 **앞 화 본문 전부**와 대조해 설정이 틀어졌는지 본다
(continuity). 생성은 설정집만 보고, 검사는 원문 전부를 본다. 「심각」이 나오면 멈춘다.

  python3 series_longrun.py --source samples/X.txt --hero 이름 --genre 장르 --out DIR
  python3 series_longrun.py --out DIR --resume          # 멈춘 자리부터

모델은 구독 경로(WEBTOON_CODEX_PROXY)만 쓴다. 키는 ~/.cli-proxy-api/openai-env.sh 에서 읽는다.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

STEP_PROMPT = """너는 원작을 웹툰 연재로 옮기는 각색 작가다. 아래 [원작 — 아직 옮기지 않은 부분]의 맨 앞부터, 이번 {n}화에 옮길 만큼을 정한다.

- 이번 화는 웹툰 한 화 분량(장면 4~6개)이다. 사건 하나가 시작되고, 그 화 안에서 상황이 한 번 바뀌는 데까지를 담는다.
- 분량: 원작은 전체 {total}자이고 아직 {left}자가 남았다. 한 화에 보통 원작 {lo}~{hi}자를 담는다. 사건이 몰린 대목은 이보다 짧게, 사건 없이 대화 · 일상이 이어지는 대목은 이보다 길게 잡는다. 웹툰 한 화가 원작의 대사 몇 줄만 옮기는 일이 없게 한다.
- 끊는 자리: 이번 화는 다음 화가 궁금해지는 자리에서 끝낸다 — 사건이 막 터진 순간, 결정적인 대사·행동 바로 뒤, 새 인물이나 위협이 나타난 순간. 대화나 행동이 이어지는 도중, 또는 사건이 다 정리되고 숨을 돌리는 대목 한가운데에서 끊지 않는다. 분량 범위 안에 그런 자리가 없으면 범위를 조금 넘더라도 그 자리까지 담는다. end_quote 는 그 자리의 문장이다.
- 원작의 순서를 지킨다. 원작의 중요한 사건을 건너뛰지 않는다. 사건 없이 대화만 길게 이어지는 대목은 한 화에 묶어도 된다.
- 원작에 같은 장면의 다른 버전이 섞여 있으면 하나만 따른다.
- [지금까지의 연재]는 이미 웹툰으로 나온 것이다. 연재가 원작과 조금 달라졌으면 연재 쪽에서 자연스럽게 이어지게 정한다.
- request: 이번 화에 일어날 일 2~3문장. 이번 화를 쓰는 작가에게 주는 요청이다. 앞 화 내용이나 인물 설정은 적지 않는다(설정집과 진행 상태가 따로 넘어간다).
- end_quote: 이번 화가 원작의 어디까지인지. [원작] 안의 한 문장을 글자 그대로 20~60자 옮긴다. 그 문장 바로 다음부터가 다음 화다.
- last: 이번 화로 원작의 끝까지 다 옮기면 true.

출력은 JSON 하나만: {{"request":"...","end_quote":"...","last":false}}

[지금까지의 연재 — 진행 상태]
{state}

[직전 화 본문]
{prev}

[원작 — 아직 옮기지 않은 부분 · 전체 {total}자 중 {start}자부터{tail}]
{window}
"""

FREE_PROMPT = """너는 웹툰 연재 작가다. 원작은 이미 다 옮겼다. 지금부터는 원작 없이, 지금까지의 연재가 세운 세계 · 인물 · 관계를
이어서 다음 화를 정한다. 이번이 {n}화이고, 원작 뒤로 이어 쓰는 화는 {total}화 중 {k}번째다.

- 이번 화는 웹툰 한 화 분량(장면 4~6개)이다. 사건 하나가 시작되고, 그 화 안에서 상황이 한 번 바뀐다.
- 새 사건은 [진행 상태]에 있는 것에서 나온다 — 미해결로 남은 일, 인물이 원하는 것과 그것을 막는 것, 이미 생긴
  갈등 · 약속 · 비밀. 지금까지 없던 세계 규칙 · 능력 · 세력을 갑자기 들이지 않는다.
- 인물은 설정집 · 진행 상태의 성격 · 말투 · 관계대로 움직인다. 관계는 한 화에 한 걸음씩만 바뀐다.
- 남은 화 수를 보고 흐름을 잡는다. 앞쪽 화는 갈등을 키우고, 마지막 두세 화는 큰 미해결을 하나씩 매듭짓는다.
  {last_note}
- 끊는 자리: 다음 화가 궁금해지는 자리에서 끝낸다.
{content_note}
- request: 이번 화에 일어날 일 2~3문장. 이번 화를 쓰는 작가에게 주는 요청이다. 앞 화 내용이나 인물 설정은 적지 않는다.

출력은 JSON 하나만: {{"request":"..."}}

[지금까지의 연재 — 진행 상태]
{state}

[직전 화 본문]
{prev}
"""

CHECK_PROMPT = """너는 연재 웹툰의 설정 감수자다. [이전 회차 원문]은 이미 연재된 사실이다. [이번 화]가 그 사실과
어긋나는지 본다. 이번 화는 작가가 설정집 · 진행 상태 · 직전 화만 보고 썼다.

찾을 것 — 이전 회차에 이미 확정된 사실이 이번 화에서 설명 없이 바뀐 것.
- 인물: 정체 · 능력 · 성격 · 말투 · 외형 · 생사 · 부상 · 소지품
- 관계: 이미 만난 사이를 처음 만난 것처럼 씀, 쌓인 신뢰 · 갈등이 이유 없이 초기화
- 사건: 이미 일어난 일의 경위 · 장소 · 순서 · 참여자가 다르게 회상됨, 끝난 일을 다시 시작함
- 지식: 인물이 알 수 없는 것을 앎, 이미 알게 된 것을 모름
- 시공간: 위치 · 이동 · 시간 흐름이 앞뒤가 안 맞음
- 세계 규칙: 이미 정해진 규칙을 이유 없이 어김

찾지 않을 것 — 새 사실 추가, 이야기 속에서 설명된 변화, 인물의 거짓말이 장면에서 거짓말로 드러나는 것,
문장 품질 · 재미.
다음 화로 넘기는 수수께끼 — 이번 화에서 처음 드러난 변화가 의문 자체로 그려지면(다른 인물이 놀라거나 이상하게
여기거나, 그 순간에서 화가 끝남) 모순이 아니라 다음 화에서 풀릴 미해결이다. 이런 것은 minor 로만 적고 what 앞에
「미해결:」을 붙인다. 이전 회차의 사실을 아무도 알아채지 못한 채 덮어쓰면 모순이다.

severity
- critical: 독자가 바로 알아챌 큰 모순(죽은 인물이 설명 없이 나옴, 핵심 관계 · 정체가 뒤집힘, 큰 사건의 경위가 바뀜)
- major: 눈에 띄는 모순(소지품 · 부상 · 위치 · 아는 정보가 틀림)
- minor: 사소한 어긋남

반드시 이전 회차의 근거 문장과 이번 화의 문장을 그대로 인용한다. 근거를 인용할 수 없으면 적지 않는다.
출력은 JSON 하나만: {"issues":[{"severity":"critical|major|minor","what":"무엇이 어긋났나",
"before":"이전 회차 인용 (몇 화)","now":"이번 화 인용"}]}

"""


READ_PROMPT = """너는 이 연재 웹툰을 1화부터 따라 읽는 독자다. 원작은 모른다. [지금까지]는 앞 화들이고, 이제 [이번 화]를 읽는다.
이번 화를 읽다가 막히는 곳을 찾는다.

찾을 것
- 지금 어디인지 · 언제인지 · 누가 함께 있는지 알 수 없는 곳
- 인물이 왜 그렇게 하는지(이유 · 목적)를 알 수 없는 행동, 이유 없이 정해진 이동 · 결정
- 처음 나온 인물 · 물건 · 장소가 누구 · 무엇인지 알 수 없는 곳
- 앞 문장과 이어지지 않고 건너뛴 곳

찾지 않을 것 — 다음 화로 넘기려고 일부러 남긴 의문, 문장 취향 · 재미, 앞 화에서 이미 나온 인물 · 장소를 이번 화에서 다시 소개하지 않은 것(매 화 인물 소개부터 하면 안 된다).

severity
- major: 이번 화의 중심 사건이나 이동 · 결정의 이유를 독자가 알 수 없다
- minor: 잠깐 헷갈리지만 곧 따라잡을 수 있다

반드시 이번 화의 문장을 그대로 인용한다. 인용할 수 없으면 적지 않는다.
출력은 JSON 하나만: {"issues":[{"severity":"major|minor","what":"읽기: 무엇을 알 수 없나","now":"이번 화 인용"}]}

"""


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def read(p: Path):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def put(p: Path, obj) -> None:
    Path(p).parent.mkdir(parents=True, exist_ok=True)
    Path(p).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def api_env() -> dict:
    """LONGRUN_API=1 — 구독 한도가 찼을 때 OpenAI API 키(LONGRUN_ENV_FILE — 기본 저장소 루트 .env — 의 LONGRUN_API_KEY_VAR, 기본 OPENAI_KEY)로.
    모델은 구독 경로와 같게 맞춘다(글 gpt-6-sol). 장면 단계도 OpenAI 로(제품 기본은 OpenRouter). 키는 어디에도 쓰지 않는다."""
    var = os.environ.get("LONGRUN_API_KEY_VAR", "OPENAI_KEY")
    root = Path(os.environ.get("LONGRUN_ENV_FILE") or HERE.parents[2] / ".env")
    got = {}
    for line in root.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            got[k.strip()] = v.strip().strip('"').strip("'")
    key = got.get(var) or ""
    if not key:
        raise SystemExit(f"{root} 에 {var} 가 없습니다")
    env = {k: v for k, v in os.environ.items() if k not in ("OPENROUTER_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY",
                                                           "WEBTOON_CODEX_PROXY", "WEBTOON_API_KEY")}
    env.update({"OPENAI_API_KEY": key, "CODEX_PROXY_API_KEY": key, "CODEX_PROXY_BASE_URL": "https://api.openai.com/v1",
                "NH_PROVIDER": "openai", "NH_MODEL": "gpt-6-sol", "SCENE_PROVIDER": "openai", "SCENE_MODEL": "gpt-6-sol",
                "PYTHONDONTWRITEBYTECODE": "1", "LOGFIRE_TOKEN": "", "NH_STORY_REVIEW": "0"})
    return env


def proxy_env() -> dict:
    """~/.cli-proxy-api/openai-env.sh 를 읽은 셸의 OPENAI_* 를 구독 경로 변수로. 키는 어디에도 쓰지 않는다."""
    if os.environ.get("LONGRUN_API") == "1":
        return api_env()
    out = subprocess.run(["bash", "-c", "source ~/.cli-proxy-api/openai-env.sh && env -0"],
                         capture_output=True, check=True).stdout.decode()
    got = dict(x.split("=", 1) for x in out.split("\0") if "=" in x)
    env = {k: v for k, v in os.environ.items() if k not in ("OPENROUTER_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY")}
    env.update({"WEBTOON_CODEX_PROXY": "1", "CODEX_PROXY_API_KEY": got["OPENAI_API_KEY"],
                "CODEX_PROXY_BASE_URL": got["OPENAI_BASE_URL"], "PYTHONDONTWRITEBYTECODE": "1",
                "LOGFIRE_TOKEN": "", "NH_STORY_REVIEW": "0"})
    return env


def ask(env: dict, prompt: str, model: str, out: Path) -> dict:
    """검사 · 계획 전용 호출(제품 단계가 아니라서 llm.Call 을 안 거친다). 보낸 것 · 받은 것을 그대로 남긴다."""
    out.parent.mkdir(parents=True, exist_ok=True)
    out.with_suffix(".prompt.txt").write_text(prompt, encoding="utf-8")
    body = json.dumps({"model": model, "input": prompt}).encode()
    req = urllib.request.Request(env["CODEX_PROXY_BASE_URL"].rstrip("/") + "/responses", data=body,
                                 headers={"Authorization": "Bearer " + env["CODEX_PROXY_API_KEY"],
                                          "Content-Type": "application/json"})
    for attempt in range(8):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.loads(r.read())
            break
        except Exception as exc:                                       # noqa: BLE001
            # 구독 경로는 서버가 붐비면 503 + Retry-After 를 준다(2026-11-19 00:52, server_is_overloaded).
            # 알려 준 만큼 기다리고, 간격을 늘려 가며 다시 한다. 인증 · 요청 오류(4xx)는 기다려도 안 되니 바로 멈춘다.
            code = getattr(exc, "code", None)
            if attempt == 7 or (isinstance(code, int) and 400 <= code < 500 and code != 429):
                raise
            hint = getattr(exc, "headers", None)
            after = int(hint.get("Retry-After", 0) or 0) if hint else 0
            wait = min(300, max(after, 20 * (attempt + 1)))
            log(f"  재시도 {attempt + 1}: {exc} — {wait}초 기다림")
            time.sleep(wait)
    text = "".join(c.get("text", "") for o in data.get("output", []) if o.get("type") == "message"
                   for c in o.get("content", []))
    out.with_suffix(".txt").write_text(text, encoding="utf-8")
    return json.loads(text[text.find("{"):text.rfind("}") + 1])


def harness(env: dict, out: Path, *args: str, code: str | None = None) -> None:
    """run.py 를 서버가 부르는 그대로 자식 프로세스로. 실패하면 멈춘다."""
    cmd = [sys.executable, "-c", code, *args] if code else [sys.executable, "run.py", *args]
    # 하네스 안의 호출은 SDK 가 세 번 다시 한다. 구독 경로가 오래 붐비면 그래도 실패해서, 단계 전체를 몇 분 뒤 다시 부른다.
    for attempt in range(4):
        with (out / "harness.log").open("a", encoding="utf-8") as f:
            f.write(f"\n$ {' '.join(args) or 'own.run_own(sheet=False)'}\n")
            f.flush()
            r = subprocess.run(cmd, cwd=HERE, env={**env, "NH_RUNS_DIR": str(out / "runs")}, stdout=f, stderr=f)
        if not r.returncode:
            return
        if attempt < 3:
            log(f"  하네스 단계 실패 — {60 * (attempt + 1)}초 뒤 다시 ({' '.join(args)[:60]})")
            time.sleep(60 * (attempt + 1))
    raise SystemExit(f"하네스가 실패했습니다 ({' '.join(args)}). {out / 'harness.log'} 를 보세요.")


EP1 = """
import sys, json
from pathlib import Path
import run as R, own
run_dir = Path(sys.argv[1]); char = R.normalize(json.loads(Path(sys.argv[2]).read_text()))
run_dir.mkdir(parents=True, exist_ok=True); R.write_json(run_dir / 'input.json', char)
own.run_own(run_dir, char, False, sheet=False)
own.own_scenes(run_dir, char, False)
"""


WINDOW = 30000


def find_quote(window: str, quote: str) -> int:
    """인용문이 끝나는 자리. 못 찾으면 -1. 공백 · 줄바꿈 차이만 너그럽게 본다(글자는 그대로여야 한다)."""
    q = str(quote or "").strip()
    if len(q) < 8:
        return -1
    i = window.find(q)
    if i >= 0:
        return i + len(q)
    import re
    pat = r"\s+".join(re.escape(w) for w in q.split())
    m = re.search(pat, window)
    return m.end() if m else -1


def plan_step(env: dict, out: Path, n: int, state: dict, source: str, model: str) -> dict:
    """이번 화에 옮길 원작 구간과 요청을 정한다. 인용문을 원문에서 못 찾으면 이유를 붙여 두 번 더 시킨다."""
    start = state["cursor"]
    window = source[start:start + WINDOW]
    tail = " · 원작의 끝까지 들어 있다" if start + WINDOW >= len(source) else ""
    prev = body_of(out / "runs" / f"ep{n - 1:02d}")["body"] if n > 1 else "(아직 없음 — 이번이 1화)"
    sys.path.insert(0, str(HERE))
    import userbook
    st = userbook.progress_text([p for p in state["book"]["progress"] if p.get("kind") != "사건"]) or "(아직 없음)"
    lo, hi = state.get("pace", [7000, 10000])
    prompt = STEP_PROMPT.format(n=n, state=st, prev=prev, total=len(source), start=start, tail=tail, window=window,
                                left=len(source) - start, lo=lo, hi=hi)
    note = ""
    for attempt in range(3):
        got = ask(env, prompt + note, model, out / "steps" / f"ep{n:02d}-{attempt}")
        end = find_quote(window, got.get("end_quote"))
        if end > 0 and str(got.get("request") or "").strip():
            break
        note = (f"\n[다시] 앞의 답에서 end_quote 「{got.get('end_quote')}」를 [원작]에서 찾지 못했다. "
                "[원작]에 실제로 있는 문장을 글자 그대로 옮긴다.\n")
    else:
        raise SystemExit(f"{n}화 회차 정하기가 세 번 모두 원작 인용에 실패했습니다 ({out / 'steps'})")
    last = bool(got.get("last")) and bool(tail) and start + end >= len(source) - 2000
    return {"n": n, "request": str(got["request"]).strip(), "start": start, "end": start + end,
            "end_quote": got["end_quote"], "last": last or start + end >= len(source) - 200}


def free_step(env: dict, out: Path, n: int, state: dict, source: str, model: str) -> dict:
    """원작을 다 옮긴 뒤(--free-episodes) 다음 화를 원작 없이 정한다. 구간은 원작 끝에 머문다(start = end = 원작 길이)."""
    total, first = state["free_episodes"], state["source_done_at"] + 1
    k = n - first + 1
    prev = body_of(out / "runs" / f"ep{n - 1:02d}")["body"]
    sys.path.insert(0, str(HERE))
    import userbook
    st = userbook.progress_text([p for p in state["book"]["progress"] if p.get("kind") != "사건"]) or "(아직 없음)"
    last = k >= total
    note = ("이번 화가 이 연재의 마지막 화다. 남은 큰 미해결을 매듭짓고, 인물들이 어디에 이르렀는지 보여 주며 끝낸다."
            if last else "")
    cn = f"- {state['content_note']}" if state.get("content_note") else ""
    prompt = FREE_PROMPT.format(n=n, total=total, k=k, last_note=note, content_note=cn, state=st, prev=prev)
    got = {}
    for attempt in range(3):
        got = ask(env, prompt, model, out / "steps" / f"ep{n:02d}-free{attempt}")
        if str(got.get("request") or "").strip():
            break
    else:
        raise SystemExit(f"{n}화 이어 쓰기 회차 정하기가 세 번 모두 빈 요청이었습니다 ({out / 'steps'})")
    return {"n": n, "request": str(got["request"]).strip(), "start": len(source), "end": len(source),
            "end_quote": "", "last": last, "free": True}


def free_after(state: dict, item: dict) -> bool:
    """이 화가 원작의 마지막 화이고, 그 뒤로 원작 없이 이어 쓸 화가 남았나(--free-episodes K > 0)."""
    return bool(item.get("last")) and not item.get("free") and int(state.get("free_episodes") or 0) > 0


def body_of(run_dir: Path) -> dict:
    d = read(run_dir / "directions.json")[0]
    scenes = read(run_dir / "scenes.json").get("scenes", []) if (run_dir / "scenes.json").is_file() else []
    return {"title": d.get("title", ""), "body": d.get("body") or d.get("raw", ""), "scenes": scenes}


def scene_lines(run_dir: Path) -> list[str]:
    sys.path.insert(0, str(HERE))
    import own
    return [own.scene_text(s) for s in body_of(run_dir)["scenes"]]


def check(env: dict, out: Path, n: int, model: str, reader: bool = False) -> dict:
    history = []
    for i in range(1, n):
        b = body_of(out / "runs" / f"ep{i:02d}")
        history.append(f"[{i}화 — {b['title']}]\n{b['body']}")
    now = body_of(out / "runs" / f"ep{n:02d}")
    prompt = CHECK_PROMPT + "[이전 회차 원문]\n" + "\n\n".join(history) + f"\n\n[이번 화 — {n}화 {now['title']}]\n" + now["body"]
    got = ask(env, prompt, model, out / "checks" / f"ep{n:02d}") if n > 1 else {"issues": []}
    if reader:
        # 설정 검사는 「앞 화와 어긋났나」만 본다. 「처음 읽는 독자가 따라올 수 있나」는 따로 본다(2026-11-19 — 설정 오류가
        # 적은 42화가 읽어 보니 무슨 상황인지 모르는 줄거리 요약이었다).
        rp = READ_PROMPT + "[지금까지]\n" + ("\n\n".join(history) or "(없음 — 이번이 1화)") + f"\n\n[이번 화 — {n}화 {now['title']}]\n" + now["body"]
        seen = ask(env, rp, model, out / "checks" / f"ep{n:02d}.read")
        got["issues"] = list(got.get("issues") or []) + [{**i, "what": "읽기: " + str(i.get("what", "")).removeprefix("읽기:").strip()}
                                                         for i in seen.get("issues") or [] if isinstance(i, dict)]
    return got


def digest(env: dict, out: Path, n: int, book: dict) -> dict:
    run_dir = out / "runs" / f"ep{n:02d}"
    packet = {"source_dir": str(run_dir), "source_run": run_dir.name, "episode_number": n,
              "source_scenes": scene_lines(run_dir),
              "previous": {"entries": book["entries"], "progress": book["progress"]}}
    put(out / "packets" / f"digest{n:02d}.json", packet)
    target = out / "lore" / f"after{n:02d}.json"
    harness(env, out, "--series-digest", str(out / "packets" / f"digest{n:02d}.json"), "--out", str(target))
    got = read(target)
    # new · updated 는 이번 정리에서 붙은 표시다. 다음 화로 넘길 때는 뗀다(화면도 다음 화에 그대로 넘기지 않는다).
    return {"entries": [{k: v for k, v in e.items() if k not in ("new", "updated")} for e in got["entries"]],
            "progress": got["progress"]}


def writer_request(state: dict, source: str, item: dict) -> str:
    """이번 화 작가에게 넘기는 「이번 화 내용」. 기본은 회차 정하기의 요청 2~3문장(사람이 다음 화 내용을 짧게 적는 것).
    --source-to-writer 면 이번 화로 옮길 원작 구간을 통째로 같이 준다 — 소설을 웹툰으로 옮기는 사람은 원작 장을 보고
    쓴다. 2~3문장만 주면 원작의 상황 · 이유 · 인물의 상태가 빠진 줄거리 요약이 된다(2026-11-19, 원작 7천 자 → 본문 770자)."""
    note = f"\n\n{state['content_note']}" if state.get("content_note") else ""
    if not state.get("source_to_writer") or item.get("free"):
        return item["request"] + note
    lo, hi = state.get("body_sentences", [20, 30])
    passage = source[item["start"]:item["end"]].strip()
    return (f"[이번 화로 옮길 원작 — 이 범위를 이번 화로 옮긴다]\n{passage}\n\n[각색 메모]\n{item['request']}\n\n"
            f"본문은 {lo}~{hi}문장으로 쓴다. 원작의 장소 · 시간 · 상황, 인물이 그렇게 하는 이유, 인물의 몸 · 마음 상태가 "
            "독자에게 전해지게 쓴다. 원작에 없는 사건을 더하지 않고, 원작의 대사는 살려 쓴다. 원작의 나레이션과 인물의 "
            "속마음 가운데 상황 · 이유 · 감정을 전하는 문장은 살려 쓴다(속마음은 그 인물의 생각으로). 인물이 자리를 옮기면 "
            "어디로 · 왜 가는지 쓴다. 원작에 없는 대사를 더하더라도 인물이 그렇게 하는 이유를 바꾸지 않는다"
            "(예: 원작에서 이름을 듣고 멈추는 인물을, 지어낸 명령을 듣고 멈추게 하지 않는다).") + note


def episode(env: dict, out: Path, n: int, state: dict, request: str) -> None:
    run_dir = out / "runs" / f"ep{n:02d}"
    book = {"name": state["title"] + " 설정집", "source": "series" if n > 1 else "extracted",
            "fixed_settings": "", "progress_state": "", "entries": state["book"]["entries"],
            "progress": state["book"]["progress"], "confirmed": True}
    char = {"name": state["hero"], "character": state["hero_desc"], "description": state["hero_desc"],
            "genre": state["genre"], "story": request, "settings": "", "lorebook": book}
    put(out / "packets" / f"character{n:02d}.json", char)
    if n == 1:
        harness(env, out, str(run_dir), str(out / "packets" / "character01.json"), code=EP1)
    else:
        prev = out / "runs" / f"ep{n - 1:02d}"
        packet = {"source_run": prev.name, "source_dir": str(prev), "episode_number": n,
                  "fixed_settings": "", "progress_state": "", "source_scenes": scene_lines(prev),
                  "request": request, "revision": n}
        put(out / "packets" / f"continue{n:02d}.json", packet)
        harness(env, out, "--run-id", run_dir.name, "--character", str(out / "packets" / f"character{n:02d}.json"),
                "--continuation", str(out / "packets" / f"continue{n:02d}.json"), "--own")
        harness(env, out, "--run-id", run_dir.name, "--own-scenes")
    # 글만 돌리는 시험이라 시트 그림을 안 그린다. 다음 화가 「앞 화 시트」를 요구하므로 자리만 채운다.
    (run_dir / "sheet.png").write_bytes(b"text-only longrun placeholder")


def setup(env: dict, args, out: Path) -> dict:
    sys.path.insert(0, str(HERE))
    source = Path(args.source).read_text(encoding="utf-8")
    log("[준비] 원작 글에서 설정집을 뽑습니다 (lore_extract.extract)")
    code = """
import sys, json
from pathlib import Path
import lore_extract as X
src = Path(sys.argv[1]).read_text(); out = Path(sys.argv[2])
if len(sys.argv) > 4 and int(sys.argv[4]) > 0:
    src = src[:int(sys.argv[4])]
entries = []
for i, part in enumerate([src[j:j+30000] for j in range(0, len(src), 30000)]):
    rows, more, renamed = X.extract(part, out.parent, 'ko', entries)
    entries = X.apply_book(entries, rows, more, renamed)
if len(sys.argv) > 3 and sys.argv[3] == "looks":
    entries = X.extract_looks(src, entries, out.parent, 'ko')
out.write_text(json.dumps(entries, ensure_ascii=False, indent=2))
"""
    (out / "lore").mkdir(parents=True, exist_ok=True)
    # --setup-upto: 원작 앞부분만으로 설정집을 뽑는다. 원작 전체로 뽑으면 뒤에서야 생기는 사실(예: 나중에 바뀌는 지위 ·
    # 혼인)이 1화부터 「지금 사실」로 들어가 앞 화들과 어긋난다(2026-11-21 피아디 12화 — 원작 후반의 「1황비」가 11화
    # 진행 상태에 미리 들어감). 나머지는 화마다 정리(digest)가 원작을 따라 덧붙인다. 0 이면 예전처럼 전체.
    upto = getattr(args, "setup_upto", 0) or 0
    extra = ["looks" if args.looks else "-", str(upto)] if upto else (["looks"] if args.looks else [])
    harness(env, out, str(Path(args.source).resolve()), str(out / "lore" / "book0.json"), *extra, code=code)
    entries = [{k: v for k, v in e.items() if k not in ("new", "updated")} for e in read(out / "lore" / "book0.json")]
    hero = next((e for e in entries if e.get("title") == args.hero), None)
    # 회차 계획을 미리 세우지 않는다. 매 화 직전에 원작의 책갈피(cursor) 뒤를 읽고 이번 화를 정한다(plan_step).
    state = {"title": args.title or Path(args.source).stem, "hero": args.hero, "genre": args.genre,
             "hero_desc": (hero or {}).get("content", "")[:1500], "plan": [], "cursor": 0,
             "source": str(Path(args.source).resolve()), "book": {"entries": entries, "progress": []},
             "done": 0, "status": "running", "issues": {}}
    put(out / "state.json", state)
    return state


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--source")
    p.add_argument("--hero")
    p.add_argument("--genre", default="")
    p.add_argument("--title", default="")
    p.add_argument("--max-episodes", type=int, default=60)
    p.add_argument("--pace", default="7000-10000", help="한 화에 담을 원작 글자 수의 보통 범위 — 회차 정하기에 알려 준다")
    p.add_argument("--out", required=True)
    p.add_argument("--resume", action="store_true")
    p.add_argument("--reuse-setup", help="다른 실행 폴더의 설정집(book0)을 그대로 쓴다 — 전/후 비교용")
    p.add_argument("--plan-model", default="gpt-6.2-astra")
    p.add_argument("--check-model", default="gpt-6.2-astra")
    p.add_argument("--stop-on", default="critical", help="이 등급이 나오면 멈춘다 (critical · major · none)")
    p.add_argument("--only", type=int, default=0, help="이번 실행에서 최대 몇 화까지 (0 = 끝까지)")
    p.add_argument("--source-to-writer", action="store_true", help="이번 화 원작 구간을 작가에게 통째로 준다(writer_request)")
    p.add_argument("--body-sentences", default="20-30", help="--source-to-writer 일 때 본문 문장 수")
    p.add_argument("--looks", action="store_true", help="설정집을 뽑을 때 인물마다 원작 생김새 인용 · 말하는 방식 · 비중을 붙인다")
    p.add_argument("--reader-check", action="store_true", help="처음 읽는 독자 검사를 더한다(기록만)")
    p.add_argument("--reader-gate", action="store_true", help="독자 검사 major 도 그 화를 다시 만드는 조건으로 친다")
    p.add_argument("--free-episodes", type=int, default=0,
                   help="원작을 다 옮긴 뒤 원작 없이 설정을 이어 몇 화를 더 쓴다(0 = 원작 끝에서 완결)")
    p.add_argument("--setup-upto", type=int, default=0,
                   help="설정집(book0)을 원작 앞 몇 자로만 뽑는다(0 = 전체). 뒤에서 바뀌는 사실이 1화부터 들어가지 않게")
    p.add_argument("--content-note", default="",
                   help="모든 화의 작가 요청 · 이어 쓰기 회차 정하기에 덧붙이는 수위 규칙(예: 성적인 장면은 암전)")
    p.add_argument("--accept", type=int, default=0,
                   help="멈춘 화의 K번째 실패본을 사람이 보고 통과시킨다(--resume 과 함께) — 그 화로 받고 정리까지 한다")
    args = p.parse_args()
    out = Path(args.out).resolve()
    env = proxy_env()
    if args.resume:
        state = read(out / "state.json")
    elif args.reuse_setup:
        base = Path(args.reuse_setup).resolve()
        got = read(base / "state.json")
        (out / "lore").mkdir(parents=True, exist_ok=True)
        (out / "lore" / "book0.json").write_text((base / "lore" / "book0.json").read_text(encoding="utf-8"), encoding="utf-8")
        entries = [{k: v for k, v in e.items() if k not in ("new", "updated")} for e in read(out / "lore" / "book0.json")]
        state = {k: got[k] for k in ("title", "hero", "genre", "hero_desc", "source")}
        state.update({"plan": [], "cursor": 0, "book": {"entries": entries, "progress": []}, "done": 0,
                      "status": "running", "issues": {}, "reused_from": str(base)})
        put(out / "state.json", state)
    else:
        state = setup(env, args, out)
    # 옵션은 사람 통과(--accept) 판정보다 먼저 채운다 — 통과 판정이 free_episodes 를 본다.
    if not args.resume or "pace" not in state:
        state["pace"] = [int(x) for x in args.pace.split("-")]
    # 이어 돌릴 때는 처음 정한 값을 그대로 쓴다. 이 값이 없던 옛 실행을 이어 돌리면 이번에 준 값을 쓴다.
    for key, val in (("source_to_writer", args.source_to_writer), ("reader_check", args.reader_check),
                     ("reader_gate", args.reader_gate), ("free_episodes", args.free_episodes),
                     ("content_note", args.content_note),
                     ("body_sentences", [int(x) for x in args.body_sentences.split("-")])):
        if not args.resume or key not in state:
            state[key] = val
    if args.resume and str(state.get("status", "")).startswith("stopped"):
        # 세 번 실패로 멈춘 것을 사람이 보고 다시 돌린 것 — 그 화의 재시도 횟수를 새로 센다.
        for x in state.get("incidents") or []:
            if x["n"] == state["done"] + 1:
                x["before_resume"] = True
    if args.accept:
        # 강의 p.85 「최대 3회 … 사람에게」의 사람 쪽 — 검사가 세 번 다 걸었지만 사람이 읽어 보고 받아들인 실패본을
        # 그 화로 받는다. 그 화의 검사 결과는 그대로 남기고 「사람이 통과」 표시를 붙인다.
        n = state["done"] + 1
        inc = next((x for x in state.get("incidents") or [] if x["n"] == n and x["try"] == args.accept), None)
        kept = out / "runs" / f"ep{n:02d}.failed{args.accept}"
        if not inc or not inc.get("item") or not kept.exists():
            raise SystemExit(f"{n}화 {args.accept}번째 실패본(과 그 회차 정하기 기록)이 없습니다")
        if (out / "runs" / f"ep{n:02d}").exists():
            raise SystemExit(f"{out / 'runs' / f'ep{n:02d}'} 가 이미 있습니다")
        kept.rename(out / "runs" / f"ep{n:02d}")
        inc["accepted"] = time.strftime("%H:%M:%S")
        item = inc["item"]
        state["plan"] = state["plan"][:n - 1] + [item]
        state["issues"][str(n)] = inc.get("all_issues") or inc["issues"]
        log(f"[{n}화] 사람이 {args.accept}번째 실패본을 통과 — 정리(설정집 · 진행 상태 갱신)")
        state["book"] = digest(env, out, n, state["book"])
        state["done"], state["cursor"], state["status"] = n, item["end"], "running"
        if free_after(state, item):
            state["source_done_at"] = n
        put(out / "state.json", state)
    failed = out / "runs" / f"ep{state['done'] + 1:02d}"
    if args.resume and failed.exists():
        # 예전 판(멈추기만 하던 것)으로 멈춘 화 — 실패본으로 옮기고 회차 정하기부터 다시 한다.
        n = state["done"] + 1
        tries = sum(1 for x in state.setdefault("incidents", []) if x["n"] == n)
        kept = failed.with_name(f"{failed.name}.failed{tries + 1}")
        while kept.exists():
            tries += 1
            kept = failed.with_name(f"{failed.name}.failed{tries + 1}")
        failed.rename(kept)
        if str(n) in state.get("issues", {}) and len(state["plan"]) >= n:
            state["incidents"].append({"n": n, "try": tries + 1, "request": state["plan"][n - 1]["request"],
                                       "issues": [i for i in state["issues"][str(n)] if i.get("severity") == "critical"],
                                       "kept": kept.name, "at": time.strftime("%H:%M:%S")})
        state["plan"] = state["plan"][:n - 1]
    state["status"] = "running"
    stop_levels = {"critical": {"critical"}, "major": {"critical", "major"}, "none": set()}[args.stop_on]
    source = Path(state["source"]).read_text(encoding="utf-8")
    while True:
        n = state["done"] + 1
        if state.get("source_done_at") and n - state["source_done_at"] > int(state.get("free_episodes") or 0):
            state["status"] = "complete"      # 이어 쓰기 마지막 화까지 받았다(사람이 통과시킨 경우 · 완결 뒤 다시 돌린 경우)
            break
        if args.only and n > args.only or n > args.max_episodes:
            break
        t0 = time.time()
        if len(state["plan"]) < n:          # 멈춘 화를 다시 돌릴 때는 정해 둔 구간을 그대로 쓴다
            step = free_step if state.get("source_done_at") else plan_step
            item = step(env, out, n, state, source, args.plan_model)
            state["plan"].append(item)
            put(out / "state.json", state)
        item = state["plan"][n - 1]
        pct = round(100 * item["end"] / len(source))
        where = "원작 없이 이어 쓰기" if item.get("free") else f"원작 {item['start']}~{item['end']}자 ({pct}%)"
        mark = (" · 원작 마지막 화" if free_after(state, item) else " · 마지막 화") if item["last"] else ""
        log(f"[{n}화] 생성 — {where}{mark}")
        episode(env, out, n, state, writer_request(state, source, item))
        found = (check(env, out, n, args.check_model, state.get("reader_check", False)).get("issues", [])
                 if n > 1 or state.get("reader_check") else [])
        state["issues"][str(n)] = found
        levels = [i.get("severity") for i in found]
        bad = [i for i in found if i.get("severity") in stop_levels
               or (state.get("reader_gate") and str(i.get("what", "")).startswith("읽기") and i.get("severity") == "major")]
        if bad:
            # 정리(설정집 갱신)를 하지 않는다 — 틀린 화가 기억으로 굳지 않게. 실패본은 따로 두고, 회차 정하기부터
            # 다시 한다(같은 요청으로 다시 쓰면 같은 실수가 난다). 두 번 다시 해도 걸리면 멈춘다(강의 p.85 「최대 3회 … 사람에게」).
            tries = sum(1 for x in state.setdefault("incidents", []) if x["n"] == n and not x.get("before_resume"))
            run_dir = out / "runs" / f"ep{n:02d}"
            kept = run_dir.with_name(f"{run_dir.name}.failed{tries + 1}")
            if kept.exists():
                shutil.rmtree(kept)
            run_dir.rename(kept)
            state["incidents"].append({"n": n, "try": tries + 1, "request": item["request"], "issues": bad,
                                       "all_issues": found, "item": item, "kept": kept.name, "at": time.strftime("%H:%M:%S")})
            state["plan"] = state["plan"][:n - 1]
            if tries + 1 >= 3:
                state["status"] = f"stopped_at_{n}"
                put(out / "state.json", state)
                log(f"[멈춤] {n}화 세 번 모두 검사 {levels}: {bad[0].get('what')}")
                return 2
            put(out / "state.json", state)
            log(f"[다시] {n}화 검사 {levels} — {tries + 1}번째 실패본 {kept.name}, 회차 정하기부터 다시: {bad[0].get('what')}")
            continue
        log(f"[{n}화] 정리(설정집 · 진행 상태 갱신)")
        state["book"] = digest(env, out, n, state["book"])
        state["done"] = n
        state["cursor"] = item["end"]
        state.setdefault("seconds", {})[str(n)] = round(time.time() - t0)
        if free_after(state, item):
            # 원작을 다 옮겼다 — 완결하지 않고 다음 화부터 원작 없이 이어 쓴다(free_step). done 과 같이 저장한다.
            state["source_done_at"] = n
        log(f"[{n}화] 끝 {round(time.time() - t0)}초 · 검사 {levels or '문제 없음'}")
        put(out / "state.json", state)
        if state.get("source_done_at") == n:
            log(f"[{n}화] 원작 끝 — 다음 화부터 {state['free_episodes']}화를 원작 없이 이어 씁니다")
            continue
        if item["last"]:
            state["status"] = "complete"
            break
    if state["status"] != "complete":
        state["status"] = "paused"
    put(out / "state.json", state)
    log(f"[끝] {state['status']} — {state['done']}화 · 원작 {state['cursor']}/{len(source)}자")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
