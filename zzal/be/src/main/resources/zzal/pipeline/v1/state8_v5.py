#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""1층 8종 후처리 v5 — 2026-09-12 신설. v3/v4 파일은 한 글자도 고치지 않는다.

v3 대비 바뀐 것 다섯
--------------------
1. **격자점 제거를 hue 기반으로 + 위치 기준을 '실제로 찍힌 마크'로**.
   (a) v3 씨앗 `r>235 & b>235` 가 모델의 실제 마크색 RGB(214,41,217)·(9,228,224)를 못 잡아
       9/11 판에서 흑연 4칸·이두나 8칸에 십자가 남았다 → v4 의 hue(마젠타 280~340 ·
       시안 165~205) 판정을 쓴다.
   ⚠️v4 는 v3 의 `key_green` 을 **먼저 그대로 부른 뒤** 후행 패스로 덧대는 구조라,
     v3 이후 처방 3종(땀방울 모서리 위치 제한 · 갇힌 초록 눈 복원 · 머리틈 B-R>10)이
     전부 살아 있다. diff 로 확인했고, v5 도 v3 키잉을 그대로 먼저 태운다.
   (b) ★그런데 **v4 를 그대로 돌려도 9/11 판의 잔여는 안 지워진다.** 실측으로 확인했다.
       v4 의 위치 제한이 `균등분할 칸의 네 모서리`(칸 너비 16% 반경)를 가정하는데,
       모델이 그린 실제 격자선은 균등하지 않다:
         · 흑연 행선 y = 32 / 312 / 612 / 865 / 1161 (행 높이 280·300·**253**·296)
           → 3행 칸(y624~973)의 아래 마크가 로컬 y=240, 4행 칸은 y=224 →
             모서리 띠(0~49 · 263~348) **밖**이라 씨앗이 통째로 탈락한다.
         · 이두나는 더 심하다 — 마크 열이 **6개**(x=23/274/535/754/978/1222)라
           마크가 아예 칸 한가운데에 찍힌다(캐릭터는 4x4 로 제대로 그려져 있다).
       → v5 는 격자 전체에서 **마크를 먼저 검출**하고(`frame_cut.find_marks_by_color` 와
         같은 hue+크기급락 방식), 그 실제 좌표 둘레만 제거 허용 구역으로 삼는다.
         균등분할 네 모서리도 합집합으로 남겨 v4 가 잡던 것은 그대로 잡는다.
       ⚠️위치 제한 자체를 빼면 안 된다 — 김애용 땀방울 hue 185.4, 여울 hue 195,
         블룸 입술 hue 336 이 전부 마크 hue 범위 안이라 색으로는 못 거른다.

2. **정렬 전에 캔버스를 먼저 넓힌다**(PAD=60).
   v3 의 `move()` 는 같은 크기 캔버스에 붙여 넣으므로 층2 가 칸을 위로 50px 옮기면
   머리끝이 조용히 잘렸다(흑연 base·eat 상여백 0 / 하여백 116 — 아래엔 116px이 남아 있었다).
   → 자른 직후 상하좌우에 `PAD_ALIGN` 을 붙이고, 모든 이동을 넓은 캔버스에서 한다.

3. **마지막에 `--frame` 방식으로 잘라낸다**.
   아래·좌·우는 원본 칸 경계(312x349)에 고정하고 **위만 필요한 만큼** 남긴다.
   발밑 여백이 원본과 같아야 화면의 바닥선과 어긋나지 않기 때문. 한 캐릭터의 8종은
   같은 상자로 자르므로 캔버스 크기가 전부 같다.

4. **웅크림·눕기 쌍은 발 기준에서 뺀다**.
   `foot_ref` 는 실루엣 아래 4% 밴드의 **좌우 끝 중점**을 발로 본다. 누운 몸은 그 밴드가
   몸통 옆선 전체라 중점이 발이 아니다 → 소닉 sleep 이 층2 dx=-52 로 밀려 좌변에 붙었다.
   → 9-10(아픔·웅크림)·15-16(자기·눕기) 두 쌍은
     · 층2 가로 보정 **dx=0**(옆으로 밀지 않는다)
     · 층2 세로는 그대로 — `foot_ref` 의 y 는 애초에 **실루엣 최하단 y** 라
       "몸 최하단선 = 바닥선"이 자동으로 성립한다.
     · 층1(쌍 안)은 발 대신 **본체 겹침(IoU)** 으로 dx·dy 를 잡는다.
       두 칸은 숨쉬기 차이뿐이라 겹침이 가장 정확하고, 발 밴드의 오판을 안 탄다.

5. **칸을 위로도 넓게 뜬다 — 아래 PAD 와 대칭**.
   v3 는 칸 **아래에만** PAD(12%≈37px)를 붙였다. 그런데 9/11 판 실측에서
   `hello`(인사)의 든 팔이 균등분할선 **위로** 여울 17px · 흑연 45px · 이두나 17px 뻗어 있어
   **절단 단계에서 이미 잘린 채로** 후처리에 들어왔다. 정렬로 내려 보내면 여백은 > 0 이 되지만
   잘린 팔은 안 돌아온다 → 위에도 `PAD_CUT_TOP=60` 을 준다.
   · 16칸 **전부에 똑같이** 준다(hello 만 특별 취급하지 않는다).
   · 딸려 들어온 이웃 칸 조각은 `drop_intruders` 가 걷어낸다(본체가 아니면서 가장자리에 닿은 것).
   · ★그 제거가 '남의 조각'인지 '내 머리카락'인지는 `VERIFY_TOP_PAD` 가 칸마다 대조해 신고한다 —
     같은 칸을 위 PAD 없이도 떠서 **본체(가장 큰 덩어리) 픽셀 수**를 비교하고,
     줄어든 칸이 하나라도 있으면 경고한다(늘거나 같아야 정상).
   · 원본(서비스) 캔버스는 여전히 312x349 다. 위 PAD 는 '더 넓게 떠서 살린 뒤
     마지막에 필요한 만큼만 남기는' 재료일 뿐, 아래·좌·우 경계는 그대로다.

그 밖에
- 로그 라벨을 v3 이름(기본·식사·기쁨·슬픔·아픔·교감·인사·자기)으로 바로잡았다.
  v3 는 8/25 8종 이름(배고픔·청소·행복·불행·쓰다듬·훈련)이 박혀 있어 **로그를 그대로 읽으면
  "훈련이 이상하다"고 잘못 결론내게 된다** — 실제로는 눕기다.
- 층2 보정이 |dx|>30 또는 |dy|>40 이면 스스로 경고를 찍는다.
- 8종 webp(2프레임 · 450ms · q80)까지 만든다.

6. **눕기·웅크림 쌍의 층2 가로를 칸 중앙에 맞춘다**(2026-09-12 추가, 기본 켬 · `--no-center-lying` 로 끔).
   4번에서 `dx=0` 으로 껐더니 모델이 그린 자리 그대로 남아 sleep 이 서 있는 칸보다
   **13~35px 왼쪽**에 앉았다. 여백은 0 을 넘어 통과하지만 8종을 이어 보면
   잘 때만 옆으로 미끄러져 보인다. → 본체 bbox 가로 중심을 **칸 중앙(156)** 에 맞춘다.
   발 중앙값(rx)이 아니라 칸 중앙을 쓰는 이유 = 누운 몸엔 발 기준이 없고, rx 는 판마다 흔들린다.

