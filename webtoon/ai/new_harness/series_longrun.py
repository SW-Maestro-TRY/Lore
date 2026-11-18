"""장기 연재 시험 — 제품의 「다음 화」 흐름을 사람 대신 완결까지 반복한다(글만).

새 하네스가 아니다. 서버(SeriesService · SeriesDigestService · JobRunner)가 회차마다 부르는 것을 같은
순서 · 같은 모양으로 부른다.

  0. 원작 글 → 설정집 뽑기(lore_extract.extract, 「글에서 설정집 만들기」와 같은 함수)
     원작 글 → 회차 계획(이 시험에서 사람이 「이번 화 내용」 칸에 적을 말을 대신 정한다)
  1화  own.run_own(sheet=False) → own.own_scenes        (= run.py --own, --own-scenes. 시트 그림만 뺌)
  N화 끝  run.py --series-digest                        (= 다음 화 버튼을 누르면 서버가 부르는 회차 정리)
  N+1화  run.py --character --continuation --own → --own-scenes  (= 서버가 다음 화를 만들 때)

사람이 하던 일 두 가지만 이 파일이 대신한다.
  - 「이번 화 내용」 적기 → 회차 계획의 한 줄
  - 정리 결과 확인 → 그대로 다 받는다(사람이 틀린 줄을 빼는 단계를 건너뛴다. 그래서 틀린 기억이 그대로
    쌓이는지가 보인다)

그리고 시험용 검사 하나를 붙인다 — 매 화 본문을 **앞 화 본문 전부**와 대조해 설정이 틀어졌는지 본다
(continuity). 생성은 설정집만 보고, 검사는 원문 전부를 본다. 「심각」이 나오면 멈춘다.

  python3 series_longrun.py --source samples/X.txt --hero 이름 --genre 장르 --episodes 30 --out DIR
  python3 series_longrun.py --out DIR --resume          # 멈춘 자리부터

모델은 구독 경로(WEBTOON_CODEX_PROXY)만 쓴다. 키는 ~/.cli-proxy-api/openai-env.sh 에서 읽는다.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent

PLAN_PROMPT = """아래 [원작]을 웹툰 연재로 각색한다. 1화부터 완결까지 회차별로 「이번 화에 일어날 일」을 정한다.

- 약 {n}화로 나눈다. 원작의 흐름상 더 적거나 많은 것이 자연스러우면 그 수를 따른다(최대 {max}화).
- 각 화는 웹툰 한 화 분량(장면 4~6개)의 사건 하나다. 원작의 큰 사건 순서를 지킨다.
- 한 줄은 그 화를 쓰는 작가에게 주는 요청이다. 그 화에 일어날 일만 2~3문장으로 적는다.
  앞 화에서 일어난 일, 인물 설정, 세계 규칙은 적지 않는다(그것은 설정집과 진행 상태가 따로 넘긴다).
- 원작에 같은 사건이 여러 버전으로 적혀 있거나 이름이 섞여 있으면 하나를 골라 끝까지 그것으로 쓴다.
- 마지막 화는 원작의 결말이다.

출력은 JSON 하나만: {{"episodes":[{{"n":1,"request":"..."}}], "choices":["원작의 모순을 어떻게 골랐는지"]}}

[원작]
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

severity
- critical: 독자가 바로 알아챌 큰 모순(죽은 인물이 설명 없이 나옴, 핵심 관계 · 정체가 뒤집힘, 큰 사건의 경위가 바뀜)
- major: 눈에 띄는 모순(소지품 · 부상 · 위치 · 아는 정보가 틀림)
- minor: 사소한 어긋남

반드시 이전 회차의 근거 문장과 이번 화의 문장을 그대로 인용한다. 근거를 인용할 수 없으면 적지 않는다.
출력은 JSON 하나만: {"issues":[{"severity":"critical|major|minor","what":"무엇이 어긋났나",
"before":"이전 회차 인용 (몇 화)","now":"이번 화 인용"}]}

