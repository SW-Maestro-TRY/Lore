"""대화 캡처로 만드는 웹툰(개인 테스트) — 캡처 전사 → 화·장면 나누기 → 장면 쓰기 → 시트 보관함.

보통 길(이야기 짓기·장면 나누기)은 쓰지 않는다. 그 길은 이야기를 다시 써서 대사가 바뀐다.
여기서는 대화를 **대사 그대로** 장면에 옮기고, 그림은 기존 장 그리기(`--detail-pages`)가 그린다.

run 폴더 구성 (부모 하나 + 화마다 하나):
  test-<32hex>/            captures/ · profiles/ · transcript.json · draft.json · plan.json
  test-<32hex>-e1/ -e2/…   input.json · scenes.json · sheet.png · sheets/ · pages/  (화 하나 = 보통 run 하나)

시트 보관함: `<runs>/_test-comic/sheets/<이름>/<그림체>/{sheet.png, spec.json}` — 저장소 밖(runs 는 gitignore).
같은 이름·같은 그림체가 있으면 그 시트를 그대로, 그림체만 다르면 사양(spec)만 가져와 새 그림체로 한 장 그린다.
옛 시트 **그림**을 참조로 붙이지 않는다 — 참조가 붙으면 편집으로 읽혀 옛 그림체를 따라간다(run.py `_sheet_attempt`).
"""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

import llm
from llm import story

STYLE = "webtoon_lock_bg"
QUALITY = "swell"
PAGE_MODEL = "gpt-image-2"
# 장 프롬프트에 모든 장면의 요약이 다 들어간다 — 한 화가 길면 32,000자 한도에 닿는다(22장면이 30.6k).
MAX_SCENES_PER_EPISODE = 22
# 사람이 그린 결과에서 확인한 것: 한 컷에 말풍선이 셋 넘게 몰리면 읽기 어렵다.
BUBBLE_RULE = "한 컷에는 말풍선 두 개까지만 두고, 대사가 길거나 많으면 컷을 더 나눠 한 줄씩 나눠 싣는다."


def _write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _json(text: str) -> dict:
    value = story.extract_json(text)
    if not isinstance(value, dict):
        raise ValueError("모델 응답이 JSON 객체가 아닙니다")
    return value


def _safe(name: str) -> str:
    return re.sub(r"[\\/:*?\"<>|]", "_", name.strip()) or "_"


def episode_dir(parent: Path, n: int) -> Path:
    return parent.parent / f"{parent.name}-e{n}"


# ── 1. 캡처 전사 ───────────────────────────────────────────────────

def extract(run_dir: Path) -> None:
    """캡처를 파일 이름 순서로 읽는다. 없는 말을 만들지 않는다."""
    captures = sorted([*(run_dir / "captures").glob("capture*.jpg"),
                       *(run_dir / "captures").glob("capture*.png")])
    if not 1 <= len(captures) <= 40:
        raise ValueError("대화 캡처는 1~40장까지 올려 주세요")
    batches = [captures[i:i + 5] for i in range(0, len(captures), 5)]
    parts = []
    for n, batch in enumerate(batches, 1):
        prompt = (
            "다음은 채팅 롤플레이 앱 화면 캡처다. 이미지 순서대로 메시지를 정확히 옮겨 적어라. "
            "메시지 하나는 말풍선(또는 한 사람이 한 번에 보낸 글) 하나다. 화자 이름을 적고, 글은 줄바꿈까지 그대로 옮긴다. "
            "서술과 대사가 섞여 있어도 나누거나 고치지 말고 화면에 보이는 그대로 적는다. "
            "성적인 표현도 단어를 순화하거나 생략하지 말고 보이는 문구 그대로 전사한다. 화면에 없는 말을 만들지 않는다. 캡처 사이에 겹쳐 보이는 메시지는 한 번만 적는다. "
            "읽을 수 없는 글자는 [판독 불가]로 남긴다. 상단 메뉴·시계·버튼·광고·안내 문구는 뺀다. "
            "JSON 객체 {\"dialogue\":[{\"speaker\":\"이름\",\"text\":\"원문\"}],\"characters\":[\"화자 이름\"]}만 돌려준다. "
            f"이 묶음은 전체 {len(batches)}개 중 {n}번째다."
        )
        raw, meta = llm.Call("STORY")(prompt, images=llm.load_images(batch), temperature=0, max_tokens=16000)
        data = _json(raw)
        lines = data.get("dialogue")
        if not isinstance(lines, list):
            raise ValueError("대화 전사 형식이 잘못되었습니다")
        batch_lines = [{"speaker": str(x.get("speaker") or "").strip(), "text": str(x["text"])}
                       for x in lines if isinstance(x, dict) and x.get("text")]
        overlap = min(len(parts), len(batch_lines))
        while overlap and parts[-overlap:] != batch_lines[:overlap]:
            overlap -= 1
        parts.extend(batch_lines[overlap:])
        _write(run_dir / f"extract-meta-{n}.json", meta)
    cleaned = parts
    if not cleaned:
        raise ValueError("캡처에서 대화를 읽지 못했습니다")
    speakers = list(dict.fromkeys(x["speaker"] for x in cleaned if not is_narrator(x["speaker"])))
    _write(run_dir / "transcript.json", {"dialogue": cleaned, "characters": speakers})


