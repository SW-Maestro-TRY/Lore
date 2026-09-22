// 여울 시안의 두뇌 — 상태 한 벌과, 화면이 그대로 그리기만 하면 되는 표(view) 한 벌.
//
// 왜 한 곳에 모았나 — 시안(`여울 반응형.dc.html`)이 그런 모양이다. 스크립트가 값을 다 계산해
// 놓고 템플릿은 그리기만 한다. 같은 구조를 지키면 시안이 바뀔 때 **어디를 고칠지가 1:1 로 보인다.**
// 부품(Room·Panels·Onboarding…)이 제 안에서 색·조건을 다시 계산하기 시작하면 그 대응이 끊긴다.
//
// 지금은 **프론트 전용**이다. 서버를 부르지 않는다 — 확인할 것이 배치·흐름·문구이기 때문이다.
// 서버가 붙을 때 갈아탈 자리는 `actions` 하나뿐이도록 상태와 행동을 갈라 두었다.
'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ALBUM, CHAR_GROUPS, CHAT_HINTS, CHAT_QUICK, CHAT_REPLY, FRAME_KEYS, LANDING_COPY, LEARN_GOALS, LINE,
  NAME_POOL, PERSONA_LABEL, PERSONALITY_OF, POSTCARDS, ROOM_KEYS, ROOM_NAME, SAY, SHEET_TITLE,
  STEPS, TUTOR, TUTOR_ROOM, SHARDS, USER_Q, WALLS, GRAD_COPY, GRAD_PREVIEW_SRC,
  WISH_COPY, WISH_MAX, UNLOCK_COPY, WISH_REPLY,
  type NeedStyle, type RoomKey, type ScreenKey, type StepKey, type TutorStep,
} from './constants';
import { josa } from '../constants';
import { ACCENT, C, C2, LV, sel, type LvKey, type Sel, ink, paperA } from './ui';
import type { Live } from './useHatch';
import { CHAR_TEXT_MAX, type CareAction, type ChatState, type Personality } from '../../lib/pet';
import { ApiError } from '../../lib/api';
import { takeGrownLine } from '../tutorial';
import type { GuessResult, Side } from '../../lib/game';
import {
  ACTION_SITUATION, CYCLE_MS, GIFT_CYCLES, LOCKED_POSE, SITUATION_TABLE,
  cyclesOfAction, poseOfSituation, situationOfAction, stagePlanOf, type ActionKey,
} from '../props/situations';
import { motionAliases, YEOUL_MOTION } from '../constants';
import { POSE_FLOORS, POSE_LABEL } from '../props/anchors-fixed';
import { assetUrl } from '../../lib/assets';
import { trackConversion } from '../../lib/analytics';

/**
 * 아이 이름 + 조사. **이름은 사용자가 짓는다** — 받침이 있는지 없는지 우리가 알 수 없으므로
 * 조사를 문장에 박아 두면 '노을가' 같은 말이 나온다(랜덤 후보에 노을·도담이 있어 버튼만 눌러도 재현된다).
 * 조사 판정은 공용 `josa()` 가 이미 한다 — 여기서는 이름이 빈 경우까지 함께 막는다.
 */
const sleepingLine = (name: string) => `${petWith(name, '이', '가')} 자고 있어요`;

/**
 * 동작 요청 실패 코드 → 화면 문구.
 *
 * ★ 표를 한 곳에 두는 이유는 `lib/hatchBlocked.ts` 와 같다 — 사유가 하나 늘 때 부르는 쪽마다
 *   흩어 두면 빠뜨린 자리만 조용히 옛 문구를 낸다. 모르는 코드는 공통 문구로 떨어진다.
 * ★ 어느 줄도 사용자를 탓하지 않는다. 하루 상한도 "많이 보냈다" 가 아니라 아이 쪽 사정으로 말한다.
 */
const wishFailLine = (code: string | null): string => {
  switch (code) {
    case 'INVALID_INPUT': return WISH_COPY.fail.tooLong;
    case 'unauthorized': return WISH_COPY.fail.login;
    case 'ZZAL_PET_NOT_FOUND':
    case 'no_pet': return WISH_COPY.fail.noPet;
    case 'ZZAL_MOTION_WISH_DAILY_LIMIT': return WISH_COPY.fail.limit;
    default: return WISH_COPY.fail.other;
  }
};

/**
 * **기권**(게임판 ✕ → 「나가기」) 실패 코드 → 화면 문구.
 *
 * ★ 넷 다 **사용자를 탓하지 않는다.** 특히 `ZZAL_GAME_FINISHED` 는 사용자 잘못이 아니다 —
 *   내가 나가려던 사이 판이 먼저 끝났을 뿐이라 "이미 정리된 판" 이라고만 말한다.
 * ★ 넷 다 **화면에서는 판을 닫는다.** 기권이 안 됐다고 게임판에 가둬 두면 나갈 길이 없다.
 * ★★ **아플 때는 거절이 없다** — 이 호출에는 `ZZAL_SICK_REFUSES` 가 없다(서버가 일부러 뺐다).
 *   그러니 화면도 **아플 때 ✕ 를 잠그면 안 된다.**
 */
/**
 * **성격·세계관을 저장하지 못했을 때** 무엇이 막혔고 어떻게 하면 되는지.
 *
 * ★★ 왜 있나 — 2026-09-22 실측: 세계관을 길게 적고 저장하면 서버가 400 으로 거절하는데
 *   **화면이 한 마디도 안 했다.** 토스트는 방 바닥에 뜨는데 그때 아이 정보 시트가 그 위를
 *   덮고 있어 보이지도 않았다. 사용자는 저장된 줄 알았다. 그래서 **시트 안, 저장 단추 옆**에
 *   이 줄을 낸다(→ `settings.save.err`).
 * ★ **사용자를 탓하지 않는다**(자캐 규범). 길이를 미리 못 막은 것은 화면 잘못이지 사용자
 *   잘못이 아니다. "어떻게 하면 되는지" 까지 적는다 — "저장하지 못했어요" 만으로는 부족하다.
 * ★ **적은 글은 그대로 둔다.** 이 줄만 뜨고 시트는 열린 채 남는다(저장 성공 때만 닫힌다).
 * ★ 문장이 아니라 **코드로 가른다** — 서버 문장이 바뀌어도 안 깨진다(기권 실패와 같은 방식).
 */
const saveFailLine = (code: string | null, status: number | undefined, message: string | null): string => {
  if (status === 401 || code === 'UNAUTHORIZED') return '로그인이 풀렸어요. 다시 로그인하면 적어 두신 글 그대로 저장할 수 있어요.';
  switch (code) {
    // 지금 이 자리에서 400 이 나는 유일한 길은 **세계관이 서버 한도보다 긴 것**이다.
    case 'INVALID_INPUT': return '세계관이 서버가 받는 길이를 넘었어요. 화면이 미리 막았어야 했는데 못 막았어요 — 조금 줄이면 그대로 저장돼요.';
    case 'FORBIDDEN': return '이 아이를 고칠 수 있는 계정이 아니에요. 로그인한 계정을 확인해 주세요.';
    case 'ZZAL_PET_NOT_FOUND': return '아이를 찾지 못했어요. 새로 고치면 다시 이어져요 — 적어 두신 글은 그대로 있어요.';
    case 'ZZAL_PET_NOT_ALIVE': return '지금은 이 아이의 정보를 바꿀 수 없어요. 잠시 뒤에 다시 눌러 주세요.';
    // 모르는 사유 — 서버가 준 문장을 **그대로 보여 주고** 무엇을 하면 되는지만 덧붙인다.
    default: return `${message || '지금은 저장하지 못했어요'} · 잠시 뒤에 다시 눌러 주세요. 적어 두신 글은 그대로 있어요.`;
  }
};

const quitFailLine = (code: string | null): string => {
  switch (code) {
    case 'ZZAL_GAME_NOT_FOUND':
    case 'ZZAL_GAME_FINISHED': return '이미 정리된 판이에요. 방으로 돌아갈게요.';
    case 'ZZAL_PET_SLEEPING': return '자고 있어요. 깨우면 정리할게요.';
    case 'ZZAL_TRAVELING': return '여행 중이에요. 돌아오면 정리할게요.';
    default: return '지금은 정리하지 못했어요. 방으로 돌아갈게요.';
  }
};

// ★ 옛 `ACT_MS = 2500`(행동 한 번의 가장 짧은 길이)은 **없앴다**(2026-09-13).
//
//   무엇이었나 — 눈으로 보고 정한 **연출 길이의 바닥값**이었다. 서버 왕복을 기다리는 값도,
//   버튼을 잠가 두는 값도 아니다. 실제로 이 파일에서 `act()` 안 한 줄에서만 쓰였고,
//   연타 잠금은 따로 있다(`live.careing`·`live.resting` → `pbtn` 의 `waiting`).
//   낙관적 UI 도 여기 안 걸린다 — 게이지는 `og`(useHatch.doCare)가 응답 전에 얹고,
//   연출(`careAct`)은 **응답이 온 뒤에** 시작한다.
//   그래서 줄여도 잠금·왕복에 아무 영향이 없다.
//
//   왜 없앴나 — 이 바닥값이 A절 표의 "한 바퀴 1.8초" 칸을 전부 2.5초로 끌어올리고 있었다.
//   이제 길이는 **바퀴 수 x `CYCLE_MS`** 하나로만 정해진다(A절 표 = `cyclesOfAction`).

/** 바퀴 타이머를 걷어낼 때 훑을 최대 바퀴 수. 표에서 가장 긴 단계 차례(4)보다 넉넉히. */
const ACT_MAX_CYCLES = 6;

// ── 좌우 맞히기의 박자 ──────────────────────────────────────────────────
//
// ★ **프레임 규칙(`situations.ts`)에서 일부러 떼어 냈다**(2026-09-20). 재설계안은 여운을
//   `CYCLE_MS`(1800ms)를 재사용해 적었는데, 그 상수는 **돌보기 연출 길이**를 정하는 값이라
//   프레임 규칙이 바뀌면 게임 박자까지 같이 흔들린다. 게임이 원하는 것은 "한 바퀴"가 아니라
//   "결과를 읽을 시간" 이므로 **여기 숫자로 따로 적는다.** 값이 같은 것은 우연이다.
// ★ 다만 **2층의 공개 지연만은 `CYCLE_MS` 를 그대로 쓴다** — 그건 `놀람` 움짤이 실제로 도는
//   길이라, 프레임 규칙이 바뀌면 **같이 바뀌는 것이 맞다**(→ `onGuess` 의 `revealAfter`).

/** 섞기 — 고른 뒤 공개까지. 양손은 주먹 그대로이고 입력만 잠긴다. 서버 왕복이 더 길면 그쪽이 이긴다. */
const GUESS_SHUFFLE_MS = 250;
/** 여운 — 결과를 읽을 시간. 끝나면 **누르지 않아도** 다음 판이 시작된다. */
const GUESS_AFTERGLOW_MS = 1800;
/** 매치 끝 — 결과 문장과 행복 한 칸을 같이 읽어야 해서 여운의 두 배다. */
const GUESS_MATCH_END_MS = 3600;
/** 정본 §7 — 한 매치 5판, 3판 먼저 맞히면 이김. **서버가 들고 있는 값과 같다**(`rounds`·`winAt`). */
const GUESS_ROUNDS = 5;
const GUESS_WIN_AT = 3;

const petWith = (name: string, withFinal: string, withoutFinal: string) => {
  const n = name || '아이';
  return `${n}${josa(n, withFinal, withoutFinal)}`;
};

// ── 상태 ────────────────────────────────────────────────────────────────

export type Mode = 'day' | 'night' | 'sleep' | 'sick';
/**
 * 시트로 남는 넷. **주방·욕실·침실은 시트가 없다**(상훈님 2026-09-08 판정 12) —
 * 그 셋은 팝오버로 할 일이 다 되고, 알림에서만 열리는 큰 창을 남길 이유가 없었다.
 */
// ★ 'album' 은 2026-09-21 에 빠졌다 — 앨범은 시트가 아니라 **전체 화면(벽)** 하나로 모았다(A-14).
export type SheetKey = 'play' | 'notify' | 'settings';
export interface LogLine { who: 'pet' | 'me'; text: string }
/**
 * 벽에 걸린 액자 하나.
 *
 * ★ `key` 는 **카탈로그 key 문자열**이다(옛 `FrameKey` 8종에서 넓혔다). 서버 도감은 18칸이고
 *   2층 동작 key 까지 오므로 8종으로는 못 담는다. 무엇을 그릴지는 `spriteUrl` 한 곳이 정한다.
 */
export interface FrameData { name: string; open: boolean; cond: string; key: string }
/**
 * 전면 판의 버튼 하나.
 *
 * ★ `action` 은 **비워 둘 수 없다**(팀 규약 C25 — 버튼은 문구가 아니라 `data-action` 으로 집는다).
 *   문구는 상훈님이 언제든 바꾸시는 자리라, 문구로 집는 검사는 디자인을 다듬을 때마다 깨진다.
 *   필수로 둔 이유는 **새 판을 만드는 사람이 빠뜨릴 수 없게** 하기 위해서다.
 */
export interface FireAction { label: string; action: string; tap: () => void; primary: boolean }
export interface Fire {
  title: string; body: string; hint?: string; tapAny?: boolean;
  /**
   * 예시 그림 한 칸(졸업 판의 구르기 미리보기).
   *
   * ★★ 옛 **폴라로이드 칸**(`polaroid`·`caption`·`shot`·`shotLabel`)은 **없앴다**(2026-09-21 A-09).
   *   그 칸은 그림 대신 **빗금 친 색상자**를 그렸다 — 없는 장면을 있는 것처럼 보이게 하는 자리라,
   *   "그림이 없으면 칸을 접는다" 는 규칙과 정면으로 어긋났다. 진짜 그림이 생기는 날에는
   *   이 `preview` 를 쓰면 된다 — 접는 규칙이 이미 들어 있다.
   * ★ 예시 그림은 방 안 액자와 **일부러 다른 모양**(점선 틀)이다. 액자는 "내 아이와 남긴 것" 이고
   *   이쪽은 "남이 먼저 보여주는 예시" 라, 같은 틀로 그리면 사용자가 제 것으로 읽는다.
   * ★ 그림 주소가 없으면 이 칸 자체를 안 만든다 — 빈 액자가 뜨는 것보다 없는 편이 낫다.
   * ★ 주소가 **있는데도 안 열리는** 경우는 화면(`Panels.FirePreview`)이 칸을 접어서 막는다.
   *   해금 판에서는 그게 정상 경로다(심화 그림은 도착 전까지 없다).
   * ★ `w`·`h` 는 서버가 준 판 크기다. 주면 자리를 미리 잡아 판이 덜컥 커지지 않는다.
   */
  preview?: { src: string; badge: string; caption: string; w?: number; h?: number };
  /**
   * 자유 입력을 **어느 판에서** 보냈는지. 기록에만 쓴다(문구에는 안 나온다).
   * 졸업 판과 해금 판이 같은 입력칸을 쓰므로, 이걸 안 남기면 어디서 온 글인지 영영 모른다.
   */
  wishFrom?: string;
  /**
   * 자유 입력칸을 이 판에 둘 것인가.
   *
   * ★ **값이 아니라 플래그다.** 입력은 글자마다 바뀌는데 `fire` 는 판이 뜰 때 한 번 만들어
   *   상태에 눌러앉는 객체라, 값을 여기 담으면 타이핑이 화면에 안 보인다.
   *   실제 값·손잡이는 뷰(`v.wish`)가 매 렌더 새로 만든다.
   */
  wish?: boolean;
  actions: FireAction[];
}

/**
 * 개발용 덮어쓰기 한 겹. **여기 있는 값만이 서버 값을 이긴다.**
 *
 * ★ 운영에서는 전부 `null`·빈 값이라 `es` 가 예전과 **글자 그대로 같은 객체**다(덮을 것이 없으면
 *   새 객체도 안 만든다). 그래서 이 겹이 운영 동작을 바꾸지 않는다.
 */
export interface DevState {
  /** 자세 강제(카탈로그 key). 상황보다 먼저 화면에 반영된다. */
  pose: string | null;
  /** 상황 강제(상황표의 id). 고르면 그 줄이 적어 둔 자세도 같이 온다. */
  sit: string | null;
  /** 바닥 흔적(똥) 개수 0~4. */
  trash: number | null;
  /** 게이지. 화면에만 얹는다 — 서버 숫자는 안 바뀐다. */
  full: number | null; happy: number | null; bond: number | null;
  /** 몸 상태. `sickLong` 은 24시간+ 방치(해골). */
  sick: boolean | null; sickLong: boolean;
  /** 잠 · 밤 창. **시각과 무관하게** 그 상태를 만든다. */
  sleeping: boolean | null; night: boolean | null;
  /** 여행 중(떠남 2차). 화면에만 — 돌보기 잠금 문구까지 재현한다. */
  trip: boolean;
  /** 2층 8종 **전부** 열기. 개별 해금보다 먼저 본다. */
  floor2: boolean;
  /** 2층 8종 **각각** 열기(자세 key → 열림). 정본 §6 의 8칸을 하나씩 켤 수 있게. */
  unlocked: Record<string, boolean>;
  /** 하루 소품 하나 고르기(`prop_ball` 같은 소품 key). `null` 이면 안 띄운다. */
  daily: string | null;
  /** 그 밖에 화면에만 얹을 상황 id(가방·재회 하트 같은 것). */
  extra: string[];
}

/** 아무것도 안 덮은 상태. 운영이 늘 이 값이다. */
export const DEV_OFF: DevState = {
  pose: null, sit: null, trash: null, full: null, happy: null, bond: null,
  sick: null, sickLong: false, sleeping: null, night: null, trip: false,
  floor2: false, unlocked: {}, daily: null, extra: [],
};

export interface YeoulState {
  screen: ScreenKey;
  step: number;
  roomSel: RoomKey;
  popOpen: boolean; popClosing: boolean;
  chatOpen: boolean; chatClosing: boolean;
  mine: string; petLine: string;
  day: number; bond: number; floorLv: number;
  cChat: number; cBath: number; cSleep: number; cGame: number;
  /** 손으로 깨운 횟수. 2층 '일어나기' 조건(정본 §6 16번)이라 재우기(`cSleep`)와 따로 센다. */
  cWake: number;
  full: number; happy: number; stock: number; trace: number; plays: number; snacks: number;
  bathUsed: boolean; pets: number; sick: boolean; sleeping: boolean; night: boolean;
  hearts: boolean; toast: string;
  /** 성격·세계관 저장이 거절됐을 때 **시트 안에** 남는 한 줄. 빈 문자열이면 안 뜬다(→ `saveFailLine`). */
  saveErr: string;
  /**
   * 선반의 「다음에 배울 것」 카드를 **펼쳤는가**.
   *
   * ★ 카드 안이 아니라 여기 둔다(2026-09-22 판정 K) — 카드는 팝오버·시트·대화·게임 중에
   *   사라졌다가 다시 뜬다. 지역 상태로 두면 그때마다 **접힌 채로 되돌아간다.**
   */
  miniOpen: boolean;
  sheet: SheetKey | null; sheetClosing: boolean;
  playTab: 'talk' | 'guess' | 'run'; draft: string;
  /** 방금 친 좌우 맞히기 결과(서버가 준 것). 한 줄 문구를 그리는 데만 쓴다. */
  lastGuess: GuessResult | null;
  /**
   * 좌우 맞히기 **한 매치**. 시트가 아니라 **무대 위**에서 돈다(2026-09-20 안 1).
   * 규칙은 정본 §7 그대로 — 5판 3승 · 하루 3판. 화면이 규칙을 새로 만들지 않는다.
   *   `gOn`    매치가 도는 중인가(무대에 손·점 줄이 뜬다)
   *   `gPhase` 대기 → 섞기 → 공개 → (여운 뒤 자동으로 다음 판) · 마지막엔 `done`
   *   `gMarks` 판마다 맞혔나(점 다섯 칸). `null` = 아직 안 친 판
   */
  gOn: boolean;
  gPhase: 'wait' | 'shuffle' | 'reveal' | 'done';
  gRound: number; gHits: number;
  gPick: Side | null; gHit: boolean | null;
  gMarks: readonly (boolean | null)[];
  /**
   * 이 매치가 **기권으로** 끝났는가(2026-09-21 판정 3). 끝말 한 줄을 고르는 데만 쓴다 —
   * 친 라운드까지의 성적("N / 5 맞혔어요")은 기권에 맞지 않는 말이라 다른 문장이 필요하다.
   * 규칙은 서버가 쥔다(진 판으로 남는다) — 화면이 승패를 새로 정하지 않는다.
   */
  gQuit: boolean;
  /**
   * 이 매치에서 **서버 판을 이미 시작했는가**(2026-09-22 판정 J3).
   *
   * ★★ 예전에는 `live.game.playing` 으로 판단했다. 그 값은 새로고침할 때 서버의 `games/current`
   *   에서 되살아나므로, **전에 치다 만 판을 그대로 이어 쳤다.** 상훈님 판정은 "게임은 중간에
   *   나가면 끝" 이다 — 이어 치기가 없다. 그래서 **이번 매치에서 내가 시작했는지**만 본다.
   */
  gStarted: boolean;
  /**
   * 매치를 **시작할 때**의 기분(0~4). 끝말에서 "기분이 한 칸 올랐어요" 를 말해도 되는지
   * 판정하는 데만 쓴다 — 기분이 이미 가득(4)이면 이겨도 안 오르는데, 그때까지 올랐다고
   * 말하면 판정 12 에서 짚인 거짓말이 자리만 옮긴 꼴이 된다.
   */
  gHappy0: number;
  log: LogLine[]; memories: string[];
  resolved: Record<string, boolean>; calls: number;
  wallId: string;
  /**
   * 캐릭터 칸에서 고른 칩들. **한 묶음에 여러 개**를 고를 수 있다(상훈님 2026-09-11).
   * 배열 순서 = 고른 순서이고, **맨 앞이 대표**다 — 서버가 성격을 하나만 받으므로
   * `POST …/personality` 에는 `picks.persona[0]` 만 나간다(나머지는 화면에만 남는다).
   */
  picks: Record<string, string[]>; texts: Record<string, string>;
  user: Record<string, string | null>; uq: number;
  petName: string; uploaded: boolean; authed: string;
  /** 오늘 찍힌 조각 수(0~4). 정본상 **잠들 때 판정·리셋**된다. 지금은 프론트 목이라 손으로 바꾼다. */
  shards: number;
  /**
   * **잠깐 하는 동작**. 밥을 주면 먹는 그림, 청소하면 씻는 그림처럼 행동에 맞춰 잠시 바뀌었다가
   * 스스로 돌아온다(상훈님 2026-09-08). 재우기처럼 **상태로 남는 것**은 여기 안 넣는다 —
   * 그건 `sleeping` 이 이미 들고 있고, 시간이 지나도 안 풀려야 한다.
   */
  acting: string | null;
  /**
   * 지금 도는 **행동의 상황 id**(`ACTION_SITUATION` 의 값). `acting` 과 **한 몸으로 켜지고 꺼진다** —
   * 반응 그림이 사라지면 소품도 같이 사라져야 하기 때문이다.
   *
   * ★ 왜 자세(`acting`)만으로는 안 되나 — `eat` 한 자세에 밥·간식·약이 같이 달려 있어서
   *   자세만 보면 무엇을 줬는지 알 수 없다. 그래서 **행동이 제 상황을 직접 말한다.**
   */
  actSit: string | null;
  /**
   * 그 행동의 **단계 소품이 지금 몇 번째 바퀴인가**(0부터). 주먹밥 3->2->1 이 여기서 넘어간다.
   * 단계가 없는 행동은 늘 0 이다.
   */
  actStep: number;
  /**
   * **개발용 덮어쓰기 한 겹**(2026-09-13 재설계). 상훈님 원칙 — **"버튼은 그 상황을 강제한다."**
   *
   * ★ 왜 한 겹인가 — 예전 개발 버튼은 상태(`s.*`)를 직접 밀었다. 그런데 진짜 아이가 있으면
   *   화면은 서버 값만 보므로(`es`), 눌러도 아무 일이 안 났다. 연습방/진짜 방을 가르는 조건이
   *   여기저기 붙어 **누를 수 있는 칸과 불이 들어오는 칸이 어긋났다.**
   *   이제 **`es` 를 만드는 바로 그 자리에서 서버 값 위에 덮는다** — 가를 필요가 없어진다.
   * ★ `null` 은 "안 덮는다"(서버·목이 정한다), 값이 있으면 **그 값이 이긴다.**
   * ★ 새로고침하면 사라진다(로컬 검증용). 서버에 아무것도 안 남긴다.
   */
  dev: DevState;
  /** 튜토리얼 완주 축하를 이미 띄웠는가. 한 번만 뜬다. */
  tutorDone: boolean;
  /** 구르기를 배웠는가(튜토리얼 완주 기념). */
  rollUnlocked: boolean;
  /** 여울의 물음에 **직접 적는** 칸의 초안(지금은 호칭 문항만 쓴다). */
  askDraft: string;
  fire: Fire | null; decoOpen: boolean; albumOpen: number;
  wallOpen: boolean; wallClosing: boolean;
  frame: FrameData | null; frameClosing: boolean;
  notifOn: boolean; needStyleLocal: NeedStyle | null; unlockShown: boolean;
  /**
   * ★ `saved`(저장한 장수)는 **뺐다**(2026-09-21 상훈님) — 저장이 아직 준비 중이라 셀 것이 없다.
   *   예전에는 아무 데도 안 담으면서 이 숫자만 올리고 "N장 저장했어요" 라고 말했다.
   *   진짜 저장이 붙는 날 다시 세면 된다.
   */
  wishes: number; cardIdx: number;
  /** 자유 입력칸에 쓰는 중인 글. 상한(`WISH_MAX`)을 넘겨 담지 않는다. */
  wishDraft: string;
  wishSending: boolean;
  /** 보내고 받아진 뒤. 입력칸 자리에 "잘 들었어요" 한 줄이 대신 남는다. */
  wishDone: boolean;
  /** 실패 안내 한 줄. 서버 문장이 아니라 우리 표(`WISH_COPY.fail`)에서 고른 것이다. */
  wishError: string;
  sampleMode: boolean; hatch: number; snapshot: Partial<YeoulState> | null;
  tutor: number; tutorOn: boolean;
  cracking: boolean; eggMsg: string; nameErr: boolean;
  hintI: number; leaveOff: boolean;
  /**
   * **재운 직후 한 바퀴, 커튼이 무대를 덮고 있는 동안**(아이가 안 보인다).
   *
   * ★ 상훈님 2026-09-14 — *"잘 때 커튼도 마찬가지야"*(= 덮었다가 걷히며 짜잔).
   *   잠은 상태라 무한 반복이므로 **들어올 때 한 번만** 덮고 걷힌다. 걷힌 뒤에는 반투명 커튼만
   *   남고 자는 아이가 드러난 채로 유지된다 — 규격이 `curtain_sheer`(반투명)인 것과 같은 뜻이다.
   */
  sleepCover: boolean;
  /**
   * 가입·로그인 모달. 랜딩에서 무언가 하려 할 때 뜬다(상훈님 9/7 결정).
   * 창 자체는 공통 부품(`@common/auth/AuthModal`)이고 여기서는 여닫기만 든다.
   */
  authOpen: boolean; authTab: 'login' | 'signup';
  /**
   * **개발용 후기 미리보기.** 이동 창에서 켜면 방이 `FeedbackSheet` 을 mock 상태로 강제로 띄운다.
   *
   * ★ 왜 `dev`(DevState)가 아니라 여기인가 — `dev` 는 무대에 얹는 **장면 덮어쓰기**(자세·게이지·소품)라
   *   `applyDev` 로 `es` 에 녹아든다. 후기 판은 장면이 아니라 그 위에 뜨는 **UI 오버레이**라 결이 다르다.
   *   `sampleMode` 처럼 최상위 한 칸으로 두어 방이 곧장 읽게 한다(서버·목 어느 쪽이든 그대로 얹힌다).
   * ★ 새로고침하면 꺼진다(로컬 검증용). 공개 도메인에선 이동 창이 없어 켜질 길이 없다.
   */
  fbPreview: boolean;
}

