"use client";

import { useEffect, useState } from "react";
import { setupLou, setupTips } from "../../lib/mascotPlay";
import {
  cancelJob, decideSheet, jobPageUrl, pickDirection, rememberMyRun, retryDirections,
} from "../../lib/nhApi";
import { useNhJob } from "./useNhJob";
import { headLine, mascotLine, mmss, NH_STAGE_ART } from "./nhStage";
import SheetApproval from "./SheetApproval";
import PickApproval from "./PickApproval";
import ZoomView from "./ZoomView";

/* 기다리는 화면 — haeun/landing/web 의 #progress 를 옮겼다.
 *
 * **이제 흉내가 아니다.** 예전에는 백엔드가 없어서 로컬 타이머로 진행을
 * 흉내 냈는데(useFakeProgress), 지금은 `/api/webtoon/nh/jobs/{id}` 를 0.8초
 * 마다 받아 실제 작업을 그린다 — 원본 app.js 의 nhTick 과 같은 방식이다.
 *
 * 사람이 멈춰 서는 자리는 **둘뿐**이다. 시트 확인 → 이야기 고르기, 그
 * 순서다(시트가 먼저인 이유는 SheetApproval 주석). 이야기를 고른 다음은 안
 * 멈춘다 — 곧장 그림이다.
 *
 * 단계 목록은 **서버가 준 것**을 그린다(s.stages · s.stage_index ·
 * s.stage_label · s.pct). 화면이 단계 목록을 들고 있으면 파이프라인이 바뀔
 * 때마다 화면이 거짓말을 한다 — 실제로 그랬다. 이 화면이 한동안 이미
 * 없어진 콘티 단계("1화를 컷으로 나누고 대사를 붙입니다")를 계속 보여주고
 * 있었다.
 */
