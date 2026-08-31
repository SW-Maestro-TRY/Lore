"use client";

import { useEffect, useRef } from "react";
import { mountEditor } from "../../lib/editorCore";

/* 편집실 — haeun/landing/web 의 editor.html/editor.css/editor.js 를 옮겼다.
 * "샘플 보기"(원본에서 `?run=` 없이 여는 경로)만 옮겼다 — 실제 작품 열기·
 * 다시 그리기·서버 저장·이미지로 뽑기는 전부 백엔드가 있어야 뜻이 있어서
 * lib/editorCore.ts 에 그 코드 자체가 없다(자세한 내용은 그 파일 머리
 * 주석 참고).
 *
 * 이 컴포넌트는 원본 markup 을 그대로 옮긴 뼈대만 그린다 — 장 카드·도구
 * 팔레트·말풍선처럼 상태에 따라 계속 다시 그려야 하는 부분은 mountEditor()
 * 가 DOM 을 직접 만들고 채운다(mascotPlay.ts 와 같은 이유 — 드래그·크기
 * 조절·회전은 포인터 이벤트를 원본과 똑같이 다뤄야 정확히 같게 움직인다).
 *
 * 원본은 이 화면 하나가 통째로 독립 페이지라 자기 머리(.ed-top)가 곧
 * 맨 위다 — 다른 화면의 공용 TopBar 가 없다. WebtoonPage 도 이 화면일
 * 때는 TopBar 를 안 그린다. */
export default function Editor({ onHome }: { onHome: () => void }) {
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!rootRef.current) return;
    const dispose = mountEditor(rootRef.current);
    return () => dispose();
  }, []);

  return (
    <div className="ed" ref={rootRef}>
      <header className="ed-top">
        <div className="ed-top-row">
          <button type="button" className="brand" onClick={onHome} style={{ background: "none", border: "none", cursor: "pointer" }}>
            <span className="brand-mark" aria-hidden="true">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/static/lou/logo-2-default.png" alt="" width={30} height={30} />
            </span>
            <span className="brand-name">LORE</span>
            <span className="brand-sub">편집실</span>
          </button>
          <div className="ed-chips">
            <span className="mock-badge" title="샘플입니다. 크레딧과 다시 그리기는 흉내만 냅니다.">샘플</span>
            <div className="credit" id="creditBox">
              <span className="credit-icon">◈</span>
              <span className="credit-num" id="creditNum">1,240</span>
              <span className="credit-unit">크레딧</span>
              <button type="button" className="credit-more" id="ledgerBtn">내역</button>
            </div>
          </div>
          <div className="ed-top-right">
            <button type="button" className="dock-open" id="dockOpen" aria-controls="edDock" aria-expanded="false">
              <span className="dock-open-icon" aria-hidden="true">☰</span>
              <span className="dock-open-label">도구</span>
            </button>
            <button type="button" className="btn btn-quiet btn-sm" id="scriptBtn">대사</button>
            <button type="button" className="btn btn-quiet btn-sm" id="saveBtn">저장</button>
            <button type="button" className="btn btn-primary btn-sm" id="bakeBtn">이미지로 뽑기</button>
          </div>
        </div>

        <div className="ed-strip">
          <button type="button" className="ed-works-toggle" id="worksToggle" aria-expanded="false" aria-controls="edWorks">
            <span aria-hidden="true">☰</span> 작품
          </button>
          <div className="ed-title">
            <b id="edTitle">—</b>
            <span id="edMeta" />
          </div>
        </div>

        <div className="bake-result" id="bakeResult" hidden />
      </header>

      <div className="ed-body">
        <aside className="ed-works" id="edWorks">
          <p className="ed-works-head">내 작품</p>
          <div id="worksList" />
        </aside>

        <main className="ed-stage" id="stageCol">
          <div className="ed-stage-head">
            <p className="eyebrow" id="edGenre" />
            <h1 id="edEpisode" data-title-edit tabIndex={0} title="눌러서 제목을 고칩니다">1화</h1>
            <p className="ed-logline" id="edLogline" />
            <label className="mini-toggle">
              <input type="checkbox" id="showOverlay" defaultChecked />
              <span>내가 얹은 것 보기</span>
            </label>
          </div>

          <div className="ep-tabs" id="edEpTabs" hidden />

          <div id="scenes" />

          <p className="ed-foot-note" id="edFootNote">여기까지가 1화입니다.</p>
        </main>

        <div className="dock-scrim" id="dockScrim" hidden />

        <aside className="ed-dock" id="edDock" aria-label="말풍선 · 스티커 · 효과음">
          <div className="dock-bar">
            <div className="dock-tabs" id="dockTabs">
              <button type="button" className="dock-tab is-on" data-tab="bubble">말풍선</button>
              <button type="button" className="dock-tab" data-tab="sticker">스티커</button>
              <button type="button" className="dock-tab" data-tab="sfx">효과음</button>
            </div>
            <button type="button" className="dock-fold" id="dockFold" aria-expanded="false" aria-controls="dockBody" aria-label="도구 닫기" />
          </div>

          <div className="dock-body" id="dockBody">
            <p className="dock-hint" id="dockHint">
              누르면 <b id="activeSceneLabel">1번째 장</b>에 올라갑니다 — 끌어서 옮기세요.
            </p>

            <div className="dock-grid" id="dockGrid" />

            <div className="dock-ledger" id="dockLedger" hidden>
              <div className="dock-props-head">
                <b>크레딧 사용 내역</b>
                <button type="button" className="icon-btn" id="ledgerClose" aria-label="닫기">✕</button>
              </div>
              <ul id="ledgerList">
                <li className="ledger-empty">아직 쓴 크레딧이 없습니다.</li>
              </ul>
            </div>
          </div>
        </aside>
      </div>

      <div className="ask" id="regenAsk" hidden>
        <div className="ask-box" role="dialog" aria-modal="true" aria-labelledby="regenAskTitle">
          <h2 id="regenAskTitle">다시 그리기</h2>
          <p className="ask-sub" id="regenAskSub" />

          <p className="fb-lead">
            무엇이 마음에 안 드나요? <small>안 골라도 됩니다</small>
          </p>
          <div className="fb-tags" id="regenAskTags" />

          <label className="field">
            <span>
              더 하고 싶은 말 <small>비워도 됩니다</small>
            </span>
            <textarea id="regenAskText" rows={3} maxLength={500} placeholder="예: 표정을 더 밝게 / 배경을 밤으로" />
          </label>

          <label className="check-line">
            <input type="checkbox" id="regenAskTextless" />
            <span>
              말풍선 없이 그림만 <small>대사는 나중에 얹으세요</small>
            </span>
          </label>

          <p className="ask-warn">실제로 다시 그립니다 — 1~2분과 생성 비용이 듭니다.</p>

          <div className="ask-actions">
            <button type="button" className="btn btn-quiet" id="regenAskCancel">취소</button>
            <button type="button" className="btn btn-primary" id="regenAskGo">다시 그리기</button>
          </div>
        </div>
      </div>

      <div className="toast" id="toast" hidden />
      <div className="fly" id="fly" hidden />

      <aside className="script-panel" id="scriptPanel" hidden>
        <div className="script-head">
          <b>대사 스크립트</b>
          <button type="button" className="icon-btn" id="scriptClose" aria-label="닫기">✕</button>
        </div>
        <div className="script-body" id="scriptBody" />
      </aside>
    </div>
  );
}