/**
 * 첫 값. 시안이 쓰던 숫자 그대로다(12일째·친밀도 40%…) — 화면을 눌러 보기 위한 자리표시다.
 * ★ 다만 첫 화면만은 시안(방)과 다르게 **랜딩**이다. 시안은 캔버스라 방부터 열지만,
 *   서비스에서 처음 온 사람을 12일째 방에 떨어뜨릴 수는 없다. 방으로는 이동 띠로 한 번에 간다.
 */
const INITIAL: YeoulState = {
  screen: 'onb', step: 0, roomSel: 'table', popOpen: true, popClosing: false,
  chatOpen: false, chatClosing: false, mine: '', petLine: '',
  day: 12, bond: 40, floorLv: 2, cChat: 0, cBath: 0, cSleep: 0, cGame: 0, cWake: 0,
  full: 2, happy: 2, stock: 3, trace: 2, plays: 3, snacks: 0,
  bathUsed: false, pets: 1, sick: false, sleeping: false, night: false,
  hearts: false, toast: '', miniOpen: false, saveErr: '',
  sheet: null, sheetClosing: false, playTab: 'talk', draft: '', lastGuess: null,
  gOn: false, gPhase: 'wait', gRound: 0, gHits: 0, gPick: null, gHit: null,
  gMarks: [null, null, null, null, null], gQuit: false, gHappy0: 0, gStarted: false,
  log: [{ who: 'pet', text: '있잖아, 오늘은 뭐 했어요?' }],
  memories: ['빵 좋아함', '비 싫어함', '왼쪽을 잘 맞힘', '늦잠', '파란색'],
  resolved: {}, calls: 3,
  wallId: 'cream', picks: {}, texts: {}, user: {}, uq: 0,
  // ★ 이름은 **비워 둔다**(상훈님 판정 3). 미리 채워 두면 지우지 않고 넘긴 사람의 아이가
  //   남의 이름으로 만들어진다. 자리표시자('여울')만 보여 주고 값은 빈 칸이다.
  petName: '', uploaded: false, authed: '', askDraft: '',
  shards: 2, tutorDone: false, rollUnlocked: false, acting: null, actSit: null,
  dev: DEV_OFF, actStep: 0,
  fire: null, decoOpen: false, albumOpen: 8,
  wallOpen: false, wallClosing: false, frame: null, frameClosing: false,
  notifOn: true, needStyleLocal: null, unlockShown: false,
  wishes: 0, cardIdx: 0,
  wishDraft: '', wishSending: false, wishDone: false, wishError: '',
  sampleMode: false, hatch: 0, snapshot: null,
  tutor: 0, tutorOn: false, cracking: false, eggMsg: '', nameErr: false,
  hintI: 0, leaveOff: false, sleepCover: false,
  authOpen: false, authTab: 'signup', fbPreview: false,
};

// ── 작은 계산들 ──────────────────────────────────────────────────────────

/**
 * 개발용 덮어쓰기를 상태 한 벌 위에 얹는다. **켜진 칸만** 이긴다.
 * 덮을 것이 없으면 받은 것을 그대로 돌려준다 — 운영에서 새 객체를 만들지 않으려고.
 */
function applyDev(base: YeoulState, d: DevState): YeoulState {
  const over: Partial<YeoulState> = {};
  if (d.full != null) over.full = d.full;
  if (d.happy != null) over.happy = d.happy;
  if (d.trash != null) over.trace = d.trash;
  if (d.bond != null) over.bond = d.bond;
  if (d.sick != null) over.sick = d.sick;
  if (d.sleeping != null) over.sleeping = d.sleeping;
  if (d.night != null) over.night = d.night;
  return Object.keys(over).length === 0 ? base : { ...base, ...over };
}

/** 급함의 단계. 시안 `levels()`. */
function levelsOf(s: YeoulState, m: Mode): Record<RoomKey, LvKey> {
  if (m === 'sleep') return { table: 'off', bath: 'off', play: 'off', bed: 'sleep', album: 'plain' };
  const table: LvKey = s.full <= 1 ? 'now' : s.full <= 2 ? 'soon' : 'ok';
  let bath: LvKey = s.trace >= 3 ? 'now' : s.trace >= 1 ? 'soon' : 'ok';
  let play: LvKey = s.plays <= 0 ? 'off' : s.happy <= 1 ? 'now' : s.happy <= 2 ? 'soon' : 'ok';
  // ★ 아플 때 욕실 타일을 붉게(now) 만들지 않는다(상훈님 판정 6). 아픈 것은 씻길 일이 아니고,
  //   알리는 일은 무대의 약 아이콘 깜빡임 하나가 맡는다. 놀이는 실제로 막히므로 회색 그대로.
  if (m === 'sick') { play = 'gray'; }
  return { table, bath, play, bed: m === 'night' ? 'ready' : 'off', album: 'plain' };
}

export interface CallItem { kind: 'chat' | 'call'; text: string; room: RoomKey }

/** 지금 아이가 기다리는 일. 첫 번째가 말풍선으로 뜨고, 그 방 타일이 흔들린다. 시안 `callQueue()`. */
function callQueueOf(s: YeoulState, m: Mode): CallItem[] {
  const r = s.resolved;
  const out: CallItem[] = [];
  if (m === 'sleep') return out;

  // ★ 순서 = **아픔 → 밤(재우기) → 배고픔 → 청소 → 대화**(상훈님 2026-09-08 판정 7).
  //   예전엔 대화가 늘 1순위라 밤에도 아플 때도 잡담이 먼저 떴다. 급한 것이 먼저 말해야 한다.
  //   첫 부름의 방이 흔들리므로 이 순서가 곧 **강조되는 타일의 순서**이기도 하다.
  if (m === 'sick') out.push({ kind: 'call', text: '몸이 무거워요…', room: 'bath' });
  if (m === 'night' && !r.bed) out.push({ kind: 'call', text: '이제 졸려요', room: 'bed' });
  // ★ 문턱을 **자세와 같은 0 칸**으로 맞췄다(2026-09-22 판정 J). 예전에는 `<= 2` 라 두 칸이나
  //   남았는데도 "배고파요" 가 떠 있었고, 자세는 0 칸에서야 바뀌어 **말과 몸이 따로 놀았다**
  //   (상훈님 dev 실측: 배부름 1·2 에서 말풍선만 떠 있음).
  if (s.full <= 0 && !r.table) out.push({ kind: 'call', text: '배고파요', room: 'table' });
  if (s.trace >= 2 && !r.bath) out.push({ kind: 'call', text: '여기 좀 치워 주세요', room: 'bath' });

  const last = s.log[s.log.length - 1];
  if (!r.chat && s.calls > 0) {
    out.push({ kind: 'chat', text: last?.who === 'pet' ? last.text : '방금 얘기 좋았어요', room: 'play' });
  }
  return out;
}

/** 하루에 줄 수 있는 간식 수. 서버 규칙(`today.snackStreak === 4` 에서 배탈)과 같은 숫자다. */
const SNACK_MAX = 4;
/** 하루에 세어지는 쓰다듬기 수. 서버 규칙(`today.pets === 3`)과 같은 숫자다. */
const PET_MAX = 3;

/**
 * 오늘 오간 말을 화면 순서대로 편다. 부름 하나가 최대 세 줄이 된다 —
 * 아이가 건넨 말 · 내가 한 답 · 아이가 돌려준 말. **전부 서버 문구 그대로다.**
 */
function serverLog(c: ChatState | null): { who: 'me' | 'pet'; text: string }[] {
  if (!c) return [];
  const out: { who: 'me' | 'pet'; text: string }[] = [];
  for (const call of c.calls) {
    out.push({ who: 'pet', text: call.line });
    if (call.answer) out.push({ who: 'me', text: call.answer });
    if (call.replyLine) out.push({ who: 'pet', text: call.replyLine });
  }
  return out;
}

/** 부름이 닫혀 있을 때 입력칸에 두는 **화면의 안내**(아이 대사가 아니다). */
function nextCallHint(nextAt: string | null): string {
  if (!nextAt) return '오늘 부름은 다 끝났어요';
  const t = new Date(nextAt);
  if (Number.isNaN(t.getTime())) return '다음 부름을 기다려요';
  const hh = String(t.getHours()).padStart(2, '0');
  const mm = String(t.getMinutes()).padStart(2, '0');
  return `${hh}:${mm}쯤 다시 불러요`;
}

const cells = (n: number, on: string, off: string) => [0, 1, 2, 3].map((i) => ({ bg: i < n ? on : off }));

// ── 표(view) 타입 ────────────────────────────────────────────────────────

export interface Tile {
  key: RoomKey; label: string; layers: string[];
  fg: string; tileBg: string; bw: string; bd: string; anim: string;
  badge: string; hasBadge: boolean; pick: () => void; dim: boolean;
}
export interface PopBtn {
  label: string; count: string; tap: () => void; anim: string;
  bg: string; fg: string; subFg: string; bd: string;
  /**
   * 지금 누를 수 없는가. **진짜 `disabled`** 로 내려간다(계약 10절 "거절될 버튼은 미리 잠가 둔다").
   * 예전에는 회색으로만 칠하고 눌리게 두어, 눌러 봐야 왜 안 되는지 알 수 있었다.
   */
  off: boolean;
  /** 왜 못 누르는지 한 줄. 잠겨 있을 때 남은 횟수 자리에 대신 뜬다. 눌릴 때는 빈 문자열. */
  why: string;
}
export interface Pop {
  show: boolean; anim: string; name: string; say: string;
  bar: { bg: string }[]; hasBar: boolean; count: string;
  a: PopBtn | null; b: PopBtn | null; hasB: boolean;
}
export interface Stage {
  wall: string; floor: string; frame: string; sky: string; pattern: string;
  moon: boolean; sun: boolean; curtain: boolean; sick: boolean;
  charFilter: string; play: 'running' | 'paused';
  /**
   * 아이 그림에만 거는 배율. 평소 1, **배부름 0 이면 0.7**(정본 §게이지 "배부름 0 = 기본 자세 0.7배").
   *
   * ★ 상자(레이아웃)는 안 건드리고 **그림에만** 건다 — 상자를 줄이면 바닥 소품(똥)이 읽는 자(`charBox` 폭)
   *   까지 같이 줄어 방 안 물건이 통째로 작아진다. 말풍선 자리는 같은 배율로 따로 낮춘다.
   */
  charScale: number;
}
/**
 * 아이 말풍선이 그릴 것. **말 한 줄이 전부다.**
 *
 * ★ 2026-09-21(A-05~A-08) — 튜토리얼 안내가 여기 얹혀 있었다. 연습방에서는 그 안내가 무대
 *   **위쪽 머리 띠**의 카드로 나왔고, 진짜 방에서는 **아래 선반 카드**로 나왔다. 같은 안내인데
 *   화면마다 자리가 달랐다(실측 — 위에서 40px 대 아래에서 125px). 이제 두 화면 모두
 *   선반 카드(`mini`)가 맡는다. 말풍선은 아이의 말만 한다.
 */
export interface Bubble {
  show: boolean; text: string;
}
export interface Opt extends Sel { text: string; pick: () => void }
export interface CharGroup {
  key: string; title: string; ph: string; value: string;
  /** 접었을 때 보이는 한 줄. 고른 칩을 그대로 읽어 준다. */
  summary: string;
  /** 무언가 채웠는가 — 접힌 칸의 테두리 색이 이걸 본다. */
  done: boolean;
  /** "여러 개 고를 수 있어요" 같은 한 줄 안내. 칩 위에 작게 붙는다. */
  note: string;
  onInput: (v: string) => void; cardBd: string; cardBg: string; opts: Opt[];
}

/**
 * 「지금 만나러 가기」를 누른 뒤 방으로 넘어가기까지(ms).
 * 깨지는 동작 1.45초(`Egg.tsx` 의 `yCrack`) + 숨 0.25초. **한 벌로 고칠 것.**
 */
export const EGG_CRACK_MS = 1700;

/** 대화 한 마디의 글자 수 한도(서버와 같은 값). 입력칸 `maxLength` 도 이걸 쓴다. */
export const CHAT_MAX = 40;

// ── 본체 ────────────────────────────────────────────────────────────────