7. **눕기·웅크림 쌍의 층1 탐색에 '시드'를 준다**(2026-09-12 추가 · 검수에서 잡힌 결함).
   증상 = v01 검수에서 웅크린 자세의 두 칸이 좌우로 왔다 갔다 하는 것이 보였다.
   실측 = 블룸 sick 두 칸이 **몸 전체 9.0px 평행이동**(겹침 IoU 0.812).
   범인 = 이 파일의 층1 눕기 분기가 쓰던 `S8.best(..., 25)`.
     `state8_v3.best(a,b,rng)` 는 **-rng~+rng 안에서만** 찾는다. 그런데 모델이 그린
     두 칸의 가로차가 블룸 sick 은 **34px** 이라 ±25 안에 정답이 없다 →
     경계값 +25 에서 멈추고 **-9px 이 남은 채로** 통과했다. 게다가 **경고도 안 찍혔다**
     (같은 병: 이두나 sick +25(정답 ~30) · 소닉 sleep +25(정답 ~26) · 흑연 sick +24 아슬).
   → 처방 = `best_seeded()`. **본체(가장 큰 덩어리) bbox 중심차를 첫 추정(시드)** 으로 놓고
     그 둘레 **±SEED_RNG(12)px 만** 겹침(IoU)으로 미세조정한다. 가로·세로 같은 구조.
   ⚠️8/24 의 'bbox 중심 정렬 실패'(팔 폭이 변하면 중심이 끌려 8개 중 6개 악화)와 다른 점 =
     **bbox 중심은 정답이 아니라 시드로만 쓴다.** 최종 판정은 여전히 겹침이 한다.
     팔 폭 변화가 만드는 오차는 수 px 규모라 ±12 안에서 겹침이 되돌린다.
   ⚠️서 있는 6쌍(발 기준)은 **안 건드렸다** — 그쪽 dx 는 `foot_ref` 차이라 애초에 무제한이고,
     dy 만 `best(...,12)` 로 잡는다. 대신 **경계에 닿으면 경고**를 찍게 했다(아래).
   · 탐색 한계 자기신고 — dx·dy 가 탐색 구간의 끝값으로 나오면 `⚠️ 층1 탐색 한계` 를 찍는다.
     "정답이 구간 밖일 수 있다"는 뜻. 이번 결함이 **조용히** 지나간 이유가 이 경고의 부재였다.

8. **후처리를 '하나'로 두지 않고 자세 유형별 프로파일로 가른다**(2026-09-12).
   서 있는 동작·앉은 동작·누운 동작은 기준으로 삼을 지점이 서로 달라서,
   후처리 하나로는 셋을 다 맞출 수 없다.
   → 칸 번호 하드코딩(`LYING_PAIRS = {4,7}`)을 버리고 `POSTURES` 세 유형으로 갈랐다.
     · `standing` 발 기준 층1·층2 · 바닥선 = 발 밑창(아래 4% 밴드)
     · `crouch`   앉음·웅크림 — 밴드가 엉덩이·무릎이라 시드+겹침 · 바닥선 = 실루엣 최하단 · 칸중앙
     · `lying`    눕기 — 밴드가 몸통 옆선 · 시드+겹침 · 칸중앙 · **폭 제한 검사**를 추가로 켠다
   8종 → 유형 매핑은 **인자로 받는다**(`--postures sick=crouch,sleep=lying`). 2층·심화에서
   앉기·눕기가 다른 칸에 와도 코드를 안 고치고 매핑만 바꾼다. 기본값은 1층 v4 매핑.
   · 틀린 키·자세는 **설정 이름을 대는 예외**로 즉시 멈춘다(조용한 기본값 복귀 금지).
   · 매핑이 그림과 어긋나면 `posture_sanity` 가 경고한다 — 가로세로비로 **눕기만** 가른다
     (앉음·서있음은 실측상 겹쳐서 못 가른다. 못 가르는 것을 가른다고 하지 않는다).
   · 이 개편은 **구조만** 바꿨다 — 7번까지 적용한 결과와 80프레임 md5 가 전부 같다.

사용:
    state8_v5.py <격자.png> [출력폴더] [--no-center-lying] [--postures <매핑>]
        출력폴더 생략 시 격자.png 옆. 만들어지는 것 =
        <out>/cut/<key>.webp 8장 · <out>/frames/f01~f16.png · <out>/_work/{상태8,순차}.gif
        --postures 예 = `--postures sick=crouch,sleep=lying` (생략 시 1층 v4 기본 매핑)
                        자세 = standing · crouch · lying
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state8_v3 as S8   # noqa: E402  (읽기만 — 기존 파일 불변)
import state8_v4 as S4   # noqa: E402  (import 하면 S8.key_green 이 v4 로 감싸진다)

# ── v3 이름표를 버리고 v3 프롬프트의 실제 8종으로 (C 6절)
NAMES = ["기본", "식사", "기쁨", "슬픔", "아픔", "교감", "인사", "자기"]
KEYS  = ["base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep"]

# ── 자세 유형 프로파일 ───────────────────────────────────────────────────────
# ★서 있는 동작·앉은 동작·누운 동작은 기준으로 삼을 지점이 서로 다르다 —
#   후처리 하나로는 셋을 다 맞출 수 없다.
# → 종전의 `LYING_PAIRS = {4, 7}` **칸 번호 하드코딩을 버리고** 자세 유형으로 갈랐다.
#   2층·심화에서 앉기·눕기가 다른 칸에 오면 `--postures` 매핑만 바꾸면 된다(코드 수정 불필요).
#
# 각 프로파일이 자기 것으로 갖는 것 = 층1 방식 · 층2 가로 기준 · 바닥선의 뜻 · PAD · 경고 임계.
#   l1        층1(쌍 안) 정렬 방식.
#             "foot" = 발 기준 가로(무제한) + 겹침 세로(±l1_rng)
#             "seed" = 본체 bbox 중심차를 시드로, 그 둘레 ±l1_rng 만 겹침으로 미세조정
#   l1_rng    위 반경(px)
#   l2_x      층2(쌍 사이) 가로 기준. "foot_median"=발 좌표 중앙값 / "cell_center"=칸 중앙 / "none"=0
#   floor     바닥선의 **뜻**(코드는 셋 다 `foot_ref` 의 y = 실루엣 최하단을 쓴다. 뜻이 다를 뿐).
#   pad_top   칸을 위로 더 뜨는 양. ⚠️**전 프로파일이 같아야 한다** — 칸마다 다르면 캔버스 크기가
#             달라져 층2 의 중앙값 좌표계가 깨진다. 그래서 값이 엇갈리면 설정 이름을 대며 멈춘다.
#   warn_dx / warn_dy   층2 자기신고 임계
#   check_width  마지막 잘라내기(원본 312 폭)에 본체가 걸리는지 본다. 누운 몸은 가로로 길어
#                칸 폭을 넘길 수 있어 눕기에만 켠다.
#   ar_gate   이 유형이면 본체 가로세로비가 서 있는 칸 대비 어느 쪽이어야 하는가 —
#             "stand_like"(작아야) / "lie_like"(커야) / None(판정 안 함).
#             ★매핑을 잘못 준 것을 잡는 **상식 게이트**. 실측 근거는 `AR_LIE_X` 주석 참고.
POSTURES = {
    "standing": dict(
        label="서 있음 — 발이 안 움직이게 설계된 부분이라 발을 기준점으로 쓴다",
        l1="foot", l1_rng=12, l2_x="foot_median", floor="발 밑창(아래 4% 밴드)",
        pad_top=60, warn_dx=30, warn_dy=40, check_width=False, ar_gate="stand_like"),
    "crouch": dict(
        label="앉음·웅크림 — 아래 4% 밴드가 발이 아니라 엉덩이·무릎이라 발 기준을 못 쓴다",
        l1="seed", l1_rng=12, l2_x="cell_center", floor="엉덩이·무릎(실루엣 최하단선)",
        pad_top=60, warn_dx=60, warn_dy=40, check_width=False, ar_gate=None),
    "lying": dict(
        label="눕기 — 아래 밴드가 몸통 옆선 전체라 발 기준이 통째로 오판된다",
        l1="seed", l1_rng=12, l2_x="cell_center", floor="누운 몸 옆선(실루엣 최하단선)",
        pad_top=60, warn_dx=60, warn_dy=40, check_width=True, ar_gate="lie_like"),
}

