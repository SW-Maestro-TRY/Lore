// 개발 리모컨 — **서버를 진짜로 바꾸는 판**(2026-10-12, remote-control-spec-1011).
//
// ★ 상훈님 원문(10/11): "정말 모든 기능이 다 리모컨에 들어가야 돼. 똥 갯수 1~4, 여행 가면 어떻게 되는 지,
//   시간 조절도 됐으면 좋겠고 내가 직접 리모컨을 눌러보면서 우리 서버에서 일어날 수 있는 거의 모든 상황으로
//   조작하고 직접 보는 걸 원해."
//
// ★ 예전 판과 다른 점 하나 — 여기 버튼은 **화면에 덮어쓰지 않는다.** 누르면 dev API 가 서버 값을 바꾸고,
//   돌아온 서버 상태(`after`)를 그대로 그린다. 그래서 새로고침해도 남고, 시계를 넘기면 운영 규칙대로 이어진다
//   (똥 4 → 6시간 → 더러움 병 → 케어 미스 → 떠남 예고 → 여행).
// ★ 응답의 `effects[]`(이번에 바뀐 칸마다 한 줄)를 토스트로 띄운다 — "눌렀는데 무슨 일이 났나" 를 서버가 말한다.
// ★ 노출은 `GET /dev/ping` 이 200 일 때만(결정 3). 도메인으로 가르지 않는다 — 진짜 자물쇠는 서버 스위치다.
'use client';

import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { ApiError } from '../../lib/api';
import {
  advanceClock, devBake, devBakeProgress, devEvent, devPatch, devPing, devState, forceOpen, nextLocalAt,
  nightSweep, setClockAt, type DevBakeProgress, type DevPatch, type DevPing, type DevResult, type DevStateMap,
} from '../../lib/dev';
import type { PetDetail } from '../../lib/pet';
import { C, MONO, TAP_MIN, chipTone, fz, gap, monoSize, radius } from './ui';

/**
 * 이 서버에 dev 도구가 켜져 있나 — `GET /api/zzal/v1/dev/ping` 이 200 이면 그 답, 아니면 null.
 *
 * ★ 로그인 전에는 401 이라 null 이다. 로그인·아이가 바뀌면(`key`) 다시 묻는다.
 * ★ 첫 렌더는 늘 null — 서버 렌더와 갈리지 않게(하이드레이션).
 */
export function useDevPing(key: unknown): DevPing | null {
  const [ping, setPing] = useState<DevPing | null>(null);
  useEffect(() => {
    let alive = true;
    devPing().then((p) => { if (alive) setPing(p?.devTools ? p : null); }).catch(() => { if (alive) setPing(null); });
    return () => { alive = false; };
  }, [key]);
  return ping;
}

/** 토스트 한 장. */
interface Toast { id: number; title: string; lines: string[]; bad?: boolean }

export type RemoteTab = 'state' | 'events' | 'clock' | 'bake';

interface Props {
  tab: RemoteTab;
  petId: number;
  pet: PetDetail | null;
  ping: DevPing;
  /** 서버가 돌려준 상태를 화면에 얹는다(다시 조회하지 않는다 — 조회는 방문이라 떠남 예고가 취소된다). */
  onDetail: (next: PetDetail) => void;
  /** 채팅·게임처럼 판 상태까지 다시 받아야 하는 이벤트 뒤에 부른다(그 조회는 방문이다). */
  onReload: () => Promise<unknown>;
}

