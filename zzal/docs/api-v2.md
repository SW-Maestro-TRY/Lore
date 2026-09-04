# zzal API v2 계약 — 다마고치 플레이 정본 v1.2 이식

> **이 문서가 프론트와 백엔드가 만나는 유일한 자리다.** 프론트는 이 파일만 보고 착수하고, 백엔드는 이 파일대로 만든다.
> 규칙의 정본은 `다마고치-플레이-설계.md` v1.2(레포 밖 — 팀은 노션 「9/6 진행 현황」)이고, 이 문서는 그 규칙을 **HTTP 모양**으로 옮긴 것이다. 규칙 자체를 여기서 다시 정하지 않는다.
> 정본이 두 가지로 읽히는 곳은 정본 16장(해석 규칙)으로 판정했고, 16장에도 없는 것은 이 문서가 기본값을 정한 뒤 **「해석」** 표시를 달았다. 표시된 곳은 상훈님이 뒤집을 수 있고, 뒤집히면 정본 16장에 추가하고 여기를 고친다.
> 숫자 상수는 `zzal/be/.../pet/ZzalRules.java`(정본 장 번호 주석) 한 파일에만 둔다. 이 문서의 숫자는 그 파일의 사본이다.

---

## 0. 공통 약속

| 항목 | 약속 |
|---|---|
| 봉투 | `ApiResponse` — `{success, data, error{code,message}, message}` (v1과 동일) |
| 인증 | HttpOnly 쿠키 JWT. 모든 v2 경로는 로그인 필요 |
| 경로 | `/api/zzal/v2/me/pets` 밑. 내 것은 `me`, 남의 펫은 **404**(`ZZAL_PET_NOT_FOUND`) — 403은 "그 번호의 펫이 있다"를 알려준다 |
| 행동 응답 | **모든 행동(POST)의 응답 = `PetDetail` 최신 상태.** 누른 뒤 다시 조회하지 않는다 |
| 시각 | ISO-8601 UTC(`2026-09-05T10:00:00Z`). 화면은 `serverNow`와의 차이로만 시간을 다루고, **기기 시계·시간대를 쓰지 않는다** |
| 시간대 | 서버 계산은 KST(Asia/Seoul) 고정. 창(19:00·23:00·07:00·10:00)은 KST 벽시계 |
| 이미지 키 | `images/…` 뒷부분만. 화면이 `assetUrl()`로 CDN을 앞에 붙인다 |
| 서버 정본 | 수치는 저장하지 않고 조회 때 계산(lazy settle). 낙관적 업데이트 금지 |
| 케어 미스 | **어디에도 내려가지 않는다.** 보이는 신호는 `leaving`(짐 가방)뿐 |
| 삭제 | v1 경로 전부(`/api/zzal/v1/me/pets/**` · `train` · `tutorial-done` · v1 `motions` 도감). v2에는 훈련이 없다 |
| 개발 시계 | `serverNow`는 **그 펫의 시계**다. dev 도구로 오프셋을 걸면 `serverNow`도 같이 밀린다(화면은 몰라도 된다) |

---

## 1. 엔드포인트

기준 경로 `/api/zzal/v2/me/pets`

### 1.1 펫

| 호출 | 요청 | 응답 | 거절 |
|---|---|---|---|
| `POST /` | `{name ≤12, note? ≤200, imageKey}` | `Created{petId,name,phase,hatchStartedAt,estimatedSeconds}` | `INVALID_UPLOAD_KEY` `UPLOAD_KEY_ALREADY_USED` `ZZAL_PET_ALREADY_HATCHING` `ZZAL_PET_LIMIT_REACHED` |
| `GET /` | | `PetDetail[]` | |
| `GET /{id}` | | `PetDetail` | `ZZAL_PET_NOT_FOUND` |
| `POST /{id}/release` | | `PetDetail`(phase DEAD) | `ZZAL_PET_RELEASE_NOT_ALLOWED` |

- 조회 = settle(흐른 시간 반영) + **그날 처음 열었으면 함께한 날 +1** + 떠남 예고 중이면 즉시 취소. 조회도 상태를 바꾸므로 읽기 전용 트랜잭션이 아니다.
- 이름 12자(정본 15장). v1의 20자에서 줄었다.

### 1.2 돌봄

| 호출 | 요청 | 효과(정본 4·5장) | 거절 |
|---|---|---|---|
| `POST /{id}/care` | `{action}` | 아래 표 | 공통: `ZZAL_PET_NOT_ALIVE` `ZZAL_PET_SLEEPING` `ZZAL_TRAVELING` |

