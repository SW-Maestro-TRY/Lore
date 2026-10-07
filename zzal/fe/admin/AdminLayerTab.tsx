'use client';

// 관리자 "2층 실패·대기" 탭(#696) — 실패한 층을 사람이 고른 격자로 살린다.
//
// 한 펫 = 카드 하나: 시트·보존 격자 썸네일 → 후보 격자 1~3장 올리기 → 후보별 게이트 판정과
// 8종 미리보기를 나란히 → "이걸로" 버튼. 검수 화면(AdminReviewScreen)과 같은 생김새를 쓴다.
//
// ★ 서버가 자동 굽기와 **같은 게이트·같은 후처리**로 후보를 자른다 — 여기 보이는 8종이 고르면 그대로 나간다.

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@common/api/client';
import { assetUrl } from '@zzal/lib/assets';
import {
  LAYER_LIST_PATH,
  fetchLayerItems,
  flagLayer2,
  pickCandidate,
  retryLayer2,
  uploadCandidates,
  type LayerCandidate,
  type LayerItem,
} from './layerApi';

type Load = { kind: 'loading' } | { kind: 'ready'; items: LayerItem[] } | { kind: 'error'; message: string };

function explain(e: unknown, where: string): string {
  if (e instanceof ApiError) {
    console.warn('[zzal/admin/layer] 요청 실패', { where, status: e.status, code: e.code, message: e.message });
    if (e.code === 'ADMIN_ONLY') return '관리자 계정이 아닙니다.';
    if (e.status === 404 && where === LAYER_LIST_PATH) {
      return `관리자 기능이 꺼져 있거나 주소가 없습니다 (${where}). ZZAL_ADMIN 설정을 확인해 주세요.`;
    }
    return e.message;
  }
  console.warn('[zzal/admin/layer] 요청 실패', { where, error: e });
  return e instanceof Error ? e.message : '요청을 처리하지 못했습니다.';
}

function Img({ k, alt }: { k: string; alt: string }) {
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={assetUrl(k)} alt={alt} style={THUMB_IMG} />;
}

