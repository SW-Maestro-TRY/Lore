"use client";

/* 마이페이지 — 디자인 캔버스의 MyPage.dc.html / MMyPage.dc.html.
 *
 * 왼쪽 줄(계정 · 크레딧 · 메뉴)과 오른쪽(내 웹툰 그리드 + 내 캐릭터 줄)이다.
 * 작품 카드의 공개 스위치는 둘러보기(Works)와 **같은 규칙**을 쓴다 — 서버에
 * 먼저 보내고 실패하면 되돌린다. 두 화면이 달라지면 같은 작품이 화면마다 다른
 * 상태로 보인다.
 *
 * 「충전」·「내역」은 공용 컴포넌트를 그대로 붙였다(#84) — 계정 크레딧이라
 * 도메인마다 따로 만들 것이 아니고, 잔액도 헤더와 같은 곳을 읽어 값이 맞는다.
 *
 * 「1:1 문의하기」·「이용약관」은 예전(리라이트 전) 이 화면이 공용 껍데기
 * (`@common/mypage/MyPage`)를 빌려 쓸 때 그 껍데기가 고정으로 주던 것이다.
 * 리라이트하며 디자인 캔버스에 없어서 빠졌는데, 실제 서비스에 필요한
 * 자리라 여기서 직접 붙였다(2026-09-19, 사용자 지적). */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import { creditBalance } from "@common/api/credits";