| action | 효과 | 거절 |
|---|---|---|
| `FEED` | 배부름 +1, 밥 재고 -1 | 재고 0 `ZZAL_NO_FOOD` · 가득 `ZZAL_CARE_NOT_NEEDED` |
| `SNACK` | 행복 +1. 다른 행동 없이 연달아 5개면 배탈(병) | 가득 `ZZAL_CARE_NOT_NEEDED` · 아픔 `ZZAL_SICK_REFUSES` |
| `PET` | 행복 0, 친밀도 +5(하루 3회까지), 반응 동작. 3회 넘어도 성공(친밀도만 안 오름) | 없음 |
| `CLEAN` | 흔적 0 | 흔적 0 `ZZAL_CARE_NOT_NEEDED` |
| `BATH` | 흔적 0 + 행복 +1. 하루 1회 | 이미 함 `ZZAL_BATH_DONE_TODAY` |
| `MEDICINE` | 병 즉시 치료 | 안 아픔 `ZZAL_CARE_NOT_NEEDED` |

- 밥·청소·목욕·약은 친밀도 +5(하루 합산 30 상한, 정본 8장).
- **「해석」** 밥을 줘도 흔적은 늘지 않는다. 정본 4장 표에서 흔적은 시간(4시간마다 1개, 아기는 15분)으로만 생긴다. v1의 "먹으면 쓰레기 +1"은 폐기.
- **「해석」** 간식 "연속"의 리셋 = 간식 아닌 어떤 행동(밥·쓰다듬기·청소·목욕·약·채팅 답·게임 시작·재우기)이든 하나 끼면 0.
- 아픈 동안 되는 것 = 밥·청소·목욕·약·채팅·쓰다듬기. 안 되는 것 = 간식·미니게임(정본 16장).

### 1.3 잠

| 호출 | 조건(정본 2·12·16장) | 거절 |
|---|---|---|
| `POST /{id}/sleep` | **밤잠**: KST 19:00~23:00, 깨어 있을 때. **낮잠**: 아기 60분 안, 아직 낮잠 전 | `ZZAL_NOT_SLEEP_TIME` `ZZAL_PET_SLEEPING` `ZZAL_TRAVELING` |
| `POST /{id}/wake` | **밤잠**: KST 07:00~10:00. **낮잠**: 재운 지 5분 뒤 | `ZZAL_NOT_WAKE_TIME` `ZZAL_PET_NOT_SLEEPING` |

- 자동 취침 23:00, 자동 기상 10:00(늦잠, `clock.overslept=true`), 낮잠 자동 기상 10분. 자동은 보상 없음(플랜 해석 기본값).
- 사용자 재우기 = 행복 +1·친밀도 +10 / 사용자 깨우기 = 친밀도 +10.
- 자는 동안 게이지·흔적·병·케어 미스 정지. **밥 충전만 돈다.** 자는 동안 모든 행동은 `ZZAL_PET_SLEEPING`.
- **하루의 경계 = 밤잠 드는 순간.** `today{}`·조각·게임 3판·채팅 3회·친밀도 상한이 그때 판정·리셋된다. 낮잠은 경계가 아니다(카운터 유지). 자정은 아무 의미 없다.
- **「해석」** 낮잠은 아기 60분 동안 **한 번만**(`napCount==0`). 두 번째 `sleep`은 `ZZAL_NOT_SLEEP_TIME`. 낮잠도 재우기·깨우기 횟수(2층 11번 조건)에 들어간다.
- **「해석」** 아기 60분이 23:00을 넘기면 자동 취침은 60분이 끝나는 순간으로 미뤄진다(정본 16장). 60분이 끝났을 때 이미 23:00을 지났으면 그 자리에서 잠든다. 그 밤잠은 사용자 재우기가 아니므로 보상 없음.
- **「해석」** 07:00 전에 `wake`를 부르면 `ZZAL_NOT_WAKE_TIME`. 10:00을 지나면 이미 자동으로 깼으므로 `ZZAL_PET_NOT_SLEEPING`.

### 1.4 성격·배경·공유

| 호출 | 요청 | 비고 | 거절 |
|---|---|---|---|
| `POST /{id}/personality` | `{personality: GENTLE\|LIVELY\|SHY\|CLINGY\|COOL, world? ≤40}` | 언제든. 온순·활발·수줍음·응석·시크(정본 16장 기본 이름) | |
| `POST /{id}/background` | `{background}` | 프론트 배경 16종 key(`room` `window_day` …). 2층 4종 열린 뒤 | `ZZAL_FEATURE_LOCKED` |
| `POST /{id}/share` | `{motionKey, kind: DOWNLOAD\|SHARE}` | 열린 동작 어느 것이든. 서버는 **횟수만 기록**(튜토리얼 25분의 "했다"가 되는 서버 사실). 파일 합성은 v2 워터마크 때 | `ZZAL_MOTION_NOT_OPEN` |

- **「해석」** `background` 값은 서버가 검증하지 않는다(문자열 32자 이하). 화면 실물이 정본이고 서버는 저장만 한다.

### 1.5 채팅(정본 10·16장)

