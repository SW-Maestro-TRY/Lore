// 진짜 서버에 붙는 **한 줄기**만 담당한다 — 그림 올리기 → 초안 → 이름 → 다 될 때까지 지켜보기 →
// 방 화면에 내 아이 그림 끼우기.
//
// ★ 왜 `useYeoul` 안에 넣지 않았나
//   `useYeoul` 은 시안을 눌러 보기 위한 **프론트 전용 목**이다. 거기에 서버를 섞으면
//   "화면이 이상한 것" 과 "서버가 이상한 것" 이 한 덩어리가 되어 판정이 안 된다.
//   그래서 서버는 이 파일 하나에 가두고, 목에는 결과(그림 주소·부화 진행)만 건넨다.
//   그림을 안 올리고 넘어가면 이 훅은 통째로 잠자고 화면은 지금까지처럼 여울로 돈다.
//
// ★ 지금 잇는 것은 **부화까지**다. 돌보기 수치·채팅·앨범은 아직 목이다(2026-09-07 상훈님 결정).
//
// ★ 2026-09-09 새 계약(`프론트-연동-계약-0909.md` 4절) — 펫 만들기가 **두 번으로 갈렸다**.
//     1) 그림을 올린 **그 순간** `draft` → 서버가 캐릭터 시트를 미리 굽기 시작한다
//     2) 이름을 받은 순간 `character` → 격자 생성 시작(알이 흔들리는 자리)
//   사용자가 이름을 짓는 약 74초를 그대로 버는 것이 이 분리의 목적이다.
//   예전처럼 이름까지 다 받고 한 번에 만들면 그 시간이 통째로 버려진다.
'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { assetUrl } from '../../lib/assets';
import { MOTION_FALLBACK, YEOUL_MOTION, motionAliases } from '../constants';
import { BASIC_KEYS, GUESS_HAND_PRELOAD } from './constants';
import {
  answerChat, care, draftPet, getAlbum, getChat, getHatchProgress, getPet, listPets,
  graduationSeen, motionWish, setCharacter, setPersonality, share, sleep as sleepPet, tutorialDone, tutorialSeen, wake as wakePet,
  type Album, type CareAction, type ChatReply, type ChatState, type CharacterInput,
  type HatchProgress, type PetDetail, type Personality,
} from '../../lib/pet';
import { abandonGame, getCurrentGame, guess, startGame, type GameState, type GuessResult, type Side } from '../../lib/game';
import { classifyUploadFailure, uploadFailureLine, uploadImage, type UploadFailure } from '../../lib/upload';
import { readHatchBlocked, type HatchBlocked } from '../../lib/hatchBlocked';
import { ApiError } from '../../lib/api';

/**
 * 눌린 순간 **먼저 얹는 값**(낙관적 갱신). 서버 응답이 오면 그 자리에서 사라지고,
 * 화면은 응답이 준 값으로 다시 그려진다 — 그래서 되감기가 아니라 **덮어쓰기**다.
 *
 * ★ 여기 담는 숫자는 상상이 아니라 **서버 규칙 그대로**다(`PetService.doCare` · `ZzalPet`):
 *   밥 = 배부름 +1·재고 -1 / 간식 = 기분 +1·연속 +1 / 쓰다듬 = 오늘 횟수 +1(3 상한) /
 *   청소 = 흔적 0 / 목욕 = 흔적 0·기분 +1·오늘 목욕함 / 약 = 나음.
 *   간식이 아닌 행동은 전부 **연속 간식을 0 으로 끊는다**(`afterNonSnack`).
 *   그래서 응답이 와도 숫자가 안 바뀌는 것이 정상이고, 다르면 서버 값이 조용히 이긴다.
 *
 * ★ 되감기는 **거절일 때만** 한다. 거절될 버튼은 미리 잠가 두므로(계약 10절) 정상적으로 쓰면
 *   여기까지 오지 않는다. 그래도 다른 기기에서 먼저 눌렀을 수 있어 길을 남긴다.
 */
export interface CareOptimistic {
  action: CareAction;
  fullness?: number;
  happiness?: number;
  trash?: number;
  foodCount?: number;
  snackStreak?: number;
  pets?: number;
  bathDone?: boolean;
  /** 아픔이 나은 것으로 먼저 그린다. */
  healed?: boolean;
}

/**
 * 돌보기 한 번의 결과.
 *
 * ★ `ok` 와 `message` 를 나눈 이유 — **"잠겨서 안 보냈다" 와 "보냈는데 거절당했다" 는 다르다.**
 *   전에는 둘 다 `null`(성공)로 읽혀서, 연타로 두 번째 눌렀을 때 서버에 나가지도 않은 채
 *   "맛있게 먹었어요" 가 떴다. 안 보낸 것은 `ok:false · message:null` 이라 아무 말도 안 한다.
 */
export interface CareResult {
  ok: boolean;
  /** 거절이면 화면에 띄울 한 줄. 안 보냈으면 null. */
  message: string | null;
  /**
   * 서버가 준 **거절 사유 코드**(`ApiError.code`). 화면이 사유별로 다른 말을 하려면
   * 문장이 아니라 이 코드로 갈라야 한다 — 서버 문장이 바뀌어도 안 깨진다(기권 실패와 같은 방식).
   * 코드 없는 401 이 실제로 있어서(→ `common/fe/api/client.ts`) `status` 도 같이 준다.
   */
  code?: string | null;
  status?: number;
}

const GAUGE_MAX = 4;
const PET_PER_DAY = 3;
const up = (n: number) => Math.min(GAUGE_MAX, n + 1);

/** 누른 순간 화면에 먼저 얹을 값. 아직 상태를 못 받았으면 아무것도 안 얹는다. */
function optimisticOf(action: CareAction, pet: PetDetail | null): CareOptimistic | null {
  const g = pet?.gauges ?? null;
  const t = pet?.today ?? null;
  // 간식이 아닌 행동은 연속 간식을 끊는다 — 그 자리도 같이 먼저 그린다.
  const cut = { snackStreak: 0 };
  switch (action) {
    case 'FEED':
      if (!g || !pet?.food) return null;
      return { action, ...cut, fullness: up(g.fullness), foodCount: Math.max(0, pet.food.count - 1) };
    case 'SNACK':
      if (!g || !t) return null;
      return { action, happiness: up(g.happiness), snackStreak: t.snackStreak + 1 };
    case 'PET':
      if (!t) return null;
      return { action, ...cut, pets: Math.min(PET_PER_DAY, t.pets + 1) };
    case 'CLEAN':
      // ★★ **한 번 쓸면 하나**다(서버 `ZzalPet.clean()` = `trash - 1` · 정본 §12 튜토리얼 안내).
      //   먼저 그리는 값이 0 이면, 흔적 2개일 때 **둘 다 사라졌다가** 서버 답이 와서 하나가
      //   되살아난다 — 화면이 서버보다 더 많이 지우는 것처럼 보인다(2026-09-22 상훈님 확인).
      if (!g) return null;
      return { action, ...cut, trash: Math.max(0, g.trash - 1) };
    case 'BATH':
      if (!g) return null;
      return { action, ...cut, trash: 0, happiness: up(g.happiness), bathDone: true };
    case 'MEDICINE':
      return { action, ...cut, healed: true };
    default:
      return null;
  }
}

