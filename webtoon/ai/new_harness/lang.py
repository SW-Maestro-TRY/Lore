"""웹툰 생성 언어. 프롬프트의 {{LANGUAGE_LINE}} 자리에 들어갈 한 줄을 만든다.

run.py 와 detailart.py 양쪽이 쓰는데 run.py -> detailart.py 방향으로 이미 서로
불러서, 언어 이름표는 따로 이 파일에 둔다(순환 import를 피하려는 것뿐이다).
"""

LANG_NAMES = {"ko": "한국어", "en": "영어", "ja": "일본어"}


def instruction(lang: str) -> str:
    """기본(ko)은 예전에 프롬프트에 박혀 있던 문장 그대로다."""
    if lang == "ko":
        return "한국어로만 쓴다. 한자나 다른 언어의 글자를 섞지 않는다."
    name = LANG_NAMES.get(lang, lang)
    return (f"{name}로만 쓴다. 한국어나 다른 언어의 글자를 섞지 않는다. "
            f"인물 이름도 {name} 화자에게 자연스러운 이름으로 짓는다.")


def card_instruction(lang: str) -> str:
    """「캐릭터 만들어보기」 카드 글의 언어. ko 는 빈 줄 — 예전 프롬프트 그대로다.

    panel_prompt 는 카드 칸마다 「한국어」「한글」「존댓말」「~자」를 박아 두었다.
    그 규정을 하나하나 갈라 쓰는 대신, 사람이 읽는 칸만 이 언어로 옮기라고
    맨 끝에서 덮는다. 그림에 들어가는 두 칸(appearance_en · scene_en)은 원래대로
    영어다.
    """
    if lang == "ko":
        return ""
    name = LANG_NAMES.get(lang, lang)
    return "\n".join([
        f"# 출력 언어: {name}",
        "",
        f"이 카드를 읽는 사람은 {name} 화자다. 사람이 읽는 칸 — name · species · "
        f"world_label · genre_word · role · twist · dialogue 의 who 와 text · fate — 는 "
        f"전부 {name}로만 쓴다. 한국어나 다른 언어의 글자를 섞지 않는다.",
        f"위에서 이 칸들을 「한국어」「한글」로 쓰라고 한 것은 {name}로 읽는다. "
        f"글자 수 제한은 {name}로 같은 분량이 되게 맞춘다. 「존댓말」은 {name}에서 "
        f"카드 소개문으로 자연스러운 정중한 문체로 옮긴다.",
        f"이름을 새로 지을 때는 그 세계관에서 {name} 화자에게 자연스러운 이름으로 짓는다. "
        f"사람이 넣은 이름은 바꾸지 않는다.",
        f"species 는 사람이면 {name}로 「사람」에 해당하는 말을 쓰고, 이때 species_en 은 "
        f"빈 문자열이다. 사람이 아니면 species_en 을 반드시 채운다.",
        "appearance_en · scene_en 은 위 규칙 그대로 영어로만 쓴다.",
    ])
