'use client';

// 관리자 화면 묶음 — 탭 둘: 움짤 검수(기존) · 2층 실패·대기(#696).
// 고른 탭은 주소 해시(#layer)로 남긴다 — 새로고침해도 보던 탭에 머문다.

import { useEffect, useState } from 'react';
import AdminReviewScreen from './AdminReviewScreen';
import AdminLayerTab from './AdminLayerTab';

type Tab = 'review' | 'layer';

export default function AdminScreen() {
  const [tab, setTab] = useState<Tab>('review');
  useEffect(() => {
    if (window.location.hash === '#layer') setTab('layer');
  }, []);
  const go = (t: Tab) => {
    setTab(t);
    window.history.replaceState(null, '', t === 'layer' ? '#layer' : '#');
  };
  return (
    <div style={{ maxWidth: 880, margin: '0 auto', padding: '8px 12px 0' }}>
      <nav data-part="admin-tabs" style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        {([['review', '움짤 검수'], ['layer', '2층 실패·대기']] as const).map(([t, label]) => (
          <button
            key={t}
            type="button"
            data-action="admin-tab"
            data-tab={t}
            data-active={tab === t}
            onClick={() => go(t)}
            style={{
              padding: '6px 12px', fontSize: 13, fontWeight: 700, borderRadius: 6, cursor: 'pointer',
              border: '1px solid rgba(127,127,127,0.35)', color: 'inherit',
              background: tab === t ? 'rgba(127,127,127,0.18)' : 'transparent',
            }}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === 'review' ? <AdminReviewScreen /> : <AdminLayerTab />}
    </div>
  );
}
