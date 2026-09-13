// **어떤 상황에 어떤 소품을 띄우나** — 표를 읽어 고르는 자리.
//
// ★ 표가 정본이고 코드는 표를 읽기만 한다(`table.ts` = `contract/소품-상황표-v1.json` 그대로).
//   **자세별 소품을 코드에 적지 않는다.** 표가 바뀌면 코드를 안 고치고도 따라가야 한다.
// ★ 표가 없으면(`null`) 아무 소품도 안 띄우고 조용히 지나간다. 고장이 아니라 "아직 없음" 이다.
// ★ 규격에 없는 소품(지금은 `firework`)은 **조용히 건너뛴다.** 오류로 화면을 멈추지 않는다.

import { confirmedSpec, PROP_SPECS } from './catalog';
import { SITUATION_TABLE } from './table';
import type { PropAnchor, PropSpec, PropStage } from './spec';

/** 층. `char` 만 한 자리에 하나고, 나머지는 서로 겹쳐도 된다. */
export type PropLayerName = 'floor' | 'room' | 'screen' | 'char';

/** 표 한 줄이 확정인지, 아직 바뀔 수 있는지. */
export type SituationStatus = 'confirmed' | 'pending' | 'decide' | 'default';

/** 상황표 한 줄. `contract/소품-상황표-v1.json` 의 `situations[]` 와 같은 모양이다. */
export interface SituationRow {
  id: string;
  /** 그 자세일 때만. `'*'` 는 자세와 무관하게 깔리는 것. */
  pose: string;
  /** 띄울 소품. **`null` 은 "이 상황에는 소품이 없다"는 확정**이다. `|` 는 그중 하나를 고른다. */
  prop: string | null;
  anchor: PropAnchor | null;
  layer: PropLayerName;
  /** 같은 자리를 둘이 노리면 **작은 쪽**이 이긴다. `null` = 그 다툼에 안 들어간다. */
  priority: number | null;
  status: SituationStatus;
  /** 그 상황에서 쓰는 단계와 순서(주먹밥 3->2->1 · 거품 1->2->3). */
  stages?: readonly number[];
}

export type PropSituationTable = readonly SituationRow[];

/** 지금 화면에서 벌어지고 있는 것. **무엇을 띄울지는 정하지 않는다** — 표가 정한다. */
export interface PropScene {
  /** 지금 짓고 있는 자세(카탈로그 key). 옛 이름이어도 된다. */
  pose: string;
  /** 지금 켜져 있는 상황 id 들. 표에 없는 이름은 조용히 무시된다. */
  active: readonly string[];
  /** 단계가 있는 소품의 **지금 단계**(예: `{ trash: 2 }`). 없으면 그 줄의 첫 단계. */
  stages?: Record<string, number>;
  /** `|` 로 여럿 중 고르는 줄에서 무엇을 골랐나(예: `{ daily_prop: 'prop_book' }`). */
  choices?: Record<string, string>;
}

/** 그릴 거리 한 장 — 규격·단계·어느 줄에서 왔는지. */
export interface ResolvedProp {
  spec: PropSpec;
  stage: PropStage;
  row: SituationRow;
}

/**
 * **행동 한 번 = 상황 한 줄.** 행동이 자세를 틀 때 "그 행동이 어떤 상황인지" 도 같이 말하게 하는 한 곳.
 *
 * ⚠️ 값은 **소품 이름이 아니라 상황 id** 다 — 무엇을 그릴지는 표(`table.ts`)가 그대로 정한다.
 *   여기서 하는 일은 "지금 벌어진 일" 에 표의 낱말을 붙여 주는 것까지다.
 * ★ **그 자세의 줄을 전부 켜지 않는다.** `eat` 한 자세에 밥(`bowl`)·간식(`snack`)·약(`medicine`)이
 *   같이 달려 있어서, 자세로 켜면 **밥을 줬는데 약병까지 뜬다.** 행동마다 한 줄씩 짝지어야 한다.
 * ★ 짝은 **표를 읽어** 지었다(추측하지 않았다). 표에 줄이 없는 행동은 여기 없다 —
 *   `재우기` 는 행동이 아니라 상태라 `sleeping` 이 맡고(아래), `공유하기` 는 `share_done` 이 받는다.
 */