# ── 대사 보존 검사 ──────────────────────────────────────────────────
# 롤플레이 봇 메시지는 서술 여러 문장 + 대사 한두 줄이다. 서술은 「…다.」로 끝나는 문장이 대부분이고,
# 대사는 줄 하나로 따로 서는 일이 많다. 그래서 「따로 선 줄 중 서술 어미로 끝나지 않는 것」을 대사 후보로 본다.
# 완벽하지 않다 — 빠진 것은 다시 쓰게 하고, 그래도 빠지면 확인 화면에 보여 준다(자동으로 끼워 넣지 않는다).

_NARRATION_END = re.compile(r"(다|듯|듯이|처럼|며|고|채|데|게|도)[.。]?$")
# 3인칭 서술(「그녀가 …」「몽이는 …했다」)은 대사가 아니다.
_NARRATION_SUBJECT = re.compile(r"(그녀|그는|그의|그가|[가-힣]{2,}(은|는|의|가|이) [가-힣]+(했|었|였|았|다))")


# 화자가 아니라 서술 칸에 붙는 이름 — 인물로 세지 않고, 대사 검사도 하지 않는다.
NARRATOR_NAMES = {"", "서술", "나레이션", "내레이션", "지문", "해설", "narration", "narrator"}


def is_narrator(name: str) -> bool:
    return str(name or "").strip().lower() in NARRATOR_NAMES


# 캡처 글은 화면 폭에서 줄이 꺾여 들어온다. 문장 끝 표시 없이 끝난 줄은 다음 줄에 이어 붙여 한 문장으로 본다.
_LINE_DONE = re.compile(r"([.!?。…~”\"’'」)\]]|[ㄱ-ㅎ]|[.]{2,})$")


def _unwrap(text: str) -> list[str]:
    out, buf = [], ""
    for raw in str(text).splitlines():
        line = raw.strip()
        if not line:
            if buf:
                out.append(buf)
            buf = ""
            continue
        buf = f"{buf} {line}" if buf else line
        if _LINE_DONE.search(buf.strip("*").strip()):
            out.append(buf)
            buf = ""
    if buf:
        out.append(buf)
    return out


def speech_lines(text: str) -> list[str]:
    out = []
    for raw in _unwrap(text):
        line = raw.strip().strip("*").strip()
        if len(line) < 2:
            continue
        quoted = re.findall(r"[“\"]([^”\"]{2,})[”\"]", line)
        if quoted:
            out.extend(q.strip() for q in quoted)
            continue
        if _NARRATION_END.search(line) or _NARRATION_SUBJECT.search(line) or len(line) > 90:
            continue
        out.append(line)
    return out


def _norm(s: str) -> str:
    return re.sub(r"[\s“”\"'‘’.,…~!?\-ㅡ]+", "", s)


def missing_lines(messages: list[dict], acting: str) -> list[str]:
    """대사 후보의 앞부분(지문이 뒤에 붙어 있어도 되게)이 장면 글에 있는지 본다."""
    have = _norm(acting)
    out = []
    for m in messages:
        if is_narrator(m.get("speaker", "")):
            continue
        for line in speech_lines(m.get("text", "")):
            head = re.split(r"\.{2,}|…|!{2,}|\?{2,}", line, maxsplit=1)[0]  # 대사 뒤에 붙은 지문은 떼고 본다
            key = _norm(head if len(_norm(head)) >= 2 else line)[:8]
            if key and key not in have:
                out.append(line)
    return out


# ── 2. 화·장면 나누기 + 장면 쓰기 ─────────────────────────────────────