| 호출 | 요청 | 응답 |
|---|---|---|
| `GET /{id}/chat` | | `Chat{openSlot, calls[{slot, line, calledAt, expiresAt, answered}], memories[≤5]}` |
| `POST /{id}/chat/{slot}/answer` | `{text ≤40}` | `PetDetail` (+ `chatReply{line, reactionKey}`) |

- slot = `BABY`(아기 8분) · `MORNING`(기상+1h) · `NOON`(기상+7h) · `EVENING`(**19:00 고정**).
- 부름은 다음 부름 시각에 만료(`ZZAL_CHAT_SLOT_CLOSED`). `EVENING`은 잠들 때 만료. 만료는 패널티 0.
- 답하면 대사 1줄 + 반응 동작 1개 + 친밀도 +40. `BABY`는 하루 3회에 **안 센다**(친밀도 +40과 2층 조건 카운터에는 센다 — 튜토리얼 채팅 1회가 곧 갸웃 해금, 정본 16장).
- **「해석」** `BABY` 부름은 만료되지 않는다 — 답하거나 첫 밤잠이 들 때까지 남는다(정본 16장 "밀린 부름은 순서대로").
- **「해석」** 기상 시각이 없는 날(부화 당일)은 `MORNING`=부화+1h, `NOON`=부화+7h.
- 대사는 v0 템플릿(5그룹×슬롯). 원망 문장 금지 필터는 출력 단계에 강제.

### 1.6 동작·앨범

| 호출 | 비고 |
|---|---|
| `POST /{id}/motions/{seq}/seen` | "배워왔어요" 확인. `learnedToday`에서 빠진다 |
| `GET /{id}/album` | `Album{motions[18], postcards[], scenes[≤3], firstGift}` — 잠긴 칸도 이름+조건 |

- 앨범 기능은 첫 심화 행동이 열릴 때 같이 열린다(`features.album`). 그 전에 부르면 `ZZAL_FEATURE_LOCKED`.

### 1.7 미니게임(정본 7장)

| 호출 | 요청 | 비고 | 거절 |
|---|---|---|---|
| `POST /{id}/games` | `{kind: LEFT_RIGHT\|RUN}` | 시작. 진행 중인 판이 있으면 그것을 돌려준다 | `ZZAL_GAME_DAILY_LIMIT` `ZZAL_SICK_REFUSES` `ZZAL_FEATURE_LOCKED`(RUN 잠김) `ZZAL_PET_SLEEPING` |
| `POST /{id}/games/{gameId}/guess` | `{pick: LEFT\|RIGHT}` | 좌우 맞히기 한 판. 5판 3승. 답은 서버가 쥔다 | `ZZAL_GAME_NOT_FOUND` `ZZAL_GAME_FINISHED` |
| `POST /{id}/games/{gameId}/finish` | `{survivedMs}` | 달리기 끝. 30,000ms 이상이면 승리. 서버는 상한(60,000)만 검증 | `ZZAL_GAME_NOT_FOUND` `ZZAL_GAME_FINISHED` |
| `GET /{id}/games/current` | | 치던 판 잇기 | |

- 두 게임 합쳐 **하루 3판**, 시작한 판 기준, 잠들 때 리셋. `RUN`은 좌우 맞히기 **5승** 뒤.
- 승리 = 행복 +1(설정 `app.zzal.reward.game-win: HAPPINESS`).
- 응답 `GameState{playing, gameId, kind, round, hits, finished, win, remainingToday}` — v1 모양 유지 + `kind`.

### 1.8 떠남·설정(v2 판, 9/14)

| 호출 | 요청 | 비고 | 거절 |
|---|---|---|---|
| `POST /{id}/call-back` | | 여행 중 "부르기" → 즉시 귀환·엽서 전달·친밀도 최고치 50%·케어 미스 0·게이지 전부 2 | `ZZAL_NOT_TRAVELING` |
| `POST /{id}/settings` | `{leaveEnabled}` | 떠남 끄기 | |

---

## 2. `PetDetail` v2 전체 스키마

부화 중이든 함께 지내는 중이든 이 하나로 답한다. `phase != ALIVE`이면 **"ALIVE 전용"** 블록은 전부 `null`.

