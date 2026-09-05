"use client";

import { useEffect } from "react";
import { mountEditor } from "../../lib/editorCore";

/* 편집실 — haeun/landing/web 의 editor.html 을 옮겼다.
 *
 * **이제 진짜 작품을 연다.** 예전에는 샘플(mock.json)만 열리는 반쪽이었는데,
 * 지금은 작품·회차를 받아 그 그림을 열고 다시 그리기·지난 판 되돌리기·
 * 이미지로 뽑기가 전부 실제로 돈다(엔진은 lib/editorCore.ts).
 *
 * **자기 머리는 안 그린다.** 원본은 혼자 뜨는 페이지라 자기 머리(.ed-top-row
 * 의 LORE 로고)가 필요했지만, 여기서는 Lore 앱 헤더가 이미 위에 있다 — 둘 다
 * 그리면 "LORE" 가 두 번 나온다. 크레딧 칩은 그 줄에 같이 있던 것이라 제목
 * 띠로 내렸다(실제 작품을 열면 엔진이 칩째로 지운다).
 */
export default function Editor({
  runId,
  episode = 1,
  onOpenRun,
}: {
  /** 열 작품. 없으면 샘플이다 — 원본이 `?run=` 없이 열었을 때와 같다. */
  runId?: string;
  episode?: number;
  /** 왼쪽 목록에서 다른 작품·회차를 골랐을 때. */
  onOpenRun?: (runId: string, episode: number) => void;
}) {
  useEffect(() => {
    const dispose = mountEditor({ runId, episode }, (r, ep) => onOpenRun?.(r, ep));
    return () => dispose();
  }, [runId, episode, onOpenRun]);

  return (
    <div className="ed">
      {/* 머리 · 제목 띠 · 구운 결과를 한 덩어리로 붙박아 둔다. 따로 붙이면
          아래 줄의 top 값을 위 줄 높이에 손으로 맞춰야 해서 글꼴이 바뀔 때
          어긋난다. */}
      <header className="ed-top">
        <div className="ed-strip">
          <button type="button" className="ed-works-toggle" id="worksToggle"
                  aria-expanded="false" aria-controls="edWorks">
            <span aria-hidden="true">☰</span> 작품
          </button>

          <div className="ed-title">
            <b id="edTitle">—</b>
            <span id="edMeta" />
          </div>

          {/* 크레딧은 "얼마 남았나" 를 그리기 **전에** 보는 값이라 맨 위가
              맞다. 샘플 표시와 한 묶음인 것은 원본 그대로다 — 실제 작품을
              열면 엔진이 칩째로 지운다. */}
          <div className="ed-chips">
            <span className="mock-badge"
                  title="작품을 고르기 전까지는 샘플입니다. 크레딧과 다시 그리기는 흉내만 냅니다.">
              샘플
            </span>
            <div className="credit" id="creditBox">
              <span className="credit-icon">◈</span>
              <span className="credit-num" id="creditNum">1,240</span>
              <span className="credit-unit">크레딧</span>
              <button type="button" className="credit-more" id="ledgerBtn">내역</button>
            </div>
          </div>
        </div>

        {/* 구운 결과. 몇 장을 구웠는지, 무엇이 빠졌는지, 어디서 받는지. */}
        <div className="bake-result" id="bakeResult" hidden />
      </header>

      <div className="ed-body">
        {/* 만들어 둔 웹툰 전부. 목록이 비어 있어도(아직 하나도 안 만든 사람)
            자리를 지운다 — 자리가 사라지면 "그런 기능이 없다" 로 읽힌다. */}
        <aside className="ed-works" id="edWorks">
          <p className="ed-works-head">내 작품</p>
          <div id="worksList" />
        </aside>

        <main className="ed-stage" id="stageCol">
          <div className="ed-stage-head">
            <p className="eyebrow" id="edGenre" />
            {/* 제목은 여기서 바로 고친다. 연필(✎)은 결과 화면과 같은 모양·
                같은 자리다 — 글자만 눌러도 고쳐지지만, 그것만으로는 고칠 수
                있다는 것을 아무도 몰랐다. */}
            <div className="title-row">
              <h1 id="edEpisode" data-title-edit tabIndex={0}
                  title="눌러서 제목을 고칩니다">1화</h1>
              <button type="button" className="icon-btn" id="edTitleEditBtn"
                      title="제목 고치기" aria-label="제목 고치기">✎</button>
            </div>
            <p className="ed-logline" id="edLogline" />
            <label className="mini-toggle">
              <input type="checkbox" id="showOverlay" defaultChecked />
              <span>내가 얹은 것 보기</span>
            </label>

            {/* 단추줄은 **제목·설명 아래**다. 결과 화면이 제목 밑에 단추를 두는
                것과 같은 자리이고, 폰에서는 이 줄이 그대로 화면 아래 고정 바가
                된다(webtoon.css 의 .ed-top-right). */}
            <div className="ed-top-right">
              {/* 도구 서랍 손잡이. position 이 화면 폭에 따라 바뀌므로
                  (폰=이 줄의 한 칸, PC=컬럼 오른쪽에 붙는 fixed) DOM 위치는
                  여기 하나로 둔다 — fixed 는 부모와 무관하게 뜬다. */}
              <button type="button" className="dock-open" id="dockOpen"
                      aria-controls="edDock" aria-expanded="false">
                <span className="dock-open-icon" aria-hidden="true">☰</span>
                <span className="dock-open-label">도구</span>
              </button>
              {/* 얹은 것을 진짜 그림에 굽는다. 여기까지 와야 가져갈 것이
                  생긴다 — 이 단추가 없던 동안, 배치한 말풍선은 브라우저 밖으로
                  나갈 길이 없었다. */}
              <button type="button" className="btn btn-primary btn-sm" id="bakeBtn">
                이미지로 뽑기
              </button>
            </div>
            {/* 얹은 것은 손을 멈추면 저절로 올라간다. 예전에는 「저장」 단추가
                그 옆에 있었는데, 누르든 안 누르든 같은 일이 일어나는 단추라
                "안 누르면 날아가나" 만 만들었다. 단추를 없앤 대신 **올라갔는지
                아닌지를 여기서 말한다** — 조용히 실패하면 그것만 모르게 된다. */}
            <p className="ed-saved" id="savedNote" data-state="idle" />
          </div>

          {/* 회차 고르개. 한 편뿐인 작품에서는 엔진이 통째로 감춘다. */}
          <div className="ep-tabs" id="edEpTabs" hidden />

          <div id="scenes" />

          <p className="ed-foot-note" id="edFootNote">여기까지가 1화입니다.</p>
        </main>

        {/* 오른쪽에서 밀려 나오는 서랍. 예전에는 화면 아래에 붙어 있었는데,
            그림을 보면서 얹는 작업인데 화면 아래 절반을 늘 서랍이 차지하고
            있었다. 기본은 닫힘이고 ☰ 를 눌러야 열린다 — 그림이 먼저다. */}
        <div className="dock-scrim" id="dockScrim" hidden />

        <aside className="ed-dock" id="edDock" aria-label="말풍선 · 스티커 · 효과음">
          <div className="dock-bar">
            <div className="dock-tabs" id="dockTabs">
              <button type="button" className="dock-tab is-on" data-tab="bubble">말풍선</button>
              <button type="button" className="dock-tab" data-tab="sticker">스티커</button>
              <button type="button" className="dock-tab" data-tab="sfx">효과음</button>
            </div>
            <button type="button" className="dock-fold" id="dockFold"
                    aria-expanded="false" aria-controls="dockBody" aria-label="도구 닫기" />
          </div>

          <div className="dock-body" id="dockBody">
            <p className="dock-hint" id="dockHint">
              누르면 <b id="activeSceneLabel">1번째 장</b>에 올라갑니다 — 끌어서 옮기세요.
            </p>

            {/* 고른 요소를 고치는 자리는 여기가 아니라 **그림 위**다. 전에는
                이 서랍에 글자칸과 슬라이더가 있었다 — 고치려면 그림에서 눈을
                떼고 서랍을 봐야 했고, 말풍선이 그림 어디에 걸리는지 보면서
                맞출 수가 없었다. 서랍은 이제 넣는 자리(팔레트)만 한다. */}
            <div className="dock-grid" id="dockGrid" />
          </div>

          <div className="dock-ledger" id="dockLedger" hidden>
            <div className="dock-props-head">
              <b>크레딧 사용 내역</b>
              <button type="button" className="icon-btn" id="ledgerClose" aria-label="닫기">✕</button>
            </div>
            <ul id="ledgerList">
              <li className="ledger-empty">아직 쓴 크레딧이 없습니다.</li>
            </ul>
          </div>
        </aside>
      </div>

      {/* 다시 그리기 확인 창 — 누르면 바로 굽지 않고 여기서 한 번 멈춘다.
          한 장 굽는 데 1~2분과 실제 생성 비용이 들어서, 잘못 눌러 나가는 것이
          그냥 되돌리면 되는 일이 아니다. **왜** 다시 그리는지도 여기서 적는다.
          항목(칩)은 서버가 준다 — 화면에 베껴 두면 갈라진다. */}
      <div className="ask" id="regenAsk" hidden>
        <div className="ask-box" role="dialog" aria-modal="true" aria-labelledby="regenAskTitle">
          <h2 id="regenAskTitle">다시 그리기</h2>
          <p className="ask-sub" id="regenAskSub" />

          {/* 이 장을 그릴 때 준 장면. 마음에 안 드는 장을 앞에 두고 사람이
              먼저 가려야 하는 것은 "이야기는 이런데 그림이 못 따라간 것" 인지
              "이야기 자체가 이런 것" 인지다 — 그걸 모르면 그림에다 대고
              이야기를 고쳐 달라고 적게 된다. */}
          <p className="ask-scene" id="regenAskScene" hidden />

          <p className="fb-lead">무엇이 마음에 안 드나요? <small>안 골라도 됩니다</small></p>
          <div className="fb-tags" id="regenAskTags" />

          <label className="field">
            <span>더 하고 싶은 말 <small>비워도 됩니다</small></span>
            <textarea id="regenAskText" rows={3} maxLength={500}
                      placeholder="예: 표정을 더 밝게 / 배경을 밤으로" />
          </label>

          <label className="check-line">
            <input type="checkbox" id="regenAskTextless" />
            <span>말풍선 없이 그림만 <small>대사는 나중에 얹으세요</small></span>
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
    </div>
  );
}