# 1층 v4 의 기본 매핑 — 9-10칸=아픔(웅크림) · 15-16칸=자기(눕기), 나머지 6쌍은 서 있음.
DEFAULT_POSTURE = {
    "base": "standing", "eat": "standing", "joy": "standing", "sad": "standing",
    "sick": "crouch",   "pet": "standing", "hello": "standing", "sleep": "lying",
}

# ★자세 상식 게이트의 임계 — 본체 가로세로비(가로/세로)가 **서 있는 칸 중앙값의 몇 배**인가.
#   9/12 1층 v4 5캐릭터 80칸 실측:
#     서 있음 0.94~1.25배 · 앉음(sick) 1.23~1.47배 · 눕기(sleep) **1.79~2.77배**
#   → 1.6 을 넘으면 누운 몸이다(서 있음 최댓값 1.25 · 앉음 최댓값 1.47 과 뚜렷이 갈린다).
#   ⚠️**앉음과 서 있음은 이 값으로 못 가른다**(소닉 sick 1.24 vs 소닉 hello 1.25 로 겹친다).
#     그래서 `crouch` 의 `ar_gate` 는 None 이다 — 못 가르는 것을 가른다고 하지 않는다.
AR_LIE_X = 1.6

PAD_CUT_TOP = 60  # ★칸 **위**에도 아래와 대칭으로 여유를 둔다 (2026-09-12).
                  #   ⚠️2026-09-12 프로파일 분리 뒤로 **실제 값은 `POSTURES[*]['pad_top']`** 이 갖는다
                  #     (현재 셋 다 60). 이 상수는 그 기본값의 출처를 적어 둔 문서 겸 옛 호출 호환용.
                  #   근거 — 9/11 판 실측에서 `hello`(인사)의 든 팔이 균등분할선 **위로**
                  #   여울 17px · 흑연 45px · 이두나 17px 뻗어 있어 절단 단계에서 이미 잘려 들어왔다.
                  #   v3 는 아래에만 PAD(12%)를 붙였는데, 잘림은 위에서도 똑같이 난다.
                  #   들어온 이웃 칸 조각은 `drop_intruders` 가 걷어낸다(분리된 덩어리 + 가장자리 접촉).
                  #   ⚠️16칸 **전부에 똑같이** 준다 — hello 만 특별 취급하면 칸마다 좌표계가 달라진다.

PAD_ALIGN = 120   # ★정렬로 밀린 만큼 캔버스를 미리 넓혀 둔다. 안 넓히면 머리·발이 조용히 잘린다.
                  #   9/11 실측 최대 보정은 흑연 세로 98px(-50 → +48)이라 120px 이면 양쪽 다 감당한다.
TOP_MARGIN = 4    # 위로 넓힐 때 최상단 픽셀 위에 남기는 여백(M=4)
FRAME_MS = 450
WEBP_Q = 80

# ⚠️층2 자기신고 임계(C 4절)는 2026-09-12 프로파일 분리로 **`POSTURES[*]['warn_dx'/'warn_dy']`**
#   가 기준이 됐다. 여기 상수를 따로 두면 두 집이 생겨 조용히 엇갈린다 → 두지 않는다.
#   현재 값 = standing 30/40 · crouch 60/40 · lying 60/40.

# ★2026-09-12 추가 — 눕기·웅크림 쌍의 층2 가로를 '칸 가로 중앙'에 맞춘다(기본 켬).
#   근거 = 독립 재측정.
#     v5 는 눕기 쌍의 층2 가로보정을 dx=0 으로 껐다(발 밴드가 몸통이라 발 기준을 못 쓴다).
#     그 대가로 **모델이 그린 위치 그대로** 남아 sleep 이 서 있는 칸보다 13~35px 왼쪽에 앉는다
#     (소닉 -35.2 · 흑연 -31.0 · 여울 -28.5 · 이두나 -19.2 · 블룸 -12.8).
#     "숫자로는 여백>0 을 통과하지만 8종을 이어 보면 잘 때만 캐릭터가 옆으로 미끄러진 것처럼 보인다."
#   → dx=0 대신 **본체(가장 큰 덩어리) bbox 의 가로 중심을 칸 중앙(W0/2=156)** 에 맞춘다.
#     발 기준(rx)이 아니라 칸 중앙을 쓰는 이유 = 누운 몸엔 '발'이라는 기준이 없고,
#     서 있는 칸의 발 중앙값(rx)도 캐릭터마다 칸 중앙에서 몇 px 씩 떠 있어 기준이 흔들린다.
#     칸 중앙은 판·캐릭터와 무관하게 고정된 값이라 재현된다.
#   ⚠️끄려면 `--no-center-lying`. 그때는 종전대로 dx=0.
CENTER_LYING = True
VERIFY_TOP_PAD = True       # 위 PAD 가 이웃 조각을 끌고 들어오지 않았는지 칸마다 대조해 보고한다

MARK_R_CORNER = 0.16   # 균등분할 네 모서리 반경(칸 너비 대비) — v4 와 같은 값
MARK_R_POINT = 36      # 실제 검출된 마크 둘레 반경(px). 실측 마크는 22~31px 폭이라 충분하다