SCENE_RULES = (
    "장면 하나는 세로 웹툰 한 장으로 그려진다. 그림 모델은 장면 글만 보고 컷을 나눠 그린다.\n"
    "- acting: 인물이 입 밖으로 낸 말은 원문 글자 그대로 “…” 안에 넣는다. 고치거나 줄이거나 합치거나 순서를 바꾸지 않는다. "
    "속마음은 ‘…’. 서술·지문은 그림에 보이는 것(동작·표정·시선·손·소품·자리)만 짧게 옮기고, 감상·비유·설명은 버린다. "
    "사용자 쪽 메시지의 짧은 지문(행동 묘사, 울상, 도리도리 같은 것)은 그 인물의 행동·표정으로 옮긴다. "
    "누가 한 말인지 알 수 있게 인물 이름을 붙인다.\n"
    "- 인물 이름은 아래 [등장인물] 이름으로 통일한다. 대화 속에서 다른 이름으로 불려도 같은 사람이면 그 이름으로 쓴다.\n"
    "- 인물의 자리·자세·거리·신체 접촉은 대화에 적힌 그대로 옮긴다. 다른 자세로 바꾸거나 순하게 고치거나 빼지 않는다. "
    "대화에 없는 노출·성적인 묘사를 덧붙이지 않는다.\n"
    "- 이어짐: 그림 모델은 한 장씩 따로 그리고 다음 장면을 모른다. 그래서 where 에 장면이 시작할 때 인물들이 서로 어디에 있는지"
    "(떨어져 있는지, 붙어 있는지, 어떤 자세인지)를 적고, ends 에는 다음 장면의 첫 동작이 시작되기 바로 전 상태를 적는다. "
    "다음 장면에서 처음 일어나는 일이 이 장면에서 미리 일어나 있으면 안 된다.\n"
    "- 두 사람이 잠자리로 넘어가는 대목은 그 직전(키스·포옹)까지만 그리고, 다음 컷은 암전(검은 컷에 짧은 효과음이나 「…」)으로 끊은 뒤 "
    "그다음 장면(다음 날 아침 등)으로 넘어간다. 그 사이의 성적인 행위·노출·묘사는 장면 글에도 그림에도 넣지 않고, 그 사이에 오간 말도 옮기지 않는다.\n"
    "- 피·상처는 작은 얼룩이나 그림자로만.\n"
    "- 「두 번 뽀뽀한다」「여러 번 때린다」처럼 같은 동작을 되풀이하는 말은 「연달아 ~한다」로 쓴다 — "
    "그림 모델이 횟수만큼 같은 인물을 한 컷에 여러 번 그린다.\n"
    "- 우스운 순간은 그렇게 보이게(과장된 표정, 개그 톤) 적는다.\n"    "- 흐름은 천천히 차분하게: 말과 말 사이에 말 없는 컷(표정, 반응, 손짓, 풍경, 둘 사이의 거리)을 넣어 숨 쉴 틈을 준다. "
    "말풍선이 몰리는 컷을 만들지 않는다.\n"

    "- wear: 이 장면에 나오는 인물마다 지금 입고 있는 옷(이름 → 옷차림). 시트의 옷은 기본값일 뿐이고 그림은 이 칸을 따른다. "
    "대화에 옷이 나오거나 갈아입으면 그 장면부터 바꿔 적고, 안 나오면 앞 장면 옷을 그대로 적는다. "
    "첫 장면은 대화와 상황(장소, 직전에 한 일)에서 정하고, 그래도 알 수 없을 때만 등장인물 설명·사용자 메모의 기본 옷을 쓴다.\n"
    "- look: 이 장면의 소품, 앞 장면과 이어져야 하는 것(손에 든 것, 묻은 것, 놓인 것)과 아직 나오면 안 되는 것.\n"
    "- where: 장소와 시간, 빛. what: 이 장면에서 일어나는 일 한 문장. ends: 장면이 끝날 때 화면에 남는 상태 한 문장.\n"
)


def _numbered(dialogue: list[dict], start: int = 1) -> str:
    return "\n".join(f'[{i}] {x.get("speaker", "?")}: {x.get("text", "")}' for i, x in enumerate(dialogue, start))


