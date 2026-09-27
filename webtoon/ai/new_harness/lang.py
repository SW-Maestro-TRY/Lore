"""웹툰 생성 언어. 프롬프트의 {{LANGUAGE_LINE}} 자리에 들어갈 한 줄을 만든다.

run.py 와 detailart.py 양쪽이 쓰는데 run.py -> detailart.py 방향으로 이미 서로
불러서, 언어 이름표는 따로 이 파일에 둔다(순환 import를 피하려는 것뿐이다).
"""

LANG_NAMES = {"ko": "한국어", "en": "영어"}


def instruction(lang: str) -> str:
    """기본(ko)은 예전에 프롬프트에 박혀 있던 문장 그대로다."""
    if lang == "ko":
        return "한국어로만 쓴다. 한자나 다른 언어의 글자를 섞지 않는다."
    name = LANG_NAMES.get(lang, lang)
    return (f"{name}로만 쓴다. 한국어나 다른 언어의 글자를 섞지 않는다. "
            f"인물 이름도 {name} 화자에게 자연스러운 이름으로 짓는다.")