export const ACTION_SITUATION = {
  /** 쓰다듬기 — 1층 `pet_l1_hand`(pose `pet` · `pet_hand`) / 2층 `pet_l2`(pose `petted` · 손이 그림 안).
   *  같은 자세의 `pet_l1_mood`(기분 하트)는 친밀도가 높을 때만이고 status=decide 라 아직 안 켠다. */
  pet: { l1: 'pet_l1_hand', l2: 'pet_l2' },
  /** 밥 주기 — 1층 `feed_rice_l1`(pose `eat` · `bowl`) / 2층 `feed_rice_l2`(pose `eat_rice` · 고기가 그림 안). */
  feed_rice: { l1: 'feed_rice_l1', l2: 'feed_rice_l2' },
  /** 간식 주기 — 1층 `feed_snack_l1`(pose `eat` · `snack`, **규격 pending 이라 안 뜨는 게 정상**)
   *  / 2층 `feed_snack_l2`(pose `eat_snack` · 막대사탕이 그림 안). */
  feed_snack: { l1: 'feed_snack_l1', l2: 'feed_snack_l2' },
  /** 약 주기 — `give_medicine`(pose `eat` · `medicine`). **2층으로 안 간다**(표 주석: 아프게 해야 상을 받는 구조가 되므로). */
  medicine: { l1: 'give_medicine' },
  /** 청소하기 — 1층 `clean_l1`(pose `base` · `dust` 1~3) / 2층 `clean_l2`(pose `sweep` · 빗자루는 그림 안 · `dust` 1~2). */
  clean: { l1: 'clean_l1', l2: 'clean_l2' },
  /** 목욕하기 — 1층 `bath_l1_foam`(pose `base` · `bath` 1~3) / 2층 `bath_l2_foam`(pose `wash` · `bath` 1~2).
   *  헹구는 물줄기(`bath_l1_rinse`·`bath_l2_rinse`)는 규격 pending 이라 아직 안 켠다. */
  bath: { l1: 'bath_l1_foam', l2: 'bath_l2_foam' },
  /** 대화 답하기 — `reply_done`(pose `reply` · `bubble_note`). ⚠️ 표에 **1층 줄이 없다** — 답하기 자세 자체가 2층이다. */
  reply: { l1: 'reply_done' },
  /** 게임 이김 — `game_win`(pose `joy` · `win_star`). */
  game_win: { l1: 'game_win' },
  /** 게임 짐 — `game_lose`(pose `sad` · `lose_dots`). 규격 pending 이라 아직 안 뜬다. */
  game_lose: { l1: 'game_lose' },
  /** 공유·저장 직후 — `share_done`(pose `joy` · `bubble_note`). */
  share: { l1: 'share_done' },
  /** 손으로 깨우기 — `wake_by_hand`(pose `wake_up`). **표가 `prop: null` 로 "소품 없음" 을 확정**했다
   *  (커튼이 걷히는 것이 신호다). ⚠️ 1층 줄이 없다 — 깨어나는 자세 자체가 2층이다. */
  wake: { l1: 'wake_by_hand' },
} as const satisfies Record<string, { l1: string; l2?: string }>;

export type ActionKey = keyof typeof ACTION_SITUATION;

/**
 * 그 행동이 지금 켤 **상황 id 하나**. 2층이 열려 있고 그 행동에 2층 줄이 있으면 2층, 아니면 1층.
 *
 * ★ 왜 층으로 갈리나 — 표가 같은 행동을 두 줄로 적어 두었다. 1층은 **소품이 대신하고**(밥그릇·쓰다듬는 손),
 *   2층은 **그림 안에 이미 들어 있다**(`prop: null`). 그러니 층이 곧 "소품을 띄우느냐" 를 정한다.
 */
