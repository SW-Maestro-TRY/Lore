#!/usr/bin/env python3
"""gen_images.py 와 같은 두 요청을 제품 그림 모델(OpenAI gpt-image-2)에 시트를 붙여 보낸다.
제품처럼 images.edit 으로 시트를 참조로 준다. 거절되면 그 사유를 그대로 남긴다."""
import base64, json, os, time
from pathlib import Path
from openai import OpenAI
import gen_images as g

HERE = Path(__file__).resolve().parent
env = {l.split("=", 1)[0]: l.split("=", 1)[1].strip() for l in open(HERE.parent.parent / ".env") if "=" in l and not l.startswith("#")}
client = OpenAI(api_key=env.get("WEBTOON_API_KEY") or env.get("OPENAI_KEY"))
SHEET = HERE / "out" / "sheet" / "soi" / "sheet.png"
QUALITY = os.environ.get("Q", "medium")

for pkey, prompt in g.PROMPTS.items():
    name = f"openai-{pkey}"
    t = time.time()
    try:
        with open(SHEET, "rb") as f:
            r = client.images.edit(model="gpt-image-2", image=f, prompt=prompt, size="1024x1536", quality=QUALITY, n=1)
        (g.OUT / f"{name}.png").write_bytes(base64.b64decode(r.data[0].b64_json))
        status, detail = "그림", (r.usage.model_dump() if getattr(r, "usage", None) else None)
    except Exception as e:  # noqa: BLE001
        status, detail = "거절/오류", repr(e)[:1500]
    sec = round(time.time() - t, 1)
    (g.OUT / f"{name}.json").write_text(json.dumps({"status": status, "seconds": sec, "detail": detail}, ensure_ascii=False, indent=1))
    print(f"{name:22s} {status}  {sec}초  {str(detail)[:300]}", flush=True)
