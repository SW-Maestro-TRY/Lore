'use client';

// 관리자 화면 탭 묶음. 선택한 탭은 주소 해시에 남긴다.
//
// ★ 기본은 "작업"(실패 추적·재시도, 설계 spec-zzal-admin-failures-retry-20261027). 기존 탭(움짤 검수 · 1·2층 복구 ·
//   맞춤8종)은 그대로 둔다 — 해시(#review · #layer · #custom)로 바로 들어가는 길도 그대로다.

import { useEffect, useState, type ReactNode } from 'react';
import AdminReviewScreen from './AdminReviewScreen';
import AdminLayerTab from './AdminLayerTab';
import AdminCustomBundlesTab from './AdminCustomBundlesTab';
import AdminWorkTab from './AdminWorkTab';
import AdminInquiryTab, { InquiryTabLabel } from './AdminInquiryTab';

type Tab = 'work' | 'review' | 'layer' | 'custom' | 'inquiry';

const TABS: readonly (readonly [Tab, ReactNode])[] = [
  ['work', '작업'],
  ['review', '움짤 검수'],
  ['layer', '1·2층 복구'],
  ['custom', '맞춤8종'],
  ['inquiry', <InquiryTabLabel key="inquiry" />],
];

function tabOf(hash: string): Tab {
  if (hash === '#review') return 'review';
  if (hash === '#layer') return 'layer';
  if (hash === '#custom' || hash === '#custom-failures') return 'custom';
  if (hash === '#inquiry') return 'inquiry';
  return 'work';
}

export default function AdminScreen() {
  const [tab, setTab] = useState<Tab>('work');
  useEffect(() => {
    const readHash = () => setTab(tabOf(window.location.hash));
    readHash();
    window.addEventListener('hashchange', readHash);
    return () => window.removeEventListener('hashchange', readHash);
  }, []);
  const go = (t: Tab) => {
    setTab(t);
    window.history.replaceState(null, '', t === 'work' ? '#' : `#${t}`);
  };
  return (
    <div style={{ maxWidth: 880, margin: '0 auto', padding: '8px 12px 0' }}>
      <nav data-part="admin-tabs" style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
        {TABS.map(([t, label]) => (
          <button
            key={t}
            type="button"
            data-action="admin-tab"
            data-tab={t}
            data-active={tab === t}
            onClick={() => go(t)}
            style={{
              minHeight: 44, padding: '6px 12px', fontSize: 13, fontWeight: 700, borderRadius: 6, cursor: 'pointer',
              border: '1px solid rgba(127,127,127,0.35)', color: 'inherit',
              background: tab === t ? 'rgba(127,127,127,0.18)' : 'transparent',
            }}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === 'work' ? <AdminWorkTab />
        : tab === 'review' ? <AdminReviewScreen />
          : tab === 'layer' ? <AdminLayerTab />
            : tab === 'inquiry' ? <AdminInquiryTab /> : <AdminCustomBundlesTab />}
    </div>
  );
}