import CreditCharge from "@common/mypage/CreditCharge";
import CreditHistory from "@common/mypage/CreditHistory";
import { LEGAL_LINKS, CONTACT_CHANNEL } from "@common/links";
import {
  coverUrl, listCharacters, myAccountRuns, myBrowserRuns, readAllowance,
  readNotifySetting, setNotifySetting, setVisibility,
  type Allowance, type Character, type RunCard,
} from "../../lib/api";
import type { Go } from "../../lib/nav";
import { LangSwitch, registerDict, useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { IconUser } from "../../ui/Icons";
import { louArt } from "../../lib/louArt";
import "./MyPage.css";

registerDict({
  "마이페이지": { en: "My page", ja: "マイページ", zh: "我的页面" },
  "로그인 안 함": { en: "Not signed in", ja: "未ログイン", zh: "未登录" },
  "크레딧": { en: "Credits", ja: "クレジット", zh: "点数" },
  "한 편 {n} C": { en: "{n} C per episode", ja: "1話 {n} C", zh: "每话 {n} C" },
  "만든 것": { en: "MADE", ja: "作ったもの", zh: "已创作" },
  "재료": { en: "MATERIALS", ja: "素材", zh: "素材" },
  "계정": { en: "ACCOUNT", ja: "アカウント", zh: "账号" },
  "내 웹툰": { en: "My webtoons", ja: "マイウェブトゥーン", zh: "我的漫画" },
  "내 캐릭터": { en: "My characters", ja: "マイキャラクター", zh: "我的角色" },
  "로그아웃": { en: "Sign out", ja: "ログアウト", zh: "退出登录" },
  "언어": { en: "Language", ja: "言語", zh: "语言" },
  "둘러보기": { en: "Browse", ja: "見てまわる", zh: "浏览" },
  "새 웹툰 만들기": { en: "New webtoon", ja: "新しいウェブトゥーン", zh: "新建漫画" },
  "캐릭터 탭으로": { en: "Go to characters", ja: "キャラクタータブへ", zh: "前往角色页" },
  "{n}편": { en: "{n} episodes", ja: "{n}話", zh: "{n} 话" },
  "{n}편 · 나만 보기 {m}": { en: "{n} episodes · {m} private", ja: "{n}話 · 非公開 {m}", zh: "{n} 话 · 私密 {m}" },
  "{n}화": { en: "EP.{n}", ja: "第{n}話", zh: "第{n}话" },
  "공개": { en: "Public", ja: "公開", zh: "公开" },
  "비공개": { en: "Private", ja: "非公開", zh: "私密" },
  "둘러보기에 공개": { en: "Show in Browse", ja: "見てまわるに公開", zh: "在浏览中公开" },
  "편집실": { en: "Editor", ja: "編集室", zh: "编辑室" },
  "바꾸지 못했습니다": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
  "만들기": { en: "Create", ja: "作る", zh: "制作" },
  "아직 만든 웹툰이 없어요": { en: "No webtoons yet", ja: "まだ作品がありません", zh: "还没有作品" },
  "제목 없음": { en: "Untitled", ja: "無題", zh: "无标题" },
  "충전": { en: "Top up", ja: "チャージ", zh: "充值" },
  "내역": { en: "History", ja: "履歴", zh: "记录" },
  "1:1 문의하기": { en: "Contact us", ja: "1:1お問い合わせ", zh: "1:1 咨询" },
  "이용약관": { en: "Terms of use", ja: "利用規約", zh: "使用条款" },
  "개인정보처리방침": { en: "Privacy Policy", ja: "プライバシーポリシー", zh: "隐私政策" },
  "목록을 가져오지 못했어요": { en: "Couldn't load the list", ja: "一覧を読み込めませんでした", zh: "无法加载列表" },
  "서버가 떠 있는지 확인해 주세요": { en: "Please check that the server is running", ja: "サーバーが起動しているか確認してください", zh: "请确认服务器是否已启动" },
  "내 캐릭터로 웹툰 만들기": { en: "Make a webtoon with my character", ja: "マイキャラクターでウェブトゥーンを作る", zh: "用我的角色制作漫画" },
  "설정": { en: "Settings", ja: "設定", zh: "设置" },
  "웹툰이 다 만들어지면 이메일로 알림": {
    en: "Email me when a webtoon is done", ja: "ウェブトゥーンが完成したらメールで通知", zh: "漫画完成时用邮件通知我",
  },
  "부탁하신 웹툰이 다 만들어지면 계정 이메일로 알려 드려요. 게스트로 만들 때 직접 적은 주소는 이 설정과 상관없이 그대로 가요.": {
    en: "We'll email your account address when the webtoon you asked for is ready. An address you type in as a guest still goes through, regardless of this setting.",
    ja: "お願いいただいたウェブトゥーンが完成したら、アカウントのメールアドレスにお知らせします。ゲストとして入力したアドレスは、この設定に関係なくそのまま送られます。",
    zh: "你要的漫画做好后，我们会发邮件到你的账号邮箱。以访客身份填写的地址不受此设置影响，仍会照常发送。",
  },
  "바꾸지 못했어요": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
});

export default function MyPage({ go }: { go: Go }) {
  const t = useT();
  const { user, isAuthenticated, signOut } = useAuth();

  /* 지금은 "내 웹툰"과 "설정" 딱 둘뿐이라 화면을 아예 나누지는 않고
     같은 레일 안에서 본문만 바꾼다 — 나중에 칸이 늘면 그때 공용 탭
     구조(@common/mypage/MyPage 의 Section)로 옮겨도 된다. */
  const [tab, setTab] = useState<"works" | "settings">("works");

  const [runs, setRuns] = useState<RunCard[]>([]);
  const [runsFailed, setRunsFailed] = useState(false);
  const [chars, setChars] = useState<Character[]>([]);
  /* 크레딧 창 — 충전·내역은 공용 것을 그대로 쓴다(계정 크레딧이라 도메인마다
     따로 만들 것이 아니다). 잔액도 헤더와 같은 `/credits/me` 를 읽어야 두
     자리가 같은 값을 말한다. */
  const [credits, setCredits] = useState<number | null>(null);
  const [creditModal, setCreditModal] = useState<"charge" | "history" | null>(null);

  const [allowance, setAllowance] = useState<Allowance | null>(null);

  /* 내 작품 — 로그인했으면 계정 것과 이 브라우저 것을 합친다(기기를 바꾸면
     둘이 다르다). run_id 로 겹치는 것을 걸러낸다. */
  const loadRuns = useCallback(async () => {
    /* 실패와 "그냥 없음"을 가른다 — 전엔 못 받아도 조용히 빈 목록으로
       떨어져서 "웹툰이 다 사라졌다"처럼 보였다. 둘 다 실패했을 때만
       실패로 본다(한쪽만 죽어도 다른 쪽 결과는 보여줄 수 있다). */
    const results = await Promise.allSettled([
      myBrowserRuns(),
      isAuthenticated ? myAccountRuns() : Promise.resolve([] as RunCard[]),
    ]);
    const bothFailed = results.every((r) => r.status === "rejected");
    setRunsFailed(bothFailed);
    const seen = new Set<string>();
    const merged: RunCard[] = [];
    for (const r of results) {
      if (r.status !== "fulfilled") continue;
      for (const one of r.value) {
        if (seen.has(one.run_id)) continue;
        seen.add(one.run_id);
        merged.push(one);
      }
    }
    setRuns(merged);
  }, [isAuthenticated]);

  useEffect(() => { void loadRuns(); }, [loadRuns]);
  useEffect(() => {
    listCharacters().then((l) => setChars(l.characters.filter((c) => c.mine))).catch(() => {});
    readAllowance().then(setAllowance).catch(() => {});
    if (isAuthenticated) creditBalance().then((b) => setCredits(b.balance)).catch(() => {});
  }, [isAuthenticated]);

  /* 설정 탭 — 웹툰 완성 메일. 안 건드렸으면 서버가 켜진 채로 준다. */
  const [notify, setNotify] = useState<boolean | null>(null);
  const [notifyBusy, setNotifyBusy] = useState(false);
  const [notifyErr, setNotifyErr] = useState("");
  useEffect(() => {
    if (!isAuthenticated) return;
    readNotifySetting().then((r) => setNotify(r.on)).catch(() => {});
  }, [isAuthenticated]);

  const toggleNotify = async () => {
    if (notify === null) return;
    const want = !notify;
    setNotify(want);
    setNotifyBusy(true);
    setNotifyErr("");
    try {
      const r = await setNotifySetting(want);
      setNotify(r.on);
    } catch {
      setNotify(!want);
      setNotifyErr(t("바꾸지 못했어요"));
    } finally {
      setNotifyBusy(false);
    }
  };

  const hidden = runs.filter((r) => r.public === false).length;

  return (
    <div className="wt-wrap wt-page wt-my">
      <aside className="wt-my-rail">
        <div className="card wt-my-me">
          <span className="wt-my-avatar"><IconUser size={18} /></span>
          <b>{user?.email?.split("@")[0] || t("마이페이지")}</b>
          <span className="dim">{user?.email ?? t("로그인 안 함")}</span>
        </div>

        {isAuthenticated && (
          <div className="card wt-my-credit">
            <span className="dim">{t("크레딧")}</span>
            <b>◈ {(credits ?? allowance?.balance ?? 0).toLocaleString()} <span>C</span></b>
            <div className="wt-my-creditacts">
              <button type="button" className="btn btn-p btn-sm" onClick={() => { track("charge_open", { where: "mypage" }); setCreditModal("charge"); }}>{t("충전")}</button>
              <button type="button" className="btn btn-w btn-sm" onClick={() => setCreditModal("history")}>{t("내역")}</button>
            </div>
          </div>
        )}

        <div className="card rail wt-my-menu">
          <small>{t("만든 것")}</small>
          <button type="button" className={tab === "works" ? "on" : ""} onClick={() => setTab("works")}>
            {t("내 웹툰")} <span className="dim">{runs.length}</span>
          </button>
          <small>{t("재료")}</small>
          <button type="button" onClick={() => go("characters")}>
            {t("내 캐릭터")} <span className="dim">{chars.length}</span>
          </button>
          {isAuthenticated && (
            <>
              <small>{t("설정")}</small>
              <button type="button" className={tab === "settings" ? "on" : ""} onClick={() => setTab("settings")}>
                {t("설정")}
              </button>
            </>
          )}
          <small>{t("계정")}</small>
          <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">{t("1:1 문의하기")}</a>
          {isAuthenticated && (
            <button type="button" onClick={() => void signOut()}>{t("로그아웃")}</button>
          )}
          <a href={LEGAL_LINKS.terms} target="_blank" rel="noopener noreferrer">{t("이용약관")}</a>
          <a href={LEGAL_LINKS.privacy} target="_blank" rel="noopener noreferrer">{t("개인정보처리방침")}</a>
        </div>

        <div className="card wt-my-lang">
          <label>{t("언어")}</label>
          <LangSwitch />
        </div>
      </aside>

      <div className="wt-my-main">
        {tab === "works" && (
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("내 웹툰")}</h2>
                <span className="muted">
                  {hidden > 0
                    ? t("{n}편 · 나만 보기 {m}", { n: runs.length, m: hidden })
                    : t("{n}편", { n: runs.length })}
                </span>
              </div>
              <div className="wt-my-headacts">
                <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기")}</button>
                <button type="button" className="btn btn-p" onClick={() => go("entry")}>{t("새 웹툰 만들기")}</button>
              </div>
            </div>

            {runsFailed && (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("error")} alt="" aria-hidden="true" />
                <b>{t("목록을 가져오지 못했어요")}</b>
                <span className="dim">{t("서버가 떠 있는지 확인해 주세요")}</span>
              </div>
            )}
            {!runsFailed && runs.length === 0 && (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("empty")} alt="" aria-hidden="true" />
                <b>{t("아직 만든 웹툰이 없어요")}</b>
                <button type="button" className="inline-link" onClick={() => go("entry")}>
                  {t("내 캐릭터로 웹툰 만들기")}
                </button>
              </div>
            )}
            {!runsFailed && runs.length > 0 && (
              <div className="wt-my-grid">
                {runs.map((r) => <WorkCard key={r.run_id} run={r} go={go} />)}
              </div>
            )}

            <div className="wt-my-head wt-my-head-sub">
              <div>
                <h2>{t("내 캐릭터")}</h2>
              </div>
              <button type="button" className="btn btn-w" onClick={() => go("characters")}>{t("캐릭터 탭으로")}</button>
            </div>
            <div className="wt-my-chars">
              {chars.map((c) => (
                <button type="button" key={c.id} className="wt-my-char" onClick={() => go("characters")}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={c.art_url || ""} alt="" />
                  <b>{c.name}</b>
                </button>
              ))}
              <button type="button" className="wt-my-char-new" onClick={() => go("try")}>
                <b>+</b>{t("만들기")}
              </button>
            </div>
          </>
        )}

        {tab === "settings" && (
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("설정")}</h2>
              </div>
            </div>
            <div className="card wt-my-setting">
              <div className="wt-my-setting-row">
                <div>
                  <b>{t("웹툰이 다 만들어지면 이메일로 알림")}</b>
                  <span className="muted">{t("부탁하신 웹툰이 다 만들어지면 계정 이메일로 알려 드려요. 게스트로 만들 때 직접 적은 주소는 이 설정과 상관없이 그대로 가요.")}</span>
                </div>
                <button type="button" className={`sw${notify ? "" : " off"}`} role="switch"
                        aria-checked={notify ?? false} aria-label={t("웹툰이 다 만들어지면 이메일로 알림")}
                        disabled={notify === null || notifyBusy} onClick={() => void toggleNotify()}><i /></button>
              </div>
              {notifyErr && <span className="wt-my-err">{notifyErr}</span>}
            </div>
          </>
        )}
      </div>

      {creditModal === "charge" && <CreditCharge onClose={() => setCreditModal(null)} />}
      {creditModal === "history" && <CreditHistory onClose={() => setCreditModal(null)} />}
    </div>
  );
}

