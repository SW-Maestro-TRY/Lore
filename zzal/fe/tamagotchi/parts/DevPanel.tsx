// 시계 스킵 패널 — 상훈님이 dev 에서 하루를 빨리 지나가 보시려고 쓰는 도구.
//
// ★ **주소에 `?dev=1` 이 있을 때만** 그린다. 기본은 없는 물건이다.
// ★ 운영에서 눌러도 아무 일이 안 난다 — dev 주소는 `ZZAL_DEV_TOOLS=true` 인 서버에만 있다.
//   화면 플래그는 편의고, 자물쇠는 서버다.
// ★ 목 모드에서는 같은 버튼이 목 시계를 민다. 규칙을 두 벌 만들지 않으려고 **버튼은 하나**다.
'use client';

import { useState } from 'react';
import { ApiError } from '../../lib/api';
import { advanceClock, forceOpen, nightSweep, setLocalTime } from '../../lib/dev';
import type { PetDetail } from '../../lib/pet';
import { EDGE, GAEGU, INK, PAPER, SUB } from './theme';

export interface DevPanelProps {
  /** 시계를 밀 펫. 없으면(아이 없음) 아무것도 안 그린다. */
  petId: number | null;
  /** 목 모드인가. 그러면 서버 대신 목 시계를 민다. */
  mock: boolean;
  /** 응답으로 온 최신 상태를 화면에 얹는다(행동 응답 = 상태). */
  onPet: (next: PetDetail) => void;
}

/** 첫 선물(구르기)의 seq. 아침 도착 화면을 dev 에서 볼 유일한 길이다. */
const GIFT_SEQ = 101;

type Jump = { label: string; minutes?: number; at?: string; act?: 'force-open' | 'night-sweep' };

/** 하루를 건너뛸 자리들. 시각 이동은 **앞으로만** 간다(서버가 뒤로는 거절한다). */
const JUMPS: Jump[] = [
  { label: '+10분', minutes: 10 },
  { label: '+1시간', minutes: 60 },
  { label: '+3시간', minutes: 180 },
  { label: '19:00으로', at: '19:00' },
  { label: '23:30으로', at: '23:30' },
  { label: '07:00으로', at: '07:00' },
  { label: '10:30으로', at: '10:30' },
  { label: '+1일', minutes: 24 * 60 },
  // ★ dev 서버는 밤 굽기가 꺼져 있다(`night.sweep-enabled: false`). 아침 도착 화면을 볼 길이 이 둘뿐이다.
  //   force-open 은 **가짜 검수 통과**만 시킬 뿐, 도착 자체는 규칙대로다 — 깨어 있는 첫 조회에 온다.
  //   그래서 자는 아이에게 누르면 아무것도 안 뜬다(고장이 아니라 규칙이다).
  { label: '선물 강제 도착', act: 'force-open' },
  { label: '밤 큐 돌리기', act: 'night-sweep' },
];

/** 목 시계에서 "다음 번 그 시각"까지 몇 분인가. 이미 지났으면 내일 그 시각이다. */
function minutesToLocal(nowIso: string, at: string): number {
  const [hh, mm] = at.split(':').map(Number);
  const now = new Date(nowIso).getTime();
  const kst = now + 9 * 3_600_000;
  const dayStart = Math.floor(kst / 86_400_000) * 86_400_000 - 9 * 3_600_000;
  let target = dayStart + (hh * 60 + mm) * 60_000;
  if (target <= now) target += 86_400_000;
  return Math.round((target - now) / 60_000);
}

export default function DevPanel({ petId, mock, onPet }: DevPanelProps) {
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  if (petId == null) return null;

  const jump = async (j: Jump) => {
    if (busy) return;
    setBusy(true);
    setNote(null);
    try {
      if (mock) {
        const handle = (window as unknown as {
          __zzalMock?: { advance: (ms: number) => void; now: () => string; forceOpen: (seq: number) => void; nightSweep: () => void };
        }).__zzalMock;
        if (!handle) { setNote('목 서버를 찾지 못했어요'); return; }
        if (j.act === 'force-open') handle.forceOpen(GIFT_SEQ);
        else if (j.act === 'night-sweep') handle.nightSweep();
        else handle.advance((j.minutes ?? minutesToLocal(handle.now(), j.at as string)) * 60_000);
        return;
      }
      const next = j.act === 'force-open' ? await forceOpen(petId, GIFT_SEQ)
        : j.act === 'night-sweep' ? await nightSweep(petId)
          : j.minutes != null ? await advanceClock(petId, j.minutes)
            : await setLocalTime(petId, j.at as string);
      onPet(next);
    } catch (e) {
      // ★ dev 도구가 꺼진 서버에서는 그 주소가 아예 없다(404) 또는 막힌다(403).
      //   그건 고장이 아니라 **그렇게 만들어 둔 것**이라, 그 말을 그대로 한다.
      const status = e instanceof ApiError ? e.status : 0;
      if (status === 404 || status === 403) setNote('이 서버는 개발 도구가 꺼져 있어요');
      else setNote(e instanceof ApiError && e.message ? e.message : '시계를 옮기지 못했어요');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      data-part="devpanel"
      style={{
        position: 'fixed', right: 8, bottom: 8, zIndex: 90, maxWidth: 240,
        display: 'flex', flexDirection: 'column', gap: 5,
        padding: '8px 9px', background: PAPER, border: '1px solid ' + EDGE, borderRadius: 3,
        boxShadow: '2px 3px 0 rgba(58,53,43,.16)', opacity: 0.95,
      }}
    >
      <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 11, color: SUB }}>시계 건너뛰기 (개발용)</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {JUMPS.map((j) => (
          <button
            key={j.label}
            data-dev-jump={j.label}
            disabled={busy}
            onClick={() => void jump(j)}
            style={{
              border: '1px solid ' + EDGE, borderRadius: 2, padding: '3px 6px', background: '#FFF9E4',
              color: INK, cursor: busy ? 'default' : 'pointer', fontFamily: GAEGU, fontWeight: 700, fontSize: 11,
            }}
          >
            {j.label}
          </button>
        ))}
      </div>
      {note && <span data-dev-note style={{ fontFamily: GAEGU, fontSize: 11, color: '#8C4B3B' }}>{note}</span>}
    </div>
  );
}
