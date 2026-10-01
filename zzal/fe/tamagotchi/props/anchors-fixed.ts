// 고정 앵커표 — **서버가 앵커를 안 줘도 화면이 완성이어야 한다.** 이것만으로 끝까지 그려진다.
//
// 값은 **스프라이트와 같은 판에서 나온 `demo/v7/anchors.json`(정본 v02 · 2026-09-19)** 을
// 스크립트로 옮긴 것이다(손으로 베끼지 않는다). 그림이 바뀌면 그 판의 `anchors.json` 으로 다시 낸다 —
// 표와 그림은 **반드시 같은 판**이어야 한다(다른 판을 섞으면 소품만 조용히 어긋난다).
// 브라우저가 아니라 그림에서 잰 값이라 화면 크기와 무관하다.
//
// ★ v6(K=239) → v02/v7(K=274) 로 갈아끼우며 달라진 것 —
//   1) 실루엣이 커졌다: K 239→274 · Hw 95→109. 화면 키(`K_SCREEN_TARGET`)는 그대로라
//      `boxHPerK`(=349/K)가 1.46→1.27 로 줄어 **상자만 작아지고 아이 크기는 안 변한다**(layout.ts 머리말).
//   2) 발끝이 한 줄로 섰다: v6 는 1층 289 · 2층 267 로 **22px 벌어져** 자세를 바꾸면 아이가 튀었는데,
//      v02 는 16 자세 전부 318~319(편차 1px)다. `belowFoot` 보정이 사실상 필요 없어진다.
//
// ★ 왜 자세마다 다른 표인가 — 지금 코드의 `head` 앵커는 캐릭터 상자 위에서 **12% 고정**이라
//   `sick`(웅크림)은 정수리가 109px, `sleep`(눕기)은 205px 어긋난다. 그래서 해골·zzz 가
//   **머리 한참 위 허공**에 떴다(소품-규격 2-1절 표). 자세별로 실루엣을 재면 그 어긋남이 사라진다.
// ★ `sick`·`sleep` 은 머리를 따로 재지 않고 **실루엣 윗변을 그대로 정수리로 쓴다** —
//   부위 트래킹 실측에서 `sleep` 오차가 27.6px(<=15px 7%)로 무너져, 못 재는 것을 재려 들지 않는다.
// ★ 좌표계는 **스프라이트 캔버스 312 x 349 안의 px**. 원점은 왼쪽 위.
//   화면으로 옮기는 일은 `layout.ts` 가 한다(렌더된 폭 / 캔버스 폭).
import type { CharAnchors } from './spec';

/**
 * 자세 16종 — 앵커 정본(`소품-앵커-자세별-v1.json`)의 `포즈` 절 그대로.
 * 1층 8 · 2층 8 이고, **순서는 배우는 순서**다. 개발용 자세 고르기가 이 순서로 보여 준다.
 */
export const POSE_FLOORS: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['1층', ['base', 'eat', 'joy', 'sad', 'sick', 'pet', 'hello', 'sleep']],
  ['2층', ['eat_rice', 'eat_snack', 'sweep', 'wash', 'reply', 'petted', 'startle', 'wake_up']],
];

/**
 * 자세 이름 — 개발 화면에만 쓴다(운영 문구가 아니다).
 * ★ 앨범과 **같은 한 벌**로 맞춰 둔다(제안서 B절 안 1 명사형 · 서버 `MotionCatalog` 와 같은 말).
 *   개발 화면에서 부르는 이름과 앨범에 걸리는 이름이 다르면 이야기할 때마다 통역이 든다.
 */
export const POSE_LABEL: Record<string, string> = {
  base: '기본', eat: '식사', joy: '기쁨', sad: '슬픔', sick: '아픔', pet: '쓰다듬', hello: '인사', sleep: '잠',
  eat_rice: '밥 먹기', eat_snack: '간식 먹기', sweep: '청소하기', wash: '목욕하기', reply: '답하기',
  petted: '쓰다듬 받기', startle: '놀람', wake_up: '일어나기',
  roll: '구르기', fall_back: '뒤로 넘어지기',
};

/**
 * 여울 실측값. **다른 캐릭터도 이 표로 그린다** — 서버가 그 아이의 앵커를 줄 때까지의 기본값이다.
 * K(키) · Hw(머리 폭)는 `base` 자세에서 **한 번만** 잰다. 웅크렸다고 아이가 작아진 게 아니다.
 */