/** 리모컨 본문 — 탭 하나를 그린다. 토스트는 패널 위에 뜬다. */
export default function DevRemote({ tab, petId, pet, ping, onDetail, onReload }: Props) {
  const [state, setState] = useState<DevStateMap | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const seq = useRef(0);

  const toast = useCallback((t: Omit<Toast, 'id'>) => {
    const id = ++seq.current;
    // ★ 한 장만 — 작은 폰(320×568)에서 석 장이 쌓이면 리모컨 탭 줄까지 덮였다(3단계 검증에서 잼). 최근 것이 이긴다.
    setToasts([{ ...t, id }]);
    window.setTimeout(() => setToasts((v) => v.filter((x) => x.id !== id)), t.bad ? 9000 : 7000);
  }, []);

  // 처음 열 때·아이가 바뀔 때 내부 칸을 받는다(방문으로 안 침).
  useEffect(() => {
    let alive = true;
    devState(petId).then((r) => { if (alive) setState(r.state); }).catch(() => {});
    return () => { alive = false; };
  }, [petId]);

  /** 서버 호출 하나 — 응답을 화면에 얹고 effects 를 토스트로. 실패하면 서버 말을 그대로. */
  const run = useCallback(async (label: string, call: () => Promise<DevResult>, reload = false) => {
    if (busy) return;
    setBusy(label);
    try {
      const r = await call();
      onDetail(r.after);
      setState(r.state);
      if (reload) await onReload();
      toast({ title: label, lines: r.effects.length ? r.effects : ['바뀐 칸 없음'] });
    } catch (e) {
      toast({ title: `${label} — 실패`, lines: [errorText(e)], bad: true });
    } finally {
      setBusy(null);
    }
  }, [busy, onDetail, onReload, toast]);

  const patch = (label: string, p: DevPatch) => run(label, () => devPatch(petId, p));
  const fire = (label: string, kind: string, arg?: string | number, reload = false) =>
    run(label, () => devEvent(petId, kind, arg), reload);

  const body = tab === 'state' ? <StateTab s={state} pet={pet} busy={busy} patch={patch} fire={fire} />
    : tab === 'events' ? <EventsTab busy={busy} fire={fire} />
      : tab === 'clock' ? <ClockTab petId={petId} pet={pet} busy={busy} run={run} onDetail={onDetail} toast={toast} setBusy={setBusy} />
        : <BakeTab petId={petId} ping={ping} busy={busy} onDetail={onDetail} setState={setState} toast={toast} setBusy={setBusy} />;

  return (
    <>
      {body}
      <Toasts list={toasts} onClose={(id) => setToasts((v) => v.filter((x) => x.id !== id))} />
    </>
  );
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 404) return '이 서버는 개발 도구가 꺼져 있거나 없는 아이예요(404)';
    return `${e.status} ${e.message}`;
  }
  return e instanceof Error ? e.message : String(e);
}

// ── 공용 조각 ─────────────────────────────────────────────────────────────

const btnBase: CSSProperties = {
  minHeight: TAP_MIN, minWidth: TAP_MIN, padding: '0 12px', borderRadius: radius.pill,
  fontSize: fz.sm, lineHeight: 1.2, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
};

/** 누르는 칸 하나(44px). `on` = 지금 서버 값이 이것이다. */
function Btn({ label, on = false, busy, onClick, title, id, danger }: {
  label: string; on?: boolean; busy: string | null; onClick: () => void; title?: string; id?: string; danger?: boolean;
}) {
  const t = chipTone(on);
  return (
    <button
      type="button" data-remote={id ?? label} data-on={on ? '1' : '0'} title={title}
      disabled={!!busy} onClick={onClick}
      style={{
        ...btnBase, border: `1px solid ${danger ? C.accent : t.bd}`, background: t.bg,
        color: danger && !on ? C.accent : t.fg, opacity: busy ? 0.55 : 1,
      }}
    >{label}</button>
  );
}

/** 한 줄 — 위에 이름(+지금 값), 아래에 칸들. 좁은 폰에서 이름이 칸을 밀어내지 않게 위아래로 쌓는다. */
function Line({ name, value, children, note }: { name: string; value?: ReactNode; children: ReactNode; note?: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.xs }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm, flexWrap: 'wrap' }}>
        <span style={{ fontSize: fz.sm, color: C.sub }}>{name}</span>
        {value != null && <span data-remote-value={name} style={{ font: `${monoSize.sm}px ${MONO}`, color: C.ink }}>{value}</span>}
        {note && <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>{note}</span>}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.xs }}>{children}</div>
    </div>
  );
}

function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section style={{ display: 'flex', flexDirection: 'column', gap: gap.sm, paddingTop: gap.sm, borderTop: `1px dashed ${C.lineHard}` }}>
      <h4 style={{ margin: 0, font: `${monoSize.sm}px ${MONO}`, color: C.faint, letterSpacing: '.04em' }}>{title}</h4>
      {children}
    </section>
  );
}

