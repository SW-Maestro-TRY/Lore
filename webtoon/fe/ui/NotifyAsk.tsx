"use client";

/* 생성 직전 알림 묻기(#641) — 「웹툰 만들기」를 누르면 바로 시작하지 않고 먼저 묻는다.
 *
 * 한 편이 10분쯤 걸리는데, 알림 받기 단추는 진행 화면 왼쪽 맨 아래에 있어서 거의 아무도 안 누른다.
 * 기다리기 시작하는 이 순간에 묻는다. 무엇을 골라도 생성은 그대로 시작한다 — 묻는 것 때문에 만들기가
 * 막히면 안 된다. 바탕을 누르거나 Esc 를 누르면 시작하지 않고 닫는다(아직 고치고 싶은 사람).
 *
 *   묻기       다 되면 알려 드릴까요?            [괜찮아요] [알려 주세요]
 *   고르기     어떻게 알려 드릴까요?            푸시 알림 / 이메일 알림 / 둘 다
 *   이메일     (게스트만) 메일 주소 칸           로그인한 사람은 계정 메일로 가니 건너뛴다
 *   푸시       브라우저 허용 안내 → 허용 요청     거부됨 · 아이폰(홈 화면에 추가해야 됨)은 안내만
 *
 * 띄울지는 부르는 쪽이 정한다(이 기기 알림이 이미 켜져 있으면 안 띄운다). 게스트도 기기 알림을 받는다 —
 * 서버가 브라우저 단위로 보낸다(JobPush.recipientsOf). */
import { useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import { setNotifySetting } from "../lib/api";
import { registerDict, useLang, useT } from "../lib/i18n";
import { enablePush, pushState, type PushState } from "../lib/push";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";
import "./NotifyAsk.css";

type Step = "ask" | "pick" | "email" | "push";

const LOOKS_LIKE = /^[^@\s]+@[^@\s.]+\.[^@\s]+$/;

/** 띄울 차례인가 — 이 기기 알림이 아직 안 켜졌으면. 상태를 모르면(불러오는 중) 띄우지 않는다. */
export async function shouldAskNotify(): Promise<boolean> {
  try {
    const s = await pushState();
    return s !== "on" && s !== "loading";
  } catch {
    return false;
  }
}

export default function NotifyAsk({ time, onStart, onClose }: {
  /** 「한 편에 ○○ 걸려요」의 ○○ — 고른 품질의 시간 */
  time: string;
  /** 시작한다. 게스트가 메일을 적었으면 그 주소 */
  onStart: (email: string | null) => void;
  /** 시작하지 않고 닫는다 */
  onClose: () => void;
}) {
  const t = useT();
  const { lang } = useLang();
  const { status, user } = useAuth();
  const loggedIn = status === "authenticated";
  const [step, setStep] = useState<Step>("ask");
  const [push, setPush] = useState<PushState>("loading");
  const [wantPush, setWantPush] = useState(false);
  const [email, setEmail] = useState("");
  const [emailErr, setEmailErr] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => { void pushState().then(setPush).catch(() => setPush("off")); }, []);
  const canPush = push === "ready" || push === "denied" || push === "ios-install";

  const finish = (mail: string | null) => {
    track("notify_ask_done", { push: wantPush, email: !!mail || (loggedIn && step !== "ask"), logged_in: loggedIn });
    onStart(mail);
  };

  /* 고른 뒤 다음 걸음 — 메일(게스트) → 푸시 → 시작 */
  const choose = (kind: "push" | "email" | "both") => {
    track("notify_ask_pick", { kind, logged_in: loggedIn });
    const p = kind !== "email";
    const m = kind !== "push";
    setWantPush(p);
    if (m && loggedIn) {
      /* 로그인한 사람은 계정 메일로 간다. 마이페이지에서 꺼 뒀으면 다시 켠다 — 지금 받겠다고 골랐다. */
      void setNotifySetting(true).catch(() => { /* 못 켜도 시작은 한다 */ });
    }
    if (m && !loggedIn) { setStep("email"); return; }
    if (p) { setStep("push"); return; }
    finish(null);
  };

  const emailNext = () => {
    const v = email.trim();
    if (!LOOKS_LIKE.test(v)) { setEmailErr(t("메일 주소를 다시 확인해 주세요")); return; }
    if (wantPush) { setStep("push"); return; }
    finish(v);
  };

  const allow = async () => {
    setBusy(true);
    try {
      const s = await enablePush(lang);
      setPush(s);
      track("notify_ask_push", { result: s });
      if (s === "on") finish(email.trim() || null);
    } catch {
      setPush("off");
    } finally {
      setBusy(false);
    }
  };

  const mailLater = email.trim() || null;

  if (step === "ask") {
    return (
      <Dialog title={t("다 되면 알려 드릴까요?")}
              sub={t("한 편에 {time} 걸려요. 화면을 닫고 다른 일을 하셔도 괜찮아요.", { time })} onClose={onClose}>
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={() => { track("notify_ask_skip"); onStart(null); }}>{t("괜찮아요")}</button>
          <button type="button" className="btn btn-p" onClick={() => setStep("pick")}>{t("알려 주세요")}</button>
        </div>
      </Dialog>
    );
  }

  if (step === "pick") {
    return (
      <Dialog title={t("어떻게 알려 드릴까요?")} onClose={onClose}>
        <div className="wt-notify-picks">
          <button type="button" className="wt-notify-pick" disabled={!canPush} onClick={() => choose("push")}>
            <b>{t("푸시 알림")}</b>
            <span>{canPush ? t("이 기기 화면에 바로 떠요") : t("이 브라우저는 푸시 알림을 받을 수 없어요")}</span>
          </button>
          <button type="button" className="wt-notify-pick" onClick={() => choose("email")}>
            <b>{t("이메일 알림")}</b>
            <span>{loggedIn && user?.email ? t("{email} 로 보내 드려요", { email: user.email }) : t("메일 주소를 적어 주시면 보내 드려요")}</span>
          </button>
          <button type="button" className="wt-notify-pick" disabled={!canPush} onClick={() => choose("both")}>
            <b>{t("둘 다")}</b>
            <span>{t("푸시와 이메일로 함께 알려 드려요")}</span>
          </button>
        </div>
      </Dialog>
    );
  }

  if (step === "email") {
    return (
      <Dialog title={t("어디로 보내 드릴까요?")} sub={t("다 되면 이 주소로 완성 소식을 보내 드려요. 다른 곳에는 쓰지 않아요.")} onClose={onClose}>
        <input className="field wt-notify-email" type="email" inputMode="email" autoComplete="email" autoFocus
               placeholder="name@example.com" aria-label={t("메일 주소")} value={email}
               onChange={(e) => { setEmail(e.target.value); setEmailErr(""); }}
               onKeyDown={(e) => { if (e.key === "Enter" && !e.nativeEvent.isComposing) emailNext(); }} />
        {emailErr && <p className="wt-dialog-err">{emailErr}</p>}
        <div className="wt-dialog-actions">
          <button type="button" className="btn btn-w" onClick={() => setStep("pick")}>{t("뒤로")}</button>
          <button type="button" className="btn btn-p" disabled={!email.trim()} onClick={emailNext}>{wantPush ? t("다음") : t("이 주소로 받고 시작하기")}</button>
        </div>
      </Dialog>
    );
  }

  /* 푸시 안내 */
  return (
    <Dialog title={t("이 기기로 알림 받기")} onClose={onClose} busy={busy}>
      {push === "ready" && (
        <p className="wt-dialog-sub">{t("「알림 허용하기」를 누르면 브라우저가 알림을 받을지 물어요. 「허용」을 눌러 주세요.")}</p>
      )}
      {push === "denied" && (
        <p className="wt-dialog-sub">{t("이 사이트 알림이 막혀 있어요. 주소창 왼쪽의 자물쇠 → 알림에서 「허용」으로 바꾼 뒤 다시 눌러 주세요.")}</p>
      )}
      {push === "ios-install" && (
        <p className="wt-dialog-sub">
          {t("아이폰은 홈 화면에 추가해야 알림을 받을 수 있어요.")}<br />
          {t("사파리 아래 공유 버튼 → 「홈 화면에 추가」 → 홈 화면의 LORE 로 다시 열기")}
        </p>
      )}
      {push === "off" && <p className="wt-dialog-sub">{t("이 브라우저는 푸시 알림을 받을 수 없어요")}</p>}
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => finish(mailLater)}>
          {mailLater || loggedIn ? t("메일로만 받고 시작") : t("그냥 시작하기")}
        </button>
        {(push === "ready" || push === "denied") && (
          <button type="button" className="btn btn-p" disabled={busy} onClick={() => void allow()}>
            {busy ? t("기다리는 중…") : t("알림 허용하기")}
          </button>
        )}
      </div>
    </Dialog>
  );
}

