"use client";

import { useEffect, useState } from "react";
import { mmss, MASCOT_MOODS } from "../../lib/progressData";
import { STYLE_INFO, type WizardForm } from "../../lib/wizardData";
import { setupLou, setupTips } from "../../lib/mascotPlay";
import { useFakeProgress } from "./useFakeProgress";

const READY_CUT_IMAGES = [
  "/static/samples/mock/scene1.jpg",
  "/static/samples/mock/scene2.jpg",
  "/static/samples/mock/scene3.jpg",
  "/static/samples/mock/scene4.jpg",
];

/* 기다리는 화면 — haeun/landing/web 의 #progress 를 옮겼다.
 *
 * 실제 파이프라인이 없어서 진행 상태는 useFakeProgress 가 흉내 낸다(그 파일
 * 주석 참고). 이 컴포넌트는 그 상태를 원본 renderProgress/paintMascot 와
 * 같은 규칙으로 그리기만 한다 — 무엇을 그리느냐는 원본과 같고, 어디서 값을
 * 받느냐만 다르다.
 *
 * 다 되면(onDone) Result(결과 화면)로 넘어간다 — 그 화면도 실제 작품이
 * 아니라 원본의 결과 화면 목업(showMockResult)을 그대로 쓴다.
 *
 * 안 옮긴 것: 전문 모드의 네 확인 팝업(시트·스토리·콘티·그림 검수),
 * 이미지 모델 거절 목록(#refusals — 데모라 거절이 생길 수 없다), 다른
 * 화면에서도 진행을 들고 다니는 떠 있는 표시(#miniProg — 둘러보기·마이페이지가
 * 아직 없어서 붙을 자리가 없다). 전부 실제 파이프라인 상태가 있어야 뜻이
 * 있는 것들이라 mock으로 채우기도 마땅치 않다. */