def _plan(dialogue: list[dict], title: str, cast: str) -> tuple[dict, dict]:
    prompt = (
        "아래는 채팅 롤플레이 대화다. 대화를 대사 그대로 세로 웹툰으로 옮기려 한다. "
        "메시지 번호로 장면을 나누고(장면 하나 = 웹툰 한 장), 장면을 화(1화, 2화…)로 묶어라.\n"
        "- 장면: 같은 자리에서 대화가 한 호흡으로 이어지는 단위. 장소·시간이 바뀌거나, 화제나 감정이 크게 바뀌는 곳에서 나눈다. "
        "한 장면에 담기는 대사가 한 장에 무리 없이 들어가는 분량이게 한다.\n"
        "- 화: 독자가 한 번에 읽는 웹툰 한 회 분량으로 묶는다(보통 장면 15개 안팎). 대화가 그만큼이 안 되면 한 화로 둔다. "
        "화를 잘게 자르지 않는다. 한 화가 끝나는 곳은 한 토막이 마무리되거나 다음이 궁금해지는 지점이다. "
        f"한 화는 {MAX_SCENES_PER_EPISODE}장면을 넘지 않는다.\n"
        "- 모든 메시지를 빠짐없이, 겹치지 않게, 순서대로 장면에 넣는다. from·to 는 포함 범위다.\n"
        "- 화마다 짧은 소제목을 붙인다(대화에 있는 말·사건에서).\n"
        "JSON 객체 {\"episodes\":[{\"n\":1,\"subtitle\":\"…\",\"scenes\":[{\"from\":1,\"to\":4}]}]}만 돌려준다.\n\n"
        f"[제목] {title}\n[등장인물]\n{cast}\n\n[대화]\n{_numbered(dialogue)}"
    )
    text, meta = llm.Call("SCENE")(prompt, temperature=0, max_tokens=8000)
    return _json(text), meta


def _check_plan(plan: dict, total: int) -> list[dict]:
    eps = plan.get("episodes")
    if not isinstance(eps, list) or not eps:
        raise ValueError("화를 나누지 못했습니다")
    cursor, out = 1, []
    for e_i, ep in enumerate(eps, 1):
        scenes = ep.get("scenes") if isinstance(ep, dict) else None
        if not isinstance(scenes, list) or not scenes:
            raise ValueError(f"{e_i}화에 장면이 없습니다")
        ranges = []
        for s in scenes:
            a, b = s.get("from"), s.get("to")
            if a != cursor or not isinstance(b, int) or not a <= b <= total:
                raise ValueError(f"{e_i}화 장면 범위가 어긋났습니다 ({a}~{b}, 기대 시작 {cursor})")
            ranges.append((a, b))
            cursor = b + 1
        for k in range(0, len(ranges), MAX_SCENES_PER_EPISODE):  # 상한을 넘으면 쪼갠다(그림 프롬프트 한도)
            out.append({"subtitle": str(ep.get("subtitle") or "").strip(), "ranges": ranges[k:k + MAX_SCENES_PER_EPISODE]})
    if cursor != total + 1:
        raise ValueError("장면에 빠진 메시지가 있습니다")
    return out


PAGE_LINES = 6   # 한 장(장면)에 담는 말 줄 수의 상한 — 넘으면 코드가 메시지 경계에서 장면을 나눈다


def _pace(episodes: list[dict], dialogue: list[dict]) -> list[dict]:
    """말이 많은 장면을 나눈다. 짧은 대사를 여러 메시지로 쪼개 보내는 대화(제타 VN 등)는 한 장에 말풍선이
    여섯 개씩 몰렸다(2026-11-24, 「말 너무 많아 보여, 천천히 차분하게」). 프롬프트에 숫자를 적는 대신 코드로 자른다.
    나눈 뒤 한 화가 MAX_SCENES_PER_EPISODE 를 넘으면 화를 더 나눈다."""
    out = []
    for ep in episodes:
        ranges = []
        for a, b in ep["ranges"]:
            start, lines = a, 0
            for i in range(a, b + 1):
                n = len(speech_lines(dialogue[i - 1].get("text", "")))
                if lines and lines + n > PAGE_LINES:
                    ranges.append((start, i - 1))
                    start, lines = i, 0
                lines += n
            ranges.append((start, b))
        for k in range(0, len(ranges), MAX_SCENES_PER_EPISODE):
            out.append({"subtitle": ep.get("subtitle", ""), "ranges": ranges[k:k + MAX_SCENES_PER_EPISODE]})
    return out


