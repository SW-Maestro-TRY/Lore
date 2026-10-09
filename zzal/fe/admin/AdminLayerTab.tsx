'use client';

// 관리자 "1·2층 복구" 탭(#696 → #702 카드 재설계) — 실패·결함인 층을 사람이 고른 후보로 살린다.
//
// ★ 움짤 검수(AdminReviewScreen)처럼 보이게 만든다(2026-10-08 상훈님: "움짤 검수 쪽이 보기가 엄청 편했거든").
//   한 펫 = 카드 하나. 카드 안에서 판정에 쓰이는 것만 — 지금 보이는 8종(결함이 있는 그것) · 후보별 8종이
//   **움직이는 채로** 나란히 · 버튼 셋("이걸로" / "다시 만들기" / "결함 표시 해제"). 그림 자리·생김새는
//   검수 화면의 것을 그대로 빌려 쓴다(Picture·THUMB·GRID — 복사하지 않는다).
//
// ★★ 사용자 화면이 바뀌는 버튼은 "이걸로" 하나뿐이다(#702).
//   결함 표시·다시 만들기·해제는 관리자 목록에만 영향을 준다 — 예전(#696)에는 결함 표시가 2층을 FAILED 로 바꿔
//   사용자 2층 8종이 그 자리에서 "연습 중" 으로 잠겼다(10/8 펭놈·쿠리만쥬). 화면 문구도 그 사실대로 쓴다.
//
// ★ "다시 만들기" = 맥미니 러너(10분마다)가 Codex 로 3판을 만들어 게이트·후처리를 통과한 것을 후보로 올린다
//   (선물 재생성의 LOCAL_REQUESTED 와 같은 길). 고르는 것은 여기서 사람이 한다.