registerDict({
  "다 되면 알려 드릴까요?": { en: "Want us to let you know when it's done?", ja: "完成したらお知らせしましょうか？", zh: "完成后要通知你吗？" },
  "한 편에 {time} 걸려요. 화면을 닫고 다른 일을 하셔도 괜찮아요.": {
    en: "One episode takes {time}. Feel free to close this and do something else.",
    ja: "1話に{time}かかります。画面を閉じてほかのことをしていても大丈夫です。",
    zh: "一话需要 {time}。可以关闭页面去做别的事。",
  },
  "괜찮아요": { en: "No thanks", ja: "大丈夫です", zh: "不用了" },
  "알려 주세요": { en: "Yes, notify me", ja: "知らせてください", zh: "通知我" },
  "어떻게 알려 드릴까요?": { en: "How should we notify you?", ja: "どの方法でお知らせしますか？", zh: "用什么方式通知你？" },
  "푸시 알림": { en: "Push notification", ja: "プッシュ通知", zh: "推送通知" },
  "이 기기 화면에 바로 떠요": { en: "Shows up right on this device", ja: "この端末の画面にすぐ表示されます", zh: "直接显示在这台设备上" },
  "이 브라우저는 푸시 알림을 받을 수 없어요": { en: "This browser can't receive push notifications", ja: "このブラウザはプッシュ通知を受け取れません", zh: "此浏览器无法接收推送通知" },
  "이메일 알림": { en: "Email", ja: "メール通知", zh: "邮件通知" },
  "{email} 로 보내 드려요": { en: "We'll send it to {email}", ja: "{email} にお送りします", zh: "将发送到 {email}" },
  "메일 주소를 적어 주시면 보내 드려요": { en: "Enter your email and we'll send it", ja: "メールアドレスを入力するとお送りします", zh: "填写邮箱后我们会发送给你" },
  "둘 다": { en: "Both", ja: "両方", zh: "两者都要" },
  "푸시와 이메일로 함께 알려 드려요": { en: "We'll send both a push and an email", ja: "プッシュとメールの両方でお知らせします", zh: "推送和邮件都会通知你" },
  "어디로 보내 드릴까요?": { en: "Where should we send it?", ja: "どこにお送りしますか？", zh: "发送到哪里？" },
  "다 되면 이 주소로 완성 소식을 보내 드려요. 다른 곳에는 쓰지 않아요.": {
    en: "We'll email you here when it's done. We won't use it for anything else.",
    ja: "完成したらこのアドレスにお知らせします。ほかの用途には使いません。",
    zh: "完成后会发送到这个地址，不会用于其他用途。",
  },
  "메일 주소": { en: "Email address", ja: "メールアドレス", zh: "邮箱地址" },
  "메일 주소를 다시 확인해 주세요": { en: "Please check your email address", ja: "メールアドレスをもう一度確認してください", zh: "请再确认一下邮箱地址" },
  "뒤로": { en: "Back", ja: "戻る", zh: "返回" },
  "다음": { en: "Next", ja: "次へ", zh: "下一步" },
  "이 주소로 받고 시작하기": { en: "Use this email and start", ja: "このアドレスで受け取って開始", zh: "用这个地址并开始" },
  "이 기기로 알림 받기": { en: "Get notifications on this device", ja: "この端末で通知を受け取る", zh: "在这台设备上接收通知" },
  "「알림 허용하기」를 누르면 브라우저가 알림을 받을지 물어요. 「허용」을 눌러 주세요.": {
    en: "Tap \"Allow notifications\" and your browser will ask. Choose \"Allow\".",
    ja: "「通知を許可」を押すとブラウザが確認します。「許可」を押してください。",
    zh: "点击「允许通知」后浏览器会询问，请选择「允许」。",
  },
  "이 사이트 알림이 막혀 있어요. 주소창 왼쪽의 자물쇠 → 알림에서 「허용」으로 바꾼 뒤 다시 눌러 주세요.": {
    en: "Notifications are blocked for this site. Change it to \"Allow\" from the lock icon left of the address bar, then try again.",
    ja: "このサイトの通知がブロックされています。アドレスバー左の鍵マーク → 通知で「許可」に変えてから、もう一度押してください。",
    zh: "此网站的通知已被阻止。请在地址栏左侧的锁图标 → 通知中改为「允许」后再试一次。",
  },
  "아이폰은 홈 화면에 추가해야 알림을 받을 수 있어요.": {
    en: "On iPhone, add LORE to your home screen to get notifications.",
    ja: "iPhoneはホーム画面に追加すると通知を受け取れます。",
    zh: "iPhone 需要添加到主屏幕才能接收通知。",
  },
  "사파리 아래 공유 버튼 → 「홈 화면에 추가」 → 홈 화면의 LORE 로 다시 열기": {
    en: "Safari share button → \"Add to Home Screen\" → open LORE from your home screen",
    ja: "Safari下の共有ボタン → 「ホーム画面に追加」 → ホーム画面のLOREから開き直す",
    zh: "Safari 下方分享按钮 →「添加到主屏幕」→ 从主屏幕打开 LORE",
  },
  "메일로만 받고 시작": { en: "Just email, and start", ja: "メールだけで開始", zh: "只用邮件并开始" },
  "그냥 시작하기": { en: "Just start", ja: "このまま開始", zh: "直接开始" },
  "기다리는 중…": { en: "Waiting…", ja: "待機中…", zh: "等待中…" },
  "알림 허용하기": { en: "Allow notifications", ja: "通知を許可", zh: "允许通知" },
});
