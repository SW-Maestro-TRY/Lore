# 웹툰 파이프라인 — 자산층(캐릭터 시트 + 존/소품 자산) 구현

## Context

`story-harness`(대본/컷 생성) → `webtoon-harness`(이미지 생성) 두 저장소로 만든 1화 결과물에서
피드백 12건이 나왔다. 층별로 갈라보면 순수 "그림 생성이 별로다"는 1건뿐이고, 나머지 11건은
① 인물이 매 컷 텍스트로 재서술되어 비율·복장이 흔들리고(윤재 증발, 자세 문제),
② 배경이 컷마다 새로 그려져 이어지지 않거나(자판기 종이컵→머그컵) 조연에게는 그림 기준선 자체가
없어서 나는 문제였다. 두 문제의 공통 원인은 같다 — **재사용 가능한 자산(캐릭터 시트·배경·소품)이
주인공 1명분밖에 없고, 나머지는 매번 프롬프트 텍스트로 새로 발명된다.**

추가로 대사·스키마·검사기·톤 관리에 대한 상세 제안(두 번째 메시지)도 받았으나, 조사 결과 그
제안들은 이번 자산층 작업과 독립적으로 진행 가능한 **후속 단계**이며 일부는 코드베이스 사실과
어긋나는 가정을 포함하고 있었다(§2). 사용자가 확인한 순서대로 — **자산층부터** — 이번 세션은
여기에 집중한다.

## 조사로 확정한 현재 상태 (근거)

- **캐릭터 시트는 구조적으로 1명 전용이다.** `webtoon-harness/charsheet.py`의 `Sheet`
  dataclass(64-89행)는 단수 설계이고, `story-harness/story.py --charsheet`가
  `runs/<id>/p1.json`(주인공 전용)에서 `appearance_en`을 읽어 시트를 생성한다
  (`story.py:1216-1219` 예시, `story.py:2095` 이하 실제 사용). 조연은 `supporting.json`에
  `gender/appearance/outfit/personality` **텍스트**만 있고(`supporting.py:88-119`), 이미지
  생성 코드가 전혀 없다. `charsheet.py`는 자기 docstring대로 **읽기 전용**이다(생성은
  story-harness 쪽) — 그래서 시트 생성 로직을 확장할 곳은 story-harness다.
- **`appearance_en`은 사진이 아니라 LLM이 짓는 영문 문단이다** (`story.py:1216-1219`가 few-shot
  예시임을 확인). 즉 조연 시트를 만들 때도 사진이 필요 없고, 이미 있는 짧은 한글
  필드(gender/appearance/outfit/personality)를 같은 방식의 영문 문단으로 확장하는 LLM 스텝
  하나만 있으면 기존 시트 생성 파이프라인(`CHARSHEET_KINDS/SIZES/RATIOS`,
  `save_charsheet_picks`, pick.html)을 그대로 재사용할 수 있다.
- **존(zone)/배경 자산 재사용 시스템은 어디에도 없다.** 배경은 매 컷 `description` 자유 텍스트로만
  재생성되고, 유일한 이음매 장치는 인접 장면 텍스트 힌트(`scenegen.seam_text()`,
  `scenegen.py:375-406`)와 조건 `S+`(직전 장 이미지 재첨부, `config.yaml:865-900`)뿐이다 —
  둘 다 "바로 앞 장면"만 잇고 재사용 가능한 자산이 아니다.
- **소품(prop) 일관성 장부도 없다.** `setting.props`(`scenegen.py:466-473`)는 W5가 매 화 새로
  적는 자유 텍스트 목록이고 저장되지 않는다.
- **`series.json`(`SeriesState`, `webtoon.py:736-745`)은 이미 "명부가 쌓이고 다음 화 프롬프트에
  되돌아가는" 패턴을 갖고 있다** — `cast[]`(인물), `places[]`(장소 이름+최초 등장화, 시각 정보
  없음), `facts[]`, `status[]`. 이번 작업은 이 패턴을 그대로 따라 `zones[]`/`props[]`를
  추가하는 것이다 — 새 아키텍처가 아니라 기존 패턴의 확장.

## 이번 세션 범위에서 제외한 것 (다음 세션들)

아래는 이번에 만들지 않는다. 자산층이 실제로 쓰이려면 결국 필요하지만, 각각 자체 설계 결정이
필요해서 분리했다 — 완료되면 다음 세션에서 순서대로 이어간다.

