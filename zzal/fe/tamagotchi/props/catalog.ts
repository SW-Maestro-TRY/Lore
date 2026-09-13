// 소품 규격표 — `~/.claude/soma/lore/contract/소품-규격-v1.2.json`(v1.2 · 2026-09-13)을 **그대로 옮긴** 표.
//
// ★ 손으로 고치지 않는다. 규격이 바뀌면 그 JSON 을 다시 옮긴다 — 판정 근거가 그 파일에 있다.
// ★ `dropped`(해)는 옮기지 않았다. `pending`(간식·컵·쓸림자국·햇살·게임 짐·zzz 낱개)은
//   자리만 잡아 두고 `status` 로 갈라 둔다 — 재제작 대기라 지금 띄우면 안 된다.
//
// 읽는 법(규격 JSON 의 `읽는_법` 절 요약)
//   크기 = max(minPx, ratio x (K | Hw))   ★ ratio 는 **가로** 기준이고 높이는 비율대로 따라간다
//   자리 = 앵커 + ref(bottom|center) + outside + offset{dx,dy,unit}
//          dx 는 **보는 쪽이 +** · dy 는 아래가 + · offset 은 제 unit 의 **비율**(px 아님)
//   unit:'screen' 은 비율을 안 쓴다 — `stages[].screen` 의 **무대 558 기준 px** 이 자리를 정한다
import type { PropSpec } from './spec';

/** 규격 JSON 의 판. 표가 어느 판에서 왔는지 로그가 말할 수 있게 둔다. */
export const PROP_SPEC_VERSION = '1.2';

/** 무대 기준값(규격 JSON `stage`). 화면 이펙트가 쓰는 좌표계다. */
export const PROP_STAGE = {
  /** 무대 가로 상한 — 768 이상 화면에서도 여기서 멈춘다(배경-화면실측 1절). */
  width: 558,
  /** 발끝선 — 무대 아래에서 이만큼 위. **6개 화면 전부 고정.** */
  footlineFromBottom: 238,
  /** 바닥선(벽↔바닥 경계). */
  floorlineFromBottom: 266,
} as const;

/**
 * 화면 최소 크기 하한(규격 1-5절). **비율과 갈리면 하한이 이긴다.**
 * 판정 기각 3건이 전부 30.2px 이하 · 채택 11건이 전부 41.4px 이상이라 그 사이에서 역산했다.
 */
export const MIN_PX = { solo: 40, pair: 30 } as const;

/**
 * 규격의 `ratio` 가 전제한 **짝**(규격 JSON `reference` · `stage`).
 * `K_screen` = 브라우저 실측 296px · `charBoxW` = 캐릭터 상자 가로 313px.
 *
 * ★ `char_box` 앵커에만 쓴다 — 그 소품은 **캐릭터와 같은 판 위의 그림**이라 자가
 *   "그 아이의 키" 가 아니라 "그 판의 폭" 이다. 규격이 `ratio 1.0574 = 313 ÷ 296` 이라고
 *   적어 둔 것이 곧 "상자 폭 그대로" 라는 뜻이라, 이 짝으로 환산해야 그 뜻이 지켜진다.
 */
export const PROP_REFERENCE = { K_screen: 296.0, charBoxW: 313 } as const;

/**
 * 확정·대기 소품 전부. **key 는 영원히 안 바뀐다** — 상황표와 코드가 이 이름을 참조한다.
 * 그림을 다시 뽑으면 key 는 그대로 두고 stage 의 `ver` 만 올린다.
 */
