'use client';

import { useEffect, useRef, useState } from 'react';
import { ApiError } from '@common/api/client';
import { Picture, CARD, STAGE, GRID, THUMB, THUMB_ON, THUMB_IMG, THUMB_CAP, BTN } from './AdminReviewScreen';
import { fetchCustomBundles, type CustomBundle, type CustomBundleFilter } from './customBundleApi';

type Shot = 'motion' | 'grid' | 'source' | 'sheet';
const LABEL: Record<Shot, string> = { motion: '완성본', grid: '격자', source: '원본', sheet: '시트' };
const WRAP = { overflowWrap: 'anywhere' as const, minWidth: 0 };
const button = { ...BTN, minHeight: 44 };
function explain(error: unknown) {
  if (error instanceof ApiError) {
    if (error.status === 403) return '관리자 권한이 필요해요. 계정 권한을 확인해 주세요.';
    if (error.status === 401) return '로그인이 필요해요. 로그인한 뒤 다시 불러와 주세요.';
    if (error.status === 404) return '조회 기능이 꺼져 있거나 주소를 찾을 수 없어요. 관리자 설정을 확인해 주세요.';
  }
  return '맞춤8종을 불러오지 못했어요. 잠시 후 다시 불러와 주세요.';
}
function Image({ imageKey, label, thumbnail = false }: { imageKey: string; label: string; thumbnail?: boolean }) {
  const [failed, setFailed] = useState(false);
  return <div onErrorCapture={() => setFailed(true)} style={{ width: '100%', ...WRAP }}>
    {failed ? <span role="status" style={{ display: 'block', padding: 10, fontSize: 12, lineHeight: 1.5 }}>이미지를 불러오지 못했어요.</span>
      : <Picture src={imageKey} alt={label} style={thumbnail ? THUMB_IMG : { display: 'block', width: '100%', maxHeight: 480, objectFit: 'contain' }} />}
  </div>;
}
function BundleViewer({ item }: { item: CustomBundle }) {
  const motions = [...item.motions].sort((a, b) => a.order - b.order);
  const [order, setOrder] = useState(motions.find(m => m.imageKey)?.order ?? motions[0]?.order);
  const [shot, setShot] = useState<Shot>('motion');
  const motion = motions.find(m => m.order === order) ?? motions[0];
  const shots: Array<[Shot, string | null]> = [['motion', motion?.imageKey ?? null], ['grid', item.gridKey], ['source', item.sourceImageKey], ['sheet', item.sheetKey]];
  const available = shots.filter((entry): entry is [Shot, string] => Boolean(entry[1]));
  const selected = shots.find(([kind, key]) => kind === shot && key) ?? available[0];
  const date = item.failedAt ? new Date(item.failedAt) : null;
  return <section data-part="custom-bundle" data-job={item.jobId} style={{ ...CARD, ...WRAP }}>
    <h3 style={{ margin: '0 0 6px', fontSize: 16 }}>작업 #{item.jobId} · 캐릭터 #{item.petId}</h3>
    <p style={{ margin: '4px 0', fontSize: 13 }}>상태 {item.state}{item.stage ? ` · ${item.stage}` : ''}</p>
    <p style={{ margin: '4px 0', fontSize: 12 }}>{item.setId ? `세트 ${item.setId}` : '세트 준비 중'}</p>
    {item.summary && <p data-part="custom-summary" style={{ margin: '8px 0', lineHeight: 1.6 }}>{item.summary}</p>}
    {item.errorCode && <p style={{ margin: '4px 0', fontSize: 12 }}>오류 {item.errorCode}</p>}
    {date && <p style={{ margin: '4px 0', fontSize: 12 }}>실패 시각 {Number.isNaN(date.getTime()) ? item.failedAt : date.toLocaleString('ko-KR')}</p>}
    <h4 data-part="custom-motion-title" style={{ margin: '14px 0 4px', fontSize: 15 }}>{motion ? `${motion.order}. ${motion.title}` : '보존된 동작 정보가 없어요.'}</h4>
    {motion && <p style={{ margin: '4px 0', fontSize: 12 }}>{motion.imageKey ? (motion.published ? '등록된 완성본' : '보존된 부분 결과') : '완성본 없음'}{motion.review ? ` · 검수 ${motion.review}` : ''}{motion.autoCheck ? ` · 자동 점검 ${motion.autoCheck}` : ''}</p>}
    <div data-part="custom-stage" data-shot={selected?.[0] ?? 'none'} style={STAGE}>
      {selected?.[1] ? <Image key={selected[1]} imageKey={selected[1]} label={`${motion?.title ?? '맞춤8종'} ${LABEL[selected[0]]}`} />
        : <p role="status" style={{ padding: 16, lineHeight: 1.6 }}>보존된 이미지가 없어요. 아래 작업 정보로 확인해 주세요.</p>}
    </div>
    {available.length > 0 && <div data-part="custom-shots" style={{ ...GRID, gridTemplateColumns: `repeat(${available.length},minmax(0,1fr))` }}>
      {available.map(([kind, key]) => <button key={kind} type="button" data-action="custom-shot" data-shot={kind} aria-pressed={selected?.[0] === kind}
        onClick={() => setShot(kind)} style={{ ...THUMB, minHeight: 44, ...(selected?.[0] === kind ? THUMB_ON : {}) }}>
        <Image key={key} imageKey={key} label={LABEL[kind]} thumbnail /><span style={THUMB_CAP}>{LABEL[kind]}</span>
      </button>)}
    </div>}
    <div data-part="custom-motions" aria-label="맞춤 동작 선택" style={{ ...GRID, gridTemplateColumns: 'repeat(auto-fit,minmax(56px,1fr))', marginTop: 14 }}>
      {motions.map(m => <button key={m.order} type="button" data-action="custom-motion" data-order={m.order} aria-pressed={motion?.order === m.order}
        onClick={() => { setOrder(m.order); setShot('motion'); }} style={{ ...THUMB, minHeight: 44, ...WRAP, ...(motion?.order === m.order ? THUMB_ON : {}) }}>
        {m.imageKey ? <Image key={m.imageKey} imageKey={m.imageKey} label={m.title} thumbnail /> : <span style={{ ...THUMB_IMG, display: 'grid', placeItems: 'center', fontSize: 11, lineHeight: 1.4 }}>완성본 없음</span>}
        <span style={{ ...THUMB_CAP, ...WRAP }}>{m.order}. {m.title}</span>
      </button>)}
    </div>
    <details style={{ marginTop: 12 }}>
      <summary style={{ cursor: 'pointer', minHeight: 44, display: 'list-item', alignContent: 'center' }}>후처리 진단</summary>
      {item.diagnostics?.available ? <>{(['stdoutExcerpt', 'stderrExcerpt'] as const).map(key => item.diagnostics[key] && <pre key={key} style={{ whiteSpace: 'pre-wrap', ...WRAP, fontSize: 12, lineHeight: 1.6 }}>{item.diagnostics[key]}</pre>)}{item.diagnostics.truncated && <p>긴 진단 내용은 일부만 표시해요.</p>}</> : <p>보존된 진단 내용이 없어요.</p>}
    </details>
  </section>;
}
export default function AdminCustomBundlesTab() {
  const [filter, setFilter] = useState<CustomBundleFilter>('failed');
  const [items, setItems] = useState<CustomBundle[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestRef = useRef<{ controller: AbortController; seq: number } | null>(null);
  const sequence = useRef(0);
  async function load(status: CustomBundleFilter, beforeId?: number) {
    requestRef.current?.controller.abort();
    const controller = new AbortController(), seq = ++sequence.current;
    requestRef.current = { controller, seq };
    setLoading(true); setError(null);
    if (beforeId == null) { setItems([]); setCursor(null); }
    try {
      const page = await fetchCustomBundles(status, beforeId, controller.signal);
      if (controller.signal.aborted || seq !== sequence.current) return;
      setItems(previous => beforeId == null ? page.items : [...previous, ...page.items.filter(item => !previous.some(old => old.jobId === item.jobId))]);
      setCursor(page.nextCursor);
    } catch (e) { if (!controller.signal.aborted && seq === sequence.current) setError(explain(e)); }
    finally { if (!controller.signal.aborted && seq === sequence.current) setLoading(false); }
  }
  useEffect(() => { void load(filter); return () => requestRef.current?.controller.abort(); }, [filter]);
  return <div data-part="admin-custom-bundles" style={WRAP}>
    <h2 style={{ fontSize: 18, margin: '8px 0' }}>맞춤8종</h2>
    <p style={{ fontSize: 13, lineHeight: 1.6 }}>확인이 필요한 후처리·자산 점검 실패와 생성된 세트를 읽기 전용으로 확인해요. 재생성·승인·출석 지급은 실행하지 않아요.</p>
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 14 }}>
      {([['failed', '확인 필요'], ['all', '전체']] as const).map(([value, label]) => <button key={value} type="button" data-action="custom-filter" data-filter={value} aria-pressed={filter === value} onClick={() => setFilter(value)} style={{ ...button, ...(filter === value ? THUMB_ON : {}) }}>{label}</button>)}
      <button type="button" data-action="custom-reload" disabled={loading} onClick={() => void load(filter)} style={button}>다시 불러오기</button>
    </div>
    {loading && <p role="status">불러오는 중이에요.</p>}
    {error && <p role="alert" style={{ lineHeight: 1.6 }}>{error}</p>}
    {!loading && !error && items.length === 0 && <p role="status">{filter === 'failed' ? '확인이 필요한 맞춤8종이 없어요.' : '아직 맞춤8종 작업이 없어요.'}</p>}
    {items.map(item => <BundleViewer key={item.jobId} item={item} />)}
    {cursor != null && <button type="button" data-action="custom-more" disabled={loading} onClick={() => void load(filter, cursor)} style={button}>이전 작업 더 보기</button>}
  </div>;
}