export function situationOfAction(action: ActionKey, floor2 = false): string {
  const row: { l1: string; l2?: string } = ACTION_SITUATION[action];
  return floor2 ? (row.l2 ?? row.l1) : row.l1;
}

/**
 * 그 상황이 **표에서 어느 자세에 붙어 있나**. 없거나 자세와 무관한 줄(`'*'`)이면 `null`.
 *
 * ★ 왜 필요한가 — 그리는 쪽(`resolveScene`)이 `row.pose === scene.pose` 를 요구한다.
 *   행동이 짓는 자세와 그 행동의 상황 줄이 **어긋나면 소품이 영영 안 뜬다**(2026-09-13 실제 사고).
 *   그래서 자세를 코드가 따로 정하지 않고 **표가 적어 둔 자세를 그대로 쓴다.**
 */
export function poseOfSituation(table: PropSituationTable | null | undefined, id: string): string | null {
  const row = (table ?? []).find((r) => r.id === id);
  return row && row.pose !== '*' ? row.pose : null;
}

/**
 * **표가 쓰는 상황 id 로 옮기는 어댑터.** 화면이 아는 상태를 표의 낱말로 번역만 한다 —
 * 여기서 소품을 고르지 않는다(고르는 것은 표의 몫).
 *
 * ⚠️ 화면이 아직 모르는 상황(재회·해금·친밀도 80%)은 여기 없다.
 *   그 신호가 화면에 생기면 **이 함수에 id 한 줄씩만** 더하면 된다. 표는 이미 그 줄을 들고 있다.
 */
export function activeSituations(s: {
  pose: string;
  sick?: boolean;
  sleeping?: boolean;
  /** ⚠️ 지금은 **안 쓴다** — 꼬르륵 소품을 뺐다(아래). 신호 자체는 남겨 둔다. */
  hungry?: boolean;
  unhappy?: boolean;
  chatOpen?: boolean;
  trash?: number;
  /** 지금 도는 **행동**의 상황 id(`ACTION_SITUATION` 의 값). 연출이 끝나면 같이 사라진다. */
  act?: string | null;
}): string[] {
  const out: string[] = [];
  // ★ 행동이 맨 앞이다 — 지금 벌어지고 있는 일이 상태보다 앞선다.
  //   `char` 층의 같은 자리를 다투면 표의 `priority` 가 정하므로, 여기 순서는 표를 이기지 않는다.
  if (s.act) out.push(s.act);
  if (s.sleeping) out.push('sleeping', 'sleeping_curtain', 'sleeping_moon');
  // 땀↔해골을 갈아 끼울지 같이 띄울지는 아직 결정 전(표 status=decide)이라, 표의 제안대로
  // **갈아 끼운다** — 아프기 시작하면 땀. '24시간 방치' 신호가 화면에 오면 sick_long 을 더한다.
  if (s.sick) out.push('sick_light');
  // ★ 배고픔 소품(꼬르륵)은 **안 띄운다**(상훈님 2026-09-13 "지금 배고파서 꼬르륵 소품은 빼는 게 맞는 것 같아").
  //   표의 `hunger_zero` 줄과 `growl` 그림은 **그대로 둔다** — 나중에 되살릴 수 있게. 여기서만 안 켠다.
  //   (표 쪽 `status` 도 곧 바뀌지만, **둘 중 하나만 되어 있어도 안 뜨도록** 코드에서도 막아 둔다.)
  if (s.unhappy) out.push('happy_zero');
  if (s.chatOpen) out.push('chat_open');
  if ((s.trash ?? 0) > 0) out.push('trash_always');
  out.push('idle');
  return out;
}

/**
 * 개발용(연습방 고르기) — 표에서 **그 자세에 붙어 있는 상황 id** 전부.
 *
 * ★ 소품은 여전히 표가 고른다. 여기서 내는 것은 "그 자세에서 켤 수 있는 상황"의 목록뿐이다 —
 *   코드에 자세별 소품을 적지 않는다는 원칙은 그대로다.
 */
