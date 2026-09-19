"use client";

/* 첫 화면 — 보드 Landing.dc.html(PC) · MLanding.dc.html(폰).
 * 위쪽 .top 줄은 그리지 않는다(공용 헤더가 있다). 대신 만들던 작업이 있으면
 * 히어로 위에 알약 하나만 둔다. */
import "./i18n";
import Link from "next/link";
import { useEffect, useState } from "react";
import * as api from "../../lib/api";
import { LangSwitch, useT, type T } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { IconDownload, IconEdit, IconPlus, IconRetry, IconShare, IconUser } from "../../ui/Icons";
import EditorMock, { CUT_IMG, PAGE_IMG, SHEET_IMG } from "./EditorMock";

/* 04 완성 칸의 표지. 캔버스가 쓰는 그림과 같은 파일이다(예시 작품
 * 「가면 아래의 조건」의 표지) — 실행 id 를 코드에 박아 두면 그 작품이
 * 빠질 때 조용히 빈칸이 된다. */
const DONE_COVER = "/static/gallery/20260910T132240-ae8c28/cover.jpg";
import { usePhone } from "./usePhone";
import "./Landing.css";

/* 답의 `**…**` 는 굵게 — 언어마다 어순이 달라 문장을 조각내지 않고 표시만 남긴다. */
const FAQ: { q: string; a: string }[] = [
  { q: "이건 뭐하는 서비스인가요?", a: "사진 한 장과 이름만 주시면 **웹툰 한 화가 통째로** 나오는 스튜디오예요. 캐릭터를 그리고, 이야기를 짓고, 표지부터 마지막 장까지 순서대로 그려 드립니다. 그림을 못 그려도, 이야기를 안 써 봤어도 괜찮아요." },
  { q: "AI SW 마에스트로란 무엇인가요?", a: "과학기술정보통신부가 주관하고 정보통신기획평가원(IITP)이 운영하는 AI 소프트웨어 인재 양성 과정이에요. LORE는 이 과정에서 실제로 기획하고 만들고 있는 프로젝트입니다." },
  { q: "마음에 안 들면 다시 만들 수 있나요?", a: "네, 가능해요. **「2번 확인하며」**를 고르면 캐릭터 시트와 이야기 단계에서 확인하면서 다시 만들 수 있고, 완성한 뒤에도 편집실에서 컷 단위로 다시 그릴 수 있어요." },
  { q: "만들다가 창을 닫거나 다른 걸 하면 어떻게 되나요?", a: "괜찮아요. 진행 상황은 서버가 갖고 있어서, 나중에 같은 작업으로 다시 들어오면 하던 데서 그대로 이어집니다." },
  { q: "얼마나 걸리나요?", a: "한 편에 보통 10분 안팎 걸려요. 그림체나 이야기 길이에 따라 조금씩 달라질 수 있어요." },
  { q: "그림을 하나도 못 그려도 쓸 수 있나요?", a: "네, 그림을 못 그리셔도 괜찮아요. 사진 한 장과 이름만 있으면 버튼 하나로 끝까지 완성됩니다." },
  { q: "로그인 안 해도 만들 수 있나요?", a: "네, 로그인 없이도 게스트로 끝까지 만들 수 있어요. 나중에 로그인해서 저장하면 마이페이지에서 다시 볼 수 있어요." },
  { q: "여러 캐릭터로 여러 편 만들 수 있나요?", a: "네, 얼마든지요. 로그인하면 그동안 만든 작품이 마이페이지에 모두 모여요." },
  { q: "완성한 웹툰은 다른 사람도 볼 수 있나요?", a: "기본적으로 **둘러보기**에 공개돼요. 마이페이지에서 언제든 비공개로 바꿀 수 있어요." },
  { q: "올린 사진은 어떻게 되나요?", a: "캐릭터를 만드는 데만 사용하고, 시트가 완성되면 서버에서 바로 삭제해요." },
];

const PROMISES = [
  { n: "/ 01", t: "같은 얼굴, 마지막 컷까지", d: "캐릭터 시트를 먼저 만들어 두어, 어느 장면에서도 얼굴과 옷이 흐트러지지 않아요." },
  { n: "/ 02", t: "마음에 안 드는 컷만 다시", d: "한 편을 다시 만들지 않아요. 그 컷만 콕 집어 다시 그려요." },
  { n: "/ 03", t: "넣은 그대로, 그 세계관 안에", d: "강아지는 강아지인 채로 악역 영애가 돼요. 사람으로 바꾸지도, 다른 얼굴로 바꾸지도 않아요." },
] as const;

function titleOf(r: api.RunCard): string {
  return (r.title || "").replace(/^"(.*)"$/, "$1");
}

