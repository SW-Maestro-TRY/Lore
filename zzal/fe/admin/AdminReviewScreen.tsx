'use client';

// 관리자 검수 화면 — 구워진 움짤을 보고 "좋음 / 다시 굽기" 를 남긴다.
//
// 일부러 최소 형태다. 목록·움짤·버튼 둘이 전부다. 판정 세션에서는 상훈님 시간이 병목이라
// 화면에 볼 것이 늘어날수록 한 건당 시간이 늘고, 그게 곧 게이트를 강화할 표본 수를 깎는다.
//
// ★ 실험 판정 도구(맥미니 /judge)와는 아무 관계가 없다. 모양이 비슷해 보여도 운영과
//   실험은 무조건 따로 간다(2026-09-03 지시) — 한쪽 기준을 고칠 때 다른 쪽이 조용히
//   따라 바뀌는 것을 막는 건 결국 이름과 자리다.

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@common/api/client';
import { assetUrl } from '@zzal/lib/assets';
import { fetchPending, submitVerdict, type HumanVerdict, type PendingMotion } from './api';

/** 화면이 처한 상태. 빈 목록과 "못 불러옴" 을 같은 화면으로 그리면 안 된다. */
type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: PendingMotion[] }
  | { kind: 'error'; message: string };

/**
 * 실패를 사람 말로 바꾼다.
 *
 * ★ 404 를 따로 잡는 이유 — 서버 스위치(ZZAL_ADMIN)가 꺼져 있으면 주소 자체가 없어서
 *   404 가 온다. 이걸 "검수할 게 없어요" 로 삼키면 서버가 꺼진 것을 영영 모른다.
 */
function explain(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.code === 'ADMIN_ONLY') return '관리자 계정이 아닙니다.';
    if (e.isUnauthorized) return '로그인이 필요합니다.';
    if (e.status === 404) return '관리자 API 가 꺼져 있습니다 (ZZAL_ADMIN).';
    return e.message;
  }
  return '요청을 처리하지 못했습니다.';
}