# ★2026-09-12 — 눕기·웅크림 층1 탐색의 미세조정 반경(시드 둘레 ±px).
#   시드 = 본체 bbox 중심차. 팔·소품이 만드는 오차는 수 px 규모라 ±12 면 겹침이 되돌린다.
#   ⚠️크게 키우면 8/24 의 '기호를 맞추려 캐릭터를 미는' 병이 되살아날 수 있다.
#   ⚠️실제로 쓰이는 값은 **`POSTURES[*]['l1_rng']`**(현재 셋 다 12). 이 상수는 `best_seeded`
#     의 기본 인자일 뿐이다 — 값을 바꾸려면 프로파일 쪽을 고칠 것.
SEED_RNG = 12

# ★2026-09-12 — **발 띠에서 빗자루를 뺀다** (`foot_ref_clean`)
#   무슨 일이 있었나 — 2층 v2 `sweep`(청소)에서, 발이 고정되고 빗자루가 쓸리는 것이 아니라
#     빗자루가 고정되고 발이 움직이는 것처럼 보였다.
#   실측 — `foot_ref` 가 쓰는 **아래 4% 띠**에 빗자루 솔이 바닥까지 내려와 함께 들어왔다.
#     띠의 덩어리가 다른 14칸은 전부 2개(소닉은 발이 붙어 1개)인데 `sweep` 두 칸만 **3개**였고
#     (여울·흑연·이두나 3판), 그 세 번째가 빗자루다. 솔의 아래 끝이 신발 밑창과 **같은 줄**이라
#     "가장 낮은 줄에 닿았나"로는 못 가른다. 크기(124~317px)도 신발(185~345px)과 겹쳐 못 가른다.
#     띠 폭이 66~75px(다른 칸) → **118~127px**(sweep)로 벌어졌고, 좌우 끝 중점이 그만큼 밀렸다.
#   결과 — 층1 dx 가 신발 기준보다 4·4·6px 어긋나, 정렬을 마친 뒤에도 **발이 4.0~6.0px 움직였다.**
#     띠가 안 더러워진 두 판(블룸·소닉)은 같은 조건에서 **0.0~0.5px** 였다. 흔들림은 전부 이 오염이다.
#   ★어떻게 가르나 = **색.** 두 신발은 한 켤레라 평균색이 거의 같고(색거리 L1 5~12),
#     빗자루는 어느 신발과도 멀다(77~284). 6배 이상 갈린다.
#     → 띠 덩어리가 **3개 이상일 때만** 작동한다(다른 78칸은 전부 1~2개라 손도 안 댄다).
#       가장 닮은 짝을 발로 보고, 그 짝의 평균색에서 먼 덩어리만 버린다. 버릴 때 로그에 찍는다.
#   ⚠️왜 '띠를 좁힌다'·'가장 낮은 것만 남긴다'로 안 했나 — 둘 다 실측에서 못 가른다(위 참조).
#   ⚠️이 함수는 `state8_v3.foot_ref` 를 **안 건드린다.** v3·v4 로 뽑은 확정본은 그대로 재현된다.
FOOT_MIN_BLOBS = 3      # 이 개수 미만이면 아무것도 안 한다 (정상 칸 = 1~2개)
FOOT_COLOR_ABS = 40.0   # 신발 짝 평균색과의 L1 거리가 이보다 멀면 발이 아니다 (실측 77~284 대 5~12)
FOOT_COLOR_REL = 5.0    # 그리고 짝끼리의 거리보다 이 배수 넘게 멀 때만 버린다


def _band_blobs(im, frac=0.04):
    """`state8_v3.foot_ref` 와 **같은 절차**로 아래 띠 덩어리를 구한다(+평균색)."""
    arr = np.array(im)
    b = S8.mk_char(im)
    ys, xs = np.nonzero(b)
    if not len(ys):
        return None, None, []
    bot = int(ys.max())
    h = bot - int(ys.min())
    fm = b.copy()
    fm[:int(bot - h * frac), :] = False
    if not fm.any():
        return (float(xs.min() + xs.max()) / 2, float(bot)), bot, []
    lab, n = ndimage.label(fm)
    sizes = ndimage.sum(fm, lab, range(1, n + 1))
    blobs = []
    for i in range(n):
        if sizes[i] < sizes.max() * 0.25:        # v3 의 25% 규칙 그대로
            continue
        m = lab == i + 1
        cx = np.nonzero(m.any(axis=0))[0]
        blobs.append(dict(size=float(sizes[i]), x0=int(cx.min()), x1=int(cx.max()),
                          rgb=np.asarray(arr[:, :, :3][m], dtype=float).mean(axis=0)))
    blobs.sort(key=lambda d: d["x0"])
    return None, bot, blobs


def foot_ref_clean(im, frac=0.04, tag=""):
    """발 기준점 — **한 켤레가 아닌 덩어리를 버린 뒤** 좌우 끝 중점을 낸다.

    덩어리가 2개 이하면 `state8_v3.foot_ref` 와 **완전히 같은 값**을 돌려준다."""
    fb, bot, blobs = _band_blobs(im, frac)
    if fb is not None:
        return fb
    if len(blobs) < FOOT_MIN_BLOBS:
        return S8.foot_ref(im, frac)
    best = None
    for i in range(len(blobs)):
        for j in range(i + 1, len(blobs)):
            d = float(np.abs(blobs[i]["rgb"] - blobs[j]["rgb"]).sum())
            if best is None or d < best[0]:
                best = (d, i, j)
    d0, i, j = best
    ref = (blobs[i]["rgb"] + blobs[j]["rgb"]) / 2
    feet, drop = [], []
    for k, c in enumerate(blobs):
        dd = float(np.abs(c["rgb"] - ref).sum())
        if k in (i, j) or not (dd > FOOT_COLOR_ABS and dd > FOOT_COLOR_REL * max(d0, 1.0)):
            feet.append(c)
        else:
            drop.append((c, dd))
    if not drop:
        return S8.foot_ref(im, frac)
    lo = min(c["x0"] for c in feet); hi = max(c["x1"] for c in feet)
    old = S8.foot_ref(im, frac)[0]
    print(f"    발 띠 정화{tag} — 덩어리 {len(blobs)}개 중 "
          + ", ".join(f"x{c['x0']}~{c['x1']}({int(c['size'])}px·색거리 {dd:.0f})" for c, dd in drop)
          + f" 를 발이 아닌 것으로 버림 (짝 색거리 {d0:.0f}) · 기준 {old:.1f}→{(lo+hi)/2:.1f}")
    return (lo + hi) / 2, float(bot)



def _cut_by_drop(blobs, lo=8, hi=40, thr=0.6):
    """크기 급락 지점에서 자른다 — `frame_cut.find_marks_by_color` 와 같은 방식.
    ★임계를 박지 않는다. 마크 크기는 판마다 다르고(여울 90 / 흑연 276 / 이두나 80),
      하한을 박았다가 두 번 틀렸다(12px→의상 오검출 / 40px→진짜 마크 탈락)."""
    s = sorted(blobs, reverse=True)
    sizes = [b[0] for b in s]
    cut = min(len(sizes), hi)
    for i in range(lo, min(len(sizes), hi)):
        if sizes[i] / sizes[i - 1] < thr:
            cut = i
            break
    return s[:cut]