/** − 값 + 스테퍼. */
function Stepper({ name, value, min, max, step = 1, busy, onSet }: {
  name: string; value: number | null; min: number; max: number; step?: number; busy: string | null; onSet: (v: number) => void;
}) {
  const v = value ?? 0;
  return (
    <Line name={name} value={value ?? '—'}>
      <Btn label="−" id={`${name}:-`} busy={busy} onClick={() => onSet(Math.max(min, v - step))} />
      <Btn label="+" id={`${name}:+`} busy={busy} onClick={() => onSet(Math.min(max, v + step))} />
      <Btn label={String(min)} id={`${name}:${min}`} busy={busy} on={v === min} onClick={() => onSet(min)} />
    </Line>
  );
}

const num = (s: DevStateMap | null, k: string): number | null => (s && typeof s[k] === 'number' ? (s[k] as number) : null);
const str = (s: DevStateMap | null, k: string): string | null => (s && typeof s[k] === 'string' ? (s[k] as string) : null);
const bool = (s: DevStateMap | null, k: string): boolean => !!(s && s[k] === true);

type Patch = (label: string, p: DevPatch) => void;
type Fire = (label: string, kind: string, arg?: string | number, reload?: boolean) => void;

// ── 상태 ─────────────────────────────────────────────────────────────────

const SICK: ReadonlyArray<readonly [string, string]> = [
  ['NONE', '없음'], ['NEGLECT', '방치'], ['DIRTY', '더러움'], ['UPSET', '배탈'], ['NATURAL', '자연'],
];
const PIECES: ReadonlyArray<readonly [string, string]> = [['FOOD', '밥'], ['PLAY', '놀이'], ['CLEAN', '청결'], ['BOND', '교감']];

