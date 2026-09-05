# 아키텍처 문서 (초안)

> 시스템 구조 개요. 세부 내용은 진행하며 채운다.

## 전체 구성

```
[ Web (Next.js) ]  ──HTTP──▶  [ API (Spring Boot 단일 서버) ]  ──▶  [ DB ]
```

- 실행되는 프로세스는 **웹 1개 + API 서버 1개**. (멘토링에서 정한 "API 서버는 공통 하나" 원칙)
- 코드는 도메인(webtoon / zzal / trailer)별 폴더로 나뉘어 있지만, 빌드/실행 시 하나로 합쳐진다.

## 폴더 조직 방식: domain-first

도메인 폴더가 최상위이고, 그 안에 `be / fe / docs` 가 들어간다.

```
webtoon/
├── be/     # Webtoon 백엔드 (Gradle 모듈)
├── fe/     # Webtoon 프론트엔드 화면
└── docs/   # Webtoon 문서
```

**왜 이렇게 했나**

- 담당자가 자기 도메인 폴더 하나만 열면 백엔드/프론트/문서가 전부 보인다 → 책임 경계가 코드 위치로 드러남.
- PR diff 도 해당 도메인 폴더 안에서만 나므로 "이건 누구 영역인지"가 명확하다.
- 대안(기술 레이어를 최상위에 두는 layer-first)은 세팅이 단순한 대신, 한 도메인을 보려면
  프론트/백엔드 두 군데를 오가야 해서 오너십이 흐려진다.

이 선택은 "각자 자기 도메인에 오너십을 갖고 판단해서 움직인다"는 팀 운영 방식에 맞춘 것이다.

## 합쳐지는 지점: apps/

domain-first 로 나눠도 결국 하나로 실행돼야 하므로, 합치는 자리를 `apps/` 에 둔다.
`apps/` 안에는 **연결/실행 코드만** 두고 비즈니스 로직은 넣지 않는다.

| 경로 | 역할 |
| --- | --- |
| `apps/web` | Next.js 라우팅 셸. `app/{도메인}/page.tsx` 는 해당 도메인 `fe/` 를 import 해서 렌더링만 한다. |
| `apps/api` | 서버 실행 진입점. main 클래스와 `application.yml` 만 둔다. |

**왜 `apps/` 가 필요한가**

- FE: Next.js App Router 는 URL 이 파일 위치로 결정된다. `/zzal` 이 존재하려면 `app/zzal/page.tsx` 가
  반드시 있어야 해서, `zzal/fe/` 에만 두면 라우팅이 잡히지 않는다. 그래서 라우팅 파일은 얇게 두고
  실제 화면은 도메인 폴더에 둔다.
- BE: `@SpringBootApplication` main 클래스는 특정 도메인 소유가 아니므로, 도메인 폴더가 아닌
  공용 실행 자리에 둔다.

## 백엔드 빌드: 단일 Gradle 프로젝트

폴더는 도메인별로 나뉘어 있지만 **Gradle 프로젝트는 하나**다.
루트 `build.gradle` 의 `sourceSets` 가 흩어진 도메인 폴더를 하나의 컴파일 단위로 묶는다.

```groovy
sourceSets {
    main {
        java {
            srcDirs = [
                'common/be/src/main/java',
                'webtoon/be/src/main/java',
                'zzal/be/src/main/java',
                'trailer/be/src/main/java',
                'apps/api/src/main/java'
            ]
        }
    }
}
```

**왜 멀티모듈로 안 나눴나**

- 멀티모듈로 나누면 도메인마다 `build.gradle` 이 생기고, `implementation project(':common:be')` 같은
  모듈 간 의존성 선언을 각자 이해해야 한다. 3인 / 짧은 스프린트에서 그 학습·관리 비용이 얻는 것보다 크다.
- 단일 프로젝트면 의존성은 루트 `build.gradle` 한 곳에만 적고, 공통 클래스는 그냥 import 해서 쓰면 된다.
  (예: `import com.lore.common.response.ApiResponse;`)
- 폴더 기준 오너십은 그대로 유지된다 — 담당자는 여전히 자기 도메인 폴더 안에서만 작업한다.

**트레이드오프 (알고 가는 부분)**

- 컴파일이 한 덩어리라, 한 도메인에 컴파일 에러가 나면 전체가 안 켜진다.
- 도메인 간 import 를 Gradle 이 막아주지 않는다. 아래 규칙은 **약속으로** 지킨다.
- 나중에 팀이 커지거나 도메인을 독립 배포하고 싶어지면 그때 멀티모듈로 쪼갠다.
  폴더 구조가 이미 도메인별로 나뉘어 있어서 그때 옮길 일은 없다.

## 의존 방향 규칙 (약속)

```
apps/api  ──▶  common
                 ▲
    webtoon ─────┤
      zzal ─────┤
    trailer ─────┘
```

- 도메인 코드는 `common` 만 참조한다. **도메인끼리는 서로 import 하지 않는다.**
  (webtoon 이 zzal 을 직접 부르기 시작하면 폴더만 나뉘고 실제로는 얽히게 된다.)
- 도메인 간 협업이 필요하면 `common` 으로 올리거나 API 로 주고받는다.

## 공통(common)에 두는 것

- 계정 / 회원가입 / 로그인 (`common/be/.../auth`)
- 설정: Swagger, Security, CORS (`common/be/.../config`)
- 공통 응답 구조 `ApiResponse` (`common/be/.../response`)
- DB 연결 설정 — 도메인별로 나누지 않는다
- 공용 UI: 내비게이션 등 (`common/fe`)

## 역할 경계 (현재 스캐폴딩 기준)

- 지금 레포에는 **폴더 구조 / 소스 경로 설정 / 빈 클래스 뼈대**까지만 있다.
- Spring Boot 초기 세팅(플러그인·버전 확정, 스타터 의존성, Gradle Wrapper, DB 연결, Swagger/Security 실제 구현)은
  **백엔드 초기 세팅 담당자 몫**으로 비워뒀다. 루트 `build.gradle` 의 `dependencies` 블록이 그 자리다.