def _write_scenes(dialogue: list[dict], ranges: list[tuple], title: str, cast: str, prev_end: str,
                  fix: dict | None = None, wear: dict | None = None) -> tuple[list[dict], dict]:
    blocks = []
    for i, (a, b) in enumerate(ranges, 1):
        extra = ""
        if fix and i in fix:
            extra = ("\n  (앞서 쓴 글에서 빠진 대사 — 이번엔 원문 그대로 꼭 넣는다. 단 암전으로 넘긴 잠자리 대목 안의 말은 넣지 않는다: "
                     + " / ".join(fix[i]) + ")")
        blocks.append(f"### 장면 {i} — 메시지 [{a}]~[{b}]{extra}\n{_numbered(dialogue[a - 1:b], a)}")
    prompt = (
        "아래 대화를 장면별로 세로 웹툰 장면 글로 옮겨라.\n" + SCENE_RULES +
        "JSON 객체 {\"scenes\":[{\"n\":1,\"where\":\"…\",\"what\":\"…\",\"acting\":\"…\",\"wear\":{\"인물 이름\":\"옷차림\"},\"look\":\"…\",\"ends\":\"…\"}]}만 돌려준다. "
        f"장면은 {len(ranges)}개, 순서대로.\n\n"
        f"[제목] {title}\n[등장인물]\n{cast}\n"
        + (f"[앞 화가 끝난 상태] {prev_end}\n" if prev_end else "")
        + (f"[앞 화가 끝날 때 옷차림] " + " / ".join(f"{k}: {v}" for k, v in wear.items()) + "\n" if wear else "")
        + "\n" + "\n\n".join(blocks)
    )
    text, meta = llm.Call("SCENE")(prompt, temperature=0.3, max_tokens=32000)
    data = _json(text)
    scenes = data.get("scenes")
    if not isinstance(scenes, list) or len(scenes) != len(ranges):
        raise ValueError(f"장면 글이 {len(ranges)}개가 아닙니다")
    return scenes, meta


_QUOTE = re.compile(r"[“\"]([^”\"]+)[”\"]")


def soften(run_dir: Path, page: int) -> bool:
    """안전 검사에 걸린 장(page = 장면 page-1)의 그림 묘사만 순하게 고쳐 쓴다. 대사(“…”)는 한 글자도 안 바꾼다.

    처음 고칠 때 원래 장면을 scenes.orig.json 에 남긴다. 대사가 하나라도 빠지면 쓰지 않고 False.
    """
    path = run_dir / "scenes.json"
    data = _read(path)
    scenes = data.get("scenes") or []
    if not 2 <= page <= len(scenes) + 1:
        return False
    scene = scenes[page - 2]
    keep = [q.strip() for q in _QUOTE.findall(str(scene.get("acting") or "")) if q.strip()]
    prompt = (
        "아래는 세로 웹툰 한 장의 장면 글이다. 이 글로 그림을 그리려 했더니 이미지 모델의 안전 검사(성적 표현)에 걸렸다. "
        "전체 이용가 로맨틱 코미디 웹툰에 그대로 실릴 그림이 되도록 where·what·acting·look·ends 를 고쳐 써라.\n"
        "- acting 안의 대사(“…” 안의 글)는 한 글자도 바꾸지 말고, 순서와 말하는 사람도 그대로 둔다.\n"
        "- 장면의 감정(설렘·장난·당황·다정함)과 흐름은 살리고, 그것을 표정·눈빛·거리·자세·소품·효과음으로 보여 준다.\n"
        "- 몸이 닿는 동작은 길에서 남이 봐도 자연스러운 가벼운 스킨십으로 바꾼다.\n"
        "- 고친 글은 보이는 것만 긍정형으로 적는다. 빼는 것을 이름으로 다시 적지 않는다.\n"
        "JSON 객체 {\"where\":\"…\",\"what\":\"…\",\"acting\":\"…\",\"look\":\"…\",\"ends\":\"…\"}만 돌려준다.\n\n"
        + json.dumps({k: scene.get(k, "") for k in ("where", "what", "acting", "look", "ends")}, ensure_ascii=False, indent=1)
    )
    for _ in range(2):
        text, meta = llm.Call("SCENE")(prompt, temperature=0.3, max_tokens=4000)
        new = _json(text)
        acting = str(new.get("acting") or "")
        if all(q in acting for q in keep):
            break
    else:
        return False
    orig = run_dir / "scenes.orig.json"
    if not orig.exists():
        _write(orig, data)
    look = str(new.get("look") or "").replace(BUBBLE_RULE, "").strip()
    scene.update({"where": str(new.get("where") or scene.get("where") or "").strip(),
                  "what": str(new.get("what") or scene.get("what") or "").strip(),
                  "acting": acting.strip(),
                  "look": (look + " " if look else "") + BUBBLE_RULE,
                  "ends": str(new.get("ends") or scene.get("ends") or "").strip(),
                  "softened": int(scene.get("softened") or 0) + 1})
    _write(path, data)
    _write(run_dir / f"soften-meta-{page:02d}.json", meta)
    return True


def _people(draft: dict) -> list[dict]:
    """인물 목록 — [{name, description, role}] (role: main=주인공급, cast=조연). 옛 칸(name·co_name)도 받는다."""
    raw = draft.get("people")
    if not isinstance(raw, list) or not raw:
        raw = [{"name": draft.get("name"), "description": draft.get("character"), "role": "main"},
               {"name": draft.get("co_name"), "description": draft.get("co_character"), "role": "main"}]
    out, seen = [], set()
    for p in raw:
        if not isinstance(p, dict):
            continue
        nm = str(p.get("name") or "").strip()
        if not nm or nm in seen or is_narrator(nm):
            continue
        seen.add(nm)
        out.append({"name": nm, "description": str(p.get("description") or "").strip()[:2000],
                    "role": "main" if p.get("role") != "cast" else "cast"})
    if out:
        out[0]["role"] = "main"
    return out[:12]