function StateTab({ s, pet, busy, patch, fire }: {
  s: DevStateMap | null; pet: PetDetail | null; busy: string | null; patch: Patch; fire: Fire;
}) {
  if (!s) return <p style={{ margin: 0, fontSize: fz.sm, color: C.faint }}>서버 상태를 받는 중…</p>;
  const gauge = (name: string, key: string) => (
    <Line name={name} value={num(s, key)}>
      {[0, 1, 2, 3, 4].map((i) => (
        <Btn key={i} label={String(i)} id={`${key}:${i}`} on={num(s, key) === i} busy={busy}
          onClick={() => patch(`${name} ${i}`, { [key]: i })} />
      ))}
    </Line>
  );
  const sick = str(s, 'sick') ?? 'NONE';
  const pieceDone = (str(s, 'pieces') ?? '').split(',').filter(Boolean);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
      <div data-remote-summary style={{ font: `${monoSize.sm}px ${MONO}`, color: C.sub, lineHeight: 1.5 }}>
        펫 시계 {str(s, 'clock')} · {str(s, 'phase')} · 기분 {str(s, 'mood') ?? '—'} · 잠 {str(s, 'sleep')}
        {pet?.trip ? ' · 여행 중' : ''}{pet?.leaving ? ' · 떠남 예고' : ''}
      </div>

      <Group title="게이지 · 똥 · 밥">
        {gauge('배부름', 'fullness')}
        {gauge('행복', 'happiness')}
        {gauge('똥', 'trash')}
        <Line name="밥 재고" value={num(s, 'food')}>
          {[0, 1, 2, 3].map((i) => (
            <Btn key={i} label={String(i)} id={`food:${i}`} on={num(s, 'food') === i} busy={busy} onClick={() => patch(`밥 ${i}`, { food: i })} />
          ))}
        </Line>
      </Group>

      <Group title="케어 미스 · 병">
        <Stepper name="케어 미스" value={num(s, 'careMiss')} min={0} max={99} busy={busy}
          onSet={(v) => patch(`케어 미스 ${v}`, { careMiss: v })} />
        <Stepper name="오늘 케어 미스" value={num(s, 'todayCareMiss')} min={0} max={99} busy={busy}
          onSet={(v) => patch(`오늘 케어 미스 ${v}`, { todayCareMiss: v })} />
        <Stepper name="잘 돌본 날" value={num(s, 'zeroMissDays')} min={0} max={999} busy={busy}
          onSet={(v) => patch(`잘 돌본 날 ${v}`, { zeroMissDays: v })} />
        <Line name="병" value={sick} note={str(s, 'sickSince') ? `since ${str(s, 'sickSince')}` : undefined}>
          {SICK.map(([k, l]) => (
            <Btn key={k} label={l} id={`sick:${k}`} on={sick === k} busy={busy} onClick={() => patch(`병 ${l}`, { sick: k })} />
          ))}
          <Btn label="24시간+" id="sick:long" busy={busy} title="병이 난 지 25시간 — 땀 대신 해골"
            onClick={() => patch('병 24시간+', sick === 'NONE' ? { sick: 'NEGLECT', sickHoursAgo: 25 } : { sickHoursAgo: 25 })} />
        </Line>
      </Group>

      <Group title="잠 · 친밀도 · 튜토리얼">
        <Line name="잠(값만 — 밤 정산은 이벤트)" value={str(s, 'sleep')}>
          {(['NONE', 'NIGHT', 'NAP'] as const).map((k) => (
            <Btn key={k} label={{ NONE: '깨어 있음', NIGHT: '밤잠', NAP: '낮잠' }[k]} id={`sleep:${k}`}
              on={str(s, 'sleep') === k} busy={busy} onClick={() => patch(`잠 ${k}`, { sleep: k })} />
          ))}
        </Line>
        <Line name="친밀도" value={`${num(s, 'intimacy')} (최고 ${num(s, 'intimacyPeak')})`}>
          {[0, 100, 400, 800, 999].map((i) => (
            <Btn key={i} label={String(i)} id={`intimacy:${i}`} on={num(s, 'intimacy') === i} busy={busy}
              onClick={() => patch(`친밀도 ${i}`, { intimacy: i })} />
          ))}
        </Line>
        <Line name="튜토리얼 칸" value={`${num(s, 'tutorialStep')}/9${bool(s, 'clockStarted') ? ' · 시계 켜짐' : ''}`}>
          <Btn label="−" id="tutorial:-" busy={busy} onClick={() => patch('튜토리얼 칸 −1', { tutorialStep: Math.max(0, (num(s, 'tutorialStep') ?? 0) - 1) })} />
          <Btn label="+" id="tutorial:+" busy={busy} onClick={() => patch('튜토리얼 칸 +1', { tutorialStep: Math.min(9, (num(s, 'tutorialStep') ?? 0) + 1) })} />
          <Btn label="처음부터" id="tutorial:restart" busy={busy} onClick={() => fire('튜토리얼 처음부터', 'tutorial-restart')} />
          <Btn label="끝(9)" id="tutorial:9" busy={busy} on={num(s, 'tutorialStep') === 9} onClick={() => patch('튜토리얼 끝', { tutorialStep: 9 })} />
        </Line>
      </Group>

      <Group title="방문 · 떠남 · 여행">
        <Stepper name="함께한 날" value={num(s, 'daysTogether')} min={1} max={9999} busy={busy}
          onSet={(v) => patch(`함께한 날 ${v}`, { daysTogether: v })} />
        <Line name="마지막 방문" value={str(s, 'lastVisitDate')}>
          {[0, 3, 5, 10].map((d) => (
            <Btn key={d} label={d === 0 ? '오늘' : `${d}일 전`} id={`visit:${d}`} busy={busy}
              onClick={() => patch(`마지막 방문 ${d}일 전`, { lastVisitDaysAgo: d })} />
          ))}
        </Line>
        <Line name="떠남" value={str(s, 'leaveNotice') ?? '예고 없음'}>
          <Btn label="예고 켜기" id="leave:on" on={!!str(s, 'leaveNotice')} busy={busy} onClick={() => patch('떠남 예고', { leaveNotice: true })} />
          <Btn label="예고 끄기" id="leave:off" busy={busy} onClick={() => patch('떠남 예고 끔', { leaveNotice: false })} />
          <Btn label={bool(s, 'leaveEnabled') ? '떠남 기능 켜짐' : '떠남 기능 꺼짐'} id="leave:enabled" on={bool(s, 'leaveEnabled')} busy={busy}
            onClick={() => patch('떠남 기능', { leaveEnabled: !bool(s, 'leaveEnabled') })} />
        </Line>
        <Line name="여행" value={str(s, 'trip') ? `${str(s, 'trip')} · 엽서 ${num(s, 'postcards')}` : '집'}>
          <Btn label="출발" id="trip:on" on={!!str(s, 'trip')} busy={busy} onClick={() => fire('여행 출발', 'trip-start')} />
          <Btn label="엽서 +1" id="trip:postcard" busy={busy} onClick={() => fire('엽서 +1', 'postcard')} />
          <Btn label="부르기" id="trip:callback" busy={busy} onClick={() => fire('부르기', 'call-back')} />
        </Line>
      </Group>

      <Group title="2층 · 3층(조각)">
        <Line name="2층 굽기" value={str(s, 'layer2Status')} note={`열린 동작 ${(str(s, 'unlocked') ?? '').split(',').filter(Boolean).length}`}>
          {(['PENDING', 'READY', 'FAILED'] as const).map((k) => (
            <Btn key={k} label={k} id={`layer2:${k}`} on={str(s, 'layer2Status') === k} busy={busy}
              onClick={() => patch(`2층 ${k}`, { layer2Status: k })} />
          ))}
          <Btn label="2층 전부 해금" id="unlock:all" busy={busy} onClick={() => fire('2층 전부 해금', 'unlock', 'all')} />
        </Line>
        <Line name="조각" value={bool(s, 'piecesEnabled') ? (pieceDone.join('·') || '빈 판') : '3층 전'}>
          {!bool(s, 'piecesEnabled')
            ? <Btn label="3층 열기" id="pieces:open" busy={busy} onClick={() => fire('3층 열기', 'pieces-open')} />
            : PIECES.map(([k, l]) => (
              <Btn key={k} label={l} id={`piece:${k}`} on={pieceDone.includes(k)} busy={busy}
                onClick={() => patch(`조각 ${l}`, { pieces: { [k]: !pieceDone.includes(k) } })} />
            ))}
        </Line>
      </Group>

      <AllFields s={s} />
    </div>
  );
}

