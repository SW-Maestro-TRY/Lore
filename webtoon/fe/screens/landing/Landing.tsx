"use client";

/* 첫 화면 — 보드 Landing.dc.html(PC) · MLanding.dc.html(폰).
 * 위쪽 .top 줄은 그리지 않는다(공용 헤더가 있다). 대신 만들던 작업이 있으면
 * 히어로 위에 알약 하나만 둔다. */
import "./i18n";
import Link from "next/link";
import localFont from "next/font/local";
import { useEffect, useState } from "react";
import { CONTACT_CHANNEL } from "@common/links";
import * as api from "../../lib/api";
import { LangSwitch, useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { IconDownload, IconEdit, IconPlus, IconRetry, IconShare, IconUser } from "../../ui/Icons";
import EditorMock, { CUT_IMG, PAGE_IMG, SHEET_IMG } from "./EditorMock";

/* 글꼴 시험 (2026-09-19, 온보딩 화면에만) — 제목은 Gmarket Sans, 나머지는
 * SUIT. 다른 화면(위자드·편집실 등)은 그대로 Noto Sans KR 이다 — 이 두 훅이
 * 만드는 className 을 이 파일 바깥에서 안 쓰면 다른 화면에 안 번진다. */
const gmarketSans = localFont({
  src: "../../assets/fonts/GmarketSansBold.woff2",
  weight: "700",
  variable: "--font-landing-title",
});
const suit = localFont({
  src: [
    { path: "../../assets/fonts/SUIT-Regular.woff2", weight: "400" },
    { path: "../../assets/fonts/SUIT-Medium.woff2", weight: "500" },
    { path: "../../assets/fonts/SUIT-SemiBold.woff2", weight: "600" },
    { path: "../../assets/fonts/SUIT-Bold.woff2", weight: "700" },
    { path: "../../assets/fonts/SUIT-ExtraBold.woff2", weight: "800" },
  ],
  variable: "--font-landing-body",
});

/* 04 「완성」 칸의 표지 — 항상 같은 고정 예시(「가면 아래의 조건」)를 보여준다.
 *
 * 2026-09-21(#355)에 여기를 "둘러보기 목록 맨 앞(runs[0])"으로 바꿨는데,
 * `/runs`가 최신순이라 아무나 웹툰을 만들어 공개하면 그게 그대로 온보딩
 * 04 칸 표지를 덮어써 버렸다(2026-09-23 리포트) — 온보딩은 "이렇게 나온다"는
 * 고정 견본을 보여줘야지, 방금 만들어진 남의 작품을 보여주면 안 된다.
 * 그래서 다시 고정 run_id로 되돌린다. 이 run은 ExampleWorks(webtoon/be)가
 * webtoon/ai/assets/examples/에서 서버 기동 때마다 DB로 심어 두므로 빠질
 * 일이 없지만, 혹시 몰라 못 받아오면 정적 견본 그림으로 대신한다. */
const DONE_EXAMPLE_RUN_ID = "20260910T132240-ae8c28";
const DONE_FALLBACK = "/static/samples/ex-romance-2.jpg";
import { usePhone } from "./usePhone";
import "./Landing.css";

/* 답의 `**…**` 는 굵게 — 언어마다 어순이 달라 문장을 조각내지 않고 표시만 남긴다. */
const FAQ: { q: string; a: string }[] = [
  { q: "LORE는 어떤 서비스인가요?", a: "사진 한 장과 이름만 주시면 **웹툰 한 화가 통째로** 나오는 스튜디오예요. 캐릭터를 그리고, 이야기를 짓고, 표지부터 마지막 장까지 순서대로 그려 드립니다. 그림을 못 그려도, 이야기를 안 써 봤어도 괜찮아요." },
  { q: "LORE의 개발자는 누구인가요?", a: "**AI·SW 마에스트로 17기**에서 만들고 있는 프로젝트예요. 과학기술정보통신부가 주관하고 정보통신기획평가원(IITP)이 운영하는 AI 소프트웨어 인재 양성 과정에서, 실제로 기획하고 개발하고 있습니다." },
  { q: "그림을 하나도 못 그려도 쓸 수 있나요?", a: "네, 그림을 못 그리셔도 괜찮아요. 사진 한 장과 이름만 있으면 버튼 하나로 끝까지 완성됩니다." },
  { q: "캐릭터를 만드는 데 얼마나 걸리나요?", a: "보통 **몇 분이면** 끝나요. 올린 사진과 그때의 대기 상황에 따라 조금 더 걸릴 수 있어요. 만드는 동안 화면을 보고 있지 않아도 됩니다." },
  { q: "웹툰 1화를 만드는 데 얼마나 걸리나요?", a: "이야기를 짓고 컷을 한 장씩 그리기까지 **보통 5~15분**이 걸려요. 고른 화질이 높을수록, 앞에 기다리는 작업이 있을수록 더 걸립니다. 진행 화면에 지금 몇 번째 걸음인지와 대기 순번이 나와요." },
  { q: "생성하는 동안 다른 일을 해도 되나요?", a: "네. 생성이 시작된 뒤에는 **화면을 계속 보고 있을 필요가 없어요.** 진행 상황은 서버가 갖고 있어서 다른 페이지를 보거나 창을 닫아도 계속 진행되고, 나중에 다시 들어오면 하던 데서 이어집니다. 로그인했거나 알림 받을 이메일을 남겨 두면 다 됐을 때 메일로 알려 드려요." },
  { q: "마음에 들지 않으면 다시 만들 수 있나요?", a: "네. **「2번 확인하며」**를 고르면 캐릭터 시트와 이야기 단계에서 확인하고 다시 만들 수 있어요. 이야기를 다시 지을 때는 크레딧이 더 들지 않고, 원하는 방향을 메모로 적어 줄 수 있습니다. 완성한 뒤에는 편집실에서 마음에 안 드는 컷만 골라 다시 그릴 수 있어요(**한 컷 3크레딧**)." },
  { q: "크레딧은 무엇인가요?", a: "생성 기능을 쓸 때 줄어드는 서비스 안의 이용 단위예요. 가입할 때 **12크레딧**을 드리고, 로그인해 있으면 **날마다 20크레딧**이 자동으로 채워집니다. 웹툰 한 편은 **12크레딧**(가장 높은 화질 「너울」은 18), 컷 하나 다시 그리기는 **3크레딧**, 캐릭터 만들기는 **하루 3번까지 무료**이고 그 뒤로는 2크레딧이에요. 만들다가 취소하거나 도중에 실패하면 **자동으로 돌려드립니다.**" },
  { q: "돈을 내야 하나요?", a: "아니요. **지금은 모든 기능이 무료**이고, 결제 수단을 넣는 곳도 없어요. 크레딧을 돈으로 사는 기능은 아직 준비 중입니다. 유료 기능이 생기면 미리 공지하고 안내해 드릴게요." },
  { q: "로그인하지 않아도 만들 수 있나요?", a: "네. **로그인하지 않아도 웹툰을 끝까지 만들어 볼 수 있어요**(같은 인터넷 연결에서 하루 2편까지). 다만 로그인하지 않고 만든 작품은 **그 브라우저에만 묶여 있어서**, 저장 기록을 지우거나 다른 기기에서 열면 다시 찾지 못할 수 있어요. 만들기 전에 로그인하시거나, 진행 화면에서 알림 받을 이메일을 남겨 두시길 권해요." },
  { q: "만든 웹툰은 어디에서 볼 수 있나요?", a: "로그인했다면 **마이페이지**에 그동안 만든 작품이 모두 모여요. 로그인하지 않았다면 같은 브라우저로 다시 들어왔을 때 목록에 보이고, 알림 메일을 받았다면 그 메일의 링크로 언제든 열 수 있습니다." },
  { q: "내가 만든 웹툰은 다른 사람에게도 공개되나요?", a: "완성한 웹툰은 **기본적으로 둘러보기에 공개돼요.** 로그인한 계정으로 만들었다면 마이페이지에서 언제든 **비공개로 바꿀 수 있습니다.** 로그인 없이 만든 작품은 공개 설정을 바꿀 수 없으니, 남에게 보이면 곤란한 사진이나 이야기는 로그인한 뒤에 만들어 주세요." },
  { q: "업로드한 사진은 어떻게 처리되나요?", a: "올린 사진은 **캐릭터를 만드는 데만** 써요. 캐릭터를 그리려면 사진을 AI 모델에 보내야 해서, 이 과정에서 사진이 국외(미국)의 AI 사업자에게 전송됩니다. 사진 원본은 다시 만들기·문제 확인을 위해 서버에 보관하고 있고, **지워 달라고 요청하시면 지워 드려요.** 자세한 내용은 개인정보처리방침에 적어 두었습니다." },
  { q: "올린 사진이 AI 학습에 쓰이나요?", a: "아니요. **올린 사진과 만들어진 결과물을 AI 모델 학습에 쓰지 않습니다.** 다른 사람의 생성에 참고 이미지로 재사용하지도 않고, 학습용 데이터로 팔거나 넘기지도 않아요. 바깥 AI 사업자에게 보낼 때도 **학습에 쓰지 않는 조건의 방식**으로만 보냅니다." },
  { q: "어떤 사진을 올려야 하나요?", a: "**본인이 찍었거나 쓸 권리가 있는 사진**만 올려 주세요. 다른 사람의 얼굴이 담긴 사진은 그 사람의 동의가 필요하고, 만화·애니메이션·게임 캐릭터처럼 남의 저작물은 올리시면 안 돼요. 올린 사진으로 생긴 문제의 책임은 올린 분에게 있습니다." },
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

export default function Landing({ go }: { go: Go }) {
  const t = useT();
  const phone = usePhone();
  const [need, setNeed] = useState(0);
  const [feat, setFeat] = useState<0 | 1 | 2>(0);

  /* 약속 셋 — 마우스를 올려도 바뀌고, 가만히 둬도 시간이 지나면 저절로 다음으로 넘어간다. */
  useEffect(() => {
    const id = setInterval(() => setFeat((f) => ((f + 1) % 3) as 0 | 1 | 2), 4000);
    return () => clearInterval(id);
  }, []);

  /* 만들던 작업 알약 */
  const [job, setJob] = useState<api.NhJob | null>(null);
  useEffect(() => {
    api.myActiveJobs().then((r) => setJob(r.jobs?.[0] ?? null)).catch(() => {});
  }, []);
  const jobLabel = job
    ? (job.art?.total ? t("{done} / {total}장", { done: job.art.done, total: job.art.total }) : t(job.stage_label))
    : "";


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
    <div className={`wt-landing ${gmarketSans.variable} ${suit.variable}`}>
      {/* 히어로 */}
      <section className="wt-landing-hero">
        {job && (
          <button type="button" className="btn btn-w wt-landing-jobpill" onClick={() => go("running", { job: job.id })}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/static/lou/logo-1-default.png" alt="" />
            {t("만들던 웹툰")} <span className="dim" style={{ fontWeight: 400 }}>· {jobLabel}</span>
          </button>
        )}
        <h1>{t("AI 웹툰 스튜디오, LORE")}</h1>
        <p className="muted">
          {t("내 캐릭터가 이야기 속에서 살아 움직이는 순간.")}
        </p>
        <button type="button" className="btn btn-p wt-landing-cta" onClick={start}>{t("지금 시작하기")}</button>
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
                    <img className="cover wt-landing-cover" src={api.coverUrl(r.run_id, r.cover_page ?? 1, r.cover_episode ?? 1)} alt={t("{title} 표지", { title: titleOf(r) })} />
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
              <img src={api.coverUrl(DONE_EXAMPLE_RUN_ID, 1, 1)} alt=""
                   onError={(e) => { e.currentTarget.onerror = null; e.currentTarget.src = DONE_FALLBACK; }} />
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
        <h2>{phone
          ? <>{t("이야기가 웹툰이 되는 과정,")}<br /><span className="wt-landing-hl">LORE</span>{t(" 하나로 충분합니다")}</>
          : <>{t("이미지 한장이 웹툰이 되는 과정,")}<br /><span className="wt-landing-hl">LORE</span>{t(" 하나로 충분합니다.")}</>}</h2>
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
                  <b style={{ fontSize: phone ? 13 : 14.5, lineHeight: 1.4 }}>{t("몽이는 이 로맨스 판타지 웹툰에서 아주 악마같은 악역 영애예요")}</b>
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
          <EditorMock feat={feat} s={phone ? 0.8 : 1} height={phone ? 360 : 560} who={t("세이엘")} />
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
        <div className="wt-landing-last-text">
          <h2 style={phone ? { whiteSpace: "pre-line" } : undefined}>{t(phone ? "당신의 이야기를\n기다리고 있어요" : "당신의 이야기를 기다리고 있어요")}</h2>
          <button type="button" className="btn wt-landing-last-cta" onClick={start}>{t("만들러가기")}</button>
        </div>
        <div className="wt-landing-last-pic">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/static/samples/ex-romance-2.jpg" alt="" />
        </div>
      </section>

      {/* FAQ */}
      <section className="wt-landing-faq">
        <h2>{t("자주 묻는 것")}</h2>
        <div className="wt-landing-faq-list">
          {/* 처음에는 전부 닫아 둔다 — 첫 항목만 펼쳐 두면 목록이 한쪽으로 기울어
              보이고, 무엇을 물을 수 있는지 한눈에 훑기 어렵다. */}
          {FAQ.map((f) => (
            <details key={f.q}>
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
          <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">{t("1:1 문의")}</a>
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