import { useCallback, useEffect, useState } from 'react';
import { ApiError } from '@common/api/client';
import { assetUrl } from '@zzal/lib/assets';
import {
  BTN,
  BTN_BAD,
  BTN_OK,
  CARD,
  DIM,
  GRID,
  INPUT,
  NOTICE,
  NOTICE_BAD,
  Picture,
  THUMB,
  THUMB_CAP,
  THUMB_IMG,
} from './AdminReviewScreen';
import {
  LAYER_LIST_PATH,
  cancelRegen,
  fetchLayerItems,
  flagLayer2,
  pickCandidate,
  requestRegen,
  retryLayer2,
  unflagLayer2,
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

/** 시각을 한국 시각 `10/08 15:20` 으로. */
function when(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  const k = new Date(d.getTime() + 9 * 3600_000);
  return `${p(k.getUTCMonth() + 1)}/${p(k.getUTCDate())} ${p(k.getUTCHours())}:${p(k.getUTCMinutes())}`;
}

/** 카드 머리의 상태 한 마디. 2층 상태 값을 그대로 말하지 않고 "사용자에게 지금 어떻게 보이나" 를 붙인다. */
function stateLabel(it: LayerItem): { text: string; bad: boolean } {
  if (it.layer === 1) return { text: '부화 실패 — 사용자에게 실패한 알', bad: true };
  if (it.layer2Status === 'READY') return { text: `결함 표시 — 사용자에게는 지금 그림 그대로`, bad: false };
  if (it.layer2Status === 'FAILED') return { text: '2층 실패 — 사용자에게 연습 중', bad: true };
  return { text: `2층 ${it.layer2Status} 오래 대기 — 사용자에게 연습 중`, bad: true };
}

const RECOVERY_LABEL: Record<LayerItem['recovery'], string> = {
  LOCAL_REQUESTED: '맥미니가 다시 만드는 중',
  CANDIDATES: '후보 도착 — 골라 주세요',
  WAITING: '후보 없음',
};

/**
 * 8상태 시트 — 그 층 8종을 움직이는 채로 4×2 로.
 * 검수 화면의 그림 자리(Picture·THUMB_IMG)를 그대로 쓴다 — 애니메이션 webp 가 첫 프레임에 멈추지 않는다.
 */
function MotionSheet({ keys, part }: { keys: Record<string, string>; part: string }) {
  const entries = Object.entries(keys);
  return (
    <div data-part={part} data-count={entries.length} style={{ ...GRID, gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 4 }}>
      {entries.map(([k, key]) => (
        <figure key={k} style={{ ...THUMB, margin: 0, cursor: 'default' }}>
          <Picture src={key} alt={k} style={THUMB_IMG} />
          <figcaption style={THUMB_CAP}>{k}</figcaption>
        </figure>
      ))}
    </div>
  );
}

export default function AdminLayerTab() {
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ text: string; bad: boolean } | null>(null);
  /** 방금 올린 후보(사유 문장이 들어 있다). 목록을 다시 부르면 사유는 비므로 따로 쥔다. */
  const [fresh, setFresh] = useState<Record<string, LayerCandidate[]>>({});

  const reload = useCallback((signal?: AbortSignal) => {
    setLoad((prev) => (prev.kind === 'ready' ? prev : { kind: 'loading' }));
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
      setMsg({ text: done, bad: false });
      reload();
    } catch (e) {
      setMsg({ text: explain(e, tag), bad: true });
    } finally {
      setBusy(null);
    }
  }

  const items = load.kind === 'ready' ? load.items : [];

  return (
    <div data-part="admin-layer" style={{ paddingBottom: 64 }}>
      <h1 style={{ fontSize: 20, fontWeight: 800, margin: '0 0 4px' }}>1·2층 복구</h1>
      <p data-part="notice" style={NOTICE}>
        사용자 화면이 바뀌는 버튼은 <b>이걸로</b> 하나뿐입니다. <b>다시 만들기</b>는 맥미니가 10분마다 확인해
        Codex로 3판을 만들고, 게이트·후처리를 통과한 것을 여기 후보로 올립니다. <b>결함 표시</b>·<b>해제</b>는
        이 목록에만 영향을 줍니다.
      </p>
      {msg && <p data-part="layer-msg" style={{ ...NOTICE, ...(msg.bad ? NOTICE_BAD : null) }}>{msg.text}</p>}
      {load.kind === 'loading' && <p style={DIM}>불러오는 중…</p>}
      {load.kind === 'error' && (
        <div data-part="load-error">
          <p style={{ ...NOTICE, ...NOTICE_BAD }}>{load.message}</p>
          <button type="button" style={BTN} onClick={() => reload()}>다시 불러오기</button>
        </div>
      )}
      {load.kind === 'ready' && items.length === 0 && <p data-part="empty" style={DIM}>고칠 펫이 없습니다.</p>}

      {items.map((it) => {
        const tag = `${it.petId}-${it.layer}`;
        const byId = new Map((fresh[tag] ?? []).map((c) => [c.candidateId, c]));
        const cands = it.candidates.map((c) => byId.get(c.candidateId) ?? c);
        const state = stateLabel(it);
        const requested = it.recovery === 'LOCAL_REQUESTED';
        const hasCurrent = Object.keys(it.currentKeys ?? {}).length > 0;
        return (
          <section
            key={tag}
            data-part="layer-card"
            data-pet-id={it.petId}
            data-layer={it.layer}
            data-recovery={it.recovery}
            data-candidates={cands.length}
            style={CARD}
          >
            <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
              {it.sheetKey && (
                <a href={assetUrl(it.sheetKey)} target="_blank" rel="noreferrer" style={{ ...THUMB, width: 64, flex: '0 0 64px' }}>
                  <Picture src={it.sheetKey} alt="시트" style={THUMB_IMG} />
                  <span style={THUMB_CAP}>시트</span>
                </a>
              )}
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ fontSize: 15, fontWeight: 800 }}>
                  #{it.petId} {it.name ?? '(이름 없음)'}
                  <span data-part="layer-pill" style={PILL}>{it.layer}층</span>
                  <span style={{ ...DIM, fontWeight: 400 }}> · 판 {it.basicRound}</span>
                </div>
                <div data-part="state" style={{ fontSize: 12, marginTop: 2, color: state.bad ? 'rgb(200,90,60)' : 'inherit' }}>
                  {state.text}
                </div>
                {it.lastError && (
                  <div data-part="last-error" style={{ ...DIM, fontSize: 12, marginTop: 2, wordBreak: 'break-all' }}>
                    사유: {it.lastError}
                  </div>
                )}
                <div data-part="recovery" style={{ fontSize: 12, marginTop: 4, fontWeight: 700 }}>
                  {RECOVERY_LABEL[it.recovery]}
                  <span style={{ ...DIM, fontSize: 12, fontWeight: 400 }}>
                    {requested && it.regenRequestedAt ? ` · 요청 ${when(it.regenRequestedAt)}` : ''}
                    {it.recoveredAt ? ` · 마지막 복구 ${when(it.recoveredAt)}` : ''}
                    {` · 시도 ${it.attempts}`}
                  </span>
                </div>
              </div>
            </div>

            <div style={{ marginTop: 12 }}>
              <div style={{ ...DIM, fontSize: 12 }}>
                후보 {cands.length ? `${cands.length}판` : ''}
                {it.layer === 1 ? ' — 고르면 알이 살아나고, 2층도 새 1층 기준으로 다시 잘립니다(2층 격자가 있으면 돈 안 듦)' : ''}
              </div>
              {/* ★ 지금 보이는 8종을 후보와 **같은 줄·같은 크기**로 — 견주는 것이 이 카드의 일이다. */}
              <div data-part="candidates" style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 10 }}>
                  {hasCurrent && (
                    <div data-part="current-col" style={{ ...CAND, background: 'rgba(127,127,127,0.06)' }}>
                      <div style={{ fontSize: 12, fontWeight: 700 }}>지금 사용자에게 보이는 것</div>
                      <MotionSheet keys={it.currentKeys} part="current" />
                    </div>
                  )}
                  {cands.length === 0 && (
                    <div data-part="no-candidates" style={{ ...EMPTY, marginTop: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      {requested ? '맥미니가 만드는 중입니다 — 끝나면 여기 나타납니다.' : '후보 없음'}
                    </div>
                  )}
                  {cands.map((c, i) => {
                    const keys = c.previewKeys ?? {};
                    const pass = c.gate === 'PASS';
                    return (
                      <div
                        key={c.candidateId}
                        data-part="candidate"
                        data-candidate-id={c.candidateId}
                        data-gate={c.gate}
                        style={{ ...CAND, ...(pass ? null : { opacity: 0.75 }) }}
                      >
                        <div style={{ fontSize: 12, fontWeight: 700 }}>
                          후보 {i + 1} · <span style={{ color: pass ? 'rgb(60,150,90)' : 'rgb(200,90,60)' }}>{c.gate}</span>
                          <span style={{ ...DIM, fontSize: 11, fontWeight: 400 }}> {c.candidateId}</span>
                        </div>
                        {c.message && <div style={{ ...DIM, fontSize: 11, wordBreak: 'break-all' }}>{c.message}</div>}
                        {Object.keys(keys).length ? (
                          <MotionSheet keys={keys} part="candidate-sheet" />
                        ) : (
                          <a href={assetUrl(c.gridKey)} target="_blank" rel="noreferrer" style={{ display: 'block', marginTop: 6 }}>
                            <Picture src={c.gridKey} alt="격자" style={THUMB_IMG} />
                          </a>
                        )}
                        <button
                          type="button"
                          data-action="pick"
                          disabled={!pass || busy !== null}
                          style={{ ...BTN, ...BTN_OK, marginTop: 8, width: '100%', opacity: pass ? 1 : 0.5 }}
                          onClick={() => {
                            const what = it.layer === 2
                              ? `사용자 2층 8종이 후보 ${i + 1}로 바뀝니다.`
                              : `알이 살아나고 1층이 후보 ${i + 1}로 정해집니다. 2층도 다시 잘립니다.`;
                            if (!window.confirm(`#${it.petId} ${it.name ?? ''} — ${what}`)) return;
                            void run(`pick ${tag}`, () => pickCandidate(it.petId, it.layer, c.candidateId), `#${it.petId} ${it.layer}층 교체 완료`);
                          }}
                        >
                          이걸로
                        </button>
                      </div>
                    );
                  })}
              </div>
            </div>

            <div data-part="actions" style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
              {requested ? (
                <button
                  type="button"
                  data-action="regen-cancel"
                  disabled={busy !== null}
                  style={BTN}
                  onClick={() => void run(`regen-cancel ${tag}`, () => cancelRegen(it.petId, it.layer), `#${it.petId} 다시 만들기 취소`)}
                >
                  요청 취소
                </button>
              ) : (
                <button
                  type="button"
                  data-action="regen"
                  disabled={busy !== null}
                  style={{ ...BTN, ...BTN_BAD }}
                  onClick={() => void run(`regen ${tag}`, () => requestRegen(it.petId, it.layer), `#${it.petId} ${it.layer}층 다시 만들기 요청 — 맥미니가 10분 안에 집습니다`)}
                >
                  다시 만들기
                </button>
              )}
              {it.layer === 2 && it.flagged && (
                <button
                  type="button"
                  data-action="unflag"
                  disabled={busy !== null}
                  style={BTN}
                  onClick={() => void run(`unflag ${tag}`, () => unflagLayer2(it.petId), `#${it.petId} 결함 표시 해제`)}
                >
                  결함 표시 해제
                </button>
              )}
            </div>

            <details style={{ marginTop: 10 }}>
              <summary style={{ ...DIM, fontSize: 12, cursor: 'pointer' }}>
                직접 올리기 · 보존 격자 {it.rejectedKeys.length}장{it.layer === 2 && it.layer2Status === 'FAILED' ? ' · API 재시도' : ''}
              </summary>
              {it.rejectedKeys.length > 0 && (
                <div style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fill, minmax(84px, 1fr))' }}>
                  {it.rejectedKeys.map((k) => (
                    <a key={k} data-part="rejected" href={assetUrl(k)} target="_blank" rel="noreferrer" style={THUMB}>
                      <Picture src={k} alt="보존 격자" style={THUMB_IMG} />
                      <span style={{ ...THUMB_CAP, wordBreak: 'break-all' }}>{k.split('/').pop()}</span>
                    </a>
                  ))}
                </div>
              )}
              {it.identityText && (
                <p style={{ ...DIM, fontSize: 12, whiteSpace: 'pre-wrap' }}>{it.identityText}</p>
              )}
              <label style={{ display: 'block', marginTop: 8, fontSize: 13 }}>
                {it.layer === 2 ? 'grid2.png' : 'grid.png'} 후보 직접 올리기(1~3장){' '}
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
              {it.layer === 2 && it.layer2Status === 'FAILED' && (
                <button
                  type="button"
                  data-action="retry"
                  disabled={busy !== null}
                  style={{ ...BTN, ...BTN_BAD, marginTop: 8 }}
                  onClick={() => {
                    if (!window.confirm('운영 API 로 2층을 처음부터 다시 굽습니다(이미지 비용 발생).')) return;
                    void run(`retry ${tag}`, () => retryLayer2(it.petId), '2층 재시도 시작');
                  }}
                >
                  API 로 다시 굽기(비용)
                </button>
              )}
            </details>
          </section>
        );
      })}
      <FlagForm busy={busy !== null} onFlag={(id, reason) => run(`flag ${id}`, () => flagLayer2(id, reason), `#${id} 2층 결함 표시 — 목록에 올렸습니다(사용자 화면 그대로)`)} />
    </div>
  );
}