/** 내부 칸 전부 — 숫자를 하나도 숨기지 않는다(접어 둔다). */
function AllFields({ s }: { s: DevStateMap }) {
  const [open, setOpen] = useState(false);
  return (
    <div style={{ borderTop: `1px dashed ${C.lineHard}`, paddingTop: gap.sm }}>
      <button type="button" data-remote="all-fields" onClick={() => setOpen((x) => !x)}
        style={{ ...btnBase, border: 'none', background: 'none', padding: 0, color: C.sub, justifyContent: 'flex-start' }}>
        {open ? '▾' : '▸'} 서버 칸 전부({Object.keys(s).length})
      </button>
      {open && (
        <pre style={{ margin: 0, maxWidth: '100%', whiteSpace: 'pre-wrap', wordBreak: 'break-all', font: `${monoSize.xs}px ${MONO}`, color: C.sub }}>
          {Object.entries(s).map(([k, v]) => `${k}: ${typeof v === 'object' && v !== null ? JSON.stringify(v) : String(v)}`).join('\n')}
        </pre>
      )}
    </div>
  );
}

// ── 이벤트 ───────────────────────────────────────────────────────────────

/** 전수표 2장 순서. [이름, 이벤트, 인자?, 판 다시 받기?] */
const EVENT_GROUPS: ReadonlyArray<{ title: string; items: ReadonlyArray<readonly [string, string, (string | number)?, boolean?]> }> = [
  { title: '부화 · 튜토리얼', items: [
    ['부화 강제 성공', 'hatch-complete'], ['부화 실패', 'hatch-fail'], ['튜토리얼 다음 칸', 'tutorial-next'],
    ['튜토리얼 건너뛰기', 'tutorial-skip'], ['튜토리얼 처음부터', 'tutorial-restart'], ['시계 켜기(완주)', 'clock-start'],
    ['졸업 창 봄', 'graduation'], ['졸업 창 다시', 'graduation-reset'],
  ] },
  { title: '돌봄 · 병', items: [
    ['케어 미스 +1', 'care-miss'], ['발병 방치', 'sick', 'NEGLECT'], ['발병 더러움', 'sick', 'DIRTY'],
    ['발병 배탈', 'sick', 'UPSET'], ['발병 자연', 'sick', 'NATURAL'], ['약(회복)', 'recover'], ['오늘 한도 리셋', 'today-reset'],
  ] },
  { title: '잠', items: [
    ['재우기(밤잠)', 'sleep'], ['낮잠', 'nap'], ['23:00 밤 정산', 'night-settle'], ['깨우기', 'wake'], ['10:00 자동 기상', 'wake-auto'],
  ] },
  { title: '떠남 · 여행', items: [
    ['떠남 예고', 'leave-notice'], ['방문(예고 취소)', 'visit'], ['여행 출발', 'trip-start'], ['엽서 +1', 'postcard'],
    ['부르기', 'call-back'], ['하루 넘기기(방문)', 'day-plus'],
  ] },
  { title: '장면 · 채팅', items: [
    ['혼자 논 장면 1', 'scene', 1], ['장면 3', 'scene', 3], ['밤 장면', 'night-scene'],
    ['채팅 창 열기', 'chat-open', undefined, true], ['채팅 닫기', 'chat-close', undefined, true],
    ['채팅 만료', 'chat-expire', undefined, true], ['채팅 이탈', 'chat-abandon', undefined, true],
  ] },
  { title: '게임 · 해금', items: [
    ['게임 승', 'game-win', undefined, true], ['게임 패', 'game-lose', undefined, true], ['2층 전부 해금', 'unlock', 'all'],
  ] },
  { title: '조각 · 선물(101 구르기 · 102 뒤로 넘어짐)', items: [
    ['3층 열기', 'pieces-open'], ['조각 밥', 'piece-add', 'FOOD'], ['조각 놀이', 'piece-add', 'PLAY'],
    ['조각 청결', 'piece-add', 'CLEAN'], ['조각 교감', 'piece-add', 'BOND'], ['조각 완주(굽기)', 'piece-complete'],
    ['101 검수 대기', 'gift-review', 101], ['101 열기', 'gift-open', 101], ['101 도착', 'gift-arrive', 101], ['101 열람', 'gift-view', 101],
    ['102 열기', 'gift-open', 102], ['102 도착', 'gift-arrive', 102],
  ] },
  { title: '생애', items: [['놓아주기', 'release'], ['되살리기', 'revive']] },
];

