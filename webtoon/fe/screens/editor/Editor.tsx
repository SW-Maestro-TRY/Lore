"use client";

import { useEffect, useState } from "react";
import { creditBalance, creditHistory, type CreditLine } from "@common/api/credits";
import { pageUrl, readAllowance, readResult, type RunResult } from "../../lib/api";
import { mountEditor, setEditorTranslator } from "../../lib/editorCore";
import { useLang } from "../../lib/i18n";
import { track } from "../../lib/track";
import type { Go } from "../../lib/nav";
import { IconClose, IconEdit, IconMenu } from "../../ui/Icons";
import ShareMenu from "../result/ShareMenu";
import "./i18n";
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

export default function Editor({ runId, go, authStatus = "loading" }:
  { runId: string; go: Go; authStatus?: string }) {
  /* 로그인 확인이 **끝난 뒤에** 셋 중 하나로 간다.
     - loading  : 아직 모른다 → 아무것도 안 그린다. 여기서 엔진을 올리면
                  곧 잠금 화면으로 바뀌며 엔진이 잡고 있던 노드가 통째로
                  뜯겨 나가 터진다(editorCore 의 render 가 null 을 잡는다).
                  반대로 잠금 화면을 먼저 그리면 로그인한 사람에게 번쩍인다.
     - anonymous: 막는다.
     - 그 밖("authenticated" · "unknown") : 연다. 서버가 한 번 더 본다
                  (RunController.mustOwn). */
  const locked = authStatus === "anonymous";
  const authenticated = authStatus !== "loading" && !locked;
  const { lang, t } = useLang();
  const episode = 1;

  useEffect(() => {
    if (!authenticated) return;                 // 잠긴 화면에서는 엔진을 안 올린다
    /* 주소에 회차 칸이 없다(lib/nav.ts) — 다른 작품으로 건너갈 때는 1화로 연다.
       언어(lang)가 바뀌면 엔진을 다시 올린다 — 엔진은 그린 글을 스스로 갱신하지 않는다. */
    setEditorTranslator(t);
    const dispose = mountEditor({ runId, episode }, (r) => go("editor", { run: r }));
    return () => { dispose(); setEditorTranslator(null); };
  }, [runId, go, lang, t, authenticated]);

  /* 페이지 썸네일과 장면 한 줄은 완성본 API 에서 받는다 — 엔진은 자기 데이터를
     밖으로 내주지 않는다. */
  const [info, setInfo] = useState<RunResult | null>(null);
  useEffect(() => {
    let alive = true;
    readResult(runId).then((r) => { if (alive) setInfo(r); }).catch(() => {});
    return () => { alive = false; };
  }, [runId]);

  /* 크레딧 잔액과 사용 내역은 **계정** 것이라 공용 모듈에서 읽는다
     (`@common/api/credits`). 예전에는 엔진이 1,240 에서 시작하는 값을 그 세션
     안에서만 깎아 보여 줬는데, 그건 목업이라 새로고침하면 되돌아갔다. */
  const [regenCost, setRegenCost] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    readAllowance().then((a) => { if (alive) setRegenCost(a.regen_cost ?? null); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  const [balance, setBalance] = useState<number | null>(null);
  const [ledger, setLedger] = useState<CreditLine[] | null>(null);
  const [ledgerOpen, setLedgerOpen] = useState(false);
  const [ledgerErr, setLedgerErr] = useState("");

  useEffect(() => {
    let alive = true;
    creditBalance().then((b) => { if (alive) setBalance(b.balance); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  /* 내역은 **펼칠 때** 받는다 — 편집실을 열 때마다 받으면 안 볼 사람에게도 한 번씩 묻는다. */
  useEffect(() => {
    if (!ledgerOpen) return;
    let alive = true;
    setLedgerErr("");
    creditHistory(20)
      .then((lines) => { if (alive) setLedger(lines); })
      .catch(() => { if (alive) setLedgerErr(t("내역을 불러오지 못했습니다.")); });
    return () => { alive = false; };
  }, [ledgerOpen, t]);

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

  /* 편집실은 로그인해야 쓴다 (서버도 같은 규칙 — RunController.mustOwn).
     게스트 브라우저 uid 는 같은 컴퓨터를 쓰는 사람끼리 겹치고 지우면 사라져서
     "고칠 권리"를 걸기에 약하다. 로그인하면 이 브라우저로 만든 작품이 그대로
     계정에 따라온다(POST /my/link). */
  /* 로그인 문턱을 본 사람 — 그다음 auth_done 이 오는지로 「로그인까지 갔나」를 센다(#413).
     ★ 아래 이른 return 들보다 위에 둔다. 훅은 매 렌더에 같은 순서로 불려야 한다. */
  useEffect(() => { if (locked) track("login_prompt", { where: "editor", run: runId }); }, [locked, runId]);

  if (authStatus === "loading") {
    return <div className="wt-wrap wt-page wt-ed-gate" aria-busy="true" />;
  }

  if (locked) {
    return (
      <div className="wt-wrap wt-page wt-ed-gate">
        <h2>{t("편집실은 로그인하고 쓸 수 있어요")}</h2>
        <p className="muted">{t("로그인하면 이 브라우저로 만든 작품도 그대로 따라옵니다. 위쪽 로그인 단추를 눌러 주세요.")}</p>
        <div className="wt-ed-gate-acts">
          <button type="button" className="btn btn-w" onClick={() => go("result", { run: runId })}>{t("완성본 보기")}</button>
          <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기")}</button>
        </div>
      </div>
    );
  }

  return (
    <div className="ed wt-ed">
      <header className="ed-top wt-ed-top">
        <div className="ed-strip wt-ed-strip">
          <button type="button" className="ed-works-toggle chip wt-ed-workstoggle" id="worksToggle"
                  aria-expanded="false" aria-controls="edWorks">
            <IconMenu size={16} /> {t("작품")}
          </button>

          <div className="ed-title wt-ed-title">
            <b id="edTitle" hidden />
            <div className="title-row wt-ed-titlerow">
              <h1 id="edEpisode" data-title-edit tabIndex={0} title={t("눌러서 제목을 고칩니다")}>—</h1>
              <button type="button" className="wt-ed-titleedit" id="edTitleEditBtn" title={t("제목 고치기")}>
                <IconEdit size={13} /> {t("제목 고치기")}
              </button>
            </div>
            <span id="edMeta" className="dim" />
          </div>

          <div className="ed-chips wt-ed-chips">
            <span className="chip wt-ed-credit">
              ◈ {balance == null ? "—" : balance.toLocaleString("ko-KR")} {t("크레딧")}
              <button type="button" className="wt-ed-ledgerbtn" aria-expanded={ledgerOpen}
                      onClick={() => setLedgerOpen((v) => !v)}>{t("내역")}</button>
            </span>
            <label className="mini-toggle wt-ed-overlaytoggle">
              <input type="checkbox" id="showOverlay" defaultChecked aria-label={t("내가 얹은 것 보기")} />
              {t("내가 얹은 것 보기")}
            </label>
            <p className="ed-saved wt-ed-saved" id="savedNote" data-state="idle" />
            <ShareMenu runId={runId} episode={episode} className="icon-btn" iconOnly />
            <button type="button" className="btn btn-p wt-ed-bake" id="bakeBtn">{t("이미지로 뽑기")}</button>
            <button type="button" className="btn btn-w wt-ed-done" onClick={() => go("result", { run: runId })}>{t("완성본으로")}</button>
          </div>
        </div>
        <div className="bake-result" id="bakeResult" hidden />
      </header>

      <div className="ed-body wt-ed-body">
        <aside className="ed-works wt-ed-left" id="edWorks">
          <b className="wt-ed-lefthead">{t("내 작품")}</b>
          <div id="worksList" className="wt-ed-workslist" />
          <div className="wt-ed-pages">
            <b className="wt-ed-lefthead">{t("페이지")}</b>
            <div className="wt-ed-pagegrid">
              {info ? info.pages.map((p) => (
                <button key={p.no} type="button" className={`wt-ed-thumb${p.no === active ? " on" : ""}`}
                        onClick={() => pickScene(p.no)} aria-label={t("{n}번째 장", { n: p.no })} aria-current={p.no === active}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={pageUrl(runId, p.no, 160)} alt="" loading="lazy" />
                  <span>{p.no}</span>
                </button>
              )) : [1, 2, 3].map((i) => <div key={i} className="skeleton wt-ed-thumb" />)}
            </div>
          </div>
        </aside>

        <main className="ed-stage wt-ed-stage" id="stageCol">
          <div className="ep-tabs wt-ed-eptabs" id="edEpTabs" hidden />
          <div id="scenes" className="wt-ed-scenes" />
          <p className="ed-foot-note wt-ed-footnote" id="edFootNote" />
        </main>

        <button type="button" className="dock-open wt-ed-dockopen" id="dockOpen"
                aria-controls="edDock" aria-expanded="false">
          <IconMenu size={18} /> {t("도구")}
        </button>
        <div className="dock-scrim" id="dockScrim" hidden />

        <aside className="ed-dock wt-ed-dock" id="edDock" aria-label={t("말풍선 · 스티커 · 효과음")}>
          <div className="dock-handle" id="dockHandle" aria-hidden="true" />
          <div className="dock-bar wt-ed-dockbar">
            <div className="dock-tabs wt-ed-docktabs" id="dockTabs">
              <button type="button" className="dock-tab chip is-on" data-tab="bubble">{t("말풍선")}</button>
              <button type="button" className="dock-tab chip" data-tab="sticker">{t("스티커")}</button>
              <button type="button" className="dock-tab chip" data-tab="sfx">{t("효과음")}</button>
            </div>
            <button type="button" className="dock-fold icon-btn wt-ed-dockfold" id="dockFold"
                    aria-expanded="false" aria-controls="dockBody" aria-label={t("도구 닫기")}><IconClose size={14} /></button>
          </div>

          <div className="dock-body wt-ed-dockbody" id="dockBody">
            {/* 엔진(editorCore)이 지금 고른 장 번호를 여기 적고, 위쪽 React 가 읽는다.
                보이는 글이 아니라 둘을 잇는 자리다 — 화면에는 안 그린다. */}
            <span id="activeSceneLabel" hidden />
            <div className="dock-grid wt-ed-dockgrid" id="dockGrid" />
          </div>

          <div className="dock-ledger wt-ed-ledger" hidden={!ledgerOpen}>
            <div className="dock-props-head">
              <b>{t("크레딧 사용 내역")}</b>
              <button type="button" className="icon-btn" aria-label={t("닫기")}
                      onClick={() => setLedgerOpen(false)}><IconClose size={14} /></button>
            </div>
            {ledgerErr ? (
              <p className="err">{ledgerErr}</p>
            ) : (
              <ul>
                {ledger == null ? (
                  <li className="ledger-empty">{t("불러오는 중…")}</li>
                ) : ledger.length === 0 ? (
                  <li className="ledger-empty">{t("아직 쓴 크레딧이 없습니다.")}</li>
                ) : ledger.map((x) => (
                  <li key={x.id}>
                    <span>{new Date(x.at).toLocaleDateString("ko-KR")} · {x.label}</span>
                    <b className={x.delta < 0 ? "" : "plus"}>{x.delta < 0 ? "" : "+"}{x.delta}</b>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="wt-ed-regen">
            <b>{t("다시 그리기")}</b>
            {activeNote && <span className="dim">{t("이 장의 장면 · {note}", { note: activeNote })}</span>}
            <button type="button" className="btn btn-p wt-ed-regenbtn" onClick={regenActive}>
              {regenCost == null
                ? t("이 컷 다시 그리기")
                : t("이 컷 다시 그리기 · {n}크레딧", { n: regenCost })}
            </button>
          </div>
        </aside>
      </div>

      {/* 다시 그리기 확인 창 — 항목(칩)은 엔진이 /config 에서 받아 채운다. */}
      <div className="ask wt-ed-ask" id="regenAsk" hidden>
        <div className="ask-box" role="dialog" aria-modal="true" aria-labelledby="regenAskTitle">
          <h2 id="regenAskTitle">{t("다시 그리기")}</h2>
          <p className="ask-sub" id="regenAskSub" />
          <p className="ask-scene" id="regenAskScene" hidden />
          <p className="fb-lead">{t("무엇이 마음에 안 드나요?")}</p>
          <div className="fb-tags" id="regenAskTags" />
          <label className="wt-ed-askfield">
            <span>{t("더 하고 싶은 말")}</span>
            <textarea id="regenAskText" rows={3} maxLength={500} className="field"
                      placeholder={t("더 하고 싶은 말 · 예: 우산을 들고 있게")} />
          </label>
          <label className="check-line">
            <input type="checkbox" id="regenAskTextless" />
            <span>{t("말풍선 없이 그림만")}</span>
          </label>
          <div className="ask-actions">
            <button type="button" className="btn btn-w" id="regenAskCancel">{t("취소")}</button>
            <button type="button" className="btn btn-p" id="regenAskGo">{t("이 컷 다시 그리기")}</button>
          </div>
        </div>
      </div>

      <div className="toast wt-ed-toast" id="toast" hidden />
    </div>
  );
}