export interface Live {
  /** 고른 그림(미리보기용). 서버에 올리기 전에도 화면에 보여 준다. */
  previewUrl: string | null;
  /** 올리기가 끝나 받은 키. */
  imageKey: string | null;
  /** 초안이 잡힌 순간부터 있다. 그림을 올리면 바로 생긴다(이름은 아직 없다). */
  petId: number | null;
  pet: PetDetail | null;
  busy: boolean;
  error: string | null;
  /**
   * 그 오류가 **그림 탓인가 우리 쪽 사정인가**(`error` 가 있을 때만 채워진다).
   *
   * ★★ 화면이 이걸 보고 「이런 그림은 어려워요」 예시를 띄울지 말지 정한다. 인프라 실패
   *   (연결 끊김·CORS·S3·5xx)인데 예시가 같이 뜨면 사용자가 **제 그림을 의심한다** —
   *   그림을 서버가 보지도 못한 실패인데도 그렇다. 판정 규칙은 `lib/upload.ts` 한 곳에 있다.
   */
  errorKind: UploadFailure | null;
  /**
   * **부화가 막혔다** — 자리·상한·바깥 한도에 걸려 서버가 거절했다.
   *
   * ★ `error` 와 따로 두는 이유 — 막힘은 **고장이 아니다.** 같은 칸에 담으면 화면이 붉은
   *   오류 한 줄로 그리게 되고, 그러면 "지금은 안 돼요" 가 "망가졌어요" 로 읽힌다(명세 E절).
   *   문구는 `lib/hatchBlocked.ts` 의 표 한 곳이 정한다 — 여기서도, 화면에서도 안 짓는다.
   * ★ 이것이 켜지면 `error` 는 null 이다. 둘 다 켜면 같은 사건이 화면에 두 줄로 뜬다.
   */
  blocked: HatchBlocked | null;
  /**
   * 그림만 올려 둔 채 이름이 아직 없는 아이가 서버에 있다 — "이어서 이름을 지어 주세요".
   * 계약 4절: 이름을 안 짓고 나갔다 오면 `draft` 가 **같은 petId** 를 준다. 이미 구운
   * 시트를 다시 쓰므로 돈이 두 번 안 나간다. 화면은 처음부터 다시 올리게 하면 안 된다.
   */
  draftOnly: boolean;
  /**
   * 이번 방문에서 **두고 간 초안을 찾아 이어붙였다.** 방금 올린 사람과 구분하려고 따로 둔다 —
   * 화면이 "이어서 이름을 지어 주세요" 를 띄울 근거이고, 갓 올린 사람에겐 그 말이 어색하다.
   */
  resumedDraft: boolean;
  /** 부화가 끝났는가(`ALIVE`). */
  ready: boolean;
  /**
   * **방을 그려도 되는가** — 서버가 준 지금 상태를 손에 쥐었는가.
   *
   * ★ 2026-09-10 — 이게 없으면 방이 먼저 그려지고 상태가 나중에 도착해서 게이지가 `0→3` 으로
   *   튄다(상훈님 지적). 목으로 폴백해 메우던 자리라 더 안 보였다. 이제 **폴백을 없애고**
   *   대신 이 값이 켜질 때까지 방을 안 그린다.
   */
  petReady: boolean;
  failed: boolean;
  /** 부화 중 지금 하는 일 한 줄(서버 문구). */
  step: string | null;
  /**
   * 끝난 단계 수와 전체 단계 수 — **둘 다 서버가 준 숫자 그대로**다.
   * 예전에는 "라벨이 바뀐 횟수" 를 셌는데, 그건 전용 API 가 없던 시절의 임시방편이었다.
   */
  progress: number;
  total: number;
  /** 남은 시간(초). 서버가 모르면 0. */
  etaSeconds: number;
  /** 실패했을 때 서버가 보낸 말. */
  message: string | null;
  /**
   * 기본 8종 중 **서버가 그림을 안 준 것**. 있으면 안 되는 상태다(→ `BASIC_KEYS` 주석).
   * ⚠️ 지금 개발 중에는 가짜 생성이 6종만 만들어서 `sick`·`call` 이 늘 여기 담긴다 —
   *   **정상적인 경고**다. 진짜 생성으로 바꾸면 비어야 한다. 이 경고를 지우지 말 것.
   */
  missingBasics: string[];
  /** 서버가 준 내 아이 그림(카탈로그 key). 아직 없으면 null → 화면은 여울로 폴백한다. */
  img: (key: string) => string | null;
  /** 파일 하나를 올린다 — 성공하면 **그 자리에서 초안까지** 잡는다. */
  upload: (file: File) => Promise<void>;
  /**
   * **고른 그림을 아직 안 올리고 손에 들고 있다**(2026-09-19).
   *
   * 가입 창이 여기서 뜬다 — 올리기는 로그인이 필요한 첫 호출이라, 미로그인이면 `presign` 직전에
   * 멈춰 세우고 창을 띄운다. 그동안 `File` 을 잃으면 사용자가 그림을 **다시 고르게** 되므로
   * 여기에 둔다. 미리보기는 곧바로 보여 준다 — 고른 그림이 눈앞에 있어야 "이 그림으로 이어진다"
   * 가 읽힌다.
   */
  pendingUpload: boolean;
  /** 올리지 않고 들고만 있는다. 미리보기는 그 자리에서 뜬다. */
  holdUpload: (file: File) => void;
  /** 들고 있던 그림을 이제 올린다(로그인이 끝난 순간). 없으면 아무 일도 안 한다. */
  resumeUpload: () => Promise<void>;
  /**
   * 들고 있던 그림을 버린다 — **서버에 이미 아이가 있을 때만**.
   * 두고 간 초안을 이어받았는데 들고 있던 그림까지 올리면 초안이 둘이 되고, 굽는 중이면
   * `ZZAL_PET_ALREADY_HATCHING` 에 막힌다.
   */
  discardUpload: () => void;
  /**
   * 이름·성격을 보낸다. 이 순간부터 격자 생성이 돈다.
   * @returns 서버가 받아들였으면 true. **false 면 알 화면으로 넘어가면 안 된다** — 굽고 있지 않다.
   */
  setChar: (input: CharacterInput) => Promise<boolean>;
  /**
   * 지금 도는 돌보기. 있으면 **버튼을 전부 잠근다** — 계약 10절 "누르면 잠그고 기다린다".
   */
  careing: CareAction | null;
  /**
   * 눌린 순간 화면에 먼저 얹은 값. 응답이 오면 null 로 돌아간다.
   * 화면(`useYeoul.es`)은 이것을 서버 값 **위에** 얹어 그린다.
   */
  optimistic: CareOptimistic | null;
  /**
   * 돌보기 한 번. 응답으로 온 상태가 곧 새 화면이다.
   *
   * ★ 2026-09-10 — **먼저 그리고 응답으로 덮는다**(상훈님 지시). 누른 그 순간 게이지가 움직여야
   *   눌린 줄 안다. 계약 10절의 "서버가 준 값으로만 그린다" 와 부딪히지 않게 절충을 이렇게 잡았다:
   *     1) **거절될 버튼은 아예 못 누르게 잠근다** — 거절 자체가 안 나오면 되감을 일이 없다
   *     2) 응답이 오면 낙관값을 버리고 **서버 값으로 덮는다**(다르면 조용히 맞춰진다)
   *     3) 그럼에도 거절이 오면 낙관값을 버리고 서버가 준 말을 띄운다
   * @returns 보냈고 받아들여졌으면 `ok`, 거절이면 `message`, 잠겨서 안 보냈으면 둘 다 비어 있다.
   */
  doCare: (action: CareAction) => Promise<CareResult>;
  /** 재우거나 깨우는 중. 그동안 침실 버튼을 잠근다. */
  resting: boolean;
  /**
   * 재우기·깨우기 한 번. **지금 자고 있으면 깨우고, 아니면 재운다** — 버튼이 하나라 여기서 가른다.
   *
   * ★ 언제 되는지는 **서버가 정한다**(`clock.canSleep` · `canWake`). 화면이 시각을 다시 세지 않는다.
   *   실측(2026-09-10)으로 이 둘은 튜토리얼 낮잠 칸(NAP)과 정확히 맞아떨어졌다 —
   *   NAP 칸에서만 `canSleep`, 재운 뒤 곧바로 `canWake`, 그 밖의 칸에서는 둘 다 false.
   *   그래서 침실 버튼은 `tutorial.step` 을 안 봐도 되고, 졸업 뒤 19~23시 창까지 같은 값으로 덮인다.
   * ★ 낮잠은 튜토리얼 8칸을 넘기는 유일한 길이다(`sleep` **과** `wake` 둘 다 해야 넘어간다 — 실측).
   *   그 전에 미리 써 버리면 튜토리얼이 영영 안 끝나므로 서버가 409 로 막는다.
   */
  doRest: () => Promise<CareResult>;
  /**
   * 성격·세계관 저장. **튜토리얼 4칸(PERSONALITY)을 넘기는 호출**이기도 하다.
   * 언제든 다시 바꿀 수 있다(정본 0장 6).
   */
  savePersonality: (personality: Personality, world?: string) => Promise<CareResult>;
  /**
   * 튜토리얼 마지막 칸. **이 호출이 시계를 켠다** — 이때부터 게이지가 줄고 하루가 흐른다.
   * 9칸을 다 하기 전에 부르면 409 `ZZAL_TUTORIAL_NOT_FINISHED`.
   */
  finishTutorial: () => Promise<CareResult>;
  /**
   * 튜토리얼 **4칸을 "확인했다" 로 넘긴다** — 성격을 안 골라도 된다(→ `lib/pet.tutorialSeen`).
   * 성격을 골라 저장하는 길(`savePersonality`)도 같은 칸을 넘기므로, 둘 중 하나만 부른다.
   */
  tutorialSeen: () => Promise<CareResult>;
  /**
   * 첫날 축하 판을 **봤다고 서버에 남긴다.**
   *
   * ★★ 왜 서버까지 가나 — 이 판은 "사람 기준 한 번" 이어야 한다. 탭 기억만 쓰던 동안에는
   *   새 탭·앱 재시작·다른 기기에서 **또 떴다**(2026-09-22 dev 재현).
   * ★ **던지지 않는다.** 못 남기면 다음에 한 번 더 뜰 뿐이고, 그건 판을 못 띄우는 것보다 낫다.
   *   백엔드가 아직 이 주소를 안 열었으면 404 인데, 그때도 조용히 넘어가 예전처럼 굴러야 한다.
   */
  markGraduationSeen: () => Promise<void>;
  /**
   * 오늘의 부름과 기억. **대사는 전부 여기서 온다** — 화면이 지어내지 않는다(상훈님 지시).
   * 아직 안 읽었거나 서버에 안 붙었으면 null.
   */
  chat: ChatState | null;
  /** 답을 보내는 중. 보내기 버튼을 잠근다. */
  chatting: boolean;
  /**
   * 부름에 답한다. 열린 부름이 없으면 아무 일도 안 한다.
   * @returns `error` 가 있으면 띄울 한 줄, 없으면 `reply` 에 아이가 돌려준 말과 반응 동작.
   */
  sendChat: (text: string) => Promise<{ error: string | null; reply: ChatReply | null }>;
  /**
   * 지금 치고 있는 좌우 맞히기 한 판. 새로고침으로 들어와도 서버가 같은 모양으로 답해서
   * 화면은 "지금 어느 쪽이지" 를 판단하지 않아도 된다.
   */
  game: GameState | null;
  /** 한 번 친 답이 도는 중. 좌·우 버튼을 잠근다. */
  guessing: boolean;
  /** 판 시작(또는 치던 판 잇기). 두 번 불러도 안전하다. */
  startPlay: () => Promise<string | null>;
  /**
   * 한 판 친다. **답은 서버가 쥐고 있다** — 화면이 혼자 이겼다고 정할 수 없다.
   * @returns `error` 면 띄울 한 줄, 아니면 방금 친 결과.
   */
  pickSide: (side: Side) => Promise<{ error: string | null; result: GuessResult | null }>;
  /**
   * 치던 판을 접는다(기권). **인자가 없다** — 어느 펫의 어느 판인지는 이 훅이 들고 있다.
   *
   * ★ 성공하면 `game` 이 **'끝남'** 으로 갱신된다(`playing:false`). 접은 판은 패배로 확정되고
   *   다시 못 친다 — 하루 판수는 시작할 때 이미 깎였으므로 **더 깎지도 돌려주지도 않는다.**
   * ★ **실패하면 던진다**(`ApiError`). 화면이 잡아서 문구를 고른다 —
   *   `ZZAL_GAME_NOT_FOUND`·`ZZAL_GAME_FINISHED`(이미 정리된 판) · `ZZAL_PET_SLEEPING` ·
   *   `ZZAL_TRAVELING`. **네 경우 모두 화면은 판을 닫는다.**
   * ★ **아플 때도 된다** — 서버에 `ZZAL_SICK_REFUSES` 가 없다(의도). 화면도 아플 때 ✕ 를 잠그면 안 된다.
   * ★ 칠 판이 애초에 없으면 아무것도 안 보내고 그냥 끝난다(화면은 어차피 판을 닫는다).
   */
  abandonPlay: () => Promise<void>;
  /**
   * **방금 열린 2층 동작 seq — 본 순간 여기 쌓인다.**
   *
   * ★★ 왜 훅이 들고 있나 — `justUnlocked` 는 **그 응답 한 번에만** 실려 온다(계약 2절
   *   "행동 응답에만"). 화면이 그 순간 다른 판(시트·폭죽·전면판)을 띄우고 있어 못 받으면,
   *   **다음 조회 한 번이 `[]` 로 덮어 해금 판이 영영 안 뜬다.** 그래서 받은 자리에서 쌓아 둔다.
   * ★ 화면은 판을 다 보여 준 뒤 `clearJustUnlocked()` 로 비운다. 비우기 전에는 계속 남는다 —
   *   **판을 못 띄운 채로 닫아도 다음에 방이 조용해지면 그때 뜬다.**
   * ★ 같은 방식이 스크랩북 쪽에 먼저 있다(`lib/usePet.ts`). 여울은 이 훅을 쓰므로 여기에 둔다.
   */
  justUnlocked: number[];
  /** 해금 판을 다 보여 준 뒤 비운다. */
  clearJustUnlocked: () => void;
  /**
   * 펫 응답이 아닌 곳(미니게임)에서 열린 동작을 이 줄에 얹는다.
   * ★ 게임 응답은 `PetDetail` 이 아니라 상태 갱신 길을 안 탄다. 다시 물어서도 못 잡는다 —
   *   조회 응답의 `justUnlocked` 는 늘 비어 있다.
   */
  noteUnlocked: (seqs: number[]) => void;
  /** 앨범(도감 18칸 · 엽서 · 장면 · 첫 선물). 아직 안 읽었으면 null. */
  album: Album | null;
  /** 앨범을 (다시) 읽는다. 벽을 열 때 부른다. */
  loadAlbum: () => Promise<void>;
  /** 동작 하나를 공유한다. 같은 동작을 다시 공유하면 있던 링크가 그대로 온다. */
  shareMotion: (motionKey: string) => Promise<{ error: string | null; url: string | null }>;
  /**
   * 보고 싶은 동작 한 줄을 남긴다.
   *
   * @returns `ok` 가 true 면 서버가 **204** 로 받았다는 뜻이다. `code` 는 화면이 문구를 고르는
   *          데만 쓴다 — 서버 문장을 그대로 띄우지 않는다(말투의 주인이 백엔드로 넘어간다).
   */
  sendWish: (text: string) => Promise<{ ok: boolean; code: string | null }>;
  /** 두고 간 아이가 있는지 서버에 물어본다. 로그인한 뒤에 한 번만 부른다. */
  resume: () => Promise<'draft' | 'hatching' | 'alive' | null>;
  reset: () => void;
}

