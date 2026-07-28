# Handoff: Lore 랜딩페이지 (라이트 / 다크)

## Overview
Lore는 "우리만의 캐릭터로 노는 만화 플랫폼"입니다. 사진 한 장에서 캐릭터 시트를 만들고, 그 시트로 **4컷 만화(Comic) → 예고편 만화(Trailer) → 스크롤 웹툰(Story)** 순서로 이어가는 서비스입니다.

이 번들은 그 공용 메인 랜딩페이지 한 장의 디자인이며, 이후 각 탭 화면을 만들 때 기준이 될 디자인 시스템(컬러·타이포·컴포넌트·레이아웃 규칙)을 함께 정의합니다.

목표 구현 범위: **랜딩페이지 1개 + 라이트/다크 테마 토글**. 각 탭의 내부 화면은 이 문서 범위 밖입니다.

## About the Design Files
번들에 든 `.html` 파일은 **HTML로 만든 디자인 레퍼런스(프로토타입)**입니다. 그대로 프로덕션에 붙이는 코드가 아닙니다. 의도한 화면과 동작을 보여주는 자료이며, 해야 할 일은 **대상 코드베이스의 기존 환경(React/Next.js 등)에서 이 디자인을 다시 구현하는 것**입니다. 팀 스택 기준으로는 Next.js(App Router) + CSS 변수 기반 테마가 적절합니다.

파일을 브라우저에서 직접 열어 확인할 수 있습니다. (HTML 안의 커스텀 태그·스크립트는 프로토타이핑 런타임이므로 무시하고, 마크업 구조와 인라인 스타일 값만 참고하세요.)

## Fidelity
**High-fidelity.** 컬러·타이포·간격·라운드·상태값이 모두 최종 의도값입니다. 픽셀 기준으로 재현하되, 버튼/카드 등은 코드베이스의 기존 컴포넌트 패턴으로 감싸도 됩니다.

## Files
- `Lore Landing.dc.html` — **기준 디자인 (라이트 모드)**. 이 파일이 단일 진실 소스입니다.
- `Lore Landing Dark.dc.html` — 이전 다크 전용 탐색안. 무드 참고용이며, 실제 다크 모드는 아래 **테마 토큰 표**의 dark 값을 따르세요 (이 파일의 팔레트를 그대로 쓰지 마세요).

---

## Screens / Views

### 화면: 랜딩페이지 (단일 페이지, 앵커 스크롤)
사용자가 서비스가 무엇인지 이해하고 "우리 애 만들기"로 진입하는 화면.

컨테이너 규칙: 모든 섹션 내부는 `max-width: 1240px; margin: 0 auto;` + 좌우 패딩 `clamp(16px, 4vw, 40px)`.
모바일 우선. 브레이크포인트를 따로 두지 않고 `clamp()`와 `repeat(auto-fit, minmax(...))`로 처리합니다.

#### 1. 헤더 (sticky)
- `position: sticky; top: 0; z-index: 60;` 배경 `--bg` 82% 투명 + `backdrop-filter: blur(16px)`, 하단 1px 보더 `--border`
- 좌: 워드마크 `LORE.` — Archivo 900 / 22px / letter-spacing -0.03em. 마침표만 `--accent-comic`
- 우: 네비 `Comic · Trailer · Story` — 14px / 500 / `--text-muted`, hover 시 `--text` + 배경 `--surface-2`, radius 999px, padding 8px 10px
- 최우: 「시작하기」 pill — 배경 `--text`(ink), 글자 `--bg`, 13.5px/700, padding 10px 18px, hover `--accent-story`
- 내부 패딩 12px, 요소 간 gap `clamp(12px, 3vw, 30px)`

#### 2. 히어로
- 배경: `--hero-bg` (라이트는 3겹 radial-gradient 소프트 블렌드, 다크는 딥 인디고 블렌드 — 토큰 표 참조)
- 2열 그리드 `repeat(auto-fit, minmax(300px, 1fr))`, gap `clamp(28px, 5vw, 56px)`, 세로 패딩 `clamp(34px,7vw,84px)`
- 좌측:
  - 배지 pill: 배경 `--bg`, 1px `--border`, radius 999px, padding 7px 14px, 12.5px/500 `--text-muted`, 앞에 7px 원형 dot `--accent-comic` — 문구: **우리만의 캐릭터로 노는 만화 플랫폼**
  - H1: Noto Sans KR 900 / `clamp(34px, 4.6vw, 62px)` / line-height 1.16 / letter-spacing -0.04em / `text-wrap: pretty`
    - 문구(3줄 강제 `<br>`): **그림은 못 그려도 / 내 캐릭터는 / 있으니까**
    - ⚠ vw 계수 4.6은 의도값입니다. 8~9vw로 올리면 900~1250px 구간에서 컬럼 폭보다 글자가 커져 줄이 단어 중간에서 깨집니다.
  - 본문: `clamp(15px,3.6vw,17px)` / 1.8 / `--text-muted` / max-width 34ch — **사진 한 장에서 캐릭터를 뽑고, 설정은 내가 고쳐 가며 정합니다. 그렇게 만든 우리 애로 4컷 · 예고편 · 웹툰까지.**
  - 버튼 2개 (flex, gap 10px, wrap): 「우리 애 만들러 가기」 primary / 「먼저 예시 보기」 secondary