export default function AdminLayerTab() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  /** 방금 올린 후보(사유 문장이 들어 있다). 목록을 다시 부르면 사유는 비므로 따로 쥔다. */
  const [fresh, setFresh] = useState<Record<string, LayerCandidate[]>>({});

  const reload = useCallback((signal?: AbortSignal) => {
    setLoad({ kind: 'loading' });
    fetchLayerItems(signal)
      .then((items) => setLoad({ kind: 'ready', items: items ?? [] }))
      .catch((e) => {
        if (!signal?.aborted) setLoad({ kind: 'error', message: explain(e, LAYER_LIST_PATH) });
      });
  }, []);

  useEffect(() => {
    const ac = new AbortController();
    reload(ac.signal);
    return () => ac.abort();
  }, [reload]);

  async function run(tag: string, fn: () => Promise<unknown>, done: string) {
    setBusy(tag);
    setMsg(null);
    try {
      await fn();
      setMsg(done);
      reload();
    } catch (e) {
      setMsg(explain(e, tag));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div data-part="admin-layer">
      <p style={NOTICE}>
        <b>2층</b>: 재시도를 다 쓴 펫·30분 넘게 대기 중인 펫·수동 등록한 펫. 사용자에게는 실패 화면 없이
        2층 8종이 <b>연습 중</b>으로 보입니다. <b>1층</b>: 부화에 실패한 알 — 고르면 그 자리에서 살아납니다.
        후보 격자는 1~3장, 서버가 자동 굽기와 같은 게이트·후처리로 자릅니다.
      </p>
      {msg && <p data-part="layer-msg" style={NOTICE}>{msg}</p>}
      {load.kind === 'loading' && <p style={DIM}>불러오는 중…</p>}
      {load.kind === 'error' && (
        <div>
          <p style={{ ...NOTICE, ...NOTICE_BAD }}>{load.message}</p>
          <button type="button" style={BTN} onClick={() => reload()}>다시 불러오기</button>
        </div>
      )}
      {load.kind === 'ready' && load.items.length === 0 && <p data-part="empty" style={DIM}>고칠 펫이 없습니다.</p>}
      {load.kind === 'ready' && load.items.map((it) => {
        const tag = `${it.petId}-${it.layer}`;
        const byId = new Map((fresh[tag] ?? []).map((c) => [c.candidateId, c]));
        const cands = it.candidates.map((c) => byId.get(c.candidateId) ?? c);
        return (
          <section key={tag} data-part="layer-card" data-pet-id={it.petId} data-layer={it.layer} style={CARD}>
            <div style={{ fontSize: 15, fontWeight: 800 }}>
              #{it.petId} {it.name ?? '(이름 없음)'} · {it.layer}층
              <span style={{ ...DIM, fontWeight: 400 }}>
                {' '}· {it.layer === 2 ? `2층 ${it.layer2Status}${it.flagged ? '(수동)' : ''}` : it.phase} · 시도 {it.attempts} · 판 {it.basicRound}
              </span>
            </div>
            {it.lastError && <div data-part="last-error" style={{ ...DIM, fontSize: 12, marginTop: 2, wordBreak: 'break-all' }}>{it.lastError}</div>}

            <div style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fill, minmax(84px, 1fr))' }}>
              {it.sheetKey && (
                <figure style={THUMB}><Img k={it.sheetKey} alt="시트" /><figcaption style={CAP}>시트</figcaption></figure>
              )}
              {it.rejectedKeys.map((k) => (
                <figure key={k} data-part="rejected" style={THUMB}>
                  <a href={assetUrl(k)} target="_blank" rel="noreferrer"><Img k={k} alt="보존 격자" /></a>
                  <figcaption style={CAP}>{k.split('/').pop()}</figcaption>
                </figure>
              ))}
            </div>
            {it.identityText && (
              <details style={{ marginTop: 6 }}>
                <summary style={DIM}>생김새 문단</summary>
                <p style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{it.identityText}</p>
              </details>
            )}

            <label style={{ display: 'block', marginTop: 10, fontSize: 13 }}>
              {it.layer === 2 ? 'grid2.png' : 'grid.png'} 후보 올리기(1~3장){' '}
              <input
                type="file"
                accept="image/png"
                multiple
                data-action="upload-candidates"
                disabled={busy !== null}
                onChange={(e) => {
                  const files = Array.from(e.target.files ?? []).slice(0, 3);
                  e.target.value = '';
                  if (!files.length) return;
                  void run(`upload ${tag}`, async () => {
                    const got = await uploadCandidates(it.petId, it.layer, files);
                    setFresh((prev) => ({ ...prev, [tag]: [...(prev[tag] ?? []), ...got] }));
                  }, `후보 ${files.length}장 처리 완료`);
                }}
              />
            </label>

            {cands.length > 0 && (
              <div data-part="candidates" style={{ ...GRID, gridTemplateColumns: `repeat(${Math.min(cands.length, 3)}, minmax(0, 1fr))` }}>
                {cands.map((c) => {
                  const keys = Object.values(c.previewKeys ?? {});
                  return (
                    <div key={c.candidateId} data-part="candidate" data-candidate-id={c.candidateId} data-gate={c.gate} style={CAND}>
                      <div style={{ fontSize: 12, fontWeight: 700 }}>{c.candidateId} · {c.gate}</div>
                      {c.message && <div style={{ ...DIM, fontSize: 11, wordBreak: 'break-all' }}>{c.message}</div>}
                      <div style={{ ...GRID, gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 2 }}>
                        {keys.length
                          ? keys.map((k) => <Img key={k} k={k} alt={k.split('/').pop() ?? ''} />)
                          : <Img k={c.gridKey} alt="격자" />}
                      </div>
                      <button
                        type="button"
                        data-action="pick"
                        disabled={c.gate !== 'PASS' || busy !== null}
                        style={{ ...BTN, ...BTN_OK, marginTop: 6, width: '100%' }}
                        onClick={() => {
                          if (!window.confirm(`#${it.petId} ${it.layer}층을 ${c.candidateId} 로 바꿉니다. 사용자 화면에 바로 반영됩니다.`)) return;
                          void run(`pick ${tag}`, () => pickCandidate(it.petId, it.layer, c.candidateId), `${it.layer}층 교체 완료`);
                        }}
                      >
                        이걸로
                      </button>
                    </div>
                  );
                })}
              </div>
            )}

            {it.layer === 2 && (
              <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                <button
                  type="button"
                  data-action="retry"
                  disabled={busy !== null || it.layer2Status === 'RUNNING'}
                  style={{ ...BTN, ...BTN_BAD }}
                  onClick={() => {
                    if (!window.confirm('운영 API 로 2층을 처음부터 다시 굽습니다(이미지 비용 발생).')) return;
                    void run(`retry ${tag}`, () => retryLayer2(it.petId), '2층 재시도 시작');
                  }}
                >
                  API 로 다시 굽기(비용)
                </button>
              </div>
            )}
          </section>
        );
      })}
      <FlagForm busy={busy !== null} onFlag={(id, reason) => run(`flag ${id}`, () => flagLayer2(id, reason), `#${id} 2층 수동 등록`)} />
    </div>
  );
}

