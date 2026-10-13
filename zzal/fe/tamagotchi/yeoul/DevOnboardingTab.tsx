// 개발 리모컨 「온보딩」 탭 — 랜딩부터 방까지 **다시 밟아 보기** 위한 손잡이(2026-10-13).
//
// ★ 상훈님 원문(10/13): "앞에 업로드나 온보딩 과정에서는 사용을 못하는 리모컨인거야?" → "그래 지금 해봐"
//   예전 리모컨은 서버 탭이 전부 "아이가 있어야" 동작해서, 온보딩 화면에서 열면 "아이가 아직 없어요" 한 줄뿐이었다.
//
// 여기 있는 것
//   1) 지금 화면 이름 — 판정할 때 "지금 어디를 보고 있나" 를 한 줄로
//   2) 화면 점프 — 랜딩·올리기·캐릭터·알·태어남·연습방(+ 진짜 방). 화면만 옮긴다(서버 안 바뀜)
//   3) 목 데이터 프리셋 세 벌(현대 학생·판타지 마법사·어두운 세상 모험가) — 캐릭터 칸을 한 번에(화면 상태만.
//      서버로는 「다음」(알에 넣기)을 눌러야 나간다). 표는 `devPresets.ts` 한 곳
//   3-1) 알 화면 시안 dots / glow / off — localStorage `zzal.devEgg`(새로고침에도 남음). 읽는 자리는 `devEgg.ts` 머리말
//   4) 굽기 단계 강제 — 1/N … N/N · 완료 · 실패(서버). 가짜 생성은 몇 초 만에 끝나 단계 표시를 볼 틈이 없어서다
//   5) 내 펫 지우고 처음부터(서버, 되돌릴 수 없음) — 한 번 더 눌러야 지운다
//
// ★ 서버 손잡이 둘은 dev 도구가 켜진 서버에만 있다(운영은 주소 자체가 없어 404). 리모컨도 ping 200 일 때만 뜬다.
'use client';

import { useEffect, useState } from 'react';
import { ApiError } from '../../lib/api';
import { devHatchStage, devResetMe } from '../../lib/dev';
import { useDevPing } from './DevRemote';
import type { HatchProgress } from '../../lib/pet';
import { STEPS } from './constants';
import { readDevEgg, writeDevEgg, onDevEggChange, type EggVariant } from './devEgg';
import { DEV_PRESETS, presetForCharScreen } from './devPresets';
import type { Live } from './useHatch';
import type { useYeoul } from './useYeoul';
import { C, MONO, TAP_MIN, chipTone, fz, gap, monoSize, radius } from './ui';

type Y = ReturnType<typeof useYeoul>;

/** 지금 화면 이름. 판정 기록에 그대로 옮겨 적을 수 있게 짧게. */
export function screenName(y: Y, live?: Live): string {
  const { s } = y;
  if (s.screen === 'egg') return live?.failed ? '알(실패)' : live?.ready ? '알(다 됐어요)' : '알(굽는 중)';
  if (s.screen === 'room') return s.sampleMode ? '연습방' : '진짜 방';
  const key = STEPS[s.step];
  return key === 'landing' ? '랜딩' : key === 'upload' ? '올리기' : key === 'char' ? '캐릭터 칸' : key === 'born' ? '태어남' : String(key);
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) return e.status === 404 ? '이 서버는 개발 도구가 꺼져 있거나 없는 아이예요(404)' : `${e.status} ${e.message}`;
  return e instanceof Error ? e.message : String(e);
}