const EMPTY: Live = {
  previewUrl: null, imageKey: null, petId: null, pet: null, busy: false, error: null, errorKind: null, blocked: null,
  draftOnly: false, resumedDraft: false, careing: null, optimistic: null, resting: false, chat: null, chatting: false,
  game: null, guessing: false, album: null, ready: false, petReady: false, failed: false, step: null,
  justUnlocked: [],
  progress: 0, total: 0, etaSeconds: 0, message: null, missingBasics: [],
  img: () => null,
  pendingUpload: false,
  upload: async () => {}, holdUpload: () => {}, resumeUpload: async () => {}, discardUpload: () => {},
  setChar: async () => false, doCare: async () => ({ ok: false, message: null }),
  doRest: async () => ({ ok: false, message: null }),
  savePersonality: async () => ({ ok: false, message: null }),
  finishTutorial: async () => ({ ok: false, message: null }),
  tutorialSeen: async () => ({ ok: false, message: null }),
  markGraduationSeen: async () => {},
  sendChat: async () => ({ error: null, reply: null }),
  startPlay: async () => null,
  pickSide: async () => ({ error: null, result: null }),
  abandonPlay: async () => {},
  clearJustUnlocked: () => {}, noteUnlocked: () => {},
  sendWish: async () => ({ ok: false, code: 'no_pet' }),
  loadAlbum: async () => {}, shareMotion: async () => ({ error: null, url: null }),
  resume: async () => null, reset: () => {},
};