def lattice_points(rgb: np.ndarray):
    """격자 전체에서 마크(마젠타·시안) 중심 좌표를 찾는다. 반환 = [(x, y), ...]"""
    h, s, v = S4._hsv(rgb)
    strong = (s > 0.45) & (v > 0.55)
    pts = []
    for lo, hi in (S4.MAG, S4.CYA):
        msk = strong & (h >= lo) & (h <= hi)
        lab, _ = ndimage.label(msk)
        blobs = []
        for i, sl in enumerate(ndimage.find_objects(lab), 1):
            px = int((lab[sl] == i).sum())
            if px < 6 or px > 900:            # 명백한 먼지·거대 영역만 뺀다
                continue
            blobs.append((px, (sl[1].start + sl[1].stop) / 2,
                          (sl[0].start + sl[0].stop) / 2))
        pts += [(x, y) for _, x, y in _cut_by_drop(blobs)]
    return pts


def mark_zone(shape, corner_rows, box, points):
    """이 칸에서 '격자점을 지워도 되는 구역' 마스크.
    = 균등분할 네 모서리(v4 와 동일) ∪ 실제 검출된 마크 둘레.
    ★corner_rows 는 **이 칸 안에서 균등분할 격자선이 지나는 로컬 y** 두 개다.
      위 PAD 를 붙이면서 0 이 아니게 됐으므로 밖에서 받는다."""
    H, W = shape
    m = np.zeros((H, W), bool)
    R = int(W * MARK_R_CORNER)
    for cy in corner_rows:
        cy = int(cy)
        for cx in (0, W - 1):
            m[max(0, cy - R):min(H, cy + R + 1), max(0, cx - R):min(W, cx + R + 1)] = True
    x0, y0 = box[0], box[1]
    P = MARK_R_POINT
    for px, py in points:
        lx, ly = int(round(px - x0)), int(round(py - y0))
        if -P <= lx <= W - 1 + P and -P <= ly <= H - 1 + P:
            m[max(0, ly - P):min(H, ly + P + 1), max(0, lx - P):min(W, lx + P + 1)] = True
    return m


def strip_marks_in_zone(cell: Image.Image, zone: np.ndarray, grow_px=4, alpha_min=8):
    """구역 안의 마젠타·시안 십자를 지운다 — v4 `strip_marks_hue()` 와 같은 씨앗+연결확장.
    다른 점은 위치 조건을 `cell_h` 로 계산하지 않고 **밖에서 받은 zone 마스크**로 쓰는 것뿐이다.
    (v4 파일은 건드리지 않으려고 여기에 따로 둔다.)"""
    out = np.array(cell.convert("RGBA"))
    h, s, v = S4._hsv(out[:, :, :3])
    보임 = out[:, :, 3] > alpha_min

    seed = 보임 & (s > 0.45) & (v > 0.55) & (S4._in(h, S4.MAG) | S4._in(h, S4.CYA)) & zone
    if not seed.any():
        return cell
    # 느슨 — 마크의 흐린 가장자리. 씨앗에 닿은 덩어리만 인정하므로 넓혀도 캐릭터를 안 먹는다.
    loose = 보임 & (s > 0.22) & (v > 0.30) & (S4._in(h, S4.MAG, 12) | S4._in(h, S4.CYA, 12))
    lab, _ = ndimage.label(loose)
    hit = np.unique(lab[seed & (lab > 0)])
    hit = hit[hit > 0]
    if not len(hit):
        return cell
    grow = np.isin(lab, hit)
    for _ in range(grow_px):
        g2 = grow.copy()
        g2[1:, :] |= grow[:-1, :]; g2[:-1, :] |= grow[1:, :]
        g2[:, 1:] |= grow[:, :-1]; g2[:, :-1] |= grow[:, 1:]
        grow = g2
    grow &= zone              # 번지기도 구역 안에서만 — 캐릭터 쪽으로 새지 않게
    out[:, :, 3] = np.where(grow, 0, out[:, :, 3])
    return Image.fromarray(out)


def bbox_center_x(im):
    """본체(가장 큰 덩어리) bbox 의 가로 중심 — 눕기 칸의 진단용 값."""
    b = S8.mk_char(im)
    xs = np.nonzero(b.any(axis=0))[0]
    return float((xs.min() + xs.max()) / 2) if len(xs) else 0.0


def body_center(m):
    """본체 마스크의 bbox 중심 (x, y). ★'정답'이 아니라 **탐색 시드**로만 쓴다."""
    ys = np.nonzero(m.any(axis=1))[0]
    xs = np.nonzero(m.any(axis=0))[0]
    if not len(xs) or not len(ys):
        return 0.0, 0.0
    return float((xs.min() + xs.max()) / 2), float((ys.min() + ys.max()) / 2)


def best_seeded(a, b, rng=SEED_RNG):
    """겹침(IoU) 탐색을 **시드 둘레**에서만 돈다. 반환 = (iou, dx, dy, sx, sy, 한계닿음).

    ★왜 — 웅크린 자세에서 두 칸이 좌우로 왔다 갔다 하는 결함이 검수에서 잡혔다.
      `state8_v3.best(a,b,rng)` 는 원점 둘레 -rng~+rng 만 본다. 눕기·웅크림 두 칸의
      가로차는 모델이 34px 까지 벌려 그려서 ±25 안에 정답이 없었고, 경계값 +25 에서
      멈춘 채 **-9px 이 남았다**(블룸 sick IoU 0.812). 반경을 무작정 키우면 탐색이
      제곱으로 커지고 엉뚱한 국소최대(기호·그림자)에 붙을 위험도 커진다.
    → 첫 추정을 **본체 bbox 중심차**로 잡고 그 둘레만 겹침으로 다듬는다.
      bbox 중심은 팔 폭 변화에 끌리지만(2026-08-24 실패), 여기서는 **시드일 뿐**이고
      최종 결정은 겹침이 한다 → 그 실패 유형과 다르다.
    """
    ax, ay = body_center(a)
    bx, by = body_center(b)
    sx, sy = int(round(ax - bx)), int(round(ay - by))
    r = (-1.0, sx, sy)
    for dy in range(sy - rng, sy + rng + 1):
        for dx in range(sx - rng, sx + rng + 1):
            S = S8.shift(b, dx, dy)
            u = (a | S).sum()
            v = (a & S).sum() / u if u else 0.0
            if v > r[0]:
                r = (v, dx, dy)
    hit = (abs(r[1] - sx) == rng) or (abs(r[2] - sy) == rng)
    return r[0], r[1], r[2], sx, sy, hit