```jsonc
{
  "petId": 7,
  "name": "여울",
  "note": "왼쪽 눈에 흉터",
  "phase": "ALIVE",                 // HATCHING · ALIVE · FAILED · DEAD
  "ready": true,                    // 부화가 끝났는가
  "step": null,                     // 부화 중일 때만: "움직임 배우는 중"
  "elapsedSeconds": null,           // 부화 중일 때만
  "deathReason": null,              // FAILED·DEAD 일 때만: HATCH_FAILED · RELEASED · NEGLECTED
  "hatchStartedAt": "2026-09-05T09:00:00Z",
  "hatchedAt": "2026-09-05T09:02:23Z",

  "serverNow": "2026-09-05T09:30:00Z",   // ★ 필수. 이 펫의 시계(dev 오프셋 포함)

  // ── 이하 ALIVE 전용 ─────────────────────────────────────────
  "clock": {
    "babyUntil": "2026-09-05T10:02:23Z", // 부화+60분. 지나면 어린이
    "sleeping": false,
    "sleepKind": null,                   // NAP · NIGHT · null
    "sleptAt": null,                     // 잠든 시각
    "wokeAt": "2026-09-05T09:02:23Z",    // 오늘 기상 시각(부화 당일은 부화 시각)
    "canSleep": false,                   // 지금 재우기 버튼이 눌리는가
    "canWake": false,                    // 지금 깨우기 버튼이 눌리는가
    "sleepWindowOpensAt": "2026-09-05T10:00:00Z", // 다음 재우기 창 시작(KST 19:00). 낮잠 가능이면 serverNow
    "autoSleepAt": "2026-09-05T14:00:00Z",        // 자동 취침(KST 23:00). 아기 60분 유예 반영
    "wakeWindowOpensAt": null,           // 자는 중일 때: 깨우기 창 시작(밤 07:00 / 낮잠 +5분)
    "autoWakeAt": null,                  // 자는 중일 때: 자동 기상(밤 10:00 / 낮잠 +10분)
    "overslept": false                   // 오늘 10:00 자동 기상이었는가. 다음 잠까지 유지
  },

  "daysTogether": 1,                     // "N일째 함께". 앱을 연 날 수

  "gauges": { "fullness": 2, "happiness": 3, "clean": 4, "trash": 0 },  // 전부 0..4. clean = 4 - trash
  "food": { "count": 2, "nextInSeconds": 7200 },   // 재고 0..3. 가득이면 nextInSeconds null

  "mood": "NORMAL",                      // SICK > HUNGRY(배부름 0) > SAD(행복 0) > DIRTY(흔적 3+) > NORMAL
  "sick": null,                          // { "since": "...", "kind": "NATURAL|NEGLECT|DIRTY|SNACK" }

  "intimacy": { "score": 120, "percent": 10, "tier": "LOW" },  // score 0..999, percent 10단위, tier LOW(0~30)·MID(40~70)·HIGH(80~100)

  "today": {                             // 잠들 때 리셋되는 것들
    "games": 1,                          // 시작한 판(두 게임 합산). 상한 3
    "pets": 2,                           // 쓰다듬기 인정 횟수. 상한 3
    "careIntimacy": 15,                  // 밥·청소·목욕·약 친밀도 합. 상한 30
    "snackStreak": 0,                    // 연속 간식. 5면 배탈
    "bathDone": false
  },

  "pieces": null,                        // 3층 전: null. 3층: { "food": true, "play": false, "clean": true, "bond": false, "streak": 1 }

  "motions": [                           // 18개 고정, seq 오름차순
    {
      "seq": 1, "key": "base", "label": "기본 자세", "layer": "BASIC_1",
      "unlocked": true,
      "basicImageKey": "images/zzal/pets/7/basic/base.webp",   // 잠김·선물이면 null
      "hint": null,                      // 잠긴 칸의 조건 문구: "채팅 응답 1회"
      "progress": null,                  // 잠긴 2층 칸: { "current": 0, "target": 1 }
      "advanced": { "status": "NONE", "imageKey": null, "revealedAt": null, "seen": false }
    },
    { "seq": 9, "key": "tilt", "label": "갸웃", "layer": "BASIC_2", "unlocked": false,
      "basicImageKey": null, "hint": "채팅 응답 1회", "progress": { "current": 0, "target": 1 },
      "advanced": { "status": "NONE", "imageKey": null, "revealedAt": null, "seen": false } },
    { "seq": 101, "key": "roll", "label": "구르기", "layer": "GIFT", "unlocked": false,
      "basicImageKey": null, "hint": "3일이나 함께해서…", "progress": null,
      "advanced": { "status": "NONE", "imageKey": null, "revealedAt": null, "seen": false } }
  ],
  "justUnlocked": [9],                   // ★ 행동 응답에만. 이번 행동으로 열린 2층 seq(폭죽)
  "learnedToday": [                      // 밤에 합격해 아침에 도착한 심화 행동. seen 전까지
    { "seq": 101, "key": "roll", "label": "구르기", "imageKey": "images/zzal/pets/7/motions/101/motion.webp", "revealedAt": "..." }
  ],
  "firstGift": { "status": "LOCKED", "daysLeft": 2 },   // LOCKED · WAITING(3일째, 오늘 케어 미스 0이면 밤에 굽기) · BAKING · OPEN

  "chatSummary": { "openSlot": null, "nextAt": "2026-09-05T10:02:23Z" },  // 열린 부름 slot / 다음 부름 시각

  "scenes": { "enabled": false, "latest": null },   // latest = { "motionKey", "background", "prop", "at", "line" }

  "personality": null,                   // GENTLE · LIVELY · SHY · CLINGY · COOL · null(아직 안 고름)
  "world": null,                         // 세계관 한 줄 ≤40
  "background": "room",

  "features": {                          // 기능 해금(정본 6장)
    "download": true,                    // 처음부터
    "leftRight": true,                   // 처음부터
    "run": false,                        // 좌우 5승
    "scenes": false,                     // 첫 부재 4시간(깨어 있는 시간) 뒤 자동
    "background": false,                 // 2층 4종
    "album": false,                      // 첫 심화 행동
    "pieces": false                      // 3층(2층 8종 다음 날 아침)
  },

  "leaving": null,                       // 짐 싸기 예고: { "noticedAt": "...", "departsAt": "..." }
  "trip": null,                          // 여행 중: { "startedAt": "...", "postcards": 2 }
  "settings": { "leaveEnabled": true },

  "tutorial": {                          // 아기 시간표(정본 12장). 전부 서버 카운터에서 파생. 브라우저 저장 없음
    "active": true,                      // babyUntil 전인가
    "minutesSince": 27,                  // 부화 뒤 몇 분
    "steps": [
      { "key": "FEED",        "dueAt": "T+0m",  "done": true,  "current": false },
      { "key": "PET",         "dueAt": "T+3m",  "done": true,  "current": false },
      { "key": "CHAT",        "dueAt": "T+8m",  "done": true,  "current": false },
      { "key": "PERSONALITY", "dueAt": "T+12m", "done": false, "current": true  },
      { "key": "CLEAN",       "dueAt": "T+15m", "done": false, "current": false },
      { "key": "GAME",        "dueAt": "T+20m", "done": false, "current": false },
      { "key": "SHARE",       "dueAt": "T+25m", "done": false, "current": false },
      { "key": "NAP",         "dueAt": "T+40m", "done": false, "current": false },
      { "key": "DONE",        "dueAt": "T+60m", "done": false, "current": false }
    ]
  }
}
```