export function useHatchState(): Live {
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [imageKey, setImageKey] = useState<string | null>(null);
  const [petId, setPetId] = useState<number | null>(null);
  const [pet, setPet] = useState<PetDetail | null>(null);
  /** 손잡이 안에서 "지금 서버가 말한 값" 을 읽을 자리. 값이 바뀔 때마다 손잡이를 새로 만들지 않는다. */
  const petRef = useRef<PetDetail | null>(null);
  petRef.current = pet;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 그 오류가 그림 탓인가 우리 쪽 사정인가. `error` 와 같이 켜지고 같이 꺼진다. */
  const [errorKind, setErrorKind] = useState<UploadFailure | null>(null);
  /**
   * 막힘 안내. 오류(`error`)와 한 짝으로 움직인다 — **한쪽을 켜면 다른 쪽은 끈다.**
   * 같은 사건을 두 줄로 띄우지 않으려는 것이다(→ Live.blocked 머리말).
   */
  const [blocked, setBlocked] = useState<HatchBlocked | null>(null);
  const objectUrl = useRef<string | null>(null);
  /**
   * 아직 안 올린 그림(가입 창을 기다리는 중). **ref 와 상태를 함께** 둔다 —
   * 손잡이(`resumeUpload`)는 옛 껍데기를 들고 불릴 수 있어 ref 로 읽어야 하고,
   * 화면은 "가입하면 이 그림으로 시작해요" 를 그려야 하므로 상태도 필요하다.
   */
  const pendingFile = useRef<File | null>(null);
  const [pendingUpload, setPendingUpload] = useState(false);
  /** 이름을 보냈는가. 이게 켜져야 굽기가 도는 것이므로 그때부터 진행을 묻는다. */
  const [charSet, setCharSet] = useState(false);
  const [hatch, setHatch] = useState<HatchProgress | null>(null);
  const [resumedDraft, setResumedDraft] = useState(false);
  const [careing, setCareing] = useState<CareAction | null>(null);
  /**
   * ★ 연타를 막는 **진짜** 자물쇠. `careing`(상태)만으로는 못 막는다 — 두 번째 클릭이
   *   다시 그려지기 전에 들어오면 그 손잡이는 아직 `careing === null` 인 옛 껍데기를 들고 있어
   *   같은 요청이 두 번 나간다. ref 는 그 자리에서 바뀌므로 같은 틱 안에서도 막힌다.
   */
  const careingRef = useRef<CareAction | null>(null);
  const [optimistic, setOptimistic] = useState<CareOptimistic | null>(null);
  const [chat, setChat] = useState<ChatState | null>(null);
  const [chatting, setChatting] = useState(false);
  const [game, setGame] = useState<GameState | null>(null);
  /**
   * ★ 판을 **그 자리에서** 읽을 자리. `startPlay()` 로 판을 만든 직후 `pickSide` 를 부르면
   *   `game`(상태)은 아직 옛것이라 `gameId` 가 없어 조용히 빠져나갔다 — 그래서
   *   **처음 좌우 맞히기를 누르면 아무 일도 안 일어나고 한 번 더 눌러야 했다**(튜토리얼 6칸이 그 자리다).
   *   상태가 다시 그려지길 기다리는 방식은 같은 함정을 또 밟는다. ref 는 같은 틱에 바뀐다.
   */
  const gameRef = useRef<GameState | null>(null);
  const [guessing, setGuessing] = useState(false);
  /** 좌우 맞히기 연타 자물쇠. `guessing`(상태)만으로는 같은 틱의 두 번째 클릭을 못 막는다. */
  const guessingRef = useRef(false);
  /**
   * 방금 열린 2층 동작 seq 를 **모아 두는 자리**(→ Live.justUnlocked).
   * 화면이 판을 띄우고 비울 때까지 남는다 — 다음 조회가 덮지 못한다.
   */
  const [justUnlocked, setJustUnlocked] = useState<number[]>([]);
  /** 기권 연타 자물쇠. ✕ 를 두 번 누르면 두 번째는 `ZZAL_GAME_FINISHED` 로 튕겨 헛 문구가 뜬다. */
  const abandoningRef = useRef(false);
  /** 판 응답도 늦게 온 옛것이 최신을 덮지 않게. 펫과 같은 순번표를 쓴다. */
  const appliedGame = useRef(0);
  const [album, setAlbum] = useState<Album | null>(null);
  /**
   * 방이 기다려야 하는 **첫 한 벌**이 다 왔는가.
   *
   * ★ 상태(`getPet`)만으로는 모자랐다 — 마당 뱃지(오늘 남은 판)는 `games/current`, 말풍선·부름점은
   *   `chat` 에서 온다. 상태만 기다리고 방을 그렸더니 마당 뱃지가 `(없음) → 2` 로 **281ms 뒤에 튀었다**
   *   (2026-09-10 실측). 늦게 오는 값이 하나라도 있으면 그게 곧 그 자리의 '튐' 이다.
   *   실패해도 켠다 — 못 받은 것 때문에 방이 영영 안 열리면 그건 더 나쁘다.
   */
  const [gameLoaded, setGameLoaded] = useState(false);
  const [chatLoaded, setChatLoaded] = useState(false);
  const [resting, setResting] = useState(false);
  const restingRef = useRef(false);

  /**
   * ── 응답 순서 자물쇠 ─────────────────────────────────────────────
   *
   * ★ 순번은 **보낼 때** 받는다. 도착할 때 받으면 늦게 온 옛 응답도 "가장 최신" 이 되어
   *   방금 받은 새 상태를 조용히 덮어쓴다(그리고 아무 소리도 안 난다).
   *   여기서 겹치는 조합이 실제로 있다 — 살아난 뒤 도는 `getPet` 재시도와 돌보기,
   *   게임을 끝낸 뒤의 `getPet` 과 그 사이에 누른 돌보기.
   *
   * ★ `putPet` 을 거치지 않고 `setPet` 을 직접 부르는 자리를 남기지 말 것.
   *   한 곳만 새면 그 한 곳이 늘 이긴다.
   */
  const issued = useRef(0);
  const applied = useRef(0);
  /**
   * 지금 보고 있는 **로그인 세대**. 로그아웃·초기화 때마다 하나 올라간다.
   *
   * ★ 왜 필요한가 — A 로 로그인해 조회가 날아간 뒤 로그아웃하고 B 로 로그인하면,
   *   **늦게 도착한 A 의 응답**이 그대로 적용돼 앞사람의 아이가 열렸다. 다시 물어보게 하는 것만으로는
   *   이미 날아간 요청을 못 막는다. 세대가 바뀌면 그 응답은 남의 것이므로 버린다.
   */
  const session = useRef(0);
  /** 보낼 때 순번을 받는다. */
  const takeSeq = useCallback(() => ++issued.current, []);
  /**
   * 해금 seq 를 쌓는다. **이미 있는 것은 안 넣는다** — 같은 응답을 두 번 받아도 폭죽이 두 번 안 뜬다.
   * ⚠️ 여기서 비우지 않는다. 비우는 것은 판을 **보여 준** 화면의 몫이다(`clearJustUnlocked`).
   */
  const noteUnlocked = useCallback((seqs: number[]) => {
    if (!seqs || seqs.length === 0) return;
    setJustUnlocked((prev) => {
      const add = seqs.filter((q) => !prev.includes(q));
      return add.length === 0 ? prev : [...prev, ...add];
    });
  }, []);
  const clearJustUnlocked = useCallback(() => setJustUnlocked((prev) => (prev.length === 0 ? prev : [])), []);
  /** 받은 상태를 얹는다. 옛 응답이면 **버린다.** @returns 얹었으면 true */
  const putPet = useCallback((seq: number, next: PetDetail) => {
    if (seq <= applied.current) return false;
    applied.current = seq;
    setPet(next);
    // ★ 위에서 버린 **낡은 응답**의 해금은 안 쌓는다 — 그건 이미 지난 사건이다.
    noteUnlocked(next.justUnlocked ?? []);
    return true;
  }, [noteUnlocked]);
  /** 판도 같은 규칙으로. ref 를 함께 바꿔 **같은 틱에** 읽을 수 있게 한다. */
  const putGame = useCallback((seq: number, next: GameState | null) => {
    if (seq <= appliedGame.current) return false;
    appliedGame.current = seq;
    gameRef.current = next;
    setGame(next);
    return true;
  }, []);

  // 미리보기 주소는 브라우저 메모리를 잡으므로 바뀌거나 떠날 때 놓아 준다.
  useEffect(() => () => { if (objectUrl.current) URL.revokeObjectURL(objectUrl.current); }, []);

  const upload = useCallback(async (file: File) => {
    setBusy(true);
    setError(null);
    setErrorKind(null);
    setBlocked(null);
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = URL.createObjectURL(file);
    setPreviewUrl(objectUrl.current);
    try {
      // ★ 한 key 는 한 번만 쓸 수 있다. 실패하면 presign 부터 다시 — 같은 key 로 재시도하지 않는다.
      const key = await uploadImage(file, 'zzal');
      setImageKey(key);
      // ★ 여기서 곧바로 초안을 잡는다. 이 한 줄이 이름 짓는 시간(약 74초)을 버는 자리다.
      const { petId: id } = await draftPet(key);
      setPetId(id);
    } catch (e) {
      // ★ 실패하면 미리보기도 함께 지운다(상훈님 판정 19). 그림만 크게 남아 있으면
      //   작은 오류 한 줄보다 그림이 먼저 읽혀 성공한 줄 안다.
      setImageKey(null);
      setPetId(null);
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
      objectUrl.current = null;
      setPreviewUrl(null);
      // ★ 막힘이면 **오류로 안 띄운다.** 자리가 없거나 오늘 몫을 다 쓴 것은 고장이 아니고,
      //   붉은 한 줄로 그리면 사용자가 제 그림을 의심한다(명세 E절 "고장으로 안 읽히게").
      const stop = readHatchBlocked(e);
      setBlocked(stop);
      // ★ 문구는 `uploadFailureLine` 이 고른다 — 그냥 `e.message` 를 쓰면 네트워크 단 실패에서
      //   브라우저가 만든 영어("Failed to fetch")가 그대로 화면에 뜬다(실패 주입으로 실측).
      setError(stop ? null : uploadFailureLine(e));
      // ★ 무엇 때문에 실패했는지도 같이 남긴다 — 화면이 예시 안내를 띄울지 이걸로 정한다.
      //   막힘이면 오류가 아니므로 갈래도 비운다(둘 다 켜면 같은 사건이 두 줄로 뜬다).
      setErrorKind(stop ? null : classifyUploadFailure(e));
    } finally {
      setBusy(false);
    }
  }, []);

  /**
   * ★ 2026-09-19 — **올리기 직전에 멈춰 세우는 자리.**
   *
   * `upload` 의 첫 걸음인 `presign` 은 로그인이 필요한 첫 호출이다. 미로그인이면 여기서
   * 파일만 들고 가입 창을 띄우고(창은 화면이 연다), 로그인이 끝나면 `resumeUpload` 가
   * **같은 File 로** 이어서 올린다 — 다시 고르게 하지 않는다.
   */
  const holdUpload = useCallback((file: File) => {
    setError(null);
    setBlocked(null);
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = URL.createObjectURL(file);
    setPreviewUrl(objectUrl.current);
    pendingFile.current = file;
    setPendingUpload(true);
  }, []);

  const resumeUpload = useCallback(async () => {
    const file = pendingFile.current;
    if (!file) return;
    // 먼저 비운다 — `upload` 를 기다리는 동안 두 번 불려도 같은 파일이 두 번 나가지 않는다.
    pendingFile.current = null;
    setPendingUpload(false);
    await upload(file);
  }, [upload]);

  const discardUpload = useCallback(() => {
    if (!pendingFile.current) return;
    pendingFile.current = null;
    setPendingUpload(false);
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null;
    setPreviewUrl(null);
  }, []);

  /**
   * 이름·성격을 보낸다 = 격자 생성 시작.
   *
   * ★ 계약 4절 — **그림 생성에 들어가는 것은 `note` 뿐**이다. `personality`·`world` 는
   *   대사 톤에만 쓰이고, 격자 프롬프트의 정체성 문단은 올린 그림에서 뽑는다.
   *   그래서 말투·장르 칩은 여기 안 싣는다(보낼 자리가 없고, 실어도 그림엔 영향이 없다).
   */
  const setChar = useCallback(async (input: CharacterInput): Promise<boolean> => {
    // 이미 보냈으면 성공으로 친다 — 두 번 보내지 않되 화면은 앞으로 가야 한다.
    if (charSet) return true;
    if (!petId) return false;
    setBusy(true);
    setError(null);
    setBlocked(null);
    try {
      const created = await setCharacter(petId, input);
      setCharSet(true);
      setHatch({
        phase: created.phase, label: null, progress: 0, total: 0,
        estimatedSeconds: created.estimatedSeconds, message: null,
      });
      return true;
    } catch (e) {
      // 막기는 **이 호출에도** 걸린다 — 돈이 나가기 시작하는 자리가 여기라서다.
      // 어느 걸음에서 막히든 사용자가 보는 안내는 같아야 하므로 두 자리 다 같은 표를 읽는다.
      const stop = readHatchBlocked(e);
      setBlocked(stop);
      setError(stop ? null : e instanceof Error ? e.message : '부화를 시작하지 못했어요');
      return false;
    } finally {
      setBusy(false);
    }
  }, [petId, charSet]);

  /**
   * 돌보기 한 번.
   *
   * ★ 누른 순간 **낙관값을 먼저 얹고**, 응답이 오면 서버 값으로 덮는다(2026-09-10 상훈님 지시).
   *   덮는 것과 낙관값을 버리는 것은 **같은 틱**에서 한다 — 나눠서 하면 그 사이 한 프레임 동안
   *   옛 값이 비쳐 오히려 깜빡인다.
   * ★ 거절이면 낙관값을 버리고, 서버가 지금 무엇을 참으로 아는지 다시 받아 그린다
   *   (폰·PC 를 같이 켜 둔 경우가 여기다). 그리고 서버가 준 말을 그대로 돌려준다.
   * ★ 401 은 여기서 다루지 않는다 — 공통 클라이언트가 갱신을 시도하고, 그래도 안 되면
   *   로그인 창을 여는 것은 바깥의 일이다.
   */
  const doCare = useCallback(async (action: CareAction): Promise<CareResult> => {
    if (!petId) return { ok: false, message: null };
    // 도는 동안 들어온 두 번째 클릭 — **아무 말도 하지 않는다.** 잠긴 버튼이 이미 말하고 있다.
    if (careingRef.current) return { ok: false, message: null };
    careingRef.current = action;
    setCareing(action);
    setOptimistic(optimisticOf(action, petRef.current));
    const seq = takeSeq();
    try {
      const next = await care(petId, action);
      putPet(seq, next);
      setOptimistic(null);
      return { ok: true, message: null };
    } catch (e) {
      setOptimistic(null);
      const back = takeSeq();
      try { putPet(back, await getPet(petId)); } catch { /* 이것마저 실패하면 화면은 그대로 둔다 */ }
      return { ok: false, message: e instanceof Error ? e.message : '지금은 할 수 없어요' };
    } finally {
      careingRef.current = null;
      setCareing(null);
    }
  }, [petId, takeSeq, putPet]);

  /**
   * 재우기·깨우기. 지금 자고 있으면 깨우고, 아니면 재운다.
   *
   * ★ **언제 되는지는 서버가 정한다**(`clock.canSleep`·`canWake`). 화면이 "저녁 7시" 를 다시 세지 않는다 —
   *   튜토리얼 중에는 시계가 아예 안 흐르므로 시각으로 판단하면 반드시 틀린다.
   * ★ 낮잠은 튜토리얼 8칸(NAP)을 넘기는 유일한 길이고, **재우기만으로는 안 넘어간다.
   *   깨워야 넘어간다**(2026-09-10 실측 — 계약 문서와 다른 자리다).
   * ★ 되감기는 없다. 다른 돌보기와 달리 낙관값도 안 얹는다 — 잠드는 연출은 방 전체가 바뀌는 일이라
   *   틀렸을 때 되돌리면 커튼이 열렸다 닫힌다.
   */
  const doRest = useCallback(async (): Promise<CareResult> => {
    if (!petId) return { ok: false, message: null };
    if (restingRef.current) return { ok: false, message: null };
    restingRef.current = true;
    setResting(true);
    const seq = takeSeq();
    const asleep = !!petRef.current?.clock?.sleeping;
    try {
      putPet(seq, await (asleep ? wakePet(petId) : sleepPet(petId)));
      return { ok: true, message: null };
    } catch (e) {
      const back = takeSeq();
      try { putPet(back, await getPet(petId)); } catch { /* 못 읽으면 화면은 그대로 */ }
      return { ok: false, message: e instanceof Error ? e.message : '지금은 재울 수 없어요' };
    } finally {
      restingRef.current = false;
      setResting(false);
    }
  }, [petId, takeSeq, putPet]);

  /** 서버로 보내고 응답(=최신 상태)을 얹는 작은 틀. 성격 저장·튜토리얼 마무리가 같은 모양이라 묶었다. */
  const send = useCallback(async (call: () => Promise<PetDetail>, fallback: string): Promise<CareResult> => {
    if (!petId) return { ok: false, message: fallback, code: 'ZZAL_PET_NOT_FOUND' };
    const seq = takeSeq();
    try {
      putPet(seq, await call());
      return { ok: true, message: null };
    } catch (e) {
      return {
        ok: false,
        message: e instanceof Error ? e.message : fallback,
        code: e instanceof ApiError ? e.code : null,
        status: e instanceof ApiError ? e.status : undefined,
      };
    }
  }, [petId, takeSeq, putPet]);

  const savePersonality = useCallback((personality: Personality, world?: string) => (
    send(() => setPersonality(petId as number, personality, world), '성격을 저장하지 못했어요')
  ), [petId, send]);

  const finishTutorial = useCallback(() => (
    send(() => tutorialDone(petId as number), '아직 배울 것이 남았어요')
  ), [petId, send]);

  const seenTutorial = useCallback(() => (
    send(() => tutorialSeen(petId as number), '지금은 넘어갈 수 없어요')
  ), [petId, send]);

  /**
   * 첫날 축하 판을 봤다고 남긴다(→ Live.markGraduationSeen).
   *
   * ★ `send` 를 안 쓴다 — 그쪽은 실패를 화면 문구로 만들어 주는 길인데, 이 기록은 **사용자가
   *   시킨 일이 아니라** 화면이 알아서 남기는 것이라 실패를 보여 줄 자리가 없다. 조용히 삼킨다.
   * ★ 남긴 뒤 상태를 다시 읽어 `graduationSeenAt` 을 손에 쥔다 — 그래야 같은 세션에서
   *   조회가 한 번 더 돌아도 판이 다시 안 뜬다.
   */
  const markGraduationSeen = useCallback(async () => {
    if (!petId) return;
    try {
      await graduationSeen(petId);
      const seq = takeSeq();
      try { putPet(seq, await getPet(petId)); } catch { /* 못 읽어도 기록은 남았다 */ }
    } catch { /* 못 남겼으면 다음에 한 번 더 뜬다 — 화면은 그대로 간다 */ }
  }, [petId, takeSeq, putPet]);

  /**
   * 부름에 답하기.
   *
   * ★ 응답 모양이 다른 유일한 행동이다 — `lib/pet.ts` 가 `{pet, chatReply}` 를 풀어 주므로
   *   여기서는 다른 행동과 똑같이 `PetDetail` 하나로 받는다.
   * ★ 답한 뒤 오늘의 부름을 다시 읽는다 — 방금 한 말과 아이가 돌려준 말이 거기 쌓인다.
   */
  const sendChat = useCallback(async (text: string) => {
    const slot = chat?.openSlot ?? pet?.chatSummary?.openSlot ?? null;
    if (!petId || !slot || chatting || !text) return { error: null, reply: null };
    setChatting(true);
    const seq = takeSeq();
    try {
      const next = await answerChat(petId, slot, text);
      // `pet` 이 바뀌면 아래 효과가 오늘의 부름을 다시 읽는다 — 여기서 또 부르면 두 번 나간다.
      putPet(seq, next);
      return { error: null, reply: next.chatReply };
    } catch (e) {
      // 슬롯이 닫혔거나(다른 기기에서 답함) 시간이 지난 경우. 지금 참인 것을 다시 받아 그린다.
      try { setChat(await getChat(petId)); } catch { /* 그래도 안 되면 화면은 그대로 */ }
      return { error: e instanceof Error ? e.message : '지금은 말을 걸 수 없어요', reply: null };
    } finally {
      setChatting(false);
    }
  }, [petId, chat?.openSlot, pet, chatting]);

  // ── 놀이 · 앨범 · 공유 ──────────────────────────────────────────
  const startPlay = useCallback(async (): Promise<string | null> => {
    if (!petId) return null;
    try {
      // ★ `putGame` 이 ref 도 함께 바꾼다 — 바로 뒤에 `pickSide` 를 불러도 판을 찾을 수 있다.
      const started = await startGame(petId, 'LEFT_RIGHT');
      putGame(takeSeq(), started);
      // 놀람은 판을 **시작하는 것만으로** 열린다. 게임 응답은 PetDetail 이 아니라 상태 갱신 길을
      // 안 타므로, 여기서 직접 쌓지 않으면 그 판을 영영 못 띄운다.
      noteUnlocked(started.justUnlocked ?? []);
      // ★ 판을 **시작하는 것만으로** 튜토리얼 6칸이 넘어간다(2026-09-10 실측).
      //   그런데 이 응답은 `GameState` 라 펫이 안 들어 있어, 다시 읽지 않으면 화면의 칸이 안 넘어간다.
      //   ⚠️ 치던 판이 있으면 서버가 그 판을 그대로 주고 칸을 **안** 넘긴다 — 그것도 실측이다.
      const seq = takeSeq();
      try { putPet(seq, await getPet(petId)); } catch { /* 못 읽어도 판은 시작됐다 */ }
      return null;
    } catch (e) { return e instanceof Error ? e.message : '지금은 못 놀아요'; }
  }, [petId, takeSeq, putPet, putGame, noteUnlocked]);

  const pickSide = useCallback(async (side: Side) => {
    // ★ 상태가 아니라 ref 를 본다 — 방금 시작한 판도 여기서 바로 잡힌다.
    const id = gameRef.current?.gameId;
    if (!petId || !id) return { error: null, result: null };
    // ★ 같은 틱에 두 번 눌러도 한 번만 나간다. 상태(`guessing`)는 다시 그려진 뒤에야 바뀌어서
    //   두 번째 클릭이 그대로 통과했고, 한 번 누른 셈인데 라운드가 두 칸 갔다.
    if (guessingRef.current) return { error: null, result: null };
    guessingRef.current = true;
    setGuessing(true);
    try {
      const r = await guess(petId, id, side);
      // 판이 끝났으면 `playing` 이 꺼진 새 상태를 받아 둔다 — 다음 판은 다시 시작해야 한다.
      const g = gameRef.current;
      if (g) {
        putGame(takeSeq(), { ...g, round: r.nextRound, hits: r.hits, playing: !r.finished, remainingToday: r.remainingToday, runUnlocked: r.runUnlocked });
      }
      noteUnlocked(r.justUnlocked ?? []);
      // 이긴 판은 기분이 오른다. 그 값은 펫 상태에 있으므로 다시 읽어 화면을 맞춘다.
      if (r.finished) { const seq = takeSeq(); try { putPet(seq, await getPet(petId)); } catch { /* 못 읽어도 판 결과는 보여 준다 */ } }
      return { error: null, result: r };
    } catch (e) {
      const seq = takeSeq();
      try { putGame(seq, await getCurrentGame(petId)); } catch { /* 화면은 그대로 둔다 */ }
      return { error: e instanceof Error ? e.message : '지금은 못 쳐요', result: null };
    } finally {
      guessingRef.current = false;
      setGuessing(false);
    }
  }, [petId, takeSeq, putPet, putGame, noteUnlocked]);

  /**
   * 기권 — 치던 판을 접는다. **인자가 없다**(→ Live.abandonPlay).
   *
   * ★ 성공하면 판을 **끝난 것**으로 갈아 둔다. 모양은 `games/current` 가 판 없이 답할 때와 같게 맞춘다
   *   (`playing:false` 면 `gameId`·`round`·`hits` 는 null — `GameState` 머리말) — 그래야 새로고침으로
   *   들어온 화면과 방금 접은 화면이 **같은 것을 본다.**
   * ★ **실패는 그대로 던진다.** 여기서 삼키면 화면이 "접었다" 로 읽고 판을 닫는데 서버에는 판이
   *   살아 있게 된다. 문구를 고르는 것은 화면의 몫이다.
   * ★ 보상이 없는 행동이지만 **상태는 다시 읽는다** — 마당 뱃지(오늘 남은 판)와 게이지가
   *   그 자리에서 맞아야 한다. 못 읽어도 접은 것은 접은 것이라 조용히 넘어간다.
   */
  const abandonPlay = useCallback(async (): Promise<void> => {
    const id = gameRef.current?.gameId;
    // 칠 판이 없으면 보낼 것이 없다. 서버도 이 경우 ZZAL_GAME_NOT_FOUND 를 주고 화면은 판을 닫으므로,
    // 없는 판을 서버에 물어 404 를 만들어 낼 이유가 없다.
    if (!petId || !id) { putGame(takeSeq(), null); return; }
    // ✕ 를 두 번 누르면 두 번째는 ZZAL_GAME_FINISHED 로 튕긴다 — 같은 틱에 막는다(→ pickSide 와 같은 함정).
    if (abandoningRef.current) return;
    abandoningRef.current = true;
    try {
      const r = await abandonGame(petId, id);
      putGame(takeSeq(), {
        playing: false, gameId: null, kind: null, round: null, hits: null,
        rounds: r.rounds, winAt: r.winAt, remainingToday: r.remainingToday,
        justUnlocked: [], runUnlocked: r.runUnlocked,
      });
      const seq = takeSeq();
      try { putPet(seq, await getPet(petId)); } catch { /* 못 읽어도 판은 접혔다 */ }
    } finally {
      abandoningRef.current = false;
    }
  }, [petId, takeSeq, putPet, putGame]);

  const loadAlbum = useCallback(async () => {
    if (!petId) return;
    try { setAlbum(await getAlbum(petId)); } catch { /* 못 읽으면 도감은 펫 상태의 18칸으로 그린다 */ }
  }, [petId]);

  /**
   * 보고 싶은 동작 한 줄.
   *
   * ★★ **성공 응답에 본문이 없다(204).** 공통 클라이언트는 본문을 `res.text()` 로 한 번만 읽고
   *   비어 있으면 봉투를 null 로 두므로 그대로 성공으로 흘러간다. 여기서 응답을 따로 파싱하지
   *   않는 것이 중요하다 — `res.json()` 류가 끼면 **성공한 요청이 실패로 뒤집히고**, 화면은
   *   안 보낸 줄 알고 사용자가 한 번 더 보낸다.
   * ★ 401 은 봉투에 코드가 없을 수 있어 **상태로 가른다**(`ApiError.isUnauthorized` 와 같은 기준).
   */
  const sendWish = useCallback(async (text: string) => {
    if (!petId) return { ok: false, code: 'no_pet' };
    try {
      await motionWish(petId, text);
      return { ok: true, code: null };
    } catch (e) {
      if (!(e instanceof ApiError)) return { ok: false, code: null };
      return { ok: false, code: e.isUnauthorized ? 'unauthorized' : e.code ?? String(e.status) };
    }
  }, [petId]);

  const shareMotion = useCallback(async (motionKey: string) => {
    if (!petId) return { error: null, url: null };
    try {
      const seq = takeSeq();
      const r = await share(petId, motionKey, 'SHARE');
      putPet(seq, r.pet);
      return { error: null, url: r.url };
    } catch (e) {
      return { error: e instanceof Error ? e.message : '지금은 공유할 수 없어요', url: null };
    }
  }, [petId]);

  /**
   * 두고 간 아이 찾기. 로그인 직후 한 번 부른다.
   *
   * `DRAFT` = 그림만 올리고 이름을 안 지은 아이 → 캐릭터 칸부터 이어서.
   * `HATCHING` = 이름까지 지어 굽는 중인 아이 → 알 화면으로. 이걸 안 받아 주면 다시 올리려다
   *   `ZZAL_PET_ALREADY_HATCHING` 에 막혀 갈 데가 없어진다.
   */
  const resume = useCallback(async (): Promise<'draft' | 'hatching' | 'alive' | null> => {
    // 물어본 시점의 세대. 답이 오는 사이에 로그아웃했으면 이 답은 **남의 것**이다.
    const era = session.current;
    const mineStill = () => era === session.current;
    try {
      const mine = await listPets();
      if (!mineStill()) return null;
      const draft = mine.find((p) => p.phase === 'DRAFT');
      if (draft) { setPetId(draft.petId); setResumedDraft(true); return 'draft'; }
      const baking = mine.find((p) => p.phase === 'HATCHING');
      if (baking) { setPetId(baking.petId); setCharSet(true); return 'hatching'; }
      // 이미 함께 살고 있는 아이. 온보딩을 다시 태우지 않고 방으로 보낸다.
      // ★ 이걸 안 하면 다시 들어올 때마다 머리줄이 목 값(12일째·친밀도 40%)으로 돌아간다.
      const living = mine.find((p) => p.phase === 'ALIVE');
      if (living) {
        // ★ 2026-09-10 상훈님 지시 — **목록을 받은 김에 그 자리에서 상태까지 받는다.**
        //   방을 그리기 전에 손에 쥐고 있어야 게이지가 튀지 않는다. 목록도 같은 `Detail` 이지만
        //   방이 보는 값의 출처를 `getPet` 한 곳으로 모아 둔다 — 나중에 둘이 갈라져도
        //   "방은 무엇을 보고 그렸나" 가 한 줄로 남는다. 못 받으면 목록 값으로 간다.
        //   ★ 한 벌을 **한꺼번에** 받는다(병렬). 하나씩 받으면 받는 순서대로 화면이 한 칸씩 채워져
        //     결국 같은 튐이 된다.
        const [detail, g, c] = await Promise.all([
          getPet(living.petId).catch(() => living),
          getCurrentGame(living.petId).catch(() => null),
          getChat(living.petId).catch(() => null),
        ]);
        if (!mineStill()) return null;
        setPetId(living.petId); setCharSet(true); putPet(takeSeq(), detail);
        if (g) putGame(takeSeq(), g);
        if (c) setChat(c);
        setGameLoaded(true); setChatLoaded(true);
        setHatch({ phase: 'ALIVE', label: null, progress: 0, total: 0, estimatedSeconds: 0, message: null });
        return 'alive';
      }
    } catch {
      // 못 물어본 것으로 화면을 막지 않는다. 처음부터 시작하면 된다.
    }
    return null;
  }, [takeSeq, putPet, putGame]);

  // ── 부화 지켜보기 ──────────────────────────────────────────────
  // 무거운 `getPet` 대신 **전용 API** 를 3초마다. 끝나면 스스로 멈춘다.
  const phase = hatch?.phase ?? null;
  const watching = !!petId && charSet && phase !== 'ALIVE' && phase !== 'FAILED' && phase !== 'DEAD';
  useEffect(() => {
    if (!watching || !petId) return;
    let alive = true;
    const look = async () => {
      try {
        const next = await getHatchProgress(petId);
        if (alive) setHatch(next);
      } catch {
        // 한 번 못 읽은 것으로 화면을 깨뜨리지 않는다. 다음 차례에 다시 묻는다.
      }
    };
    look();
    const t = setInterval(look, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [watching, petId]);

  // 다 됐을 때 무거운 쪽을 부른다 — 그림 주소(`motions[].basicImageKey`)가 거기 있다.
  //
  // ★ 한 번 실패하면 다시 부른다(2026-09-10). 전에는 한 번만 부르고 실패를 삼켰는데,
  //   방을 그 값이 올 때까지 안 그리기로 한 뒤로는 **그 한 번을 놓치면 방이 영영 안 열린다.**
  //   (지연·실패를 주입해 보고 알았다.) 3초 간격으로 다시 묻고, 받으면 스스로 멈춘다.
  useEffect(() => {
    if (phase !== 'ALIVE' || !petId || pet) return;
    let alive = true;
    const look = () => {
      const seq = takeSeq();
      void getPet(petId).then((d) => { if (alive) putPet(seq, d); }).catch(() => {});
    };
    look();
    const t = setInterval(look, 3000);
    return () => { alive = false; clearInterval(t); };
  }, [phase, petId, pet]);

  // 오늘의 부름 읽기. 아이가 살아난 뒤, 그리고 **무언가 한 뒤마다** 다시 읽는다.
  //
  // ⚠️ `Detail.chatSummary.openSlot` 을 못 믿는다 — 튜토리얼 부름(BABY)이 열려 있어도
  //   거기는 계속 null 이다(2026-09-09 실측: 밥·쓰다듬 뒤 `GET /chat` 은 openSlot=BABY 인데
  //   같은 순간 `GET /pets/{id}` 는 null). 그래서 부름이 왔는지 알려면 `/chat` 을 읽어야 한다.
  //   가벼운 응답이고 돌보기 한 번에 한 번뿐이라 그만한 값은 한다. 서버가 요약을 맞춰 주면
  //   그때 이 자리를 `chatSummary` 로 되돌린다.
  const living = pet?.phase === 'ALIVE';
  useEffect(() => {
    if (!petId || !living) return;
    let alive = true;
    void getChat(petId)
      .then((c) => { if (alive) setChat(c); })
      .catch(() => {})
      .finally(() => { if (alive) setChatLoaded(true); });
    return () => { alive = false; };
  }, [petId, living, pet]);

  // 치던 판과 앨범은 아이가 살아난 뒤 한 번만 읽는다 — 돌보기마다 다시 읽을 값이 아니다.
  // (판은 칠 때마다 응답으로 갱신되고, 앨범은 벽을 열 때 `loadAlbum` 으로 다시 읽는다)
  useEffect(() => {
    if (!petId || !living) return;
    let alive = true;
    const seq = takeSeq();
    void getCurrentGame(petId)
      .then((g) => { if (alive) putGame(seq, g); })
      .catch(() => {})
      .finally(() => { if (alive) setGameLoaded(true); });
    // 앨범은 기다리지 않는다 — 못 받아도 펫 상태의 같은 18칸으로 **같은 숫자**가 나온다(튀지 않는다).
    void getAlbum(petId).then((a) => { if (alive) setAlbum(a); }).catch(() => {});
    return () => { alive = false; };
  }, [petId, living, takeSeq, putGame]);

  /**
   * 기본 8종을 미리 받아 둔다(상훈님 2026-09-10 승인).
   *
   * ★ 왜 — 지금은 그 동작을 **처음 지을 때** 그제야 받아와서 첫 재생이 한 박자 늦는다.
   *   밥을 주면 먹는 자세로 바뀌어야 하는데 그림이 그때 도착한다.
   * ★ 헛되이 받는 것이 없다 — 기본 8종은 방에 들어온 시점에 전부 있는 것이 보장이고
   *   (`missingBasics` 가 그걸 감시한다), 여덟 장 다 쓰인다. 한 장 27KB 남짓.
   * ★ 응답에 `Cache-Control: max-age=31536000, immutable` 이 붙어 있어 두 번 받지 않는다.
   * ★ **실패해도 조용히 넘어간다.** 미리 받기는 편의일 뿐이라 화면을 막으면 안 된다.
   */
  // ⚠️ `pet` 객체가 아니라 **그림 키 목록**에 반응해야 한다. 돌보기 응답마다 `pet` 이 새 객체가 되는데,
  //   거기 매달면 밥 한 번에 미리 받기가 통째로 다시 돌고, 받던 것을 끊어 오히려 느려진다.
  const basicKeys = pet?.phase === 'ALIVE'
    // 이름이 바뀌는 중이라 **별칭 중 있는 것**을 집는다(→ missingBasics 주석).
    ? BASIC_KEYS.map((k) => motionAliases(k).map((a) => pet.motions?.find((m) => m.key === a)?.basicImageKey).find(Boolean) ?? '').join('|')
    : '';
  const preloaded = useRef('');
  useEffect(() => {
    if (!basicKeys || preloaded.current === basicKeys) return;
    preloaded.current = basicKeys;
    // 받아만 두면 브라우저 캐시에 남는다. 끊지 않는다 — 끊으면 미리 받은 의미가 없다.
    basicKeys.split('|').filter(Boolean).forEach((k) => { new Image().src = assetUrl(k); });
  }, [basicKeys]);

  /**
   * **좌우 맞히기의 펼친 손 네 장을 미리 받아 둔다**(2026-09-21 판정 4).
   *
   * ★ 왜 여기인가 — 이 훅은 여울 화면(`skins/Yeoul.tsx`) 맨 위에서 한 번 만들어져 **연습방과
   *   진짜 방을 모두 덮는다.** 손 그림은 두 방이 **같은 정적 파일**을 쓰므로 한 번만 데우면 된다.
   * ★ 왜 판이 뜰 때가 아니라 들어올 때인가 — 판이 뜬 뒤에 받기 시작하면 **첫 탭과 경주**가 된다.
   *   네 장 다 합쳐 100KB 남짓이고 `max-age=31536000, immutable` 이라 두 번 받지 않는다.
   * ★ 실패해도 조용히 넘어간다 — 미리 받기는 편의일 뿐이라 화면을 막으면 안 된다.
   *   못 받았으면 그때 `<img>` 가 평소처럼 받는다(지금과 같아질 뿐 더 나빠지지 않는다).
   */
  // ★★ 받아 둔 `Image` 를 **붙잡고 있는다.** 기본 8종처럼 `new Image().src = …` 만 하고 놓아 주면
  //   그 객체가 치워지면서 브라우저의 **메모리 그림 칸**에서도 함께 빠진다. 그러면 개발 서버처럼
  //   `max-age=0` 을 주는 곳에서는 공개하는 순간 **20KB 를 다시 받는다**(실측: 미리 받고도 200 응답).
  //   네 장뿐이라 붙잡는 값이 싸고, 붙잡으면 그 왕복이 통째로 사라진다.
  const handsHeld = useRef<HTMLImageElement[]>([]);
  useEffect(() => {
    if (handsHeld.current.length) return;
    handsHeld.current = GUESS_HAND_PRELOAD.filter(Boolean).map((src) => {
      const im = new Image();
      im.src = src;
      return im;
    });
  }, []);

  /**
   * 카탈로그 key 하나를 **내 아이 그림 주소**로. 아직 못 받았으면 null.
   * ★ 18 동작 전부를 받는다 — 서버 `Motion.key` 와 우리 key 는 같은 이름이라 표가 필요 없다.
   */
  const img = useCallback((key: string) => {
    const m = pet?.motions?.find((x) => x.key === key);
    return m?.basicImageKey ? assetUrl(m.basicImageKey) : null;
  }, [pet]);

  const reset = useCallback(() => {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null;
    // 들고 있던 그림도 함께 놓는다 — 로그아웃한 화면에 **앞사람이 고른 그림**이 남으면 안 된다.
    pendingFile.current = null;
    setPendingUpload(false);
    // ★ 번호를 0 으로 되돌리지 않는다. **지금까지 나간 것을 전부 지난 것으로 만든다** —
    //   되돌리면 날아가 있던 옛 응답이 다시 '최신' 이 되어 들어온다.
    applied.current = issued.current;
    appliedGame.current = issued.current;
    session.current += 1;
    setPreviewUrl(null); setImageKey(null); setPetId(null); setPet(null); setError(null); setErrorKind(null); setBlocked(null);
    setCharSet(false); setHatch(null); setResumedDraft(false); setOptimistic(null);
    setGameLoaded(false); setChatLoaded(false);
    setChat(null); putGame(takeSeq(), null); setAlbum(null);
    // 앞사람의 해금 판이 다음 사람 화면에 뜨면 안 된다.
    setJustUnlocked([]);
  }, [takeSeq, putGame]);

  return {
    previewUrl, imageKey, petId, pet, busy, error, errorKind, blocked,
    // 초안은 아직 부화가 아니다 — 이름을 받아야 굽기가 시작된다.
    draftOnly: !!petId && !charSet,
    resumedDraft: resumedDraft && !charSet,
    ready: phase === 'ALIVE',
    // 방을 그려도 되는 순간 = 서버가 준 지금 상태(게이지 포함)를 들고 있을 때.
    petReady: pet?.phase === 'ALIVE' && !!pet.gauges && gameLoaded && chatLoaded,
    failed: phase === 'FAILED' || phase === 'DEAD',
    step: hatch?.label ?? null,
    progress: hatch?.progress ?? 0,
    total: hatch?.total ?? 0,
    etaSeconds: hatch?.estimatedSeconds ?? 0,
    message: hatch?.message ?? null,
    // ★ 별칭으로 센다(2026-09-13) — 서버가 `shy`→`pet` 처럼 이름을 바꾸는 중이라, 이름 하나로만
    //   세면 **바뀐 날부터 여덟 개가 전부 '없음'** 으로 잡혀 경고가 거짓말을 한다.
    missingBasics: pet?.phase === 'ALIVE'
      ? BASIC_KEYS.filter((k) => !motionAliases(k).some((a) => pet.motions?.some((m) => m.key === a && m.basicImageKey)))
      : [],
    careing, optimistic, resting, chat, chatting, game, guessing, album,
    pendingUpload,
    img, upload, holdUpload, resumeUpload, discardUpload,
    justUnlocked,
    setChar, doCare, doRest, savePersonality, finishTutorial, tutorialSeen: seenTutorial, markGraduationSeen, sendChat, startPlay, pickSide, abandonPlay,
    clearJustUnlocked, noteUnlocked,
    loadAlbum, shareMotion, sendWish, resume, reset,
  };
}

const LiveContext = createContext<Live>(EMPTY);
export const LiveProvider = LiveContext.Provider;

/** 화면 어디서나 쓴다. 서버에 안 붙은 상태(그림 없이 계속)면 전부 null 이라 폴백이 걸린다. */
export function useLive(): Live {
  return useContext(LiveContext);
}



// ── 발밑 여백 재기 ──────────────────────────────────────────────────────
//
// ★ 왜 상수로 두면 안 되는가
//   배경을 지운 그림은 발 아래가 비어 있고, 그 여백이 **그림마다 다르다**.
//   여울 시연본은 350 중 54px(15.4%)인데, 서버가 만든 아이는 30px(8.6%)이었다(2026-09-07 실측).
//   상수 하나로 내리면 어떤 아이는 뜨고 어떤 아이는 바닥에 잠긴다 — 그리고 **아무 소리도 안 난다.**
//   그래서 그림에서 직접 잰다. 못 재면 넘겨받은 기본값으로 되돌아간다.
//
// ★ 지금은 **같은 출처의 그림만** 잰다. 생성된 그림은 CloudFront 에서 오는데 그쪽이 CORS 헤더를
//   안 줘서 캔버스로 읽는 순간 막히고, 콘솔에 오류만 쌓인다(2026-09-07 실측). 시도조차 안 하는
//   편이 조용하다. `/images/*` 를 같은 출처로 넘기는 프록시가 생기면 그때 저절로 켜진다.
//   그때까지 생성된 아이는 여울 기준값으로 앉는다 — 상훈님 결정(2026-09-07): 지금은 이대로 간다.
/**
 * 그림 위·아래의 **투명 여백 비율**. 한 번 훑어 둘 다 잰다.
 *
 * ★ 위쪽(`top`)을 같이 재게 된 이유(2026-09-20) — 서버 앵커가 없는 아이(옛 펫·목)는 머리끝을
 *   **고정 앵커표**로 잡는데, 그 표는 옛 판 그림에서 잰 값이라 지금 그림과 어긋난다. 실측에서
 *   그 어긋남이 44.5px 이었고, 말풍선이 딱 그만큼 얼굴을 덮었다(진짜 방 1200 에서 24.5px).
 *   발밑을 그림에서 재는 것과 **같은 이유·같은 방법**으로 머리 위도 그림에서 잰다.
 */
interface SpritePads {
  /** 위 여백 ÷ 캔버스 세로. */
  top: number;
  /** 아래 여백 ÷ 캔버스 세로. */
  bottom: number;
  /** 실루엣 **왼쪽 가장자리** ÷ 캔버스 가로. */
  left: number;
  /** 실루엣 **오른쪽 가장자리** ÷ 캔버스 가로. */
  right: number;
}
const padCache = new Map<string, SpritePads>();

function useSpritePads(src: string, fallbackBottom: number): SpritePads {
  const [pads, setPads] = useState(() => padCache.get(src) ?? { top: 0, bottom: fallbackBottom, left: 0, right: 1 });

  useEffect(() => {
    const cached = padCache.get(src);
    if (cached !== undefined) { setPads(cached); return; }
    if (!src) return;
    // 다른 출처면 어차피 못 읽는다. 조용히 기본값으로 간다.
    try {
      if (new URL(src, window.location.href).origin !== window.location.origin) return;
    } catch { return; }
    let alive = true;
    const img = new Image();
    // 다른 출처의 그림을 캔버스로 읽으려면 이 표시가 있어야 한다(없으면 읽는 순간 막힌다).
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = img.naturalWidth; c.height = img.naturalHeight;
        const ctx = c.getContext('2d');
        if (!ctx) return;
        ctx.drawImage(img, 0, 0);
        const d = ctx.getImageData(0, 0, c.width, c.height).data;
        const opaqueRow = (y: number) => {
          for (let x = 0; x < c.width; x++) if (d[(y * c.width + x) * 4 + 3] > 10) return true;
          return false;
        };
        let bottom = -1;
        // 아래에서 위로 훑다가 처음 만나는 불투명한 줄이 발끝이다.
        for (let y = c.height - 1; y >= 0 && bottom < 0; y--) if (opaqueRow(y)) bottom = y;
        if (bottom < 0) return;
        let top = -1;
        // 위에서 아래로 훑다가 처음 만나는 불투명한 줄이 정수리다.
        for (let y = 0; y < c.height && top < 0; y++) if (opaqueRow(y)) top = y;
        const opaqueCol = (x: number) => {
          for (let y = 0; y < c.height; y++) if (d[(y * c.width + x) * 4 + 3] > 10) return true;
          return false;
        };
        let left = -1;
        for (let x = 0; x < c.width && left < 0; x++) if (opaqueCol(x)) left = x;
        let right = -1;
        for (let x = c.width - 1; x >= 0 && right < 0; x--) if (opaqueCol(x)) right = x;
        const next = {
          top: Math.max(0, top) / c.height,
          bottom: (c.height - 1 - bottom) / c.height,
          left: Math.max(0, left) / c.width,
          right: (right < 0 ? c.width - 1 : right + 1) / c.width,
        };
        padCache.set(src, next);
        if (alive) setPads(next);
      } catch {
        // 캔버스를 못 읽는 경우(CORS)엔 기본값 그대로 간다. 화면은 멀쩡히 돈다.
      }
    };
    img.src = src;
    return () => { alive = false; };
  }, [src, fallbackBottom]);

  return pads;
}

