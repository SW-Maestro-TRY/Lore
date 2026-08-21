"""외형 고정 문구 검증. API 없음.

머리 길이가 컷마다 짧아지던 문제를 코드로 막고 있는지 본다. 시트에는 가슴
아래까지 오는 롱웨이브인데 컷은 턱선 단발로 나왔던 일이 있었고, 원인은
프롬프트에서 실제로 지켜지는 자리(design_details)에 길이가 없었던 것이다.
"""
import sys
sys.path.insert(0, r"C:\lore\webtoon-harness")

import charsheet as C

fails = []


def ok(name, cond, extra=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   {extra}" if extra and not cond else ""))
    if not cond:
        fails.append(name)


# ---------------- 머리 구절 뽑기 ----------------
SIHA = ("A young woman with messy, long ash blonde wavy hair, large bright "
        "lavender eyes with faint freckles and a natural flush under her eyes, "
        "a slim build and long limbs, usually seen in black off-shoulder tops.")

ok("머리 구절: 수식어까지 함께 뽑는다 ('with' 에서 멈춘다)",
   C.hair_phrase(SIHA) == "messy, long ash blonde wavy hair",
   C.hair_phrase(SIHA))
ok("머리 구절: 눈·체형 서술을 끌고 오지 않는다",
   "eyes" not in C.hair_phrase(SIHA) and "build" not in C.hair_phrase(SIHA))
ok("머리 구절: 뒤에 붙은 길이 서술도 가져온다",
   C.hair_phrase("She has jet-black hair down to her waist, tied with a ribbon.")
   == "jet-black hair down to her waist")
ok("머리 구절: 머리 얘기가 없으면 빈 문자열",
   C.hair_phrase("A person with no notable features.") == "")
ok("머리 구절: 빈 입력에서 터지지 않는다",
   C.hair_phrase("") == "" and C.hair_phrase(None) == "")

# ---------------- 길이 → 몸의 지점 ----------------
# long/short 은 상대적인 말이라 모델이 자기 기본값(짧은 쪽)으로 당겨 간다.
# 셀 수 있는 지시(피어싱 개수)는 한 번도 틀리지 않았으므로 같은 종류로 바꾼다.
ok("길이 환산: long 은 가슴까지로 풀어 쓴다",
   "chest" in C.length_anchor("messy, long ash blonde wavy hair"))
ok("길이 환산: 긴 표현이 짧은 표현보다 먼저 잡힌다 (shoulder-length ≠ shoulder)",
   C.length_anchor("shoulder-length brown hair") == C._LENGTH_ANCHOR["shoulder-length"])
ok("길이 환산: bob 은 턱선",
   "jaw" in C.length_anchor("a neat blonde bob"))
ok("길이 환산: 길이 형용사가 없으면 덧붙이지 않는다",
   C.length_anchor("wavy ash blonde hair") == "")

# ---------------- 고정 블록 ----------------
block = C.hair_text("messy, long ash blonde wavy hair")
ok("고정 블록: 원문 구절이 그대로 들어간다", "messy, long ash blonde wavy hair" in block)
ok("고정 블록: 몸의 지점 문장이 붙는다", "middle of the chest" in block)
ok("고정 블록: 짧게 그리는 쪽을 실수로 못박는다", "LONGER rather than shorter" in block)
ok("고정 블록: 그림체 문구의 단순화와 길이를 구분해 준다",
   "never how long it is" in block)
ok("고정 블록: 비어 있으면 아무것도 넣지 않는다", C.hair_text("") == "")

# ---------------- lock_text 안에서의 자리 ----------------
sheet = C.Sheet(run_dir=None, appearance=SIHA,
                design_details="Both ears with multiple silver piercings",
                color_palette="hair: ash blonde (#CFC3B0)")
locked = C.lock_text(sheet, outfit="a black off-shoulder top")
ok("lock_text: hair 를 안 넘겨도 appearance_en 에서 스스로 뽑는다",
   "HAIR —" in locked and "middle of the chest" in locked)
ok("lock_text: 의상 고정이 머리보다 먼저 온다 (appearance 의 옷 나열을 먼저 덮는다)",
   locked.index("DEFAULT OUTFIT") < locked.index("HAIR —"))
ok("lock_text: 넘겨준 hair 가 뽑아낸 것보다 우선한다",
   "waist" in C.lock_text(sheet, hair="black hair down to her waist"))
ok("lock_text: 시트가 없어도 터지지 않는다", isinstance(C.lock_text(None, "옷"), str))

# ---------------- 경고 ----------------
# 고쳐 넣는 것과 별개로, 원본의 어느 칸이 비었는지 사람이 보게 한다.
ok("경고: 길이가 appearance 에만 있고 design_details 에 없으면 알린다",
   "design_details" in C.hair_warning(sheet, "messy, long ash blonde wavy hair"))
sheet_ok = C.Sheet(run_dir=None, appearance=SIHA,
                   design_details="Long messy ash blonde hair past the chest")
ok("경고: design_details 에 길이가 있으면 조용하다",
   C.hair_warning(sheet_ok, "messy, long ash blonde wavy hair") == "")
ok("경고: 길이 형용사 자체가 없으면 조용하다",
   C.hair_warning(sheet, "wavy ash blonde hair") == "")

# ---------------- 소지품·머리장식 경고 ----------------
# 지팡이·모자가 appearance_en 에는 있는데 design_details 에 없어 컷마다
# 디자인이 바뀌거나 사라졌던 실제 사고(1컷·2컷 지팡이 불일치, 모자 착탈).
STAFF_APPEARANCE = ("A young woman with short black hair, always seen carrying "
                     "a wooden staff and wearing a wide-brimmed hat.")
sheet_no_accessory = C.Sheet(run_dir=None, appearance=STAFF_APPEARANCE,
                              design_details="A thin scar above the left eyebrow")
ok("소지품 경고: 지팡이·모자가 appearance 에만 있으면 알린다",
   "staff" in C.accessory_warning(sheet_no_accessory, STAFF_APPEARANCE)
   and "hat" in C.accessory_warning(sheet_no_accessory, STAFF_APPEARANCE))
sheet_with_accessory = C.Sheet(
    run_dir=None, appearance=STAFF_APPEARANCE,
    design_details="Carries a gnarled wooden staff with a blue crystal tip; "
                    "wears a wide-brimmed hat with a single feather")
ok("소지품 경고: design_details 에 이미 있으면 조용하다",
   C.accessory_warning(sheet_with_accessory, STAFF_APPEARANCE) == "")
ok("소지품 경고: 소지품 단어 자체가 없으면 조용하다",
   C.accessory_warning(sheet, SIHA) == "")
ok("소지품 경고: 시트가 없으면 터지지 않는다",
   C.accessory_warning(None, STAFF_APPEARANCE) == "")
ok("소지품 경고: 빈 appearance 에서 터지지 않는다",
   C.accessory_warning(sheet_no_accessory, "") == "")

print()
print(f"{'ALL PASS' if not fails else 'FAILED: ' + ', '.join(fails)}")
sys.exit(1 if fails else 0)
