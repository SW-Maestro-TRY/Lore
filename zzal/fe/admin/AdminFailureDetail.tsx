'use client';

// 실패 한 건 상세 — 그림 4칸(입력 · 시트 · 격자 · 결과) · 단계 타임라인 · 이유 원문 · 재시도 이력(결정 2).
//
// ★ 넓은 화면은 줄 아래 펼침, 폭 600px 미만은 화면을 덮는다(닫기 44px). 그림 4칸은 600 미만에서 2열.

import { useEffect, useState } from 'react';
import { CARD, DIM, GRID, NOTICE, NOTICE_BAD, Picture, THUMB, THUMB_CAP, THUMB_IMG, BTN } from './AdminReviewScreen';
import { MotionSheet } from './AdminLayerTab';
import AdminCustomSequenceViewer from './AdminCustomSequenceViewer';
import { REQUEST_LABEL, when } from './AdminFailureRow';
import {
  cancelRetry,
  explain,
  fetchFailureDetail,
  FAILURES_PATH,
  pickRetry,
  requestRetry,
  type FailureDetail,
  type FailureRow,
  type FailureStep,
  type RunnerView,
} from './failuresApi';

const BAD = 'rgb(200,70,50)';

export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia('(max-width: 599px)');
    const on = () => setNarrow(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return narrow;
}

function Cell({ label, src, part }: { label: string; src: string | null | undefined; part: string }) {
  return (
    <figure data-part={part} style={{ ...THUMB, margin: 0, cursor: 'default' }}>
      {src ? <Picture src={src} alt={label} style={THUMB_IMG} /> : <div style={{ ...THUMB_IMG, display: 'grid', placeItems: 'center', lineHeight: 1.2, fontSize: 12 }}>없음</div>}
      <figcaption style={THUMB_CAP}>{label}</figcaption>
    </figure>
  );
}

function Timeline({ steps }: { steps: FailureStep[] }) {
  const max = Math.max(1, ...steps.map((s) => s.durationMs ?? 0));
  if (steps.length === 0) return <p style={DIM}>단계 기록 없음</p>;
  return (
    <ol data-part="timeline" style={{ listStyle: 'none', padding: 0, margin: '6px 0 0' }}>
      {steps.map((s, i) => {
        const failed = s.status === 'FAILED';
        const pct = s.durationMs == null ? 0 : Math.max(2, (s.durationMs / max) * 100);
        return (
          <li key={`${s.jobId}-${s.name}-${i}`} data-part="step" data-status={s.status} style={{ fontSize: 12, marginTop: 4 }}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, color: failed ? BAD : 'inherit', fontWeight: failed ? 700 : 400 }}>
              <span>{s.attempt > 0 ? `${s.attempt}회 · ` : ''}{s.name}</span>
              <span>{s.status}</span>
              {s.durationMs != null && <span>{Math.round(s.durationMs / 1000)}초</span>}
              {s.errorCode && <span>{s.errorCode}</span>}
              {s.costUsd != null && <span>${s.costUsd.toFixed(3)}</span>}
            </div>
            <div style={{ height: 6, marginTop: 2, background: 'rgba(127,127,127,0.15)', borderRadius: 3 }}>
              <div style={{ width: `${pct}%`, height: '100%', borderRadius: 3, background: failed ? BAD : 'rgba(60,150,90,0.7)' }} />
            </div>
          </li>
        );
      })}
    </ol>
  );
}

export interface DetailProps {
  row: FailureRow;
  runner: RunnerView | null;
  onClose: () => void;
  onChanged: () => void;
}