- 우측: 이미지 콜라주 2열 그리드 gap `clamp(10px,1.6vw,16px)`, radius 22px
  - 좌: 3/4 비율 `--tint-story` — CHARACTER SHEET
  - 우 상: 1/1 `--tint-comic` — 4-CUT / 우 하: 4/3 `--tint-trailer` — TEASER
  - 전체에 `loreBob` 부유 애니메이션 (11s ease-in-out infinite, translateY 0 → -12px)
- 히어로 하단 바: 반투명 흰 띠 + 상단 보더. 검정 pill 「한 캐릭터」 + 13.5px 설명 — **4컷 · 예고편 · 웹툰 — 어느 탭에서 꺼내도 같은 얼굴, 같은 설정으로 이어집니다.**

#### 3. 섹션 인트로
- 라벨: IBM Plex Mono 11.5px / letter-spacing 0.18em / uppercase / `--accent-story` — `Comic → Trailer → Story`
- H2: 900 / `clamp(27px,6.6vw,48px)` / 1.22 / -0.035em — **4컷에서 시작해 / 웹툰 한 화까지**
- 본문 15.5px / 1.8 / `--text-muted` / max-width 42ch

#### 4. 3개 탭 카드 (핵심)
`repeat(auto-fit, minmax(290px,1fr))`, gap `clamp(14px,2vw,20px)`. 카드 공통: radius **28px**, padding `clamp(22px,4vw,30px)`, `min-height: clamp(340px,64vw,440px)`, flex column, 전체가 링크(`<a>`).
카드 상단 행: 좌측 순번 배지 pill(11.5px/700, 흰 글자) + 우측 `↗` 18px.
H3: 900 / `clamp(22px,4.6vw,27px)` / 1.32 / -0.025em. 본문 14.5px / 1.75 / max-width 32ch. 프리뷰 블록은 `margin-top: auto`로 카드 하단 정렬.

| 순서 | 탭 | 배경/보더 | 배지 | 제목 | 본문 | 프리뷰 |
|---|---|---|---|---|---|---|
| 01 | Comic (시작점) | `--tint-comic` / `--accent-comic` 22% | `01 Comic · 시작점` | 한 방에 웃기는 / 4컷 만화 | 오늘 있었던 일을 우리 애한테 시켜보세요. 대사 두 줄이면 기승전결 4컷, 그대로 타임라인에 올릴 수 있는 크기로. | 2×2 정사각 그리드(radius 14), 4번째 칸은 `--accent-comic` 채움 + `4 / PUNCH` |
| 02 | Trailer (건너뛰기 가능) | `--tint-trailer` / `--accent-trailer` 20% | `02 Trailer · 건너뛰기 가능` | 다음 화가 궁금한 / 예고편 만화 | “다음 화 언제 나와요”를 내 캐릭터로 만들어 보는 티저 컷. 본편은 아직 없어도, 기다리게 만드는 건 오늘 됩니다. | 16:9 와이드 컷(radius 16) + 하단 1:1 3컷, 마지막 칸 `--accent-trailer` 채움 + `TO BE` |
| 03 | Story (본편) | `--accent-story` 풀 색면, 흰 글자 | `03 Story · 본편` (흰색 18% 배경) | 내가 주인공인 / 스크롤 웹툰 | 세계관을 고르면 그 안에서 내 캐릭터가 어떤 존재였는지부터 시작합니다. 쌓인 컷에 스토리를 붙여 한 화 완성. | 좌: 16:9 + 16:11 세로 스택(`EP.01 SCROLL`), 우: 3/4 |

> 순서는 반드시 **Comic → Trailer → Story**. 사용자 여정(가볍게 시작 → 선택적 티저 → 본편)을 표현한 것이라 알파벳/기존 순서로 바꾸면 안 됩니다.

