# Git 컨벤션 (초안)

> 팀 협업 규칙 초안. 논의 후 확정한다.

## 브랜치 전략

- `main`: 배포 가능한 안정 브랜치
- `develop`: 개발 통합 브랜치 (선택)
- `feature/{도메인}-{작업}`: 기능 개발 (예: `feature/story-card-list`)
- `fix/{내용}`: 버그 수정

## 커밋 메시지

형식: `type: 제목`

| type | 설명 |
| --- | --- |
| feat | 새 기능 |
| fix | 버그 수정 |
| docs | 문서 변경 |
| style | 포맷팅 등 (로직 변화 없음) |
| refactor | 리팩터링 |
| test | 테스트 추가/수정 |
| chore | 빌드/설정 등 잡무 |

예시: `feat: story 카드 목록 API 추가`

## Pull Request

- 제목에 작업 요약, 본문에 변경 내용 / 테스트 방법 기재
- 리뷰어 1명 이상 승인 후 머지
- 도메인 담당자 기준으로 리뷰 요청

## 폴더 구조상 주의

- 자기 도메인 폴더(`story/`, `comic/`, `trailer/`) 안은 담당자가 자유롭게 판단해서 작업한다.
- `common/`, `apps/`, 루트 설정(`settings.gradle`, `package.json` 등)은 **모두가 공유하는 영역**이라,
  변경 시 PR 에 이유를 남기고 다른 담당자에게 알린다.
