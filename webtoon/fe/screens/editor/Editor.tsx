"use client";

import { useEffect, useState } from "react";
import { allowanceLine, pageUrl, readAllowance, readResult, type RunResult } from "../../lib/api";
import { mountEditor } from "../../lib/editorCore";
import type { Go } from "../../lib/nav";
import { IconClose, IconEdit, IconMenu } from "../../ui/Icons";
import ShareMenu from "../result/ShareMenu";
import "./Editor.css";

/* 편집실 — 캔버스 Editor(PC). 폰은 같은 구성을 세로로 무너뜨린다.
 *
 * 얹기·굽기·다시 그리기·되돌리기는 lib/editorCore.ts 의 mountEditor 가 **그대로**
 * 한다. 엔진은 아래 id·클래스를 document 에서 찾아 채우므로, 이 뼈대는 엔진이
 * 기대하는 대로 두고 겉모습만 Editor.css 로 캔버스에 맞춘다.
 *
 * 엔진이 직접 그리는 것: #worksList(작품 목록) · #scenes(장 카드 전부, 세로) ·
 * #edEpTabs(회차) · #dockGrid(말풍선·스티커·효과음) · #regenAsk(다시 그리기 창) ·
 * #bakeResult(구운 결과) · 지난 판(.page-versions) · 여백 막대(.scene-gap).
 * React 가 더 그리는 것: 왼쪽 페이지 썸네일, 오른쪽 「다시 그리기」 단(활성 장의
 * 장면 한 줄 + 단추 — 누르면 엔진의 그 장 다시 그리기 단추를 대신 누른다),
 * 크레딧 딱지, 공유, 「완성본으로」. */
