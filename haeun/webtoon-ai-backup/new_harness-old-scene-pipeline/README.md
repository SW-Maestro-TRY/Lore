# 옛 장면 이음새 방식 (2026-09-15 이전)

`scenelink.py` + `prompt/detail_prompt` — story_prompt 가 이미 만들어 둔
장면 목록(one-liner)을 받아서, 장면마다 「시작」·「마무리」만 따로 정하던
단계다.

2026-09-15에 파이프라인을 사용자 설계대로 다시 짜면서 이 방식을 뺐다:

- `story_prompt`가 이제 방향 4개(소개+본문)만 낸다 — 장면 목록을 미리 안
  만든다.
- 방향을 고른 **뒤에** `prompt/scene_prompt`(신규) + `run.py --scenes`가
  본문을 장면으로 쪼개면서 직전 상태·끝나는 상태·등장인물까지 한 번에
  만든다 — `scenelink.py`가 하던 일(이음새)까지 이 한 단계가 포함한다.

`detailart.py`는 이제 `scenes.json`(scene_prompt 산출물)을 읽지,
`direction['scenes']` + `scene_link.json`을 읽지 않는다.

참고용으로 남겨 둔다 — new_harness 의 실행 경로에서는 더 이상 안 쓰인다.