세부 규칙

- `dueAt`은 실제 ISO 시각으로 내려간다(위 `T+0m`은 설명용). `current` = 도래했고(`dueAt ≤ serverNow`) 아직 안 한 것 중 **가장 앞의 하나**. 나갔다 와도 밀린 부름이 순서대로 나온다. 부름은 버튼을 잠그지 않는다(정본 0장 7).
- `done` 판정(전부 누적 카운터): FEED=밥 1회 이상 / PET=쓰다듬기 1회 / CHAT=채팅 응답 1회 / PERSONALITY=성격 선택됨 / CLEAN=청소 1회 / GAME=게임 시작 1회 / SHARE=공유·다운로드 1회 / NAP=낮잠 1회 / DONE=60분 경과.
- **「해석」** `tutorial`은 9단계가 모두 `done`이 되면 `null`. 60분이 지나도 남은 단계가 있으면 `active=false`인 채로 남아 순서대로 나온다.
- `motions[].unlocked`는 **기본 행동(2프레임)이 열렸는가**. 1층 8종은 부화 즉시 true. `advanced.status`는 그 동작의 심화 행동(16프레임) 진행: `NONE`(안 굽음) · `QUEUED`(밤 큐) · `BAKING` · `REVIEW`(검수 대기) · `LOCAL_REQUESTED`(맥미니 재생성 요청) · `OPEN`(공개) · `FAILED`(그 밤 실패, 다음 밤 재시도).
- `basicImageKey` 규약 = `images/zzal/pets/{petId}/basic/{key}.webp`. v1 파이프라인으로 부화한 펫은 `legacyFile` 매핑으로 채운다(`base←idle` `eat←eat` `joy←happy` `sad←sad` `shy←pet` `practice←train`, 나머지 `sick` `call`은 null → 화면 폴백).
- 심화 행동 imageKey 규약 = `images/zzal/pets/{petId}/motions/{seq}/motion.webp`.
- `mood`는 정본 4장 우선순위 그대로. 화면의 대기 동작 선택은 `mood` 하나로 한다.
- `intimacy.percent` = `floor(score / 999 * 10) * 10`(0·10·…·100). **「해석」** tier 경계는 percent 기준 LOW ≤30 · MID 40~70 · HIGH ≥80.
- `firstGift.daysLeft` = `max(0, 3 - daysTogether)`.

### 부화 초기값 「해석」

정본은 부화 순간의 게이지를 적지 않는다. 12장 0분 "배가 고픈가 봐요"가 성립하도록 **배부름 1·행복 3·흔적 0·밥 3**으로 시작한다(v1과 같음). 시계는 부화 순간 켜진다(정본 15장 6).

---

## 3. 동작 카탈로그 (고정 18, `MotionCatalog`)