export default function Editor({ runId, go }: { runId: string; go: Go }) {
  const episode = 1;

  useEffect(() => {
    /* 주소에 회차 칸이 없다(lib/nav.ts) — 다른 작품으로 건너갈 때는 1화로 연다. */
    const dispose = mountEditor({ runId, episode }, (r) => go("editor", { run: r }));
    return () => dispose();
  }, [runId, go]);

  /* 페이지 썸네일과 장면 한 줄은 완성본 API 에서 받는다 — 엔진은 자기 데이터를
     밖으로 내주지 않는다. */
  const [info, setInfo] = useState<RunResult | null>(null);
  useEffect(() => {
    let alive = true;
    readResult(runId).then((r) => { if (alive) setInfo(r); }).catch(() => {});
    return () => { alive = false; };
  }, [runId]);

  const [credit, setCredit] = useState("");
  useEffect(() => {
    let alive = true;
    readAllowance().then((a) => { if (alive) setCredit(allowanceLine(a)); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  /* 지금 고른 장 — 엔진이 #activeSceneLabel 에 「N번째 장」이라고 적는 것을 읽는다. */
  const [active, setActive] = useState(1);
  useEffect(() => {
    const el = document.getElementById("activeSceneLabel");
    if (!el) return;
    const read = () => {
      const n = parseInt(el.textContent || "", 10);
      if (Number.isFinite(n) && n > 0) setActive(n);
    };
    read();
    const mo = new MutationObserver(read);
    mo.observe(el, { childList: true, characterData: true, subtree: true });
    return () => mo.disconnect();
  }, [runId]);

  const pickScene = (no: number) => {
    const el = document.getElementById(`scene-${no}`);
    if (!el) return;
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    el.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
  };

  const regenActive = () => {
    const btn = document.querySelector<HTMLButtonElement>(`#scene-${active} [data-act="regen"]`);
    btn?.click();
  };

  const activeNote = info?.pages.find((p) => p.no === active)?.caption || "";

  return (
    <div className="ed wt-ed">
      <header className="ed-top wt-ed-top">
        <div className="ed-strip wt-ed-strip">
          <button type="button" className="ed-works-toggle chip wt-ed-workstoggle" id="worksToggle"
                  aria-expanded="false" aria-controls="edWorks">
            <IconMenu size={16} /> 작품
          </button>

          <div className="ed-title wt-ed-title">
            <b id="edTitle" hidden />
            <div className="title-row wt-ed-titlerow">
              <h1 id="edEpisode" data-title-edit tabIndex={0} title="눌러서 제목을 고칩니다">—</h1>
              <button type="button" className="wt-ed-titleedit" id="edTitleEditBtn" title="제목 고치기">
                <IconEdit size={13} /> 제목 고치기
              </button>
            </div>
            <span id="edMeta" className="dim" />
          </div>

          <div className="ed-chips wt-ed-chips">
            <span className="mock-badge" title="작품을 고르기 전까지는 샘플입니다.">샘플</span>
            <div className="credit" id="creditBox">
              <span className="credit-icon">◈</span>
              <span className="credit-num" id="creditNum">1,240</span>
              <span className="credit-unit">크레딧</span>
              <button type="button" className="credit-more" id="ledgerBtn">내역</button>
            </div>
            {credit && <span className="chip wt-ed-credit">◈ {credit}</span>}
            <label className="mini-toggle wt-ed-overlaytoggle">
              <input type="checkbox" id="showOverlay" defaultChecked aria-label="내가 얹은 것 보기" />
              내가 얹은 것 보기
            </label>
            <p className="ed-saved wt-ed-saved" id="savedNote" data-state="idle" />
            <ShareMenu runId={runId} episode={episode} className="icon-btn" iconOnly />
            <button type="button" className="btn btn-p wt-ed-bake" id="bakeBtn">이미지로 뽑기</button>
            <button type="button" className="btn btn-w wt-ed-done" onClick={() => go("result", { run: runId })}>완성본으로</button>
          </div>
        </div>
        <div className="bake-result" id="bakeResult" hidden />
      </header>

      <div className="ed-body wt-ed-body">
        <aside className="ed-works wt-ed-left" id="edWorks">
          <b className="wt-ed-lefthead">내 작품</b>
          <div id="worksList" className="wt-ed-workslist" />
          <div className="wt-ed-pages">
            <b className="wt-ed-lefthead">페이지</b>
            <div className="wt-ed-pagegrid">
              {info ? info.pages.map((p) => (
                <button key={p.no} type="button" className={`wt-ed-thumb${p.no === active ? " on" : ""}`}
                        onClick={() => pickScene(p.no)} aria-label={`${p.no}번째 장`} aria-current={p.no === active}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={pageUrl(runId, p.no, 160, false, info.example)} alt="" loading="lazy" />
                  <span>{p.no}</span>
                </button>
              )) : [1, 2, 3].map((i) => <div key={i} className="skeleton wt-ed-thumb" />)}
            </div>
          </div>
        </aside>

        <main className="ed-stage wt-ed-stage" id="stageCol">
          <div className="ed-stage-head wt-ed-stagehead">
            <p className="eyebrow wt-ed-genre" id="edGenre" />
            <p className="ed-logline wt-ed-logline" id="edLogline" />
          </div>
          <div className="ep-tabs wt-ed-eptabs" id="edEpTabs" hidden />
          <div id="scenes" className="wt-ed-scenes" />
          <p className="ed-foot-note wt-ed-footnote" id="edFootNote" />
        </main>

        <button type="button" className="dock-open wt-ed-dockopen" id="dockOpen"
                aria-controls="edDock" aria-expanded="false">
          <IconMenu size={18} /> 도구
        </button>
        <div className="dock-scrim" id="dockScrim" hidden />

        <aside className="ed-dock wt-ed-dock" id="edDock" aria-label="말풍선 · 스티커 · 효과음">
          <div className="dock-handle" id="dockHandle" aria-hidden="true" />
          <div className="dock-bar wt-ed-dockbar">
            <div className="dock-tabs wt-ed-docktabs" id="dockTabs">
              <button type="button" className="dock-tab chip is-on" data-tab="bubble">말풍선</button>
              <button type="button" className="dock-tab chip" data-tab="sticker">스티커</button>
              <button type="button" className="dock-tab chip" data-tab="sfx">효과음</button>
            </div>
            <button type="button" className="dock-fold icon-btn wt-ed-dockfold" id="dockFold"
                    aria-expanded="false" aria-controls="dockBody" aria-label="도구 닫기"><IconClose size={14} /></button>
          </div>

          <div className="dock-body wt-ed-dockbody" id="dockBody">
            <p className="dock-hint wt-ed-dockhint" id="dockHint">
              누르면 <b id="activeSceneLabel">1번째 장</b>에 올라갑니다 — 끌어서 옮기고, 한 번 더 누르면 글을 고칩니다.
            </p>
            <div className="dock-grid wt-ed-dockgrid" id="dockGrid" />
          </div>

          <div className="dock-ledger wt-ed-ledger" id="dockLedger" hidden>
            <div className="dock-props-head">
              <b>크레딧 사용 내역</b>
              <button type="button" className="icon-btn" id="ledgerClose" aria-label="닫기"><IconClose size={14} /></button>
            </div>
            <ul id="ledgerList">
              <li className="ledger-empty">아직 쓴 크레딧이 없습니다.</li>
            </ul>
          </div>

          <div className="wt-ed-regen">
            <b>다시 그리기</b>
            {activeNote && <span className="dim">이 장의 장면 · {activeNote}</span>}
            <button type="button" className="btn btn-p wt-ed-regenbtn" onClick={regenActive}>
              이 컷 다시 그리기 · 3크레딧
            </button>
          </div>
        </aside>
      </div>

      {/* 다시 그리기 확인 창 — 항목(칩)은 엔진이 /config 에서 받아 채운다. */}
      <div className="ask wt-ed-ask" id="regenAsk" hidden>
        <div className="ask-box" role="dialog" aria-modal="true" aria-labelledby="regenAskTitle">
          <h2 id="regenAskTitle">다시 그리기</h2>
          <p className="ask-sub" id="regenAskSub" />
          <p className="ask-scene" id="regenAskScene" hidden />
          <p className="fb-lead">무엇이 마음에 안 드나요? <small>안 골라도 됩니다</small></p>
          <div className="fb-tags" id="regenAskTags" />
          <label className="wt-ed-askfield">
            <span>더 하고 싶은 말 <small>비워도 됩니다</small></span>
            <textarea id="regenAskText" rows={3} maxLength={500} className="field"
                      placeholder="더 하고 싶은 말 · 예: 우산을 들고 있게" />
          </label>
          <label className="check-line">
            <input type="checkbox" id="regenAskTextless" />
            <span>말풍선 없이 그림만</span>
          </label>
          <p className="ask-warn">실제로 다시 그립니다 — 1~2분과 생성 비용이 듭니다.</p>
          <div className="ask-actions">
            <button type="button" className="btn btn-w" id="regenAskCancel">취소</button>
            <button type="button" className="btn btn-p" id="regenAskGo">이 컷 다시 그리기</button>
          </div>
        </div>
      </div>

      <div className="toast wt-ed-toast" id="toast" hidden />
      <div className="fly wt-ed-fly" id="fly" hidden />
    </div>
  );
}