#### 5. "캐릭터 시트 하나가 세 탭을 굴립니다"
- 섹션 배경 `--surface-2`, 상하 1px 보더. 2열 `repeat(auto-fit, minmax(300px,1fr))`, gap `clamp(26px,5vw,56px)`
- 좌: 라벨 `One character sheet`(`--accent-comic`) + H2 900 `clamp(26px,6vw,42px)` + 본문 max-width 34ch
- 우: 3개 스텝 카드 세로 스택 gap 12px — 배경 `--bg`, 1px `--border`, radius 20px, padding 18px 20px
  - 번호 원형 배지 30×30, radius 999px, 흰 글자, Archivo 800 13px — 01 `--accent-story` / 02 `--accent-comic` / 03 `--accent-trailer`
  - 01 **우리 애 만들기** — 사진 한 장에서 뽑고, 얼굴·표정·설정은 마음에 들 때까지 직접 고칩니다.
  - 02 **원하는 포맷에서 꺼내 쓰기** — 같은 시트로 4컷도, 예고편도, 웹툰도. 매번 다시 만들 필요 없습니다.
  - 03 **보여주고, 다음 화로** — 올린 컷에 달린 반응이 다음 화 소재가 됩니다. 친구 캐릭터를 불러와 같이 굴려도 되고요.

#### 6. Design foundations 섹션
컬러 스와치 / 타입 스케일 / 컴포넌트 샘플 3열. **프로덕션 랜딩에서는 제거**하고 내부 스타일가이드 페이지로 옮기는 것을 권장합니다(디자인 참고용 섹션).

#### 7. CTA
- 배경 `--ink-section`(라이트/다크 모두 잉크 톤), 흰 글자
- H2 900 / `clamp(28px,7.2vw,56px)` / 1.18 / -0.04em — **우리 애 이야기, / 오늘 1화부터**
- 본문 15.5px / 흰색 70% / max-width 36ch — 사진 한 장이면 됩니다. 그림 실력도, 설정집도 미리 준비할 필요 없어요.
- 버튼: 「사진 올리기」 배경 `--accent-comic` / 「둘러보기」 흰색 35% 아웃라인

#### 8. 푸터
워드마크 + Comic/Trailer/Story 링크 + 우측 `© 2026 LORE — 우리만의 캐릭터로 만드는 만화`. 12.5px, `--text-subtle`, 상단 1px 보더.

---

## Design Tokens (라이트 / 다크)

CSS 변수로 정의하고 `:root` / `[data-theme="dark"]`로 스왑하세요. 두 테마의 **역할(semantic)은 동일**하고 값만 다릅니다.

| 토큰 | Light | Dark | 용도 |
|---|---|---|---|
| `--bg` | `#FFFFFF` | `#0E0B16` | 페이지 배경, 카드 흰 면 |
| `--surface-2` | `#F7F5FB` | `#171325` | 섹션 대비 배경 |
| `--text` | `#17121F` | `#F3F0FA` | 본문 최고 대비, primary 버튼 배경(라이트) |
| `--text-muted` | `#4B4358` | `#B3AAC6` | 본문 |
| `--text-subtle` | `#7A7288` | `#8A819F` | 캡션·푸터 |
| `--border` | `rgba(23,18,31,0.07)` | `rgba(243,240,250,0.10)` | 헤어라인 |
| `--accent-story` | `#5B2BE8` | `#7B54FF` | Story 탭, 라벨 |
| `--accent-comic` | `#FF6B45` | `#FF7E5C` | Comic 탭, 포인트 dot, CTA 버튼 |
| `--accent-trailer` | `#1C7866` | `#3FA792` | Trailer 탭 |
| `--tint-story` | `#EDE6FF` | `rgba(123,84,255,0.14)` | Story 계열 연한 면 |
| `--tint-comic` | `#FFF2E4` | `rgba(255,126,92,0.12)` | Comic 카드 배경 |
| `--tint-trailer` | `#EAF6F3` | `rgba(63,167,146,0.12)` | Trailer 카드 배경 |
| `--ink-section` | `#17121F` | `#08060F` | CTA 섹션 배경 |
| `--hero-bg` | `radial-gradient(120% 90% at 8% 0%, #E9E1FF 0%, transparent 55%), radial-gradient(100% 80% at 92% 4%, #FFE6D2 0%, transparent 52%), radial-gradient(90% 70% at 60% 100%, #DDF3F0 0%, transparent 60%), #F7F4FF` | `radial-gradient(120% 90% at 8% 0%, rgba(91,43,232,0.30) 0%, transparent 55%), radial-gradient(100% 80% at 92% 4%, rgba(255,107,69,0.20) 0%, transparent 52%), radial-gradient(90% 70% at 60% 100%, rgba(28,120,102,0.20) 0%, transparent 60%), #100B1D` | 히어로 배경 |

