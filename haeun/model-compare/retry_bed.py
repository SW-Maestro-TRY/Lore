#!/usr/bin/env python3
"""아까 그려졌던 B(이불로 가린 베드신) 요청을 Grok 에 글자 하나 안 바꾸고 다시 한 번 보낸다.
결과: out/images/grok-B-bed-retry.png (거절이면 grok-B-bed-retry.json 에 사유)"""
import gen_images as g

O = g.OUT
first = {ext: O / f"grok-B-bed.{ext}" for ext in ("png", "json")}
keep = {ext: O / f"grok-B-bed-first.{ext}" for ext in ("png", "json")}
# call() 은 grok-B-bed.* 이름으로 쓴다 — 아까 결과를 잠시 비켜 두고, 끝나면 되돌린다
for ext in first:
    if first[ext].exists():
        first[ext].rename(keep[ext])
try:
    name, status, sec = g.call("grok", "B-bed")
    for ext in first:
        if first[ext].exists():
            first[ext].rename(O / f"grok-B-bed-retry.{ext}")
finally:
    for ext in keep:
        if keep[ext].exists():
            keep[ext].rename(first[ext])
print("다시 보낸 결과:", status, f"{sec}초")