export function useFootPad(src: string, fallback: number): number {
  return useSpritePads(src, fallback).bottom;
}

/** 그림 **위쪽** 투명 여백의 비율. 못 재면 0(= 예전처럼 앵커표를 그대로 믿는다). */
export function useHeadPad(src: string, fallbackBottom: number): number {
  return useSpritePads(src, fallbackBottom).top;
}

/**
 * 실루엣의 **좌·우 가장자리**(캔버스 가로 대비 0~1). 머리 옆에 말풍선을 놓을 때 "아이 옆에 얼마나
 * 남았나" 를 재는 자다. 못 재면 상자 전체(0~1)로 두어 **가장 불리하게** 잡는다 — 자리를 넉넉히
 * 요구하게 되므로 옆으로 비키지 못할 뿐, 잘리거나 겹치지는 않는다.
 */
export function useSideEdges(src: string, fallbackBottom: number): { left: number; right: number } {
  const pads = useSpritePads(src, fallbackBottom);
  return { left: pads.left, right: pads.right };
}

/**
 * **어떤 그림을 그릴지 정하는 단 한 곳.** 축이 둘이고, 각각 순서가 있다.
 *
 *   누구를  :  내 아이 그림  →  (여울 샘플 방에서만) 여울
 *   무엇을  :  지금 하는 동작  →  없으면 상태(아픔·잠·배고픔…)  →  기본
 *
 * '무엇을' 은 화면이 `key` 로 정해 넘기고(→ `useYeoul` 의 `spriteKey`), 여기서는 '누구를' 만 푼다.
 *
 * ★ **진짜 방에서는 여울로 내려가지 않는다**(상훈님 2026-09-08).
 *   방에 들어왔다는 것은 기본 8종이 다 만들어졌다는 뜻이라(→ `BASIC_KEYS`), 거기서 여울이 보이면
 *   그건 폴백이 아니라 **고장을 덮은 것**이다. 그래서 진짜 방의 폴백은 **기본 8종 안에서** 끝난다:
 *     2층 동작(`wash`·`sleep`·`nod` …) → `MOTION_FALLBACK` → 기본 8종. 사슬은 전부 기본 8종에서 끝난다(실측).
 *   그래도 없으면 `base` 로 버티되 **콘솔에 경고**를 남긴다 — 조용히 넘어가면 생성이 8종을 못 채워도 아무도 모른다.
 *
 * ★ 여울은 **샘플 방 전용**이다. 거기서는 여울이 주인공이라 그게 맞다.
 *   펫이 아예 없는데 진짜 방에 있는 경우는 **개발용 '이동' 으로 건너뛴 때뿐**이라, 그때만 여울로 버틴다.
 */
