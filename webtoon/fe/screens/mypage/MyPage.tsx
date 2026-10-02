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
  browseRuns, coverUrl, deleteRun, forgetMyRun, listCharacters, myAccountRuns, myActiveJobs, myBrowserRuns, sheetImageUrl, type NhActiveCard, myLikes, myTrash, readAllowance, recentRuns,
  readNotifySetting, restoreRun, setNotifySetting, setVisibility, withdrawAccount,
  type Allowance, type Character, type NhJob, type RunCard, type TrashCard,
  mySurveyStatus, type SurveyStatus,
} from "../../lib/api";
import { activeJobLabel, activeJobTitle } from "../../lib/jobLabel";
import type { Go } from "../../lib/nav";
import RunStrip from "../../ui/RunStrip";
import { LangSwitch, registerDict, useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { IconUser } from "../../ui/Icons";
import { ConfirmDialog, Dialog } from "../../ui/Dialog";
import { louArt } from "../../lib/louArt";
import AdminSurvey from "./AdminSurvey";
import "./MyPage.css";

/** 「1:1 문의하기」 창구 셋. 카카오톡 채널 주소는 공용(`CONTACT_CHANNEL`)이고, 인스타·X 는
 *  이 화면에서만 쓰는 웹툰 SNS 계정이라 여기 둔다. */
const CONTACT_LINKS: { key: string; label: string; href: string }[] = [
  { key: "kakao", label: "카카오톡", href: CONTACT_CHANNEL },
  { key: "instagram", label: "인스타그램", href: "https://www.instagram.com/lorecomic_/" },
  { key: "x", label: "X", href: "https://x.com/lorecomic_" },
];

registerDict({
  "마이페이지": { en: "My page", ja: "マイページ", zh: "我的页面" },
  "로그인 안 함": { en: "Not signed in", ja: "未ログイン", zh: "未登录" },
  "크레딧": { en: "Credits", ja: "クレジット", zh: "点数" },
  "한 편 {n} C": { en: "{n} C per episode", ja: "1話 {n} C", zh: "每话 {n} C" },
  "만든 것": { en: "MADE", ja: "作ったもの", zh: "已创作" },
  "재료": { en: "MATERIALS", ja: "素材", zh: "素材" },
  "계정": { en: "ACCOUNT", ja: "アカウント", zh: "账号" },
  "내 웹툰": { en: "My webtoons", ja: "マイウェブトゥーン", zh: "我的漫画" },
  "모두 보기": { en: "See all", ja: "すべて見る", zh: "查看全部" },
  "새 캐릭터 만들기": { en: "New character", ja: "新しいキャラクター", zh: "新建角色" },
  "아직 만든 캐릭터가 없어요": { en: "No characters yet", ja: "まだキャラクターがいません", zh: "还没有角色" },
  "그리는 중": { en: "Drawing", ja: "描画中", zh: "绘制中" },
  "못 그렸어요": { en: "Failed", ja: "描けませんでした", zh: "未能绘制" },
  "이 캐릭터로 웹툰": { en: "Make a webtoon", ja: "このキャラでウェブトゥーン", zh: "用此角色做漫画" },
  "내 캐릭터": { en: "My characters", ja: "マイキャラクター", zh: "我的角色" },
  "로그아웃": { en: "Sign out", ja: "ログアウト", zh: "退出登录" },
  "계정 탈퇴": { en: "Delete account", ja: "退会", zh: "注销账号" },
  "탈퇴하면 바로 로그인할 수 없게 되고, 30일이 지나면 계정·작품·캐릭터·올린 사진이 지워져요. 남은 크레딧도 함께 사라져요. 필요한 작품은 먼저 내려받아 두세요.": {
    en: "You'll be signed out right away. After 30 days your account, webtoons, characters and uploaded photos are deleted, and any remaining credits are gone. Download anything you want to keep first.",
    ja: "退会するとすぐにログインできなくなり、30日後にアカウント・作品・キャラクター・写真が削除されます。残りのクレジットも消えます。必要な作品は先に保存してください。",
    zh: "注销后将立即无法登录，30天后账号、作品、角色和上传的照片会被删除，剩余点数也会一并清除。请先下载需要保留的作品。",
  },
  "탈퇴하기": { en: "Delete", ja: "退会する", zh: "注销" },
  "정말 탈퇴할까요? 되돌릴 수 없어요.": { en: "Really delete your account? This can't be undone.", ja: "本当に退会しますか？元に戻せません。", zh: "确定要注销吗？此操作无法撤销。" },
  "탈퇴": { en: "Delete account", ja: "退会", zh: "注销" },
  "취소": { en: "Cancel", ja: "キャンセル", zh: "取消" },
  "지우기": { en: "Delete", ja: "削除", zh: "删除" },
  "휴지통": { en: "Trash", ja: "ゴミ箱", zh: "回收站" },
  "휴지통으로 옮길까요?": { en: "Move to trash?", ja: "ゴミ箱に移しますか？", zh: "移到回收站吗？" },
  "{n}일 안에는 휴지통에서 되살릴 수 있어요.": { en: "You can restore it from the trash within {n} days.", ja: "{n}日以内ならゴミ箱から元に戻せます。", zh: "{n} 天内可以从回收站恢复。" },
  "휴지통이 비어 있어요.": { en: "The trash is empty.", ja: "ゴミ箱は空です。", zh: "回收站是空的。" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "지운 웹툰은 {n}일 동안 여기 있다가 영구 삭제돼요.": { en: "Deleted webtoons stay here for {n} days, then are removed for good.", ja: "削除した作品は{n}日間ここに残り、その後完全に削除されます。", zh: "删除的漫画会在这里保留 {n} 天，之后永久删除。" },
  "{n}일 뒤 영구 삭제": { en: "Deleted for good in {n} days", ja: "{n}日後に完全削除", zh: "{n} 天后永久删除" },
  "오늘 영구 삭제": { en: "Deleted for good today", ja: "本日完全削除", zh: "今天永久删除" },
  "되살리기": { en: "Restore", ja: "元に戻す", zh: "恢复" },
  "되살리지 못했습니다": { en: "Couldn't restore", ja: "元に戻せませんでした", zh: "恢复失败" },
  "지우지 못했습니다": { en: "Couldn't delete", ja: "削除できませんでした", zh: "删除失败" },
  "탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요.": { en: "Couldn't delete the account. Please try again shortly.", ja: "退会できませんでした。しばらくしてからもう一度お試しください。", zh: "注销失败，请稍后再试。" },
  "언어": { en: "Language", ja: "言語", zh: "语言" },
  "둘러보기": { en: "Browse", ja: "見てまわる", zh: "浏览" },
  "새 웹툰 만들기": { en: "New webtoon", ja: "新しいウェブトゥーン", zh: "新建漫画" },
  "최근 본 웹툰": { en: "Recently read", ja: "最近読んだ作品", zh: "最近看过" },
  "찜한 웹툰": { en: "Saved webtoons", ja: "お気に入りの作品", zh: "收藏的漫画" },
  "아직 찜한 웹툰이 없어요. 둘러보기에서 하트를 눌러 보세요.": { en: "Nothing saved yet. Tap a heart in Browse.", ja: "まだお気に入りがありません。見て回るでハートを押してみてください。", zh: "还没有收藏。去浏览里点个爱心吧。" },
  "캐릭터 탭으로": { en: "Go to characters", ja: "キャラクタータブへ", zh: "前往角色页" },
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
  "어디로 문의할까요?": { en: "Where would you like to reach us?", ja: "どちらにお問い合わせしますか？", zh: "想通过哪个渠道联系我们？" },
  "카카오톡": { en: "KakaoTalk", ja: "カカオトーク", zh: "KakaoTalk" },
  "인스타그램": { en: "Instagram", ja: "インスタグラム", zh: "Instagram" },
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

export default function MyPage({ go, initialTab }: { go: Go; initialTab?: "settings" }) {
  const t = useT();
  const { user, isAuthenticated, signOut } = useAuth();

  /* 지금은 "내 웹툰"과 "설정" 딱 둘뿐이라 화면을 아예 나누지는 않고
     같은 레일 안에서 본문만 바꾼다 — 나중에 칸이 늘면 그때 공용 탭
     구조(@common/mypage/MyPage 의 Section)로 옮겨도 된다. */
  const [tab, setTab] = useState<"works" | "chars" | "settings">(initialTab === "settings" ? "settings" : "works");
  const [contactOpen, setContactOpen] = useState(false);
  const [surveyStatus, setSurveyStatus] = useState<SurveyStatus | null>(null);
  useEffect(() => {
    if (!isAuthenticated) { setSurveyStatus(null); return; }
    mySurveyStatus().then(setSurveyStatus).catch(() => setSurveyStatus(null));
  }, [isAuthenticated]);

  const [runs, setRuns] = useState<RunCard[]>([]);
  const [runsFailed, setRunsFailed] = useState(false);
  const [chars, setChars] = useState<Character[]>([]);
  /* 크레딧 창 — 충전·내역은 공용 것을 그대로 쓴다(계정 크레딧이라 도메인마다
     따로 만들 것이 아니다). 잔액도 헤더와 같은 `/credits/me` 를 읽어야 두
     자리가 같은 값을 말한다. */
  const [credits, setCredits] = useState<number | null>(null);
  const [creditModal, setCreditModal] = useState<"charge" | "history" | null>(null);

  const [allowance, setAllowance] = useState<Allowance | null>(null);

  /* 최근 본 것(브라우저)과 찜한 것(계정) — #247. 최근 본 것은 둘러보기 목록에서 카드를 찾아 쓴다. */
  const [recent, setRecent] = useState<RunCard[]>([]);
  const [liked, setLiked] = useState<RunCard[]>([]);
  useEffect(() => {
    const ids = recentRuns();
    if (ids.length === 0) { setRecent([]); return; }
    browseRuns().then((all) => {
      const byId = new Map(all.map((r) => [r.run_id, r]));
      setRecent(ids.map((id) => byId.get(id)).filter((r): r is RunCard => !!r).slice(0, 10));
    }).catch(() => setRecent([]));
  }, []);
  useEffect(() => {
    if (!isAuthenticated) { setLiked([]); return; }
    myLikes().then(setLiked).catch(() => setLiked([]));
  }, [isAuthenticated]);

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

  /* 만드는 중(#548) — 아직 안 끝난 작업. 장면 확인처럼 사람이 누를 때까지 멈춰 있는 작업을
     며칠 뒤에도 여기서 찾아 「이어서 만들기」로 돌아간다. */
  const [active, setActive] = useState<NhJob[]>([]);
  const [activeCards, setActiveCards] = useState<Record<string, NhActiveCard>>({});
  useEffect(() => {
    /* 화면만 보는 자리 — 주소에 #mock-drafts 를 붙이면 서버 대신 가짜 셋을 보여 준다(#548). */
    if (typeof window !== "undefined" && window.location.hash === "#mock-drafts") {
      setActive(MOCK_DRAFTS.jobs);
      setActiveCards(Object.fromEntries(MOCK_DRAFTS.cards.map((c) => [c.id, c])));
      return;
    }
    myActiveJobs().then((r) => {
      setActive(r.jobs ?? []);
      setActiveCards(Object.fromEntries((r.cards ?? []).map((c) => [c.id, c])));
    }).catch(() => setActive([]));
  }, []);

  /* 휴지통(#157) — 지운 작품은 영구 삭제 전까지 여기서 되살린다. 지우기가
     로그인한 사람만 되므로 휴지통도 로그인했을 때만 읽는다. */
  const [trash, setTrash] = useState<TrashCard[]>([]);
  const [keepDays, setKeepDays] = useState(30);
  const [trashOpen, setTrashOpen] = useState(false);
  const loadTrash = useCallback(async () => {
    if (!isAuthenticated) { setTrash([]); return; }
    try {
      const got = await myTrash();
      setTrash(got.runs);
      setKeepDays(got.keepDays);
    } catch {
      setTrash([]);
    }
  }, [isAuthenticated]);
  useEffect(() => { void loadTrash(); }, [loadTrash]);
  useEffect(() => {
    listCharacters().then((l) => setChars(l.characters.filter((c) => c.mine))).catch(() => {});
    readAllowance().then(setAllowance).catch(() => {});
    if (isAuthenticated) creditBalance().then((b) => setCredits(b.balance)).catch(() => {});
  }, [isAuthenticated]);

  /* 설정 탭 — 웹툰 완성 메일. 안 건드렸으면 서버가 켜진 채로 준다. */
  const [notify, setNotify] = useState<boolean | null>(null);
  const [notifyBusy, setNotifyBusy] = useState(false);
  const [notifyErr, setNotifyErr] = useState("");

  /* 계정 탈퇴(#405) — 두 번 눌러야 된다. 첫 번째는 자리를 확인 문구로 바꾸기만 한다.
     서버가 받으면 토큰이 즉시 폐기되므로 화면도 바로 로그아웃으로 넘기고 첫 화면으로 보낸다.
     signOut 은 서버 호출이 실패해도 화면을 로그아웃으로 넘기는 약속이라 여기서도 안전하다. */
  const [withdrawMode, setWithdrawMode] = useState<"idle" | "confirm">("idle");
  const [withdrawBusy, setWithdrawBusy] = useState(false);
  const [withdrawErr, setWithdrawErr] = useState("");
  const withdraw = async () => {
    setWithdrawBusy(true);
    setWithdrawErr("");
    try {
      await withdrawAccount();
      await signOut();
      go("landing", undefined, { replace: true });
    } catch {
      setWithdrawErr(t("탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요."));
      setWithdrawBusy(false);
    }
  };
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
          <button type="button" className={tab === "chars" ? "on" : ""} onClick={() => setTab("chars")}>
            {t("내 캐릭터")} <span className="dim">{chars.length}</span>
          </button>
          {isAuthenticated && (
            <>
              <small>{t("설정")}</small>
              <button type="button" className={tab === "settings" ? "on" : ""} onClick={() => setTab("settings")}>
                {t("설정")}
              </button>
              {/* 휴지통(#157) — 지운 웹툰은 창으로 따로 연다. */}
              <button type="button" className="wt-my-trashmenu"
                      onClick={() => { void loadTrash(); setTrashOpen(true); track("trash_open", { where: "mypage" }); }}>
                {t("휴지통")} <span className="dim">{trash.length}</span>
              </button>
            </>
          )}
          <small>{t("계정")}</small>
          <button type="button" onClick={() => { track("contact_open", { where: "mypage" }); setContactOpen(true); }}>
            {t("1:1 문의하기")}
          </button>
          <button type="button" onClick={() => { track("feedback_open", { where: "mypage" }); go("feedback"); }}>
            {t("피드백 보내기")}
            {surveyStatus && !surveyStatus.done && <span className="dim">+{surveyStatus.reward}C</span>}
          </button>
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
            {contactOpen && (
              <Dialog title={t("어디로 문의할까요?")} onClose={() => setContactOpen(false)}>
                <div className="wt-my-contact">
                  {CONTACT_LINKS.map((c) => (
                    <a key={c.key} className="btn btn-w" href={c.href} target="_blank" rel="noopener noreferrer"
                       onClick={() => { track("contact_pick", { where: "mypage", channel: c.key }); setContactOpen(false); }}>
                      {t(c.label)}
                    </a>
                  ))}
                </div>
              </Dialog>
            )}
            {trashOpen && (
              <Dialog title={t("휴지통")} wide onClose={() => setTrashOpen(false)}
                      sub={t("지운 웹툰은 {n}일 동안 여기 있다가 영구 삭제돼요.", { n: keepDays })}>
                {trash.length === 0 ? (
                  <p className="wt-my-trashempty muted">{t("휴지통이 비어 있어요.")}</p>
                ) : (
                  <div className="wt-my-trash">
                    {trash.map((r) => (
                      <TrashRow key={r.run_id} run={r}
                                onRestored={() => { void loadRuns(); void loadTrash(); }} />
                    ))}
                  </div>
                )}
                <div className="wt-dialog-actions">
                  <button type="button" className="btn btn-w" onClick={() => setTrashOpen(false)}>{t("닫기")}</button>
                </div>
              </Dialog>
            )}
        {tab === "works" && (
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("내 웹툰")}</h2>
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
                {runs.map((r) => (
                  <WorkCard key={r.run_id} run={r} go={go} keepDays={keepDays}
                            onDeleted={() => {
                              setRuns((list) => list.filter((x) => x.run_id !== r.run_id));
                              void loadTrash();
                            }} />
                ))}
              </div>
            )}


            {active.length > 0 && (
              /* 만드는 중(#548) — 「최근 본 웹툰」 자리. 표지 한 줄과 같은 크기로, 눌러서 이어 만든다. */
              <div className="wt-my-strip">
                <div className="wt-strip">
                  <b className="wt-strip-title">{t("만드는 중")} <span className="dim wt-my-draftcount">{active.length}</span></b>
                  <div className="wt-strip-row">
                    {active.map((j) => (
                      <DraftCard key={j.id} job={j} card={activeCards[j.id]}
                                 onOpen={() => { track("resume_job", { job: j.id, status: j.status }); go("running", { job: j.id }); }} />
                    ))}
                  </div>
                </div>
              </div>
            )}

            {isAuthenticated && (
              <div className="wt-my-strip">
                {liked.length > 0 ? (
                  <RunStrip title={t("찜한 웹툰")} runs={liked}
                            onOpen={(r) => { track("works_open", { run: r.run_id, where: "mypage_likes" }); go("result", { run: r.run_id }); }} />
                ) : (
                  <div className="wt-strip">
                    <b className="wt-strip-title">{t("찜한 웹툰")}</b>
                    <span className="muted" style={{ fontSize: 12.5 }}>{t("아직 찜한 웹툰이 없어요. 둘러보기에서 하트를 눌러 보세요.")}</span>
                  </div>
                )}
              </div>
            )}

            <div className="wt-my-head wt-my-head-sub">
              <div>
                <h2>{t("내 캐릭터")}</h2>
              </div>
              <button type="button" className="btn btn-w" onClick={() => setTab("chars")}>{t("모두 보기")}</button>
            </div>
            <div className="wt-my-chars">
              {chars.map((c) => (
                <button type="button" key={c.id} className="wt-my-char" onClick={() => setTab("chars")}>
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

        {tab === "chars" && (
          /* 내 캐릭터(#548) — 내 웹툰처럼 마이페이지 안에서 카드로 본다. 캐릭터 탭으로 나가지 않는다. */
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("내 캐릭터")}</h2>
              </div>
              <div className="wt-my-headacts">
                <button type="button" className="btn btn-p" onClick={() => go("try")}>{t("새 캐릭터 만들기")}</button>
              </div>
            </div>
            {chars.length === 0 ? (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("empty")} alt="" aria-hidden="true" />
                <b>{t("아직 만든 캐릭터가 없어요")}</b>
              </div>
            ) : (
              <div className="wt-my-grid">
                {chars.map((c) => (
                  <div key={c.id} className="card wt-my-work wt-my-charcard">
                    <span className="wt-my-cover">
                      {c.art_url ? (
                        /* eslint-disable-next-line @next/next/no-img-element */
                        <img src={c.art_url} alt="" />
                      ) : (
                        <span className="wt-my-charnoimg" aria-hidden="true" />
                      )}
                      {c.status !== "ready" && (
                        <span className="wt-my-draftchip">{c.status === "drawing" ? t("그리는 중") : t("못 그렸어요")}</span>
                      )}
                    </span>
                    <b>{c.name}</b>
                    <span className="muted">
                      {[c.card?.world_label, c.card?.species, ago(c.created_at, t)].filter(Boolean).join(" · ")}
                    </span>
                    <div className="wt-my-workfoot">
                      <button type="button" className="btn btn-p btn-sm grow" disabled={c.status !== "ready"}
                              onClick={() => go("create", { step: 1, character: c.id })}>{t("이 캐릭터로 웹툰")}</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
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
            {isAuthenticated && (
              <div className="card wt-my-setting">
                <div className="wt-my-setting-row">
                  <div>
                    <b>{t("계정 탈퇴")}</b>
                    <span className="muted">{t("탈퇴하면 바로 로그인할 수 없게 되고, 30일이 지나면 계정·작품·캐릭터·올린 사진이 지워져요. 남은 크레딧도 함께 사라져요. 필요한 작품은 먼저 내려받아 두세요.")}</span>
                  </div>
                  {withdrawMode === "idle" && (
                    <button type="button" className="btn btn-w wt-my-withdraw" onClick={() => setWithdrawMode("confirm")}>{t("탈퇴하기")}</button>
                  )}
                </div>
                {withdrawMode === "confirm" && (
                  <div className="wt-my-withdraw-confirm">
                    <span className="muted">{t("정말 탈퇴할까요? 되돌릴 수 없어요.")}</span>
                    <button type="button" className="btn btn-p" disabled={withdrawBusy} onClick={() => void withdraw()}>{t("탈퇴")}</button>
                    <button type="button" className="btn btn-w" disabled={withdrawBusy} onClick={() => setWithdrawMode("idle")}>{t("취소")}</button>
                  </div>
                )}
                {withdrawErr && <span className="wt-my-err">{withdrawErr}</span>}
              </div>
            )}
            {isAuthenticated && <AdminSurvey />}
          </>
        )}
      </div>

      {creditModal === "charge" && <CreditCharge onClose={() => setCreditModal(null)} />}
      {creditModal === "history" && <CreditHistory onClose={() => setCreditModal(null)} />}
    </div>
  );
}

/** 작품 한 칸 — 표지 · 제목 · 회차 · 공개 스위치 · 편집실. (둘러보기와 같은 규칙) */
/** 휴지통 한 줄 — 표지 · 제목 · 남은 날 · 되살리기(#157). */
function TrashRow({ run, onRestored }: { run: TrashCard; onRestored: () => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const left = Math.max(0, Math.ceil((new Date(run.purge_at).getTime() - Date.now()) / 86_400_000));

  const restore = async () => {
    setBusy(true);
    setErr("");
    try {
      await restoreRun(run.run_id);
      track("run_restore", { run: run.run_id, where: "mypage" });
      onRestored();
    } catch (e) {
      setErr((e as Error).message || t("되살리지 못했습니다"));
      setBusy(false);
    }
  };

  return (
    <div className="wt-my-trashrow">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {run.cover_url ? <img src={run.cover_url} alt="" /> : <span className="wt-my-trashimg" aria-hidden="true" />}
      <div className="wt-my-trashtext">
        <b>{run.title || t("제목 없음")}</b>
        <span className="muted">{left > 0 ? t("{n}일 뒤 영구 삭제", { n: left }) : t("오늘 영구 삭제")}</span>
        {err && <span className="wt-my-err">{err}</span>}
      </div>
      <button type="button" className="btn btn-w" disabled={busy} onClick={() => void restore()}>{t("되살리기")}</button>
    </div>
  );
}


/* 「만드는 중」 화면 확인용 가짜 작업 셋 — 장면 확인 · 그리는 중 · 이야기 고르기. */
const MOCK_DRAFTS: { jobs: NhJob[]; cards: NhActiveCard[] } = (() => {
  const ago = (min: number) => new Date(Date.now() - min * 60000).toISOString();
  const base = { run_id: "mock", error: null, directions: [], pick: null, style: "", style_label: "", stage: "pages",
    stage_index: 2, stages: [], stage_label: "", say: "", checkpoints: true, queue: null, notice: null,
    minutes_left: null, pct: 0, art: null, redraw: null, log: [], elapsed: 0, sheet_ready: false } as unknown as NhJob;
  return {
    jobs: [
      { ...base, id: "mock-a", status: "awaiting_scenes", mode: "own", story: { title: "아이들을 지키는 자", body: "" } } as NhJob,
      { ...base, id: "mock-b", status: "running", mode: "own", story: { title: "비 오는 날의 우산", body: "" }, art: { done: 3, total: 6, retry_page: 0, pages: [1, 2, 3] } } as NhJob,
      { ...base, id: "mock-c", status: "awaiting_pick", mode: "quick", stage: "story" } as NhJob,
    ],
    cards: [
      { id: "mock-a", name: "Isolde Verlaine", created_at: ago(180), updated_at: ago(12) },
      { id: "mock-b", name: "서연화", created_at: ago(90), updated_at: ago(2) },
      { id: "mock-c", name: "몽이", created_at: ago(3000), updated_at: ago(2900) },
    ],
  };
})();

/* 만드는 중 카드(#548) — 시트가 있으면 시트, 없으면 루. 상태는 그림 위 작은 딱지로,
   사람이 답할 차례면 진하게. 아래에 캐릭터 · 길 · 마지막으로 손댄 때. */
function DraftCard({ job, card, onOpen }: { job: NhJob; card?: NhActiveCard; onOpen: () => void }) {
  const t = useT();
  const title = activeJobTitle(job);
  const waiting = job.status.startsWith("awaiting_");
  const meta = [card?.name, ago(card?.updated_at, t)].filter(Boolean).join(" · ");
  return (
    <button type="button" className="wt-strip-item wt-my-draft" onClick={onOpen} aria-label={`${title || t("제목 짓기 전")} · ${t("이어서 만들기")}`}>
      <span className="wt-my-draftcover">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={job.sheet_ready ? sheetImageUrl(job.id) : louArt("generating")} alt="" className={job.sheet_ready ? "" : "lou"} />
        <span className={`wt-my-draftchip${waiting ? " wait" : ""}`}>
          {job.status === "running" && job.art?.total ? t("그리는 중 · {label}", { label: activeJobLabel(job, t) }) : activeJobLabel(job, t)}
        </span>
      </span>
      <span className={`wt-strip-name${title ? "" : " dim"}`}>{title || t("제목 짓기 전")}</span>
      {meta && <span className="wt-my-draftmeta">{meta}</span>}
      <span className="wt-my-draftgo">{t("이어서 만들기")} ›</span>
    </button>
  );
}

/** 「방금 · n분 전 · n시간 전 · n일 전」 */
function ago(iso: string | null | undefined, t: (s: string, p?: Record<string, string | number>) => string): string {
  if (!iso) return "";
  const min = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60000));
  if (min < 1) return t("방금");
  if (min < 60) return t("{n}분 전", { n: min });
  if (min < 60 * 24) return t("{n}시간 전", { n: Math.floor(min / 60) });
  return t("{n}일 전", { n: Math.floor(min / 1440) });
}

function WorkCard({ run, go, keepDays, onDeleted }: { run: RunCard; go: Go; keepDays: number; onDeleted: () => void }) {
  const t = useT();
  const [pub, setPub] = useState(run.public !== false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  /* 지우기(#55) — 두 번 눌러야 된다. 첫 번째는 자리를 확인 문구로 바꾸기만 한다. */
  const [confirming, setConfirming] = useState(false);
  const remove = async () => {
    setBusy(true);
    setErr("");
    try {
      await deleteRun(run.run_id);
      forgetMyRun(run.run_id);
      track("run_delete", { run: run.run_id, where: "mypage" });
      onDeleted();
    } catch (e) {
      setErr((e as Error).message || t("지우지 못했습니다"));
      setBusy(false);
    }
  };

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
      <span className="muted">{[run.character, run.genre && t(run.genre)].filter(Boolean).join(" · ")}</span>
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
        <span className="wt-my-workacts">
          <button type="button" className="btn btn-w wt-my-edit" onClick={() => go("editor", { run: run.run_id })}>
            {t("편집실")}
          </button>
          <button type="button" className="btn btn-w wt-my-edit" disabled={busy} onClick={() => setConfirming(true)}>
            {t("지우기")}
          </button>
        </span>
      </div>
      {confirming && (
        <ConfirmDialog title={t("휴지통으로 옮길까요?")}
                       sub={<><b>{run.title || t("제목 없음")}</b><br />{t("{n}일 안에는 휴지통에서 되살릴 수 있어요.", { n: keepDays })}</>}
                       confirmLabel={t("지우기")} cancelLabel={t("취소")} busy={busy}
                       error={err} onConfirm={() => void remove()} onClose={() => { setConfirming(false); setErr(""); }} />
      )}
      {err && <span className="wt-my-err">{err}</span>}
    </div>
  );
}