export const FIXED_ANCHORS: CharAnchors = {
  char: '여울',
  K: 274,
  Hw: 109,
  canvas: [312, 349],
  poses: {
    /** 1층 */
    base: {
      bbox: { x: 95, y: 44, w: 130, h: 274 },
      head_top: { x: 146.5, y: 44 },
      head_side: { y: 71.2, left_x: 92, right_x: 201 },
      hand_front: { y: 194.7, left_x: 105, right_x: 216 },
      feet: { y: 318, left_x: 124, right_x: 178, center_x: 160 },
    },
    /** 1층 */
    eat: {
      bbox: { x: 96, y: 44, w: 131, h: 274 },
      head_top: { x: 147.1, y: 44 },
      head_side: { y: 71.2, left_x: 92.6, right_x: 201.6 },
      hand_front: { y: 194.7, left_x: 106, right_x: 215 },
      feet: { y: 318, left_x: 124, right_x: 177, center_x: 161.5 },
    },
    /** 1층 */
    joy: {
      bbox: { x: 98, y: 45, w: 128, h: 273 },
      head_top: { x: 147.7, y: 45 },
      head_side: { y: 72.2, left_x: 93.2, right_x: 202.2 },
      hand_front: { y: 195.7, left_x: 105, right_x: 221 },
      feet: { y: 318, left_x: 124, right_x: 179, center_x: 162 },
    },
    /** 1층 */
    sad: {
      bbox: { x: 93, y: 45, w: 130, h: 273 },
      head_top: { x: 140.2, y: 45 },
      head_side: { y: 72.2, left_x: 85.7, right_x: 194.7 },
      hand_front: { y: 195.7, left_x: 102, right_x: 220 },
      feet: { y: 318, left_x: 125, right_x: 178, center_x: 158 },
    },
    /** 1층 */
    sick: {
      bbox: { x: 90, y: 121, w: 133, h: 198 },
      head_top: { x: 134.6, y: 121 },
      head_side: { y: 148.2, left_x: 80.1, right_x: 189.1 },
      hand_front: { y: 271.7, left_x: 99, right_x: 211 },
      feet: { y: 319, left_x: 108, right_x: 157, center_x: 156.5 },
    },
    /** 1층 */
    pet: {
      bbox: { x: 95, y: 55, w: 131, h: 264 },
      head_top: { x: 134.4, y: 55 },
      head_side: { y: 82.2, left_x: 79.9, right_x: 191 },
      hand_front: { y: 205.7, left_x: 108, right_x: 215 },
      feet: { y: 319, left_x: 125, right_x: 178, center_x: 160.5 },
    },
    /** 1층 */
    hello: {
      bbox: { x: 81, y: 44, w: 147, h: 275 },
      head_top: { x: 149.7, y: 44 },
      head_side: { y: 71.2, left_x: 95.2, right_x: 204.2 },
      hand_front: { y: 194.7, left_x: 98, right_x: 219 },
      feet: { y: 319, left_x: 123, right_x: 178, center_x: 154.5 },
    },
    /** 1층 */
    sleep: {
      bbox: { x: 70, y: 163, w: 172, h: 156 },
      head_top: { x: 125.8, y: 163 },
      head_side: { y: 190.2, left_x: 71.3, right_x: 193 },
      hand_front: { y: 313.7, left_x: 219, right_x: 238 },
      feet: { y: 319, left_x: 220, right_x: 236, center_x: 156 },
    },
    /** 2층 */
    eat_rice: {
      bbox: { x: 85, y: 60, w: 131, h: 259 },
      head_top: { x: 136.2, y: 60 },
      head_side: { y: 87.2, left_x: 81.7, right_x: 190.7 },
      hand_front: { y: 210.7, left_x: 89, right_x: 214 },
      feet: { y: 319, left_x: 117, right_x: 172, center_x: 150.5 },
    },
    /** 2층 */
    eat_snack: {
      bbox: { x: 86, y: 57, w: 133, h: 262 },
      head_top: { x: 136.5, y: 57 },
      head_side: { y: 84.2, left_x: 82, right_x: 191 },
      hand_front: { y: 207.7, left_x: 89, right_x: 217 },
      feet: { y: 319, left_x: 118, right_x: 169, center_x: 152.5 },
    },
    /** 2층 */
    sweep: {
      bbox: { x: 71, y: 71, w: 171, h: 248 },
      head_top: { x: 154.2, y: 71 },
      head_side: { y: 98.2, left_x: 99.8, right_x: 209 },
      hand_front: { y: 221.7, left_x: 117, right_x: 233 },
      feet: { y: 319, left_x: 149, right_x: 198, center_x: 156.5 },
    },
    /** 2층 */
    wash: {
      bbox: { x: 83, y: 92, w: 147, h: 227 },
      head_top: { x: 143.7, y: 92 },
      head_side: { y: 119.2, left_x: 89.2, right_x: 198.2 },
      hand_front: { y: 242.7, left_x: 83, right_x: 230 },
      feet: { y: 319, left_x: 104, right_x: 209, center_x: 156.5 },
    },
    /** 2층 */
    reply: {
      bbox: { x: 86, y: 73, w: 131, h: 246 },
      head_top: { x: 134.2, y: 73 },
      head_side: { y: 100.2, left_x: 79.7, right_x: 188.7 },
      hand_front: { y: 223.7, left_x: 95, right_x: 209 },
      feet: { y: 319, left_x: 120, right_x: 169, center_x: 151.5 },
    },
    /** 2층 */
    petted: {
      bbox: { x: 63, y: 64, w: 153, h: 255 },
      head_top: { x: 80.4, y: 64 },
      head_side: { y: 91.2, left_x: 25.9, right_x: 180 },
      hand_front: { y: 214.7, left_x: 90, right_x: 210 },
      feet: { y: 319, left_x: 121, right_x: 169, center_x: 139.5 },
    },
    /** 2층 */
    startle: {
      bbox: { x: 85, y: 67, w: 132, h: 252 },
      head_top: { x: 134.9, y: 67 },
      head_side: { y: 94.2, left_x: 80.4, right_x: 189.4 },
      hand_front: { y: 217.7, left_x: 93, right_x: 207 },
      feet: { y: 319, left_x: 116, right_x: 170, center_x: 151 },
    },
    /** 2층 */
    wake_up: {
      bbox: { x: 80, y: 65, w: 140, h: 254 },
      head_top: { x: 139.7, y: 65 },
      head_side: { y: 92.2, left_x: 85.2, right_x: 194.2 },
      hand_front: { y: 215.7, left_x: 89, right_x: 219 },
      feet: { y: 319, left_x: 121, right_x: 170, center_x: 150 },
    },
  },
};