export default function DevOnboardingTab({ y, live }: { y: Y; live: Live }) {
  const { s, actions } = y;
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ title: string; lines: string[]; bad?: boolean } | null>(null);
  const [armed, setArmed] = useState(false);
  const petId = live.petId;
  const total = live.total > 0 ? live.total : 4;
  const onb = s.screen === 'onb';
  // 알 화면 시안 — 첫 렌더는 null(서버 렌더와 갈리지 않게), 마운트 뒤 저장소에서 읽는다.
  const [egg, setEgg] = useState<EggVariant | null>(null);
  useEffect(() => { setEgg(readDevEgg()); return onDevEggChange(setEgg); }, []);

  const jumps: ReadonlyArray<readonly [string, boolean, () => void]> = [
    ['랜딩', onb && s.step === 0, () => actions.goStep(0)],
    ['올리기', onb && s.step === STEPS.indexOf('upload'), () => actions.goStep(STEPS.indexOf('upload'))],
    ['캐릭터', onb && s.step === STEPS.indexOf('char'), () => actions.goStep(STEPS.indexOf('char'))],
    ['알', s.screen === 'egg', actions.goEgg],
    ['태어남', onb && s.step === STEPS.indexOf('born'), () => actions.goStep(STEPS.indexOf('born'))],
    ['연습방', s.screen === 'room' && s.sampleMode, actions.enterSample],
    ...(live.ready ? [['진짜 방', s.screen === 'room' && !s.sampleMode, actions.enterRoom] as const] : []),
  ];

  /** 목 데이터 프리셋 — 캐릭터 칸 값을 통째로. 캐릭터 칸이 아니면 그리로 옮겨 준다(채운 것을 바로 보게). */
  const fill = (id: string) => {
    const p = DEV_PRESETS.find((x) => x.id === id);
    if (!p) return;
    actions.patch(presetForCharScreen(p));
    if (s.screen === 'onb' && s.step < STEPS.indexOf('char')) actions.goStep(STEPS.indexOf('char'));
    setMsg({ title: `프리셋 — ${p.label}`, lines: [`이름 ${p.name} · ${p.answers.world.text} · ${p.answers.job.text}`, '서버로는 캐릭터 칸의 「알에 넣기」 를 눌러야 나갑니다'] });
  };

  const stage = async (st: number | 'done' | 'failed') => {
    if (busy || petId == null) return;
    const label = st === 'done' ? '굽기 완료 강제' : st === 'failed' ? '굽기 실패 강제' : `굽기 ${st}/${total} 강제`;
    setBusy(label);
    try {
      // ★ 굽는 중(409 "굽는 중")이면 끝나길 기다렸다가 다시 누른다 — 가짜 굽기도 10초 남짓 걸린다(로컬 실측).
      //   기다리지 않으면 이름을 낸 직후의 「완료」·「2/4」 가 매번 거절돼 사람이 몇 번이고 다시 눌러야 한다.
      const until = Date.now() + 90_000;
      let r = null as Awaited<ReturnType<typeof devHatchStage>> | null;
      while (!r) {
        try {
          r = await devHatchStage(petId, st);
        } catch (e) {
          const baking = e instanceof ApiError && e.status === 409 && /굽는 중/.test(e.message);
          if (!baking || Date.now() > until) throw e;
          setMsg({ title: label, lines: ['굽기가 끝나길 기다리는 중… (끝나면 바로 적용)'] });
          await new Promise((res) => window.setTimeout(res, 2000));
        }
      }
      live.putHatch(r.hatch as HatchProgress);
      // 숫자 단계·실패는 알 화면에서 보는 것이다 — 다른 화면에 있으면 알로 옮긴다.
      if ((typeof st === 'number' || st === 'failed') && s.screen !== 'egg') actions.goEgg();
      setMsg({ title: label, lines: r.effects.length ? r.effects : ['바뀐 것 없음'] });
    } catch (e) {
      setMsg({ title: `${label} — 실패`, lines: [errorText(e)], bad: true });
    } finally {
      setBusy(null);
    }
  };

  const reset = async () => {
    if (busy) return;
    if (!armed) { setArmed(true); return; }
    setArmed(false);
    setBusy('reset');
    try {
      const r = await devResetMe();
      // 손에 든 아이를 버리고 첫 칸으로. 로그인은 그대로라 「올리기」 에서 가입 창을 다시 묻지 않게 문을 열어 둔다.
      live.reset();
      actions.leaveAccount();
      actions.passAuth('session');
      actions.goStep(0);
      const rows = Object.entries(r.deleted).filter(([, n]) => n > 0).map(([t, n]) => `${t} ${n}`);
      setMsg({ title: '내 펫 지움 — 처음부터', lines: [r.petIds.length ? `펫 #${r.petIds.join(', #')}` : '지울 펫이 없었어요', ...(rows.length ? [rows.join(' · ')] : [])] });
    } catch (e) {
      setMsg({ title: '내 펫 지우기 — 실패', lines: [errorText(e)], bad: true });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div data-part="remote-onb" style={{ display: 'flex', flexDirection: 'column', gap: gap.md }}>
      <Row label="지금 화면">
        <span data-remote-screen style={{ fontSize: fz.md, fontWeight: 700, color: C.ink }}>{screenName(y, live)}</span>
        <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>
          {petId != null ? `펫 #${petId} · ${live.progress}/${live.total || '?'}` : '펫 없음'}
        </span>
      </Row>

      <Row label="화면 점프" note="화면만">
        {jumps.map(([label, on, pick]) => <Chip key={label} id={`onb-jump:${label}`} label={label} on={on} disabled={!!busy} onClick={pick} />)}
      </Row>

      <Row label="목 데이터" note="캐릭터 칸 채우기 · 화면만">
        {DEV_PRESETS.map((p) => (
          <Chip key={p.id} id={`onb-preset:${p.id}`} label={p.label} on={onb && s.petName === p.name} disabled={!!busy} onClick={() => fill(p.id)} />
        ))}
      </Row>

      <Row label="알 화면 시안" note="새로고침에도 남음">
        {(['dots', 'glow', 'off'] as const).map((v) => (
          <Chip key={v} id={`onb-egg:${v}`} label={v} on={egg === v} disabled={!!busy} onClick={() => writeDevEgg(v)} />
        ))}
        <Chip id="onb-egg:url" label="주소대로" on={egg === null} disabled={!!busy} onClick={() => writeDevEgg(null)} />
      </Row>

      <Row label="굽기 단계" note={petId == null ? '그림을 올린 뒤(펫이 생긴 뒤)' : '서버 · 돈 0'}>
        {Array.from({ length: total }, (_, i) => i + 1).map((n) => (
          <Chip key={n} id={`onb-stage:${n}`} label={`${n}/${total}`} on={live.progress === n && !live.ready && !live.failed}
            disabled={!!busy || petId == null} onClick={() => void stage(n)} />
        ))}
        <Chip id="onb-stage:done" label="완료" on={petId != null && live.ready} disabled={!!busy || petId == null} onClick={() => void stage('done')} />
        <Chip id="onb-stage:failed" label="실패" on={petId != null && live.failed} disabled={!!busy || petId == null} onClick={() => void stage('failed')} danger />
      </Row>

      <Row label="처음부터" note="서버 · 되돌릴 수 없음">
        <Chip id="onb-reset" label={armed ? '정말 지울까요? 한 번 더' : '내 펫 지우고 처음부터'} on={armed}
          disabled={!!busy} onClick={() => void reset()} danger />
        {armed && <Chip id="onb-reset-cancel" label="취소" on={false} disabled={!!busy} onClick={() => setArmed(false)} />}
      </Row>

      {msg && (
        <div data-part="remote-onb-msg" role="status" style={{
          padding: '8px 10px', borderRadius: radius.sm, background: msg.bad ? C.accentSoft : 'rgba(0,0,0,.04)',
          display: 'flex', flexDirection: 'column', gap: 2,
        }}>
          <span style={{ fontSize: fz.sm, fontWeight: 700, color: msg.bad ? C.accent : C.ink }}>{msg.title}</span>
          {msg.lines.map((l, i) => <span key={i} style={{ font: `${monoSize.xs}px ${MONO}`, color: C.sub, wordBreak: 'break-all' }}>{l}</span>)}
        </div>
      )}
    </div>
  );
}

