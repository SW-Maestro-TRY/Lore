"""편집실에서 얹은 것 — 저장·굽기·판본. API 없음, 그림만 만든다.

두 하네스의 테스트와 같은 방식이다 (pytest 아님, 마지막 줄에 ALL PASS).

    cd landing && python test_overlay.py
"""
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import overlay as OV
import pipeline as P

fails = []


def ok(name, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   {extra}" if extra and not cond else ""))
    if not cond:
        fails.append(name)


def img(w=400, h=500, color=(180, 180, 200)):
    from PIL import Image
    return Image.new("RGB", (w, h), color)


BUBBLE = {"type": "bubble", "variant": "normal", "text": "여기 앉아도 돼?",
          "x": 10, "y": 20, "w": 40, "size": 15, "rot": 0, "tail": "left"}

# ---------------- 브라우저에서 온 값 깎기 ----------------
#
# 여기 들어오는 것은 전부 브라우저가 보낸 값이라 무엇이든 올 수 있다. 항목
# 하나가 이상하다고 화 전체를 못 굽게 하지 않는다 — 그 항목만 버린다.

ok("깎기: 멀쩡한 항목은 그대로 통과",
   (OV.clean_item(BUBBLE) or {}).get("text") == "여기 앉아도 돼?")
ok("깎기: 모르는 type 은 버린다",
   OV.clean_item({**BUBBLE, "type": "동영상"}) is None)
ok("깎기: 글자가 비면 버린다 (그릴 것이 없다)",
   OV.clean_item({**BUBBLE, "text": "   "}) is None)
ok("깎기: 모르는 말풍선 모양은 normal 로 떨어진다",
   OV.clean_item({**BUBBLE, "variant": "폭발"})["variant"] == "normal")
ok("깎기: 좌표가 숫자가 아니어도 터지지 않는다",
   OV.clean_item({**BUBBLE, "x": "저쪽", "y": None, "w": [1]})["x"] == 20.0)
ok("깎기: 화면 밖으로 너무 멀리 나간 값은 끌어온다",
   OV.clean_item({**BUBBLE, "x": 9999})["x"] == 110.0
   and OV.clean_item({**BUBBLE, "x": -9999})["x"] == -20.0)
ok("깎기: 아주 긴 글은 잘린다 (말풍선 하나가 화를 덮지 않게)",
   len(OV.clean_item({**BUBBLE, "text": "가" * 5000})["text"]) == 400)
ok("깎기: NaN/무한대가 와도 기본값으로 떨어진다",
   OV.clean_item({**BUBBLE, "size": float("nan")})["size"] == 15.0
   and OV.clean_item({**BUBBLE, "rot": float("inf")})["rot"] == 0.0)
ok("깎기: 꼬리는 아는 값만 받는다",
   OV.clean_item({**BUBBLE, "tail": "위"})["tail"] == "left"
   and OV.clean_item({**BUBBLE, "tail": "none"})["tail"] == "none")

payload = OV.clean_payload({"scenes": {
    "1": {"ref_w": 700, "items": [BUBBLE, {"type": "나쁨"}]},
    "0": {"items": [BUBBLE]},          # 장 번호는 1부터다
    "두번째": {"items": [BUBBLE]},      # 숫자가 아니면 버린다
    "3": {"ref_w": 700, "items": []},   # 빈 장도 남는다 (지운 것과 안 연 것은 다르다)
}})
ok("깎기: 장 번호가 이상하면 버린다", sorted(payload["scenes"]) == ["1", "3"],
   sorted(payload["scenes"]))
ok("깎기: 못 쓸 항목만 빠지고 나머지는 남는다",
   len(payload["scenes"]["1"]["items"]) == 1)
ok("깎기: 얹은 것을 다 지운 장도 남는다",
   payload["scenes"]["3"]["items"] == [])
ok("세기: 얹은 것 개수를 센다", OV.count_items(payload) == 1)

# ---------------- 저장 — 브라우저가 아니라 작품 폴더에 ----------------

tmp = Path(tempfile.mkdtemp())
ok("저장: 파일이 없으면 빈 것으로 읽는다",
   OV.load_overlay(tmp) == {"scenes": {}})
OV.save_overlay(tmp, {"scenes": {"1": {"ref_w": 700, "items": [BUBBLE]}}})
ok("저장: 저장한 것이 그대로 다시 읽힌다",
   OV.load_overlay(tmp)["scenes"]["1"]["items"][0]["text"] == BUBBLE["text"])
OV.overlay_path(tmp).write_text("{ 망가진 json", encoding="utf-8")
ok("저장: 파일이 깨져도 편집실이 열린다 (빈 것으로 본다)",
   OV.load_overlay(tmp) == {"scenes": {}})

# ---------------- 굽기 ----------------

def one(kind, **over):
    base = {"bubble": BUBBLE,
            "sticker": {"type": "sticker", "variant": "", "text": "💦",
                        "x": 30, "y": 40, "w": 14, "size": 16, "rot": 0, "tail": "none"},
            "sfx": {"type": "sfx", "variant": "", "text": "우당탕",
                    "x": 40, "y": 60, "w": 30, "size": 18, "rot": -7, "tail": "none"}}[kind]
    return OV.clean_item({**base, **over})


plain = img()
baked, gone = OV.render_scene(plain, {"ref_w": 400, "items": [one("bubble")]})
ok("굽기: 밑그림 크기는 안 바뀐다", baked.size == plain.size, baked.size)
ok("굽기: 그림이 실제로 달라진다 (말풍선이 얹혔다)",
   list(baked.getdata()) != list(plain.convert("RGB").getdata()))
ok("굽기: 원본은 안 건드린다",
   plain.getpixel((plain.width // 2, 30)) == (180, 180, 200))

untouched, _ = OV.render_scene(plain, {"ref_w": 400, "items": []})
ok("굽기: 얹은 것이 없으면 그림이 그대로다",
   list(untouched.getdata()) == list(plain.convert("RGB").getdata()))

for v in OV.BUBBLE_VARIANTS:
    got, _ = OV.render_scene(plain, {"ref_w": 400,
                                     "items": [one("bubble", variant=v)]})
    ok(f"굽기: 말풍선 '{v}' 가 그려진다",
       list(got.getdata()) != list(plain.convert("RGB").getdata()))

rot, _ = OV.render_scene(plain, {"ref_w": 400, "items": [one("sfx")]})
ok("굽기: 효과음이 기울어져 그려진다",
   list(rot.getdata()) != list(plain.convert("RGB").getdata()))

# 해상도가 달라도 **비율**이 같아야 한다 — ref_w 가 그 다리다.
small, _ = OV.render_scene(img(400, 500), {"ref_w": 400, "items": [one("bubble")]})
big, _ = OV.render_scene(img(800, 1000), {"ref_w": 400, "items": [one("bubble")]})


def ink_ratio(im):
    px = list(im.convert("L").getdata())
    return sum(1 for v in px if v > 240) / len(px)


ok("굽기: 해상도가 두 배여도 말풍선이 차지하는 비율은 같다",
   abs(ink_ratio(small) - ink_ratio(big)) < 0.01,
   f"{ink_ratio(small):.4f} vs {ink_ratio(big):.4f}")

# 스티커는 이모지 글꼴이 없는 서버에서 빠질 수 있다. 그때도 나머지는 구워지고,
# 무엇이 빠졌는지 말해 줘야 한다 — 조용히 사라지면 아무도 모른다.
got, skipped = OV.render_scene(plain, {"ref_w": 400,
                                       "items": [one("bubble"), one("sticker")]})
ok("굽기: 스티커가 빠져도 말풍선은 구워진다",
   list(got.getdata()) != list(plain.convert("RGB").getdata()))
ok("굽기: 못 그린 것은 조용히 사라지지 않고 목록으로 나온다",
   isinstance(skipped, list))

# ---------------- 한 편으로 잇기 ----------------

work = Path(tempfile.mkdtemp())
srcs = {}
for n in (1, 2, 3):
    p = work / f"src{n}.png"
    img(300, 400, (100 + n * 30, 150, 200)).save(p)
    srcs[n] = p
OV.save_overlay(work, {"scenes": {"1": {"ref_w": 300, "items": [BUBBLE]}}})
res = OV.bake(work, [1, 2, 3], lambda n: srcs.get(n))
ok("잇기: 세 장을 전부 굽는다 (얹은 것이 없는 장도)", res["scenes"] == [1, 2, 3], res)
ok("잇기: 장마다 파일이 남는다",
   all(OV.baked_scene_path(work, n).exists() for n in (1, 2, 3)))
ok("잇기: 한 편이 세로로 이어진다",
   res["width"] == 300 and res["height"] == 1200, (res["width"], res["height"]))
ok("잇기: 원본은 그대로 남는다 (다시 구울 수 있어야 한다)",
   all(srcs[n].stat().st_size > 0 for n in (1, 2, 3)))

res2 = OV.bake(work, [1, 2, 9], lambda n: srcs.get(n))
ok("잇기: 아직 안 그려진 장은 빠지고 그 번호를 알려 준다",
   res2["scenes"] == [1, 2] and res2["missing"] == [9], res2)

try:
    OV.bake(work, [8, 9], lambda n: None)
    ok("잇기: 구울 그림이 하나도 없으면 알려 준다", False)
except OV.OverlayError as exc:
    ok("잇기: 구울 그림이 하나도 없으면 알려 준다", "먼저 웹툰을" in str(exc), str(exc))

# ---------------- 판본이 늘어나던 것 ----------------
#
# 지난 판을 눌러 보기만 해도 판본이 계속 늘었다 — v1~v3 를 번갈아 누르면
# v4·v5·v6 이 생기고, 그 셋은 v1~v3 와 픽셀 하나까지 같은 그림이었다.
# 사용자가 본 것이 그것이다 ("갑자기 v7 v8 이런 식으로 생성").

RUN = "__overlay_test__"
ep = P.episode_dir(RUN)
shutil.rmtree(ep.parent, ignore_errors=True)
(ep / "scene_S+").mkdir(parents=True)
cur = ep / "scene_S+" / "scene1_c1.png"


def versions():
    return sorted(v["version"] for v in P.scene_versions(RUN, 1))


for c in ((220, 40, 40), (40, 190, 60), (50, 90, 230)):
    P.archive_scene(RUN, 1)
    img(64, 64, c).save(cur)
made = versions()
ok("판본: 새로 그릴 때마다 하나씩 쌓인다", made == [1, 2], made)

for v in (1, 2, 1, 2, 1, 2):
    P.revert_scene(RUN, 1, v)
ok("판본: 지난 판을 눌러 봐도 늘어나지 않는다", len(versions()) <= 3, versions())
ok("판본: 되돌리면 그 그림이 실제로 걸린다",
   P.unit_image(RUN, 1).read_bytes() == P.version_path(RUN, 1, 2).read_bytes())

img(64, 64, (10, 10, 10)).save(cur)          # 처음 보는 그림
before = versions()
P.archive_scene(RUN, 1)
ok("판본: 처음 보는 그림은 새 판본으로 쌓인다", len(versions()) == len(before) + 1,
   (before, versions()))
shutil.rmtree(ep.parent, ignore_errors=True)
shutil.rmtree(tmp, ignore_errors=True)
shutil.rmtree(work, ignore_errors=True)

print()
print(f"{'ALL PASS' if not fails else 'FAILED: ' + ', '.join(fails)}")
sys.exit(1 if fails else 0)
