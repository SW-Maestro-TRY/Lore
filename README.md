# Lore

사진을 업로드하면 캐릭터 카드 / 웹툰을 만들어주는 창작 플랫폼.

3개 도메인 탭으로 구성되며, 계정 / 인증 / API 서버는 하나로 공유한다.

| 도메인 | 내용 | 담당 |
| --- | --- | --- |
| **Webtoon** | 세로 스크롤 웹툰 | 하은 |
| **Zzal** | 만화 캐릭터 치환 / 짤 | 상훈 |
| **Trailer** | 웹툰 예고편 | 병연 |

## 폴더 구조

**도메인 폴더가 최상위(domain-first)** 이고, 그 안에 백엔드/프론트/문서가 함께 들어간다.
담당자는 자기 도메인 폴더 하나만 보면 자기 작업 전부가 보인다.

```
repo/
├── common/                  # 3개 도메인이 공유하는 영역
│   ├── be/                  # config(Swagger/Security), ApiResponse, 회원가입/로그인
│   ├── fe/                  # 공용 UI
│   │   ├── SiteHeader.tsx   # 랜딩 · 도메인 탭이 함께 쓰는 상단 헤더
│   │   ├── links.ts         # 탭 순서 (Zzal → Trailer → Webtoon) 단일 출처
│   │   ├── landing/         # 랜딩페이지 화면 (특정 도메인 소유가 아님)
│   │   ├── theme/           # 라이트/다크 테마 토글 + 초기화 스크립트
│   │   └── styles/          # 디자인 토큰 (tokens.css)
│   └── docs/                # 아키텍처 / API 명세 / 깃 컨벤션 / 디자인 핸드오프
│
├── webtoon/                 # 담당: 하은
│   ├── be/                  # 백엔드 (com.lore.webtoon 패키지)
│   ├── fe/                  # 실제 화면 컴포넌트
│   └── docs/
├── zzal/                   # 담당: 상훈  (구조 동일)
├── trailer/                 # 담당: 병연  (구조 동일)
│
├── apps/                    # 나뉜 코드를 하나로 합쳐 실행하는 자리 (로직 없음)
│   ├── web/                 # Next.js 라우팅 셸
│   │   └── app/
│   │       ├── layout.tsx         # 폰트 + 테마 초기화 + 전역 스타일
│   │       ├── page.tsx           # 랜딩 — common/fe/landing 에서 import 만
│   │       └── (domains)/         # URL 에 영향 없는 라우트 그룹
│   │           ├── layout.tsx         # common/fe 의 SiteHeader 렌더링
│   │           ├── webtoon/page.tsx   # webtoon/fe 에서 import 만 → /webtoon
│   │           ├── zzal/page.tsx     # zzal/fe 에서 import 만   → /zzal
│   │           └── trailer/page.tsx   # trailer/fe 에서 import 만 → /trailer
│   └── api/                 # Spring Boot 실행 셸 (main 클래스 + application.yml)
│
├── build.gradle             # 백엔드 빌드 설정 (레포 전체에 이거 하나뿐)
├── settings.gradle          # 프로젝트 이름만
├── package.json             # npm workspaces 루트
├── infra/                   # Docker, CI/CD, .env 예시
└── README.md
```

### `common/` 과 `apps/` 는 뭐가 다른가

둘 다 도메인에 속하지 않는 공용 영역이지만 성격이 반대다.

| | 하는 일 | 지우면 | 방향 |
| --- | --- | --- | --- |
| `common/` | 여러 도메인이 **가져다 쓰는 기능 코드** (로그인, ApiResponse, SiteHeader) | 기능이 사라진다 | 도메인 → common 을 import |
| `apps/` | **실행·연결 껍데기.** 기능 코드는 없다 | 기능은 그대로, 앱이 안 켜진다 | apps → 도메인들을 import |

`apps/` 는 프레임워크가 "이 위치에 파일이 있어야 한다"고 강제해서 생긴 자리다.