| seq | key | label | layer | 해금 조건(정본 6장) | 16f 지시문 파일 |
|---|---|---|---|---|---|
| 1 | `base` | 기본 자세 | BASIC_1 | 처음부터 | `기본자세` |
| 2 | `eat` | 먹기 | BASIC_1 | 처음부터 | `먹기` |
| 3 | `joy` | 기쁜 자세 | BASIC_1 | 처음부터 | `기쁜자세` |
| 4 | `sad` | 슬픈 자세 | BASIC_1 | 처음부터 | `슬픈자세` |
| 5 | `sick` | 아픈 자세 | BASIC_1 | 처음부터 | `아픈자세` |
| 6 | `practice` | 훈련 자세 | BASIC_1 | 처음부터(밤 연습 장면 전용) | `훈련자세` |
| 7 | `shy` | 교감 자세 | BASIC_1 | 처음부터 | `교감자세` |
| 8 | `call` | 부르기 | BASIC_1 | 처음부터 | `부르기` |
| 9 | `tilt` | 갸웃 | BASIC_2 | 채팅 응답 1회 | `갸웃` |
| 10 | `wave` | 손 흔들며 인사 | BASIC_2 | 채팅 응답 4회 | `손흔들며인사` |
| 11 | `sleep` | 자기 | BASIC_2 | 재우기·깨우기 합쳐 3회 | `자기` |
| 12 | `wash` | 씻기 | BASIC_2 | 목욕 3회 | `씻기` |
| 13 | `startle` | 놀라기 | BASIC_2 | 미니게임 3판(승패·종류 무관, 시작 기준) | `놀라기` |
| 14 | `nod` | 끄덕이기 | BASIC_2 | 채팅 응답 12회 | `끄덕이기` |
| 15 | `smile_idle` | 웃는 대기 | BASIC_2 | 케어 미스 0인 날 3번 | `웃는대기` |
| 16 | `sit` | 앉아 쉬기 | BASIC_2 | 2층 6종 열림(자기 제외) | `앉아쉬기` |
| 101 | `roll` | 구르기 | GIFT | 첫 심화 행동 — 함께한 날 3 + 그날 케어 미스 0 | `구르기` |
| 102 | `fall_back` | 뒤로 넘어짐 | GIFT | 3층 8번째 뒤 두 번째 선물 | `뒤로넘어짐` |

- 2층 조건 카운터는 **부화 순간부터 누적**(아기 60분 포함). 충족 즉시 열리고 `justUnlocked`에 실린다.
- 3층 심화 순서 = seq 1→16(정본 16장). 선물 둘은 순서 밖.
- **「해석」** 16프레임 지시문 파일 = `zzal/prompt/{motion-version}/motions/{파일명}.txt`. 파일명은 위 표(한글, 띄어쓰기 없음)로 고정 — 생성 세션이 이 이름으로 만든다. 설정 `app.zzal.advanced-motions`·`gift-motions`에 **key**가 적힌 것만 밤 큐에 오르고, 적힌 key의 파일이 없으면 부팅이 막힌다.
- **「해석」** `progress`는 2층 잠긴 칸에만. 조건 종류별 `current` = 채팅 응답 수 / 재우기+깨우기 수 / 목욕 수 / 게임 시작 수 / 케어 미스 0인 날 수 / 열린 2층 수.

기능 해금(정본 6장) — `features` 블록의 근거

| 기능 | 조건 |
|---|---|
| download · leftRight | 처음부터 |
| run | 좌우 맞히기 5승 |
| scenes | 첫 부재 4시간(깨어 있는 시간, 마지막 조회 기준) 뒤 자동 |
| background | 2층 4종 열림 |
| album | 첫 심화 행동 열림 |
| pieces | 2층 8종 다 열린 다음 첫 기상 |

---

## 4. 에러 코드 (`ErrorCode`)

