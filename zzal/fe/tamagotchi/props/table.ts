// 소품 상황표 — `~/.claude/soma/lore/contract/소품-상황표-v1.json`(v1 · 2026-09-13)을 **그대로 옮긴** 표.
//
// ★ 손으로 고치지 않는다. 표가 바뀌면 그 JSON 을 다시 옮긴다. **코드에 자세별 소품을 적지 않는다** —
//   표가 바뀌어도 코드를 안 고치고 따라가는 것이 이 계층의 존재 이유다.
//
// 읽는 법
//   한 줄 = `{situation id, pose, prop, anchor, layer, priority, status}`.
//   `prop: null` 은 **"이 상황에는 소품이 없다"는 확정**이다. 값이 없어서 빈 것이 아니다.
//   `pose: '*'` 는 자세와 무관하게 깔리는 것(흔적·하루 소품·가방·엽서틀·매트).
//   `prop` 에 `|` 가 있으면 **그중 하나를 고른다**(하루 소품).
//   `stages` 는 그 상황에서 쓰는 단계와 **순서**다(주먹밥 3알->1알 · 거품 조금->가득).
//   ⚠️ **번호가 곧 양은 아니다.** `n` 이 무엇을 세는지는 소품마다 다르다 — 규격 `note` 와 manifest 비고를
//      읽고 정한다. 주먹밥은 `n` 이 **파일 번호**라 `bowl_1` 이 3알이고 `bowl_3` 이 1알이다(줄어들려면 1->2->3).
//      거품·먼지는 `n` 이 **진행 단계**라 1->2->3 이 그대로 차오름·흩날림이다.
//
// 층(`layer`) — 겹침 규칙이 여기서 갈린다
//   floor·room·screen 은 서로 겹쳐도 된다. **char 만 한 자리(anchor)에 하나**다.
// 우선순위 — 같은 자리를 둘이 노리면 **숫자가 작은 쪽**이 이긴다.
//   1 해금 폭죽 > 2 게임 결과 > 3 재회 하트 > 4 상태 표시 > 5 평상시 반짝임·음표
import type { SituationRow } from './situations';

export const SITUATION_TABLE_VERSION = '1';

/**
 * `status` 가 `confirmed` 가 아닌 줄은 **값이 바뀐다** — 상훈님 결정 전이거나 실물 판정 대기다.
 *   `decide`  상황표 정본 3절에서 고르셔야 하는 것(아픈 아이의 땀/해골 · 둘째 하트 · 낱개냐 말풍선이냐)
 *   `pending` 실물 판정 대기(간식 · 게임 짐 · 물줄기 · 컵)
 *   `default` 이 표가 제안한 기본값. 이의 없으면 이대로 간다
 * 개발 화면에서는 이 셋을 **점선 테두리**로 구분해 보여 준다(운영에서는 안 보인다).
 */
