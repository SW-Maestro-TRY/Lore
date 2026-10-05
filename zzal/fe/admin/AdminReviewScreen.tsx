'use client';

// 관리자 검수 화면 — 밤에 구운 움짤을 보고 "좋음 / 다시 굽기" 를 남긴다.
//
// 빠른 판정 도구다. 판정 세션에서는 상훈님 시간이 병목이라, 한 건에 드는 시간이 늘면 그만큼
// 게이트를 강화할 표본 수가 깎인다. 그래서 화면에 두는 것은 **판정에 실제로 쓰이는 것만**이다 —
// 크게 보이는 그림 하나, 눈으로 견줄 참고 그림 넷, 판 고르기, 버튼 둘.
//
// ★ 왜 한 장이 아니라 여럿을 보는가
//   한 모션에 판이 최대 일곱이고(서버 API 1 + 맥미니 3판 x 2라운드) 그중 하나가 공개된다.
//   무엇보다 **캐붕(캐릭터가 원본과 달라지는 것)은 완성본만 봐서는 안 보인다** — 사용자가 올린
//   원본 그림과 부화 때 만든 시트를 나란히 놓아야 드러난다. 격자(16프레임 원본)는 움짤로 합치기
//   전 단계라, 흔들림이 어느 칸에서 났는지를 여기서만 알 수 있다.
//
// ★ 실험 판정 도구(맥미니 /judge)와는 아무 관계가 없다. 모양이 비슷해 보여도 운영과
//   실험은 무조건 따로 간다(2026-09-03 지시) — 한쪽 기준을 고칠 때 다른 쪽이 조용히
//   따라 바뀌는 것을 막는 건 결국 이름과 자리다.

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@common/api/client';
import { assetUrl } from '@zzal/lib/assets';
import {
  PENDING_PATH,
  fetchPending,
  submitVerdict,
  type HumanVerdict,
  type MotionCandidate,
  type PendingMotion,
} from './api';

/** 화면이 처한 상태. 빈 목록과 "못 불러옴" 을 같은 화면으로 그리면 안 된다. */
type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: PendingMotion[] }
  | { kind: 'error'; message: string };

/** 크게 띄울 그림. 넷을 오가며 견준다. */
type Shot = 'motion' | 'grid' | 'source' | 'sheet';

const SHOT_LABEL: Record<Shot, string> = {
  motion: '완성본',
  grid: '격자',
  source: '원본',
  sheet: '시트',
};

/**
 * 실패를 사람 말로 바꾼다.
 *
 * ★★ 404 는 **두 가지 서로 다른 사실**이 같은 숫자로 온다.
 *    (1) 서버 스위치(ZZAL_ADMIN)가 꺼져 있으면 컨트롤러가 아예 안 올라와 주소가 없다
 *    (2) 없는 모션에 판정을 보내면 서버가 NOT_FOUND 로 막는다
 *    둘을 숫자만으로 가를 수 없으니 **단정하지 않는다** — 문구는 둘 다 가리키게 쓰고,
 *    실제 상태·주소·코드는 콘솔에 남겨 눌러 본 사람이 확인할 수 있게 한다.
 *    억지로 한쪽으로 단정하면, 틀렸을 때 엉뚱한 곳을 몇 시간씩 뒤지게 된다.
 *
 * ★ 권한(403 ADMIN_ONLY)은 404 와 전혀 다른 사실이다. 스위치는 켜져 있고 주소도 있는데
 *   내 계정이 관리자가 아닌 것이라, 할 일이 "서버를 켜기" 가 아니라 "계정을 확인하기" 다.
 */
function explain(e: unknown, where: string): string {
  if (e instanceof ApiError) {
    // 무엇이 왔는지 그대로 남긴다. 문구는 뭉뚱그려도 콘솔은 뭉뚱그리지 않는다.
    console.warn('[zzal/admin] 요청 실패', {
      where,
      status: e.status,
      code: e.code,
      message: e.message,
    });
    if (e.code === 'ADMIN_ONLY') return '관리자 계정이 아닙니다. 로그인한 계정을 확인해 주세요.';
    if (e.isUnauthorized) return '로그인이 필요합니다.';
    if (e.code === 'ZZAL_NOT_IN_REVIEW') {
      return '지금은 검수할 수 없는 상태입니다. 목록을 다시 불러와 주세요.';
    }
    if (e.status === 404) {
      return `관리자 기능이 꺼져 있거나 주소가 없습니다 (${where}). ZZAL_ADMIN 설정과 콘솔 로그를 확인해 주세요.`;
    }
    if (e.status >= 500) return `서버가 응답하지 못했습니다 (${e.status}). 잠시 뒤 다시 시도해 주세요.`;
    return e.message;
  }
  console.warn('[zzal/admin] 요청 실패', { where, error: e });
  return '요청을 처리하지 못했습니다.';
}