const warned = new Set<string>();

/**
 * 그 동작 하나를 찾을 때 **실제로 두드려 볼 이름들**(앞에서부터).
 *
 * ★ 별칭이 먼저다(2026-09-13) — 서버가 곧 1층·2층 key 를 새 이름으로 바꾸는데 화면에는 옛 이름이
 *   박혀 있다. `motionAliases` 가 **있는 쪽을 먼저, 없으면 옛것으로** 모아 준다.
 *   그 뒤에 `MOTION_FALLBACK`(잠긴 2층 → 1층 대역)이 붙는다. 순서를 바꾸면 **열려 있는 새 이름을
 *   두고 옛 대역 그림을 쓰게** 된다.
 */
function spriteCandidates(key: string): string[] {
  const out: string[] = [];
  for (const k of motionAliases(key)) {
    if (!out.includes(k)) out.push(k);
    const alt = MOTION_FALLBACK[k];
    if (alt && !out.includes(alt)) out.push(alt);
  }
  return out;
}

/**
 * 그 자세의 **여울 그림**(우리가 확실히 갖고 있는 것). 내 아이 그림이 실제로 안 열릴 때 댈 자리다.
 * ★ `spriteUrl` 과 **같은 후보 순서**를 쓴다 — 두 곳이 갈리면 폴백이 엉뚱한 자세를 그린다.
 */
export function yeoulSpriteUrl(key: string): string {
  const tries = spriteCandidates(key);
  return tries.map((k) => YEOUL_MOTION[k]).find(Boolean) ?? YEOUL_MOTION.base;
}

export function spriteUrl(live: Live, key: string, sample = false): string {
  const tries = spriteCandidates(key);
  const yeoul = tries.map((k) => YEOUL_MOTION[k]).find(Boolean) ?? YEOUL_MOTION.base;

  // 여울 샘플 방 · 펫이 없는 개발용 경로 — 여울로 그린다.
  if (sample || !live.petId) return yeoul;

  const mine = tries.map((k) => live.img(k)).find(Boolean) ?? null;
  if (mine) return mine;

  const base = live.img('base');
  if (!warned.has(key)) {
    warned.add(key);
    // eslint-disable-next-line no-console
    console.warn(`[여울] 내 아이 그림이 없습니다 — key=${key} (시도: ${tries.join(' → ')}). `
      + `${base ? 'base 로 버팁니다.' : 'base 마저 없어 여울로 버팁니다.'} 기본 8종은 방에 들어온 시점에 다 있어야 합니다.`);
  }
  return base ?? yeoul;
}