def parse_postures(spec, base=None):
    """`--postures sick=crouch,sleep=lying` 을 8종 → 자세 유형 표로 푼다.

    ★왜 인자인가 — 2층·심화에서 앉기·눕기가 다른 칸에 오면
      코드를 고치는 게 아니라 **매핑만 바꿔서** 같은 후처리를 쓰게 하기 위해서다.
      서비스 쪽에서 `--keys` 처럼 문자열로 넘길 수 있는 형식으로 뒀다.
    ⚠️틀린 값은 **설정 이름을 말하는 예외**로 즉시 멈춘다. 조용히 기본값으로 되돌리면
      "돌긴 돌았는데 엉뚱한 후처리를 탄" 판이 나오고, 그건 로그를 봐도 안 보인다.
    """
    m = dict(base or DEFAULT_POSTURE)
    if not spec:
        return m
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "=" not in part:
            raise ValueError(f"--postures 형식 오류: '{part}' — `키=자세` 로 쓸 것 "
                             f"(예: --postures sick=crouch,sleep=lying)")
        k, v = (s.strip() for s in part.split("=", 1))
        if k not in KEYS:
            raise ValueError(f"--postures 의 모르는 키 '{k}' — 가능한 키: {', '.join(KEYS)}")
        if v not in POSTURES:
            raise ValueError(f"--postures 의 모르는 자세 '{v}' (키 '{k}') — "
                             f"가능한 자세: {', '.join(POSTURES)}")
        m[k] = v
    missing = [k for k in KEYS if k not in m]
    if missing:
        raise ValueError(f"--postures 에 빠진 키: {', '.join(missing)}")
    return m


def resolve_pad_top(mapping):
    """쓰이는 프로파일들의 `pad_top` 이 하나로 모이는지 확인하고 그 값을 준다.
    ⚠️칸마다 다른 PAD 를 주면 칸 크기가 달라지고, 그러면 층2 의 '발 좌표 중앙값'이
      서로 다른 좌표계의 값을 섞게 된다 — 통과는 하는데 결과가 조용히 틀어진다."""
    used = {mapping[k] for k in KEYS}
    vals = {POSTURES[p]["pad_top"] for p in used}
    if len(vals) > 1:
        raise ValueError(
            "POSTURES 의 `pad_top` 이 유형마다 다릅니다: "
            + ", ".join(f"{p}={POSTURES[p]['pad_top']}" for p in sorted(used))
            + " — 칸 크기가 달라지면 층2 중앙값 좌표계가 깨집니다. 하나로 맞추세요.")
    return vals.pop()


def aspect_ratio(m):
    """본체 마스크의 가로/세로 비. 평행이동에 안 흔들려 자세 판별에 쓸 수 있다."""
    ys = np.nonzero(m.any(axis=1))[0]
    xs = np.nonzero(m.any(axis=0))[0]
    if not len(xs) or not len(ys):
        return 0.0
    return (xs.max() - xs.min() + 1) / (ys.max() - ys.min() + 1)


def posture_sanity(cells, postures):
    """매핑이 그림과 어긋나지 않는지 보는 **상식 게이트**. 경고만 찍고 아무것도 안 바꾼다.

    기준은 이 판 **안에서** 잡는다 — 서 있다고 선언된 칸들의 가로세로비 중앙값.
    캐릭터마다 체형이 달라(소닉 0.62 · 블룸 0.48) 절대값으로는 못 가르기 때문이다.
    ⚠️가르는 것은 **눕기뿐**이다. 앉음과 서 있음은 이 값이 겹쳐서 못 가른다(AR_LIE_X 주석).
    """
    ar = [aspect_ratio(S8.mk_char(c)) for c in cells]
    stand = [ar[k*2+j] for k in range(len(postures)) for j in (0, 1)
             if postures[k] == "standing"]
    if not stand:
        print("  자세 게이트 — 서 있는 칸이 하나도 없어 기준을 못 잡습니다(건너뜀)")
        return
    ref = float(np.median(stand))
    bad = []
    for k, p in enumerate(postures):
        gate = POSTURES[p]["ar_gate"]
        if not gate:
            continue
        for j in (0, 1):
            x = ar[k*2+j] / ref if ref else 0.0
            if gate == "stand_like" and x >= AR_LIE_X:
                bad.append((k*2+j+1, KEYS[k], p, x, "누운 몸으로 보입니다 → `lying`"))
            if gate == "lie_like" and x < AR_LIE_X:
                bad.append((k*2+j+1, KEYS[k], p, x, "누운 몸이 아닌 것 같습니다"))
    if bad:
        for n, key, p, x, msg in bad:
            print(f"    ⚠️ 자세 매핑 의심 — f{n:02d}({key}) 은 `--postures {key}={p}` 인데 "
                  f"가로세로비가 서 있는 칸의 {x:.2f}배입니다({AR_LIE_X} 기준). {msg}")
    else:
        print(f"  자세 게이트 — 매핑과 그림이 일치(서 있는 칸 가로세로비 중앙값 {ref:.2f})")


def expand(im, pad=PAD_ALIGN):
    """정렬 여유를 사방에 붙인다. 이동은 전부 이 넓은 캔버스 안에서 일어난다."""
    out = Image.new("RGBA", (im.width + pad * 2, im.height + pad * 2), (0, 0, 0, 0))
    out.paste(im, (pad, pad))
    return out