/** 작품 한 칸 — 표지 · 제목 · 회차 · 공개 스위치 · 편집실. (둘러보기와 같은 규칙) */
function WorkCard({ run, go }: { run: RunCard; go: Go }) {
  const t = useT();
  const [pub, setPub] = useState(run.public !== false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  /* 서버에 먼저 보내고, 실패하면 되돌린다 — 화면만 바뀌어 있으면 다음에
     들어왔을 때 값이 달라 보인다. */
  const flip = async () => {
    const want = !pub;
    setBusy(true);
    setErr("");
    setPub(want);
    try {
      const out = await setVisibility(run.run_id, want);
      setPub(out.public ?? want);
    } catch {
      setPub(!want);
      setErr(t("바꾸지 못했습니다"));
    } finally {
      setBusy(false);
    }
  };

  const open = () => go("result", { run: run.run_id });

  return (
    <div className="card wt-my-work">
      <button type="button" className="wt-my-cover" onClick={open} aria-label={run.title || t("제목 없음")}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={coverUrl(run.run_id, run.cover_page ?? 1, run.cover_episode ?? 1)} alt="" />
      </button>
      <b>{run.title || t("제목 없음")}</b>
      <span className="muted">{[run.character, run.genre].filter(Boolean).join(" · ")}</span>
      <div className="wt-my-eps">
        {run.episodes.map((n) => (
          <button key={n} type="button" className="ep" onClick={open}>{t("{n}화", { n })}</button>
        ))}
      </div>
      <div className="wt-my-workfoot">
        <span className="wt-my-pub">
          <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                  aria-label={t("둘러보기에 공개")} disabled={busy} onClick={() => void flip()}><i /></button>
          {pub ? t("공개") : t("비공개")}
        </span>
        <button type="button" className="btn btn-w wt-my-edit" onClick={() => go("editor", { run: run.run_id })}>
          {t("편집실")}
        </button>
      </div>
      {err && <span className="wt-my-err">{err}</span>}
    </div>
  );
}