function EventsTab({ busy, fire }: { busy: string | null; fire: Fire }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
      <p style={{ margin: 0, fontSize: fz.xs, color: C.faint, lineHeight: 1.45 }}>
        운영이 그 일을 일으키는 메서드를 그대로 부릅니다(시각·조건 무시). 결과는 위 토스트에.
      </p>
      {EVENT_GROUPS.map((g) => (
        <Group key={g.title} title={g.title}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.xs }}>
            {g.items.map(([label, kind, arg, reload]) => (
              <Btn key={label} label={label} id={`event:${kind}${arg != null ? `:${arg}` : ''}`} busy={busy}
                danger={kind === 'release'}
                onClick={() => {
                  if (kind === 'release' && !window.confirm('놓아주면 이 아이가 떠납니다(되살리기로 돌릴 수 있음). 할까요?')) return;
                  fire(label, kind, arg, reload);
                }} />
            ))}
          </div>
        </Group>
      ))}
    </div>
  );
}

// ── 시계 ─────────────────────────────────────────────────────────────────

function ClockTab({ petId, pet, busy, run, onDetail, toast, setBusy }: {
  petId: number; pet: PetDetail | null; busy: string | null;
  run: (label: string, call: () => Promise<DevResult>) => Promise<void>;
  onDetail: (p: PetDetail) => void; toast: (t: Omit<Toast, 'id'>) => void; setBusy: (b: string | null) => void;
}) {
  // ★ 기본은 방문(예전과 같음). 끄면 하루씩 밀어도 결석 떠남이 난다.
  const [noVisit, setNoVisit] = useState(false);
  const base = pet?.serverNow ? Date.parse(pet.serverNow) : Date.now();
  const clock = pet?.serverNow
    ? new Date(pet.serverNow).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short' })
    : '—';
  const jump = (label: string, minutes: number) => run(label, () => advanceClock(petId, minutes, !noVisit));
  const at = (hhmm: string) => run(`${hhmm}로`, () => setClockAt(petId, new Date(nextLocalAt(base, hhmm)).toISOString(), !noVisit));
  /** 예전 두 주소(force-open·night-sweep)는 화면 상태(Detail)로 답한다 — effects 가 없다. */
  const legacy = async (label: string, call: () => Promise<PetDetail>) => {
    if (busy) return;
    setBusy(label);
    try { onDetail(await call()); toast({ title: label, lines: ['완료 — 바뀐 칸 목록은 이 주소가 안 줍니다'] }); }
    catch (e) { toast({ title: `${label} — 실패`, lines: [errorText(e)], bad: true }); }
    finally { setBusy(null); }
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
      <div data-remote-clock style={{ fontSize: fz.lg, color: C.ink, fontFamily: MONO }}>{clock}</div>
      <label style={{ display: 'flex', alignItems: 'center', gap: gap.sm, minHeight: TAP_MIN, fontSize: fz.sm, color: C.sub }}>
        <input type="checkbox" data-remote="no-visit" checked={noVisit} onChange={(e) => setNoVisit(e.target.checked)}
          style={{ width: 22, height: 22 }} />
        방문 없이 넘기기 <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>(결석 떠남 재현 — 켜면 함께한 날·예고 취소가 안 일어남)</span>
      </label>
      <Line name="앞으로">
        {([['+10분', 10], ['+1시간', 60], ['+6시간', 360], ['+1일', 1440], ['+3일', 4320], ['+7일', 10080]] as const).map(([l, m]) => (
          <Btn key={l} label={l} id={`clock:${l}`} busy={busy} onClick={() => jump(l, m)} />
        ))}
      </Line>
      <Line name="다음 그 시각으로" note="낮 14:00 · 밤 창 19:00 · 자동 취침 23:00">
        {['07:00', '10:00', '14:00', '19:00', '23:00', '23:30'].map((h) => (
          <Btn key={h} label={h} id={`clock:${h}`} busy={busy} onClick={() => at(h)} />
        ))}
      </Line>
      <Line name="밤 굽기">
        <Btn label="밤 큐 돌리기" id="clock:night-sweep" busy={busy} onClick={() => legacy('밤 큐 돌리기', () => nightSweep(petId))} />
        <Btn label="선물 강제 열기(가짜)" id="clock:force-open" busy={busy} onClick={() => legacy('선물 강제 열기', () => forceOpen(petId, 101))} />
      </Line>
    </div>
  );
}