def _same_wear(a: str, b: str) -> bool:
    """같은 옷을 말만 바꿔 적은 것인가 — 낱말(조사 뗀 것) 겹침으로 본다."""
    def words(t):
        return {re.sub(r"(와|과|에|을|를|은|는|이|가|의|도)$", "", w) for w in re.findall(r"[가-힣A-Za-z]+", t)} - {"", "입은", "입고", "차림"}
    x, y = words(a), words(b)
    return bool(x and y) and len(x & y) / len(x | y) >= 0.6


def prepare(run_dir: Path) -> None:
    """확인한 대화(draft.json) → 화마다 run 폴더(scenes.json·input.json…)."""
    import run as R
    draft = _read(run_dir / "draft.json")
    dialogue = [{"speaker": str(x.get("speaker") or "").strip(), "text": str(x.get("text") or "").strip()}
                for x in (draft.get("dialogue") or []) if isinstance(x, dict) and str(x.get("text") or "").strip()]
    if not dialogue:
        raise ValueError("확인한 대화가 비어 있습니다")
    people = _people(draft)
    if not people:
        raise ValueError("주인공 이름을 적어 주세요")
    main, mains, extras = people[0], [p for p in people[1:] if p["role"] == "main"][:3], []
    extras = [p for p in people[1:] if p not in mains]
    name, description = main["name"], main["description"]
    title = str(draft.get("title") or "").strip() or "대화로 만든 웹툰"
    # 봇이 기본 이름(예: 다른 여주 이름)으로 부르는 것을 페르소나 이름으로 바꾼다 — 「옛이름=새이름」 줄마다.
    for row in str(draft.get("rename") or "").splitlines():
        if "=" in row:
            old, new = (s.strip() for s in row.split("=", 1))
            if old and new:
                for x in dialogue:
                    x["text"] = x["text"].replace(old, new)
                    if x["speaker"] == old:
                        x["speaker"] = new
    cast = "\n".join(f"- {p['name']}{'' if p['role'] == 'main' else '(조연)'}: {p['description'] or '(설명 없음)'}" for p in people)
    notes = str(draft.get("notes") or "").strip()[:2000]
    if notes:
        cast += f"\n[사용자 메모] {notes}"

    source_scenes = bool(draft.get("source_scenes"))
    if source_scenes:
        spans = [{"from": i, "to": min(i + 1, len(dialogue))} for i in range(1, len(dialogue) + 1, 2)]
        plan, meta = {"episodes": [{"scenes": spans}]}, {"source": "dialogue"}
    else:
        plan, meta = _plan(dialogue, title, cast)
    _write(run_dir / "plan-meta.json", meta)
    episodes = _check_plan(plan, len(dialogue))
    episodes = _pace(episodes, dialogue)

    def photos(i: int) -> list[str]:
        legacy = {0: "main.jpg", 1: "co.jpg"}.get(i)
        for nm in (f"p{i}.jpg", legacy):
            if nm and (run_dir / "profiles" / nm).exists():
                return [str(run_dir / "profiles" / nm)]
        return []
    # 앞 작품에 이어 붙이는 경우(서버가 draft 에 넣어 준다): 화 번호를 이어 세고, 앞 화가 끝난 장면에서 시작한다.
    start = int(draft.get("start_number") or 1)
    summary, prev_end = [], str(draft.get("prev_end") or "").strip()
    always = [str(t).strip() for t in (draft.get("always") or []) if str(t).strip()][:6]   # 장마다 그림 옆에 붙일 규칙
    wear: dict[str, str] = dict(draft.get("prev_wear") or {})   # 옷 상태 — 장면마다 저장하고 안 바뀐 사람은 앞 장면 옷을 물려받는다
    for n, ep in enumerate(episodes, 1):
        ranges = ep["ranges"]
        if source_scenes:
            scenes = []
            for a, b in ranges:
                source = "\n".join(f"{m['speaker']}: {m['text']}" for m in dialogue[a - 1:b])
                scenes.append({"acting": source, "user_text": source})
            lost = {}
        else:
            scenes, meta = _write_scenes(dialogue, ranges, title, cast, prev_end, wear=wear)
            lost = {i: v for i, ((a, b), s) in enumerate(zip(ranges, scenes), 1)
                    if (v := missing_lines(dialogue[a - 1:b], str(s.get("acting") or "")))}
            if lost:  # 빠진 대사가 있으면 그 화를 한 번 다시 쓰고, 빠졌던 장면만 바꾼다.
                again, _ = _write_scenes(dialogue, ranges, title, cast, prev_end, fix=lost, wear=wear)
                scenes = [again[i - 1] if i in lost else s for i, s in enumerate(scenes, 1)]
                lost = {i: v for i, ((a, b), s) in enumerate(zip(ranges, scenes), 1)
                        if (v := missing_lines(dialogue[a - 1:b], str(s.get("acting") or "")))}
        out, prev = [], prev_end
        for i, ((a, b), s) in enumerate(zip(ranges, scenes), 1):
            look = str(s.get("look") or "").strip()
            got = s.get("wear") if isinstance(s.get("wear"), dict) else {}
            for k, v in got.items():
                k, v = str(k).strip(), str(v).strip()
                if k and v and not _same_wear(wear.get(k, ""), v):   # 말만 바꾼 같은 옷은 앞 글자 그대로 — 옷 시트를 또 그리지 않게
                    wear[k] = v
            out.append({"n": i, "prev": prev, "where": str(s.get("where") or "").strip(), "wear": dict(wear),
                        **({"always": always} if always else {}),
                        **({"user_text": s["user_text"]} if source_scenes else {}),
                        "what": str(s.get("what") or "").strip(), "acting": str(s.get("acting") or "").strip(),
                        "look": (look + " " if look else "") + BUBBLE_RULE,
                        "ends": str(s.get("ends") or "").strip(), "narration": [],
                        "messages": [a, b], **({"missing": lost[i]} if i in lost else {})})
            prev = out[-1]["ends"]
        prev_end = prev
        ep_dir = episode_dir(run_dir, n)
        raw = {"name": name, "description": description, "genre": str(draft.get("genre") or "로맨틱 코미디"),
               "photos": photos(0), "story": " / ".join(x["what"] for x in out)[:1500], "title": title, "settings": notes}
        if mains:
            raw["co"] = [{"name": p["name"], "description": p["description"], "photos": photos(people.index(p))} for p in mains]
        char = R.normalize(raw)
        _write(ep_dir / "input.json", char)
        _write(ep_dir / "directions.json", [{"n": 1, "title": title, "genre": raw["genre"], "intro": char["story"],
                                             "body": char["story"], "plot": char["story"], "scenes": [], "cast": [], "hidden": []}])
        number = start + n - 1
        _write(ep_dir / "pick.json", {"n": 1, "title": title if (len(episodes) == 1 and start == 1) else f"{title} {number}화",
                                      "genre": raw["genre"]})
        # 조연은 장면 글에 이름이 나오는 장에서 시트(보관함에 있으면)와 생김새 글로 붙는다(detailart.cast_sheets_in_scene).
        _write(ep_dir / "scenes.json", {"plot": "", "scenes": out, "fixed": [],
                                        "cast": [{"name": p["name"], "appearance": p["description"]} for p in extras]})
        (ep_dir / "style.txt").write_text(STYLE, encoding="utf-8")
        (ep_dir / "quality.txt").write_text(QUALITY, encoding="utf-8")
        (ep_dir / "page_model.txt").write_text(PAGE_MODEL, encoding="utf-8")
        _write(ep_dir / "scene-meta.json", meta)
        summary.append({"n": n, "number": number, "run_id": ep_dir.name, "subtitle": ep["subtitle"], "scenes": len(out),
                        "pages": len(out) + 1, "messages": [ranges[0][0], ranges[-1][1]],
                        "missing": sum(len(v) for v in lost.values())})
    _write(run_dir / "plan.json", {"title": title, "episodes": summary, "continues": draft.get("continue_of") or "",
                                   "last_end": prev_end})


