# 웹툰 행동 기록

웹툰 화면에서 사람들이 어디까지 오고 무엇을 누르는지 남기는 기록입니다(#413).
이벤트를 새로 달거나, 기록을 보고 숫자를 뽑을 때 읽습니다.

## 구조

```
화면  webtoon/fe/lib/track.ts   track("이름", { 키: 값 })
  → POST /api/webtoon/v1/events  (EventController)
  → 깎아서 저장                   (EventService)
  → 표 webtoon_event
```

- **공용 행동 기록(`/api/v1/events`, `zzal_event`)과 섞지 않습니다.** 짤 쪽 수집기라
  허용 키와 보관 기준이 짤 화면에 맞춰져 있습니다. 웹툰은 따로 둡니다.
- **외부 분석 도구(Amplitude 등)는 쓰지 않습니다.** 개인정보처리방침 제13조 3항이
  "외부 분석 서비스를 사용하지 않는다"고 약속합니다. 외부 도구를 쓰려면 제6조 국외 이전
  표에 이전받는 자를 추가해야 하고, 제15조 2항에 따라 시행 30일 전에 알려야 합니다.
  이벤트 이름과 값은 나중에 서버에서 그대로 넘길 수 있는 모양(이름 + 평평한 키·값)으로
  맞춰 두었습니다.
- 모아 두었다가 5초마다 또는 20줄이 차면 보냅니다. 화면이 숨겨지거나 페이지를 떠날 때는
  남은 것을 `sendBeacon` 으로 보냅니다.

## 사람 구분

| 칸 | 무엇 |
|---|---|
| `uid` | 이 브라우저. 웹툰 화면이 이미 쓰는 localStorage `lore_uid` |
| `user_id` | 로그인했으면 계정 번호 |

같은 사람을 로그인 전후로 잇고 싶으면 `uid` 로 묶습니다(로그인한 뒤의 줄에는 둘 다 있습니다).

## 무엇을 안 남기나

- **사람이 쓴 글**(이름·설명·메모·이야기·고친 본문·이메일)은 값으로 싣지 않습니다.
  `has_note: true` 처럼 있었는지만 싣습니다.
- 서버는 허용한 키(`EventService.ALLOWED_KEYS`)만, 값은 짧은 기호(영문·숫자·`_.:-`,
  64자까지)·숫자·참거짓만 저장합니다. 한글이나 공백이 든 값은 버립니다.
- IP 와 브라우저 원문은 저장하지 않습니다. 기기는 `mobile`/`desktop` 둘로만, 유입
  주소는 호스트만 남깁니다.
- 1년이 지나면 지우고(`EventRetention`, 매일 05:45, `lore.retention.sweep-enabled`),
  탈퇴한 사람의 줄은 계정을 지울 때 함께 지웁니다(`WebtoonEventPurge`).

**새 키가 필요하면** `EventService.ALLOWED_KEYS` 에 먼저 넣습니다. 거기 없는 키는 화면이
보내도 저장되지 않습니다.

## 유저 스토리와 깔때기

"100명이 들어와서 50명이 가입했다" 같은 말을 하려면, 단계마다 **그 단계를 지났다고
셀 이벤트**가 하나씩 정해져 있어야 합니다. 아래 표가 그 약속입니다. 이벤트를 새로 달거나
없앨 때 이 표부터 고칩니다. 세는 단위는 사람(`coalesce(user_id, uid)`)입니다.

### A. 들어와서 만들기까지

| # | 사람이 하는 일 | 세는 이벤트 | 조건 |
|---|---|---|---|
| A1 | 웹툰 탭에 들어온다 | `session_start` | — (`kind` 로 처음/재방문, `target` 으로 어느 화면으로 들어왔는지) |
| A2 | 첫 화면에서 시작 단추를 누른다 | `landing_cta` | — |
| A3 | 갈림길에서 「웹툰 만들기」를 고른다 | `page_view` | `view = create`, `step = 1` |
| A4 | 1걸음: 캐릭터(사진·이름)를 넣고 다음으로 | `page_view` | `view = create`, `step = 2` |
| A5 | 2걸음: 이야기·장르를 넣고 다음으로 | `page_view` | `view = create`, `step = 3` |
| A6 | 3걸음: 그림체를 고르고 다음으로 | `page_view` | `view = create`, `step = 4` |
| A7 | 4걸음: 화질·방식을 고르고 「웹툰 만들기」를 누른다 | `create_start` | — |
| A8 | 서버가 받는다 (여기서 빠지면 오류·한도) | `create_started` | 못 받으면 `create_failed` · 막혔으면 `create_blocked` |
| A9 | 만드는 화면을 끝까지 지킨다 | `job_status` | `status = done` (중간에 나간 사람은 `page_leave` 의 `target = running` 만 있고 done 이 없음) |
| A10 | 완성본을 본다 | `page_view` | `view = result` |
| A11 | 끝까지 읽는다 | `read_end` | — |
| A12 | 공유·내려받기·편집실·다음화 중 하나를 한다 | `share_click` · `download_click` · `editor_open` · `next_episode_click` | — |

작업이 실제로 끝났는지는 `webtoon_job.status` 가 정본입니다. A9 는 「사람이 화면에서
끝을 봤는가」입니다. 둘이 다르면 그 차이가 「닫고 나가서 못 본 사람」입니다.

### B. 캐릭터 만들어보기

| # | 사람이 하는 일 | 세는 이벤트 | 조건 |
|---|---|---|---|
| B1 | 갈림길에서 「캐릭터 만들어보기」를 고른다 | `page_view` | `view = try` |
| B2 | 무엇이든 넣고(또는 랜덤) 「캐릭터 만들기」를 누른다 | `try_start` | — |
| B3 | 카드가 그려진다 | `try_result` | `status = ready` |
| B4 | 카드를 공유한다 | `card_share` | — |
| B5 | 이 캐릭터로 1화를 만들러 간다 | `card_to_webtoon` | 이 뒤로는 A4 부터 이어짐 |
| B6 | 다시 뽑는다 | `try_again` | — |

**공유로 들어온 사람의 깔때기**: `session_start` 의 `target = sharedCard` → `shared_card_try`
→ B2 부터.

### C. 로그인·가입

로그인 창은 공용 헤더의 것이라 웹툰 기록이 창 안을 보지 못합니다. 그래서 「문턱을 봤다」와
「이 방문 중에 로그인 상태가 됐다」 두 점만 셉니다. 가입인지 로그인인지는 SQL 에서
`users.created_at` 이 그 방문 안에 있는지로 가릅니다.

| # | 사람이 하는 일 | 세는 이벤트 | 조건 |
|---|---|---|---|
| C1 | 로그인 안 한 채 들어온다 | `session_start` | `logged_in = false` |
| C2 | 로그인이 필요한 문턱을 본다 | `login_prompt` | `where`: editor · character_limit (`create_blocked` 의 `logged_in = false` 도 같은 뜻) |
| C3 | 이 방문 중에 로그인 상태가 된다 | `auth_done` | `target` = 그때 보던 화면 |
| C4 | 그중 새로 가입한 사람 | `auth_done` + `users` | `users.created_at` 이 `auth_done.occurred_at` 앞 10분 안 |

### D. 만드는 동안 (A8 ~ A9 사이)

| # | 사람이 하는 일 | 세는 이벤트 |
|---|---|---|
| D1 | 이야기 후보를 고른다 | `story_pick` (본문을 고쳤으면 `edited`) · 다시 짓기는 `story_retry` |
| D2 | 캐릭터 시트를 승인한다 | `sheet_decide` `result = approve` · 다시는 `retry` |
| D3 | 알림 메일을 남긴다 | `notify_optin` |
| D4 | 기다리는 동안 둘러보기로 간다 | `browse_while_waiting` |
| D5 | 중단한다 | `job_cancel` |
| D6 | 화면을 떠난다 | `page_leave` `target = running` (`ms` 로 얼마나 있다 나갔는지) |

### E. 둘러보기(남의 작품)

| # | 사람이 하는 일 | 세는 이벤트 |
|---|---|---|
| E1 | 둘러보기에 온다 | `page_view` `view = works` |
| E2 | 작품을 연다 | `works_open` (`where` 로 첫 화면인지 둘러보기인지) |
| E3 | 끝까지 읽는다 | `read_end` `mine = false` |
| E4 | 다음화를 찾는다 | `next_episode_click` `where = other_foot` |
| E5 | 직접 만들러 간다 | `page_view` `view = entry` 가 그 뒤에 옴 |

## 이벤트 목록

작업의 실제 성공·실패·걸린 시간·환불·크레딧은 이벤트로 다시 모으지 않습니다. 서버의
`webtoon_job`(상태·시작·끝 시각·대기 순번·환불)과 크레딧 장부에 이미 있습니다.

| 이벤트 | 언제 | 주요 값 |
|---|---|---|
| `session_start` | 한 탭에서 웹툰 화면을 처음 열 때 (`WebtoonPage.tsx`) | `kind`: new · returning(이 브라우저가 전에 왔었나), `target`(들어온 화면), `logged_in` |
| `page_view` | 화면이 바뀔 때마다 | `view` 칸에 화면 이름, `step`(만들기 화면만), `run`, `logged_in` |
| `page_leave` | 화면을 떠날 때 (다른 화면으로 가거나 페이지를 닫을 때) | `target`(떠난 화면), `ms`(머문 시간, 탭이 뒤로 가 있던 시간 포함) |
| `auth_done` | 이 방문 중에 로그인 안 한 상태에서 로그인 상태가 됨 | `target`(그때 보던 화면) |
| `login_prompt` | 로그인이 필요한 문턱을 봄 | `where`: editor · character_limit |
| `landing_cta` | 첫 화면의 시작 단추 | `where`: hero · bottom · need_create · need_try · works_all |
| `create_blocked` | 마지막 걸음에서 막힘이 보일 때 | `reason`: blocked · free_used, `logged_in`, `free_left` |
| `create_start` | 「웹툰 만들기」를 누름 | `quality`, `style`, `mode`, `count`(사진 수), `character`, `has_desc`, `has_note`, `preset`(장르를 목록에서 골랐나), `cost`, `free_left`, `logged_in` |
| `create_started` · `create_failed` | 서버가 작업을 받음 · 못 받음 | 위 값 + `job` · `status`(HTTP 코드) |
| `job_status` | 만드는 화면이 본 상태가 바뀔 때 | `job`, `status`, `reason`(실패 때 환불 종류) |
| `sheet_decide` | 캐릭터 시트 승인 · 다시 | `result`: approve · retry, `has_note` |
| `story_pick` | 이야기 후보를 확정 | `n`(몇 번), `count`(후보 수), `edited`(본문을 고쳤나) |
| `story_retry` | 후보 다시 만들기 | `has_note` |
| `notify_optin` | 완성 알림 메일 받기 | `job` |
| `browse_while_waiting` | 기다리는 동안 둘러보기로 감 | `pane`, `status` |
| `job_cancel` | 만들기 중단 | `status`, `count`(그린 장), `page`(전체 장) |
| `remake_after_fail` | 실패 화면에서 다시 만들기 | `job` |
| **`next_episode_click`** | 다음화 단추 (아직 준비 중 창이 뜸) | `where`: mine_button(PC 내 작품) · mine_mobile(폰 내 작품) · mine_tile(다른 편 줄의 EP.n 만들기) · other_foot(남의 작품 「다음화 보기」), `mine`, `run`, `ep`, `logged_in` |
| `read_end` | 완성본을 끝까지 내림 (작품당 한 번) | `run`, `mine`, `ep`, `page` |
| `share_click` | 완성본 공유 | `target`: native · menu · kakao · x · line · postype · copy |
| `download_click` · `download_per_page_open` | 내려받기 | `kind`: episode · page, `page` |
| `editor_open` · `sibling_open` | 편집실 · 같은 캐릭터의 다른 편 | `run` |
| `works_open` · `works_filter` · `visibility_change` | 둘러보기 | `where`, `mine`, `filter`: mine · genre, `result`: public · private |
| `try_random_click` · `try_start` | 캐릭터 만들어보기 | `has_photo`, `has_name`, `has_desc`, `preset`(세계관을 목록에서), `random` |
| `try_result` · `try_again` | 카드가 다 그려짐 · 다시 뽑기 | `character`, `status`: ready · error |
| `card_share` · `card_to_webtoon` · `shared_card_try` | 카드 공유 · 이 캐릭터로 1화 · 공유 카드를 보고 「나도 만들어보기」 | `target`, `character` |
| `swap_survey_view` · `swap_survey` | 종이 바뀐 카드(#331)의 설문 팝업이 뜸 · 답함 (다 그려지고 5초 뒤, 카드마다 한 번) | `character`, `result`: confused(당황했어요) · fine(괜찮았어요) |
| `limit_view` | 하루 몫이 다 됨 | `kind`: character |
| `regen_start` · `regen_result` · `regen_revert` · `bake` | 편집실 다시 그리기 · 결과 · 판 되돌리기 · 이미지로 뽑기 | `cut`, `count`(태그 수), `has_note`, `status`, `n`(판) |
| `charge_open` | 마이페이지에서 충전 창을 엶 | `where` |

### 알고 있는 한계

- 폰의 공유 화면은 사용자가 닫아도 성공으로 돌려줍니다(`lib/share.ts`). 그래서
  `share_click`·`card_share` 는 **누른 것**까지만 정확합니다.
- 내려받기는 `<a download>` 라서 누른 것만 알고, 받았는지는 모릅니다.

## 자주 보는 숫자

로컬 DB 는 `psql -U mint -d lore`, staging 은 `webtoon/docs/server.md` 「DB 직접 접근」.

**다음화를 누가, 어느 단추에서, 얼마나 누르나**

```sql
select date(occurred_at at time zone 'Asia/Seoul') as day,
       props::jsonb->>'where'                         as where_,
       count(*)                                        as clicks,
       count(distinct coalesce(user_id::text, uid))    as people,
       count(distinct user_id)                         as logged_in_people
from webtoon_event
where name = 'next_episode_click'
group by 1, 2
order by 1 desc, 3 desc;
```

(`props` 는 TEXT 라 `props::jsonb->>'where'` 로 씁니다. 아래도 같습니다.)

**끝까지 읽고 다음화를 눌렀나**

```sql
select count(distinct e.uid) filter (where r.uid is not null) as read_to_end_then_clicked,
       count(distinct e.uid)                                  as clicked
from webtoon_event e
left join webtoon_event r
  on r.name = 'read_end' and r.uid = e.uid
 and r.props::jsonb->>'run' = e.props::jsonb->>'run' and r.occurred_at <= e.occurred_at
where e.name = 'next_episode_click';
```

**깔때기 A (최근 7일, 사람 수) — 위 표의 A1~A12**

```sql
with p as (
  select coalesce(user_id::text, uid) as who, name, view, props::jsonb as props
  from webtoon_event
  where occurred_at > now() - interval '7 days'
),
steps(ord, stage, who) as (
  select 1,  'A1 들어옴',        who from p where name = 'session_start'
  union select 2,  'A2 시작 단추',   who from p where name = 'landing_cta'
  union select 3,  'A3 만들기 1걸음', who from p where name = 'page_view' and view = 'create' and props->>'step' = '1'
  union select 4,  'A4 2걸음',       who from p where name = 'page_view' and view = 'create' and props->>'step' = '2'
  union select 5,  'A5 3걸음',       who from p where name = 'page_view' and view = 'create' and props->>'step' = '3'
  union select 6,  'A6 4걸음',       who from p where name = 'page_view' and view = 'create' and props->>'step' = '4'
  union select 7,  'A7 만들기 누름',  who from p where name = 'create_start'
  union select 8,  'A8 서버가 받음',  who from p where name = 'create_started'
  union select 9,  'A9 끝까지 봄',    who from p where name = 'job_status' and props->>'status' = 'done'
  union select 10, 'A10 완성본',      who from p where name = 'page_view' and view = 'result'
  union select 11, 'A11 끝까지 읽음', who from p where name = 'read_end'
  union select 12, 'A12 공유·내려받기·편집·다음화', who from p
        where name in ('share_click', 'download_click', 'editor_open', 'next_episode_click')
)
select ord, stage, count(distinct who) as people,
       round(100.0 * count(distinct who) / nullif(first_value(count(distinct who)) over (order by ord), 0), 1) as pct_of_entry
from steps group by ord, stage order by ord;
```

**깔때기 C — 로그인·가입 (최근 7일)**

```sql
with s as (
  select uid, min(occurred_at) as entered
  from webtoon_event
  where name = 'session_start' and (props::jsonb->>'logged_in') = 'false'
    and occurred_at > now() - interval '7 days'
  group by uid
),
a as (
  select e.uid, e.user_id, e.occurred_at
  from webtoon_event e join s on s.uid = e.uid
  where e.name = 'auth_done' and e.occurred_at >= s.entered
)
select (select count(*) from s)                                                  as entered_logged_out,
       (select count(distinct uid) from webtoon_event e
         where e.name = 'login_prompt' and e.uid in (select uid from s))         as saw_login_prompt,
       (select count(distinct uid) from a)                                       as logged_in_during_visit,
       (select count(distinct a.uid) from a join users u on u.id = a.user_id
         where u.created_at between a.occurred_at - interval '10 minutes' and a.occurred_at) as signed_up;
```

**화면별 머문 시간 (중앙값, 초)**

```sql
select props::jsonb->>'target' as screen,
       count(*) as leaves,
       round(percentile_cont(0.5) within group (order by (props::jsonb->>'ms')::numeric) / 1000) as median_sec
from webtoon_event
where name = 'page_leave' and occurred_at > now() - interval '7 days'
group by 1 order by 2 desc;
```

**만들기 깔때기 (짧은 판)**

```sql
with p as (
  select coalesce(user_id::text, uid) as who, name, view, props::jsonb as props
  from webtoon_event
  where occurred_at > now() - interval '7 days'
)
select 'landing'         as stage, count(distinct who) from p where name = 'page_view' and view = 'landing'
union all select 'create step 1', count(distinct who) from p where name = 'page_view' and view = 'create' and props->>'step' = '1'
union all select 'create step 4', count(distinct who) from p where name = 'page_view' and view = 'create' and props->>'step' = '4'
union all select 'create_start',  count(distinct who) from p where name = 'create_start'
union all select 'create_started',count(distinct who) from p where name = 'create_started'
union all select 'result',        count(distinct who) from p where name = 'page_view' and view = 'result';
```

**만들기가 실제로 끝났나 (이벤트가 아니라 작업 표)**

```sql
select status, count(*),
       percentile_cont(0.5) within group (order by extract(epoch from finished_at - started_at)) / 60 as median_min
from webtoon_job
where created_at > now() - interval '7 days'
group by status;
```

**공유 카드로 들어와서 직접 만들어 봤나**

```sql
select count(distinct uid) filter (where name = 'page_view' and view = 'sharedCard') as saw_shared_card,
       count(distinct uid) filter (where name = 'shared_card_try')                  as tried_after
from webtoon_event
where occurred_at > now() - interval '30 days';
```