export default function Progress({
  form,
  onExit,
  onDone,
}: {
  form: WizardForm;
  onExit: () => void;
  onDone: () => void;
}) {
  const { snapshot, cancel } = useFakeProgress();
  const [cancelOpen, setCancelOpen] = useState(false);

  useEffect(() => {
    const disposeLou = setupLou();
    const disposeTips = setupTips();
    return () => {
      disposeLou();
      disposeTips();
    };
  }, []);

  const currentStage = snapshot.stages[snapshot.stageIndex];
  const styleLabel = STYLE_INFO.find(([key]) => key === form.style)?.[1] || "루가 고른 그림체";

  let mood = "think";
  let line = "";
  if (snapshot.status === "done") {
    mood = "done";
    line = "루가 다 그렸어요!";
  } else if (snapshot.status === "cancelled") {
    mood = "error";
    line = "루가 멈췄어요";
  } else if (currentStage) {
    const hit = MASCOT_MOODS[currentStage.key];
    if (hit) [mood, line] = hit;
  }

  if (snapshot.status === "cancelled") {
    return (
      <section className="progress">
        <div className="progress-inner">
          <header className="progress-head">
            <p className="eyebrow">중단됨</p>
            <h2>만들기를 중단했습니다</h2>
            <p className="progress-sub">지금까지 그려 둔 장은 남아 있습니다 — 편집실은 아직 이 화면에서 못 갑니다.</p>
          </header>
          <div className="cancel-row" style={{ justifyContent: "center" }}>
            <button type="button" className="btn btn-primary" onClick={onExit}>
              홈으로
            </button>
          </div>
        </div>
      </section>
    );
  }

  if (snapshot.status === "done") {
    return (
      <section className="progress">
        <div className="progress-inner">
          <header className="progress-head">
            <div className="stage-art" data-mood="done" />
            <p className="stage-say">{line}</p>
            <p className="eyebrow" style={{ marginTop: 16 }}>완성</p>
            <h2>웹툰이 완성됐습니다</h2>
            <p className="progress-sub">
              결과 화면도 아직 실제 작품이 아니라 화면 구경용 목업입니다 — 만든 이야기 대신
              샘플을 보여드립니다.
            </p>
          </header>
          <button type="button" className="btn btn-primary" style={{ width: "100%" }} onClick={onDone}>
            결과 보러 가기
          </button>
        </div>
      </section>
    );
  }

  return (
    <section className="progress">
      <div className="progress-inner">
        <header className="progress-head">
          <div className="stage-now">
            <div className="stage-art" data-stage={currentStage?.key} data-mood={mood} />
            <p className="stage-say" id="mascotLine">{line}</p>
            <div className="lou-progress" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={snapshot.pct}>
              <div className="lou-bar"><i style={{ width: `${snapshot.pct}%` }} /></div>
              <span className="lou-pct">{snapshot.pct}%</span>
            </div>
            <p className="stage-clock">
              <span className="clock-time">{mmss(snapshot.elapsed)}</span>
              <span className="clock-label">경과</span>
            </p>
            <details className="stage-detail">
              <summary>지금 하고 있는 일 자세히</summary>
              <ol className="rail">
                {snapshot.stages.map((st, i) => {
                  const num = String(i + 1).padStart(2, "0");
                  const mark = st.state === "done" ? "✓" : num;
                  const showSteps = st.state === "active";
                  const bar =
                    st.key === "art" && showSteps
                      ? Math.round((snapshot.art.done / snapshot.art.total) * 100)
                      : null;
                  return (
                    <li key={st.key} className="stage" data-state={st.state}>
                      <span className="stage-dot">{mark}</span>
                      <div className="stage-main">
                        <h3>{st.title}</h3>
                        <p className="stage-desc">{st.desc}</p>
                        {showSteps && (
                          <ul className="steps">
                            {st.steps.map((s) => (
                              <li key={s.key} data-state={s.state}>
                                <span className="tick">{s.state === "done" ? "✓" : ""}</span>
                                {s.label}
                              </li>
                            ))}
                          </ul>
                        )}
                        {bar != null && (
                          <div className="bar"><i style={{ width: `${bar}%` }} /></div>
                        )}
                      </div>
                      <span className="stage-time">{st.seconds != null ? mmss(st.seconds) : ""}</span>
                    </li>
                  );
                })}
              </ol>
            </details>
            <button
              type="button"
              className="btn btn-quiet btn-sm stage-away"
              disabled
              title="둘러보기는 아직 이 화면에서 연결 전입니다"
            >
              기다리는 동안 다른 웹툰 둘러보기 →
            </button>
          </div>
          <p className="eyebrow">{styleLabel} · 미리보기</p>
          <h2>웹툰을 만들고 있습니다</h2>
          <p className="progress-sub">지금 무엇을 하고 있는지 아래에 그대로 보여드립니다.</p>
        </header>

        <div className="play">
          <div className="mascot-stage" id="mascotStage">
            <button type="button" className="mascot" id="mascot" data-mood="think" aria-label="루를 눌러 보기">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img id="mascotImg" src="/static/lou/react/idle/01.webp" alt="" draggable={false} />
            </button>
          </div>
          <p className="play-say" id="playSay">루를 눌러 보세요</p>
          <p className="play-hint" id="playHint">눌러 보기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기</p>
          <button type="button" className="btn btn-quiet btn-sm" id="shakeAllow" hidden>흔들기 켜기</button>
        </div>

        <div className="tips" id="tips" hidden aria-live="polite">
          <span className="tip-kind" id="tipKind">팁</span>
          <p className="tip-text" id="tipText" />
        </div>

        {snapshot.readyCuts.length > 0 && (
          <div className="cutstrip">
            <div className="cutstrip-head">
              <span>그려진 장</span>
              <span>{snapshot.art.done} / {snapshot.art.total}장</span>
            </div>
            <div className="cutstrip-grid">
              {snapshot.readyCuts.map((n) => (
                <figure key={n}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={READY_CUT_IMAGES[(n - 1) % READY_CUT_IMAGES.length]} alt={`${n}번째 장`} loading="lazy" />
                  <figcaption>{n}</figcaption>
                </figure>
              ))}
            </div>
          </div>
        )}

        <details className="console">
          <summary>자세히 보기 <small>파이프라인 로그</small></summary>
          <pre>{snapshot.log.join("\n")}</pre>
        </details>

        <div className="cancel-row">
          <button type="button" className="btn btn-danger btn-sm" onClick={() => setCancelOpen(true)}>
            만들기 중단
          </button>
        </div>
      </div>

      {cancelOpen && (
        <div
          className="modal-veil"
          onClick={(e) => {
            if (e.target === e.currentTarget) setCancelOpen(false);
          }}
        >
          <div className="modal-box modal-narrow" role="dialog" aria-modal="true" aria-labelledby="cancelModalTitle">
            <h3 id="cancelModalTitle">정말로 중단하시겠습니까?</h3>
            <p className="cancel-warn"><b>크레딧은 환불되지 않습니다.</b></p>
            <p className="cancel-sub">지금까지 그려 둔 장은 그대로 남습니다 — 편집실에서 볼 수 있습니다.</p>
            <div className="cancel-actions">
              <button type="button" className="btn btn-quiet" onClick={() => setCancelOpen(false)}>계속 만들기</button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => {
                  cancel();
                  setCancelOpen(false);
                }}
              >
                중단하기
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
