# webtoon/be — 지금 무엇이 있고 무엇이 없나

## 지금 있는 것: 하네스 앞에 서 주는 프록시 한 자리

```
브라우저 ──/api/webtoon/**──> Spring (8080) ──/api/**──> serve.py (8800) ──> new_harness
```

`WebtoonController` 가 `/api/webtoon/**` 를 받아 접두사만 갈아 끼우고
생성 하네스로 넘긴다. 상태·헤더·본문을 **안 건드린다.**

    POST /api/webtoon/nh/create                -> POST …:8800/api/nh/create
    GET  /api/webtoon/nh/jobs/{id}             -> GET  …/api/nh/jobs/{id}
    GET  /api/webtoon/runs/{id}/result         -> GET  …/api/runs/{id}/result
    POST /api/webtoon/runs/{id}/scenes/3/regen -> …/api/runs/{id}/scenes/3/regen

### 왜 자바로 다시 안 쓰고 넘기나

생성 파이프라인은 파이썬이다(`haeun/new_harness`). 그 앞에 `serve.py` 가
이미 서서 **줄 세우기 · 검수 진행 표시 · 판본 · 오버레이 · 굽기 ·
워터마크**를 다 하고 있고, 그건 실제로 한 편을 만들어 보며 검증된 코드다.
같은 것을 자바로 옮기면 검증을 처음부터 다시 해야 한다.

그래서 지금 이 백엔드가 하는 일은 **앞에 서 주는 것**이다. 인증·크레딧·DB
처럼 자바가 맡아야 할 것이 생기면 그때 이 자리에서 하나씩 가로챈다 —
스프링은 더 구체적인 매핑을 먼저 고르므로, 진짜 컨트롤러를 옆에 만들면
그 주소만 프록시보다 앞선다. **프론트가 부르는 주소는 안 바뀐다.**

### 응답을 ApiResponse 로 안 감싼다

이 저장소 규약은 `ApiResponse` 인데 이 자리만 예외다.

- 넘어오는 JSON 을 화면(프로토타입에서 옮겨 온 것)이 이미 그 모양대로
  읽는다. 한 겹 씌우면 화면을 다 고쳐야 하고, 그러면 원본과 이식본이 서로
  다른 응답을 읽게 된다.
- **이미지**가 섞여 있다. 페이지 그림·시트·한 편으로 이어 붙인 것. 감쌀 수
  없는 바이트다.

자바가 뜻을 갖고 판단하는 API 를 만들 때는 그때 규약대로 감싼다.

## 설정

| 값 | 기본 | 환경변수 |
| --- | --- | --- |
| 하네스 주소 | `http://127.0.0.1:8800` | `LORE_WEBTOON_HARNESS_BASEURL` |
| 한 호출 대기 | 120초 | `LORE_WEBTOON_HARNESS_TIMEOUT` |

기본값이 로컬 `serve.py` 라 **application.yml 을 안 고쳐도 뜬다** — 그
파일은 공용 자리(`apps/api`)라 도메인 하나 때문에 건드리지 않는다.

## 띄우는 법

```
cd haeun/landing && python3 serve.py      # 하네스 (8800)
./gradlew bootRun                         # 스프링 (8080)  ※ Postgres 필요
```

스프링은 `application.yml` 이 Postgres 를 요구해서 DB 없이는 안 뜬다(이
도메인이 만든 제약이 아니라 원래 그렇다). 프록시만 확인하려면 테스트를
쓴다 — DB 없이 돈다.

## 검사

```
./gradlew test --tests 'com.lore.webtoon.*'
```

- `WebtoonControllerTest` — 경로 매핑, 그대로 넘기기, 안 감싸기, 실패 전달.
  DB·하네스 없이 돈다.
- `HarnessGatewayLiveTest` — **진짜 하네스에 붙여 본다.** JSON·이미지
  바이트·404 한글 사유. 하네스가 안 떠 있으면 통째로 건너뛴다(CI 에는
  파이썬 서버가 없고, 그것 때문에 빨개지면 사람들이 이 검사를 지운다).

## 아직 없는 것

- 인증·크레딧·내 작품 목록 — 지금은 하네스 쪽 파일 기반 구현이 그대로 돈다.
- DB(`WebtoonRepository` 는 아직 빈 자리).
- 스트리밍. 지금은 응답 바이트를 통째로 읽어 넘긴다 — 한 편이 15MB 쯤이라
  그만큼 힙을 쓴다. 사람이 몇 안 되는 지금은 단순한 쪽이 낫고, 늘면
  `HarnessGateway` 한 곳만 고치면 된다.
