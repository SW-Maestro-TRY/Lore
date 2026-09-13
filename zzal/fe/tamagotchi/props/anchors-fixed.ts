// 고정 앵커표 — **서버가 앵커를 안 줘도 화면이 완성이어야 한다.** 이것만으로 끝까지 그려진다.
//
// 값은 `~/.claude/soma/lore/contract/소품-앵커-자세별-v1.json`(v1 · 2026-09-13)의 **여울** 칸을
// 그대로 옮긴 것이다. 브라우저가 아니라 그림에서 잰 값이라 화면 크기와 무관하다.
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
 * 여울 실측값. **다른 캐릭터도 이 표로 그린다** — 서버가 그 아이의 앵커를 줄 때까지의 기본값이다.
 * K(키) · Hw(머리 폭)는 `base` 자세에서 **한 번만** 잰다. 웅크렸다고 아이가 작아진 게 아니다.
 */
export const FIXED_ANCHORS: CharAnchors = {
  char: '여울',
  K: 239,
  Hw: 95,
  canvas: [312, 349],
  poses: {
    /** 1층 */
    base: {
      bbox: { x: 99, y: 50, w: 118, h: 239 },
      head_top: { x: 158.6, y: 50 },
      head_side: { y: 73.8, left_x: 111.1, right_x: 206.1 },
      hand_front: { y: 181.5, left_x: 104, right_x: 214 },
      feet: { y: 289, left_x: 130, right_x: 184, center_x: 158.0 },
    },
    /** 1층 */
    eat: {
      bbox: { x: 97, y: 49, w: 119, h: 240 },
      head_top: { x: 156.7, y: 49 },
      head_side: { y: 72.8, left_x: 109.2, right_x: 204.2 },
      hand_front: { y: 180.5, left_x: 101, right_x: 212 },
      feet: { y: 289, left_x: 130, right_x: 184, center_x: 156.5 },
    },
    /** 1층 */
    hello: {
      bbox: { x: 84, y: 46, w: 135, h: 243 },
      head_top: { x: 160.8, y: 46 },
      head_side: { y: 69.8, left_x: 113.3, right_x: 208.3 },
      hand_front: { y: 177.5, left_x: 104, right_x: 219 },
      feet: { y: 289, left_x: 129, right_x: 186, center_x: 151.5 },
    },
    /** 1층 */
    joy: {
      bbox: { x: 98, y: 52, w: 119, h: 236 },
      head_top: { x: 157.3, y: 52 },
      head_side: { y: 75.8, left_x: 109.8, right_x: 204.8 },
      hand_front: { y: 183.5, left_x: 103, right_x: 213 },
      feet: { y: 288, left_x: 130, right_x: 185, center_x: 157.5 },
    },
    /** 1층 */
    pet: {
      bbox: { x: 97, y: 56, w: 117, h: 233 },
      head_top: { x: 153.5, y: 56 },
      head_side: { y: 79.8, left_x: 106.0, right_x: 201.0 },
      hand_front: { y: 187.5, left_x: 103, right_x: 209 },
      feet: { y: 289, left_x: 130, right_x: 184, center_x: 155.5 },
    },
    /** 1층 */
    sad: {
      bbox: { x: 98, y: 52, w: 121, h: 236 },
      head_top: { x: 154.0, y: 52 },
      head_side: { y: 75.8, left_x: 106.5, right_x: 201.5 },
      hand_front: { y: 183.5, left_x: 105, right_x: 215 },
      feet: { y: 288, left_x: 128, right_x: 185, center_x: 158.5 },
    },
    /** 1층 */
    sick: {
      bbox: { x: 98, y: 119, w: 117, h: 169 },
      head_top: { x: 149.3, y: 119 },
      head_side: { y: 142.8, left_x: 101.8, right_x: 196.8 },
      hand_front: { y: 250.5, left_x: 114, right_x: 206 },
      feet: { y: 288, left_x: 119, right_x: 171, center_x: 156.5 },
    },
    /** 1층 */
    sleep: {
      bbox: { x: 76, y: 151, w: 161, h: 138 },
      head_top: { x: 124.0, y: 151 },
      head_side: { y: 174.8, left_x: 76.5, right_x: 181 },
      hand_front: { y: 282.5, left_x: 173, right_x: 234 },
      feet: { y: 289, left_x: 192, right_x: 232, center_x: 156.5 },
    },
    /** 2층 */
    eat_rice: {
      bbox: { x: 88, y: 12, w: 132, h: 255 },
      head_top: { x: 146.9, y: 12 },
      head_side: { y: 35.8, left_x: 99.4, right_x: 194.4 },
      hand_front: { y: 143.5, left_x: 90, right_x: 220 },
      feet: { y: 267, left_x: 116, right_x: 181, center_x: 154.0 },
    },
    /** 2층 */
    eat_snack: {
      bbox: { x: 89, y: 12, w: 133, h: 255 },
      head_top: { x: 147.0, y: 12 },
      head_side: { y: 35.8, left_x: 99.5, right_x: 196 },
      hand_front: { y: 143.5, left_x: 91, right_x: 221 },
      feet: { y: 267, left_x: 116, right_x: 180, center_x: 155.5 },
    },
    /** 2층 */
    petted: {
      bbox: { x: 89, y: 18, w: 127, h: 249 },
      head_top: { x: 99.4, y: 18 },
      head_side: { y: 41.8, left_x: 51.9, right_x: 182 },
      hand_front: { y: 149.5, left_x: 91, right_x: 214 },
      feet: { y: 267, left_x: 117, right_x: 180, center_x: 152.5 },
    },
    /** 2층 */
    reply: {
      bbox: { x: 92, y: 30, w: 126, h: 237 },
      head_top: { x: 145.6, y: 30 },
      head_side: { y: 53.8, left_x: 98.1, right_x: 194 },
      hand_front: { y: 161.5, left_x: 95, right_x: 218 },
      feet: { y: 267, left_x: 116, right_x: 181, center_x: 155.0 },
    },
    /** 2층 */
    startle: {
      bbox: { x: 90, y: 15, w: 129, h: 252 },
      head_top: { x: 146.4, y: 15 },
      head_side: { y: 38.8, left_x: 98.9, right_x: 195 },
      hand_front: { y: 146.5, left_x: 93, right_x: 219 },
      feet: { y: 267, left_x: 117, right_x: 182, center_x: 154.5 },
    },
    /** 2층 */
    sweep: {
      bbox: { x: 34, y: 34, w: 180, h: 233 },
      head_top: { x: 138.3, y: 34 },
      head_side: { y: 57.8, left_x: 90.8, right_x: 186 },
      hand_front: { y: 165.5, left_x: 91, right_x: 209 },
      feet: { y: 267, left_x: 112, right_x: 182, center_x: 124.0 },
    },
    /** 2층 */
    wake_up: {
      bbox: { x: 79, y: 16, w: 145, h: 251 },
      head_top: { x: 155.3, y: 16 },
      head_side: { y: 39.8, left_x: 107.8, right_x: 202.8 },
      hand_front: { y: 147.5, left_x: 95, right_x: 224 },
      feet: { y: 267, left_x: 116, right_x: 181, center_x: 151.5 },
    },
    /** 2층 */
    wash: {
      bbox: { x: 86, y: 55, w: 141, h: 212 },
      head_top: { x: 152.7, y: 55 },
      head_side: { y: 78.8, left_x: 105.2, right_x: 201 },
      hand_front: { y: 186.5, left_x: 102, right_x: 223 },
      feet: { y: 267, left_x: 110, right_x: 203, center_x: 156.5 },
    },
  },
};