| 단계 | 내용 | 왜 지금 안 하나 |
|---|---|---|
| B. 텍스트 분리 렌더링 | 말풍선·효과음·화면UI 텍스트를 이미지에 굽지 않고 합성 | `overlay` 모드는 이미 있음(`bubbles.py`) — 효과음 예외(`scenegen.sfx_clause()` 261-275행, 항상 이미지에 굽는다)만 고치면 됨. 자산층과 독립적 |
| C. 컷표 스키마 확장 | `characters_in_frame`, `composition`, `bubble_zone`, `screen_text`, `scenes[].tone` | 존 자산이 실제로 붙는 걸 먼저 보고 나서, "그림에서 뭐가 더 안 맞았나"로 스키마를 정하는 게 낫다 |
| D. W7.5 기계 검사기 | 존 불연속·소품 이탈 등 코드 검사 | 검사 대상 필드(zone/characters_in_frame)가 이번에 막 생기므로, 다음 화를 뽑아 본 뒤 무엇이 실제로 어긋나는지 보고 짠다 |
| E. W8 대사 패스 + W8.5 | 대사를 컷 분해에서 분리해 화 전체를 한 번에 다시 쓰는 별도 LLM 단계 | 자산층과 독립. **주의**: 사용자가 준 "대사타입" 컬럼 제안은 실제로는 불필요 — `dialogue/narration/thought/sfx`가 이미 서로 다른 4개 필드다(`prompts/w7.txt:687-691`). 정말 없는 건 5번째 필드인 "화면 텍스트"뿐. 또 "톤을 W5로 옮기자"는 제안도 W5엔 `scenes[]` 자체가 없어서(장면·분위기는 이미 **W7** 출력, `prompts/w7.txt:669-675`) 톤 라벨은 W7의 `scenes[]`에 붙는 게 맞다. 이 두 교정은 다음 세션에서 C·E 설계할 때 반영 |

---

## 이번 세션 구현 내용

### 1. `series.json`에 `zones[]` / `props[]` 추가 (story-harness)

**파일**: `story-harness/webtoon.py`

- `SeriesState` (736-745행) — 필드 2개 추가:
  ```python
  zones: list[dict]   # {"zone_id","place","label","relative_position","first_episode"}
  props: list[dict]   # {"prop_id","zone_id","name","fixed_facts":[...],"first_episode"}
  ```
- `.as_dict()`/`.save()`/`load()` 대응 갱신 (기존 `places`/`cast` 처리와 동일 패턴).
- `SeriesState.add()` (771-779행 부근) — W7이 새 `zone_id`를 쓰면 스텁 등록 (`record_cut_cast()`가
  화자 스텁을 추가하는 방식, 1844-1873행과 동일한 패턴으로 `record_cut_zone()` 신설):
  이름/장소만 채우고 `label`/`relative_position`은 빈 채로 남겨 사람이 채우게 한다.
- `props[]`는 **자동 생성하지 않는다.** LLM이 "이 소품은 이런 고정 사실을 갖는다"를 지어내는 건
  신뢰도가 없다(코드베이스가 이미 프롬프트 산문 기반 게이트를 없앤 전례가 있다,
  `webtoon.py:1546-1551` `text_warnings` docstring). 사람이 `series.json`을 직접 열어
  `props[]`에 항목을 추가하는 수동 등록으로 시작한다 — `places[]`가 사람이 안 고치는 자동
  기록인 것과 달리, `props[]`는 `supporting.json`처럼 사람이 채우는 파일이다.
- `brief()` (827-889행 부근, 다음 화 프롬프트에 "이미 나온 장소" 보여주는 함수) — 같은 자리에
  "이미 나온 존" 목록도 보여주도록 확장. 소품은 존 옆에 표시.

### 2. `Cut`에 `zone` 필드 추가 — 이번에 필요한 유일한 스키마 변경 (story-harness)

**파일**: `story-harness/prompts/w7.txt`, `story-harness/webtoon.py`

- w7.txt 스키마 블록(666-703행)에 `zone: str` 추가. 프롬프트에 "이미 등록된 존 목록"(위 `brief()`
  확장분)을 보여주고, 그 화의 장소에 해당하는 존이 있으면 **재사용**, 진짜 새 구역이면 짧은
  kebab-case id(`z-hallway-vending`)를 새로 짓게 지시.
- `gate_layout()`(1695행 부근)에 최소 검사만 추가: `zone`이 비어있지 않은가. (존-불연속 같은
  정교한 검사는 D 단계로 미룸 — 지금은 "필드가 채워졌는가"만 본다.)
- `characters_in_frame` 필드는 **이번엔 추가하지 않는다.** 캐릭터 시트를 어느 컷에 붙일지는
  기존의 이름-매칭 방식(`cast.py:171-195`, `supporting.py:326-330`)이 이미 여러 조연 이름에
  대해 동작하므로, 시트가 여러 개로 늘어나도 그대로 재사용된다 — 새 필드 없이도 이번 기능이
  작동한다. (정확도 개선은 C 단계에서.)

**webtoon-harness 쪽 미러링**: `storyload.Cut` dataclass(48-69행 부근)에 `zone: str = ""` 추가,
`_cut_from()`(212-239행)에서 읽기. README.md 필드 매핑 표(1284-1298행 부근)에 한 줄 추가.

### 3. 캐릭터 시트를 전원(주인공+조연)으로 확장 (story-harness)

**파일**: `story-harness/story.py`