| 코드 | HTTP | 언제 |
|---|---|---|
| `ZZAL_PET_NOT_FOUND` | 404 | 없는 펫·남의 펫 |
| `ZZAL_PET_ALREADY_HATCHING` | 409 | 부화 중인데 또 만듦 |
| `ZZAL_PET_LIMIT_REACHED` | 409 | 자리 없음 |
| `ZZAL_PET_NOT_ALIVE` | 409 | ALIVE가 아닌 펫에 행동 |
| `ZZAL_PET_SLEEPING` | 409 | 자는 중 행동 |
| `ZZAL_PET_NOT_SLEEPING` | 409 | 안 자는데 깨움 |
| `ZZAL_CARE_NOT_NEEDED` | 409 | 가득·깨끗·안 아픔 |
| `ZZAL_NO_FOOD` | 409 | 밥 재고 0 |
| `ZZAL_PET_RELEASE_NOT_ALLOWED` | 409 | 부화 중 보내기 |
| **`ZZAL_NOT_SLEEP_TIME`** | 409 | 재우기 창 밖(19~23시·낮잠 조건 아님) |
| **`ZZAL_NOT_WAKE_TIME`** | 409 | 깨우기 창 밖(07시 전·낮잠 5분 전) |
| **`ZZAL_SICK_REFUSES`** | 409 | 아픈데 간식·게임 |
| **`ZZAL_BATH_DONE_TODAY`** | 409 | 오늘 목욕 이미 함 |
| **`ZZAL_CHAT_SLOT_CLOSED`** | 409 | 만료·안 열린 부름에 답 |
| **`ZZAL_FEATURE_LOCKED`** | 409 | 배경·달리기·앨범 등 아직 안 열림 |
| **`ZZAL_TRAVELING`** | 409 | 여행 중 행동 |
| **`ZZAL_NOT_TRAVELING`** | 409 | 여행 중 아닌데 부르기 |
| **`ZZAL_MOTION_NOT_OPEN`** | 409 | 안 열린 동작 공유·seen |
| **`ZZAL_REGEN_NOT_REQUESTED`** | 409 | 재생성 요청 없는 모션에 업로드(맥미니) |
| `ZZAL_GAME_NOT_FOUND` · `ZZAL_GAME_FINISHED` · `ZZAL_GAME_DAILY_LIMIT` | 404·409·409 | v1과 같음 |
| `ZZAL_FEEDBACK_ALREADY_SUBMITTED` | 409 | v1과 같음 |
| `ADMIN_ONLY` | 403 | 관리자 아님 |

삭제(v1 전용, PR-3에서 v1 컨트롤러와 함께 제거): `ZZAL_TRAIN_IN_PROGRESS` `ZZAL_TRAIN_ENOUGH` `ZZAL_TRAIN_NOT_ENOUGH` `ZZAL_ALL_UNLOCKED` `ZZAL_MOTION_NOT_READY` `ZZAL_PET_STILL_SLEEPING`. 프론트 `common/fe/api/client.ts`의 코드 유니온도 같이 갱신한다.

---

## 5. 관리자 `/api/zzal/v2/admin/motions` (`ZZAL_ADMIN=true`일 때만 존재, 관리자 계정만)

| 호출 | 요청 | 응답·비고 |
|---|---|---|
| `GET /pending` | | `REVIEW` 상태 목록(오래된 순). 펫 이름·주인 없음. `{motionId, key, label, imageKey, gateVerdict, gateNote, gateVersion, attempts, regenRound, nightOf, createdAt}` |
| `POST /{id}/verdict` | `{verdict: OK\|REGENERATE, note? ≤500}` | OK → `OPEN`(아침 공개). REGENERATE → `regenRound<2`면 `LOCAL_REQUESTED`, 아니면 `FAILED`(다음 밤 재등록) |
| `GET /regen-requests` | | 맥미니 폴링. `LOCAL_REQUESTED` 목록 `{motionId, petId, key, promptFile, sheetImageKey, identityText, regenRound}` |
| `POST /{id}/upload` | `{imageKey}` | 맥미니가 presign으로 올린 결과 등록 → `REVIEW`. `LOCAL_REQUESTED`가 아니면 `ZZAL_REGEN_NOT_REQUESTED` |
| `GET /night/summary?date=YYYY-MM-DD` | | 그 밤 현황 `{nightOf, queued, baked, review, open, failed, localRequested, costUsd}` |

---

## 6. 개발 도구 `/api/zzal/v2/dev/pets/{id}` (`ZZAL_DEV_TOOLS=true`일 때만 존재, 내 펫만)

**의미 변경 — 앵커를 미는 것이 아니라 그 펫의 시계에 오프셋을 건다.** "지금이 23:00"이 성립해야 창·자동 취침을 실제 규칙으로 검증할 수 있다. 오프셋은 `zzal_pet.dev_clock_offset_seconds`에 남고 모든 계산과 `serverNow`가 그 시계를 쓴다.

| 호출 | 요청 | 비고 |
|---|---|---|
| `POST /advance-clock` | `{seconds?, minutes?}` | 오프셋에 더한다(≤30일). 규칙은 한 글자도 안 바뀐다 |
| `POST /set-clock` | `{at?: ISO} \| {sinceHatchMinutes?: int} \| {localTime?: "HH:mm"}` | 펫 시계를 그 시각으로. 셋 중 하나. `localTime`은 오늘(KST) 그 시각 |
| `POST /night-sweep` | | 이 펫에 대해 23:00 스위프를 지금 실행(큐 등록·굽기) |
| `POST /force-open/{seq}` | | 그 동작의 심화 행동을 가짜 imageKey로 즉시 `OPEN`(아침 도착 화면 확인용) |

응답은 전부 `PetDetail`.

---

## 7. 설정 키 (`application.yml` `app.zzal.*` — PR-1에서 자리 선점, 이후 동결)