// ── 굽기 ─────────────────────────────────────────────────────────────────

const KINDS: ReadonlyArray<readonly [string, 'hatch' | 'layer2' | 'gift', number?]> = [
  ['부화(다시)', 'hatch'], ['2층 8종', 'layer2'], ['선물 101 구르기', 'gift', 101], ['선물 102 뒤로 넘어짐', 'gift', 102],
];

function BakeTab({ petId, ping, busy, onDetail, setState, toast, setBusy }: {
  petId: number; ping: DevPing; busy: string | null; onDetail: (p: PetDetail) => void;
  setState: (s: DevStateMap) => void; toast: (t: Omit<Toast, 'id'>) => void; setBusy: (b: string | null) => void;
}) {
  const [pick, setPick] = useState(2);
  const [real, setReal] = useState(false);
  const [job, setJob] = useState<{ id: string; cost: number; note: string } | null>(null);
  const [prog, setProg] = useState<DevBakeProgress | null>(null);
  const [refused, setRefused] = useState<string | null>(null);

  // 진행 — 2초마다. 끝나면 멈춘다.
  useEffect(() => {
    if (!job) return;
    let alive = true;
    const tick = async () => {
      try {
        const p = await devBakeProgress(petId, job.id);
        if (!alive) return;
        setProg(p);
        if (!p.finished) window.setTimeout(tick, 2000);
      } catch { if (alive) window.setTimeout(tick, 4000); }
    };
    void tick();
    return () => { alive = false; };
  }, [job, petId]);

  const start = async () => {
    const [label, kind, seq] = KINDS[pick];
    if (busy) return;
    if (real && !window.confirm(`진짜로 굽습니다 — 돈이 나갑니다(${kind === 'hatch' ? '약 $0.19 + 2층 약 $0.09' : '약 $0.09'}). 할까요?`)) return;
    setBusy(`굽기 ${label}`); setRefused(null); setProg(null);
    try {
      const r = await devBake(petId, kind, real, seq);
      onDetail(r.result.after); setState(r.result.state);
      setJob({ id: r.jobId, cost: r.estimatedCostUsd, note: r.note });
      toast({ title: `굽기 ${label}`, lines: [r.note, ...r.result.effects] });
    } catch (e) {
      const text = errorText(e);
      if (text.includes('과금 스위치 꺼짐')) setRefused('과금 스위치 꺼짐 — 이 서버는 ZZAL_GENERATION_REAL=false 입니다(켜는 것은 상훈님 직접)');
      toast({ title: `굽기 ${label} — 거절`, lines: [text], bad: true });
    } finally { setBusy(null); }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
      <Line name="무엇을">
        {KINDS.map(([l], i) => <Btn key={l} label={l} id={`bake:${i}`} on={pick === i} busy={busy} onClick={() => setPick(i)} />)}
      </Line>
      <Line name="어떻게" note={`서버 스위치: ${ping.generationReal ? '진짜(과금)' : '가짜(돈 0)'}`}>
        <Btn label="가짜" id="bake:fake" on={!real} busy={busy} onClick={() => setReal(false)} />
        <Btn label="진짜(과금)" id="bake:real" on={real} danger busy={busy} onClick={() => setReal(true)} />
      </Line>
      {refused && <div data-remote-refused style={{ padding: '8px 10px', borderRadius: radius.sm, background: C.accentSoft, color: C.accent, fontSize: fz.sm }}>{refused}</div>}
      <div>
        <Btn label="굽기 시작" id="bake:start" busy={busy} onClick={() => void start()} />
      </div>
      {job && (
        <div data-remote-bake style={{ font: `${monoSize.sm}px ${MONO}`, color: C.sub, lineHeight: 1.6 }}>
          {job.id} · 예상 ${job.cost}<br />
          {prog ? `${prog.status}${prog.total ? ` ${prog.done}/${prog.total}` : ''} · 기록된 비용 $${prog.costUsd}${prog.finished ? ' · 끝' : ' · 굽는 중…'}` : '진행 받는 중…'}
        </div>
      )}
    </div>
  );
}