- 새 스텝 "PN"(가칭) 프롬프트 — `series.json.cast[]`에서 아직 시트가 없는 이름을 골라, 그
  인물의 기존 `gender/appearance/outfit/personality`(짧은 한글 필드)를 P1 스키마의 축약판
  (`appearance_en` 필수, `design_details`/`color_palette`/`expression_set`은 선택 — 주인공만큼
  정교할 필요는 없지만 최소 표정 세트는 있어야 컷마다 얼굴이 안 흔들린다)으로 확장하는 LLM 호출
  1회. 결과를 `runs/<id>/charsheet/<이름>/p.json` 같은 이름공간에 저장 (기존 `p1.json`은 주인공
  전용으로 그대로 둬서 하위 호환 유지).
- `--charsheet` 플래그에 `--character <이름>` 옵션 추가 (생략 시 기존처럼 주인공). 생성·후보
  저장·픽 로직(`CHARSHEET_KINDS/SIZES/RATIOS`, `save_charsheet_picks`, `pick.html`,
  4025-4035행·4515-4538행 등)은 이미 인물 무관하게 짜여 있으므로 **그대로 재사용** — 읽는
  경로(`p1.json` → `runs/<id>/charsheet/`)만 인물별로 파라미터화한다.
- 조연은 사진이 없으므로 4면도(`--split`)까지는 과함 — 기본은 통합 시트(`sheet`) 1장 + 표정
  1장 정도로 시작(비용/가치 균형). 필요하면 나중에 `--split`도 켤 수 있게 옵션은 열어둔다.

### 4. 존 배경 자산 생성 (story-harness — 캐릭터 시트와 같은 자리에 둔다, R2 결정과 동일 이유)

**파일**: `story-harness/story.py` (신규 함수, `--charsheet`와 나란히 `--zonesheet` 커맨드)

- `series.json.zones[]`의 각 항목(사람이 `label`/`relative_position`을 채운 것)에 대해, 배경
  1장을 생성해 `runs/<id>/zonesheet/<zone_id>/`에 저장. 프롬프트는 zone의 `place` +
  `label` + `relative_position` + 그 화의 `setting`(time/weather/light)을 합성.
  캐릭터 시트와 동일한 candidate=1/자동 채택 철학(`README.md` "후보 장수" 절)을 따른다 —
  비교 실험이 아니라 실제로 쓸 자산 1장이 목적이므로.
- 채택 기록은 `charsheet_picks.json`과 같은 패턴으로 `zonesheet_picks.json`.

### 5. webtoon-harness에서 시트/배경 자산 첨부 (webtoon-harness)

**파일**: `webtoon-harness/charsheet.py`, `webtoon-harness/run.py`

- `charsheet.py`의 `Sheet`를 단수 → **이름으로 키를 가진 dict**로 일반화. `load()`가
  `runs/<id>/charsheet/`를 스캔해 인물별 서브폴더를 전부 읽어 `{name: Sheet}`을 반환하도록 변경.
- `run.py:apply_charsheet()`(1422-1492행)를 인물별로 확장: 컷/장면에 등장하는 인물 이름을
  기존 이름-매칭(`cast.py`/`supporting.py`)으로 찾아, **그 인물의 시트만** 첨부. 지금처럼
  주인공 시트를 조연이 나오는 컷에까지 붙이는 실수를 막는다 (README가 이미 경고한
  "조연이 주인공 얼굴로 그려진다" 문제, README:592-596행).
- 새 함수 `apply_zonesheet()` — 컷의 `zone` 필드(§2에서 추가)가 `zonesheet_picks.json`에 있는
  존과 일치하면 그 배경 이미지를 참조로 첨부. 기존 `S+` 조건(직전 장 이미지)과는 **병행** —
  존 배경은 "이 장소는 항상 이렇게 생겼다"를, `S+`는 "인물이 방금 전과 이어진다"를 맡아 서로
  다른 문제를 푼다.
- 기존 캐릭터 시트가 있는 run(조연 시트 없음)에서도 깨지지 않아야 한다 — 시트 없는 인물은
  지금처럼 텍스트 블록(`supporting.block()`)만 붙는 경로로 자연히 폴백.

---

## 검증 방법

1. **0원 확인**: `story.py --charsheet --character 하윤재 --run-id <id> --dry-run`로 조연 시트
   프롬프트만 출력해 영문 문단이 말이 되는지 확인 (API 호출 없음).
2. **시트 생성 1건**: 위에서 하윤재 시트 실제로 1장 뽑아 눈으로 확인 (7등신 비율 유지되는지,
   기존 조연 텍스트 묘사와 일치하는지).
3. **존 배경 1건**: `--zonesheet`로 K대 복도의 소파존 배경 1장 생성, 프롬프트 dry-run으로 먼저
   확인 후 실제 생성.
4. **통합 실행**: `webtoon-harness`에서 `python run.py --run-id <id> --episode 1 --mode scene
   -c S+ --style webtoon --dry-run`로 프롬프트 조립 결과에 하윤재 시트와 존 배경이 올바른 컷에만
   첨부되는지 로그로 확인 (API 호출 없음), 이후 실제 생성해 1화를 통독하며 윤재 비율·복장,
   배경 일관성이 이전 대비 개선됐는지 확인.
5. **회귀 확인**: 조연 시트/존 자산이 없는 기존 run으로 같은 명령을 돌려도 에러 없이 기존처럼
   동작하는지 확인 (폴백 경로).
