#!/usr/bin/env python3
"""
웹툰 생성 파이프라인 하네스 (4~7단계).

  [스토리 파이프라인 완료 — 장면까지 나오고 사람이 판정함]
        |
        v  사람이 [웹툰으로 만들기] 를 누른다  (= 이 스크립트를 --run 으로 실행)
   작품 카드(엔진 카드) 재조립 — 작가 원문 + 사람이 통과시킨 장면 포함
        |
  4단계  큰 줄거리 분할        Arc 4~6개, 각 2~5화
        |   게이트: 설정 3종 사용 · 반전 Arc 비율 1/3 이상
  5단계  Arc -> 회차 분해       Arc 하나씩 반복
        |   회차마다 무대(장소·시간대·사물·동선)를 함께 정한다 — 이게 없으면
        |   7단계가 그릴 것이 인물 얼굴밖에 없다
        |   코드가 id 부여: order · 질문 id · 상환 대상 · 스팅어 연결 (assign_ids)
        |   게이트: engine_fired 비어있지 않음 · 화 수 · Arc 단위 상환 1회 · 무대
  6단계  회차 검사 (별도 세션)  화별 7체크, 불합격 화가 있으면 그 Arc 를 5단계로 되돌림
        |   치명: 엔진급 질문 훼손 -> 자동 재생성 금지, 즉시 중단하고 사람 호출
  7단계  회차 -> 컷 분해        회차 하나씩 반복, 컷 8~16개
        |   컷 하나 = 무엇이 보이는가 + 어디서 보는가(거리·앵글·전환)
        |             + 어떻게 놓이는가(크기·여백·시선·리듬·화면경계·칸)
        |   게이트: 엔진 컷 분산 · 마지막 컷이 스팅어 · 독자 우위 컷
        |          · 크기 분포 · 여백 분포 · turn 존재 · turn 직전 여백 · Scene 경계
        |          · 얼굴 거리 비율 · 인서트/원경 최소 · 앵글 편중 · 전환 다양성
      완료

핵심 규칙
  - 6단계는 항상 새 API 호출. 생성 히스토리를 절대 넣지 않는다.
    (story.py 와 마찬가지로 이 하네스는 어떤 단계에서도 대화를 쌓지 않는다.)
  - 4/5/7 단계 temperature 0.9, 6단계 0.2
  - 컷의 크기·여백은 컷 내용과 **같은 호출에서** 정한다. 예전에는 7.5단계가 확정된
    컷에 연출만 얹었는데, 앞 단계가 이미 균일한 컷을 써 놓은 뒤라 사후 라벨을
    무엇으로 붙이든 결과가 "크기 같은 이미지 나열"이었다.
  - **컷이 단조로운 것은 7단계만의 문제가 아니다.** 완성된 run 3개(643컷)를 세어 보니
    클로즈업+바스트가 60% 였는데, 원인의 절반은 5단계에 있었다 — 회차 요약에 장소가
    한두 곳이고 사물·시간대가 없으면 카메라를 돌릴 곳이 없다. 그래서 무대를 5단계에
    두고, 7단계는 그 무대를 가리키는 축(거리·앵글·전환)을 갖는다.
  - 질문 장부에는 **검사 AI가 인정한 것만** 들어간다. 작가 신고는 반영하지 않는다.
  - **id 는 모델이 짓지 않는다.** 5단계 모델은 질문 본문만 쓰고, 화 번호·질문 id·
    상환 대상·스팅어 연결은 코드가 붙인다. 온도 0.9 에서 id 정합성을 자연어 지시로
    맞추는 것은 확률적으로 계속 실패한다 — 실제로 다섯 번 고쳐도 재발했다.

사용법
  python webtoon.py --list                     # 웹툰으로 만들 수 있는 실행 목록
  python webtoon.py --run <run_id>             # 그 실행을 웹툰으로
  python webtoon.py --run <run_id> --resume    # 중단 지점부터 이어서
  python webtoon.py --run <run_id> --mock      # API 없이 게이트·장부만 점검
  python webtoon.py --run <run_id> --cuts-only   # 회차는 그대로, 컷만 다시 뽑기
  python webtoon.py --run <run_id> --build-view  # webtoon.html 만 다시 생성
"""

from __future__ import annotations

import argparse
import csv
import difflib
import html
import copy
import json
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from story import (
    ROOT, RUNS_DIR, DEFAULT_MODEL, DEFAULT_PROVIDER, DEFAULT_MAX_TOKENS, PROVIDERS,
    TEMP_CREATIVE, TEMP_JUDGE,
    STATUS_OK, STATUS_HUMAN, STATUS_PARSE_FAIL, STATUS_API_FAIL,
    Caller, MockBackend, make_backend, resolve_provider, describe_setup,
    ParseFailure, ApiFailure, Usage, PromptSet, cost_text, append_csv_row,
    load_prompts, render, feedback_block, feedback_slot, write_json, log, warn,
    is_blank, normalize_source, log_prompt_hashes, normalize_palette,
    resolve_directing_notes, env_bool,
    load_user_memory, resolve_user_memory, MEMORY_FILE,
)


STATUS_STOPPED = "중단(엔진급훼손)"

WEBTOON_CONTRACT = {
    "w4": {"engine_card", "character_sheet", "retry_feedback", "user_memory"},
    "w5": {"engine_card", "series_arc", "arc_json", "series_state",
           "retry_feedback", "user_memory"},
    "w6": {"engine_card", "arc_json", "ledger_snapshot", "episodes_json",
           "series_state"},
    "w7": {"engine_card", "series_arc", "arc_json", "episode_json",
           "ledger_snapshot",
           "engine_fired_list", "setting_block", "zones_block",
           "directing_notes",
           "retry_feedback", "user_memory"},
    "w8": {"engine_card", "episode_json", "ledger_snapshot", "cuts_json",
           "pov", "banned_words", "retry_feedback", "user_memory"},
    # 9단계 — 페이지 편집. 컷을 만들지도 고치지도 않고, 확정된 컷을 **화면
    # 단위로 묶고 각 화면의 바탕 컷을 고르는 것**만 한다.
    "w9": {"engine_card", "episode_json", "cuts_json", "retry_feedback"},
}

# ---- 8단계 — 글자를 다시 쓴다 -------------------------------------------
#
# 대사에 나오면 안 되는 낱말. **세계관 용어**다 — 인물이 이런 말을 소리 내어
# 하면 그건 대화가 아니라 독자에게 하는 설명이 된다:
#
#     ✗ 시하 "오늘은 내 '기록' 좀 쉴 차례야."
#
# 같은 낱말이 나레이션·속마음·화면 글자에 있는 것은 괜찮다. 거기는 설명해도
# 되는 자리이고, 오히려 세계를 알려 주는 유일한 통로다.
#
# 이 목록은 엔진 카드에서 자동으로 뽑지 않는다 — 작품마다 다르고, 자동으로
# 뽑으면 "사랑"·"학교" 같은 평범한 낱말까지 걸린다. 새 작품에서 걸릴 낱말이
# 보이면 여기 추가한다.
BANNED_IN_DIALOGUE = ("기록", "평판", "규정", "낙인", "스펙", "등급", "판정")
MAX_MUTE_RUN_W8 = 3      # 무음 연속 상한 (4연속부터 경고)

# 7단계 — 세로 스크롤 문법. 컷 내용과 함께 정해진다.
#
# 크기·여백·시선은 컷을 다 쓴 뒤에 붙이는 라벨이 아니다. 예전에는 7.5단계가 확정된
# 컷 목록에 연출만 얹었는데, 그러면 앞 단계가 균일한 컷 10개를 써 놓은 뒤라 사후
# 라벨이 무엇을 붙이든 결과는 "크기 같은 이미지 나열"이었다. 그 컷이 impact 인지
# wide 인지에 따라 무엇을 그려 넣을지가 달라지므로 같은 호출에서 같이 정한다.
SIZES = ("wide", "normal", "tall", "impact")

# ---- 카메라: 거리·앵글·전환 세 축 ---------------------------------------
#
# 예전에는 카메라를 **서술 첫머리에 낱말로** 적게 했다. 두 가지가 동시에 실패했다.
#
#   ① 그 자리에 size 값을 적는다. 실측: 완성된 run 3개(643컷)에서 서술이
#      "wide." · "normal." 로 시작한 컷이 49% / 64% / 100%. 경고만이라 전부
#      그냥 통과했다. 규칙을 지키라고 더 세게 쓰는 것으로는 안 고쳐진다.
#   ② 낱말 6개에 **거리와 앵글이 한 축에 섞여** 있었다(부감·앙각만 앵글).
#      그래서 실제로는 두 낱말로 붕괴했다 — 같은 643컷에서 클로즈업+바스트가
#      60%, 익스트림 클로즈업 0.5~2%, 앙각 1~4%. 이것이 "컷이 단조롭다"의 정체다.
#      말하는 얼굴과 듣는 얼굴이 번갈아 나오는 화면은 웹툰이 아니라 대본이다.
#
# 그래서 서술에서 빼내 **필드로 분리하고 축을 나눈다.** 필드가 되면 ①은 구조적으로
# 불가능해지고(값이 목록에 없으면 게이트가 잡는다), ②는 축마다 따로 셀 수 있다.
#
# 거리 7종. 인서트를 넣은 것이 핵심이다 — 인물이 없는 컷에 이름이 없으면 모델은
# 그런 컷을 아예 만들지 않는다. Cohn 의 amorphic(활성 인물 없는 패널)에 해당하고,
# 일본 만화가 미국 만화보다 이 비중이 높다는 것이 "분위기가 있다"의 정체다.
SHOTS = ("원경", "전신", "중간", "바스트", "클로즈업", "익스트림", "인서트")
# 얼굴에 붙는 거리. 이 셋만으로 화를 채우면 그게 곧 "말하는 얼굴 나열"이다.
FACE_SHOTS = ("바스트", "클로즈업", "익스트림")
MAX_FACE_RATIO = 0.55   # 실측 60% 였다. 넘으면 되돌린다
MIN_INSERT = 1          # 인물 없는 컷 화당 하한 (사물·손·풍경만 있는 컷)
MIN_WIDE_SHOT = 1       # 장소를 한 번은 보여준다 (establishing)

# 앵글 5종. 거리와 **직교**한다 — 같은 클로즈업도 올려다보면 위협이고 내려다보면
# 열세다. 예전 어휘에는 이 축이 아예 없어서 사실상 전부 눈높이였다.
ANGLES = ("수평", "부감", "앙각", "수직", "기울임")
MAX_LEVEL_RATIO = 0.7   # 수평(눈높이)만으로 채우면 앵글 변화가 없는 것이다
MAX_TILT = 2            # 기울임(더치)은 남발하면 불안이 아니라 멀미다

# 컷 전환 5종 (McCloud 6전환에서 non-sequitur 만 뺐다 — 일반 서사에는 혼란만 준다).
# 이 축이 없으면 모델은 "다음 행동"만 이어 붙인다. 그것이 action-to-action 편중이고,
# McCloud 가 미국 주류 만화의 단조로움으로 지목한 바로 그 패턴이다.
TRANSITIONS = ("순간", "동작", "인물", "장면", "분위기")
# 이 둘은 "같은 자리에서 조금 움직였다" 는 뜻이다. 그 사이에 zone 이 바뀌면
# 한 컷 만에 순간이동한 것이 되므로 서로 모순이다 (zone_warnings).
SAME_PLACE_TRANSITIONS = ("순간", "동작")
# 한 장면은 SCENE_MAX(5)컷까지 갈 수 있고, **한 장면 안에서 쓸 수 있는 전환은
# 순간·동작 둘뿐이다**(SAME_PLACE_TRANSITIONS — 그 사이에 장소가 바뀌면 순간이동이
# 된다). 그래서 5컷짜리 장면 하나는 같은 전환을 4번 연달아 쓸 수밖에 없다 —
# 3으로 두면 **정상적인 장면이 게이트를 위반한다.** 규칙 둘이 서로 싸우고
# 있었고, 실제로 "컷 7~12 의 transition 이 '동작' 로 6컷 연속" 으로 막혔다.
# SCENE_MAX 와 같은 값으로 맞춘다.
MAX_SAME_TRANSITION_RUN = 5   # 6연속부터 실패 (SCENE_MAX 와 같은 값)
MIN_TRANSITION_KINDS = 3      # 한 화에 최소 3종이 섞여야 한다
MOOD_TRANSITION = "분위기"    # aspect-to-aspect. 시간이 흐르지 않는 컷
MIN_MOOD_TRANSITION = 1
SCENE_TRANSITION = "장면"     # 시간·장소가 건너뛴다. 첫 컷은 언제나 이것이다

# 실제 웹툰은 한 화 안에서 그림체가 고정되지 않는다. 진지한 컷은 정식 작화로,
# 분위기 푸는 컷은 SD(2~3등신)로 그린다. 그 전환이 리듬의 일부다.
# bleed·breakout 은 그림체가 아니라 **칸을 어떻게 쓰는가**인데, 컷 하나에 붙는
# 배타적 선택이라는 점이 같아서 같은 필드에 둔다. 축을 더 늘리면 온도 0.9 에서
# 서로 얽힌 조건이 다시 폭발한다 (아래 gate_layout 주석의 실패 이력을 볼 것).
RENDER_STYLES = ("normal", "sd", "emphasis", "bleed", "breakout", "float")
# 하한은 두지 않는다. 최소 개수를 강제하면 모델은 자리를 채우려고 진지한 컷을
# 데포르메로 올린다 — 개수는 맞지만 그 장면이 가벼워진다. SD 는 장면이 부를 때
# 나와야 하는 것이라(긴장이 풀리는 리액션 컷, 개그·당황, 분위기 전환) 진지한
# 장면이 이어지는 화는 0개가 맞는 답이다. 상한만 둔다 — 많으면 산만해진다.
SD_MAX = 5
# build 를 넣은 이유: release/hold 는 화당 몇 개 안 되므로 상한이 거기서 걸렸다.
# 쌓는 중에 한 컷 가볍게 빠지는 것은 실제 웹툰에서 흔한 리듬이다.
# turn 은 여전히 뺀다 — 뒤집는 순간을 데포르메로 그리면 그 순간이 가벼워진다.
SD_BEATS = ("release", "hold", "build")
# 통컷(full-bleed) — 테두리 없이 화면 가장자리까지 꽉 차서 칸이라는 것이 사라진다.
# 화당 한 번뿐이다. 실무 가이드가 한결같이 말하는 것이 이것이다: 남발하면
# "블록버스터 순간"이 아니라 그냥 큰 그림이 된다.
BLEED_MAX = 1
BLEED_BEATS = ("turn", "release")
# 칸 밖으로 튀어나오는 연출 — 인물·사물이 테두리를 뚫고 여백으로 넘어온다.
# 세로 스크롤에서만 되는 것이라(지면은 옆 칸을 침범한다) 웹툰다움의 핵심이지만,
# 매 컷이 튀어나오면 칸이 없는 것과 같아진다.
BREAKOUT_MAX = 2
BREAKOUT_BEATS = ("build", "turn", "release")
# 떠 있는 컷 — **칸도 배경도 없다.** 인물만 단색(또는 톤·별 몇 개) 위에 떠 있고,
# 지면 폭도 절반쯤만 쓴다.
#
# 왜 필요했나: 실제 웹툰은 컷마다 무게가 다르다. 스쳐 가는 리액션 한 컷과 판이
# 뒤집히는 컷이 같은 지면을 먹을 이유가 없는데, 지금까지 이 하네스의 컷은 전부
# 캔버스 하나를 통째로 썼다 — "한 컷 한 컷이 다 의미 있는 컷"이라는 전제가
# size 표에 박혀 있었다.
#
# emphasis 와 다른 점: emphasis 는 배경을 지우고 집중선을 넣는 **극적인** 카드라
# 한 방을 세게 칠 때 쓴다. float 은 그 반대로 **힘을 빼는** 자리다 — 배경을 안
# 그리는 이유가 강조가 아니라 "여기는 굳이 그릴 것이 없어서"다.
# sd 와 다른 점: sd 는 그림체(2~3등신)만 바꾸고 배경은 파스텔로라도 남는다.
#
# 자리는 sd 와 같다(가벼워지는 자리). 상한이 sd 보다 낮은 것은, 배경 없는 컷이
# 이어지면 이야기가 어디서 벌어지는지가 통째로 사라지기 때문이다.
FLOAT_MAX = 3
FLOAT_BEATS = ("release", "hold", "build")
BEATS = ("setup", "build", "turn", "release", "hold")

# ---- 서사적 중요도 — 시각 축과 완전히 분리된 하나의 축 ---------------------
#
# size 는 **틀의 비율**이고 render_style 은 **칸을 어떻게 쓰는가**다. 둘 다
# 화면 이야기이지 이야기의 무게가 아니다. 그런데 무게(weight)를 그 둘에서
# 파생시키고 있었다:
#
#     bleed 또는 impact -> full,  float -> light,  나머지 -> normal
#
# 그래서 "화면은 평범하지만 서사적으로 중요한 컷"(각성·이별·깨달음)을 표현할
# 자리가 없었다. tall 이라 큰 것이 아니라 중요해서 커야 하는데, 지금 구조에서는
# 크게 보이려면 impact 를 골라야 하고 그러면 화면 비율까지 따라 바뀐다.
#
# 그래서 축을 하나 새로 세운다. **W8 이 판정한다** — 8단계는 이미 1화 컷 전부와
# 엔진 카드·회차 설계·질문 장부를 한 번에 받아 읽고 있어서(대사를 고치려면
# 흐름을 알아야 한다) 판정에 필요한 재료를 그대로 들고 있고, 호출도 안 늘어난다.
# 7단계에 12번째 축으로 얹지 않은 이유는 그쪽이 이미 과부하이기 때문이다
# (실측: 한 화에서 bleed·float·sd 가 각 0개 — 뒤쪽 축을 놓치고 있다).
#
# 7.5단계를 새로 만들지 않은 이유는 파일 머리말에 적힌 이력 때문이다 — 확정된
# 컷에 size·render_style 을 사후로 얹는 7.5단계가 예전에 있었고 폐기됐다.
# 다만 그 실패는 **라벨이 내용을 바꿔야 하는데 못 바꿔서** 난 것이고, 중요도는
# 이미 쓰인 내용을 읽고 판정만 하므로 사후가 오히려 맞다.
NARRATIVE_WEIGHTS = ("major", "normal", "minor")

# 중요도 -> 지면 무게. 이 매핑은 **묶기 단계의 내부 규칙**이다. major 가
# "크게 그려라" 라는 뜻이 아니라, 중요한 컷이 결과적으로 한 장을 차지하게 되는
# 것이다 — W8 프롬프트에는 full·light 라는 말이 한 번도 안 나온다.
WEIGHT_BY_NARRATIVE = {"major": "full", "normal": "normal", "minor": "light"}

GAZES = ("down", "toward-next", "at-viewer", "away")
MAX_GAP = 3
MIN_LONG_GAPS = 1       # gap_after 3 의 화당 하한 — 낙차 없는 화는 없다
MAX_LONG_GAPS = 2       # gap_after 3 의 화당 상한
MIN_GAP_KINDS = 3       # 여백은 몇 개만 바꾸는 게 아니라 실제로 흩어져야 한다
MAX_IMPACT = 2          # impact 컷의 화당 상한
MAX_SAME_BEAT_RUN = 3   # 같은 beat 연속 상한 (4연속부터 실패)
MAX_SAME_SIZE_RUN = 3   # 같은 size 연속 상한 (4연속부터 실패)
MAX_SAME_SHOT_RUN = 3   # 같은 거리 연속 상한 (4연속부터 실패)
# 정식 작화 연속 상한 — 게이트가 아니라 경고다. 화 전체의 sd 개수는 이야기가
# 정하지만, normal 만 길게 이어지면 그 구간에 눈이 쉴 자리가 없다.
MAX_NORMAL_RUN = 5
END_SIZES = ("impact", "tall")   # 마지막 컷(스팅어)에 허용되는 크기
SCENE_MIN, SCENE_MAX = 2, 5      # 한 장면(화면 하나)에 들어가는 컷 수
SCENE_COUNT_MIN, SCENE_COUNT_MAX = 2, 5   # 한 화가 몇 장면인가
MIN_SILENT_CUTS = 2     # 글자 없는 컷의 화당 하한 — 침묵이 리듬을 만든다

# ---- 장면의 tone — 그림체를 부르는 자리 ---------------------------------
#
# SD 가 한 화에 한 컷도 안 나오던 이유가 여기 있었다. render_style 은 컷마다
# 정해지는데, 컷을 쓰는 시점에는 "이 장면이 웃긴 장면인가" 를 아무도 말해 주지
# 않는다. 프롬프트는 심지어 "sd 개수를 세지 마라" 라고 가르치므로(옳다), 신호가
# 없으면 모델은 안전한 normal 로 수렴한다. 실제로 4장면 전부 톤이 무색이었고
# 13컷 전부 normal 이 나왔다.
#
# 그래서 **하한을 강제하는 대신 신호를 준다.** "SD 를 3개 넣어라"(숫자)가 아니라
# "이 장면은 개그다"(내용)로 말한다. 이 코드베이스는 비율 게이트를 두 번 넣었다
# 뺐고(text_warnings·render_warnings 주석 참조), 이유는 언제나 같았다 —
# 비율을 강제하면 모델이 이야기가 아니라 숫자에 맞춘다.
TONES = ("개그", "일상", "긴장", "감정")

# ---- 구도 — "의도" 를 "그릴 수 있는 것" 으로 바꾸는 자리 ------------------
#
# 컷 서술에 "몰래 촬영하고 있다" 라고 적으면 그리는 쪽은 그냥 휴대폰을 든 사람을
# 그린다. 의도는 그림이 아니다. 그 순간이 보이려면 **누가 어디에 있는지**가
# 지정되어야 하고, 그게 이 필드다.
COMPOSITIONS = ("none", "over-the-shoulder", "two-shot", "silhouette",
                "reflection", "frame-in-frame")
# ---- 말풍선 자리 — 그림을 그릴 때 비워 둘 곳 -----------------------------
#
# 말풍선은 그림 위에 얹힌다. 그릴 때 자리를 안 비우면 얼굴 위에 놓이거나 글자가
# 잘린다. 이건 보장이 아니라 **확률을 올리는 힌트**다 — 모델은 여전히 무시할 수
# 있고, 최종 안전장치는 합성 단계(bubbles.py)다.
BUBBLE_ZONES = ("top", "bottom", "left", "right", "center", "none")
# 말하는 사람이 화면의 어느 쪽에 있는가. **말풍선 꼬리를 붙일 곳**이다.
#
# speaker(누가 말하는가)만으로는 꼬리를 못 붙인다 — 그 사람이 화면 어디에
# 있는지를 아무도 적지 않았기 때문이다. 그래서 윤재가 하는 말의 꼬리가 시하를
# 가리켰다. 7단계는 이미 "좌우를 고정한다"를 판단하고 있으므로(w7 대화 장면 ①)
# 그 값을 그대로 받아 적기만 하면 된다.
SPEAKER_SIDES = ("left", "right", "center", "offscreen")
# 말풍선·나레이션 상자로 그려지는 글자. sfx 는 그림에 녹아드는 레터링이고
# screen_text 는 화면 안에 있으므로 자리를 따로 비울 필요가 없다.
BUBBLE_FIELDS = ("dialogue", "narration", "thought")
# tone 별로 **금지되는** render_style. 허용 목록이 아니라 금지 목록인 것이
# 중요하다 — 허용으로 쓰면 "개그면 sd 를 써야 한다"가 되어 다시 할당량이 된다.
# 긴장·감정에서 sd 를 막는 것은 개수가 아니라 의미의 문제다: 조여진 공기와
# 드러나는 속을 데포르메로 그리면 그 순간이 통째로 풀린다.
TONE_FORBIDS = {"긴장": ("sd",), "감정": ("sd",)}

# 컷의 텍스트 칸. 웹툰은 말풍선만으로 굴러가지 않는다.
# screen_text 는 휴대폰·모니터 **화면 안**에 글자로 보이는 것이다. 말풍선이
# 아니라 UI 로 그려지지만 독자가 읽는 글자라는 점은 같아서, "글자 없는 컷"을
# 셀 때는 여기 들어간다 — 단톡방이 화면을 채운 컷은 무음이 아니다.
TEXT_FIELDS = ("dialogue", "narration", "thought", "sfx", "screen_text")

# ---- 말이 있는 컷 -------------------------------------------------------
#
# sfx 는 여기 들어가지 않는다. "쿵" 은 소리지 상황이 아니다. 예전에는 네 칸을
# 한 덩어리로 세는 바람에 실태가 가려졌다 — sfx 를 빼고 다시 세니 이랬다
# (완성된 run 3개, 643컷):
#
#   말(대사·나레이션·속마음) 있는 컷   38% / 41% / 28%
#   말 없는 컷 최장 연속               12컷 / 11컷 / 10컷
#   화당 말 있는 컷 최소               1개 / 1개 / 1개
#   컷당 평균 글자수                   9자 / 8자 / 5자
#
# 한 화가 12~13컷인데 말 없는 컷이 12연속이면 그 화는 통째로 무성영화다. 실제로
# 그런 화에서 독자는 "여기가 어디고 지금 무슨 일인지"를 알 수 없었다 — 궁정
# 만찬장 화의 첫 컷(setup)과 판이 뒤집히는 컷(turn·impact)에 둘 다 텍스트가 없었다.
#
# 왜 이렇게 됐나: 프롬프트가 무성 쪽으로만 밀고 있었다. "글자 없는 컷 최소 2개"
# (하한만 있고 상한 없음), "나레이션으로 그림을 때우지 마라", "hold 컷은 대개
# 침묵이다"(hold 가 전체의 24%), "전부 비어 있어도 된다". 반대 방향 제동이 없었다.
#
# 침묵은 카드지 기본값이 아니다. 도쿄대 측정(Ikuta et al. 2023)에 따르면 패널
# 체류 시간은 그림 크기가 아니라 **말풍선 텍스트 길이에 비례**한다 — 글자가 없으면
# 큰 컷을 놓아도 독자는 그냥 지나간다.
SPEECH_FIELDS = ("dialogue", "narration", "thought")

# 한 컷에 말이 여러 줄 들어갈 수 있다 — 두 사람이 주고받는 칸이 웹툰의 기본이다.
#
# 예전 스키마는 컷마다 speaker 1명 + dialogue 1줄이었다. 모델이 소극적이었던 게
# 아니라 **두 줄을 쓸 칸이 없었다.** 그래서 어떤 화를 뽑아도 "한 컷 한 사람 한
# 마디" 만 나왔고, 같은 칸에서 받아치는 연출이 구조적으로 불가능했다.
#
# 새 형식은 cuts[].lines 다:
#   "lines": [{"speaker": "청명", "side": "left",  "kind": "dialogue", "text": "..."},
#             {"speaker": "운학", "side": "right", "kind": "dialogue", "text": "..."}]
#
# 옛 run 을 버리지 않는다. 읽는 쪽은 전부 speech_lines() 를 거치고, 그것이
# lines 가 없으면 옛 세 칸에서 같은 모양을 만들어 준다. 반대로 새 형식이 오면
# sync_legacy_speech() 가 옛 칸에 첫 줄을 되비춰, 옛 칸만 보는 코드(표·통계·
# 그림 하네스의 예비 경로)도 그대로 돈다.
SPEECH_KINDS = ("narration", "dialogue", "thought")
BUBBLE_SIDES = ("left", "right", "center", "offscreen")


def speech_lines(cut: dict) -> list:
    """이 컷의 말 목록 [{kind, text, speaker, side}]. 두 형식을 하나로 만든다."""
    if not isinstance(cut, dict):
        return []
    raw = cut.get("lines")
    out = []
    if isinstance(raw, list) and raw:
        for row in raw:
            if not isinstance(row, dict):
                continue
            text = str(row.get("text") or "").strip()
            kind = str(row.get("kind") or "dialogue").strip().lower()
            if not text or kind not in SPEECH_KINDS:
                continue
            out.append({"kind": kind, "text": text,
                        "speaker": str(row.get("speaker") or "").strip(),
                        "side": str(row.get("side") or "").strip().lower()})
        return out
    # 옛 형식 — 나레이션·대사·속마음 각 한 줄. 순서는 읽는 순서다.
    speaker = str(cut.get("speaker") or "").strip()
    side = str(cut.get("speaker_side") or "").strip().lower()
    for kind in SPEECH_KINDS:
        text = str(cut.get(kind) or "").strip()
        if not text:
            continue
        # 나레이션은 화자가 없다 (상자이지 풍선이 아니다).
        out.append({"kind": kind, "text": text,
                    "speaker": "" if kind == "narration" else speaker,
                    "side": "" if kind == "narration" else side})
    return out


def sync_legacy_speech(cut: dict) -> None:
    """lines 를 옛 칸에 되비춘다. lines 가 없으면 아무것도 하지 않는다.

    되비추는 것은 **종류별 첫 줄**이다. 옛 칸은 종류마다 하나뿐이라 그 이상은
    담을 수 없고, 담으려고 이어 붙이면 말풍선 하나에 두 사람 대사가 들어간다.
    옛 칸만 보는 코드에게 "적어도 첫 줄은 맞다"를 보장하는 것이 여기 목적이다.
    """
    if not isinstance(cut, dict) or not isinstance(cut.get("lines"), list):
        return
    rows = speech_lines(cut)
    for kind in SPEECH_KINDS:
        first = next((r for r in rows if r["kind"] == kind), None)
        cut[kind] = first["text"] if first else ""
    talker = next((r for r in rows if r["kind"] in ("dialogue", "thought")), None)
    cut["speaker"] = talker["speaker"] if talker else ""
    cut["speaker_side"] = talker["side"] if talker else ""


def sync_lines_from_legacy(cut: dict) -> None:
    """옛 칸의 글자를 lines 에 되쓴다. sync_legacy_speech 의 **반대 방향**이다.

    이것이 없던 동안 8단계(글자 다시 쓰기)가 통째로 헛돌았다. 8단계는 옛 세 칸
    (narration·dialogue·thought)에만 적는데, 읽는 쪽은 전부 speech_lines() 를
    거치고 그것은 lines 가 있으면 lines 만 본다 — 그래서 8단계가 다듬은 대사는
    저장은 되면서 화면에도 그림 프롬프트에도 닿지 못했고, 7단계 초안이 그대로
    나갔다. 실측: 말이 있는 컷 71개 중 18개(25%)가 8단계 결과를 버리고 있었다
    (예: 7단계 "여기, 시민! 저쪽은 위험합니다!" 가 나가고, 8단계가 고쳐 둔
    "거기, 강아지 안고 있는 분! 멈추세요, 위험합니다!" 는 버려짐).

    갈아 끼우는 것은 **종류별 첫 줄**이다 — 옛 칸이 종류당 하나뿐이라 그 이상은
    대응할 자리가 없다. 둘째 줄부터는 8단계가 가리킬 방법이 없으므로 그대로
    둔다(지우지 않는다. 한 컷에서 둘이 주고받는 연출이 통째로 사라진다).
    """
    if not isinstance(cut, dict) or not isinstance(cut.get("lines"), list):
        return                      # lines 가 없으면 옛 칸이 이미 유일한 원본이다
    rows = [r for r in cut["lines"] if isinstance(r, dict)]
    speaker = str(cut.get("speaker") or "").strip()
    side = str(cut.get("speaker_side") or "").strip().lower()
    for kind in SPEECH_KINDS:
        text = str(cut.get(kind) or "").strip()
        first = next((r for r in rows
                      if str(r.get("kind") or "dialogue").strip().lower() == kind), None)
        if text and first is not None:
            first["text"] = text
        elif text:
            # 무음이던 자리에 8단계가 글자를 넣었다 — 그것이 이 단계 일의 절반이다.
            rows.append({"kind": kind, "text": text,
                         "speaker": "" if kind == "narration" else speaker,
                         "side": "" if kind == "narration" else side})
        elif first is not None:
            rows.remove(first)      # 8단계가 지운 것은 지운 것이다
    # 나레이션이 아닌 줄의 화자·자리도 8단계 값을 따른다 (옛 칸은 하나뿐이라
    # 첫 줄에만 적용한다 — 둘째 줄의 화자를 첫 줄 화자로 덮으면 주고받음이
    # 한 사람 독백이 된다).
    talker = next((r for r in rows
                   if str(r.get("kind") or "dialogue").strip().lower()
                   in ("dialogue", "thought")), None)
    if talker is not None:
        if speaker:
            talker["speaker"] = speaker
        if side:
            talker["side"] = side
    cut["lines"] = rows


def speakers_in(cut: dict) -> list:
    """이 컷에서 실제로 말하는 사람들 (나레이션 제외, 중복 제거·순서 유지)."""
    seen, out = set(), []
    for row in speech_lines(cut):
        who = row["speaker"]
        if row["kind"] == "narration" or not who or who in seen:
            continue
        seen.add(who)
        out.append(who)
    return out


# ---- 대화는 주고받는 것이다 ---------------------------------------------
#
# 밀도를 채운 다음에도 남는 문제가 있다. 같은 실측에서 대사 **한 줄의 길이**는
# 평균 21~26자로 짧지 않았다. 고장 난 것은 길이가 아니라 구조였다.
#
#   화당 "대사가 연속으로 이어진 최대 길이"
#     1컷뿐(대사가 고립됨)   8화 / 2화 / 10화     ← 절반이 여기다
#     2컷                    6화 / 9화 / 6화
#     3컷 이상               1화 / 6화 / 1화
#
# 한 화에 대사가 3~4줄인데 전부 따로 떨어져 있었다. 아무도 아무에게 대답하지 않는다.
# 그러면 한 줄이 아무리 길어도 상황이 서지 않는다 — "아 강도윤한테 연락왔어" 하고
# 두 컷 건너뛰면 독자는 강도윤이 누구고 그게 왜 문제인지 알 길이 없다.
# 옆에서 "걔한테 또?" "이 정도면 스토커 아니야?" 가 받아쳐 줘야 상황이 쌓인다.
#
# 그리고 **누가 말하는지가 필드에 없었다.** 화자가 description 안에 묻혀 있으면
# 세 사람이 주고받는 장면을 쓸 수가 없다. speaker 를 세운다.
# 아래 둘은 **경고**의 기준이지 게이트가 아니다. 혼자 말하는 화도 나레이션이나
# 속마음이 받쳐 주면 성립하고(1인칭 나레이션으로 굴러가는 웹툰이 많다), 대사를
# 주고받는 것은 여러 수단 중 하나일 뿐이다. 특정 수단을 하한으로 못 박으면
# 모델은 그 수단을 채우기만 하고 연출은 오히려 뻣뻣해진다.
MIN_DIALOGUE_CHAIN = 2   # 대사가 이어지는 구간의 길이. 이보다 짧으면 경고
MONOLOGUE_LIMIT = 3      # 대사가 이만큼인데 화자가 하나면 경고 (나레이션을 권한다)
# 효과음은 그림 위에 레터링으로 얹힌다. 로마자가 섞이면 그림에 로마자가 박힌다.
ROMAN_RE = re.compile(r"[A-Za-z]")
SFX_MAX_LEN = 8         # "위이잉" 은 되고 문장은 안 된다

# 거리 -> 그 거리에 어울리는 size. w7.txt 의 호응표와 같은 내용이다.
# size 를 코드가 고칠 때 아무 값이나 넣지 않고 **모델이 이미 정한 거리**에서
# 끌어온다. 서술은 그대로 두고 크기만 바꾸는 것이므로, 둘이 어긋나면 안 된다.
SHOT_SIZE = {
    "원경": ("wide", "tall"),
    "전신": ("tall", "wide"),
    "중간": ("normal", "tall"),
    "바스트": ("normal", "tall"),
    "클로즈업": ("normal", "impact"),
    "익스트림": ("impact", "normal"),
    "인서트": ("normal", "wide"),
}

# 서술에 남아 있으면 안 되는 것 — 이제 전부 필드다. 서술은 "보이는 것"만 쓴다.
CAMERA_WORDS = ("익스트림 클로즈업", "클로즈업", "바스트", "전신", "부감", "앙각",
                "원경", "중간", "인서트", "수평", "수직", "기울임")

# 컷 서술이 소설 지문으로 흐르는 자리들. 반려하지 않고 경고만 남긴다 —
# 장르에 따라 예외가 있고, 여기서 되돌리면 컷 내용을 재생성하게 되기 때문이다.
#
#   시간 경과: "닦다가 시선을 느끼고 몸을 웅크린다" 는 두 개의 순간이다.
#             이미지 한 장으로 그리면 둘 다 뭉개진다.
#   속마음  : 독자가 볼 수 있는 것만이 컷이다. 생각은 그릴 수 없다.
# "울린 뒤" 의 ㄴ 은 음절 안에 합쳐져 있어 [ㄴ] 로는 잡히지 않는다. 종성이 ㄴ 인
# 한글 음절(399개)을 만들어 문자군으로 쓴다.
#
# 오탐을 줄이는 쪽을 택했다. 경고는 사람이 읽는 것이라, 틀린 경고가 쌓이면 전부
# 안 읽게 된다. 그래서 두 가지를 뺀다:
#   - 뒤/후 가 공간을 뜻하는 자리 (뒤에서 · 뒤로 · 뒤편)
#   - 앞이 한 글자짜리 낱말인 자리 ("문 뒤에" 의 문). 시간을 뜻하는 -ㄴ/-은 은
#     대개 두 음절 이상의 동사에 붙는다("울린 뒤"). 대신 "본 뒤" 같은 한 음절
#     동사는 놓친다 — 놓치는 쪽이 낫다.
N_FINAL = "".join(chr(0xAC00 + i) for i in range(11172) if i % 28 == 4)

TIME_LAPSE = [
    (re.compile(r"[가-힣]다가(?![오가서선])"), "~다가"),
    (re.compile(r"[가-힣]고\s*나서"), "~하고 나서"),
    (re.compile(rf"[가-힣][{N_FINAL}]\s+(뒤|후)에?(?![서로편쪽])(?=[\s,.]|$)"),
     "~한 뒤 / ~한 후"),
    (re.compile(r"[가-힣]더니"), "~하더니"),
    (re.compile(r"[가-힣]자마자"), "~하자마자"),
    (re.compile(r"이윽고|그러고는|그리고는|그 다음|잠시 후|곧이어"), "시간 접속어"),
]
INNER_VOICE = [
    (re.compile(r"생각(한다|하며|하고|에 잠긴|이 든다)"), "생각한다"),
    (re.compile(r"느(낀다|끼고|끼며|끼는|껴진다)"), "느낀다"),
    (re.compile(r"깨(닫는다|닫고|달으며|달은)"), "깨닫는다"),
    (re.compile(r"(회상|직감|예감|결심|다짐|후회|안도)(한다|하며|하고)"), "속마음 서술"),
    (re.compile(r"떠올(린다|리며|리고)"), "떠올린다"),
]

ARC_TYPES = ("전개", "반전", "상승", "해소")
QUESTION_TYPES = ("mystery", "suspense", "dramatic_irony")

# 문서 4장: 떡밥 상한은 코드가 8, 기준 문서가 5. 작업기억 근거를 따라 5 를 기본으로 둔다.
DEFAULT_LEDGER_CAP = 5

# 몇 화가 지나도록 안 갚으면 "오래 묵었다"고 볼 것인가.
#
# 상환 일정을 미리 짜지 않는 대신 나이로 본다. 4로 잡은 이유: Arc 하나가
# 2~5화(gate_arcs)라, 4화가 지났다는 것은 그 질문을 연 Arc 가 사실상 끝났는데
# 아직 안 갚았다는 뜻이다.
STALE_AFTER = 4

WEBTOON_SUMMARY_COLUMNS = [
    "run_id", "character", "genre",
    "arc_count", "reversal_ratio", "episode_count", "series_total", "cut_count",
    "scene_count", "impact_count",
    "episodes_passed", "episodes_failed",
    "eq_violation",                 # 엔진급 질문 훼손 여부
    "ledger_open", "ledger_closed", "betrayal_closures",
    "warnings",                     # 미스터리박스 / 떡밥과부하 / 연체
    "regen_stage4", "regen_stage5", "regen_stage7",
    "elapsed_sec", "total_tokens",
    "cost_usd",                     # 단가를 모르는 모델이 섞이면 빈 칸
    "cost_note",                    # 부분 합계인 이유 (단가를 모르는 모델 이름)
    "status", "note",
]


# ---------------------------------------------------------------- 엔진 카드

def _fmt(value) -> str:
    if is_blank(value):
        return "(비어 있음)"
    if isinstance(value, (list, tuple)):
        return " / ".join(str(v).strip() for v in value if str(v).strip())
    return str(value).strip()


def relational_block(p1: dict) -> list:
    """낙차의 예외 — 이게 있으면 두 사람이 나올 때마다 배신이 재발동한다.

    엔진 카드에 넣는 이유는 4~7단계 전부가 이걸 봐야 하기 때문이다. anchor 가
    카드에 없으면 회차 설계가 주인공 혼자 걸어다니는 이야기를 만든다.
    """
    rel = p1.get("relational_gap")
    if not isinstance(rel, dict):
        return []
    if rel.get("solo") is True:
        return [f"  낙차의 예외: 없음 (1인 완결형) — {_fmt(rel.get('solo_reason'))}"]
    return [
        f"  모두에게: {_fmt(rel.get('to_everyone'))}",
        f"  단 한 사람에게만: {_fmt(rel.get('to_one_only'))}",
        f"  그 한 사람: {_fmt(rel.get('anchor'))}",
        f"  왜 그 사람만 예외인가: {_fmt(rel.get('exception_reason'))}",
    ]


def visual_block(p1: dict) -> list:
    """외형 고정 설계 — 카드에 넣는 이유는 7단계가 이걸 봐야 하기 때문이다.

    컷 서술이 "불안한 표정" 으로 흐르는 것은 어휘가 없어서다. expression_set 을
    카드에 실어 두면 컷마다 표정을 새로 지어내지 않고 여기 적힌 것에서 고른다.
    design_details·color_palette 도 같은 이유다 — 매 컷에 유지될 것을 미리 못 박는다.
    """
    ap = p1.get("appearance") if isinstance(p1.get("appearance"), dict) else {}
    palette = p1.get("color_palette") if isinstance(p1.get("color_palette"), dict) else {}
    # 색은 영문 이름 + hex 로 통일한다. 시트 프롬프트와 같은 값이어야 한다 —
    # 한쪽만 형식이 다르면 같은 인물이 두 색으로 나온다.
    palette = normalize_palette(palette)[0] if palette else {}
    details = p1.get("design_details")
    details = details if isinstance(details, list) else []
    faces = p1.get("expression_set")
    faces = faces if isinstance(faces, list) else []

    # 없는 절은 아예 싣지 않는다. 빈 목록을 실으면 "아래 목록에서 골라 쓰라"는
    # 지시가 빈칸을 가리키게 되고, 모델은 그 자리를 학습 데이터 평균값으로 채운다.
    # (새 필드가 없는 옛 run 도 그대로 다시 돌 수 있어야 한다)
    lines = []
    body = [f"  머리: {_fmt(ap.get('hair'))} · 눈: {_fmt(ap.get('eyes'))}"
            if not is_blank(ap.get("hair")) or not is_blank(ap.get("eyes")) else "",
            f"  체격: {_fmt(ap.get('build'))} · 복장: {_fmt(ap.get('clothing'))}"
            if not is_blank(ap.get("build")) or not is_blank(ap.get("clothing")) else "",
            f"  이미지 생성용(영문): {_fmt(p1.get('appearance_en'))}"
            if not is_blank(p1.get("appearance_en")) else ""]
    body = [b for b in body if b]

    if details:
        body.append("  고정 요소 — 매 컷에 빠지면 안 되는 것:")
        body += [f"    · {_fmt(d)}" for d in details]
    if palette:
        body.append(
            "  색: 머리 {} / 눈 {} / 피부 {} / 옷(주) {} / 옷(보조) {} / 강조 {}".format(
                *[_fmt(palette.get(k)) for k in
                  ("hair", "eyes", "skin", "outfit_main", "outfit_sub", "accent")]))
    if body:
        lines += ["", "[외형 — 모든 컷에서 같아야 하는 것]"] + body

    if faces:
        lines += [
            "",
            "[표정 — 컷 서술은 이 목록의 말로 쓴다]",
            "  이 인물이 이 이야기에서 짓는 표정은 아래가 전부다.",
            "  컷에 표정을 쓸 때 '불안한 표정' 같은 추상어 대신 아래 서술을 옮겨 쓴다.",
        ] + [f"    · {_fmt(f)}" for f in faces]
    return lines


def normalize_cast_row(c: dict, first_episode: int = 0) -> dict:
    """P1 의 supporting_cast 와 5단계의 new_cast 를 한 모양으로 맞춘다.

    P1 은 relation/role 로, 5단계는 note 로 사람을 설명한다. 두 곳에서 온 사람이
    명부에 섞이므로 여기서 통일하지 않으면 카드에 어떤 사람은 관계가 보이고
    어떤 사람은 안 보인다.
    """
    if not isinstance(c, dict):
        return {}
    name = str(c.get("name") or "").strip()
    if not name:
        return {}
    note = (str(c.get("note") or "").strip()
            or str(c.get("relation") or "").strip()
            or str(c.get("role") or "").strip())
    row = {"name": name, "note": note,
           "first_episode": c.get("first_episode", first_episode)}
    for key in CAST_FIELDS:
        row[key] = str(c.get(key) or "").strip()
    # 말투는 CAST_FIELDS 에 넣지 않는다 — 그 목록은 gate_arc_cast 가 **비어 있으면
    # 반려하는** 필수 항목이라, 넣는 순간 이 칸이 없는 예전 회차가 전부 막힌다.
    # 여기서는 있으면 싣고 없으면 빈 문자열로 둔다.
    row["voice_notes"] = str(c.get("voice_notes") or "").strip()
    # P1 의 role 은 '이 사람이 이야기에서 하는 일' 이라 note 와 다를 때만 남긴다.
    role = str(c.get("role") or "").strip()
    row["role"] = role if role and role != note else ""
    return row


def cast_block(roster) -> list:
    """조연 명부 — 주인공과 달리 이들은 캐릭터 시트가 없다.

    카드에 안 실으면 뒷단계가 매번 새로 만든다. 실제로 같은 캐릭터 파일을 두 번
    돌렸더니 후배 이름이 '하윤재' → '장지운' 으로 바뀌었고, 그림에서는 설정상
    남자인 후배가 여자로 그려졌다. 성별과 외형을 여기 못 박아야 7단계 컷 지시가
    그걸 옮겨 적는다.

    roster 는 **그 시점까지의 전체 명부**다. 1화 전에는 P1 이 정한 사람들뿐이지만,
    연재가 진행되면 5단계가 새로 만든 인물이 쌓인다. P1 것만 실으면 3화에서
    나온 사람이 7화 컷 설계에서 사라진다 — 그리는 쪽은 그 사람을 처음 보게 된다.

    dict(P1 카드)를 넘기면 supporting_cast 를 꺼내 쓴다 — 옛 호출 경로용.
    """
    if isinstance(roster, dict):
        roster = roster.get("supporting_cast")
    rows = [normalize_cast_row(c) for c in roster or []]
    rows = [r for r in rows if r]
    if not rows:
        return []
    lines = [
        "",
        "[조연 — 이름·성별·외형을 바꾸지 않는다]",
        "  이 이야기에 이름을 가지고 나오는 사람은 주인공과 아래가 전부다.",
        "  새 이름을 만들지 말고, 아래 성별·외형을 컷 지시에 그대로 옮겨 쓴다.",
    ]
    for r in rows:
        when = "" if not r["first_episode"] else f", {r['first_episode']}화부터"
        head = f"    · {_fmt(r['name'])} ({_fmt(r['gender'])}{when})"
        lines.append(f"{head} — {r['note']}" if r["note"] else head)
        for key, label in (("appearance", "외형"), ("outfit", "옷차림"),
                           ("personality", "성격"), ("voice_notes", "말투"),
                           ("role", "역할")):
            if not is_blank(r.get(key)):
                lines.append(f"        {label}: {_fmt(r[key])}")
    return lines


def status_block(status) -> list:
    """상태 카드 — 명부(안 바뀌는 외형) 옆에 붙는 **지금 몸에 남아 있는 것**.

    둘을 나눠 두는 이유: 외형은 끝까지 같지만 상태는 이야기가 바꾼다. 한 칸에
    뭉쳐 두면 반창고를 그리려다 머리색까지 흔들린다 — 주인공에게 실제로 났던 일이다.
    """
    rows = [s for s in status or [] if isinstance(s, dict) and s.get("who")]
    if not rows:
        return []
    lines = [
        "",
        "[지금 인물에게 남아 있는 것 — 컷마다 그려야 한다]",
        "  외형은 안 바뀌지만 아래는 이야기가 남긴 자국이다. 그 사람이 나오는 "
        "**모든 컷**에 있어야 한다.",
    ]
    for s in rows:
        until = str(s.get("until") or "").strip()
        tail = f", {until}" if until and until != "계속" else ""
        lines.append(f"    · {_fmt(s.get('who'))}: {_fmt(s.get('state'))} "
                     f"({s.get('since_episode')}화부터{tail})")
    return lines


def build_engine_card(p1: dict, p2: dict, idea: str, scenes: list,
                      roster=None, status=None, seed=None) -> str:
    """4~7단계 모든 프롬프트에 원문 그대로 삽입되는 작품 카드.

    마지막 두 블록이 핵심이다. 로그라인·룰·대가만 담으면 작가가 쓴 구체적 묘사와
    사람이 통과시킨 장면이 여기서 잘려 나가고, 뒤 단계는 빈칸을 학습 데이터
    평균값(의문의 책·빛나는 유물)으로 채운다.
    """
    rule = p2.get("rule") if isinstance(p2.get("rule"), dict) else {}
    cost = p2.get("cost") if isinstance(p2.get("cost"), dict) else {}
    irony = p2.get("irony") if isinstance(p2.get("irony"), dict) else {}

    # 세계관은 seed 에만 있었고 카드에는 실리지 않았다. 그래서 5·7단계는 이 이야기가
    # **어떤 세계인지** 모른 채 썼고, 나레이션이 쓸 재료가 시간·장소밖에 없었다 —
    # 실측: 완성된 화의 나레이션 3개가 전부 "늦은 오후, 라운지 한켠" 꼴이었다.
    # 로판이라면 "엘젠하르트 제국" 같은 고유명사가 여기서 나와야 한다.
    seed = seed if isinstance(seed, dict) else {}
    world = [ln for ln in (f"[장르] {_fmt(seed.get('genre'))}"
                           if not is_blank(seed.get("genre")) else "",
                           f"[세계] {_fmt(seed.get('world'))}"
                           if not is_blank(seed.get("world")) else "") if ln]

    lines = [
        "=== 엔진 카드 ===",
        f"[로그라인] {_fmt(p2.get('logline'))}",
        f"[장르 기대] {_fmt(p2.get('genre_expectation'))}",
        f"[장르 약속 — 지킬 것] {_fmt(p2.get('genre_promise'))}",
        f"[반전 축] {_fmt(p2.get('subversion_axis'))}",
        f"[장르 배신] {_fmt(p2.get('genre_betrayal'))}",
    ] + world + [
        "",
        f"[RULE] {_fmt(rule.get('statement'))}",
        f"  재발동 조건: {_fmt(rule.get('retrigger_condition'))}",
        f"[COST] {_fmt(cost.get('statement'))}",
        f"  발동 메커니즘: {_fmt(cost.get('advance_mechanism'))}",
        f"[IRONY] {_fmt(irony.get('statement'))}",
        f"  A측이 원하는 것: {_fmt(irony.get('side_a'))}",
        f"  B측이 원하는 것: {_fmt(irony.get('side_b'))}",
        f"  출구 봉쇄 이유: {_fmt(irony.get('no_exit_reason'))}",
        "",
        f"[낙차] {_fmt(p2.get('drop'))}",
        f"[엔진급 질문] {_fmt(p2.get('engine_question'))}",
        f"[금지된 뒤집기] {_fmt(p2.get('forbidden_subversion'))}",
        "",
        f"[주인공] {_fmt(p1.get('name'))}",
        "  편지·쪽지 등 소품 텍스트에 등장하는 인물 이름도 항상 이 이름을 "
        "그대로 쓴다. 새 이름을 짓지 않는다.",
        f"  성격: {_fmt(p1.get('personality'))}",
    ] + ([
        # 말투는 P1 이 정해 놓고도 여기서 잘려 나가고 있었다. 그래서 1화 도입부
        # (scene.txt)까지만 이 인물의 말투가 들렸고, 연재 회차의 대사는 인물이
        # 누구든 같은 목소리로 나왔다. 대사를 쓰는 단계가 이걸 봐야 한다.
        f"  말투 — 이 사람의 대사는 이렇게 들려야 한다: "
        f"{_fmt(p1.get('voice_notes'))}",
    ] if not is_blank(p1.get("voice_notes")) else []) + [
        f"  카드 한 줄(intro): {_fmt(p1.get('intro'))}",
        f"  직함: {_fmt(p1.get('rank'))}",
        f"  want: {_fmt(p1.get('want'))}",
        f"  need: {_fmt(p1.get('need'))}",
        f"  겉모습(A): {_fmt(p1.get('surface_attribute_a'))}",
        f"  속모습(B): {_fmt(p1.get('betrayal_attribute_b'))}",
        f"  공존 강제: {_fmt(p1.get('coexistence_forced'))}",
        f"  독자가 대리 체험하는 것: {_fmt(p1.get('wish_fulfillment'))}",
    ]
    lines += relational_block(p1)
    lines += visual_block(p1)
    # roster 를 안 주면 P1 이 정한 사람들만 — 화가 진행될수록 틀려지므로,
    # run_webtoon 은 화마다 그 시점의 명부를 넘겨 카드를 다시 만든다.
    lines += cast_block(roster if roster is not None else p1)
    lines += status_block(status)
    lines += [
        "",
        "[운명 비트 — 1화 오프닝 시퀀스로 쓴다]",
    ]
    beats = p1.get("fate_beats")
    if isinstance(beats, list) and beats:
        labels = ("① 상황", "② 사건", "③ 위협/운명", "④ 비대칭 카드")
        for i, b in enumerate(beats):
            label = labels[i] if i < len(labels) else f"{i + 1}."
            lines.append(f"  {label}: {_fmt(b)}")
    else:
        lines.append("  (없음)")
    lines += [
        "",
        "[작가가 처음 준 한 줄]",
        f"  {idea.strip()}",
        "",
        # ── 승인은 계약이다 ────────────────────────────────────────────────
        # 사용자는 이 장면들의 **산문 전문**을 읽고 "이대로 진행"을 눌렀다.
        # 예전에는 여기 요약(one_line·choice)만 실려서, 사용자가 읽고 승인한
        # 본문 — 구체적 행동·감정·묘사 — 이 콘티 단계에 안 닿았다. 요약이
        # 사건을 잘 담으면 운 좋게 따라가지만, 승인한 것과 다른 1화가 나와도
        # 막을 것이 없었다. 승인받은 원문을 그대로 싣고, 1화 컷은 이것의
        # **번역**이지 재창작이 아니라고 못박는다.
        "[1화 도입부 — 사람이 통과시킨 장면]",
        "  ★ 작가가 이 장면들을 읽고 승인했다. 1화의 컷은 이 장면을 컷으로",
        "    옮기는 일이다 — 사건·순서·인물의 행동을 지키고, 다른 사건으로",
        "    바꾸거나 빼지 않는다. 살은 붙여도 뼈대는 이것이다.",
    ]
    if scenes:
        for i, s in enumerate(scenes, 1):
            one = _fmt(s.get("one_line")) if not is_blank(s.get("one_line")) \
                else str(s.get("text") or "")[:60]
            lines.append(f"  {i}. {one}")
            if not is_blank(s.get("choice")):
                lines.append(f"     선택: {_fmt(s.get('choice'))}")
            # 승인받은 산문 전문. 들여쓰기로 요약과 구분한다.
            text = str(s.get("text") or "").strip()
            if text:
                lines.append("     본문:")
                for ln in text.splitlines():
                    lines.append(f"       {ln.strip()}" if ln.strip() else "")
    else:
        lines.append("  (장면 없음)")
    lines.append("=== /엔진 카드 ===")
    return "\n".join(lines)


# ---------------------------------------------------------------- 질문 장부

@dataclass
class Question:
    id: str
    text: str
    type: str
    opened_arc: int
    opened_episode: int          # 통산 화 번호
    planned_payoff_episode: object = None
    is_engine: bool = False
    closed_arc: object = None
    closed_episode: object = None
    is_betrayal: object = None

    @property
    def is_open(self) -> bool:
        return self.closed_episode is None

    def as_dict(self) -> dict:
        return {
            "id": self.id, "text": self.text, "type": self.type,
            "openedAt": {"arc": self.opened_arc, "episode": self.opened_episode},
            "closedAt": None if self.is_open else
                        {"arc": self.closed_arc, "episode": self.closed_episode},
            "plannedPayoffEpisode": self.planned_payoff_episode,
            "isEngine": self.is_engine,
            "isBetrayal": self.is_betrayal,
        }


@dataclass
class Fact:
    """열린/닫힌 질문과는 다른 것 — 답이 정해질 질문이 아니라 이미 확정된 사실.

    "3화에서 왼손에 흉터가 생겼다" 같은 것. Question 은 "언젠가 닫힐 궁금증"을
    추적하고, Fact 는 "이후 화들이 잊으면 안 되는 확정된 사실"을 추적한다 —
    둘을 같은 목록에 섞으면 열림/닫힘 상태가 없는 Fact 가 open_items/
    closed_items 계산을 흐린다.
    """
    id: str
    text: str
    established_episode: int      # 통산 화 번호

    def as_dict(self) -> dict:
        return {"id": self.id, "text": self.text,
                "establishedEpisode": self.established_episode}


class Ledger:
    """열린 질문·닫힌 질문·확정된 사실을 코드가 추적한다. 검사자가 인정한 것만 들어온다."""

    def __init__(self, engine_question: str, cap: int = DEFAULT_LEDGER_CAP):
        self.items: list = []
        self.facts: list = []
        self.fact_seq = 0
        self.cap = cap
        self.seq = 0
        self.engine = Question(
            id="EQ", text=engine_question or "(엔진급 질문 미지정)",
            type="engine", opened_arc=0, opened_episode=0, is_engine=True)
        self.items.append(self.engine)

    def add_fact(self, text: str, episode: int) -> Fact:
        """확정된 사실을 하나 기록한다. sync_facts 가 회차 명부에서 끌어온다."""
        self.fact_seq += 1
        f = Fact(id=f"F-{self.fact_seq}", text=text, established_episode=episode)
        self.facts.append(f)
        return f

    def sync_facts(self, rows) -> int:
        """SeriesState.facts 를 장부로 옮긴다. 이미 있는 문장은 건너뛴다.

        이 통로가 없어서 **확정된 설정이 대사를 쓰는 단계에 한 번도 도달하지
        않았다.** snapshot() 은 established_facts 를 싣고 7·8단계가 그걸 받지만,
        여기 담기는 Ledger.facts 는 아무도 채우지 않아 언제나 빈 목록이었다.
        설정을 쌓는 곳(SeriesState)과 설정을 봐야 하는 곳(장부 스냅샷)이 끊겨
        있었던 것이라, 글자를 쓰는 모델은 앞 화에서 무엇이 정해졌는지 모른 채
        썼다 — 앞뒤가 어긋나는 대사·나레이션의 기계적인 원인이다.
        """
        seen = {f.text for f in self.facts}
        added = 0
        for row in rows or []:
            if not isinstance(row, dict):
                continue
            text = str(row.get("fact") or "").strip()
            if not text or text in seen:
                continue
            seen.add(text)
            self.add_fact(text, row.get("first_episode") or 0)
            added += 1
        return added

    # -- 조회
    def get(self, qid: str):
        for q in self.items:
            if q.id == qid:
                return q
        return None

    @property
    def open_items(self) -> list:
        return [q for q in self.items if q.is_open and not q.is_engine]

    @property
    def closed_items(self) -> list:
        return [q for q in self.items if not q.is_open]

    # -- 변경
    def open(self, text: str, qtype: str, arc: int, episode: int,
             planned=None) -> Question:
        self.seq += 1
        q = Question(id=f"Q-{self.seq}", text=text,
                     type=qtype if qtype in QUESTION_TYPES else "mystery",
                     opened_arc=arc, opened_episode=episode,
                     planned_payoff_episode=planned)
        self.items.append(q)
        return q

    def close(self, qid: str, arc: int, episode: int, is_betrayal) -> bool:
        q = self.get(qid)
        if q is None or not q.is_open:
            return False
        q.closed_arc, q.closed_episode = arc, episode
        q.is_betrayal = bool(is_betrayal)
        return True

    # -- 자동 경고 (문서 4장)
    def warnings(self, current_episode: int) -> list:
        out = []

        recent = [q for q in self.closed_items
                  if q.closed_episode and q.closed_episode > current_episode - 3]
        if current_episode >= 3 and not recent:
            out.append("미스터리 박스: 최근 3화 상환 0건 — 떡밥만 쌓이고 회수가 없습니다.")

        if len(self.open_items) > self.cap:
            out.append(
                f"떡밥 과부하: 안 풀린 질문이 {len(self.open_items)}개로 상한 {self.cap} 초과 "
                "— 궁금한 게 너무 많아 아무것도 안 궁금해집니다.")

        # 연체 경고는 없앴다. 몇 화에 갚겠다는 계획을 미리 세우지 않기 때문이다 —
        # 연재는 앞 화를 보고 다음 화를 정하는 것이지, 17화치 상환 일정을 1화에서
        # 짜는 것이 아니다.
        #
        # 그렇다고 "언제 갚나"를 아예 안 볼 수는 없다. 개수 상한(과부하)과 최근
        # 상환 여부(미스터리 박스)만으로는 **오래 묵은 질문 하나**가 안 잡힌다 —
        # 개수가 상한 안이고 매 화 뭔가 갚고 있어도, 3화에 연 질문이 12화까지
        # 열려 있으면 독자는 그것을 잊는다. 일정 대신 **나이**로 본다.
        stale = [q for q in self.open_items
                 if current_episode - q.opened_episode >= STALE_AFTER]
        if stale:
            oldest = min(stale, key=lambda q: q.opened_episode)
            out.append(
                f"장기 미상환: {len(stale)}개가 {STALE_AFTER}화 넘게 열려 있습니다 "
                f"(가장 오래된 것은 {oldest.opened_episode}화에 연 "
                f"\"{str(oldest.text)[:40]}\"). 갚거나, 지금 다시 꺼내 살려 두세요 "
                "— 오래 묵으면 독자는 그것을 궁금해하지 않고 잊습니다.")

        if not self.engine.is_open:
            out.append("엔진급 훼손: 엔진급 질문이 닫혔습니다 — 치명, 즉시 중단.")
        return out

    def snapshot(self, current_episode: int, hide_ids: bool = False) -> str:
        """hide_ids=True 는 5단계(작가) 전용이다.

        id 를 보여주면 온도 0.9 에서 그것을 베끼거나 지어낸다. 실제로 'EQ' 를 상환
        대상으로 적거나 출력 예시의 'Q-3' 를 그대로 옮겨 적는 실패가 반복됐다.
        작가는 본문만 보고 본문으로 지목한다 — id 연결은 코드가 한다(assign_ids).
        """
        def view(q: Question) -> dict:
            d = q.as_dict()
            if hide_ids:
                d.pop("id", None)
            # 열린 질문에는 **몇 화째 열려 있는지**를 같이 준다. 작가가 목록만
            # 보면 다섯 개가 다 똑같이 급해 보이는데, 실제로 급한 것은 오래
            # 묵은 쪽이다. 상환 순서를 정하는 유일한 단서가 이 숫자다.
            if q.is_open and not q.is_engine:
                age = current_episode - q.opened_episode
                if age >= 0:
                    d["openFor"] = age
                    if age >= STALE_AFTER:
                        d["stale"] = True
            return d

        engine = {"text": self.engine.text,
                  "note": "이 질문은 어느 화에서도 닫지 않는다"}
        if not hide_ids:
            engine["id"] = "EQ"
        return json.dumps({
            "engine_question": engine,
            "open": [view(q) for q in self.open_items],
            "closed": [view(q) for q in self.closed_items],
            "open_count": len(self.open_items),
            "open_cap": self.cap,
            "established_facts": [f.as_dict() for f in self.facts],
            "warnings": self.warnings(current_episode),
        }, ensure_ascii=False, separators=(",", ":"))

    def as_dict(self) -> dict:
        return {"cap": self.cap, "questions": [q.as_dict() for q in self.items],
                "facts": [f.as_dict() for f in self.facts]}

    @classmethod
    def from_dict(cls, data: dict) -> "Ledger":
        """ledger.json 을 되살린다 — --cuts-only 가 장부를 다시 만들지 않게.

        컷만 다시 뽑을 때 장부를 처음부터 재계산하면 4~6단계를 전부 다시 도는 것이다.
        파일에 남은 것이 그때 검사자가 인정한 결과이므로 그대로 읽는다.
        """
        led = cls("", cap=data.get("cap") or DEFAULT_LEDGER_CAP)
        led.items = []
        for d in data.get("questions") or []:
            opened = d.get("openedAt") or {}
            closed = d.get("closedAt") or {}
            q = Question(
                id=str(d.get("id") or ""), text=str(d.get("text") or ""),
                type=str(d.get("type") or ""),
                opened_arc=opened.get("arc") or 0,
                opened_episode=opened.get("episode") or 0,
                planned_payoff_episode=d.get("plannedPayoffEpisode"),
                is_engine=bool(d.get("isEngine")),
                closed_arc=closed.get("arc"),
                closed_episode=closed.get("episode"),
                is_betrayal=d.get("isBetrayal"))
            led.items.append(q)
            if q.is_engine:
                led.engine = q
        if not any(q.is_engine for q in led.items):
            led.items.insert(0, led.engine)
        led.seq = len([q for q in led.items if not q.is_engine])
        # "facts" 키가 없는 예전 ledger.json 도 그대로 읽는다 — 순수 추가라
        # 없으면 그냥 빈 목록.
        for d in data.get("facts") or []:
            led.facts.append(Fact(
                id=str(d.get("id") or ""), text=str(d.get("text") or ""),
                established_episode=d.get("establishedEpisode") or 0))
        led.fact_seq = len(led.facts)
        return led


# ---------------------------------------------------------------- 연재 상태
#
# 화를 하나씩 만들면 생기는 문제가 하나 있다: **3화가 1화를 모른다.**
# 직전 화만 넘기면 1화에서 이름 붙인 인물이 3화에서 다른 이름이 되고, 1화에서
# 세운 설정이 3화에서 조용히 뒤집힌다. 요약을 이어 붙이는 것만으로는 부족하다 —
# 요약은 사건을 남기지 사람과 규칙을 남기지 않는다.
#
# 그래서 화를 만들 때마다 **누적 명부**를 갱신한다. 다음 화를 쓰는 모델은 이
# 명부를 통째로 본다. 17화를 미리 설계하지 않는 대신, 지금까지 확정된 것은
# 하나도 잊지 않는 것이 이 구조가 성립하는 조건이다.

@dataclass
class SeriesState:
    run_id: str = ""
    episodes: list = field(default_factory=list)   # {no, arc, title, summary, stinger}
    cast: list = field(default_factory=list)       # {name, note, first_episode}
    facts: list = field(default_factory=list)      # {fact, first_episode}
    places: list = field(default_factory=list)     # {place, first_episode}
    # 상태 카드 — 외형(명부)과 달리 **이야기가 남긴 자국**이다. 다치고, 머리를
    # 자르고, 옷이 바뀐다. 여기 없으면 다음 화에서 없던 일이 된다: 3화에서 부러진
    # 팔이 4화에 멀쩡하면 독자에게는 그 부상이 취소된 것이다.
    status: list = field(default_factory=list)     # {who, state, until, since_episode}
    # 존 — 배경 자산을 재사용할 자리. places 와 달리 이름뿐 아니라 **그림 하네스가
    # 배경 1장을 만들어 붙일 수 있는 단위**다. 7단계가 zones[] 로 "그 구역이
    # 사람 없이 어떻게 생겼는가" 를 따로 적고, 그것이 그대로 배경 프롬프트가 된다.
    #
    # 소품 장부는 두지 않는다. 자판기에 머그컵이 나오는 것 같은 사고는 소품을
    # 하나씩 등록해 매 컷 대조하는 것으로 막지 않는다 — 그 검사는 컷마다
    # 수십 건씩 판정을 만들고 진짜 오류는 회당 한둘이라, 노이즈에 묻혀 아무도
    # 안 보게 된다. 대신 **존 배경을 한 번 굽고 재사용**한다. 자판기가
    # z-hallway-vending 배경에 한 번 그려지면 그 뒤 그 존의 컷은 전부 그
    # 배경을 참조하므로 머그컵이 새로 생길 경로 자체가 없다.
    zones: list = field(default_factory=list)      # {zone_id, place, label, first_episode}
    # ---- Story State — 이야기의 현재 ------------------------------------
    # status 가 몸에 남는 자국이라면, 아래 둘은 **사람 사이와 사람 속**에 남는
    # 자국이다. 이것이 없던 동안 W5 는 "1화에 서도윤이 나왔으니 아는 사이겠지"
    # 를 매번 추론했고, 초면인 인물이 이름을 부르는 화가 나왔다(실측, 2화).
    #
    # 둘 다 append-only 다. 나중 것이 앞을 지우지 않는다 — "1화: 처음 마주침"
    # 위에 "3화: 통성명"이 쌓이면 그 이력 자체가 관계의 현재이고, 다음 화가
    # "아 맞아 얘네 이랬지"를 만들 재료다.
    #
    # relations 는 **방향이 있다** (who → about). 그래서 "도윤은 기억하는데
    # 리아는 기억 못 한다" 같은 비대칭이 자연스럽게 표현된다 — 도윤→리아
    # 항목만 있고 리아→도윤 항목이 없으면 그것이 곧 비대칭이다.
    relations: list = field(default_factory=list)  # {who, about, state, since_episode}
    # minds — 한 사람의 속. 새로 알게 된 것·기억·오해·감정·목표.
    minds: list = field(default_factory=list)      # {who, state, since_episode}

    @property
    def made(self) -> int:
        return len(self.episodes)

    def next_no(self) -> int:
        return self.made + 1

    def add(self, no: int, arc_order, episode: dict) -> None:
        setting = episode.get("setting")
        setting = setting if isinstance(setting, dict) else {}
        self.episodes.append({
            "no": no,
            "arc": arc_order,
            "title": str(episode.get("title") or "").strip(),
            "summary": str(episode.get("summary") or "").strip(),
            "stinger": str((episode.get("stinger") or {}).get("text") or "").strip(),
            "place": str(setting.get("place") or "").strip(),
        })
        # 장소는 쌓아 둔다. 매 화 새 장소를 만들면 세계가 넓어지는 게 아니라
        # 얇아진다 — 독자가 두 번째로 보는 공간이 있어야 그곳이 기억에 남는다.
        seen_place = {p["place"] for p in self.places}
        place = str(setting.get("place") or "").strip()
        if place and place not in seen_place:
            self.places.append({"place": place, "first_episode": no})
        known = {c["name"] for c in self.cast}
        for c in episode.get("new_cast") or []:
            name = str((c or {}).get("name") or "").strip()
            if name and name not in known:
                known.add(name)
                # 인물 카드를 통째로 명부에 남긴다. 이 인물들은 캐릭터 시트가
                # 없어서 그림 단계가 가진 것이 이것뿐이다 — 비어 있으면 컷마다
                # 다른 사람으로 그려진다 (성별까지 바뀐다).
                self.cast.append(normalize_cast_row(c, first_episode=no))
        seen = {f["fact"] for f in self.facts}
        for f in episode.get("new_facts") or []:
            text = str(f or "").strip()
            if text and text not in seen:
                seen.add(text)
                self.facts.append({"fact": text, "first_episode": no})

        # 상태는 사람마다 쌓인다. 같은 사람이 3화에서 다치고 5화에서 머리를
        # 자르면 둘 다 유지된다 — 나중 것이 앞의 것을 지우지 않는다.
        held = {(s["who"], s["state"]) for s in self.status}
        for s in episode.get("state_changes") or []:
            if not isinstance(s, dict):
                continue
            who = str(s.get("who") or "").strip()
            what = str(s.get("state") or "").strip()
            if not who or not what or (who, what) in held:
                continue
            held.add((who, what))
            self.status.append({
                "who": who, "state": what,
                "until": str(s.get("until") or "계속").strip(),
                "since_episode": no})

        # 사람 사이 — 이 화가 두 사람 관계에 남긴 것. status 와 같은 append-only.
        held_r = {(r["who"], r["about"], r["state"]) for r in self.relations}
        for r in episode.get("relation_changes") or []:
            if not isinstance(r, dict):
                continue
            who = str(r.get("who") or "").strip()
            about = str(r.get("about") or "").strip()
            what = str(r.get("change") or r.get("state") or "").strip()
            if not who or not about or not what or (who, about, what) in held_r:
                continue
            held_r.add((who, about, what))
            self.relations.append({"who": who, "about": about,
                                   "state": what, "since_episode": no})

        # 사람 속 — 이 화가 한 사람의 인지·감정·목표에 남긴 것.
        held_m = {(m["who"], m["state"]) for m in self.minds}
        for m in episode.get("mind_changes") or []:
            if not isinstance(m, dict):
                continue
            who = str(m.get("who") or "").strip()
            what = str(m.get("change") or m.get("state") or "").strip()
            if not who or not what or (who, what) in held_m:
                continue
            held_m.add((who, what))
            self.minds.append({"who": who, "state": what, "since_episode": no})

    def seed_cast(self, supporting_cast) -> int:
        """P1 이 확정한 조연을 0화 명부로 깔아 둔다.

        이걸 안 하면 1화가 조연을 '새로 만드는' 것이 되고, 같은 캐릭터 파일로
        두 번 돌릴 때마다 후배 이름이 달라진다(하윤재 → 장지운). 스토리 단계에서
        정한 이름·성별이 연재 명부의 출발점이어야 한다.

        first_episode 는 0 — 1화가 시작되기 전부터 있던 사람이라는 뜻이다.
        """
        if not isinstance(supporting_cast, list):
            return 0
        known = {c["name"] for c in self.cast}
        added = 0
        for c in supporting_cast:
            if not isinstance(c, dict):
                continue
            name = str(c.get("name") or "").strip()
            if not name or name in known:
                continue
            known.add(name)
            self.cast.append(normalize_cast_row(c, first_episode=0))
            added += 1
        return added

    def brief(self, ledger: "Ledger") -> str:
        """다음 화를 쓰는 모델에게 주는 '지금까지'. 요약이 아니라 명부다."""
        if not self.episodes:
            # 1화라도 명부는 비어 있지 않다 — 스토리 단계가 정한 조연이 이미
            # 여기 들어와 있다. 그걸 안 보여주면 1화가 같은 역할의 사람을
            # 새 이름으로 또 만든다.
            out = ["아직 만들어진 화가 없습니다. 이번이 **1화**입니다.",
                   "독자가 이 작품을 처음 보는 자리이므로, 인물과 세계를 처음으로 "
                   "보여주고 앞으로를 궁금하게 만드는 것이 이 화의 일입니다."]
        else:
            out = [f"지금까지 {self.made}화가 나왔습니다. "
                   f"이번은 {self.next_no()}화입니다.", ""]
            out.append("[나온 화 — 전부]")
            for e in self.episodes:
                out.append(f"  {e['no']}화 (Arc {e['arc']}) 「{e['title']}」")
                out.append(f"      {e['summary']}")
                if e["stinger"]:
                    out.append(f"      마지막 훅: {e['stinger']}")

        if self.cast:
            out.append("")
            out.append("[등장한 인물 — 이름과 설정을 바꾸지 마세요]")
            for c in self.cast:
                note = f" — {c['note']}" if c["note"] else ""
                # first_episode 0 = 스토리 단계(P1)에서 확정된 조연.
                # 아직 화면에 안 나왔을 뿐이지, 이름은 이미 정해진 사람이다.
                when = ("스토리 단계에서 확정" if not c["first_episode"]
                        else f"{c['first_episode']}화 등장")
                out.append(f"  {c['name']} ({when}){note}")
                # 인물 카드는 한 번 정해지면 끝까지 같아야 한다. 다시 적게 하지
                # 말고 이미 정해진 것을 그대로 보여 준다 — 3화에서 머리색이나
                # 옷이 바뀌면 독자에게는 다른 사람이다.
                for key in CAST_FIELDS:
                    value = str(c.get(key) or "").strip()
                    if value:
                        out.append(f"      {CAST_FIELD_LABEL[key]}(고정): {value}")
                voice = str(c.get("voice_notes") or "").strip()
                if voice:
                    out.append(f"      말투(고정): {voice}")

        if self.status:
            out.append("")
            out.append("[지금 인물에게 남아 있는 것 — 없던 일로 만들지 마세요]")
            out.append("  앞 화가 남긴 자국입니다. 이번 화에서도 그대로 있고, "
                       "그림에도 그려집니다.")
            for s in self.status:
                until = f", {s['until']}" if s["until"] and s["until"] != "계속" else ""
                out.append(f"  - {s['who']}: {s['state']} "
                           f"({s['since_episode']}화부터{until})")
            out.append("  나은 것이 있으면 그 사실을 이번 화 안에서 보여 주세요 — "
                       "말없이 사라지면 독자에게는 앞 화가 취소된 것입니다.")

        # ---- 사람 사이와 사람 속 — 이 화가 딛고 서는 것 -------------------
        # 제약 목록이 아니라 **집필 재료**다. "1화에서 마주쳤지만 대화가 없었다"
        # 가 눈앞에 있으면 다음 수(인사·경계·"당신 누구야")가 거기서 나온다.
        # 그리고 앞 화를 본 독자가 "아 맞아 얘네 이랬지" 하게 되는 회수의 재료도
        # 이것이다.
        if self.relations or self.cast:
            out.append("")
            out.append("[인물 사이 — 지금까지. 이 화는 여기서 이어집니다]")
            if self.relations:
                by_pair: dict = {}
                for r in self.relations:
                    by_pair.setdefault((r["who"], r["about"]), []).append(r)
                for (who, about), rows in by_pair.items():
                    out.append(f"  {who} → {about}:")
                    for r in rows:
                        out.append(f"      {r['since_episode']}화: {r['state']}")
                out.append("")
            out.append("  ★ **여기 없는 관계는 아직 없는 관계입니다.** 두 인물이 "
                       "서로 이름을 부르려면 이름을 알게 되는 장면이 먼저 있어야 "
                       "합니다 — 그 장면을 이 화에 쓰거나, 모르는 사람으로 "
                       "대하게 하세요.")
            out.append("  ★ 관계가 반드시 이번 화에 진행될 필요는 없습니다. 두 "
                       "사람이 안 만나면 위 상태가 그대로 유지됩니다.")
            out.append("  ★ 위의 구체적인 순간을 **재료로 쓰세요.** 다시 만나는 "
                       "장면이라면 그때의 일이 지금 행동의 이유가 되게 — 그래야 "
                       "앞 화를 본 독자가 '아 맞아' 하고 알아봅니다.")

        if self.minds:
            out.append("")
            out.append("[각자의 속 — 지금 무엇을 알고, 느끼고, 원하는가]")
            by_who: dict = {}
            for m in self.minds:
                by_who.setdefault(m["who"], []).append(m)
            for who, rows in by_who.items():
                out.append(f"  {who}:")
                for m in rows:
                    out.append(f"      {m['since_episode']}화: {m['state']}")
            out.append("  ★ 인물은 **여기 적힌 것만** 압니다. 여기 없는 정보를 "
                       "아는 것처럼 말하거나 행동하게 하지 마세요 — 독자는 그 "
                       "인물이 어떻게 알았는지 본 적이 없습니다.")

        if self.places:
            out.append("")
            out.append("[이미 나온 장소 — 다시 써도 됩니다]")
            for p in self.places:
                out.append(f"  - {p['place']} ({p['first_episode']}화)")
            out.append("  매 화 새 장소를 만들 필요는 없습니다. 같은 공간에 두 번째로 "
                       "가면 독자는 그곳을 기억하게 되고, 그때 달라진 것이 보입니다.")

        if self.zones:
            out.append("")
            out.append("[이미 나온 존 — 배경 자산이 있는 자리]")
            for z in self.zones:
                out.append(f"  - {z['zone_id']} ({z['place']}) — {z['label']}")
            out.append("  같은 구역은 같은 zone_id 를 그대로 쓰세요. 배경이 이 id 로 "
                       "재사용됩니다 — 매번 새 id 를 지으면 배경도 매번 새로 그려집니다.")

        if self.facts:
            out.append("")
            out.append("[확정된 설정 — 이미 독자가 본 것이라 뒤집을 수 없습니다]")
            for f in self.facts:
                out.append(f"  - {f['fact']} ({f['first_episode']}화)")
            # 쌓기만 하고 서로 부딪히는지 안 보면, 3화에서 "창이 없다"고 정해 놓고
            # 7화에서 창밖을 그리는 일이 그대로 통과한다.
            clashes = fact_conflicts(self.facts)
            if clashes:
                out.append("")
                out.append("  ★ 아래 설정끼리 어긋나 보입니다. 이번 화를 쓰기 전에 "
                           "어느 쪽이 맞는지 정하고, 맞는 쪽만 지키세요:")
                for c in clashes:
                    out.append("  " + c.replace("\n", "\n  "))

        open_qs = ledger.open_items
        out.append("")
        if open_qs:
            out.append(f"[열려 있는 질문 {len(open_qs)}개 — 닫을 것이 있으면 "
                       f"문장을 그대로 옮겨 적으세요]")
            for q in open_qs:
                # 몇 화째 열려 있는지를 같이 준다. 목록만 보면 다섯 개가 다
                # 똑같이 급해 보이는데, 실제로 급한 것은 오래 묵은 쪽이다.
                age = self.made - q.opened_episode
                aged = f", {age}화째" if age >= 1 else ""
                mark = "  ← 오래 묵음" if age >= STALE_AFTER else ""
                out.append(f'  - "{q.text}" ({q.opened_episode}화에 열림, '
                           f'{q.type}{aged}){mark}')
            if len(open_qs) > ledger.cap:
                out.append("")
                out.append(f"  ★ 열린 질문이 {len(open_qs)}개로 많습니다(권장 "
                           f"{ledger.cap}개). 이번 화는 새로 여는 것보다 "
                           "닫는 쪽에 무게를 두세요.")
        else:
            out.append("[열려 있는 질문 없음] 이번 화는 새로 열기만 해도 됩니다.")
        return "\n".join(out)

    def status_of(self, name: str) -> list:
        """그 사람에게 지금 남아 있는 자국. 카드와 컷 지시가 이걸 본다."""
        return [s for s in self.status if s["who"] == name]

    def as_dict(self) -> dict:
        return {"run_id": self.run_id, "episodes_made": self.made,
                "episodes": self.episodes, "cast": self.cast,
                "facts": self.facts, "places": self.places,
                "status": self.status, "zones": self.zones,
                "relations": self.relations, "minds": self.minds}

    def save(self, path: Path) -> None:
        write_json(path, self.as_dict())

    @classmethod
    def load(cls, path: Path, run_id: str = "") -> "SeriesState":
        if not path.exists():
            return cls(run_id=run_id)
        d = json.loads(path.read_text(encoding="utf-8"))
        return cls(run_id=d.get("run_id") or run_id,
                   episodes=d.get("episodes") or [],
                   cast=d.get("cast") or [],
                   facts=d.get("facts") or [],
                   places=d.get("places") or [],
                   status=d.get("status") or [],
                   zones=d.get("zones") or [],
                   relations=d.get("relations") or [],
                   minds=d.get("minds") or [])


def series_arc_block(arcs: list, current: dict = None) -> str:
    """작품 전체 줄거리 — 지금 Arc 하나가 아니라 **어디로 가는지**를 보여준다.

    예전에는 그 화가 속한 Arc 하나만 넘겼다. 그러면 2화를 쓰는 모델은 이 작품이
    앞으로 어디로 가는지 모른 채 눈앞의 Arc 만 보고 쓴다 — 방향이 없으니 매 화가
    제자리를 돌거나, 이번 Arc 에서 다 터뜨리려 든다.

    그렇다고 전 Arc 를 통째로 주면 반대쪽으로 고장 난다. 17화를 한꺼번에 설계하던
    시절에 **17화치 떡밥을 1화에서 미리 스케줄링**하다 부채가 쌓여 마지막 Arc 가
    무너졌다. 그래서 여기서는 **한 줄씩만** 준다 — 지도는 보이되 일정표는 못 짠다.
    지금 지나는 Arc 의 전문은 arc_json 으로 따로 간다.
    """
    if not arcs:
        return "(아직 큰 줄거리가 없습니다)"
    now = (current or {}).get("order")
    # 전 Arc 를 관통하는 인물 = 주인공. 그 사람의 change 를 Arc 순서대로 이어
    # 보여주면 W5 가 **변화 곡선** 위에서 이번 화를 쓴다. 사건 줄거리만 주면
    # 인물이 매 화 같은 자리로 돌아온다.
    #
    # 여기서도 한 줄씩만이다 — 줄거리 한 줄, 인물 한 줄. 전문은 arc_json 으로 간다.
    through = _through_line(arcs)
    lines = []
    for a in arcs:
        if not isinstance(a, dict):
            continue
        order = a.get("order")
        mark = " ←  지금 여기" if order == now else ""
        title = _fmt(a.get("title"))
        kind = _fmt(a.get("arc_type"))
        n = a.get("estimated_episode_count")
        span = f"{n}화" if isinstance(n, int) and n > 0 else "?화"
        lines.append(f"  Arc {order}. {title} ({kind} · {span}){mark}")
        one = a.get("summary") or a.get("one_line")
        if not is_blank(one):
            lines.append(f"      {str(one).strip().splitlines()[0][:90]}")
        if through:
            change = _arc_change(a, through)
            if change:
                lines.append(f"      {through}: {change[:80]}")
    return "\n".join(lines)


def _through_line(arcs: list) -> str:
    """전 Arc 의 cast_roles 에 모두 나오는 인물. 없으면 빈 문자열.

    cast_roles 가 없는 옛 run 은 언제나 빈 문자열이라 예전 출력이 그대로 나온다.
    """
    sets = []
    for a in arcs:
        if not isinstance(a, dict):
            continue
        rows = a.get("cast_roles")
        if not isinstance(rows, list) or not rows:
            return ""
        sets.append({str(r.get("name") or "").strip()
                     for r in rows if isinstance(r, dict) and r.get("name")})
    if not sets:
        return ""
    both = set.intersection(*sets)
    return sorted(both)[0] if both else ""


def _arc_change(arc: dict, name: str) -> str:
    for r in (arc.get("cast_roles") or []):
        if isinstance(r, dict) and str(r.get("name") or "").strip() == name:
            return " ".join(str(r.get("change") or "").split())
    return ""


def arc_for_episode(arcs: list, no: int) -> dict:
    """통산 화 번호 -> 그 화가 속한 Arc.

    Arc 는 큰 줄거리일 뿐이라 화 수는 대략이다. 계획된 화 수를 순서대로 채우고,
    계획을 넘기면 마지막 Arc 에 머문다 — 연재가 계획보다 길어지는 것은 정상이다.
    """
    running = 0
    for arc in arcs:
        n = arc.get("estimated_episode_count")
        n = n if isinstance(n, int) and n > 0 else 3
        running += n
        if no <= running:
            return arc
    return arcs[-1] if arcs else {}


# ------------------------------------------------- 본문 -> id (5단계 정합성)
#
# 5단계는 온도 0.9 생성 단계다. 여기서 id 정합성을 자연어 지시로 맞추려는 시도는
# 반복해서 실패했다 — 엔진급 질문을 상환 대상으로 지목하고, 출력 예시의 id 를 그대로
# 베끼고, 닫을 게 없으면 빈 문자열을 넣고, 같은 화 안에서 연 temp_id 를 닫으려다
# 여는 쪽과 닫는 쪽 id 가 어긋났다. 프롬프트를 다섯 번 고쳐도 같은 종류가 재발했다.
#
# 그래서 5단계 산출물에는 id 를 아예 두지 않는다. 모델은 질문 **본문**만 쓰고,
# id·화 번호·연결은 전부 아래 코드가 부여한다. 모델이 지목한 본문이 장부와 맞지
# 않으면 그 상환만 조용히 버린다 — 재생성 사유로 삼지 않는다.

_TEXT_NOISE = re.compile(r"[\s\W_]+", re.UNICODE)
_ID_TOKEN = re.compile(r"(Q-\d+|EQ|T\d+-\d+)")

# 질문 본문 매칭 임계값. 낮추면 엉뚱한 질문을 닫고, 높이면 정당한 상환을 놓친다.
# 프롬프트가 "본문을 그대로 옮겨 적으라"고 지시하므로 정상 경로는 1.0 근처에서 맞는다.
QUESTION_MATCH_THRESHOLD = 0.6


def _norm_q(text) -> str:
    """공백·문장부호를 지운 비교용 문자열. 한글은 \\w 에 포함되므로 그대로 남는다."""
    return _TEXT_NOISE.sub("", str(text or "")).lower()


def _similarity(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    short, long_ = (a, b) if len(a) <= len(b) else (b, a)
    if len(short) >= 8 and short in long_:
        return 0.9          # 축약해서 옮겨 적은 경우
    return difflib.SequenceMatcher(None, a, b).ratio()


def match_question(text, candidates, threshold: float = QUESTION_MATCH_THRESHOLD):
    """본문으로 질문을 찾는다. candidates 는 [(key, 질문본문), ...].

    확실하지 않으면 None 을 돌려준다. 엉뚱한 질문을 닫는 것보다 무시하는 편이 낫다.
    """
    raw = str(text or "").strip()
    if not raw or not candidates:
        return None

    keys = {k for k, _ in candidates}
    for token in _ID_TOKEN.findall(raw):        # 본문 대신 id 를 적은 경우도 받아준다
        if token in keys:
            return token

    target = _norm_q(raw)
    if not target:
        return None
    best, best_score = None, 0.0
    for key, cand in candidates:
        score = _similarity(target, _norm_q(cand))
        if score > best_score:
            best, best_score = key, score
    return best if best_score >= threshold else None


@dataclass
class Resolution:
    """코드가 5단계 산출물에 무엇을 부여하고 무엇을 버렸는지 남긴다."""
    closed: int = 0
    ignored: list = field(default_factory=list)
    fallback_stingers: list = field(default_factory=list)


def assign_ids(payload: dict, ledger: Ledger) -> Resolution:
    """모델이 쓴 본문에 코드가 id 를 붙인다. payload 를 제자리에서 고친다.

      - order          : episodes 배열 순서대로 1부터. 모델은 쓰지 않는다.
      - temp_id        : questions_opened 에 코드가 순서대로 부여한다.
      - ledger_id      : questions_closed[].question_text 를 장부의 열린 질문과
                         맞춰 붙인다. 못 맞추면 그 상환은 버린다.
      - linked_question_id : stinger.linked_question_text 를 같은 방식으로 맞춘다.
                         못 맞추면 이 화가 연 첫 질문으로 떨어뜨린다 — 실패시키지 않는다.

    같은 Arc 의 앞선 화가 연 질문도 상환 대상이 된다. 장부 id 는 6단계 검수 통과 후에
    발급되므로 그 질문들은 아직 임시 id 만 갖는다. 실제 장부 id 로 바꾸는 것은
    commit_ledger 가 맡는다.
    """
    res = Resolution()
    eps = [e for e in (payload.get("episodes") or []) if isinstance(e, dict)]
    payload["episodes"] = eps

    ledger_pool = [(q.id, q.text) for q in ledger.open_items]
    earlier_opens = []          # 이번 Arc 의 앞선 화가 연 질문 [(temp_id, 본문)]
    claimed = set()             # 한 질문을 두 화가 겹쳐 닫지 않게 한다

    for index, e in enumerate(eps, 1):
        e["order"] = index                      # 배열 순서가 곧 이 Arc 안의 화 번호다
        e.pop("temp_id", None)

        # -- 여는 질문: id 는 코드가 붙인다
        opened = []
        for q in (e.get("questions_opened") or []):
            if isinstance(q, str):
                q = {"text": q}
            if not isinstance(q, dict) or is_blank(q.get("text")):
                res.ignored.append(f"{index}번째 화: 본문이 없는 여는 질문 항목을 버렸습니다.")
                continue
            q["temp_id"] = f"T{index}-{len(opened) + 1}"
            opened.append(q)
        e["questions_opened"] = opened

        # -- 상환: 본문으로 장부와 맞춘다. 같은 화가 연 질문은 후보에서 뺀다.
        pool = [(k, t) for k, t in (ledger_pool + earlier_opens) if k not in claimed]
        closed = []
        for c in (e.get("questions_closed") or []):
            if isinstance(c, str):
                c = {"question_text": c}
            if not isinstance(c, dict):
                continue
            text = c.get("question_text") or c.get("text") or c.get("ledger_id") or ""
            key = match_question(text, pool)
            if key is None:
                shown = str(text).strip()[:40] or "(빈 값)"
                if str(text).strip().upper() == "EQ" or match_question(
                        text, [("EQ", ledger.engine.text)]):
                    why = "엔진급 질문은 어느 화에서도 닫지 않습니다"
                elif not str(text).strip():
                    why = "닫을 질문의 본문이 비어 있습니다"
                else:
                    why = "장부의 열린 질문과 맞는 것이 없습니다"
                res.ignored.append(f"{index}번째 화: 상환 '{shown}' 무시 — {why}.")
                continue
            c.pop("question_text", None)
            c["ledger_id"] = key
            c["closed_question_text"] = dict(pool)[key]
            claimed.add(key)
            closed.append(c)
        e["questions_closed"] = closed
        res.closed += len(closed)

        # -- 스팅어: 연결 대상도 본문으로 지목받는다. 실패해도 되돌리지 않는다.
        stinger = e.get("stinger")
        if not isinstance(stinger, dict):
            stinger = {"text": str(stinger or "")}
        link_pool = ([(q["temp_id"], q.get("text")) for q in opened]
                     + ledger_pool + earlier_opens
                     + [("EQ", ledger.engine.text)])
        raw = (stinger.get("linked_question_text")
               or stinger.get("linked_question_id") or "")
        key = match_question(raw, link_pool)
        if key is None:
            key = (opened[0]["temp_id"] if opened
                   else (closed[0]["ledger_id"] if closed else "EQ"))
            res.fallback_stingers.append(f"{index}번째 화")
        stinger.pop("linked_question_text", None)
        stinger["linked_question_id"] = key
        e["stinger"] = stinger

        earlier_opens.extend((q["temp_id"], q.get("text")) for q in opened)

    return res


# ---------------------------------------------------------------- 게이트

def gate_arcs(payload: dict) -> list:
    """4단계 게이트: 요소 사용 · 반전 비율 · 순번 · 화 수."""
    failures = []
    arcs = payload.get("arcs")
    if not isinstance(arcs, list) or not arcs:
        return ["arcs 가 배열이 아니거나 비어 있습니다."]

    if not 4 <= len(arcs) <= 6:
        failures.append(f"Arc 가 {len(arcs)}개입니다. 4~6개여야 합니다.")

    orders = [a.get("order") for a in arcs if isinstance(a, dict)]
    if orders != list(range(1, len(arcs) + 1)):
        failures.append(f"order 가 1부터 연속이 아닙니다: {orders}")

    used = set()
    reversal = 0
    for a in arcs:
        if not isinstance(a, dict):
            failures.append("arcs 안에 객체가 아닌 항목이 있습니다.")
            continue
        label = f"Arc {a.get('order')}"

        n = a.get("estimated_episode_count")
        if not isinstance(n, int) or not 2 <= n <= 5:
            failures.append(f"{label}: estimated_episode_count 가 {n} 입니다. 2~5 만 허용됩니다.")

        elems = a.get("premise_element_used")
        elems = elems if isinstance(elems, list) else [elems]
        norm = {normalize_source(e) for e in elems if e}
        norm = {e for e in norm if e in ("rule", "cost", "irony")}
        if not norm:
            failures.append(
                f"{label}: premise_element_used 에 rule/cost/irony 가 없습니다 "
                f"(현재 {elems}).")
        used |= norm

        atype = str(a.get("arc_type") or "").strip()
        if atype not in ARC_TYPES:
            failures.append(f"{label}: arc_type 이 '{atype}' 입니다. {list(ARC_TYPES)} 중 하나여야 합니다.")
        if atype == "반전":
            reversal += 1

    for required in ("rule", "cost", "irony"):
        if required not in used:
            failures.append(
                f"전체 Arc 를 합쳤을 때 '{required}' 가 주 동력으로 한 번도 쓰이지 않았습니다. "
                f"현재 사용: {sorted(used) or '없음'}. 한 요소만 반복하면 나머지는 장식이 됩니다.")

    if arcs and reversal * 3 < len(arcs):
        failures.append(
            f"반전 Arc 가 {reversal}/{len(arcs)} 로 1/3 미만입니다. "
            "전개·상승만 이어지면 설정집 비대증에 빠집니다.")

    failures.extend(gate_arc_cast(arcs))
    failures.extend(gate_arc_pressure(arcs))
    return failures


def gate_arc_cast(arcs: list) -> list:
    """Arc 마다 그 Arc 를 움직이는 인물과 그 인물의 자리가 적혀 있는가.

    Arc 요약은 "무슨 일이 벌어지는가"만 말한다. 누가 그 일을 밀고 누가 막는지가
    없으면 화 단위 설계(W5)가 매번 인물을 새로 배치하고, 그러면 주인공이 Arc
    마다 다른 사람처럼 움직인다.

    **cast_roles 가 한 Arc 에도 없으면 아무것도 검사하지 않는다.** 이 칸이
    생기기 전에 돌린 run 을 다시 돌려도 결과가 그대로여야 한다.
    """
    arcs = [a for a in arcs if isinstance(a, dict)]
    if not any(a.get("cast_roles") for a in arcs):
        return []

    failures = []
    per_arc = []
    for a in arcs:
        label = f"Arc {a.get('order')}"
        rows = a.get("cast_roles")
        if not isinstance(rows, list) or not rows:
            failures.append(f"{label}: cast_roles 가 비어 있습니다. "
                            "그 Arc 를 움직이는 인물을 최소 한 명 적습니다.")
            per_arc.append(set())
            continue
        names = set()
        for r in rows:
            if not isinstance(r, dict):
                failures.append(f"{label}: cast_roles 안에 객체가 아닌 항목이 있습니다.")
                continue
            name = str(r.get("name") or "").strip()
            if not name:
                failures.append(f"{label}: cast_roles 에 이름 없는 항목이 있습니다.")
                continue
            names.add(name)
            for key, why in (("role", "그 인물이 이 Arc 에서 하는 일"),
                             ("change", "이 Arc 를 지나며 그 인물에게서 달라지는 것")):
                if not str(r.get(key) or "").strip():
                    failures.append(f"{label} · {name}: {key} 가 비어 있습니다 ({why}).")
        per_arc.append(names)

    # 모든 Arc 에 걸쳐 있는 인물이 하나도 없으면 연재를 관통하는 사람이 없다.
    # 주인공 이름을 여기서 알 수 없으므로(캐릭터 시트는 이 게이트에 안 온다)
    # "전 Arc 에 나오는 사람이 있는가" 로 대신 본다.
    if per_arc and not set.intersection(*per_arc):
        failures.append(
            "모든 Arc 에 나오는 인물이 없습니다. 주인공은 전 Arc 의 cast_roles 에 "
            "들어가야 합니다 — 주인공이 빠진 Arc 는 주인공의 이야기가 아닙니다.")
    return failures


# Arc 의 압력 세 칸. 이 셋이 다 있어야 "사건이 아니라 압력" 설계가 성립한다.
ARC_PRESSURE_FIELDS = (
    ("starts_with", "이 Arc 가 시작할 때 주인공이 할 수 있다고 믿는 것"),
    ("pressure", "그 믿음을 조이는 힘 (누가 · 무엇으로)"),
    ("ends_with", "이 Arc 끝에서 더는 가능하지 않게 된 것"),
)

# summary 를 몇 문장까지 허용하는가. 넘으면 Arc 가 줄거리 요약으로 돌아간 것이다.
ARC_SUMMARY_MAX_SENTENCES = 2
_SENT_END = re.compile(r"[.!?…]+(?:\s|$)")


def gate_arc_pressure(arcs: list) -> list:
    """Arc 가 **사건**이 아니라 **압력**으로 적혀 있는가.

    W4 가 Arc 요약에 사건을 적어 버리면 W5 는 그것을 받아쓴다. 실제로 그랬다 —
    Arc 1 요약의 "기자회견장에서 얼굴 공개를 거부한다" 가 그대로 1화가 되었고,
    W5 가 고를 수 있었던 다른 1화는 시도조차 되지 않았다. W5 프롬프트에는 이미
    "Arc 는 방향이지 일정표가 아니다" 라고 적혀 있지만, 지시와 데이터가 부딪히면
    데이터가 이긴다 — arc_json 안에 사건이 문장으로 박혀 있으면 그것이 이긴다.

    그래서 Arc 의 몸통을 상태 세 칸(starts_with · pressure · ends_with)으로 옮기고,
    summary 는 그 셋을 줄인 한 문장으로 제한한다.

    **세 칸이 한 Arc 에도 없으면 아무것도 검사하지 않는다.** 이 칸이 생기기 전에
    돌린 run 의 arcs.json 을 다시 읽어도 결과가 그대로여야 한다.
    """
    arcs = [a for a in arcs if isinstance(a, dict)]
    if not any(any(a.get(k) for k, _ in ARC_PRESSURE_FIELDS) for a in arcs):
        return []

    failures = []
    for a in arcs:
        label = f"Arc {a.get('order')}"
        for key, why in ARC_PRESSURE_FIELDS:
            if not str(a.get(key) or "").strip():
                failures.append(f"{label}: {key} 가 비어 있습니다 ({why}).")

        start, end = (" ".join(str(a.get(k) or "").split())
                      for k in ("starts_with", "ends_with"))
        if start and start == end:
            failures.append(
                f"{label}: starts_with 와 ends_with 가 같습니다. "
                "지나가도 아무것도 안 바뀌는 구간은 Arc 가 아닙니다.")

        nots = a.get("not_yet")
        if not isinstance(nots, list) or not [x for x in nots if str(x or "").strip()]:
            failures.append(
                f"{label}: not_yet 이 비어 있습니다. 이 Arc 에서 아직 일어나지 않는 "
                "일을 적어야 W5 가 뒤 Arc 의 사건을 당겨오지 않습니다.")

        summary = " ".join(str(a.get("summary") or "").split())
        n = len([x for x in _SENT_END.split(summary) if x.strip()])
        if n > ARC_SUMMARY_MAX_SENTENCES:
            failures.append(
                f"{label}: summary 가 {n}문장입니다. "
                f"{ARC_SUMMARY_MAX_SENTENCES}문장 이하로 줄입니다 — 사건을 나열하는 "
                "자리가 아니라 압력 세 칸을 한 줄로 줄이는 자리입니다.")
    return failures


# 조연 인물 카드의 항목. 순서가 곧 명부·프롬프트에 적히는 순서다.
#
# appearance 와 outfit 을 나눈 이유: 외형은 끝까지 안 바뀌지만 옷은 장면에 따라
# 바뀔 수 있다. 한 칸에 뭉쳐 두면 그리는 쪽이 둘을 구분하지 못해 옷을 바꾸려다
# 머리색까지 같이 흔든다. 주인공에게서 실제로 그 일이 났다 (appearance_en 이
# 옷을 네 벌 나열해서 컷마다 다른 옷이 나왔다).
CAST_FIELDS = ("gender", "appearance", "outfit", "personality")
CAST_FIELD_LABEL = {"gender": "성별", "appearance": "외형",
                    "outfit": "옷차림", "personality": "성격"}

# ---- 무대 (5단계가 정하고 7단계가 그리는 것) -----------------------------
#
# 이걸 넣는 이유가 컷 단조로움의 **가장 큰 지렛대**다. 실측을 보면 원인이 7단계에만
# 있지 않았다: 엔진 카드에는 인물 외형 4줄·표정 6종·색 6개가 실려 있는데 장소는
# "서울 K대" 한 줄뿐이고, 시간대·소품·배경색은 아예 없다. p1.txt 는 소품을 명시적으로
# 제외하고(캐릭터 시트에는 맞는 설계다), p2·w4·w6 에는 공간 어휘가 한 번도 안 나온다.
#
# 그 상태로 5단계에 "이 화에서 벌어지는 사건. 구체적으로." 만 시키면 요약이
# 소설 시놉시스가 된다. 실제 산출된 1화 요약에는 장소가 잔디광장·복도 둘뿐이고
# 물리적 사건이 없다 — 전부 대화와 표정이다. 심지어 요약 안에 "눈매가 날카로워지고
# 턱이 굳어진다" 같은 표정 묘사가 들어가 있어서 7단계가 그대로 옮겨 적는다.
#
# 그리면 얼굴밖에 그릴 것이 없다. 어휘를 아무리 늘려도 가리킬 대상이 없으면
# 카메라는 얼굴로 돌아온다. 그래서 **회차마다 무대를 먼저 정하게** 한다.
# 무대의 여섯 칸. SETTING_LABEL 이 전체 목록이고, 그중 비면 안 되는 것이
# SETTING_REQUIRED 다 (props 는 개수를 따로 세므로 여기 없다).
SETTING_LABEL = {"place": "장소", "time": "시간대", "weather": "날씨",
                 "light": "광원", "props": "사물", "movement": "동선"}
SETTING_FIELDS = tuple(SETTING_LABEL)
# 그리는 쪽이 지어내면 안 되는 칸. 비면 같은 화 안에서 낮이었다 밤이 되고
# 비가 왔다 갠다 — 한 장씩 따로 그려지기 때문에 이어 볼 방법이 없다.
SETTING_REQUIRED = ("place", "time", "weather", "light", "movement")
MIN_PROPS = 2           # 화면에 실제로 나오는 사물. 인서트 컷이 여기서 나온다


def gate_setting(setting, label: str) -> list:
    """무대(장소·시간대·사물·동선)가 그릴 수 있게 적혀 있는지.

    7단계를 되돌리는 대신 5단계에서 잡는 이유: 컷을 다시 뽑는 것보다 회차를 다시
    쓰는 편이 싸고, 무대가 비어 있으면 컷을 몇 번 다시 뽑아도 그릴 것이 없다.
    """
    failures = []
    if not isinstance(setting, dict):
        return [f"{label}: setting 이 없습니다. 장소·시간대·날씨·광원·사물·동선을 "
                "적으세요 — 이게 없으면 컷 단계가 그릴 것이 인물 얼굴밖에 없습니다."]

    for key in SETTING_REQUIRED:
        if is_blank(setting.get(key)):
            failures.append(
                f"{label}: setting.{key}({SETTING_LABEL[key]}) 가 비어 있습니다. "
                "그리는 쪽에는 이 칸이 전부라, 비우면 컷마다 새로 정합니다.")

    props = setting.get("props")
    props = [str(p).strip() for p in props if str(p or "").strip()] \
        if isinstance(props, list) else []
    if len(props) < MIN_PROPS:
        failures.append(
            f"{label}: setting.props 가 {len(props)}개입니다. {MIN_PROPS}개 이상 "
            "적으세요. 화면에 실제로 보이는 사물이어야 합니다 — 인물 없이 사물만 "
            "나오는 컷이 여기서 나오고, 그런 컷이 없으면 화 전체가 얼굴 나열이 "
            "됩니다.")
    return failures


# 엔진급 질문을 화 질문으로 베낀 것을 잡는 문턱. 프롬프트가 "옮겨 적지 마라" 라고
# 말하므로, 걸리는 것은 거의 그대로 베낀 경우다. 낮추면 정당한 화 질문(엔진과 소재가
# 겹치는 것은 당연하다)까지 잡는다.
ENGINE_ECHO_THRESHOLD = 0.85


def gate_question_echo(eps: list, ledger: Ledger) -> list:
    """이 화가 연 질문이 엔진급 질문을 베낀 것인가.

    엔진급 질문은 시즌 내내 열려 있다. 그것을 화 질문으로 다시 여는 것은 아무것도
    여는 것이 아니다. 실측(1화): questions_opened 의 첫 줄이 engine_question 문장
    거의 그대로였고, 스팅어까지 그 질문에 연결됐다 — 1화를 막 본 독자가 품을 질문이
    아니라 작품 전체의 질문이다.

    **엔진급 질문이 비어 있으면 아무것도 검사하지 않는다.** 그 문장이 없으면
    비교 대상이 없다.
    """
    engine = getattr(getattr(ledger, "engine", None), "text", "")
    base = _norm_q(engine)
    if not base or base.startswith("("):        # "(엔진급 질문 미지정)"
        return []

    failures = []
    for i, e in enumerate(eps, 1):
        if not isinstance(e, dict):
            continue
        for q in e.get("questions_opened") or []:
            text = q.get("text") if isinstance(q, dict) else q
            if _similarity(_norm_q(text), base) < ENGINE_ECHO_THRESHOLD:
                continue
            failures.append(
                f"{i}번째 화: 이 화가 연 질문이 엔진급 질문과 거의 같습니다 "
                f"(\"{str(text)[:40]}…\"). 엔진급 질문은 이미 열려 있으므로 다시 "
                "열 수 없습니다. 이 화에서 방금 벌어진 일에서 나오는 질문을 "
                "적으세요 — 이 화를 안 보고도 물을 수 있는 질문이면 이 화의 "
                "질문이 아닙니다.")
    return failures


# 값이 아닌 것. 위험과 감정은 아직 아무것도 잃지 않은 상태다.
_NOT_A_PRICE = ("위험", "불안", "초조", "긴장", "우려", "걱정", "예감", "느낌")


def gate_price_paid(eps: list) -> list:
    """이 화가 무엇을 치렀다고 적었는가 — 그리고 그게 화면에 보이는가.

    선택이 걸린 화에서 인물이 양쪽을 다 피해 가는 것이 이 단계의 가장 쉬운 길이다.
    값은 다음 화에 치르면 되기 때문이다. 실측: 「드러내면 일상을 잃고 안 들어가면
    시민이 죽는다」는 사건을 받고서, 인물이 마스크를 고쳐 쓰고 출동하는 화가 나왔다.
    아무것도 안 잃었고 화는 출동 버튼을 누르는 데서 끝났다.

    **price_paid 칸이 없으면 아무것도 검사하지 않는다.** 이 칸이 생기기 전 run 과,
    값을 치를 선택이 없는 화(빈 칸이 정답이다)를 둘 다 그대로 통과시킨다.
    """
    failures = []
    for i, e in enumerate(eps, 1):
        if not isinstance(e, dict):
            continue
        paid = e.get("price_paid")
        if not isinstance(paid, dict) or not any(
                str(paid.get(k) or "").strip() for k in ("what", "shown_by", "instead_of")):
            continue                    # 칸이 없거나 통째로 비었으면 보지 않는다

        label = f"{i}번째 화"
        what = " ".join(str(paid.get("what") or "").split())
        if not what:
            failures.append(f"{label}: price_paid.what 이 비어 있습니다. "
                            "이 화에서 실제로 치른 값을 적거나, 치를 것이 없으면 "
                            "price_paid 를 통째로 비우세요.")
        elif any(w in what for w in _NOT_A_PRICE):
            failures.append(
                f"{label}: 치른 값이 \"{what[:30]}…\" 입니다 — 위험과 감정은 값이 "
                "아닙니다. 아직 아무것도 잃지 않았다는 뜻입니다. 이 화에서 실제로 "
                "잃은 것을 적으세요.")
        if what and not str(paid.get("shown_by") or "").strip():
            failures.append(f"{label}: price_paid.shown_by 가 비어 있습니다. "
                            "치른 값이 화면에 안 보이면 독자에게는 안 일어난 일입니다.")
    return failures


def gate_episodes_shape(payload: dict, ledger: Ledger, arc: dict = None,
                        resolution: Resolution = None) -> list:
    """5단계 게이트. **내용**만 본다 — id 정합성은 assign_ids 가 이미 보장했다.

    반드시 assign_ids 를 먼저 돌린 payload 로 부른다. 여기서 다시 id 를 검사하면
    코드가 스스로 채운 값을 코드가 되돌려 세우는 꼴이 된다.

    문서 5장은 '검사 전에는 판정하지 않음'이라고 적고 있으나, engine_fired 빈 배열처럼
    문서가 명시한 규칙 위반은 검사 호출을 낭비하지 않도록 여기서 되돌린다.
    내용 판정은 전부 6단계가 한다.
    """
    failures = []
    eps = payload.get("episodes")
    if not isinstance(eps, list) or not eps:
        return ["episodes 가 배열이 아니거나 비어 있습니다."]

    failures.extend(gate_question_echo(eps, ledger))
    failures.extend(gate_price_paid(eps))

    # 화 수. 재생성 지시를 받으면 모델이 불합격 화만 남기고 나머지를 버리는 일이
    # 실제로 있었다(제출 [2,3]). order 를 배열 순서로 부여하는 이상 그건 조용한
    # 누락이 되므로, Arc 계획과 개수가 어긋나면 여기서 되돌린다.
    planned = arc.get("estimated_episode_count") if isinstance(arc, dict) else None
    if isinstance(planned, int) and len(eps) != planned:
        failures.append(
            f"이 Arc 는 {planned}화로 계획되었는데 {len(eps)}화가 제출되었습니다. "
            f"고칠 화만 보내지 말고, 통과한 화까지 포함해 {planned}화 전부를 "
            "episodes 배열에 순서대로 담아 다시 제출하세요.")

    for i, e in enumerate(eps, 1):
        if not isinstance(e, dict):
            failures.append(f"{i}번째 화: 객체가 아닙니다.")
            continue
        label = f"{i}번째 화"

        if is_blank(e.get("title")) or is_blank(e.get("summary")):
            failures.append(f"{label}: title 또는 summary 가 비어 있습니다.")

        fired = e.get("engine_fired")
        fired = [normalize_source(x) for x in fired] if isinstance(fired, list) else []
        if not [f for f in fired if f in ("rule", "cost", "irony")]:
            failures.append(
                f"{label}: engine_fired 가 비어 있습니다. "
                "엔진이 발동하지 않는 화는 존재 이유가 없습니다.")

        stinger = e.get("stinger") if isinstance(e.get("stinger"), dict) else {}
        if is_blank(stinger.get("text")):
            failures.append(
                f"{label}: stinger.text 가 비어 있습니다. "
                "마지막 컷의 훅이 없으면 다음 화를 누를 이유가 없습니다.")

        failures.extend(gate_setting(e.get("setting"), label))

        # new_cast 의 인물 카드. 프롬프트로 시키기만 하고 검사하지 않으면 모델이
        # 빼먹고, 빼먹은 것은 그림 단계에 가서야 드러난다 — 이미 그려진 뒤다.
        #
        # 조연에게는 캐릭터 시트가 없다. 여기 적힌 것이 그림 단계가 가진 전부이고,
        # 비어 있으면 그리는 쪽이 컷마다 새로 만들어 낸다. 실제로 같은 화 안에서
        # 같은 인물이 남자였다 여자였다 하고 옷이 매번 바뀌었다.
        for c in e.get("new_cast") or []:
            if not isinstance(c, dict):
                continue
            name = str(c.get("name") or "").strip()
            if not name:
                continue
            missing = [CAST_FIELD_LABEL[k] for k in CAST_FIELDS
                       if is_blank(c.get(k))]
            if missing:
                failures.append(
                    f"{label}: new_cast 의 「{name}」 에 {' / '.join(missing)} 가 "
                    f"비어 있습니다. 이 인물은 캐릭터 시트가 없어서 여기 적힌 것이 "
                    f"그림 단계가 가진 전부입니다 — 비워 두면 컷마다 다른 사람으로, "
                    f"성별까지 바뀌어 그려집니다.\n"
                    f"      appearance = 안 바뀌는 것만 (머리·눈·키·체형·특징 하나).\n"
                    f"      outfit     = 늘 입는 옷 **한 벌**. 여러 벌 나열 금지.\n"
                    f"      personality= 표정과 자세로 드러나는 성격·버릇.")

    # 상환은 **Arc 단위**로만 강제한다.
    #   - Arc 진입 시점 장부에 열린 질문이 하나도 없으면(첫 Arc) 강제하지 않는다.
    #     장부 id 는 6단계 검수 통과 후에 발급되므로 첫 Arc 는 구조적으로 상환이 불가능하다.
    #   - 열린 질문이 있는데 Arc 안의 어느 화도 닫지 않았을 때만 되돌린다.
    # 화 단위 강제는 없앴다. 모델이 매 화 억지로 닫으려다 없는 id 를 지어내는 것이
    # 실패의 주된 경로였다.
    open_at_entry = ledger.open_items
    closed_here = (resolution.closed if resolution is not None else
                   sum(len(e.get("questions_closed") or [])
                       for e in eps if isinstance(e, dict)))
    if open_at_entry and not closed_here:
        listed = "\n".join(f'      · "{q.text}"' for q in open_at_entry[:5])
        failures.append(
            f"이 Arc 는 진입 시점에 열린 질문이 {len(open_at_entry)}개인데 어느 화도 "
            "상환하지 않았습니다. Arc 안에서 최소 한 화는 아래 질문 중 하나를 닫아야 "
            "합니다. 닫는 화의 questions_closed[].question_text 에 아래 본문을 "
            "그대로 옮겨 적으세요:\n" + listed)

    failures.extend(gate_why_now(eps))
    return failures


# 화면에 없는 이유를 "있다"고 우기는 답. shown_by 가 이것들로 때워지면
# 독자에게는 아무것도 안 보인다 — 독자는 설정집을 안 읽는다.
_WHY_DODGE = ("설정상", "앞에서 설명", "앞 화에서 설명", "이미 설명", "기존 설정",
              "독자는 안다", "말할 필요", "당연", "자명")


def gate_why_now(eps: list) -> list:
    """인물이 그 행동을 하는 이유가 **이 화의 화면에** 있는가.

    머릿속 설정으로만 성립하는 행동은 독자에게 개연성이 없다. 실제로 그렇게
    나왔다 — 인물이 갑자기 노래를 부르는데 왜 불러야 하는지가 어디에도 없어서
    독자가 납득하지 못했다. 설정집에는 이유가 있었고 화면에는 없었다.

    **why_now 가 한 화에도 없으면 아무것도 검사하지 않는다.** 이 칸이 생기기
    전에 돌린 run 을 다시 돌려도 결과가 그대로여야 한다.
    """
    eps = [e for e in eps if isinstance(e, dict)]
    if not any(e.get("why_now") for e in eps):
        return []

    failures = []
    for e in eps:
        label = f"{e.get('order') or e.get('episode') or '?'}화"
        w = e.get("why_now")
        if not isinstance(w, dict) or not w:
            failures.append(f"{label}: why_now 가 없습니다 — 이 화의 중심 행동과 "
                            "그 이유, 그 이유가 화면에 어떻게 보이는지를 적습니다.")
            continue
        for key, why in (("action", "이 화의 중심 행동"),
                         ("reason", "그 행동을 하는 이유"),
                         ("shown_by", "그 이유가 화면에 보이는 방식")):
            if not str(w.get(key) or "").strip():
                failures.append(f"{label}: why_now.{key} 가 비어 있습니다 ({why}).")
        shown = str(w.get("shown_by") or "").strip()
        if shown and any(d in shown for d in _WHY_DODGE):
            failures.append(
                f"{label}: why_now.shown_by 가 \"{shown[:40]}\" 입니다. "
                "화면 밖을 가리키는 답은 답이 아닙니다 — 이 화의 대사 한 줄, "
                "나레이션 한 줄, 또는 눈에 보이는 사건으로 적으세요.")
    return failures


# beat 문장에서 핵심 명사/동사만 남기는 거친 토큰화 — story.py 의 _stem 과
# 같은 정신이다: 정교한 형태소 분석 없이, 조사·어미만 떼면 충분히 걸린다.
_BEAT_PARTICLE = re.compile(
    r"(을|를|이|가|은|는|에게|한테|에서|으로|로|과|와|도|만|의)$")
# 마지막 토큰(대개 동사)의 활용형 차이를 흡수한다. "구출한다"/"구출했다"/
# "구출할게" 가 다 다른 문자열이라 그대로 대조하면 대사에서 다른 활용형으로
# 같은 행동을 말해도 놓친 것으로 잡힌다 — 어간만 남겨 부분일치로 본다.
_VERB_ENDING = re.compile(
    r"(한다면|했었다|하는데|하고서|한다|했다|할게|할까|하는|해서|하며|"
    r"았다|었다|인다|된다|됐다|는다|ㄴ다)$")


def _beat_tokens(beat: str) -> list:
    words = re.split(r"[\s,.·]+", str(beat or "").strip())
    out = []
    for w in words:
        w = _BEAT_PARTICLE.sub("", w).strip()
        if len(w) >= 2:
            out.append(w)
    return out


def _verb_stem(token: str) -> str:
    stemmed = _VERB_ENDING.sub("", token)
    return stemmed if len(stemmed) >= 2 else token


# 느슨한 판정에서 동사 대신 인정할 명사 개수. 1 로 두면 결과만 그린 컷
# ("생쥐를 안고 있다")이 '생쥐' 하나로 통과해 버려서, 이 게이트가 원래 잡으려던
# 사고를 놓친다. 2 면 "덫" 과 "생쥐" 처럼 그 장면을 이루는 요소가 둘은 나와야 한다.
BEAT_LOOSE_MIN_NOUNS = 2


def gate_beat_coverage(cuts: list, beats: list, loose: bool = None) -> list:
    """beats 에 적힌 핵심 행동이 컷에서 통째로 빠지지 않았는지 본다.

    실제 사고: 생쥐를 구출하는 핵심 장면(그림+말풍선)이 통째로 빠지고 결과
    (생쥐를 안고 있는 모습)만 남아서, 독자가 상황 파악에 시간이 걸렸다.
    W5(에피소드 플랜)의 beats 를 컷의 description·대사와 대조해, 어느 컷에도
    흔적이 없는 행동을 잡는다.

    판정은 두 가지다.

    **엄격(기본)** — beat 의 마지막 토큰(대개 동사)을 어간으로 줄여 그 문자열이
    컷 어딘가에 있는지만 본다. 앞쪽 명사만으로 통과시키면 그 명사가 결과 컷에도
    나오기 때문에 "행동 자체가 빠졌다"를 못 잡는다는 판단이었다.

    **느슨(BEAT_GATE_LOOSE=1)** — 동사가 그대로 안 나와도, 그 beat 의 다른
    토큰이 BEAT_LOOSE_MIN_NOUNS 개 이상 나오면 통과시킨다.

    느슨한 쪽을 연 이유(2026-08-23): 엄격 판정은 **낱말이 다르면 뜻이 같아도
    실패한다.** "발견한다" 는 컷이 "보고 눈이 커진다" 로 그려도 '발견' 이 없어서
    걸리고, "푼다" 는 어간이 한 글자라 원형으로 되돌아가 "풀어 준다" 조차 못
    맞춘다. 실제 실행에서 이 게이트가 재시도를 다 태우고 멈췄고, 그러면 컷이
    한 개도 저장되지 않아 사람이 승인으로도 넘어갈 수 없다 — 생성이 끝까지
    가지 못한다.

    (엄격 판정에는 반대 방향의 허점도 있다. 컷 전체를 한 덩이 문자열로 합쳐
    찾으므로, 다른 컷에 우연히 같은 글자가 있으면 통과한다 — "주의를 준다" 가
    옆 컷의 "풀어 준다" 에 걸리는 식이다. 그래서 엄격 = 정확이 아니다.)

    기본값은 엄격 그대로다. 예전 run 을 다시 돌려도 결과가 안 바뀐다.

    beats 가 없는(과거 run, 또는 이 필드를 아직 안 채우는 옛 ep_plan) 경우는
    빈 배열로 취급해 항상 통과한다 — 과거 데이터를 다시 돌려도 동작이
    바뀌지 않아야 한다.
    """
    if not isinstance(beats, list) or not beats:
        return []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return []
    if loose is None:
        # 호출 시점에 읽는다 — 모듈을 불러오는 시점에는 아직 load_dotenv 가
        # 안 돌았을 수 있어서, 그때 읽으면 .env 의 값을 못 본다.
        loose = env_bool("BEAT_GATE_LOOSE", False)
    haystack_parts = []
    for c in cuts:
        haystack_parts.append(str(c.get("description") or ""))
        for ln in (c.get("lines") if isinstance(c.get("lines"), list) else []):
            if isinstance(ln, dict):
                haystack_parts.append(str(ln.get("text") or ""))
    haystack = " ".join(haystack_parts)

    out = []
    for beat in beats:
        text = str(beat or "").strip()
        if not text:
            continue
        tokens = _beat_tokens(text)
        if not tokens:
            continue
        # 마지막 토큰만 본다 — 한국어 beat 문장은 "목적어를 동사한다" 꼴이라
        # 끝 토큰이 대개 동사(행동 그 자체)다.
        if _verb_stem(tokens[-1]) in haystack:
            continue
        # 동사가 안 보인다. 느슨한 판정이면 그 장면을 이루는 다른 낱말이
        # 충분히 나왔는지로 한 번 더 본다 — 낱말만 다를 뿐 그린 경우를 살린다.
        if loose:
            hits = sum(1 for t in tokens[:-1] if t in haystack)
            if hits >= BEAT_LOOSE_MIN_NOUNS:
                continue
        out.append(
            f"beats 의 '{text}' 이 어느 컷의 description·대사에도 나타나지 "
            "않습니다. 결과만 그리고 그 행동 자체가 통째로 빠진 것일 수 "
            "있습니다 — 이 행동이 실제로 벌어지는 컷을 추가하세요.")
    return out


def gate_cuts(payload: dict, episode: dict, irony_present: bool,
              known_zones: set = None, known: set = None) -> list:
    """7단계 게이트: 내용(엔진 컷·스팅어·독자 우위) + 세로 스크롤 문법(크기·여백·리듬).

    "이 연출이 좋은가" 는 묻지 않는다. 그건 사람이 스크롤을 내려봐야 아는 것이다.
    여기서 보는 것은 세로 스크롤로 성립조차 안 하는 경우들이다:
      - 크기가 다 같은 화 (= 크기 같은 이미지 나열. 웹툰이 아니다)
      - 여백이 전부 같은 화 (연출을 안 한 것이다)
      - 아무것도 뒤집히지 않는 화 (turn 이 없다)
      - 반전 직전에 여백이 없는 화 (충격이 그냥 다음 그림이 된다)
      - 아무 데서나 끊긴 Scene (설명하다 만 자리에서 화면이 넘어간다)
    """
    failures = []
    cuts = payload.get("cuts")
    if not isinstance(cuts, list) or not cuts:
        return ["cuts 가 배열이 아니거나 비어 있습니다."]

    if not 8 <= len(cuts) <= 16:
        failures.append(f"컷이 {len(cuts)}개입니다. 8~16개여야 합니다.")

    numbers = [c.get("cut_number") for c in cuts if isinstance(c, dict)]
    if numbers != list(range(1, len(cuts) + 1)):
        failures.append(f"cut_number 가 1부터 연속이 아닙니다: {numbers}")

    total = len(cuts)
    last_quarter_start = total - max(1, total // 4) + 1

    fired = episode.get("engine_fired")
    fired = [normalize_source(x) for x in fired] if isinstance(fired, list) else []
    fired = [f for f in fired if f in ("rule", "cost", "irony")]

    refs = payload.get("engine_cut_refs")
    refs = refs if isinstance(refs, list) else []
    ref_map = {}
    for r in refs:
        if not isinstance(r, dict):
            continue
        elem = normalize_source(r.get("element"))
        num = r.get("cut_number")
        if not isinstance(num, int) or not 1 <= num <= total:
            failures.append(f"engine_cut_refs 가 존재하지 않는 컷 번호 {num} 을 가리킵니다.")
            continue
        ref_map.setdefault(elem, []).append(num)

    for elem in fired:
        if elem not in ref_map:
            failures.append(
                f"이 화에서 발동한다고 적은 '{elem}' 의 engine_cut_refs 가 없습니다.")

    engine_cuts = [n for nums in ref_map.values() for n in nums]
    if engine_cuts and all(n >= last_quarter_start for n in engine_cuts):
        failures.append(
            f"엔진 발동 컷 {sorted(engine_cuts)} 이 전부 마지막 1/4({last_quarter_start}~{total}) "
            "구간에 몰려 있습니다. 앞 3/4 가 긴장 없는 소모 구간이 됩니다.")

    stinger = payload.get("stinger_cut_number")
    if stinger != total:
        failures.append(
            f"stinger_cut_number 가 {stinger} 입니다. 반드시 마지막 컷({total})이어야 합니다.")

    if irony_present:
        reader_only = [c.get("cut_number") for c in cuts
                       if isinstance(c, dict) and c.get("reader_only") is True]
        if not reader_only:
            failures.append(
                "이 화에 dramatic_irony 질문이 있는데 reader_only 컷이 없습니다. "
                "독자만 알고 인물은 모르는 컷이 그 질문의 물리적 구현입니다.")

    failures.extend(gate_beat_coverage(cuts, episode.get("beats")))
    failures.extend(gate_scenes(payload, len(cuts)))
    failures.extend(gate_layout(cuts))
    # 대사에 화자가 있는가만 막는다. 말의 밀도·비율은 text_warnings 로 내려갔다 —
    # 비율을 강제하면 모델이 이야기가 아니라 숫자에 맞춰 대사를 넣는다.
    failures.extend(gate_dialogue(cuts))
    # 존은 배경으로 그려지기 **전에** 글로 본다 — 한 번 구운 배경은 그 존의
    # 모든 컷이 재사용하므로, 이미지 뒤로 미루면 그 존의 컷이 전부 다시다.
    failures.extend(gate_zone(payload, known_zones, known))
    failures.extend(gate_frame(cuts))
    return failures


def gate_frame(cuts: list) -> list:
    """화면에 누가 있고 글자가 어디 놓이는가 — 값이 갖춰졌는가만 본다.

    셋을 막는다. 전부 **데이터 완결성**이지 연출 취향이 아니다:

      1. composition / bubble_zone 이 아는 값인가.
      2. 속마음이 있는 컷의 speaker 가 characters_in_frame 에 있는가 —
         화면에 없는 사람의 머릿속을 말풍선으로 띄울 수는 없다. 그 말은
         나레이션으로 가야 한다.
      3. 글자가 있는데 bubble_zone 이 none 인가 — 자리를 안 비우면 말풍선이
         얼굴을 덮거나 잘린다.

    "characters_in_frame 이 서술과 맞는가" 는 보지 않는다. 그건 자유 서술을
    코드가 판정하는 것이라 신뢰도가 낮다 (gate_dialogue 와 같은 이유).
    """
    failures = []
    for c in cuts:
        n = c.get("cut_number")
        comp = str(c.get("composition") or "").strip().lower()
        zone = str(c.get("bubble_zone") or "").strip().lower()
        if comp not in COMPOSITIONS:
            failures.append(
                f"컷 {n} 의 composition '{c.get('composition')}' 를 모릅니다. "
                f"{' | '.join(COMPOSITIONS)} 중 하나여야 합니다.")
        if zone not in BUBBLE_ZONES:
            failures.append(
                f"컷 {n} 의 bubble_zone '{c.get('bubble_zone')}' 를 모릅니다. "
                f"{' | '.join(BUBBLE_ZONES)} 중 하나여야 합니다.")

        # 말하는 사람이 있으면 그가 화면 어디에 있는지도 있어야 한다 —
        # 없으면 꼬리를 붙일 데를 코드가 짐작하게 되고, 짐작은 틀린다.
        # 말이 여러 줄이면 **줄마다** 자리가 있어야 한다. 한 컷에서 둘이 주고받는데
        # 자리가 하나뿐이면 두 풍선의 꼬리가 같은 사람을 가리킨다.
        rows = [r for r in speech_lines(c) if r["kind"] in ("dialogue", "thought")]
        legacy_side = str(c.get("speaker_side") or "").strip().lower()
        bad_side = [r for r in rows
                    if (r["side"] or legacy_side) not in SPEAKER_SIDES]
        if bad_side:
            shown = bad_side[0]["side"] or c.get("speaker_side")
            failures.append(
                f"컷 {n} 에 말이 있는데 자리(side)가 '{shown}' 입니다. "
                f"{' | '.join(SPEAKER_SIDES)} 중 하나여야 합니다 — 말풍선 꼬리를 "
                "어느 쪽에 붙일지가 이 값으로 정해집니다"
                + (f" (말 {len(rows)}줄 중 {len(bad_side)}줄)." if len(rows) > 1 else "."))

        who = c.get("characters_in_frame")
        if not isinstance(who, list):
            failures.append(
                f"컷 {n} 의 characters_in_frame 이 배열이 아닙니다. 화면에 "
                "보이는 인물 이름을 배열로 적으세요 (인물이 없으면 빈 배열).")
            continue
        here = {str(x or "").strip() for x in who}

        legacy_speaker = str(c.get("speaker") or "").strip()
        for row in speech_lines(c):
            if row["kind"] != "thought":
                continue
            speaker = row["speaker"] or legacy_speaker
            if not speaker:
                failures.append(
                    f"컷 {n} 에 속마음이 있는데 speaker 가 비어 있습니다. "
                    "누구의 머릿속인지 없으면 말풍선을 누구에게도 붙일 수 없습니다.")
            elif speaker not in here:
                failures.append(
                    f"컷 {n} 의 속마음은 「{speaker}」 의 것인데 그 사람이 "
                    f"characters_in_frame({sorted(here) or '비어 있음'}) 에 "
                    "없습니다. 화면에 없는 사람의 속마음은 나레이션으로 옮기세요.")

        if zone == "none" and speech_lines(c):
            failures.append(
                f"컷 {n} 에 말풍선·나레이션 글자가 있는데 bubble_zone 이 none "
                "입니다. 그 자리를 비워 두지 않으면 말풍선이 얼굴을 덮거나 "
                "글자가 화면 밖으로 잘립니다.")
    return failures


def longest_run(values: list) -> tuple:
    """가장 긴 연속 구간 → (값, 길이, 시작 index). 빈 목록이면 ("", 0, 0)."""
    if not values:
        return "", 0, 0
    best = (values[0], 1, 0)
    run, start = 1, 0
    for i in range(1, len(values)):
        if values[i] == values[i - 1]:
            run += 1
        else:
            run, start = 1, i
        if run > best[1]:
            best = (values[i], run, start)
    return best


def gate_scenes(payload: dict, total: int) -> list:
    """이 화가 **무슨 장면들로 되어 있는가** — 컷보다 먼저 정해야 하는 것.

    카메라를 아무리 갈라도 "이 장면이 무슨 장면이고 어떤 공기여야 하는가"가 없으면
    컷은 그냥 예쁜 그림의 나열이 된다. 예전 작업 순서는 컷 수부터 정하고 beat 수열로
    넘어가서, 장면의 의도를 한 번도 묻지 않았다.

    그래서 모델이 장면을 먼저 나누고 what·mood 를 쓴다. 경계(scene_break)도 여기서
    나온다 — 어디서 끊을지는 산수가 아니라 "여기까지가 한 장면"이라는 판단이다.
    """
    failures = []
    scenes = payload.get("scenes")
    if not isinstance(scenes, list) or not scenes:
        return ["scenes 가 없습니다. 컷을 쓰기 전에 이 화를 장면 "
                f"{SCENE_COUNT_MIN}~{SCENE_COUNT_MAX}개로 나누고, 장면마다 무슨 "
                "장면인지(what)와 어떤 분위기인지(mood)를 적으세요."]

    if not SCENE_COUNT_MIN <= len(scenes) <= SCENE_COUNT_MAX:
        failures.append(
            f"장면이 {len(scenes)}개입니다. {SCENE_COUNT_MIN}~{SCENE_COUNT_MAX}개여야 "
            "합니다 — 하나뿐이면 화가 한자리에 서 있는 것이고, 너무 많으면 어디에도 "
            "머물지 못합니다.")

    prev = 0
    for i, sc in enumerate(scenes, 1):
        if not isinstance(sc, dict):
            failures.append(f"{i}번째 장면: 객체가 아닙니다.")
            continue
        if is_blank(sc.get("what")):
            failures.append(
                f"{i}번째 장면: what 이 비어 있습니다. 이 장면이 무슨 장면인지 "
                "한 줄로 쓰세요 (누가 어디서 무엇을 하는 장면인가).")
        if is_blank(sc.get("mood")):
            failures.append(
                f"{i}번째 장면: mood 가 비어 있습니다. 이 장면이 어떤 공기여야 하는지 "
                "쓰세요 — 컷의 빛·거리·여백이 전부 여기서 나옵니다.")
        tone = str(sc.get("tone") or "").strip()
        if tone not in TONES:
            failures.append(
                f"{i}번째 장면: tone 이 '{sc.get('tone')}' 입니다. "
                f"{' | '.join(TONES)} 중 하나여야 합니다 — 이 값이 그 장면 컷들의 "
                "그림체(render_style)를 좌우합니다.")
        last = sc.get("last_cut")
        if not isinstance(last, int) or isinstance(last, bool):
            failures.append(f"{i}번째 장면: last_cut 이 숫자가 아닙니다 ({last}).")
            continue
        if last <= prev:
            failures.append(
                f"{i}번째 장면: last_cut 이 {last} 인데 앞 장면이 {prev} 에서 "
                "끝났습니다. 장면은 컷 번호 순서대로 이어져야 합니다.")
            continue
        span = last - prev
        # 마지막 장면은 하한을 보지 않는다 — 스팅어(마지막 컷)가 그 자체로
        # 한 장면일 수 있다. render_gate(3088줄 부근)도 같은 예외를 둔다;
        # 두 게이트가 다른 기준이면 모델은 여기를 통과해도 저기서 막힌다.
        if span > SCENE_MAX or (span < SCENE_MIN and i != len(scenes)):
            failures.append(
                f"{i}번째 장면이 {span}컷입니다 ({SCENE_MIN}~{SCENE_MAX}컷). "
                "한 화면에 들어가는 분량이 그 정도입니다.")
        prev = last

    if prev and prev != total:
        failures.append(
            f"마지막 장면이 컷 {prev} 에서 끝나는데 이 화는 컷이 {total}개입니다. "
            "마지막 장면의 last_cut 은 마지막 컷 번호여야 합니다.")
    return failures


def has_speech(cut: dict) -> bool:
    """이 컷에 말이 있는가. sfx 는 말이 아니다.

    lines 를 거쳐 본다 — 옛 칸만 보면 새 형식으로만 적힌 컷이 침묵으로 세어져,
    말이 가득한 화가 "무성영화" 경고를 받는다.
    """
    return bool(speech_lines(cut))


def render_warnings(cuts: list) -> list:
    """그림체·칸 쓰기가 잦은가 — **세기만 하고 막지 않는다.**

    상한을 게이트로 두었다가 뺐다. sd 다섯 개, 통컷 한 개 같은 숫자는 이야기가
    정할 일이지 코드가 정할 일이 아니다. 개그가 계속되는 화는 sd 가 여덟이어도
    맞고, 전투 한 판을 통째로 보여 주는 화는 통컷이 둘이어도 맞다.

    다만 남발은 대개 실수라서, 사람이 한 번 볼 수 있게 찍어는 둔다.
    """
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return out
    out.extend(narration_voice_warnings(cuts))
    out.extend(layout_warnings(cuts))
    got = [str(c.get("render_style") or "").strip().lower() for c in cuts]
    for style, many, why in (
            ("sd", SD_MAX, "데포르메가 잦으면 산만해지고, 정식 작화로 돌아왔을 때의 "
                           "무게가 사라집니다"),
            ("bleed", BLEED_MAX, "통컷이 여러 번 나오면 두 번째부터는 그냥 큰 "
                                 "그림입니다"),
            ("breakout", BREAKOUT_MAX, "매 컷이 칸을 넘으면 칸이 없는 것과 "
                                       "같습니다"),
            ("float", FLOAT_MAX, "배경 없는 컷이 이어지면 이야기가 어디서 "
                                 "벌어지는지가 사라집니다")):
        spots = [c.get("cut_number") for c, g in zip(cuts, got) if g == style]
        if len(spots) > many:
            out.append(
                f"render_style 이 {style} 인 컷이 {spots} 로 {len(spots)}개입니다 "
                f"(보통 {many}개까지). 장면이 부른 것이면 그대로 두세요 — {why}.")

    # 정식 작화가 길게 이어지는 구간. 상한이 아니라 **연속**을 본다 — 화 전체의
    # sd 개수는 이야기가 정할 일이지만, 정식 작화만 여섯 컷이 이어지면 그 구간은
    # 눈이 쉴 자리가 없다. 여백·SD·집중선 중 무엇이든 하나 있어야 리듬이 산다.
    value, length, at = longest_run(got)
    if value == "normal" and length > MAX_NORMAL_RUN:
        out.append(
            f"컷 {cuts[at].get('cut_number')}~"
            f"{cuts[at + length - 1].get('cut_number')} 이 정식 작화(normal)로 "
            f"{length}컷 연속입니다 (보통 {MAX_NORMAL_RUN}컷까지). 그 구간에 눈이 "
            "쉴 자리가 없습니다 — 장면 tone 이 개그·일상이면 sd 를, 한 방이 "
            "필요하면 emphasis 를 한 컷 놓을 자리가 있는지 보세요.")
    return out


def zone_warnings(cuts: list) -> list:
    """존이 바뀐 자리와 전환이 어긋나는가 — **세기만 하고 막지 않는다.**

    transition 은 컷 사이의 관계다(순간·동작·인물·장면·분위기). 앞 둘은 "같은
    자리에서 조금 움직였다" 는 뜻이라, 그 사이에 zone 이 바뀌면 서로 모순이다 —
    한 컷 만에 소파에서 자판기로 순간이동한 것이 된다. 실제로 그 자리에서
    인물이 말없이 사라지고 배경만 그대로인 화면이 나왔다.

    막지 않는 이유: 예외가 있다. 카메라가 같은 순간을 다른 구역에서 잡는 컷
    (맞은편 벤치에서 이쪽을 보는 컷)은 zone 이 바뀌고도 "순간"이 맞다.
    반려하면 그런 연출이 막히므로, 사람이 보고 판단할 몫으로 남긴다.
    """
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    for prev, cur in zip(cuts, cuts[1:]):
        a = str(prev.get("zone") or "").strip()
        b = str(cur.get("zone") or "").strip()
        tran = str(cur.get("transition") or "").strip()
        if not a or not b or a == b or tran not in SAME_PLACE_TRANSITIONS:
            continue
        out.append(
            f"컷 {cur.get('cut_number')} 에서 존이 {a} -> {b} 로 바뀌는데 "
            f"transition 이 '{tran}' 입니다. 그 값은 같은 자리에서 조금 움직였다는 "
            "뜻이라 서로 어긋납니다 — 자리를 옮겼으면 '장면', 카메라만 다른 "
            "구역을 잡은 것이면 그대로 두세요.")
    return out


# ---- 설정끼리 어긋나는가 --------------------------------------------------
#
# 확정된 설정(new_facts)은 "다음 화가 지켜야 할 것"으로 쌓인다. 그런데 쌓기만
# 하고 **서로 부딪히는지는 아무도 안 봤다.** 3화에서 "그 방에는 창이 없다"고
# 정해 놓고 7화에서 "창밖으로 비가 보였다"고 쓰면, 둘 다 목록에 나란히 남는다.
#
# 의미 충돌을 기계가 다 잡을 수는 없다. 그래서 **오탐이 적은 두 패턴만** 본다:
#   1. 같은 것을 말하는데 한쪽에만 부정어가 있다 ("창이 있다" / "창이 없다")
#   2. 같은 단위의 숫자가 다르다 ("3층" / "5층")
# 나머지는 사람이 본다. 못 잡는 것이 많아도, 잡는 것이 진짜이면 쓸모가 있다.

_NEG_MARKS = ("없다", "없었", "없는", "아니다", "아니었", "아닌", "않다", "않았",
              "않는", "못한", "못했", " 안 ", "못 ")
_NUM_UNIT = re.compile(r"(\d+)\s*(층|명|화|년|살|개|번|시|분|주|달|권|회)")
# 조사·흔한 낱말은 겹쳐도 같은 주제라는 신호가 아니다.
_STOP = {"그", "그녀", "그것", "이", "가", "은", "는", "을", "를", "의", "에", "에서",
         "으로", "로", "와", "과", "도", "만", "하다", "한다", "있다", "없다", "된다",
         "것", "수", "때", "더", "안", "못", "이다", "였다", "했다"}


def _content_words(text: str) -> set:
    """조사·흔한 낱말을 뺀 알맹이. 사람에게 보여 줄 주제 낱말을 고르는 데 쓴다."""
    words = re.findall(r"[가-힣A-Za-z]{2,}", str(text or ""))
    return {w for w in words if w not in _STOP}


def _bigrams(text: str) -> set:
    """글자 두 개씩. 한국어는 조사가 붙어서 낱말 단위로 비교하면 안 맞는다 —
    '동아리방에는' 과 '동아리방' 은 다른 낱말이지만 같은 것을 말한다."""
    s = re.sub(r"[^가-힣A-Za-z0-9]", "", str(text or ""))
    return {s[i:i + 2] for i in range(len(s) - 1)}


def _same_topic(a: str, b: str) -> bool:
    """두 문장이 같은 것을 말하는가. 짧은 쪽 기준으로 얼마나 겹치는지 본다.

    자카드(합집합 기준)를 쓰면 한쪽이 길 때 값이 눌린다 — "창이 없다" 처럼
    짧은 설정이 긴 설정과 부딪히는 경우가 정확히 그 모양이라, 짧은 쪽을 분모로
    둔다.
    """
    ga, gb = _bigrams(a), _bigrams(b)
    if not ga or not gb:
        return False
    return len(ga & gb) / min(len(ga), len(gb)) >= 0.30


def _negated(text: str) -> bool:
    return any(m in str(text or "") for m in _NEG_MARKS)


def fact_conflicts(facts: list) -> list:
    """확정된 설정끼리 부딪히는 자리. **되돌리지 않고 경고만 한다.**

    facts 는 [{"fact": 문장, "first_episode": n}, …] (SeriesState.facts).
    같은 주제를 말하는 두 문장만 비교한다 — 알맹이 낱말이 2개 이상 겹칠 때.
    """
    rows = [f for f in (facts or []) if isinstance(f, dict) and str(f.get("fact") or "").strip()]
    out = []
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            a, b = rows[i], rows[j]
            ta, tb = str(a["fact"]), str(b["fact"])
            if not _same_topic(ta, tb):
                continue                      # 다른 이야기다
            shared = _content_words(ta) & _content_words(tb)
            topic = ", ".join(sorted(shared)[:3]) or "같은 대상"
            where = (f"{a.get('first_episode', '?')}화 ↔ "
                     f"{b.get('first_episode', '?')}화")

            if _negated(ta) != _negated(tb):
                out.append(
                    f"설정 충돌 의심 ({where}) — 같은 것({topic})을 말하는데 "
                    f"한쪽만 부정입니다.\n    · \"{ta[:60]}\"\n    · \"{tb[:60]}\"")
                continue

            na = dict((u, n) for n, u in _NUM_UNIT.findall(ta))
            nb = dict((u, n) for n, u in _NUM_UNIT.findall(tb))
            clash = [u for u in na if u in nb and na[u] != nb[u]]
            if clash:
                u = clash[0]
                out.append(
                    f"설정 충돌 의심 ({where}) — 같은 것({topic})에 "
                    f"{na[u]}{u} 과(와) {nb[u]}{u} 이(가) 함께 있습니다.\n"
                    f"    · \"{ta[:60]}\"\n    · \"{tb[:60]}\"")
    return out


# ---- 글자 길이 -----------------------------------------------------------
#
# 지금까지 길이는 **하한만** 봤다(말 있는 컷 비율, 무음 연속). 상한이 없으니
# 반대쪽으로 넘어가는 것을 아무도 안 막았다.
#
# 말풍선은 그림 위에 얹힌다. 길어지면 그림을 가리고, 세로 스크롤에서는 그
# 컷에서 눈이 멈춘다 — 체류 시간이 텍스트 길이에 비례한다는 것은 침묵을
# 경계하는 근거이자 **장문을 경계하는 근거**이기도 하다.
#
# 종류마다 다르게 잡는다. 말풍선(대사·속마음)은 인물 옆에 떠서 그림을 직접
# 가리지만, 나레이션은 보통 여백의 띠로 들어가 덜 가린다.
MAX_DIALOGUE_CHARS = 40       # 한 말풍선. 넘으면 두 개로 나누는 편이 낫다
MAX_NARRATION_CHARS = 70      # 나레이션 상자
MAX_CUT_CHARS = 110           # 한 컷의 글자 총합. 말풍선이 여러 개일 때
_LEN_CAP = {"dialogue": MAX_DIALOGUE_CHARS, "thought": MAX_DIALOGUE_CHARS,
            "narration": MAX_NARRATION_CHARS}


def length_warnings(cuts: list) -> list:
    """말풍선이 그림을 가릴 만큼 길지 않은가. **되돌리지 않고 경고만 한다.**

    길이는 판단이 필요한 자리다 — 독백 한 편이 통째로 들어가야 하는 컷도 있고,
    그때는 긴 것이 맞다. 숫자로 막으면 모델이 숫자를 맞추느라 문장을 자르고,
    잘린 문장은 짧아진 것이지 좋아진 것이 아니다.
    """
    out = []
    for c in cuts:
        if not isinstance(c, dict):
            continue
        no = c.get("cut_number")
        total = 0
        for ln in speech_lines(c):
            text = " ".join(str(ln.get("text") or "").split())
            if not text:
                continue
            total += len(text)
            cap = _LEN_CAP.get(str(ln.get("kind") or "dialogue"))
            if cap and len(text) > cap:
                # 조사까지 같이 적어 둔다 — 받침이 있고 없고가 낱말마다 달라서
                # "나레이션가" 같은 문장이 사람 눈에 걸린다.
                kind_ko = {"dialogue": "대사가", "thought": "속마음이",
                           "narration": "나레이션이"}.get(ln.get("kind"), "글자가")
                out.append(
                    f"컷 {no} {kind_ko} {len(text)}자입니다(권장 {cap}자 이내): "
                    f"\"{text[:28]}…\". 말풍선이 길면 그림을 가리고 그 컷에서 "
                    "눈이 멈춥니다 — 두 개로 나누거나, 그림이 이미 말하는 부분을 "
                    "덜어내세요.")
        if total > MAX_CUT_CHARS:
            out.append(
                f"컷 {no} 의 글자가 모두 {total}자입니다(권장 {MAX_CUT_CHARS}자 이내). "
                "한 컷에 말풍선이 여러 개면 그림이 설 자리가 없습니다.")
    return out


def text_warnings(cuts: list) -> list:
    """말의 밀도와 자리 — **되돌리지 않고 경고만 한다.**

    한동안 이걸 게이트로 두었다. 말 있는 컷 50% 이상, 말 없는 컷 2연속까지,
    첫 컷과 장면 전환 컷에 말 필수. 실측이 험했기 때문이다 — 말 있는 컷 28~41%,
    말 없는 컷 최장 12연속, 한 화가 통째로 무성영화.

    그런데 비율을 강제하면 모델은 비율을 맞춘다. 이야기가 부르는 대로가 아니라
    숫자가 부르는 대로 대사를 넣는다. 무성으로 밀어붙이는 화도, 나레이션으로만
    굴러가는 화도 정답일 수 있는데 그 답이 막힌다.

    그래서 세기는 하되 막지 않는다. 사람이 보고 판단할 몫이다 — 프롬프트가
    이 셋을 충분히 길게 가르치고 있고(w7.txt 5-5), 그래도 어긋나면 여기 찍힌다.
    """
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    n = len(cuts)
    if not n:
        return out

    speak = [has_speech(c) for c in cuts]
    num = [c.get("cut_number") for c in cuts]
    said = sum(speak)

    if said * 2 < n:
        out.append(
            f"말(대사·나레이션·속마음)이 있는 컷이 {said}/{n}컷 "
            f"({said * 100 // n}%)입니다. 의도한 것이면 그대로 두세요 — 다만 "
            "글자가 없으면 독자가 무슨 상황인지 알기 어렵고, 큰 컷을 놓아도 "
            "그냥 스크롤로 지나갑니다.")

    run = best = at = 0
    for i, sp in enumerate(speak):
        run = 0 if sp else run + 1
        if run > best:
            best, at = run, i - run + 1
    if best >= 4:
        out.append(
            f"컷 {num[at]}~{num[at + best - 1]} 이 말 없이 {best}컷 연속입니다. "
            "그 구간에서 이야기가 멈춘 것처럼 읽힐 수 있습니다.")

    out += length_warnings(cuts)

    # ---- 종류 편중 ----------------------------------------------------
    # 밀도와 따로 센다. 말이 충분해도 **전부 대사**면 화면이 납작해진다 —
    # 나레이션은 그림이 못 담는 것을, 속마음은 인물의 판단을, 효과음은 소리를
    # 맡는데, 실측에서 이 셋이 통째로 비는 화가 흔했다(213컷에 나레이션 2개).
    #
    # 여기서도 막지 않는다. 무성으로 미는 화도, 나레이션 없는 화도 답일 수
    # 있다. 다만 "안 쓴 것"과 "안 쓰기로 한 것"은 사람이 봐야 갈린다.
    # 종류별 개수는 **세지 않는다.** 한동안 "나레이션·속마음이 0개면 알림",
    # "효과음이 20% 미만이면 알림" 을 두었다가 둘 다 뺐다. 경고여도 그건 최소
    # 할당량이고, 모델은 할당량을 채운다 — 필요 없는 자리에 속마음을 하나 끼워
    # 넣고 그 컷이 늘어진다. 혼자 말하는 화도, 속마음이 없는 화도 답일 수 있다.
    # 효과음을 넉넉히 쓰라는 것은 프롬프트가 말할 일이지 코드가 셀 일이 아니다.
    #
    # 여기서 남은 것은 **"지나쳤다" 쪽 하나뿐**이다. 상한은 채우려는 압력을
    # 만들지 않는다 — 넘지 말라는 말에는 채울 칸이 없다.
    multi = [c.get("cut_number") for c in cuts
             if len([r for r in speech_lines(c) if r["kind"] != "narration"]) > 1]
    if len(multi) * 2 > n:
        out.append(
            f"한 컷에 말이 여러 줄인 컷이 {len(multi)}/{n}컷 입니다 ({multi}). "
            "대부분의 컷은 한 줄이 맞습니다 — 무게 있는 한 마디는 그 컷을 혼자 "
            "써야 세고, 받아치는 말을 붙이면 그 한 마디가 대화에 묻힙니다.")

    if not speak[0]:
        out.append(
            f"첫 컷({num[0]})에 말이 없습니다. 독자가 이 화를 여는 자리라, "
            "여기가 어디이고 무슨 상황인지 세워 주면 나머지가 읽힙니다.")

    jumped = [num[i] for i, c in enumerate(cuts)
              if i > 0 and str(c.get("transition") or "").strip() == SCENE_TRANSITION
              and not speak[i]]
    if jumped:
        out.append(
            f"컷 {jumped} 는 시간·장소가 건너뛰는 자리인데 말이 없습니다. "
            "그림만으로는 장면이 바뀐 줄 모를 수 있습니다.")
    return out


def gate_dialogue(cuts: list) -> list:
    """대사에 화자가 붙어 있는가. **여기서 막는 것은 이것 하나다.**

    화자는 연출 취향이 아니라 데이터 완결성이다 — 없으면 그리는 쪽이 말풍선을
    누구에게 붙일지 짐작해야 하고, 여러 사람이 주고받는 장면 자체를 쓸 수 없다.

    "대사가 주고받아지는가", "말하는 사람이 둘 이상인가" 는 여기서 막지 않는다.
    그건 **특정 수단을 못 박는 하한**이기 때문이다. 혼자 말하는 화도 나레이션이
    받쳐 주면 성립하고, 실제로 1인칭 나레이션으로 굴러가는 웹툰이 많다.
    수단을 강제하면 모델은 그 수단을 채우기만 하고 연출은 더 뻣뻣해진다.
    같은 관찰은 prose_warnings 가 경고로 남긴다 — 사람이 보고 판단할 몫이다.
    """
    # side(좌우 배치)는 여기서 보지 않는다 — gate_frame 이 이미 본다. 같은 규칙을
    # 두 게이트에 두면 한쪽을 고칠 때 다른 쪽이 남는다.
    nameless = []
    for c in cuts:
        for row in speech_lines(c):
            if row["kind"] == "narration":
                continue          # 나레이션 상자는 화자가 없는 것이 맞다
            if not row["speaker"]:
                nameless.append(c.get("cut_number"))
    if not nameless:
        return []
    return [f"컷 {sorted(set(nameless))} 에 대사나 속마음이 있는데 speaker 가 비어 "
            "있습니다. 누가 말하는지 적으세요 — 말풍선을 누구에게 붙일지 알 수 "
            "없고, 한 컷에서 둘이 주고받으면 어느 풍선이 누구 것인지 구분할 "
            "방법이 사라집니다."]


HANGUL_FULL_NAME = re.compile(r"^[가-힣]{3,4}$")


def name_variants(names) -> set:
    """대조에 쓸 이름 조각 — **성을 뗀 형태까지.**

    서술에는 "하윤재" 가 아니라 "윤재가", "윤재는" 으로 나온다. 전체 이름만
    찾으면 한 번도 안 걸린다 — 그림 하네스에서 실제로 그랬다(7 Scene 중 1곳만
    걸렸다가, 성을 떼고 나서 5곳이 됐다). webtoon-harness 의 cast.name_keys()
    와 같은 규칙이고, 같은 이유로 한 글자짜리 조각은 넣지 않는다 (아무 문장에나
    걸린다).

    띄어쓰기가 없는 한 덩어리 한글 이름에만 성을 뗀다. "제라프 알베리온" 처럼
    이미 나뉜 이름은 앞 글자를 떼면 "라프" 같은 조각이 생겨 엉뚱한 낱말에 걸린다.
    """
    out = set()
    for raw in names or ():
        name = str(raw or "").strip()
        if len(name) < 2:
            continue
        out.add(name)
        if HANGUL_FULL_NAME.match(name):
            out.add(name[1:])            # 성 1자 + 이름 2~3자
            if len(name) == 4:
                out.add(name[2:])        # 복성 2자 + 이름 2자
    return {n for n in out if len(n) >= 2}


def gate_zone(payload: dict, known_zones: set = None, known: set = None) -> list:
    """존이 배경으로 그려질 수 있는 상태인가 — **그림을 뽑기 전에 글로 본다.**

    존 배경은 한 번 구워지면 그 존의 **모든 컷이 재사용**한다. 그래서 잘못된
    배경 하나가 화 전체로 번지고, 이미 뽑은 뒤에 알면 그 존의 컷이 전부
    다시다. 검수를 이미지 뒤로 미루지 않고 여기서 하는 이유다.

    셋을 본다. 전부 필드 대조라 LLM 도 문자열 매칭도 필요 없다:

      1. 컷의 zone 이 비어 있지 않은가 — 없으면 배경을 붙일 수 없다.
      2. 컷이 가리키는 새 존의 서술이 zones 에 있는가 — 없으면 안 그려진다.
      3. 그 서술에 **사람이 들어있지 않은가** — 배경은 빈 공간이다. 인물이
         들어가면 그 사람이 배경에 구워져 그 존의 모든 컷에 따라다닌다.

    3번이 이 게이트의 핵심이다. 나머지 둘은 빈칸 검사지만 이건 "필드를 잘못
    이해했다"는 신호이고, 놓치면 결과가 가장 크게 망가진다.

    known_zones 는 이미 배경이 있는 존이다 — 그 id 는 서술을 다시 요구하지
    않는다 (다시 적으면 같은 자리가 두 개의 배경을 갖게 된다).
    """
    failures = []
    cuts = [c for c in payload.get("cuts") or [] if isinstance(c, dict)]
    if not cuts:
        return failures

    empty = [c.get("cut_number") for c in cuts
             if not str(c.get("zone") or "").strip()]
    if empty:
        failures.append(
            f"컷 {empty} 의 zone 이 비어 있습니다. [이 화에서 쓸 수 있는 존] "
            "목록에서 고르거나, 정말 새 구역이면 짧은 id 를 새로 지으세요.")

    described = {}
    for z in payload.get("zones") or []:
        if not isinstance(z, dict):
            continue
        zid = str(z.get("zone_id") or "").strip()
        if zid:
            described[zid] = str(z.get("description") or "").strip()

    used = {str(c.get("zone") or "").strip() for c in cuts}
    used.discard("")
    fresh = sorted(used - set(known_zones or ()))
    missing = [z for z in fresh if not described.get(z)]
    if missing:
        failures.append(
            f"새로 만든 존 {missing} 의 서술이 zones 에 없습니다. 컷이 가리키는데 "
            "서술이 없으면 그 배경은 그려지지 않습니다 — 그 자리가 사람 없이 "
            "어떻게 생겼는지 적으세요.")

    # 배경 서술에 사람이 있으면 그 사람이 배경에 구워진다.
    variants = name_variants(known)
    for zid in fresh:
        text = described.get(zid) or ""
        if not text:
            continue
        hit = sorted(n for n in variants if n in text)
        if hit:
            failures.append(
                f"존 '{zid}' 의 서술에 인물({', '.join(hit)})이 들어 있습니다. "
                "배경은 빈 공간입니다 — 사람이 들어가면 그 사람이 배경에 구워져 "
                "이 존의 모든 컷에 따라다닙니다. 사람을 빼고 그 자리에 항상 있는 "
                "것(가구·구조·창·조명)만 적으세요.")
    return failures


def camera_warnings(num: list, shots: list, angles: list, trans: list) -> list:
    """카메라 세 축이 갈렸는지 — **경고만 한다. 생성을 막지 않는다.**

    예전에는 게이트였다(gate_camera). 여기 있는 것은 전부 **경험적 휴리스틱**이지
    결과물을 성립시키는 조건이 아니다 — 얼굴이 60%인 화도, 계속 눈높이인 화도
    이야기에 따라 맞을 수 있다. 그런데 막아 버리면 그런 연출은 아예 못 나온다.

    실제로 사고가 났다: 한 장면은 5컷까지 갈 수 있고 그 안에서 쓸 수 있는 전환은
    순간·동작 둘뿐이라, 5컷짜리 장면 하나는 같은 전환을 4번 연달아 쓸 수밖에 없다.
    그런데 연속 상한이 3이어서 **정상적인 장면이 게이트를 위반했다.** 규칙 둘이
    서로 싸운 것이고, 값을 올려 막긴 했지만 같은 종류의 충돌은 언제든 다시 난다.
    숫자로 연출을 강제하는 한 그렇다.

    그래서 판정은 그대로 두되 **결과를 보존한다.** 사람이 보고 판단할 몫이다.

    이 게이트가 없던 시절의 실측(완성 run 3개·643컷)이 이 함수의 존재 이유다.
      · 클로즈업+바스트 60%          → 말하는 얼굴과 듣는 얼굴의 반복
      · 익스트림 클로즈업 0.5~2%      → 감정 절정에 쓸 카드가 없다
      · 앙각 1~4%                     → 앵글 축이 사실상 죽어 있었다
      · 인물 없는 컷에 이름이 없었음  → 그런 컷을 아예 만들지 않는다
    전부 "규칙이 없어서"가 아니라 "세지 않아서" 생긴 일이다. 프롬프트에는 그때도
    같은 말이 적혀 있었다.

    수리하지 않고 되돌리는 이유: 거리·앵글·전환은 **무엇을 그리는가**와 묶여 있다.
    size 처럼 숫자만 바꿔 놓으면 서술과 정면으로 어긋난다. 대신 되돌릴 때 어느 컷을
    무엇으로 바꾸라고 지목한다 — 그게 없으면 모델은 아무 데나 고치고 다른 걸 깨뜨린다.

    아래 문구가 "~여야 합니다" 로 남아 있는 것은 그대로 둔다 — 다시 뽑을 때 모델이
    읽는 말이라 지시형이 맞다. 다만 이제 그것이 **중단 사유는 아니다.**
    """
    failures = []
    n = len(shots)
    if not n:
        return failures

    # ---- 거리 ----------------------------------------------------------
    face = [num[i] for i, s in enumerate(shots) if s in FACE_SHOTS]
    if len(face) > n * MAX_FACE_RATIO:
        failures.append(
            f"얼굴 컷({' / '.join(FACE_SHOTS)})이 {len(face)}/{n}컷 "
            f"({len(face) * 100 // n}%)입니다. {int(MAX_FACE_RATIO * 100)}% 이하여야 "
            f"합니다 — 얼굴만 이어지면 웹툰이 아니라 대본입니다. 컷 {face[len(face)//2:]} "
            "중 몇 개를 인서트(사물·손), 원경(장소), 전신(자세)으로 바꾸세요. "
            "무대의 사물 목록에서 고르면 됩니다.")

    shot_run, shot_len, shot_at = longest_run(shots)
    if shot_len > MAX_SAME_SHOT_RUN:
        failures.append(
            f"컷 {num[shot_at]}~{num[shot_at + shot_len - 1]} 이 '{shot_run}' 로 "
            f"{shot_len}컷 연속입니다. 카메라가 그 자리에 붙박여 있는 것입니다.")

    # ---- 앵글 ----------------------------------------------------------
    level = angles.count("수평")
    if level > n * MAX_LEVEL_RATIO:
        failures.append(
            f"angle 이 수평인 컷이 {level}/{n}컷 ({level * 100 // n}%)입니다. "
            f"{int(MAX_LEVEL_RATIO * 100)}% 이하여야 합니다 — 전부 눈높이면 앵글을 "
            "안 쓴 것입니다. 열세·고립은 부감으로, 위협·경외는 앙각으로 바꾸세요.")

    tilt = [num[i] for i, a in enumerate(angles) if a == "기울임"]
    if len(tilt) > MAX_TILT:
        failures.append(
            f"angle 이 기울임인 컷이 {tilt} 로 {len(tilt)}개입니다. 화당 최대 "
            f"{MAX_TILT}개입니다 — 계속 기울어져 있으면 불안이 아니라 기본값이 됩니다.")

    # ---- 전환 ----------------------------------------------------------
    if trans and trans[0] != SCENE_TRANSITION:
        failures.append(
            f"첫 컷의 transition 이 '{trans[0]}' 입니다. 화의 첫 컷은 앞에 아무것도 "
            f"없으므로 언제나 '{SCENE_TRANSITION}' 입니다.")

    tran_run, tran_len, tran_at = longest_run(trans)
    if tran_len > MAX_SAME_TRANSITION_RUN:
        failures.append(
            f"컷 {num[tran_at]}~{num[tran_at + tran_len - 1]} 의 transition 이 "
            f"'{tran_run}' 로 {tran_len}컷 연속입니다. 컷이 넘어가는 방식이 하나뿐이면 "
            "독자는 같은 리듬만 받습니다.")

    kinds = {t for t in trans if t}
    if len(kinds) < MIN_TRANSITION_KINDS:
        failures.append(
            f"transition 이 {sorted(kinds)} {len(kinds)}종뿐입니다. "
            f"{MIN_TRANSITION_KINDS}종 이상 섞으세요.")

    return failures


def gate_layout(cuts: list) -> list:
    """**무결성만** 본다 — 값이 목록에 있는가, 컷이 있는가.

    여기서 걸리면 그림 단계가 그 컷을 못 읽는다. 결과물이 성립하지 않으므로
    되돌리는 것이 맞다. 연출 휴리스틱(비율·개수·연속)은 directing_warnings 로
    옮겼다 — 그쪽은 경고만 하고 결과를 보존한다.
    """
    return _layout_check(cuts)[0]


def directing_warnings(cuts: list) -> list:
    """연출 휴리스틱 — 경고만 한다. 얼굴 비율·앵글·impact·연속 길이 따위."""
    return _layout_check(cuts)[1]


def _layout_check(cuts: list) -> tuple[list, list]:
    """(무결성 실패, 연출 경고). 세로 스크롤 문법 중 모델이 판단할 것 — beat 와 size.

    여백·시선·화면 경계는 게이트에 없다. 그건 beat 시퀀스에서 나오는 산수라서
    derive_layout() 이 계산한다. 유령 id 때와 같은 이유다: 온도 0.9 에서 서로 얽힌
    조건 여덟 개를 자연어로 맞추게 하면 하나를 고칠 때마다 다른 게 깨진다.
    실제로 gpt-4.1 은 여섯 번 연속 같은 자리에서 실패했다 — 가운데가 전부 build 이고
    Scene 이 6~9컷이 되는 실패였다. 모델에게 남기는 것은 내용에서 나오는 판단뿐이다.
    """
    failures = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return ["컷이 하나도 없어 연출을 판정할 수 없습니다."], []
    num = [c.get("cut_number") for c in cuts]
    sizes, beats, renders, shots, angles, trans = [], [], [], [], [], []
    for c in cuts:
        n = c.get("cut_number")
        size = str(c.get("size") or "").strip().lower()
        beat = str(c.get("beat") or "").strip().lower()
        if size not in SIZES:
            failures.append(f"컷 {n} 의 size '{c.get('size')}' 를 모릅니다. "
                            f"{'|'.join(SIZES)} 중 하나여야 합니다.")
        if beat not in BEATS:
            failures.append(f"컷 {n} 의 beat '{c.get('beat')}' 를 모릅니다. "
                            f"{'|'.join(BEATS)} 중 하나여야 합니다.")
        render = str(c.get("render_style") or "").strip().lower()
        if render not in RENDER_STYLES:
            failures.append(f"컷 {n} 의 render_style '{c.get('render_style')}' 를 "
                            f"모릅니다. {'|'.join(RENDER_STYLES)} 중 하나여야 합니다.")
        shot = str(c.get("shot") or "").strip()
        angle = str(c.get("angle") or "").strip()
        tran = str(c.get("transition") or "").strip()
        if shot not in SHOTS:
            failures.append(f"컷 {n} 의 shot '{c.get('shot')}' 를 모릅니다. "
                            f"{' | '.join(SHOTS)} 중 하나여야 합니다.")
        if angle not in ANGLES:
            failures.append(f"컷 {n} 의 angle '{c.get('angle')}' 를 모릅니다. "
                            f"{' | '.join(ANGLES)} 중 하나여야 합니다.")
        if tran not in TRANSITIONS:
            failures.append(f"컷 {n} 의 transition '{c.get('transition')}' 를 "
                            f"모릅니다. {' | '.join(TRANSITIONS)} 중 하나여야 합니다.")
        sizes.append(size)
        beats.append(beat)
        renders.append(render)
        shots.append(shot)
        angles.append(angle)
        trans.append(tran)

    if failures:      # 값이 깨져 있으면 리듬 판정은 의미가 없다
        return failures, []          # 값이 깨졌으면 연출 판정은 의미가 없다

    # ---- 여기서부터는 **경고**다 (생성을 막지 않는다) ---------------------
    #
    # 위까지가 무결성이다 — 값이 목록에 없으면 그림 단계가 그 컷을 못 읽으므로
    # 결과물이 성립하지 않는다. 아래는 전부 경험적 연출 휴리스틱이고, 이야기에
    # 따라 어겨야 맞는 경우가 있다. 막으면 그런 연출이 아예 못 나온다.
    warn = []
    warn.extend(camera_warnings(num, shots, angles, trans))
    failures = warn

    # ---- 크기 ----------------------------------------------------------
    n_impact = sizes.count("impact")
    if n_impact > MAX_IMPACT:
        failures.append(
            f"size 가 impact 인 컷이 {n_impact}개입니다. 화당 최대 {MAX_IMPACT}개입니다 — "
            "전부 크면 아무것도 크지 않습니다.")

    size_run, size_len, size_at = longest_run(sizes)
    if size_len > MAX_SAME_SIZE_RUN:
        failures.append(
            f"컷 {num[size_at]}~{num[size_at + size_len - 1]} 이 '{size_run}' 로 "
            f"{size_len}컷 연속입니다. 크기가 같은 컷이 이어지면 웹툰이 아니라 "
            "크기 같은 이미지 나열이 됩니다.")

    if sizes[-1] not in END_SIZES:
        failures.append(
            f"마지막 컷(스팅어)의 size 가 '{sizes[-1]}' 입니다. "
            f"{' 또는 '.join(END_SIZES)} 여야 합니다 — 납작한 컷으로 화를 끝내면 "
            "다음 화를 누를 이유가 남지 않습니다.")

    # ---- 리듬 ----------------------------------------------------------
    if "turn" not in beats:
        failures.append(
            "이 화에 turn 이 없습니다. 아무것도 뒤집히지 않는 화는 독자가 다음을 "
            "누를 이유가 없습니다. 예상이 깨지는 컷을 최소 1개 지정하세요.")

    beat_run, beat_len, beat_at = longest_run(beats)
    if beat_len > MAX_SAME_BEAT_RUN:
        failures.append(
            f"컷 {num[beat_at]}~{num[beat_at + beat_len - 1]} 이 '{beat_run}' 로 "
            f"{beat_len}컷 연속입니다. 리듬이 아니라 평지입니다.")

    if beats[-1] not in ("turn", "hold"):
        failures.append(
            f"마지막 컷(스팅어)의 beat 가 '{beats[-1]}' 입니다. turn 이나 hold 여야 "
            "다음 화를 부릅니다 — 설명으로 끝나면 궁금증이 남지 않습니다.")

    # ---- 그림체·칸 쓰기 -------------------------------------------------
    # 개수는 세지 않는다. sd 몇 개, 통컷 몇 개는 **이야기가 정할 일**이다.
    # 상한을 두면 모델이 그 숫자에 맞춰 배치한다 — 자리를 만들어 끼워 넣거나,
    # 정말 필요한 자리를 상한 때문에 포기한다. 개수 관찰은 render_warnings 가
    # 경고로 남기고, 여기서는 **자리**만 본다.
    #
    # 자리를 남겨 두는 이유: 비율이 아니라 의미의 문제다. 뒤집히는 순간을
    # 데포르메로 그리면 그 순간이 가벼워지고, 스팅어를 SD 로 끝내면 훅이 풀린다.
    # 이건 "몇 개냐" 가 아니라 "여기냐" 라서 이야기가 달라져도 답이 같다.
    bad_beat = [num[i] for i, r in enumerate(renders)
                if r == "sd" and beats[i] not in SD_BEATS]
    if bad_beat:
        failures.append(
            f"컷 {bad_beat} 의 render_style 이 sd 인데 beat 가 "
            f"{' 또는 '.join(SD_BEATS)} 가 아닙니다. 뒤집는 순간(turn)이나 "
            "처음 세우는 자리(setup)를 SD 로 그리면 그 순간이 가벼워집니다.")

    if renders[-1] == "sd":
        failures.append(
            f"마지막 컷(스팅어) {num[-1]} 의 render_style 이 sd 입니다. "
            "다음 화를 부르는 자리를 데포르메로 그리면 훅이 풀립니다.")

    bad_bleed = [num[i] for i, r in enumerate(renders)
                 if r == "bleed" and beats[i] not in BLEED_BEATS]
    if bad_bleed:
        failures.append(
            f"컷 {bad_bleed} 의 render_style 이 bleed 인데 beat 가 "
            f"{' 또는 '.join(BLEED_BEATS)} 가 아닙니다. 통컷은 판이 뒤집히거나 "
            "터진 자리에 씁니다.")

    bad_breakout = [num[i] for i, r in enumerate(renders)
                    if r == "breakout" and beats[i] not in BREAKOUT_BEATS]
    if bad_breakout:
        failures.append(
            f"컷 {bad_breakout} 의 render_style 이 breakout 인데 beat 가 "
            f"{' 또는 '.join(BREAKOUT_BEATS)} 가 아닙니다. 칸을 뚫고 나오는 것은 "
            "힘이 실린 자리에서만 뜻이 있습니다.")

    # ---- 효과음 --------------------------------------------------------
    # 효과음은 그림 위에 그대로 레터링된다. 로마자가 섞이면 그림에 로마자가 박힌다.
    roman_sfx = [num[i] for i, c in enumerate(cuts)
                 if ROMAN_RE.search(str(c.get("sfx") or ""))]
    if roman_sfx:
        failures.append(
            f"컷 {roman_sfx} 의 sfx 에 로마자가 있습니다. 한글 의성어·의태어만 "
            "씁니다 (쿵 / 두근 / 위이잉 / 콰앙).")

    long_sfx = [num[i] for i, c in enumerate(cuts)
                if len(str(c.get("sfx") or "").strip()) > SFX_MAX_LEN]
    if long_sfx:
        failures.append(
            f"컷 {long_sfx} 의 sfx 가 너무 깁니다 ({SFX_MAX_LEN}자 이내). "
            "효과음은 그림에 얹는 레터링이지 문장이 아닙니다.")

    return [], failures          # 무결성은 위에서 끝났다 — 여기 남은 것은 전부 경고다


# ------------------------------------------------------- 컷 서술 경고

def record_cut_cast(state: "SeriesState", cuts: list, known: set,
                    episode_no: int) -> list:
    """7단계에서 실제로 말한 사람 중 명부에 없는 이름을 명부에 올린다.

    지금까지 7단계는 명부에 아무것도 올리지 않았다. 명부에 없는 사람이 말하면
    경고만 찍고 잊었다. 그런데 그 사람은 **이미 이 화에 등장한 인물**이다 —
    다음 화가 그를 모르면 같은 사람을 다시 만들거나 다른 사람으로 만든다.
    인물은 5단계에서만 생기는 것이 아니라 6·7단계를 지나며 구체화된다.

    외형은 비워 둔다. 7단계는 카메라와 리듬을 정하는 자리이지 인물을 설계하는
    자리가 아니므로, 여기서 지어내면 5단계가 정한 것과 어긋난다. 빈 칸은
    그림 단계의 supporting.json 초안에 그대로 나타나고 사람이 채운다.

    돌려주는 것은 새로 올린 이름 목록 — 화면에 찍어 사람이 알게 한다.
    """
    added = []
    have = {str(c.get("name") or "").strip() for c in state.cast}
    for cut in cuts:
        who = str((cut or {}).get("speaker") or "").strip()
        if not who or who in known or who in have:
            continue
        have.add(who)
        added.append(who)
        row = {"name": who, "first_episode": episode_no,
               "note": f"{episode_no}화 컷 단계에서 대사로 처음 등장",
               "source": "w7"}
        for key in CAST_FIELDS:
            row[key] = ""
        state.cast.append(row)
    return added


def record_cut_zone(state: "SeriesState", payload: dict, place: str,
                    episode_no: int) -> list:
    """7단계가 새로 만든 존을 명부에 올린다. label 은 zones[].description.

    record_cut_cast 와 같은 자리다 — zone 도 5단계가 아니라 7단계에서
    구체화된다(그 컷이 실제로 무슨 구역을 그리는지는 컷을 쪼개 봐야 안다).

    label 로 **컷 서술을 쓰지 않는다.** 컷 서술은 그 순간을 적은 것이라 사람과
    동작이 들어 있고("소파에 시하가 앉아 휴대폰을 본다"), 그것을 배경 프롬프트로
    쓰면 그 사람이 배경에 구워져 그 존의 모든 컷에 따라다닌다. 그래서 7단계가
    zones[] 에 "사람 없이 그 자리가 어떻게 생겼는가" 를 따로 적고, gate_zone 이
    그림을 뽑기 전에 그것을 검사한다.
    """
    added = []
    have = {str(z.get("zone_id") or "").strip() for z in state.zones}
    described = {}
    for z in payload.get("zones") or []:
        if not isinstance(z, dict):
            continue
        zid = str(z.get("zone_id") or "").strip()
        if zid:
            described[zid] = str(z.get("description") or "").strip()

    for cut in payload.get("cuts") or []:
        zid = str((cut or {}).get("zone") or "").strip()
        if not zid or zid in have:
            continue
        label = described.get(zid, "")
        if not label:
            # 게이트가 이미 막았어야 하는 자리다. 그래도 여기까지 왔다면
            # 서술 없는 존을 명부에 올리지 않는다 — 올리면 다음 화가 그 id 를
            # "이미 배경이 있는 존"으로 알고 서술을 영영 안 적는다.
            continue
        have.add(zid)
        added.append(zid)
        state.zones.append({"zone_id": zid, "place": place, "label": label,
                            "first_episode": episode_no})
    return added


def known_speakers(card: str, episodes: list, cast: list = None) -> set:
    """대사를 해도 되는 이름 — 엔진 카드의 주인공·그 한 사람 + 명부의 조연.

    7단계가 명부에 없는 사람에게 대사를 주면, 그림 단계에는 그 사람의 설계가 없다.
    5단계가 "대사가 있거나 두 컷 이상 나오면 new_cast 에 적는다"고 요구하는 것과
    같은 이유다. 반려하지 않고 경고만 한다 — "지나가던 학생" 이 한마디 하는 것은
    정상이고, 그때는 사람이 보고 판단할 몫이다.

    cast 는 연재 명부(SeriesState.cast)다. 스토리 단계에서 확정된 조연은 5단계가
    new_cast 에 다시 적지 않아도 되는 사람이므로, 이걸 안 넘기면 이미 설계가
    있는 인물을 "명부에 없다"고 경고하게 된다.
    """
    names = set()
    for c in cast or []:
        if isinstance(c, dict):
            names.add(str(c.get("name") or "").strip())
    for line in str(card or "").splitlines():
        line = line.strip()
        for head in ("[주인공]", "그 한 사람:"):
            if line.startswith(head):
                value = line[len(head):].strip()
                # "하윤재 — 같은 학과 남후배" 처럼 뒤에 설명이 붙는다
                names.add(re.split(r"[—\-(]", value)[0].strip())
    for e in episodes or []:
        if not isinstance(e, dict):
            continue
        for c in e.get("new_cast") or []:
            if isinstance(c, dict):
                name = str(c.get("name") or "").strip()
                if name:
                    names.add(name)
    return {n for n in names if n}


# 나레이션 어미 — 화 전체에 서술자는 하나다.
#
# 대사는 인물마다 말투가 다른 것이 맞지만 나레이션은 아니다. 실제 사고: 한 화에
# 나레이션이 둘뿐이었는데 "이 세계에는 능력자가 존재합니다." 다음이 "…히어로들이
# 시민을 지킨다." 였다 — 서술자가 두 사람인 것처럼 읽힌다. w7 프롬프트에 어미를
# 고정하라는 규칙이 아예 없어서 매번 복불복이었다.
#
# 게이트가 아니라 경고인 이유: 어미 판정은 형태소 없이 어말 몇 글자로 보는 것이라
# 인용문("…라고 했습니다") 같은 데서 틀릴 수 있다. 컷을 통째로 다시 뽑게 할 만큼
# 확신할 수 있는 판정이 아니다.
POLITE_ENDINGS = ("습니다", "합니다", "입니다", "됩니다", "습니까", "십시오", "세요", "어요", "아요")


def narration_register(text: str) -> str:
    """나레이션 한 줄의 말투 — "높임" | "평서" | "" (판정 불가)."""
    t = str(text or "").strip().rstrip('"\'」』)').rstrip(".!?…")
    if not t:
        return ""
    if t.endswith(POLITE_ENDINGS):
        return "높임"
    if t.endswith(("다", "다.", "라", "군", "지")):
        return "평서"
    return ""


def narration_voice_warnings(cuts: list) -> list:
    seen = {}
    for i, c in enumerate(cuts, 1):
        for ln in (c.get("lines") or []):
            if str((ln or {}).get("kind") or "").strip() != "narration":
                continue
            reg = narration_register((ln or {}).get("text"))
            if reg:
                seen.setdefault(reg, []).append(i)
    if len(seen) < 2:
        return []
    parts = " / ".join(f"{k}({', '.join(str(n) for n in v[:4])}번 컷)"
                       for k, v in seen.items())
    return [f"나레이션 어미가 섞였습니다 — {parts}. 대사는 인물마다 달라도 되지만 "
            f"나레이션은 화 전체에 서술자가 하나입니다. 한쪽으로 맞추세요."]


def layout_warnings(cuts: list) -> list:
    """겹침(overlap)이 뜻을 잃은 자리 — **이유 없는 겹침만** 본다.

    개수는 안 센다. "몇 컷까지"는 장면이 무엇을 하려는지와 무관한 규칙이라,
    조용히 흐르는 화면에서는 하나도 안 겹치는 것이 맞고 밀고 들어오는 장면에서는
    연달아 겹치는 것이 맞다. 여기서 잡을 수 있는 것은 개수가 아니라 **의도가
    적혔는가** 하나뿐이다.
    """
    out = []
    over = [i for i, c in enumerate(cuts, 1)
            if str(c.get("layout") or "").strip().lower() == "overlap"]
    if not over:
        return out
    noreason = [i for i in over
                if not str(cuts[i - 1].get("overlap_reason") or "").strip()]
    if noreason:
        out.append(f"컷 {', '.join(str(n) for n in noreason)} 이 overlap 인데 "
                   f"overlap_reason 이 비었습니다. 왜 겹치는지 한 줄로 못 적으면 "
                   f"그 겹침은 연출이 아니라 사고입니다 — normal 로 두세요.")
    return out


def prose_warnings(cuts: list, known: set = None) -> list:
    """컷 서술이 웹툰 컷이 아니라 소설 지문으로 흐른 자리 — 경고만 한다.

    되돌리지 않는 이유: 여기 걸리는 것은 "틀린 것"이 아니라 "웹툰 문법에서 벗어난 것"이고,
    장르에 따라 예외가 있다. 반려하면 컷 내용 전체를 다시 뽑게 되는데, 그 비용이
    경고 한 줄보다 훨씬 크다. 사람이 보고 판단할 몫으로 남긴다.
    """
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return out

    for c in cuts:
        n = c.get("cut_number")
        text = str(c.get("description") or "")
        for rx, label in TIME_LAPSE:
            m = rx.search(text)
            if m:
                out.append(
                    f"컷 {n} 의 서술에 시간 경과('{m.group(0).strip()}' — {label})가 있습니다. "
                    "한 컷은 한 순간입니다 — 동작이 둘이면 컷을 나누세요.")
                break
        for rx, label in INNER_VOICE:
            m = rx.search(text)
            if m:
                out.append(
                    f"컷 {n} 의 서술에 속마음('{m.group(0).strip()}')이 있습니다. "
                    "그릴 수 없는 것은 컷이 아닙니다 — 표정·자세·시선·손의 위치로 쓰세요.")
                break

    # 카메라는 이제 shot·angle 필드다. 서술에 다시 적으면 이미지 프롬프트에 같은
    # 지시가 두 번 들어가고, 둘이 어긋나면 그리는 쪽이 어느 쪽을 따를지 모른다.
    # (예전에는 서술 첫머리에 적게 했는데 그 자리에 size 값을 적는 컷이 49~100%
    #  였다. 필드로 옮긴 뒤에도 습관이 남을 수 있어 경고는 유지한다.)
    leaked = [c.get("cut_number") for c in cuts
              if re.match(r"^\s*(wide|normal|tall|impact)\b",
                          str(c.get("description") or ""), re.I)
              or any(k in str(c.get("description") or "") for k in CAMERA_WORDS)]
    if leaked:
        out.append(
            f"컷 {leaked} 의 서술에 카메라·크기 낱말이 들어 있습니다. 그건 shot · "
            "angle · size 필드에 이미 있습니다 — 서술에는 **화면에 보이는 것**만 "
            "쓰세요 (누가/무엇이/어떤 자세로/어디에).")

    # 인서트 컷에 사람이 들어가면 인서트가 아니다. 인물 없는 컷을 세는 근거가
    # 이 필드라서, 여기가 흐려지면 얼굴 비율 게이트가 무의미해진다.
    people_re = re.compile(r"(얼굴|표정|눈빛|미소|웃|고개|어깨|서 있|앉아|바라본)")
    fake_insert = [c.get("cut_number") for c in cuts
                   if _shot_of(c) == "인서트"
                   and people_re.search(str(c.get("description") or ""))]
    if fake_insert:
        out.append(
            f"컷 {fake_insert} 은 shot 이 인서트인데 서술에 인물이 있습니다. "
            "인서트는 사물·손끝·풍경만 나오는 컷입니다 — 얼굴이 보이면 "
            "클로즈업이나 바스트입니다.")

    # 어휘를 한 번도 안 쓴 축이 있는가. 게이트에서 뺀 하한들이 여기 있다 —
    # 화마다 맞는 답이 다르고, 강제하면 자리를 만들어 끼워 넣게 된다.
    shots = [_shot_of(c) for c in cuts]
    if shots.count("인서트") < MIN_INSERT:
        out.append(
            "인물 없는 컷(인서트)이 하나도 없습니다. 사물·손·풍경만 있는 컷이 "
            "분위기를 만듭니다 — 무대의 사물 목록에서 고를 자리가 있는지 보세요.")
    if shots.count("원경") < MIN_WIDE_SHOT:
        out.append(
            "원경 컷이 하나도 없습니다. 독자가 여기가 어디인지 한 번은 봐야 "
            "나머지 컷의 공간이 상상됩니다.")
    trans = [str(c.get("transition") or "").strip() for c in cuts]
    if any(trans) and trans.count(MOOD_TRANSITION) < MIN_MOOD_TRANSITION:
        out.append(
            f"transition 이 '{MOOD_TRANSITION}' 인 컷이 없습니다. 시간이 흐르지 않고 "
            "같은 순간의 다른 구석을 보여주는 컷입니다 — 사건만 이어 붙이면 빨리 "
            "읽히지만 그 장면에 머문 느낌이 남지 않습니다.")

    # 나레이션을 한 가지 일에만 쓰고 있는가. 실측에서 나온 나레이션 셋이 전부
    # "늦은 오후, 라운지 한켠" 꼴의 시간·장소 표시였다. 세계 설명도, 1인칭 내면도,
    # 나중에 아는 사실도 0회. 셀 수 있는 신호는 **어느 컷에 붙어 있는가**다 —
    # 전부 장면이 건너뛰는 자리에만 있으면 장면 표시로만 쓴 것이다.
    # 개수는 세지 않는다. 나레이션이 0개인 화도 정답일 수 있고, 하한을 두면
    # 장면이 바뀔 때마다 "늦은 오후, 라운지 한켠" 을 붙여 칸만 채운다.
    # 여기서 보는 것은 **한 가지 일만 하고 있는가**다 — 셋 넘게 나왔는데 전부
    # 장면이 건너뛰는 자리에만 있으면 그건 공식이지 연출이 아니다.
    narrated = [c for c in cuts if not is_blank(c.get("narration"))]
    if len(narrated) >= 3 and all(
            str(c.get("transition") or "").strip() == SCENE_TRANSITION
            for c in narrated):
        out.append(
            f"나레이션 {len(narrated)}개가 전부 장면이 바뀌는 컷에만 붙어 있습니다. "
            "시간·장소 표시로만 쓰고 있다는 뜻입니다 — 세계 설명, 주인공의 1인칭 "
            "독백, 나중에 아는 사실도 나레이션이 할 일입니다.")

    # 대사가 한 줄씩 고립되어 있는가. 실측에서 화의 절반이 그랬다(연속 최대 1컷).
    # 되돌리지 않는 이유: 받아치게 하는 것은 여러 수단 중 하나다. 나레이션이나
    # 속마음으로 상황을 세우는 화도 정답이라, 여기서 막으면 그 답을 없애게 된다.
    spoken = [c for c in cuts if not is_blank(c.get("dialogue"))]
    if spoken:
        best = run = 0
        for c in cuts:
            run = run + 1 if not is_blank(c.get("dialogue")) else 0
            best = max(best, run)
        if best < MIN_DIALOGUE_CHAIN:
            out.append(
                f"대사가 한 줄씩 떨어져 있습니다 (연속 최대 {best}컷). 아무도 아무에게 "
                "대답하지 않으면 상황이 쌓이지 않습니다 — 누가 말할 때 옆에서 "
                "받아치게 하거나, 나레이션·속마음으로 그 자리를 메우세요.")

        who = {str(c.get("speaker") or "").strip() for c in spoken}
        who.discard("")
        if len(spoken) >= MONOLOGUE_LIMIT and len(who) == 1:
            out.append(
                f"대사 {len(spoken)}줄이 전부 「{list(who)[0]}」 한 사람입니다. "
                "혼자 말하는 화가 잘못된 것은 아니지만, 말풍선으로 혼잣말을 계속 하면 "
                "어색합니다 — 소리 내지 않는 말은 thought(속마음)나 나레이션으로 "
                "옮기고, 말풍선은 실제로 남에게 하는 말만 남기세요.")

    out += text_warnings(cuts)
    out += render_warnings(cuts)

    # 명부에 없는 사람이 말하고 있는가. 그 사람은 그림 단계에 설계가 없다.
    if known:
        strangers = {}
        for c in cuts:
            who = str(c.get("speaker") or "").strip()
            if who and who not in known:
                strangers.setdefault(who, []).append(c.get("cut_number"))
        for who, nums in strangers.items():
            out.append(
                f"컷 {nums} 에서 「{who}」 가 말합니다. 명부에 없는 이름이라 그림 "
                "단계에는 이 사람의 성별·외형·옷차림이 없습니다 — 스쳐 지나가는 "
                "인물이 아니라면 5단계 new_cast 에 있어야 합니다.")

    silent = [c.get("cut_number") for c in cuts
              if all(is_blank(c.get(k)) for k in TEXT_FIELDS)]
    if len(silent) < MIN_SILENT_CUTS:
        out.append(
            f"글자가 하나도 없는 컷이 {len(silent)}개입니다. 화당 {MIN_SILENT_CUTS}개 "
            "이상이어야 합니다 — 침묵 컷이 리듬을 만듭니다 "
            "(대사·나레이션·속마음·효과음이 전부 빈 컷).")

    # SD 컷의 효과음. 데포르메 컷에서 효과음은 장식의 일부다.
    sd_nosfx = [c.get("cut_number") for c in cuts
                if str(c.get("render_style") or "").strip().lower() == "sd"
                and is_blank(c.get("sfx"))]
    if sd_nosfx:
        out.append(
            f"컷 {sd_nosfx} 은 SD 인데 sfx 가 비어 있습니다. 데포르메 컷에서 효과음은 "
            "장식의 일부라, 없으면 심심한 그림이 됩니다.")

    # 나레이션이 그림을 때우고 있는지. 서술과 같은 말이 겹치면 의심한다.
    for c in cuts:
        narration = str(c.get("narration") or "").strip()
        if len(narration) < 6:
            continue
        desc = str(c.get("description") or "")
        words = [w for w in re.split(r"[\s,.·]+", narration) if len(w) >= 3]
        hit = [w for w in words if w in desc]
        if len(hit) >= 2:
            out.append(
                f"컷 {c.get('cut_number')} 의 narration 이 서술과 겹칩니다 ({hit[:3]}). "
                "화면에 이미 보이는 것을 글로 다시 쓰면 독자는 그림을 안 봅니다 — "
                "나레이션은 그림이 담을 수 없는 것(흐른 시간·장소·나중에 아는 사실)만 "
                "씁니다.")
    return out


# 소품 텍스트(편지·쪽지 등) 속 인명 — advisory-only.
#
# 실제 사고: 편지지 속 캐릭터 이름이 작가가 입력한 이름과 다르게 나왔다.
# 대사 화자는 known_speakers() 로 이미 검증되지만(위 "명부에 없는 사람이
# 말하고 있는가"), screen_text 는 검증 대상이 아니었다 — W7 이 description
# 처럼 자유 생성하고 그대로 scenegen 프롬프트로 흘러간다.
#
# 한국어에는 대문자가 없어 고유명사를 정확히 가려낼 수 없다 — 그래서
# 호격 조사(-에게/-한테/-아/-야) 앞, 또는 편지 서명 낱말(-올림/-드림/-씀)
# 앞의 낱말만 이름 후보로 본다. 오탐 위험이
# 있는 휴리스틱이라 하드 블록이 아니라 advisory 로만 남긴다(tone_warnings 와
# 같은 급).
_PROP_NAME_TOKEN = re.compile(
    r"[가-힣]{2,4}(?=(?:에게|한테|아|야|보고\s*싶)\b)"
    r"|[가-힣]{2,4}(?=\s*(?:올림|드림|씀)\b)")


def prop_text_name_check(cuts: list, known: set = None) -> list:
    """screen_text(편지·쪽지·화면 글자)에 명부에 없는 이름이 나오는지 본다."""
    out = []
    known = {str(n).strip() for n in (known or set()) if str(n).strip()}
    for c in cuts:
        if not isinstance(c, dict):
            continue
        text = str(c.get("screen_text") or "")
        if not text:
            continue
        hits = {m.group(0) for m in _PROP_NAME_TOKEN.finditer(text)}
        strangers = sorted(h for h in hits if h not in known)
        if strangers:
            out.append(
                f"컷 {c.get('cut_number')} 의 screen_text에 {strangers} 로 보이는 "
                "이름이 있는데 명부(주인공·조연)에 없습니다. 작가가 정한 이름과 "
                "다르게 새로 지어낸 것일 수 있습니다 (오탐일 수 있습니다 — 실제 "
                "인물이 아니면 무시해도 됩니다).")
    return out


# ------------------------------------------------------- 코드 수리 (안전망)

def _shot_of(cut: dict) -> str:
    """컷의 거리. 필드에서 읽는다 — 예전에는 서술에서 낱말을 찾아냈다.

    서술에서 찾던 시절에는 카메라가 아예 없는 컷이 10~17% 였고, 있어도 그 자리에
    size 값이 적혀 있는 컷이 절반을 넘었다. 필드가 되면 게이트가 값을 보증한다.
    """
    shot = str(cut.get("shot") or "").strip()
    return shot if shot in SHOTS else ""


# ---- 안 고치고 보기만 하는 판 ------------------------------------------------
#
# repair_* 는 컷을 **그 자리에서 고친다.** 기본 동작을 "안 고침"으로 바꾸면서도
# 무엇이 걸렸는지는 알려야 해서, 컷을 깊은 복사해 수리를 돌려 보고 그 보고만
# 가져온다. 원본은 한 글자도 안 바뀐다.
#
# 이렇게 감싸는 이유(수리 함수를 둘로 쪼개지 않는 이유): 저 함수들은 서로 순서가
# 얽혀 있고(tone 강등은 render_style 강등 뒤에 와야 한다) 안에서 여러 값을 같이
# 본다. 판정만 떼어내면 그 순서가 두 군데로 갈라져 언젠가 어긋난다.
def _dry(fn, cuts, *rest):
    shadow = copy.deepcopy(cuts)
    return [f"{x} (그대로 두었습니다)" for x in fn(shadow, *rest)]


def dry_repair_sizes(cuts: list) -> list:
    return _dry(repair_sizes, cuts)


def dry_repair_render_styles(cuts: list) -> list:
    return _dry(repair_render_styles, cuts)


def dry_repair_tone_lock(cuts: list, scenes: list) -> list:
    return _dry(repair_tone_lock, cuts, scenes)


def repair_sizes(cuts: list) -> list:
    """같은 size 가 4연속인 구간을 흩는다. 고친 자리를 메모로 돌려준다.

    왜 코드가 고치는가: 모델은 이 조건을 못 지킨다. 실제로 여섯 번 연속 실패했고,
    size 를 고치면 beat 가 깨지고 beat 를 고치면 size 가 깨지는 두더지잡기가 됐다.
    "같은 값이 4번 이어지지 않게 한다" 는 판단이 아니라 산수다 — 코드가 할 일이다.

    아무 값이나 넣지 않는다. 모델이 서술 첫머리에 적어 둔 **카메라**에서 끌어온다.
    서술은 그대로 두고 크기만 바꾸는 것이므로 둘이 어긋나면 둘 다 죽는다.
    beat 는 건드리지 않는다 — scene_break 계산이 beat 에 얹혀 있고, beat 는
    "독자의 상태" 라서 그림에서 되짚을 근거가 없다. 남으면 재시도로 넘긴다.
    """
    notes = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    n = len(cuts)
    if n < 2:
        return notes

    def sizes():
        return [str(c.get("size") or "").strip().lower() for c in cuts]

    # impact 가 상한을 넘으면 뒤엣것부터 tall 로 내린다. 크기를 낮추는 것이므로
    # 안전하다 — 어느 컷이 가장 커야 하는지는 모델이 앞에서부터 고른 순서를 믿는다.
    idx = [i for i, z in enumerate(sizes()) if z == "impact"]
    if len(idx) > MAX_IMPACT:
        # 마지막 컷이 impact 면 그것부터 지킨다 (스팅어는 커야 한다). 나머지는
        # 앞에서부터 채운다 — 모델이 먼저 고른 자리를 더 중요하다고 본다.
        keep = {n - 1} if idx[-1] == n - 1 else set()
        for i in idx:
            if len(keep) >= MAX_IMPACT:
                break
            keep.add(i)
        for i in idx:
            if i in keep:
                continue
            cuts[i]["size"] = "tall"
            notes.append(
                f"컷 {cuts[i].get('cut_number')} 의 size 를 impact -> tall 로 "
                f"내렸습니다 (impact 가 {len(idx)}개로 상한 {MAX_IMPACT}개를 "
                "넘었습니다).")

    # 스팅어 크기. 4연속을 고친 뒤 남는 유일한 size 위반이 이것이었다 (7회 중 2회).
    # "마지막 컷은 크게" 도 판단이 아니라 규칙이므로 여기서 끝낸다.
    last = sizes()[-1]
    if last in SIZES and last not in END_SIZES:
        shot = _shot_of(cuts[-1])
        want = "impact" if (sizes().count("impact") < MAX_IMPACT
                            and shot in ("클로즈업", "익스트림")) else "tall"
        cuts[-1]["size"] = want
        notes.append(
            f"마지막 컷 {cuts[-1].get('cut_number')} 의 size 를 {last} -> {want} 로 "
            "바꿨습니다 (스팅어는 납작하게 끝내지 않습니다"
            + (f", 거리 '{shot}'" if shot else "") + ").")

    for _ in range(n):           # 한 번에 한 자리씩. 못 고치면 빠져나온다
        cur = sizes()
        if any(s not in SIZES for s in cur):
            return notes         # 값이 깨져 있으면 게이트가 먼저 말하게 둔다
        value, length, start = longest_run(cur)
        if length <= MAX_SAME_SIZE_RUN:
            return notes

        # 구간 가운데를 고른다. 마지막 컷은 impact/tall 규칙이 있어 건드리지 않는다.
        spot = None
        for offset in range(length):
            i = start + MAX_SAME_SIZE_RUN - 1 - offset   # 가운데에서 왼쪽으로
            if 0 <= i < n - 1 and i > start - 1:
                spot = i
                break
        if spot is None:
            return notes

        n_impact = cur.count("impact")
        wanted = [w for w in SHOT_SIZE.get(_shot_of(cuts[spot]), ())
                  if w != value]
        # 거리가 없거나 거리가 가리키는 값이 지금 값과 같으면 이웃과 다른 값 중에서
        wanted += [w for w in ("tall", "wide", "normal", "impact") if w != value]
        picked = None
        for w in wanted:
            if w == "impact" and n_impact >= MAX_IMPACT:
                continue
            left = cur[spot - 1] if spot > 0 else None
            right = cur[spot + 1] if spot + 1 < n else None
            if w == left == right:      # 새 연속을 만들면 안 된다
                continue
            picked = w
            break
        if picked is None:
            return notes

        shot = _shot_of(cuts[spot])
        cuts[spot]["size"] = picked
        notes.append(
            f"컷 {cuts[spot].get('cut_number')} 의 size 를 {value} -> {picked} 로 "
            f"바꿨습니다 (같은 크기 {length}연속을 끊었습니다"
            + (f", 거리 '{shot}' 에 맞춤" if shot else "") + ").")
    return notes


def repair_stinger_number(payload: dict) -> list:
    """stinger_cut_number 를 마지막 컷 번호로 맞춘다.

    스팅어는 정의상 마지막 컷이다. 이 필드는 모델이 셀 것이 아니라 코드가 아는 값인데,
    컷을 12개로 쓰려다 14개가 되면 번호만 12로 남는 일이 반복됐다. 숫자 세기로
    재생성을 부르는 것은 낭비다 — 훅을 어디에 두는가는 서술이 정하고, 이 필드는
    그 자리를 가리키는 번호일 뿐이다.
    """
    cuts = [c for c in payload.get("cuts") or [] if isinstance(c, dict)]
    if not cuts:
        return []
    last = cuts[-1].get("cut_number")
    if payload.get("stinger_cut_number") == last:
        return []
    was = payload.get("stinger_cut_number")
    payload["stinger_cut_number"] = last
    return [f"stinger_cut_number 를 {was} -> {last} 로 맞췄습니다 "
            "(스팅어는 언제나 마지막 컷입니다)."]


def repair_speech(cuts: list) -> list:
    """새 형식(lines)을 옛 칸에 되비추고, 종류를 모르는 줄을 대사로 본다.

    강등만 한다 — 없던 말을 만들지 않는다. 되비추기는 산수라서 모델을 되돌릴
    이유가 없다 (repair_sizes 와 같은 판단).
    """
    notes = []
    for cut in cuts:
        if not isinstance(cut, dict) or not isinstance(cut.get("lines"), list):
            continue
        rows = speech_lines(cut)
        if len(rows) != len([r for r in cut["lines"] if isinstance(r, dict)
                             and str(r.get("text") or "").strip()]):
            notes.append(f"컷 {cut.get('cut_number')} 의 lines 에서 kind 를 알 수 "
                         f"없는 줄을 버렸습니다.")
        sync_legacy_speech(cut)
        if len(rows) > 1:
            notes.append(f"컷 {cut.get('cut_number')} 에 말이 {len(rows)}줄입니다 "
                         f"({', '.join(r['speaker'] or r['kind'] for r in rows)}).")
    return notes


def repair_render_styles(cuts: list) -> list:
    """자리를 어긴 render_style 을 **강등만** 한다 (sd·bleed·breakout -> normal).

    강등만 한다. normal 로 내리는 것은 "이 컷은 특별하지 않다" 는 뜻이라
    안전하지만, 반대로 어떤 컷을 SD 나 통컷으로 올릴지는 톤 판단이라 코드가
    정할 수 없다.

    승격을 코드가 하면 안 되는 더 큰 이유가 있다: 서술이 그림체에 묶여 있기
    때문이다(w7 규칙 8). 정식 작화용으로 쓰인 "눈동자에 미묘한 떨림이 감돈다"
    를 그대로 두고 render_style 만 sd 로 바꾸면, 얼굴을 극단적으로 단순화하라는
    그림체 지시와 서술이 정면으로 부딪친다. 그래서 승격은 아예 하지 않는다 —
    sd 가 0개인 화는 게이트도 여기도 손대지 않고 그대로 통과시킨다.

    **bleed·breakout 도 여기서 본다.** 예전에는 sd 만 봤는데, 그러면 똑같은
    모양의 위반(자리를 어긴 render_style)이 한쪽은 조용히 고쳐지고 한쪽은 화를
    통째로 되돌린다. 실제로 `마지막 컷 · hold · bleed` 가 여섯 번 연속 나와
    1화를 못 만들었다 — 모델을 바꿔도 같은 자리였다(w7 의 "스팅어는 가장 큰
    컷" 과 BLEED_BEATS 가 turn 하나에서만 겹치기 때문이다).

    그리고 이 둘은 sd 보다 강등이 **더** 안전하다. 위 RENDER_STYLES 주석대로
    bleed·breakout 은 그림체가 아니라 **칸을 어떻게 쓰는가**라서, sd 와 달리
    서술에 묶여 있지 않다. 통컷을 normal 로 내려도 서술은 그대로 맞는다 —
    size 가 그 컷의 크기를 따로 들고 있으므로 화면에서 작아지지도 않는다.
    """
    notes = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return notes
    renders = [str(c.get("render_style") or "").strip().lower() for c in cuts]
    beats = [str(c.get("beat") or "").strip().lower() for c in cuts]
    if any(r not in RENDER_STYLES for r in renders):
        return notes                     # 값이 깨져 있으면 게이트가 먼저 말한다

    def demote(i, why):
        was = renders[i]
        cuts[i]["render_style"] = "normal"
        renders[i] = "normal"
        notes.append(f"컷 {cuts[i].get('cut_number')} 의 render_style 을 "
                     f"{was} -> normal 로 되돌렸습니다 ({why}).")

    # 자리(beat)를 어긴 것은 종류를 가리지 않고 내린다. 어느 beat 가 그 카드의
    # 자리인지는 위의 SD_BEATS · BLEED_BEATS · BREAKOUT_BEATS 가 정한다.
    for i, r in enumerate(renders):
        if r == "sd" and beats[i] not in SD_BEATS:
            demote(i, f"beat 가 '{beats[i]}' 라 SD 자리가 아닙니다")
        elif r == "bleed" and beats[i] not in BLEED_BEATS:
            demote(i, f"beat 가 '{beats[i]}' 라 통컷 자리가 아닙니다")
        elif r == "breakout" and beats[i] not in BREAKOUT_BEATS:
            demote(i, f"beat 가 '{beats[i]}' 라 칸 밖으로 나갈 자리가 아닙니다")
        elif r == "float" and beats[i] not in FLOAT_BEATS:
            demote(i, f"beat 가 '{beats[i]}' 라 힘을 빼는 자리가 아닙니다")
    if renders[-1] in ("sd", "float"):
        demote(len(renders) - 1,
               "스팅어는 데포르메나 배경 없는 컷으로 끝내지 않습니다")

    # 개수로는 강등하지 않는다. 몇 개가 맞는지는 이야기가 정한다 — 코드가 여섯
    # 번째 sd 를 조용히 normal 로 내리면, 그 화가 왜 심심해졌는지 아무도 모른다.
    # 많으면 render_warnings 가 찍어 주고, 판단은 사람이 한다.
    return notes


def tone_of_cut(scenes: list, cut_number: int) -> str:
    """그 컷이 속한 장면의 tone. 못 찾으면 빈 문자열.

    장면 경계는 scenes[].last_cut 이 정한다 — gate_scenes 가 이미 순서와
    연속성을 확인한 뒤라 여기서는 그대로 믿는다.
    """
    prev = 0
    for sc in scenes or []:
        if not isinstance(sc, dict):
            continue
        last = sc.get("last_cut")
        if not isinstance(last, int) or isinstance(last, bool):
            continue
        if prev < cut_number <= last:
            return str(sc.get("tone") or "").strip()
        prev = last
    return ""


def repair_tone_lock(cuts: list, scenes: list) -> list:
    """장면 tone 이 금지한 그림체를 되돌린다 (긴장·감정의 sd -> normal).

    repair_render_styles 와 **같은 방향으로만** 움직인다 — 강등만 한다.
    개그 장면이라고 코드가 sd 를 올리지는 않는다. 승격을 코드가 하면 안 되는
    이유는 저기 적힌 것과 같다: 서술이 그림체에 묶여 있어서(w7 규칙 8), 정식
    작화용으로 쓰인 표정 묘사를 그대로 두고 render_style 만 sd 로 바꾸면
    그림체 지시와 서술이 정면으로 부딪친다.

    그러면 SD 는 어떻게 나오나 — **프롬프트가 부른다.** tone 을 장면에 적게
    하고(w7.txt 1번), "개그 장면에서는 sd 를 적극적으로 쓴다"를 가르친다.
    코드는 잘못 놓인 것만 치운다. 이것이 하한 게이트와 다른 점이다: 하한은
    "숫자를 채워라"라서 진지한 컷이 데포르메가 되고, 이건 "이 자리는 아니다"라서
    그런 일이 안 생긴다.
    """
    notes = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts or not scenes:
        return notes
    for c in cuts:
        render = str(c.get("render_style") or "").strip().lower()
        if render not in RENDER_STYLES:
            continue                     # 값이 깨져 있으면 게이트가 먼저 말한다
        num = c.get("cut_number")
        if not isinstance(num, int) or isinstance(num, bool):
            continue
        tone = tone_of_cut(scenes, num)
        if render in TONE_FORBIDS.get(tone, ()):
            c["render_style"] = "normal"
            notes.append(
                f"컷 {num} 의 render_style 을 {render} -> normal 로 되돌렸습니다 "
                f"(이 장면의 tone 이 '{tone}' 입니다 — 조여진 공기를 데포르메로 "
                f"그리면 그 순간이 풀립니다).")
    return notes


# 성격에 없는 진지함이 갑자기 튀어나오는 사고 — 실제 사고: "장난스럽다"만
# 적었는데 후반부가 급격히 진지해졌고, 빌드업 없이 "모든 선택을 바꿔놓았다"
# 류 단정적 전환 문장이 뜬금없이 들어갔다. hard block 이 아니라 다른 tone
# 경고와 같은 급의 advisory — 오탐이 있어도 실행을 막지 않는다.
_SERIOUS_PERSONALITY_WORDS = ("진지", "무겁", "심각", "냉철", "비장", "우울")
_ABRUPT_SHIFT_PATTERN = re.compile(
    r"모든.{0,10}?(바꾸|바꿔|바꿨|바뀌|바뀐|달라지|달라진|달라졌|"
    r"뒤바뀌|뒤바뀐|무너지|무너진|무너졌|무너뜨리)")


def tone_warnings(cuts: list, scenes: list, personality: str = "") -> list:
    """tone 을 적어 놓고 살리지 않은 자리 — **세기만 하고 막지 않는다.**

    개그 장면을 전부 normal 로 그리는 것은 위반이 아니다. 정식 작화로 웃기는
    장면도 있다. 다만 tone 을 개그로 잡아 놓고 그림체가 하나도 안 바뀌면
    대개 놓친 것이라, 사람이 한 번 볼 수 있게 찍어 둔다.

    한 화의 tone 이 전부 같은 것도 마찬가지다 — 그런 화가 있을 수 있지만,
    대개는 장면을 나눠 놓고 성격을 안 준 것이다.
    """
    out = []
    personality_text = str(personality or "")
    if not any(w in personality_text for w in _SERIOUS_PERSONALITY_WORDS):
        cuts_list = [c for c in cuts if isinstance(c, dict)]
        for i, c in enumerate(cuts_list):
            lines = c.get("lines") if isinstance(c.get("lines"), list) else []
            texts = [str(ln.get("text") or "") for ln in lines
                     if isinstance(ln, dict) and ln.get("kind") in ("narration", "dialogue")]
            hit = next((t for t in texts if _ABRUPT_SHIFT_PATTERN.search(t)), None)
            if hit:
                out.append(
                    f"컷 {c.get('cut_number')}에 '{hit.strip()}' 처럼 단정적인 전환 "
                    "문장이 있는데, personality 에는 진지함을 가리키는 말이 없습니다. "
                    "지정 안 한 성격 이탈(설정 붕괴)일 수 있습니다 — 빌드업 없이 "
                    "톤이 급전환된 것이 아닌지 보세요.")
                # 실제 사고에서 "빌드업 없이" 가 핵심이었다 — 급전환 문장 자체보다,
                # 그 앞에 조짐이 한 비트도 없이 바로 꺾이는 것이 문제였다. beat 가
                # build/turn 인 컷이 하나라도 앞에 있으면 이미 조여지고 있었던
                # 것으로 보고, 없으면 별도로 한 번 더 짚는다.
                lead = cuts_list[max(0, i - 2):i]
                has_buildup = any(
                    str(p.get("beat") or "").strip().lower() in ("build", "turn")
                    for p in lead)
                if not has_buildup:
                    out.append(
                        f"컷 {c.get('cut_number')} 앞 두 컷 모두 beat 가 build/turn 이 "
                        "아닙니다 — 전환 직전에 조짐(빌드업)이 없다는 뜻일 수 있습니다. "
                        "장난스러운 흐름이 예고 없이 바로 진지하게 꺾이는 것이 아닌지 "
                        "보세요.")
                break
    cuts = [c for c in cuts if isinstance(c, dict)]
    scenes = [s for s in (scenes or []) if isinstance(s, dict)]
    if not cuts or not scenes:
        return out

    tones = [str(s.get("tone") or "").strip() for s in scenes]
    named = [t for t in tones if t in TONES]
    if len(named) >= SCENE_COUNT_MIN and len(set(named)) == 1:
        out.append(
            f"이 화의 장면 {len(named)}개가 전부 '{named[0]}' 입니다. "
            "장면을 나눠 놓고 성격을 안 준 것이 아닌지 보세요 — tone 이 같으면 "
            "화 전체가 같은 그림체로 나옵니다.")

    prev = 0
    for i, sc in enumerate(scenes, 1):
        tone = str(sc.get("tone") or "").strip()
        last = sc.get("last_cut")
        if not isinstance(last, int) or isinstance(last, bool):
            continue
        span = [c for c in cuts
                if isinstance(c.get("cut_number"), int)
                and prev < c["cut_number"] <= last]
        prev = last
        if tone != "개그" or not span:
            continue
        kinds = {str(c.get("render_style") or "normal").strip().lower()
                 for c in span}
        if kinds <= {"normal"}:
            out.append(
                f"{i}번째 장면(컷 {span[0]['cut_number']}~{span[-1]['cut_number']})은 "
                f"tone 이 '개그' 인데 컷이 전부 normal 입니다. 의도한 것이면 그대로 "
                "두세요 — 다만 개그로 잡아 놓고 정식 작화로만 그리면 그 장면을 "
                "살리지 않은 것입니다 (sd·emphasis 가 이 자리의 카드입니다).")
    return out


# 인접 컷에서 얼굴 방향이 예고 없이 뒤집히는 사고 — 실제 사고: "1·3컷은 정면인데
# 2컷만 갑자기 뒷모습이라 독자가 읽는 흐름에 불편감을 느낀다"는 피드백. shot·angle
# 필드에는 얼굴 방향(정면/뒷모습)이 없다 — 카메라의 거리·높이일 뿐, 인물이 카메라를
# 보고 있는지는 서술(description)의 자연어로만 존재해서 게이트가 못 본다. 그래서
# advisory 로, 인접한 두 컷의 서술에서 정면류/뒷모습류 낱말이 그대로 부딪힐 때만
# 짚는다 — 오탐을 피하려고 낱말을 좁게 잡았다(넓게 잡으면 "돌아서서 걷는다"류의
# 흔한 동작 서술까지 다 걸린다).
_FRONT_FACING = re.compile(r"(정면|마주\s*보|마주본|얼굴을\s*보)")
_BACK_FACING = re.compile(r"(뒷모습|뒤통수|등을\s*보이|등을\s*돌리)")


def facing_warnings(cuts: list) -> list:
    """인접 컷 사이 얼굴 방향(정면 ↔ 뒷모습)이 완충 없이 뒤집히는 자리 — advisory."""
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    for prev, cur in zip(cuts, cuts[1:]):
        prev_desc = str(prev.get("description") or "")
        cur_desc = str(cur.get("description") or "")
        flipped = ((_FRONT_FACING.search(prev_desc) and _BACK_FACING.search(cur_desc))
                   or (_BACK_FACING.search(prev_desc) and _FRONT_FACING.search(cur_desc)))
        if flipped:
            out.append(
                f"컷 {prev.get('cut_number')}→{cur.get('cut_number')} 사이 얼굴 방향이 "
                "정면↔뒷모습으로 바로 뒤집힙니다. 독자가 읽는 흐름이 끊길 수 있습니다 — "
                "옆모습·반측면처럼 완충이 되는 각도를 사이에 넣거나, 한쪽 방향을 "
                "맞추는 것을 검토하세요.")
    return out


def suggest_beat_sequence(cuts: list) -> str:
    """4연속을 끊은 beat 수열 한 줄. 재시도 때 모델에게 **베껴 쓸 답**으로 준다.

    "4연속입니다" 라고만 말하면 모델은 그 자리만 고치고 다른 곳을 깨뜨린다.
    고쳐 놓은 수열을 통째로 주면 베끼기만 하면 되므로 한 번에 끝난다.
    """
    beats = [str(c.get("beat") or "").strip().lower() for c in cuts
             if isinstance(c, dict)]
    silent = [is_blank(c.get("dialogue")) for c in cuts if isinstance(c, dict)]
    if not beats:
        return ""
    fixed = list(beats)
    for _ in range(len(fixed)):
        value, length, start = longest_run(fixed)
        if length <= MAX_SAME_BEAT_RUN:
            break
        # 구간 안에서 대사 없는 컷을 먼저 hold 로 돌린다 — 침묵 컷이 곧 hold 다.
        span = range(start, start + length)
        spot = next((i for i in span
                     if i < len(silent) and silent[i] and i != len(fixed) - 1), None)
        if spot is None:
            spot = min(start + MAX_SAME_BEAT_RUN - 1, len(fixed) - 2)
        fixed[spot] = "hold" if value != "hold" else "build"
    return " ".join(fixed)


# ------------------------------------------------ 연출 계산 (코드가 하는 몫)

def scene_edges(scenes, n: int) -> list:
    """모델이 낸 scenes → 경계가 되는 컷 index 목록. 쓸 수 없으면 빈 목록.

    여기서 모양만 본다. "2~5컷이 맞는가" 같은 판정은 gate_scenes 가 한다 —
    이 함수는 게이트를 통과한 뒤에도 불리고, 옛 run 에는 scenes 가 아예 없다.
    """
    if not isinstance(scenes, list) or not scenes:
        return []
    edges = []
    for sc in scenes:
        if not isinstance(sc, dict):
            return []
        last = sc.get("last_cut")
        if not isinstance(last, int) or isinstance(last, bool):
            return []
        if not 1 <= last <= n:
            return []
        edges.append(last - 1)
    if edges != sorted(set(edges)) or edges[-1] != n - 1:
        return []
    return edges


def derive_layout(cuts: list, scenes=None) -> list:
    """gap_after · scene_break · gaze 를 계산해 컷에 써 넣는다.

    모델은 "이 컷이 무엇인가"(beat·size)와 "여기까지가 한 장면"(scenes)을 정하고,
    "그래서 어디에 어떻게 놓이는가"는 코드가 센다. 여백은 컷 사이의 관계라서 규칙이
    곧 산수이고, 시선은 크기·앵글에서 나온다. 모델이 이걸 자연어로 맞추려 하면
    조건끼리 충돌하지만(하나를 고치면 다른 하나가 깨진다), 코드는 한 번에 배치한다.

    돌려주는 것은 사람이 봐야 할 메모다 — 규칙대로 떨어지지 않아 코드가 손을 댄 자리.
    """
    cuts = [c for c in cuts if isinstance(c, dict)]
    n = len(cuts)
    if not n:
        return []
    beats = [str(c.get("beat") or "").strip().lower() for c in cuts]
    sizes = [str(c.get("size") or "").strip().lower() for c in cuts]
    trans = [str(c.get("transition") or "").strip() for c in cuts]
    renders = [str(c.get("render_style") or "").strip().lower() for c in cuts]
    notes = []

    # ---- gap_after ------------------------------------------------------
    # 예전에는 beat 만 보고 4값을 배정했다. 그러면 여백이 리듬 라벨의 함수일 뿐,
    # 컷 사이에 실제로 무슨 일이 일어났는지와 무관해진다 — 장면이 통째로 바뀌는
    # 자리와 같은 동작이 이어지는 자리에 같은 여백이 들어갔다.
    # 여백은 "두 컷 사이에 흐른 시간"이므로 **전환 유형**이 1차 근거다.
    #
    #   다음이 turn / 통컷 → 3 (터지기 직전의 뜸들이기. 화당 2회까지, 넘치면 2)
    #   다음이 '장면'      → 2 (시간·장소가 건너뛴다)
    #   현재가 release     → 0 (터진 뒤에는 몰아친다)
    #   다음이 '순간'      → 0 (같은 동작의 다음 찰나. 붙어 있어야 이어진다)
    #   다음이 '분위기'    → 2 (시간이 안 흐르는 컷. 머무르게 둔다)
    #   현재가 hold        → 2 (멈춘 자리는 길게 남긴다)
    #   나머지             → 1
    gaps = [1] * n
    claimed = [False] * n
    long_used = 0
    for i in range(n - 1):
        if beats[i + 1] == "turn" or renders[i + 1] == "bleed":
            if long_used < MAX_LONG_GAPS:
                gaps[i] = MAX_GAP
                long_used += 1
            else:
                gaps[i] = 2         # 반전이 셋 이상이면 나머지는 2 — 3 은 화당 2회까지
            claimed[i] = True
    for i in range(n - 1):
        if claimed[i]:
            continue
        if trans[i + 1] == SCENE_TRANSITION:
            gaps[i] = 2
        elif beats[i] == "release":
            gaps[i] = 0
        elif trans[i + 1] == "순간":
            gaps[i] = 0
        elif trans[i + 1] == MOOD_TRANSITION:
            gaps[i] = 2
        elif beats[i] == "hold":
            gaps[i] = 2
    gaps[-1] = 1                    # 마지막 컷의 여백은 읽히지 않는다

    # 낙차 자리가 하나도 없는 화 — 스팅어 직전을 벌린다.
    # (turn 이 1번 컷뿐이면 "turn 직전"이 없어서 3 이 안 나온다)
    if MAX_GAP not in gaps[:-1] and n >= 3:
        spot = next((i for i in range(n - 2, -1, -1) if beats[i] != "release"), None)
        if spot is not None:
            gaps[spot] = MAX_GAP
            notes.append(f"컷 {cuts[spot].get('cut_number')} 뒤를 낙차 자리로 벌렸습니다 "
                         "(이 화에는 반전 직전이 없었습니다).")

    # 몰아치는 자리가 없는 화 — 같은 beat 가 이어지는 첫 자리를 붙인다.
    if 0 not in gaps[:-1] and n >= 3:
        spot = next((i for i in range(n - 1)
                     if not claimed[i] and gaps[i] != MAX_GAP
                     and beats[i] == beats[i + 1]), None)
        if spot is None:
            spot = next((i for i in range(n - 1)
                         if not claimed[i] and gaps[i] != MAX_GAP), None)
        if spot is not None:
            gaps[spot] = 0
            notes.append(f"컷 {cuts[spot].get('cut_number')} 뒤를 몰아치는 자리로 "
                         "붙였습니다 (이 화에는 release 가 없었습니다).")

    # 여백이 두 종류뿐이면 리듬이 아니라 스위치다 — 한 자리를 되돌린다.
    if len(set(gaps[:-1])) < MIN_GAP_KINDS and n >= 3:
        for i in range(n - 1):
            if claimed[i] or gaps[i] == MAX_GAP:
                continue
            if gaps[:-1].count(gaps[i]) > 1:
                gaps[i] = 1 if gaps[i] != 1 else 2
                break

    # ---- scene_break ----------------------------------------------------
    # 장면 경계는 **모델이 정한다.** 컷을 어디서 끊을지는 산수가 아니라 "여기까지가
    # 한 장면"이라는 판단이고, 그 판단에는 이 장면이 무슨 장면이고 어떤 분위기인지가
    # 딸려 온다(scenes 의 what·mood). 코드가 beat 만 보고 끊으면 그 의도가 없다.
    #
    # scenes 가 없거나 깨져 있으면 예전처럼 계산한다 — 옛 run 을 다시 그릴 수 있어야
    # 하고, 게이트(gate_scenes)가 먼저 잡아 주므로 여기까지 오는 일은 드물다.
    breaks = [False] * n
    edges = scene_edges(scenes, n)
    if edges:
        for e in edges:
            breaks[e] = True
    else:
        # 한 Scene 은 2~5컷이다. 끊는 자리는 hold 나 turn 으로 끝나는 컷을 고르되,
        # 남은 컷이 5개를 넘는데 후보가 없으면 어쩔 수 없이 끊고 메모를 남긴다.
        breaks[-1] = True
        start = 0
        while n - start > SCENE_MAX:
            lo = start + SCENE_MIN - 1
            hi = min(start + SCENE_MAX - 1, n - 2)
            if hi < lo:
                break
            spot = next((e for e in range(hi, lo - 1, -1)
                         if beats[e] in ("hold", "turn")), None)
            if spot is None:
                spot = hi
                notes.append(
                    f"컷 {cuts[spot].get('cut_number')} 뒤에서 화면을 끊었는데 그 컷의 "
                    f"beat 가 '{beats[spot]}' 입니다. 끊을 자리에 hold 가 없었습니다 — "
                    "설명하다 만 자리에서 화면이 넘어갑니다.")
            breaks[spot] = True
            start = spot + 1

    # ---- gaze -----------------------------------------------------------
    # 세로 스크롤에서 시선은 아래로 흘러야 다음 컷이 읽힌다. 마지막 컷에서만 멈춘다.
    # 인서트 컷은 사람이 없으므로 시선을 줄 주체가 없다 — 구도만 아래로 흘린다.
    angs = [str(c.get("angle") or "").strip() for c in cuts]
    shots = [str(c.get("shot") or "").strip() for c in cuts]
    for i in range(n):
        if i == n - 1:
            gaze = "at-viewer"
        elif shots[i] == "인서트":
            gaze = "down"
        elif angs[i] == "앙각" or sizes[i] in ("tall", "impact"):
            gaze = "toward-next"
        else:
            gaze = "down"
        cuts[i]["gap_after"] = gaps[i]
        cuts[i]["scene_break"] = bool(breaks[i])
        cuts[i]["gaze"] = gaze

    # ---- vertical_link — 배경이 컷을 넘어 이어지는 자리 ---------------------
    # 세로 스크롤을 만화와 가르는 것이 여백만은 아니다. 붙여 놓은 두 컷이 서로
    # 무관한 그림이면 붙여도 나열이고, 독자는 "칸 여덟 개짜리 만화 페이지를 세로로
    # 잘라 놓은 것"으로 읽는다. 웹툰에서만 되는 연출은 그 반대다 — **무대는
    # 그대로 두고 카메라만 아래로 내리는 것.** 탑을 올려다보다 내려오고, 떨어지는
    # 인물을 따라가고, 방 안을 위에서 아래로 훑는 컷들이 그것이다.
    #
    # 어디가 그 자리인지는 이미 계산해 둔 값에 다 들어 있다:
    #   · 앞 컷의 여백이 0        같은 동작의 다음 찰나라 시간이 거의 안 흘렀다
    #   · zone 이 같다            무대가 바뀌지 않았다
    #   · 둘 다 sd 가 아니다      데포르메 컷은 배경이 파스텔로 빠져 이어질 것이 없다
    #   · 뒤 컷이 bleed 가 아니다 통컷은 혼자 화면을 먹는 카드다
    #
    # 값은 **뒤 컷**에 붙는다 — "이 컷은 앞 컷에서 이어진다"는 뜻이다.
    # 여기서 정하는 것은 자리뿐이고, 그 자리를 실제로 이어 그릴지는 웹툰 하네스가
    # config 로 켠다(기본 꺼짐). 그래서 이 값이 생겨도 예전 run 의 그림은 그대로다.
    zones = [str(c.get("zone") or "").strip() for c in cuts]
    for i in range(n):
        cuts[i]["vertical_link"] = bool(
            i > 0
            and gaps[i - 1] == 0
            and zones[i] and zones[i] == zones[i - 1]
            and renders[i] != "sd" and renders[i - 1] != "sd"
            and renders[i] not in ("bleed", "float"))

    # ---- weight — 이 컷이 지면을 얼마나 먹는가 -----------------------------
    # 지금까지 컷은 전부 캔버스 하나를 통째로 썼다. 그런데 실제 웹툰에서 컷의
    # 무게는 균일하지 않다 — 스쳐 가는 리액션과 판이 뒤집히는 컷이 같은 지면을
    # 먹을 이유가 없다. "한 컷 한 컷이 다 의미 있는 컷"은 만화 페이지의 전제이지
    # 세로 스크롤의 전제가 아니다.
    #
    # 무게는 **모델이 정하지 않는다.** 축을 하나 더 주면 온도 0.9 에서 조건이
    # 서로 얽힌다(위 gate_layout 주석의 실패 이력). 모델은 "이 컷이 무엇인가"
    # (render_style·size)만 정하고, 무게는 거기서 나오는 산수다:
    #
    #   full   통컷이거나 화면을 꽉 채우는 컷. 캔버스 하나를 혼자 쓴다.
    #   light  떠 있는 컷. 배경이 없어서 옆 컷과 나눌 배경도 없다 —
    #          그래서 여럿이 한 캔버스를 나눠 써도 격자가 생기지 않는다.
    #   normal 나머지.
    #
    # 이 값을 실제로 쓸지는 웹툰 하네스가 config 로 켠다(기본 꺼짐). 그래서 값이
    # 생겨도 예전 run 의 묶기와 그림은 그대로다.
    # 2026-08-27: **narrative_weight 가 있으면 그것이 정한다.** 위 세 줄은
    # 화면(size·render_style)에서 무게를 유추하던 것이라, 화면이 평범하면서
    # 서사적으로 중요한 컷을 표현할 수 없었다(NARRATIVE_WEIGHTS 주석 참고).
    #
    # 8단계가 아직 안 돈 시점(7단계 직후)이나 그 필드가 없는 옛 콘티는 예전
    # 규칙 그대로 간다 — 옛 run 을 다시 돌려도 무게가 안 바뀐다. 8단계가 값을
    # 채우면 apply_narrative_weights() 가 이 값을 덮어쓴다.
    for i in range(n):
        narrative = str(cuts[i].get("narrative_weight") or "").strip().lower()
        if narrative in WEIGHT_BY_NARRATIVE:
            weight = WEIGHT_BY_NARRATIVE[narrative]
        elif renders[i] == "bleed" or sizes[i] == "impact":
            weight = "full"
        elif renders[i] == "float":
            weight = "light"
        else:
            weight = "normal"
        cuts[i]["weight"] = weight

    return notes


def layout_violations(cuts: list) -> list:
    """derive_layout 이 만든 배치가 규칙대로인지 — 코드가 코드를 검사한다.

    모델을 되돌리는 게이트가 아니다. 여기서 뭔가 나오면 derive_layout 의 버그다.
    """
    bad = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return bad
    n = len(cuts)
    beats = [str(c.get("beat") or "").strip().lower() for c in cuts]
    gaps = [c.get("gap_after") for c in cuts]
    breaks = [bool(c.get("scene_break")) for c in cuts]
    gazes = [str(c.get("gaze") or "") for c in cuts]
    num = [c.get("cut_number") for c in cuts]

    for i, g in enumerate(gaps):
        if not isinstance(g, int) or isinstance(g, bool) or not 0 <= g <= MAX_GAP:
            bad.append(f"컷 {num[i]} 의 gap_after 가 {g} 입니다 (0~{MAX_GAP}).")
    if bad:
        return bad

    head = gaps[:-1] if n > 1 else gaps
    for i, beat in enumerate(beats):
        if beat == "turn" and i > 0 and gaps[i - 1] < 2:
            bad.append(f"컷 {num[i]} 이 turn 인데 직전 여백이 {gaps[i - 1]} 입니다.")
    if n >= 3:
        if len(set(head)) < MIN_GAP_KINDS:
            bad.append(f"여백이 {sorted(set(head))} 뿐입니다 ({MIN_GAP_KINDS}종 이상).")
        if 0 not in head:
            bad.append("몰아치는 자리(여백 0)가 하나도 없습니다.")
        n_long = head.count(MAX_GAP)
        if not MIN_LONG_GAPS <= n_long <= MAX_LONG_GAPS:
            bad.append(f"여백 {MAX_GAP} 이 {n_long}회입니다 "
                       f"({MIN_LONG_GAPS}~{MAX_LONG_GAPS}회).")

    if not breaks[-1]:
        bad.append(f"마지막 컷 {num[-1]} 에 화면 경계가 없습니다.")
    for start, end, span in scene_spans(breaks):
        if span > SCENE_MAX or (span < SCENE_MIN and end != n - 1):
            bad.append(f"컷 {num[start]}~{num[end]} 이 한 Scene 에 {span}개입니다 "
                       f"({SCENE_MIN}~{SCENE_MAX}).")

    if n >= 4 and not any(g in ("down", "toward-next") for g in gazes):
        bad.append("시선을 아래로 흘리는 컷이 하나도 없습니다.")
    return bad


def apply_layout(cuts: list, scenes=None) -> list:
    """derive_layout + 자기검사. 규칙을 못 지켰으면 코드 버그이므로 즉시 세운다."""
    notes = derive_layout(cuts, scenes)
    violations = layout_violations(cuts)
    assert not violations, (
        "derive_layout 이 규칙을 어겼습니다 (코드 버그): " + " / ".join(violations))
    return notes


def setting_block(episode: dict) -> str:
    """이 화의 무대를 7단계 프롬프트에 그대로 박아 넣는다.

    episode_json 안에 이미 들어 있는데 왜 따로 뽑는가: 회차 JSON 은 질문·엔진·
    연속성으로 길고, 그 안에 묻힌 setting 은 모델이 서사 재료로만 읽고 넘어간다.
    무대는 서사가 아니라 **그릴 대상 목록**이라서 따로 세워 두어야 한다.
    사물 목록이 특히 그렇다 — 이 목록이 인서트 컷의 재료다.
    """
    setting = episode.get("setting")
    if not isinstance(setting, dict):
        return ("(회차에 무대가 적혀 있지 않습니다. 담당 화 summary 에서 장소와 "
                "사물을 읽어 내어 쓰되, 없는 것을 새로 만들지는 마세요.)")

    props = setting.get("props")
    props = [str(p).strip() for p in props if str(p or "").strip()] \
        if isinstance(props, list) else []

    lines = [
        f"  장소  : {_fmt(setting.get('place'))}",
        f"  시간대: {_fmt(setting.get('time'))}",
        f"  날씨  : {_fmt(setting.get('weather'))}",
        f"  광원  : {_fmt(setting.get('light'))}",
        f"  동선  : {_fmt(setting.get('movement'))}",
    ]
    if props:
        lines.append(f"  사물  : {' · '.join(props)}")
        lines.append("")
        lines.append("  ★ 위 사물은 **화면에 실제로 있는 것**입니다. 인물 없이 사물만 "
                     "나오는 컷(인서트)은 여기서 고릅니다.")
    lines.append("")
    lines.append("  ★ 시간대·날씨·광원은 **이 화 내내 이어집니다.** 컷마다 다시 "
                 "정하지 말고, 바뀌는 컷이 있으면 그 컷에만 적으세요 — "
                 "그림은 컷을 한 장씩 따로 그려서, 안 적으면 매번 달라집니다.")
    return "\n".join(lines)


def zones_block(state: "SeriesState", episode: dict) -> str:
    """이미 등록된 존을 7단계 프롬프트에 그대로 박아 넣는다.

    setting_block 과 같은 이유로 따로 세운다 — 존은 series.json 에만 쌓이고
    episode_json 안에는 없으므로, 여기서 넘겨주지 않으면 7단계는 존이 이미
    있다는 사실 자체를 모른다. place 로 걸러 이 화와 무관한 다른 장소의
    존까지 나열하지 않는다.

    이미 있는 존은 **배경이 이미 그려져 있다**는 뜻이다. 그래서 서술을 다시
    적게 하지 않고 그때 적힌 것을 보여 준다 — 다시 적으면 조금씩 달라지고,
    그 순간 같은 자리가 두 개의 배경을 갖게 된다.
    """
    setting = episode.get("setting")
    place = str((setting or {}).get("place") or "").strip()
    zones = [z for z in state.zones if not place or z.get("place") == place]

    if not zones:
        return ("  (이 장소의 존이 아직 없습니다. 이 화에서 쓰는 구역마다 새로 "
                "지으세요 — 짧은 kebab-case id 로. 예: z-hallway-sofa)")

    lines = ["  이미 있는 존 — 같은 구역이면 이 id 를 그대로 쓰고, 아래 서술을",
             "  다시 적지 마세요 (zones 출력에서 빼면 됩니다):"]
    for z in zones:
        lines.append(f"    · {z['zone_id']} — {z['label']}")
    return "\n".join(lines)


def fired_list(episode: dict) -> str:
    """이 화에서 발동하는 엔진 요소를 프롬프트에 그대로 박아 넣는다.

    예전에는 engine_cut_refs 의 element 를 모델이 알아서 쓰게 두었는데, 온도 0.9 에서
    **출력 예시의 값을 그대로 베끼는** 실패가 반복됐다 — engine_fired 가 ["irony"] 인
    화에서 예시의 {"element": "rule"} 을 옮겨 적고 세 번 연속 게이트에 걸렸다.
    5단계 유령 id 와 같은 실패다. 그래서 쓸 값을 코드가 알려 준다.
    """
    fired = episode.get("engine_fired")
    fired = [normalize_source(x) for x in fired] if isinstance(fired, list) else []
    fired = [f for f in fired if f in ("rule", "cost", "irony")]
    if not fired:
        return "(없음 — engine_cut_refs 는 빈 배열로 둔다)"
    return " · ".join(fired)


def solve_cuts(ps: PromptSet, call, card: str, arc_json: str, episode: dict,
               ledger_snapshot: str, irony_present: bool, absolute: int,
               max_retries: int, spent: int = 0, known: set = None,
               series_arc: str = "", zones_txt: str = "",
               known_zones: set = None, personality: str = "",
               author_note: str = "", memory_text: str = "") -> tuple:
    """(게이트를 통과하고 연출이 계산된 payload, 재시도 횟수, 메모). 못 하면 Stopped.

    run_webtoon 의 7단계와 --cuts-only 가 같은 것을 쓰게 하려고 밖에 둔다.
    나중에 다시 뽑은 컷과 파이프라인이 만든 컷이 다르면 비교가 성립하지 않는다.

    spent 는 **화 하나 안에서** 이미 쓴 재시도다. 실행 전체의 누적을 넘기면
    앞 화가 예산을 다 써 버려 뒤 화가 한 번도 못 고친다 (run_webtoon 의 주석 참조).

    순서가 중요하다. 게이트는 **모델이 낸 것**(beat·size)만 보고, 통과한 뒤에
    코드가 여백·경계·시선을 계산해 붙인다. 계산 결과로 모델을 되돌리지 않는다.
    """
    feedback, regens = "", 0
    # 이번 화 서술에 등장하는 태그와 겹치는 연출 지식만 골라 붙인다 — 액션 장면이
    # 아니면 액션 연출 지식은 안 붙는다 (resolve_directing_notes 문서 참고).
    directing_notes = resolve_directing_notes(
        card, json.dumps(episode, ensure_ascii=False))
    while True:
        payload = call(
            "W7", f"{absolute}화 컷 분해",
            render(ps.texts["w7"], {
                "engine_card": card,
                "series_arc": series_arc or "(큰 줄거리가 넘어오지 않았습니다)",
                "arc_json": arc_json,
                "episode_json": json.dumps(episode, ensure_ascii=False, separators=(",", ":")),
                "ledger_snapshot": ledger_snapshot,
                "engine_fired_list": fired_list(episode),
                "setting_block": setting_block(episode),
                "zones_block": zones_txt or "(등록된 존이 없습니다)",
                "directing_notes": directing_notes or "(해당 없음)",
                "user_memory": memory_text,
                "retry_feedback": feedback_slot(author_note, feedback),
            }),
            TEMP_CREATIVE,
            lambda o: cuts_brief(o.get("cuts") or []))

        # 게이트를 보기 **전에** 코드가 고칠 수 있는 것은 고친다. size 4연속은
        # 산수라서 되돌릴 이유가 없다 — 되돌리면 모델이 size 를 고치다 beat 를
        # 깨뜨리는 두더지잡기가 된다 (실제로 여섯 번 연속 그렇게 실패했다).
        cuts = payload.get("cuts") or []
        # 말을 먼저 정돈한다. 게이트도 표도 그림 단계도 speech_lines() 를 거치는데,
        # 옛 칸을 함께 맞춰 두지 않으면 lines 를 쓴 화에서 옛 칸만 보는 코드가
        # 빈 대사를 본다.
        repaired = repair_speech(cuts)
        repaired += repair_stinger_number(payload)
        # size · render_style · tone 을 코드가 덮어쓰던 자리다. 지금은 **안
        # 덮어쓰고 경고만 한다** (REPAIR_DIRECTING=1 로 예전 동작을 되살릴 수 있다).
        #
        # 덮어쓰기를 끈 이유: 저것들은 모델이 컷 내용을 보고 정한 값인데, 코드는
        # 내용을 모르고 숫자만 본다. "마지막 컷 size 를 normal -> tall 로 바꿨습니다"
        # 같은 수리는 스팅어를 세로로 늘려 놓지만, 그 컷이 왜 납작해야 하는지는
        # 모델만 안다. 결과를 보존하고 사람이 판단하게 둔다.
        if env_bool("REPAIR_DIRECTING", False):
            repaired += repair_sizes(cuts) + repair_render_styles(cuts)
            repaired += repair_tone_lock(cuts, payload.get("scenes"))
        else:
            directing_notes = (dry_repair_sizes(cuts) + dry_repair_render_styles(cuts)
                               + dry_repair_tone_lock(cuts, payload.get("scenes")))

        failures = gate_cuts(payload, episode, irony_present, known_zones, known)
        if not failures:
            notes = [f"코드 수리: {x}" for x in repaired]
            if not env_bool("REPAIR_DIRECTING", False):
                notes += [f"연출 경고: {x}" for x in directing_notes]
            notes += [f"연출 메모: {x}" for x in apply_layout(
                cuts, payload.get("scenes"))]
            # 얼굴 비율·앵글·impact·연속 길이 — 예전에는 여기서 생성을 막았다.
            notes += [f"연출 경고: {x}" for x in directing_warnings(cuts)]
            notes += [f"서술 경고: {x}" for x in prose_warnings(cuts, known)]
            notes += [f"이름 경고: {x}" for x in prop_text_name_check(cuts, known)]
            notes += [f"톤 메모: {x}" for x in tone_warnings(
                cuts, payload.get("scenes"), personality)]
            notes += [f"각도 메모: {x}" for x in facing_warnings(cuts)]
            notes += [f"공간 메모: {x}" for x in zone_warnings(cuts)]
            return payload, regens, notes
        log(f"  {absolute}화 7단계 게이트 실패 {len(failures)}건")
        for f in failures:
            log(f"      - {f}")
        if spent + regens >= max_retries:
            # 마지막 시도를 버리지 않는다 — 게이트에 걸린 초안이라도 사람이
            # 봐야 진행할지 다시 만들지 고를 수 있다.
            raise Stopped(
                f"{absolute}화 컷 게이트 재시도 소진: " + " / ".join(failures),
                STATUS_HUMAN, draft=payload)
        regens += 1

        # 코드가 되돌린 것을 먼저 알린다. 강등은 조용히 일어나므로, 말해 주지
        # 않으면 모델은 다음 시도에서 같은 자리에 sd 를 다시 놓는다.
        feedback = "\n".join(f"- (코드가 되돌림) {x}" for x in repaired)
        if feedback:
            feedback += "\n"
        # "4연속입니다" 라고만 말하면 모델은 그 자리만 고치고 다른 곳을 깨뜨린다.
        # 고쳐 놓은 수열을 통째로 줘서 베끼게 한다.
        feedback += "\n".join(f"- {f}" for f in failures)
        if any("리듬이 아니라 평지" in f for f in failures):
            fixed = suggest_beat_sequence(cuts)
            if fixed:
                feedback += (
                    "\n\n- beat 를 스스로 고치지 말고 **아래 수열을 그대로** 쓰세요. "
                    "4연속이 없도록 이미 고쳐 둔 것입니다. i 번째 컷의 beat 는 "
                    "i 번째 낱말입니다. 컷 서술은 그 beat 에 맞게 다듬으세요.\n"
                    f"  beat_sequence: {fixed}")


# --------------------------------------------------------------------- 8단계
#
# 컷이 확정된 뒤에 **글자만** 다시 쓴다.
#
# 왜 따로 부르나 — 7단계는 이미 화 전체를 한 번에 보고 있다. 그러니 "전부 보게
# 하려고" 나눈 것이 아니다. 나눈 이유는 셋이다:
#
#   1. 7단계는 컷을 하나씩 쓰면서 그 컷에 어울리는 말을 붙인다. 그러면 말이
#      그림의 설명이 되고 앞뒤가 안 이어진다 — "여기 앉으려고?" / "커피
#      마실래요?" 가 그렇게 나왔다. 글자만 놓고 처음부터 다시 쓰면 컷4의 말이
#      컷3을 받는다.
#   2. 재시도 예산이 섞이지 않는다. 지금까지는 대사가 어색해서 되돌리면 카메라
#      분포와 리듬까지 같이 다시 뽑혔다 — 어렵게 통과한 것을 대사 때문에 버렸다.
#   3. 그림을 다시 뽑지 않고 대사를 고칠 수 있다. **단 이건 글자가 이미지에
#      구워지지 않을 때만 성립한다** (webtoon-harness 의 scene.lettering:
#      overlay). in_image 로 두면 이 이점은 없다.
#
# 바꿀 수 있는 것은 글자와 그 자리(bubble_zone)뿐이다. description·카메라·
# characters_in_frame 은 손대지 못한다 — 그림 지시는 이미 확정된 것이다.

TEXT_PATCH_FIELDS = ("lines", "speaker", "speaker_side", "dialogue", "narration",
                     "thought", "sfx", "screen_text", "bubble_zone")


def pov_of(card: str) -> str:
    """이 화의 시점 인물 — 엔진 카드의 [주인공].

    화마다 바꿀 수 있게 만들지 않았다. POV 가 흔들리는 것은 연출이 아니라
    대개 사고이고, 지금 이 파이프라인에는 "이 화는 누구 시점인가" 를 정하는
    자리가 없다. 필요해지면 5단계 출력에 칸을 만드는 것이 맞다.
    """
    for line in str(card or "").splitlines():
        line = line.strip()
        if line.startswith("[주인공]"):
            return line[len("[주인공]"):].strip()
    return ""


def apply_text_patch(cuts: list, patch: list) -> list:
    """8단계가 낸 글자를 컷에 얹는다. 돌려주는 것은 **적용하지 못한 것**의 메모.

    적힌 컷만 바꾼다. 빠진 컷은 7단계의 글자가 그대로 남는다 — 프롬프트는
    전부 적으라고 하지만, 빠졌다고 그 컷의 글자를 지우면 모델의 실수 하나가
    화의 일부를 통째로 무음으로 만든다. 조용히 지우는 것보다 남기는 편이 낫다.

    **lines 는 문자열이 아니다.** 예전에는 TEXT_PATCH_FIELDS 를 통째로 돌면서
    str() 로 찍어 넣어서, 모델이 lines 를 배열로 주면 "[{'kind': ...}]" 라는
    문자열이 저장됐다 — speech_lines() 가 list 가 아닌 것을 무시해서 화면은
    멀쩡했지만 컷 파일에는 쓰레기가 남았다. 여기서 종류를 갈라 받는다.
    """
    notes = []
    by_num = {c.get("cut_number"): c for c in cuts if isinstance(c, dict)}
    touched = set()
    for item in patch or []:
        if not isinstance(item, dict):
            continue
        num = item.get("cut_number")
        cut = by_num.get(num)
        if cut is None:
            notes.append(f"8단계가 없는 컷 {num} 을 가리켰습니다 — 건너뜁니다.")
            continue
        touched.add(num)
        gave_lines = False
        for key in TEXT_PATCH_FIELDS:
            if key not in item:
                continue
            if key == "lines":
                # 모델이 직접 줄 구조를 다시 짠 경우(말풍선 나누기)만 여기 온다.
                if isinstance(item[key], list):
                    cut[key] = item[key]
                    gave_lines = True
                else:
                    notes.append(f"컷 {num} 의 lines 가 배열이 아니라 "
                                 "무시했습니다 — 옛 칸의 글자를 씁니다.")
                continue
            cut[key] = str(item.get(key) or "").strip()
        # 모델이 lines 를 안 줬으면(지금까지의 전부) 옛 칸의 글자를 lines 에
        # 되쓴다. 이걸 안 하면 8단계 결과가 저장만 되고 화면·그림에는 7단계
        # 초안이 나간다 — sync_lines_from_legacy 의 설명 참고.
        if gave_lines:
            sync_legacy_speech(cut)     # 반대로 옛 칸을 새 lines 에 맞춘다
        else:
            sync_lines_from_legacy(cut)
    missing = [n for n in by_num if n not in touched]
    if missing:
        notes.append(f"8단계가 컷 {sorted(missing)} 을 적지 않았습니다 — "
                     "그 컷은 7단계의 글자를 그대로 둡니다.")
    return notes


def apply_narrative_weights(cuts: list, rows: list) -> list:
    """8단계가 판정한 서사적 중요도를 컷에 얹는다. 돌려주는 것은 메모다.

    **text_patch 와 따로 받는다.** 같은 배열에 섞으면 대사 한 줄이 잘못 왔을 때
    중요도까지 같이 떨어지고, 무엇이 왜 바뀌었는지도 안 보인다 — 실제로 lines 를
    text_patch 에 섞어 두었다가 8단계 결과가 통째로 버려진 적이 있다.

    적힌 컷만 바꾼다. 빠진 컷은 derive_layout 이 계산해 둔 무게를 그대로 쓴다 —
    조용히 normal 로 미는 것보다 낫다. 값이 이상하면 그 컷만 버린다: 여기서
    화를 세우면 8단계가 실패해도 화는 성립한다는 이 단계의 전제가 깨진다.
    """
    notes = []
    by_num = {c.get("cut_number"): c for c in cuts if isinstance(c, dict)}
    seen = set()
    for row in rows or []:
        if not isinstance(row, dict):
            continue
        num = row.get("cut_number")
        cut = by_num.get(num)
        if cut is None:
            notes.append(f"8단계가 없는 컷 {num} 의 중요도를 적었습니다 — 건너뜁니다.")
            continue
        value = str(row.get("weight") or row.get("narrative_weight") or "").strip().lower()
        if value not in NARRATIVE_WEIGHTS:
            notes.append(f"컷 {num} 의 중요도 '{row.get('weight')}' 를 모릅니다 "
                         f"({' | '.join(NARRATIVE_WEIGHTS)} 중 하나여야 합니다) — "
                         "그 컷은 7단계가 계산한 무게를 그대로 씁니다.")
            continue
        cut["narrative_weight"] = value
        cut["weight"] = WEIGHT_BY_NARRATIVE[value]
        seen.add(num)
    missing = [n for n in by_num if n not in seen]
    if missing:
        notes.append(f"8단계가 컷 {sorted(missing)} 의 중요도를 안 적었습니다 — "
                     "그 컷은 7단계가 계산한 무게를 그대로 둡니다.")
    # 남발 감시. 막지는 않는다(개수 상한을 두지 않기로 했다) — 절반을 넘으면
    # "전부 중요하면 아무것도 중요하지 않다" 이므로 사람이 볼 수 있게 남긴다.
    majors = [n for n, c in by_num.items()
              if str(c.get("narrative_weight") or "").lower() == "major"]
    if by_num and len(majors) * 2 > len(by_num):
        notes.append(f"중요(major) 컷이 {len(majors)}/{len(by_num)} 개입니다 — "
                     "절반을 넘었습니다. 전부 중요하면 아무것도 중요하지 않습니다.")
    return notes


def gate_pages(pages: list, cuts: list) -> list:
    """9단계가 낸 화면 묶음이 **성립하는가**. 무결성과 물리 제약만 본다.

    "이 묶음이 좋은 편집인가" 는 여기서 안 본다 — 그건 사람이 읽어야 아는 것이고,
    9단계가 있는 이유가 바로 그 판단이다. 여기서 막는 것은 **틀리면 그림이 안
    나오는 것**과, 연출 취향이 아니라 물리적 사실인 둘뿐이다:

      · bleed 는 테두리 없이 지면 끝까지 흘러넘치는 컷이라 같은 화면의 다른 컷을
        덮는다.
      · minor 만으로 된 화면은 스쳐 갈 컷이 화면 하나를 통째로 먹는다.

    개수·순서·중요도 분포는 안 본다. 그런 규칙을 여기 넣는 순간 9단계가 그
    규칙의 대리인이 되고, "A 이면 무조건 B" 를 없애려고 만든 단계가 다시 그것이
    된다.
    """
    failures = []
    if not isinstance(pages, list) or not pages:
        return ["9단계가 화면 묶음(pages)을 내지 않았습니다."]

    by_num = {c.get("cut_number"): c for c in cuts if isinstance(c, dict)}
    want = sorted(by_num)
    seen: list = []

    for i, page in enumerate(pages, 1):
        if not isinstance(page, dict):
            failures.append(f"{i}번째 화면이 객체가 아닙니다.")
            continue
        nums = page.get("cuts")
        if not isinstance(nums, list) or not nums:
            failures.append(f"{i}번째 화면에 컷이 없습니다.")
            continue
        nums = [n for n in nums if isinstance(n, int)]
        seen += nums
        if nums != sorted(nums):
            failures.append(f"{i}번째 화면의 컷 순서가 뒤바뀌었습니다: {nums} — "
                            "읽는 순서가 곧 컷 번호 순서입니다.")
        base = page.get("base")
        if base not in nums:
            failures.append(f"{i}번째 화면의 base 가 {base} 인데 그 화면의 컷"
                            f"{nums} 에 없습니다.")

        rows = [by_num[n] for n in nums if n in by_num]
        bleeds = [n for n, c in zip(nums, rows)
                  if str(c.get("render_style") or "").strip().lower() == "bleed"]
        if bleeds and len(nums) > 1:
            failures.append(
                f"{i}번째 화면에 통컷(bleed) 컷 {bleeds} 이 다른 컷과 같이 있습니다 "
                f"({nums}). 통컷은 테두리 없이 지면 끝까지 흘러넘쳐서 같은 화면의 "
                "다른 컷을 덮습니다 — 혼자 두세요.")
        if rows and all(str(c.get("narrative_weight") or "").strip().lower() == "minor"
                        for c in rows):
            failures.append(
                f"{i}번째 화면이 가벼운(minor) 컷 {nums} 만으로 되어 있습니다. "
                "스쳐 갈 컷이 화면 하나를 통째로 먹습니다 — 앞뒤 화면에 붙이세요.")

    if sorted(seen) != want:
        missing = [n for n in want if n not in seen]
        dup = sorted({n for n in seen if seen.count(n) > 1})
        detail = []
        if missing:
            detail.append(f"빠진 컷 {missing}")
        if dup:
            detail.append(f"두 번 넣은 컷 {dup}")
        failures.append("모든 컷이 정확히 한 번씩 들어가야 합니다 — "
                        + ", ".join(detail or ["번호가 컷 목록과 다릅니다"]) + ".")
    if seen != sorted(seen):
        failures.append("화면 사이에서 컷 번호가 오름차순이 아닙니다 — "
                        "독자가 읽는 순서를 바꿀 수 없습니다.")
    return failures


def undrawable_notes(out: dict, cuts: list) -> list:
    """9단계가 신고한 "그릴 수 없는 컷" 을 사람이 읽을 메모로. **막지 않는다.**

    이 신고는 콘티(7단계)를 고쳐야 하는 것이지 이 화를 세울 이유가 아니다.
    컷과 대사가 다 나와 있는데 "서술이 부족하다" 로 되돌리면 잃는 것이 더 크다.
    """
    rows = (out or {}).get("undrawable")
    if not isinstance(rows, list) or not rows:
        return []
    nums = {c.get("cut_number") for c in cuts if isinstance(c, dict)}
    notes = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        n = row.get("cut_number")
        if n not in nums:
            continue                       # 없는 컷을 가리킨 신고는 버린다
        what = str(row.get("what") or "").strip()
        fix = str(row.get("fix") or "").strip()
        if not what:
            continue
        notes.append(f"그리기 어려운 컷 {n}: {what}"
                     + (f" → {fix}" if fix else ""))
    return notes


def solve_pages(ps: PromptSet, call, card: str, episode: dict, cuts: list,
                absolute: int, max_retries: int, author_note: str = "") -> tuple:
    """9단계 — 확정된 컷을 화면 단위로 묶는다. (pages, 메모)

    8단계와 같은 태도다: **막히면 중단하지 않는다.** 여기서 세우면 컷과 대사가
    다 나와 있는데 화가 통째로 버려진다. 재시도를 다 쓰면 pages 를 비워 돌려주고,
    받는 쪽(웹툰 하네스)은 예전처럼 자기 규칙으로 묶는다 — 9단계는 더 나은 묶음을
    주는 자리이지, 없으면 안 되는 자리가 아니다.
    """
    feedback, notes = "", []
    for attempt in range(max_retries + 1):
        out = call(
            "W9", f"{absolute}화 화면 묶기",
            render(ps.texts["w9"], {
                "engine_card": card,
                "episode_json": json.dumps(episode, ensure_ascii=False,
                                           separators=(",", ":")),
                "cuts_json": json.dumps(cuts, ensure_ascii=False,
                                        separators=(",", ":")),
                "retry_feedback": feedback_slot(author_note, feedback),
            }),
            TEMP_CREATIVE)
        pages = (out or {}).get("pages")
        failures = gate_pages(pages, cuts)
        if not failures:
            sizes = [len(p["cuts"]) for p in pages]
            notes.append(f"화면 {len(pages)}장 ({'+'.join(str(s) for s in sizes)}컷), "
                         f"바탕 컷 {[p.get('base') for p in pages]}")
            # 그릴 수 없는 컷 신고 — **경고만 한다.** 화를 세우지 않는다:
            # 9단계의 일은 화면 묶기이고 이것은 곁일이라, 신고 때문에 묶음이
            # 되돌려지면 책임이 섞인다. 사람이 보고 콘티를 고칠 자리다.
            notes += undrawable_notes(out, cuts)
            return pages, notes
        log(f"  {absolute}화 9단계 게이트 실패 {len(failures)}건")
        for f in failures:
            log(f"      - {f}")
        if attempt >= max_retries:
            warn(f"  {absolute}화 9단계 재시도 소진 — 화면 묶기는 하네스 규칙으로 "
                 "돌아갑니다.")
            return [], [f"화면 묶기 실패: {f}" for f in failures]
        feedback = "\n".join(f"- {f}" for f in failures)
    return [], notes


def repair_bubble_zone(cuts: list) -> list:
    """글자와 자리가 어긋난 것만 맞춘다. 8단계가 빠뜨렸을 때의 안전망이다.

    글자가 생겼는데 자리가 none 이면 말풍선이 얼굴을 덮고, 글자가 없는데 자리가
    남아 있으면 구도만 비운다. 어느 쪽이든 되돌릴 이유는 없다 — 산수다.

    글자가 있는데 자리가 없을 때 top 을 쓰는 것은 **어림값**이다. 어디가 비어
    있는지는 그림을 봐야 아는 것이라 코드가 알 수 없다. 그래서 이건 최후의
    보정이고, 제대로 된 값은 8단계가 description 을 읽고 골라야 한다.
    """
    notes = []
    for c in cuts:
        if not isinstance(c, dict):
            continue
        has_text = any(not is_blank(c.get(k)) for k in BUBBLE_FIELDS)
        zone = str(c.get("bubble_zone") or "none").strip().lower()
        if has_text and zone == "none":
            c["bubble_zone"] = "top"
            notes.append(f"컷 {c.get('cut_number')} 에 글자가 있는데 "
                         "bubble_zone 이 none 이라 top 으로 두었습니다 "
                         "(어디가 비었는지는 그림을 봐야 압니다).")
        elif not has_text and zone != "none":
            c["bubble_zone"] = "none"
            notes.append(f"컷 {c.get('cut_number')} 에 글자가 없어 bubble_zone 을 "
                         "none 으로 되돌렸습니다.")
    return notes


def gate_text_pass(cuts: list, pov: str) -> list:
    """8.5단계 — 글자가 규칙을 지켰는가. **전부 필드 대조다.**

    "대사가 자연스러운가" 는 여기서 안 본다. 그건 사람이 읽어야 아는 것이고,
    LLM 판정을 넣으면 비용과 흔들림이 같이 들어온다. 여기서 막는 것은 **지키지
    않으면 이야기가 망가지는 것**들뿐이다.
    """
    failures = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    pov = str(pov or "").strip()

    for c in cuts:
        n = c.get("cut_number")
        line = str(c.get("dialogue") or "")
        hit = [w for w in BANNED_IN_DIALOGUE if w in line]
        if hit:
            failures.append(
                f"컷 {n} 의 대사에 세계관 낱말({', '.join(hit)})이 있습니다: "
                f"\"{line}\" — 인물이 설정을 소리 내어 말하면 독자에게 하는 "
                "설명이 됩니다. 나레이션이나 화면 글자로 옮기고, 대사는 그 사람이 "
                "실제로 할 말로 바꾸세요.")

        # 화면에 없는 사람이 말하고 있는가. 화면 밖 목소리(offscreen)는 웹툰의
        # 정상 문법이라 막지 않는다 — 막는 것은 **그렇게 표시하지 않은 것**이다.
        # side 가 left/right/center 면 그리는 쪽은 화면 안에서 화자를 찾고,
        # 그 컷에 그 사람이 없으면 아무에게나 꼬리를 붙이거나 인물을 만들어낸다.
        # 실측(20260826T202930 컷2): characters_in_frame 이 [한리아, 빌런] 인데
        # 서도윤이 side=left 로 말해서, 화면에 없는 인물의 대사가 엉뚱한 사람에게
        # 붙었다.
        here = {str(x or "").strip() for x in (c.get("characters_in_frame") or [])
                if str(x or "").strip()}
        if here:
            legacy_side = str(c.get("speaker_side") or "").strip().lower()
            for row in speech_lines(c):
                if row["kind"] == "narration":
                    continue          # 나레이션은 화자가 없다
                talker = row["speaker"]
                side = (row["side"] or legacy_side)
                if not talker or talker in here or side == "offscreen":
                    continue
                failures.append(
                    f"컷 {n} 에서 「{talker}」 가 말하는데 그 컷의 화면에는 "
                    f"{sorted(here)} 만 있습니다. 화면에 없는 사람이 화면 안에서 "
                    "말할 수는 없습니다 — 그 컷에 있는 사람에게 대사를 주거나, "
                    "나레이션으로 옮기거나, 화면 밖 목소리라면 그 줄의 side 를 "
                    "offscreen 으로 적으세요.")

        if is_blank(c.get("thought")):
            continue
        who = str(c.get("speaker") or "").strip()
        # POV 잠금. 다른 인물의 속마음을 보여 주면 "저 사람은 왜 저러는가" 가
        # 그 자리에서 닫힌다 — 질문 장부가 통째로 무너지는 자리다.
        if pov and who and who != pov:
            failures.append(
                f"컷 {n} 의 속마음이 「{who}」 의 것입니다. 이 화의 시점은 "
                f"「{pov}」 이고, 다른 인물의 머릿속은 이 화에 들어가지 않습니다 — "
                "그 사람의 속은 행동과 대사로만 보여야 열린 질문이 유지됩니다.")
        here = c.get("characters_in_frame")
        here = {str(x or "").strip() for x in here} if isinstance(here, list) else set()
        if who and who not in here:
            failures.append(
                f"컷 {n} 의 속마음은 「{who}」 의 것인데 그 사람이 화면에 "
                "없습니다. 화면에 없는 사람의 속마음은 나레이션으로 옮기세요.")
    return failures


# ---- 나레이션이 한 가지 일만 하고 있는가 --------------------------------
#
# 나레이션은 이 화가 세계를 설명할 수 있는 **유일한 칸**이다. 고유명사·제도·역사·
# 인물의 처지는 그림으로 못 그리고, 대사로 말하면 인물이 독자에게 설정을 읽어
# 주는 꼴이 된다(그래서 gate_text_pass 가 대사에서 그걸 막는다). 막기만 하고
# 갈 곳을 안 열어 주면 세계관은 어디에도 안 나온다.
#
# 실측된 실패는 "나레이션이 없다"보다 "나레이션이 전부 같은 일을 한다"였다 —
# 나온 셋이 전부 "늦은 오후, 라운지 한켠" 꼴의 시간·장소 표시였다. 그래서 여기서
# 세는 것은 개수가 아니라 **종류**다.
_NARR_TIME = re.compile(
    r"(아침|점심|낮|오후|저녁|밤|새벽|정오|자정|해질녘|땅거미|어스름|노을"
    r"|다음\s*날|이튿날|며칠|사흘|이틀|하루|한참|잠시|그날|그때쯤|늦은|이른"
    r"|\d+\s*(시|분|초|교시|일|주|주일|달|개월|년|시간)(\s*(뒤|후|째|만에))?)")
_NARR_PLACE = re.compile(
    r"(에서|에는|한켠|한편|모퉁이|뒤편|근처|골목|복도|교실|강의실|운동장"
    r"|옥상|정문|계단|로비|라운지|동아리방|기숙사|사무실|주차장|\d+\s*층)")
# ① 은 **문장이 아니라 표찰**이다. "늦은 오후, 라운지 한켠." · "사흘 뒤" ·
# "학생회관 3층" — 전부 명사로 끝난다. 반대로 ②~⑤ 는 서술이라 '다' 로 끝난다:
#   "엘젠하르트 제국. 황후는 열여섯에 정해진다."   ← ②
#   "나는 그때 이미 알고 있었다."                  ← ③
#   "윤재는 1학년 때부터 시하를 따라다녔다."        ← ⑤
# 그래서 낱말 목록으로 뜻을 맞히려 하지 않고 **끝맺음**으로 가른다. 낱말로
# 가르려던 첫 판은 "게이트가 열린 지 20년. 등급은 국가가 매긴다."(②) 를 '20년'
# 때문에 ① 로 세었다 — 세계관을 알려 주는 줄을 시간 표시로 오해한 것이다.
_NARR_SENTENCE_END = re.compile(r"다[.!?…\s\"'”’)]*$")


def narration_kinds(cuts: list) -> dict:
    """나레이션을 ①(시간·장소 표찰) 과 그 밖(②~⑤ 서술)으로 나눈 목록."""
    stamp, beyond = [], []
    for c in cuts:
        if not isinstance(c, dict):
            continue
        for row in speech_lines(c):
            if row["kind"] != "narration":
                continue
            text = row["text"]
            is_stamp = (bool(_NARR_TIME.search(text) or _NARR_PLACE.search(text))
                        and not _NARR_SENTENCE_END.search(text))
            (stamp if is_stamp else beyond).append((c.get("cut_number"), text))
    return {"stamp": stamp, "beyond": beyond}


def narration_warnings(cuts: list) -> list:
    """세계관을 설명할 칸이 비었거나 한 가지 일만 하고 있는 자리 — 경고만 한다.

    되돌리지 않는 이유는 이 파일의 다른 advisory 들과 같다: "나레이션이 적다"는
    위반이 아니라 **선택**이고, 조용한 화가 맞는 경우가 있다. 다만 그 선택을
    했는지 실수인지는 사람이 봐야 알므로 찍어 둔다.
    """
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return out
    kinds = narration_kinds(cuts)
    stamp, beyond = kinds["stamp"], kinds["beyond"]
    total = len(stamp) + len(beyond)

    if total == 0:
        out.append(
            "이 화에 나레이션이 하나도 없습니다. 세계관·인물의 처지·지나고 나서 "
            "하는 말은 그림으로도 대사로도 갈 곳이 없어서, 이 칸이 비면 독자는 "
            "여기가 어떤 세계인지 모른 채 읽습니다 — 의도한 것이 아니면 "
            "w8.txt 5번의 ②~⑤ 중 하나를 넣으세요.")
        return out

    if total >= 2 and not beyond:
        listed = " / ".join(f'컷 {n} "{t[:24]}"' for n, t in stamp[:3])
        out.append(
            f"나레이션 {total}개가 전부 시간·장소 표시입니다 ({listed}). "
            "그 칸을 안 쓴 것과 같습니다 — 세계를 알려 주거나(②), 지나고 나서 "
            "하는 말(③)이나, 인물의 처지(⑤) 중 하나는 섞으세요.")
    return out


# ---- 말과 말이 어긋나는 자리 --------------------------------------------
#
# fact_conflicts 는 회차가 선언한 new_facts 끼리만 비교한다. 정작 독자가 읽는
# **대사·나레이션 본문**은 아무도 설정과 대조하지 않았다. 여기서 같은 두 패턴을
# 말에도 적용한다 (같은 주제인데 한쪽만 부정 / 같은 단위의 숫자가 다름).
#
# 의미 모순을 기계가 다 잡을 수는 없고, 여기는 오탐이 특히 위험하다 — 인물이
# 거짓말하거나 모르고 틀리게 말하는 것은 **모순이 아니라 연출**이기 때문이다.
# 그래서 되돌리지 않고, 문구도 "확인하세요"로 남긴다.
#
# 단위는 _NUM_UNIT 을 그대로 쓰지 않고 여기서 넓힌다. 사람을 가리키는 말에는
# 학년·기·급처럼 _NUM_UNIT 에 없는 단위가 자주 나오는데("윤재는 3학년이다"),
# 공용 _NUM_UNIT 을 고치면 fact_conflicts 가 만드는 5단계 프롬프트까지 같이
# 바뀌어 예전 회차의 입력이 달라진다. 새 검사에만 쓰는 목록을 따로 둔다.
_LINE_NUM_UNIT = re.compile(
    r"(\d+)\s*(학년|학기|기수|등급|급|층|명|화|년|살|개|번|시|분|주|달|권|회)")


def contradiction_warnings(cuts: list, facts: list = None) -> list:
    """대사·나레이션이 확정된 설정과, 또는 이 화 안에서 서로 어긋나는 자리."""
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    rows = []
    for c in cuts:
        for row in speech_lines(c):
            rows.append((c.get("cut_number"), row["kind"], row["text"]))

    def clash(a: str, b: str):
        """부딪히면 (사유, 주제) 를, 아니면 None."""
        if not _same_topic(a, b):
            return None
        shared = _content_words(a) & _content_words(b)
        topic = ", ".join(sorted(shared)[:3]) or "같은 대상"
        if _negated(a) != _negated(b):
            return ("한쪽만 부정입니다", topic)
        na = dict((u, n) for n, u in _LINE_NUM_UNIT.findall(a))
        nb = dict((u, n) for n, u in _LINE_NUM_UNIT.findall(b))
        hit = [u for u in na if u in nb and na[u] != nb[u]]
        if hit:
            u = hit[0]
            return (f"{na[u]}{u} 과(와) {nb[u]}{u} 이(가) 함께 있습니다", topic)
        return None

    # ① 확정된 설정과 대조. 설정은 앞 화에서 독자가 이미 본 것이라 이쪽이 기준이다.
    for fact in (facts or []):
        if not isinstance(fact, dict):
            continue
        ftext = str(fact.get("fact") or "").strip()
        if not ftext:
            continue
        for n, kind, text in rows:
            hit = clash(ftext, text)
            if not hit:
                continue
            why, topic = hit
            out.append(
                f"컷 {n} 의 {kind} 가 확정된 설정과 어긋나 보입니다 "
                f"({topic}) — {why}.\n"
                f"    · 설정({fact.get('first_episode', '?')}화): \"{ftext[:60]}\"\n"
                f"    · 이 화: \"{text[:60]}\"\n"
                "    설정이 기준입니다. 인물이 일부러 틀리게 말하는 것이 아니면 "
                "말을 고치세요.")

    # ② 이 화 안에서 말끼리. 8단계는 화 전체를 한꺼번에 보므로 여기서 잡을 수 있다.
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            n_a, kind_a, ta = rows[i]
            n_b, kind_b, tb = rows[j]
            hit = clash(ta, tb)
            if not hit:
                continue
            why, topic = hit
            out.append(
                f"컷 {n_a} 의 {kind_a} 와 컷 {n_b} 의 {kind_b} 가 어긋나 보입니다 "
                f"({topic}) — {why}.\n"
                f"    · \"{ta[:60]}\"\n    · \"{tb[:60]}\"\n"
                "    인물이 거짓말하거나 아직 모르는 상황이면 그대로 두세요.")
    return out


# 예고편 문체 — 나레이션이 "AI 가 쓴 것 같다"고 읽히는 패턴들. 문체라 게이트로
# 막으면 오탐이 이야기를 세우므로 **경고만** 한다. 실측(2026-08-27, 한 화의
# 나레이션 다섯이 전부 이 문체): "서도윤. 세계에서 가장 강한 히어로." ·
# "오늘, 도시의 숨은 균열이 드러나기 시작했다." · "감정이 세상을 뒤덮는다."
# 한국어 문장의 종결어미로 끝나는가 — 명사로 끝나면("~한 힘.") 안 걸린다.
_KO_SENT_END = re.compile("[다까요네지라야만]$")
_TRAILER_OPEN = re.compile(r"^(오늘|이제|지금),")
_TRAILER_VERB = re.compile(r"(시작된다|시작한다|드러난다|드러나기 시작|뒤덮는다|"
                           r"깨어난다|열리기 시작|무너지기 시작)")
_ABSTRACT_SUBJ = re.compile(r"(감정|운명|침묵|어둠|긴장|공포|슬픔)(이|가)\s*[^,.]{0,14}"
                            r"(뒤덮|삼키|휘감|무너뜨|덮쳐|찢|짓누르|파고들)")
_SENT_TAIL = ".!?\u2026\u201d\u2019)\u300d\u300f]"   # 떼고 볼 문장 끝 장식


def narration_style_warnings(cuts: list) -> list:
    """나레이션의 예고편 문체를 적어만 둔다. 막지 않는다 — 문체는 오탐이 있다."""
    out = []
    for c in cuts:
        if not isinstance(c, dict):
            continue
        n = c.get("cut_number")
        for row in speech_lines(c):
            if row["kind"] != "narration":
                continue
            text = row["text"]
            sents = [t.strip() for t in re.split(r"(?<=[.!?])\s+", text) if t.strip()]
            nounish = [t for t in sents if len(t) >= 4
                       and not _KO_SENT_END.search(t.rstrip(_SENT_TAIL))]
            if len(nounish) >= 2:
                head = nounish[0][:24]
                out.append(f"컷 {n} 나레이션이 명사로 끝나는 문장을 나열합니다 "
                           f"({head}...) — 영화 예고편 문체입니다. "
                           "문장으로 풀어 쓰는 쪽이 이야기답습니다.")
            if any(_TRAILER_OPEN.search(t) and _TRAILER_VERB.search(t)
                   for t in sents):
                out.append(f"컷 {n} 나레이션이 이 화에서 벌어질 일을 미리 선언합니다 "
                           f"({text[:30]}...) — 그림이 보여줄 것을 나레이션이 "
                           "먼저 팔아버리는 예고편 문체입니다.")
            m = _ABSTRACT_SUBJ.search(text)
            if m:
                out.append(f"컷 {n} 나레이션에서 추상어가 물리 행동을 합니다 "
                           f"({m.group(0)}) — 감정은 뒤덮지도 삼키지도 못합니다. "
                           "실제로 움직이는 것(덩굴·물·사람)을 주어로 쓰세요.")
    return out


def text_pass_warnings(cuts: list, scenes: list, facts: list = None) -> list:
    """8.5단계에서 **막지 않고 적어만 두는 것.** 위반이 아니라 놓치기 쉬운 자리다."""
    out = []
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return out
    out += narration_style_warnings(cuts)

    silent = [all(is_blank(c.get(k)) for k in TEXT_FIELDS) for c in cuts]
    num = [c.get("cut_number") for c in cuts]
    run = best = at = 0
    for i, s in enumerate(silent):
        run = run + 1 if s else 0
        if run > best:
            best, at = run, i - run + 1
    if best > MAX_MUTE_RUN_W8:
        out.append(
            f"컷 {num[at]}~{num[at + best - 1]} 이 글자 없이 {best}컷 연속입니다. "
            "의도한 침묵이면 그대로 두세요 — 다만 그 구간에서 이야기가 멈춘 것처럼 "
            "읽힐 수 있습니다.")

    # 장면이 시작되는 컷에 상황이 서 있는가. 나레이션이 아니어도 되지만
    # (대사가 세우기도 한다) 아무것도 없으면 장면이 바뀐 줄 모른다.
    prev = 0
    for i, sc in enumerate(scenes or [], 1):
        if not isinstance(sc, dict):
            continue
        last = sc.get("last_cut")
        if not isinstance(last, int) or isinstance(last, bool):
            continue
        first = next((c for c in cuts
                      if isinstance(c.get("cut_number"), int)
                      and prev < c["cut_number"] <= last), None)
        prev = last
        if first is None:
            continue
        if all(is_blank(first.get(k)) for k in SPEECH_FIELDS):
            out.append(
                f"{i}번째 장면이 시작되는 컷 {first.get('cut_number')} 에 말이 "
                "없습니다. 장면이 바뀌는 자리라, 여기가 어디이고 무슨 상황인지 "
                "세워 주면 나머지가 읽힙니다.")

    out += narration_warnings(cuts)
    out += contradiction_warnings(cuts, facts)
    return out


def solve_text(ps: PromptSet, call, card: str, episode: dict, payload: dict,
               ledger_snapshot: str, absolute: int, max_retries: int,
               facts: list = None, author_note: str = "",
               memory_text: str = "") -> tuple:
    """(글자를 다시 쓴 payload, 재시도 횟수, 메모). 못 하면 원본을 그대로 돌려준다.

    7단계와 달리 **막히면 중단하지 않는다.** 여기서 세우면 이미 통과한 컷
    한 벌이 통째로 버려지는데, 8단계가 실패해도 7단계의 글자는 남아 있어서
    화가 성립한다. 재시도를 다 쓰면 경고만 남기고 원본으로 간다.
    """
    cuts = payload.get("cuts") or []
    pov = pov_of(card)
    feedback, regens = "", 0
    while True:
        out = call(
            "W8", f"{absolute}화 글자 다시 쓰기",
            render(ps.texts["w8"], {
                "engine_card": card,
                "episode_json": json.dumps(episode, ensure_ascii=False, separators=(",", ":")),
                "ledger_snapshot": ledger_snapshot,
                "cuts_json": json.dumps(cuts, ensure_ascii=False, separators=(",", ":")),
                "pov": pov or "(엔진 카드에서 주인공을 찾지 못했습니다)",
                "banned_words": " · ".join(BANNED_IN_DIALOGUE),
                "user_memory": memory_text,
                "retry_feedback": feedback_slot(author_note, feedback),
            }),
            TEMP_CREATIVE)

        # 원본을 건드리기 전에 사본에 얹어 본다. 게이트에 걸리면 원본은 그대로다.
        trial = json.loads(json.dumps(cuts, ensure_ascii=False))
        notes = apply_text_patch(trial, out.get("text_patch"))
        failures = gate_text_pass(trial, pov)
        if not failures:
            payload["cuts"] = trial
            notes += repair_bubble_zone(trial)
            # 서사적 중요도는 글자 게이트를 통과한 뒤에 얹는다. 글자 때문에
            # 되돌릴 때 중요도까지 같이 날아갈 이유가 없고, 반대로 중요도가
            # 이상해도 대사는 살아야 한다 — 둘은 독립된 판단이다.
            notes += apply_narrative_weights(trial, out.get("narrative_weights"))
            notes += text_pass_warnings(trial, payload.get("scenes"), facts)
            return payload, regens, [f"글자: {x}" for x in notes]

        log(f"  {absolute}화 8단계 게이트 실패 {len(failures)}건")
        for f in failures:
            log(f"      - {f}")
        if regens >= max_retries:
            warn(f"  {absolute}화 8단계 재시도 소진 — 7단계의 글자를 그대로 씁니다.")
            return payload, regens, [f"글자: (8단계 실패) {f}" for f in failures]
        regens += 1
        feedback = "\n".join(f"- {f}" for f in failures)


def scene_spans(breaks: list) -> list:
    """scene_break 목록 → [(시작 index, 끝 index, 컷 수)]."""
    spans, start = [], 0
    for i, brk in enumerate(breaks):
        if brk or i == len(breaks) - 1:
            spans.append((start, i, i - start + 1))
            start = i + 1
    return spans


def scene_sizes(cuts: list) -> list:
    """컷 목록 → Scene 별 컷 수. 화면 하나 분량이 몇 컷인지 파일에 남긴다."""
    return [span for _, _, span in scene_spans(
        [bool(c.get("scene_break")) for c in cuts if isinstance(c, dict)])]


def size_histogram(cuts: list) -> dict:
    """컷 목록 → {size: 개수}. 크기가 실제로 갈렸는지 파일만 열어도 보이게."""
    hist = {s: 0 for s in SIZES}
    for c in cuts:
        if isinstance(c, dict):
            key = str(c.get("size") or "").strip().lower()
            if key in hist:
                hist[key] += 1
    return hist


def camera_histogram(cuts: list) -> dict:
    """컷 목록 → 거리·앵글·전환 분포 + 얼굴 비율.

    파일만 열어도 "이 화가 얼굴 나열인가"가 보여야 한다. 완성된 run 을 나중에
    세어 보고서야 클로즈업+바스트가 60% 였다는 것을 알았다 — 그때는 이미 전부
    그려진 뒤다.
    """
    cuts = [c for c in cuts if isinstance(c, dict)]
    shots = {s: 0 for s in SHOTS}
    angles = {a: 0 for a in ANGLES}
    trans = {t: 0 for t in TRANSITIONS}
    for c in cuts:
        for key, hist in (("shot", shots), ("angle", angles),
                          ("transition", trans)):
            value = str(c.get(key) or "").strip()
            if value in hist:
                hist[value] += 1
    face = sum(shots[s] for s in FACE_SHOTS)
    return {"shot": shots, "angle": angles, "transition": trans,
            "face_ratio": round(face / len(cuts), 2) if cuts else 0.0}


def tally_cuts(result, cuts: list) -> None:
    """한 화의 컷을 실행 요약(csv 한 줄)에 더한다."""
    result.cut_count += len([c for c in cuts if isinstance(c, dict)])
    result.scene_count += len(scene_sizes(cuts))
    result.impact_count += size_histogram(cuts)["impact"]


SIZE_MARK = {"wide": "▭", "normal": "□", "tall": "▯", "impact": "█"}


def cuts_brief(cuts: list) -> str:
    """연출을 붙이기 **전** 한 줄 — 모델이 낸 것(크기·beat)만 보인다."""
    cuts = [c for c in cuts if isinstance(c, dict)]
    marks = [SIZE_MARK.get(str(c.get("size") or "").strip().lower(), "?")
             for c in cuts]
    beats = [str(c.get("beat") or "?")[0] for c in cuts]
    return f"컷 {len(cuts)}개 {''.join(marks)} · {''.join(beats)}"


RENDER_MARK = {"normal": "", "sd": "◐", "emphasis": "✦",
               "bleed": "▚", "breakout": "↗"}
# 로그 한 줄에 들어갈 거리 약호. 얼굴 셋은 대문자로 둔다 — 대문자가 몰려 있으면
# 그 화가 얼굴 나열이라는 뜻이고, 그게 한눈에 보여야 한다.
SHOT_MARK = {"원경": "w", "전신": "f", "중간": "m",
             "바스트": "B", "클로즈업": "C", "익스트림": "E", "인서트": "i"}


def cuts_summary(cuts: list) -> str:
    """호출 기록·로그에 남길 한 줄 — 크기와 리듬이 눈에 보이게."""
    cuts = [c for c in cuts if isinstance(c, dict)]
    if not cuts:
        return "컷 0개"
    marks = [SIZE_MARK.get(str(c.get("size") or "").strip().lower(), "?")
             for c in cuts]
    beats = [str(c.get("beat") or "?")[0] for c in cuts]
    gaps = [str(c.get("gap_after")) for c in cuts]
    shots = [SHOT_MARK.get(str(c.get("shot") or "").strip(), "?") for c in cuts]
    n_scene = len(scene_spans([bool(c.get("scene_break")) for c in cuts]))
    n_sd = sum(1 for c in cuts
               if str(c.get("render_style") or "").strip().lower() == "sd")
    face = camera_histogram(cuts)["face_ratio"]
    said = sum(1 for c in cuts if has_speech(c))
    return (f"컷 {len(cuts)}개 {''.join(marks)} · {''.join(beats)} · "
            f"{''.join(shots)} · 여백 {''.join(gaps)} · Scene {n_scene}개 · "
            f"얼굴 {int(face * 100)}% · 말 {said * 100 // len(cuts)}%"
            + (f" · SD {n_sd}컷" if n_sd else ""))


# ---------------------------------------------------------------- 6단계 판독

def summarize_review(review: dict, episodes: list) -> dict:
    """검사 결과를 정규화한다. 치명 위반(eq)은 따로 뽑는다."""
    per = review.get("per_episode")
    per = per if isinstance(per, list) else []
    by_order = {}
    for item in per:
        if isinstance(item, dict) and isinstance(item.get("order"), int):
            by_order[item["order"]] = item

    passed, failed, directives = [], [], {}
    for e in episodes:
        order = e.get("order")
        item = by_order.get(order)
        if item is None:
            failed.append(order)
            directives[order] = ["검사 결과에 이 화가 없습니다. 다시 제출하세요."]
            continue
        violations = item.get("violations")
        violations = violations if isinstance(violations, list) else []
        ok = item.get("pass") is True and not violations
        if ok:
            passed.append(order)
        else:
            failed.append(order)
            lines = []
            for v in violations:
                if isinstance(v, dict):
                    lines.append(
                        f"[{v.get('check')}] {v.get('detail')} -> {v.get('fix_directive')}")
                else:
                    lines.append(str(v))
            directives[order] = lines or ["pass=false 이나 violations 가 비어 있습니다."]

    eq_hits = []
    for item in (review.get("eq_untouched") if isinstance(review.get("eq_untouched"), list) else []):
        if isinstance(item, dict) and item.get("violated") is True:
            eq_hits.append({"episode_order": item.get("episode_order"),
                            "detail": item.get("detail")})

    return {
        "passed": sorted(passed),
        "failed": sorted(failed),
        "directives": directives,
        "eq_violations": eq_hits,
        "verified_types": review.get("verified_question_types")
                          if isinstance(review.get("verified_question_types"), list) else [],
        "verified_closures": review.get("verified_closures")
                             if isinstance(review.get("verified_closures"), list) else [],
    }


def commit_ledger(ledger: Ledger, arc_order: int, episodes: list, view: dict,
                  base_episode_no: int) -> None:
    """검사 AI가 인정한 것만 장부에 반영한다.

    작가가 신고한 유형이 아니라 검사자가 판정한 유형이 들어간다.

    상환 후보는 코드가 이미 본문으로 맞춰 둔 것뿐이다(assign_ids). 검사자는 그 중
    무엇을 인정하는지만 답한다 — 검사자가 id 를 옮겨 적다 틀려도 본문으로 다시
    맞춰보고, 그래도 안 되면 그 상환만 버린다.
    """
    ep_by_order = {e.get("order"): e for e in episodes if isinstance(e, dict)}

    closures_by_ep = {}
    for c in view["verified_closures"]:
        if isinstance(c, dict):
            closures_by_ep.setdefault(c.get("episode_order"), []).append(c)
    types_by_ep = {}
    for t in view["verified_types"]:
        if isinstance(t, dict):
            types_by_ep.setdefault(t.get("episode_order"), []).append(t)

    # 같은 Arc 의 앞선 화가 연 질문은 지금 이 자리에서 처음 장부 id 를 받는다.
    # 뒤 화가 그 질문을 닫으려면 임시 id 를 실제 id 로 바꿔야 한다.
    temp_to_ledger = {}

    for order in sorted(ep_by_order):
        absolute = base_episode_no + order
        episode = ep_by_order[order]

        # -- 상환 먼저. 이번 화가 여는 질문을 같은 화가 닫는 일은 구조적으로 없다.
        candidates = {}                 # 검사자가 쓸 법한 id -> (실제 장부 id, 본문)
        for c in (episode.get("questions_closed") or []):
            if not isinstance(c, dict):
                continue
            key = str(c.get("ledger_id") or "")
            qid = temp_to_ledger.get(key, key)
            if not qid.startswith("Q-"):
                # 앞선 화가 검사에서 인정받지 못해 아직 발급되지 않은 질문이다.
                warn(f"  장부: {order}화의 상환 대상 '{key}' 은 아직 장부에 없습니다 — 건너뜁니다.")
                continue
            entry = (qid, str(c.get("closed_question_text") or ""))
            candidates[key] = entry
            candidates[qid] = entry

        for v in closures_by_ep.get(order, []):
            vid = str(v.get("ledger_id") or "")
            entry = candidates.get(vid)
            if entry is None:
                key = match_question(
                    v.get("question_text") or v.get("text") or vid,
                    [(k, t) for k, (_, t) in candidates.items()])
                entry = candidates.get(key) if key else None
            if entry is None:
                # 후보가 하나뿐이면 검사자가 id 를 잘못 옮긴 것으로 본다. 고를 것이
                # 하나뿐이라 엉뚱한 질문을 닫을 위험이 없다.
                distinct = {e[0]: e for e in candidates.values()}
                if len(distinct) == 1:
                    entry = next(iter(distinct.values()))
                    warn(f"  장부: {order}화의 상환 id '{vid}' 이 제출물과 다릅니다 — "
                         f"후보가 '{entry[0]}' 하나뿐이라 그것으로 반영합니다.")
            if entry is None:
                warn(f"  장부: {order}화의 상환 '{vid}' 은 코드가 맞춰 둔 상환 목록에 "
                     "없습니다 — 반영하지 않습니다.")
                continue
            if not ledger.close(entry[0], arc_order, absolute, v.get("is_betrayal")):
                warn(f"  장부: {order}화의 상환 '{entry[0]}' 은 반영되지 않았습니다 "
                     "(존재하지 않거나 이미 닫힘).")

        # -- 여는 질문
        submitted = episode.get("questions_opened")
        submitted = submitted if isinstance(submitted, list) else []
        by_temp = {str(q.get("temp_id")): q for q in submitted if isinstance(q, dict)}
        for t in types_by_ep.get(order, []):
            temp_id = str(t.get("temp_id") or "")
            src = by_temp.get(temp_id)
            if src is None:
                key = match_question(t.get("text") or temp_id,
                                     [(k, q.get("text")) for k, q in by_temp.items()])
                src = by_temp.get(key) if key else None
            if src is None:
                warn(f"  장부: {order}화의 검증 질문 '{temp_id}' 이 제출물에 없습니다 — 건너뜁니다.")
                continue
            if src.get("_ledger_id"):           # 검사자가 같은 질문을 두 번 적은 경우
                continue
            q = ledger.open(
                text=str(src.get("text") or "").strip(),
                qtype=str(t.get("type") or src.get("type") or "").strip(),
                arc=arc_order, episode=absolute,
                planned=src.get("planned_payoff_episode"))
            src["_ledger_id"] = q.id            # 컷 단계에서 스팅어 연결에 쓴다
            temp_to_ledger[str(src.get("temp_id"))] = q.id

        # -- 스팅어가 가리키는 임시 id 도 실제 장부 id 로 바꿔 둔다
        stinger = episode.get("stinger")
        if isinstance(stinger, dict):
            link = str(stinger.get("linked_question_id") or "")
            if link in temp_to_ledger:
                stinger["linked_question_id"] = temp_to_ledger[link]


# ---------------------------------------------------------------- 실행

@dataclass
class WebtoonResult:
    run_id: str
    character: str = ""
    genre: str = ""
    status: str = STATUS_OK
    note: str = ""
    arc_count: int = 0
    reversal_ratio: str = ""
    episode_count: int = 0     # 이번 실행에서 만든 화
    series_total: int = 0      # 이 작품에 지금까지 쌓인 화 (이어 만들기 때문에 다르다)
    cut_count: int = 0
    episodes_passed: int = 0
    episodes_failed: int = 0
    eq_violation: str = "no"
    ledger_open: int = 0
    ledger_closed: int = 0
    betrayal_closures: int = 0
    warnings: str = ""
    scene_count: int = 0        # 컷이 정한 Scene 경계의 총 개수
    impact_count: int = 0       # impact 컷 수 — 크기 분포가 평지인지 한눈에
    regen_stage4: int = 0
    regen_stage5: int = 0
    regen_stage7: int = 0
    elapsed_sec: float = 0.0
    total_tokens: int = 0
    cost_usd: float = None      # 단가를 모르는 모델이면 None — 0.0 이 아니다
    cost_note: str = ""         # 합계가 부분 합계인 이유 (모르는 모델 이름)

    def as_row(self) -> dict:
        d = {k: getattr(self, k) for k in WEBTOON_SUMMARY_COLUMNS}
        d["elapsed_sec"] = round(self.elapsed_sec, 1)
        # 모르는 단가는 빈 칸. 0 을 적으면 합계가 조용히 낮아진다.
        d["cost_usd"] = ("" if self.cost_usd is None
                         else f"{self.cost_usd:.6f}")
        return d


class Stopped(Exception):
    """엔진급 질문 훼손 등 자동으로 이어갈 수 없는 지점.

    draft 는 멈추기 직전까지 만들어 둔 마지막 시도(컷 payload)다. 없으면 None.
    게이트 재시도가 소진돼 멈출 때 이것을 실어 보내면, 부르는 쪽이 초안으로
    저장해서 **사람이 확인 화면에서 실제 컷을 보고** 진행/재시도를 고를 수 있다.
    예전에는 여기서 전부 버려져서, 확인 화면이 "콘티를 확인해 주세요" 라고
    말하면서 보여줄 콘티가 없었다(cut_count=0).
    """

    def __init__(self, reason: str, status: str = STATUS_STOPPED, draft=None):
        super().__init__(reason)
        self.reason = reason
        self.status = status
        self.draft = draft


class CallLog:
    """[AI를 부른 기록] — 단계·모델·시간·토큰·비용·판정이 한 줄씩 쌓인다.

    기록 자체는 Usage 가 호출 시점에 만든다(story.Usage.add). 여기서 하는 일은
    거기에 사람이 읽을 라벨과 판정을 붙여 파일로 옮기는 것뿐이다. 원장이 두 벌이
    되면 언젠가 서로 어긋나므로, 숫자의 출처는 한 곳으로 둔다.
    """

    def __init__(self, path: Path):
        self.path = path
        self.rows = []
        self.written = 0        # usage.records 중 어디까지 옮겼는지

    def add(self, usage, start: int, label: str = "", verdict: str = "") -> list:
        """usage.records[start:] 에 라벨을 붙여 옮긴다.

        마지막 한 줄이 아니라 **구간 전체**를 가져온다 — JSON 파싱 실패로 한
        단계가 두 번 호출되면 기록도 두 줄이고, 재시도 비용도 거기 있다.
        """
        usage.annotate(start, label=label, verdict=verdict)
        return self.flush(usage)

    def flush(self, usage) -> list:
        """아직 안 옮긴 기록을 전부 옮긴다.

        중간에 예외로 끊긴 호출도 원장에 남아야 한다 — 실패한 호출도 돈은 나갔다.
        """
        fresh = usage.records[self.written:]
        if not fresh:
            return []
        self.rows.extend(fresh)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with open(self.path, "a", encoding="utf-8") as f:
            for row in fresh:
                f.write(json.dumps(row, ensure_ascii=False) + "\n")
        self.written = len(usage.records)
        return fresh


def human_passed(out_dir: Path, run_id: str) -> object:
    """blind_result.csv 에서 이 실행의 사람 판정을 찾는다. 없으면 None."""
    path = out_dir / "blind_result.csv"
    if not path.exists():
        return None
    verdicts = []
    with open(path, encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            if (row.get("run_id") or "").strip() == run_id:
                verdicts.append((row.get("next_scene_curious") or "").strip())
    if not verdicts:
        return None
    return any(v == "예" for v in verdicts)


def load_story_run(run_dir: Path) -> dict:
    need = ["p1.json", "p2.json", "meta.json", "scenes.json"]
    # seed.json 은 없어도 돌아간다 (옛 run). 있으면 세계관과 장르를 카드에 싣는다 —
    # 그게 없으면 나레이션이 쓸 재료가 시간·장소밖에 없다.
    optional = ["seed.json"]
    missing = [n for n in need if not (run_dir / n).exists()]
    if missing:
        raise SystemExit(
            f"{run_dir.name}: {', '.join(missing)} 가 없습니다. "
            "스토리 파이프라인을 먼저 끝내세요 (python story.py --input inputs.csv).")
    data = {n[:-5]: json.loads((run_dir / n).read_text(encoding="utf-8")) for n in need}
    for n in optional:
        path = run_dir / n
        data[n[:-5]] = (json.loads(path.read_text(encoding="utf-8"))
                        if path.exists() else {})
    if data["meta"].get("condition") != "pipeline":
        raise SystemExit(
            f"{run_dir.name} 은 condition={data['meta'].get('condition')} 입니다. "
            "웹툰 단계는 파이프라인 실행에만 이어집니다.")
    return data


def run_webtoon(caller: Caller, ps: PromptSet, run_dir: Path, out_dir: Path,
                max_retries: int, ledger_cap: int, resume: bool,
                episode_target: int = 1, replan: bool = False,
                author_note: str = "") -> WebtoonResult:
    """Arc 큰 줄거리를 잡고, **다음 화부터 episode_target 편**을 만든다.

    기본이 1편인 이유: 지금 보고 싶은 것은 1화다. 1화가 재미있으면 그때 2화를
    만든다. 다시 부르면 이어서 다음 화를 만든다 (연재 상태가 series.json 에 남는다).
    """
    story = load_story_run(run_dir)
    p1, p2, meta = story["p1"], story["p2"], story["meta"]
    scenes = story["scenes"].get("scenes") or []
    idea = meta.get("input", {}).get("one_line", "")

    wt_dir = run_dir / "webtoon"
    wt_dir.mkdir(parents=True, exist_ok=True)
    calls = CallLog(wt_dir / "calls.jsonl")

    result = WebtoonResult(
        run_id=meta["run_id"],
        character=meta.get("input", {}).get("character", ""),
        genre=meta.get("input", {}).get("genre", ""))
    usage = Usage()
    t0 = time.monotonic()

    # 4단계(Arc)까지는 P1 이 정한 사람들이 전부다. 1화가 시작되면 5단계가 인물을
    # 추가하므로, 화 단위 루프 안에서 그 시점 명부로 다시 만든다 (아래 참조).
    card = build_engine_card(p1, p2, idea, scenes, seed=story.get("seed"))
    (wt_dir / "engine_card.txt").write_text(card, encoding="utf-8")

    ledger = Ledger(str(p2.get("engine_question") or ""), cap=ledger_cap)
    sheet = json.dumps(p1, ensure_ascii=False, separators=(",", ":"))
    # 작가 규칙 — run 폴더에 memory.json 이 있으면 매 단계 프롬프트에 실린다.
    # 없으면 빈 구조라 resolve 가 늘 "" 를 주고, 프롬프트는 예전 그대로다.
    user_mem = load_user_memory(run_dir / MEMORY_FILE)

    def call(stage: str, label: str, prompt: str, temp: float, verdict_of=None):
        start = len(usage.records)
        obj, _ = caller.json_call(stage, prompt, temp, usage)
        calls.add(usage, start, label, verdict_of(obj) if verdict_of else "")
        return obj

    arcs_path = wt_dir / "arcs.json"
    series_path = wt_dir / "series.json"
    all_episodes = []          # [(arc_order, episode dict, 통산번호)]
    stopped_note = ""

    # 이어 만들 때는 앞 화들이 만든 장부를 되살린다. 안 그러면 2화가 1화의
    # 질문을 못 닫는다 — 장부가 비어 있으니 닫을 대상이 없다.
    ledger_path = wt_dir / "ledger.json"
    if ledger_path.exists():
        ledger = Ledger.from_dict(
            json.loads(ledger_path.read_text(encoding="utf-8")))
        ledger.engine.text = str(p2.get("engine_question") or "")
        ledger.cap = ledger_cap

    try:
        # ---------------------------------------------------- 4단계
        #
        # Arc 는 **작품에 한 번** 정하는 것이다. 화를 하나씩 뽑는 구조라
        # webtoon.py 를 3번 부르면 3화가 나오는데, 예전에는 그때마다 W4 가
        # 다시 돌았다. 그러면 3화는 1화가 따랐던 것과 **다른 Arc** 를 보고
        # 설계된다 — arcs.json 은 마지막 것만 남아서 어긋난 줄도 모른다.
        # 방향을 다시 잡고 싶으면 --replan 으로 명시한다.
        if arcs_path.exists() and not replan:
            arcs_payload = json.loads(arcs_path.read_text(encoding="utf-8"))
            log(f"  4단계: 기존 arcs.json 재사용 "
                f"(Arc {len(arcs_payload.get('arcs') or [])}개) — "
                f"다시 잡으려면 --replan")
        else:
            feedback = ""
            while True:
                arcs_payload = call(
                    "W4", "큰 줄거리 분할",
                    render(ps.texts["w4"], {
                        "engine_card": card, "character_sheet": sheet,
                        "user_memory": resolve_user_memory(user_mem, card, sheet),
                        "retry_feedback": feedback_slot(author_note, feedback),
                    }),
                    TEMP_CREATIVE,
                    lambda o: f"Arc {len(o.get('arcs') or [])}개")
                failures = gate_arcs(arcs_payload)
                if not failures:
                    break
                log(f"  4단계 게이트 실패 {len(failures)}건")
                for f in failures:
                    log(f"      - {f}")
                if result.regen_stage4 >= max_retries:
                    raise Stopped("4단계 게이트 재시도 소진: " + " / ".join(failures),
                                  STATUS_HUMAN)
                result.regen_stage4 += 1
                feedback = "\n".join(f"- {f}" for f in failures)
            write_json(arcs_path, arcs_payload)

        arcs = arcs_payload["arcs"]
        result.arc_count = len(arcs)
        reversal = sum(1 for a in arcs if str(a.get("arc_type")).strip() == "반전")
        result.reversal_ratio = f"{reversal}/{len(arcs)}"
        log(f"  4단계 통과: Arc {len(arcs)}개 (반전 {reversal})")

        # ---------------------------------------------------- 5·6단계
        #
        # 화를 **하나씩** 만든다. 예전에는 Arc 5개를 돌며 17화를 한꺼번에 설계했다.
        # 그러면 1화를 보기 위해 17화를 다 만들어야 하고(호출 50여 회), 17화치
        # 떡밥을 미리 스케줄링하느라 부채가 쌓여 마지막 Arc 가 무너졌다.
        # 연재는 앞 화가 나온 뒤에 다음 화를 정하는 일이다.
        state = SeriesState.load(series_path, result.run_id)
        # 스토리 단계가 정한 조연을 명부의 출발점으로 깐다. 이걸 안 하면 1화가
        # 조연을 '새로 만드는' 일이 되어, 같은 캐릭터로 두 번 돌릴 때마다
        # 후배 이름이 달라진다.
        seeded = state.seed_cast(p1.get("supporting_cast"))
        if seeded:
            log(f"  조연 {seeded}명을 스토리 단계에서 이어받음 "
                f"({', '.join(str(c.get('name')) for c in p1['supporting_cast'] if isinstance(c, dict))})")

        # 몇 화를 이어 만드는지 **먼저** 말한다. 같은 명령을 다시 치면 조용히 다음
        # 화가 붙는 구조라(연재니까 그게 맞다), 말해 주지 않으면 1화만 만든 줄 알고
        # 세 번 부른 뒤에 3화짜리 결과를 보게 된다. 실제로 그 일이 있었다.
        first, last = state.next_no(), state.made + episode_target
        span = f"{first}화" if first == last else f"{first}~{last}화"
        if state.made:
            log(f"  지금까지 {state.made}화가 있습니다 → 이번에 {span}를 이어 만듭니다 "
                f"(처음부터 다시 만들려면 webtoon/ 을 비우세요)")
        else:
            log(f"  {span}를 만듭니다 (한 번 더 부르면 다음 화가 이어집니다)")

        for _ in range(episode_target):
            no = state.next_no()
            # 엔진 카드를 **화마다 다시 만든다.** 인물은 P1 에서 끝나지 않는다 —
            # 5단계가 화마다 사람을 추가하고, 그 사람이 다음 화에도 나온다.
            # 카드를 한 번 만들고 얼려 두면 3화에서 나온 인물이 7화 컷 설계에서
            # 사라진다. 5단계는 series_state 로 명부를 따로 받지만 6·7단계는
            # 카드가 전부라, 그리는 쪽이 그 사람을 처음 보게 된다.
            card = build_engine_card(p1, p2, idea, scenes, state.cast,
                                     state.status, seed=story.get("seed"))
            (wt_dir / "engine_card.txt").write_text(card, encoding="utf-8")
            arc = arc_for_episode(arcs, no)
            arc_order = arc.get("order")
            arc_json = json.dumps(arc, ensure_ascii=False, separators=(",", ":"))
            ep_path = wt_dir / f"arc{arc_order}_episodes.json"
            rv_path = wt_dir / f"ep{no:02d}_review.json"
            cut_path = wt_dir / f"ep{no:02d}_cuts.json"

            feedback, review, episode = "", {}, None
            while True:
                one = call(
                    "W5", f"{no}화 설계",
                    render(ps.texts["w5"], {
                        "engine_card": card,
                        "series_arc": series_arc_block(arcs, arc),
                        "arc_json": arc_json,
                        # 앞 화 요약만이 아니라 **인물·설정 명부까지** 넘긴다.
                        # 요약만 주면 3화가 1화의 인물 이름을 잊는다.
                        "series_state": state.brief(ledger),
                        "user_memory": resolve_user_memory(
                            user_mem, card, arc_json, state.brief(ledger)),
                        "retry_feedback": feedback_slot(author_note, feedback),
                    }),
                    TEMP_CREATIVE,
                    lambda o: str(o.get("title") or "")[:20])

                # 아래 단계들은 화 배열을 받게 되어 있다. 한 화를 배열 하나로 싼다.
                ep_payload = {"arc_order": arc_order, "episodes": [one]}
                resolution = assign_ids(ep_payload, ledger)
                episodes = ep_payload.get("episodes") or []
                for line in resolution.ignored:
                    warn(f"  5단계 정리: {line}")
                if resolution.fallback_stingers:
                    warn("  5단계 정리: 스팅어 연결 대상을 찾지 못해 이 화가 연 첫 "
                         "질문으로 이었습니다.")

                shape = gate_episodes_shape(ep_payload, ledger, None, resolution)
                if shape:
                    log(f"  {no}화 형식 게이트 실패 {len(shape)}건")
                    for f in shape:
                        log(f"      - {f}")
                    if result.regen_stage5 >= max_retries:
                        raise Stopped(f"{no}화 형식 게이트 재시도 소진: "
                                      + " / ".join(shape), STATUS_HUMAN)
                    result.regen_stage5 += 1
                    feedback = "\n".join(f"- {f}" for f in shape)
                    continue

                # ---- 6단계: 별도 세션. 생성 히스토리를 넣지 않는다.
                review = call(
                    "W6", f"{no}화 검사",
                    render(ps.texts["w6"], {
                        "engine_card": card, "arc_json": arc_json,
                        "ledger_snapshot": ledger.snapshot(no),
                        # Story State — 3자 대조의 한 축. 이것 없이는 "이름을
                        # 아는 게 맞는가"를 판정할 근거가 없다.
                        "series_state": state.brief(ledger),
                        "episodes_json": json.dumps(
                            {"episodes": episodes}, ensure_ascii=False, separators=(",", ":")),
                    }),
                    TEMP_JUDGE,
                    lambda o: "검사 완료")
                view = summarize_review(review, episodes)

                if view["eq_violations"]:
                    detail = "; ".join(h["detail"] for h in view["eq_violations"])
                    result.eq_violation = "yes"
                    write_json(rv_path, review)
                    raise Stopped(
                        f"엔진급 질문이 닫혔습니다 ({no}화) — {detail}. 이야기의 중심 "
                        "질문이 해소되었다는 뜻이므로 자동 재생성하지 않습니다.")

                if not view["failed"]:
                    log(f"  {no}화 검사 통과 · 「{one.get('title')}」")
                    episode = one
                    break

                if result.regen_stage5 >= max_retries:
                    result.note = f"{no}화가 재시도 소진 후에도 불합격입니다."
                    result.status = STATUS_HUMAN
                    log(f"  {no}화 검사 불합격 — 재시도 소진")
                    break

                result.regen_stage5 += 1
                log(f"  {no}화 검사 불합격 — 다시 씁니다")
                feedback = "이 화를 다시 쓰라.\n" + "\n".join(
                    view["directives"].get(1, []))

            write_json(rv_path, review)
            result.episodes_passed += len(view["passed"])
            result.episodes_failed += len(view["failed"])
            if episode is None:
                break           # 불합격 화는 장부와 명부에 넣지 않는다

            commit_ledger(ledger, arc_order, episodes, view, no - 1)
            all_episodes.append((arc_order, episodes[0], no))

            # ---------------------------------------------------- 7·8단계
            #
            # state.add (이 화를 "만들었다" 고 명부·series.json 에 남기는 일)는
            # 컷까지 끝난 뒤에만 한다. 예전에는 6단계 통과 직후 바로 state.add 를
            # 불렀다 — 그러면 7단계(컷) 게이트가 재시도를 다 쓰고 사람확인필요로
            # 멈춰도 series.json 에는 이미 이 화가 완료로 남는다. 다음 실행은
            # next_no() 만 보고 곧장 다음 화로 넘어가 버려서, 못 그린 화는 영영
            # 다시 시도되지 않는다 (실제로 8/20 실행에서 1·2화가 이렇게 버려졌다).
            if resume and cut_path.exists():
                payload = json.loads(cut_path.read_text(encoding="utf-8"))
                tally_cuts(result, payload.get("cuts") or [])
            else:
                # 유형은 검사자가 판정한 것(장부)을 쓴다. 작가 신고는 근거가 아니다.
                opened = episode.get("questions_opened") or []
                irony_present = False
                for q in opened:
                    if not isinstance(q, dict):
                        continue
                    entry = ledger.get(str(q.get("_ledger_id") or ""))
                    qtype = entry.type if entry else str(q.get("type") or "")
                    if qtype.strip() == "dramatic_irony":
                        irony_present = True
                        break

                # 재시도 예산은 **화마다** 준다. 예전에는 result.regen_stage7 을
                # spent 로 넘겨 실행 전체가 max_retries(기본 2)를 나눠 썼는데,
                # 그러면 17화짜리 실행에서 1화가 두 번 되돌아가는 순간 나머지 16화는
                # 예산이 0이 된다. 게이트가 느슨할 때는 드러나지 않았지만(실측 stage7
                # 재생성 0~2회), 얼굴 비율·말 밀도까지 세기 시작하면 그 구조로는
                # 2화를 못 넘긴다. --cuts-only 는 원래 화마다 주고 있었다 —
                # 두 경로가 어긋나 있던 것을 맞춘다.
                # 앞 화들이 확정한 설정을 장부에 옮긴 뒤 스냅샷을 뜬다 — 이걸
                # 안 하면 7·8단계가 established_facts 를 빈 목록으로 받는다.
                ledger.sync_facts(state.facts)
                payload, regens, notes = solve_cuts(
                    ps, call, card, arc_json, episode, ledger.snapshot(no),
                    irony_present, no, max_retries,
                    known=known_speakers(card, [e for _, e, _ in all_episodes],
                                         state.cast),
                    series_arc=series_arc_block(arcs, arc),
                    zones_txt=zones_block(state, episode),
                    known_zones={z["zone_id"] for z in state.zones},
                    personality=p1.get("personality"),
                    author_note=author_note,
                    memory_text=resolve_user_memory(
                        user_mem, card,
                        json.dumps(episode, ensure_ascii=False)))
                result.regen_stage7 += regens

                # ---- 8단계: 컷이 확정된 뒤에 글자만 다시 쓴다 ----------
                payload, w8_regens, w8_notes = solve_text(
                    ps, call, card, episode, payload, ledger.snapshot(no),
                    no, max_retries, facts=state.facts, author_note=author_note,
                    memory_text=resolve_user_memory(
                        user_mem, card,
                        json.dumps(payload, ensure_ascii=False)))
                notes += w8_notes
                # ---- 9단계: 확정된 컷을 화면 단위로 묶는다 ----------
                pages, w9_notes = solve_pages(
                    ps, call, card, episode, payload.get("cuts") or [],
                    no, max_retries, author_note=author_note)
                if pages:
                    payload["pages"] = pages
                notes += w9_notes
                for note in notes:
                    warn(f"    {note}")

                cuts = payload.get("cuts") or []
                payload["_absolute_episode"] = no
                payload["_arc_order"] = arc_order
                payload["_scene_sizes"] = scene_sizes(cuts)
                payload["_size_histogram"] = size_histogram(cuts)
                payload["_camera_histogram"] = camera_histogram(cuts)
                payload["_prompt_w7"] = ps.short_hashes.get("w7", "")
                payload["_prompt_w8"] = ps.short_hashes.get("w8", "")
                payload["_notes"] = notes
                write_json(cut_path, payload)
                tally_cuts(result, cuts)

            cuts = payload.get("cuts") or []
            state.add(no, arc_order, episode)
            state.save(series_path)

            # 뷰어는 arc{N}_episodes.json 을 읽는다. 같은 Arc 의 화를 이어 붙인다.
            bucket = {"arc_order": arc_order, "episodes": []}
            if ep_path.exists():
                bucket = json.loads(ep_path.read_text(encoding="utf-8"))
                bucket.setdefault("episodes", [])
            bucket["episodes"].append(episodes[0])
            write_json(ep_path, bucket)

            # 7단계가 구체화한 것을 명부로 되돌린다. 경고만 하고 잊으면
            # 다음 화가 이 인물을 모른다 — 인물은 5단계에서만 생기는 것이
            # 아니라 6·7단계를 지나며 구체화된다.
            fresh = record_cut_cast(
                state, cuts,
                known_speakers(card, [e for _, e, _ in all_episodes], state.cast),
                no)
            if fresh:
                state.save(series_path)
                log(f"  명부에 추가: {', '.join(fresh)} "
                    f"(외형은 빈 칸 — 그림 단계에서 채웁니다)")

            setting = episode.get("setting")
            place = str((setting or {}).get("place") or "").strip()
            fresh_zones = record_cut_zone(state, payload, place, no)
            if fresh_zones:
                state.save(series_path)
                log(f"  존 명부에 추가: {', '.join(fresh_zones)}")

            log(f"  {no}화 통과 · {cuts_summary(cuts)}")

            for w in ledger.warnings(no):
                warn(f"  장부 경고: {w}")

            if result.status == STATUS_HUMAN:
                break

    except Stopped as e:
        result.status = e.status
        stopped_note = e.reason
        warn(f"  중단: {e.reason}")
        # 마지막 초안이 있으면 남긴다 — 확인 화면이 이것을 보여준다.
        # solve_cuts 는 실제로 컷을 뽑아 놓고도(gate 만 못 넘겨도) 이 바깥
        # 핸들러까지 오면 그 payload 가 그냥 버려졌다 — 그래서 컷이 진짜로는
        # 나왔는데(예: 12개) 확인 화면은 컷이 0개인 것처럼 보였다 (2026-08-26
        # 실사용에서 확인: ep01_cuts.draft.json 이 아예 안 생김). solve_cuts
        # 안쪽의 --replan 재시도 경로(다른 곳)는 이미 이걸 하고 있었다 — 여기도
        # 같은 것을 한다.
        if getattr(e, "draft", None):
            try:
                draft_path = wt_dir / f"ep{no:02d}_cuts.draft.json"
                draft_path.write_text(json.dumps(
                    e.draft, ensure_ascii=False, indent=1), encoding="utf-8")
                warn(f"  마지막 초안을 남겼습니다: {draft_path.name}")
            except (OSError, NameError):
                pass               # 초안을 못 남겨도 멈춘 사실은 그대로다
    except ParseFailure as e:
        result.status = STATUS_PARSE_FAIL
        stopped_note = f"{e.stage} JSON 파싱 2회 실패"
        (wt_dir / "parse_failure.txt").write_text(e.raw or "", encoding="utf-8")
        warn(stopped_note)
    except ApiFailure as e:
        result.status = STATUS_API_FAIL
        stopped_note = str(e)
        warn(stopped_note)

    # ---- 마무리
    result.episode_count = len(all_episodes)
    result.series_total = SeriesState.load(series_path, result.run_id).made
    if stopped_note:
        result.note = (result.note + " / " if result.note else "") + stopped_note

    write_json(wt_dir / "ledger.json", ledger.as_dict())
    result.ledger_open = len(ledger.open_items)
    result.ledger_closed = len(ledger.closed_items)
    result.betrayal_closures = sum(1 for q in ledger.closed_items if q.is_betrayal)
    result.warnings = " / ".join(ledger.warnings(max(1, result.episode_count)))
    result.elapsed_sec = time.monotonic() - t0
    result.total_tokens = usage.total
    result.cost_usd = usage.cost_usd()
    result.cost_note = usage.cost_note()
    calls.flush(usage)      # 예외로 끊긴 호출도 원장에 남긴다

    write_json(wt_dir / "meta.json", {
        "run_id": result.run_id,
        "status": result.status,
        "note": result.note,
        "mock": caller.is_mock,
        "provider": caller.provider,
        "model": caller.model,
        "judge_model": caller.judge_model,
        "stage_models": {s: caller.model_for(s)
                         for s in ("W4", "W5", "W6", "W7")},
        "temperature": {"creative": TEMP_CREATIVE, "judge": TEMP_JUDGE},
        "temperature_applied": caller.temp_ok,
        "judge_temperature_applied": caller.judge_temp_ok,
        "review_isolated": True,
        "review_history_included": False,
        "prompt_sha256": ps.short_hashes,
        "ledger_cap": ledger_cap,
        "regen": {"stage4": result.regen_stage4, "stage5": result.regen_stage5,
                  "stage7": result.regen_stage7},
        "usage": usage.as_dict(),
        "elapsed_sec": round(result.elapsed_sec, 1),
        "finished_at": datetime.now().isoformat(timespec="seconds"),
    })
    write_json(wt_dir / "transcript.json", caller.transcript)
    caller.transcript = []

    build_webtoon_output(run_dir)
    append_webtoon_summary(out_dir, result)
    return result


# ---------------------------------------------------------------- 기록

def run_cuts_only(caller: Caller, ps: PromptSet, run_dir: Path,
                  max_retries: int, resume: bool, only: list = None,
                  author_note: str = "") -> int:
    """이미 회차까지 나온 run 의 컷만 다시 뽑는다 (화당 호출 1회).

    4~6단계를 다시 돌지 않는 것이 요점이다. 같은 회차 설계에 컷 분해만 바꿔 보고
    "크기와 여백이 실제로 갈리는가"를 앞 단계 재생성 없이 확인한다.
    """
    story = load_story_run(run_dir)
    cuts_mem = load_user_memory(run_dir / MEMORY_FILE)   # 작가 규칙 (없으면 빈 구조)
    wt_dir = run_dir / "webtoon"
    ep_paths = sorted(wt_dir.glob("arc*_episodes.json"))
    if not ep_paths:
        raise SystemExit(
            f"{run_dir.name}: arc*_episodes.json 이 없습니다. 5단계를 먼저 끝내세요.")

    # 컷만 다시 뽑을 때도 명부는 그 시점 것을 쓴다 — 저장된 카드는 마지막으로
    # 만든 화 기준이라, 앞 화를 다시 뽑으면 아직 안 나온 사람이 실릴 수 있다.
    # 그래도 없는 사람보다는 낫다(카드에 있는 사람을 안 그리는 건 자유다).
    series_path = wt_dir / "series.json"
    state = SeriesState.load(series_path)
    card_path = wt_dir / "engine_card.txt"
    card = (card_path.read_text(encoding="utf-8") if card_path.exists()
            else build_engine_card(story["p1"], story["p2"],
                                   story["meta"].get("input", {}).get("one_line", ""),
                                   story["scenes"].get("scenes") or [],
                                   state.cast, state.status,
                                   seed=story.get("seed")))

    arcs = {}
    arcs_path = wt_dir / "arcs.json"
    if arcs_path.exists():
        for a in json.loads(arcs_path.read_text(encoding="utf-8")).get("arcs") or []:
            arcs[a.get("order")] = a

    ledger_path = wt_dir / "ledger.json"
    ledger = (Ledger.from_dict(json.loads(ledger_path.read_text(encoding="utf-8")))
              if ledger_path.exists()
              else Ledger(str(story["p2"].get("engine_question") or "")))

    # 통산 화 번호는 Arc 순서대로, **실린 차례대로** 매긴다.
    #
    # 예전에는 base_no + episodes[].order 로 셌다. Arc 하나를 통째로 설계하던
    # 시절에는 order 가 그 Arc 안에서 1,2,3… 이라 맞았는데, 한 화씩 만들게 되면서
    # 5단계가 화를 **덧붙일 때마다 order 를 1 로** 적는다. 그래서 한 파일에 2화가
    # 쌓이면 둘 다 통산 1화가 되어, --cuts-only 가 2화를 영영 못 찾고
    # (--episode 2 가 0건), 필터 없이 돌리면 2화 설계를 ep01_cuts.json 에 덮어쓴다.
    #
    # 차례로 세면 옛 형식(order 1..N)에서도 같은 값이 나오므로 둘 다 맞는다.
    plan, absolute = [], 0
    for path in ep_paths:
        payload = json.loads(path.read_text(encoding="utf-8"))
        arc_order = payload.get("arc_order")
        for e in payload.get("episodes") or []:
            absolute += 1
            plan.append((arc_order, e, absolute))

    # 대사를 해도 되는 이름은 **모든 화**의 명부에서 모은다. 3화 인물이 5화에서
    # 말하는 것은 정상이므로, 그 화 것만 보면 틀린 경고가 쌓인다.
    # 연재 명부(스토리 단계에서 확정된 조연 포함)도 같이 본다.
    cast_known = known_speakers(card, [e for _, e, _ in plan], state.cast)

    calls = CallLog(wt_dir / "calls.jsonl")
    usage = Usage()

    def call(stage, label, prompt, temp, verdict_of=None):
        start = len(usage.records)
        obj, _ = caller.json_call(stage, prompt, temp, usage)
        calls.add(usage, start, label, verdict_of(obj) if verdict_of else "")
        return obj

    done = 0
    for arc_order, episode, absolute in plan:
        if only and absolute not in only:
            continue
        cut_path = wt_dir / f"ep{absolute:02d}_cuts.json"
        if resume and cut_path.exists():
            log(f"  {absolute}화: 기존 컷 재사용")
            continue

        irony_present = False
        for q in episode.get("questions_opened") or []:
            if not isinstance(q, dict):
                continue
            entry = ledger.get(str(q.get("_ledger_id") or ""))
            qtype = entry.type if entry else str(q.get("type") or "")
            if qtype.strip() == "dramatic_irony":
                irony_present = True
                break

        arc_json = json.dumps(arcs.get(arc_order) or {"order": arc_order},
                              ensure_ascii=False, separators=(",", ":"))
        # 앞 화들이 확정한 설정을 장부에 옮긴 뒤 스냅샷을 뜬다 — 이걸 안 하면
        # 7·8단계가 established_facts 를 빈 목록으로 받는다.
        ledger.sync_facts(state.facts)
        try:
            payload, _, notes = solve_cuts(
                ps, call, card, arc_json, episode,
                ledger.snapshot(absolute), irony_present,
                absolute, max_retries, known=cast_known,
                series_arc=series_arc_block([arcs[k] for k in sorted(arcs)],
                                            arcs.get(arc_order)),
                zones_txt=zones_block(state, episode),
                known_zones={z["zone_id"] for z in state.zones},
                personality=story["p1"].get("personality"),
                author_note=author_note,
                memory_text=resolve_user_memory(
                    cuts_mem, card, json.dumps(episode, ensure_ascii=False)))
        except Stopped as exc:
            # 게이트를 못 넘겼다. 트레이스백으로 죽지 않고 무엇이 걸렸는지만
            # 보여 준다 — 사람이 고칠 것은 프롬프트지 파이썬 스택이 아니다.
            warn(f"  {absolute}화를 만들지 못했습니다: {exc.reason}")
            warn("  같은 자리에서 계속 걸리면 --max-retries 를 올리거나 "
                 "prompts/w7.txt 의 그 항목을 손보세요.")
            # 마지막 초안이 있으면 남긴다 — 확인 화면이 이것을 보여준다.
            # 정식 파일(epNN_cuts.json)에는 안 쓴다: 뒷단계(그림)가 그것을
            # 읽으므로, 게이트에 걸린 것이 통과한 것처럼 흘러가면 안 된다.
            if getattr(exc, "draft", None):
                draft_path = wt_dir / f"ep{absolute:02d}_cuts.draft.json"
                try:
                    draft_path.write_text(json.dumps(
                        exc.draft, ensure_ascii=False, indent=1),
                        encoding="utf-8")
                    warn(f"  마지막 초안을 남겼습니다: {draft_path.name}")
                except OSError:
                    pass               # 초안을 못 남겨도 멈춘 사실은 그대로다
            continue
        payload, _, w8_notes = solve_text(
            ps, call, card, episode, payload, ledger.snapshot(absolute),
            absolute, max_retries, facts=state.facts, author_note=author_note,
            memory_text=resolve_user_memory(
                cuts_mem, card, json.dumps(payload, ensure_ascii=False)))
        notes += w8_notes
        # ---- 9단계: 확정된 컷을 화면 단위로 묶는다 ------------------
        # 컷도 대사도 손대지 않는다. 실패해도 화는 성립한다(solve_pages 참고).
        pages, w9_notes = solve_pages(
            ps, call, card, episode, payload.get("cuts") or [],
            absolute, max_retries, author_note=author_note)
        if pages:
            payload["pages"] = pages
        notes += w9_notes
        for note in notes:
            warn(f"    {note}")
        cuts = payload.get("cuts") or []
        payload["_absolute_episode"] = absolute
        payload["_arc_order"] = arc_order
        payload["_scene_sizes"] = scene_sizes(cuts)
        payload["_size_histogram"] = size_histogram(cuts)
        payload["_camera_histogram"] = camera_histogram(cuts)
        # 어느 프롬프트로 뽑은 화인지. 프롬프트를 고친 뒤 --stats 로 앞 화와
        # 비교할 때, 이게 없으면 표를 봐도 무엇 때문에 달라졌는지 못 짚는다.
        payload["_prompt_w7"] = ps.short_hashes.get("w7", "")
        payload["_prompt_w8"] = ps.short_hashes.get("w8", "")
        payload["_notes"] = notes
        write_json(cut_path, payload)
        done += 1

        setting = episode.get("setting")
        place = str((setting or {}).get("place") or "").strip()
        fresh_zones = record_cut_zone(state, payload, place, absolute)
        if fresh_zones:
            state.save(series_path)
            log(f"  존 명부에 추가: {', '.join(fresh_zones)} "
                f"(label 은 빈 칸 — 배경 자산 단계에서 채웁니다)")

        log(f"  {absolute}화 통과 · {cuts_summary(cuts)}")
        log(f"    -> {cut_path}")

    calls.flush(usage)      # 예외로 끊긴 호출도 원장에 남긴다
    log(f"컷 {done}화 · {usage.cost_line()}")
    return 0


def append_webtoon_summary(out_dir: Path, result: WebtoonResult) -> None:
    # 파일이 잠겨 있어도 실행을 죽이지 않는다 (story.append_csv_row 주석 참고).
    append_csv_row(out_dir / "webtoon_summary.csv",
                   WEBTOON_SUMMARY_COLUMNS, result.as_row())


# ---------------------------------------------------------------- 화면

def collect_webtoon(run_dir: Path) -> dict:
    wt = run_dir / "webtoon"
    if not (wt / "arcs.json").exists():
        return {}
    arcs = json.loads((wt / "arcs.json").read_text(encoding="utf-8")).get("arcs") or []
    episodes, cuts = [], {}
    for path in sorted(wt.glob("arc*_episodes.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        for e in payload.get("episodes") or []:
            episodes.append((payload.get("arc_order"), e))
    for path in sorted(wt.glob("ep*_cuts.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        cuts[payload.get("_absolute_episode")] = payload
    ledger = {}
    if (wt / "ledger.json").exists():
        ledger = json.loads((wt / "ledger.json").read_text(encoding="utf-8"))
    calls = []
    if (wt / "calls.jsonl").exists():
        for line in (wt / "calls.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                calls.append(json.loads(line))
    meta = {}
    if (wt / "meta.json").exists():
        meta = json.loads((wt / "meta.json").read_text(encoding="utf-8"))
    series = {}
    if (wt / "series.json").exists():
        series = json.loads((wt / "series.json").read_text(encoding="utf-8"))
    card = ""
    if (wt / "engine_card.txt").exists():
        card = (wt / "engine_card.txt").read_text(encoding="utf-8")
    return {"arcs": arcs, "episodes": episodes, "cuts": cuts, "card": card,
            "ledger": ledger, "calls": calls, "meta": meta, "series": series}


# 여백은 눈에 보여야 눈으로 검사된다. 표 안에서 길이가 곧 크기다.
GAP_BAR = {0: "", 1: "·", 2: "··", 3: "····"}
SIZE_RATIO = {"wide": "16:9", "normal": "4:3", "tall": "3:4", "impact": "9:16"}


def size_cell(c: dict) -> str:
    """컷 표에 넣을 크기 한 칸: 기호 · 이름 · 비율."""
    key = str(c.get("size") or "").strip().lower()
    if key not in SIZES:
        return "?"
    return f"{SIZE_MARK[key]} {key} {SIZE_RATIO[key]}"


def dialogue_cell(c: dict) -> str:
    """컷 표에 넣을 대사 한 칸: 화자 · 대사. 여러 줄이면 " / " 로 잇는다.

    한 컷에 둘이 주고받을 수 있게 된 뒤로 첫 줄만 보이면 표가 거짓말을 한다 —
    사람이 화를 훑을 때 그 칸에서 주고받음을 못 본다.
    """
    parts = []
    for row in speech_lines(c):
        if row["kind"] == "narration":
            continue          # 나레이션은 대사 칸이 아니다
        who = row["speaker"]
        text = row["text"]
        parts.append(f"{who}: {text}" if who else text)
    return " / ".join(parts)


def camera_cell(c: dict) -> str:
    """컷 표에 넣을 카메라 한 칸: 거리 · 앵글 · 전환 · 칸 쓰기."""
    if not c:
        return ""
    shot = str(c.get("shot") or "").strip()
    angle = str(c.get("angle") or "").strip()
    tran = str(c.get("transition") or "").strip()
    render = str(c.get("render_style") or "").strip().lower()
    mark = RENDER_MARK.get(render, "")
    if not (shot or angle or tran):
        # 카메라 축이 생기기 전에 만든 run — 빈 칸이 맞다. 물음표를 찍으면
        # 값이 깨진 것처럼 보인다.
        return "—"
    return f"{shot or '?'}·{angle or '?'}" + (f" ⇢{tran}" if tran else "") + \
           (f" {mark}{render}" if render and render != "normal" else "")


def direction_cell(c: dict) -> str:
    """컷 표에 넣을 연출 한 칸: beat · 여백 · 시선 · 화면 경계."""
    if not c:
        return ""
    gap = c.get("gap_after")
    gap = gap if isinstance(gap, int) and 0 <= gap <= MAX_GAP else 1
    return (f"{c.get('beat') or '?'} {GAP_BAR[gap]}{gap} {c.get('gaze') or ''}"
            + (" ⏎" if c.get("scene_break") else ""))


# 서사적 중요도를 표에서 한눈에 보이게. 8단계가 판정한 값이고(NARRATIVE_WEIGHTS),
# 지면 무게(full/light)는 그것에서 나온 결과다 — 그래서 둘을 같이 보여 준다.
# 판정이 맞는지는 사람이 컷 서술과 나란히 놓고 봐야 안다.
NARRATIVE_MARK = {"major": "●●● 중요", "normal": "●● 보통", "minor": "● 가벼움"}


def narrative_cell(c: dict) -> str:
    """컷 표에 넣을 중요도 한 칸: 중요도 · 그것이 만든 지면 무게."""
    if not c:
        return ""
    nw = str(c.get("narrative_weight") or "").strip().lower()
    label = NARRATIVE_MARK.get(nw, "—")
    weight = str(c.get("weight") or "").strip()
    return f"{label}" + (f" · {weight}" if weight else "")


def build_webtoon_output(run_dir: Path) -> None:
    data = collect_webtoon(run_dir)
    if not data:
        return
    wt = run_dir / "webtoon"
    _write_webtoon_md(wt, data)
    _write_webtoon_html(wt, data)


# 엔진 카드에서 뽑아 보여줄 줄. 카드는 우리가 만든 형식이라 앞머리 라벨이 고정이다.
CARD_FIXED = ("[로그라인]", "[엔진급 질문]", "[낙차]", "[장르 배신]", "[RULE]",
              "[COST]", "[IRONY]")


def fixed_assets_block(card_text: str, arcs: list) -> list:
    """**작품 자체**가 가진 것 — 2화도 3화도 이걸 받고 시작한다.

    회차가 남긴 것(carryover_block)과 나눠 놓은 이유: 이쪽은 화가 지나도 안 바뀌는
    고정 자산이다. 훅·엔진·주인공·전체 줄거리가 여기 있고, 프롬프트에는 매 화
    엔진 카드와 series_arc 로 실려 나간다. 화면에서도 같이 보여야 "2화가 이걸
    받는가" 를 눈으로 확인할 수 있다.
    """
    out = []
    lines = [ln.rstrip() for ln in str(card_text or "").splitlines()]
    picked = [ln.strip() for ln in lines
              if any(ln.strip().startswith(h) for h in CARD_FIXED)]
    who = [ln.strip() for ln in lines
           if ln.strip().startswith("[주인공]") or ln.strip().startswith("그 한 사람:")]
    if not (picked or who or arcs):
        return out

    out += ["## 작품이 가진 것 (모든 화가 물려받는다)", ""]
    if picked:
        out.append("**엔진과 훅**")
        for ln in picked:
            out.append(f"- {ln}")
        out.append("")
    if who:
        out.append("**중심 인물**")
        for ln in who:
            out.append(f"- {ln}")
        out.append("")
    if arcs:
        out.append("**전체 줄거리**")
        for a in arcs:
            if not isinstance(a, dict):
                continue
            n = a.get("estimated_episode_count")
            span = f"{n}화" if isinstance(n, int) and n > 0 else "?화"
            out.append(f"- Arc {a.get('order')}. {_fmt(a.get('title'))} "
                       f"({_fmt(a.get('arc_type'))} · {span})")
        out.append("")
    return out


def carryover_block(series: dict, ledger: dict) -> list:
    """다음 화가 물려받는 것 — 인물·설정·장소·열린 질문·몸에 남은 자국.

    위 fixed_assets_block 이 **작품**이 가진 것이라면 여기는 **지나온 화들**이
    쌓아 놓은 것이다. 지금 만드는 것은 1화 하나지만, 1화의 결과물은 컷 목록만이
    아니다. 여기 쌓인 것이 다음 화를 쓸 때 쓰는 재료이고, 비어 있으면 2화가 1화를
    잊는다. 파일(series.json·ledger.json)에는 늘 있었지만 사람이 열어 볼 일이
    없어서 확인이 안 됐다.
    """
    out = []
    cast = series.get("cast") or []
    facts = series.get("facts") or []
    places = series.get("places") or []
    status = series.get("status") or []
    opened = [q for q in (ledger.get("questions") or [])
              if isinstance(q, dict) and not q.get("closedAt")]
    if not any((cast, facts, places, status, opened)):
        return out

    out += ["## 다음 화가 물려받는 것", ""]
    if cast:
        out.append("**인물**")
        for c in cast:
            note = f" — {c.get('note')}" if c.get("note") else ""
            out.append(f"- {c.get('name')} ({c.get('first_episode')}화){note}")
        out.append("")
    if places:
        out.append("**장소** · "
                   + " / ".join(f"{p.get('place')}({p.get('first_episode')}화)"
                                for p in places))
        out.append("")
    if facts:
        out.append("**확정된 설정**")
        for f in facts:
            out.append(f"- {f.get('fact')} ({f.get('first_episode')}화)")
        out.append("")
    if status:
        out.append("**몸에 남은 것**")
        for st in status:
            until = f" (~{st.get('until')})" if st.get("until") else ""
            out.append(f"- {st.get('who')}: {st.get('what')}{until}")
        out.append("")
    if opened:
        out.append("**아직 열려 있는 질문**")
        for q in opened:
            at = (q.get("openedAt") or {}).get("episode")
            when = f"{at}화에 열림, " if at else ""          # 엔진급은 0화라 안 적는다
            out.append(f"- [{q.get('id')}] {q.get('text')} ({when}{q.get('type')})")
        out.append("")
    return out


def _write_webtoon_md(wt: Path, data: dict) -> None:
    out = ["# 웹툰 구성", "", "## 큰 줄거리", ""]
    for a in data["arcs"]:
        out.append(f"### Arc {a.get('order')}. {a.get('title')}  [{a.get('arc_type')}]")
        out.append(f"- 주 동력: {', '.join(a.get('premise_element_used') or [])}"
                   f" · 예상 {a.get('estimated_episode_count')}화")
        out.append(f"- {a.get('summary')}")
        # 압력 세 칸은 이 칸이 생기기 전 run 에는 없다 — 없으면 줄 자체가 안 나온다.
        for key, label in (("starts_with", "시작 상태"), ("pressure", "조이는 힘"),
                           ("ends_with", "끝 상태")):
            if not is_blank(a.get(key)):
                out.append(f"  - {label}: {a.get(key)}")
        for x in a.get("not_yet") or []:
            out.append(f"  - 아직 아님: {x}")
        for q in a.get("opens") or []:
            out.append(f"  - 여는 질문: {q}")
        for q in a.get("closes") or []:
            out.append(f"  - 닫는 질문: {q}")
        out.append("")

    out.append("## 회차")
    out.append("")
    absolute = 0
    for arc_order, e in data["episodes"]:
        absolute += 1
        out.append(f"### {absolute}화 — {e.get('title')}  (Arc {arc_order})")
        out.append(f"{e.get('summary')}")
        out.append(f"- 발동 엔진: {', '.join(e.get('engine_fired') or [])}")
        for q in e.get("questions_opened") or []:
            out.append(f"- 여는 질문[{q.get('type')}]: {q.get('text')}")
        for c in e.get("questions_closed") or []:
            mark = "배신형" if c.get("is_betrayal") else "공개형"
            out.append(f"- 닫는 질문[{mark}] {c.get('ledger_id')}: {c.get('answer')}")
        stinger = e.get("stinger") or {}
        out.append(f"- 마지막 장면: {stinger.get('text')}")
        out.append("")

        payload = data["cuts"].get(absolute)
        if not payload:
            continue
        engine_cuts = {r.get("cut_number") for r in payload.get("engine_cut_refs") or []
                       if isinstance(r, dict)}
        stinger_cut = payload.get("stinger_cut_number")
        cut_list = payload.get("cuts") or []

        # 장면의 의도를 컷 표보다 **먼저** 놓는다. 사람이 콘티를 볼 때 처음 묻는 것이
        # "이게 무슨 장면이냐" 이고, 컷은 그 답을 어떻게 보여줄지일 뿐이다.
        scenes = payload.get("scenes")
        if isinstance(scenes, list) and scenes:
            out.append("**장면**")
            out.append("")
            at = 0
            for i, sc in enumerate(scenes, 1):
                if not isinstance(sc, dict):
                    continue
                last = sc.get("last_cut")
                span = (f"컷 {at + 1}~{last}"
                        if isinstance(last, int) and last > at else "컷 ?")
                at = last if isinstance(last, int) else at
                out.append(f"{i}. ({span}) {_fmt(sc.get('what'))}")
                out.append(f"   분위기 · {_fmt(sc.get('mood'))}")
            out.append("")

        out.append("| # | 크기 | 카메라 | 화면 | 대사 | 연출 | |")
        out.append("| --- | --- | --- | --- | --- | --- | --- |")
        for c in cut_list:
            marks = []
            if c.get("cut_number") in engine_cuts:
                marks.append("엔진")
            if c.get("cut_number") == stinger_cut:
                marks.append("스팅어")
            if c.get("reader_only"):
                marks.append("독자우위")
            out.append(f"| {c.get('cut_number')} | {size_cell(c)} "
                       f"| {camera_cell(c)} "
                       f"| {str(c.get('description') or '').strip()} "
                       f"| {dialogue_cell(c)} "
                       f"| {direction_cell(c)} "
                       f"| {' '.join(marks)} |")
        hist = size_histogram(cut_list)
        cam = camera_histogram(cut_list)
        out.append("")
        out.append("크기 " + " / ".join(f"{k} {v}" for k, v in hist.items() if v)
                   + " · Scene " + "-".join(str(s) for s in scene_sizes(cut_list)) + "컷")
        if any(cam["shot"].values()):    # 카메라 축이 없던 옛 run 은 건너뛴다
            out.append("거리 "
                       + " / ".join(f"{k} {v}" for k, v in cam["shot"].items() if v)
                       + f" · 얼굴 {int(cam['face_ratio'] * 100)}%")
            out.append("앵글 "
                       + " / ".join(f"{k} {v}" for k, v in cam["angle"].items() if v)
                       + " · 전환 "
                       + " / ".join(f"{k} {v}"
                                    for k, v in cam["transition"].items() if v))
        out.append("연출: beat · 여백(0 붙임 ~ 3 화면 하나) · 시선 · ⏎ 는 화면(Scene) 경계")
        out.append("")

    out += fixed_assets_block(data.get("card") or "", data.get("arcs") or [])
    out += carryover_block(data.get("series") or {}, data.get("ledger") or {})
    (wt / "webtoon.md").write_text("\n".join(out), encoding="utf-8")


def _esc(x) -> str:
    return html.escape(str(x if x is not None else ""))


# 크기는 글자로 읽는 것이 아니라 눈으로 보는 것이다. 표 안에 실제 비율의 네모를 둔다.
SIZE_BOX = {"wide": (46, 26), "normal": (36, 27), "tall": (26, 35), "impact": (22, 39)}


def _size_box(c: dict) -> str:
    key = str(c.get("size") or "").strip().lower()
    if key not in SIZE_BOX:
        return '<span class="meta">?</span>'
    w, h = SIZE_BOX[key]
    return (f'<span class="sizebox" style="width:{w}px;height:{h}px"></span>'
            f'<span class="meta"> {key}<br>{SIZE_RATIO[key]}</span>')


def _write_webtoon_html(wt: Path, data: dict) -> None:
    parts = []

    parts.append('<section><h2>큰 줄거리</h2>')
    for a in data["arcs"]:
        elems = ", ".join(a.get("premise_element_used") or [])
        parts.append(
            f'<article class="arc"><h3>Arc {_esc(a.get("order"))}. {_esc(a.get("title"))}'
            f'<span class="tag t-{_esc(a.get("arc_type"))}">{_esc(a.get("arc_type"))}</span></h3>'
            f'<p class="meta">주 동력 {_esc(elems)} · 예상 {_esc(a.get("estimated_episode_count"))}화</p>'
            f'<p>{_esc(a.get("summary"))}</p>')
        for key, label in (("starts_with", "시작 상태"), ("pressure", "조이는 힘"),
                           ("ends_with", "끝 상태")):
            if not is_blank(a.get(key)):
                parts.append(f'<p class="meta">{label} · {_esc(a.get(key))}</p>')
        for x in a.get("not_yet") or []:
            parts.append(f'<p class="meta">아직 아님 · {_esc(x)}</p>')
        for q in a.get("opens") or []:
            parts.append(f'<p class="q open">여는 질문 · {_esc(q)}</p>')
        for q in a.get("closes") or []:
            parts.append(f'<p class="q close">닫는 질문 · {_esc(q)}</p>')
        parts.append("</article>")
    parts.append("</section>")

    parts.append('<section><h2>회차와 컷</h2>')
    absolute = 0
    for arc_order, e in data["episodes"]:
        absolute += 1
        stinger = e.get("stinger") or {}
        parts.append(
            f'<article class="ep"><h3>{absolute}화 — {_esc(e.get("title"))}'
            f'<span class="meta">Arc {_esc(arc_order)} · 엔진 '
            f'{_esc(", ".join(e.get("engine_fired") or []))}</span></h3>'
            f'<p>{_esc(e.get("summary"))}</p>')
        for q in e.get("questions_opened") or []:
            parts.append(
                f'<p class="q open">여는 질문 <b>{_esc(q.get("type"))}</b> · '
                f'{_esc(q.get("text"))}</p>')
        for c in e.get("questions_closed") or []:
            mark = "배신형 상환" if c.get("is_betrayal") else "공개형 상환"
            parts.append(
                f'<p class="q close">{mark} <b>{_esc(c.get("ledger_id"))}</b> · '
                f'{_esc(c.get("answer"))}</p>')
        parts.append(f'<p class="stinger">마지막 장면 · {_esc(stinger.get("text"))}</p>')

        payload = data["cuts"].get(absolute)
        if payload:
            engine_cuts = {r.get("cut_number") for r in payload.get("engine_cut_refs") or []
                           if isinstance(r, dict)}
            stinger_cut = payload.get("stinger_cut_number")
            cut_list = payload.get("cuts") or []
            scenes = payload.get("scenes")
            if isinstance(scenes, list) and scenes:
                at = 0
                rows = []
                for i, sc in enumerate(scenes, 1):
                    if not isinstance(sc, dict):
                        continue
                    last = sc.get("last_cut")
                    span = (f"컷 {at + 1}~{last}"
                            if isinstance(last, int) and last > at else "컷 ?")
                    at = last if isinstance(last, int) else at
                    rows.append(
                        f'<p class="q"><b>{i}. {_esc(span)}</b> '
                        f'{_esc(sc.get("what"))}<br>'
                        f'<span class="meta">분위기 · {_esc(sc.get("mood"))}</span></p>')
                parts.append("".join(rows))
            parts.append('<div class="scroll"><table><thead><tr>'
                         '<th>#</th><th>중요도</th><th>크기</th><th>카메라</th>'
                         '<th>화면에 보이는 것</th><th>대사</th>'
                         '<th>연출</th><th></th></tr></thead><tbody>')
            for c in cut_list:
                n = c.get("cut_number")
                cls = []
                if n in engine_cuts:
                    cls.append("engine")
                if n == stinger_cut:
                    cls.append("stinger")
                eye = "👁" if c.get("reader_only") else ""
                gap = c.get("gap_after")
                gap = gap if isinstance(gap, int) and 0 <= gap <= MAX_GAP else 1
                parts.append(
                    f'<tr class="{" ".join(cls)}"><td>{_esc(n)}</td>'
                    f'<td class="nw nw-{_esc(str(c.get("narrative_weight") or "none"))}">'
                    f'{_esc(narrative_cell(c))}</td>'
                    f'<td>{_size_box(c)}</td>'
                    f'<td class="meta">{_esc(camera_cell(c))}</td>'
                    f'<td>{_esc(c.get("description"))}</td>'
                    f'<td>{_esc(dialogue_cell(c))}</td>'
                    f'<td class="meta">{_esc(direction_cell(c))}</td>'
                    f'<td>{eye}</td></tr>')
                # 여백은 표에서도 여백으로 보여야 한다 — 숫자만으로는 리듬이 안 보인다.
                if gap and n != stinger_cut:
                    parts.append(f'<tr class="gap g{gap}"><td colspan="7"></td></tr>')
            parts.append("</tbody></table></div>")
            # 9단계가 정한 화면 묶음. 표만 보면 컷이 어디서 끊겨 한 장이 되는지
            # 안 보인다 — 판정이 맞는지는 묶음과 바탕 컷을 같이 봐야 안다.
            pages = payload.get("pages") or []
            if pages:
                rows = []
                for i, pg in enumerate(pages, 1):
                    nums = pg.get("cuts") or []
                    base = pg.get("base")
                    rows.append(
                        f'<li><b>{i}장</b> 컷 '
                        + _esc("·".join(str(x) for x in nums))
                        + f' <span class="nw-major">바탕 {_esc(base)}</span>'
                        + (f'<br><span class="meta">{_esc(pg.get("why"))}</span>'
                           if pg.get("why") else "")
                        + "</li>")
                parts.append('<p class="meta"><b>화면 묶기 (9단계)</b> — '
                             f'{len(pages)}장</p><ul class="pages">'
                             + "".join(rows) + "</ul>")
            hist = size_histogram(cut_list)
            parts.append(
                '<p class="meta">크기 '
                + _esc(" / ".join(f"{k} {v}" for k, v in hist.items() if v))
                + ' · Scene '
                + _esc("-".join(str(s) for s in scene_sizes(cut_list)))
                + '컷 · 연출 = beat · 여백(0 붙임 ~ 3 화면 하나) · 시선 · '
                '⏎ 는 화면(Scene) 경계</p>')
        else:
            parts.append('<p class="meta">컷 미생성</p>')
        parts.append("</article>")
    parts.append("</section>")

    # 질문 장부
    questions = (data.get("ledger") or {}).get("questions") or []
    parts.append('<section><h2>질문 장부</h2><div class="scroll"><table><thead><tr>'
                 '<th>id</th><th>질문</th><th>유형</th><th>열림</th><th>닫힘</th>'
                 '<th>상환예정</th></tr></thead><tbody>')
    for q in questions:
        opened = q.get("openedAt") or {}
        closed = q.get("closedAt")
        cls = "engine" if q.get("isEngine") else ("" if closed else "open-debt")
        parts.append(
            f'<tr class="{cls}"><td>{_esc(q.get("id"))}</td><td>{_esc(q.get("text"))}</td>'
            f'<td>{_esc(q.get("type"))}</td>'
            f'<td>{_esc(opened.get("episode"))}화</td>'
            f'<td>{"—" if not closed else _esc(closed.get("episode")) + "화"}'
            f'{" · 배신형" if q.get("isBetrayal") else ""}</td>'
            f'<td>{_esc(q.get("plannedPayoffEpisode") or "—")}</td></tr>')
    parts.append("</tbody></table></div></section>")

    # AI를 부른 기록. 토큰은 입력/출력을 나눠 보여준다 — 출력이 단가가 몇 배
    # 비싸서, 합계만 보면 어디서 돈이 나갔는지 알 수 없다.
    parts.append('<section><h2>AI를 부른 기록</h2><div class="scroll">'
                 '<table><thead><tr><th>단계</th><th>내용</th><th>모델</th>'
                 '<th>온도</th><th>초</th><th>입력</th><th>출력</th>'
                 '<th>비용(USD)</th><th>판정</th></tr></thead><tbody>')
    call_total = 0.0
    for c in data["calls"]:
        tok = c.get("tokens") or {}
        cost = c.get("cost_usd")
        if isinstance(cost, dict):
            call_total += cost.get("total") or 0.0
            cost_txt = f'{cost.get("total", 0):.4f}'
        else:
            cost_txt = "단가 없음"
        parts.append(
            f'<tr><td>{_esc(c.get("stage"))}</td><td>{_esc(c.get("label"))}</td>'
            f'<td>{_esc(c.get("model"))}</td><td>{_esc(c.get("temperature"))}</td>'
            f'<td>{_esc(c.get("seconds"))}</td>'
            f'<td>{_esc(tok.get("input"))}</td><td>{_esc(tok.get("output"))}</td>'
            f'<td>{_esc(cost_txt)}</td>'
            f'<td>{_esc(c.get("verdict"))}</td></tr>')
    if data["calls"]:
        parts.append(f'<tr><td colspan="7"><b>합계</b></td>'
                     f'<td><b>{call_total:.4f}</b></td><td></td></tr>')
    parts.append("</tbody></table></div></section>")

    meta = data.get("meta") or {}
    banner = ""
    if meta.get("status") and meta["status"] != STATUS_OK:
        banner = (f'<div class="banner">{_esc(meta["status"])} — '
                  f'{_esc(meta.get("note"))}</div>')
    if meta.get("mock"):
        banner += '<div class="banner mock">MOCK 실행 — 실제 모델 결과가 아닙니다</div>'

    page = (WEBTOON_TEMPLATE
            .replace("__BANNER__", banner)
            .replace("__BODY__", "\n".join(parts)))
    (wt / "webtoon.html").write_text(page, encoding="utf-8")


WEBTOON_TEMPLATE = """<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>웹툰 구성</title>
<style>
  :root { --bg:#fbfaf8; --fg:#1c1b19; --muted:#6b6862; --line:#e2ded6; --card:#fff;
          --engine:#e8f1fb; --engine-line:#3b7dd8; --stinger:#fdf4dc; --stinger-line:#c9971f;
          --accent:#2f6f5e; --warn:#b4462f; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#16171a; --fg:#e8e6e1; --muted:#9b978f; --line:#2c2e33; --card:#1d1f23;
            --engine:#16283d; --engine-line:#5b9bef; --stinger:#332a12; --stinger-line:#e0b445;
            --accent:#6fbfa6; --warn:#e2765c; }
  }
  :root[data-theme="dark"] {
    --bg:#16171a; --fg:#e8e6e1; --muted:#9b978f; --line:#2c2e33; --card:#1d1f23;
    --engine:#16283d; --engine-line:#5b9bef; --stinger:#332a12; --stinger-line:#e0b445;
    --accent:#6fbfa6; --warn:#e2765c;
  }
  :root[data-theme="light"] {
    --bg:#fbfaf8; --fg:#1c1b19; --muted:#6b6862; --line:#e2ded6; --card:#fff;
    --engine:#e8f1fb; --engine-line:#3b7dd8; --stinger:#fdf4dc; --stinger-line:#c9971f;
    --accent:#2f6f5e; --warn:#b4462f;
  }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); line-height:1.7; font-size:15px;
         font-family:"Apple SD Gothic Neo","Noto Sans KR",system-ui,-apple-system,sans-serif; }
  main { max-width:56rem; margin:0 auto; padding:2.5rem 1.25rem 6rem; }
  h1 { font-size:1.3rem; margin:0 0 .3rem; }
  h2 { font-size:1.05rem; margin:3rem 0 1rem; padding-bottom:.5rem;
       border-bottom:1px solid var(--line); letter-spacing:.02em; }
  h3 { font-size:1rem; margin:0 0 .5rem; display:flex; flex-wrap:wrap; gap:.6rem;
       align-items:baseline; }
  p { margin:.35rem 0; overflow-wrap:break-word; }
  .arc, .ep { background:var(--card); border:1px solid var(--line); border-radius:10px;
              padding:1.25rem 1.4rem; margin:1rem 0; }
  .meta { color:var(--muted); font-size:.85rem; font-weight:400; }
  .tag { font-size:.72rem; padding:.1rem .5rem; border-radius:99px; border:1px solid var(--line);
         color:var(--muted); }
  .tag.t-반전 { color:var(--warn); border-color:var(--warn); }
  .q { font-size:.9rem; padding-left:.8rem; border-left:2px solid var(--line); }
  .q.open { border-left-color:var(--accent); }
  .q.close { border-left-color:var(--muted); color:var(--muted); }
  .stinger { font-size:.9rem; margin-top:.6rem; padding:.5rem .8rem; border-radius:6px;
             background:var(--stinger); border-left:3px solid var(--stinger-line); }
  .scroll { overflow-x:auto; margin-top:1rem; }
  table { border-collapse:collapse; width:100%; min-width:34rem; font-size:.88rem; }
  th, td { text-align:left; padding:.5rem .7rem; border-bottom:1px solid var(--line);
           vertical-align:top; }
  th { color:var(--muted); font-weight:600; font-size:.8rem; }
  td:first-child { width:2.5rem; color:var(--muted); }
  tr.engine { background:var(--engine); box-shadow:inset 3px 0 0 var(--engine-line); }
  tr.stinger { background:var(--stinger); box-shadow:inset 3px 0 0 var(--stinger-line); }
  tr.open-debt td:first-child { color:var(--accent); font-weight:700; }
  tr.engine td:first-child { font-weight:700; }
  .sizebox { display:inline-block; vertical-align:middle; border:1.5px solid var(--muted);
             border-radius:2px; background:var(--line); }
  td:nth-child(2) { width:7.5rem; white-space:nowrap; }
  /* 서사적 중요도 — 8단계가 판정한 값. 지면 무게(full/light)는 여기서 나온다. */
  td.nw { white-space:nowrap; font-size:.82rem; font-weight:600; }
  td.nw-major  { color:#b3261e; background:rgba(179,38,30,.07); }
  td.nw-minor  { color:#7a8a94; }
  td.nw-normal { color:#3a4a55; }
  td.nw-none   { color:#c0c8cd; }
  ul.pages { margin:.3rem 0 .8rem; padding-left:1.2rem; font-size:.86rem; }
  ul.pages li { margin:.25rem 0; }
  tr.gap td { border-bottom:none; padding:0; }
  tr.gap.g1 td { height:.5rem; }
  tr.gap.g2 td { height:1.6rem; }
  tr.gap.g3 td { height:3.5rem; }
  .banner { background:var(--warn); color:#fff; padding:.7rem 1rem; border-radius:8px;
            margin-bottom:1.5rem; font-size:.9rem; }
  .banner.mock { background:var(--muted); }
  .legend { color:var(--muted); font-size:.85rem; margin-top:.5rem; }
</style>
</head>
<body>
<main>
  <h1>웹툰 구성</h1>
  <p class="legend">파란 줄 = 설정이 발동하는 컷 · 노란 줄 = 다음 화를 부르는 마지막 컷 ·
     👁 = 독자만 아는 정보</p>
  __BANNER__
  __BODY__
</main>
</body>
</html>
"""


# ---------------------------------------------------------------- CLI

def list_candidates(out_dir: Path) -> None:
    rows = []
    for run_dir in sorted(out_dir.iterdir()):
        if not run_dir.is_dir() or not (run_dir / "meta.json").exists():
            continue
        meta = json.loads((run_dir / "meta.json").read_text(encoding="utf-8"))
        if meta.get("condition") != "pipeline":
            continue
        if not (run_dir / "scenes.json").exists():
            continue
        verdict = human_passed(out_dir, meta["run_id"])
        rows.append((
            meta["run_id"],
            meta.get("input", {}).get("character", ""),
            meta.get("input", {}).get("genre", ""),
            meta.get("status", ""),
            {True: "예", False: "아니오", None: "미평가"}[verdict],
            "있음" if (run_dir / "webtoon" / "arcs.json").exists() else "",
        ))
    if not rows:
        log("장면까지 나온 파이프라인 실행이 없습니다. 먼저 story.py 를 돌리세요.")
        return
    log(f"{'run_id':<26}{'인물':<12}{'장르':<12}{'상태':<14}{'사람판정':<10}웹툰")
    for r in rows:
        log(f"{r[0]:<26}{r[1]:<12}{r[2]:<12}{r[3]:<14}{r[4]:<10}{r[5]}")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="웹툰 생성 파이프라인 하네스 (4~7단계)",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", default=None, help="스토리 파이프라인 run_id")
    p.add_argument("--all-passed", action="store_true",
                   help="사람이 '예' 로 통과시킨 실행을 전부 웹툰으로")
    p.add_argument("--list", action="store_true", help="웹툰으로 만들 수 있는 실행 목록")
    p.add_argument("--stats", action="store_true",
                   help="화별 컷 지표를 한 표로 (--run 과 함께). 프롬프트를 "
                        "바꾼 뒤 앞 화와 비교할 때 쓴다")
    p.add_argument("--build-view", action="store_true",
                   help="이미 만들어진 산출물로 webtoon.md/html 만 다시 생성")
    p.add_argument("--episodes", type=int, default=1, metavar="N",
                   help="이번에 만들 화 수 (기본 1). 다시 부르면 다음 화부터 "
                        "이어서 만듭니다 — 연재 상태는 webtoon/series.json 에 남습니다")
    p.add_argument("--resume", action="store_true", help="이미 있는 단계 산출물을 재사용")
    p.add_argument("--replan", action="store_true",
                   help="Arc(큰 줄거리)를 다시 잡는다. 기본은 arcs.json 재사용 — "
                        "화마다 Arc 가 바뀌면 앞 화와 방향이 어긋난다")
    p.add_argument("--cuts-only", action="store_true",
                   help="이미 있는 회차로 7단계(컷)만 다시 돌림 (화당 1회 호출)")
    p.add_argument("--episode", type=int, action="append", default=[],
                   help="--cuts-only 를 특정 화에만 (여러 번 쓸 수 있음)")
    p.add_argument("--skip-human-gate", action="store_true",
                   help="사람 판정 없이 진행 (문서: 재미없는 설계로 컷을 뽑아도 의미가 없다)")
    p.add_argument("--provider", default=None, choices=sorted(PROVIDERS),
                   help=f".env 의 PROVIDER 를 덮어씀 (현재 {DEFAULT_PROVIDER})")
    p.add_argument("--model", default=None,
                   help=f".env 의 모델을 덮어씀 (현재 {DEFAULT_MODEL})")
    p.add_argument("--judge-model", default=None, help="6단계 전용 모델 (기본: --model)")
    p.add_argument("--max-tokens", type=int, default=DEFAULT_MAX_TOKENS)
    p.add_argument("--max-retries", type=int, default=2,
                   help="재생성 상한 (기본 2). 4·5단계는 실행 전체, "
                        "7단계는 화마다 이만큼")
    p.add_argument("--ledger-cap", type=int, default=DEFAULT_LEDGER_CAP,
                   help=f"떡밥 상한 (기본 {DEFAULT_LEDGER_CAP})")
    p.add_argument("--out", default=str(RUNS_DIR), help="runs 디렉터리")
    p.add_argument("--check", action="store_true", help="API 없이 프롬프트 변수만 점검")
    p.add_argument("--mock", action="store_true", help="API 없이 게이트·장부만 점검")
    # 작가가 콘티를 보고 "이걸 고쳐 달라"고 적은 말. W4·W5·W7·W8 프롬프트의
    # {retry_feedback} 자리에 [작가 요청] 블록으로 들어간다. 안 주면 예전과 똑같다.
    p.add_argument("--author-note", default="",
                   help="작가가 다시 만들며 요청한 것 (W4·W5·W7·W8 프롬프트에 실림)")
    return p


def print_episode_stats(out_dir: Path, run_id: str) -> None:
    """화별 컷 지표를 한 표로 — 프롬프트를 바꿨을 때 무엇이 달라졌는지 보는 자리.

    webtoon.md 는 화마다 따로 적어서 화끼리 비교가 안 된다. 프롬프트를 고치고
    나면 궁금한 것은 언제나 "앞 화보다 나빠졌나" 라서, 세로로 세워 준다.
    괄호 안은 게이트 한도다 — 넘으면 애초에 여기까지 오지 않는다.
    """
    wt = out_dir / run_id / "webtoon"
    paths = sorted(wt.glob("ep*_cuts.json"))
    if not paths:
        raise SystemExit(f"{run_id} 에 컷 파일이 없습니다: {wt}")

    log(f"[{run_id}]")
    log(f"  {'화':<4}{'컷':>3}{'얼굴':>7}{'수평':>7}{'말':>8}"
        f"{'전환':>6}{'sd':>5}{'인서트':>6}{'원경':>5}   w7 프롬프트")
    log(f"  {'':<5}{'':>4}{'≤55%':>7}{'≤70%':>8}{'≥50%':>8}"
        f"{'≥3':>6}{'≤5':>5}{'≥1':>6}{'≥1':>6}   ← 게이트 한도")
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        cuts = [c for c in data.get("cuts") or [] if isinstance(c, dict)]
        if not cuts:
            continue
        n = len(cuts)
        shots = [str(c.get("shot") or "") for c in cuts]
        face = sum(1 for s in shots if s in FACE_SHOTS)
        level = sum(1 for c in cuts if str(c.get("angle") or "") == ANGLES[0])
        said = sum(1 for c in cuts if has_speech(c))
        kinds = len({str(c.get("transition") or "") for c in cuts})
        sd = sum(1 for c in cuts if str(c.get("render_style") or "") == "sd")
        insert = sum(1 for s in shots if s == SHOTS[-1])    # 인서트
        wide = sum(1 for s in shots if s == SHOTS[0])       # 원경
        # 어느 프롬프트로 뽑은 화인지. 이게 없으면 표를 봐도 원인을 못 짚는다.
        stamp = str(data.get("_prompt_w7") or "(기록 없음)")
        log(f"  {path.stem.replace('_cuts', ''):<5}{n:>4}{face * 100 // n:>7}%"
            f"{level * 100 // n:>7}%{said * 100 // n:>7}%"
            f"{kinds:>7}{sd:>5}{insert:>7}{wide:>6}   {stamp}")


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    out_dir = Path(args.out).resolve()

    if args.list:
        list_candidates(out_dir)
        return 0

    if args.stats:
        if not args.run:
            raise SystemExit("--stats 에는 --run RUN_ID 가 필요합니다.")
        print_episode_stats(out_dir, args.run)
        return 0

    ps = load_prompts(contract=WEBTOON_CONTRACT)
    if args.check:
        log("웹툰 프롬프트 변수 점검 완료. 위에 경고가 없으면 계약이 맞습니다.")
        log(f"해시: {json.dumps(ps.short_hashes, ensure_ascii=False, indent=2)}")
        return 0

    targets = []
    if args.all_passed:
        for run_dir in sorted(out_dir.iterdir()):
            if not run_dir.is_dir() or not (run_dir / "scenes.json").exists():
                continue
            meta_path = run_dir / "meta.json"
            if not meta_path.exists():
                continue
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            if meta.get("condition") == "pipeline" and \
                    human_passed(out_dir, meta["run_id"]) is True:
                targets.append(run_dir)
        if not targets:
            raise SystemExit(
                "사람이 '예' 로 통과시킨 실행이 없습니다. "
                "python story.py --serve 로 블라인드 평가를 먼저 받으세요.")
    elif args.run:
        run_dir = out_dir / args.run
        if not run_dir.is_dir():
            raise SystemExit(f"실행 디렉터리가 없습니다: {run_dir}")
        targets = [run_dir]
    else:
        raise SystemExit("--run <run_id> 또는 --all-passed 또는 --list 를 지정하세요.")

    if args.build_view:
        for run_dir in targets:
            build_webtoon_output(run_dir)
            log(f"{run_dir.name}: webtoon.md / webtoon.html 재생성")
        return 0

    # 사람 판정 게이트 — 순서를 지켜야 한다 (문서 1장)
    if not args.skip_human_gate:
        blocked = []
        for run_dir in targets:
            verdict = human_passed(out_dir, run_dir.name)
            if verdict is not True:
                blocked.append((run_dir.name,
                                "미평가" if verdict is None else "아니오"))
        if blocked:
            for name, why in blocked:
                warn(f"{name}: 사람 판정 {why}")
            raise SystemExit(
                "재미없는 설계로 컷을 아무리 잘 뽑아도 재미없는 웹툰이 나옵니다.\n"
                "  python story.py --serve  로 블라인드 평가를 먼저 받으세요.\n"
                "  검증 목적으로 건너뛰려면 --skip-human-gate 를 붙이세요.")

    provider, model, judge_model = resolve_provider(args)
    backend = MockBackend(provider) if args.mock else make_backend(provider)
    describe_setup(provider, model, judge_model, args.mock)

    log_prompt_hashes(out_dir, ps, f"{provider}/{model}")

    caller = Caller(backend, model, judge_model, args.max_tokens)

    if args.cuts_only:
        for run_dir in targets:
            log(f"컷만 다시 뽑기 · {run_dir.name}")
            run_cuts_only(caller, ps, run_dir, args.max_retries, args.resume,
                          only=args.episode, author_note=args.author_note)
            build_webtoon_output(run_dir)
        return 0

    tally = {}
    t_start = time.monotonic()
    for i, run_dir in enumerate(targets, 1):
        log(f"[{i}/{len(targets)}] 웹툰으로 만들기 · {run_dir.name}")
        res = run_webtoon(caller, ps, run_dir, out_dir,
                          args.max_retries, args.ledger_cap, args.resume,
                          episode_target=max(1, args.episodes),
                          replan=args.replan, author_note=args.author_note)
        tally[res.status] = tally.get(res.status, 0) + 1
        made = (f"{res.episode_count}화"
                + (f"(누적 {res.series_total}화)"
                   if res.series_total > res.episode_count else ""))
        log(f"    -> {res.status} · Arc {res.arc_count} · {made} · "
            f"컷 {res.cut_count}(impact {res.impact_count}) · Scene {res.scene_count} · "
            f"{res.total_tokens:,}토큰 · "
            f"{cost_text(res.cost_usd, res.cost_note)} · "
            f"{res.elapsed_sec:.0f}초")
        if res.warnings:
            warn(f"    장부 경고: {res.warnings}")
        log(f"    화면: {run_dir / 'webtoon' / 'webtoon.html'}")

    log("\n" + "-" * 60)
    log(f"완료 {len(targets)}건 / {time.monotonic() - t_start:.0f}초")
    for k, v in sorted(tally.items()):
        log(f"  {k}: {v}")
    log(f"요약: {out_dir / 'webtoon_summary.csv'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
