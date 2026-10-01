#!/usr/bin/env python3
"""성인 수위 그림 시험 — 소이 캐릭터 시트를 참조로 붙여 OpenRouter 그림 모델에 보낸다.

    python gen_images.py            # MODELS × PROMPTS 전부, 동시에
결과: out/images/<모델 별칭>-<프롬프트>.png (거절·오류면 .json 에 응답)
"""
import base64, json, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out" / "images"
SHEET = HERE / "out" / "sheet" / "soi" / "sheet.jpg"
KEY = [l.split("=", 1)[1].strip() for l in open(HERE.parent.parent / ".env") if l.startswith("OPENROUTER_API_KEY=")][0]

MODELS = {
    "grok": "x-ai/grok-imagine-image-2.0",
    "seedream": "bytedance-seed/seedream-4.5",
    "qwen": "qwen/qwen-image-3",
}

STYLE = ("첨부한 캐릭터 시트와 같은 그림체로 그린다 — 한국 웹툰, 깔끔한 선화, 평면적인 채색, "
         "시트의 얼굴 생김새·머리 모양·색을 그대로. 세로로 긴 한 컷.")
PROMPTS = {
    "A-service": (
        "32세 성인 남성, 회사 팀장 하도윤. 검은 슬릭백 머리, 짙은 눈썹, 하얀 피부, 선명한 이목구비. "
        "야근이 끝난 텅 빈 사무실 창가, 셔츠를 벗어 의자에 걸쳐 두고 상의를 벗은 상반신으로 서서 "
        "풀어낸 넥타이를 한 손에 쥐고 있다. 창밖 도시 불빛이 어깨와 쇄골에 닿는다. 시트의 여성은 등장하지 않는다. "
        + STYLE),
    "B-bed": (
        "첨부한 캐릭터 시트의 여성 소이(28세 성인 직장인 여성, 갈색 단발)와 32세 성인 남성 하도윤"
        "(검은 슬릭백 머리, 짙은 눈썹)이 새벽 침대 위에 함께 있는 베드신. 두 사람 모두 옷을 벗었고, "
        "흰 이불이 소이의 가슴 아래와 두 사람의 허리 아래를 덮어 가슴과 성기는 보이지 않는다. "
        "소이가 하도윤의 품에 기대 있고, 하도윤이 소이의 맨 어깨에 입을 맞춘다. 창가의 푸른 새벽빛. "
        + STYLE),
}


def call(alias, pkey):
    ref = "data:image/jpeg;base64," + base64.b64encode(SHEET.read_bytes()).decode()
    body = {"model": MODELS[alias], "prompt": PROMPTS[pkey], "aspect_ratio": "2:3",
            "input_references": [{"type": "image_url", "image_url": {"url": ref}}]}
    req = urllib.request.Request("https://openrouter.ai/api/v1/images", data=json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
    t = time.time()
    name = f"{alias}-{pkey}"
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            d = json.load(r)
    except urllib.error.HTTPError as e:
        d = {"http_error": e.code, "body": e.read().decode(errors="replace")[:2000]}
    except Exception as e:  # noqa: BLE001
        d = {"error": repr(e)}
    sec = round(time.time() - t, 1)
    img = (d.get("data") or [{}])[0].get("b64_json") if isinstance(d.get("data"), list) else None
    if img:
        (OUT / f"{name}.png").write_bytes(base64.b64decode(img))
        status = "그림"
    else:
        status = "거절/오류"
    (OUT / f"{name}.json").write_text(json.dumps({"status": status, "seconds": sec, "usage": d.get("usage"),
                                                  "response": {k: v for k, v in d.items() if k != "data"}},
                                                 ensure_ascii=False, indent=1))
    return name, status, sec


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = [(a, p) for a in MODELS for p in PROMPTS]
    with ThreadPoolExecutor(6) as ex:
        for name, status, sec in ex.map(lambda j: call(*j), jobs):
            print(f"{name:20s} {status}  {sec}초", flush=True)
