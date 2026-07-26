### 1. Commit Message Structure

```
[#issueNumber] Type: 내용

#예시
[#14] Feat: 로그인 기능 추가
```

형식은 **이 하나만** 사용한다. 여러 형식을 섞으면 `git log --grep` 으로 필터링이 안 되고
로그를 훑을 때 눈에 안 들어온다.

**이슈 번호가 없는 경우** (레포 초기 세팅, 오타 수정 등 이슈를 만들지 않는 작업)는
번호 부분을 생략한다.

```
Chore: 레포 도메인별 구조 초기 세팅
```

### 2. Commit Type (첫 글자만 대문자)

| Type | 설명 |
| --- | --- |
| Feat | 새로운 기능 추가 |
| Fix | 버그 수정 |
| Style | 코드 스타일 수정 (세미콜론, 인덴트 등 스타일적인 부분만) |
| Refactor | 코드 리팩토링 (더 효율적인 코드로 변경 등) |
| Design | CSS 등 사용자 디자인 추가/수정 |
| Comment | 주석 추가/수정 |
| Docs | 문서 작성 및 수정 |
| Test | 테스트 추가/수정 |
| Chore | 그 외 잡무 (설정 파일, 패키지 추가/삭제, .gitignore 등) |
| Rename | 파일 및 폴더명 수정 |
| Remove | 파일 삭제 |
| Build | 빌드 시스템 자체 변경 (build.gradle, package.json 의 빌드 스크립트·의존성) |

> 커밋 타입은 총 12개이며, GitHub 라벨(이슈/PR용)은 이 중 필요한 항목만 사용합니다.

**Chore 와 Build 구분** — 헷갈리면 이 기준으로 나눈다.

- `Build`: 빌드/의존성에 직접 영향 (`build.gradle` 의존성 추가, `package.json` 스크립트 변경)
- `Chore`: 그 외 잡무 (`.gitignore`, 에디터 설정, 템플릿 파일 등)
- 그래도 애매하면 `Chore` 를 쓴다. 구분에 시간 쓰지 않는다.

### 3. 작성 규칙

#### 3.1 Subject 제목 작성

- **타입**은 첫 글자만 대문자 (`Feat`, `Fix`, `Chore` — `FEAT`, `feat` 아님)
- **내용**은 한글, 50자 이내, 개조식으로 작성 (영어 명령어 사용 안 함)
- 마침표로 끝내지 않음

```
예시)
[#14] Feat: 로그인 기능 추가
```

#### 3.2 Body 본문 작성

- 한 줄 당 72자 이내
- 양에 제한받지 않고 최대한 상세히 작성
- 어떻게 변경했는지보다 **무엇을 변경했는지, 왜 변경했는지** 위주로 작성
- `[#숫자]` 형식으로 쓰면 GitHub에서 자동으로 해당 이슈 링크 연결

### 4. Issue Convention

> GitHub 에서 이슈를 만들면 템플릿이 뜬다. (`.github/ISSUE_TEMPLATE/`)
> 제목 형식과 라벨은 템플릿이 자동으로 채워주므로 내용만 적으면 된다.

**Title**

`[이슈 타입] 구현할 기능`

예) `[Feat] 회원가입 구현`

**Description**

- 어떤 기능인가요? → 한 줄로 기능 소개
- 작업 상세 내용 → `[ ]` todo 체크박스
- 참고할만한 자료 (선택)

**Assignees**

해당 이슈 참여자 설정 (보통 본인)

**Labels**

아래 중 해당되는 라벨 선택 (다중 선택 가능)

- FEAT, FIX, REFACTOR, DESIGN, DOCS, TEST, CHORE, RENAME, REMOVE, BUILD, STYLE

주요 사용 라벨:

- 주요 기능 구현 → `FEAT`
- 추가 수정 사항 → `REFACTOR`
- `FIX`는 배포 후 버그 수정 시에만 사용

커밋 타입은 12개를 세분화해서 사용하되, GitHub 이슈/PR 라벨은 FEAT / FIX / REFACTOR / DESIGN / DOCS / CHORE / TEST / BUILD 8개로 운영한다. (Style, Rename, Remove, Comment 관련 작업은 가장 가까운 라벨로 대체)

**브랜치 생성**

이슈 생성 후 브랜치 생성: `[이슈타입]/#이슈번호`

```
예시
Title: [Feat] 회원가입 구현
Description: 회원가입 구현
- [ ] 회원가입 기능 구현하기
- [ ] 인증 서비스 만들기
Labels: ✨FEAT
Branch name: feat/#3
```

### 5. Pull Request Convention

> PR 을 열면 템플릿이 자동으로 들어간다. (`.github/PULL_REQUEST_TEMPLATE.md`)

**Title**

`[이슈 타입] 구현한 기능` — 보통 issue와 동일하게 가져감

예) `[Feat] 회원가입 구현`

**Description**

- 관련 이슈: `#`을 달아 리다이렉트 설정
- 요약: 구현한 기능 한 줄 요약
- 상세 내용: 템플릿 형식에 따라 작성
- 유의사항 또는 기타: 코드리뷰 시 팀원이 확인해줬으면 하는 내용

**Assignees**

해당 PR 참여자 설정 (보통 본인)

**Reviewers**

해당 PR 리뷰어 지정

**Labels**

해당되는 라벨 선택 (다중 선택 가능)

### 6. 브랜치 전략

- `main`: 배포 브랜치
- `develop`: 개발 브랜치
- 작업 브랜치: `[이슈타입]/#이슈번호` (예: `feat/#14`, `fix/#22`, `refactor/#8`)

### 7. PR 승인 인원

현재 단계 미설정 → 추후 팀 논의를 통해 결정

### 8. Merge 충돌 해결

충돌 발생 시, **나중에 머지하려는 사람**이 `develop` 기준으로 rebase(또는 merge)하여 충돌을 해결한 후 재시도한다.