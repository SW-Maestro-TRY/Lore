"use client";

/* 캐릭터 만들어보기 2 · 웹툰 한 컷 — PhotoResult.dc.html / MPhotoResult.dc.html.
 * 그림에는 글자가 없다 — 세계관 딱지와 말풍선은 화면이 얹는다. */
import { useEffect, useRef, useState } from "react";
import { readCharacter, readSharedCard, type Character } from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { copyLink, kakaoAvailable, shareKakao, shareNative } from "../../lib/share";
import { IconClose, IconDownload, IconRetry, IconShare } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import { LimitView } from "./Photo";
import { isLimitError, loadDraft, runTry, lastCardId } from "./draft";
import { track } from "../../lib/track";
import "./i18n";
import "./PhotoResult.css";

const POLL_MS = 2500;
/* 종이 바뀐 카드를 다 그린 뒤 설문을 띄우기까지. 그림을 먼저 볼 틈을 준다. */
const SWAP_SURVEY_DELAY_MS = 5000;

/* 자리의 무게(하네스가 굴린 값) → 카드에 붙는 딱지. 하네스 값은 화면에 그대로 내보내지 않는다 —
   목록에 없는 값이면 딱지를 안 단다. */
const TIER_LABEL: Record<string, string> = { "중심": "주연", "곁": "조연", "스쳐감": "단역", "뜬금": "엑스트라" };

