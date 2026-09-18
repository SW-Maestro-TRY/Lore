"use client";

/* 캐릭터 만들어보기 2 · 웹툰 한 컷 — PhotoResult.dc.html / MPhotoResult.dc.html.
 * 그림에는 글자가 없다 — 세계관 딱지와 말풍선은 화면이 얹는다. */
import { useEffect, useRef, useState } from "react";
import { listCharacters, readCharacter, readSharedCard, type Character } from "../../lib/api";
import type { Go } from "../../lib/nav";
import { copyLink, kakaoAvailable, shareKakao, shareNative } from "../../lib/share";
import { startJob } from "../../lib/start";
import { emptyWizardForm } from "../../lib/wizardData";
import { IconDownload, IconRetry, IconShare } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import { LimitView } from "./Photo";
import { isLimitError, loadDraft, runTry } from "./draft";
import "./PhotoResult.css";

const POLL_MS = 2500;
const SHORT_QUOTE = 16;

export default function PhotoResult({ id, shared, go, authenticated }: { id: string; shared: boolean; go: Go; authenticated: boolean }) {
  const [ch, setCh] = useState<Character | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [left, setLeft] = useState<{ free_left: number; free_per_day: number } | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState<"episode" | "again" | null>(null);
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
        setLoadErr(e instanceof Error ? e.message : "카드를 못 불러왔습니다");
      }
    };
    void tick();
    return () => { alive = false; if (timer) clearTimeout(timer); };
  }, [id, shared, tryN]);

  useEffect(() => {
    if (shared) return;
    listCharacters().then((l) => setLeft({ free_left: l.free_left, free_per_day: l.free_per_day })).catch(() => {});
  }, [id, shared]);

  useEffect(() => {
    if (!menu) return;
    const close = (ev: MouseEvent) => {
      if (!shareRef.current?.contains(ev.target as Node)) setMenu(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [menu]);

  const card = ch?.card;
  const url = typeof window === "undefined" ? "" : `${window.location.origin}/webtoon?card=${encodeURIComponent(id)}`;
  const title = card?.twist || ch?.name || "캐릭터 카드";

  const onShare = async () => {
    if (await shareNative(url, title)) return;
    setMenu((v) => !v);
  };
  const onCopy = async () => {
    setMenu(false);
    if (await copyLink(url)) {
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    }
  };
  const onKakao = async () => {
    setMenu(false);
    if (!(await shareKakao(url))) void onCopy();
  };

  const onEpisode = async () => {
    if (!ch) return;
    setBusy("episode");
    setActErr("");
    try {
      const job = await startJob({
        ...emptyWizardForm(),
        characterId: id,
        name: ch.name,
        character: ch.description || card?.twist || "",
        genre: card?.genre || card?.world_label || "",
        style: card?.style || "webtoon_lock_bg",
        quality: "surf",
        mode: "simple",
        agreeIp: true,
        photos: [],
      }, authenticated);
      go("running", { job }, { replace: true });
    } catch (e) {
      setActErr(e instanceof Error ? e.message : "1화를 시작하지 못했습니다");
      setBusy(null);
    }
  };

  const onAgain = async () => {
    if (!ch) return;
    setBusy("again");
    setActErr("");
    const d = loadDraft() || { name: ch.name, description: ch.description, world: card?.world || "" };
    try {
      const c = await runTry(d);
      go("card", { id: c.id });
    } catch (e) {
      if (isLimitError(e)) setLimited(e instanceof Error ? e.message : "");
      else setActErr(e instanceof Error ? e.message : "다시 뽑지 못했습니다");
      setBusy(null);
    }
  };

  if (limited !== null) return <LimitView go={go} message={limited} />;

  const quote = card?.quote || "";
  const ready = !!ch && ch.status === "ready" && !!ch.art_url;

  const art = (
    <div className="wt-ch-res-art">
      {ready ? (
        <>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={ch!.art_url!} alt="웹툰 한 컷" />
          {card?.world_label && <span className="badge">{card.world_label}</span>}
          {quote && <div className={`wt-ch-res-bubble${quote.length <= SHORT_QUOTE ? " short" : ""}`}>{quote}</div>}
        </>
      ) : ch?.status === "error" ? (
        <div className="wt-ch-res-wait">
          <span className="err">{ch.error || "못 그렸어요"}</span>
          {!shared && (
            <button type="button" className="btn btn-w btn-sm" disabled={busy !== null} onClick={() => void onAgain()}>
              <IconRetry size={16} /> 다시 뽑기
            </button>
          )}
        </div>
      ) : loadErr ? (
        <div className="wt-ch-res-wait">
          <span className="err">{loadErr}</span>
          <button type="button" className="btn btn-w btn-sm" onClick={() => setTryN((n) => n + 1)}>
            <IconRetry size={16} /> 다시 시도
          </button>
        </div>
      ) : (
        <>
          <div className="skeleton wt-ch-res-skel" />
          <div className="wt-ch-res-wait"><span className="spin" />그리는 중 · 약 1분</div>
        </>
      )}
    </div>
  );

  const shareBtn = (
    <div className="wt-ch-res-share" ref={shareRef}>
      <button type="button" className="btn btn-w" onClick={() => void onShare()}>
        <IconShare size={18} /> {copied ? "링크를 복사했어요" : "공유"}
      </button>
      {menu && (
        <div className="wt-ch-res-menu">
          {kakaoAvailable() && <button type="button" onClick={() => void onKakao()}>카카오톡</button>}
          <button type="button" onClick={() => void onCopy()}>링크 복사</button>
        </div>
      )}
    </div>
  );

  return (
    <>
      <MobileTop
        back={shared ? undefined : { href: "/webtoon?view=try", onClick: () => go("try") }}
        title="웹툰 한 컷"
        right={shared ? undefined : "2 / 3"}
      />
      {!shared && <div className="wt-ch-steps"><span className="on" /><span className="on" /><span /></div>}
      <div className="wt-wrap wt-page">
        <div className="crumb"><span>캐릭터</span><i>›</i><b>웹툰 한 컷</b><i>›</i><span>1화</span></div>
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
                {card?.role && <b className="wt-ch-res-role">{card.role}</b>}
                <span className="muted wt-ch-res-who">
                  {[ch.name, card?.genre].filter(Boolean).join(" · ")}
                </span>
                {card && card.fate?.length > 0 && (
                  <div className="card wt-ch-res-fate">
                    <b>운명</b>
                    {card.fate.map((line, i) => <span key={i}>{line}</span>)}
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
                <button type="button" className="btn btn-p" onClick={() => go("try")}>나도 만들어보기</button>
              </div>
            ) : (
              <>
                <button type="button" className="btn btn-p wt-ch-res-pc-cta" disabled={!ready || busy !== null} onClick={() => void onEpisode()}>
                  {busy === "episode" && <span className="spin" style={{ borderTopColor: "#fff" }} />} 이 캐릭터로 1화 보기
                </button>
                <div className="wt-ch-res-row">
                  {shareBtn}
                  {saved ? (
                    <button type="button" className="btn btn-w" onClick={() => go("characters")}>내 캐릭터에 있어요</button>
                  ) : (
                    <button type="button" className="btn btn-w" onClick={() => setSaved(true)}><IconDownload size={18} /> 내 캐릭터에 저장</button>
                  )}
                </div>
                {actErr && <span className="err">{actErr}</span>}
                <div className="wt-ch-res-again">
                  <b>마음에 안 들어요?</b>
                  {left && <span className="dim" style={{ fontSize: 12.5 }}>오늘 남은 다시 뽑기 {left.free_left} / {left.free_per_day}</span>}
                </div>
                <button type="button" className="opt wt-ch-res-opt" disabled={busy !== null} onClick={() => void onAgain()}>
                  <b>{busy === "again" ? <span className="spin" /> : <IconRetry size={18} />} 다시 뽑기</b>
                  <span>같은 입력으로 다른 캐릭터</span>
                </button>
              </>
            )}
          </div>
        </div>
      </div>
      {!shared && (
        <div className="mfoot">
          <button type="button" className="btn btn-p" style={{ height: 52 }} disabled={!ready || busy !== null} onClick={() => void onEpisode()}>
            {busy === "episode" && <span className="spin" style={{ borderTopColor: "#fff" }} />} 이 캐릭터로 1화 보기
          </button>
        </div>
      )}
    </>
  );
}