def main(grid, outdir=None, cols=4, rows=4, center_lying=CENTER_LYING, postures=None):
    grid = Path(grid)
    out = Path(outdir) if outdir else grid.parent
    out.mkdir(parents=True, exist_ok=True)
    npairs = cols * rows // 2

    # ── 자세 매핑 확정 — 어느 쌍이 어느 후처리를 타는지 **먼저 로그에 박는다**
    pmap = postures if isinstance(postures, dict) else parse_postures(postures)
    prof = [pmap[KEYS[k]] for k in range(min(npairs, len(KEYS)))]
    prof += ["standing"] * (npairs - len(prof))          # 8종보다 칸이 많으면 나머지는 서 있음
    PAD_CUT_TOP = resolve_pad_top(pmap)
    print("자세 매핑 — " + " · ".join(f"{KEYS[k]}={prof[k]}" for k in range(min(npairs, len(KEYS)))))
    for p in sorted({*prof}):
        d = POSTURES[p]
        print(f"    [{p:8s}] {d['label']}")
        print(f"               층1={d['l1']}(±{d['l1_rng']}) · 층2가로={d['l2_x']} · "
              f"바닥선={d['floor']} · 위PAD={d['pad_top']} · "
              f"경고 |dx|>{d['warn_dx']}·|dy|>{d['warn_dy']}"
              + (" · 폭제한검사" if d["check_width"] else ""))

    im = Image.open(grid).convert("RGB")
    W, H = im.size
    cw, ch = W / cols, H / rows
    # 칸 아래 여유 — v3 그대로(12%). 발이 하단 마커에 닿게 그려지면 분할선이 밑창을 스친다.
    PAD_CUT = int(ch * 0.12)
    W0 = int(cw)
    H0 = int(ch) + PAD_CUT                       # ★원본(서비스) 캔버스 = 312x349. 이 값은 안 바뀐다.
    # ★위에도 같은 뜻의 여유를 붙인다. 격자를 아래로 PAD_CUT_TOP 만큼 내려 붙여
    #   모든 칸이 위·아래 양쪽으로 더 넓게 떠진다(칸 = 312 x (349+60)).
    ext = Image.new("RGB", (W, H + PAD_CUT + PAD_CUT_TOP), (0, 255, 0))
    ext.paste(im, (0, PAD_CUT_TOP))

    # ★격자 전체에서 마크를 먼저 찾는다 — 제거 허용 구역의 근거. **격자 원본 좌표계**로 잰다.
    points = lattice_points(np.array(im))
    print(f"격자점 검출 {len(points)}개")

    def cut_one(r, c, up):
        """칸 하나를 떠서 키잉 + 격자점 제거까지. up = 위로 더 뜨는 양(0 이면 v3 와 같은 절단)."""
        x0 = int(round(c * cw))
        gy = int(round(r * ch))                  # 격자 원본 좌표계에서의 칸 위 경계
        box = (x0, gy + PAD_CUT_TOP - up, x0 + W0, gy + PAD_CUT_TOP + H0)
        cell = S4._v3_key_green(ext.crop(box), up + int(ch))   # v3 키잉 원본 그대로
        # 균등분할 격자선이 이 칸 안에서 지나는 로컬 y 두 개
        corner_rows = (up, up + int(ch))
        zone = mark_zone((cell.height, cell.width), corner_rows, (x0, gy - up), points)
        return strip_marks_in_zone(cell, zone)

    cells = []
    pad_check = []                               # (칸번호, 위PAD 없을 때 본체, 있을 때 본체)
    for r in range(rows):
        for c in range(cols):
            cell = cut_one(r, c, PAD_CUT_TOP)
            cells.append(cell)
            if VERIFY_TOP_PAD:
                # ★"지워진 것이 남의 조각인지 내 머리카락인지"를 가른다.
                #   위 PAD 를 안 준 같은 칸과 **본체(가장 큰 덩어리)** 크기를 대조한다.
                #   위 PAD 는 잘린 제 몸을 되찾는 것이므로 본체는 **늘거나 같아야** 한다.
                #   줄었다면 이웃 조각이 제 몸에 붙어 덩어리 판정이 뒤집혔다는 뜻이다.
                a = int(S8.mk_char(cut_one(r, c, 0)).sum())
                b = int(S8.mk_char(cell).sum())
                pad_check.append((r * cols + c, a, b))

    # 침범 제거는 **칸 크기 그대로** 한다 — '칸 가장자리에 닿았나'가 판정 기준이라
    # 캔버스를 먼저 넓히면 아무것도 가장자리에 닿지 않아 이 판정이 통째로 죽는다.
    tot = 0
    rm_each = []
    for i, cl in enumerate(cells):
        cl, rm = S8.drop_intruders(cl)
        cells[i] = cl
        tot += rm
        rm_each.append(rm)
    print(f"침범 제거 {tot}px  (칸별 {rm_each})")

    if VERIFY_TOP_PAD:
        grew = [(i, a, b) for i, a, b in pad_check if b > a]
        shrank = [(i, a, b) for i, a, b in pad_check if b < a]
        print(f"  위PAD 점검 — 본체 늘어난 칸 {len(grew)}개 "
              + ", ".join(f"f{i+1:02d} {a}→{b}(+{b-a})" for i, a, b in grew))
        if shrank:
            print("  ⚠️ 본체가 **줄어든** 칸 — 이웃 조각이 붙었을 수 있다: "
                  + ", ".join(f"f{i+1:02d} {a}→{b}({b-a})" for i, a, b in shrank))
        else:
            print("  위PAD 점검 — 본체가 줄어든 칸 0개 (이웃 조각 유입 없음)")

    cells = [expand(c) for c in cells]           # ★여기서 정렬 여유를 붙인다
    print(f"칸 {W0}x{H0+PAD_CUT_TOP}(원본 {W0}x{H0} + 위{PAD_CUT_TOP}) → 정렬 캔버스 {cells[0].size} (사방 +{PAD_ALIGN})")

    # ── 자세 매핑이 그림과 어긋나지 않는지 먼저 본다(경고만 · 픽셀은 안 건드린다)
    posture_sanity(cells, prof)

    # ── 층1: 쌍 안 — **프로파일이 정한 방식**으로
    for k in range(npairs):
        P = POSTURES[prof[k]]
        rng = P["l1_rng"]
        if P["l1"] == "seed":
            # 누운·웅크린 몸은 발 밴드가 발이 아니라 몸통·엉덩이라 발 기준을 쓸 수 없다.
            # ★탐색은 원점이 아니라 **본체 bbox 중심차(시드) 둘레**에서 돈다 (2026-09-12).
            v, dx, dy, sx, sy, hit = best_seeded(
                S8.mk_char(cells[k*2]), S8.mk_char(cells[k*2+1]), rng)
            cells[k*2+1] = S8.move(cells[k*2+1], dx, dy)
            print(f"  {NAMES[k]:3s}({KEYS[k]:5s}) 층1 dx={dx:+3d}(겹침·시드{sx:+d}) "
                  f"dy={dy:+3d}(시드{sy:+d}) 겹침={v:.3f}  [{prof[k]}]")
            if hit:
                print(f"    ⚠️ 층1 탐색 한계 — 시드 둘레 ±{rng}px 의 **끝값**이 최적이다. "
                      f"정답이 구간 밖일 수 있으니 시드(본체 bbox 중심)를 의심할 것")
        elif P["l1"] == "foot":
            ax, _ = foot_ref_clean(cells[k*2], tag=f" f{k*2+1:02d}")
            bx, _ = foot_ref_clean(cells[k*2+1], tag=f" f{k*2+2:02d}")
            dx = int(round(ax - bx))
            tmp = S8.move(cells[k*2+1], dx, 0)
            v, _, dy = S8.best(S8.mk_char(cells[k*2]), S8.mk_char(tmp), rng)
            cells[k*2+1] = S8.move(cells[k*2+1], dx, dy)
            print(f"  {NAMES[k]:3s}({KEYS[k]:5s}) 층1 dx={dx:+3d}(발)   dy={dy:+3d} "
                  f"겹침={v:.3f}  [{prof[k]}]")
            if abs(dy) == rng:
                print(f"    ⚠️ 층1 탐색 한계 — dy 가 ±{rng}px 의 **끝값**이다. "
                      f"정답이 구간 밖일 수 있다")
        else:
            raise ValueError(f"POSTURES['{prof[k]}']['l1'] 값 '{P['l1']}' 을 모릅니다 "
                             f"— 'foot' 또는 'seed'")

    # ── 층2: 쌍 사이 — 발 좌표 중앙값에 맞춘다(v3 그대로).
    #   foot_ref 의 y 는 실루엣 최하단 y 이므로, 눕기 칸은 세로만 맞춰도
    #   "몸 최하단선 = 바닥선"이 그대로 성립한다.
    refs = [foot_ref_clean(c) for c in cells]
    rx = float(np.median([r[0] for r in refs]))
    ry = float(np.median([r[1] for r in refs]))
    for k in range(npairs):
        P = POSTURES[prof[k]]
        cx, by = refs[k*2]
        dy = int(round(ry - by))                 # 세로는 셋 다 같다 — 실루엣 최하단선 = 바닥선
        l2x = P["l2_x"]
        if l2x == "cell_center" and not center_lying:
            l2x = "none"                         # `--no-center-lying` = 종전 v5 동작
        if l2x == "foot_median":
            dx = int(round(rx - cx))
            note = ""
        elif l2x in ("cell_center", "none"):
            bcx = bbox_center_x(cells[k*2])      # 넓힌 캔버스 기준 본체 가로 중심
            mid = PAD_ALIGN + W0 / 2             # 칸 가로 중앙(=최종 캔버스 x=W0/2)
            diag = int(round(rx - bcx))          # 발 중앙값에 맞추면 얼마였을지(진단용)
            if l2x == "cell_center":
                dx = int(round(mid - bcx))       # ★칸 중앙 정렬
                note = (f"  [{prof[k]} · 칸중앙 정렬 · 본체중심 {bcx - PAD_ALIGN:.1f}"
                        f"→{W0/2:.1f} · dx=0이면 {bcx - PAD_ALIGN:.1f} · "
                        f"발기준이면 dx={int(round(rx - cx)):+d} · 발중앙값기준이면 dx={diag:+d}]")
            else:
                dx = 0                           # 옆으로 밀지 않는다(종전 v5 동작)
                note = (f"  [{prof[k]} · dx=0 · 본체중심 {bcx - PAD_ALIGN:.1f} "
                        f"· 발기준이면 dx={int(round(rx - cx)):+d} · bbox중심기준이면 dx={diag:+d}]")
        else:
            raise ValueError(f"POSTURES['{prof[k]}']['l2_x'] 값 '{P['l2_x']}' 을 모릅니다 "
                             f"— 'foot_median' · 'cell_center' · 'none'")
        if dx or dy:
            for j in (0, 1):
                cells[k*2+j] = S8.move(cells[k*2+j], dx, dy)
        print(f"  {NAMES[k]:3s}({KEYS[k]:5s}) 층2 dx={dx:+3d} dy={dy:+3d} "
              f"(바닥선={P['floor']}){note}")
        # 임계는 프로파일이 자기 것으로 갖는다 — 눕기·웅크림은 중앙정렬 자체가 큰 이동이라 넉넉하다.
        if abs(dx) > P["warn_dx"] or abs(dy) > P["warn_dy"]:
            print(f"    ⚠️ 층2 보정 과다 — |dx|>{P['warn_dx']} 또는 |dy|>{P['warn_dy']} "
                  f"({prof[k]} 기준). 격자 행·열 간격을 의심할 것")
        # 누운 몸은 가로로 길어 원본 칸 폭(312)을 넘길 수 있다 → 프로파일이 켠 유형만 검사.
        if P["check_width"]:
            for j in (0, 1):
                b = S8.mk_char(cells[k*2+j])
                xs = np.nonzero(b.any(axis=0))[0]
                if not len(xs):
                    continue
                lo, hi = int(xs.min()) - PAD_ALIGN, int(xs.max()) - PAD_ALIGN
                if lo < 0 or hi > W0 - 1:
                    print(f"    ⚠️ 폭 제한 — f{k*2+j+1:02d} 본체가 칸 폭(0~{W0-1})을 벗어난다 "
                          f"(x {lo}~{hi}). 마지막 잘라내기에서 좌·우가 잘린다")

    # ── 마지막 잘라내기 (--frame 방식)
    #   아래·좌·우는 원본 칸 경계에 고정, 위만 필요한 만큼 넓힌다.
    base_top = PAD_ALIGN + PAD_CUT_TOP           # 넓힌 캔버스 안에서 **원본 칸의 위 경계**
    uy0 = min(int(np.nonzero(np.array(c)[:, :, 3] > 8)[0].min()) for c in cells)
    top = max(0, min(uy0 - TOP_MARGIN, base_top))
    box = (PAD_ALIGN, top, PAD_ALIGN + W0, base_top + H0)
    cells = [c.crop(box) for c in cells]
    print(f"캔버스 {W0}x{H0} → {cells[0].size} (위로 +{base_top - top} · 아래·좌·우는 원본 고정)")

    # ── 저장
    fdir = out / "frames"; fdir.mkdir(exist_ok=True)
    for i, cl in enumerate(cells, 1):
        cl.save(fdir / f"f{i:02d}.png")

    cdir = out / "cut"; cdir.mkdir(exist_ok=True)
    for k in range(npairs):
        a, b = cells[k*2], cells[k*2+1]
        a.save(cdir / f"{KEYS[k]}.webp", save_all=True, append_images=[b],
               duration=FRAME_MS, loop=0, quality=WEBP_Q)
    print(f"webp {npairs}장 저장 → {cdir}")

    wdir = out / "_work"; wdir.mkdir(exist_ok=True)
    w, h = cells[0].size

    def chk(w, h, s=16):
        bg = Image.new("RGB", (w, h), (210, 214, 220)); px = bg.load()
        for y in range(h):
            for x in range(w):
                if ((x // s) + (y // s)) % 2 == 0:
                    px[x, y] = (170, 176, 186)
        return bg

    sc = 4 if npairs <= 8 else 3
    sr = (npairs + sc - 1) // sc
    fr = []
    for ph in (0, 1):
        cv = chk(w * sc, h * sr).convert("RGBA")
        for k in range(npairs):
            cv.alpha_composite(cells[k*2+ph], ((k % sc) * w, (k // sc) * h))
        fr.append(cv.convert("RGB"))
    fr[0].save(wdir / "상태8.gif", save_all=True, append_images=[fr[1]], duration=450, loop=0)
    seq = []
    for k in range(npairs):
        for _ in range(2):
            seq += [cells[k*2], cells[k*2+1]]
    S8.save_transparent_gif(seq, wdir / "순차.gif", 420)
    print("저장:", wdir / "상태8.gif", "·", wdir / "순차.gif")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    pspec = None
    plain = set()
    for f in flags:                              # `--postures a=b,c=d` 는 값을 갖는다
        if f.startswith("--postures="):
            pspec = f.split("=", 1)[1]
        elif f == "--postures":
            i = sys.argv.index(f)
            if i + 1 < len(sys.argv) and not sys.argv[i+1].startswith("--"):
                pspec = sys.argv[i+1]
                if pspec in args:
                    args.remove(pspec)           # 값이 위치인자로 새지 않게
            else:
                print("✗ --postures 뒤에 매핑이 없습니다 "
                      "(예: --postures sick=crouch,sleep=lying)", file=sys.stderr)
                raise SystemExit(2)
        else:
            plain.add(f)
    unknown = plain - {"--center-lying", "--no-center-lying"}
    if len(args) < 1 or unknown:
        if unknown:
            print(f"✗ 모르는 옵션: {' '.join(sorted(unknown))}", file=sys.stderr)
        print(__doc__); raise SystemExit(2)
    try:
        pmap = parse_postures(pspec)
    except ValueError as e:                      # ★설정 이름을 말하는 예외 — 조용히 안 넘어간다
        print(f"✗ {e}", file=sys.stderr); raise SystemExit(2)
    main(args[0], args[1] if len(args) > 1 else None,
         center_lying=("--no-center-lying" not in plain), postures=pmap)