- **FE**: Next.js App Router 는 URL 이 파일 위치로 결정된다. `/zzal` 이 존재하려면
  `apps/web/app/zzal/page.tsx` 가 반드시 있어야 하므로, 이 파일은 `zzal/fe` 를 import 만 하는
  한 줄짜리로 두고 실제 화면은 `zzal/fe/` 에 둔다. 담당자는 `zzal/fe/` 안에서만 작업하면 된다.
  세 도메인 라우트를 `(domains)` 라우트 그룹으로 묶은 이유는 공용 `SiteHeader` 를 붙일 위치가
  필요해서다. 괄호 폴더는 URL 에 나타나지 않으므로 `/zzal` 은 그대로 `/zzal` 이다.
  (랜딩은 헤더 아래 자체 푸터까지 갖는 한 장짜리 화면이라 헤더를 직접 렌더링한다.
  헤더 컴포넌트 자체는 랜딩과 도메인 탭이 같은 것을 쓴다.)
- **BE**: `@SpringBootApplication` main 클래스는 특정 도메인 소유가 아니라 공용 실행 지점이다.

> `apps/` 안에는 로직을 넣지 않는다. 화면/기능 코드가 여기 들어가기 시작하면 오너십 경계가 무너진다.

### 백엔드 빌드는 단일 Gradle 프로젝트

폴더는 도메인별로 나뉘어 있지만 **`build.gradle` 은 루트에 하나뿐**이다.
`sourceSets` 가 흩어진 도메인 폴더를 하나의 컴파일 단위로 묶는다.

그래서 도메인 담당자는:

- 자기 폴더(예: `zzal/be/src/main/java`) 안에서만 작업하면 되고
- 공통 기능은 그냥 import 해서 쓰면 된다 — `import com.lore.common.response.ApiResponse;`
- Gradle 모듈이나 의존성 선언 개념을 몰라도 된다. 의존성은 루트 `build.gradle` 한 곳에만 적는다.

자세한 배경과 의존 방향 규칙은 [common/docs/architecture.md](./common/docs/architecture.md) 참고.

## 기술 스택

- **Web**: Next.js (App Router) · React · TypeScript
- **API**: Spring Boot · Java 21 (예정) — 실제 초기 세팅(플러그인/버전/의존성/DB)은 백엔드 담당자가 진행

## 로컬 실행 방법

### Web

```bash
npm install        # 루트에서 (workspaces 라 루트에서 한 번만)
npm run dev        # http://localhost:3000
```

> 각 도메인의 `fe/` 가 `apps/web` 바깥에 있기 때문에 npm workspaces 를 쓴다.
> `apps/web` 에서 따로 `npm install` 하지 말고 루트에서 실행할 것.

### API

> Spring Boot 초기 세팅(플러그인·버전 확정, 스타터 의존성, Gradle Wrapper, DB 연결) 이후 실행 가능.
> 현재는 소스 경로 설정과 빈 클래스 뼈대만 존재한다.

## 현재 상태 / 역할 경계

기능 구현 전, **레포 구조 세팅 단계**.

- **레포 스캐폴딩**: 폴더 구조, 소스 경로 설정(build.gradle sourceSets / npm workspaces),
  프론트 초기 세팅 + 내비게이션, docs / infra, 패키지 뼈대까지.
- **백엔드 초기 세팅(별도 담당)**: Spring Boot 플러그인·버전 확정, 스타터 의존성, Gradle Wrapper,
  Swagger/Security 실제 구현, DB 연결, 인증 API 구현 — 이 스캐폴딩 범위에 넣지 않고 자리만 잡아둠.
  루트 `build.gradle` 의 `plugins` / `dependencies` 블록이 그 자리다.

## 작업 규칙 요약

- 자기 도메인 폴더 안(`webtoon/`, `zzal/`, `trailer/`)은 담당자가 판단해서 자유롭게.
- `common/`, `apps/`, 루트 설정은 공유 영역이라 변경 시 PR 에 이유를 남기고 공유한다.
- 도메인끼리 서로 의존하지 않는다. 공유가 필요하면 `common` 으로 올린다.
