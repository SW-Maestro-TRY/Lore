# 지금 안 쓰는 그림체

`prompt/style/` 에는 **화면에서 고를 수 있는 것만** 둔다. 여덟 개다.
여기 있는 것은 그 여덟에 안 들어가는 것들이고, 지우지 않고 남겨 둔 이유는
되살릴 수 있어서다.

`load_style()` 은 `prompt/style/` 만 본다. 여기 있는 파일은 그대로는 안
불린다 — 쓰려면 `prompt/style/` 로 도로 옮긴다.

## 대체된 것

| 파일 | 무엇이 대신하나 |
| --- | --- |
| `romance` | `romance_fantasy` — 화면의 「로맨스 판타지」가 그쪽으로 간다 |
| `webtoon` | `webtoon_lock_bg` — 화면의 「일반 웹툰」이 그쪽으로 간다 |
| `webtoon_lock` | `webtoon_lock_bg` (배경 규칙까지 들어간 판) |

`romance` 와 `webtoon` 은 **이름이 같아서 헷갈리기 딱 좋다.** 화면이 보내는
값(`romance`·`webtoon`)과 하네스 파일 이름이 같은데 실제로 읽히는 파일은
다른 것이라, 파일 이름만 보고 "이게 로맨스 판타지구나" 하면 틀린다. 매핑은
두 곳에 있다 — `landing/newharness_pipeline.py` 의 `STYLE_CHOICES` 와
`webtoon/be` 의 `JobService.STYLE`.

## 화면에서 뺀 것

| 파일 | 사정 |
| --- | --- |
| `action` | 랜딩 카드 자체를 없애기로 해서 목록에서 빠졌다. 문구는 온전하다 — 액션 그림체가 다시 필요하면 그대로 쓸 수 있다 |

## 비어 있는 것

| 파일 | 사정 |
| --- | --- |
| `webtoon_char` | 0바이트. `prompt/style/` 에 두면 고르는 순간 「그림체가 비어 있습니다」로 생성이 죽는다. 지워도 된다 |
