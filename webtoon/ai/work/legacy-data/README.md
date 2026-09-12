# 옛 프로토타입(landing/serve.py)이 남긴 기록

2026-09-12에 랜딩 프로토타입 서버를 걷어내면서 `landing/data/` 에 있던 것을
여기로 옮겼습니다. **코드가 읽지 않습니다** — 보관용입니다.

| 파일 | 내용 |
| --- | --- |
| `credits.json` | 브라우저 uid 별 크레딧 잔액 |
| `credit_events.jsonl` | 크레딧 사용 이력 |
| `hidden_runs.json` | 사용자가 숨긴 작품 |
| `ip_consent.jsonl` | 저작권 확인 동의 기록 |

지금 크레딧·동의·공개여부는 전부 DB(`credit_event` · `webtoon_work` 등)가
관리합니다. 이 파일들은 그 표가 생기기 전의 것이라, 옮겨 담을 것이 있는지
확인할 때만 봅니다.
