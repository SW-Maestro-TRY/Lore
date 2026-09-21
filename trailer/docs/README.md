# Trailer 도메인 문서

1. 확인한 사실

- 폴더와 담당
    - 저장소의 최상위 폴더는 도메인이다. webtoon/, zzal/, trailer/마다 be/, fe/, docs/가 있다. (README.md:15-35)
    - common/에는 세 도메인이 가져다 쓰는 코드가 있다. apps/에는 실행과 연결을 맡는 파일만 있다. (README.md:56-76)
    - 백엔드는 Gradle 프로젝트 하나다. Gradle은 도메인끼리 import하는 것을 막지 않는다. (common/docs/architecture.md:52-86)
    - README.md는 작업 규칙 셋을 적었다. 도메인 폴더 안은 담당자가 판단한다. 공유 영역을 고치면 PR에 이유를 남긴다. 도메인끼리 의존하지 않는다. (README.md:123-127)
- API 경로
    - 공통 API의 경로는 /api/v1/...이다. 도메인 API의 경로는 /api/{도메인}/v1/...이다. (webtoon/be/.../WebtoonApi.java:10-18)
    - zzal은 로그인한 사용자의 자원을 /api/zzal/v1/me/pets 아래에 둔다. (zzal/be/.../pet/PetController.java:33-39)
    - zzal은 돌보기와 재우기를 POST /{petId}/care, POST /{petId}/sleep으로 만들었다. (PetController.java:210,233)
    - 로그인 없이 열린 조회 경로는 GET /api/zzal/v1/public/**다. 기계가 부르는 경로는 /api/zzal/v1/agent/**다. (common/be/.../config/WebSecurityConfig.java:60,68)
- 응답과 오류
    - 서버의 응답 모양은 {success, data, message, error{code, message}}다. (common/docs/api-spec.md:9-34)
    - 에러 코드는 ErrorCode.java enum 하나에 있다. 도메인 코드는 ZZAL_, WEBTOON_, TRAILER_ 접두어를 쓴다. TRAILER_ 코드는 아직 없다. (common/be/.../exception/ErrorCode.java:11)
    - zzal의 업무 거절은 모두 409다. 남의 펫은 404다. 크레딧 부족은 402다. (ErrorCode.java:32-35,43-44,50-52)
    - zzal은 쓸 코드를 한 번에 다 적고 ErrorCode.java를 얼렸다. (ErrorCode.java:69-70,98-101)
    - GlobalExceptionHandler가 받는 예외는 다섯 가지다. 쿼리 인자가 빠졌을 때 나는 예외는 그 안에 없다. (common/be/.../exception/GlobalExceptionHandler.java:28-80)
    - webtoon의 옛 API는 봉투를 쓰지 않는다. webtoon/docs/backend.md는 이것을 예외라고 적었다. (backend.md:29-39)
- 인증
    - 로그인은 HttpOnly 쿠키에 담긴 JWT다. (zzal/docs/api-v2.md:15)
    - 허용 목록에 없는 경로는 모두 로그인을 요구한다. (WebSecurityConfig.java:100)
    - 컨트롤러는 @LoginUser Long userId로 로그인한 사용자를 받는다. (common/be/.../credit/CreditController.java:108)
    - 화면의 공용 호출 함수 request<T>()는 쿠키를 붙인다. 401을 받으면 토큰을 한 번 갱신하고 다시 부른다. (common/fe/api/client.ts:187,195-196)
- 데이터 표기
    - JSON 칸 이름을 바꾸는 설정이 서버에 없다. 칸 이름은 Java 이름 그대로 camelCase다. (설정을 검색했고 결과가 없었다)
    - enum 값은 대문자다. 예: FEED. (zzal/be/.../pet/CareAction.java:13)
    - 시각은 ISO-8601 UTC다. 예: 2026-09-05T10:00:00Z. (api-v2.md:18)
    - webtoon은 숫자 id 대신 public_id 문자열을 내보낸다. 숫자 id를 내보내면 남의 번호를 셀 수 있기 때문이다. (webtoon/be/.../character/WebtoonCharacter.java:34-35)
- 목록과 오래 걸리는 일
    - 목록 API의 페이징 규격은 아직 없다. (api-spec.md:93)
    - 목록 선례는 ?limit=(기본 50)이다. 응답의 data는 배열이다. (CreditController.java:107-111)
    - zzal은 offset 페이지네이션을 쓰지 않고 id를 기준점으로 쓴다고 적었다. (zzal/be/.../archive/JdbcEventSource.java:14-17)
    - 화면은 부화 진행을 GET /{petId}/hatch로 되풀이해 묻는다. 서버 코드에 SSE와 WebSocket은 없다. (PetController.java:149, 코드 검색)
    - 서버는 바깥 기계를 부르지 않는다. 기계가 서버에 일감을 묻는다. (zzal/be/.../agent/AgentController.java:62-66)
    - zzal의 행동(POST) 응답은 그 펫의 최신 상태다. (api-v2.md:17)
    - 저장소에 @PutMapping은 없다. @PatchMapping은 둘 있다. (코드 검색)
- DB
    - ddl-auto의 기본값은 validate다. 엔티티와 DB가 다르면 서버가 뜨지 않는다. 표는 Flyway의 SQL 파일이 만든다. (apps/api/src/main/resources/application.yml:47-55)
    - SQL 파일을 두는 폴더가 둘이다. apps/api/.../db/migration의 가장 큰 번호는 V22다. webtoon/be/.../db/migration에는 V11, V16이 있다.
    - 두 폴더는 이력 표 하나를 함께 쓴다. 번호가 겹쳐 배포가 두 번 실패했다. (webtoon/CLAUDE.md)
- 프론트
    - apps/web의 의존성은 next, react, react-dom 셋이다. (apps/web/package.json:15-19)
    - 경로 별칭은 @common/*, @webtoon/*, @zzal/*, @trailer/*다. (apps/web/tsconfig.json:19-25)
    - README.md는 npm install을 루트에서만 돌리라고 적었다. (README.md:105-106)
    - tokens.css는 컴포넌트가 hex를 직접 쓰지 말라고 적었다. 도메인 CSS에는 hex가 있다(zzal.css 8곳, webtoon.css 40곳). (common/fe/styles/tokens.css:1-5)
    - 도메인 CSS는 루트 클래스(.zzal-page, .wt, .trailer-page) 아래에 규칙을 둔다.
    - 화면 검사는 Playwright다. apps/web에서 npm run e2e로 돌린다. (apps/web/package.json:13)
- 문서
    - zzal의 API 계약은 zzal/docs/api-v2.md다. 컨트롤러 주석이 이 문서를 가리킨다. (PetController.java:31)
    - api-spec.md는 담당자가 도메인 에러 코드와 도메인 API를 그 문서에 더하라고 적었다. Trailer 줄은 비어 있다. (api-spec.md:51-53,65,94)
    - 백엔드는 common/docs/openapi.json을 갱신하고 커밋한다. CI가 서버를 띄우지 못하기 때문이다. (scripts/gen-api-types.sh:8-9)
- Git과 배포
    - 커밋 제목은 [#이슈번호] Type: 내용이다. 이슈가 없으면 번호를 뺀다. (common/docs/git-convention.md:3-18)
- Type은 12가지다. 첫 글자만 대문자다. 내용은 한글 50자 이내이고 마침표가 없다. (git-convention.md:20-51)
- PR은 develop으로 보낸다. PR 템플릿에 체크리스트 넷이 있다. (.github/PULL_REQUEST_TEMPLATE.md:5,34-37)
- develop, staging, main에 push하면 배포된다. 배포를 일으키는 경로에 trailer/**가 있다. (.github/workflows/deploy.yml:18-28)

2. 규약

주체를 가리키는 말은 넷입니다.

- 담당자: 도메인 폴더의 코드를 고치는 사람이다. Trailer의 담당자는 병연이다.
- 서버: apps/api가 띄우는 Spring 서버다.
- 화면: apps/web이 띄우는 Next 앱이다.
- 공유 영역: common/, apps/, 저장소 루트의 설정 파일이다.
- 폴더와 담당
    - 작업 범위: 담당자는 trailer/ 안에서 코드를 고친다. 코드의 위치로 책임을 드러내기 위해서다.
    - 공유 영역: 담당자는 공유 영역을 고치면 PR 본문에 까닭을 적는다. 세 도메인이 함께 쓰는 코드이기 때문이다.
    - 의존 방향: 도메인 코드는 common만 import한다. 도메인끼리 얽히지 않게 하기 위해서다. 함께 쓸 코드는 담당자가 common으로 올린다.
    - apps/: 담당자는 apps/에 로직을 넣지 않는다. apps/에는 라우팅 파일과 실행 진입점만 둔다.
- API 경로
    - 앞머리: 담당자는 Trailer API의 경로를 /api/trailer/v1/...로 짓는다. 도메인마다 API 버전을 따로 올리기 위해서다.
    - 내 자원: 담당자는 로그인한 사용자의 자원을 /me/ 아래에 둔다. 경로에 다른 사용자의 번호를 넣을 자리를 없애기 위해서다.
    - 공개 조회: 담당자는 로그인 없이 여는 조회를 /public/ 아래에 둔다. 그 경로를 WebSecurityConfig의 허용 목록에도 더한다. 목록에 없는 경로는 로그인을 요구하기 때문이다.
    - 기계용: 담당자는 기계가 부르는 API를 /agent/ 아래에 둔다. 서버는 전용 헤더의 열쇠와 설정 스위치로 이 API를 지킨다. 사람의 로그인 토큰은 몇 시간이면 만료되기 때문이다.
    - 행동: 담당자는 자원에 하는 행동을 POST /{id}/행동으로 짓는다. zzal의 API와 모양을 맞추기 위해서다.
- 응답과 오류
    - 봉투: 서버는 모든 응답을 {success, data, message, error{code, message}}에 담는다. 화면이 모든 API의 성공과 실패를 같은 방법으로 가리게 하기 위해서다.
    - 분기: 화면은 success로 성공을 가리고 error.code로 분기한다. message 문구로는 분기하지 않는다. 문구는 바뀌고 코드는 바뀌지 않기 때문이다.
    - 에러 코드: 담당자는 Trailer의 에러 코드를 ErrorCode.java에 TRAILER_ 접두어로 더한다. 쓸 코드는 한 번에 다 더한다. 여러 사람이 이 파일을 따로 고치면 충돌하기 때문이다. 같은 코드를 api-spec.md의
      표에도 적는다.
    - HTTP 상태: 서버는 HTTP 상태를 다음 뜻으로 쓴다.
        - 400: 입력이 규칙에 맞지 않는다.
        - 401: 로그인이 필요하다.
        - 402: 크레딧이 모자란다.
        - 403: 로그인했지만 권한이 없다.
        - 404: 자원이 없거나 남의 자원이다.
        - 409: 서버가 업무 규칙으로 거절한다.
        - 500: 서버 오류다.
    - 남의 자원: 서버는 남의 자원을 요청받으면 404를 준다. 403은 그 번호의 자원이 있다는 사실을 알려 주기 때문이다.
    - 업무 거절: 서버는 거절 사유마다 에러 코드를 따로 둔다. 상태가 모두 409라서 화면은 코드로만 사유를 가릴 수 있기 때문이다.
- 인증
    - 방식: 서버는 HttpOnly 쿠키의 JWT로 사용자를 확인한다. 화면의 자바스크립트는 이 토큰을 읽지 못한다.
    - 서버 코드: 담당자는 컨트롤러 인자 @LoginUser Long userId로 로그인한 사용자를 받는다.
    - 화면 코드: 담당자는 봉투를 쓰는 API를 @common/api/client의 request<T>()로 부른다. 이 함수가 쿠키를 붙이고, 401을 받으면 토큰을 한 번 갱신하기 때문이다.
- 데이터 표기
    - 칸 이름: 서버는 JSON 칸 이름을 camelCase로 준다. Jackson의 기본 설정을 그대로 쓰기 때문이다.
    - enum: 서버는 enum 값을 대문자로 준다. zzal의 API와 모양을 맞추기 위해서다.
    - 시각: 서버는 시각을 ISO-8601 UTC로 준다.
    - id: /me/ 아래의 자원에는 담당자가 숫자 id를 그대로 쓴다. /me/ 밖에서 부르는 자원에는 문자열 id를 따로 만든다. 숫자 id를 내보내면 남의 번호를 셀 수 있기 때문이다.
- 목록과 오래 걸리는 일
    - 페이징: 페이징 규격은 아직 없다. 목록을 나눠 주는 API를 처음 만드는 담당자가 규격을 정해 api-spec.md에 적는다. 선례는 ?limit=과 id 기준점이다. offset은 쓰지 않는다. offset은 뒤쪽일수록 느리고,
      줄이 끼어들면 같은 줄이 두 번 나오거나 빠지기 때문이다.
    - 오래 걸리는 일: 서버는 오래 걸리는 일을 받으면 바로 답한다. 화면은 상태를 주는 조회 API를 되풀이해 부른다. 서버는 SSE와 WebSocket을 쓰지 않는다.
    - 바깥 기계: 서버는 바깥 기계를 부르지 않는다. 기계가 서버에 일감을 묻는다. 기계가 꺼져 있어도 서버가 멈추지 않게 하기 위해서다.
    - 행동의 응답: 서버는 자원을 바꾸는 POST에 그 자원의 최신 상태로 답한다. 화면이 다시 조회하지 않게 하기 위해서다. 이것은 zzal의 관례다.
    - 고치는 동사: 담당자는 자원을 고치는 API를 PATCH로 짓는다. PUT은 선례가 없기 때문이다.
- DB
    - 스키마 변경: 담당자는 엔티티를 더하거나 바꾸는 커밋에 V번호__이름.sql을 함께 넣는다. 엔티티와 DB가 다르면 서버가 뜨지 않기 때문이다.
    - 파일 위치와 번호: 담당자는 새 SQL 파일을 apps/api/src/main/resources/db/migration에 둔다. 번호는 두 폴더를 합쳐 겹치지 않게 잡는다. 번호가 겹치면 배포가 실패하기 때문이다. 지금 비어 있는 다음
      번호는 V23이다.
- 프론트
    - 코드 위치: 담당자는 화면 코드를 trailer/fe에 둔다. apps/web/app/(domains)/trailer/page.tsx는 @trailer/TrailerPage를 내보내는 한 줄로 둔다.
    - 라이브러리: 담당자는 새 라이브러리를 더하지 않는다. 더하려면 공유 영역인 apps/web/package.json을 고쳐야 하기 때문이다.
    - 설치: 담당자는 npm install을 저장소 루트에서만 돌린다. 도메인의 fe/가 apps/web 밖에 있어 npm workspaces를 쓰기 때문이다.
    - CSS: 담당자는 도메인 CSS의 규칙을 모두 .trailer-page 아래에 둔다. 다른 탭과 공용 헤더의 모양을 바꾸지 않기 위해서다.
    - 색: 담당자는 색을 tokens.css의 변수로 쓴다.
    - 검사: 담당자는 화면의 동작을 apps/web/e2e의 Playwright 검사로 확인한다.
- 문서
    - API 계약: 담당자는 Trailer의 API 계약을 trailer/docs/에 둔다. 컨트롤러 주석에 그 문서의 경로를 적는다. 코드에서 계약 문서를 바로 찾게 하기 위해서다.
    - OpenAPI: 담당자는 API를 바꾸면 common/docs/openapi.json을 갱신해 커밋한다. CI가 서버를 띄우지 못해 이 파일이 정본이기 때문이다.
- Git과 배포
    - 커밋 제목: 담당자는 커밋 제목을 [#이슈번호] Type: 내용으로 쓴다. 이슈가 없으면 번호를 뺀다. git log --grep으로 커밋을 거를 수 있게 하기 위해서다.
    - Type: 담당자는 Type을 다음 12가지에서 고르고 첫 글자만 대문자로 쓴다. Feat, Fix, Style, Refactor, Design, Comment, Docs, Test, Chore, Rename, Remove, Build.
    - 내용과 본문: 담당자는 내용을 한글 50자 이내로 쓰고 마침표를 찍지 않는다. 본문에는 무엇을 왜 바꿨는지 적는다.
    - PR: 담당자는 PR을 develop으로 보낸다. PR 제목은 [이슈 타입] 구현한 기능으로 쓴다. 템플릿의 체크리스트 넷을 확인한다.
    - 배포: develop에 push하면 dev 환경에 배포된다. 담당자는 trailer/의 변경을 develop에 합치기 전에 배포된 /trailer가 어떻게 보일지 확인한다.
    - 충돌: 나중에 머지하는 담당자가 develop 기준으로 rebase해서 충돌을 푼다.

3. 문서와 코드가 다른 곳

- 도메인 API의 경로: api-spec.md:67-68은 /api/v1/...을 예로 든다. 코드는 /api/{도메인}/v1/...을 쓴다. 코드를 따른다.
- 인증: api-spec.md:91은 모든 API가 로그인 없이 열려 있다고 적었다. 지금 서버는 허용 목록 밖의 경로에 로그인을 요구한다. 코드를 따른다.
- 봉투: webtoon의 옛 API만 봉투를 쓰지 않는다. backend.md:39는 새로 만드는 API를 규약대로 봉투에 담으라고 적었다.
- 브랜치 이름: git-convention.md:102,147은 feat/#14 꼴을 적었다. 최근 브랜치와 지금 브랜치(feat/trailer-piece-maker)는 타입/설명 꼴이다. 어느 쪽을 따를지 정한 기록은 찾지 못했다.
- 색: tokens.css는 hex를 직접 쓰지 말라고 적었다. zzal.css와 webtoon.css는 hex를 쓴다.