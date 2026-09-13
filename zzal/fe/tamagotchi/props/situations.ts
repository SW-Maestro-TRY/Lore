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
 * **표가 쓰는 상황 id 로 옮기는 어댑터.** 화면이 아는 상태를 표의 낱말로 번역만 한다 —
 * 여기서 소품을 고르지 않는다(고르는 것은 표의 몫).
 *
 * ⚠️ 화면이 아직 모르는 상황(재회·해금·게임 승패·친밀도 80%·공유 직후)은 여기 없다.
 *   그 신호가 화면에 생기면 **이 함수에 id 한 줄씩만** 더하면 된다. 표는 이미 그 줄을 들고 있다.
 */
export function activeSituations(s: {
  pose: string;
  sick?: boolean;
  sleeping?: boolean;
  hungry?: boolean;
  unhappy?: boolean;
  chatOpen?: boolean;
  trash?: number;
}): string[] {
  const out: string[] = [];
  if (s.sleeping) out.push('sleeping', 'sleeping_curtain', 'sleeping_moon');
  // 땀↔해골을 갈아 끼울지 같이 띄울지는 아직 결정 전(표 status=decide)이라, 표의 제안대로
  // **갈아 끼운다** — 아프기 시작하면 땀. '24시간 방치' 신호가 화면에 오면 sick_long 을 더한다.
  if (s.sick) out.push('sick_light');
  if (s.hungry) out.push('hunger_zero');
  if (s.unhappy) out.push('happy_zero');
  if (s.chatOpen) out.push('chat_open');
  if ((s.trash ?? 0) > 0) out.push('trash_always');
  out.push('idle');
  return out;
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