export default function Progress({
  jobId,
  styleLabel,
  onExit,
  onDone,
}: {
  jobId: string;
  /** 만들 때 고른 그림체 이름. 서버도 style_label 을 주지만 첫 폴링 전까지 비어 있다. */
  styleLabel?: string;
  onExit: () => void;
  onDone: (runId: string) => void;
}) {
  const { job, offline, busy, send } = useNhJob(jobId);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [zoom, setZoom] = useState<{ src: string; alt: string } | null>(null);
  // 시트를 다시 만들면 주소가 같아서 옛 그림이 뜬다 — 이 값으로 캐시를 흘린다.
  const [sheetVersion, setSheetVersion] = useState(() => Date.now());
  const [failed, setFailed] = useState<string | null>(null);

  useEffect(() => {
    const disposeLou = setupLou();
    const disposeTips = setupTips();
    return () => { disposeLou(); disposeTips(); };
  }, []);

  /* 다 되면 결과 화면으로. **내 작품으로 남기는 것을 잊으면 안 된다** —
     안 남기면 앱이 남의 작품으로 보고 완성본 화면의 내려받기·편집실·저장·
     공유를 통째로 감춘다(원본에서 실제로 겪은 것이다). */
  const doneRunId = job?.status === "done" ? job.run_id : null;
  useEffect(() => {
    if (!doneRunId) return;
    rememberMyRun(doneRunId);
    onDone(doneRunId);
  }, [doneRunId, onDone]);

  if (!job) {
    return (
      <section className="progress">
        <div className="progress-inner">
          <header className="progress-head">
            <h2>{offline ? "서버에 닿지 못했습니다" : "불러오는 중…"}</h2>
            {offline && (
              <p className="progress-sub">
                잠시 뒤 다시 시도합니다 — 만들기는 서버에서 계속 돌고 있습니다.
              </p>
            )}
          </header>
        </div>
      </section>
    );
  }

  if (job.status === "error") {
    return (
      <section className="progress">
        <div className="progress-inner">
          <header className="progress-head">
            <p className="eyebrow">멈췄습니다</p>
            <h2>만들지 못했습니다</h2>
            {/* 하네스가 사유를 한글로 적어 보낸다 — 그대로 보여준다. */}
            <p className="progress-sub">{job.error || "알 수 없는 이유로 멈췄습니다."}</p>
          </header>
          <button type="button" className="btn btn-primary" style={{ width: "100%" }} onClick={onExit}>
            홈으로
          </button>
        </div>
      </section>
    );
  }

  const head = headLine(job.status, job.style_label || styleLabel || "");
  const line = mascotLine(job.status, job.stage, job.say, job.art);
  const waiting = job.status === "awaiting_sheet" || job.status === "awaiting_pick";

  /** 검수 답 보내기 — 실패하면 그 자리에서 말한다(조용히 삼키면 사람이 또 누른다). */
  const answer = (fn: () => Promise<unknown>) => {
    setFailed(null);
    void send(fn).catch((e: Error) => setFailed(e.message));
  };

  return (
    <section className="progress">
      <div className="progress-inner">
        <header className="progress-head">
          <div className="stage-now">
            <div className="stage-art" data-stage={NH_STAGE_ART[job.stage] || job.stage} />
            <p className="stage-say">{line}</p>
            <div className="lou-progress" role="progressbar"
                 aria-valuemin={0} aria-valuemax={100} aria-valuenow={job.pct}>
              <div className="lou-bar"><i style={{ width: `${job.pct}%` }} /></div>
              <span className="lou-pct">{job.pct}%</span>
            </div>
            <p className="stage-clock">
              <span className="clock-time">{mmss(job.elapsed)}</span>
              <span className="clock-label">경과</span>
            </p>

            <details className="stage-detail">
              <summary>지금 하고 있는 일 자세히</summary>
              <ol className="rail">
                {job.stages.map((key, i) => {
                  const state = i < job.stage_index ? "done" : i === job.stage_index ? "active" : "todo";
                  return (
                    <li key={key} className="stage" data-state={state}>
                      <span className="stage-dot">
                        {state === "done" ? "✓" : String(i + 1).padStart(2, "0")}
                      </span>
                      <div className="stage-main">
                        <h3>{i === job.stage_index ? job.stage_label : key}</h3>
                      </div>
                    </li>
                  );
                })}
              </ol>
            </details>
          </div>

          <p className="eyebrow">{head.eyebrow}</p>
          <h2>{head.title}</h2>
          <p className="progress-sub">{head.sub}</p>
        </header>

        {/* ---- 사람이 멈춰 서는 자리 둘 ---- */}
        {job.status === "awaiting_sheet" && (
          <SheetApproval
            jobId={jobId}
            version={sheetVersion}
            busy={busy}
            onApprove={() => answer(() => decideSheet(jobId, "approve"))}
            onRetry={(note) => {
              setSheetVersion(Date.now());
              answer(() => decideSheet(jobId, "retry", note));
            }}
            onZoom={(src, alt) => setZoom({ src, alt })}
          />
        )}

        {job.status === "awaiting_pick" && (
          <PickApproval
            directions={job.directions}
            busy={busy}
            onPick={(n) => answer(() => pickDirection(jobId, n))}
            onRetry={(note) => answer(() => retryDirections(jobId, note))}
          />
        )}

        {failed && <p className="progress-sub" role="alert">{failed}</p>}

        {/* 기다리는 동안 놀 것. 사람이 답할 차례일 때는 안 띄운다 — 눌러야
            할 것이 화면에 있는데 마스코트가 같이 움직이면 그쪽으로 눈이 간다. */}
        {!waiting && (
          <>
            <div className="play">
              <div className="mascot-stage" id="mascotStage">
                <button type="button" className="mascot" id="mascot" data-mood="think"
                        aria-label="루를 눌러 보기">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img id="mascotImg" src="/static/lou/react/idle/01.webp" alt="" draggable={false} />
                </button>
              </div>
              <p className="play-say" id="playSay">루를 눌러 보세요</p>
              <p className="play-hint" id="playHint">
                눌러 보기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기
              </p>
              <button type="button" className="btn btn-quiet btn-sm" id="shakeAllow" hidden>
                흔들기 켜기
              </button>
            </div>

            <div className="tips" id="tips" hidden aria-live="polite">
              <span className="tip-kind" id="tipKind">팁</span>
              <p className="tip-text" id="tipText" />
            </div>
          </>
        )}

        {/* 그려진 장은 나오는 대로 보여준다 — 몇 분을 기다리는 사람에게
            가장 큰 정보다. */}
        {job.art && job.art.done > 0 && (
          <div className="cutstrip">
            <div className="cutstrip-head">
              <span>그려진 장</span>
              <span>{job.art.done} / {job.art.total}장</span>
            </div>
            <div className="cutstrip-grid">
              {Array.from({ length: job.art.done }, (_, i) => i + 1).map((n) => (
                <figure key={n}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={jobPageUrl(jobId, n)} alt={`${n}번째 장`} loading="lazy" />
                  <figcaption>{n}</figcaption>
                </figure>
              ))}
            </div>
          </div>
        )}

        {/* 서버가 찍는 줄을 그대로 보여준다 — 무엇을 하고 있는지 숨기지
            않는 것이 이 화면의 약속이다. */}
        <details className="console">
          <summary>자세히 보기 <small>파이프라인 로그</small></summary>
          <pre>{(job.log || []).join("\n")}</pre>
        </details>

        {/* 통신이 잠깐 끊긴 것은 작업 실패가 아니다 — 서버에서는 계속 돈다. */}
        {offline && (
          <p className="progress-sub">연결이 잠깐 끊겼습니다 — 다시 받아오는 중입니다.</p>
        )}

        {!waiting && (
          <div className="cancel-row">
            <button type="button" className="btn btn-danger btn-sm" onClick={() => setCancelOpen(true)}>
              만들기 중단
            </button>
          </div>
        )}
      </div>

      {cancelOpen && (
        <div
          className="modal-veil"
          onClick={(e) => { if (e.target === e.currentTarget) setCancelOpen(false); }}
        >
          <div className="modal-box modal-narrow" role="dialog" aria-modal="true"
               aria-labelledby="cancelModalTitle">
            <h3 id="cancelModalTitle">정말로 중단하시겠습니까?</h3>
            <p className="cancel-warn"><b>크레딧은 환불되지 않습니다.</b></p>
            <p className="cancel-sub">
              지금까지 그려 둔 장은 그대로 남습니다 — 편집실에서 볼 수 있습니다.
            </p>
            <div className="cancel-actions">
              <button type="button" className="btn btn-quiet" onClick={() => setCancelOpen(false)}>
                계속 만들기
              </button>
              <button
                type="button"
                className="btn btn-danger"
                onClick={() => { setCancelOpen(false); answer(() => cancelJob(jobId)); }}
              >
                중단하기
              </button>
            </div>
          </div>
        </div>
      )}

      {zoom && <ZoomView src={zoom.src} alt={zoom.alt} onClose={() => setZoom(null)} />}
    </section>
  );
}