/**
 * 지금 고른 판.
 *
 * ★ 기본값을 **서버와 같은 규칙**으로 고른다(AdminService.chooseCandidate) — 대표로 올라와 있는
 *   판, 없으면 마지막 판. 화면이 다른 기본값을 쓰면 아무것도 안 고르고 누른 판정과
 *   고르고 누른 판정이 서로 다른 그림을 공개하게 된다.
 */
function defaultCandidate(m: PendingMotion): MotionCandidate | null {
  if (m.candidates.length === 0) return null;
  return (
    m.candidates.find((c) => c.imageKey === m.imageKey)
    ?? m.candidates.find((c) => c.chosen)
    ?? m.candidates[m.candidates.length - 1]
  );
}

/**
 * 그림 한 장.
 *
 * next/image 를 안 쓰는 이유 — 애니메이션 webp 는 최적화를 거치면 첫 프레임만 남는다.
 * 검수는 움직임을 보는 일이라 그러면 목적을 잃는다.
 */
function Picture({ src, alt, style }: { src: string; alt: string; style: React.CSSProperties }) {
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={assetUrl(src)} alt={alt} style={style} />;
}

export default function AdminReviewScreen() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  /** 모션 번호별 메모. 판정을 누를 때 함께 보낸다. */
  const [notes, setNotes] = useState<Record<number, string>>({});
  /** 모션 번호별로 고른 판. 안 건드리면 기본값(대표 판)을 쓴다. */
  const [picked, setPicked] = useState<Record<number, number>>({});
  /** 모션 번호별로 크게 띄운 그림. 기본은 완성본. */
  const [shot, setShot] = useState<Record<number, Shot>>({});
  /** 지금 보내는 중인 모션. 두 번 눌리는 것을 막는다. */
  const [sending, setSending] = useState<number | null>(null);
  const [failed, setFailed] = useState<string | null>(null);

  const reload = useCallback((signal?: AbortSignal) => {
    setLoad({ kind: 'loading' });
    fetchPending(signal)
      .then((items) => setLoad({ kind: 'ready', items: items ?? [] }))
      .catch((e) => {
        if (signal?.aborted) return;
        setLoad({ kind: 'error', message: explain(e, PENDING_PATH) });
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    reload(ac.signal);
    return () => ac.abort();
  }, [reload]);

  async function decide(motion: PendingMotion, verdict: HumanVerdict) {
    const chosen = currentCandidate(motion);
    setSending(motion.motionId);
    setFailed(null);
    try {
      await submitVerdict(
        motion.motionId,
        verdict,
        notes[motion.motionId],
        chosen ? chosen.candidateId : null,
      );
      // 판정한 것은 목록에서 뺀다. 다시 불러오지 않는 이유는, 판정 중에 새 움짤이
      // 끼어들어 목록 순서가 흔들리면 보던 자리를 잃기 때문이다.
      setLoad((prev) =>
        prev.kind === 'ready'
          ? { kind: 'ready', items: prev.items.filter((m) => m.motionId !== motion.motionId) }
          : prev,
      );
    } catch (e) {
      setFailed(explain(e, `verdict #${motion.motionId}`));
    } finally {
      setSending(null);
    }
  }

  /** 이 모션에서 지금 고른 판. 후보가 없으면 null — 그때는 번호 없이 판정한다(옛 행). */
  function currentCandidate(m: PendingMotion): MotionCandidate | null {
    const id = picked[m.motionId];
    if (id != null) {
      const hit = m.candidates.find((c) => c.candidateId === id);
      if (hit) return hit;
    }
    return defaultCandidate(m);
  }

  return (
    <div data-part="admin-review" style={PAGE}>
      <h1 style={{ fontSize: 20, fontWeight: 800, margin: '0 0 4px' }}>움짤 검수</h1>

      {/* ★★ 이 문장을 사실대로 유지할 것. `좋음` 은 기록이 아니라 **공개 결정**이다
          (AdminService.review 가 OK 를 받으면 그 자리에서 OPEN 으로 바꾼다). 다만 그 순간
          사용자 화면에 뜨지는 않고, 그 펫이 깨어 있는 첫 정산에 도착한다. "기록만 된다" 고
          적어 두면 되돌릴 수 없는 결정을 가벼운 마음으로 누르시게 된다. */}
      <p data-part="notice" style={NOTICE}>
        <b>좋음</b>을 누르면 그 판으로 <b>공개가 확정</b>됩니다. 사용자 화면에는 그 즉시가 아니라
        펫이 <b>깨어 있는 첫 정산</b>에 도착합니다. <b>다시 구워야 함</b>은 재생성 한도가 남아 있으면
        맥미니로 넘어가고, 다 썼으면 보류함에 들어갑니다.
      </p>

      {failed && <p data-part="submit-error" style={{ ...NOTICE, ...NOTICE_BAD }}>{failed}</p>}

      {load.kind === 'loading' && <p style={DIM}>불러오는 중…</p>}

      {load.kind === 'error' && (
        <div data-part="load-error">
          <p style={{ ...NOTICE, ...NOTICE_BAD }}>{load.message}</p>
          <button type="button" style={BTN} onClick={() => reload()}>
            다시 불러오기
          </button>
        </div>
      )}

      {load.kind === 'ready' && load.items.length === 0 && (
        <p data-part="empty" style={DIM}>검수할 움짤이 없습니다.</p>
      )}

      {load.kind === 'ready'
        && load.items.map((m) => {
          const chosen = currentCandidate(m);
          const view = shot[m.motionId] ?? 'motion';
          // 크게 볼 수 있는 것만 골라 둔다. 없는 그림은 탭도 안 만든다 —
          // 눌렀는데 아무것도 안 나오는 칸이 있으면 "고장인가" 를 매번 확인하게 된다.
          const shots: Array<[Shot, string]> = [
            ['motion', chosen?.imageKey ?? m.imageKey],
            ['grid', chosen?.gridKey ?? ''],
            ['source', m.sourceImageKey ?? ''],
            ['sheet', m.sheetImageKey ?? ''],
          ];
          const available = shots.filter(([, key]) => Boolean(key));
          const bigKey = shots.find(([k]) => k === view)?.[1] || (chosen?.imageKey ?? m.imageKey);

          return (
            <section
              key={m.motionId}
              data-part="motion-card"
              data-motion-id={m.motionId}
              data-candidate-id={chosen?.candidateId ?? ''}
              data-candidates={m.candidates.length}
              style={CARD}
            >
              <div style={{ fontSize: 15, fontWeight: 800 }}>
                #{m.motionId} · {m.label || m.key}
                {m.label && m.key ? <span style={{ ...DIM, fontWeight: 400 }}> ({m.key})</span> : null}
              </div>
              <div style={{ ...DIM, fontSize: 12, marginTop: 2 }}>
                게이트 {m.gateVerdict ?? '—'}
                {m.gateVersion ? ` (${m.gateVersion})` : ''} · {m.attempts}번째 굽기
                {m.regenRound > 0 ? ` · 재생성 ${m.regenRound}회` : ''}
                {m.nightOf ? ` · ${m.nightOf} 밤` : ''}
                {m.gateNote ? ` · ${m.gateNote}` : ''}
              </div>

              {/* 크게 보는 자리. 탭을 눌러 원본·시트·격자와 견준다. */}
              <div style={STAGE}>
                <Picture
                  src={bigKey}
                  alt={`${m.label || m.key} ${SHOT_LABEL[view]}`}
                  style={{ width: '100%', display: 'block', imageRendering: 'auto' }}
                />
              </div>

              <div style={{ ...GRID, gridTemplateColumns: `repeat(${available.length}, minmax(0, 1fr))` }}>
                {available.map(([kind, key]) => (
                  <button
                    key={kind}
                    type="button"
                    data-action="show-shot"
                    data-shot={kind}
                    data-active={view === kind}
                    onClick={() => setShot((prev) => ({ ...prev, [m.motionId]: kind }))}
                    style={{ ...THUMB, ...(view === kind ? THUMB_ON : null) }}
                  >
                    <Picture src={key} alt={SHOT_LABEL[kind]} style={THUMB_IMG} />
                    <span style={THUMB_CAP}>{SHOT_LABEL[kind]}</span>
                  </button>
                ))}
              </div>

              {/* 판 고르기. 후보가 하나뿐이면 고를 것이 없으니 아예 안 그린다 —
                  누를 일 없는 줄이 한 칸씩 쌓이면 그만큼 판정이 느려진다. */}
              {m.candidates.length > 1 && (
                <div data-part="candidates" style={{ marginTop: 10 }}>
                  <div style={{ ...DIM, fontSize: 12, marginBottom: 4 }}>
                    판 고르기 — 고른 판이 공개됩니다 ({m.candidates.length}판)
                  </div>
                  <div style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fill, minmax(72px, 1fr))' }}>
                    {m.candidates.map((c) => {
                      const on = chosen?.candidateId === c.candidateId;
                      return (
                        <button
                          key={c.candidateId}
                          type="button"
                          data-action="pick-candidate"
                          data-candidate-id={c.candidateId}
                          data-active={on}
                          onClick={() => {
                            setPicked((prev) => ({ ...prev, [m.motionId]: c.candidateId }));
                            setShot((prev) => ({ ...prev, [m.motionId]: 'motion' }));
                          }}
                          style={{ ...THUMB, ...(on ? THUMB_ON : null) }}
                          title={c.gateNote ?? undefined}
                        >
                          <Picture src={c.imageKey} alt={`${c.round}라운드 판`} style={THUMB_IMG} />
                          <span style={THUMB_CAP}>
                            {c.round === 0 ? 'API' : `맥미니 ${c.round}`}
                            {c.gateVerdict ? ` · ${c.gateVerdict}` : ''}
                          </span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

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
                  data-action="verdict-ok"
                  style={{ ...BTN, ...BTN_OK }}
                  disabled={sending === m.motionId}
                  onClick={() => decide(m, 'OK')}
                >
                  좋음
                </button>
                <button
                  type="button"
                  data-action="verdict-regen"
                  style={{ ...BTN, ...BTN_BAD }}
                  disabled={sending === m.motionId}
                  onClick={() => decide(m, 'REGENERATE')}
                >
                  다시 구워야 함
                </button>
              </div>
            </section>
          );
        })}
    </div>
  );
}

// ── 생김새. 검수용이라 꾸미지 않는다 ────────────────────────────────────────

const PAGE: React.CSSProperties = {
  maxWidth: 880,
  margin: '0 auto',
  padding: '8px 12px 64px',
};

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

/** 크게 보는 자리. 폭을 고정해 두어야 탭을 오갈 때 카드 높이가 튀지 않는다. */
const STAGE: React.CSSProperties = {
  marginTop: 10,
  width: '100%',
  maxWidth: 420,
  marginInline: 'auto',
  background: 'rgba(127,127,127,0.08)',
  borderRadius: 8,
  overflow: 'hidden',
};

/**
 * 작은 그림을 늘어놓는 줄.
 *
 * ★ flex 로 두고 그림 폭을 픽셀로 박으면 **폰에서 줄이 접힌다** — 실측으로 카드 안쪽 폭이
 *   390 화면에서 308px 뿐이라, 72px 그림 넷이면 2px 모자라 넷째가 아래로 떨어졌다.
 *   원본과 완성본이 위아래로 갈라지면 "나란히 놓고 견준다" 는 목적 자체가 사라지므로,
 *   칸 수를 grid 에 맡기고 그림이 칸에 맞춰 줄어들게 한다. 더 좁은 폰에서도 안 접힌다.
 */
const GRID: React.CSSProperties = {
  display: 'grid',
  gap: 6,
  marginTop: 8,
};

const THUMB: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  padding: 3,
  border: '1px solid rgba(127,127,127,0.30)',
  borderRadius: 8,
  background: 'transparent',
  color: 'inherit',
  cursor: 'pointer',
  lineHeight: 0,
};

const THUMB_ON: React.CSSProperties = {
  borderColor: 'rgba(60,150,90,0.95)',
  boxShadow: '0 0 0 1px rgba(60,150,90,0.6) inset',
};

/** 칸에 맞춰 줄어든다. 정사각으로 잡아 두어야 그림이 없는 칸에서도 줄 높이가 안 튄다. */
const THUMB_IMG: React.CSSProperties = {
  width: '100%',
  aspectRatio: '1 / 1',
  objectFit: 'contain',
  display: 'block',
};

const THUMB_CAP: React.CSSProperties = {
  display: 'block',
  marginTop: 3,
  fontSize: 10,
  lineHeight: 1.2,
  textAlign: 'center',
  // 자르지 않고 접는다. `API · REVIEW` 가 `API · REVI…` 로 잘리면 게이트가 뭐라 했는지가
  // 사라져, 판을 고르는 데 쓰라고 띄운 글자가 제 일을 못 한다.
  wordBreak: 'keep-all',
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
  boxSizing: 'border-box',
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
