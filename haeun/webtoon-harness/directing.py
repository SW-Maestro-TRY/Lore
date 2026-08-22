"""컷/장면 서술에 맞는 연출 지식만 골라 붙인다.

story-harness 쪽 웹툰 연출 리서치(docs/*.md)를 사람이 한 번 청크·태그로
나눠 둔 것을 여기서도 그대로 읽는다 — 새로 만들지 않는다. 저장소가 두 곳으로
갈라지면 한쪽만 고치고 잊어버리는 사고가 난다. story-harness 는 story.py 의
`resolve_directing_notes` 가 같은 파일을 같은 방식(정확 태그 매칭)으로 읽는다;
여기는 그 웹툰-하네스 쪽 짝이다 — 이미지 프롬프트(prompt_gen.txt·
scene_gen.txt)에 붙는다는 것만 다르다.

벡터 검색이 아니다. 컷 서술·대사에 태그 문자열이 등장할 때만 그 조각을
원문 그대로 붙이고, 하나도 안 걸리면 빈 문자열을 준다 — 아무 것도 안 주는
편이 안 맞는 연출 지식을 우기는 것보다 낫다.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent

# story-harness 쪽(story.py 의 resolve_directing_notes)과 같은 저장소를 읽는다
# — 여기서 따로 안 만든다. config.yaml 을 거치지 않는 이유: 이 값은 운영자가
# 화마다 고를 값이 아니라 저장소 위치일 뿐이라 코드 상수로 충분하고, 무엇보다
# config.yaml 은 지금 다른 작업(그림체 추가)이 진행 중이라 건드리지 않는다.
DEFAULT_ROOT = os.environ.get("DIRECTING_KNOWLEDGE_FILE") or str(
    HERE.parent / "story-harness" / "knowledge" / "directing")

DEFAULT_LIMIT = 3

_cache: dict[str, list[dict]] = {}


def load_chunks(root: str | Path) -> list[dict]:
    """root(디렉터리) 안의 *.json 을 한 번만 읽어 합친다. 캐시는 root별로 따로."""
    key = str(root)
    if key in _cache:
        return _cache[key]
    d = Path(root)
    chunks: list[dict] = []
    if d.is_dir():
        for p in sorted(d.glob("*.json")):
            try:
                data = json.loads(p.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, OSError):
                continue
            if isinstance(data, list):
                chunks.extend(c for c in data
                             if isinstance(c, dict) and c.get("text") and c.get("tags"))
    _cache[key] = chunks
    return chunks


def resolve_notes(root: str | Path, *texts: str, limit: int = DEFAULT_LIMIT) -> str:
    """texts 안에 태그가 등장하는 조각만 원문 그대로 이어 붙인다. 없으면 빈 문자열."""
    chunks = load_chunks(root)
    if not chunks:
        return ""
    haystack = " ".join(t for t in texts if t)
    if not haystack:
        return ""
    hits = [c for c in chunks if any(tag in haystack for tag in c["tags"])]
    if not hits:
        return ""
    return "\n\n".join(f"[direction ref — {c['id']}]\n{c['text']}" for c in hits[:limit])
