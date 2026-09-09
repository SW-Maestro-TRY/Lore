#!/usr/bin/env bash
#
# 생성 하네스(haeun/new_harness · haeun/story-harness)를 webtoon/be 가
# jar 에 실어 배포할 수 있는 자리로 옮긴다.
#
# **손으로 돌릴 일이 거의 없다.** build.gradle 의 processResources 가
# 빌드·bootRun 전에 이걸 부른다. 그래서 haeun/ 쪽 원본만 고치고 커밋하면
# 배포까지 그대로 따라온다 — 지금까지는 haeun/new_harness 를 배포 파이프라인이
# 서버로 올리는 절차가 아예 없어서, lore.webtoon.python.direct=true 로 스프링이
# 직접 파이썬을 실행하려 해도 운영 서버에 그 코드 자체가 없었다.
#
#   bash webtoon/be/sync-harness.sh      # 직접 돌리고 싶을 때
#
# 왜 복사인가 — 자바 jar 에는 리소스(자원)만 담을 수 있고, webtoon/be 의
# build.gradle 리소스 폴더는 이미 webtoon/be/src/main/resources 로 정해져
# 있다. 원본은 haeun/ 에 그대로 두고(하은이 직접 돌리며 다듬는 자리이자
# story-harness 는 "완성본" 규약이 있는 자리다 — haeun/CLAUDE.md), 여기서는
# 배포용 사본만 만든다. 실행 시점에는 AiHarnessResources 가 이 사본을 다시
# 서버의 임시 폴더로 풀어서 파이썬이 실제 경로에서 돌게 한다(zzal 의
# PipelineScripts 와 같은 방식).
#
# story-harness 도 같이 담는 이유 — new_harness/llm.py 가 story-harness 를
# 형제 폴더로 보고 import 한다(STORY_HARNESS = HERE.parent / "story-harness").
# new_harness 만 옮기면 import 가 그 자리에서 깨진다.
#
# 기본 제공 캐릭터 견본 그림(haeun/landing/web/samples)도 같은 이유로 담는다 —
# BuiltinCharacters 가 서버 기동 때마다 이 그림들을 S3 에 올려 캐릭터 탭을
# 채우는데, 원본이 배포 서버에 없으면 "그림이 없다" 며 아무것도 안 심고
# 조용히 건너뛴다(#274 — 운영 캐릭터 탭이 통째로 비어 있던 원인).
#
# 만들어지는 자리(webtoon/be/src/main/resources/webtoon/{ai,character-samples})는
# .gitignore 에 있다 — 저장소에는 원본 한 벌만 둔다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DST="$HERE/src/main/resources/webtoon/ai"

sync_one() {
  local name="$1"
  local src="$ROOT/haeun/$name"
  local dst="$DST/$name"
  [ -d "$src" ] || { echo "원본이 없습니다: $src" >&2; exit 1; }
  mkdir -p "$dst"
  rsync -a --delete \
    --exclude='.env' \
    --exclude='.env.local' \
    --exclude='*.bak' \
    --exclude='.DS_Store' \
    --exclude='__pycache__/' \
    --exclude='runs/' \
    --exclude='runs_backup/' \
    --exclude='.git' \
    --exclude='.gitignore' \
    --exclude='input/' \
    --exclude='characters/photos/' \
    --exclude='style_compare.html' \
    "$src/" "$dst/"
}

sync_one "new_harness"
sync_one "story-harness"

echo "하네스 동기화: haeun/{new_harness,story-harness} -> $DST"

SAMPLES_SRC="$ROOT/haeun/landing/web/samples"
SAMPLES_DST="$HERE/src/main/resources/webtoon/character-samples"
[ -d "$SAMPLES_SRC" ] || { echo "원본이 없습니다: $SAMPLES_SRC" >&2; exit 1; }
mkdir -p "$SAMPLES_DST"
rsync -a --delete --exclude='.DS_Store' "$SAMPLES_SRC/" "$SAMPLES_DST/"

echo "기본 캐릭터 견본 동기화: haeun/landing/web/samples -> $SAMPLES_DST"