# ── 3. 시트 보관함 ─────────────────────────────────────────────────
# 주인공급(첫 주인공 + 함께 주인공 셋까지)은 시트 그림 + 사양(spec)이 짝이다 — 장 프롬프트가 사양 글을 읽는다.
# 조연은 그림 한 장이면 된다(원작 컬러 그림을 그대로 넣어도 된다). 그 조연 이름이 나오는 장에 참조로 붙는다.

def _sheet_slots(ep_dir: Path, char: dict, cast: list[dict] = ()) -> list[tuple[str, str, Path, Path]]:
    """(이름, 역할, png, spec) — 첫 주인공은 sheet.png, 나머지는 sheets/<이름>.png (run.sheet_paths 와 같은 자리)."""
    slots = [(char["name"], "main", ep_dir / "sheet.png", ep_dir / "sheet_spec.json")]
    for one in char.get("co") or []:
        slots.append((one["name"], "main", ep_dir / "sheets" / f"{one['name']}.png", ep_dir / "sheets" / f"{one['name']}.spec.json"))
    for one in cast:
        slots.append((one["name"], "cast", ep_dir / "sheets" / f"{one['name']}.png", ep_dir / "sheets" / f"{one['name']}.spec.json"))
    return slots


def _slots_of(ep_dir: Path) -> list[tuple[str, str, Path, Path]]:
    char = _read(ep_dir / "input.json")
    cast = (_read(ep_dir / "scenes.json").get("cast") or []) if (ep_dir / "scenes.json").exists() else []
    return _sheet_slots(ep_dir, char, [c for c in cast if isinstance(c, dict) and c.get("name")])


