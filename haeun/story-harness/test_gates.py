"""게이트·장부·판정 로직 직접 검증. API 없음."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))

import json
import story, webtoon, samples

fails = []


def ok(name, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   {extra}" if extra and not cond else ""))
    if not cond:
        fails.append(name)


# ---------------- P2 게이트 (story) ----------------
banned = ["그리고 나서", "그 다음", "그리고는"]

good = {"generative_check": [{"event": f"e{i}", "source": s}
                             for s in ("rule", "cost", "irony") for i in range(4)],
        "forbidden_subversion": ["꿈이었다", "사실 살아있었다"],
        # 장르는 배신하되 트로프는 지킨다 — 지킬 것과 뒤집을 축을 둘 다 적어야 한다
        "genre_promise": "빙의는 반드시 일어난다",
        "subversion_axis": "성별"}
ok("P2 게이트: 정상 통과", story.gate_p2(good, banned) == [])

few = dict(good, generative_check=good["generative_check"][:6])
ok("P2 게이트: generative_check 10개 미만 탈락", len(story.gate_p2(few, banned)) >= 1)

lopsided = {"generative_check": [{"event": f"e{i}", "source": "rule"} for i in range(12)],
            "forbidden_subversion": ["x"]}
r = story.gate_p2(lopsided, banned)
ok("P2 게이트: source 편중 탈락", any("cost" in f for f in r) and any("irony" in f for f in r))

no_fs = {k: v for k, v in good.items() if k != "forbidden_subversion"}
ok("P2 게이트: forbidden_subversion 없음 탈락",
   any("forbidden_subversion" in f for f in story.gate_p2(no_fs, banned)))

legacy = {"causal_chains": [{"effect": "a", "source": s, "connector": "그리고 나서"}
                            for s in ("rule", "cost", "irony")],
          "forbidden_subversion": ["x"]}
r = story.gate_p2(legacy, banned)
ok("P2 게이트: 구판 causal_chains 3개 기준 적용 + 접속사 탈락",
   any("나열 접속사" in f for f in r) and not any("3개 이상" in f for f in r), r)

# ---------------- P3 판정 (story) ----------------
allyes = {"checks": {k: {"verdict": "yes"} for k in
                     ("ea_stated", "sniping_verified", "coexistence_forced",
                      "engine_installed", "auto_event_generation", "drop_present")},
          "capture_test": "yes", "first_question": "왜 그런가?", "cliche_detected": None}
ok("P3: 전부 yes + capture + 질문 + 클리셰없음 -> 맛있음",
   story.summarize_p3(allyes)["verdict"] == "맛있음")

ok("P3: capture=no -> 보통",
   story.summarize_p3(dict(allyes, capture_test="no"))["verdict"] == "보통")
ok("P3: 클리셰 감지 -> 보통",
   story.summarize_p3(dict(allyes, cliche_detected="회귀물 반복"))["verdict"] == "보통")
ok("P3: first_question 이 문자열 'null' 이어도 빈 값 -> 보통",
   story.summarize_p3(dict(allyes, first_question="없음"))["verdict"] == "보통")
one_no = {"checks": dict(allyes["checks"], ea_stated={"verdict": "no"}),
          "capture_test": "yes", "first_question": "q", "cliche_detected": None,
          "target_stage": "P1"}
v = story.summarize_p3(one_no)
ok("P3: 게이트 항목 no 1개 -> 별로 + 탈락항목 + P1 라우팅",
   v["verdict"] == "별로" and v["failed"] == ["ea_stated"] and v["target_stage"] == "P1")

# sniping_verified 는 기본 경고 항목 — no 여도 게이트를 막지 않는다.
# 저격 구조의 검증 가능한 부분은 gate_p1 이 코드로 잡는다.
snipe_no = {"checks": dict(allyes["checks"], sniping_verified={"verdict": "no"}),
            "capture_test": "yes", "first_question": "q", "cliche_detected": None,
            "target_stage": "P1"}
v = story.summarize_p3(snipe_no)
ok("P3: 경고 항목 no 는 재생성을 부르지 않고 warned 에만 남는다",
   v["failed"] == [] and v["warned"] == ["sniping_verified"] and v["verdict"] == "맛있음", v)

# ---------------- P1 저격 구조 게이트 (story) ----------------
# 비주얼 훅은 아래 별도 절에서 본다. 저격 구조만 보려는 여기서는 통과하는 한 벌을
# 깔고 간다 — gate_p1 이 둘을 같이 판정하기 때문이다.
VISUAL_OK = {
    # gender 는 카드의 필수 칸이다. 여기서 빠지면 뒷단계가 성별을 짐작한다
    # (실제로 그렇게 여자 주인공이 이미지에서 남자로 그려졌다).
    "gender": "남성",
    "visual_hook": "은발이 눈을 반쯤 덮은, 웃지 않는데 입꼬리가 비대칭으로 올라간 남자",
    "appearance": {"hair": "은발 반묶음", "eyes": "회색 · 서늘한 눈매",
                   "build": "185 언저리, 마르고 어깨가 넓다",
                   "clothing": "검은 하이넥 코트에 회색 슬랙스",
                   "impression": "말 걸기 어려운 인상", "element": "먼지"},
    "appearance_en": ("A man in his late twenties, silver hair falling over half his "
                      "eyes, narrow grey eyes, lean tall frame, wears a black "
                      "high-collared coat with frayed cuffs, quiet unreadable air"),
    "design_details": ["왼쪽 소매 끝의 해진 실밥",
                       "목 뒤로 낮게 묶은 잔머리",
                       "오른손 검지의 낡은 은가락지"],
    "color_palette": {"hair": "silver white (#DCE0E4)", "eyes": "grey (#8A8A8A)",
                      "skin": "pale apricot (#F9E5D7)", "outfit_main": "black (#1C1B19)",
                      "outfit_sub": "grey (#6E6E6E)", "accent": "faded yellow (#D2BA72)"},
    "expression_set": [
        "기본 — 입을 다물고 시선만 정면에 둔다",
        "겁먹음 — 동공이 커지고 턱이 굳는다",
        "결의 — 눈썹이 내려가고 입술 선이 곧아진다",
        "안도 — 어깨가 내려가고 눈가가 풀린다",
        "당황 — 시선이 옆으로 흐르고 입이 벌어진다",
        "체념 — 눈꺼풀이 반쯤 내려오고 고개가 기운다",
    ],
    "visual_gap": "정면을 보는 외형은 압도적인데, 말을 걸면 소심함이 튀어나와 시선이 바닥으로 떨어진다",
}
CARD_OK = {
    "intro": "야근하던 말단 사원, 눈 떠보니 폐기 예정 기록보관소의 마지막 사서",
    "rank": "제3기록보관소 사서 (폐기 D-30)",
    "rank_irony": "폐기 D-30",
    "personality": "누구에게나 당당한데 한 사람 앞에서만 말끝을 흐린다",
    "quote": ("다들 내가 당당하다고들 하지. 그런데 있잖아, 아무도 안 볼 때 나는 늘 "
              "문 앞에서 한참을 서 있어. 그걸 본 사람은 딱 하나뿐이야."),
    "wish_fulfillment": "아무도 몰랐던 내 자리가 마지막에 가장 중요해지는 것",
    "relational_gap": {
        "to_everyone": "누구 앞에서도 물러서지 않는 당당함",
        "to_one_only": "그 사람 앞에서만 말끝이 흐려지고 시선이 바닥으로 간다",
        "anchor": "하연 — 폐기 명령서에 서명한 감사관",
        "exception_reason": "십 년 전 같은 보관소에서 그가 내 이름을 지워 준 적이 있다",
        "solo": False, "solo_reason": None,
    },
    # anchor 로 지목한 사람은 명부에 반드시 있어야 한다 — 조연은 캐릭터 시트가
    # 없어서 여기 적힌 것이 그림 단계가 가진 전부다.
    "supporting_cast": [
        {"name": "하연", "gender": "여성", "relation": "폐기 명령서에 서명한 감사관",
         "appearance": "짧게 친 검은 머리, 큰 키에 각진 어깨, 감사관 제복에 은테 안경",
         "role": "보관소를 닫으러 오지만 십 년 전 일을 혼자 기억하고 있다"},
    ],
    "fate_beats": [
        "당신은 아무도 찾지 않는 제3기록보관소의 마지막 사서입니다.",
        "그런데 오늘 아침, 30일 뒤 폐기한다는 명령서가 내려옵니다.",
        "서명한 사람은 십 년 전 당신의 이름을 지워 준 그 감사관입니다.",
        "그리고 지워진 이름을 되살릴 수 있는 사람은 당신뿐입니다.",
    ],
}
good_p1 = {"expectation_ea": "당당함으로 어떤 자리에서도 먼저 나설 것이다",
           "ea_key_word": "당당함",
           "b_trait_word": "소심함",
           "betrayal_attribute_b": "소심함 — 결정적인 순간마다 말끝을 흐리고 결정을 피한다",
           **CARD_OK, **VISUAL_OK}
ok("P1 게이트: 반의쌍 + 각 문장에 포함 + 행동 있음 -> 통과",
   story.gate_p1(good_p1) == [], story.gate_p1(good_p1))

ok("P1 게이트: ea_key_word 없음 -> 탈락",
   any("ea_key_word" in f for f in story.gate_p1(dict(good_p1, ea_key_word=""))))

ok("P1 게이트: 단순 부정형은 반의어가 아니다",
   any("단순 부정형" in f for f in story.gate_p1(dict(
       good_p1, b_trait_word="당당하지않음",
       betrayal_attribute_b="당당하지않음 — 매번 뒤로 물러선다"))))

ok("P1 게이트: 트레이트 단어가 B 문장에 없으면 탈락",
   any("betrayal_attribute_b 안에" in f for f in story.gate_p1(dict(
       good_p1, betrayal_attribute_b="결정적인 순간마다 말끝을 흐리고 결정을 피한다"))))

ok("P1 게이트: 트레이트 단어가 E(A) 문장에 없으면 탈락",
   any("expectation_ea 안에" in f for f in story.gate_p1(dict(
       good_p1, expectation_ea="누구보다 먼저 나설 것이다"))))

ok("P1 게이트: 트레이트 단어만 있고 행동이 없으면 탈락",
   any("구체적 행동" in f for f in story.gate_p1(dict(
       good_p1, betrayal_attribute_b="소심함"))))

inflected = dict(good_p1, expectation_ea="늘 당당하다",
                 betrayal_attribute_b="소심하여 매번 뒤로 물러서고 말을 삼킨다")
ok("P1 게이트: 어간이 달라도 활용형이면 포함으로 본다",
   story.gate_p1(inflected) == [], story.gate_p1(inflected))

ok("P1 게이트: 한 단어가 아니라 구절이면 탈락",
   any("한 단어여야" in f for f in story.gate_p1(dict(
       good_p1, b_trait_word="소심하고 겁이 많음"))))

# ---------------- P1 외형 고정 설계 게이트 (story) ----------------
#
# 컷마다 같은 인물이 나오려면 무엇이 고정인지가 카드에 못 박혀 있어야 한다.
# 비어 있는 칸은 이미지 모델이 매번 다르게 그린다 — 그 자리가 흔들리는 자리다.
B_WORD = good_p1["b_trait_word"]


def vis(**over):
    return dict(good_p1, **over)


ok("외형 게이트: 정상 통과", story.gate_visual(good_p1, B_WORD) == [],
   story.gate_visual(good_p1, B_WORD))

# -- appearance 칸
for slot in ("hair", "eyes", "build", "clothing", "impression", "element"):
    ap = dict(VISUAL_OK["appearance"])
    ap[slot] = ""
    ok(f"외형 게이트: appearance 의 {slot} 이 비면 탈락",
       any(slot in f for f in story.gate_visual(vis(appearance=ap), B_WORD)))

# -- appearance_en 은 이미지 생성기로 그대로 간다
ok("외형 게이트: appearance_en 에 한글이 섞이면 탈락",
   any("한글" in f for f in story.gate_visual(
       vis(appearance_en=VISUAL_OK["appearance_en"] + " 은발 남자"), B_WORD)))

# -- design_details
ok("외형 게이트: design_details 가 3개 미만이면 탈락",
   any("design_details" in f for f in story.gate_visual(
       vis(design_details=["왼쪽 소매 끝의 해진 실밥", "목 뒤로 낮게 묶은 잔머리"]),
       B_WORD)))
ok("외형 게이트: design_details 가 5개를 넘으면 탈락",
   any("이하여야" in f for f in story.gate_visual(
       vis(design_details=[f"왼쪽 소매의 표식 {i}" for i in range(6)]), B_WORD)))
for abstract in ("우아한 분위기", "신비로운 느낌", "차가운 아우라", "매력적인 실루엣"):
    ok(f"외형 게이트: design_details 의 추상어 '{abstract}' 탈락",
       any("추상어" in f for f in story.gate_visual(
           vis(design_details=VISUAL_OK["design_details"] + [abstract]), B_WORD)))
ok("외형 게이트: 위치·색·형태로 쓴 고정 요소는 통과",
   not any("추상어" in f for f in story.gate_visual(
       vis(design_details=["왼쪽 소매의 노란 반사띠", "목 뒤로 낮게 묶은 머리",
                           "오른쪽 눈썹을 가로지르는 짧은 흉터"]), B_WORD)))

# -- color_palette
for slot in ("hair", "eyes", "skin", "outfit_main", "outfit_sub", "accent"):
    pal = dict(VISUAL_OK["color_palette"])
    pal[slot] = ""
    ok(f"외형 게이트: color_palette 의 {slot} 이 비면 탈락",
       any(slot in f for f in story.gate_visual(vis(color_palette=pal), B_WORD)))

# -- expression_set : W7 컷 서술이 쓰는 어휘 목록이다
ok("외형 게이트: expression_set 이 6개가 아니면 탈락",
   any("expression_set" in f for f in story.gate_visual(
       vis(expression_set=VISUAL_OK["expression_set"][:4]), B_WORD)))
ok("외형 게이트: 감정 이름만 있으면 탈락 (무엇이 보이는지가 없다)",
   any("감정 이름뿐" in f for f in story.gate_visual(
       vis(expression_set=["기본", "불안", "결의", "안도", "당황", "체념"]), B_WORD)))

# -- visual_hook 평가어
for word in ("잘생긴", "예쁜", "매력적인", "카리스마"):
    ok(f"외형 게이트: visual_hook 의 평가어 '{word}' 탈락",
       any("평가어" in f for f in story.gate_visual(
           vis(visual_hook=f"{word} 남자가 은발을 쓸어 올리며 서 있다"), B_WORD)))

# -- 모의 카드도 같은 게이트를 통과해야 한다
mock_p1 = story.mock_payload("P1", "")
ok("외형 게이트: 모의 P1 카드가 통과한다",
   story.gate_visual(mock_p1, mock_p1["b_trait_word"]) == [],
   story.gate_visual(mock_p1, mock_p1["b_trait_word"]))

# -- 엔진 카드가 외형·표정을 싣는다 (7단계가 이걸 봐야 한다)
card = webtoon.build_engine_card(mock_p1, story.mock_payload("P2", ""), "한 줄", [])
ok("엔진 카드: 고정 요소가 실린다",
   "왼쪽 소매 끝이 해져 실밥이 나와 있다" in card)
ok("엔진 카드: 색 팔레트가 hex 로 실린다", "#D9C879" in card, card[card.find("[외형"):][:400])
ok("엔진 카드: 표정 어휘가 실린다",
   "[표정" in card and "동공이 커지고 턱이 굳는다" in card)
ok("엔진 카드: 영문 외형이 실린다", "silver" in card or "black hair" in card
   or "short black hair" in card)

# ---------------- 색 팔레트: 영문 이름 + hex (story) ----------------
#
# 이 값은 두 곳으로 그대로 나간다 — story-harness 의 시트 프롬프트와,
# webtoon-harness 의 컷 프롬프트(charsheet.py 의 PALETTE_HEAD)다.
# 한쪽만 형식이 다르면 같은 인물이 두 색으로 나온다.

ok("색 게이트: hex 가 있으면 통과", story.gate_visual(good_p1, B_WORD) == [])

for bad_color in ("밝은 금색", "gold", "노랑"):
    pal = dict(VISUAL_OK["color_palette"], hair=bad_color)
    ok(f"색 게이트: hex 없는 '{bad_color}' 탈락",
       any("hex 가 없습니다" in f for f in story.gate_visual(vis(color_palette=pal), B_WORD)))

# -- 한글 -> 영문 + hex 변환 (hex 가 없던 시절의 카드를 위해)
CASES = [
    ("밝은 금색", "bright gold"), ("먹색", "ink black"), ("창백한 살구", "pale apricot"),
    ("짙은 갈색", "deep brown"), ("어두운 남색", "dark navy"), ("연한 하늘색", "light sky blue"),
    ("바랜 노랑", "faded yellow"), ("은백", "silver white"),
]
for ko, want_en in CASES:
    text, note = story.normalize_color(ko)
    ok(f"색 변환: '{ko}' -> {want_en} + hex",
       text.startswith(want_en) and story.HEX_RE.search(text) and note is None, text)

ok("색 변환: 밝게 해도 흰색으로 포화되지 않는다",
   story.normalize_color("창백한 살구")[0] != "pale apricot (#FFFFFF)",
   story.normalize_color("창백한 살구")[0])

ok("색 변환: 이미 hex 면 그대로 둔다",
   story.normalize_color("bright gold (#F0C44C)") == ("bright gold (#F0C44C)", None))
ok("색 변환: 소문자 hex 를 대문자로",
   story.normalize_color("#e8712f")[0] == "#E8712F")
ok("색 변환: 3자리 hex 를 6자리로",
   story.normalize_color("#ABC")[0] == "#AABBCC")
ok("색 변환: hex 옆의 한글 이름을 영문으로 바꾸되 hex 는 카드 값을 살린다",
   story.normalize_color("밝은 금색 (#F0C44C)")[0] == "bright gold (#F0C44C)")

text, note = story.normalize_color("orange")
ok("색 변환: 영문인데 hex 가 없으면 경고", note is not None and "hex" in note)
text, note = story.normalize_color("형광 무지개색")
ok("색 변환: 모르는 한글 색은 원문을 남기고 경고",
   text == "형광 무지개색" and note is not None)

# -- 시트 조립이 정규화된 팔레트를 쓴다
old_card = dict(good_p1, color_palette={
    "hair": "먹색", "eyes": "짙은 갈색", "skin": "창백한 살구",
    "outfit_main": "회색", "outfit_sub": "검정", "accent": "바랜 노랑"})
src = story.charsheet_source(old_card)
ok("시트 조립: 한글 팔레트를 영문+hex 로 바꿔 넣는다",
   all(story.HEX_RE.search(v) for v in src["color_palette"].values()),
   src["color_palette"])
ok("시트 조립: 원본 값도 남긴다", src["color_palette_raw"]["hair"] == "먹색")

prompts = story.charsheet_prompts(src, "테스트 스타일")
ok("시트 프롬프트: 색이 한글로 나가지 않는다",
   not any(story.HANGUL_RE.search(line)
           for line in prompts["turnaround"].splitlines()
           if line.startswith("hair:")),
   [l for l in prompts["turnaround"].splitlines() if l.startswith("hair:")])
ok("시트 프롬프트: 세 장 모두 hex 를 담는다",
   all("#" in v for v in prompts.values()))

bad_card = dict(good_p1, color_palette=dict(VISUAL_OK["color_palette"],
                                            hair="형광 무지개색"))
ok("시트 조립: 못 바꾼 색은 경고로 남는다",
   any("color_palette.hair" in n for n in story.charsheet_source(bad_card)["palette_notes"]))

# ---------------- 장면 점검 11항목 (story) ----------------
idea = "장례식장에서만 진실을 말할 수 있는 변호사"
setting = "장례식장 진실 변호사 규칙"
clean = [
    {"text": '장례식장 문을 닫고 그는 유족의 부탁을 거절했다. "오늘은 말 못 합니다." 변호사의 목소리가 낮았다.',
     "choice": "거절했다", "source": "rule", "new_element": None},
    {"text": '진실을 아는 사람은 그뿐이었다. 그는 서류를 숨겼다. "제가 가져가겠습니다." 유족이 그를 붙잡았다.',
     "choice": "숨겼다", "source": "cost", "new_element": None},
    {"text": '빈소 밖에서 그는 거짓말했다. "저는 아무것도 모릅니다." 장례식장 조명이 꺼졌다.',
     "choice": "거짓말했다", "source": "irony", "new_element": None},
]
ok("장면점검: 정상 통과", story.check_scenes(clean, idea, setting) == [],
   story.check_scenes(clean, idea, setting))

prop = [dict(clean[0], text="복도에서 낡은 일기장을 발견했다. \"이게 뭐지.\" 그가 중얼거렸다.",
             choice="일기장을 발견한다")] + clean[1:]
names = [h["name"] for h in story.check_scenes(prop, idea, setting)]
ok("장면점검: 소품 구동 검출", "선택에서 시작" in names and "소품 구동" in names, names)

label = [dict(clean[0], text=clean[0]["text"] + " 그는 우스꽝스러운 춤을 췄다.")] + clean[1:]
ok("장면점검: 라벨 서술 검출",
   "라벨 서술" in [h["name"] for h in story.check_scenes(label, idea, setting)])

silent = [dict(clean[0], text="그는 거절했다. 문이 닫혔다. 아무 말도 오가지 않았다.")] + clean[1:]
ok("장면점검: 대사 없음 검출",
   "대사 없음" in [h["name"] for h in story.check_scenes(silent, idea, setting)])

same = [dict(c, source="rule") for c in clean]
ok("장면점검: 출처 단일 검출",
   "출처 단일" in [h["name"] for h in story.check_scenes(same, idea, setting)])

evap = [dict(c, text='그는 거절했다. "안 됩니다." 방이 조용해졌다.') for c in clean]
ok("장면점검: 설정 증발 검출",
   "설정 증발" in [h["name"] for h in story.check_scenes(evap, idea, setting)])

# ---- 9~11: '하지 마라' 가 아니라 '해야 한다'
# 1~8 을 다 통과하고도 아무 일 없는 원고가 나왔다. 실제로 그 원고가 걸려야 한다.
_p1s = {"name": "민시하",
        "supporting_cast": [{"name": "이태준"}, {"name": "임채연"}]}

_evade = [dict(clean[0], choice="장난스레 웃으며 확실하게 거리를 둔다")] + clean[1:]
ok("장면점검: 첫 장면이 회피로 끝나면 검출",
   "회피로 시작" in [h["name"] for h in story.check_scenes(_evade, idea, setting)])
# 거절·숨김·거짓말은 회피가 아니다 — 상대에게 무언가를 하는 행동이다.
ok("장면점검: 거절/숨김은 회피가 아니다",
   "회피로 시작" not in [h["name"] for h in story.check_scenes(clean, idea, setting)])

# 회피 판정: 다른 뜻으로 쓰인 낱말과 부정형에 속으면 잘 쓴 원고를 되돌리게 된다.
ok("회피 판정: 웃어넘기기는 회피다", story.evasive_words("웃으며 받아넘긴다"))
ok("회피 판정: 부정형은 회피가 아니다",
   story.evasive_words("피하지 않고 정면으로 맞선다") == [],
   story.evasive_words("피하지 않고 정면으로 맞선다"))
ok("회피 판정: 물러서지 않는다는 회피가 아니다",
   story.evasive_words("한 발도 물러서지 않는다") == [])
ok("회피 판정: '피해'(손해)에 걸리지 않는다",
   story.evasive_words("상대에게 피해를 감수하고 붙잡는다") == [],
   story.evasive_words("상대에게 피해를 감수하고 붙잡는다"))
ok("회피 판정: '선을 긋는다'는 상대를 향한 행동이라 회피가 아니다",
   story.evasive_words("그 자리에서 선을 긋는다") == [])
ok("회피 판정: 뒤에 진짜 회피가 또 나오면 잡는다",
   story.evasive_words("피하지 않는 척하다가 결국 자리를 피한다"),
   story.evasive_words("피하지 않는 척하다가 결국 자리를 피한다"))

# 본문은 성을 떼고 부른다. '시하' 를 못 알아보면 멀쩡한 훅을 잡는다.
ok("이름 줄임: 성을 뗀 형태도 같은 사람", story.name_forms("민시하") == ["민시하", "시하"])
ok("이름 줄임: 두 글자 이름은 그대로", story.name_forms("하연") == ["하연"])
ok("이름 줄임: 영문 이름은 자르지 않는다", story.name_forms("Alex") == ["Alex"])

_hook_bad = {"hook": "이태준이 시하의 생활기록부를 보게 된다면?"}
ok("장면점검: 훅이 조연으로 시작하면 검출",
   "훅의 주어" in [h["name"] for h in
                 story.check_scenes(clean, idea, setting, _p1s, _hook_bad)],
   story.check_scenes(clean, idea, setting, _p1s, _hook_bad))
ok("장면점검: 훅에 주인공이 없으면 검출",
   "훅의 주어" in [h["name"] for h in story.check_scenes(
       clean, idea, setting, _p1s, {"hook": "그 파일은 누가 꺼냈을까?"})])
ok("장면점검: 훅의 주어가 주인공이면 통과",
   "훅의 주어" not in [h["name"] for h in story.check_scenes(
       clean, idea, setting, _p1s,
       {"hook": "민시하는 이태준이 그걸 알고도 계속 밀어낼 수 있을까?"})])
# hook 이 없던 옛 실행도 그대로 다시 돌 수 있어야 한다.
ok("장면점검: 훅이 없으면 이 항목은 보지 않는다",
   "훅의 주어" not in [h["name"] for h in
                    story.check_scenes(clean, idea, setting, _p1s, {})])

_percept = [dict(c, changed="태준이 시하의 진짜 모습을 알게 된다") for c in clean]
ok("장면점검: 세 장면이 전부 인식 변화면 검출",
   "인식만 바뀜" in [h["name"] for h in story.check_scenes(_percept, idea, setting)])
_one_real = [dict(_percept[0]), dict(_percept[1]),
             dict(clean[2], changed="유족이 그를 고소하고 변호사 자격이 정지된다")]
ok("장면점검: 한 장면이라도 실제로 일어나면 통과",
   "인식만 바뀜" not in [h["name"] for h in
                      story.check_scenes(_one_real, idea, setting)])
ok("장면점검: changed 가 아예 없으면 이 항목은 보지 않는다",
   "인식만 바뀜" not in [h["name"] for h in story.check_scenes(clean, idea, setting)])

# ---------------- 4단계 게이트 (webtoon) ----------------
def arc(o, t, elems, n=3):
    return {"order": o, "title": f"A{o}", "arc_type": t,
            "premise_element_used": elems, "summary": "s",
            "estimated_episode_count": n, "opens": ["q"], "closes": []}

good_arcs = {"arcs": [arc(1, "전개", ["rule"]), arc(2, "반전", ["cost"]),
                      arc(3, "상승", ["irony"]), arc(4, "반전", ["rule"])]}
ok("4단계: 정상 통과", webtoon.gate_arcs(good_arcs) == [], webtoon.gate_arcs(good_arcs))

no_rev = {"arcs": [arc(1, "전개", ["rule"]), arc(2, "상승", ["cost"]),
                   arc(3, "상승", ["irony"]), arc(4, "해소", ["rule"])]}
ok("4단계: 반전 비율 1/3 미만 탈락",
   any("반전" in f for f in webtoon.gate_arcs(no_rev)))

one_elem = {"arcs": [arc(i, "반전" if i % 2 else "전개", ["rule"]) for i in range(1, 5)]}
r = webtoon.gate_arcs(one_elem)
ok("4단계: cost/irony 미사용 탈락",
   any("cost" in f for f in r) and any("irony" in f for f in r))

bad_n = {"arcs": [arc(1, "전개", ["rule"], 7), arc(2, "반전", ["cost"]),
                  arc(3, "상승", ["irony"]), arc(4, "반전", ["rule"])]}
ok("4단계: 화 수 2~5 위반 탈락",
   any("estimated_episode_count" in f for f in webtoon.gate_arcs(bad_n)))

ok("4단계: Arc 개수 4~6 위반 탈락",
   any("4~6" in f for f in webtoon.gate_arcs({"arcs": [arc(1, "반전", ["rule", "cost", "irony"])]})))

# ---- Arc 인물 역할 (#28) ----
#
# Arc 요약은 "무슨 일이 벌어지는가"만 말한다. 누가 그 일을 밀고 누가 막는지가
# 없으면 W5 가 매번 인물을 새로 배치하고, 주인공이 Arc 마다 다른 사람처럼 움직인다.
#
# ★ 가장 중요한 것: **cast_roles 가 없는 옛 run 은 아무것도 검사하지 않는다.**
#   이 칸이 생기기 전에 돌린 run 을 다시 돌려도 결과가 그대로여야 한다.
ok("4단계: cast_roles 가 없으면 검사하지 않는다 (옛 run 회귀)",
   webtoon.gate_arc_cast(good_arcs["arcs"]) == []
   and webtoon.gate_arcs(good_arcs) == [])

def _role(n, ch="달라진다"):
    return {"name": n, "role": "쫓는다", "change": ch}

def _cast(arcs, rows_by_order):
    return {"arcs": [dict(a, cast_roles=rows_by_order[a["order"]]) for a in arcs]}

_ok_cast = _cast(good_arcs["arcs"], {i: [_role("서지한"), _role("한다인")] for i in range(1, 5)})
ok("4단계: cast_roles 를 제대로 채우면 통과",
   webtoon.gate_arcs(_ok_cast) == [], webtoon.gate_arcs(_ok_cast))

_no_through = _cast(good_arcs["arcs"], {1: [_role("서지한")], 2: [_role("서지한")],
                                        3: [_role("딴사람")], 4: [_role("서지한")]})
ok("4단계: 모든 Arc 에 나오는 인물이 없으면 탈락 (주인공 실종)",
   any("모든 Arc" in f for f in webtoon.gate_arcs(_no_through)))

_blank = _cast(good_arcs["arcs"], {1: [{"name": "서지한", "role": "쫓는다", "change": ""}],
                                   2: [_role("서지한")], 3: [_role("서지한")],
                                   4: [_role("서지한")]})
ok("4단계: change 가 비면 탈락 (달라지는 게 없으면 그 Arc 에 있을 이유가 없다)",
   any("change" in f for f in webtoon.gate_arcs(_blank)))

_empty = _cast(good_arcs["arcs"], {1: [], 2: [_role("서지한")], 3: [_role("서지한")],
                                   4: [_role("서지한")]})
ok("4단계: cast_roles 가 빈 배열인 Arc 는 탈락",
   any("비어 있습니다" in f for f in webtoon.gate_arcs(_empty)))

# 전체 줄거리 지도에 주인공 변화 곡선이 실리는가.
_curve = [dict(a, cast_roles=[_role("서지한", f"{a['order']}번째로 달라진다")])
          for a in good_arcs["arcs"]]
_blk = webtoon.series_arc_block(_curve, _curve[1])
ok("4단계: 지도에 주인공 변화가 Arc 순서대로 실린다",
   _blk.count("서지한:") == 4 and "2번째로 달라진다" in _blk, _blk[:70])
ok("4단계: cast_roles 없으면 지도 출력이 예전 그대로",
   "서지한" not in webtoon.series_arc_block(good_arcs["arcs"], good_arcs["arcs"][1]))
ok("4단계: 관통 인물을 찾는다", webtoon._through_line(_curve) == "서지한"
   and webtoon._through_line(good_arcs["arcs"]) == "")

# ---- 행동의 이유가 화면에 있는가 (#29) ----
#
# 머릿속 설정으로만 성립하는 행동은 독자에게 개연성이 없다. 실제로 그렇게 나왔다
# — 인물이 갑자기 노래를 부르는데 왜 불러야 하는지가 어디에도 없었다.
ok("5단계: why_now 가 없는 옛 run 은 검사하지 않는다",
   webtoon.gate_why_now([{"order": 1, "summary": "s"}, {"order": 2}]) == [])
_why_ok = [{"order": 1, "why_now": {
    "action": "노래를 부른다", "reason": "동생 병원비가 오늘까지다",
    "shown_by": '컷2에 독촉 문자가 보이고, 컷3 대사 "오늘까지래"'}}]
ok("5단계: 행동·이유·화면이 다 있으면 통과", webtoon.gate_why_now(_why_ok) == [],
   webtoon.gate_why_now(_why_ok))
ok("5단계: why_now 자체가 비면 탈락",
   any("why_now 가 없습니다" in f for f in webtoon.gate_why_now([{"order": 1, "why_now": {}},
                                                                _why_ok[0]])))
ok("5단계: reason 이 비면 탈락",
   any("reason" in f for f in webtoon.gate_why_now(
       [{"order": 1, "why_now": {"action": "a", "reason": "", "shown_by": "컷2 대사"}}])))
# 화면 밖을 가리키는 답은 답이 아니다 — 독자는 설정집을 안 읽는다.
for _dodge in ("설정상 가수 지망생이라서", "앞 화에서 설명했다", "이미 설명한 대로"):
    ok(f"5단계: shown_by 가 화면 밖을 가리키면 탈락 ({_dodge[:8]}…)",
       any("화면 밖" in f for f in webtoon.gate_why_now(
           [{"order": 1, "why_now": {"action": "a", "reason": "r", "shown_by": _dodge}}])))

# ---- 오래 묵은 질문 (#30) ----
#
# 상환 일정을 미리 짜지 않는 대신 **나이**로 본다. 개수 상한과 최근 상환 여부만으로는
# 오래 묵은 질문 하나가 안 잡힌다 — 3화에 연 질문이 12화까지 열려 있으면 독자는 잊는다.
_led = webtoon.Ledger("주인공은 왜 돌아왔는가", cap=5)
_led.open("흉터의 정체는?", "mystery", 1, 2)
_led.open("오늘 밤 무슨 일이?", "suspense", 2, 8)
_q = _led.open("갚을 것", "mystery", 2, 7)
_led.close(_q.id, 2, 8, False)
ok("장부: 오래 묵은 질문을 경고한다",
   any("장기 미상환" in w for w in _led.warnings(9)), _led.warnings(9))
ok("장부: 아직 안 묵었으면 조용하다",
   not any("장기 미상환" in w for w in _led.warnings(4)), _led.warnings(4))
_snap = json.loads(_led.snapshot(9, hide_ids=True))
_by_text = {o["text"]: o for o in _snap["open"]}
ok("장부: 열린 질문마다 몇 화째인지 실린다",
   _by_text["흉터의 정체는?"]["openFor"] == 7
   and _by_text["오늘 밤 무슨 일이?"]["openFor"] == 1)
ok("장부: 오래 묵은 것만 표시된다",
   _by_text["흉터의 정체는?"].get("stale") is True
   and "stale" not in _by_text["오늘 밤 무슨 일이?"])
ok("장부: 엔진급 질문에는 나이를 안 붙인다",
   "openFor" not in json.loads(_led.snapshot(9))["engine_question"])

# ---------------- 질문 장부 (webtoon) ----------------
led = webtoon.Ledger("그는 끝내 무엇을 택하는가", cap=5)
ok("장부: EQ 는 열린 부채로 세지 않는다", led.open_items == [])
q1 = led.open("왜 그런가", "mystery", 1, 1, planned=3)
q2 = led.open("어떻게 되는가", "suspense", 1, 1)
ok("장부: id 발급", (q1.id, q2.id) == ("Q-1", "Q-2"))
ok("장부: 없는 id 상환 거부", led.close("Q-99", 1, 2, True) is False)
ok("장부: 상환 성공", led.close("Q-2", 1, 2, True) is True)
ok("장부: 중복 상환 거부", led.close("Q-2", 1, 3, True) is False)

for i in range(6):
    led.open(f"q{i}", "suspense", 2, 3)
ok("장부: 떡밥 과부하 경고", any("과부하" in w for w in led.warnings(3)))
# 연체 경고는 없앴다. 몇 화에 갚겠다는 계획을 미리 세우지 않기 때문이다 —
# 연재는 앞 화를 보고 다음 화를 정하는 것이지 17화치 상환 일정을 1화에서 짜는
# 것이 아니다. 남은 신호는 "지금 몇 개가 열려 있나" 하나뿐이다.
ok("장부: 연체 경고는 더 이상 없다 (사전 계획을 안 하므로)",
   not any("연체" in w for w in led.warnings(6)), led.warnings(6))

led2 = webtoon.Ledger("EQ", cap=5)
led2.open("a", "suspense", 1, 1)
ok("장부: 미스터리 박스 경고 (최근 3화 상환 0건)",
   any("미스터리 박스" in w for w in led2.warnings(5)))

# ---------------- 장부: 확정된 사실 (facts) ----------------
led3 = webtoon.Ledger("EQ", cap=5)
f1 = led3.add_fact("3화에서 왼손에 흉터가 생겼다", 3)
f2 = led3.add_fact("주인공은 커피를 못 마신다", 1)
ok("장부: fact id 발급", (f1.id, f2.id) == ("F-1", "F-2"))
d3 = led3.as_dict()
ok("장부: as_dict 에 facts 포함", len(d3.get("facts") or []) == 2)
led3b = webtoon.Ledger.from_dict(d3)
ok("장부: facts 라운드트립",
   [(f.id, f.text, f.established_episode) for f in led3b.facts]
   == [("F-1", "3화에서 왼손에 흉터가 생겼다", 3), ("F-2", "주인공은 커피를 못 마신다", 1)])
snap3 = json.loads(led3b.snapshot(4))
ok("장부: snapshot 에 established_facts 포함", len(snap3.get("established_facts") or []) == 2)

old_ledger = {"cap": 40, "questions": [
    {"id": "EQ", "text": "엔진 질문", "type": "engine",
     "openedAt": {"arc": 0, "episode": 0}, "closedAt": None,
     "plannedPayoffEpisode": None, "isEngine": True, "isBetrayal": None},
]}
led_old = webtoon.Ledger.from_dict(old_ledger)
ok("장부: facts 키 없는 예전 ledger.json 도 그대로 읽힌다 (하위호환)",
   led_old.facts == [] and led_old.fact_seq == 0)

# ---------------- 본문 -> id 매칭 (5단계 정합성) ----------------
def led_with(*texts):
    L = webtoon.Ledger("그는 끝내 무엇을 택하는가", cap=5)
    for t in texts:
        L.open(t, "suspense", 1, 1)
    return L


def setting(**over):
    """무대 픽스처. 이게 없으면 컷 단계가 그릴 것이 얼굴밖에 없다."""
    s = {"place": "소방서 3층 휴게실", "time": "새벽 4시",
         "weather": "비가 그친 직후, 창밖 바닥이 젖어 있다",
         "light": "천장 형광등 하나. 그림자가 발밑에 짧게 진다",
         "props": ["엎어 둔 머그컵", "벽에 걸린 낡은 시계"],
         "movement": "윤아가 문 앞에서 들어와 창가로 걸어간다"}
    s.update(over)
    return s


def episode(title="t", summary="s", opened=None, closed=None,
            fired=("rule",), stinger_text="hook", link=None, stage=None):
    return {"title": title, "summary": summary,
            "setting": setting() if stage is None else stage,
            "questions_opened": opened if opened is not None else
                                [{"text": "이 화가 여는 질문", "type": "suspense"}],
            "questions_closed": closed if closed is not None else [],
            "engine_fired": list(fired),
            "stinger": {"text": stinger_text,
                        "linked_question_text": link or "이 화가 여는 질문"}}


LED_Q = "그 편지는 누가 보냈는가"

# 1) order 는 배열 순서대로 코드가 붙인다 — 모델이 쓴 order 는 무시된다
pay = {"episodes": [dict(episode(), order=7), dict(episode(), order=99)]}
webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: order 를 배열 순서대로 1부터 부여 (모델 값 무시)",
   [e["order"] for e in pay["episodes"]] == [1, 2])

# 2) temp_id 는 코드가 순서대로 부여한다
pay = {"episodes": [episode(opened=[{"text": "질문 하나"}, {"text": "질문 둘"}])]}
webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: temp_id 를 코드가 부여",
   [q["temp_id"] for q in pay["episodes"][0]["questions_opened"]] == ["T1-1", "T1-2"])

# 3) 상환은 본문으로 장부와 연결된다
pay = {"episodes": [episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
res = webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: 상환 본문 -> 장부 id 연결",
   pay["episodes"][0]["questions_closed"][0].get("ledger_id") == "Q-1" and res.closed == 1)

pay = {"episodes": [episode(closed=[{"question_text": "그 편지는 도대체 누가 보낸 것인가",
                                     "answer": "a"}])]}
webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: 표현이 조금 달라도 같은 질문으로 연결",
   pay["episodes"][0]["questions_closed"][0].get("ledger_id") == "Q-1")

# 4) 실패했던 네 가지가 전부 '무시'로 처리되고 게이트를 막지 않는다
for name, closed in [
        ("엔진급 질문(EQ) 지목", [{"question_text": "그는 끝내 무엇을 택하는가"}]),
        ("출력 예시 id 베끼기", [{"ledger_id": "Q-3"}]),
        ("빈 문자열", [{"ledger_id": ""}]),
        ("장부에 없는 본문", [{"question_text": "전혀 상관없는 다른 문장이 여기 들어간다"}])]:
    L = led_with(LED_Q)
    pay = {"episodes": [episode(closed=list(closed)),
                        episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
    res = webtoon.assign_ids(pay, L)
    gate = webtoon.gate_episodes_shape(pay, L, {"estimated_episode_count": 2}, res)
    ok(f"assign_ids: {name} 은 무시할 뿐 재생성 사유가 아니다",
       pay["episodes"][0]["questions_closed"] == [] and len(res.ignored) == 1
       and gate == [], (res.ignored, gate))

# 5) 스팅어는 본문으로 연결되고, 못 찾으면 그 화가 연 첫 질문으로 떨어진다
pay = {"episodes": [episode(opened=[{"text": "이 화가 여는 질문"}],
                            link="이 화가 여는 질문")]}
webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: 스팅어 본문 -> temp_id 연결",
   pay["episodes"][0]["stinger"]["linked_question_id"] == "T1-1")

pay = {"episodes": [episode(opened=[{"text": "이 화가 여는 질문"}], link="T2")]}
res = webtoon.assign_ids(pay, led_with(LED_Q))
ok("assign_ids: 유령 id 를 가리켜도 실패시키지 않고 첫 질문으로 잇는다",
   pay["episodes"][0]["stinger"]["linked_question_id"] == "T1-1"
   and res.fallback_stingers == ["1번째 화"])

# 6) 같은 Arc 의 앞선 화가 연 질문을 뒤 화가 닫을 수 있다
pay = {"episodes": [episode(opened=[{"text": "앞 화가 연 질문"}]),
                    episode(closed=[{"question_text": "앞 화가 연 질문", "answer": "a"}])]}
res = webtoon.assign_ids(pay, webtoon.Ledger("EQ", cap=5))
ok("assign_ids: 앞선 화가 연 질문을 뒤 화가 닫으면 임시 id 로 연결",
   pay["episodes"][1]["questions_closed"][0].get("ledger_id") == "T1-1")

# 7) 같은 화가 연 질문은 그 화가 닫을 수 없다
pay = {"episodes": [episode(opened=[{"text": "이 화가 연 질문"}],
                            closed=[{"question_text": "이 화가 연 질문"}])]}
res = webtoon.assign_ids(pay, webtoon.Ledger("EQ", cap=5))
ok("assign_ids: 같은 화가 연 질문은 같은 화가 닫지 못한다",
   pay["episodes"][0]["questions_closed"] == [])

# ---------------- 5단계 게이트 ----------------
led3 = led_with(LED_Q)
arc1 = {"estimated_episode_count": 1}
good_ep = {"episodes": [episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
res3 = webtoon.assign_ids(good_ep, led3)
ok("5단계: 정상 통과",
   webtoon.gate_episodes_shape(good_ep, led3, arc1, res3) == [],
   webtoon.gate_episodes_shape(good_ep, led3, arc1, res3))

bad = {"episodes": [episode(fired=())]}
r3 = webtoon.assign_ids(bad, led3)
ok("5단계: engine_fired 빈 배열 탈락",
   any("engine_fired" in f for f in
       webtoon.gate_episodes_shape(bad, led3, None, r3)))

bad = {"episodes": [episode(stinger_text="")]}
r3 = webtoon.assign_ids(bad, led3)
ok("5단계: stinger.text 가 비면 탈락",
   any("stinger.text" in f for f in
       webtoon.gate_episodes_shape(bad, led3, None, r3)))

# Arc 단위 상환 규칙
none_closed = {"episodes": [episode(), episode()]}
r3 = webtoon.assign_ids(none_closed, led3)
ok("5단계: 장부에 열린 질문이 있는데 Arc 안에서 한 번도 안 닫으면 탈락",
   any("어느 화도 상환하지 않았습니다" in f for f in
       webtoon.gate_episodes_shape(none_closed, led3, None, r3)))

one_closed = {"episodes": [episode(),
                           episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
r3 = webtoon.assign_ids(one_closed, led3)
ok("5단계: Arc 안에서 한 화만 닫으면 통과 (화 단위 강제 없음)",
   webtoon.gate_episodes_shape(one_closed, led3, None, r3) == [],
   webtoon.gate_episodes_shape(one_closed, led3, None, r3))

empty_led = webtoon.Ledger("EQ", cap=5)
first_arc = {"episodes": [episode(), episode()]}
r3 = webtoon.assign_ids(first_arc, empty_led)
ok("5단계: 장부가 비면 상환을 강제하지 않음 (첫 Arc)",
   webtoon.gate_episodes_shape(first_arc, empty_led, None, r3) == [])

short = {"episodes": [episode()]}
r3 = webtoon.assign_ids(short, empty_led)
ok("5단계: Arc 계획보다 화가 적으면 탈락 (불합격 화만 재제출하는 것 차단)",
   any("3화로 계획" in f for f in webtoon.gate_episodes_shape(
       short, empty_led, {"estimated_episode_count": 3}, r3)))

# ---------------- 조연 인물 카드 (5단계) ----------------
# 조연에게는 캐릭터 시트가 없다. new_cast 에 적힌 것이 그림 단계가 가진 전부라,
# 비면 컷마다 다른 사람이 된다 (성별까지 바뀐다). 실제로 그 일이 나서 게이트를
# 넣었으므로, 게이트가 실제로 잡는지도 테스트로 남긴다.
FULL_CARD = {"name": "하윤재", "note": "같은 학과 남후배",
             "gender": "male",
             "appearance": "큰 키에 마른 체형, 짧고 헝클어진 검은 머리, 처진 눈매",
             "outfit": "회색 티셔츠에 연청 데님 자켓, 검은 슬랙스, 흰 스니커즈",
             "personality": "반 박자 늦게 반응하고 말할 때 눈을 못 맞춘다"}


def with_cast(*rows):
    """빈 장부로 만든다 — 여기서 보려는 것은 인물 카드 규칙뿐이고,
    열린 질문이 있으면 Arc 상환 규칙이 같이 걸려 무엇 때문에 탈락했는지 흐려진다."""
    pay = {"episodes": [dict(episode(), new_cast=[dict(r) for r in rows])]}
    return pay, webtoon.assign_ids(pay, empty_led)


pay, res = with_cast(FULL_CARD)
ok("조연 카드: 네 항목이 다 차 있으면 통과",
   webtoon.gate_episodes_shape(pay, empty_led, None, res) == [],
   webtoon.gate_episodes_shape(pay, empty_led, None, res))

for key, label in webtoon.CAST_FIELD_LABEL.items():
    pay, res = with_cast({**FULL_CARD, key: ""})
    hits = webtoon.gate_episodes_shape(pay, empty_led, None, res)
    ok(f"조연 카드: {label}({key}) 가 비면 탈락",
       any("하윤재" in f and label in f for f in hits), hits)

pay, res = with_cast({"name": "이름만 있는 사람"})
hits = webtoon.gate_episodes_shape(pay, empty_led, None, res)
ok("조연 카드: 이름만 있으면 네 항목이 전부 실패 사유에 적힌다",
   len(hits) == 1 and all(l in hits[0] for l in webtoon.CAST_FIELD_LABEL.values()), hits)

# 이름 없는 줄까지 세우면 모델이 빈 객체 하나 때문에 화 전체를 다시 쓴다.
pay, res = with_cast({"name": "  ", "note": "지나가는 군중"})
ok("조연 카드: 이름이 없는 줄은 건너뛴다 (재생성 사유가 아니다)",
   webtoon.gate_episodes_shape(pay, empty_led, None, res) == [])

# 명부에 쌓여야 다음 화가 같은 사람을 그린다. 쌓기만 하고 안 보여 주면 소용없다.
book = webtoon.SeriesState(run_id="t")
book.add(1, "1", dict(episode(), new_cast=[dict(FULL_CARD)]))
stored = book.cast[0]
ok("조연 카드: 명부가 네 항목을 그대로 쌓는다",
   all(stored.get(k) == FULL_CARD[k] for k in webtoon.CAST_FIELDS), stored)

brief = book.brief(empty_led)
ok("조연 카드: 다음 화 프롬프트에 항목별로 되돌려 준다",
   all(f"{l}(고정)" in brief for l in webtoon.CAST_FIELD_LABEL.values())
   and FULL_CARD["outfit"] in brief, brief)

# ---------------- 7단계 게이트 (내용) ----------------
def cuts(n=10, stinger=None, engine=(2, 6), reader=(3,), **over):
    """게이트를 통과하는 컷 한 벌 + 덮어쓸 컷 (c3={"size": "wide"} 꼴)."""
    payload = story.mock_cuts(n)
    for c in payload["cuts"]:
        c["reader_only"] = c["cut_number"] in reader
    payload["engine_cut_refs"] = [{"element": "rule", "cut_number": engine[0]},
                                  {"element": "cost", "cut_number": engine[1]}]
    payload["stinger_cut_number"] = stinger or n
    for num, patch in over.items():
        payload["cuts"][int(num[1:]) - 1].update(patch)
    return payload


epi = {"engine_fired": ["rule", "cost"]}
ok("7단계: 정상 통과", webtoon.gate_cuts(cuts(), epi, True) == [],
   webtoon.gate_cuts(cuts(), epi, True))

ok("7단계: 엔진 컷이 마지막 1/4 에 몰리면 탈락",
   any("마지막 1/4" in f for f in webtoon.gate_cuts(cuts(engine=(9, 10)), epi, True)))
ok("7단계: 스팅어가 마지막 컷이 아니면 탈락",
   any("마지막 컷" in f for f in webtoon.gate_cuts(cuts(stinger=5), epi, True)))
ok("7단계: dramatic_irony 있는데 독자우위 컷 없으면 탈락",
   any("reader_only" in f for f in webtoon.gate_cuts(cuts(reader=()), epi, True)))
ok("7단계: dramatic_irony 없으면 독자우위 컷 불필요",
   webtoon.gate_cuts(cuts(reader=()), epi, False) == [])
ok("7단계: 컷 8개 미만 탈락",
   any("8~16" in f for f in webtoon.gate_cuts(cuts(6, engine=(2, 4), reader=(3,)), epi, True)))
ok("7단계: 존재하지 않는 컷 번호 참조 탈락",
   any("존재하지 않는" in f for f in webtoon.gate_cuts(cuts(engine=(2, 99)), epi, True)))

# ---------------- 6단계 판독 ----------------
eps = [{"order": 1, "questions_opened": [{"temp_id": "T1", "text": "q1", "type": "mystery"}]},
       {"order": 2, "questions_opened": []}]
review = {"per_episode": [{"order": 1, "pass": True, "violations": []},
                          {"order": 2, "pass": False,
                           "violations": [{"check": "debt_closed", "detail": "d",
                                           "fix_directive": "f"}]}],
          "verified_question_types": [{"episode_order": 1, "temp_id": "T1",
                                       "type": "suspense"}],
          "verified_closures": [],
          "eq_untouched": [{"episode_order": 1, "violated": False, "detail": None},
                           {"episode_order": 2, "violated": True, "detail": "중심질문 닫힘"}]}
v = webtoon.summarize_review(review, eps)
ok("6단계: 화별 pass/fail 분리", v["passed"] == [1] and v["failed"] == [2])
ok("6단계: 치명 위반(eq) 검출", len(v["eq_violations"]) == 1)
ok("6단계: 재생성 지시 조립", "debt_closed" in v["directives"][2][0])

led4 = webtoon.Ledger("EQ", cap=5)
webtoon.commit_ledger(led4, 1, eps, v, 0)
ok("장부: 검사자가 판정한 유형이 들어간다 (작가 신고 mystery -> suspense)",
   led4.open_items[0].type == "suspense" and len(led4.open_items) == 1)

# ---------------- 6단계 -> 장부 반영 (코드가 맞춰 둔 상환만) ----------------
led5 = led_with(LED_Q)
pay5 = {"episodes": [episode(opened=[{"text": "1화가 여는 질문"}],
                             closed=[{"question_text": LED_Q, "answer": "a"}]),
                     episode(opened=[{"text": "2화가 여는 질문"}],
                             closed=[{"question_text": "1화가 여는 질문",
                                      "answer": "b"}])]}
webtoon.assign_ids(pay5, led5)
eps5 = pay5["episodes"]
view5 = webtoon.summarize_review({
    "per_episode": [{"order": 1, "pass": True, "violations": []},
                    {"order": 2, "pass": True, "violations": []}],
    "verified_question_types": [{"episode_order": 1, "temp_id": "T1-1", "type": "suspense"},
                                {"episode_order": 2, "temp_id": "T2-1", "type": "mystery"}],
    "verified_closures": [{"episode_order": 1, "ledger_id": "Q-1", "is_betrayal": True},
                          {"episode_order": 2, "ledger_id": "T1-1", "is_betrayal": False}],
    "eq_untouched": [],
}, eps5)
webtoon.commit_ledger(led5, 1, eps5, view5, 0)
closed_ids = sorted(q.id for q in led5.closed_items)
ok("장부: 장부 질문 상환 반영", "Q-1" in closed_ids)
ok("장부: 같은 Arc 앞선 화가 연 질문의 상환도 실제 id 로 반영 (T1-1 -> Q-2)",
   "Q-2" in closed_ids and led5.get("Q-2").text == "1화가 여는 질문", closed_ids)
ok("장부: 스팅어의 임시 id 도 실제 장부 id 로 바뀐다",
   eps5[0]["stinger"]["linked_question_id"] == "Q-2")

led6 = led_with(LED_Q)
pay6 = {"episodes": [episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
webtoon.assign_ids(pay6, led6)
view6 = webtoon.summarize_review({
    "per_episode": [{"order": 1, "pass": True, "violations": []}],
    "verified_question_types": [],
    "verified_closures": [{"episode_order": 1, "ledger_id": "Q-9", "is_betrayal": True}],
    "eq_untouched": [],
}, pay6["episodes"])
webtoon.commit_ledger(led6, 1, pay6["episodes"], view6, 0)
ok("장부: 검사자가 id 를 틀리게 옮겨도 본문으로 다시 맞춘다",
   [q.id for q in led6.closed_items] == ["Q-1"])

led7 = led_with(LED_Q)
pay7 = {"episodes": [episode(closed=[{"question_text": LED_Q, "answer": "a"}])]}
webtoon.assign_ids(pay7, led7)
view7 = webtoon.summarize_review({
    "per_episode": [{"order": 1, "pass": True, "violations": []}],
    "verified_question_types": [], "verified_closures": [], "eq_untouched": [],
}, pay7["episodes"])
webtoon.commit_ledger(led7, 1, pay7["episodes"], view7, 0)
ok("장부: 검사자가 인정하지 않은 상환은 반영되지 않는다",
   led7.closed_items == [])

# ---------------- P1 비주얼 훅 게이트 (story) ----------------
VIS_OK = dict(good_p1)
ok("P1 비주얼: 정상 통과", story.gate_p1(VIS_OK) == [], story.gate_p1(VIS_OK))

r = story.gate_p1(dict(VIS_OK, visual_hook="아주 잘생기고 매력적인 남자의 얼굴"))
ok("P1 비주얼: 평가어(잘생김) 탈락", any("평가어" in f for f in r))

r = story.gate_p1(dict(VIS_OK, visual_hook="누구든 한 번은 돌아보게 되는 사람이다"))
ok("P1 비주얼: 그릴 자리 없는 훅 탈락", any("눈이 걸릴 자리" in f for f in r))

r = story.gate_p1(dict(VIS_OK, appearance_en=VIS_OK["appearance_en"] + " 그리고 은발이다"))
ok("P1 비주얼: appearance_en 한글 혼입 탈락", any("한글" in f for f in r))

r = story.gate_p1(dict(VIS_OK, appearance_en=(
    "A man in his late twenties with silver hair over his eyes, and a very quiet "
    "unreadable air about him that never quite settles into anything readable")))
ok("P1 비주얼: appearance_en 에 체형·복장 없으면 탈락",
   any("build" in f and "clothing" in f for f in r))

r = story.gate_p1(dict(VIS_OK, appearance={"hair": "은발", "eyes": "", "impression": "",
                                           "element": "먼지"}))
ok("P1 비주얼: appearance 네 칸 중 빈 칸이 있으면 탈락",
   any("appearance 의" in f for f in r))

r = story.gate_p1(dict(VIS_OK, visual_gap="외형과 성격이 정반대라서 사람들이 자주 놀란다"))
ok("P1 비주얼: visual_gap 이 B 와 안 묶이면 탈락",
   any("b_trait_word" in f for f in r))

r = story.gate_p1({k: v for k, v in VIS_OK.items() if k != "visual_hook"})
ok("P1 비주얼: visual_hook 없음 탈락", any("visual_hook 이 비어있" in f for f in r))


# ---------------- P1 상업 훅 공식 게이트 (story) ----------------
def card(**over):
    return dict(good_p1, **over)


ok("P1 카드: 정상 통과", story.gate_p1(card()) == [], story.gate_p1(card()))

r = story.gate_p1(card(intro="강력한 힘을 가진 신비로운 소녀의 이야기"))
ok("P1 카드: intro 에 전환어 없으면 탈락", any("전환어가 없습니다" in f for f in r))

for pivot in ("그런데", "하필", "알고 보니", "눈 떠보니", "인 줄 알았는데"):
    r = story.gate_p1(card(intro=f"평범한 회사원이었던 당신, {pivot} 제국의 마지막 검사"))
    ok(f"P1 카드: 전환어 '{pivot}' 통과", not any("전환어" in f for f in r), r)

r = story.gate_p1(card(intro="회사원, " + "그런데 " * 30 + "검사"))
ok("P1 카드: intro 가 길면 탈락", any("70자" in f for f in r))

r = story.gate_p1(card(rank="공작가의 장녀", rank_irony=""))
ok("P1 카드: rank_irony 없으면 탈락", any("rank_irony" in f for f in r))

r = story.gate_p1(card(rank="공작가의 장녀", rank_irony="3권에서 사망 예정"))
ok("P1 카드: rank 안에 아이러니가 없으면 탈락", any("들어 있지 않습니다" in f for f in r))

rel = dict(good_p1["relational_gap"], anchor="그 사람")
r = story.gate_p1(card(relational_gap=rel))
ok("P1 카드: anchor 가 이름 없는 대명사면 탈락", any("공동 주인공급" in f for f in r))

rel = dict(good_p1["relational_gap"], exception_reason="운명이라서")
r = story.gate_p1(card(relational_gap=rel))
ok("P1 카드: exception_reason 이 '운명이라서' 면 탈락", any("구체적 사연" in f for f in r))

rel = dict(good_p1["relational_gap"], to_everyone="")
r = story.gate_p1(card(relational_gap=rel))
ok("P1 카드: relational_gap 빈 칸 탈락", any("to_everyone" in f for f in r))

solo_ok = card(relational_gap={"solo": True,
                               "solo_reason": "이 캐릭터의 낙차는 기억 안에서만 닫힌다"})
ok("P1 카드: 1인 완결형(solo) 허용", story.gate_p1(solo_ok) == [], story.gate_p1(solo_ok))

r = story.gate_p1(card(relational_gap={"solo": True, "solo_reason": ""}))
ok("P1 카드: solo 인데 이유 없으면 탈락", any("solo_reason" in f for f in r))

r = story.gate_p1(card(fate_beats=good_p1["fate_beats"][:3]))
ok("P1 카드: fate_beats 가 4개가 아니면 탈락", any("fate_beats 가 3개" in f for f in r))

beats = list(good_p1["fate_beats"])
beats[-1] = "그래서 당신은 끝까지 살아남기로 결심합니다."
r = story.gate_p1(card(fate_beats=beats))
ok("P1 카드: fate_beats ④ 가 각오면 탈락", any("각오로 끝납니다" in f for f in r))

r = story.gate_p1(card(wish_fulfillment=""))
ok("P1 카드: wish_fulfillment 없으면 탈락", any("wish_fulfillment" in f for f in r))

r = story.gate_p1(card(quote="짧은 말."))
ok("P1 카드: quote 가 3단이 안 되면 탈락", any("quote" in f for f in r))

# ---------------- P2 반전 축 (story) ----------------
p2_ok = dict(good, genre_promise="빙의는 반드시 일어난다", subversion_axis="성별")
ok("P2 게이트: 반전 축·약속 있으면 통과", story.gate_p2(p2_ok, banned) == [])

r = story.gate_p2(dict(p2_ok, subversion_axis="분위기"), banned)
ok("P2 게이트: 모르는 반전 축 탈락", any("subversion_axis" in f for f in r))

r = story.gate_p2(dict(p2_ok, genre_promise=""), banned)
ok("P2 게이트: genre_promise 없으면 탈락", any("genre_promise" in f for f in r))

ok("P2: 반전 축 이름을 느슨하게 받아준다",
   story.normalize_axis("gender") == "성별" and story.normalize_axis("신분") == "위상"
   and story.normalize_axis("") == "")

# ---------------- P3 skip 판정 (story) ----------------
p3_skip = story.mock_payload("P3", "")
p3_skip["checks"]["relational_gap_exists"] = {"verdict": "skip", "reason": "1인 완결형"}
v = story.summarize_p3(p3_skip)
ok("P3: skip 은 통과도 탈락도 아니다",
   v["skipped"] == ["relational_gap_exists"] and v["failed"] == []
   and v["verdict"] == "맛있음", v)

# ---------------- 샘플 카드 (samples) ----------------
ok("샘플: 장르 4종이 다 읽힌다", set(samples.available()) == set(samples.GENRES))
for key in samples.available():
    cards = samples.load(key)
    ok(f"샘플 {key}: 6장 · 필수 칸이 다 있다",
       len(cards) == 6 and all(
           c.get("intro") and c.get("rank") and c.get("quote")
           and len(c.get("fateBeats") or []) == 4 for c in cards))

ok("샘플: 장르 추측", samples.guess_genre("로맨스 판타지") == "romance"
   and samples.guess_genre("헌터물") == "hunter"
   and samples.guess_genre("느와르") == "")

# 시험지 정규화 — 서식으로 AI 를 골라내지 못하게 한다
ok("믹싱: 번호 표기를 지운다",
   story.clean_card_text("① 당신은 사서입니다.") == "당신은 사서입니다.")
ok("믹싱: 슬래시 구분을 지운다",
   story.clean_card_text("나는 차갑다. / 그런데 아니다.") == "나는 차갑다. 그런데 아니다.")
ok("믹싱: 샘플과 생성 카드가 같은 칸으로 보인다",
   set(story.card_view(samples.load("romance")[0]))
   == set(story.card_view(story.mock_payload("P1", ""))))

# ---------------- 7단계 (세로 스크롤 문법) ----------------
#
# 여기서 갈리는 것은 **누가 무엇을 정하는가**다.
#   모델 : description / dialogue / size / beat   — 내용에서 나오는 판단
#   코드 : gap_after / scene_break / gaze         — beat 시퀀스에서 나오는 산수
# 게이트는 모델이 낸 것만 본다. 코드가 계산한 것은 게이트가 아니라 자기검사로 본다.
# 조건 여덟 개를 온도 0.9 에서 동시에 맞추게 하면 하나를 고칠 때 다른 게 깨진다 —
# 유령 id 때와 같은 실패였고, 실제로 gpt-4.1 이 여섯 번 연속 같은 자리에서 실패했다.
CUT_EPI = {"engine_fired": []}


def lay(payload):
    return webtoon.gate_cuts(payload, CUT_EPI, False)


ok("7단계 게이트: 정상 통과", lay(cuts(engine=(2, 6))) == [], lay(cuts(engine=(2, 6))))

# -- 값 자체 (모델이 내는 두 필드만 본다)
r = lay(cuts(c3={"size": "huge"}))
ok("7단계 게이트: 모르는 size 탈락", any("size" in f for f in r))

r = lay(cuts(c3={"beat": "쌓기"}))
ok("7단계 게이트: 모르는 beat 탈락", any("beat" in f for f in r))

# -- 게이트 1. 한 화에 turn 최소 1개
flat = cuts()
for c in flat["cuts"]:
    c["beat"] = "build"
flat["cuts"][-1]["beat"] = "hold"
ok("게이트1 turn 최소 1개: turn 이 없으면 탈락",
   any("turn 이 없습니다" in f for f in lay(flat)))

# -- 게이트 3. impact 는 화당 최대 2개
ok("게이트3 impact 상한: 2개는 통과", not any("impact" in f for f in lay(cuts())))
ok("게이트3 impact 상한: 3개면 탈락",
   any("impact 인 컷이 3개" in f for f in lay(cuts(c1={"size": "impact"}))))

# -- 게이트 5. 같은 beat 4연속 금지
r = lay(cuts(c1={"beat": "build"}, c3={"beat": "build", "size": "normal"},
             c4={"beat": "build"}))
ok("게이트5 beat 4연속: 'build' 가 4컷 이어지면 탈락",
   any("'build' 로 4컷 연속" in f for f in r))
ok("게이트5 beat 4연속: 3컷까지는 통과",
   not any("컷 연속입니다. 리듬" in f
           for f in lay(cuts(c1={"beat": "build"},
                             c3={"beat": "build", "size": "normal"}))))

# -- 게이트 6. 같은 size 4연속 금지
# 기본 크기는 wide-normal-impact-normal-tall … 이다. 앞의 두 자리를 normal 로 덮으면
# 1~4번 컷이 전부 normal 이 되어 "크기 같은 이미지 나열"이 된다.
r = lay(cuts(c1={"size": "normal"}, c3={"size": "normal"}))
ok("게이트6 size 4연속: 'normal' 이 4컷 이어지면 탈락",
   any("'normal' 로 4컷 연속" in f for f in r), r)
ok("게이트6 size 4연속: 3컷까지는 통과",
   not any("컷 연속입니다. 크기" in f for f in lay(cuts(c3={"size": "normal"}))))

# -- 게이트 8. 마지막 컷(스팅어)은 impact 또는 tall
for bad in ("wide", "normal"):
    ok(f"게이트8 스팅어 크기: 마지막 컷이 {bad} 면 탈락",
       any("마지막 컷(스팅어)의 size" in f for f in lay(cuts(c10={"size": bad}))))
for good in ("impact", "tall"):
    payload = cuts(c10={"size": good})
    if good == "impact":
        payload["cuts"][2]["size"] = "tall"     # impact 상한과 겹치지 않게
    ok(f"게이트8 스팅어 크기: 마지막 컷이 {good} 면 통과",
       not any("마지막 컷(스팅어)의 size" in f for f in lay(payload)))

# 스팅어 beat — 다음 화를 부르는 자리다
ok("7단계 게이트: 마지막 컷이 설명으로 끝나면 탈락",
   any("마지막 컷(스팅어)의 beat" in f for f in lay(cuts(c10={"beat": "setup"}))))

# -- 게이트에서 **빠진** 것: 코드가 계산하므로 모델을 되돌리지 않는다
noisy = cuts()
for c in noisy["cuts"]:
    c["gap_after"] = 1              # 모델이 여백을 전부 1 로 적어 보내도
    c["scene_break"] = True         # 화면을 아무 데서나 끊는다고 적어 보내도
    c["gaze"] = "away"
ok("게이트에서 빠짐: 모델이 적은 여백·경계·시선은 게이트를 흔들지 않는다",
   lay(noisy) == [], lay(noisy))

# ---------------- 7단계 연출 계산 (코드가 하는 몫) ----------------

def derived(n=10, **over):
    """모델 출력 → 코드가 연출을 계산한 컷 목록."""
    payload = cuts(n, engine=(2, 6), **over)
    webtoon.derive_layout(payload["cuts"])
    return payload["cuts"]


d = derived()
gaps = [c["gap_after"] for c in d]
beats = [c["beat"] for c in d]
breaks = [c["scene_break"] for c in d]

ok("연출 계산: turn 직전은 여백 3",
   all(gaps[i - 1] == 3 for i, b in enumerate(beats) if b == "turn" and i > 0), gaps)
ok("연출 계산: release 뒤는 여백 0 (몰아침)",
   all(gaps[i] == 0 for i, b in enumerate(beats)
       if b == "release" and i < len(d) - 1 and beats[i + 1] != "turn"), gaps)
ok("연출 계산: hold 뒤는 여백 2",
   all(gaps[i] == 2 for i, b in enumerate(beats)
       if b == "hold" and i < len(d) - 1 and beats[i + 1] != "turn"), gaps)
ok("연출 계산: 마지막 컷의 여백은 1 (읽히지 않는다)", gaps[-1] == 1)

ok("연출 계산: 여백이 3종 이상 흩어진다", len(set(gaps[:-1])) >= 3, gaps)
ok("연출 계산: 몰아치는 자리(0)가 있다", 0 in gaps[:-1], gaps)
ok("연출 계산: 낙차 자리(3)가 있다", 3 in gaps[:-1], gaps)
ok("연출 계산: 낙차는 화당 2회까지", gaps[:-1].count(3) <= 2, gaps)

ok("연출 계산: 마지막 컷에 화면 경계", breaks[-1] is True)
ok("연출 계산: 경계는 hold 나 turn 뒤에만",
   all(beats[i] in ("hold", "turn") for i, b in enumerate(breaks[:-1]) if b), beats)
ok("연출 계산: 한 Scene 은 2~5컷",
   all(2 <= span <= 5 or end == len(d) - 1
       for _, end, span in webtoon.scene_spans(breaks)),
   webtoon.scene_sizes(d))

ok("연출 계산: 시선은 아래로 흐르고 마지막에서만 멈춘다",
   d[-1]["gaze"] == "at-viewer"
   and all(c["gaze"] in ("down", "toward-next") for c in d[:-1]))

# 모델이 적어 보낸 연출 값은 덮어쓴다 — 계산 결과가 유일한 출처다
over = cuts()
for c in over["cuts"]:
    c.update(gap_after=1, scene_break=False, gaze="away")
webtoon.derive_layout(over["cuts"])
ok("연출 계산: 모델이 적어 보낸 값을 덮어쓴다",
   len({c["gap_after"] for c in over["cuts"][:-1]}) >= 3
   and over["cuts"][-1]["scene_break"] is True)

# turn 이 1번 컷뿐이면 "turn 직전"이 없다 — 코드가 낙차 자리를 따로 벌린다
lead = cuts()
for i, c in enumerate(lead["cuts"]):
    c["beat"] = "turn" if i == 0 else ("hold" if i in (4, 9) else "build")
notes = webtoon.derive_layout(lead["cuts"])
lead_gaps = [c["gap_after"] for c in lead["cuts"]]
ok("연출 계산: 반전 직전이 없어도 낙차 자리를 만든다", 3 in lead_gaps[:-1], lead_gaps)
ok("연출 계산: 손댄 자리는 메모로 남는다", any("낙차" in n for n in notes), notes)

# hold 가 모자라면 어쩔 수 없이 끊고 메모를 남긴다 (경계를 못 만드는 것보다 낫다)
nohold = cuts(12, engine=(2, 6))
for i, c in enumerate(nohold["cuts"]):
    c["beat"] = "turn" if i == 11 else ("release" if i % 3 == 2 else "build")
notes = webtoon.derive_layout(nohold["cuts"])
ok("연출 계산: 끊을 hold 가 없으면 메모를 남긴다",
   any("hold 가 없었습니다" in n for n in notes), notes)
ok("연출 계산: 그래도 Scene 은 5컷을 넘지 않는다",
   all(span <= 5 for _, _, span in
       webtoon.scene_spans([c["scene_break"] for c in nohold["cuts"]])),
   webtoon.scene_sizes(nohold["cuts"]))

# 자기검사 — 계산 결과가 규칙을 어기면 코드 버그다
for n in (8, 10, 11, 12, 14, 16):
    payload = cuts(n, engine=(2, 6))
    webtoon.apply_layout(payload["cuts"])          # 어기면 AssertionError
    ok(f"연출 자기검사: 컷 {n}개짜리 화가 규칙대로 배치된다",
       webtoon.layout_violations(payload["cuts"]) == [],
       webtoon.layout_violations(payload["cuts"]))

broken = derived()
for i in range(len(broken) - 1):                    # turn 직전을 손으로 망가뜨린다
    if broken[i + 1]["beat"] == "turn":
        broken[i]["gap_after"] = 1
        break
ok("연출 자기검사: 규칙을 어긴 배치를 잡아낸다",
   any("turn 인데 직전" in v for v in webtoon.layout_violations(broken)),
   webtoon.layout_violations(broken))

# ---------------- 7단계 컷 서술 경고 (반려하지 않는다) ----------------
#
# 컷은 이미지 한 장으로 그려진다. 소설 지문처럼 쓰면 그릴 수 없는 것이 나온다.
# 다만 장르에 따라 예외가 있어서 되돌리지 않고 경고만 남긴다 — 반려하면 컷 내용
# 전체를 다시 뽑게 되는데 그 비용이 경고 한 줄보다 훨씬 크다.

def prose(*descs, dialogue=None):
    """서술만 갈아 끼운 컷 목록. dialogue 를 안 주면 전부 침묵 컷이다."""
    return [{"cut_number": i + 1, "description": d,
             "dialogue": (dialogue[i] if dialogue else "")}
            for i, d in enumerate(descs)]


BLANK2 = ("빈 복도", "젖은 아스팔트")   # 침묵 컷 하한을 채우는 들러리


def prose_hit(desc, kind):
    hits = webtoon.prose_warnings(prose(desc, *BLANK2))
    return any(h.startswith("컷 1") and kind in h for h in hits)


# -- 한 컷 = 한 순간
ok("서술 경고: '~다가' 를 잡는다",
   prose_hit("윤아가 호스를 닦다가 시선을 느끼고 몸을 웅크린다", "시간 경과"))
ok("서술 경고: '~하고 나서' 를 잡는다",
   prose_hit("문을 닫고 나서 계단을 내려간다", "시간 경과"))
ok("서술 경고: '~한 뒤' 를 잡는다 (ㄴ 이 음절 안에 있어도)",
   prose_hit("경보음이 울린 뒤 대원들이 뛴다", "시간 경과"))
ok("서술 경고: '~한 후' 를 잡는다", prose_hit("문을 닫은 후 그가 돌아선다", "시간 경과"))
ok("서술 경고: '~하더니' 를 잡는다", prose_hit("사이렌을 듣더니 달린다", "시간 경과"))
ok("서술 경고: '잠시 후' 를 잡는다", prose_hit("잠시 후 소방차가 멈춘다", "시간 경과"))

ok("서술 경고: 정지 상태 서술은 통과",
   not prose_hit("윤아가 고개를 숙인 채 호스를 닦고 있다", "시간 경과"))
ok("서술 경고: '다가온다' 는 시간 경과가 아니다",
   not prose_hit("그가 천천히 다가온다", "시간 경과"))
ok("서술 경고: 공간을 뜻하는 '뒤' 는 잡지 않는다 (뒤에서)",
   not prose_hit("그의 등 뒤에서 불길이 솟는다", "시간 경과"))
ok("서술 경고: 공간을 뜻하는 '뒤' 는 잡지 않는다 (한 글자 낱말)",
   not prose_hit("문 뒤에 서 있는 실루엣", "시간 경과"))

# -- 속마음
ok("서술 경고: '생각한다' 를 잡는다",
   prose_hit("그는 이대로는 안 된다고 생각한다", "속마음"))
ok("서술 경고: '느낀다' 를 잡는다", prose_hit("윤아가 공포를 느낀다", "속마음"))
ok("서술 경고: '깨닫는다' 를 잡는다", prose_hit("자신이 늦었음을 깨닫는다", "속마음"))
ok("서술 경고: 표정·자세로 쓴 감정은 통과",
   not prose_hit("윤아의 동공이 확대되어 있다. 소매를 쥔 "
                 "손가락 마디가 하얗게 질려 있다", "속마음"))

# -- 침묵 컷
loud = prose("윤아가 서 있다", "헬멧이 놓여 있다", "대원들이 달린다",
             dialogue=["\"가자\"", "\"준비\"", "\"출동\""])
ok("서술 경고: 대사 없는 컷이 2개 미만이면 경고",
   any("침묵 컷" in w for w in webtoon.prose_warnings(loud)))

half = prose("윤아가 서 있다", "헬멧이 놓여 있다", "대원들이 달린다",
             dialogue=["\"가자\"", "", ""])
ok("서술 경고: 대사 없는 컷이 2개면 통과",
   not any("침묵 컷" in w for w in webtoon.prose_warnings(half)))

# -- 카메라는 이제 필드다. 서술에 다시 적으면 같은 지시가 두 번 들어간다.
ok("서술 경고: 서술에 카메라 낱말이 남으면 경고",
   any("카메라·크기 낱말" in w
       for w in webtoon.prose_warnings(prose("바스트. 윤아가 서 있다", *BLANK2))))
ok("서술 경고: 서술이 size 값으로 시작하면 경고",
   any("카메라·크기 낱말" in w
       for w in webtoon.prose_warnings(prose("tall. 윤아가 서 있다", *BLANK2))))
ok("서술 경고: 보이는 것만 쓰면 통과",
   not any("카메라·크기 낱말" in w
           for w in webtoon.prose_warnings(prose("윤아가 서 있다", *BLANK2))))

# 인서트인데 얼굴이 보이면 인물 없는 컷을 세는 근거가 흐려진다
fake = prose("윤아의 얼굴이 보인다", *BLANK2)
fake[0]["shot"] = "인서트"
ok("서술 경고: 인서트에 인물이 들어가면 경고",
   any("인서트" in w for w in webtoon.prose_warnings(fake)))
real = prose("탁자 위에 엎어 둔 컵", *BLANK2)
real[0]["shot"] = "인서트"
ok("서술 경고: 사물만 있는 인서트는 통과",
   not any("인서트" in w for w in webtoon.prose_warnings(real)))

# -- 경고는 게이트가 아니다
# 대사를 넣으면 그 컷은 말풍선 자리가 필요하고(bubble_zone), 말하는 사람이
# 화면에 있어야 한다(characters_in_frame). 여기서 보려는 것은 "서술 경고가
# 게이트를 막지 않는가" 이므로, 그 둘은 맞춰 두고 서술만 험하게 만든다.
sloppy = cuts(engine=(2, 6))
for i, c in enumerate(sloppy["cuts"]):
    c["description"] = "윤아가 호스를 닦다가 공포를 느낀다"
    c["dialogue"] = "\"...\""
    c["speaker"] = ("윤아", "대원")[i % 2]
    c["thought"] = ""
    c["bubble_zone"] = "top"
    c["speaker_side"] = ("left", "right")[i % 2]
    c["characters_in_frame"] = [c["speaker"]]
ok("서술 경고: 경고가 있어도 게이트는 통과시킨다 (반려하지 않는다)",
   webtoon.gate_cuts(sloppy, CUT_EPI, False) == [],
   webtoon.gate_cuts(sloppy, CUT_EPI, False))
ok("서술 경고: 그래도 경고는 남는다",
   len(webtoon.prose_warnings(sloppy["cuts"])) >= 3)

ok("서술 경고: 모의 컷은 경고가 없다",
   webtoon.prose_warnings(cuts()["cuts"]) == [],
   webtoon.prose_warnings(cuts()["cuts"]))

# ---------------- 7단계 텍스트 4종 ----------------
#
# 웹툰은 말풍선만으로 굴러가지 않는다. 나레이션·속마음·효과음이 같이 쓰인다.

def texted(**over):
    c = story.mock_cuts(12)["cuts"]
    for num, patch in over.items():
        c[int(num[1:]) - 1].update(patch)
    return c


ok("텍스트: 모의 컷이 통과한다", webtoon.gate_layout(texted()) == [])

r = webtoon.gate_layout(texted(c3={"sfx": "BOOM"}))
ok("텍스트 게이트: 로마자 sfx 탈락", any("로마자" in f for f in r), r)
ok("텍스트 게이트: 한글 sfx 통과",
   not any("로마자" in f for f in webtoon.gate_layout(texted(c3={"sfx": "콰앙"}))))
ok("텍스트 게이트: 침묵 표현도 통과",
   not any("sfx" in f for f in webtoon.gate_layout(texted(c3={"sfx": "…"}))))

r = webtoon.gate_layout(texted(c3={"sfx": "쿵쿵쿵쿵쿵쿵쿵쿵쿵"}))
ok("텍스트 게이트: 너무 긴 sfx 탈락", any("너무 깁니다" in f for f in r))

# -- 침묵 컷은 네 칸이 전부 비어야 한다
loud = story.mock_cuts(12)["cuts"]
for c in loud:
    c["narration"] = "무언가"
ok("텍스트 경고: 나레이션으로 채우면 침묵 컷이 사라진다",
   any("글자가 하나도 없는 컷" in w for w in webtoon.prose_warnings(loud)))

# -- 나레이션이 그림을 때우는 경우
dup = texted(c1={"description": "부감. 불타는 상가 앞에 소방차가 멈춘다",
                 "narration": "소방차가 상가 앞에 멈춘다"})
ok("텍스트 경고: 서술과 겹치는 나레이션을 잡는다",
   any("narration 이 서술과 겹칩니다" in w for w in webtoon.prose_warnings(dup)))

fine = texted(c1={"description": "부감. 불타는 상가 앞에 소방차가 멈춘다",
                  "narration": "그날 밤, 관내 세 번째 출동이었다"})
ok("텍스트 경고: 그림이 담을 수 없는 나레이션은 통과",
   not any("narration" in w for w in webtoon.prose_warnings(fine)))

# -- SD 컷의 효과음
nosfx = story.mock_cuts(12)["cuts"]
for c in nosfx:
    if c["render_style"] == "sd":
        c["sfx"] = ""
ok("텍스트 경고: SD 컷에 sfx 가 없으면 경고",
   any("SD 인데 sfx" in w for w in webtoon.prose_warnings(nosfx)))

ok("텍스트: 나레이션 개수에는 상한이 없다",
   webtoon.gate_layout(texted(**{f"c{i}": {"narration": f"{i}일 뒤"}
                                 for i in range(1, 9)})) == [])

# ---------------- 7단계 그림체 (render_style) ----------------
#
# 실제 웹툰은 한 화 안에서 그림체가 고정되지 않는다. 진지한 컷은 정식 작화,
# 분위기 푸는 컷은 SD. 그 전환이 리듬의 일부다.

def cam_axes(i, n):
    """게이트를 통과하는 카메라 세 축 — 다른 축을 시험할 때 쓰는 배경 픽스처.

    모의 컷과 같은 순환을 쓴다. 여기서 카메라가 게이트에 걸리면 render_style 이나
    size 를 시험하는 검사가 엉뚱한 이유로 실패한다.
    """
    return {
        "shot": ("클로즈업" if i == n - 1
                 else story.MOCK_SHOTS[i % len(story.MOCK_SHOTS)]),
        "angle": story.MOCK_ANGLES[i % len(story.MOCK_ANGLES)],
        "transition": ("장면" if i == 0
                       else story.MOCK_TRANSITIONS[(i - 1) % len(story.MOCK_TRANSITIONS)]),
    }


def styled(beats, renders):
    n = len(beats)
    return [{"cut_number": i + 1, "size": "tall" if i % 2 else "normal",
             "beat": b, "render_style": r, "dialogue": "",
             "description": f"{i + 1}번 컷", **cam_axes(i, n)}
            for i, (b, r) in enumerate(zip(beats, renders))]


RB = ["setup", "build", "hold", "build", "turn", "release", "build", "hold"]

PASS_R = ["normal", "sd", "sd", "normal", "emphasis", "sd", "normal", "normal"]
ok("그림체 게이트: 정상 통과", webtoon.gate_layout(styled(RB, PASS_R)) == [],
   webtoon.gate_layout(styled(RB, PASS_R)))

# 하한은 없다. 진지한 장면이 이어지는 화는 sd 가 0개인 것이 맞는 답이고,
# 개수를 맞추려고 올린 SD 는 그 장면을 가볍게 만들 뿐이다.
r = webtoon.gate_layout(styled(RB, ["normal"] * 8))
ok("그림체 게이트: sd 가 0개여도 통과 (하한 없음)", r == [], r)

r = webtoon.gate_layout(styled(RB, ["normal", "sd", "normal", "normal",
                                    "emphasis", "normal", "normal", "normal"]))
ok("그림체 게이트: sd 가 1개여도 통과", r == [], r)

r = webtoon.gate_layout(styled(RB, ["normal", "sd", "sd", "sd", "normal", "sd",
                                    "normal", "normal"]))
ok("그림체 게이트: sd 4개는 통과 (상한 5개)", r == [], r)

# build 도 SD 자리다. 쌓는 중에 한 컷 가볍게 빠지는 것은 흔한 리듬이다.
r = webtoon.gate_layout(styled(RB, ["normal", "sd", "normal", "sd", "normal", "sd",
                                    "sd", "normal"]))
ok("그림체 게이트: build 컷의 sd 통과", r == [], r)

r = webtoon.gate_layout(styled(RB, ["normal", "sd", "sd", "sd", "normal", "sd",
                                    "sd", "normal"]))
ok("그림체 게이트: sd 가 5개면 통과 (상한 경계)", r == [], r)

# 상한은 남는다 — 데포르메가 너무 잦으면 산만해진다.
RB10 = ["setup", "build", "hold", "build", "turn",
        "release", "build", "hold", "release", "hold"]
# 개수는 이야기가 정한다. 게이트는 세지 않고, 경고만 찍는다.
c6 = styled(RB10, ["normal", "sd", "sd", "sd", "emphasis",
                   "sd", "sd", "normal", "sd", "normal"])
ok("그림체 게이트: sd 가 6개여도 되돌리지 않는다",
   not any("sd" in f and "개입니다" in f for f in webtoon.gate_layout(c6)))
ok("그림체 경고: sd 가 잦으면 경고는 남긴다",
   any("sd" in x and "보통" in x for x in webtoon.render_warnings(c6)),
   webtoon.render_warnings(c6))

for i, beat in ((4, "turn"), (0, "setup")):
    rs = list(PASS_R)
    rs[i] = "sd"
    r = webtoon.gate_layout(styled(RB, rs))
    ok(f"그림체 게이트: {beat} 컷의 sd 탈락",
       any("beat 가" in f and "sd" in f for f in r), r)

rs = list(PASS_R)
rs[-1] = "sd"
r = webtoon.gate_layout(styled(RB, rs))
ok("그림체 게이트: 마지막 컷의 sd 탈락", any("스팅어" in f for f in r), r)

r = webtoon.gate_layout(styled(RB, ["normal", "sd", "chibi", "normal",
                                    "normal", "sd", "sd", "normal"]))
ok("그림체 게이트: 모르는 값 탈락", any("render_style" in f for f in r))

ok("그림체 게이트: emphasis 는 개수 제한이 없다",
   webtoon.gate_layout(styled(RB, ["emphasis", "sd", "sd", "emphasis",
                                   "emphasis", "sd", "emphasis",
                                   "emphasis"])) == [])

# -- 수리는 강등만 한다 (sd -> normal). 어느 컷을 SD 로 올릴지는 톤 판단이다.
c = styled(RB, ["sd", "sd", "normal", "normal", "normal", "sd", "normal", "normal"])
notes = webtoon.repair_render_styles(c)
ok("그림체 수리: 잘못된 beat(setup) 의 sd 를 되돌린다",
   [x["render_style"] for x in c][0] == "normal"
   and [x["render_style"] for x in c][1] == "sd" and len(notes) == 1, notes)

c = styled(RB, ["normal"] * 7 + ["sd"])
webtoon.repair_render_styles(c)
ok("그림체 수리: 스팅어의 sd 를 되돌린다", c[-1]["render_style"] == "normal")

RB6 = ["build", "build", "hold", "build", "release", "release", "build", "hold"]
c = styled(RB6, ["sd"] * 7 + ["normal"])
webtoon.repair_render_styles(c)
left = [x["cut_number"] for x in c if x["render_style"] == "sd"]
ok("그림체 수리: 개수로는 강등하지 않는다 (몇 개가 맞는지는 이야기가 정한다)",
   left == [1, 2, 3, 4, 5, 6, 7], left)

c = styled(RB, ["normal"] * 8)
ok("그림체 수리: sd 가 0개여도 승격하지 않는다 (위반이 아니다)",
   webtoon.repair_render_styles(c) == []
   and all(x["render_style"] == "normal" for x in c))

# -- 통컷(bleed) 과 칸 밖으로(breakout)
RB_B = ["setup", "build", "hold", "build", "turn", "release", "build", "hold"]
base_b = ["normal"] * 8

r = webtoon.gate_layout(styled(RB_B, ["normal", "normal", "normal", "breakout",
                                      "bleed", "normal", "normal", "normal"]))
ok("칸 게이트: 통컷 1개 + 칸밖 1개는 통과", r == [], r)

rs = list(base_b)
rs[4], rs[5] = "bleed", "bleed"      # turn, release — 자리는 맞다
ok("칸 게이트: 통컷이 2개여도 되돌리지 않는다",
   not any("bleed" in f and "개입니다" in f for f in webtoon.gate_layout(styled(RB_B, rs))))
ok("칸 경고: 통컷이 잦으면 경고는 남긴다",
   any("bleed" in x for x in webtoon.render_warnings(styled(RB_B, rs))))

rs = list(base_b)
rs[0] = "bleed"          # setup 자리
r = webtoon.gate_layout(styled(RB_B, rs))
ok("칸 게이트: 통컷이 turn·release 가 아닌 자리면 탈락",
   any("bleed" in f and "beat" in f for f in r), r)

rs = list(base_b)
rs[1] = rs[3] = rs[6] = "breakout"   # 전부 build — 자리는 맞다
ok("칸 게이트: 칸밖이 3개여도 되돌리지 않는다",
   not any("breakout" in f and "개입니다" in f
           for f in webtoon.gate_layout(styled(RB_B, rs))))
ok("칸 경고: 칸밖이 잦으면 경고는 남긴다",
   any("breakout" in x for x in webtoon.render_warnings(styled(RB_B, rs))))

rs = list(base_b)
rs[2] = "breakout"       # hold 자리
r = webtoon.gate_layout(styled(RB_B, rs))
ok("칸 게이트: 칸밖이 힘 없는 자리면 탈락",
   any("breakout" in f and "beat" in f for f in r), r)

ok("모의 컷: render_style 을 낸다",
   all(x["render_style"] in webtoon.RENDER_STYLES for x in story.mock_cuts(12)["cuts"]))
ok(f"모의 컷: sd 가 상한 {webtoon.SD_MAX}개를 넘지 않는다",
   sum(1 for x in story.mock_cuts(12)["cuts"]
       if x["render_style"] == "sd") <= webtoon.SD_MAX)

# ---------------- 다음 화가 물려받는 것 ----------------
#
# 2화를 쓸 때 받는 것은 1화가 남긴 것만이 아니다. 작품 자체가 가진 고정 자산
# (훅·엔진·주인공·전체 줄거리)도 같이 가야 한다. 예전에는 arc_json 이 **그 화가
# 속한 Arc 하나뿐**이라, 이 작품이 어디로 가는지 전체 지도를 못 봤다.

ARCS = [{"order": 1, "title": "경계의 선", "arc_type": "전개",
         "estimated_episode_count": 3, "summary": "시하가 선을 긋는다."},
        {"order": 2, "title": "침범", "arc_type": "반전",
         "estimated_episode_count": 3, "summary": "윤재가 넘어온다."},
        {"order": 3, "title": "대가", "arc_type": "상승",
         "estimated_episode_count": 4, "summary": "경계가 흐려진다."}]

blk = webtoon.series_arc_block(ARCS, ARCS[1])
ok("전체 줄거리: Arc 를 전부 싣는다",
   all(f"Arc {i}." in blk for i in (1, 2, 3)), blk)
ok("전체 줄거리: 지금 어디인지 표시한다",
   "지금 여기" in blk and blk.index("지금 여기") > blk.index("침범"), blk)
ok("전체 줄거리: 한 줄 요약까지만 (일정표를 짜지 못하게)",
   "윤재가 넘어온다." in blk and len(blk.splitlines()) == 6, blk)
ok("전체 줄거리: Arc 가 없어도 죽지 않는다",
   "없습니다" in webtoon.series_arc_block([], None))

# 프롬프트에 실제로 실려 나가는가 (계약만 맞고 주입이 빠지는 일이 있었다)
_ps = story.load_prompts(contract=webtoon.WEBTOON_CONTRACT)
for _name in ("w5", "w7"):
    _txt = story.render(_ps.texts[_name],
                        dict({k: f"<{k}>" for k in webtoon.WEBTOON_CONTRACT[_name]},
                             series_arc=blk))
    ok(f"{_name}: 전체 줄거리가 프롬프트에 주입된다", "Arc 3. 대가" in _txt)
    ok(f"{_name}: 치환 안 된 변수가 없다",
       "{series_arc}" not in _txt and "{arc_json}" not in _txt)

CARD = ("=== 엔진 카드 ===\n"
        "[로그라인] 기록이 사람을 앞서는 대학에서 시하가 흔들린다\n"
        "[엔진급 질문] 시하는 진짜 자기를 내보일 수 있을까?\n"
        "[RULE] 기록이 사람을 앞선다\n"
        "[주인공] 민시하\n"
        "  그 한 사람: 하윤재 — 같은 학과 남후배\n")
fx = webtoon.fixed_assets_block(CARD, ARCS)
joined = "\n".join(fx)
ok("고정 자산: 훅과 엔진을 싣는다",
   "[로그라인]" in joined and "[엔진급 질문]" in joined and "[RULE]" in joined, fx)
ok("고정 자산: 중심 인물을 싣는다",
   "민시하" in joined and "하윤재" in joined, fx)
ok("고정 자산: 전체 줄거리를 싣는다", "Arc 3. 대가" in joined, fx)
ok("고정 자산: 아무것도 없으면 절을 만들지 않는다",
   webtoon.fixed_assets_block("", []) == [])


# ---------------- 7단계 장면 (무슨 장면이고 어떤 공기인가) ----------------
#
# 카메라를 아무리 갈라도 "이 장면이 무슨 장면이고 어떤 공기여야 하는가"가 없으면
# 컷은 예쁜 그림의 나열이 된다. 예전 작업 순서는 컷 수부터 정하고 beat 수열로
# 넘어가서 장면의 의도를 한 번도 묻지 않았다.

def scened(*spans, what="라운지에서 윤재가 또 옆에 앉는다", mood="느긋한 오후",
           tones=None):
    """tones 를 안 주면 일상/개그를 번갈아 준다 — 전부 같은 tone 은 경고 대상이라
    기본값으로 쓰면 다른 검사의 결과가 그 경고에 섞인다."""
    at, out = 0, []
    for i, n in enumerate(spans):
        at += n
        tone = (tones[i] if tones and i < len(tones)
                else ("일상", "개그")[i % 2])
        out.append({"what": what, "mood": mood, "tone": tone, "last_cut": at})
    return {"scenes": out}, at


pay, total = scened(4, 4, 4)
ok("장면 게이트: 정상 통과", webtoon.gate_scenes(pay, total) == [],
   webtoon.gate_scenes(pay, total))

ok("장면 게이트: scenes 가 없으면 탈락",
   any("scenes 가 없습니다" in f for f in webtoon.gate_scenes({}, 12)))

pay, total = scened(12)
ok("장면 게이트: 장면이 하나뿐이면 탈락",
   any("장면이 1개" in f for f in webtoon.gate_scenes(pay, total)),
   webtoon.gate_scenes(pay, total))

pay, total = scened(2, 2, 2, 2, 2, 2)
ok("장면 게이트: 장면이 6개면 탈락",
   any("장면이 6개" in f for f in webtoon.gate_scenes(pay, total)))

pay, total = scened(6, 6)
ok("장면 게이트: 한 장면이 6컷이면 탈락",
   any("6컷입니다" in f for f in webtoon.gate_scenes(pay, total)),
   webtoon.gate_scenes(pay, total))

pay, total = scened(4, 4, 1)
ok("장면 게이트: 마지막 장면은 1컷(스팅어)이어도 통과 (render_gate 3088줄과 "
   "같은 예외)",
   webtoon.gate_scenes(pay, total) == [], webtoon.gate_scenes(pay, total))

pay, total = scened(4, 1, 4)
ok("장면 게이트: 마지막이 아닌 장면이 1컷이면 그대로 탈락",
   any("1컷입니다" in f for f in webtoon.gate_scenes(pay, total)),
   webtoon.gate_scenes(pay, total))

pay, total = scened(4, 4)
r = webtoon.gate_scenes(pay, 12)
ok("장면 게이트: 마지막 장면이 마지막 컷에서 안 끝나면 탈락",
   any("마지막 장면이" in f for f in r), r)

pay, total = scened(4, 4)
pay["scenes"][0]["what"] = ""
ok("장면 게이트: what 이 비면 탈락",
   any("what 이 비어" in f for f in webtoon.gate_scenes(pay, total)))
pay, total = scened(4, 4)
pay["scenes"][1]["mood"] = "  "
ok("장면 게이트: mood 가 비면 탈락",
   any("mood 가 비어" in f for f in webtoon.gate_scenes(pay, total)))

pay, total = scened(4, 4)
pay["scenes"][1]["last_cut"] = 3          # 앞 장면보다 작다
ok("장면 게이트: last_cut 이 거꾸로면 탈락",
   any("순서대로" in f for f in webtoon.gate_scenes(pay, total)))

# 경계는 이제 모델이 정한다 — 코드는 옮겨 적기만 한다
cuts12 = story.mock_cuts(12)["cuts"]
scenes = [{"what": "a", "mood": "m", "last_cut": 5},
          {"what": "b", "mood": "m", "last_cut": 9},
          {"what": "c", "mood": "m", "last_cut": 12}]
webtoon.derive_layout(cuts12, scenes)
ok("장면 경계: 모델이 준 last_cut 을 그대로 쓴다",
   webtoon.scene_sizes(cuts12) == [5, 4, 3], webtoon.scene_sizes(cuts12))

cuts12 = story.mock_cuts(12)["cuts"]
webtoon.derive_layout(cuts12, None)
ok("장면 경계: scenes 가 없으면 예전처럼 beat 로 계산한다 (옛 run 호환)",
   sum(webtoon.scene_sizes(cuts12)) == 12
   and all(2 <= x <= 5 for x in webtoon.scene_sizes(cuts12)),
   webtoon.scene_sizes(cuts12))

cuts12 = story.mock_cuts(12)["cuts"]
webtoon.derive_layout(cuts12, [{"what": "a", "mood": "m", "last_cut": 99}])
ok("장면 경계: 값이 깨져 있으면 계산으로 되돌아간다 (assert 로 죽지 않는다)",
   sum(webtoon.scene_sizes(cuts12)) == 12)

ok("모의 컷: 장면 게이트를 통과한다",
   all(webtoon.gate_scenes(story.mock_cuts(n), n) == [] for n in range(8, 17)))


# ---------------- 7단계 장면 tone — SD 가 한 컷도 안 나오던 자리 ----------------
#
# render_style 은 컷마다 정해지는데, 컷을 쓰는 시점에 "이 장면이 웃긴 장면인가" 를
# 아무도 말해 주지 않았다. 프롬프트는 (옳게) "sd 개수를 세지 마라" 라고 가르치므로
# 신호가 없으면 모델은 안전한 normal 로 수렴한다 — 실측 13컷 전부 normal.
#
# 고치는 방향이 중요하다. 하한("sd 3개 이상")을 넣으면 이 저장소가 두 번 뺐던
# 비율 게이트가 돌아온다. 대신 **신호**(tone)를 주고, 코드는 잘못 놓인 것만 치운다.

pay, total = scened(4, 4)
pay["scenes"][0]["tone"] = "코믹"          # TONES 에 없는 값
ok("장면 게이트: 모르는 tone 은 탈락",
   any("tone" in f for f in webtoon.gate_scenes(pay, total)),
   webtoon.gate_scenes(pay, total))

pay, total = scened(4, 4)
del pay["scenes"][1]["tone"]
ok("장면 게이트: tone 이 없으면 탈락",
   any("tone" in f for f in webtoon.gate_scenes(pay, total)))


def toned(tone, *renders):
    """한 장면짜리 컷 목록 — render_style 만 다르게."""
    cuts = [{"cut_number": i, "beat": "hold", "render_style": r}
            for i, r in enumerate(renders, 1)]
    scenes = [{"what": "a", "mood": "m", "tone": tone, "last_cut": len(renders)}]
    return cuts, scenes


c, s = toned("긴장", "normal", "sd", "normal")
notes = webtoon.repair_tone_lock(c, s)
ok("tone 강등: 긴장 장면의 sd 는 normal 로 내려간다",
   c[1]["render_style"] == "normal" and len(notes) == 1, notes)

c, s = toned("감정", "sd", "normal")
webtoon.repair_tone_lock(c, s)
ok("tone 강등: 감정 장면의 sd 도 내려간다", c[0]["render_style"] == "normal")

c, s = toned("개그", "sd", "sd", "normal")
notes = webtoon.repair_tone_lock(c, s)
ok("tone 강등: 개그 장면의 sd 는 건드리지 않는다",
   [x["render_style"] for x in c] == ["sd", "sd", "normal"] and notes == [], notes)

# 승격은 하지 않는다 — repair_render_styles 와 같은 이유다. 서술이 그림체에
# 묶여 있어서(w7 규칙 8), 정식 작화용 표정 묘사를 둔 채 sd 로 올리면 지시가
# 서로 부딪친다. 개그 장면이 전부 normal 인 것은 위반이 아니라 경고다.
c, s = toned("개그", "normal", "normal")
ok("tone 강등: 개그라고 코드가 sd 로 올리지는 않는다",
   webtoon.repair_tone_lock(c, s) == []
   and all(x["render_style"] == "normal" for x in c))

c, s = toned("긴장", "emphasis", "bleed")
ok("tone 강등: 긴장 장면에서 emphasis·bleed 는 막지 않는다",
   webtoon.repair_tone_lock(c, s) == [])

c, s = toned("개그", "normal", "normal")
ok("tone 경고: 개그 장면이 전부 normal 이면 메모가 남는다",
   any("개그" in x for x in webtoon.tone_warnings(c, s)),
   webtoon.tone_warnings(c, s))

c, s = toned("개그", "normal", "sd")
ok("tone 경고: 개그 장면에 sd 가 하나라도 있으면 메모하지 않는다",
   webtoon.tone_warnings(c, s) == [])

c, s = toned("긴장", "normal", "normal")
ok("tone 경고: 긴장 장면이 전부 normal 인 것은 정상이다",
   webtoon.tone_warnings(c, s) == [])

tone_cuts = [{"cut_number": i, "beat": "hold", "render_style": "normal"}
             for i in range(1, 9)]
tone_same = [{"what": "a", "mood": "m", "tone": "일상", "last_cut": 4},
             {"what": "b", "mood": "m", "tone": "일상", "last_cut": 8}]
ok("tone 경고: 한 화의 tone 이 전부 같으면 메모가 남는다",
   any("개가 전부" in x for x in webtoon.tone_warnings(tone_cuts, tone_same)),
   webtoon.tone_warnings(tone_cuts, tone_same))

tone_mixed = [{"what": "a", "mood": "m", "tone": "개그", "last_cut": 4},
              {"what": "b", "mood": "m", "tone": "긴장", "last_cut": 8}]
ok("tone 경고: tone 이 섞여 있으면 그 메모는 없다",
   not any("개가 전부" in x for x in webtoon.tone_warnings(tone_cuts, tone_mixed)),
   webtoon.tone_warnings(tone_cuts, tone_mixed))

# 옛 run 은 scenes 에 tone 이 없다. 강등도 경고도 조용히 지나가야 한다 —
# 여기서 죽으면 예전 화를 다시 열 수 없다.
tone_old = [{"what": "a", "mood": "m", "last_cut": 8}]
ok("tone: 옛 run(tone 없음)에서도 죽지 않는다",
   webtoon.repair_tone_lock(tone_cuts, tone_old) == []
   and webtoon.tone_warnings(tone_cuts, tone_old) == []
   and webtoon.repair_tone_lock(tone_cuts, None) == [])

ok("tone: 컷이 속한 장면을 last_cut 으로 찾는다",
   (webtoon.tone_of_cut(tone_mixed, 1), webtoon.tone_of_cut(tone_mixed, 4),
    webtoon.tone_of_cut(tone_mixed, 5), webtoon.tone_of_cut(tone_mixed, 99))
   == ("개그", "개그", "긴장", ""))


# ---------------- 7단계 존 — 배경을 뽑기 전에 글로 본다 ----------------
#
# 존 배경은 한 번 구우면 그 존의 **모든 컷이 재사용**한다. 그래서 잘못된 배경
# 하나가 화 전체로 번지고, 이미지를 뽑은 뒤에 알면 그 존의 컷이 전부 다시다.
# 검수를 이미지 뒤로 미루지 않고 텍스트 단계(여기)에서 하는 이유다.

z_cuts = [{"cut_number": 1, "zone": "z-sofa"}, {"cut_number": 2, "zone": "z-vending"}]
z_known = {"하윤재", "민시하"}


def zpay(zones, cuts=None):
    return {"cuts": cuts if cuts is not None else z_cuts, "zones": zones}


ok("존 게이트: 정상 통과",
   webtoon.gate_zone(zpay([
       {"zone_id": "z-sofa", "description": "창가 쪽 2인용 낡은 천 소파와 낮은 탁자"},
       {"zone_id": "z-vending", "description": "복도 끝 커피 자판기. 종이컵만 나온다"},
   ]), set(), z_known) == [])

ok("존 게이트: zone 이 비면 탈락",
   any("비어 있습니다" in f for f in
       webtoon.gate_zone(zpay([], [{"cut_number": 1, "zone": ""}]), set(), z_known)))

ok("존 게이트: 컷이 가리키는 존의 서술이 없으면 탈락",
   any("z-vending" in f for f in webtoon.gate_zone(
       zpay([{"zone_id": "z-sofa", "description": "창가 소파"}]), set(), z_known)))

# 이 게이트의 핵심. 배경 서술에 사람이 들어가면 그 사람이 배경에 구워져
# 그 존의 모든 컷에 따라다닌다 — 컷 서술을 label 로 재사용하던 때의 실패다.
r = webtoon.gate_zone(zpay([
    {"zone_id": "z-sofa", "description": "창가 소파에 윤재가 앉아 있다"},
    {"zone_id": "z-vending", "description": "복도 끝 커피 자판기"},
]), set(), z_known)
ok("존 게이트: 배경 서술에 인물이 있으면 탈락 (성을 뗀 이름도 잡는다)",
   any("윤재" in f and "배경은 빈 공간" in f for f in r), r)

ok("존 게이트: 이미 배경이 있는 존은 서술을 다시 요구하지 않는다",
   webtoon.gate_zone(zpay([]), {"z-sofa", "z-vending"}, z_known) == [])

ok("이름 조각: 성을 뗀 형태까지 만든다",
   webtoon.name_variants({"하윤재"}) == {"하윤재", "윤재"},
   webtoon.name_variants({"하윤재"}))
ok("이름 조각: 띄어쓴 이름은 성을 떼지 않는다 ('라프' 같은 조각 방지)",
   webtoon.name_variants({"제라프 알베리온"}) == {"제라프 알베리온"})

# 서술 없는 존은 명부에 올리지 않는다 — 올리면 다음 화가 그 id 를 "이미 배경이
# 있는 존" 으로 알고 서술을 영영 안 적는다.
st = webtoon.SeriesState(run_id="t")
added = webtoon.record_cut_zone(st, zpay([
    {"zone_id": "z-sofa", "description": "창가 쪽 낡은 천 소파"},
]), "학생회관 복도", 1)
ok("존 명부: 서술이 있는 존만 올린다",
   added == ["z-sofa"] and len(st.zones) == 1
   and st.zones[0]["label"] == "창가 쪽 낡은 천 소파", (added, st.zones))

ok("존 명부: 같은 존을 두 번 올리지 않는다",
   webtoon.record_cut_zone(st, zpay([
       {"zone_id": "z-sofa", "description": "다르게 적은 같은 자리"}]),
       "학생회관 복도", 2) == []
   and len(st.zones) == 1 and st.zones[0]["label"] == "창가 쪽 낡은 천 소파")

ok("모의 컷: 존 게이트를 통과한다",
   all(webtoon.gate_zone(story.mock_cuts(n), set(), z_known) == []
       for n in range(8, 17)))


# ---------------- 존이 바뀌는 자리와 전환이 어긋나는가 (경고) ----------------
#
# transition 의 '순간'·'동작' 은 "같은 자리에서 조금 움직였다" 는 뜻이다. 그
# 사이에 zone 이 바뀌면 한 컷 만에 순간이동한 것이 된다 — 인물이 말없이 사라지고
# 배경만 그대로인 화면이 그렇게 나왔다. 막지는 않는다: 카메라가 같은 순간을 다른
# 구역에서 잡는 컷은 zone 이 바뀌고도 '순간' 이 맞다.

def zt(*pairs):
    return [{"cut_number": i, "zone": z, "transition": t}
            for i, (z, t) in enumerate(pairs, 1)]


ok("공간 경고: 존이 바뀌는데 전환이 '동작' 이면 경고",
   any("z-sofa -> z-vending" in w
       for w in webtoon.zone_warnings(zt(("z-sofa", "장면"), ("z-vending", "동작")))))
ok("공간 경고: 존이 바뀌고 전환이 '장면' 이면 조용하다",
   webtoon.zone_warnings(zt(("z-sofa", "장면"), ("z-vending", "장면"))) == [])
ok("공간 경고: 같은 존이 이어지면 전환이 무엇이든 조용하다",
   webtoon.zone_warnings(zt(("z-sofa", "장면"), ("z-sofa", "순간"))) == [])
ok("공간 경고: zone 이 비어 있으면(옛 run) 조용하다",
   webtoon.zone_warnings(zt(("", "순간"), ("", "동작"))) == [])
ok("모의 컷: 공간 경고가 없다",
   all(webtoon.zone_warnings(story.mock_cuts(n)["cuts"]) == []
       for n in range(8, 17)))

# 정식 작화가 길게 이어지면 눈이 쉴 자리가 없다. 상한이 아니라 **연속**을 본다 —
# 화 전체의 sd 개수는 여전히 이야기가 정한다.
run_cuts = [{"cut_number": i, "render_style": "normal"} for i in range(1, 12)]
ok("그림체 경고: normal 이 길게 이어지면 경고",
   any("정식 작화" in w for w in webtoon.render_warnings(run_cuts)),
   webtoon.render_warnings(run_cuts))
short = [{"cut_number": i, "render_style": "normal"} for i in range(1, 5)]
ok("그림체 경고: 짧게 이어지는 것은 조용하다",
   not any("정식 작화" in w for w in webtoon.render_warnings(short)))


# ---------------- 화면에 누가 있고 글자가 어디 놓이는가 (gate_frame) ----------
#
# 컷 서술의 산문에서 사람을 짐작하던 것을 필드로 옮겼다. 그래야 "주인공이 없는
# 컷에 주인공 시트를 붙여 조연이 주인공 얼굴로 그려지는" 일을 막을 수 있다.

def frame(**over):
    c = {"cut_number": 1, "composition": "none", "composition_note": "",
         "bubble_zone": "none", "characters_in_frame": [], "speaker": "",
         "speaker_side": "left",
         "dialogue": "", "narration": "", "thought": ""}
    c.update(over)
    return [c]


ok("화면 게이트: 정상 통과", webtoon.gate_frame(frame()) == [])
ok("화면 게이트: 인서트(빈 배열)는 정상이다",
   webtoon.gate_frame(frame(characters_in_frame=[])) == [])
ok("화면 게이트: 모르는 composition 은 탈락",
   any("composition" in f for f in webtoon.gate_frame(frame(composition="ots"))))
ok("화면 게이트: 모르는 bubble_zone 은 탈락",
   any("bubble_zone" in f for f in webtoon.gate_frame(frame(bubble_zone="아래"))))
ok("화면 게이트: characters_in_frame 이 배열이 아니면 탈락",
   any("배열이 아닙니다" in f
       for f in webtoon.gate_frame(frame(characters_in_frame="민시하"))))

# 이 검사가 핵심 — 화면에 없는 사람의 머릿속을 말풍선으로 띄울 수는 없다.
ok("화면 게이트: 속마음인데 화자가 화면에 없으면 탈락",
   any("나레이션으로 옮기세요" in f for f in webtoon.gate_frame(
       frame(thought="…또 왔네.", speaker="민시하",
             characters_in_frame=["하윤재"], bubble_zone="top"))))
ok("화면 게이트: 속마음의 화자가 화면에 있으면 통과",
   webtoon.gate_frame(frame(thought="…또 왔네.", speaker="민시하",
                            characters_in_frame=["민시하"],
                            bubble_zone="top")) == [])
ok("화면 게이트: 속마음인데 speaker 가 비면 탈락",
   any("speaker 가 비어" in f for f in webtoon.gate_frame(
       frame(thought="…또 왔네.", characters_in_frame=["민시하"],
             bubble_zone="top"))))

ok("화면 게이트: 글자가 있는데 bubble_zone 이 none 이면 탈락",
   any("bubble_zone 이 none" in f
       for f in webtoon.gate_frame(frame(dialogue="여기 앉으려고?",
                                         speaker="민시하",
                                         characters_in_frame=["민시하"]))))
ok("화면 게이트: 글자가 없으면 none 이 정상이다",
   webtoon.gate_frame(frame(sfx="찰칵")) == [])
# 효과음·화면글자는 말풍선이 아니라 자리를 비울 필요가 없다
ok("화면 게이트: 효과음만 있는 컷은 bubble_zone none 이어도 통과",
   webtoon.gate_frame(frame(sfx="지잉", screen_text="둘이 또 붙어있음ㅋㅋ")) == [])

ok("모의 컷: 화면 게이트를 통과한다",
   all(webtoon.gate_frame(story.mock_cuts(n)["cuts"]) == [] for n in range(8, 17)))


# ---------------- 8단계 — 컷이 확정된 뒤에 글자만 다시 쓴다 ----------------
#
# 7단계는 이미 화 전체를 한 번에 본다. 나눈 이유는 "전부 보게 하려고" 가 아니라,
# 컷을 하나씩 쓰면서 붙인 말이 그림의 설명이 되기 때문이다 —
# "여기 앉으려고?" / "커피 마실래요?" 가 그렇게 나왔다.

CARD = "[주인공] 민시하\n  그 한 사람: 하윤재 — 같은 학과 남후배\n"
ok("POV: 엔진 카드의 주인공을 읽는다", webtoon.pov_of(CARD) == "민시하")
ok("POV: 주인공이 없으면 빈 문자열", webtoon.pov_of("아무것도 없음") == "")


def w8cut(**over):
    c = {"cut_number": 1, "speaker": "", "dialogue": "", "narration": "",
         "thought": "", "sfx": "", "screen_text": "", "bubble_zone": "none",
         "characters_in_frame": ["민시하"]}
    c.update(over)
    return c


# -- 인물은 설정을 말하지 않는다
ok("8.5 게이트: 대사에 세계관 낱말이 있으면 탈락",
   any("세계관 낱말" in f for f in webtoon.gate_text_pass(
       [w8cut(dialogue="오늘은 내 '기록' 좀 쉴 차례야.", speaker="민시하")], "민시하")))
ok("8.5 게이트: 같은 낱말이 나레이션에 있는 것은 통과한다",
   webtoon.gate_text_pass(
       [w8cut(narration="이 학교에서는 기록이 사람보다 먼저 도착한다.")],
       "민시하") == [])
ok("8.5 게이트: 화면 글자의 세계관 낱말도 통과한다",
   webtoon.gate_text_pass([w8cut(screen_text="기록 열람 요청")], "민시하") == [])

# -- POV 잠금. 이건 취향이 아니라 질문 장부를 지키는 장치다.
ok("8.5 게이트: POV 가 아닌 인물의 속마음은 탈락",
   any("시점" in f for f in webtoon.gate_text_pass(
       [w8cut(thought="선배는 왜 저럴까.", speaker="하윤재",
              characters_in_frame=["하윤재"])], "민시하")))
ok("8.5 게이트: POV 인물의 속마음은 통과",
   webtoon.gate_text_pass(
       [w8cut(thought="…또 왔네.", speaker="민시하")], "민시하") == [])
ok("8.5 게이트: 속마음의 화자가 화면에 없으면 탈락",
   any("화면에" in f for f in webtoon.gate_text_pass(
       [w8cut(thought="…또 왔네.", speaker="민시하",
              characters_in_frame=["하윤재"])], "민시하")))

# -- 글자를 얹는다
base = [w8cut(cut_number=1, narration="원래 나레이션"),
        w8cut(cut_number=2, dialogue="원래 대사", speaker="민시하")]
notes = webtoon.apply_text_patch(base, [
    {"cut_number": 1, "narration": "3교시 끝. 다음 강의까지 40분.",
     "bubble_zone": "top"},
    {"cut_number": 2, "dialogue": "자리 저렇게 많은데.", "speaker": "민시하",
     "bubble_zone": "top"}])
ok("글자 얹기: 적힌 대로 바뀐다",
   base[0]["narration"] == "3교시 끝. 다음 강의까지 40분."
   and base[1]["dialogue"] == "자리 저렇게 많은데." and notes == [], notes)

# 빠진 컷의 글자를 지우면 모델의 실수 하나가 화의 일부를 통째로 무음으로 만든다.
keep = [w8cut(cut_number=1, narration="남아야 한다"),
        w8cut(cut_number=2, dialogue="이것도", speaker="민시하")]
notes = webtoon.apply_text_patch(keep, [{"cut_number": 1, "narration": "바뀜"}])
ok("글자 얹기: 빠진 컷은 7단계의 글자를 그대로 둔다",
   keep[1]["dialogue"] == "이것도" and any("적지 않았습니다" in n for n in notes),
   notes)
ok("글자 얹기: 없는 컷을 가리키면 메모만 남는다",
   any("없는 컷" in n for n in
       webtoon.apply_text_patch([w8cut()], [{"cut_number": 99, "dialogue": "x"}])))
ok("글자 얹기: 그림 지시는 손대지 못한다",
   (lambda c: (webtoon.apply_text_patch(
       c, [{"cut_number": 1, "description": "다른 그림", "shot": "클로즈업",
            "dialogue": "말만 바뀐다"}]),
       c[0].get("description"), c[0].get("shot"), c[0]["dialogue"]))(
       [w8cut(description="원래 그림", shot="원경")])[1:]
   == ("원래 그림", "원경", "말만 바뀐다"))

# -- 자리 보정
fix = [w8cut(cut_number=1, dialogue="말이 있다", speaker="민시하",
             bubble_zone="none"),
       w8cut(cut_number=2, bubble_zone="top")]
notes = webtoon.repair_bubble_zone(fix)
ok("자리 보정: 글자가 있는데 none 이면 채운다", fix[0]["bubble_zone"] == "top")
ok("자리 보정: 글자가 없는데 자리가 있으면 none 으로", fix[1]["bubble_zone"] == "none")
ok("자리 보정: 맞으면 아무것도 안 한다",
   webtoon.repair_bubble_zone([w8cut(dialogue="말", speaker="민시하",
                                     bubble_zone="bottom")]) == [])

# -- 경고 (막지 않는다)
mute = [w8cut(cut_number=i) for i in range(1, 7)]
ok("8.5 경고: 무음이 길게 이어지면 메모가 남는다",
   any("글자 없이" in w for w in webtoon.text_pass_warnings(mute, [])),
   webtoon.text_pass_warnings(mute, []))
ok("8.5 경고: 장면 첫 컷에 말이 없으면 메모가 남는다",
   any("시작되는 컷" in w for w in webtoon.text_pass_warnings(
       [w8cut(cut_number=1, sfx="툭"), w8cut(cut_number=2, dialogue="말",
                                              speaker="민시하")],
       [{"last_cut": 2}])))
ok("8.5 경고: 장면 첫 컷에 말이 있으면 조용하다",
   not any("시작되는 컷" in w for w in webtoon.text_pass_warnings(
       [w8cut(cut_number=1, narration="3교시 끝."),
        w8cut(cut_number=2, dialogue="말", speaker="민시하")],
       [{"last_cut": 2}])))

ok("모의 컷: 8.5 게이트를 통과한다",
   all(webtoon.gate_text_pass(story.mock_cuts(n)["cuts"], "모의주인공") == []
       for n in range(8, 17)))


# ---------------- 7단계 말의 밀도 — 세되 막지 않는다 ----------------
#
# 완성된 run 3개(643컷) 실측: 말 있는 컷 38/41/28% · 말 없는 컷 최장 12연속.
# 한동안 이걸 게이트로 막았다가 경고로 내렸다 — 비율을 강제하면 모델이
# 이야기가 아니라 숫자에 맞춰 대사를 넣는다. 무성으로 밀어붙이는 화도,
# 나레이션으로만 굴러가는 화도 정답일 수 있고 그 답이 막히면 안 된다.

def talky(speech, trans=None):
    """말이 있는 자리만 지정한 컷 목록. speech[i] 가 True 면 대사가 있다."""
    n = len(speech)
    trans = trans or (["장면"] + ["동작", "인물", "분위기", "순간"] * n)[:n]
    return [{"cut_number": i + 1, "transition": trans[i],
             "speaker": (("시하", "윤재")[i % 2] if speech[i] else ""),
             "dialogue": ("\"…\"" if speech[i] else ""),
             "narration": "", "thought": "", "sfx": "",
             "description": f"{i + 1}번 컷"} for i in range(n)]


sparse = talky([True, False, False, True, False, False,
                True, False, False, True])
ok("말: 밀도가 낮아도 되돌리지 않는다",
   webtoon.gate_dialogue(sparse) == [], webtoon.gate_dialogue(sparse))
ok("말 경고: 밀도가 낮으면 경고는 남긴다",
   any("있는 컷이" in x for x in webtoon.text_warnings(sparse)),
   webtoon.text_warnings(sparse))

quiet = talky([True, True, True, True, True, True,
               False, False, False, False, True, True])
ok("말 경고: 말 없는 컷이 길게 이어지면 경고",
   any("말 없이 4컷 연속" in x for x in webtoon.text_warnings(quiet)),
   webtoon.text_warnings(quiet))
ok("말: 3연속 정도는 아무 말도 하지 않는다",
   not any("말 없이" in x for x in webtoon.text_warnings(
       talky([True, True, False, False, False, True, True, True]))))

ok("말 경고: 첫 컷이 비면 경고",
   any("첫 컷" in x for x in webtoon.text_warnings(
       talky([False, True, True, True, False, True, True, True]))))

sc = talky([True, True, False, True, True, False, True, True],
           trans=["장면", "동작", "장면", "인물", "분위기", "동작", "인물", "동작"])
ok("말 경고: '장면' 전환 컷이 비면 경고",
   any("건너뛰는 자리" in x and "[3]" in x for x in webtoon.text_warnings(sc)),
   webtoon.text_warnings(sc))

# 효과음은 말이 아니다 — 네 칸을 한 덩어리로 세면 실태가 가려진다
ok("말 판정: sfx 만 있으면 말이 없는 컷", not webtoon.has_speech({"sfx": "쿵"}))
ok("말 판정: 나레이션만 있어도 말이 있는 컷",
   webtoon.has_speech({"narration": "사흘 뒤."}))
noisy = talky([True, False, False, True, False, False, True, False, False, True])
for c in noisy:
    if not c["dialogue"]:
        c["sfx"] = "쿵"
ok("말 경고: 효과음으로 채운 것은 말로 세지 않는다",
   any("있는 컷이" in x for x in webtoon.text_warnings(noisy)))

mc = story.mock_cuts(12)["cuts"]
ok("모의 컷: 말 경고가 없다", webtoon.text_warnings(mc) == [],
   webtoon.text_warnings(mc))


# ---------------- 나레이션이 한 가지 일만 하고 있는가 ----------------
#
# 실측: 나온 나레이션 셋이 전부 "늦은 오후, 라운지 한켠" 꼴의 시간·장소 표시였다.
# 세계 설명도, 1인칭 내면도, 나중에 아는 사실도 0회.
#
# 개수는 세지 않는다. 0개인 화도 정답일 수 있고, 하한을 두면 장면이 바뀔 때마다
# 배경 설명을 붙여 칸만 채운다 — 그게 바로 위 실패다.

def narr(*specs):
    """(나레이션, transition) → 컷 목록."""
    return [{"cut_number": i + 1, "narration": n, "transition": t,
             "dialogue": "\"…\"", "speaker": "시하", "thought": "", "sfx": "",
             "description": f"{i + 1}번 컷"}
            for i, (n, t) in enumerate(specs)]


def narr_warn(cuts):
    # "나레이션" 은 다른 경고 문구에도 나온다(혼잣말 → 속마음/나레이션 권유).
    # 이 검사가 보려는 한 줄만 집는다.
    return [x for x in webtoon.prose_warnings(cuts)
            if "전부 장면이 바뀌는 컷에만" in x]


ok("나레이션 경고: 전부 장면 표시뿐이면 경고 (3개 이상)",
   narr_warn(narr(("늦은 오후, 라운지", "장면"), ("저녁, 동아리방", "장면"),
                  ("늦은 밤, 골목", "장면"))), )
ok("나레이션 경고: 하나라도 다른 자리에 있으면 조용",
   not narr_warn(narr(("늦은 오후, 라운지", "장면"), ("저녁, 동아리방", "장면"),
                      ("나는 그때 이미 알고 있었다", "동작"))))
ok("나레이션 경고: 두 개뿐이면 따지지 않는다 (진짜 두 번 바뀐 것일 수 있다)",
   not narr_warn(narr(("늦은 오후", "장면"), ("늦은 밤", "장면"))))
ok("나레이션 경고: 아예 없어도 경고하지 않는다 (하한을 두면 칸만 채운다)",
   not narr_warn(narr(("", "장면"), ("", "동작"), ("", "인물"))))

mc = story.mock_cuts(12)["cuts"]
spots = [(c["cut_number"], c["transition"]) for c in mc if c["narration"]]
ok("모의 컷: 나레이션을 장면 전환에만 달지 않는다",
   any(t != "장면" for _, t in spots), spots)
ok("모의 컷: 나레이션 경고가 없다", not narr_warn(mc), narr_warn(mc))

# 세계관이 카드에 실려야 나레이션이 쓸 재료가 생긴다
_card = webtoon.build_engine_card(
    {"name": "민시하"}, {"logline": "L"}, "한 줄", [],
    seed={"genre": "로맨스 판타지", "world": "엘젠하르트 제국. 황후는 열여섯에 정해진다."})
ok("엔진 카드: 세계관을 싣는다", "[세계] 엘젠하르트 제국" in _card)
ok("엔진 카드: 장르를 싣는다", "[장르] 로맨스 판타지" in _card)
ok("엔진 카드: seed 가 없어도 카드가 만들어진다 (옛 run)",
   "[로그라인]" in webtoon.build_engine_card({"name": "민시하"}, {"logline": "L"},
                                             "한 줄", []))


# ---------------- 7단계 대화 구조 (주고받는가) ----------------
#
# 밀도를 채운 다음에도 남는 문제다. 실측에서 대사 한 줄의 길이는 평균 21~26자로
# 짧지 않았다. 고장 난 것은 구조다 — 화의 절반에서 대사가 한 줄씩 고립되어 있었고
# (연속 최대 1컷), 아무도 아무에게 대답하지 않았다.
#   화당 '대사가 연속으로 이어진 최대 길이'  1컷뿐 8/2/10화 · 2컷 6/9/6화 · 3컷+ 1/6/1화

def said(*lines):
    """(화자, 대사) 목록 → 컷. 빈 문자열이면 대사 없는 컷."""
    return [{"cut_number": i + 1,
             "speaker": s, "dialogue": d,
             "narration": ("사흘 뒤." if not d else ""),
             "thought": "", "sfx": "", "description": f"{i + 1}번 컷"}
            for i, (s, d) in enumerate(lines)]


good = said(("시하", "아, 강도윤한테 또 연락 왔어."),
            ("친구A", "걔한테 또? 이번 주만 몇 번째야."),
            ("친구B", "야, 걔 이 정도면 스토커 아니야?"),
            ("", ""), ("신입생", "왜요 선배들? 무슨 일인데요?"),
            ("친구A", "쟤 따라다니는 후배 하나 있거든."))
ok("대화 게이트: 주고받으면 통과", webtoon.gate_dialogue(good) == [],
   webtoon.gate_dialogue(good))

# 실제로 나왔던 모양 — 한 줄씩 떨어져 있고 아무도 대답하지 않는다
lonely = said(("시하", "아, 강도윤한테 연락 왔어."), ("", ""), ("", ""),
              ("윤재", "선배."), ("", ""), ("동기", "둘이 또 같이 있네."))
ok("대화 게이트: 대사가 고립돼도 되돌리지 않는다 (나레이션이라는 답이 있다)",
   webtoon.gate_dialogue(lonely) == [], webtoon.gate_dialogue(lonely))
ok("서술 경고: 대사가 한 줄씩 떨어져 있으면 경고",
   any("한 줄씩 떨어져" in x for x in webtoon.prose_warnings(lonely)),
   webtoon.prose_warnings(lonely))

# 혼자 말하는 화는 잘못이 아니다 — 나레이션·속마음으로 굴러가는 웹툰이 많다.
# 다만 그 혼잣말을 **말풍선**으로 계속 하면 어색하므로 경고만 남긴다.
mono = said(("시하", "왜 자꾸 따라와."), ("시하", "그만 좀 해."),
            ("", ""), ("시하", "진짜 마지막이야."))
ok("대화 게이트: 혼자 말해도 되돌리지 않는다",
   webtoon.gate_dialogue(mono) == [], webtoon.gate_dialogue(mono))
w = webtoon.prose_warnings(mono)
ok("서술 경고: 혼잣말이 전부 말풍선이면 thought·나레이션을 권한다",
   any("thought(속마음)나 나레이션" in x for x in w), w)

nameless = said(("", "누가 말하는지 모르는 대사."), ("친구A", "그러게."))
r = webtoon.gate_dialogue(nameless)
ok("대화 게이트: speaker 가 비면 탈락", any("speaker 가 비어" in f for f in r), r)

ok("대화 게이트: 대사가 아예 없으면 아무것도 막지 않는다 (나레이션 중심 화)",
   webtoon.gate_dialogue(said(("", ""), ("", ""))) == [])

# 명부에 없는 사람이 말하면 경고 (반려는 아니다 — 스쳐가는 인물이 있다)
stranger = said(("시하", "가자."), ("정체불명", "잠깐."))
w = webtoon.prose_warnings(stranger, {"시하", "윤재"})
ok("서술 경고: 명부에 없는 화자를 경고한다",
   any("「정체불명」" in x for x in w), w)
ok("서술 경고: 명부에 있으면 조용하다",
   not any("명부에 없는" in x
           for x in webtoon.prose_warnings(stranger, {"시하", "정체불명"})))
ok("서술 경고: 명부를 모르면 화자를 따지지 않는다",
   not any("명부에 없는" in x for x in webtoon.prose_warnings(stranger)))

card = ("[주인공] 민시하\n  직함: 2학년\n"
        "  그 한 사람: 하윤재 — 같은 학과 남후배\n")
eps = [{"new_cast": [{"name": "학과 동기"}]}]
ok("화자 명부: 카드의 주인공·그 한 사람 + 명부의 조연을 모은다",
   webtoon.known_speakers(card, eps) == {"민시하", "하윤재", "학과 동기"},
   webtoon.known_speakers(card, eps))

mc = story.mock_cuts(12)["cuts"]
ok("모의 컷: 대화 게이트를 통과한다", webtoon.gate_dialogue(mc) == [],
   webtoon.gate_dialogue(mc))
ok("모의 컷: 대사에 화자가 있다",
   all(c["speaker"] for c in mc if c["dialogue"]))


# ---------------- 7단계 카메라 세 축 (거리·앵글·전환) ----------------
#
# 이 게이트가 없던 시절의 실측이 근거다. 완성된 run 3개·643컷에서
# 클로즈업+바스트 60% / 익스트림 클로즈업 0.5~2% / 앙각 1~4% / 인물 없는 컷 이름 없음.
# 프롬프트에는 그때도 "카메라를 다양하게" 라고 적혀 있었다 — 세지 않으면 안 지켜진다.

def camd(shots, angles=None, trans=None):
    """거리·앵글·전환만 갈아 끼운 컷 목록."""
    n = len(shots)
    angles = angles or ["수평", "부감", "앙각"] * n
    trans = trans or (["장면"] + ["동작", "인물", "분위기", "순간"] * n)
    return ([i + 1 for i in range(n)], list(shots),
            list(angles[:n]), list(trans[:n]))


OKAY = ["원경", "중간", "바스트", "인서트", "전신", "클로즈업", "익스트림", "중간"]
ok("카메라 게이트: 정상 통과", webtoon.gate_camera(*camd(OKAY)) == [],
   webtoon.gate_camera(*camd(OKAY)))

# 얼굴만으로 채운 화 — 실제 산출물이 이 모양이었다
faces = ["원경", "바스트", "클로즈업", "바스트", "인서트", "클로즈업",
         "바스트", "클로즈업"]
r = webtoon.gate_camera(*camd(faces))
ok("카메라 게이트: 얼굴 컷이 절반을 넘으면 탈락",
   any("얼굴 컷" in f for f in r), r)
ok("카메라 게이트: 되돌릴 때 바꿀 컷을 지목한다",
   any("인서트(사물·손)" in f for f in r), r)

# 수단의 하한(인서트·원경·분위기 최소 1개)은 게이트에서 뺐다. 화마다 맞는 답이
# 다르고, 강제하면 모델이 자리를 만들어 끼워 넣는다 — 개수는 맞고 장면은 어색해진다.
ok("카메라 게이트: 인서트가 없어도 되돌리지 않는다",
   webtoon.gate_camera(*camd(["원경", "중간", "바스트", "전신", "중간",
                              "클로즈업", "전신", "중간"])) == [])
ok("카메라 게이트: 원경이 없어도 되돌리지 않는다",
   webtoon.gate_camera(*camd(["중간", "전신", "바스트", "인서트", "전신",
                              "클로즈업", "중간", "중간"])) == [])

r = webtoon.gate_camera(*camd(["원경", "중간", "중간", "중간", "중간",
                               "인서트", "전신", "클로즈업"]))
ok("카메라 게이트: 같은 거리 4연속 탈락", any("4컷 연속" in f for f in r), r)

r = webtoon.gate_camera(*camd(OKAY, angles=["수평"] * 8))
ok("카메라 게이트: 전부 눈높이면 탈락", any("수평인 컷이" in f for f in r), r)

r = webtoon.gate_camera(*camd(OKAY, angles=["기울임", "수평", "기울임", "부감",
                                            "기울임", "수평", "앙각", "수평"]))
ok("카메라 게이트: 기울임 3개면 탈락", any("기울임인 컷이" in f for f in r), r)

r = webtoon.gate_camera(*camd(OKAY, trans=["동작"] + ["인물", "분위기", "장면"] * 8))
ok("카메라 게이트: 첫 컷이 '장면' 이 아니면 탈락",
   any("첫 컷" in f for f in r), r)

r = webtoon.gate_camera(*camd(OKAY, trans=["장면", "동작", "동작", "동작", "동작",
                                           "인물", "분위기", "동작"]))
ok("카메라 게이트: 같은 전환 4연속 탈락", any("transition 이" in f and "연속" in f
                                              for f in r), r)

r = webtoon.gate_camera(*camd(OKAY, trans=["장면", "동작", "장면", "동작",
                                           "장면", "동작", "장면", "동작"]))
ok("카메라 게이트: 전환이 2종뿐이면 탈락", any("2종뿐" in f for f in r), r)
ok("카메라 게이트: '분위기' 가 없어도 되돌리지 않는다 (경고로 남긴다)",
   not any("'분위기'" in f for f in
           webtoon.gate_camera(*camd(OKAY, trans=["장면", "동작", "인물", "순간",
                                                  "동작", "인물", "장면", "동작"]))))

# 대신 경고로 남는다 — 사람이 보고 판단할 몫이다
bare = [{"cut_number": i + 1, "shot": s_, "angle": "수평", "transition": t,
         "dialogue": "", "narration": "", "thought": "", "sfx": "",
         "description": f"{i + 1}번 컷"}
        for i, (s_, t) in enumerate(zip(["중간", "바스트", "전신", "클로즈업"],
                                        ["장면", "동작", "인물", "동작"]))]
w = webtoon.prose_warnings(bare)
ok("서술 경고: 인서트가 없으면 경고", any("인물 없는 컷(인서트)" in x for x in w), w)
ok("서술 경고: 원경이 없으면 경고", any("원경 컷이 하나도" in x for x in w), w)
ok("서술 경고: '분위기' 전환이 없으면 경고", any("'분위기'" in x for x in w), w)

r = webtoon.gate_layout([{"cut_number": 1, "size": "wide", "beat": "setup",
                          "render_style": "normal", "shot": "부감",
                          "angle": "수평", "transition": "장면"}])
ok("카메라 게이트: 모르는 거리 값 탈락", any("shot" in f for f in r), r)
r = webtoon.gate_layout([{"cut_number": 1, "size": "wide", "beat": "setup",
                          "render_style": "normal", "shot": "원경",
                          "angle": "로우앵글", "transition": "장면"}])
ok("카메라 게이트: 모르는 앵글 값 탈락", any("angle" in f for f in r), r)
r = webtoon.gate_layout([{"cut_number": 1, "size": "wide", "beat": "setup",
                          "render_style": "normal", "shot": "원경",
                          "angle": "수평", "transition": "aspect"}])
ok("카메라 게이트: 모르는 전환 값 탈락", any("transition" in f for f in r), r)

mc = story.mock_cuts(12)["cuts"]
ok("모의 컷: 거리·앵글·전환을 낸다",
   all(x["shot"] in webtoon.SHOTS and x["angle"] in webtoon.ANGLES
       and x["transition"] in webtoon.TRANSITIONS for x in mc))
ok("모의 컷: 얼굴 비율이 상한 아래다",
   webtoon.camera_histogram(mc)["face_ratio"] <= webtoon.MAX_FACE_RATIO,
   webtoon.camera_histogram(mc))
ok("모의 컷: 서술에 카메라 낱말이 없다",
   not any(k in x["description"] for x in mc for k in webtoon.CAMERA_WORDS))

# -- 여백은 이제 전환 유형을 본다 (예전에는 beat 만 봤다)
def gapped(beats, trans, renders=None):
    n = len(beats)
    c = [{"cut_number": i + 1, "size": "tall" if i % 2 else "normal",
          "beat": beats[i], "transition": trans[i],
          "render_style": (renders[i] if renders else "normal"),
          "shot": "중간", "angle": "수평"} for i in range(n)]
    webtoon.derive_layout(c)
    return [x["gap_after"] for x in c]


g = gapped(["setup", "build", "build", "hold", "build", "turn", "release", "hold"],
           ["장면", "동작", "동작", "장면", "동작", "동작", "동작", "동작"])
ok("여백: 장면이 바뀌기 직전은 벌어진다", g[2] == 2, g)

g = gapped(["setup", "build", "build", "build", "build", "turn", "release", "hold"],
           ["장면", "동작", "순간", "순간", "동작", "동작", "동작", "동작"])
ok("여백: '순간' 전환 직전은 붙는다", g[1] == 0 and g[2] == 0, g)

g = gapped(["setup", "build", "build", "hold", "build", "turn", "release", "hold"],
           ["장면", "동작", "분위기", "동작", "동작", "동작", "동작", "동작"])
ok("여백: '분위기' 전환 직전은 머무른다", g[1] == 2, g)

g = gapped(["setup", "build", "build", "hold", "build", "release", "build", "hold"],
           ["장면", "동작", "인물", "분위기", "동작", "동작", "인물", "동작"],
           renders=["normal"] * 5 + ["bleed"] + ["normal"] * 2)
ok("여백: 통컷 직전은 화당 최대 여백", g[4] == webtoon.MAX_GAP, g)


# ---------------- 5단계 무대 (컷 단조로움의 상류 원인) ----------------
#
# 컷이 얼굴 나열이 되는 가장 큰 이유는 7단계 어휘가 아니라 **그릴 것이 없어서**다.
# 엔진 카드에는 인물 외형 4줄·표정 6종·색 6개가 실리는데 장소는 한 줄이고
# 시간대·소품은 아예 없다. 무대를 회차마다 정하게 하는 것이 그 상류 수리다.

ok("무대: 정상 통과", webtoon.gate_setting(setting(), "1화") == [],
   webtoon.gate_setting(setting(), "1화"))
ok("무대: 아예 없으면 탈락",
   any("setting 이 없습니다" in f for f in webtoon.gate_setting(None, "1화")))
# 날씨·광원까지 검사한다. 그림은 Scene 을 한 장씩 따로 굽기 때문에, 여기가 비면
# 그리는 쪽이 매번 새로 정해서 같은 화 안에서 낮이었다 밤이 된다.
for key in webtoon.SETTING_REQUIRED:
    r = webtoon.gate_setting(setting(**{key: ""}), "1화")
    ok(f"무대: {webtoon.SETTING_LABEL[key]} 가 비면 탈락",
       any(f"setting.{key}" in f for f in r), r)
# ---- 상태 카드 -------------------------------------------------------------
# 외형(명부)은 안 바뀌지만 이야기는 몸에 자국을 남긴다. 3화에서 부러진 팔이
# 4화에 멀쩡하면 독자에게는 그 부상이 취소된 것이다.
_ss = webtoon.SeriesState(run_id="t")
_ss.add(3, 1, dict(episode(), setting=setting(), state_changes=[
    {"who": "윤아", "state": "왼쪽 손등에 거즈", "until": "5화까지"}]))
ok("상태 카드: 화가 남긴 자국을 쌓는다",
   _ss.status and _ss.status[0]["who"] == "윤아"
   and _ss.status[0]["since_episode"] == 3
   and _ss.status[0]["until"] == "5화까지", _ss.status)
_ss.add(4, 1, dict(episode(), setting=setting(), state_changes=[
    {"who": "윤아", "state": "머리를 턱선까지 잘랐다"}]))
ok("상태 카드: 뒤에 온 것이 앞의 것을 지우지 않는다", len(_ss.status) == 2)
ok("상태 카드: until 을 안 적으면 계속이다", _ss.status[1]["until"] == "계속")
_ss.add(5, 1, dict(episode(), setting=setting(), state_changes=[
    {"who": "윤아", "state": "머리를 턱선까지 잘랐다"}]))
ok("상태 카드: 같은 상태를 두 번 쌓지 않는다", len(_ss.status) == 2)
ok("상태 카드: 사람별로 꺼낼 수 있다",
   len(_ss.status_of("윤아")) == 2 and _ss.status_of("없는사람") == [])
ok("상태 카드: 바뀐 게 없으면 안 쌓는다",
   len(webtoon.SeriesState().status) == 0)

# 그림 단계에는 명부의 고정 외형밖에 없다. 카드에 안 실으면 반창고를 못 그린다.
_sb = webtoon.status_block(_ss.status)
ok("상태 카드: 엔진 카드에 실린다",
   any("거즈" in l for l in _sb) and any("3화부터" in l for l in _sb)
   and any("5화까지" in l for l in _sb), _sb)
ok("상태 카드: '계속' 이면 기간을 안 붙인다",
   not any("계속" in l for l in _sb), _sb)
ok("상태 카드: 없으면 빈 절을 만들지 않는다", webtoon.status_block([]) == [])
ok("상태 카드: 5단계 브리핑에도 보인다",
   "거즈" in _ss.brief(webtoon.Ledger("q")))
import json as _sj
ok("상태 카드: 저장·복원된다",
   _sj.loads(_sj.dumps(_ss.as_dict()))["status"][0]["who"] == "윤아"
   and webtoon.SeriesState().status == [])

r = webtoon.gate_setting(setting(props=["컵"]), "1화")
ok("무대: 사물이 1개면 탈락", any("props 가 1개" in f for f in r), r)
r = webtoon.gate_setting(setting(props=[]), "1화")
ok("무대: 사물이 비면 탈락", any("props 가 0개" in f for f in r), r)

pay = {"episodes": [dict(episode(), setting=None)]}
webtoon.assign_ids(pay, webtoon.Ledger("EQ", cap=5))
ok("5단계: 무대가 없는 화는 되돌린다",
   any("setting" in f for f in webtoon.gate_episodes_shape(
       pay, webtoon.Ledger("EQ", cap=5))))

# 장소는 연재 명부에 쌓인다 — 매 화 새 장소를 만들면 세계가 얇아진다
st = webtoon.SeriesState(run_id="r")
st.add(1, 1, episode(stage=setting(place="소방서 3층 휴게실")))
st.add(2, 1, episode(stage=setting(place="지하 주차장")))
st.add(3, 1, episode(stage=setting(place="소방서 3층 휴게실")))
ok("연재 명부: 장소를 쌓고 중복은 한 번만 센다",
   [p["place"] for p in st.places] == ["소방서 3층 휴게실", "지하 주차장"], st.places)
ok("연재 명부: 이미 나온 장소를 다음 화에 보여준다",
   "이미 나온 장소" in st.brief(webtoon.Ledger("EQ", cap=5)))
ok("연재 명부: 장소가 저장·복원된다",
   webtoon.SeriesState(**{k: v for k, v in st.as_dict().items()
                          if k in ("run_id", "episodes", "cast", "facts",
                                   "places")}).places == st.places)


# ---------------- 7단계 코드 수리 (안전망) ----------------
#
# 실제 실행에서 6회 연속 게이트 실패가 났다. size 를 고치면 beat 가 깨지고 beat 를
# 고치면 size 가 깨지는 두더지잡기였다. "같은 값이 4번 이어지지 않게 한다" 는 판단이
# 아니라 산수이므로 코드가 고친다. beat 는 건드리지 않는다 — scene_break 가 거기
# 얹혀 있고, "독자의 상태" 라서 그림에서 되짚을 근거가 없다.

def sized(sizes, beats=None, shots=None, dialogues=None):
    beats = beats or (["setup"] + ["build", "hold", "release", "turn"]
                      * len(sizes))[:len(sizes)]
    return [{"cut_number": i + 1, "size": z, "beat": beats[i],
             "shot": (shots[i] if shots else "바스트"),
             "dialogue": (dialogues[i] if dialogues else ""),
             "description": f"{i + 1}번 컷"}
            for i, z in enumerate(sizes)]


c = sized(["wide", "tall"] + ["normal"] * 9 + ["impact"],
          shots=["원경", "전신", "클로즈업", "바스트", "전신", "클로즈업", "바스트",
                 "원경", "전신", "바스트", "클로즈업", "익스트림"])
notes = webtoon.repair_sizes(c)
after = [x["size"] for x in c]
ok("크기 수리: 9연속을 끊는다", webtoon.longest_run(after)[1] <= 3, after)
ok("크기 수리: 고친 자리를 메모로 남긴다", len(notes) >= 2, notes)
ok("크기 수리: 거리에 맞는 값을 고른다",
   any("거리" in n for n in notes), notes)
ok("크기 수리: 마지막 컷은 건드리지 않는다", after[-1] == "impact", after)

c = sized(["wide"] + ["normal"] * 10 + ["tall"], shots=[""] * 12)
webtoon.repair_sizes(c)
after = [x["size"] for x in c]
ok("크기 수리: 거리가 없어도 끊는다", webtoon.longest_run(after)[1] <= 3, after)
ok("크기 수리: 스팅어 크기 규칙을 지킨다", after[-1] == "tall", after)

c = sized(["impact", "impact"] + ["normal"] * 5 + ["tall"], shots=["클로즈업"] * 8)
webtoon.repair_sizes(c)
after = [x["size"] for x in c]
ok("크기 수리: impact 상한을 넘기지 않는다",
   after.count("impact") <= webtoon.MAX_IMPACT, after)

c = sized(["wide", "normal", "tall", "normal", "wide", "normal", "tall", "impact"])
before = [x["size"] for x in c]
ok("크기 수리: 멀쩡하면 손대지 않는다",
   webtoon.repair_sizes(c) == [] and [x["size"] for x in c] == before)

# 스팅어 크기 — 4연속을 고친 뒤 남던 유일한 size 위반이다
c = sized(["wide", "normal", "tall", "normal", "wide", "normal", "tall", "normal"])
webtoon.repair_sizes(c)
ok("크기 수리: 마지막 컷이 납작하면 세운다", c[-1]["size"] == "tall", c[-1]["size"])

c = sized(["wide", "normal", "tall", "normal", "wide", "normal", "tall", "normal"],
          shots=["원경"] * 7 + ["익스트림"])
webtoon.repair_sizes(c)
ok("크기 수리: 스팅어가 클로즈업이면 impact 로", c[-1]["size"] == "impact", c[-1]["size"])

c = sized(["impact", "normal", "tall", "normal", "impact", "normal", "tall", "wide"],
          shots=["원경"] * 7 + ["클로즈업"])
webtoon.repair_sizes(c)
ok("크기 수리: impact 가 이미 2개면 tall 로",
   c[-1]["size"] == "tall" and [x["size"] for x in c].count("impact") == 2)

c = sized(["wide", "huge", "normal", "tall"])
ok("크기 수리: 값이 깨져 있으면 게이트에 맡긴다", webtoon.repair_sizes(c) == [])

c = sized(["normal"] * 6 + ["tall"], beats=["setup"] + ["build"] * 5 + ["hold"])
webtoon.repair_sizes(c)
ok("크기 수리: beat 는 건드리지 않는다",
   [x["beat"] for x in c] == ["setup"] + ["build"] * 5 + ["hold"])

# -- 재시도 피드백에 실을 beat 수열
c = sized(["normal"] * 12,
          beats=["setup", "build", "build", "build", "build", "turn",
                 "release", "hold", "build", "release", "hold", "turn"],
          dialogues=["", "\"…\"", "", "\"…\"", "", "\"…\"",
                     "", "", "\"…\"", "", "", ""])
fixed = webtoon.suggest_beat_sequence(c).split()
ok("beat 제안: 4연속을 끊은 수열을 준다",
   webtoon.longest_run(fixed)[1] <= webtoon.MAX_SAME_BEAT_RUN, fixed)
ok("beat 제안: 길이가 컷 수와 같다", len(fixed) == 12, fixed)
ok("beat 제안: 마지막 컷의 beat 를 바꾸지 않는다", fixed[-1] == "turn", fixed)
ok("beat 제안: 침묵 컷을 먼저 hold 로 돌린다", fixed[2] == "hold", fixed)

c = sized(["normal"] * 12, beats=["setup"] + ["build"] * 8 + ["turn", "release", "hold"])
fixed = webtoon.suggest_beat_sequence(c).split()
ok("beat 제안: 8연속도 끊는다",
   webtoon.longest_run(fixed)[1] <= webtoon.MAX_SAME_BEAT_RUN, fixed)

c = sized(["wide", "normal", "tall", "normal", "impact", "normal", "tall", "hold"][:8],
          beats=["setup", "build", "hold", "build", "turn", "release", "build", "hold"])
ok("beat 제안: 멀쩡하면 그대로 돌려준다",
   webtoon.suggest_beat_sequence(c).split()
   == ["setup", "build", "hold", "build", "turn", "release", "build", "hold"])

# -- 모의 컷이 수열을 함께 낸다 (프롬프트가 요구하는 형식)
mock = story.mock_cuts(12)
ok("모의 컷: beat_sequence 를 낸다",
   mock["beat_sequence"].split() == [x["beat"] for x in mock["cuts"]])
ok("모의 컷: size_sequence 를 낸다",
   mock["size_sequence"].split() == [x["size"] for x in mock["cuts"]])

ok("7단계: scene_spans 가 경계로 자른다",
   webtoon.scene_spans([False, True, False, False, True]) == [(0, 1, 2), (2, 4, 3)])
ok("7단계: 경계가 없으면 마지막에서 한 번 자른다",
   webtoon.scene_spans([False, False, False]) == [(0, 2, 3)])
ok("7단계: longest_run 이 가장 긴 연속 구간을 찾는다",
   webtoon.longest_run(["a", "b", "b", "b", "a"]) == ("b", 3, 1))

hist = webtoon.size_histogram(cuts()["cuts"])
ok("7단계: size_histogram 이 크기 분포를 센다",
   sum(hist.values()) == 10 and hist["impact"] == 2, hist)
ok("7단계: 모의 컷의 크기가 한 종류가 아니다",
   len([k for k, v in hist.items() if v]) >= 3, hist)

# ---------------- 토큰·비용 기록 ----------------
#
# 여기서 지키려는 것은 "숫자가 맞다" 보다 **"모르면 모른다고 한다"** 이다.
# 모르는 단가를 0 으로 세면 합계가 조용히 낮아지고, 그 합계를 믿고 예산을 잡는다.

PRICES = {"models": {
    "test-model": {"input": 2.0, "output": 8.0, "cache_read": 0.5, "cache_write": 0.0},
    "test-mini": {"input": 0.4, "output": 1.6, "cache_read": 0.1, "cache_write": 0.0},
}}

ok("단가: 100만 토큰당 USD 로 계산한다",
   story.cost_of("test-model", {"input": 1_000_000, "output": 0}, PRICES)["total"] == 2.0)
ok("단가: 입력·출력 단가를 따로 적용한다",
   story.cost_of("test-model",
                 {"input": 500_000, "output": 250_000}, PRICES)["total"] == 3.0)
ok("단가: 캐시 읽기는 싸게 센다",
   story.cost_of("test-model", {"cache_read": 1_000_000}, PRICES)["total"] == 0.5)

ok("단가: 모르는 모델은 0 이 아니라 None",
   story.cost_of("no-such-model", {"input": 1_000_000}, PRICES) is None)

# 날짜 고정본은 기본 이름의 단가를 쓴다.
ok("단가: 날짜 스냅샷(-20251001)은 기본 이름을 따라간다",
   story.price_for("test-model-20251001", PRICES) is story.price_for("test-model", PRICES))
ok("단가: 날짜 스냅샷(-2025-04-14)도 따라간다",
   story.price_for("test-model-2025-04-14", PRICES) is story.price_for("test-model", PRICES))
# 여기가 핵심이다. 그냥 앞자리로 맞추면 test-mini 가 test-model 단가를 물려받는데,
# 그러면 5배 비싸게 계산되고 아무도 눈치채지 못한다.
ok("단가: 이름이 겹쳐도 다른 모델의 단가를 물려받지 않는다",
   story.price_for("test-model-mini", PRICES) is None)

u = story.Usage()
u.add({"input": 1000, "output": 500}, model="test-model", stage="P1")
u.add({"input": 2000, "output": 1000}, model="test-mini", stage="P3")
ok("사용량: 호출마다 한 줄씩 남는다", len(u.records) == 2, u.records)
ok("사용량: 어떤 단계·어떤 모델인지 남는다",
   u.records[0]["stage"] == "P1" and u.records[0]["model"] == "test-model")
ok("사용량: 모델별로 나눠 센다",
   u.by_model["test-model"]["input"] == 1000
   and u.by_model["test-mini"]["input"] == 2000, u.by_model)
ok("사용량: 합계 토큰은 그대로", u.total == 4500)

# 모델이 섞이면 합계 토큰만으로는 비용을 못 낸다 — 단가가 다르기 때문이다.
u_cost = {n: story.cost_of(n, t, PRICES)["total"] for n, t in u.by_model.items()}
ok("비용: 모델마다 제 단가로 계산한다",
   abs(u_cost["test-model"] - 0.006) < 1e-9
   and abs(u_cost["test-mini"] - 0.0024) < 1e-9, u_cost)

ok("서식: 아는 비용은 금액으로", story.cost_text(0.044) == "$0.0440")
ok("서식: 부분 합계는 부분이라고 말한다",
   "단가 없음" in story.cost_text(0.044, "단가 없음: x"))
ok("서식: 전부 모르면 금액을 만들지 않는다",
   story.cost_text(None) == "비용 미상")

# 모의 실행이 실제로 원장을 남기는지 (경로·형식까지)
import json as _json
import tempfile as _tempfile
from pathlib import Path as _Path

with _tempfile.TemporaryDirectory() as _tmp:
    _p = _Path(_tmp) / "calls.jsonl"
    u.write_calls(_p)
    _rows = [_json.loads(x) for x in _p.read_text(encoding="utf-8").splitlines()]
    ok("원장: 한 줄에 한 호출", len(_rows) == 2, _rows)
    ok("원장: 토큰이 입력·출력으로 나뉘어 있다",
       _rows[0]["tokens"]["input"] == 1000 and _rows[0]["tokens"]["output"] == 500)

# 단가표 파일 자체가 읽히는지. 여기 없으면 실제 실행에서 비용이 전부 미상이 된다.
_prices = story.load_prices()
ok("단가표: prices.json 이 읽힌다", bool(_prices.get("models")), list(_prices)[:3])
ok("단가표: 기록한 날짜가 있다", bool(_prices.get("_as_of")))

# 지금 .env 가 가리키는 모델에 단가가 있는지는 **게이트가 아니다.** 코드 결함이
# 아니라 각자의 설정이라, 여기서 실패시키면 남의 .env 때문에 빌드가 깨진다.
# 대신 알려는 준다 — 단가가 없으면 실행 기록에 비용이 계속 미상으로 남는다.
if story.price_for(story.DEFAULT_MODEL) is None:
    print(f"      (참고) 지금 기본 모델 '{story.DEFAULT_MODEL}' 의 단가가 "
          f"{story.PRICES_FILE} 에 없습니다 — 비용이 '미상'으로 기록됩니다.")

# ---------------- 캐릭터 입력 (--character) ----------------
#
# 여기서 지키려는 것은 **"작가가 준 것은 건드리지 않는다"** 이다.
# 자기 캐릭터를 넣었는데 이름이나 장르가 슬쩍 바뀌어 있으면, 그 도구는 못 쓴다.

import json as _j
import tempfile as _tf
from pathlib import Path as _P


def _charfile(tmp, obj, name="c.json"):
    p = _P(tmp) / name
    p.write_text(_j.dumps(obj, ensure_ascii=False), encoding="utf-8")
    return p


with _tf.TemporaryDirectory() as _tmp:
    # 캐릭터 자유 서술 하나 — 이것만으로 시작돼야 한다.
    r = story.read_character(_charfile(_tmp, {"name": "윤아", "character": "구조대원"}))
    ok("캐릭터: 자유 서술만으로 읽힌다", "구조대원" in r["character"], r["character"])
    ok("캐릭터: 이름이 재료에 들어간다", "윤아" in r["character"])
    ok("캐릭터: 안 준 칸은 비어 있다 (코드가 채우지 않는다)",
       r["genre"] == "" and r["world"] == "" and r["one_line"] == "", r)

    # 항목별 필드만 줘도 된다.
    r = story.read_character(_charfile(
        _tmp, {"fields": {"직업": "심사관", "약점": "판정을 못 뒤집는다", "빈칸": ""}}))
    ok("캐릭터: 항목별 필드만으로도 읽힌다",
       "직업: 심사관" in r["character"] and "약점" in r["character"], r["character"])
    ok("캐릭터: 빈 필드는 넘기지 않는다", "빈칸" not in r["character"])

    # 이름이 없으면 파일 이름을 쓴다.
    r = story.read_character(_charfile(_tmp, {"character": "x"}, "차도경.json"))
    ok("캐릭터: 이름이 없으면 파일 이름을 쓴다", r["_name"] == "차도경", r["_name"])

    # _ 로 시작하는 키는 주석이다. 템플릿 설명문이 캐릭터 설정으로 새면 안 된다.
    r = story.read_character(_charfile(
        _tmp, {"character": "본문", "_설명": "이건 주석입니다 절대 캐릭터가 아님"}))
    ok("캐릭터: _ 로 시작하는 키(주석)는 모델에게 안 넘어간다",
       "주석입니다" not in r["character"], r["character"])

    # 아무것도 없으면 명확히 멈춘다.
    try:
        story.read_character(_charfile(_tmp, {"genre": "hunter"}, "empty.json"))
        ok("캐릭터: 캐릭터가 없으면 멈춘다", False, "안 멈췄음")
    except SystemExit as e:
        ok("캐릭터: 캐릭터가 없으면 멈춘다", "photo" in str(e), str(e))

    # 세계관: 프리셋 · 직접 입력 · 둘 다
    r = story.read_character(_charfile(
        _tmp, {"character": "x", "world": {"preset": "hunter_gate"}}))
    ok("세계관: 프리셋 키가 본문으로 펼쳐진다", "게이트" in r["world"], r["world"][:40])

    r = story.read_character(_charfile(
        _tmp, {"character": "x", "world": {"preset": "hunter_gate", "text": "내가 쓴 세계"}}))
    ok("세계관: 직접 쓴 것이 프리셋을 이긴다", r["world"] == "내가 쓴 세계", r["world"])

    r = story.read_character(_charfile(_tmp, {"character": "x", "world": "문자열도 받는다"}))
    ok("세계관: 문자열로 줘도 받는다", r["world"] == "문자열도 받는다", r["world"])

    try:
        story.read_character(_charfile(
            _tmp, {"character": "x", "world": {"preset": "없는프리셋"}}, "bad.json"))
        ok("세계관: 모르는 프리셋이면 멈춘다", False, "안 멈췄음")
    except SystemExit as e:
        ok("세계관: 모르는 프리셋이면 멈춘다 (가능한 값을 알려주며)",
           "hunter_gate" in str(e), str(e))

    # 사진 경로는 캐릭터 파일 기준으로 푼다.
    (_P(_tmp) / "p.png").write_bytes(b"x")
    r = story.read_character(_charfile(_tmp, {"character": "x", "photo": "p.png"}))
    ok("사진: 상대경로를 캐릭터 파일 기준으로 푼다",
       _P(r["photos"][0]).name == "p.png" and _P(r["photos"][0]).is_absolute() is False
       or _P(r["photos"][0]).exists(), r["photos"])
    ok("사진: 경로는 문자열이다 (meta.json 에 그대로 실린다)",
       all(isinstance(x, str) for x in r["photos"]), r["photos"])

    r = story.read_character(_charfile(
        _tmp, {"character": "x", "photo": ["p.png", "p.png"]}))
    ok("사진: 여러 장도 받는다", len(r["photos"]) == 2, r["photos"])

# 사진 파일 검사 — API 400 보다 먼저 우리가 막는다.
with _tf.TemporaryDirectory() as _tmp:
    bad = _P(_tmp) / "x.bmp"
    bad.write_bytes(b"x")
    try:
        story.load_image(bad)
        ok("사진: 지원 안 하는 형식은 멈춘다", False, "안 멈췄음")
    except SystemExit as e:
        ok("사진: 지원 안 하는 형식은 멈춘다", "bmp" in str(e) or "형식" in str(e), str(e))

    try:
        story.load_image(_P(_tmp) / "없는파일.png")
        ok("사진: 없는 파일이면 멈춘다", False, "안 멈췄음")
    except SystemExit as e:
        ok("사진: 없는 파일이면 멈춘다", "없습니다" in str(e), str(e))

    png = _P(_tmp) / "ok.png"
    png.write_bytes(b"\x89PNG\r\n\x1a\n" + b"0" * 32)
    im = story.load_image(png)
    ok("사진: png 를 base64 로 싣는다",
       im["mime"] == "image/png" and im["b64"] and im["name"] == "ok.png", im["mime"])

# LOOK 결과 -> 캐릭터 재료. 빈 칸은 문장으로 만들지 않는다.
_look = {
    "appearance": {"hair": "검은 단발", "eyes": "", "build": "",
                   "clothing": "남색 근무복", "impression": "", "element": ""},
    "color_palette": {"hair": "ink black (#1B1B1F)", "eyes": ""},
    "design_details": ["왼쪽 눈썹의 흉터", ""],
    "age_look": "20대 중반", "mood": "",
    "story_seeds": [{"seed": "소매가 그을렸다", "evidence": "왼쪽 소매 끝"}],
    "not_visible": ["하의"],
}
_mat = story.look_to_material(_look)
ok("사진→재료: 보인 것만 옮긴다", "검은 단발" in _mat and "남색 근무복" in _mat, _mat)
ok("사진→재료: 빈 칸은 줄을 만들지 않는다", "눈:" not in _mat and "체형:" not in _mat, _mat)
ok("사진→재료: 안 보인 칸을 신고한다", "하의" in _mat, _mat)
ok("사진→재료: 이야기 실마리를 근거와 함께 넘긴다",
   "소매가 그을렸다" in _mat and "왼쪽 소매 끝" in _mat, _mat)
ok("사진→재료: 사진이 준 것임을 표시한다", "사진에서 읽은 것" in _mat)
ok("사진→재료: LOOK 이 없으면 빈 문자열", story.look_to_material(None) == "")

# 세계관 프리셋 파일
_w = story.load_worlds()
ok("세계관 프리셋: worlds.json 이 읽힌다", bool(_w.get("presets")), list(_w)[:3])
for _k, _v in (_w.get("presets") or {}).items():
    ok(f"세계관 프리셋: {_k} 에 본문이 있다", bool(str(_v.get("text") or "").strip()))

# 프롬프트 계약 — 새 단계가 코드와 맞는지
_ps = story.load_prompts()
ok("프롬프트: look·seed 가 계약에 맞는다",
   story.check_prompt_vars(_ps) == [], story.check_prompt_vars(_ps))

# ---------------- 장르·스토리 템플릿 주입 ----------------
#
# 여기서 지키려는 것 두 가지:
#   1) 장르에 걸리는 템플릿만 보낸다 (전부 보내면 매 호출마다 6장르가 따라다닌다)
#   2) **기존 작품이 프롬프트에 도달하지 않는다** — 이게 더 중요하다.
#      템플릿은 장르 문법을 알려주려고 있는 것이지 남의 이야기를 옮기라고 있는
#      것이 아니다.

ok("템플릿: 프리셋 키가 장르로 풀린다",
   story.resolve_genre_templates("hunter") == ["판타지", "액션", "스릴러"],
   story.resolve_genre_templates("hunter"))
ok("템플릿: 파이프라인 프리셋 4종이 전부 매핑돼 있다",
   all(story.resolve_genre_templates(k) for k in ("hunter", "romance", "academy", "idol")))
ok("템플릿: 한글 장르명은 그대로 쓴다",
   story.resolve_genre_templates("판타지") == ["판타지"])
ok("템플릿: 자유 입력에서 장르명을 찾아낸다 (전용 키가 없는 조합은 부분일치)",
   set(story.resolve_genre_templates("액션 스릴러")) == {"액션", "스릴러"},
   story.resolve_genre_templates("액션 스릴러"))
# P0-4: '로맨스 판타지'는 더 이상 로맨스+판타지 부분일치로 섞이지 않는다.
# 로판 특유의 필수 트로프(궁정 신분·빙의)가 둘 다 빠지는 사고가 있었다.
ok("템플릿: '로맨스 판타지'는 전용 로판 템플릿으로 정확매치된다",
   story.resolve_genre_templates("로맨스 판타지") == ["로맨스 판타지"])
ok("템플릿: '로판' 도 같은 전용 템플릿으로 매칭된다",
   story.resolve_genre_templates("로판") == ["로맨스 판타지"])
ok("템플릿: 로판 템플릿에 궁정 신분·빙의 트로프가 들어 있다",
   "황태자" in json.dumps(story.load_genre_templates()["로맨스 판타지"], ensure_ascii=False)
   and "빙의" in json.dumps(story.load_genre_templates()["로맨스 판타지"], ensure_ascii=False))
# UI 가 제안하는 '헌터·게이트'/'아이돌' 을 그대로 입력해도(원문 그대로, genre_key
# 경유 없이) 템플릿이 걸려야 한다 — 전에는 부분일치 대상 장르명이 문자열에
# 하나도 없어 조용히 빈 템플릿으로 빠졌다.
ok("템플릿: '헌터·게이트' 를 그대로 입력해도 템플릿이 걸린다",
   story.resolve_genre_templates("헌터·게이트") == ["판타지", "액션", "스릴러"])
ok("템플릿: '아이돌' 을 그대로 입력해도 템플릿이 걸린다",
   story.resolve_genre_templates("아이돌") == ["일상", "로맨스"])
# UI placeholder 가 예시로 드는 '무협' 도 전용 템플릿이 있어야 한다.
ok("템플릿: '무협' 도 전용 템플릿으로 매칭된다",
   story.resolve_genre_templates("무협") == ["무협"])
# landing/web/index.html 의 장르 datalist 에 있는 나머지 값들도 전부 확인한다
# — 하나라도 별칭이 빠지면 그 옵션을 고른 사용자는 템플릿 없이 생성된다.
ok("템플릿: '마법학교' 를 그대로 입력해도 템플릿이 걸린다",
   story.resolve_genre_templates("마법학교") == ["판타지", "일상"])
ok("템플릿: '오컬트 미스터리' 를 그대로 입력해도 템플릿이 걸린다",
   story.resolve_genre_templates("오컬트 미스터리") == ["판타지", "스릴러"])
ok("템플릿: '좀비 아포칼립스' 를 그대로 입력해도 템플릿이 걸린다",
   story.resolve_genre_templates("좀비 아포칼립스") == ["액션", "스릴러"])
ok("템플릿: '현대 판타지' 는 부분일치로 이미 걸려 있었다 (회귀 확인)",
   story.resolve_genre_templates("현대 판타지") == ["판타지"])
# 못 찾으면 아무거나 끼워 넣지 않는다. 안 맞는 장르 문법은 캐릭터를 끌고 간다.
# ('오컬트 미스터리'는 예전엔 이 예시였지만 이제 별칭이 생겨 더 이상 빈 목록이
# 아니다 — 위 "'오컬트 미스터리' 를 그대로 입력해도" 테스트가 그걸 확인한다.)
ok("템플릿: 모르는 장르면 빈 목록 (억지로 안 고른다)",
   story.resolve_genre_templates("존재하지 않는 장르 이름 123") == []
   and story.resolve_genre_templates("") == [])

_g = story.genre_template_block(story.resolve_genre_templates("hunter"))
_s = story.story_template_block()
ok("템플릿: 고른 장르만 들어간다",
   "[판타지]" in _g and "[액션]" in _g and "[로맨스]" not in _g and "[개그]" not in _g)
ok("템플릿: 장르 문법(전개 패턴·체크리스트)은 들어간다",
   "사건전개패턴" in _g and "체크리스트" in _g, _g[:80])
ok("템플릿: 스토리 문법(3막·반전)은 들어간다",
   "스토리 구조" in _s and "반전과 플롯 장치" in _s)

# --- 저작권 ---
_titles = story.template_work_titles()
ok("저작권: 템플릿의 작품 제목 목록을 뽑아낸다", len(_titles) >= 20, len(_titles))
ok("저작권: '대표작' 칸이 프롬프트에 안 들어간다", "대표작" not in _g)
ok("저작권: '참고자료'(URL) 가 안 들어간다",
   "참고자료" not in _g and "http" not in _g and "http" not in _s)
# examples 배열이 통째로 빠졌는지. 판정은 그 배열에만 있는 마커로 한다 —
# '작품명' 은 삭제 표시 "(작품명 생략)" 에도 있고, '회차' 는 "회차 말미에
# 클리프행어" 처럼 본문에서 쓰이는 낱말이라 둘 다 오탐이 난다.
ok("저작권: examples 칸(출처 표기)이 안 들어간다",
   "출처" not in _s and "출처" not in _g and "examples" not in _s)
# 칸을 골라내는 것만으로는 부족하다 — 설명문 **본문**에도 작품명이 박혀 있다.
ok("저작권: 설명문 본문의 작품명까지 지워진다",
   story.check_borrowed_titles(_g + _s) == [],
   story.check_borrowed_titles(_g + _s))
ok("저작권: 지운 자리에 표시가 남는다", story.TITLE_REDACTION in _s)

# ---------------- 새로 추가된 장르 ----------------
#
# 전에는 UI 에서 고를 수 있는데 템플릿이 없는 장르가 있었다. 템플릿이 없으면
# resolve 가 빈 목록을 주고, 그러면 P1·P2 둘 다 장르를 '문자열 한 줄'로만 안다.
for _new in ("센티넬", "오메가버스", "게임 판타지", "학원로맨스", "BL(오메가버스)"):
    ok(f"템플릿: '{_new}' 가 장르로 풀린다",
       bool(story.resolve_genre_templates(_new)), story.resolve_genre_templates(_new))
ok("템플릿: 새 장르를 넣어도 기존 매핑이 그대로다",
   story.resolve_genre_templates("판타지") == ["판타지"]
   and story.resolve_genre_templates("헌터·게이트") == ["판타지", "액션", "스릴러"]
   and story.resolve_genre_templates("현대 판타지") == ["판타지"])

# ---------------- 이야기 변수 축 ----------------
#
# 여기서 지키려는 것:
#   1) 축이 실제로 매번 달라진다 (고정되면 넣은 의미가 없다)
#   2) 장르에 안 맞는 조합은 미리 잘린다 (개그인데 '음울' 같은 것)
#   3) 축이 없거나 깨져도 파이프라인이 서지 않는다

_axes_names = samples.axis_names()
ok("축: variation_axes.json 이 읽힌다", len(_axes_names) >= 5, _axes_names)
ok("축: 관계 구도가 장르가 아니라 축으로 들어가 있다",
   "관계_구도" in _axes_names
   and {"삼각관계", "라이벌"} <= {v["이름"] for v in
                                samples.load_axes()["관계_구도"]["값"]})

_pick = samples.pick_axes("판타지")
ok("축: 축마다 값이 하나씩 뽑힌다", set(_pick) == set(_axes_names), sorted(_pick))
ok("축: 뽑힌 값에 이름과 설명이 다 있다",
   all(v.get("이름") and v.get("설명") for v in _pick.values()))

# 다양성 — 이 축을 넣은 유일한 이유다. 200회에서 고유 조합이 절반도 안 나오면
# 어딘가 고정돼 있다는 뜻이라, 샘플 카드만 늘렸을 때와 다를 게 없어진다.
_combos = {samples.axes_summary(samples.pick_axes("판타지")) for _ in range(200)}
ok("축: 200회 뽑으면 조합이 실제로 흩어진다", len(_combos) >= 150, len(_combos))

_gag_tones = {samples.pick_axes("개그")["톤"]["이름"] for _ in range(120)}
ok("축: 장르에 안 맞는 값은 잘린다 (개그에 '음울'이 안 나온다)",
   "음울" not in _gag_tones, sorted(_gag_tones))
_daily_starts = {samples.pick_axes("일상")["이야기_시작점"]["이름"] for _ in range(120)}
ok("축: 일상에는 '폐허 이후'가 안 나온다",
   "폐허 이후" not in _daily_starts, sorted(_daily_starts))
ok("축: 모르는 장르는 전체 값을 그대로 쓴다",
   len({samples.pick_axes("듣도보도 못한 장르")["톤"]["이름"] for _ in range(120)}) >= 5)

ok("축: seed 를 주면 같은 조합이 재현된다",
   samples.axes_summary(samples.pick_axes("판타지", seed=42))
   == samples.axes_summary(samples.pick_axes("판타지", seed=42)))

_blk = samples.axes_block(samples.pick_axes("판타지", seed=1))
ok("축: 프롬프트 블록에 축 이름과 설명이 같이 나간다",
   "톤:" in _blk and len(_blk.splitlines()) >= len(_axes_names) * 2, _blk[:60])
ok("축: 축이 비면 빈 문자열 (프롬프트에 빈 자리를 안 남긴다)",
   samples.axes_block({}) == "")

# ---------------- P1 이 장르를 아는가 ----------------
#
# 이게 이번 수정의 핵심이다. 예전 P1 은 장르를 '{genre}' 문자열로만 받아서,
# 전용 샘플이 없는 장르는 엉뚱한 장르 카드를 보고 썼다.
_p1_vars = story.declared_vars(_ps.texts["p1"])
ok("P1: 장르 문법이 P1 에 주입된다", "genre_template" in _p1_vars, sorted(_p1_vars))
ok("P1: 이야기 변수가 P1 에 주입된다", "variation_axes" in _p1_vars)
ok("P1: P2 도 장르 문법을 그대로 받는다",
   "genre_template" in story.declared_vars(_ps.texts["p2"]))

_p1_rendered = story.render(_ps.texts["p1"], {
    "genre": "일상", "one_line_intro": "", "world": "(없음)",
    "character_input": "", "card_json": "(없음)", "sample_cards": "(생략)",
    "genre_template": story.genre_template_block(story.resolve_genre_templates("일상")),
    "variation_axes": samples.axes_block(samples.pick_axes("일상", seed=3)),
    "retry_feedback": "",
})
ok("P1: 렌더 후 안 채워진 자리가 없다",
   story.declared_vars(_p1_rendered) == set(), story.declared_vars(_p1_rendered))
ok("P1: '일상'을 고르면 일상 문법이 실제로 실린다",
   "일상" in _p1_rendered and "사건전개패턴" in _p1_rendered)

# ---------------- 장르별 샘플 카드 ----------------
#
# 전에는 샘플이 romance/idol/hunter/academy 4종뿐이라, 그 밖의 장르를 고르면
# exemplars_all() 로 폴백해 **엉뚱한 장르 카드**를 보고 썼다. "평범한 일상"을
# 골랐는데 각성·던전이 나오던 원인이다.

_TONES = {"somber", "serene", "radiant", "intense"}
ok("샘플: 장르가 13종으로 늘었다", len(samples.GENRES) >= 13, len(samples.GENRES))
ok("샘플: 등록된 장르에 파일이 전부 있다",
   sorted(samples.available()) == sorted(samples.GENRES),
   [k for k in samples.GENRES if k not in samples.available()])

for _key in sorted(samples.GENRES):
    _cards = samples.load(_key)
    _tones = {t for c in _cards for t in (c.get("tones") or [])}
    _missing = [f for c in _cards
                for f in ("id", "intro", "name", "personality", "quote",
                          "appearance", "fateBeats") if f not in c]
    ok(f"샘플[{_key}]: 6장 · 필수 칸 · 톤 4종",
       len(_cards) == 6 and not _missing and _tones == _TONES,
       f"n={len(_cards)} 빠진칸={_missing[:3]} 톤={sorted(_tones)}")

# 저작권 — 새로 쓴 카드에 실존 작품명이 섞이면 프롬프트로 새어 나간다.
for _key in sorted(samples.GENRES):
    _raw = (samples.SAMPLE_DIR / samples.GENRES[_key][0]).read_text(encoding="utf-8")
    ok(f"샘플[{_key}]: 실존 작품명이 없다",
       story.check_borrowed_titles(_raw) == [], story.check_borrowed_titles(_raw))

# UI 에서 고를 수 있는 장르가 전부 전용 샘플로 이어지는가.
# 하나라도 빈 문자열이면 그 장르는 여전히 남의 장르 카드를 보고 쓴다.
for _g in ("판타지", "일상", "무협", "액션", "스릴러", "개그", "센티넬",
           "게임 판타지", "BL(오메가버스)", "헌터·게이트", "로맨스 판타지",
           "아이돌", "마법학교", "현대 판타지", "오컬트 미스터리", "좀비 아포칼립스"):
    ok(f"샘플: '{_g}' 가 전용 샘플로 이어진다",
       samples.guess_genre(_g) in samples.GENRES, repr(samples.guess_genre(_g)))
ok("샘플: 모르는 장르는 여전히 빈 문자열 (억지로 안 고른다)",
   samples.guess_genre("느와르") == "" and samples.guess_genre("") == "")

# --- 샘플을 일부만, 매번 다르게 보여준다 ---
#
# 6장을 통째로 넣으면 그 6장이 곧 정답지가 되어 같은 장르 생성물이 서로 닮는다.
_pick_runs = [{l.split()[-1] for l in samples.exemplars("fantasy").splitlines()
               if l.startswith("[샘플")} for _ in range(40)]
ok("샘플: 기본은 일부만 넣는다", all(len(s) == samples.EXEMPLAR_PICK for s in _pick_runs),
   sorted(_pick_runs[0]))
ok("샘플: 매번 다른 조합이 뽑힌다", len({frozenset(s) for s in _pick_runs}) >= 5,
   len({frozenset(s) for s in _pick_runs}))
# 반전(04~06)이 한 장도 안 뽑히면 그 장르는 정통만 있는 것처럼 보인다.
_mixed = all(any(int(i.split("-")[1]) <= 3 for i in s)
             and any(int(i.split("-")[1]) >= 4 for i in s) for s in _pick_runs)
ok("샘플: 정통과 반전이 항상 함께 뽑힌다", _mixed)
ok("샘플: pick=0 이면 예전처럼 전부 넣는다 (--card-mix 용)",
   samples.exemplars("fantasy", pick=0).count("[샘플") == 6)
ok("샘플: 폴백은 장르 몇 개만 보여준다 (13종을 다 넣지 않는다)",
   samples.exemplars_all().count("──") <= 3,
   samples.exemplars_all().count("──"))

# ---------------- 회차 구조 다양화 ----------------
#
# story_templates.json 의 '스토리 구조'는 3막 하나뿐이라, 어떤 장르를 골라도
# 같은 리듬으로 나왔다 — 일상물인데도 사건이 터지고 반전이 생기고 다음 화
# 떡밥이 깔리던 이유다.

_st_names = samples.structure_names()
ok("구조: story_structures.json 이 읽힌다",
   "구조" in _st_names and "반전_배치" in _st_names, _st_names)
_structs = {v["이름"] for v in samples.load_structures()["구조"]["값"]}
ok("구조: 3막 말고도 여러 구조가 있다", len(_structs) >= 8, sorted(_structs))
ok("구조: 반전을 넣지 않는 선택지도 있다",
   "정공법" in {v["이름"] for v in samples.load_structures()["반전_배치"]["값"]})

_pick_st = samples.pick_structure("판타지")
ok("구조: 구조와 반전 배치가 하나씩 뽑힌다",
   set(_pick_st) == set(_st_names), sorted(_pick_st))
ok("구조: 뽑힌 구조에 단계가 들어 있다",
   len(_pick_st["구조"].get("단계") or []) >= 3, _pick_st["구조"].get("이름"))

_st_runs = {samples.pick_structure("판타지")["구조"]["이름"] for _ in range(200)}
ok("구조: 매번 3막으로 굳지 않는다", len(_st_runs) >= 8, sorted(_st_runs))

# 장르에 안 맞는 구조는 잘린다. 일상물에 '추락'·'구출'·'대결'이 걸리면
# 지금 고치려는 증상(일상인데 사건이 터진다)이 그대로 돌아온다.
_daily_st = {samples.pick_structure("일상")["구조"]["이름"] for _ in range(300)}
ok("구조: 일상에는 추락·구출·대결이 안 나온다",
   not (_daily_st & {"추락", "구출", "대결"}), sorted(_daily_st))
ok("구조: 일상에도 선택지가 여럿 남는다 (과하게 자르지 않았다)",
   len(_daily_st) >= 4, sorted(_daily_st))
ok("구조: 모르는 장르는 전체 구조를 쓴다",
   len({samples.pick_structure("듣도보도 못한 장르")["구조"]["이름"]
        for _ in range(300)}) == len(_structs))

_st_blk = samples.structure_block(samples.pick_structure("일상", seed=5))
ok("구조: 프롬프트 블록에 단계와 끝내는 법이 같이 나간다",
   "→" in _st_blk and ("끝내는 법" in _st_blk or "배치" in _st_blk), _st_blk[:60])
ok("구조: 비면 빈 문자열", samples.structure_block({}) == "")
ok("구조: seed 를 주면 재현된다",
   samples.structure_summary(samples.pick_structure("판타지", seed=9))
   == samples.structure_summary(samples.pick_structure("판타지", seed=9)))

# P2 가 실제로 구조를 받는가.
ok("P2: 회차 구조가 P2 에 주입된다",
   "story_structure" in story.declared_vars(_ps.texts["p2"]),
   sorted(story.declared_vars(_ps.texts["p2"])))
_p2_rendered = story.render(_ps.texts["p2"], {
    "genre": "일상", "world": "(없음)", "character_sheet": "{}",
    "genre_template": story.genre_template_block(story.resolve_genre_templates("일상")),
    "story_template": story.story_template_block(),
    "story_structure": samples.structure_block(samples.pick_structure("일상", seed=5)),
    "retry_feedback": "",
})
ok("P2: 렌더 후 안 채워진 자리가 없다",
   story.declared_vars(_p2_rendered) == set(), story.declared_vars(_p2_rendered))
ok("P2: 참고 자료의 3막보다 지정 구조가 우선이라고 못 박는다",
   "이쪽이 우선" in _p2_rendered)

# 결과 검사는 구조적 차단을 대신하지 않는다. 모델은 자기가 아는 작품도 꺼낸다.
ok("저작권: 결과물에 작품명이 있으면 잡아낸다",
   story.check_borrowed_titles({"logline": "왕좌의 게임 같은 이야기"}) == ["왕좌의 게임"],
   story.check_borrowed_titles({"logline": "왕좌의 게임 같은 이야기"}))
ok("저작권: 깨끗한 결과물은 통과한다",
   story.check_borrowed_titles({"logline": "심사관이 자기 서명 때문에 심사받는다"}) == [])

# 긴 제목부터 지워야 반쪽이 안 남는다.
ok("저작권: 제목이 겹쳐도 반쪽만 남지 않는다",
   story.TITLE_REDACTION in story.redact_titles("신의 탑 이야기")
   and "신의" not in story.redact_titles("신의 탑 이야기"),
   story.redact_titles("신의 탑 이야기"))

# 통째로 보내는 것보다 실제로 작아졌는가.
_TG_ROOT = _P(__file__).resolve().parent
_full = (_P(_TG_ROOT / "samples" / "genre_template.json").read_text(encoding="utf-8")
         + _P(_TG_ROOT / "samples" / "story_templates.json").read_text(encoding="utf-8"))
ok(f"템플릿: 통째로 보내는 것보다 작다 ({len(_g)+len(_s):,}자 < {len(_full):,}자)",
   len(_g) + len(_s) < len(_full) * 0.5)

# 이미 만들어 둔 산출물도 같은 기준으로 본다.
_out = _TG_ROOT / "outputs" / "chadogyeong"
if _out.exists():
    for _f in sorted(_out.glob("*.json")):
        _hit = story.check_borrowed_titles(_f.read_text(encoding="utf-8"))
        ok(f"저작권: {_f.name} 에 기존 작품명이 없다", _hit == [], _hit)

# ---------------- 연재 상태 (화를 하나씩 만들 때) ----------------
#
# 여기서 지키려는 것은 하나다: **3화가 1화를 잊지 않는 것.**
# 직전 화만 넘기면 1화에서 이름 붙인 인물이 3화에서 다른 사람이 되고, 1화에서
# 세운 설정이 3화에서 조용히 뒤집힌다. 그래서 요약이 아니라 누적 명부를 넘긴다.

_S = webtoon.SeriesState(run_id="t")
_L0 = webtoon.Ledger("엔진 질문", cap=5)
ok("연재: 처음이면 1화라고 알려 준다",
   "1화" in _S.brief(_L0) and _S.next_no() == 1, _S.brief(_L0)[:60])

_S.add(1, 1, {
    "title": "열두 번째 이름", "summary": "심사관이 신청자를 알아본다",
    "stinger": {"text": "파형이 겹친다"},
    "new_cast": [{"name": "이세윤", "note": "재측정 신청자"}],
    "new_facts": ["재측정은 5년에 한 번뿐이다"],
})
_S.add(2, 1, {
    "title": "보류", "summary": "서명을 미룬다",
    "stinger": {"text": "경보가 뜬다"},
    "new_cast": [{"name": "심사국장", "note": "상급자"},
                 {"name": "이세윤", "note": "중복 — 다시 안 실려야 함"}],
    "new_facts": ["보류가 3일을 넘기면 자동 보고된다"],
})
ok("연재: 화가 쌓인다", _S.made == 2 and _S.next_no() == 3)
ok("연재: 인물이 누적된다",
   [c["name"] for c in _S.cast] == ["이세윤", "심사국장"], _S.cast)
ok("연재: 같은 인물을 두 번 싣지 않는다", len(_S.cast) == 2)
ok("연재: 인물이 몇 화에 나왔는지 남는다", _S.cast[0]["first_episode"] == 1)
ok("연재: 설정이 누적된다", len(_S.facts) == 2, _S.facts)

_brief = _S.brief(_L0)
# 이게 핵심이다. 3화를 쓸 때 1화의 인물과 사건이 프롬프트에 살아 있어야 한다.
ok("연재: 3화를 쓸 때 1화 내용이 보인다",
   "열두 번째 이름" in _brief and "심사관이 신청자를 알아본다" in _brief, _brief)
ok("연재: 3화를 쓸 때 1화 인물이 보인다", "이세윤" in _brief)
ok("연재: 3화를 쓸 때 1화 설정이 보인다", "재측정은 5년에 한 번뿐이다" in _brief)
ok("연재: 바꾸지 말라고 말해 준다", "바꾸지 마세요" in _brief and "뒤집을 수 없" in _brief)

_L0.open("이 사람은 누구인가", "mystery", 1, 1)
ok("연재: 열린 질문 문장을 그대로 보여준다 (모델이 베끼게)",
   '"이 사람은 누구인가"' in _S.brief(_L0), _S.brief(_L0)[-200:])
for _i in range(6):
    _L0.open(f"질문 {_i}", "suspense", 1, 2)
ok("연재: 질문이 많으면 닫는 쪽에 무게를 두라고 한다",
   "닫는 쪽에 무게" in _S.brief(_L0))

# 저장 -> 불러오기. 다음 실행이 앞 화를 이어받는 경로다.
with _tf.TemporaryDirectory() as _tmp:
    _p = _P(_tmp) / "series.json"
    _S.save(_p)
    _S2 = webtoon.SeriesState.load(_p)
    ok("연재: 저장하고 되살린다",
       _S2.made == 2 and [c["name"] for c in _S2.cast] == ["이세윤", "심사국장"],
       _S2.as_dict())
    ok("연재: 파일이 없으면 빈 상태",
       webtoon.SeriesState.load(_P(_tmp) / "없음.json").made == 0)

# 통산 화 번호 -> Arc. Arc 는 방향이지 일정표가 아니라서, 계획을 넘겨도 멈추지 않는다.
_arcs = [{"order": 1, "estimated_episode_count": 2},
         {"order": 2, "estimated_episode_count": 3},
         {"order": 3, "estimated_episode_count": 2}]
ok("연재: 화 번호로 Arc 를 찾는다",
   [webtoon.arc_for_episode(_arcs, n)["order"] for n in (1, 2, 3, 5, 6, 7)]
   == [1, 1, 2, 2, 3, 3],
   [webtoon.arc_for_episode(_arcs, n)["order"] for n in (1, 2, 3, 5, 6, 7)])
ok("연재: 계획보다 길어지면 마지막 Arc 에 머문다",
   webtoon.arc_for_episode(_arcs, 99)["order"] == 3)
ok("연재: Arc 가 없어도 죽지 않는다", webtoon.arc_for_episode([], 1) == {})

# ---------------- 요약 CSV 가 잠겨도 실행을 죽이지 않는다 ----------------
#
# 이 함수는 파이프라인의 맨 마지막에 불린다. 여기서 예외가 올라가면 몇 분치
# API 호출과 몇 달러가 이미 나간 뒤에 프로세스가 죽는다. 실제로 그렇게 죽었다 —
# 엑셀로 CSV 를 열어 둔 채 다음 실행을 돌린 것뿐인데.

with _tf.TemporaryDirectory() as _tmp:
    _p = _P(_tmp) / "summary.csv"
    story.append_csv_row(_p, ["a", "b"], {"a": 1, "b": 2})
    ok("요약 CSV: 정상 기록", _p.exists() and "1,2" in _p.read_text(encoding="utf-8"))

    _locked = _P(_tmp) / "locked.csv"
    _locked.mkdir()          # 같은 이름의 디렉터리 -> 쓰기 시 PermissionError
    _crashed = False
    try:
        story.append_csv_row(_locked, ["a", "b"], {"a": 3, "b": 4})
    except Exception:
        _crashed = True
    ok("요약 CSV: 잠겨 있어도 예외를 올리지 않는다", not _crashed)
    _spare = _locked.with_suffix(".pending.csv")
    ok("요약 CSV: 잠기면 옆 파일에 줄을 남긴다 (잃지 않는다)",
       _spare.exists() and "3,4" in _spare.read_text(encoding="utf-8"),
       _spare.exists())

# ---------------- 성별 보존 (작가가 준 사실은 제약이다) ----------------
#
# 실제로 이렇게 망가졌다: 작가가 "여성, 능글맞고…" 라고 적었는데 P1 카드에
# 성별 칸이 없어서 그 사실이 통째로 증발했다. 뒷단계(P2·장면·컷·이미지)는
# 카드만 보므로 아무도 그 인물이 여자인 줄 몰랐고, 이미지에 남자가 그려졌다.
# 프롬프트로만 부탁하면 언젠가 또 빠지므로 코드가 막는다.

ok("성별 읽기: 한글 표현", story.gender_of("여성, 능글맞고") == "여")
ok("성별 읽기: 남성 표현", story.gender_of("31세 남성, 심사관") == "남")
# 캐릭터 JSON 의 fields 는 "- 성별: 남" 꼴로 펼쳐져서 넘어온다.
ok("성별 읽기: 항목 형태", story.gender_of("- 나이: 31\n- 성별: 남") == "남")
ok("성별 읽기: 따옴표가 붙어도", story.gender_of('"성별": "여"') == "여")
# '남'·'여' 한 글자는 판정하지 않는다 — '남기다', '여기' 같은 말에 걸린다.
ok("성별 읽기: 한 글자만으로는 판정하지 않는다",
   story.gender_of("남은 이야기를 여기서 끝낸다") == "",
   story.gender_of("남은 이야기를 여기서 끝낸다"))
ok("성별 읽기: 영문 표현", story.gender_of("a young woman") == "여")
# 안 적은 사람에게 적으라고 하면 안 된다. 못 잡는 쪽이 잘못 잡는 쪽보다 낫다.
ok("성별 읽기: 안 적었으면 빈 값", story.gender_of("구조대원. 5년차.") == "")
ok("성별 읽기: 둘 다 있으면 빈 값 (짐작하지 않는다)",
   story.gender_of("여성 주인공과 남자 후배") == "")

_good = {"gender": "여성",
         "appearance_en": "A young woman with short hair, wears a coat"}
ok("성별 게이트: 제대로 적혀 있으면 통과", story.gate_gender(_good, "여성, 능글맞고") == [])

ok("성별 게이트: gender 가 비면 탈락",
   any("gender 가 비어" in f for f in story.gate_gender(
       {"appearance_en": "A young woman with short hair"}, "여성")))

ok("성별 게이트: 작가가 적은 것과 다르면 탈락",
   any("작가는 이 인물을" in f for f in story.gate_gender(
       dict(_good, gender="남성"), "여성, 능글맞고")))
ok("성별 게이트: 작가가 안 적었으면 카드가 정한 값을 존중한다",
   story.gate_gender(dict(_good, gender="남성"), "구조대원. 5년차.") == [])

# 이미지 생성기가 그대로 받는 문장. 여기 성별이 없으면 옷차림으로 짐작한다.
ok("성별 게이트: appearance_en 에 성별이 없으면 탈락",
   any("appearance_en 에 성별이" in f for f in story.gate_gender(
       dict(_good, appearance_en="Short ashy brown hair, navy wide-leg pants"), "")))
ok("성별 게이트: appearance_en 에 성별이 있으면 통과",
   not any("appearance_en" in f for f in story.gate_gender(
       dict(_good, appearance_en="A young man with short hair"), "")))
# 'T-shirt' 안의 he 같은 부분 문자열에 속으면 안 된다. 낱말 단위로 본다.
ok("성별 게이트: 낱말 안에 우연히 들어간 글자에 속지 않는다",
   any("appearance_en 에 성별이" in f for f in story.gate_gender(
       dict(_good, appearance_en="Wears a white short-sleeved T-shirt and a hoodie"),
       "")))

# 실제로 망가진 그 카드가 걸리는지 — 이 게이트가 존재하는 이유 그 자체다.
_broken = _TG_ROOT / "runs" / "20260815T001950-b5af28" / "p1.json"
if _broken.exists():
    _card = _j.loads(_broken.read_text(encoding="utf-8"))
    _hits = story.gate_gender(_card, "여성, 능글맞고 장난기 많지만")
    ok("성별 게이트: 실제로 성별이 증발했던 카드를 잡아낸다",
       len(_hits) == 2
       and any("gender 가 비어" in f for f in _hits)
       and any("appearance_en" in f for f in _hits), _hits)

ok("성별 게이트: gate_p1 에 연결돼 있다",
   any("gender" in f for f in story.gate_p1({}, "여성")))

ok("모의 P1 카드에 gender 가 있다",
   bool(story.mock_payload("P1", "").get("gender")))
ok("모의 P1 카드가 성별 게이트를 통과한다",
   story.gate_gender(story.mock_payload("P1", ""), "여성") == [],
   story.gate_gender(story.mock_payload("P1", ""), "여성"))

# ---- 조연 고정 -------------------------------------------------------------
# 같은 캐릭터 파일을 두 번 돌렸더니 후배 이름이 '하윤재' → '장지운' 으로 바뀌고,
# 그림에서는 남자 후배가 여자로 그려졌다. 조연은 캐릭터 시트가 없어서 P1 에
# 적힌 것이 뒷단계가 가진 전부다.

_cast_ok = {
    "relational_gap": {"anchor": "하연 — 감사관", "solo": False},
    "supporting_cast": [{"name": "하연", "gender": "여성", "relation": "감사관",
                         "appearance": "짧은 검은 머리, 은테 안경", "role": "보관소를 닫으러 온다"}],
}
ok("조연 게이트: 명부가 제대로 있으면 통과",
   story.gate_supporting_cast(_cast_ok) == [],
   story.gate_supporting_cast(_cast_ok))

ok("조연 게이트: 명부가 비면 탈락",
   any("supporting_cast 가 비어" in f
       for f in story.gate_supporting_cast(dict(_cast_ok, supporting_cast=[]))))

ok("조연 게이트: 성별이 비면 탈락",
   any("성별(gender)" in f for f in story.gate_supporting_cast(
       dict(_cast_ok, supporting_cast=[dict(_cast_ok["supporting_cast"][0], gender="")]))))
ok("조연 게이트: 외형이 비면 탈락",
   any("외형(appearance)" in f for f in story.gate_supporting_cast(
       dict(_cast_ok, supporting_cast=[dict(_cast_ok["supporting_cast"][0], appearance="")]))))
ok("조연 게이트: 성별이 남/여가 아니면 탈락",
   any("남성 또는 여성" in f for f in story.gate_supporting_cast(
       dict(_cast_ok, supporting_cast=[dict(_cast_ok["supporting_cast"][0], gender="불명")]))))

# anchor 는 공동 주인공급이다. 그 사람이 명부에서 빠지면 뒷단계가 새로 만든다.
ok("조연 게이트: anchor 가 명부에 없으면 탈락",
   any("anchor" in f for f in story.gate_supporting_cast(
       dict(_cast_ok, supporting_cast=[dict(_cast_ok["supporting_cast"][0], name="다른사람")]))))

# 1인 완결형은 조연이 없는 것이 맞는 답이다.
ok("조연 게이트: 1인 완결형이면 명부가 없어도 통과",
   story.gate_supporting_cast({"relational_gap": {"solo": True}}) == [])

ok("조연 게이트: gate_p1 에 연결돼 있다",
   any("supporting_cast" in f for f in story.gate_p1({}, "")))
ok("모의 P1 카드가 조연 게이트를 통과한다",
   story.gate_supporting_cast(story.mock_payload("P1", "")) == [],
   story.gate_supporting_cast(story.mock_payload("P1", "")))

# 엔진 카드에 실려야 4~7단계와 그림이 같은 사람을 그린다.
_cast_card = webtoon.cast_block(_cast_ok)
ok("조연이 엔진 카드에 실린다",
   any("하연" in l for l in _cast_card) and any("여성" in l for l in _cast_card)
   and any("은테 안경" in l for l in _cast_card), _cast_card)
ok("조연이 없으면 빈 절을 만들지 않는다", webtoon.cast_block({}) == [])

# 인물은 P1 에서 끝나지 않는다. 5단계가 화마다 사람을 추가하고, 그 사람이 다음
# 화에도 나온다. 카드를 한 번 만들고 얼려 두면 3화 인물이 7화에서 사라진다.
_p1_card = {"name": "주인공", "supporting_cast": _cast_ok["supporting_cast"]}
_roster = [{"name": "하연", "gender": "여성", "appearance": "짧은 검은 머리",
            "note": "감사관", "first_episode": 0},
           {"name": "도경", "gender": "남성", "appearance": "덩치 큰 백발",
            "outfit": "낡은 작업복", "note": "3화에 온 정비공", "first_episode": 3}]
_later = webtoon.cast_block(_roster)
ok("엔진 카드: 연재 중 늘어난 인물도 실린다",
   any("도경" in l for l in _later) and any("남성" in l for l in _later)
   and any("백발" in l for l in _later), _later)
ok("엔진 카드: 몇 화부터 나온 사람인지 보인다",
   any("3화부터" in l for l in _later), _later)
ok("엔진 카드: 처음부터 있던 사람에는 화수를 안 붙인다",
   not any("0화" in l for l in _later), _later)
ok("엔진 카드: 옷차림도 실린다", any("낡은 작업복" in l for l in _later))

_frozen = webtoon.build_engine_card(_p1_card, {}, "한 줄", [])
_live = webtoon.build_engine_card(_p1_card, {}, "한 줄", [], _roster)
ok("엔진 카드: roster 를 주면 P1 명부 대신 그걸 쓴다",
   "도경" in _live and "도경" not in _frozen)
ok("엔진 카드: roster 를 안 주면 P1 명부로 되돌아간다 (옛 실행)",
   "하연" in _frozen)

# P1(relation/role)과 5단계(note)가 서로 다른 모양으로 사람을 설명한다.
_n = webtoon.normalize_cast_row(
    {"name": "하연", "gender": "여성", "relation": "감사관", "role": "보관소를 닫는다"})
ok("명부 정규화: relation 이 note 자리로 온다", _n["note"] == "감사관")
ok("명부 정규화: role 은 따로 남는다", _n["role"] == "보관소를 닫는다")
ok("명부 정규화: note 와 같은 role 은 중복해 싣지 않는다",
   webtoon.normalize_cast_row({"name": "하연", "note": "감사관",
                               "role": "감사관"})["role"] == "")
ok("명부 정규화: 이름 없으면 버린다", webtoon.normalize_cast_row({"gender": "여성"}) == {})

# 스토리 단계에서 확정된 조연을 5단계가 new_cast 에 다시 안 적어도, 그 사람은
# 이미 설계가 있는 사람이다. 여기서 안 보면 멀쩡한 인물에게 경고가 붙는다.
ok("대사 허용 명부: 연재 명부의 조연을 포함한다",
   "정이슬" in webtoon.known_speakers("[주인공] 민시하", [],
                                    [{"name": "정이슬"}]))
ok("대사 허용 명부: 명부에 없는 사람은 여전히 경고",
   webtoon.prose_warnings(
       [{"cut_number": 1, "speaker": "낯선 사람", "dialogue": "안녕"}],
       {"민시하", "정이슬"}))

# 1화가 조연을 '새로 만드는' 일이 되면 안 된다 — 명부는 0화에서 이미 차 있다.
_st = webtoon.SeriesState(run_id="t")
ok("연재 명부: P1 조연을 0화로 이어받는다",
   _st.seed_cast(_cast_ok["supporting_cast"]) == 1 and _st.cast[0]["name"] == "하연"
   and _st.cast[0]["first_episode"] == 0 and _st.cast[0]["gender"] == "여성", _st.cast)
ok("연재 명부: 같은 사람을 두 번 넣지 않는다",
   _st.seed_cast(_cast_ok["supporting_cast"]) == 0 and len(_st.cast) == 1)
_brief = _st.brief(webtoon.Ledger("엔진급 질문"))
ok("연재 명부: 1화 브리핑에도 조연이 보인다",
   "하연" in _brief and "스토리 단계에서 확정" in _brief and "1화" in _brief, _brief)

# 검출해 놓고 통과시키는 것이 검출 안 하는 것보다 나쁘다.
ok("장면 점검: '설정 증발'은 사람이 봐야 하는 항목이다",
   "설정 증발" in story.SCENE_BLOCKING_CHECKS)
ok("장면 점검: '대사 없음'은 메모로만 남긴다 (막지 않는다)",
   "대사 없음" not in story.SCENE_BLOCKING_CHECKS)

# ---------------- P0 수정 (2026-08) ----------------
# user_feedback_summary.md 의 P0 6건 중 하네스 게이트로 반영한 것들.

# P0-3: 이름 게이트 — 작가가 준 이름이 카드에서 바뀌면 안 된다.
ok("이름 게이트: given_name 이 비어 있으면 검사하지 않는다",
   story.gate_name({"name": "아무개"}, "") == [])
ok("이름 게이트: 카드 이름이 일치하면 통과",
   story.gate_name({"name": "초롱"}, "초롱") == [])
ok("이름 게이트: 카드 이름이 다르면 탈락",
   any("초롱" in f for f in story.gate_name({"name": "루나"}, "초롱")))
ok("이름 게이트: 카드 이름이 비어 있으면 탈락",
   story.gate_name({"name": ""}, "초롱") != [])

# P0-5: 지정 안 한 성격 이탈 — personality 에 진지함 신호가 없는데 대사·
# 나레이션에 단정적 전환 문장이 있으면 경고(advisory, 막지 않는다).
_abrupt_cuts = [{"cut_number": 3, "lines": [
    {"kind": "narration", "text": "그 순간이 모든 것을 바꿔놓았다"}]}]
ok("톤 경고: 장난스러운 personality + 단정적 전환 문장 -> 경고",
   any("성격 이탈" in w for w in webtoon.tone_warnings(_abrupt_cuts, [], "장난스럽다")))
ok("톤 경고: personality 에 '진지'가 있으면 조용하다",
   not any("성격 이탈" in w for w in
           webtoon.tone_warnings(_abrupt_cuts, [], "평소엔 장난스럽지만 진지한 순간도 있다")))
ok("톤 경고: 단정적 전환 문장이 없으면 조용하다",
   not any("성격 이탈" in w for w in
           webtoon.tone_warnings(
               [{"cut_number": 1, "lines": [{"kind": "dialogue", "text": "그냥 평범한 하루였다"}]}],
               [], "장난스럽다")))

# P0-6: 핵심 액션 비트 — beats 가 어느 컷에도 안 나오면 탈락(hard gate).
_beat_missing = [{"cut_number": 1, "description": "시하가 생쥐를 안고 있다", "lines": []}]
_beat_present = [{"cut_number": 1,
                  "description": "시하가 덫에 걸린 생쥐를 손으로 구출한다", "lines": []}]
_beat_dialogue = [{"cut_number": 1, "description": "시하가 무릎을 굽힌다",
                   "lines": [{"kind": "dialogue", "text": "지금 구출할게"}]}]
ok("beat 게이트: beats 없는 옛 run 은 항상 통과",
   webtoon.gate_beat_coverage(_beat_missing, None) == [])
ok("beat 게이트: 행동이 어느 컷에도 없으면 탈락",
   webtoon.gate_beat_coverage(_beat_missing, ["생쥐를 구출한다"]) != [])
ok("beat 게이트: description 에 그대로 있으면 통과",
   webtoon.gate_beat_coverage(_beat_present, ["생쥐를 구출한다"]) == [])
ok("beat 게이트: 활용형이 달라도(구출할게) 어간이 맞으면 통과",
   webtoon.gate_beat_coverage(_beat_dialogue, ["생쥐를 구출한다"]) == [])

# P0-3: 소품 텍스트(편지 등) 속 이름 — advisory.
_prop_cuts_stranger = [{"cut_number": 5, "screen_text": "초롱에게, 잘 지내고 있어? - 루나 올림"}]
ok("소품 이름 경고: 명부에 없는 이름이 screen_text 에 있으면 경고",
   any("루나" in w for w in webtoon.prop_text_name_check(_prop_cuts_stranger, {"초롱"})))
ok("소품 이름 경고: 명부에 있는 이름만 있으면 조용하다",
   webtoon.prop_text_name_check(_prop_cuts_stranger, {"초롱", "루나"}) == [])
ok("소품 이름 경고: screen_text 가 비어 있으면 조용하다",
   webtoon.prop_text_name_check([{"cut_number": 1, "screen_text": ""}], {"초롱"}) == [])

print()
print(f"{'ALL PASS' if not fails else 'FAILED: ' + ', '.join(fails)}")
sys.exit(1 if fails else 0)