// ── 토스트 ───────────────────────────────────────────────────────────────

function Toasts({ list, onClose }: { list: Toast[]; onClose: (id: number) => void }) {
  if (list.length === 0) return null;
  return (
    <div data-remote-toasts aria-live="polite" style={{
      position: 'fixed', left: 10, right: 10, top: 'max(10px, env(safe-area-inset-top))', zIndex: 1000, // 사이트 머리줄 위로(아래에 깔려 잘렸다)
      display: 'flex', flexDirection: 'column', gap: gap.xs, alignItems: 'center', pointerEvents: 'none',
    }}>
      {list.map((t) => (
        <button key={t.id} type="button" data-remote-toast onClick={() => onClose(t.id)} style={{
          pointerEvents: 'auto', width: 'min(414px, 100%)', textAlign: 'left', padding: '9px 12px', borderRadius: radius.md,
          border: `1px solid ${t.bad ? C.accent : C.ink}`, background: t.bad ? C.accentSoft : 'rgba(74,64,56,.94)',
          color: t.bad ? C.accent : '#FBF6EC', boxShadow: '0 6px 18px rgba(74,64,56,.25)',
        }}>
          <div style={{ fontSize: fz.sm, fontWeight: 700, marginBottom: 2 }}>{t.title}</div>
          {/* ★ 줄을 자르지 않는다 — 부르기처럼 열 줄 넘게 바뀌면 중요한 줄(여행: … → 없음)이 뒤에 온다(검증에서 잘려 안 보였다).
              대신 높이를 화면의 16% 로 묶고 안에서 스크롤한다 — 리모컨 탭 줄을 덮지 않게(320×568 에서 잰 값). */}
          <div style={{ maxHeight: 'min(16dvh, 180px)', overflowY: 'auto' }}>
            {t.lines.map((l, i) => (
              <div key={i} style={{ font: `${monoSize.sm}px ${MONO}`, lineHeight: 1.45, wordBreak: 'break-all' }}>{l}</div>
            ))}
          </div>
        </button>
      ))}
    </div>
  );
}
