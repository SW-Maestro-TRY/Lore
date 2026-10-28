'use client';

// 기존 검수·복구와 읽기 전용 맞춤8종 뷰어. 선택한 탭은 주소 해시에 남긴다.

import { useEffect, useState } from 'react';
import AdminReviewScreen from './AdminReviewScreen';
import AdminLayerTab from './AdminLayerTab';
import AdminCustomBundlesTab from './AdminCustomBundlesTab';
import AdminInquiryTab, { InquiryTabLabel } from './AdminInquiryTab';

type Tab = 'review' | 'layer' | 'custom' | 'inquiry';

export default function AdminScreen() {
  const [tab, setTab] = useState<Tab>('review');
  useEffect(() => {
    const readHash = () => setTab(window.location.hash === '#layer' ? 'layer' : window.location.hash === '#inquiry' ? 'inquiry' : ['#custom', '#custom-failures'].includes(window.location.hash) ? 'custom' : 'review');
    readHash();
    window.addEventListener('hashchange', readHash);
    return () => window.removeEventListener('hashchange', readHash);
  }, []);
  const go = (t: Tab) => {
    setTab(t);
    window.history.replaceState(null, '', t === 'review' ? '#' : `#${t}`);
  };
  return (
    <div style={{ maxWidth: 880, margin: '0 auto', padding: '8px 12px 0' }}>
      <nav data-part="admin-tabs" style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
        {([['review', '움짤 검수'], ['layer', '1·2층 복구'], ['custom', '맞춤8종'], ['inquiry', <InquiryTabLabel key="inquiry" />]] as const).map(([t, label]) => (
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
      {tab === 'review' ? <AdminReviewScreen /> : tab === 'layer' ? <AdminLayerTab /> : tab === 'inquiry' ? <AdminInquiryTab /> : <AdminCustomBundlesTab />}
    </div>
  );
}