/** `**굵게**` 표시를 <b> 로 바꿔 그린다. */
function bold(text: string): React.ReactNode {
  const parts = text.split("**");
  return parts.map((p, i) => (i % 2 ? <b key={i}>{p}</b> : p));
}

/* lib/api.allowanceLine 과 같은 규칙 — 번역하려고 여기서 조립한다. 서버가 막은 이유(blocked)는 그대로. */
function allowanceText(t: T, a: api.Allowance | null): string {
  if (!a) return "";
  if (a.blocked) return a.blocked;
  if (!a.logged_in) {
    if (a.free_left == null) return "";
    return a.free_left > 0 ? t("오늘 무료 {n}편", { n: a.free_left }) : t("오늘 무료 소진 · 로그인하면 이어서");
  }
  // 로그인한 사람의 "한 편 {cost}크레딧 · 보유 {balance}C" 는 여기서 안 보여준다 —
  // 헤더에 잔액이 이미 있고, 온보딩 히어로에 또 나오면 중복이다(2026-09-19 지적).
  return "";
}

export default function Landing({ go }: { go: Go }) {
  const t = useT();
  const phone = usePhone();
  const [need, setNeed] = useState(0);
  const [feat, setFeat] = useState<0 | 1 | 2>(0);

  /* 만들던 작업 알약 */
  const [job, setJob] = useState<api.NhJob | null>(null);
  useEffect(() => {
    api.myActiveJobs().then((r) => setJob(r.jobs?.[0] ?? null)).catch(() => {});
  }, []);
  const jobLabel = job
    ? (job.art?.total ? t("{done} / {total}장", { done: job.art.done, total: job.art.total }) : t(job.stage_label))
    : "";

  /* 허용량 딱지 */
  const [allowance, setAllowance] = useState<api.Allowance | null>(null);
  useEffect(() => {
    api.readAllowance().then(setAllowance).catch(() => {});
  }, []);
  const allowLine = allowanceText(t, allowance);

  /* 예시 작품 띠 */
  const [runs, setRuns] = useState<api.RunCard[] | null>(null);
  const [runsErr, setRunsErr] = useState("");
  const [tries, setTries] = useState(0);
  useEffect(() => {
    let alive = true;
    setRunsErr("");
    api.browseRuns()
      .then((list) => { if (alive) setRuns(list); })
      .catch((e: Error) => { if (alive) setRunsErr(e.message || t("작품을 못 불러왔습니다")); });
    return () => { alive = false; };
  }, [tries]);

  const start = () => go("entry");
  const needTo = (i: number) => (ev: React.MouseEvent) => {
    ev.preventDefault();
    setNeed(i);
    go(i === 0 ? "create" : "try");
  };

  const marqueeList = runs && runs.length ? [...runs, ...runs] : [];

  return (
    <div className="wt-landing">
      {/* 히어로 */}
      <section className="wt-landing-hero">
        {job && (
          <button type="button" className="btn btn-w wt-landing-jobpill" onClick={() => go("running", { job: job.id })}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/static/lou/logo-1-default.png" alt="" />
            {t("만들던 웹툰")} <span className="dim" style={{ fontWeight: 400 }}>· {jobLabel}</span>
          </button>
        )}
        <h1>{t("AI 웹툰 제작 서비스 LORE")}</h1>
        <p className="muted">{t(phone ? "캐릭터 · 사진 · 그림 · 좋아하는 사람" : "캐릭터 · 사진 · 그림 · 그 무엇이든!")}</p>
        <button type="button" className="btn btn-p wt-landing-cta" onClick={start}>{t("지금 시작하기")}</button>
        {allowLine && <span className="wt-landing-allow">{allowLine}</span>}
      </section>

      {/* 예시 작품 띠 */}
      <section className="wt-landing-works">
        <div className="wt-landing-works-head">
          <button type="button" className="btn btn-w btn-sm wt-landing-works-all" onClick={() => go("works")}>
            {t("웹툰 전체 보러가기")}
          </button>
        </div>
        {runsErr ? (
          <div className="wt-landing-works-err">
            <span className="err">{runsErr}</span>
            <button type="button" className="btn btn-w btn-sm" onClick={() => setTries((n) => n + 1)}>{t("다시 시도")}</button>
          </div>
        ) : !runs ? (
          <div className="wt-landing-marq-clip">
            <div className="wt-landing-marq" style={{ animation: "none" }}>
              {Array.from({ length: 5 }, (_, i) => (
                <figure key={i} className="wt-landing-fig"><div className="skeleton wt-landing-cover" /></figure>
              ))}
            </div>
          </div>
        ) : runs.length === 0 ? null : (
          <div className="wt-landing-marq-clip">
            <div className="wt-landing-marq" style={{ animationDuration: `${Math.max(20, runs.length * 8)}s` }}>
              {marqueeList.map((r, i) => (
                <figure key={`${r.run_id}-${i}`} className="wt-landing-fig">
                  <a href={hrefOf("result", { run: r.run_id })} onClick={(ev) => { ev.preventDefault(); go("result", { run: r.run_id }); }}
                     aria-hidden={i >= runs.length} tabIndex={i >= runs.length ? -1 : 0}>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img className="cover wt-landing-cover" src={api.coverUrl(r.run_id, r.cover_page ?? 1, r.cover_episode ?? 1, !!r.example)} alt={t("{title} 표지", { title: titleOf(r) })} />
                    <figcaption><b>{titleOf(r)}</b><span className="dim">{r.genre}</span></figcaption>
                  </a>
                </figure>
              ))}
            </div>
          </div>
        )}
      </section>

      {/* 4단계 */}
      <section className="wt-landing-steps">
        <h2>{t(phone ? "고르면, 이렇게 만들어져요" : "나만의 이야기를 만들어 보세요!")}</h2>
        <div className="wt-landing-steps-row">
          <div className="wt-landing-step">
            <div className="wt-landing-step-box">
              <div className="wt-landing-step-form">
                <div className="wt-landing-step-name">
                  <div className="wt-landing-step-avatar"><IconUser size={phone ? 27 : 34} stroke="rgba(15,51,63,.35)" /></div>
                  <div className="wt-landing-step-input">{t("세라핀")}</div>
                </div>
                <div className="wt-landing-step-chips">
                  {["로맨스", "판타지", "액션", "일상", "스릴러"].map((g, i) => (
                    <span key={g} className={i === 0 ? "on" : ""}>{t(g)}</span>
                  ))}
                </div>
                <div className="wt-landing-step-btn">{t("웹툰 만들기")}</div>
              </div>
              <span className="wt-landing-step-no">01</span>
            </div>
            <b>{t("시작")}</b>
          </div>
          <div className="wt-landing-step-line"><div /></div>
          <div className="wt-landing-step">
            <div className="wt-landing-step-box">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={SHEET_IMG} alt="" />
              <span className="wt-landing-step-no">02</span>
            </div>
            <b>{t("캐릭터")}</b>
          </div>
          <div className="wt-landing-step-line"><div /></div>
          <div className="wt-landing-step">
            <div className="wt-landing-step-box">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={PAGE_IMG} alt="" />
              <span className="wt-landing-step-no">03</span>
            </div>
            <b>{t("컷")}</b>
          </div>
          <div className="wt-landing-step-line"><div /></div>
          <div className="wt-landing-step">
            <div className="wt-landing-step-box">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={DONE_COVER} alt="" />
              <div className="wt-landing-step-tools">
                <IconEdit size={phone ? 14 : 18} /><IconRetry size={phone ? 14 : 18} /><IconShare size={phone ? 14 : 18} /><IconDownload size={phone ? 14 : 18} />
              </div>
              <span className="wt-landing-step-no">04</span>
            </div>
            <b>{t("완성")}</b>
          </div>
        </div>
      </section>

      {/* 니즈 카드 둘 */}
      <section className="wt-landing-needs">
        <h2>{phone ? <>{t("이야기가 웹툰이 되는 과정,")}<br />{t("LORE 하나로 충분합니다")}</> : <>{t("이미지 한장이 웹툰이 되는 과정,")}<br />{t("LORE 하나로 충분합니다.")}</>}</h2>
        <div className="wt-landing-needs-row">
          <a href={hrefOf("create")} className={`wt-landing-need${need === 0 ? " on" : ""}`}
             onMouseEnter={() => setNeed(0)} onClick={needTo(0)}>
            <div className="wt-landing-need-text">
              <b>{t("내 캐릭터가 살아 움직이는 걸 보세요")}</b>
              <span className="muted">
                {phone
                  ? t("설정만 있던 캐릭터가 이야기 속에서 말하고 움직여요. 캐릭터를 넣으면 그 캐릭터가 주인공인 웹툰이 나옵니다.")
                  : <>{t("설정만 있던 캐릭터가 이야기 속에서 말하고 움직여요.")}<br />{t("캐릭터를 넣으면, 그 캐릭터가 주인공인 웹툰이 나옵니다.")}</>}
              </span>
            </div>
            <div className="wt-landing-need-fig">
              <EditorMock feat={0} s={phone ? 0.7 : 0.9} height={phone ? 260 : 400} who={t("세이엘")} />
            </div>
          </a>

          <a href={hrefOf("try")} className={`wt-landing-need${need === 1 ? " on" : ""}`}
             onMouseEnter={() => setNeed(1)} onClick={needTo(1)}>
            <div className="wt-landing-need-text">
              <b>{t(phone ? "뭐든 넣으면 웹툰 속 캐릭터가 돼요" : "어떤 캐릭터가 나올지, 뽑아볼까요?")}</b>
              <span className="muted">
                <>{t("사진을 넣어도, 이야기를 적어도, 아무것도 없이 시작해도 좋아요.")}<br />{t("당신이 고른 세계관에 맞춰 새로운 캐릭터를 만들어드려요.")}</>
              </span>
            </div>
            <div className="wt-landing-need-fig">
              <div className="wt-landing-cut">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={CUT_IMG} alt={t("웹툰 한 컷")} />
                <span className="wt-landing-genre" style={{ left: 12, top: 12, fontSize: 11, padding: "3px 9px" }}>{t("로판")}</span>
                <div className="wt-landing-bubble" style={{ right: 14, top: 18, maxWidth: "58%", borderRadius: 14, padding: "7px 11px", fontSize: phone ? 11 : 12.5 }}>{t("멍!")}</div>
                <div className="wt-landing-cap" style={{ padding: "14px 16px", display: "flex", flexDirection: "column", gap: 4 }}>
                  <b style={{ fontSize: phone ? 13 : 14.5, lineHeight: 1.4 }}>{t("몽이는 이 로맨스 웹툰에서, 강아지인 채로 악역 영애예요")}</b>
                  <span style={{ fontSize: phone ? 11.5 : 12.5, opacity: 0.85 }}>{t("이 캐릭터로 1화 보기")}</span>
                </div>
              </div>
            </div>
          </a>
        </div>
      </section>

      {/* 약속 셋 */}
      <section className="wt-landing-promise">
        <h2><span className="wt-landing-hl">{t("웹툰 1화를 위해서")}</span><br />{t("LORE가 약속 하는 세 가지")}</h2>
        <div className="wt-landing-promise-grid">
          <EditorMock feat={feat} s={phone ? 0.8 : 1} height={phone ? 360 : 560} who={t("몽이")} />
          <div className="wt-landing-promise-list">
            {PROMISES.map((p, i) => (
              <div key={p.n} className={`wt-landing-promise-item${feat === i ? " on" : ""}`}
                   onMouseEnter={() => setFeat(i as 0 | 1 | 2)} onClick={() => setFeat(i as 0 | 1 | 2)}>
                <span className="wt-landing-promise-n">{p.n}</span>
                <div>
                  <b>{t(p.t)}</b>
                  <div className="wt-landing-promise-more"><p className="muted">{t(p.d)}</p></div>
                </div>
              </div>
            ))}
            <div className="wt-landing-hr" />
          </div>
        </div>
      </section>

      {/* 마지막 CTA */}
      <section className="wt-landing-last">
        <h2 style={phone ? { whiteSpace: "pre-line" } : undefined}>{t(phone ? "당신의 이야기를\n기다리고 있어요" : "당신의 이야기를 기다리고 있어요")}</h2>
        <button type="button" className="btn btn-p wt-landing-cta" onClick={start}>{t("지금 시작하기")}</button>
      </section>

      {/* FAQ */}
      <section className="wt-landing-faq">
        <h2>{t("자주 묻는 것")}</h2>
        <div className="wt-landing-faq-list">
          {FAQ.map((f, i) => (
            <details key={f.q} open={i === 0}>
              <summary>{t(f.q)}<span className="wt-landing-faq-ic"><IconPlus size={20} /></span></summary>
              <p className="muted">{bold(t(f.a))}</p>
            </details>
          ))}
          <div className="wt-landing-hr" />
        </div>
      </section>

      <footer className="wt-landing-foot">
        <b>{t("LORE 웹툰 스튜디오")}</b>
        <nav>
          <Link href="/legal/terms">{t("이용약관")}</Link>
          <Link href="/legal/privacy">{t("개인정보처리방침")}</Link>
          <span>{t("1:1 문의")}</span>
        </nav>
        <div className="wt-landing-badges">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/asm-icon.png" alt="" />AI SW MAESTRO</span>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/msit-icon.png" alt="" />{t("과학기술정보통신부")}</span>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <span><img src="/static/badges/iitp-icon.png" alt="" />{t("정보통신기획평가원(IITP)")}</span>
        </div>
        <LangSwitch className="wt-landing-lang" />
        <span className="dim wt-landing-copy">© 2026 LORE</span>
      </footer>
    </div>
  );
}