function Row({ label, note, children }: { label: string; note?: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: gap.xs }}>
      <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
        <span style={{ fontSize: fz.sm, color: C.sub }}>{label}</span>
        {note && <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>{note}</span>}
      </span>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: gap.xs }}>{children}</div>
    </div>
  );
}

function Chip({ label, on, onClick, disabled, id, danger }: {
  label: string; on: boolean; onClick: () => void; disabled?: boolean; id: string; danger?: boolean;
}) {
  const t = chipTone(on);
  return (
    <button type="button" data-remote={id} data-on={on ? '1' : '0'} disabled={disabled} onClick={onClick}
      style={{
        minHeight: TAP_MIN, minWidth: TAP_MIN, padding: '0 12px', borderRadius: radius.pill, fontSize: fz.xs,
        border: `1px solid ${danger ? C.accent : t.bd}`, background: t.bg, color: danger && !on ? C.accent : t.fg,
        opacity: disabled ? 0.55 : 1,
      }}>{label}</button>
  );
}

/**
 * 캐릭터 칸 맨 위의 **목 데이터 줄** — dev 도구가 켜진 서버에서만(ping 200) 보인다. 운영은 ping 이 404 라 안 그린다.
 * 리모컨을 안 열고도 같은 세 벌을 바로 누를 수 있게(상훈님 10/13 "기존 온보딩에도").
 */
export function DevPresetBar({ y }: { y: Y }) {
  const ping = useDevPing('preset-bar');
  if (!ping) return null;
  const { s, actions } = y;
  return (
    <div data-part="dev-presets" style={{
      display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: gap.xs, padding: '6px 8px',
      borderRadius: radius.sm, border: `1px dashed ${C.line}`,
    }}>
      <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint }}>dev 목 데이터</span>
      {DEV_PRESETS.map((p) => (
        <Chip key={p.id} id={`char-preset:${p.id}`} label={p.label} on={s.petName === p.name}
          onClick={() => actions.patch(presetForCharScreen(p))} />
      ))}
    </div>
  );
}