/** 통과했지만 결함인 2층을 목록에 올리는 칸(펫23 빈 칸 같은 것). */
function FlagForm({ busy, onFlag }: { busy: boolean; onFlag: (petId: number, reason: string) => void }) {
  const [id, setId] = useState('');
  const [reason, setReason] = useState('');
  return (
    <section data-part="flag-form" style={CARD}>
      <div style={{ fontSize: 14, fontWeight: 800 }}>2층 결함 표시</div>
      <div style={{ ...DIM, fontSize: 12 }}>
        통과했지만 결함인 펫을 이 목록에 올립니다. <b>사용자 화면은 그대로</b>이고, 후보를 골라야 바뀝니다.
      </div>
      <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
        <input value={id} onChange={(e) => setId(e.target.value.replace(/\D/g, ''))} placeholder="펫 번호" style={{ ...INPUT, marginTop: 0, width: 90, flex: '0 0 90px' }} />
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="사유(선택)" maxLength={200} style={{ ...INPUT, marginTop: 0, flex: 1 }} />
        <button type="button" data-action="flag" disabled={busy || !id} style={BTN} onClick={() => onFlag(Number(id), reason)}>올리기</button>
      </div>
    </section>
  );
}

const PILL: React.CSSProperties = {
  display: 'inline-block',
  marginLeft: 6,
  padding: '1px 7px',
  fontSize: 11,
  fontWeight: 700,
  borderRadius: 999,
  border: '1px solid rgba(127,127,127,0.4)',
  verticalAlign: 'middle',
};
const CAND: React.CSSProperties = { border: '1px solid rgba(127,127,127,0.30)', borderRadius: 8, padding: 8, minWidth: 0 };
const EMPTY: React.CSSProperties = {
  marginTop: 6,
  padding: '18px 10px',
  textAlign: 'center',
  fontSize: 13,
  borderRadius: 8,
  border: '1px dashed rgba(127,127,127,0.4)',
  color: 'var(--muted, #7a7a7a)',
};