export const PROP_SPECS: Record<string, PropSpec> = {
  /** 한 알이 K×0.20(화면 59.2px)로 3·2·1 단계 모두 같다. 단계 그림 폭이 서로 달라 ratio 도 다르다. */
  bowl: {
    key: "bowl", name: "주먹밥", anchor: "hand_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.02, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "bowl_1", ver: 1, file: "bowl_1.v1.webp", ratio: 0.4286, srcW: 254, srcH: 224 },
      { n: 2, key: "bowl_2", ver: 1, file: "bowl_2.v1.webp", ratio: 0.3682, srcW: 218, srcH: 156 },
      { n: 3, key: "bowl_3", ver: 1, file: "bowl_3.v1.webp", ratio: 0.2, srcW: 118, srcH: 107 },
    ],
  },
  /** 주먹밥(K×0.20)과 한눈에 갈라지도록 K×0.19. 전 181px·후 41px 의 중간(판정). */
  medicine: {
    key: "medicine", name: "알약", anchor: "hand_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.02, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "medicine", ver: 1, file: "medicine.v1.webp", ratio: 0.19, srcW: 112, srcH: 93 },
    ],
  },
  /** 배 높이 = 정수리 아래 K×0.50 이라 손앞 앵커에서 K×0.05 올린다. 그림의 발원점이 왼쪽에 박혀 있어 **보는 쪽의 반대**에 둔다 — 상수표상 캐릭터가 늘 왼쪽을 보므로 화면 오른쪽 고정. */
  growl: {
    key: "growl", name: "꼬르륵", anchor: "hand_front", ref: "center", unit: "Hw",
    outside: true, facing: "fixed_right", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.02, dy: -0.05, unit: "K" },
    stages: [
      { n: 1, key: "growl", ver: 1, file: "growl.v1.webp", ratio: 0.55, srcW: 187, srcH: 222 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_bang: {
    key: "bubble_bang", name: "말풍선 느낌표", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: true,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_bang", ver: 1, file: "bubble_bang.v1.webp", ratio: 0.9, srcW: 305, srcH: 297 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_question: {
    key: "bubble_question", name: "말풍선 물음표", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: false,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_question", ver: 1, file: "bubble_question.v1.webp", ratio: 0.9, srcW: 305, srcH: 270 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_dots: {
    key: "bubble_dots", name: "말풍선 말줄임표", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: false,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_dots", ver: 1, file: "bubble_dots.v1.webp", ratio: 0.9, srcW: 305, srcH: 238 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_note: {
    key: "bubble_note", name: "말풍선 음표", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: false,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_note", ver: 1, file: "bubble_note.v1.webp", ratio: 0.9, srcW: 305, srcH: 288 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_zzz: {
    key: "bubble_zzz", name: "말풍선 zzz", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: false,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_zzz", ver: 1, file: "bubble_zzz.v1.webp", ratio: 0.9, srcW: 305, srcH: 255 },
    ],
  },
  /** 확정 규칙은 **꼬리가 캐릭터 쪽을 가리킨다** 하나다. 후보 그림의 꼬리는 왼쪽 아래에 박혀 있다. 그래서 ① 꼬리 있는 원본을 **화면 오른쪽**에 두면 규칙이 그대로 지켜진다(재제작 0). ② 왼쪽에 두려면 좌우반전이 필요한데 글자가 같이 뒤집혀 **느낌표(mirrorable)만** 된다. ③ 기본값은 화면 왼쪽이므로 `_tailless` 판 + 프론트가 그리는 꼬리가 정답이다. */
  bubble_heart: {
    key: "bubble_heart", name: "말풍선 하트", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    mirrorable: false,
    tailSide: "bottom_left",
    stages: [
      { n: 1, key: "bubble_heart", ver: 1, file: "bubble_heart.v1.webp", ratio: 0.9, srcW: 305, srcH: 262 },
    ],
  },
  skull: {
    key: "skull", name: "해골", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "skull", ver: 1, file: "skull.v1.webp", ratio: 0.45, srcW: 153, srcH: 132 },
    ],
  },
  /** 전 130px·후 60px 의 중간(판정). */
  sweat: {
    key: "sweat", name: "땀방울", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "sweat", ver: 1, file: "sweat.v1.webp", ratio: 0.4, srcW: 136, srcH: 169 },
    ],
  },
  /** 친밀도가 높으면 둘을 띄운다. 둘째는 첫째의 0.75배 — 같은 크기면 복사해 붙인 것으로 보인다. 둘째에만 보조 하한 30px 을 쓴다. */
  heart: {
    key: "heart", name: "하트(낱개)", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "heart", ver: 1, file: "heart.v1.webp", ratio: 0.35, srcW: 119, srcH: 101 },
    ],
  },
  win_star: {
    key: "win_star", name: "게임 이김 표시", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "win_star", ver: 1, file: "win_star.v1.webp", ratio: 0.7, srcW: 238, srcH: 223 },
    ],
  },
  /** 해금 순간("○○를 배웠어요") · 부화 직후 · 아침 도착에 `joy`·`hello` 와 함께 뜬다(리스트업 v2 1-5·2-5, 확정). **기존 실물 에셋이다** — `apps/web/public/zzal/assets/firework.webp` 가 그대로 S3 에 올라가 있고 코드가 이미 쓰고 있다(constants.ts `ASSET.firework`). ★ **5프레임 애니메이션 webp**(각 220ms)라 프레임마다 크기가 다르다(f0 65×64 → f4 283×299, 합집합 301×311). 그래서 자르지 않는다 — 313×350 캔버스 **그 자체가 좌표계**다(`char_box`). ratio 1.0574 = 상자 폭 313 ÷ K 296. 파일 이름도 기존 그대로 두어 **재업로드가 필요 없다**. 다음 판부터 `firework.v2.webp` 처럼 ver 규칙을 따른다. */
  firework: {
    key: "firework", name: "폭죽", anchor: "char_box", ref: "center", unit: "K",
    outside: false, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "K" },
    animated: true, frames: 5, frameMs: 220,
    // ⚠️ 아직 확정 아님: 크기 기준이 규격 v1.2 4절 표에 **없다**(폭죽 칸 자체가 없음). 지금 값은 코드에 올라가 있는 `at:'full' size 1` 을 그대로 옮긴 것이다. 리스트업 v2 2-5 가 남긴 숙제 — `joy` 에서 폭죽·반짝임(Hw×1.50)·음표가 **같은 자리를 나눠 쓰므로 셋이 안 겹치게 크기를 맞춰야** 한다. 아직 정해지지 않았다.
    stages: [
      { n: 1, key: "firework", ver: 1, file: "firework.webp", ratio: 1.0574, srcW: 313, srcH: 350 },
    ],
  },
  /** 다른 머리 위 소품과 달리 아랫변이 정수리보다 K×0.05 **아래**다 — 손이 머리를 조금 덮어야 한다(판정). */
  pet_hand: {
    key: "pet_hand", name: "쓰다듬는 손", anchor: "head_top", ref: "bottom", unit: "Hw",
    outside: false, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.0, dy: 0.05, unit: "K" },
    stages: [
      { n: 1, key: "pet_hand", ver: 1, file: "pet_hand.v1.webp", ratio: 0.6, srcW: 204, srcH: 240 },
    ],
  },
  /** 얼굴을 덮으면 실격 — 아랫변이 정수리 부근이라 머리 위 띠에만 온다. */
  sparkle: {
    key: "sparkle", name: "반짝임", anchor: "head_top", ref: "bottom", unit: "Hw",
    outside: false, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.0, dy: 0.03, unit: "K" },
    stages: [
      { n: 1, key: "sparkle", ver: 1, file: "sparkle.v1.webp", ratio: 1.5, srcW: 509, srcH: 402 },
    ],
  },
  /** 「전(at:full)」 크기를 그대로 K 비율로 옮긴 값이다. 단계가 화면에서 확실히 는다(세로 124→157→181→136px). */
  trash: {
    key: "trash", name: "똥 1~4단계", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: false, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.22, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "trash_1", ver: 1, file: "trash_1.v1.webp", ratio: 0.301, srcW: 178, srcH: 247 },
      { n: 2, key: "trash_2", ver: 1, file: "trash_2.v1.webp", ratio: 0.735, srcW: 435, srcH: 315 },
      { n: 3, key: "trash_3", ver: 1, file: "trash_3.v1.webp", ratio: 0.9408, srcW: 557, srcH: 362 },
      { n: 4, key: "trash_4", ver: 1, file: "trash_4.v1.webp", ratio: 1.169, srcW: 692, srcH: 273 },
    ],
  },
  /** 아이 밑에 깔린다 — 캐릭터보다 먼저 그린다. 아랫변이 발끝선보다 K×0.02 아래. */
  mat: {
    key: "mat", name: "바닥 매트", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: false, facing: "follow", z: "below_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.0, dy: 0.02, unit: "K" },
    stages: [
      { n: 1, key: "mat", ver: 1, file: "mat.v1.webp", ratio: 0.75, srcW: 444, srcH: 212 },
    ],
  },
  bag: {
    key: "bag", name: "가방(보따리)", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "bag", ver: 1, file: "bag.v1.webp", ratio: 0.3, srcW: 178, srcH: 214 },
    ],
  },
  robot: {
    key: "robot", name: "로봇 인형", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "robot", ver: 1, file: "robot.v1.webp", ratio: 0.28, srcW: 166, srcH: 275 },
    ],
  },
  prop_ball: {
    key: "prop_ball", name: "하루 소품 — 공", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "prop_ball", ver: 1, file: "prop_ball.v1.webp", ratio: 0.14, srcW: 83, srcH: 83 },
    ],
  },
  prop_book: {
    key: "prop_book", name: "하루 소품 — 책", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "prop_book", ver: 1, file: "prop_book.v1.webp", ratio: 0.16, srcW: 95, srcH: 121 },
    ],
  },
  prop_plant: {
    key: "prop_plant", name: "하루 소품 — 화분", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "confirmed",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "prop_plant", ver: 1, file: "prop_plant.v1.webp", ratio: 0.22, srcW: 130, srcH: 182 },
    ],
  },
  /** 가로만 무대(558)에 맞추고 아랫변을 무대 아랫변에 고정한다. **세로로 늘리지 않는다** — 늘리면 거품 한 알이 같이 커져 구름이 된다(판정 기각). 차오르는 높이는 그림이 정한다. */
  bath: {
    key: "bath", name: "거품 1~3단계", anchor: "screen_bottom", ref: "bottom", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "bath_1", ver: 1, file: "bath_1.v1.webp", srcW: 1116, srcH: 310, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 155.2, bottomFromStageBottomPx: 0.0, topFromFootlinePx: -82.8, fillTopFromFootlinePx: -150.1 } },
      { n: 2, key: "bath_2", ver: 1, file: "bath_2.v1.webp", srcW: 1116, srcH: 932, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 466.0, bottomFromStageBottomPx: 0.0, topFromFootlinePx: 228.0, fillTopFromFootlinePx: 77.0 } },
      { n: 3, key: "bath_3", ver: 1, file: "bath_3.v1.webp", srcW: 1116, srcH: 1332, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 665.9, bottomFromStageBottomPx: 0.0, topFromFootlinePx: 427.9, fillTopFromFootlinePx: 338.5 } },
    ],
  },
  /** 3단계(걷힘)는 그림이 없다 — 반짝은 단계가 아니라 전환 신호다. */
  dust: {
    key: "dust", name: "먼지 1~2단계", anchor: "screen_bottom", ref: "bottom", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "dust_1", ver: 1, file: "dust_1.v1.webp", srcW: 1116, srcH: 584, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 291.8, bottomFromStageBottomPx: 0.0, topFromFootlinePx: 53.8, fillTopFromFootlinePx: -40.7 } },
      { n: 2, key: "dust_2", ver: 1, file: "dust_2.v1.webp", srcW: 1116, srcH: 1488, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 744.0, bottomFromStageBottomPx: 0.0, topFromFootlinePx: 506.0, fillTopFromFootlinePx: 500.3 } },
    ],
  },
  /** 거품 2단계가 찬 뒤에 온다. 위에서 내려오므로 무대 위끝에 붙이고 아랫변을 발끝선 위 K×0.60 에 맞춘다(세로로 늘린다). ⚠ 무대가 429px 로 낮은 360×640 화면에서는 남는 세로가 13px 뿐이다 — 낮은 화면 처리는 아직 안 정했다. */
  shower: {
    key: "shower", name: "샤워 물줄기", anchor: "screen_full", ref: "bottom", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "shower", ver: 1, file: "shower.v1.webp", srcW: 1116, srcH: 1040, screen: { widthPx: 558.0, leftPx: 0.0, heightPx: 520.0, topFromStageTopPx: 0, bottomFromFootlinePx: 177.6, stretch: "vertical" } },
    ],
  },
  /** 무대 전체를 덮도록 늘린다. 자는 중에는 무대가 4px 커진다. */
  curtain_sheer: {
    key: "curtain_sheer", name: "반투명 커튼", anchor: "screen_full", ref: "center", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "curtain_sheer", ver: 1, file: "curtain_sheer.v1.webp", srcW: 1116, srcH: 1488, screen: { stretchToStage: true, widthPx: 558 } },
    ],
  },
  /** 후보 #1 흰 여백 테두리. 안쪽은 비워 그 칸의 자세가 다 보여야 한다. */
  postcard: {
    key: "postcard", name: "엽서 프레임", anchor: "screen_full", ref: "center", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "postcard", ver: 1, file: "postcard.v1.webp", srcW: 1116, srcH: 1116, screen: { stretchToStage: true, widthPx: 558 } },
    ],
  },
  /** 캐릭터 비율이 아니라 **화면 좌표 고정** — 무대 왼쪽 위 창문 칸(왼쪽 28 · 위 34 · 98×98)에 넣는다. 캐릭터 실루엣과 겹침이 0 이어야 한다. ⚠ 원본 moon.webp 의 별 5개는 함께 오지 않는다 — 별은 배경(밤) 몫으로 남겼다. */
  moon: {
    key: "moon", name: "달", anchor: "screen_full", ref: "bottom", unit: "screen",
    outside: false, facing: "follow", z: "below_char", minPx: 0, status: "confirmed",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "moon", ver: 1, file: "moon.v1.webp", srcW: 154, srcH: 197, screen: { leftPx: 28, topPx: 34, boxPx: 98, widthPx: 76.8, heightPx: 98.0 } },
    ],
  },
  /** 판정 실패 — "간식 후보 … 일단 다 별로야". 무엇이 별로였는지가 기록에 없다. 재제작 전에 한 줄 필요. */
  snack: {
    key: "snack", name: "간식 한 알", anchor: "hand_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "pending",
    offset: { dx: 0.02, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "snack", ver: 1, ratio: 0.13 },
    ],
  },
  /** 판정 실패 — "컵들은 좀 별로고". 원인 미상. */
  prop_cup: {
    key: "prop_cup", name: "하루 소품 — 컵", anchor: "foot_front", ref: "bottom", unit: "K",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "pending",
    offset: { dx: 0.03, dy: 0.0, unit: "K" },
    stages: [
      { n: 1, key: "prop_cup", ver: 1, ratio: 0.13 },
    ],
  },
  /** 판정 실패. 후보가 1024 정사각이라 `full` 로 얹으면 자국이 바닥이 아니라 가슴 높이를 가로질렀다 — 그림보다 자리 문제일 수 있다(내 관찰). */
  trash_sweep: {
    key: "trash_sweep", name: "청소 쓸림 자국", anchor: "screen_bottom", ref: "bottom", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "pending",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "trash_sweep", ver: 1 },
    ],
  },
  /** 판정 실패. 원인 미상. */
  sunshine: {
    key: "sunshine", name: "햇살", anchor: "screen_full", ref: "center", unit: "screen",
    outside: false, facing: "follow", z: "above_char", minPx: 0, status: "pending",
    offset: { dx: 0.0, dy: 0.0, unit: "screen" },
    stages: [
      { n: 1, key: "sunshine", ver: 1 },
    ],
  },
  /** 판정 실패. bubble_dots(“둘 다 좋아”)와 그림이 거의 같아 소품이 아니라 역할 겹침이 걸렸을 수 있다(내 관찰). */
  lose_dots: {
    key: "lose_dots", name: "게임 짐 표시", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "pending",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "lose_dots", ver: 1, ratio: 0.7 },
    ],
  },
  /** 판정 전. bubble_zzz 와 역할이 겹친다 — 둘 중 하나만 남겨야 한다(규격 5절 2). */
  zzz_single: {
    key: "zzz_single", name: "zzz 낱개", anchor: "head_side", ref: "center", unit: "Hw",
    outside: true, facing: "follow", z: "above_char", minPx: 40, status: "pending",
    offset: { dx: 0.08, dy: 0.0, unit: "Hw" },
    stages: [
      { n: 1, key: "zzz_single", ver: 1, ratio: 0.55 },
    ],
  },
};

/** 규격에 있는 소품 key 전부(확정 + 대기). */
export const PROP_KEYS_ALL = Object.keys(PROP_SPECS);

/**
 * 지금 띄워도 되는 것만 돌려준다 — `pending` 은 재제작 대기라 화면에 안 낸다.
 * 규격에 아예 없는 이름이면 null 이다(상황표가 낡았다는 뜻).
 */
export function confirmedSpec(key: string): PropSpec | null {
  const s = PROP_SPECS[key];
  return s && s.status === 'confirmed' ? s : null;
}