export default function AdminFailureDetail({ row, runner, onClose, onChanged }: DetailProps) {
  const narrow = useNarrow();
  const [detail, setDetail] = useState<FailureDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [sequence, setSequence] = useState(false);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    const ac = new AbortController();
    fetchFailureDetail(row.kind, row.id, ac.signal)
      .then(setDetail)
      .catch((e) => {
        if (!ac.signal.aborted) setError(explain(e, `${FAILURES_PATH}/${row.kind}/${row.id}`));
      });
    return () => ac.abort();
  }, [row.kind, row.id, version]);

  const refresh = () => {
    setVersion((v) => v + 1);
    onChanged();
  };

  const images = detail?.images;
  const firstGrid = images ? images.gridKeys[0] ?? images.rejectedGridKeys[0] ?? null : null;
  const results = images ? Object.entries(images.resultKeys) : [];

  const body = (
    <div data-part="failure-detail" data-kind={row.kind} data-id={row.id} style={{ ...CARD, marginTop: 6 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
        <b style={{ fontSize: 15, overflowWrap: 'anywhere' }}>
          {row.kind === 'HATCH' ? '부화' : '맞춤'} · {row.petName ?? '이름 없음'} <span style={{ ...DIM, fontWeight: 400 }}>펫 {row.petId}</span>
        </b>
        <button type="button" data-action="failure-close" onClick={onClose} style={{ ...BTN, minHeight: 44, minWidth: 44 }}>닫기</button>
      </div>
      {error && <p style={{ ...NOTICE, ...NOTICE_BAD, marginTop: 8 }}>{error}</p>}
      {!detail && !error && <p style={DIM}>불러오는 중…</p>}
      {detail && images && (
        <>
          <div data-part="detail-images" style={{ ...GRID, gridTemplateColumns: narrow ? 'repeat(2, minmax(0, 1fr))' : 'repeat(4, minmax(0, 1fr))' }}>
            <Cell part="img-input" label="입력" src={images.inputKey} />
            <Cell part="img-sheet" label="시트" src={images.sheetKey} />
            <Cell part="img-grid" label="격자" src={firstGrid} />
            <Cell part="img-result" label="결과" src={results[0]?.[1] ?? null} />
          </div>
          {detail.identityMissing && (
            <p data-part="identity-missing" style={{ fontSize: 13, fontWeight: 700, color: BAD, marginTop: 6 }}>문단 없음 — 러너가 정체성 문단부터 만들어야 합니다</p>
          )}

          {(images.gridKeys.length > 1 || images.rejectedGridKeys.length > 0) && (
            <details style={{ marginTop: 8 }}>
              <summary style={{ fontSize: 13, cursor: 'pointer', minHeight: 44, display: 'flex', alignItems: 'center' }}>
                격자 — 성공 {images.gridKeys.length} · 보존 {images.rejectedGridKeys.length}
              </summary>
              <div style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))' }}>
                {images.gridKeys.map((k) => <Cell key={k} part="grid-ok" label="성공" src={k} />)}
                {images.rejectedGridKeys.map((k) => <Cell key={k} part="grid-rejected" label="보존" src={k} />)}
              </div>
            </details>
          )}

          {results.length > 0 && row.kind === 'HATCH' && (
            <details style={{ marginTop: 8 }}>
              <summary style={{ fontSize: 13, cursor: 'pointer', minHeight: 44, display: 'flex', alignItems: 'center' }}>결과 {results.length}종</summary>
              <MotionSheet keys={images.resultKeys} part="result-sheet" />
            </details>
          )}
          {row.kind === 'CUSTOM' && row.id > 0 && (
            <button type="button" data-action="custom-sequence" onClick={() => setSequence(true)} style={{ ...BTN, minHeight: 44, marginTop: 8 }}>
              맞춤 8종 이어보기
            </button>
          )}
          {sequence && (
            <AdminCustomSequenceViewer jobId={row.id} titles={Array.from({ length: 8 }, (_, i) => `동작 ${i + 1}`)} onClose={() => setSequence(false)} />
          )}

          <h3 style={{ fontSize: 13, margin: '12px 0 0' }}>단계</h3>
          <Timeline steps={detail.steps} />

          {detail.errorDetail && (
            <>
              <h3 style={{ fontSize: 13, margin: '12px 0 0' }}>이유 원문</h3>
              <pre data-part="error-detail" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, margin: '4px 0 0', padding: 8, background: 'rgba(127,127,127,0.08)', borderRadius: 6 }}>
                {detail.errorDetail}
              </pre>
            </>
          )}

          <DetailActions row={row} detail={detail} runner={runner} onChanged={refresh} />

          <h3 style={{ fontSize: 13, margin: '12px 0 0' }}>재시도 이력</h3>
          {detail.retries.length === 0 ? <p style={DIM}>없음</p> : (
            <ul data-part="retry-history" style={{ listStyle: 'none', padding: 0, margin: '4px 0 0' }}>
              {detail.retries.map((r) => (
                <li key={r.id} data-part="retry-item" data-result={r.result ?? 'OPEN'} style={{ fontSize: 12, padding: '4px 0', borderTop: '1px solid rgba(127,127,127,0.2)', overflowWrap: 'anywhere' }}>
                  <b>#{r.id}</b> {r.auto ? '자동' : '수동'} · {when(r.requestedAt)} · {r.result ?? (r.pickedAt ? '굽는 중' : '대기')}
                  {r.appliedChoice && <> · 고름 {r.appliedChoice}</>}
                  {r.memo && <div>메모: {r.memo}</div>}
                  {r.note && <div style={{ color: r.result === 'APPLY_FAILED' ? BAD : 'inherit' }}>메모(러너·서버): {r.note}</div>}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  );

  if (!narrow) return body;
  return (
    <div
      data-part="failure-overlay"
      role="dialog"
      aria-modal="true"
      style={{ position: 'fixed', inset: 0, zIndex: 70, overflowY: 'auto', background: 'var(--bg, #fff)', padding: '8px 12px 24px', boxSizing: 'border-box' }}
    >
      {body}
    </div>
  );
}

type CandidateEntry = { candidateId: string; gate: string; previewKey?: string | null; previewKeys?: Record<string, string> | null };

/** 결과를 기다리는 · 고를 수 있는 최신 요청. */
function latestOpen(detail: FailureDetail) {
  return detail.retries.find((r) => r.result == null || r.result === 'CANDIDATES') ?? null;
}

/**
 * 재시도 · 취소 · 후보 고르기(결정 3·4).
 *
 * ★ 사용자 화면을 바꾸는 버튼은 "이걸로"(부화) · "이걸로 지급"(맞춤) 둘이다 — 둘 다 확인 창을 한 번 거친다.
 * ★ 재시도는 "맥미니로 재시도" 하나다(과금 없음). 서버에서 굽는 길은 두지 않는다(2026-10-28 결정).
 */
function DetailActions({ row, detail, runner, onChanged }: { row: FailureRow; detail: FailureDetail; runner: RunnerView | null; onChanged: () => void }) {
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ text: string; bad: boolean } | null>(null);
  const open = latestOpen(detail);
  const openId = row.openRetryId ?? open?.id ?? null;
  const failedNote = detail.retries.find((r) => r.result === 'APPLY_FAILED')?.note ?? null;
  const candidates = open?.result === 'CANDIDATES' ? open.candidates : null;

  async function run(fn: () => Promise<string | null>) {
    setBusy(true);
    setMsg(null);
    try {
      const done = await fn();
      if (done) setMsg({ text: done, bad: false });
      onChanged();
    } catch (e) {
      setMsg({ text: explain(e, FAILURES_PATH), bad: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div data-part="failure-actions" style={{ marginTop: 12 }}>
      <p data-part="request-state" style={{ ...DIM, margin: 0 }}>요청: {REQUEST_LABEL[row.request]}</p>
      {failedNote && <p data-part="apply-failed" style={{ fontSize: 13, color: BAD, margin: '4px 0 0', overflowWrap: 'anywhere' }}>반영 실패: {failedNote}</p>}
      {msg && <p data-part="action-msg" style={{ ...NOTICE, ...(msg.bad ? NOTICE_BAD : null), marginTop: 6 }}>{msg.text}</p>}

      {candidates && row.kind === 'HATCH' && (
        <div data-part="hatch-candidates" style={{ marginTop: 8 }}>
          {((candidates.grids as CandidateEntry[] | undefined) ?? []).map((c) => {
            const pass = c.gate === 'PASS';
            const keys = c.previewKeys && Object.keys(c.previewKeys).length ? c.previewKeys : c.previewKey ? { base: c.previewKey } : {};
            return (
              <div key={c.candidateId} data-part="failure-candidate" data-gate={c.gate} style={{ ...CARD, marginTop: 6 }}>
                <div style={{ fontSize: 12, fontWeight: 700 }}>{c.candidateId} · {pass ? '게이트 통과' : `게이트 ${c.gate}`}</div>
                {Object.keys(keys).length > 0 && <MotionSheet keys={keys} part="candidate-sheet" />}
                <button
                  type="button"
                  data-action="failure-pick"
                  data-choice={c.candidateId}
                  disabled={!pass || busy}
                  onClick={() => {
                    if (!window.confirm('이 후보로 1층 8종을 올리고 알을 살립니다. 사용자 화면이 바뀝니다.')) return;
                    void run(async () => { await pickRetry(open!.id, c.candidateId); return '반영했습니다.'; });
                  }}
                  style={{ ...BTN, minHeight: 44, marginTop: 6, opacity: pass ? 1 : 0.5 }}
                >
                  이걸로
                </button>
              </div>
            );
          })}
        </div>
      )}

      {candidates && row.kind === 'CUSTOM' && (
        <div data-part="custom-candidates" style={{ marginTop: 8 }}>
          <MotionSheet
            part="custom-candidate-sheet"
            keys={Object.fromEntries(Object.entries((candidates.files as Record<string, string> | undefined) ?? {})
              .filter(([p]) => p.endsWith('.webp') && !p.includes('/')).map(([p, k]) => [p.replace(/\.webp$/, ''), k]))}
          />
          <button
            type="button"
            data-action="failure-pick"
            data-choice="custom"
            disabled={busy}
            onClick={() => {
              if (!window.confirm('이 8종을 사용자에게 지급합니다.')) return;
              void run(async () => { await pickRetry(open!.id, 'custom'); return '지급했습니다.'; });
            }}
            style={{ ...BTN, minHeight: 44, marginTop: 6 }}
          >
            이걸로 지급
          </button>
        </div>
      )}

      {!openId && (
        <>
          <label style={{ display: 'block', fontSize: 12, marginTop: 8 }}>
            메모(러너에 같이 갑니다)
            <textarea
              data-part="retry-memo"
              maxLength={500}
              value={memo}
              onChange={(e) => setMemo(e.target.value)}
              rows={3}
              style={{ display: 'block', width: '100%', boxSizing: 'border-box', marginTop: 4, padding: 8, fontSize: 13, borderRadius: 6, border: '1px solid rgba(127,127,127,0.35)', background: 'transparent', color: 'inherit' }}
            />
          </label>
          <div style={{ ...DIM, fontSize: 11, textAlign: 'right' }} data-part="memo-left">{500 - memo.length}자 남음</div>
          {!row.revivable && row.blockedReason && <p data-part="blocked" style={{ fontSize: 12, color: BAD, margin: '4px 0 0' }}>{row.blockedReason}</p>}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 6 }}>
              <button
                type="button"
                data-action="failure-retry"
                disabled={busy || !row.revivable}
                onClick={() => void run(async () => {
                  await requestRetry(row.kind, row.id, memo.trim());
                  return runner?.stale || runner?.lastSeenAt == null
                    ? '요청은 저장됐고 러너가 켜지면 집습니다.'
                    : '요청했습니다. 러너가 다음 확인 때 집습니다.';
                })}
                style={{ ...BTN, minHeight: 44, opacity: row.revivable ? 1 : 0.5 }}
              >
                맥미니로 재시도
              </button>
          </div>
        </>
      )}

      {openId && (
        <button
          type="button"
          data-action="failure-retry-cancel"
          disabled={busy}
          onClick={() => void run(async () => { await cancelRetry(openId); return '요청을 취소했습니다.'; })}
          style={{ ...BTN, minHeight: 44, marginTop: 8 }}
        >
          요청 취소
        </button>
      )}
    </div>
  );
}