export default function PhotoResult({ id, shared, go, authenticated }: { id: string; shared: boolean; go: Go; authenticated: boolean }) {
  const t = useT();
  const [ch, setCh] = useState<Character | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState<"again" | null>(null);
  const [actErr, setActErr] = useState("");
  const [limited, setLimited] = useState<string | null>(null);
  const [menu, setMenu] = useState(false);
  const [copied, setCopied] = useState(false);
  const [tryN, setTryN] = useState(0);
  const shareRef = useRef<HTMLDivElement>(null);

  /* 읽기 — 공유는 한 번, 내 것은 ready 가 될 때까지 폴링. */
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setCh(null);
    setLoadErr("");
    setSaved(false);
    const tick = async () => {
      try {
        const c = shared ? await readSharedCard(id) : await readCharacter(id);
        if (!alive) return;
        setCh(c);
        if (!shared && c.status === "drawing") timer = setTimeout(tick, POLL_MS);
      } catch (e) {
        if (!alive) return;
        setLoadErr(e instanceof Error ? e.message : t("카드를 못 불러왔습니다"));
      }
    };
    void tick();
    return () => { alive = false; if (timer) clearTimeout(timer); };
  }, [id, shared, tryN]);

  useEffect(() => {
    if (!menu) return;
    const close = (ev: MouseEvent) => {
      if (!shareRef.current?.contains(ev.target as Node)) setMenu(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [menu]);

  /* 내 카드가 다 그려졌는지(또는 실패했는지) — 한 카드에 한 번만(#413). */
  const resultSent = useRef("");
  useEffect(() => {
    if (shared || !ch || ch.status === "drawing" || resultSent.current === ch.id) return;
    resultSent.current = ch.id;
    track("try_result", { character: ch.id, status: ch.status });
  }, [ch, shared]);

  const card = ch?.card;

  /* 종이 바뀐 카드(#331)는 다 그려지고 잠시 뒤 설문을 띄운다 — 카드마다 한 번만.
     카드에 딱지를 붙이는 대신, 당황했는지를 직접 묻고 공유를 권한다. */
  const [survey, setSurvey] = useState<"ask" | "thanks" | null>(null);
  useEffect(() => {
    if (shared || !ch?.card?.lucky || ch.status !== "ready") return;
    const key = `lore_wt_swap_survey:${ch.id}`;
    try { if (localStorage.getItem(key)) return; } catch { /* 저장소가 막혀도 띄운다 */ }
    const cardId = ch.id;
    const timer = setTimeout(() => {
      try { localStorage.setItem(key, "1"); } catch { /* 무시 */ }
      track("swap_survey_view", { character: cardId });
      setSurvey("ask");
    }, SWAP_SURVEY_DELAY_MS);
    return () => clearTimeout(timer);
  }, [ch, shared]);
  const answerSurvey = (result: "confused" | "fine") => {
    track("swap_survey", { character: id, result });
    setSurvey("thanks");
  };
  const url = typeof window === "undefined" ? "" : `${window.location.origin}/webtoon?card=${encodeURIComponent(id)}`;
  const title = card?.twist || ch?.name || t("캐릭터 카드");

  const onShare = async () => {
    const native = await shareNative(url, title);
    track("card_share", { character: id, target: native ? "native" : "menu" });
    if (native) return;
    setMenu((v) => !v);
  };
  const onCopy = async () => {
    track("card_share", { character: id, target: "copy" });
    setMenu(false);
    if (await copyLink(url)) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    }
  };
  const onKakao = async () => {
    track("card_share", { character: id, target: "kakao" });
    setMenu(false);
    if (!(await shareKakao(url))) void onCopy();
  };

  /* 만들기 위저드로 보낸다. 예전에는 여기서 곧장 만들기를 시작했는데, 이야기·장르·
   * 그림체를 사용자가 한 번도 못 고르고 웹툰이 나와 버렸다. */
  const onEpisode = () => {
    track("card_to_webtoon", { character: id, logged_in: authenticated });
    go("create", { step: 1, character: id });
  };

  const onAgain = async () => {
    if (!ch) return;
    track("try_again", { character: ch.id });
    setBusy("again");
    setActErr("");
    // 지금 보는 카드의 입력이 기준이다. 초안(sessionStorage)은 이 카드를 만든 그것일 때만 통째로 쓴다 —
    // 옛 카드를 목록에서 열고 「다시 뽑기」를 누르면 다른 입력으로 만들어지면 안 된다.
    const draft = loadDraft();
    const d = draft && lastCardId() === ch.id
      ? draft
      : { name: ch.name, description: ch.description, world: card?.world || "", photo: draft?.photo };
    try {
      const c = await runTry(d);
      go("card", { id: c.id });
    } catch (e) {
      if (isLimitError(e)) {
        track("limit_view", { kind: "character", logged_in: authenticated });
        setLimited(e instanceof Error ? e.message : "");
      }
      else setActErr(e instanceof Error ? e.message : t("다시 뽑지 못했습니다"));
      setBusy(null);
    }
  };

  if (limited !== null) return <LimitView go={go} message={limited} authenticated={authenticated} />;

  /* 대사는 그림에 안 굽고 운명 아래 칸에 적는다 — 그림이 1화의 참고 그림으로도 쓰여서
     글자를 구우면 1화에 샌다. 옛 카드(dialogue 없음)는 quote 한 줄을 이 캐릭터의 말로 적는다. */
  const dialogue = card?.dialogue?.length
    ? card.dialogue
    : card?.quote ? [{ who: ch?.name || "", mine: true, side: "center" as const, text: card.quote }] : [];
  const ready = !!ch && ch.status === "ready" && !!ch.art_url;

  const art = (
    <div className="wt-ch-res-art">
      {ready ? (
        <>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ch!.art_url!} alt={t("웹툰 한 컷")} />
          {card?.world_label && <span className="badge">{t(card.world_label)}</span>}
        </>
      ) : ch?.status === "error" ? (
        <div className="wt-ch-res-wait">
          <span className="err">{ch.error || t("못 그렸어요")}</span>
          {!shared && (
            <button type="button" className="btn btn-w btn-sm" disabled={busy !== null} onClick={() => void onAgain()}>
              <IconRetry size={16} /> {t("다시 뽑기")}
            </button>
          )}
        </div>
      ) : loadErr ? (
        <div className="wt-ch-res-wait">
          <span className="err">{loadErr}</span>
          <button type="button" className="btn btn-w btn-sm" onClick={() => setTryN((n) => n + 1)}>
            <IconRetry size={16} /> {t("다시 시도")}
          </button>
        </div>
      ) : (
        <>
          <div className="skeleton wt-ch-res-skel" />
          <div className="wt-ch-res-wait"><span className="spin" />{t("그리는 중 · 약 1분")}</div>
        </>
      )}
    </div>
  );

  const shareBtn = (
    <div className="wt-ch-res-share" ref={shareRef}>
      <button type="button" className="btn btn-w" onClick={() => void onShare()}>
        <IconShare size={18} /> {copied ? t("링크를 복사했어요") : t("공유")}
      </button>
      {menu && (
        <div className="wt-ch-res-menu">
          {kakaoAvailable() && <button type="button" onClick={() => void onKakao()}>{t("카카오톡")}</button>}
          <button type="button" onClick={() => void onCopy()}>{t("링크 복사")}</button>
        </div>
      )}
    </div>
  );

  return (
    <>
      <MobileTop
        back={shared ? undefined : { href: "/webtoon?view=try", onClick: () => go("try") }}
        title={t("웹툰 한 컷")}
        right={shared ? undefined : "2 / 3"}
      />
      {!shared && <div className="wt-ch-steps"><span className="on" /><span className="on" /><span /></div>}
      <div className="wt-wrap wt-page">
        <div className="crumb"><span>{t("캐릭터")}</span><i>›</i><b>{t("웹툰 한 컷")}</b><i>›</i><span>{t("1화")}</span></div>
        <div className="wt-ch-res-body">
          {ch ? (
            <h2 className="wt-ch-res-mtitle">{card?.twist || ch.name}</h2>
          ) : (
            <div className="skeleton wt-ch-res-mtitle" style={{ height: 28, borderRadius: 8 }} />
          )}
          {art}
          <div className="wt-ch-res-side">
            {ch ? (
              <>
                <h2>{card?.twist || ch.name}</h2>
                {card?.role && (
                  <b className="wt-ch-res-role">
                    {card.role}
                    {TIER_LABEL[card.role_tier] && <span className="tag wt-ch-res-tier">{t(TIER_LABEL[card.role_tier])}</span>}
                  </b>
                )}
                <span className="muted wt-ch-res-who">
                  {[ch.name, card?.genre].filter(Boolean).join(" · ")}
                </span>
                {!shared && ch.share_visits != null && ch.share_visits > 0 && (
                  <span className="muted wt-ch-res-share">
                    {t("공유 링크로 {n}명이 봤어요", { n: ch.share_visits })}
                    {ch.share_bonus ? ` · ${t("무료 횟수 +{n}", { n: ch.share_bonus })}` : ""}
                  </span>
                )}
                {!shared && card && (
                  /* 넣은 설정이 어디로 갔나(#329). 점수를 매기지 않고, 카드에 실제로 남은 값을
                     넣은 것 옆에 놓는다. 다르면 다르다고만 말한다 — 다시 뽑기 전에 볼 근거. */
                  <div className="card wt-ch-res-inputs">
                    <b>{t("넣은 설정이 간 곳")}</b>
                    <dl>
                      <dt>{t("이름")}</dt>
                      <dd>{ch.asked_name
                        ? (ch.asked_name === ch.name ? ch.name : t("{a} → {b} (카드가 바꿈)", { a: ch.asked_name, b: ch.name }))
                        : t("안 넣음 → 카드가 지음: {b}", { b: ch.name })}</dd>
                      <dt>{t("설명")}</dt>
                      <dd>{ch.description
                        ? t("「{d}」→ {s}", { d: ch.description, s: [card.species, card.role].filter(Boolean).join(" · ") })
                        : t("안 넣음 → {s}", { s: [card.species, card.role].filter(Boolean).join(" · ") })}</dd>
                      <dt>{t("세계관")}</dt>
                      <dd>{ch.asked_world
                        ? (ch.asked_world === card.world || ch.asked_world === card.world_label
                            ? t(card.world_label)
                            : t("{a} → {b}", { a: ch.asked_world, b: t(card.world_label) }))
                        : t("안 정함 → {b}", { b: t(card.world_label) })}</dd>
                      <dt>{t("사진")}</dt>
                      <dd>{ch.source === "photo" ? t("사진을 보고 외모를 읽었어요") : t("사진 없음 → 설명으로만")}</dd>
                    </dl>
                  </div>
                )}
                {card && card.fate?.length > 0 && (
                  <div className="card wt-ch-res-fate">
                    {card.fate.map((line, i) => <span key={i}>{line}</span>)}
                  </div>
                )}
                {dialogue.length > 0 && (
                  <div className="card wt-ch-res-lines">
                    {dialogue.map((line, i) => (
                      <span key={i} className={line.mine ? "mine" : undefined}>
                        {line.who && <em>{line.who}</em>}“{line.text}”
                      </span>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <>
                <div className="skeleton" style={{ height: 34, borderRadius: 8 }} />
                <div className="skeleton" style={{ height: 26, width: "70%", borderRadius: 8 }} />
                <div className="skeleton" style={{ height: 120, borderRadius: 16 }} />
              </>
            )}

            {shared ? (
              <div className="wt-ch-res-row">
                {shareBtn}
                <button type="button" className="btn btn-p" onClick={() => { track("shared_card_try", { character: id }); go("try"); }}>{t("나도 만들어보기")}</button>
              </div>
            ) : (
              <>
                <button type="button" className="btn btn-p wt-ch-res-pc-cta" disabled={!ready} onClick={onEpisode}>
                  {t("이 캐릭터로 1화 보기")}
                </button>
                <div className="wt-ch-res-row">
                  {shareBtn}
                  {saved ? (
                    <button type="button" className="btn btn-w" onClick={() => go("characters")}>{t("내 캐릭터에 있어요")}</button>
                  ) : (
                    <button type="button" className="btn btn-w" onClick={() => setSaved(true)}><IconDownload size={18} /> {t("내 캐릭터에 저장")}</button>
                  )}
                </div>
                {actErr && <span className="err">{actErr}</span>}
                <div className="wt-ch-res-again">
                  <b>{t("마음에 안 들어요?")}</b>
                </div>
                <button type="button" className="opt wt-ch-res-opt" disabled={busy !== null} onClick={() => void onAgain()}>
                  <b>{busy === "again" ? <span className="spin" /> : <IconRetry size={18} />} {t("다시 뽑기")}</b>
                  <span>{t("같은 입력으로 다른 캐릭터")}</span>
                </button>
              </>
            )}
          </div>
        </div>
      </div>
      {!shared && (
        <div className="mfoot">
          <button type="button" className="btn btn-p" style={{ height: 52 }} disabled={!ready} onClick={onEpisode}>
            {t("이 캐릭터로 1화 보기")}
          </button>
        </div>
      )}

      {survey && (
        <div className="wt-ch-survey" onClick={() => setSurvey(null)}>
          <div className="wt-ch-survey-box" role="dialog" aria-modal="true"
               aria-labelledby="wt-ch-survey-title" onClick={(e) => e.stopPropagation()}>
            <button type="button" className="icon-btn wt-ch-survey-x" aria-label={t("닫기")}
                    onClick={() => setSurvey(null)}><IconClose size={18} /></button>
            {survey === "ask" ? (
              <>
                <h2 id="wt-ch-survey-title">{t("캐릭터가 다른 종으로 나와서 당황하셨나요?")}</h2>
                <p className="muted">{t("아주 가끔, 넣은 것과 다른 종으로 태어나는 캐릭터가 있어요.")}</p>
                <div className="wt-ch-survey-row">
                  <button type="button" className="btn btn-w" onClick={() => answerSurvey("confused")}>{t("당황했어요")}</button>
                  <button type="button" className="btn btn-w" onClick={() => answerSurvey("fine")}>{t("괜찮았어요")}</button>
                </div>
              </>
            ) : (
              <>
                <h2 id="wt-ch-survey-title">{t("답해 주셔서 고마워요!")}</h2>
                <p className="muted">{t("이 카드를 공유해서 친구가 링크를 열면, 무료 기회를 1번 돌려드려요.")}</p>
                <div className="wt-ch-survey-row">
                  <button type="button" className="btn btn-p" autoFocus
                          onClick={() => { setSurvey(null); void onShare(); }}>
                    <IconShare size={18} /> {t("공유")}
                  </button>
                  <button type="button" className="btn btn-w" onClick={() => setSurvey(null)}>{t("닫기")}</button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