다크 모드 추가 규칙
1. 다크에서는 흰 카드 대신 `--surface-2`를 카드 배경으로 쓰고, 보더 대비를 한 단계 올립니다(`0.10 → 0.14`).
2. Story 카드는 라이트에서 풀 색면이지만, 다크에서는 `--accent-story`를 그대로 쓰면 눈부시므로 `#4B22C8` 정도로 한 단계 내려 사용합니다.
3. primary 버튼: 라이트 = ink 배경/흰 글자, 다크 = `--text`(밝은 색) 배경/`#0E0B16` 글자로 반전.
4. 이미지 플레이스홀더의 사선 패턴 투명도는 다크에서 2배(0.10 → 0.20) 올려야 보입니다.
5. 테마는 `prefers-color-scheme` 초기값 + 수동 토글, 선택값은 localStorage 유지.

### Typography
| 역할 | 폰트 | 크기 |
|---|---|---|
| Latin display (워드마크, 숫자) | Archivo 800/900, letter-spacing -0.03~-0.045em | 17–40px |
| KO heading | Noto Sans KR 900, letter-spacing -0.025~-0.04em, line-height 1.16–1.32 | `clamp()` 기반 (위 각 섹션 참조) |
| Body | Noto Sans KR 400, line-height 1.75–1.8, max-width 32–42ch | 13.5–17px |
| Label / meta | IBM Plex Mono 400, letter-spacing 0.16–0.18em, uppercase | 9–11.5px |

Google Fonts: `Archivo:wght@600;700;800;900`, `Noto Sans KR:wght@400;500;700;900`, `IBM Plex Mono:wght@400;500`

### Radius / Spacing / Motion
- Radius: pill `999px` (버튼·배지·네비), 탭 카드 `28px`, 스텝/콜라주 `20–22px`, 프리뷰 셀 `12–16px`
- 섹션 세로 패딩 `clamp(44px, 8vw, 96px)`, 컨테이너 좌우 `clamp(16px, 4vw, 40px)`
- 그리드 gap: 카드 `clamp(14px,2vw,20px)`, 2열 레이아웃 `clamp(26px,5vw,56px)`
- 그림자 없음 (보더 + 색면으로 위계 표현)
- 모션: `loreBob` 부유 11s, hover는 배경색 전환만 (150ms ease). 과한 transform 금지.

## Interactions & Behavior
- 네비/카드/버튼 앵커는 해당 섹션으로 스무스 스크롤. 실제 서비스에서는 `/comic`, `/trailer`, `/story` 라우트로 교체.
- 카드 전체가 클릭 영역이며 hover 시 배경 한 단계 진하게. 포커스 링은 `--accent-story` 2px outline + 2px offset으로 반드시 유지.
- 반응형: 1열(모바일) → 2열 → 3열로 auto-fit 전환. 별도 미디어쿼리 불필요.
- 로딩/에러 상태는 이 랜딩 범위에 없음(정적 페이지).

## State Management
테마 상태 하나뿐입니다: `theme: 'light' | 'dark'` (초기값 `prefers-color-scheme`, 토글 시 `document.documentElement.dataset.theme` 갱신 + localStorage 저장). 그 외 서버 상태·폼 없음.

## Assets
디자인의 모든 이미지 영역은 **사선 패턴 플레이스홀더**입니다. 실제 캐릭터 아트/컷 이미지는 아직 없습니다. 구현 시 동일 비율(3/4, 1/1, 4/3, 16/9, 16/11)의 슬롯으로 두고 이미지가 준비되면 교체하세요. 아이콘 세트 미사용(텍스트 `↗`만 사용), 로고는 텍스트 워드마크입니다.

## Copy 원칙 (중요)
- **"AI"라는 단어를 UI에 쓰지 않습니다.** 타깃(자캐 커뮤니티)에 AI 거부감이 있어 의도적으로 배제했습니다. 기술이 아니라 결과물과 정서로 말합니다.
- 사용자가 결과물을 고치고 정한다는 뉘앙스를 유지합니다("직접 고칩니다", "내가 고쳐 가며").
- 팬덤 화법("우리 애", "다음 화 언제 나와요")을 유지하되, "그림 실력·설정집 필요 없음"으로 일반인 진입 장벽을 낮춥니다.