export function useYeoul(live?: Live) {
  const [s, setS] = useState<YeoulState>(INITIAL);
  const patch = useCallback((p: Partial<YeoulState>) => setS((v) => ({ ...v, ...p })), []);

  /**
   * 부화 진행. **아이가 있으면 서버 말만 듣는다**(상훈님 2026-09-09 판정 1).
   *
   * 목의 `s.hatch` 는 그림을 안 올린 사람이 시안을 눌러 볼 때 쓰는 가짜 계수기다. 그런데
   * 그것이 튜토리얼을 넘기거나 토스트가 뜰 때마다 올라가서, 서버는 아직 굽고 있는데 알 화면이
   * '다 됐어요' 를 띄웠다. 눌러도 안 열리는 문이 된다.
   *
   * ★ 2026-09-09 새 계약 — 진행이 **서버 숫자**로 온다(`progress / total`). 전에는 '라벨이
   *   바뀐 횟수' 를 셌는데 그건 전용 API 가 없던 시절의 임시방편이었다. 이제 세지 않는다.
   *   알 그림의 칸은 넷으로 고정이므로 서버 비율을 넷에 옮겨 칠하고, **글자는 서버 숫자 그대로**
   *   보여 준다(총 단계가 넷이 아닐 수 있어 'N / 4' 라고 쓰면 거짓말이 된다).
   * ★ '다 됐어요' 의 근거는 칸 수가 아니라 **`phase === 'ALIVE'`** 다. 칸이 다 차 보여도
   *   서버가 아직 안 끝났다고 하면 문을 열지 않는다.
   *
   * ref 로 들고 있는 이유 = 아래 `setS` 안에서 읽는데, 값이 바뀔 때마다 콜백을 새로 만들면
   * 타이머가 걸린 손잡이들이 통째로 다시 태어난다.
   */
  /**
   * ── 서버가 준 지금 상태 ─────────────────────────────────────────
   *
   * `sv` 가 있으면 **그 값이 정본**이고 목(`s.*`)은 쓰지 않는다. 둘을 섞으면 어느 숫자가
   * 진짜인지 알 수 없어지므로, 값마다 `onServer ? 서버 : 목` 한 줄로만 가른다.
   *
   * ★ 여울 샘플 방은 예외다 — 거긴 연습 상대라 처음부터 끝까지 목이 맞다.
   * ★ 아직 안 옮긴 값(게이지·재고·부름·조각·앨범 칸 수…)은 그대로 목이다. 옮길 때마다
   *   이 갈림길을 하나씩 늘린다.
   */
  const sv = live?.pet ?? null;
  const onServer = !!sv && sv.phase === 'ALIVE' && !s.sampleMode;

  /**
   * 눌린 순간 먼저 얹은 값(낙관적 갱신). 응답이 오면 `useHatch` 가 이걸 버리고 서버 값으로 덮는다.
   * 없으면(`null`) 그냥 서버 값이다.
   */
  const og = live?.optimistic ?? null;

  /**
   * **지금 값** — 목 상태 위에 서버 값(그리고 방금 누른 낙관값)을 덮은 것. 아래 계산은 전부 이걸 본다.
   *
   * ★ 숫자는 전부 서버가 준 것이다. 화면이 스스로 올리는 것은 **방금 누른 한 번뿐**이고,
   *   그것도 응답이 오는 순간 서버 값으로 덮인다(계약 10절 + 2026-09-10 절충 → `useHatch.doCare`).
   * ★ 아직 안 옮긴 것(놀이 횟수 `plays`·부름 `calls`·앨범 칸 수·조각)은 그대로 목이다.
   */
  const svEs: YeoulState = onServer ? {
    ...s,
    // ★ 2026-09-10 — **목으로 폴백하지 않는다.** `?? s.full` 로 메우던 자리가 방 입장 때
    //   게이지를 튀게 하던 범인이었다(목 값으로 먼저 그려졌다가 서버 값이 도착하며 0→3).
    //   방은 `live.petReady` 전에는 아예 안 그리므로(Yeoul.tsx) 여기서 비어 있을 일이 없고,
    //   그래도 비면 0 으로 둔다 — 목 숫자를 섞느니 빈 게이지가 정직하다.
    full: og?.fullness ?? sv.gauges?.fullness ?? 0,
    happy: og?.happiness ?? sv.gauges?.happiness ?? 0,
    trace: og?.trash ?? sv.gauges?.trash ?? 0,
    stock: og?.foodCount ?? sv.food?.count ?? 0,
    snacks: og?.snackStreak ?? sv.today?.snackStreak ?? 0,
    bathUsed: og?.bathDone ?? !!sv.today?.bathDone,
    pets: og?.pets ?? sv.today?.pets ?? 0,
    sick: og?.healed ? false : sv.sick != null,
    sleeping: !!sv.clock?.sleeping,
  } : s;

  /**
   * **개발용 덮어쓰기를 여기서 얹는다** — 서버 값이 정해진 바로 그 자리다.
   *
   * ★ 이 한 자리 덕에 "연습방이냐 진짜 방이냐" 를 아래에서 다시 안 가른다. 개발 버튼은 전부
   *   `s.dev` 에만 쓰고, 화면은 늘 `es` 만 읽는다. 그래서 **눌리는 칸과 불이 들어오는 칸이 같다.**
   * ★ 덮을 것이 하나도 없으면 **원래 객체를 그대로 돌려준다**(`svEs`). 운영에서 렌더가 늘던
   *   일이 없게, 그리고 예전과 같은 값임을 눈으로 보이게.
   */
  const es: YeoulState = applyDev(svEs, s.dev);

  /** `setS` 안이나 손잡이 안에서 읽을 것들. 값이 바뀔 때마다 손잡이를 새로 만들지 않으려고 ref 로 둔다. */
  const liveRef = useRef<Live | undefined>(undefined);
  liveRef.current = live;
  const onServerRef = useRef(false);
  onServerRef.current = onServer;
  const esRef = useRef(es);
  esRef.current = es;
  const sRef = useRef(s);
  sRef.current = s;

  const realHatch = !!live?.petId;
  const hatchTotal = live?.total ?? 0;
  const hatchRatio = hatchTotal > 0 ? Math.min(1, (live?.progress ?? 0) / hatchTotal) : 0;
  const hatchReady = realHatch ? !!live?.ready : s.hatch >= 4;
  // 채워진 칸(0~4). 다 되기 전에는 3 에서 멈춘다 — 가득 찬 칸은 '이제 열린다' 로 읽힌다.
  const hatchN = realHatch
    ? (hatchReady ? 4 : Math.min(3, Math.floor(hatchRatio * 4)))
    : s.hatch;
  const hatchPct = realHatch ? Math.round(hatchRatio * 100) : Math.min(100, s.hatch * 25);
  const hatchText = realHatch
    ? (hatchTotal > 0 ? `${live?.progress ?? 0} / ${hatchTotal}` : '시작하는 중')
    : `${Math.min(4, s.hatch)} / 4`;
  const realRef = useRef(false);
  realRef.current = realHatch;
  // setS 콜백 안에서도 '다 됐는지' 를 읽어야 한다(exitSample).
  const readyRef = useRef(false);
  readyRef.current = hatchReady;

  // 타이머는 정리해야 하므로 한곳에 모아 둔다.
  const T = useRef<Record<string, ReturnType<typeof setTimeout> | undefined>>({});
  const lastSel = useRef(0);
  const later = useCallback((k: string, ms: number, fn: () => void) => {
    clearTimeout(T.current[k]);
    T.current[k] = setTimeout(fn, ms);
  }, []);
  useEffect(() => {
    const timers = T.current;
    return () => { Object.values(timers).forEach((t) => clearTimeout(t)); };
  }, []);

  const mode: Mode = es.sleeping ? 'sleep' : es.sick ? 'sick' : es.night ? 'night' : 'day';
  const needStyle: NeedStyle = s.needStyleLocal ?? '색+모양+글자';

  /**
   * 행동 하나를 화면에 잠깐 보여 준다 — **자세와 상황을 한 몸으로** 켠다.
   *
   * @param key 지을 자세(카탈로그 key).
   * @param sit 그 행동의 **상황 id**(`ACTION_SITUATION`). 주면 —
   *   1. 자세를 **표가 그 줄에 적어 둔 자세**로 맞춘다. 둘이 어긋나면 그리는 쪽이
   *      `row.pose === scene.pose` 를 요구하므로 **소품이 영영 안 뜬다**(2026-09-13 실제 사고).
   *   2. 같은 타이머로 함께 꺼진다 — 반응 그림이 사라지면 소품도 같이 사라진다.
   */
  const act = useCallback((key: string, sit: string | null = null, cycles?: number) => {
    const pose = (sit ? poseOfSituation(SITUATION_TABLE, sit) : null) ?? key;
    // 단계가 있는 소품은 **단계 수만큼 바퀴를 돈다**(밥 3->2->1 = 3바퀴 = 12프레임).
    // ★ 바퀴 수를 밖에서 주면 그것이 이긴다 — 단계 그림이 없는 층(밥 2층·쓰다듬)과
    //   박자 밖인 선물 2종(16프레임 = 4바퀴)이 그 길로 온다.
    const plan = sit ? stagePlanOf(SITUATION_TABLE, sit) : null;
    const n = Math.max(1, Math.round(cycles ?? plan?.stages.length ?? 1));

    // 앞서 돌던 연출의 바퀴 타이머를 **전부** 걷어낸다 — 안 그러면 다음 행동 도중에 옛 단계가 끼어든다.
    for (let i = 1; i <= ACT_MAX_CYCLES; i++) clearTimeout(T.current[`actStep${i}`]);
    setS((v) => ({ ...v, acting: pose, actSit: sit, actStep: 0 }));
    for (let i = 1; i < n; i++) {
      later(`actStep${i}`, i * CYCLE_MS, () => setS((v) => ({ ...v, actStep: i })));
    }
    // ★ 길이는 **바퀴 수 하나로만** 정해진다(A절 표). 바닥값을 따로 두지 않는다.
    later('acting', n * CYCLE_MS, () => setS((v) => ({ ...v, acting: null, actSit: null, actStep: 0 })));
  }, [later]);

  /**
   * 연출을 틀기 전에 **무대를 비운다** — 방 화면으로 옮기고, 자세 고정·시트·팝오버를 걷는다.
   *
   * ★ 왜 필요한가 — 이동 창은 랜딩·온보딩에서도 열린다. 거기서 연출 칩을 누르면 아이를 그리는
   *   자리가 아예 없어 **아무 일도 안 일어난 것처럼 보인다**(2026-09-13 실측으로 그랬다).
   * ★ 자세 고정(`dev.pose`)도 같이 푼다. 고정이 화면을 이기므로 안 풀면 그림이 안 바뀐다.
   */
  const toRoom = useCallback(() => setS((v) => ({
    ...v, screen: 'room', sheet: null, popOpen: false, chatOpen: false, wallOpen: false, fire: null,
    dev: { ...v.dev, pose: null, sit: null },
  })), []);

  /**
   * 선물 한 판(구르기 · 뒤로 넘어짐). **A절 박자 밖**이다 — 16프레임짜리 한 판이라
   * 소품 없이 그대로 한 번 틀고 기본으로 돌아간다(4바퀴 = 7.2초).
   */
  const playGift = useCallback((key: string) => {
    toRoom();
    act(key, null, GIFT_CYCLES);
  }, [act, toRoom]);

  /**
   * **개발 창의 연출 한 판** — 규칙도 서버도 안 탄다(상훈님 2026-09-13 "이동으로 만지는 건 프론트만").
   *
   * ★ 왜 따로 두나 — 방의 행동 버튼(`onRice`·`onClean` …)은 재고·흔적·시각을 먼저 본다.
   *   그게 맞다, 거긴 진짜 돌보기니까. 그런데 **연출이 제대로 도는지 보려는 사람에게는 그 검사가
   *   벽**이다(밥 재고 0 이면 주먹밥이 줄어드는 걸 볼 길이 없다). 그래서 이 길은 바로 연출로 간다.
   * ★ 자세 고정(`dev.pose`)이 걸려 있으면 그것이 화면을 이기므로 먼저 푼다 — 안 그러면
   *   눌러도 그림이 안 바뀌어 "고장" 으로 읽힌다.
   * ★ 청소는 **화면의 흔적을 하나 줄인다.** 규칙을 안 타는 대신 눈에 줄어드는 것이 보여야
   *   "청소가 됐다" 를 확인할 수 있다(상훈님 "똥 4개로 채운 다음에 청소하려니까 흔적이 없다고 안 된다").
   */
  const playScene = useCallback((p: { id: string; pose: string; cycles: number }) => () => {
    toRoom();
    if (p.id === 'clean_l1' || p.id === 'clean_l2') {
      const cur = sRef.current.dev.trash ?? esRef.current.trace;
      setS((v) => ({ ...v, dev: { ...v.dev, trash: Math.max(0, cur - 1) } }));
    }
    act(p.pose, p.id, p.cycles);
  }, [act, toRoom]);

  /**
   * 그 행동이 지금 켤 **상황 id**. 2층이 열려 있으면 2층 줄을 쓴다.
   *
   * ★ 층에 따라 갈리는 이유 — 표가 같은 행동을 두 줄로 적어 두었다. 1층은 **소품이 대신하고**
   *   (밥그릇·쓰다듬는 손), 2층은 **그림 안에 이미 들어 있다**(`prop: null`).
   * ★ 지금 2층을 여는 길은 **연습방 스위치 하나뿐**이다(`floor2`). 진짜 아이의 해금은 서버가 쥔다 —
   *   그 값이 오면 여기만 바꿔 읽으면 된다.
   */
  /**
   * 이 행동의 **2층이 열렸는가.**
   *
   * ★★ 2026-09-22 판정 H — 예전에는 **개발 창 값만** 봤다(`s.dev.floor2`·`s.dev.unlocked`).
   *   그래서 진짜 방에서는 서버가 아무리 열어 줘도 **2층 자세가 영영 안 나왔다.**
   *   dev 실측: 쓰다듬 2층(`petted`)이 서버에서 `unlocked=true` 인데 화면은 1층 `pet` 을 재생.
   *   도감(앨범)은 서버를 읽고 재생은 목을 읽어서, **한 화면에서 두 출처가 갈려 있었다.**
   * ★ 이제 순서는 이렇다 — 개발 창(목·연습방 전용) → **서버 도감** → 닫힘.
   *   개발 창 값은 **덮어쓰기**로만 남는다(켜면 열린 것으로 친다). 끄는 데는 안 쓴다.
   * ★ 자세 key 는 서버 도감의 key 와 **같은 낱말**이다(`petted`·`eat_rice`·`sweep`…) —
   *   표(`SITUATION_TABLE`)가 그 낱말로 적혀 있어 통역이 필요 없다.
   */
  const floor2Of = useCallback((action: ActionKey) => {
    const d = sRef.current.dev;
    if (d.floor2) return true;
    const l2 = (ACTION_SITUATION[action] as { l2?: string }).l2;
    const pose = l2 ? poseOfSituation(SITUATION_TABLE, l2) : null;
    if (!pose) return false;
    if (d.unlocked[pose]) return true;
    if (!onServerRef.current) return false;
    return !!liveRef.current?.pet?.motions?.find((m) => m.key === pose)?.unlocked;
  }, []);
  const sitOf = useCallback((action: ActionKey) => situationOfAction(action, floor2Of(action)), [floor2Of]);

  /**
   * **행동 한 번을 연출한다** — 상황 id 하나만 정하면 자세는 표가 따라온다.
   *
   * ★ 자세를 여기 적지 않는 이유 — 층에 따라 자세가 갈린다(밥 주기: 1층 `eat` · 2층 `eat_rice`).
   *   코드에 자세를 박으면 표와 어긋나고, 어긋나면 **소품이 조용히 안 뜬다.**
   */
  /**
   * 그 행동이 **실제로 바꾸는 값**. 행동이 성공하면 그 칸의 개발 덮개를 걷는다.
   *
   * ★ 왜 걷어야 하나 — 안 걷으면 똥을 4로 강제해 둔 채 청소를 눌렀을 때 화면에 똥이 그대로 남는다.
   *   "눌렀는데 아무 일도 안 난다" 가 되고, 그게 바로 이번에 고친 고장의 얼굴이다.
   * ★ 걷는 것은 **그 행동이 건드리는 칸만**이다. 재워 놓고 밥을 줬다고 잠이 풀리면 안 된다.
   */
  const RELEASE: Partial<Record<ActionKey, readonly (keyof DevState)[]>> = useMemo(() => ({
    feed_rice: ['full'], feed_snack: ['full'],
    clean: ['trash'], bath: ['trash'],
    medicine: ['sick', 'sickLong'],
    game_win: ['happy'], game_lose: ['happy'],
    wake: ['sleeping'],
  }), []);

  const careAct = useCallback((action: ActionKey) => {
    const f2 = floor2Of(action);
    const sit = situationOfAction(action, f2);
    // ★ 표가 그 층에 줄을 안 적어 둔 행동(답하기·깨우기·좌우 고르기의 1층)은 **소품 없이 몸짓만**
    //   짓는다(2026-09-22 판정 I). 예전에는 그 셋이 1층 줄이 없다는 이유로 **잠겨 있어도 2층
    //   자세를 그대로 재생**하거나(답하기·깨우기) **아무 자세도 안 켜졌다**(좌우 고르기).
    if (!sit) {
      const fallback = LOCKED_POSE[action];
      if (fallback) act(fallback, null, cyclesOfAction(SITUATION_TABLE, action, false));
      return;
    }
    // ★ 그 행동이 건드리는 칸의 개발 덮개를 걷는다 — 안 그러면 눌러도 화면이 안 바뀐다.
    const keys = RELEASE[action];
    if (keys?.length) {
      setS((v) => {
        if (!keys.some((k) => v.dev[k] !== DEV_OFF[k])) return v;
        const next = { ...v.dev };
        for (const k of keys) (next as Record<string, unknown>)[k] = DEV_OFF[k];
        return { ...v, dev: next };
      });
    }
    act(poseOfSituation(SITUATION_TABLE, sit) ?? 'base', sit, cyclesOfAction(SITUATION_TABLE, action, f2));
  }, [act, floor2Of, RELEASE]);

  /** 아무 일도 안 하는 손잡이. 안 보이는 버튼 자리를 채운다. */
  const noop = useCallback(() => {}, []);

  const flash = useCallback((t: string) => {
    setS((v) => ({ ...v, toast: t, hatch: v.sampleMode && !realRef.current ? Math.min(4, v.hatch + 1) : v.hatch }));
    later('toast', 1700, () => setS((v) => ({ ...v, toast: '' })));
  }, [later]);

  /**
   * "이런 동작도 보고 싶어요" — 정본 §심화 행동의 수요조사 한 줄.
   *
   * ★★ 2026-09-20 — 예전에는 **아무 데도 안 보내면서** "기록해 뒀어요" 라고 했다. 화면이
   *   하지 않은 일을 했다고 말하는 자리였다. 지금은 진짜로 남긴다 — 전용 서버 API 를 새로
   *   만들 필요가 없다. 행동 기록 수집기(`POST /api/v1/events`)가 이미 열려 있고 비로그인도 받는다.
   * ★ `trackConversion` 인 이유 — 평범한 `track` 은 5초 뒤에 묶어 보낸다. 이 판은 누르고
   *   바로 닫고 나가는 자리라, 그 5초 안에 탭이 사라지면 정작 알고 싶은 한 줄을 잃는다.
   * ★ props 키는 수집기 허용 목록(`ALLOWED_PROP_KEYS`) 안의 것만 쓴다 — 목록 밖 키는 조용히 버려진다.
   */
  const recordWish = useCallback((from: string) => {
    void trackConversion('zzal_motion_wish', { from });
    setS((v) => ({ ...v, wishes: v.wishes + 1, fire: null }));
    flash('기록해 뒀어요');
  }, [flash]);

  /** 입력칸에 쓰는 중. **상한을 넘겨 담지 않는다** — 넘긴 뒤 꾸짖는 것보다 못 넘게 하는 쪽이 낫다. */
  const onWishDraft = useCallback((t: string) => {
    setS((v) => ({ ...v, wishDraft: t.slice(0, WISH_MAX), wishError: '' }));
  }, []);

  /**
   * 보고 싶은 동작 한 줄 보내기.
   *
   * ★ 보낸 뒤 **같은 자리에서** "받았다" 고 말하고 입력칸을 접는다(`wishDone`). 토스트만 띄우고
   *   입력칸을 남겨 두면 보낸 줄 모르고 한 번 더 보낸다.
   * ★ 사람이 쓴 글은 전용 API 로만 간다. 행동 기록에는 **고정된 이름 한 줄**만 —
   *   글을 실어 보내 봐야 수집기가 조용히 버린다(`common/fe/analytics.ts` 의 허용 키).
   * ★ 실패 문구는 **코드로 고른다.** 서버 문장을 그대로 띄우면 말투의 주인이 백엔드로 넘어간다.
   */
  const sendWish = useCallback(() => {
    const text = sRef.current.wishDraft.trim();
    if (!text || sRef.current.wishSending || sRef.current.wishDone) return;
    setS((v) => ({ ...v, wishSending: true, wishError: '' }));
    void (async () => {
      const r = await liveRef.current?.sendWish(text) ?? { ok: false, code: null };
      if (r.ok) {
        void trackConversion('motion_wish_submitted', { from: sRef.current.fire?.wishFrom ?? 'tutorial_gift' });
        /**
         * ★ 보낸 뒤 **답 판**으로 갈아 끼운다(2026-09-21 판정 5 · A-12). 예전에는 입력칸 자리에
         *   한 줄만 남기고 끝이라, 보낸 사람 입장에서는 그 글이 어디로 갔는지 알 수 없었다.
         * ★★ 문구가 **"곧 추가하겠다" 를 약속하지 않는다.** 언제 되는지 우리도 모르고,
         *   단정하는 순간 늦어지는 분께는 그대로 거짓말이 된다. 이미 참인 것만 말한다 —
         *   글은 진짜로 남고, 만드는 사람이 그걸 읽는다.
         */
        setS((v) => ({
          ...v, wishSending: false, wishDone: true, wishDraft: '', wishError: '',
          fire: {
            title: WISH_REPLY.title, body: WISH_REPLY.body, hint: '', tapAny: true,
            actions: [{
              label: WISH_REPLY.close, action: 'wish-reply-close', primary: true,
              tap: () => setS((w) => ({ ...w, fire: null })),
            }],
          },
        }));
        return;
      }
      setS((v) => ({ ...v, wishSending: false, wishError: wishFailLine(r.code) }));
    })();
  }, []);

  // ── 튜토리얼 ──
  //
  // ★ 2026-09-10 — 진짜 아이는 **서버가 칸을 센다**(`tutorial.step`). 화면은 순서를 다시 판정하지 않는다.
  //   1~8칸은 부를 API 가 따로 없다 — 그 칸의 행동을 평소대로 하면 서버가 스스로 넘긴다.
  //   9칸만 `tutorial/done` 을 부르고, **그 호출이 시계를 켠다.**
  const svTut = onServer ? (sv.tutorial ?? null) : null;
  /** 서버가 "지금 이 칸" 이라고 찍어 준 자리. 없으면(졸업했거나 목이면) null. */
  const svTutIdx = svTut?.steps.findIndex((x) => x.current) ?? -1;
  /**
   * 지금 쓰는 칸 목록. **둘뿐이다**(2026-09-22).
   *   연습방(`sampleMode`) = `TUTOR` — 여울과 **가지고 노는 자리**. 앞뒤로 오갈 수 있다.
   *   진짜 방 = `TUTOR_ROOM` — **서버든 목이든 같은 아홉 칸.** 목에서 밟은 것이 곧 서버에서 밟을 것이다.
   * ★ 예전에는 진짜 방 목이 8칸짜리 다른 목록을 써서, 서버 없는 화면으로는 진짜 튜토리얼을
   *   확인할 수 없었다. 목록을 하나로 합치면서 그 구멍이 닫혔다.
   */
  const TUT: readonly TutorStep[] = s.sampleMode ? TUTOR : TUTOR_ROOM;
  const tut: TutorStep | null = onServer
    ? (svTutIdx >= 0 ? TUTOR_ROOM[svTutIdx] ?? null : null)
    : (s.sampleMode || s.tutorOn) && s.tutor < TUT.length ? TUT[s.tutor] : null;
  /** 지금 몇 번째 칸인가(점·`3 / 9` 표시용). 서버에 붙어 있으면 서버 숫자 그대로. */
  const tutIdx = onServer ? Math.max(0, svTutIdx) : s.tutor;
  /** 마지막 칸 — 누를 것이 없어서 우리가 `tutorial/done` 을 보내야 하는 자리. */
  /** 마지막 칸 — 누를 것이 없어서 **눌러서 끝내는** 자리. 목도 서버와 같은 칸을 쓴다. */
  const atDone = !s.sampleMode && !!tut && tut.done === 'DONE';

  /**
   * **튜토리얼이 지금 시키는 것 하나**(정본 §12 "안내 버튼 외 잠금" · 2026-09-22 판정 J8).
   *
   * ★★ 왜 잠그나 — 아기 시간표는 **한 번에 하나씩** 가르치는 자리다. 다른 버튼이 같이 살아 있으면
   *   순서가 흐트러지고, 서버는 그 칸에서 안 시킨 행동을 **거절**하거나 세지 않는다. 즉 눌러도
   *   아무 일이 안 나는 버튼이 여럿 있는 셈이라, 잠그지 않는 편이 오히려 고장으로 읽힌다.
   * ★★ **다만 "고장 난 것" 으로 보이면 안 된다.** 잠긴 손잡이는 **왜 지금 못 누르는지**를 그 자리에서
   *   말한다(아래 `note`). 사용자를 탓하지 않는다 — "아직 안 배웠어요"·"순서를 어겼어요" 가 아니라
   *   **"지금은 ○○를 해 볼 차례예요"** 다(자캐 규범).
   * ★ 첫 칸(`done: 'any'` — "천천히 둘러봐도 돼요")은 **안 잠근다.** 거기는 아무거나 눌러 보라는 칸이다.
   * ★ 목(8칸)·서버(9칸) **둘 다** 이 한 곳을 지난다 — 칸 목록만 다르고 규칙은 하나다.
   * ★ 원칙 7("첫 순간부터 전부 열려 있다")과 어긋나지 않는다 — 그 원칙은 **튜토리얼이 끝난 뒤**의
   *   방을 말한다. `tut` 이 비는 순간 이 잠금은 통째로 사라진다.
   */
  const tutLock = useMemo(() => {
    // ★★ **마지막 칸(DONE)은 안 잠근다**(2026-09-22 실측). 8칸이 "재우고 다시 깨워 주세요" 라
    //   9칸은 **아이가 잠든 채로** 오는데, 여기서 다 잠그면 침실 팝오버의 「깨우기」까지 막힌다.
    //   그 팝오버는 자는 동안 닫히지도 않아서 안내 카드가 뒤에 가리고, 결국 **아무 데도 못 간다**
    //   (390 실측: 9칸에서 손잡이 0개). 마지막 칸은 누를 것이 하나뿐이라 안내만으로 충분하고,
    //   잠가서 얻는 것이 없다 — 첫 칸(`any`)을 안 잠그는 것과 같은 이유다.
    if (!tut || tut.done === 'any' || tut.done === 'DONE') return null;
    const roomName = ROOM_KEYS.includes(tut.room as RoomKey) ? ROOM_NAME[tut.room as RoomKey] : '';
    const note = tut.act === 'pet' ? '지금은 아이를 쓰다듬어 볼 차례예요'
      : tut.room === 'chat' ? '지금은 말풍선을 눌러 답할 차례예요'
        : tut.room === 'info' ? '지금은 아이 정보에서 성격을 고를 차례예요'
          : roomName ? `지금은 ${roomName}에서 할 차례예요`
            // 마지막 칸(DONE)은 누를 것이 하나뿐이다 — 그 이름을 그대로 말해 준다.
            : tut.done === 'DONE' ? '지금은 「이제 시작할게요」를 눌러 주세요'
              : '지금은 위 안내를 먼저 해 볼 차례예요';
    return { room: tut.room, act: tut.act, note };
  }, [tut]);
  const tutLockRef = useRef<{ room: string | null; act: string | null; note: string } | null>(null);
  tutLockRef.current = tutLock;

  /**
   * 튜토리얼을 끝까지 마친 순간. 2층 해금과 **같은 전면 판**으로 한 번만 축하한다.
   *
   * ★ 정본 v1.2(2026-09-07) — **구르기 = 첫날 튜토리얼 완주 보상**으로 앞당겨졌다.
   *   (3층 첫 심화 행동 선물은 '뒤로 넘어짐' 하나로 줄었다)
   *
   * ★★ 2026-09-20 — 이 판이 **사실과 다른 말을 세 가지** 하고 있어서 고쳤다. 무엇이 왜
   *   거짓이었는지, 그리고 아직 안 붙인 자리 둘(구르기 미리보기 · 자유 입력)이 무엇인지는
   *   문구를 쥔 `constants.ts` 의 `GRAD_COPY` 머리말에 적어 두었다.
   *   요지만 — (1) 구르기는 이 순간 굽기가 시작될 뿐이라 앨범에 아직 없다,
   *   (2) 밤에 새 동작을 연습하지 않는다(조건을 채운 순간 굽는 모델 + 3층 목록이 비어 있다),
   *   (3) "구르기 저장하기" 는 저장할 그림이 없는데 저장했다고 말했다 → 버튼을 없앴다.
   *   그림이 실제로 도착한 뒤의 저장·공유는 이미 앨범 액자가 하고 있다(`Album.tsx` 의 frame-save).
   *
   * ★ 쓰지 않는 말 — "꼭 오세요"·"기다릴게요"·"안 오면 서운해요". 초대이지 숙제가 아니고,
   *   아이가 사용자를 원망하는 말은 자캐 커뮤니티에서 가장 싫어하는 결이다.
   * ★ 지킬 수 없는 약속(날짜·시각·"매일 하나씩")도 안 쓴다 — 굽기는 실패할 수 있고,
   *   그때 화면은 "아직 연습 중이에요" 다.
   */
  /**
   * 판에 적을 아이 이름. **서버가 아는 이름이 먼저다.**
   *
   * ★★ 2026-09-22 dev 실측 — 축하 판이 **"아이도 구르기를…"** 로 떴다. 머리줄에는 "초코" 가
   *   제대로 있는데 판만 그랬다. 까닭은 경주다: 이 판을 여는 효과(아래 `clockStartedAt` 효과)가
   *   **서버 이름을 목 칸에 옮겨 적는 효과(`skins/Yeoul.tsx`)보다 한 틱 먼저** 돌고, 판은 한 번
   *   만들어지면 그 문자열을 그대로 쥐고 있어서다. 그래서 목 칸(`v.petName`)을 보면 안 되고
   *   **그 순간 이미 손에 있는 서버 값**을 본다. 한 화면에 이름이 둘로 갈리는 일이 없어진다.
   */
  const petNameFor = (v: YeoulState) => (onServer ? sv?.name : '') || v.petName || '아이';

  const finishTutor = (v: YeoulState): YeoulState => ({
    ...v, tutor: 0, tutorOn: false,
    ...(v.tutorDone ? {} : {
      tutorDone: true, rollUnlocked: true,
      // 자유 입력칸은 이 판에서 처음 열린다 — 앞 판의 글·오류가 남아 있으면 안 된다(→ `withFire`).
      wishDraft: '', wishError: '', wishSending: false, wishDone: false,
      fire: {
        title: GRAD_COPY.title,
        body: GRAD_COPY.body(petNameFor(v)),
        // ★ 그림이 게시되기 전에는 `GRAD_PREVIEW_SRC` 가 비어 있어 이 칸이 아예 안 생긴다.
        //   주소가 정해지면 상수 한 줄만 채우면 되고, 여기도 화면도 안 고친다.
        ...(GRAD_PREVIEW_SRC
          ? { preview: { src: GRAD_PREVIEW_SRC, badge: GRAD_COPY.previewBadge, caption: GRAD_COPY.previewCaption } }
          : {}),
        hint: '', tapAny: true,
        // 정본이 선물 화면에 붙이라고 한 수요조사. **한 번 누르는 버튼이 아니라 자유 글**이다 —
        // 우리가 알고 싶은 것은 "더 원한다" 가 아니라 **목록에 없는 동작이 무엇인가** 라서,
        // 정해진 값만 받으면 그 질문에 영영 답할 수 없다.
        wish: true, wishFrom: 'tutorial_gift',
        actions: [
          { label: GRAD_COPY.close, action: 'grad-close', tap: () => setS((w) => ({ ...w, fire: null })), primary: false },
        ],
      },
    }),
  });

  /**
   * 목(서버 없는 진짜 방)에서 한 칸을 넘긴다.
   *
   * ★ 서버에 붙어 있으면 칸은 **서버가 넘긴다.** 화면이 같이 세면 두 곳에서 판정하게 되고,
   *   언젠가 갈리며 갈린 쪽은 아무 소리도 안 낸다(계약 5절).
   * ★ 넘기는 열쇠말(`done`)은 **서버 것과 같은 낱말**이다(`FEED`·`PET`·`CHAT`…) — 목록이 한 벌이니
   *   열쇠말도 한 벌이어야 한다. 마지막 칸(`DONE`)은 눌러서 끝내므로 여기서 넘기지 않는다.
   */
  const tutorDone = useCallback((what: string) => {
    if (onServerRef.current) return;
    setS((v) => {
      if (!v.tutorOn || v.sampleMode) return v;
      const st = TUTOR_ROOM[v.tutor];
      if (!st || st.done !== what) return v;
      const next = v.tutor + 1;
      return next >= TUTOR_ROOM.length ? finishTutor(v) : { ...v, tutor: next };
    });
  }, []);
  /** 마지막 칸의 「이제 시작할게요」 — **목 전용**(서버는 `onFinishTutorial` 이 시계를 켠다). */
  const finishTutorHere = useCallback(() => setS((v) => finishTutor(v)), []);
  const skipTutorStep = useCallback(() => {
    setS((v) => {
      const next = v.tutor + 1;
      if (next < TUT.length) return { ...v, tutor: next };
      return v.sampleMode ? { ...v, tutor: 0, tutorOn: false } : finishTutor(v);
    });
  }, [TUT.length]);
  const nextTutor = useCallback(() => setS((v) => ({ ...v, tutor: v.tutor + 1, hatch: realRef.current ? v.hatch : Math.min(4, v.hatch + 1) })), []);
  const prevTutor = useCallback(() => setS((v) => ({ ...v, tutor: Math.max(0, v.tutor - 1) })), []);
  const startTutor = useCallback(() => patch({ screen: 'room', sampleMode: false, tutorOn: true, tutor: 0, sheet: null, popOpen: false, chatOpen: false }), [patch]);
  /**
   * 튜토리얼만 끈다. ★ 예전에는 여기서 `sampleMode: false` 까지 내렸다 —
   * 그래서 「튜토리얼 끝」한 번에 **연습방에서 튕겨 나가고** 자세·상황 칸이 통째로 사라졌다
   * (상훈님 "튜토리얼 끝 누르고 자는 중은 또 안눌러지고"의 첫 번째 까닭). 방은 안 건드린다.
   */
  const endTutor = useCallback(() => patch({ screen: 'room', tutorOn: false, tutor: 0, sheet: null }), [patch]);

  // ── 열고 닫기 ──
  const closePop = useCallback(() => {
    setS((v) => {
      if (v.chatOpen) {
        later('chatClose', 170, () => setS((w) => ({ ...w, chatOpen: false, chatClosing: false, draft: '' })));
        return { ...v, chatClosing: true };
      }
      if (v.popOpen && !v.popClosing) {
        later('popClose', 170, () => setS((w) => ({ ...w, popOpen: false, popClosing: false })));
        return { ...v, popClosing: true };
      }
      return v;
    });
  }, [later]);
  const bottomTap = useCallback(() => {
    if (Date.now() - lastSel.current < 150) return;
    closePop();
  }, [closePop]);
  const selRoom = useCallback((k: RoomKey) => () => {
    lastSel.current = Date.now();
    setS((v) => {
      if (v.chatOpen) return { ...v, roomSel: k, chatOpen: false, chatClosing: false, draft: '', popOpen: true, popClosing: false, toast: '' };
      if (v.popOpen && v.roomSel === k) {
        later('popClose', 170, () => setS((w) => ({ ...w, popOpen: false, popClosing: false })));
        return { ...v, popClosing: true };
      }
      return { ...v, roomSel: k, popOpen: true, popClosing: false, toast: '' };
    });
  }, [later]);

  const openSheet = useCallback((k: SheetKey) => () => {
    if (esRef.current.sleeping) { flash(sleepingLine(s.petName)); return; }
    if (esRef.current.sick && k === 'play') { flash('아플 땐 못 놀아요'); return; }
    setS((v) => ({ ...v, sheet: k, resolved: { ...v.resolved, [k]: true }, decoOpen: false }));
  }, [s.petName, flash]);
  const closeSheet = useCallback(() => {
    setS((v) => {
      if (!v.sheet || v.sheetClosing) return v;
      later('sheetClose', 210, () => setS((w) => ({ ...w, sheet: null, sheetClosing: false, decoOpen: false })));
      return { ...v, sheetClosing: true };
    });
  }, [later]);

  const openWall = useCallback(() => {
    lastSel.current = Date.now();
    // 벽을 열 때 도감을 다시 읽는다 — 그사이 밤에 배운 것이 도착해 있을 수 있다.
    if (onServerRef.current) void liveRef.current?.loadAlbum();
    tutorDone('SHARE');
    patch({ wallOpen: true, wallClosing: false, popOpen: false, sheet: null, chatOpen: false, toast: '' });
  }, [patch, tutorDone]);
  const closeWall = useCallback(() => {
    patch({ wallClosing: true });
    later('wallClose', 230, () => setS((v) => ({ ...v, wallOpen: false, wallClosing: false, frame: null })));
  }, [patch, later]);
  const pickFrame = useCallback((f: FrameData) => () => patch({ frame: f, frameClosing: false }), [patch]);
  const closeFrame = useCallback(() => {
    patch({ frameClosing: true });
    later('frameClose', 180, () => setS((v) => ({ ...v, frame: null, frameClosing: false })));
  }, [patch, later]);

  const closeFire = useCallback(() => patch({ fire: null }), [patch]);
  // ★ 대화를 **열 때** 지난 판의 내 말을 비운다 — 내 말이 안 사라지게 바꿨으므로(→ `pushReply`)
  //   비우는 자리를 한 곳으로 옮긴 것이다. 한 판 안에서는 주고받은 두 줄이 그대로 남는다.
  const openChat = useCallback(() => {
    // ★ 튜토리얼이 대화 칸을 가리킬 때만 열린다(판정 J8).
    const tl = tutLockRef.current;
    if (tl && tl.room !== 'chat') { flash(tl.note); return; }
    lastSel.current = Date.now();
    patch({ chatOpen: true, popOpen: false, toast: '', mine: '' });
  }, [patch, flash]);
  const closeChat = useCallback(() => {
    lastSel.current = Date.now();
    patch({ chatClosing: true });
    later('chatClose', 170, () => setS((v) => ({ ...v, chatOpen: false, chatClosing: false, draft: '' })));
  }, [patch, later]);

  // ── 돌보기 ──
  //
  // 판단(되는지 안 되는지)은 **여기 바깥**에서 하고, setS 안에서는 값만 바꾼다.
  // setS 콜백은 React 가 두 번 부를 수 있어서 그 안에서 알림을 띄우면 두 번 뜬다.
  //
  // ★ 서버에 붙어 있으면(`onServerRef`) **화면은 값을 하나도 안 만진다**(계약 10절).
  //   눌러 → 서버가 정하고 → 응답으로 온 상태가 곧 다음 화면이다. 되감기는 없다.
  //   연출(먹는 자세·하트)은 응답이 온 뒤에 시작한다 — 되돌릴 일이 없어진다.
  //   눌린 반응은 즉시 준다: 누르는 순간 버튼이 잠기고(`live.careing`) 흐려진다.
  /**
   * 돌보기 한 번을 서버에 맡긴다.
   * @param motion 성공했을 때 연출할 **행동**(자세·소품은 상황표가 정한다). 거절이면 아무것도 안 짓는다.
   */
  const serverCare = useCallback(async (action: CareAction, motion: ActionKey, ok: string) => {
    const r = await liveRef.current?.doCare(action);
    // 잠겨서 안 보낸 경우(`ok:false · message:null`)는 **아무 말도 안 한다** — 잠긴 버튼이 이미 말한다.
    if (!r || !r.ok) { if (r?.message) flash(r.message); return; }
    careAct(motion);
    flash(ok);
  }, [flash, careAct]);

  /**
   * 약을 먹인 뒤 **나음**(A절 표 "해금 · 나음 · 공유" 줄) — 약 연출 한 바퀴가 끝나면
   * 기쁨 + 반짝임 한 바퀴가 이어진다. 표가 두 줄로 적어 둔 것을 순서대로 튼다.
   */
  const cureAfterMed = useCallback(() => {
    later('cured', CYCLE_MS, () => careAct('cured'));
  }, [later, careAct]);

  const onPet = useCallback(() => {
    // ★ 튜토리얼이 쓰다듬기 칸이 아닐 때는 잠근다(판정 J8) — 한 줄만 말하고 아무 일도 안 한다.
    const tl = tutLockRef.current;
    if (tl && tl.act !== 'pet') { flash(tl.note); return; }
    // ★★ 떠 있는 창은 닫되 **탭을 삼키지 않는다**(2026-09-21 판정 8). 예전에는 여기서 그냥
    //   돌아서서, 팝오버를 열어 둔 채 아이를 누르면 창만 닫히고 아무 일도 안 났다 —
    //   "한 번은 그냥 없어지는 탭" 이라는 규칙은 사용자가 세울 수 없는 규칙이다.
    //   닫는 일은 그대로 하고(바깥을 눌렀으니 맞다), 쓰다듬기도 **같이** 한다.
    if (s.chatOpen) patch({ chatOpen: false, draft: '' });
    else if (s.popOpen) patch({ popOpen: false });
    if (esRef.current.sleeping) { flash('자고 있어요'); return; }
    if (onServerRef.current) {
      // ★ 4회째부터 — **반응 동작만 나온다.** 하트도 안 뜨고, 문구도 안 뜬다(상훈님 2026-09-10 판정).
      //   하루 세 번이 지났다고 아이를 못 만지게 하면 그건 잠금이 아니라 벌이다. 그래서 막지 않고,
      //   대신 "세어졌다" 는 표시(하트)만 거둔다 — 말로 설명할 것도 없다.
      //   서버 규칙도 같은 결이다(`PetService.doCare`: "쓰다듬기는 거절이 없다 — 하루 3회를 넘어도
      //   반응 동작은 나온다. 친밀도만 안 오른다").
      // 4회째부터는 서버를 안 부른다. 누적 `pets` 를 쓰는 해금이 생기면 여기를 되살릴 것
      // (2026-09-10 확인: `ZzalPet.pet()` 이 올리는 평생 누적 `pets` 를 읽는 곳이 아직 아무 데도 없다).
      if (esRef.current.pets >= PET_MAX) { careAct('pet'); return; }
      // 돌보기가 도는 중엔 아무 일도 안 한다 — 같은 요청이 두 번 나가지 않게(계약 10절).
      if (liveRef.current?.careing) return;
      // 쓰다듬기도 돌보기 하나다(`PET`). 하트는 서버가 세어 준 오늘 횟수로 판단한다.
      void (async () => {
        const r = await liveRef.current?.doCare('PET');
        if (!r || !r.ok) { if (r?.message) flash(r.message); return; }
        careAct('pet');
        patch({ hearts: true });
        later('hearts', 1100, () => setS((w) => ({ ...w, hearts: false })));
      })();
      return;
    }
    const counted = s.sampleMode || s.pets < 3;
    patch({
      pets: Math.min(3, s.pets + 1), hearts: counted,
      bond: counted ? Math.min(100, s.bond + 1) : s.bond,
    });
    careAct('pet');
    // 4회째부터는 하트를 안 띄운다(위 서버 경로와 같은 규칙). 목 화면도 같은 결이어야
    // 시안을 눌러 본 것과 실제가 어긋나지 않는다.
    if (counted) later('hearts', 1100, () => setS((w) => ({ ...w, hearts: false })));
    tutorDone('PET');
  }, [s.chatOpen, s.popOpen, s.sampleMode, s.pets, s.bond, patch, careAct, flash, later, tutorDone]);

  const onRice = useCallback(() => {
    if (s.sampleMode) {
      patch({ full: Math.min(4, s.full + 1), bond: Math.min(100, s.bond + 1) });
      careAct('feed_rice');
      flash('맛있게 먹었어요');
      return;
    }
    if (onServerRef.current) { void serverCare('FEED', 'feed_rice', '맛있게 먹었어요'); return; }
    // ★ 잠금 판정은 **화면이 실제로 쓰는 값**(`es`)으로 한다. 개발 덮개로 배부름을 0 으로
    //   눌러 두었으면 밥이 눌려야 한다 — `s` 를 보면 덮개가 무시되어 "눌러도 아무 일이 없다" 가 된다.
    if (esRef.current.full >= 4) { flash('배가 가득이라 거절했어요'); return; }
    if (esRef.current.stock <= 0) { flash('밥 재고가 없어요'); return; }
    patch({ full: esRef.current.full + 1, stock: esRef.current.stock - 1, bond: Math.min(100, s.bond + 1) });
    careAct('feed_rice');
    flash('맛있게 먹었어요');
    tutorDone('FEED');
  }, [s.sampleMode, s.full, s.stock, s.bond, patch, careAct, flash, tutorDone, serverCare]);

  const onSnack = useCallback(() => {
    if (onServerRef.current) {
      void serverCare('SNACK', 'feed_snack', esRef.current.snacks >= SNACK_MAX - 1 ? '조금 많아요' : '간식은 언제나 좋아요');
      return;
    }
    const n = s.snacks + 1;
    patch({ snacks: n, full: Math.min(4, s.full + 1) });
    careAct('feed_snack');
    flash(n >= 4 ? '조금 많아요' : '간식은 언제나 좋아요');
  }, [s.snacks, s.full, patch, careAct, flash, serverCare]);

  const onClean = useCallback(() => {
    // 청소는 v4 에서 `sweep` 이다. 아직 그 그림이 없으면 별칭이 옛 `wash` 로 받쳐 준다(constants.MOTION_ALIAS).
    if (onServerRef.current) { void serverCare('CLEAN', 'clean', '깨끗해졌어요'); return; }
    if (esRef.current.trace <= 0 && !s.sampleMode) { flash('이미 깨끗해요'); return; }
    patch({ trace: 0 });
    careAct('clean');
    flash('깨끗해졌어요');
    tutorDone('CLEAN');
  }, [s.trace, s.sampleMode, patch, careAct, flash, tutorDone, serverCare]);

  const onBath = useCallback(() => {
    if (onServerRef.current) { void serverCare('BATH', 'bath', '반짝반짝해졌어요'); return; }
    if (esRef.current.bathUsed && !s.sampleMode) { flash('오늘 목욕은 했어요'); return; }
    patch({ bathUsed: true, trace: 0, bond: Math.min(100, s.bond + 2), cBath: s.cBath + 1 });
    careAct('bath');
    flash('반짝반짝해졌어요');
  }, [s.bathUsed, s.sampleMode, s.bond, s.cBath, patch, careAct, flash, serverCare]);

  const onMed = useCallback(() => {
    lastSel.current = Date.now();
    if (onServerRef.current) {
      // ★ 약 단추는 **아플 때만 그려진다**(`v.medFab.show`) — 그것이 "안 아픔" 거절의 미리 잠금이다.
      //   여기서는 도는 중 연타만 막는다.
      if (liveRef.current?.careing) return;
      void (async () => {
        await serverCare('MEDICINE', 'medicine', '바로 나았어요');
        cureAfterMed();
      })();
      return;
    }
    if (!esRef.current.sick) { flash('지금은 약이 필요 없어요'); return; }
    patch({ sick: false });
    careAct('medicine');
    cureAfterMed();
    flash('바로 나았어요');
  }, [s.sick, patch, careAct, flash, serverCare, cureAfterMed]);

  /**
   * 성격·세계관을 서버에 저장한다. **튜토리얼 4칸을 넘기는 자리**이기도 하다.
   * 성격을 안 고르면 보낼 것이 없다 — 서버가 성격을 필수로 받는다.
   *
   * ★ 화면에서는 성격을 여러 개 고를 수 있지만 **서버는 하나만 받는다**
   *   (`PersonalityChoice.personality` 는 enum 하나 · `@NotNull`). 그래서 **맨 앞(처음 고른 것)**
   *   만 보내고 나머지는 화면에만 남긴다. 서버가 여러 개를 받게 되면 여기만 고치면 된다.
   */
  const onSavePersona = useCallback(() => {
    // 화면에 켜져 보이는 것과 **같은 기준**이다. 아무것도 안 건드렸으면 서버가 아는 성격을 그대로
    // 다시 보낸다 — 4칸은 "고쳤는가" 가 아니라 "확인했는가" 를 세는 칸이라 그래야 넘어간다.
    const svName = PERSONA_LABEL[liveRef.current?.pet?.personality ?? ''] ?? '';
    const chosen = sRef.current.picks.persona ?? (svName ? [svName] : []);
    const persona = PERSONALITY_OF[chosen[0] ?? ''];
    if (!persona) { flash('성격을 하나 이상 골라 주세요'); return; }
    // 다시 눌렀다 — 지난 실패 줄은 지우고 시작한다.
    patch({ saveErr: '' });
    // ★ 목(서버 없는 진짜 방)에서도 **이 칸을 밟을 수 있어야** 한다(2026-09-22) — 튜토리얼이
    //   서버와 같은 아홉 칸이 되었으니 4칸도 같이 넘어가야 한다. 보낼 곳이 없으므로 화면 상태에만
    //   남기고 칸을 넘긴다. 연습방은 여전히 저장하지 않는다(거긴 연습이다).
    if (!onServerRef.current) {
      patch({ sheet: null });
      flash(sRef.current.sampleMode ? '연습방이라 저장되지 않아요' : '기억해 뒀어요');
      if (!sRef.current.sampleMode) tutorDone('PERSONALITY');
      return;
    }
    // 세계관은 칩 여러 개 + 직접 적은 한 줄을 **서버 한 칸에** 이어 붙인다.
    // ★★ 자르는 길이는 `CHAR_TEXT_MAX.world` 한 곳에서만 온다(`lib/pet.ts`, 계약 옆).
    //   2026-09-22 — 여기 `100` 이 박혀 있어, 한도를 200 으로 열어도 **방(아이 정보)에서 저장할 때
    //   말없이 100 에서 잘렸다.** 온보딩 쪽 같은 계산과 **두 벌**이던 자리이기도 하다.
    const world = [...(sRef.current.picks.world ?? []), (sRef.current.texts.world ?? '').trim()]
      .filter(Boolean).join(' · ').slice(0, CHAR_TEXT_MAX.world);
    void (async () => {
      const r = await liveRef.current?.savePersonality(persona, world || undefined);
      // ★ 거절이면 **시트 안에** 남긴다(2026-09-22 판정 6). 토스트는 시트 밑에 깔려 안 보인다 —
      //   실제로 그래서 400 거절이 화면에 한 마디도 안 나왔다. 시트도 안 닫고, 적은 글도 안 지운다.
      if (!r || !r.ok) { patch({ saveErr: saveFailLine(r?.code ?? null, r?.status, r?.message ?? null) }); return; }
      patch({ sheet: null, saveErr: '' });
      flash('기억해 뒀어요');
    })();
  }, [flash, patch, tutorDone]);

  /** 튜토리얼 마지막 칸 — 이 호출이 시계를 켠다. */
  const onFinishTutorial = useCallback(() => {
    void (async () => {
      const r = await liveRef.current?.finishTutorial();
      if (!r || !r.ok) { if (r?.message) flash(r.message); return; }
    })();
  }, [flash]);

  const onSleep = useCallback(() => {
    if (onServerRef.current) {
      // ★ 재우기·깨우기가 **서버로 나간다**(2026-09-10). 전에는 목 상태만 바꿔서, 진짜 아이는
      //   재워도 서버가 몰랐고 그 탓에 **튜토리얼 8칸이 영영 안 넘어갔다.**
      //   되는지 안 되는지는 서버가 정한다(`clock.canSleep`·`canWake`) — 침실 버튼이 그 값으로 잠긴다.
      if (liveRef.current?.resting) return;
      const wasAsleep = esRef.current.sleeping;
      void (async () => {
        const r = await liveRef.current?.doRest();
        if (!r || !r.ok) { if (r?.message) flash(r.message); return; }
        patch({ sheet: null });
        // 서버가 재우고/깨웠으니 잠 덮개를 걷는다 — 안 걷으면 서버가 바꾼 것이 화면에 안 보인다.
        setS((v) => ({ ...v, dev: { ...v.dev, sleeping: null } }));
        // ★ 재우기는 **상태**라 행동이 아니다(자는 동안 계속이므로 `sleeping` 이 맡는다).
        //   깨우기만 잠깐 하는 행동이라 표의 `wake_by_hand` 를 켠다 — 그 줄은 `prop: null`,
        //   즉 **표가 "이 상황에는 소품이 없다" 고 확정한 자리**다(커튼이 걷히는 것이 신호).
        if (wasAsleep) careAct('wake');
        flash(wasAsleep ? '잘 잤어요' : '잘 자요');
      })();
      return;
    }
    if (esRef.current.sleeping) {
      // ★ 손으로 깨운 것만 센다 — 2층 '일어나기' 조건(정본 §6 16번). 이 자리가 바로 그 손이다.
      patch({ sleeping: false, night: false, pets: 0, bathUsed: false, plays: 3, day: s.day + 1, sheet: null, cWake: s.cWake + 1 });
      careAct('wake');
      flash('잘 잤어요');
      return;
    }
    // ★ 연습방은 **시각을 안 본다**(상훈님 2026-09-13). 시연·검수용인데 저녁 7시를 기다려야 하면
    //   낮에는 자는 자세와 커튼을 확인할 길이 없다. 진짜 방(서버 경로)은 위에서 이미 갈라져 나갔고
    //   거기는 `clock.canSleep` 이 정본 규칙(19:00~23:00 재우기 · 23:00 자동)을 그대로 든다.
    // ★★ **튜토리얼은 시계와 논외다**(정본 1.4·1.5 · §16). 8칸이 "재우고 다시 깨워 주세요" 인데
    //   낮에 막히면 목의 진짜 방 튜토리얼은 저녁 7시까지 **거기서 멎는다**(2026-09-22 실측:
    //   8/9 에서 더 못 감). 서버도 튜토리얼 낮잠을 허용한다(그 낮잠이 재우기·깨우기 2회로 잡히는
    //   것이 의도라고 정본이 못 박았다). 튜토리얼이 끝나면 그때부터 19:00 규칙이 산다.
    if (!esRef.current.night && !s.sampleMode && !s.tutorOn) { flash('저녁 7시부터 재울 수 있어요'); return; }
    // 재우기는 행동이 아니라 상태라 `careAct` 를 안 탄다 — 잠 덮개는 여기서 직접 맞춘다.
    setS((v) => ({ ...v, dev: { ...v.dev, sleeping: null } }));
    patch({ sleeping: true, sheet: null, resolved: { ...s.resolved, bed: true }, cSleep: s.cSleep + 1 });
    flash('잘 자요');
    tutorDone('NAP');
  }, [s.sleeping, s.night, s.sampleMode, s.tutorOn, s.day, s.resolved, s.cSleep, s.cWake, patch, flash, careAct, tutorDone]);

  /**
   * 좌우 맞히기 **한 매치**(2026-09-20 재설계 · 안 1). 시트를 없애고 무대 위에서 아이와 마주 본다.
   *
   * ★ **게임 규칙은 정본 §7 그대로다** — 5판 3승 · 하루 3판 · 답은 서버가 쥔다. 바꾼 것은
   *   *어디서·어떻게 보이나* 뿐이다. 목(연습방)에도 같은 규칙의 카운터를 넣었다(예전엔 없었다 —
   *   판을 쳐도 "오늘 남은 판 3" 이 그대로였다).
   * ★ 한 판의 박자: 대기(주먹 둘) → 탭 → 섞기 → 공개(손·얼굴 **동시**) → 여운 → **자동으로 다음 판**.
   *   예전엔 결과가 난 뒤 화면이 **영구 정지**했고(18.4초 재어 확인) 두 손도 안 돌아왔다.
   * ★ 공개를 **0초로 당긴다** — 1층은 `game_choose` 가 표에서 `null` 이라 기다리는 한 바퀴가
   *   빈 시간이었다(결과와 아이 반응이 1.95초 어긋났다). 2층은 그 한 바퀴에 `놀람` 이 실제로
   *   돌므로 예전처럼 한 바퀴 뒤에 결과를 붙인다.
   */
  const startGuess = useCallback(() => {
    lastSel.current = Date.now();
    const happy0 = esRef.current.happy;
    // ★ 목의 6칸(게임)은 **넘어가는 길이 아예 없었다**(2026-09-22 발견) — 옛 8칸 목록에도 이 칸이
    //   있었는데 아무도 `tutorDone` 을 안 불러서, 서버 없는 진짜 방 튜토리얼은 거기서 멎었다.
    //   서버는 **판을 시작한 순간** 이 칸을 넘긴다(기권해도 넘어간다 — dev 실측). 목도 같게.
    tutorDone('GAME');
    // 하루 판수는 **매치 단위**로 준다(정본: 판 = 한 매치). 예전 목은 한 판(라운드)마다 깎았다.
    setS((v) => (v.gOn ? v : {
      ...v,
      gOn: true, gPhase: 'wait', gRound: 0, gHits: 0, gPick: null, gHit: null,
      gMarks: [null, null, null, null, null], gQuit: false, gHappy0: happy0, gStarted: false,
      lastGuess: null,
      // 시작하면 팝오버를 내린다 — 예전엔 안 내려서 방으로 돌아가는 데 2탭이 들었다.
      popOpen: false, popClosing: false, sheet: null, toast: '',
      // ★★ **여기서 깎지 않는다**(2026-09-22 판정 2). 서버는 **첫 탭**에서야 판을 만들고
      //   그때 하루 판수를 쓴다. 판을 열자마자 깎으면 한 판도 안 치고 ✕ 해도 「3판 남음」이
      //   2 가 되어, 목이 서버와 다른 말을 한다(실측). 목의 차감은 `onGuess` 첫 탭에 있다.
    }));
  }, [tutorDone]);

  /** 매치를 접고 마당 팝오버를 다시 연다 — 거기 "좌우 맞히기" 가 곧 "한 판 더" 다. */
  const endGuess = useCallback(() => {
    for (const k of ['guessAct', 'guessReveal', 'guessNext', 'guessEnd']) clearTimeout(T.current[k]);
    // ★ 매치가 끝나도 **팝오버를 자동으로 열지 않는다**(2026-09-21 판정 12). 예전엔 결과를
    //   읽기도 전에 「기분 N/4」 게이지가 먼저 덮어, 맞힌 횟수가 곧 기분인 것처럼 읽혔다.
    //   한 판 더 치려면 마당 타일을 누르면 된다 — 고른 방은 그대로 마당으로 둔다.
    setS((v) => ({ ...v, gOn: false, gPhase: 'wait', gPick: null, gHit: null, gQuit: false, gStarted: false, roomSel: 'play', popOpen: false, popClosing: false }));
  }, []);

  /**
   * 기권이 받아들여진 뒤 — **끝난 판과 똑같은 길**을 탄다(2026-09-21 판정 3).
   * 새 화면을 만들지 않는다: 끝말 한 줄(`gQuit`)이 바뀔 뿐, 여운 3.6초 뒤 `endGuess` 로 방에 돌아간다.
   * ★ 표정을 새로 짓지 않는다 — 진 연출(`game_lose`)을 붙이면 나가겠다고 누른 사람에게
   *   아이가 시무룩해 보이고, 그건 규범상 원망으로 읽힐 수 있는 자리다.
   */
  const quitEnd = useCallback(() => {
    for (const k of ['guessAct', 'guessReveal', 'guessNext', 'guessEnd']) clearTimeout(T.current[k]);
    setS((v) => ({ ...v, fire: null, gQuit: true, gPhase: 'done', gPick: null, gHit: null }));
    later('guessNext', GUESS_MATCH_END_MS, endGuess);
  }, [later, endGuess]);

  /**
   * 확인창의 「나가기」 — 서버에 기권을 보낸다.
   *
   * ★ 이름·모양은 **A·B 두 사람이 먼저 맞춰 둔 것**이다: `Live.abandonPlay(): Promise<void>` —
   *   인자가 없고(펫·판 번호는 배관이 안다), 실패는 던진다. 화면은 잡아서 문구만 고른다.
   * ★ 목(연습방)에는 서버가 없다. 같은 끝 연출만 태운다 — 규칙(판수·승패)은 서버의 몫이라
   *   화면이 목에서 따로 깎지 않는다.
   */
  const abandonGuess = useCallback(() => {
    if (!onServerRef.current) { quitEnd(); return; }
    patch({ fire: null });
    void (async () => {
      const lv = liveRef.current;
      if (!lv) { quitEnd(); return; }
      try {
        // 인자가 없다 — 펫·판 번호는 배관이 안다. 연타 자물쇠도 배관 쪽에 있다(`useHatch.abandonPlay`).
        await lv.abandonPlay();
        quitEnd();
      } catch (e) {
        flash(quitFailLine(e instanceof ApiError ? e.code : null));
        endGuess();
      }
    })();
  }, [patch, flash, endGuess, quitEnd]);

  /**
   * 게임판의 ✕ — **나가기 전에 한 번만 묻는다**(2026-09-21 판정 3).
   *
   * ★ 타일 탭은 이탈이 아니다 — 게임판을 접어도 판은 살아 있다. **✕ 만 기권이다.**
   * ★ 한 판도 안 쳤으면 **판 자체가 없다**(서버는 첫 탭에서야 판을 만든다). 차감도 기권도
   *   없으므로 묻지 않고, 서버도 부르지 않고 그냥 닫는다 — 안 일어난 일을 경고하면 거짓말이다.
   * ★ 아파도 누를 수 있다(서버가 아픔 거절을 안 만들어 두었다 → `quitFailLine` 머리말).
   */
  const quitGuess = useCallback(() => {
    const v0 = sRef.current;
    // 이미 끝난 판이면 물어볼 것이 없다 — 남은 여운만 접고 방으로 돌아간다.
    if (v0.gPhase === 'done') { endGuess(); return; }
    // ★ 서버에 판을 만들었는가는 **이번 매치 기준**이다(판정 J3) — `live.game.playing` 은
    //   새로고침으로 되살아난 옛 판까지 참으로 만든다.
    // ★ 목도 서버와 **같은 잣대**로 본다(2026-09-22) — 첫 탭에 `gStarted` 가 켜지고
    //   그때 판수도 깎이므로, "판이 있었는가" 를 한 값으로 물을 수 있다.
    const played = v0.gStarted;
    if (!played) { endGuess(); return; }
    setS((v) => ({ ...v, fire: {
      title: '지금 나가면 이 판은 져요',
      body: '오늘 세 판 중 한 판을 쓴 것이 되고, 치던 판은 진 것으로 남아요.',
      hint: '',
      actions: [
        { label: '계속 치기', action: 'guess-quit-cancel', tap: closeFire, primary: true },
        { label: '나가기', action: 'guess-quit-ok', tap: abandonGuess, primary: false },
      ],
    } }));
  }, [endGuess, closeFire, abandonGuess]);

  /**
   * 한 판을 친다. 대기 중일 때만 받는다(섞기·공개·여운 동안은 입력 잠금).
   *
   * ★ 서버에 붙어 있으면 **답은 서버가 쥔다**(`lib/game.ts` 머리말). 목일 때는 반반이다.
   */
  const onGuess = useCallback((side: Side = 'LEFT') => {
    if (!sRef.current.gOn || sRef.current.gPhase !== 'wait') return;
    // 섞기 — 양손은 주먹 그대로, 입력만 잠근다. 서버 왕복이 더 길면 그쪽이 곧 섞기다.
    patch({ gPhase: 'shuffle', gPick: side });
    careAct('game_choose');
    const pickedAt = Date.now();
    /** 공개까지의 시간. **2층만** 한 바퀴를 기다린다 — 그 바퀴에 `놀람` 이 실제로 도니까. */
    const revealAfter = () => {
      const span = floor2Of('game_choose') ? CYCLE_MS : GUESS_SHUFFLE_MS;
      return Math.max(0, span - (Date.now() - pickedAt));
    };

    /** 공개 → 여운 → 다음 판(또는 매치 끝). 목·서버가 같은 길을 쓴다. */
    const reveal = (hit: boolean, hits: number, finished: boolean, win: boolean | null) => {
      later('guessReveal', revealAfter(), () => {
        setS((v) => {
          const marks = v.gMarks.slice();
          marks[v.gRound] = hit;
          return { ...v, gPhase: 'reveal', gHit: hit, gHits: hits, gMarks: marks };
        });
        careAct(hit ? 'game_win' : 'game_lose');
        if (finished) {
          later('guessEnd', GUESS_AFTERGLOW_MS, () => {
            setS((v) => ({ ...v, gPhase: 'done' }));
            later('guessNext', GUESS_MATCH_END_MS, endGuess);
          });
          return;
        }
        // ★ 여운이 끝나면 **누르지 않아도** 다음 판이 시작되고 두 손이 주먹으로 돌아온다.
        later('guessNext', GUESS_AFTERGLOW_MS, () => setS((v) => ({
          ...v, gPhase: 'wait', gPick: null, gHit: null, gRound: Math.min(v.gRound + 1, 4),
        })));
      });
    };

    if (onServerRef.current) {
      void (async () => {
        const lv = liveRef.current;
        if (!lv) { patch({ gPhase: 'wait', gPick: null }); return; }
        // ★★ **이어 치기는 없다**(2026-09-22 판정 J3 — 상훈님 "게임은 중간에 나가면 끝이야").
        //   예전에는 `lv.game?.playing` 을 보고 **서버에 살아 있는 옛 판을 그대로 이어받았다** —
        //   새로고침·재접속이 곧 재개였다. 이제 **이번 매치에서 내가 시작했는가**(`gStarted`)만 본다.
        //   판을 만드는 시점은 그대로 **첫 탭**이다(한 번도 안 치고 나가면 차감이 없어야 하므로).
        if (!sRef.current.gStarted) {
          const err = await lv.startPlay();
          if (err) { flash(err); patch({ gPhase: 'wait', gPick: null }); return; }
          patch({ lastGuess: null, gStarted: true });
        }
        const { error, result } = await lv.pickSide(side);
        if (error) { flash(error); patch({ gPhase: 'wait', gPick: null }); return; }
        if (!result) { patch({ gPhase: 'wait', gPick: null }); return; }
        patch({ lastGuess: result });
        reveal(result.hit, result.hits, result.finished, result.win);
      })();
      return;
    }

    // ── 목(연습방·진짜 방 목) — 규칙은 서버와 **같은 값**으로 센다(5판 3승) ──
    const v0 = sRef.current;
    // ★ 하루 판수는 **첫 탭에 깎는다** — 서버가 판을 만드는 시점과 같다(판정 2).
    //   연습방은 안 깎는다(거긴 판수가 없는 연습 자리다). 서버 방은 서버 값을 그대로 보여 준다.
    if (!v0.gStarted) {
      patch({ gStarted: true, plays: v0.sampleMode ? v0.plays : Math.max(0, v0.plays - 1) });
    }
    const hit = Math.random() < 0.5;
    const hits = v0.gHits + (hit ? 1 : 0);
    const misses = v0.gRound + 1 - hits;
    const finished = hits >= GUESS_WIN_AT || misses > GUESS_ROUNDS - GUESS_WIN_AT;
    const win = finished ? hits >= GUESS_WIN_AT : null;
    // ★ 이긴 매치의 보상은 **기분 +1 하나뿐**이다(정본 §7). 친밀도는 안 올린다 —
    //   정본 8장 친밀도 목록에 게임 승리가 없는데 목만 몰래 올리고 있었다(2026-09-21 판정 12).
    // ★ 게임 카운터는 **매치를 끝까지 쳤을 때만** 올린다(2026-09-22 계약 — "완주 4매치").
    //   예전에는 한 판(라운드)마다 올려서, 안내판의 "게임 …" 숫자가 실제 매치 수와 달랐다.
    patch({
      cGame: finished ? v0.cGame + 1 : v0.cGame,
      happy: finished && win ? Math.min(4, v0.happy + 1) : v0.happy,
    });
    reveal(hit, hits, finished, win);
  }, [patch, careAct, flash, later, endGuess, floor2Of]);
  const onGuessSide = useCallback((side: Side) => () => onGuess(side), [onGuess]);

  // ── 대화 ──
  const pushReply = useCallback((text: string) => {
    if (!text) return;
    if (onServerRef.current) {
      // ★ 보내면 잠그고 기다린다(계약 10절). 아이가 돌려주는 말도, 그때 짓는 자세도 서버가 정한다.
      //   화면이 미리 답을 띄우지 않는다 — 그러면 서버 말이 왔을 때 두 번 말한 꼴이 된다.
      // ★ 내 말은 **안 사라진다**(2026-09-20). 예전엔 4.2초 뒤 지워서, 아이 답이 오는 사이에
      //   내가 뭐라고 했는지가 화면에서 없어졌다. 다음 부름을 열 때(`openChat`) 비운다.
      patch({ draft: '', mine: text });
      void (async () => {
        const r = await liveRef.current?.sendChat(text);
        if (!r) return;
        if (r.error) { flash(r.error); return; }
        // ★ 자세는 **서버가 정한다**. 그 자세가 '답하기' 일 때만 표의 `reply_done` 을 같이 켠다 —
        //   서버가 다른 자세(기쁨·놀람…)를 골랐는데 답하기 상황을 켜면 자세가 덮여 서버 뜻이 사라진다.
        // ★★ **안전망**(2026-09-22) — 이 길은 `careAct` 를 안 거쳐서 잠금 대체(`LOCKED_POSE`)가
        //   안 걸린다. 그래서 **아직 못 배운 답하기 자세를 서버가 보내오면 그대로 재생됐다.**
        //   서버도 고치는 중이지만(잠겼으면 `hello` 를 준다) **두 겹으로 막는다** — 한쪽이 빠져도
        //   화면에 못 배운 몸짓이 새지 않게. 서버가 고쳐져도 이 줄은 남겨 둘 것.
        if (r.reply?.reactionKey) {
          const key = r.reply.reactionKey;
          const isReply = motionAliases(key)[0] === 'reply';
          const locked2 = isReply && !floor2Of('reply');
          if (locked2) act(LOCKED_POSE.reply ?? 'hello', null);
          else act(key, isReply ? sitOf('reply') : null);
        }
      })();
      return;
    }
    setS((v) => {
      const reply = CHAT_REPLY[v.log.length % CHAT_REPLY.length];
      return {
        ...v,
        log: [...v.log, { who: 'me' as const, text }, { who: 'pet' as const, text: reply }].slice(-6),
        draft: '', mine: text, petLine: reply,
        calls: Math.max(0, v.calls - 1),
        resolved: { ...v.resolved, chat: true },
        bond: Math.min(100, v.bond + 2),
        memories: [...v.memories, text.slice(0, 8)].slice(-8),
        cChat: v.cChat + 1,
      };
    });
    careAct('reply');
    tutorDone('CHAT');
  }, [act, careAct, tutorDone, patch, flash, floor2Of, sitOf]);
  /**
   * 보내기.
   *
   * ★ **입력칸이 지금 들고 있는 글자**(`text`)를 받아서 보낸다(2026-09-11).
   *   전에는 리액트 상태(`s.draft`)만 보냈는데, 한글은 **조합이 끝나야** 상태에 닿는다.
   *   조합 중에 Enter 를 누르면(= 마지막 글자를 확정하려고 누른 그 Enter) 상태는 한 글자
   *   뒤처져 있고, 그 뒤처진 값이 그대로 나갔다. 화면에 보이는 것과 보내는 것이 갈리면
   *   **아무 소리도 안 난다** — 사용자만 잘린 말을 본다. 그래서 눈에 보이는 값이 기준이다.
   *   `text` 를 안 주면 예전처럼 상태를 쓴다(목 화면·빠른 답).
   */
  const onSend = useCallback((text?: string) => {
    lastSel.current = Date.now();
    pushReply((text ?? sRef.current.draft).trim().slice(0, CHAT_MAX));
  }, [pushReply]);
  const onDraft = useCallback((t: string) => patch({ draft: t.slice(0, CHAT_MAX) }), [patch]);

  /** 알림에서 대화로. 대화의 입구는 **말풍선 하나**다(판정 14) — 시트로 가지 않는다. */
  const openChatFromNotify = useCallback(() => { patch({ sheet: null }); openChat(); }, [patch, openChat]);
  /**
   * 알림에서 그 방으로. **시트를 닫고 타일 팝오버를 연다**(판정 12) —
   * 주방·욕실·침실은 시트가 없어졌으므로 알림만 열어 두면 막다른 길이 된다.
   */
  const goRoomFromNotify = useCallback((k: RoomKey) => () => {
    lastSel.current = Date.now();
    patch({ sheet: null, sheetClosing: false, roomSel: k, popOpen: true, popClosing: false, toast: '' });
  }, [patch]);

  const onAnswerCall = useCallback(() => {
    const top = callQueueOf(esRef.current, mode)[0];
    if (!top) return;
    if (top.kind === 'chat') { openChat(); return; }
    selRoom(top.room)();
  }, [mode, openChat, selRoom]);

  // ── 앨범·엽서 ──
  /**
   * 저장 — **아직 준비 중이라 안내만 한다**(2026-09-21 상훈님 "저장, 공유는 준비중이에요!").
   *
   * ★★ 예전에는 세는 숫자만 1 올리고 **"앨범에 저장했어요"** 라고 했다. 아무 데도 안 담으면서
   *   담았다고 말하는 자리였고, 이어서 "앨범 칸을 누르면 다시 볼 수 있어요"·"폴라로이드 N장"
   *   까지 같은 거짓말을 퍼뜨렸다. 셋 다 지웠다.
   * ★ **진짜 내려받기를 여기 연결하지 않는다** — 네 자리 중 셋은 받을 그림 자체가 아직 없다.
   * ★ 토스트가 아니라 **전면 판**인 이유 — 저장 버튼은 액자 판(zIndex 10)·전면 판(12) 위에서도
   *   눌린다. 토스트는 그 아래(5)라 정작 눌린 자리에서는 안 보인다.
   */
  const saveShot = useCallback(() => {
    setS((v) => ({ ...v, fire: {
      title: '저장은 준비 중이에요',
      body: '아직 만드는 중이라, 지금은 담아 드릴 수 없어요.',
      hint: '', tapAny: true,
      actions: [{ label: '알겠어요', action: 'save-soon-ok', tap: closeFire, primary: true }],
    } }));
  }, [closeFire]);

  /**
   * 액자 하나를 공유한다. 서버가 주소를 만들어 주고, 같은 동작을 다시 공유하면 있던 주소가 온다.
   *
   * ★ 파일이 아니라 **주소**를 준다 — X·인스타 인앱 브라우저가 다운로드를 막기 때문이다.
   *   그래서 여기서 하는 일은 주소를 복사해 손에 쥐여 드리는 것까지다.
   */
  const shareFrame = useCallback(() => {
    const key = sRef.current.frame?.key;
    if (!onServerRef.current || !key) { flash('공유는 아이가 태어난 뒤에 돼요'); return; }
    void (async () => {
      const { error, url } = await liveRef.current?.shareMotion(key) ?? { error: null, url: null };
      if (error) { flash(error); return; }
      if (!url) return;
      // 공유가 성사된 순간이다 — 복사가 되든 안 되든(막는 브라우저가 있다) 아이는 좋아한다.
      careAct('share');
      try {
        await navigator.clipboard.writeText(url);
        flash('링크를 복사했어요');
      } catch {
        // 복사를 막는 브라우저가 있다. 그때는 주소를 그대로 보여 드린다.
        flash(url);
      }
    })();
  }, [flash, careAct]);
  const addWish = useCallback(() => recordWish('postcard'), [recordWish]);

  // ★ 옛 `tapAlbumCell`(앨범 칸을 눌렀을 때의 판)은 **지웠다**(2026-09-21). 앨범이 시트에서
  //   벽 한 장으로 합쳐질 때(A-14) 부르는 곳이 끊겼는데 함수만 남아 있었고, 그 안에 빗금
  //   폴라로이드와 「앨범에 저장」이 들어 있어 **고칠 것 목록에 유령으로 올라왔다.**
  //   지금 앨범 칸은 `pickFrame` 이 받아 액자를 크게 연다.

  /**
   * 아침 엽서 한 장.
   *
   * ★ **앨범 벽에서는 내렸다**(2026-09-21 상훈님 — 실물이 붙기 전까지). 지금 이 판을 여는 길은
   *   이동 창의 「엽서 판」 하나뿐이다. 문구가 어떻게 보이는지는 눈으로 봐야 하므로 남긴다.
   * ★ 빗금 색상자(가짜 폴라로이드)를 **안 그린다**(A-09) — 엽서 그림은 아직 없다. 대신 엽서에
   *   적힌 **말 자체를 본문으로** 올린다. 그림이 생기면 `preview` 로 붙이면 된다.
   * ★ "문구는 세 벌 중 하나로 바뀌어요" 는 **개발 메모**였다. 아이 방에 남길 말이 아니다.
   */
  const popPostcard = useCallback(() => {
    setS((v) => {
      const [caption] = POSTCARDS[v.cardIdx % POSTCARDS.length];
      return { ...v, cardIdx: v.cardIdx + 1, fire: {
        title: '아침에 도착했어요', body: caption, hint: '',
        actions: [
          { label: '저장', action: 'postcard-save', tap: saveShot, primary: true },
          { label: '이런 동작도 보고 싶어요', action: 'postcard-wish', tap: addWish, primary: false },
          { label: '닫기', action: 'postcard-close', tap: closeFire, primary: false },
        ],
      } };
    });
  }, [saveShot, addWish, closeFire]);

  const popScenes = useCallback(() => {
    setS((v) => ({ ...v, fire: {
      title: '저장한 장면',
      body: '아직 모아 둔 장면이 없어요. 저장이 준비되면 여기에 모여요.',
      hint: '', tapAny: true,
      actions: [{ label: '앨범으로', action: 'scenes-close', tap: closeFire, primary: true }],
    } }));
  }, [closeFire]);

  // ── 온보딩 ──
  const enterSample = useCallback(() => {
    setS((v) => ({
      ...v,
      snapshot: {
        petName: v.petName, day: v.day, bond: v.bond, full: v.full, trace: v.trace, plays: v.plays,
        pets: v.pets, stock: v.stock, snacks: v.snacks, bathUsed: v.bathUsed, sick: v.sick, night: v.night,
        sleeping: v.sleeping, calls: v.calls, log: v.log, memories: v.memories, resolved: v.resolved,
        floorLv: v.floorLv, albumOpen: v.albumOpen,
      },
      screen: 'room', sampleMode: true, hatch: 0, tutor: 0, sheet: null, toast: '', fire: null,
      popOpen: false, chatOpen: false, uq: 0,
      petName: '여울', day: 0, bond: 12, full: 2, trace: 1, plays: 3, pets: 0, stock: 3, snacks: 0,
      bathUsed: false, sick: false, night: false, sleeping: false, calls: 3, resolved: {},
      floorLv: 1, albumOpen: 2,
      log: [{ who: 'pet', text: '저는 여울이에요. 연습 상대예요.' }],
      memories: ['연습용 기억'],
    }));
  }, []);

  /**
   * 이미 함께 사는 아이가 있을 때 곧장 방으로. 온보딩을 다시 태우지 않는다.
   * 튜토리얼은 켜지 않는다 — 어디까지 했는지는 서버(`tutorial.step`)가 알고 있다.
   */
  const enterRoom = useCallback(() => {
    patch({
      screen: 'room', sampleMode: false, sheet: null, popOpen: false, chatOpen: false,
      toast: '', fire: null, cracking: false,
    });
    // A절 표 "방에 들어올 때 · 인사" — 한 바퀴(느낌표 말풍선) 틀고 기본으로 돌아간다.
    careAct('enter_room');
  }, [patch, careAct]);

  const goEgg = useCallback(() => {
    lastSel.current = Date.now();
    patch({ screen: 'egg', sheet: null, popOpen: false, chatOpen: false, toast: '', cracking: false });
  }, [patch]);

  const exitSample = useCallback(() => {
    setS((v) => ({
      ...v, ...(v.snapshot ?? {}),
      sampleMode: false, snapshot: null, screen: 'onb',
      step: STEPS.indexOf(readyRef.current ? 'born' : 'char'),
      sheet: null, toast: '', fire: null, hatch: v.hatch,
    }));
  }, []);

  const tapEgg = useCallback(() => {
    if (!hatchReady) {
      patch({ eggMsg: '아직 부화 중이에요. 조금만 더 기다려 주세요.' });
      later('eggMsg', 2400, () => setS((w) => ({ ...w, eggMsg: '' })));
      return;
    }
    if (s.cracking) return;
    patch({ cracking: true });
    // 껍질이 **한 번** 깨지고 나면 '태어남' 칸으로. 샘플 전 상태를 스냅샷에서 되돌린다.
    // ★ 대기는 깨지는 동작 길이(Egg.tsx 의 `yCrack 1.45s`)에 숨 한 번(0.25초)을 더한 값이다.
    //   전에는 2.6초를 기다리면서 동작은 0.4초짜리를 무한 반복했다 — 깨지다 마는 알을 여섯 번 봤다.
    later('crack', EGG_CRACK_MS, () => setS((w) => ({
      ...w, ...(w.snapshot ?? {}),
      cracking: false, sampleMode: false, snapshot: null, eggMsg: '',
      screen: 'onb', step: STEPS.indexOf('born'),
      tutor: 0, tutorOn: true,
      day: 1, bond: 10, full: 2, happy: 2, trace: 0, plays: 3, pets: 0, stock: 3, snacks: 0,
      calls: 3, resolved: {}, sleeping: false, night: false, sick: false, albumOpen: 8,
      popOpen: false, chatOpen: false, sheet: null, toast: '',
    })));
  }, [hatchReady, s.cracking, patch, later]);

  const goStep = useCallback((i: number) => patch({ screen: 'onb', step: i, sheet: null }), [patch]);

  const onNext = useCallback(() => {
    const key = STEPS[s.step];
    // ★ 2026-09-19 — **랜딩에서는 아무것도 묻지 않는다.** 전에는 첫 CTA 한 번에 가입 창이 떠서
    //   SNS 로 처음 온 사람이 무엇을 주는 곳인지도 모른 채 계정부터 만들어야 했다.
    //   가입은 **그림을 실제로 올리는 순간**(presign 직전)으로 미뤘다 — 자리는 `Onboarding` 의
    //   올리기 칸이고, 그동안 고른 그림은 `useHatch.holdUpload` 가 들고 있다.
    //   9/7 결정("가입은 칸이 아니라 모달")은 그대로다. 뜨는 **시점만** 뒤로 갔다.
    if (key === 'char') {
      if (!s.petName) {
        patch({ nameErr: true });
        later('nameErr', 2600, () => setS((w) => ({ ...w, nameErr: false })));
        return;
      }
      enterSample();
      return;
    }
    if (s.step >= STEPS.length - 1) {
      patch({ tutorOn: true, tutor: 0, day: 1, bond: 10, screen: 'room', sheet: null, toast: '', fire: null });
      return;
    }
    patch({ screen: 'onb', step: s.step + 1 });
  }, [s.step, s.petName, patch, later, enterSample]);

  const onBack = useCallback(() => setS((v) => ({ ...v, step: Math.max(0, v.step - 1) })), []);
  const onUpload = useCallback(() => patch({ uploaded: true }), [patch]);
  const onName = useCallback((t: string) => patch({ petName: t.slice(0, 12), nameErr: false }), [patch]);
  const randomName = useCallback(() => patch({ petName: NAME_POOL[Math.floor(Math.random() * NAME_POOL.length)] }), [patch]);
  /**
   * 칩 하나를 켜고 끈다. **여러 개를 켤 수 있다.** 다시 누르면 그것만 빠진다.
   * 새로 고른 것은 **뒤에 붙는다** — 맨 앞(대표)이 흔들리면 서버에 저장되는 성격이 바뀐다.
   *
   * @param shown 지금 **켜져 보이는** 칩들. 아직 이 묶음을 한 번도 안 건드렸으면 여기서 시작한다 —
   *   서버가 아는 성격은 켜진 채로 뜨는데, 그걸 빈 손에서 토글하면 **켜진 칩을 눌렀는데 또 켜진다.**
   */
  const pickChip = useCallback((k: string, v: string, shown: readonly string[] = []) => () => setS((w) => {
    const cur = w.picks[k] ?? [...shown];
    const next = cur.includes(v) ? cur.filter((x) => x !== v) : [...cur, v];
    return { ...w, picks: { ...w.picks, [k]: next } };
  }), []);
  /**
   * 자유 입력 한 칸에 적는다.
   *
   * ★★ 자르는 길이는 **`CHAR_TEXT_MAX` 한 곳에서만** 가져온다(`lib/pet.ts`, 계약 옆).
   *   2026-09-22 — 여기가 혼자 `60` 으로 자르고 있어서, 화면이 `maxLength` 를 100·200 으로 열어도
   *   **상태에 담길 때 60 에서 조용히 멎었다.** 세계관의 "남은 자리" 안내도 그 벽 때문에
   *   20 밑으로 못 내려가 영영 안 떴다. 숫자를 여기 다시 박으면 같은 일이 또 난다.
   * ★ 모르는 칸은 가장 넉넉한 값으로 받는다 — 화면이 서버보다 먼저 자르면 사용자는 **왜 잘렸는지**
   *   알 길이 없다. 넘치면 서버가 이유를 말해 준다.
   */
  const onGroupText = useCallback((k: string) => (t: string) => setS((w) => ({
    ...w, texts: { ...w.texts, [k]: t.slice(0, CHAR_TEXT_MAX[k] ?? 200) },
  })), []);
  const pickUser = useCallback((k: string, v: string) => () => setS((w) => ({ ...w, user: { ...w.user, [k]: w.user[k] === v ? null : v } })), []);
  /**
   * 여울의 물음에 답하거나 넘긴다. 답은 `user` 에 쌓인다.
   * ★ 고른 호칭(`user.nick`)을 아이가 실제로 부르는 말에 끼우는 것은 아직 안 했다 —
   *   말투·대사 생성이 서버로 넘어갈 때 그쪽에서 쓴다. 지금은 저장만 한다.
   */
  const askNext = useCallback((key: string | null, val: string | null) => () => {
    lastSel.current = Date.now();
    setS((v) => ({ ...v, user: key && val ? { ...v.user, [key]: val } : v.user, uq: v.uq + 1, askDraft: '' }));
  }, []);
  /** 직접 적기. 적기 시작하면 칩 선택을 지운다 — 둘 다 켜져 있으면 무엇이 답인지 알 수 없다. */
  const onAskDraft = useCallback((key: string, t: string, max: number) => {
    setS((v) => ({ ...v, askDraft: t.slice(0, max), user: { ...v.user, [key]: null } }));
  }, []);

  // ── 가입·로그인 모달 ──
  //
  // 창은 공통 부품이 그린다(`@common/auth/AuthModal` — 헤더의 '로그인' 과 **같은 창**).
  // 여기서는 여닫기와 "통과했다" 만 든다. 서버를 부르는 일은 전부 그 부품 몫이다.
  const openAuth = useCallback((tab: 'login' | 'signup') => () => patch({ authOpen: true, authTab: tab }), [patch]);
  const closeAuth = useCallback(() => patch({ authOpen: false }), [patch]);
  /** 로그인·가입에 **성공했을 때만** 부른다. 이미 로그인한 채로 들어온 사람도 이 길로 통과한다. */
  const passAuth = useCallback((how: string) => {
    setS((w) => (w.authed === how ? w : {
      ...w, authed: how, authOpen: false,
      step: w.screen === 'onb' ? Math.max(w.step, STEPS.indexOf('upload')) : w.step,
    }));
  }, []);

  // ── 설정·개발용 ──
  const pickWall = useCallback((id: string) => () => { patch({ wallId: id }); flash('벽지를 바꿨어요'); }, [patch, flash]);
  const toggleDeco = useCallback(() => setS((v) => ({ ...v, decoOpen: !v.decoOpen })), []);
  const openNotify = useCallback(() => patch({ sheet: 'notify', decoOpen: false }), [patch]);
  const openSettings = useCallback(() => {
    // ★ 튜토리얼이 '아이 정보' 칸을 가리킬 때만 열린다(판정 J8).
    const tl = tutLockRef.current;
    if (tl && tl.room !== 'info') { flash(tl.note); return; }
    // 새로 열 때는 지난 실패 줄을 지운다 — 다시 와서 보는 사람에게 옛 경고가 남아 있으면 안 된다.
    patch({ sheet: 'settings', decoOpen: false, saveErr: '' });
  }, [patch, flash]);
  const pickNeedStyle = useCallback((v: NeedStyle) => () => patch({ needStyleLocal: v }), [patch]);
  const toggleNotif = useCallback(() => setS((v) => ({ ...v, notifOn: !v.notifOn })), []);
  const toggleLeave = useCallback(() => setS((v) => ({ ...v, leaveOff: !v.leaveOff })), []);
  // ── 개발용 덮어쓰기 손잡이 ──────────────────────────────────────────
  //
  // ★ **모든 개발 버튼은 `s.dev` 에만 쓴다.** 상태(`s.sick`·`s.night`…)를 직접 밀지 않는다 —
  //   그러면 진짜 아이가 있을 때 서버 값에 덮여 아무 일도 안 났다(예전 고장의 두 번째 까닭).
  /** 덮어쓰기 한 칸을 바꾼다. 화면이 그 자리에서 바뀐다. */
  const devSet = useCallback((p: Partial<DevState>) => setS((v) => ({ ...v, dev: { ...v.dev, ...p } })), []);
  /** 덮어쓰기를 통째로 걷어낸다 — 서버·목이 정하는 대로 돌아간다. */
  const devReset = useCallback(() => setS((v) => ({ ...v, dev: DEV_OFF })), []);
  /** 2층 한 종을 열고 닫는다(정본 §6 의 여덟 칸을 각각). */
  const devUnlock = useCallback((pose: string) => () => setS((v) => ({
    ...v, dev: { ...v.dev, unlocked: { ...v.dev.unlocked, [pose]: !v.dev.unlocked[pose] } },
  })), []);
  /** 화면에만 얹는 상황 한 줄을 켜고 끈다(가방·재회 하트처럼 서버 신호가 아직 없는 것). */
  const devExtra = useCallback((id: string) => () => setS((v) => ({
    ...v,
    dev: {
      ...v.dev,
      extra: v.dev.extra.includes(id) ? v.dev.extra.filter((x) => x !== id) : [...v.dev.extra, id],
    },
  })), []);

  const toggleSick = useCallback(() => devSet({ sick: !esRef.current.sick, sickLong: false }), [devSet]);
  const pickTime = useCallback((v: 'day' | 'night' | 'sleep') => () => devSet({
    night: v !== 'day', sleeping: v === 'sleep',
  }), [devSet]);
  /**
   * 이동 창의 시간대·몸 상태 넷. **아이 정보 시트에 있던 같은 버튼과 한 벌로 합쳤다** —
   * 예전엔 `setMode`(이동 창)와 `pickTime`·`toggleSick`(시트)이 따로 두 벌이라 서로 어긋났다.
   */
  const setMode = useCallback((m: Mode) => () => {
    devSet({
      sleeping: m === 'sleep', sick: m === 'sick',
      night: m === 'night' || m === 'sleep',
      sickLong: false,
    });
    patch({ screen: 'room', sheet: null, toast: '' });
  }, [devSet, patch]);
  /**
   * 개발용 — 하루를 넘긴다.
   * ★ 예전에는 0.5초 뒤 **엽서 판**을 자동으로 띄웠다. 날짜를 넘기려고 누른 사람에게 판이
   *   튀어나오는 자리였고, 엽서는 실물이 없어 내린 판이다(2026-09-21). 엽서를 보려면
   *   이동 창의 「엽서 판」 을 누른다 — 칩 자체는 날짜를 넘기는 데 계속 쓴다.
   */
  const nextDay = useCallback(() => {
    setS((v) => ({
      ...v, day: v.day + 1, full: Math.max(0, v.full - 2), trace: Math.min(4, v.trace + 2),
      plays: 3, pets: 0, bathUsed: false, calls: 3, resolved: {}, sleeping: false, night: false, sheet: null,
    }));
    flash('다음 날 아침이에요');
  }, [flash]);
  const restart = useCallback(() => setS(() => ({ ...INITIAL })), []);
  /**
   * 로그아웃 — 화면을 **첫 화면(랜딩 칸)으로** 되돌린다.
   *
   * ★ 왜 `screen` 만 되돌리지 않는가 — 이 상태 한 벌에는 방금까지 함께 있던 아이의 흔적이
   *   곳곳에 남는다(이름·오간 말·기억 칩·앨범·게임 결과·튜토리얼 진행·연습방 스냅샷).
   *   화면만 랜딩으로 옮기면 그 값들이 그대로 살아 있다가, 같은 탭에서 **다음 사람이 로그인하는
   *   순간 앞사람의 것이 그대로 보인다.** 그래서 처음 들어온 사람과 똑같은 한 벌로 통째로 되돌린다.
   *   (서버에서 받아 둔 것은 `useHatch.reset` 이 같은 순간에 함께 버린다.)
   */
  const leaveAccount = useCallback(() => setS(() => ({ ...INITIAL })), []);
  /** 개발용 — 2층 로드맵을 다 배운 것으로 만든다(= 3층 시작 = 조각 등장). */
  const finishRoadmap = useCallback(() => patch({ cChat: 4, cBath: 3, cSleep: 3, cGame: 4, cWake: 4 }), [patch]);
  /** 개발용 — 조각 도장을 0·2·4 로 바꿔 본다. 실제로는 잠들 때 판정·리셋된다(정본). */
  const setShards = useCallback((n: number) => () => patch({ shards: n }), [patch]);
  /**
   * 개발용(연습방) — 자세·상황을 손으로 고정한다. 둘 다 `null` 이면 평소대로 돌아간다.
   * ★ 상황을 고르면 **그 줄이 적어 둔 자세**도 같이 온다(표가 짝지어 둔 것을 화면이 다시 정하지 않는다).
   */
  const pickScene = useCallback((pose: string | null, sit: string | null = null) => () => devSet({ pose, sit }), [devSet]);
  /**
   * 개발용(연습방) — **2층 8종 전부 열기/닫기.** 켜면 돌보기가 2층 자세를 쓰고,
   * 그에 맞는 소품(대개 "그림 안에 있으니 소품 없음")이 따라온다.
   */
  const toggleFloor2 = useCallback(() => setS((v) => ({ ...v, dev: { ...v.dev, floor2: !v.dev.floor2 } })), []);
  /** 개발용 — 후기 판(FeedbackSheet)을 mock 상태로 강제로 띄운다/끈다. 실서버는 안 탄다. */
  const toggleFbPreview = useCallback(() => setS((v) => ({ ...v, fbPreview: !v.fbPreview })), []);
  /** 선반 카드 펼치기/접기. 카드가 사라졌다 다시 떠도 **편 채로 남는다**(→ `miniOpen`). */
  const toggleMini = useCallback(() => setS((v) => ({ ...v, miniOpen: !v.miniOpen })), []);
  /** 개발용 — 튜토리얼 완주 축하 판을 다시 띄운다. */
  const showTutorEnd = useCallback(() => setS((v) => finishTutor({ ...v, tutorDone: false })), []);
  const openPlay = useCallback((tab: 'talk' | 'guess' | 'run') => () => patch({ sheet: 'play', playTab: tab, toast: '' }), [patch]);
  const pickTab = useCallback((t: 'talk' | 'guess' | 'run') => () => patch({ playTab: t }), [patch]);

  /**
   * 튜토리얼 졸업 — **시계가 켜진 순간, 사람 기준으로 딱 한 번** 축하한다.
   *
   * ★ 판정 기준이 `tutorial.steps` 의 DONE 칸이 **아니다.** 아홉 칸을 다 한 사람에게는
   *   서버가 `tutorial` 블록 자체를 null 로 준다(계약 해석 9). 그러면 DONE 칸을 못 찾아
   *   **가장 잘 따라온 사람만 축하를 못 받는다.** 그래서 `clock.clockStartedAt` 으로 본다 —
   *   그게 곧 졸업의 정의다(`tamagotchi/tutorial.ts` 의 `takeGrownLine` 과 같은 기준).
   *
   * ★★ **"한 번" 을 서버가 기억한다**(2026-09-22). 예전에는 탭 기억(`sessionStorage`)뿐이라
   *   **새 탭·앱 재시작·다른 기기에서 또 떴다** — dev 에서 그대로 재현했다. 순서는 이렇다:
   *     1) 서버가 `graduationSeenAt` 을 주면 **그것이 먼저다** — 값이 있으면 여기서 끝.
   *     2) 탭 기억은 **보조**다. 서버 기록이 오가는 사이 같은 탭에서 두 번 뜨는 것만 막는다.
   *     3) 판을 띄운 뒤 서버에 "봤다" 를 남긴다. 실패해도 화면은 그대로 간다(다음에 한 번 더 뜰 뿐).
   *   ⚠️ 백엔드가 아직 이 칸을 안 줄 수 있다. 그때는 `undefined` 라 1)이 통과하고 **예전과 똑같이**
   *   탭 기억으로만 막힌다 — 새 칸이 오면 저절로 서버 기준으로 올라선다.
   */
  useEffect(() => {
    if (!onServer || !sv?.clock?.clockStartedAt) return;
    if (sv.graduationSeenAt != null) return;
    if (!takeGrownLine(sv.petId, sv)) return;
    setS((v) => finishTutor(v));
    void liveRef.current?.markGraduationSeen();
    // finishTutor 는 렌더마다 새로 만들어지는 평범한 함수라 의존성에 넣지 않는다(넣으면 매 렌더 재실행).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [onServer, sv]);

  /**
   * 판을 띄우면서 **자유 입력칸을 깨끗이 비운다.**
   *
   * ★ 2026-09-21 실측 — 앞 판에서 난 실패 한 줄("아이를 찾지 못했어요")이 **다음 판에 그대로
   *   남았다.** 졸업 판과 해금 판이 같은 입력칸을 쓰게 되면서 생긴 자리다. 판이 바뀌면
   *   그 판의 입력은 처음부터다 — 보낸 적 있음(`wishDone`)까지 같이 푼다. 중복 전송은
   *   `wishSending` 잠금과 서버의 하루 상한이 막는다.
   */
  const withFire = (v: YeoulState, fire: Fire | null): YeoulState =>
    ({ ...v, fire, wishDraft: '', wishError: '', wishSending: false, wishDone: false });

  /**
   * 해금 판 한 장을 만든다 — **판은 하나, 출처는 둘**(2026-09-21 판정 5 · A-10).
   *
   * ★★ 해금은 두 종류이고 그림을 가져오는 칸이 서로 다르다.
   *   - `now`   2층 기본 행동 : 돌보는 그 자리에서 열린다. `justUnlocked`(seq 목록)로 알고,
   *             그림은 `motions[].basicImageKey` 에 **잠겨 있을 때부터** 들어 있다.
   *   - `slept` 심화 행동·선물 : 자는 동안 되고 아침에 도착한다. `learnedToday[].imageKey` 로 알고,
   *             그 칸은 **`revealedAt` 전에는 null** 이며 자는 동안에는 채워지지도 않는다.
   *   ⚠️ `album.motions[].imageKey` 라는 칸은 **없다.** 그 이름으로 찾지 말 것.
   *   두 경우를 한 칸으로 뭉치면 한쪽이 조용히 빈 화면이 된다 — 그래서 여기서만 갈라 둔다.
   *
   * ★ 그림이 없으면 미리보기를 **안 만든다**(빈 자리표를 두지 않는다).
   * ★★ 옛 "그림은 아직 그리는 중이에요" 한 줄은 **뺐다**(2026-09-21 판정 11). 서버는 아침 목록에
   *   **검수를 통과(OPEN)하고 도착 시각이 찍힌 것만** 담는다(`PetResponses`·`ZzalMotion`·`AdminService`
   *   전수 확인). 즉 아침 판에 그림이 없는 경우가 없으므로, 그 문장은 설 자리가 없다.
   *   여기서 그림이 안 보이면 그것은 "아직" 이 아니라 **불러오기 실패**고, 화면은 조용히 접는다.
   */
  const unlockFire = useCallback((
    kind: 'now' | 'slept',
    items: ReadonlyArray<{ label: string; src: string | null }>,
    bodyOverride?: string,
  ): Fire | null => {
    if (items.length === 0) return null;
    const c = UNLOCK_COPY[kind];
    const names = items.map((i) => i.label).join(' · ');
    const shot = items.find((i) => i.src);
    return {
      title: c.title,
      body: bodyOverride ?? c.body(names),
      ...(shot?.src
        ? { preview: { src: shot.src, badge: c.previewBadge, caption: c.previewCaption(shot.label) } }
        : {}),
      hint: '', tapAny: true,
      // 정본 §심화 행동의 수요조사. 졸업 판과 **같은 입력칸**이고, 보낸 자리만 다르게 기록한다.
      wish: true, wishFrom: `unlock_${kind}`,
      actions: [{
        label: c.close, action: `unlock-close-${kind}`, primary: false,
        tap: () => setS((w) => ({ ...w, fire: null })),
      }],
    };
  }, []);

  /**
   * 개발용 — **해금 판을 강제로 띄운다**(2026-09-21 A-13).
   *
   * ★ 왜 필요한가 — 실제 해금은 `justUnlocked`(행동 응답에만 실림)·`learnedToday`(아침 도착)로만
   *   온다. 이동 창의 `친밀도 80%` 는 덮어쓰기 겹(`s.dev.bond`)에만 쓰는데 목 트리거는 본 값
   *   (`s.bond`)을 보므로, **눌러도 판이 안 떴다**. 판정할 화면을 눈으로 못 보는 상태였다.
   * ★ 두 갈래를 따로 띄운다 — 즉시형과 아침형은 말도 그림 출처도 다르다. 한 버튼으로 묶으면
   *   한쪽만 확인하고 다 봤다고 착각한다.
   */
  const showUnlock = useCallback((kind: 'now' | 'slept') => () => {
    const motions = sv?.motions ?? [];
    const pick = kind === 'now'
      ? motions.find((x) => x.layer === 'BASIC_2') ?? motions[0]
      : motions.find((x) => x.advanced?.imageKey);
    const key = kind === 'now' ? pick?.basicImageKey : pick?.advanced?.imageKey;
    // 목(연습방)에는 서버 목록이 없다. 2층 자세표의 **진짜 이름**을 쓴다 — 개발 화면과 앨범이
    // 같은 한 벌을 쓰므로, 여기서만 '2층' 같은 가짜 이름을 지어내면 대화할 때마다 통역이 든다.
    // 연습방의 아이는 여울 자신이라 여울 그림을 거는 것이 맞다(진짜 방에서는 위 `pick` 이 이긴다).
    const mockKey = POSE_FLOORS[1][1][0];
    const item = pick && key
      ? { label: pick.label, src: assetUrl(key) }
      : { label: POSE_LABEL[mockKey] ?? mockKey, src: YEOUL_MOTION[mockKey] ?? null };
    // ★★ 아침 판은 **그림이 있는 것만** 띄운다(2026-09-21 판정 11). 서버는 아침 목록에 검수를
    //   통과(OPEN)하고 도착한 것만 담으므로 **그림 없는 아침 판은 실제로 존재하지 않는다.**
    //   그런데 이 손잡이만 그림 없는 것까지 띄워서, 상훈님이 보신 "그림은 아직 그리는 중이에요"
    //   가 바로 여기서 나왔다 — 화면이 아니라 손잡이가 없는 상태를 만들어 낸 것이다.
    if (kind === 'slept' && !item.src) { flash('아침 판은 그림이 도착한 동작이 있어야 떠요'); return; }
    setS((v) => withFire({ ...v, sheet: null }, unlockFire(kind, [item])));
  }, [sv, unlockFire, flash]);

  // 2층 해금(목) — 친밀도 50% 를 넘긴 순간 한 번만 축하한다(시안 componentDidUpdate).
  // ★ 서버에 붙어 있으면 이 길로 안 온다 — 아래 `justUnlocked` 효과가 맡는다. 두 곳이 같이
  //   띄우면 같은 사건에 판이 두 번 뜬다.
  useEffect(() => {
    if (onServer) return;
    if (s.bond < 50 || s.floorLv >= 3 || s.unlockShown || s.screen !== 'room') return;
    // A절 표 "해금 · 나음 · 공유" 줄의 첫째 — 기쁨 + 폭죽 한 바퀴.
    careAct('unlock');
    // 목에는 서버 목록이 없다. 무엇이 열렸는지는 아는 대로만 말하고, 그림은 걸지 않는다
    // (여울 그림을 걸면 "내 아이가 여울로 바뀌었나" 로 읽힌다 — 자캐 규범).
    const fire = unlockFire('now', [{ label: '2층', src: null }], '조각 네 칸과 달리기가 함께 열렸어요.');
    setS((v) => withFire({ ...v, unlockShown: true, floorLv: 3, sheet: null }, fire));
  }, [onServer, s.bond, s.floorLv, s.unlockShown, s.screen, careAct, unlockFire]);

  /**
   * 2층 해금(서버·즉시형).
   *
   * ★★ **원본(`sv.justUnlocked`)을 보지 않는다** — 그 칸은 행동 응답 **한 번에만** 실려 오고
   *   다음 조회가 `[]` 로 덮는다(계약 2절). 마침 그때 다른 판이 떠 있으면 판을 영영 놓쳤다.
   *   그래서 배관이 받은 자리에서 쌓아 두고(`live.justUnlocked` · `useHatch`), 화면은 **다 보여 준 뒤**
   *   `clearJustUnlocked()` 로 비운다. 못 띄운 채 닫아도 방이 조용해지면 그때 뜬다.
   * ★ 그래서 "본 seq 를 따로 기억하는 자리" 도 없앴다 — 비우는 것이 곧 본 표시다. 두 벌로 두면
   *   한쪽만 지워졌을 때 판이 다시 뜨거나 영영 안 뜬다.
   */
  useEffect(() => {
    if (!onServer || s.screen !== 'room' || s.fire) return;
    const fresh = live?.justUnlocked ?? [];
    if (fresh.length === 0) return;
    const motions = sv?.motions ?? [];
    const items = fresh.map((q) => {
      const m = motions.find((x) => x.seq === q);
      return { label: m?.label ?? '새 동작', src: m?.basicImageKey ? assetUrl(m.basicImageKey) : null };
    });
    careAct('unlock');
    setS((v) => withFire({ ...v, sheet: null }, unlockFire('now', items)));
    live?.clearJustUnlocked();
  }, [onServer, live, sv, s.screen, s.fire, careAct, unlockFire]);

  /**
   * 심화 행동 도착(서버·기상형) — `learnedToday` 에 남아 있는 것만.
   *
   * ★ 서버는 **확인(`…/motions/{seq}/seen`) 전까지** 이 목록에 남겨 둔다. 화면이 판을 닫는 것과
   *   서버가 "봤다" 를 아는 것은 다른 일이라, 닫기만으로 목록이 비지 않는다 — 다시 들어오면
   *   또 뜬다. 그게 맞다(아직 안 본 것으로 서버가 알고 있으므로). 확인을 보내는 것은
   *   상태 층(`useHatch`)의 일이고 여기서 하지 않는다.
   * ★ `imageKey` 는 도착 전에는 없다. 없으면 미리보기를 안 만든다 — 그게 정상 경로다.
   */
  const learnedSeen = useRef<Set<number>>(new Set());
  useEffect(() => {
    if (!onServer || s.screen !== 'room' || s.fire) return;
    const fresh = (sv?.learnedToday ?? []).filter((m) => !learnedSeen.current.has(m.seq));
    if (fresh.length === 0) return;
    fresh.forEach((m) => learnedSeen.current.add(m.seq));
    const items = fresh.map((m) => ({ label: m.label, src: m.imageKey ? assetUrl(m.imageKey) : null }));
    setS((v) => withFire({ ...v, sheet: null }, unlockFire('slept', items)));
  }, [onServer, sv, s.screen, s.fire, unlockFire]);

  /**
   * **재운 순간 한 바퀴 덮기.** 잠은 상태(무한)라 박자를 못 타므로, 들어오는 그 한 번만 여기서 센다.
   * 깨어나면 다시 준비된다 — 다음에 재울 때 또 덮였다 걷힌다.
   */
  const wasAsleep = useRef(false);
  useEffect(() => {
    if (es.sleeping && !wasAsleep.current) {
      setS((v) => ({ ...v, sleepCover: true }));
      later('sleepCover', CYCLE_MS, () => setS((v) => ({ ...v, sleepCover: false })));
    }
    if (!es.sleeping && wasAsleep.current) setS((v) => (v.sleepCover ? { ...v, sleepCover: false } : v));
    wasAsleep.current = es.sleeping;
  }, [es.sleeping, later]);

  // 대화창의 예시 문구가 2.6초마다 바뀐다.
  useEffect(() => {
    if (!s.chatOpen) return;
    const t = setInterval(() => setS((v) => ({ ...v, hintI: v.hintI + 1 })), 2600);
    return () => clearInterval(t);
  }, [s.chatOpen]);

  // 키보드 — 웹에서 손이 마우스를 떠나지 않게(1~5 방 · Space 쓰다듬기 · Enter 대화 · Esc 닫기).
  useEffect(() => {
    const on = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement | null)?.tagName ?? '';
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      const n = parseInt(e.key, 10);
      if (n >= 1 && n <= 5) { selRoom(ROOM_KEYS[n - 1])(); return; }
      if (e.key === ' ') { e.preventDefault(); onPet(); return; }
      if (e.key === 'Enter') { openChat(); return; }
      if (e.key === 'Escape') {
        if (s.frame) { closeFrame(); return; }
        if (s.wallOpen) { closeWall(); return; }
        if (s.sheet) { closeSheet(); return; }
        closePop();
      }
    };
    window.addEventListener('keydown', on);
    return () => window.removeEventListener('keydown', on);
  }, [s.frame, s.wallOpen, s.sheet, selRoom, onPet, openChat, closeFrame, closeWall, closeSheet, closePop]);

  // ── 표(view) ─────────────────────────────────────────────────────────

  const statusText = useCallback((k: LvKey) => {
    const l = LV[k];
    if (needStyle === '색+글자') return l.word;
    if (needStyle === '색+모양') return l.shape;
    return l.shape ? `${l.shape} ${l.word}` : l.word;
  }, [needStyle]);

  const v = useMemo(() => {
    // 오늘 남은 판 — 서버에 붙으면 서버가 센다.
    const playsLeft = onServer ? (live?.game?.remainingToday ?? 0) : s.plays;

    // ── 앨범 칸 수 ──
    const albumMotions = onServer ? (live?.album?.motions ?? sv.motions ?? []) : [];
    const albumAll = onServer ? (albumMotions.length || 18) : 18;
    const albumOpen = onServer ? albumMotions.filter((m) => m.unlocked).length : s.albumOpen;

    const lv = levelsOf(es, mode);
    const calls = callQueueOf(es, mode);
    const top = calls[0] ?? null;
    const wl = WALLS.find((x) => x.id === s.wallId) ?? WALLS[0];
    const unlimited = s.sampleMode;

    const roomDefs: ReadonlyArray<readonly [RoomKey, LvKey, string, boolean]> = [
      ['table', lv.table, String(es.stock), !unlimited],
      ['bath', lv.bath, String(es.trace), !unlimited && es.trace > 0],
      ['play', lv.play, String(playsLeft), !unlimited && playsLeft > 0],
      ['bed', lv.bed, '', false],
      ['album', lv.album, `${albumOpen}/${albumAll}`, !unlimited],
    ];

    const selK: RoomKey = ROOM_KEYS.includes(s.roomSel) ? s.roomSel : 'table';
    const hlRoom = tut ? tut.room : top?.room ?? null;

    const tiles: Tile[] = roomDefs.map(([k, key, badge, hasBadge]) => {
      const on = selK === k && s.popOpen;
      const hl = hlRoom === k;
      const l = LV[key];
      const asleep = mode === 'sleep';
      return {
        key: k, label: ROOM_NAME[k],
        layers: [...LINE[k]],
        fg: on ? C2.onDark : l.fg,
        tileBg: on ? C.ink : l.bg,
        bw: hl ? '2.5px' : '0',
        bd: hl ? ACCENT : 'transparent',
        anim: hl ? 'yNudge 1.9s ease-in-out infinite' : 'none',
        badge, hasBadge: hasBadge && !asleep,
        // 자는 동안은 어느 방도 안 열린다 — 눌러도 한 줄만 말한다.
        // ★ **침실만은 예외다**(2026-09-10). 자는 동안 침실까지 막으면 **깨울 방법이 없어진다** —
        //   재우기가 진짜 서버 호출이 된 뒤 브라우저로 눌러 보다 걸렸다(그전에는 개발용 토글로만
        //   잠들 수 있어서 안 드러났다). 자는 아이를 깨우는 자리는 열려 있어야 한다.
        // ★ 튜토리얼 중에는 **그 칸이 가리키는 방만** 열린다(판정 J8). 나머지는 눌러도
        //   방이 안 열리고 **왜 지금 못 누르는지** 한 줄만 말한다(고장처럼 보이지 않게).
        pick: asleep && k !== 'bed' ? () => flash(sleepingLine(s.petName))
          : tutLock && tutLock.room !== k ? () => flash(tutLock.note)
            : selRoom(k),
        dim: (asleep && k !== 'bed') || !!(tutLock && tutLock.room !== k),
      };
    });

    // ── 팝오버 ──
    // ★ 자는 동안은 **전부 잠근다**(상훈님 판정 13). 방마다 깨어 있는 말을 하던 것이 문제였다.
    //   커튼을 치고 타일·팝오버를 통째로 막고, 하는 말은 한 줄뿐이다.
    const sleepLine = sleepingLine(s.petName);
    // ★ 여행 중에는 **돌보기가 통째로 막힌다**(서버 `ZZAL_TRAVELING`). 침실도 예외가 아니다.
    //   떠남·재회는 2차라 지금은 `trip` 이 늘 null 이지만, 값이 오기 시작하는 날
    //   화면만 모른 채 눌리는 것보다 미리 막아 두는 편이 안전하다.
    const tripMsg = (s.dev.trip || (onServer && sv.trip)) ? `${petWith(s.petName, '이', '가')} 여행 중이에요` : '';
    const lockMsg = tripMsg || (mode === 'sleep' ? sleepLine
      : (mode === 'sick' && selK === 'play') ? '아플 땐 못 놀아요' : '');
    // 여행이면 침실까지 잠근다. 그 밖의 잠금(자는 중·아픔)은 침실을 비켜 간다 — 거기서 깨워야 하므로.
    const locked = !!lockMsg && (!!tripMsg || selK !== 'bed');

    /** `no` = 눌러도 거절될 이유. 비어 있지 않으면 **미리 잠근다**(계약 10절). */
    interface Raw { label: string; count: string; tap: () => void; soft?: boolean; no?: string }
    const P: Record<RoomKey, { say: string; n: number; on: number; tint: string; a: Raw; b: Raw | null }> = {
      table: {
        say: SAY.table[lv.table as keyof typeof SAY.table] ?? SAY.table.ok, n: 4, on: es.full, tint: '#C08552',
        // ★ 거절될 버튼은 미리 잠근다(계약 10절). 조건이 전부 응답에 있으므로,
        //   정상적으로 쓰는 사람은 거절 문구를 볼 일이 없다.
        a: { label: '밥 주기', count: `밥 ${es.stock}개 남음`, tap: onRice,
          no: es.full >= 4 ? '배가 불러요' : es.stock <= 0 ? '밥이 다 떨어졌어요' : '' },
        // ★ 남은 횟수와 잠기는 자리는 **같은 숫자**여야 한다. 서버 규칙은 `snackStreak === 4`
        //   에서 배탈이므로 네 번까지 되고, 남은 횟수도 4 에서 뺀다(예전엔 3 에서 빼서
        //   '0번 남음' 인데 한 번 더 눌리는 어긋남이 있었다 — 2026-09-09 실측).
        b: { label: '간식 주기', count: `${Math.max(0, SNACK_MAX - es.snacks)}번 남음`, tap: onSnack, soft: true,
          no: es.snacks >= SNACK_MAX ? '더 주면 배탈이 나요' : '' },
      },
      bath: {
        // ★ 아프다고 욕실 팝오버가 빨개지지는 않는다(상훈님 판정 6) — 알리는 일은 무대의
        //   약 아이콘 깜빡임이 맡는다. 여기서는 말만 바꾼다.
        say: mode === 'sick' ? '약을 주면 바로 나아요' : (SAY.bath[lv.bath as keyof typeof SAY.bath] ?? SAY.bath.ok),
        n: 4, on: Math.max(0, 4 - es.trace), tint: '#7FA8A0',
        a: { label: '청소하기', count: `흔적 ${es.trace}개`, tap: onClean, no: es.trace <= 0 ? '이미 깨끗해요' : '' },
        b: { label: '목욕', count: es.bathUsed ? '0번 남음' : '1번 남음', tap: onBath,
          no: es.bathUsed ? '오늘 목욕은 했어요' : '' },
      },
      play: {
        say: mode === 'sick' ? '아파서 못 놀아요' : (SAY.play[lv.play as keyof typeof SAY.play] ?? SAY.play.ok),
        n: 4, on: es.happy, tint: '#C98B93',
        // ★ '대화하기' 는 뺐다(상훈님 판정 11) — 대화는 오른쪽 아래 말풍선이 맡고,
        //   마당에는 게임을 하나둘 붙일 예정이라 그 자리를 비워 둔다.
        // 오늘 남은 판은 서버가 센다(두 게임 합산 · 지금 치는 판은 빠져 있다).
        a: { label: '좌우 맞히기', count: `${playsLeft}판 남음`, tap: startGuess },
        // ★ 놀이 시트의 **입구**(2026-09-21). 좌우 맞히기가 무대로 나가면서 시트가 문을 잃었는데,
        //   시트 이름이 '놀이' 이고 마당이 놀이의 집이라 **같은 팝오버의 둘째 칸**이 가장 자연스럽다.
        //   왼쪽 칸 = 바로 하는 놀이 · 오른쪽 칸 = 지난 말과 아직 잠긴 놀이(대화·달리기 탭).
        b: { label: '놀이', count: '대화 · 달리기', tap: openPlay('talk') },
      },
      /**
       * 침실. **서버에 붙어 있으면 되는지 안 되는지를 서버가 정한다**(`clock.canSleep`·`canWake`).
       *
       * ★ 시각으로 판단하면 반드시 틀린다 — 튜토리얼 중에는 시계가 아예 안 흐르므로
       *   "저녁 7시" 라는 말 자체가 성립하지 않는다. 그래서 진짜 아이에게는 시각 문구를 안 쓰고,
       *   못 누를 때는 서버가 거절할 때 쓰는 말("아직 안 졸린가 봐요")을 그대로 보여 준다.
       * ★ 실측(2026-09-10) — `canSleep` 은 튜토리얼 8칸에서만, `canWake` 는 재운 직후에만 true 다.
       *   그래서 이 두 값만 보면 낮잠을 미리 써서 튜토리얼이 막히는 일이 생기지 않는다.
       */
      bed: (() => {
        const asleep = onServer ? es.sleeping : mode === 'sleep';
        const canSleep = onServer ? !!sv.clock?.canSleep : mode === 'night' || s.sampleMode;
        const canWake = onServer ? !!sv.clock?.canWake : true;
        const svSay = asleep ? '자고 있어요' : canSleep ? '슬슬 졸려요' : '아직 안 졸린가 봐요';
        return {
          // ★ 연습방은 시각을 안 보므로 **시각을 말하지 않는다** — 낮에 "저녁 7시부터" 라고 하면
          //   눌러도 안 되는 줄 알고 안 누르게 된다(실제로는 지금 눌린다).
          say: onServer ? svSay
            : mode === 'sleep' ? '자고 있어요' : s.sampleMode ? '언제든 재울 수 있어요'
              : mode === 'night' ? '슬슬 졸려요' : '저녁 7시는 넘어야 졸려요',
          n: 4, on: asleep ? 4 : canSleep ? 1 : 3, tint: '#6E7BA6',
          a: {
            label: asleep ? '깨우기' : '재우기',
            count: onServer
              ? (asleep ? (canWake ? '지금 가능' : '') : canSleep ? '지금 가능' : '')
              : mode === 'sleep' ? '아침 7~10시' : (mode === 'night' || s.sampleMode) ? '지금 가능' : '저녁 7시부터',
            tap: onSleep,
            no: !onServer ? '' : asleep
              ? (canWake ? '' : '아직 더 자야 해요')
              : (canSleep ? '' : '아직 안 졸린가 봐요'),
          },
          b: null,
        };
      })(),
      album: {
        say: `함께한 순간이 ${albumOpen}개예요`, n: albumAll, on: albumOpen, tint: '#B08968',
        /**
         * ★ 2026-09-21(A-14·A-15) — 버튼 **하나**다. 예전에는 「벽 보기」·「방 꾸미기」 둘이었고,
         *   둘째가 앨범 시트로 가는 **유일한 상시 입구**였다. 그래서 그 버튼만 지우면 시트 안의
         *   도감 18칸·엽서·장면·저장·벽지가 통째로 도달 불가가 됐다.
         *   시트를 없애고 그 내용을 **앨범 보기(벽) 안**으로 옮겼다 — 갈 곳이 한 군데면 헤맬 일이 없다.
         */
        a: { label: '앨범 보기', count: `${albumOpen}개 열림`, tap: openWall },
        b: null,
      },
    };
    const cur = P[selK];

    const bar = Array.from({ length: cur.n }, (_, i) => ({
      bg: i < cur.on ? (locked ? ink(.28) : cur.tint) : C.line,
    }));

    const pbtn = (r: Raw | null, isTutTarget: boolean, tutOff: string | false = false): PopBtn | null => {
      if (!r) return null;
      const sickSnack = mode === 'sick' && selK === 'table' && !!r.soft;
      // 서버가 이미 아는 거절 이유. 여울 샘플 방에서는 안 건다 — 거긴 연습이라 늘 눌려야 한다.
      const pre = unlimited ? '' : (r.no ?? '');
      // 돌보기·재우기가 도는 동안엔 전부 잠근다 — 두 번 눌러 두 번 나가는 일을 막는다.
      const waiting = !!live?.careing || !!live?.resting;
      // ★ 튜토리얼 잠금이 **가장 앞이다**(판정 J8) — 그 칸에서 안 시킨 버튼은 다른 이유를 따지기 전에 잠긴다.
      const why = tutOff ? tutOff
        : locked ? lockMsg : sickSnack ? '아플 땐 간식을 안 먹어요' : pre;
      const off = !!why || waiting;
      return {
        label: r.label,
        // ★ 잠겨 있으면 남은 횟수 대신 **왜 못 누르는지**를 그 자리에 쓴다(상훈님 2026-09-10).
        //   '0번 남음' 은 사실이지만 이유가 아니다 — 사람은 "왜?" 를 먼저 묻는다.
        //   기다리는 중(`waiting`)은 이유를 안 쓴다. 곧 풀릴 것이라 한 줄이 깜빡이기만 한다.
        //
        // ★★ 2026-09-21(A-20) — **숫자와 거절 이유를 갈라서 넘긴다.** 예전에는 둘을 한 칸
        //   (`count`)에 섞어 넣어서, "4번 남음" 과 "아플 땐 간식을 안 먹어요" 가 **같은 크기·같은
        //   색·같은 자리**에 떴다. 눌러도 되는지 아닌지가 글을 끝까지 읽어야만 갈렸다.
        //   화면이 둘을 다른 결로 그릴 수 있게 값을 나눠 준다(색만으로 가르지 않는다 — 렌즈 §5-5).
        count: unlimited ? '' : r.count,
        off, why: waiting ? '' : why,
        tap: () => {
          lastSel.current = Date.now();
          // 잠긴 버튼은 `disabled` 라 여기까지 오지 않는다. 와도 아무 일도 안 한다.
          if (off) return;
          r.tap();
        },
        anim: isTutTarget ? 'yBlink 1.2s ease-in-out infinite' : 'none',
        bg: off ? C.off : C.paper,
        fg: off ? '#655C53' : C.ink,
        subFg: off ? C.sub2 : C.faint,
        bd: off ? `1px solid ${C.lineHard}` : `1.5px solid ${C.ink}`,
      };
    };

    const isTutTarget = !!tut && tut.act === 'a' && tut.room === selK;
    const pop: Pop = {
      // 자는 동안은 팝오버를 안 띄운다 — 다만 **침실은 띄운다.** 거기서 깨워야 하기 때문이다.
      show: s.screen === 'room' && !s.sheet && (s.popOpen || s.popClosing) && !s.chatOpen
        && (mode !== 'sleep' || selK === 'bed'),
      anim: s.popClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      name: ROOM_NAME[selK], say: cur.say, bar,
      hasBar: selK !== 'bed' && selK !== 'album',
      count: selK === 'bed' || selK === 'album' ? ''
        : `${({ table: '배부름', bath: '단정함', play: '기분' } as Record<string, string>)[selK]} ${cur.on}/${cur.n}`,
      // ★ 그 칸이 시키는 방의 **첫 버튼(a)** 만 열린다. 둘째 버튼(간식·목욕 같은 것)은 튜토리얼
      //   동안 늘 잠긴다 — 안내가 가리키는 것이 언제나 첫 버튼이라서다.
      // ★ **이미 그 방에 들어와 있으면 방 이름을 다시 말하지 않는다** — "지금은 주방에서 할
      //   차례예요" 가 주방 안에서 뜨면 어디로 가라는 말인지 알 수 없다. 그 자리에서는
      //   **눌러야 할 버튼 이름**을 말해 준다.
      a: pbtn(cur.a, isTutTarget, tutLock && !(tutLock.act === 'a' && tutLock.room === selK) ? tutLock.note : false),
      b: pbtn(cur.b, false, !tutLock ? false
        : (tutLock.act === 'a' && tutLock.room === selK)
          ? `지금은 ${cur.a.label}${josa(cur.a.label, '을', '를')} 해 볼 차례예요`
          : tutLock.note),
      hasB: !!cur.b,
    };

    // ── 무대 ──
    // ★ 아픔은 **벽을 갈아엎지 않는다**(2026-09-16). 예전엔 벽을 단색 회색(#EDEAE4)으로 덮고
    //   창문 해·달까지 꺼서, 방이 아니라 "화면이 깨진 회색 슬래브"로 보였다. 이제 벽·바닥·창문은
    //   평소 방 그대로 두고, **채도만** 낮춰(아래 무대의 `st.sick` 오버레이가 backdrop 로 탈색)
    //   앓는 방으로 읽히게 한다. 창문 해/달도 낮/밤(es.night)을 따라 그대로 뜬다.
    const st: Stage = {
      wall: mode === 'sleep' ? '#DDE3F0' : wl.wall,
      floor: mode === 'sleep' ? '#C9D1E3' : wl.floor,
      frame: C.paper,
      sky: mode === 'day' || (mode === 'sick' && !es.night) ? '#DCEBF5'
        : mode === 'night' || mode === 'sleep' || (mode === 'sick' && es.night) ? '#33406B' : '#E4E7EC',
      pattern: `repeating-linear-gradient(90deg,${ink(.035)} 0 1px,transparent 1px 22px)`,
      moon: mode === 'night' || mode === 'sleep' || (mode === 'sick' && es.night),
      sun: mode === 'day' || (mode === 'sick' && !es.night),
      curtain: mode === 'sleep',
      sick: mode === 'sick',
      charFilter: mode === 'sleep' ? 'saturate(.65) brightness(.9)' : mode === 'sick' ? 'saturate(.5)' : 'none',
      play: mode === 'sleep' || mode === 'sick' ? 'paused' : 'running',
      // 정본 §게이지 — 배부름 0 이면 기본 자세를 **0.7배**로. 자는 동안·아플 때는 그 상태가 먼저다.
      charScale: mode !== 'sleep' && mode !== 'sick' && es.full <= 0 ? 0.7 : 1,
    };

    /**
     * **무엇을 그릴지** — 순서가 곧 우선순위다.
     *   지금 하는 동작(act) → 상태(잠·아픔·배고픔·대화) → 기본
     * 값은 **카탈로그 key**이고, 그 key 를 그림 주소로 바꾸는 일은 `spriteUrl` 한 곳이 맡는다
     * (잠긴 동작을 무엇으로 대신 그릴지도 거기서 정한다).
     * ★ 재우기는 여기 `sleeping` 으로 남는다 — 잠깐 하는 동작이 아니라 자는 동안 계속이라서다.
     */
    // ★ 개발용 고정(자세 고르기)이 있으면 그것이 이긴다. 운영에서는 늘 null 이다.
    const spriteKey: string = s.dev.pose ? s.dev.pose
      : s.acting ? s.acting
      : es.sleeping ? 'sleep'
        // ★ 2026-09-13 — **임시 대체를 걷었다**(판정 5 의 전제가 사라짐).
        //   판정 5 는 "아픈 그림이 아직 없어 슬픈 자세로 대신한다" 였는데, 확정 16종에 `sick` 이 들어왔고
        //   서버 카탈로그도 seq 5 `sick` 을 1층(부화 즉시)으로 준다. 이제 진짜 아픈 그림이 있다.
        //   ⚠️ 대신하고 있던 동안 **땀(`sick_light`)이 영영 안 떴다** — 표의 그 줄은 자세가 `sick` 일 때만
        //   켜지는데 화면은 `sad` 를 짓고 있어서, 그리는 쪽이 자세 불일치로 조용히 걸렀다(2026-09-13 실측).
        : es.sick ? 'sick'
          // ★ 정본 §게이지 61줄 — **배부름 0 = 기본 자세 0.7배**(슬픈 자세가 아니다). 우선순위도
          //   정본 그대로 **병 > 배부름 > 행복** 이라, 배가 고프면 그쪽이 먼저다(2026-09-22 판정 J).
          //   0.7배는 아래 `st.charScale` 이 건다. 꼬르륵 소품은 상훈님 2026-09-13 지시대로 계속 끈 채다.
          : es.full <= 0 ? 'base'
            : es.happy <= 0 ? 'sad'
              : s.chatOpen ? 'joy' : 'base';

    // ── 대화 ──
    //
    // ★ **대사는 전부 서버가 준다**(상훈님 지시). 화면은 고르지도 지어내지도 않는다 —
    //   부름 한 줄도, 답에 대한 대답도 서버 문구 그대로다. 화면이 만드는 글은 안내(다음 부름
    //   시각 같은 것)뿐이고, 그건 아이가 하는 말이 아니라 화면의 말이라 자리를 나눠 둔다.
    const sc = live?.chat ?? null;
    const openCall = sc?.calls.find((c) => c.slot === sc.openSlot && !c.answered) ?? null;
    const lastAnswered = sc ? [...sc.calls].reverse().find((c) => c.answered) ?? null : null;
    /** 지금 아이가 걸어 둔 말. 열린 부름이 없으면 마지막으로 돌려준 말. 둘 다 없으면 없음. */
    const svPetLine = openCall?.line ?? lastAnswered?.replyLine ?? null;
    const canAnswer = onServer ? !!openCall : true;

    // ── 말풍선 ──
    const chatLine = s.chatOpen
      ? (onServer ? svPetLine : (s.petLine || '오늘은 뭐 했어요?'))
      : null;
    /**
     * 좌우 맞히기가 **아이 말풍선으로** 말한다(2026-09-20 안 1) — 게임 전용 말 장치를 새로 만들지 않는다.
     * ★ 사용자를 탓하는 말을 쓰지 않는다(자캐 규범) — 빗나가도 "아쉬워요" 까지다.
     */
    //   ★ 숫자는 **서버에 붙으면 서버 것**을 쓴다(`rounds`·`winAt`·`hits`). 목은 같은 값을 스스로 센다.
    const gr = s.lastGuess;
    const gHits = onServer ? (gr?.hits ?? 0) : s.gHits;
    const gRounds = onServer ? (gr?.rounds ?? GUESS_ROUNDS) : GUESS_ROUNDS;
    const gWinAt = onServer ? (gr?.winAt ?? GUESS_WIN_AT) : GUESS_WIN_AT;
    const gRoundNo = (onServer ? (gr?.nextRound ?? s.gRound) : s.gRound) + 1;
    const guessLine = !s.gOn ? ''
      : s.gPhase === 'shuffle' ? '어느 쪽일까…'
        : s.gPhase === 'reveal' ? (s.gHit ? '맞았어요!' : '아쉬워요, 반대쪽이었어요')
          : s.gPhase === 'done'
            // ★ 기권으로 끝난 판은 **성적을 말하지 않는다** — "N / 5 맞혔어요" 는 끝까지 친 판의 말이다.
            // ★ 이겼으면 **무엇이 좋아졌는지 말한다**(판정 12) — 기분이 오른 사실이 화면 어디에도
            //   안 적혀 있어서, 마당 게이지의 「기분 N/4」 를 맞힌 횟수로 읽는 일이 생겼다.
            //   기분이 이미 가득이면 안 오르므로 그때는 말하지 않는다.
            ? (s.gQuit ? '이 판은 여기까지 할게요.'
              : gHits >= gWinAt
                ? `${gHits} / ${gRounds} 맞혔어요. 이겼어요!${es.happy > s.gHappy0 ? ' 기분이 한 칸 올랐어요.' : ''}`
                : `${gHits} / ${gRounds} 맞혔어요. 다음엔 이겨요.`)
            : `${gRoundNo}번째 · 어느 손에 있을까요?`;

    const bub: Bubble = {
      // ★ 튜토리얼 중에는 부름 말풍선을 접어 둔다 — 그 자리 안내는 아래 선반 카드가 맡는다.
      //   다만 **대화를 열어 아이가 건넨 말이 있으면 그건 보여야 한다**. 안 그러면 서버가 준
      //   대사가 화면에 한 번도 안 나온다(2026-09-09 실측으로 그랬다).
      // 게임이 도는 동안에는 **게임이 말한다** — 부름·튜토리얼보다 앞선다(무대에 그것만 남으므로).
      show: s.gOn ? true : ((!tut || !!chatLine) && (!!top || !!chatLine) && !es.sleeping),
      text: s.gOn ? guessLine : (chatLine || (top?.text ?? '')),
    };

    // ── 시트 ──
    const sk = s.sheet;
    const sheetTitle: readonly [string, string] = sk === 'settings'
      // ★ 제목은 **기능 이름**, 부제가 아이 이름이다(A-18). 다른 시트(놀이·알림)와 같은 규칙 —
      //   예전에는 이 시트만 거꾸로라 머리글을 읽는 법이 화면마다 달랐다.
      ? ['아이 정보', s.petName || '아이']
      : sk ? SHEET_TITLE[sk] : ['', ''];
    const sheet = {
      show: !!sk && s.screen === 'room',
      anim: s.sheetClosing ? 'ySheetOut .2s ease forwards' : 'ySheetIn .26s cubic-bezier(.2,.8,.2,1)',
      dimAnim: s.sheetClosing ? 'yFadeOut .2s ease forwards' : 'yFadeIn .2s ease',
      height: sk && ['play', 'settings'].includes(sk) ? '58%' : '46%',
      title: sheetTitle[0], sub: sheetTitle[1], key: sk,
    };

    // ── 알림 ──
    interface NotifItem { text: string; note: string; action: string; tap: () => void; dot: string; bg: string; bd: string }
    const notifItems: NotifItem[] = calls.map((c): NotifItem => ({
      text: c.text,
      note: c.kind === 'chat' ? '대화 · 답을 기다려요'
        : `${ROOM_NAME[c.room]} · 지금 할 수 있어요`,
      action: c.kind === 'chat' ? '답하기' : '들어가기',
      // ★ 시트가 없어진 방(주방·욕실·침실)으로도 갈 수 있어야 한다 — **타일 팝오버를 연다**
      //   (상훈님 2026-09-08 판정 12). 시트를 열면 그 방은 이제 막다른 길이다.
      tap: c.kind === 'chat' ? openChatFromNotify : goRoomFromNotify(c.room),
      dot: ACCENT, bg: C.paper, bd: C.line,
    }));
    // ★ 옛 "폴라로이드 N장 · 앨범에 저장돼 있어요" 줄은 **지웠다**(2026-09-21) — 저장이 준비 중이라
    //   담긴 것이 없고, 받은 것이 생기더라도 그건 앨범이 아니라 **그분 기기**에 있다.

    // ── 캐릭터 칸(온보딩·아이 정보 공용) ──
    // 서버가 아는 성격을 칩 이름으로. 아직 아무것도 안 고른 사람에게 **지금 값**을 보여 준다
    // (전에는 저장돼 있어도 아무것도 안 골라진 채로 떴다).
    const svPersona = onServer ? (PERSONA_LABEL[sv.personality ?? ''] ?? '') : '';
    // 지금 **켜져 보이는** 성격들. 저장 버튼의 잠금도 이것으로 판단해야 화면과 어긋나지 않는다
    //   (전에는 칩을 다 꺼도 서버 값 때문에 버튼이 열려 있었고, 누르면 아무 일 없이 잔소리만 떴다).
    const personaShown = s.picks.persona ?? (svPersona ? [svPersona] : []);
    const charGroups: CharGroup[] = CHAR_GROUPS.map((g) => {
      // ★ **안 건드린 것**(undefined)과 **전부 끈 것**([])은 다르다. 안 건드렸을 때만 서버가 아는
      //   성격을 켜 보인다. `?? []` 로 뭉뚱그리면 마지막 칩을 꺼도 도로 켜져서 **끌 수가 없다**.
      const picked = s.picks[g.key] ?? (g.key === 'persona' && svPersona ? [svPersona] : []);
      const noteVal = s.texts[g.key] ?? '';
      const done = picked.length > 0 || !!noteVal;
      return {
        key: g.key, title: g.label.split(' · ')[0], ph: g.ph, value: noteVal,
        // 여러 개 고를 수 있다는 것은 **글로 말해 준다** — 칩만 보면 하나만 되는 줄 안다.
        // ★★ 다만 **맨 위 한 번만** 말한다(2026-09-22 상훈님 "기존처럼 깔끔하게"). 예전에는 네 묶음이
        //   각각 같은 줄을 달고 있어 한 화면에 **똑같은 안내가 네 번** 떴다. 규칙은 네 칸 모두 같으니
        //   첫 칸에서 한 번 말하면 된다. 성격은 서버가 하나만 받으므로 그 사실만 뒤에 덧붙인다.
        note: g.key === 'persona'
          ? (picked.length > 1
            ? `여러 개 고를 수 있어요 · 대표는 '${picked[0]}'`
            : '여러 개 고를 수 있어요 · 성격은 처음 고른 것이 대표예요')
          : '',
        onInput: onGroupText(g.key),
        cardBd: done ? C.accentDim : C.lineSoft,
        cardBg: done ? C.shell : C.paper,
        opts: g.opts.map((o) => ({ text: o, pick: pickChip(g.key, o, picked), ...sel(picked.includes(o)) })),
        /**
         * 접었을 때 보이는 한 줄(A-17 · 2026-09-22 상훈님 1안).
         *
         * ★ **고른 것을 그대로 읽어 준다** — 접힌 칸이 무엇을 담고 있는지 안 보이면 접는 것이
         *   곧 숨기는 것이 된다.
         * ★★ 비었을 때는 **"비어 있음"** 이다. 예전 "아직 안 골랐어요" 는 (1) '아직' 이 재촉으로
         *   읽히고 (2) **칩만** 말해서 **적는 칸이 있다는 사실이 안 보였다** — 그래서 세계관 칸이
         *   "없다" 로 읽혔다(2026-09-22 실측 보고). 제목 + 이 한 마디가 붙어 `세계관 비어 있음`
         *   으로 읽히면, 거기에 들어갈 것이 있다는 사실이 접힌 채로도 보인다.
         * ★ 네 묶음 모두 같은 규칙이고 **한 줄을 넘기지 않는다**.
         */
        summary: picked.length > 0
          ? picked.join(', ') + (noteVal ? ' · 적어 둔 말 있음' : '')
          : (noteVal ? '적어 둔 말 있음' : '비어 있음'),
        done,
      };
    });

    // ── 앨범 벽 ──
    // ★ 구르기는 18칸 **밖의 선물**이다(정본 §"첫 심화 행동 동작 = 카탈로그 밖 특별 1종").
    //   그래서 칸 수(N/18)를 건드리지 않고 맨 앞에 따로 붙인다.
    const gift: ReadonlyArray<readonly [string, number]> = s.rollUnlocked ? [['구르기 · 선물', 1]] : [];
    /**
     * 벽에 걸 액자들.
     *
     * ★ 서버에 붙으면 **도감 18칸을 서버가 준다**(`album.motions`, 없으면 펫 상태의 같은 목록).
     *   이름·잠금·조건이 전부 거기 있고, 그림 주소도 `key` 로 `spriteUrl` 이 찾아간다.
     */
    const svMotions = onServer ? (live?.album?.motions ?? sv.motions ?? []) : [];
    const svFrames: ReadonlyArray<readonly [string, number, string, string]> = svMotions
      .map((m) => [m.label, m.unlocked ? 1 : 0, m.hint ?? '', m.key] as const);
    const mockFrames = [...gift, ...ALBUM].map(([name, open], i) => {
      const parts = String(name).split(' · ');
      return [parts[0], open ? 1 : 0, parts[1] || '조건 미정', FRAME_KEYS[i % FRAME_KEYS.length]] as const;
    });
    const frames = (onServer ? svFrames : mockFrames).map(([name, open, cond, key]) => {
      const f: FrameData = { name, open: !!open, cond: open ? '' : (cond || '조건 미정'), key };
      return {
        ...f,
        label: open ? f.name : (cond || '조건 미정'),
        labelFg: open ? '#5A4A3C' : C.faint,
        bd: open ? C.frameWood : 'rgba(201,169,141,.45)',
        bg: open ? C.paper : paperA(.5),
        shadow: open ? `0 4px 10px ${ink(.18)}` : 'none',
        opacity: open ? 1 : 0.2,
        filter: open ? 'none' : 'grayscale(.4)',
        tap: pickFrame(f),
      };
    });

    // ── 다음에 배울 것 ──
    //
    // ★★ **원천은 서버 도감이다**(2026-09-22 판정 K). 예전에는 프론트 상수(`LEARN_GOALS`)와
    //   **목 카운터**(`s.cChat`·`cBath`·`cSleep`·`cGame`)로 만들었다. 그 카운터는 서버 경로에서
    //   한 번도 안 올라서(돌보기가 전부 `serverCare` 로 빠진다) 무엇을 해도 **영원히 0** 이었고,
    //   목록에는 **이미 열린 1층**(손 흔들며 인사 = `hello` · 자기 = `sleep`)이 "배울 것" 으로
    //   올라와 있었으며, 숫자도 서버와 달랐다(좌우 3 ↔ 서버 4판 · '재우기' 는 서버에 없는 조건).
    //   이제 **남은 2층만**, **서버가 준 이름·조건·진행도 그대로** 보여 준다.
    // ★ 목(연습방·서버 없는 진짜 방)은 **같은 구조에 목 값**을 넣는다 — 규칙은 하나, 값만 갈린다.
    const goals: { name: string; cond: string; have: number; need: number; done: boolean }[] = onServer
      ? svMotions
        .filter((m) => m.layer === 'BASIC_2' && !m.unlocked)
        .map((m) => ({
          name: m.label,
          cond: m.hint ?? '',
          have: m.progress?.current ?? 0,
          need: Math.max(1, m.progress?.target ?? 1),
          done: false,
        }))
      : LEARN_GOALS.map((g) => {
        const have = s[g.counter] as number;
        return { name: g.name, cond: g.cond, have, need: g.need, done: have >= g.need };
      });
    // 접혀 있을 때 보여 줄 한 줄 — **아직 못 채운 것 중 가장 가까운 것**.
    // 서버 목록은 이미 "남은 것" 뿐이라, 진행도가 가장 앞선 줄이 곧 다음에 열릴 것이다.
    const goal = (onServer
      ? [...goals].sort((a, b) => (b.have / b.need) - (a.have / a.need))[0]
      : goals.find((g) => !g.done)) ?? null;
    // ★ 연습방·진짜 방 **둘 다** 이 카드가 안내를 맡는다(2026-09-21 A-06). 예전에는 연습방만
    //   머리 띠 위의 다른 카드를 썼다 — 같은 안내가 화면마다 다른 자리에 있었다.
    /**
     * 좌측 하단 카드가 **튜토리얼의 목소리**를 낼 때.
     *
     * ★★ 자고 있어도 낸다(2026-09-22 실측). 8칸("재우고 다시 깨워 주세요")을 끝내면 9칸으로
     *   넘어가는데 그때 아이는 **자고 있다** — 카드를 접으면 마지막 칸의 「이제 시작할게요」가
     *   화면에서 사라지고, 다른 손잡이는 전부 잠겨 있어서(J8) **아무 데도 못 간다.**
     *   자는 동안 카드를 접는 것은 "방이 조용해야 한다" 는 연출인데, 튜토리얼 중에는
     *   그 카드가 유일한 길이라 연출보다 길이 먼저다.
     */
    const showTutMini = !!tut && !s.chatOpen;

    /**
     * 자유 입력칸이 그릴 것 한 벌. **뷰에서 만든다** — 글자마다 바뀌는 값이라
     * 상태에 눌러앉는 `fire` 객체에 담으면 타이핑이 화면에 안 보인다.
     *
     * ★ `canSend` 가 **공백을 뗀 뒤**를 본다. 공백만 친 사람에게 버튼을 열어 주면 서버가 400 으로
     *   거절하고, 화면은 자기가 막을 수 있었던 실패를 사용자에게 보여 주게 된다.
     */
    const wishText = s.wishDraft.trim();
    const wish = {
      label: WISH_COPY.label,
      placeholder: WISH_COPY.placeholder,
      done: WISH_COPY.done,
      // ★ 이름을 따로 준다 — `...WISH_COPY` 로 펼치면 문구 `send`·`sending` 이
      //   아래의 손잡이·불리언에 **조용히 덮인다**(버튼 글자가 빈 값이 된다).
      sendLabel: WISH_COPY.send,
      sendingLabel: WISH_COPY.sending,
      value: s.wishDraft,
      max: WISH_MAX,
      /** "12 / 60". 넘긴 뒤 꾸짖지 않고 **못 넘게** 막으므로 이건 경고가 아니라 남은 양의 표시다. */
      count: `${s.wishDraft.length} / ${WISH_MAX}`,
      full: s.wishDraft.length >= WISH_MAX,
      canSend: wishText.length > 0 && !s.wishSending && !s.wishDone,
      sending: s.wishSending,
      sent: s.wishDone,
      error: s.wishError,
      onInput: onWishDraft,
      send: sendWish,
    };

    return {
      wish,
      lv, calls, top, mode, tut, TUT, unlimited, selK,
      tiles, pop, st, bub, sheet, charGroups, frames, spriteKey,
      /** 개발용 고정(자세·상황 고르기). 방과 이동 창이 함께 읽는다. */
      posePick: s.dev.pose, sitPick: s.dev.sit,
      /** 개발용 — 2층을 다 연 것으로 치고 있는가. 이동 창의 스위치가 읽는다. */
      floor2: s.dev.floor2,
      /** 개발용 — 후기 판을 mock 으로 강제로 띄우는 중인가. 방과 이동 창이 함께 읽는다. */
      fbPreview: s.fbPreview,
      /** 개발용 덮어쓰기 한 벌 그대로. 이동 창이 불(켜짐 표시)을 이 값으로만 판단한다. */
      dev: s.dev,
      /**
       * **지금 화면이 실제로 쓰고 있는 값**. 이동 창의 불은 덮어쓰기가 아니라 **이것**을 본다 —
       * "눌렀는데 불이 안 들어온다"·"안 눌리는데 불만 들어온다" 가 거기서 생겼다(C절 3번 고장).
       */
      now: {
        full: es.full, happy: es.happy, trash: es.trace, bond: es.bond,
        sick: es.sick, sleeping: es.sleeping, night: es.night, shards: s.shards,
      },
      /**
       * 소품 오버레이가 읽는 **지금 상태**. ★ 무엇을 띄울지는 여기서 정하지 않는다 —
       * 상황표(`contract/소품-상황표-v1.json`)가 정하고, 이 값은 그 표의 낱말로 번역될 재료다.
       * (번역은 `tamagotchi/props/situations.ts` 의 `activeSituations`.)
       */
      scene: {
        pose: spriteKey,
        sick: es.sick,
        sleeping: es.sleeping,
        hungry: es.full <= 0,
        unhappy: es.happy <= 0,
        chatOpen: s.chatOpen,
        trash: es.trace,
        /** 지금 도는 행동의 상황 id. 반응 그림(`acting`)과 **같이 켜지고 같이 꺼진다**. */
        act: s.actSit,
        /** 그 행동의 단계 소품이 지금 몇 번째 바퀴인가. 방이 표를 보고 실제 단계로 옮긴다. */
        actStep: s.actStep,
        /** 24시간+ 방치 — 땀 대신 해골. 표의 `sick_long` 줄이다(지금은 개발용으로만 켠다). */
        sickLong: s.dev.sickLong,
        /** 화면에만 얹는 상황 줄(가방·재회 하트…). 서버 신호가 생기면 여기 말고 상태에서 온다. */
        extra: s.dev.extra,
        /** 하루 소품 하나(`prop_ball`…). 고른 것이 없으면 안 띄운다. */
        daily: s.dev.daily,
      },
      // 자는 동안은 방을 아예 못 연다(판정 13). 화면이 이 값 하나만 보면 되게 둔다.
      asleep: mode === 'sleep',
      // ★ 2026-09-14 — 감춤은 **재운 직후 한 바퀴(1.8초)만** 남는다.
      //   덮었다가 걷히며 자는 아이가 드러나고, 그 뒤로는 반투명 커튼 아래에서 계속 보인다
      //   (상훈님 "잘 때 커튼도 마찬가지야"). 옛 판정 5 의 "자는 그림이 없어 통째로 감춘다" 는
      //   그림이 나오면서 전제가 사라졌다.
      //   ⚠️ 방을 못 열게 막는 판정 13(`asleep`)은 그대로다 — 그건 그림이 아니라 규칙이다.
      hidePet: s.sleepCover,
      sleepLine: sleepingLine(s.petName),
      screen: { room: s.screen === 'room', onb: s.screen === 'onb', egg: s.screen === 'egg' },
      hud: {
        show: !s.sampleMode,
        // 튜토리얼 4칸은 '아이 정보' 안에서 하는 일이라, 타일이 아니라 이 버튼을 가리켜야 한다.
        hl: !!tut && tut.room === 'info',
        // ★ 그 칸이 아니면 잠근다(판정 J8) — 눌러도 시트가 안 열리고 한 줄만 말한다.
        off: !!tutLock && tutLock.room !== 'info',
      },
      /**
       * 머리줄. 서버가 붙으면 **이름·N일째·친밀도 셋 다 서버 값**이다.
       * ★ `intimacy.percent` 만 쓴다 — `score`(0~999)는 내부 점수라 화면에 그대로 못 쓴다.
       */
      pet: onServer
        ? {
          name: sv.name,
          dayText: `${sv.daysTogether ?? 1}일째`,
          bond: sv.intimacy?.percent ?? 0,
        }
        : { name: s.petName, dayText: `${s.day}일째`, bond: s.bond },
      chat: {
        show: s.screen === 'room' && (s.chatOpen || s.chatClosing) && !s.sheet,
        anim: s.chatClosing ? 'yPopOut .17s ease forwards' : 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
        // ★ **내가 쓴 줄만** 입력칸 위에 남는다. 아이 말은 무대 말풍선 하나뿐이다
        //   (2026-09-22 판정 2안) — 같은 문장을 두 곳에 띄우지 않는다(→ `ChatBar`).
        hasMine: !!s.mine, mine: s.mine, draft: s.draft,
        // 열린 부름이 없으면 적을 곳을 잠그고 **언제 다시 부르는지**만 알려 준다.
        // 이건 아이의 말이 아니라 화면의 안내라, 아이 말풍선이 아니라 입력칸에 둔다.
        can: canAnswer && !live?.chatting,
        /**
         * 보낼 수 있는가 — **적은 글이 있어야 한다**(2026-09-21 판정 8).
         * 예전엔 빈 칸으로 「보내기」를 눌러도 아무 일도 안 났다(눌리기는 하는데 조용히 버려졌다).
         * 자유 입력칸(`WishBox`)이 이미 쓰는 방식 그대로 — 적기 전에는 버튼을 흐리게 둔다.
         */
        canSend: canAnswer && !live?.chatting && s.draft.trim().length > 0,
        hint: !canAnswer
          ? nextCallHint(sv?.chatSummary?.nextAt ?? null)
          : `${CHAT_HINTS[s.hintI % CHAT_HINTS.length]}처럼 · 40자까지`,
      },
      fab: {
        // ★ 대화의 **유일한 입구**다(상훈님 2026-09-08 판정 14). 마당 팝오버에서 대화를 뺐고,
        //   아파도 눌린다 — 아플 때 말이 막히면 아이가 제일 필요한 순간에 말을 못 한다.
        //   자는 동안만 안 뜬다.
        show: s.screen === 'room' && !s.chatOpen && !s.popOpen && !s.sheet && !s.gOn && !es.sleeping,
        dot: onServer ? !!openCall : s.calls > 0,
        bw: tut && tut.room === 'chat' ? '2.5px' : '1px',
        bd: tut && tut.room === 'chat' ? ACCENT : C.line,
        anim: tut && tut.room === 'chat' ? 'yNudge 1.9s ease-in-out infinite' : 'none',
        // ★ 튜토리얼이 대화 칸을 가리킬 때만 열린다(판정 J8). 잠긴 동안에도 **버튼은 그대로 보인다** —
        //   사라지면 "없어졌다" 로 읽히고, 흐려지면 "지금은 아니다" 로 읽힌다.
        off: !!tutLock && tutLock.room !== 'chat',
        why: tutLock?.note ?? '',
      },
      /**
       * 약 단추. **아플 때만 그린다** — 계약 10절의 여섯 거절 중 "안 아픔" 은 버튼을 잠그는 대신
       * 아예 안 내는 것으로 막는다(안 아플 때 회색 약병이 떠 있으면 아픈 줄 안다).
       * 돌보기가 도는 동안에는 잠근다(연타 방지).
       */
      medFab: {
        show: s.screen === 'room' && es.sick && !s.chatOpen && !s.popOpen && !s.sheet && !s.gOn && !es.sleeping,
        off: !!live?.careing,
      },
      // 좌측 하단 카드. 세 얼굴을 차례로 갖는다 —
      //   튜토리얼 중엔 부름 / 2층을 배우는 동안엔 로드맵 / 다 배우면 **조각 도장 4칸**.
      // ★ 로드맵이 끝나도 카드가 사라지지 않는다(2026-09-07 상훈님 지시). 정본상 2층 8종을
      //   다 열면 3층이 시작되고 그때 조각 4칸이 등장하므로, 그 자리를 그대로 이어받는다.
      mini: {
        // ★ 자는 동안에는 접는다 — **튜토리얼 중만 빼고**(→ `showTutMini` 머리말).
        //   9칸은 아이가 잠든 채로 오므로, 접으면 마지막 손잡이가 화면에서 사라진다.
        show: s.screen === 'room' && !s.chatOpen && !s.popOpen && !s.sheet && !s.gOn && (!es.sleeping || showTutMini),
        isTut: showTutMini, tutText: tut?.text ?? '',
        tutStep: `${tutIdx + 1} / ${TUT.length}`,
        /**
         * 연습방에서만 앞뒤로 오간다 — 연습이라 되돌아가 다시 볼 수 있어야 한다.
         * 진짜 방에는 없다: 서버가 칸을 세므로 화면이 되감으면 두 곳에서 세게 되고 언젠가 갈린다.
         */
        tutPrev: { show: !!tut && s.sampleMode && s.tutor > 0, tap: prevTutor },
        tutNext: {
          show: !!tut && s.sampleMode,
          label: tutIdx === TUT.length - 1 ? '알았어요' : '다음',
          tap: nextTutor,
        },
        /** 진짜 방(목)에서만 뜨는 한 줄. 누를 것이 아니라 **어떻게 넘어가는지**를 말해 준다.
         *  ★ 마지막 칸에는 안 낸다 — 거기는 누를 버튼이 있다. */
        tutHint: !!tut && !s.sampleMode && !onServer && !atDone ? '직접 해 보면 다음으로' : '',
        /**
         * 칸 아래 버튼.
         *
         * ★ **서버 튜토리얼에는 '나중에' 가 없다** — 건너뛸 방법이 서버에 없어서, 눌러도 아무
         *   일이 안 나면 고장으로 읽힌다. 마지막 칸에서만 「이제 시작할게요」를 낸다.
         * ★★ **연습방에서도 뺐다**(2026-09-22 상훈님 판정 F). 연습방에는 이미 「이전」·「다음」이
         *   있는데 '나중에' 가 **「다음」과 거의 같은 일**(칸 +1)을 해서 한 줄에 셋이 겹쳐 떴다.
         *   판정 8 에서 이 버튼이 "죽은 버튼" 에서 진짜 버튼으로 살아난 결과였다.
         *   연습은 앞뒤로 오가며 보는 자리이므로 **넘기는 손잡이는 「다음」 하나면 된다.**
         * ★ 진짜 방(목)에는 남긴다 — 거기는 「이전」·「다음」이 없어서 이것이 유일한 손잡이다.
         */
        // ★★ 2026-09-22 — 진짜 방은 **목도 서버와 같은 아홉 칸**을 쓴다. 그래서 목의 '나중에' 도
        //   뺐다: 서버에는 없는 손잡이라, 목에서 건너뛰며 연습하면 **연습이 진짜와 다른 말**을 한다.
        //   넘기는 길은 양쪽 다 "직접 해 보기" 하나이고, 마지막 칸만 눌러서 끝낸다.
        tutBtn: atDone
          ? { show: true, label: '이제 시작할게요', tap: onServer ? onFinishTutorial : finishTutorHere }
          : { show: false, label: '', tap: noop },
        hasGoal: !showTutMini && !!goal,
        name: goal?.name ?? '',
        cond: goal ? `${goal.cond} ${Math.min(goal.have, goal.need)} / ${goal.need}` : '',
        barW: goal ? `${Math.round(Math.min(1, goal.have / goal.need) * 100)}%` : '0%',
        /**
         * 펼쳤는가. **훅이 들고 있다**(2026-09-22 판정 K) — 예전에는 카드 안의 지역 상태라,
         * 팝오버·시트가 떠서 카드가 사라졌다 다시 뜨면 **늘 접힌 채로 돌아왔다.**
         * 상훈님이 "목록이 왔다갔다" 라고 하신 것의 절반이 이것이다.
         */
        open: s.miniOpen,
        toggle: toggleMini,
        // 배울 것이 남지 않았으면 조각으로 넘어간다.
        // ★ 서버가 `pieces: null` 이라고 하면 **아예 안 그린다**(백엔드 2026-09-09 지시).
        //   3층 전에는 조각이라는 개념이 없어서, 빈 도장 넷이 보이면 "내가 못 채운 것" 으로 읽힌다.
        hasShards: !showTutMini && !goal && (!onServer || !!sv.pieces),
        shards: SHARDS.map((x, i) => ({
          label: x.label, cond: x.cond, on: i < s.shards,
        })),
        shardCount: `${Math.min(4, s.shards)} / 4`,
        // 펼쳤을 때 보여 줄 **남은 것 전부**(접혀 있을 땐 `goal` 하나만 보인다).
        goals: goals.map((g) => ({
          name: g.name,
          cond: `${g.cond} ${Math.min(g.have, g.need)} / ${g.need}`.trim(),
          done: g.done,
        })),
      },
      ask: (() => {
        // ★ 튜토리얼이 도는 동안엔 묻지 않는다(상훈님 2026-09-09 판정 4).
        //   같이 띄우면 첫 화면에 여울의 카드가 둘, 진행 표시가 셋이라 어느 쪽을 하라는 건지 모른다.
        //   여울의 안내를 끝까지 따라간 뒤(`tut` 이 비면) 그때부터 하나씩 묻는다.
        const q = s.sampleMode && !tut && s.uq < USER_Q.length ? USER_Q[s.uq] : null;
        const draft = s.askDraft.trim();
        return {
          show: s.screen === 'room' && !!q && !s.chatOpen && !s.sheet && !s.popOpen && !s.gOn,
          // label 이 이미 여울의 말(물음표 포함)이라 손대지 않고 그대로 쓴다.
          text: q ? q.label : '', step: `${s.uq + 1} / ${USER_Q.length}`,
          // 시각이 붙은 칩은 그 시각까지 답으로 저장한다 — '아침' 만 남기면 나중에 구간을 알 수 없다.
          opts: q ? q.opts.map((o) => ({
            text: o.text, note: o.note ?? '',
            pick: askNext(q.key, o.note ? `${o.text} ${o.note}` : o.text),
          })) : [],
          hasInput: !!q?.input,
          inputPh: q?.input?.ph ?? '', inputMax: q?.input?.max ?? 12,
          draft: s.askDraft,
          onDraft: (t: string) => { if (q?.input) onAskDraft(q.key, t, q.input.max); },
          // 적어 넣었으면 넘길 손잡이가 필요하다 — 칩을 안 골라도 이걸로 넘어간다.
          hasConfirm: !!q?.input && draft.length > 0,
          confirm: q ? askNext(q.key, draft) : askNext(null, null),
          skip: askNext(null, null),
        };
      })(),
      guide: {
        tap: s.screen === 'room' && s.sampleMode && !es.sleeping && !es.sick && !s.chatOpen && !s.sheet && !s.popOpen && !s.gOn && (!tut || tut.act === 'pet'),
        label: '톡 눌러 보세요',
      },
      sample: {
        show: s.sampleMode,
        ring: `conic-gradient(${ACCENT} 0 ${hatchPct}%, ${C.lineHard} ${hatchPct}% 100%)`,
        eggAnim: hatchReady ? 'yCrack 1.5s ease-in-out infinite'
          : hatchN === 3 ? 'yWiggle 2.4s ease-in-out infinite' : 'yBob 2.8s ease-in-out infinite',
        eggNote: hatchReady ? '부화 완료' : '부화 중',
        eggCount: hatchText,
        noteBg: hatchReady ? ACCENT : ink(.82),
        haloOpacity: hatchReady ? 1 : 0,
        exit: exitSample, forceHatch: goEgg,
      },
      // 하트 개수는 **오늘 쓰다듬은 횟수**다. 서버에 붙었으면 서버가 센 값(`es.pets`)을 쓴다 —
      // 목의 `s.pets` 는 서버 모드에서 영영 0 이라 늘 ♥♡♡ 만 떴다(2026-09-10).
      hearts: { show: s.hearts, text: es.pets >= 3 ? '♥♥♥' : es.pets === 2 ? '♥♥♡' : '♥♡♡' },
      /**
       * 무대 위 좌우 맞히기(2026-09-20 안 1). 시트가 아니라 **아이 몸에 붙은 손**과 **발밑 점 줄**이다.
       * ★ 손 그림 여섯 장은 이미 있던 자산 그대로다(`guess_*`). 새 그림·새 앵커를 만들지 않았다.
       */
      game: {
        show: s.gOn,
        /** 대기·섞기면 주먹, 공개·끝이면 **고른 쪽만** 펼친다(사탕이 있었나에 따라 두 장 중 하나). */
        openSide: s.gPhase === 'reveal' || s.gPhase === 'done' ? s.gPick : null,
        openKind: (s.gHit ? 'candy' : 'empty') as 'candy' | 'empty',
        /** 대기 중에만 누를 수 있다 — 섞기·공개·여운에는 잠긴다. */
        can: s.gOn && s.gPhase === 'wait' && !live?.guessing,
        pick: (side: Side) => () => onGuess(side),
        /**
         * ✕ — 나가기. **아플 때도 잠그지 않는다**(서버가 아픔 거절을 안 만들어 두었다).
         * 확인창을 띄울지·그냥 닫을지는 `quitGuess` 가 정한다(한 판도 안 쳤으면 안 묻는다).
         */
        quit: quitGuess,
        /** 판 표시는 **점 다섯 칸뿐**이다(2026-09-21 상훈님 — 글줄 삭제). 규칙은 그대로 돈다.
         *  맞힘 ● · 빗나감 ✕ · 아직 ○ — **색만으로 가르지 않는다.** */
        marks: s.gMarks.map((m, i) => ({
          hit: m, now: i === s.gRound && s.gPhase !== 'done',
        })),
      },
      toast: { show: !!s.toast, text: s.toast },
      wall: {
        show: s.screen === 'room' && (s.wallOpen || s.wallClosing),
        anim: s.wallClosing ? 'yWallDown .22s ease forwards' : 'yWallUp .3s cubic-bezier(.2,.85,.25,1)',
        count: `${albumOpen} / ${albumAll}`, close: closeWall, frames,
        /**
         * 앨범 안의 손잡이 줄. **개수가 늘 것을 전제로 둔다** — 엽서·여행처럼 앨범에 들어올 것이
         * 더 있다(2026-09-21). 그래서 화면이 넉 줄을 딱 맞춰 그리지 않고 **흐르게** 그린다
         * (`auto-fit`). 여기 한 줄을 더해도 화면은 안 고친다.
         */
        // ★ 「엽서」는 **내렸다**(2026-09-21 상훈님) — 실물 엽서가 붙기 전까지. 이동 창에는 남아 있다.
        actions: ([
          ['장면', popScenes],
          ['저장', saveShot],
          ['방 꾸미기', toggleDeco],
        ] as const).map(([label, tap]) => ({
          label, tap,
          ...(label === '방 꾸미기' && s.decoOpen
            ? { bg: C.accentSoft, bd: ACCENT, fg: C2.accentInk2 }
            : { bg: C.paper, bd: C.line, fg: C.ink }),
        })),
        deco: s.decoOpen,
        decoNote: s.floorLv > 1 ? '' : '2층 동작을 열면 더 고를 수 있어요',
        walls: WALLS.map((w) => ({
          name: w.name, color: w.wall, pick: pickWall(w.id),
          ...(s.wallId === w.id ? { bd: ACCENT, bw: '2px' } : { bd: C.line, bw: '1px' }),
        })),
      },
      frame: {
        show: !!s.frame,
        anim: s.frameClosing ? 'yFrameOut .17s ease forwards' : 'yFrameZoom .22s cubic-bezier(.2,.9,.25,1)',
        name: s.frame?.name ?? '', key: s.frame?.key ?? 'base',
        open: !!s.frame?.open, locked: !!s.frame && !s.frame.open,
        cond: s.frame?.cond ?? '', opacity: s.frame?.open ? 1 : 0.24,
        close: closeFrame, save: saveShot, share: shareFrame,
      },
      fullCells: cells(es.full, '#F2C3A8', C.slotDim),
      happyCells: cells(es.happy, '#C9DFB4', C.slotDim),
      food: {
        stock: es.stock,
        riceBg: es.full >= 4 || es.stock <= 0 ? C.off : C.slot,
        snackNote: s.snacks >= 3 ? '조금 많아요' : '가득이어도 받아요',
      },
      bath: {
        trace: s.trace, sick: s.sick,
        bathBg: s.bathUsed ? C.off : C.slot, bathFg: s.bathUsed ? C2.dim : C.ink,
        bathNote: s.bathUsed ? '오늘 완료' : '오늘 1회',
        medBg: s.sick ? '#FADCD6' : C.off, medFg: s.sick ? ACCENT : C2.dim,
        medBd: s.sick ? '#EFBDB2' : '#DFD9D0',
      },
      play: {
        // ★ 좌우 맞히기 탭을 뺐다(2026-09-20) — 그 놀이는 이제 **무대 위**에서 한다.
        //   대화·달리기 탭은 그대로다.
        tabs: ([['talk', '대화'], ['run', '달리기']] as const).map(([k, label]) => ({
          label, pick: pickTab(k),
          ...(s.playTab === k ? { bg: C.ink, fg: C2.onDark, bd: C.ink } : { bg: C.slot, fg: C.sub2, bd: '#E3DBCD' }),
        })),
        isTalk: s.playTab === 'talk', isRun: s.playTab === 'run',
        callsLeft: onServer ? (openCall ? 1 : 0) : s.calls,
        memCount: onServer ? (sc?.memories.length ?? 0) : s.memories.length,
        // 오늘 오간 말. 서버가 부름마다 [건넨 말 · 내가 한 답 · 돌려준 말] 셋을 들고 있다.
        log: (onServer ? serverLog(sc) : s.log).map((l) => (l.who === 'pet'
          ? { text: l.text, align: 'flex-start', radius: '15px 15px 15px 5px', bg: C2.paperDim, fg: C.ink }
          : { text: l.text, align: 'flex-end', radius: '15px 15px 5px 15px', bg: ACCENT, fg: C.accentInk })),
        // 빠른 답은 **내가 하는 말**이라 화면이 갖고 있어도 된다(아이 대사가 아니다).
        quick: CHAT_QUICK.map((t) => ({ text: t, pick: () => pushReply(t) })),
        draft: s.draft,
        memories: (onServer ? (sc?.memories ?? []) : s.memories).map((t) => ({ text: t })),
        // 오늘 남은 판은 **두 게임 합산**이고 지금 치는 판은 빠져 있다(서버 규칙).
        playsLeft,
        // ★ 달리기는 2차다. 서버가 열렸다고 해도 화면은 아직 없으므로 잠긴 채로 둔다.
        runCond: onServer
          ? '달리기는 아직 준비 중이에요. 좌우 맞히기부터 함께해요.'
          : `2층 해금 + 친밀도 50% 이상이면 열려요. 지금 ${s.floorLv}층 · 친밀도 ${s.bond}%`,
      },
      bed: (() => {
        const on = s.sleeping || s.night || s.sampleMode;
        return {
          label: s.sleeping ? '깨우기' : '재우기',
          note: s.sleeping ? '아침에 깨워 주세요. 07~10시엔 깨워도 돼요.'
            : s.night ? '창 밖이 어두워요. 지금 재울 수 있어요.'
              // 연습방은 시각을 안 본다 — 시각을 말하면 안 되는 줄 알고 안 누른다.
              : s.sampleMode ? '연습방이라 지금 재울 수 있어요.' : '저녁 7시부터 재울 수 있어요.',
          bg: on ? '#DFE5F2' : C.off, fg: on ? '#3B4A6E' : C2.dim,
          bd: on ? '#C2CBE2' : '#DFD9D0', opacity: on ? 1 : 0.7,
        };
      })(),
      notif: { items: notifItems, empty: notifItems.length === 0, dot: s.notifOn && calls.length > 0, count: calls.length },
      settings: {
        groups: [
          { label: '버튼 표기', opts: (['색+모양+글자', '색+글자', '색+모양'] as NeedStyle[]).map((o) => ({ text: o, pick: pickNeedStyle(o), ...sel(needStyle === o) })) },
          // ★ '시간대'·'몸 상태' 줄은 **여기서 뺐다**(2026-09-13). 사용자용 시트에 개발 버튼이
          //   섞여 있었고, 같은 일을 하는 버튼이 이동 창에도 있어 **두 벌이 서로 어긋났다.**
          //   지금은 이동 창 한 곳(`setMode`·`devSet`)만이 그 둘을 바꾼다.
          { label: '알림', opts: [{ text: s.notifOn ? '부름 알림 받는 중' : '알림 꺼짐', pick: toggleNotif, bg: s.notifOn ? C.accentSoft : C.slotDim, bd: s.notifOn ? ACCENT : C.lineHard, bw: s.notifOn ? '2px' : '1px', fg: s.notifOn ? C2.accentInk2 : C.sub2 }] },
        ],
        /**
         * 성격 저장. **서버에 붙어 있을 때만 낸다** — 목에는 보낼 곳이 없다.
         * 튜토리얼 4칸(PERSONALITY)을 넘기는 것이 이 버튼이다.
         */
        /** 연습방에서는 저장 버튼이 없다 — **없는 이유를 말해 준다**(A-19).
         *  예전에는 버튼만 조용히 사라져 "저장이 어디 갔지" 로 읽혔다. 사용자를 탓하지 않고
         *  여기가 연습하는 곳이라는 사실만 담담히 적는다. */
        // ★ 진짜 방 목에서는 저장이 **화면에만** 남는다 — 그래도 4칸을 밟아야 하므로 버튼은 낸다.
        saveNote: onServer ? '' : (s.sampleMode ? '연습방이라 저장되지 않아요' : '서버가 없어 화면에만 남아요'),
        save: {
          show: onServer || !s.sampleMode,
          label: '성격 저장하기',
          tap: onSavePersona,
          // 고르지 않았으면 보낼 것이 없다. 서버가 성격을 필수로 받는다.
          off: !personaShown[0],
          why: '성격을 하나 이상 골라 주세요',
          /** 저장이 거절된 이유 한 줄. 빈 문자열이면 안 그린다(→ `saveFailLine`). */
          err: s.saveErr,
        },
        toggleLeave,
        leaveLabel: s.leaveOff ? '떠나지 않아요' : '오래 비우면 여행을 가요',
        leaveBw: s.leaveOff ? '2px' : '1px', leaveBd: s.leaveOff ? ACCENT : C.lineHard,
        leaveBg: s.leaveOff ? C.accentSoft : C.paper, leaveFg: s.leaveOff ? C2.accentInk2 : C.ink,
      },
      egg: {
        title: s.cracking ? '지금 나오고 있어요' : (hatchReady ? '다 됐어요' : '부화 중이에요'),
        sub: s.cracking ? '잠시만요.' : (hatchReady ? '이제 만나러 가도 돼요.' : '여울과 놀며 기다려도 돼요.'),
        isCrack: s.cracking, isReady: !s.cracking && hatchReady, isWait: !s.cracking && !hatchReady,
        dots: [0, 1, 2, 3].map((i) => ({ bg: i < hatchN ? ACCENT : '#EBD3C7' })),
        // 목 전용 문구. 진짜 아이면 Egg 화면이 서버가 준 말(`live.step`)로 덮어쓴다.
        stage: hatchReady ? '다 됐어요' : ['그림을 살펴보는 중', '그리는 중', '움직이는 중', '거의 다 됐어요'][Math.min(3, hatchN)],
        count: hatchText,
        cta: s.cracking ? '지금 나오고 있어요' : '지금 만나러 가기',
        ctaBg: hatchReady && !s.cracking ? ACCENT : '#DED6C9',
        ctaFg: hatchReady && !s.cracking ? C.accentInk : C2.dim2,
        hasMsg: !!s.eggMsg, msg: s.eggMsg,
      },
      onb: {
        canBack: s.step > 0,
        dots: STEPS.map((_, i) => ({ w: i === s.step ? '20px' : '6px', bg: i === s.step ? ACCENT : i < s.step ? C.accentDim : '#E3DBCD' })),
        stepKey: STEPS[s.step] as StepKey,
        nameError: s.nameErr,
        upBd: s.uploaded ? ACCENT : ink(.18),
        upBg: s.uploaded ? C.accentSoft : C.paper,
        upLabel: s.uploaded ? '그림을 올렸어요' : '그림 올리기',
        upNote: s.uploaded ? '다시 누르면 바꿀 수 있어요' : 'PNG · JPG · 10MB까지',
        // ★ 올리기 칸의 라벨·잠금은 여기서 정하지 않는다. 목(useYeoul)은 그림이 **실제로**
        //   올라갔는지 모르고 `s.uploaded`(파일을 골랐다) 까지만 안다. 진짜 기준인
        //   `live.imageKey` 는 화면(Onboarding)만 볼 수 있어서 거기서 덮어쓴다.
        cta: ({
          // 랜딩 칸 CTA 는 랜딩 v2 와 **같은 한 벌**(constants.LANDING_COPY)이다.
          landing: LANDING_COPY.cta,
          upload: '다음',
          user: '다 됐어요',
          char: s.petName ? '이 아이로 시작하기' : '이름부터 지어 줘요',
          born: `${s.petName || '아이'}의 방으로 들어가기`,
        } as Record<StepKey, string>)[STEPS[s.step] as StepKey],
        // 옛 온보딩 'user' 칸의 잔재다. STEPS 에 'user' 가 없어 **지금은 도달할 수 없는 길**이고,
        // 되살리려면 문구부터 다시 봐야 한다 — USER_Q.label 은 항목 이름이 아니라 여울의 말이다.
        userFields: USER_Q.map((f) => ({
          label: f.label,
          opts: f.opts.map((o) => ({ text: o.text, pick: pickUser(f.key, o.text), ...sel(s.user[f.key] === o.text) })),
        })),
        extraVal: s.texts.extra ?? '',
        onExtra: onGroupText('extra'),
        // 이름이 아직 없으면 매달린 가운뎃점("· 1일째")이 남지 않게 점째 뺀다.
        bornName: s.petName ? `${s.petName} · 1일째` : '1일째',
        bornTraits: [...(s.picks.persona ?? []), ...(s.picks.tone ?? [])].join(' · ') || '성격은 지내면서 알게 돼요',
      },
      statusText,
    };
  }, [
    // hatchN 은 s 가 아니라 서버(live)에서도 온다 — 빼면 부화가 진행돼도 화면이 안 바뀐다.
    s, es, sv, onServer, live?.careing, live?.chat, live?.chatting, live?.game, live?.guessing, live?.album, hatchN, hatchReady, hatchPct, hatchText, mode, tut, TUT, needStyle, statusText, selRoom, onRice, onSnack, onClean, onBath, onSleep,
    openPlay, openChat, openWall, openSheet, closeWall, closeFrame, saveShot, pickFrame, prevTutor, startGuess, endGuess, quitGuess,
    nextTutor, onAnswerCall, skipTutorStep, finishTutorHere, pickChip, onGroupText, pickUser, askNext, pickTab,
    pushReply, popPostcard, popScenes, toggleDeco, toggleMini, pickWall, pickNeedStyle, pickTime, onAskDraft,
    toggleSick, toggleNotif, toggleLeave, exitSample, goEgg, flash,
    tutIdx, atDone, onFinishTutorial, onSavePersona, noop, live?.resting,
  ]);

  const actions = useMemo(() => ({
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed, shareFrame,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings, enterRoom,
    setMode, nextDay, restart, leaveAccount, setShards, finishRoadmap, showTutorEnd, startTutor, endTutor, skipTutorStep, openPlay, onGuessSide, startGuess, endGuess, quitGuess,
    popPostcard,
    openAuth, closeAuth, passAuth, onSavePersona, onFinishTutorial, pickScene, toggleFloor2, toggleFbPreview, showUnlock,
    devSet, devReset, devUnlock, devExtra, playGift, playScene, pickTime, toggleSick,
    backToSample: () => patch({ screen: 'room' }),
  }), [
    patch, flash, closePop, bottomTap, selRoom, openSheet, closeSheet, openWall, closeWall,
    closeFrame, closeFire, openChat, closeChat, onPet, onRice, onSnack, onClean, onBath, onMed, shareFrame,
    onSleep, onGuess, onSend, onDraft, onAnswerCall, saveShot, enterSample, goEgg, exitSample,
    tapEgg, goStep, onNext, onBack, onUpload, onName, randomName, openNotify, openSettings, enterRoom,
    setMode, nextDay, restart, leaveAccount, setShards, finishRoadmap, showTutorEnd, startTutor, endTutor, skipTutorStep, openPlay, onGuessSide, startGuess, endGuess, quitGuess,
    popPostcard,
    openAuth, closeAuth, passAuth, onSavePersona, onFinishTutorial, pickScene, toggleFloor2, toggleFbPreview, showUnlock,
    devSet, devReset, devUnlock, devExtra, playGift, playScene, pickTime, toggleSick,
  ]);

  return { s, v, actions };
}

export type Yeoul = ReturnType<typeof useYeoul>;