/** 통과했지만 결함인 2층을 목록에 올리는 칸(펫23 빈 칸 같은 것). */
function FlagForm({ busy, onFlag }: { busy: boolean; onFlag: (petId: number, reason: string) => void }) {
  const [id, setId] = useState('');
  const [reason, setReason] = useState('');
  return (
    <section data-part="flag-form" style={CARD}>
      <div style={{ fontSize: 14, fontWeight: 800 }}>2층 수동 등록</div>
      <div style={{ ...DIM, fontSize: 12 }}>통과했지만 결함인 펫. 올라간 그림은 그대로 두고 2층을 FAILED 로 — 사용자에게는 연습 중이 됩니다.</div>
      <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
        <input value={id} onChange={(e) => setId(e.target.value.replace(/\D/g, ''))} placeholder="펫 번호" style={{ ...INPUT, width: 90 }} />
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="사유(선택)" maxLength={200} style={INPUT} />
        <button type="button" data-action="flag" disabled={busy || !id} style={BTN} onClick={() => onFlag(Number(id), reason)}>등록</button>
      </div>
    </section>
  );
}

const DIM: React.CSSProperties = { color: 'var(--muted, #7a7a7a)', fontSize: 13 };
const NOTICE: React.CSSProperties = { fontSize: 13, lineHeight: 1.5, margin: '0 0 16px', padding: '8px 10px', borderRadius: 6, background: 'rgba(127,127,127,0.10)' };
const NOTICE_BAD: React.CSSProperties = { background: 'rgba(220,80,60,0.14)' };
const CARD: React.CSSProperties = { border: '1px solid rgba(127,127,127,0.28)', borderRadius: 10, padding: 12, marginBottom: 16 };
const GRID: React.CSSProperties = { display: 'grid', gap: 6, marginTop: 8 };
const THUMB: React.CSSProperties = { margin: 0, padding: 3, border: '1px solid rgba(127,127,127,0.30)', borderRadius: 8, lineHeight: 0 };
const THUMB_IMG: React.CSSProperties = { width: '100%', aspectRatio: '1 / 1', objectFit: 'contain', display: 'block', background: 'rgba(127,127,127,0.06)' };
const CAP: React.CSSProperties = { display: 'block', marginTop: 3, fontSize: 10, lineHeight: 1.2, textAlign: 'center', wordBreak: 'break-all' };
const CAND: React.CSSProperties = { border: '1px solid rgba(127,127,127,0.30)', borderRadius: 8, padding: 6, minWidth: 0 };
const INPUT: React.CSSProperties = { flex: 1, padding: '7px 9px', fontSize: 13, borderRadius: 6, border: '1px solid rgba(127,127,127,0.35)', background: 'transparent', color: 'inherit', boxSizing: 'border-box' };
const BTN: React.CSSProperties = { padding: '8px 14px', fontSize: 13, fontWeight: 700, borderRadius: 6, border: '1px solid rgba(127,127,127,0.35)', background: 'transparent', color: 'inherit', cursor: 'pointer' };
const BTN_OK: React.CSSProperties = { borderColor: 'rgba(60,150,90,0.7)' };
const BTN_BAD: React.CSSProperties = { borderColor: 'rgba(200,90,60,0.7)' };