export function situationsOfPose(table: PropSituationTable | null | undefined, pose: string): string[] {
  if (!table) return [];
  return table.filter((r) => r.pose === pose).map((r) => r.id);
}

/** 자세와 무관하게 깔리는 줄(`pose: '*'`)의 id. 자세를 손으로 고를 때도 이건 그대로 둔다. */
export function alwaysSituationIds(table: PropSituationTable | null | undefined): Set<string> {
  return new Set((table ?? []).filter((r) => r.pose === '*').map((r) => r.id));
}

const warned = new Set<string>();

function warnOnce(key: string, why: string) {
  if (warned.has(key)) return;
  warned.add(key);
  // eslint-disable-next-line no-console
  console.warn(`[소품] ${why} — ${key}. 이 장은 건너뜁니다(화면은 그대로 돕니다).`);
}

/** `a|b|c` 중 하나를 고른다. 고른 것이 없으면 **규격에 있는 첫 번째**. */
function choose(row: SituationRow, scene: PropScene): string | null {
  const names = (row.prop ?? '').split('|').map((x) => x.trim()).filter(Boolean);
  if (names.length === 0) return null;
  const picked = scene.choices?.[row.id];
  if (picked && names.includes(picked)) return picked;
  return names.find((n) => !!confirmedSpec(n)) ?? names[0];
}

/**
 * 지금 그릴 소품 전부. 순서는 —
 *   1. 표에서 **지금 켜진 줄**만 고른다(자세가 맞거나 `'*'`)
 *   2. `prop: null` 은 건너뛴다(소품이 없다는 확정)
 *   3. 규격에 없거나 아직 못 쓰는 소품은 **조용히 건너뛴다**(경고는 한 번만)
 *   4. ★ `layer: 'char'` 는 **한 자리(anchor)에 하나** — 우선순위가 작은 쪽이 남는다
 *      (`priority: null` 은 그 다툼에 안 들어가므로 가장 뒤로 민다)
 */
export function resolveScene(table: PropSituationTable | null | undefined, scene: PropScene): ResolvedProp[] {
  if (!table) return [];
  const on = new Set(scene.active);

  const rows = table.filter((r) => on.has(r.id) && (r.pose === '*' || r.pose === scene.pose));

  const out: ResolvedProp[] = [];
  /** 캐릭터 층의 자리별 승자. */
  const seat = new Map<string, { row: SituationRow; item: ResolvedProp }>();

  for (const row of rows) {
    if (!row.prop) continue;
    const key = choose(row, scene);
    if (!key) continue;

    const spec = confirmedSpec(key);
    if (!spec) {
      warnOnce(key, PROP_SPECS[key] ? '규격이 아직 재제작 대기(pending)입니다' : '규격에 없는 소품입니다');
      continue;
    }

    const want = scene.stages?.[key] ?? row.stages?.[0];
    const stage = (want !== undefined ? spec.stages.find((s) => s.n === want) : undefined) ?? spec.stages[0];
    if (!stage) continue;

    const item: ResolvedProp = { spec, stage, row };
    if (row.layer !== 'char') { out.push(item); continue; }

    // ★ 한 자리에 하나. 자리는 앵커 이름이 말한다.
    const at = row.anchor ?? spec.anchor;
    const cur = seat.get(at);
    const rank = (r: SituationRow) => r.priority ?? 99;
    if (!cur || rank(row) < rank(cur.row)) seat.set(at, { row, item });
  }

  for (const s of seat.values()) out.push(s.item);
  return out;
}

/** 그 줄이 아직 바뀔 수 있는가 — 개발 화면에서 점선으로 구분해 보여 준다. */
export const isSettled = (row: SituationRow) => row.status === 'confirmed';

export { SITUATION_TABLE } from './table';