"""


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def read(p: Path):
    return json.loads(Path(p).read_text(encoding="utf-8"))


def put(p: Path, obj) -> None:
    Path(p).parent.mkdir(parents=True, exist_ok=True)
    Path(p).write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def proxy_env() -> dict:
    """~/.cli-proxy-api/openai-env.sh 를 읽은 셸의 OPENAI_* 를 구독 경로 변수로. 키는 어디에도 쓰지 않는다."""
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
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                data = json.loads(r.read())
            break
        except Exception as exc:                                       # noqa: BLE001
            if attempt == 2:
                raise
            log(f"  재시도 {attempt + 1}: {exc}")
            time.sleep(10)
    text = "".join(c.get("text", "") for o in data.get("output", []) if o.get("type") == "message"
                   for c in o.get("content", []))
    out.with_suffix(".txt").write_text(text, encoding="utf-8")
    return json.loads(text[text.find("{"):text.rfind("}") + 1])


def harness(env: dict, out: Path, *args: str, code: str | None = None) -> None:
    """run.py 를 서버가 부르는 그대로 자식 프로세스로. 실패하면 멈춘다."""
    cmd = [sys.executable, "-c", code, *args] if code else [sys.executable, "run.py", *args]
    with (out / "harness.log").open("a", encoding="utf-8") as f:
        f.write(f"\n$ {' '.join(args) or 'own.run_own(sheet=False)'}\n")
        f.flush()
        r = subprocess.run(cmd, cwd=HERE, env={**env, "NH_RUNS_DIR": str(out / "runs")}, stdout=f, stderr=f)
    if r.returncode:
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


def body_of(run_dir: Path) -> dict:
    d = read(run_dir / "directions.json")[0]
    scenes = read(run_dir / "scenes.json").get("scenes", []) if (run_dir / "scenes.json").is_file() else []
    return {"title": d.get("title", ""), "body": d.get("body") or d.get("raw", ""), "scenes": scenes}


def scene_lines(run_dir: Path) -> list[str]:
    sys.path.insert(0, str(HERE))
    import own
    return [own.scene_text(s) for s in body_of(run_dir)["scenes"]]


def check(env: dict, out: Path, n: int, model: str) -> dict:
    history = []
    for i in range(1, n):
        b = body_of(out / "runs" / f"ep{i:02d}")
        history.append(f"[{i}화 — {b['title']}]\n{b['body']}")
    now = body_of(out / "runs" / f"ep{n:02d}")
    prompt = CHECK_PROMPT + "[이전 회차 원문]\n" + "\n\n".join(history) + f"\n\n[이번 화 — {n}화 {now['title']}]\n" + now["body"]
    return ask(env, prompt, model, out / "checks" / f"ep{n:02d}")


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
entries = []
for i, part in enumerate([src[j:j+30000] for j in range(0, len(src), 30000)]):
    rows, more, renamed = X.extract(part, out.parent, 'ko', entries)
    entries = X.apply_book(entries, rows, more, renamed)
out.write_text(json.dumps(entries, ensure_ascii=False, indent=2))
"""
    (out / "lore").mkdir(parents=True, exist_ok=True)
    harness(env, out, str(Path(args.source).resolve()), str(out / "lore" / "book0.json"), code=code)
    entries = [{k: v for k, v in e.items() if k not in ("new", "updated")} for e in read(out / "lore" / "book0.json")]
    log("[준비] 회차 계획을 세웁니다")
    plan = ask(env, PLAN_PROMPT.format(n=args.episodes, max=args.max_episodes) + source, args.plan_model, out / "plan")
    hero = next((e for e in entries if e.get("title") == args.hero), None)
    state = {"title": args.title or Path(args.source).stem, "hero": args.hero, "genre": args.genre,
             "hero_desc": (hero or {}).get("content", "")[:1500], "plan": plan["episodes"],
             "choices": plan.get("choices", []), "book": {"entries": entries, "progress": []},
             "done": 0, "status": "running", "issues": {}}
    put(out / "state.json", state)
    return state


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--source")
    p.add_argument("--hero")
    p.add_argument("--genre", default="")
    p.add_argument("--title", default="")
    p.add_argument("--episodes", type=int, default=30)
    p.add_argument("--max-episodes", type=int, default=40)
    p.add_argument("--out", required=True)
    p.add_argument("--resume", action="store_true")
    p.add_argument("--plan-model", default="gpt-6.2-astra")
    p.add_argument("--check-model", default="gpt-6.2-astra")
    p.add_argument("--stop-on", default="critical", help="이 등급이 나오면 멈춘다 (critical · major · none)")
    p.add_argument("--only", type=int, default=0, help="이번 실행에서 최대 몇 화까지 (0 = 끝까지)")
    args = p.parse_args()
    out = Path(args.out).resolve()
    env = proxy_env()
    state = read(out / "state.json") if args.resume else setup(env, args, out)
    failed = out / "runs" / f"ep{state['done'] + 1:02d}"
    if args.resume and failed.exists():
        failed.rename(failed.with_name(failed.name + time.strftime(".failed-%H%M%S")))
    state["status"] = "running"
    stop_levels = {"critical": {"critical"}, "major": {"critical", "major"}, "none": set()}[args.stop_on]
    total = len(state["plan"])
    for item in state["plan"][state["done"]:]:
        n = int(item["n"])
        if args.only and n > args.only:
            break
        t0 = time.time()
        log(f"[{n}/{total}화] 생성")
        episode(env, out, n, state, item["request"])
        found = check(env, out, n, args.check_model).get("issues", []) if n > 1 else []
        state["issues"][str(n)] = found
        levels = [i.get("severity") for i in found]
        bad = [i for i in found if i.get("severity") in stop_levels]
        if bad:
            # 정리(설정집 갱신)를 하지 않고 멈춘다 — 틀린 화가 기억으로 굳지 않게. 다시 돌리면 이 화는 따로 옮겨 두고 새로 만든다.
            state["status"] = f"stopped_at_{n}"
            put(out / "state.json", state)
            log(f"[멈춤] {n}화 검사 {levels}: {bad[0].get('what')}")
            return 2
        log(f"[{n}/{total}화] 정리(설정집 · 진행 상태 갱신)")
        state["book"] = digest(env, out, n, state["book"])
        state["done"] = n
        state.setdefault("seconds", {})[str(n)] = round(time.time() - t0)
        log(f"[{n}/{total}화] 끝 {round(time.time() - t0)}초 · 검사 {levels or '문제 없음'}")
        put(out / "state.json", state)
    state["status"] = "complete" if state["done"] >= total else "paused"
    put(out / "state.json", state)
    log(f"[끝] {state['status']} — {state['done']}/{total}화")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