export default function AdminReviewScreen() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  /** 모션 번호별 메모. 판정을 누를 때 함께 보낸다. */
  const [notes, setNotes] = useState<Record<number, string>>({});
  /** 지금 보내는 중인 모션. 두 번 눌리는 것을 막는다. */
  const [sending, setSending] = useState<number | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  const reload = useCallback((signal?: AbortSignal) => {
    setLoad({ kind: 'loading' });
    fetchPending(signal)
      .then((items) => setLoad({ kind: 'ready', items }))
      .catch((e) => {
        if (signal?.aborted) return;
        setLoad({ kind: 'error', message: explain(e) });
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    reload(ac.signal);
    return () => ac.abort();
  }, [reload]);

  async function decide(motion: PendingMotion, verdict: HumanVerdict) {
    setSending(motion.motionId);
    setFailed(null);
    try {
      await submitVerdict(motion.motionId, verdict, notes[motion.motionId]);
      // 판정한 것은 목록에서 뺀다. 다시 불러오지 않는 이유는, 판정 중에 새 움짤이
      // 끼어들어 목록 순서가 흔들리면 보던 자리를 잃기 때문이다.
      setLoad((prev) =>
        prev.kind === 'ready'
          ? { kind: 'ready', items: prev.items.filter((m) => m.motionId !== motion.motionId) }
          : prev,
      );
    } catch (e) {
      setFailed(explain(e));
    } finally {
      setSending(null);
    }
  }

  return (
    <div style={{ maxWidth: 720, margin: '0 auto', padding: '8px 0 64px' }}>
      <h1 style={{ fontSize: 20, fontWeight: 800, margin: '0 0 4px' }}>움짤 검수</h1>

      {/* ★★ 이 문장을 빼지 말 것. 모션은 검수 전에 이미 사용자에게 열려 있어서
          (밤에 잠든 사용자가 아침에 갇히지 않도록) 여기서 무엇을 눌러도 사용자가 보는
          그림은 그대로다. 안 적으면 누르고 나서 바뀐 줄 아시게 된다. */}
      <p style={{ ...NOTICE }}>
        판정만 기록됩니다. 사용자 화면은 바뀌지 않습니다 — 다시 굽기는 별도 작업입니다.
      </p>

      {failed && <p style={{ ...NOTICE, ...NOTICE_BAD }}>{failed}</p>}

      {load.kind === 'loading' && <p style={DIM}>불러오는 중…</p>}

      {load.kind === 'error' && (
        <div>
          <p style={{ ...NOTICE, ...NOTICE_BAD }}>{load.message}</p>
          <button type="button" style={BTN} onClick={() => reload()}>
            다시 불러오기
          </button>
        </div>
      )}

      {load.kind === 'ready' && load.items.length === 0 && (
        <p style={DIM}>검수할 움짤이 없습니다.</p>
      )}

      {load.kind === 'ready' &&
        load.items.map((m) => (
          <section key={m.motionId} style={CARD}>
            {/* 움짤. next/image 를 안 쓰는 이유 — 애니메이션 webp 는 최적화를 거치면
                첫 프레임만 남는다. 검수는 움직임을 보는 일이라 그러면 목적을 잃는다. */}
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={assetUrl(m.imageKey)}
              alt={m.name}
              style={{ width: '100%', maxWidth: 360, display: 'block', margin: '0 auto', imageRendering: 'auto' }}
            />

            <div style={{ marginTop: 10, fontSize: 14, fontWeight: 700 }}>
              #{m.motionId} · {m.name}
            </div>
            <div style={{ ...DIM, fontSize: 12, marginTop: 2 }}>
              게이트 {m.gateVerdict ?? '—'}
              {m.gateVersion ? ` (${m.gateVersion})` : ''} · {m.attempts}번째 굽기
              {m.gateNote ? ` · ${m.gateNote}` : ''}
            </div>

            <input
              type="text"
              value={notes[m.motionId] ?? ''}
              onChange={(e) => setNotes((prev) => ({ ...prev, [m.motionId]: e.target.value }))}
              placeholder="왜 그렇게 보셨는지 (선택)"
              maxLength={500}
              style={INPUT}
            />

            <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
              <button
                type="button"
                style={{ ...BTN, ...BTN_OK }}
                disabled={sending === m.motionId}
                onClick={() => decide(m, 'OK')}
              >
                좋음
              </button>
              <button
                type="button"
                style={{ ...BTN, ...BTN_BAD }}
                disabled={sending === m.motionId}
                onClick={() => decide(m, 'REGENERATE')}
              >
                다시 구워야 함
              </button>
            </div>
          </section>
        ))}
    </div>
  );
}

// ── 생김새. 검수용이라 꾸미지 않는다 ────────────────────────────────────────

const DIM: React.CSSProperties = { color: 'var(--muted, #7a7a7a)', fontSize: 13 };

const NOTICE: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.5,
  margin: '0 0 16px',
  padding: '8px 10px',
  borderRadius: 6,
  background: 'rgba(127,127,127,0.10)',
};

const NOTICE_BAD: React.CSSProperties = { background: 'rgba(220,80,60,0.14)' };

const CARD: React.CSSProperties = {
  border: '1px solid rgba(127,127,127,0.28)',
  borderRadius: 10,
  padding: 12,
  marginBottom: 16,
};

const INPUT: React.CSSProperties = {
  width: '100%',
  marginTop: 8,
  padding: '7px 9px',
  fontSize: 13,
  borderRadius: 6,
  border: '1px solid rgba(127,127,127,0.35)',
  background: 'transparent',
  color: 'inherit',
};

const BTN: React.CSSProperties = {
  padding: '8px 14px',
  fontSize: 13,
  fontWeight: 700,
  borderRadius: 6,
  border: '1px solid rgba(127,127,127,0.35)',
  background: 'transparent',
  color: 'inherit',
  cursor: 'pointer',
};

const BTN_OK: React.CSSProperties = { borderColor: 'rgba(60,150,90,0.7)' };
const BTN_BAD: React.CSSProperties = { borderColor: 'rgba(200,90,60,0.7)' };