def library_root(runs_dir: Path) -> Path:
    return runs_dir / "_test-comic" / "sheets"


def _good_spec(path: Path) -> bool:
    try:
        v = _read(path)
    except Exception:  # noqa: BLE001 — 깨진 파일은 없는 것으로 본다
        return False
    return isinstance(v, dict) and bool(v.get("color_palette") or v.get("design_details"))


def use_library(parent: Path) -> None:
    """1화 run 에 보관함 시트를 깐다. 무엇을 썼는지 sheet_source.json 에 남긴다(화면이 보여 준다)."""
    ep1 = episode_dir(parent, 1)
    lib = library_root(parent.parent)
    source = {}
    for name, role, png, spec in _slots_of(ep1):
        folder = lib / _safe(name)
        same = folder / STYLE
        if role == "cast":  # 조연은 그림만 — 그림체가 달라도(원작 그림이어도) 그대로 붙인다
            pics = ([same / "sheet.png"] if (same / "sheet.png").is_file() else []) + sorted(folder.glob("*/sheet.png")) if folder.is_dir() else []
            if pics:
                png.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(pics[0], png)
                source[name] = "보관함 그림 그대로(조연)"
            else:
                source[name] = "시트 없음 — 생김새 글로만 그림(조연)"
            continue
        if (same / "sheet.png").is_file() and _good_spec(same / "spec.json"):
            png.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(same / "sheet.png", png)
            shutil.copy2(same / "spec.json", spec)
            source[name] = "보관함 시트 그대로"
            continue
        others = [s for s in sorted(folder.glob("*/spec.json")) if _good_spec(s)] if folder.is_dir() else []
        if others:
            spec.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(others[0], spec)  # 사양만 — 그림은 이 그림체로 새로 그린다
            source[name] = f"보관함 사양({others[0].parent.name})으로 새 그림체 시트"
        elif png.is_file() and spec.is_file():
            source[name] = "앞 화 시트 그대로"
        else:
            source[name] = "새로 만든 시트"
    _write(parent / "sheet_source.json", source)
    _write(parent / "people.json", people_of(parent))  # 화면이 인물별 시트 자리를 읽는다


def save_library(parent: Path) -> list[str]:
    """확인한 1화 시트를 보관함에 넣는다(같은 이름·그림체는 덮어쓴다). 조연은 그림만."""
    ep1 = episode_dir(parent, 1)
    saved = []
    for name, role, png, spec in _slots_of(ep1):
        if not png.is_file() or (role == "main" and not spec.is_file()):
            continue
        dest = library_root(parent.parent) / _safe(name) / STYLE
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copy2(png, dest / "sheet.png")
        if spec.is_file():
            shutil.copy2(spec, dest / "spec.json")
        saved.append(name)
    _write(parent / "sheet_saved.json", saved)
    return saved


def share_sheets(parent: Path) -> None:
    """1화 시트를 2화부터 그대로 쓴다 — 화마다 다시 뽑으면 인물이 달라진다."""
    ep1 = episode_dir(parent, 1)
    for ep in _read(parent / "plan.json")["episodes"][1:]:
        dest = parent.parent / ep["run_id"]
        for (_, _, png, spec), (_, _, png2, spec2) in zip(_slots_of(ep1), _slots_of(dest)):
            for a, b in ((png, png2), (spec, spec2)):
                if a.is_file():
                    b.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(a, b)


def people_of(parent: Path) -> list[dict]:
    """화면용 — 1화 run 기준 인물·역할·시트 자리."""
    ep1 = episode_dir(parent, 1)
    if not (ep1 / "input.json").exists():
        return []
    return [{"name": n, "role": r, "sheet": str(png.relative_to(ep1)), "has_sheet": png.is_file()}
            for n, r, png, _ in _slots_of(ep1)]