| 키 | 기본 | 뜻 |
|---|---|---|
| `pipeline-version` | v1 | 부화 파이프라인(v2 = 격자 2장·16종) |
| `motion-pipeline-version` | v1 | 심화 행동 파이프라인 |
| `hatch.states.v1` / `.v2` | 8종 / 16 key | 후처리가 만들어야 하는 파일 이름(버전별) |
| `advanced-motions` | (빈) | 3층 큐에 오를 수 있는 key 목록(지시문 있는 것만) |
| `gift-motions` | (빈) | 선물 key 목록(`roll,fall_back`) |
| `night.sweep-enabled` | false | 23:00 스위프 켜기(서버 여러 대면 한 대만) |
| `night.max-bakes` | 200 | 밤 굽기 상한 K |
| `night.local-regen-max` | 2 | 로컬 재생성 최대 횟수 |
| `chat.mode` | template | template · llm |
| `game.daily-limit` | 3 | 두 게임 합산 하루 판수 |
| `reward.game-win` | HAPPINESS | 승리 보상 |
| `reward.feedback` | NONE | 후기 보상 |
| `dev-tools` · `admin.enabled` | false | 기존 |
| `generation.*` · `openai.*` · `python.*` · `recovery.*` · `max-hatch-attempts` · `gate-version` | 기존 | |

---

## 8. 「해석」 목록 (한눈에)

| # | 어디 | 기본값 |
|---|---|---|
| 1 | 1.2 | 밥을 줘도 흔적은 늘지 않는다 |
| 2 | 1.2 | 간식 연속의 리셋 = 간식 아닌 어떤 행동이든 하나 |
| 3 | 1.3 | 낮잠은 아기 60분 안 한 번만 |
| 4 | 1.3 | 아기 60분 유예 뒤 자동 취침은 보상 없음 |
| 5 | 1.3 | 07:00 전 깨우기 = `NOT_WAKE_TIME`, 10:00 뒤 = `NOT_SLEEPING` |
| 6 | 1.4 | 배경 값은 서버가 검증하지 않음 |
| 7 | 1.5 | BABY 부름은 답하거나 첫 밤잠까지 유지 |
| 8 | 1.5 | 부화 당일 MORNING·NOON = 부화+1h·+7h |
| 9 | 2 | 튜토리얼 블록은 9단계 모두 done이면 null |
| 10 | 2 | 친밀도 tier 경계 LOW ≤30 · MID 40~70 · HIGH ≥80 |
| 11 | 2 | 부화 초기값 배부름 1·행복 3·흔적 0·밥 3 |
| 12 | 3 | 16f 지시문 파일명 = 한글 label(띄어쓰기 없음) |
| 13 | 3 | `progress`는 2층 잠긴 칸에만 |
| 14 | 6 | dev 시계는 오프셋 방식, `serverNow`도 밀린다 |
| 15 | 플랜 | 튜토리얼 낮잠은 하루 경계 아님 / 자동 취침·기상 보상 없음 / 케어 미스 0인 날에 아기 첫날 포함 / 부재 4h 청크는 마지막 조회 기준 / 아기 8분 채팅은 3회에 미포함 |

---

## 9. 구현 진행 (어디까지 붙었나 — 프론트가 실서버에 연결할 때 확인)

| 절 | 상태 | 어느 PR |
|---|---|---|
| 1.1 펫 · 1.2 돌봄 6종 · 1.3 잠 · 1.4 성격/배경/공유 · `PetDetail` v2 전체 모양 | **v2 경로 동작** | PR-3 (#192) |
| 2 `motions[].advanced` | 전부 `NONE`(부화 18행·밤 굽기 전) | PR-5·7 |
| 2 `justUnlocked` | 행동 응답에 실림(카운터 비교) | PR-3 |
| 2 `sick`·`pieces`·`leaving`·`trip`·`learnedToday`·`scenes.latest` | 항상 null·빈 목록 | PR-8·10·11·7·9 |
| 2 `chatSummary.openSlot` · 1.5 채팅 | null / 미구현 | PR-4 |
| 1.7 미니게임 | **아직 v1 경로** `/api/zzal/v1/me/pets/{id}/games`(kind 없음, 달력일 5판) | PR-4 |
| 1.6 앨범·seen | 미구현 | PR-5·7 |
| 5 관리자 | **아직 v1 경로** `/api/zzal/v1/admin/motions` | PR-7 |
| 6 개발 도구 | v2 경로 동작(`advance-clock`·`set-clock`). `night-sweep`·`force-open`은 PR-6·7 | PR-2 |
| 후기 | v1 경로 `/api/zzal/v1/me/pets/{id}/feedback` 유지 | (변경 없음) |

## 변경 기록

- **2026-09-05** — 최초(PR-1, #192). 정본 v1.2 + 플랜 API v2 계약을 전문으로.
- **2026-09-05** — PR-3: 9절 구현 진행 표 추가. `settings.leaveEnabled` 는 v2 판까지 항상 true.