export const SITUATION_TABLE: readonly SituationRow[] = [
  /** 평상시 */
  { id: "idle", pose: "base", prop: null, anchor: null, layer: "char", priority: null, status: "confirmed" },
  /** 아이가 먼저 말 검(하루 3회 부름) */
  { id: "child_speaks", pose: "base", prop: "bubble_bang", anchor: "head_side", layer: "char", priority: 4, status: "confirmed" },
  /** 대화창 열림·되묻기 */
  { id: "chat_open", pose: "base", prop: "bubble_question", anchor: "head_side", layer: "char", priority: 4, status: "confirmed" },
  /** 청소하기 — sweep 잠김 */
  { id: "clean_l1", pose: "base", prop: "dust", anchor: "screen_bottom", layer: "screen", priority: null, status: "confirmed", stages: [1, 2, 3] },
  /** 목욕하기 1단계 — 거품이 먼저 차오른다 */
  { id: "bath_l1_foam", pose: "base", prop: "bath", anchor: "screen_bottom", layer: "screen", priority: null, status: "confirmed", stages: [1, 2, 3] },
  /** 목욕하기 2단계 — 물줄기가 위에서 헹군다 */
  { id: "bath_l1_rinse", pose: "base", prop: "shower", anchor: "screen_full", layer: "screen", priority: null, status: "pending" },
  /**
   * 밥 주기 — eat_rice 잠김. 머리 옆은 비운다.
   *
   * ⚠️ **정본 JSON 은 아직 `[3, 2, 1]` 이고, 그대로 옮기면 밥이 먹을수록 늘어난다**(2026-09-13 실측).
   *   `stages[].n` 이 **밥알 개수가 아니라 파일 번호**이기 때문이다 —
   *   manifest.tsv: `bowl_1` = 3알(가득) · `bowl_2` = 2알 · `bowl_3` = 1알(거의 빔). 그림으로도 확인했다.
   *   상훈님이 말씀하신 "주먹밥 3 -> 2 -> 1" 은 **밥알 개수**이므로 파일 차례로는 `1 -> 2 -> 3` 이다.
   *   실제로 `[3,2,1]` 로 돌렸더니 58 -> 107 -> 124px 로 **커졌다.**
   *   ★ 상황표 JSON 이 같은 값으로 고쳐지면 이 주석과 함께 그냥 옮겨 적으면 된다.
   */
  { id: "feed_rice_l1", pose: "eat", prop: "bowl", anchor: "hand_front", layer: "char", priority: null, status: "confirmed", stages: [1, 2, 3] },
  /** 간식 주기 — eat_snack 잠김. 머리 옆은 비운다 */
  { id: "feed_snack_l1", pose: "eat", prop: "snack", anchor: "hand_front", layer: "char", priority: null, status: "pending" },
  /** 약 주기 — 2층으로 안 간다(아프게 해야 상을 받는 구조가 되므로) */
  { id: "give_medicine", pose: "eat", prop: "medicine", anchor: "hand_front", layer: "char", priority: null, status: "confirmed" },
  /** 해금 '○○를 배웠어요' · 부화 직후. ⚠ firework 가 소품-규격-v1.2.json items 에 빠져 있다 — 채워야 함 */
  { id: "unlock", pose: "joy", prop: "firework", anchor: "head_top", layer: "char", priority: 1, status: "confirmed" },
  /** 게임 이김 — 영문 WIN 안 씀. 별·꽃가루 */
  { id: "game_win", pose: "joy", prop: "win_star", anchor: "head_side", layer: "char", priority: 2, status: "confirmed" },
  /** 재회한 순간. 낱개냐 말풍선이냐 = 3절 (3) */
  { id: "reunion", pose: "joy", prop: "heart", anchor: "head_side", layer: "char", priority: 3, status: "decide" },
  /** 병이 나음 */
  { id: "cured", pose: "joy", prop: "sparkle", anchor: "head_top", layer: "char", priority: 4, status: "confirmed" },
  /** 공유·다운로드 한 뒤 · 기분 좋은 날 아침 */
  { id: "share_done", pose: "joy", prop: "bubble_note", anchor: "head_side", layer: "char", priority: 5, status: "confirmed" },
  /** 게임 짐. 이 순간이 선물 2(fall_back) 해금 조건이기도 하다 */
  { id: "game_lose", pose: "sad", prop: "lose_dots", anchor: "head_side", layer: "char", priority: 2, status: "pending" },
  /** 배부름 0. 배 높이·캐릭터 반대쪽 */
  { id: "hunger_zero", pose: "sad", prop: "growl", anchor: "hand_front", layer: "char", priority: 4, status: "confirmed" },
  /** 행복 0(삐침) */
  { id: "happy_zero", pose: "sad", prop: "bubble_dots", anchor: "head_side", layer: "char", priority: 4, status: "confirmed" },
  /** 아프기 시작. 땀↔해골을 갈아 끼울지 같이 띄울지 = 3절 (1) */
  { id: "sick_light", pose: "sick", prop: "sweat", anchor: "head_side", layer: "char", priority: 4, status: "decide" },
  /** 24시간 넘게 방치된 아픔 */
  { id: "sick_long", pose: "sick", prop: "skull", anchor: "head_side", layer: "char", priority: 4, status: "decide" },
  /** 쓰다듬기 — petted 잠김. 아랫변이 정수리 아래 K×0.05(머리카락에 묻힌다) */
  { id: "pet_l1_hand", pose: "pet", prop: "pet_hand", anchor: "head_top", layer: "char", priority: null, status: "confirmed" },
  /** 쓰다듬는 중 기분. 친밀도가 높으면 둘 — 둘째 자리 = 3절 (2) */
  { id: "pet_l1_mood", pose: "pet", prop: "bubble_heart", anchor: "head_side", layer: "char", priority: 4, status: "decide" },
  /** 방에 들어올 때 · 앱을 켤 때 */
  { id: "enter_room", pose: "hello", prop: "bubble_bang", anchor: "head_side", layer: "char", priority: 4, status: "confirmed" },
  /** 친밀도 80% 이상에서 반길 때 */
  { id: "hello_intimate", pose: "hello", prop: "heart", anchor: "head_side", layer: "char", priority: 3, status: "decide" },
  /** 자는 동안. 낱개 zzz 와 겹친다 = 3절 (3) */
  { id: "sleeping", pose: "sleep", prop: "bubble_zzz", anchor: "head_side", layer: "char", priority: 4, status: "decide" },
  /** 반투명 커튼 — 너머로 자는 아이가 보여야 한다 */
  { id: "sleeping_curtain", pose: "sleep", prop: "curtain_sheer", anchor: "screen_full", layer: "screen", priority: null, status: "confirmed" },
  /** 밤. 캐릭터와 겹침 0 · 화면 왼쪽 위 */
  { id: "sleeping_moon", pose: "sleep", prop: "moon", anchor: "screen_full", layer: "room", priority: null, status: "confirmed" },
  /** 밥 주기 — 만화 고기가 그림 안에 있다 */
  { id: "feed_rice_l2", pose: "eat_rice", prop: null, anchor: null, layer: "char", priority: null, status: "confirmed" },
  /** 간식 주기 — 막대사탕이 그림 안에 있다 */
  { id: "feed_snack_l2", pose: "eat_snack", prop: null, anchor: null, layer: "char", priority: null, status: "confirmed" },
  /** 청소하기 — 빗자루는 그림 안. 먼지는 2단계까지만(기본값) */
  { id: "clean_l2", pose: "sweep", prop: "dust", anchor: "screen_bottom", layer: "screen", priority: null, status: "default", stages: [1, 2] },
  /** 목욕하기 — 욕조·흰 가운은 그림 안. 거품은 2단계까지 */
  { id: "bath_l2_foam", pose: "wash", prop: "bath", anchor: "screen_bottom", layer: "screen", priority: null, status: "confirmed", stages: [1, 2] },
  /** 헹구는 물줄기 */
  { id: "bath_l2_rinse", pose: "wash", prop: "shower", anchor: "screen_full", layer: "screen", priority: null, status: "pending" },
  /** 대화에 답한 뒤 */
  { id: "reply_done", pose: "reply", prop: "bubble_note", anchor: "head_side", layer: "char", priority: 5, status: "confirmed" },
  /** 친밀도 80% 이상 */
  { id: "reply_intimate", pose: "reply", prop: "heart", anchor: "head_side", layer: "char", priority: 3, status: "decide" },
  /** 쓰다듬기 — 손이 그림 안에 그려진다 */
  { id: "pet_l2", pose: "petted", prop: null, anchor: null, layer: "char", priority: null, status: "confirmed" },
  /** 게임에서 좌/우 고르는 순간. 그림상세가 머리 꼭대기 위를 느낌표 자리로 비워 뒀다 */
  { id: "game_choose", pose: "startle", prop: "bubble_bang", anchor: "head_top", layer: "char", priority: 4, status: "default" },
  /** 손으로 깨울 때. 커튼이 걷히는 것이 신호다(해 sun 은 규격 v1.2에서 폐기) */
  { id: "wake_by_hand", pose: "wake_up", prop: null, anchor: null, layer: "char", priority: null, status: "default" },
  /** 바닥 흔적. 깨어 있는 4시간마다 +1(최대 4). 청소 한 번에 −1. 파리·냄새는 이 그림에 합쳐져 있다. 캐릭터가 보는 쪽(기본값) */
  { id: "trash_always", pose: "*", prop: "trash", anchor: "foot_front", layer: "floor", priority: null, status: "confirmed", stages: [1, 2, 3, 4] },
  /** 하루 소품 — 아침에 하나 뽑는다. 발치(코드의 '손 옆'이 아니라 · 규격 v1.2 foot_front). 흔적의 반대쪽(기본값) */
  { id: "daily_prop", pose: "*", prop: "prop_ball|prop_book|prop_cup|prop_plant", anchor: "foot_front", layer: "room", priority: null, status: "default" },
  /** 떠남 예고(짐 싸기). 접속하면 즉시 사라진다 */
  { id: "leaving_soon", pose: "*", prop: "bag", anchor: "foot_front", layer: "room", priority: null, status: "confirmed" },
  /** 여행 엽서 틀 · 앨범 */
  { id: "album_frame", pose: "*", prop: "postcard", anchor: "screen_full", layer: "screen", priority: null, status: "confirmed" },
  /** 바닥 매트. 캐릭터보다 먼저 그린다(아이 밑에 깔린다) */
  { id: "floor_mat", pose: "*", prop: "mat", anchor: "foot_front", layer: "room", priority: null, status: "confirmed" },
];
