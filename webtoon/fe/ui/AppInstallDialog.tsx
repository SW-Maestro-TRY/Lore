"use client";

/**
 * 앱 설치 배너를 누르면 뜨는 창(2026-11-06).
 * 위: 앱은 아직 준비 중 — 이메일을 남기면 출시날 50크레딧.
 * 아래: 지금 바로 쓸 수 있는 「홈 화면에 추가」를 기기·버전별로 안내한다.
 */
import { useEffect, useState, type FormEvent } from "react";
import { useAuth } from "@common/auth/useAuth";
import { joinAppWaitlist } from "../lib/api";
import { registerDict, useLang, useT } from "../lib/i18n";
import { canInstall, isIos, onInstallChange, promptInstall } from "../lib/push";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";
import "./AppInstallDialog.css";

export type AppDevice = "ios" | "android";

registerDict({
  "아직 앱 출시는 준비중이에요!": { en: "The LORE app is coming soon!", ja: "アプリはまだ準備中です！", zh: "应用还在准备中！" },
  "이메일을 알려주시면 출시날 50크레딧을 드릴게요!": { en: "Leave your email and get 50 credits on launch day!", ja: "メールを登録すると、リリース日に50クレジットをプレゼント！", zh: "留下邮箱，上线当天送你50积分！" },
  "이메일": { en: "Email", ja: "メール", zh: "邮箱" },
  "알림 받기": { en: "Notify me", ja: "通知を受け取る", zh: "接收通知" },
  "이메일 주소를 확인해 주세요.": { en: "Please check your email address.", ja: "メールアドレスを確認してください。", zh: "请检查邮箱地址。" },
  "신청했어요! 출시날 메일로 알려드릴게요.": { en: "You're in! We'll email you on launch day.", ja: "登録しました！リリース日にメールでお知らせします。", zh: "已登记！上线当天会发邮件通知你。" },
  "지금 홈 화면에 추가하기": { en: "Add to Home Screen now", ja: "今すぐホーム画面に追加", zh: "现在添加到主屏幕" },
  "바로 추가하기": { en: "Add now", ja: "今すぐ追加", zh: "立即添加" },
  "내 폰": { en: "My phone", ja: "この端末", zh: "我的手机" },
  "iOS 26 이상": { en: "iOS 26 or later", ja: "iOS 26以降", zh: "iOS 26 及以上" },
  "iOS 16.4 ~ 18": { en: "iOS 16.4 – 18", ja: "iOS 16.4〜18", zh: "iOS 16.4 ~ 18" },
  "iOS 16.3 이하": { en: "iOS 16.3 or earlier", ja: "iOS 16.3以前", zh: "iOS 16.3 及以下" },
  "Safari 오른쪽 아래 ⋯ → 공유 → ‘홈 화면에 추가’": { en: "Safari: ⋯ at bottom right → Share → Add to Home Screen", ja: "Safari 右下の ⋯ → 共有 →「ホーム画面に追加」", zh: "Safari 右下角 ⋯ → 分享 →“添加到主屏幕”" },
  "Chrome 등 다른 브라우저도 공유 메뉴에서 추가할 수 있어요.": { en: "Chrome and other browsers can also add it from the Share menu.", ja: "Chromeなど他のブラウザでも共有メニューから追加できます。", zh: "Chrome 等其他浏览器也可以在分享菜单中添加。" },
  "Safari 아래쪽 공유(□↑) → ‘홈 화면에 추가’": { en: "Safari: Share (□↑) at the bottom → Add to Home Screen", ja: "Safari 下の共有（□↑）→「ホーム画面に追加」", zh: "Safari 底部分享（□↑）→“添加到主屏幕”" },
  "Chrome은 주소창 오른쪽 공유 → ‘홈 화면에 추가’": { en: "Chrome: Share at the right of the address bar → Add to Home Screen", ja: "Chromeはアドレスバー右の共有 →「ホーム画面に追加」", zh: "Chrome：地址栏右侧分享 →“添加到主屏幕”" },
  "Safari에서만 돼요. Safari로 열고 공유(□↑) → ‘홈 화면에 추가’": { en: "Safari only. Open in Safari, then Share (□↑) → Add to Home Screen", ja: "Safariのみ対応。Safariで開いて共有（□↑）→「ホーム画面に追加」", zh: "仅限 Safari。用 Safari 打开后 分享（□↑）→“添加到主屏幕”" },
  "크롬": { en: "Chrome", ja: "Chrome", zh: "Chrome" },
  "삼성 인터넷": { en: "Samsung Internet", ja: "Samsung Internet", zh: "三星浏览器" },
  "오른쪽 위 ⋮ → ‘홈 화면에 추가’ → ‘설치’": { en: "⋮ at top right → Add to Home screen → Install", ja: "右上の ⋮ →「ホーム画面に追加」→「インストール」", zh: "右上角 ⋮ →“添加到主屏幕”→“安装”" },
  "아래 ≡ 메뉴 → ‘현재 페이지 추가’ → ‘홈 화면’": { en: "≡ menu at the bottom → Add page to → Home screen", ja: "下の ≡ メニュー →「ページを追加」→「ホーム画面」", zh: "底部 ≡ 菜单 →“添加页面至”→“主屏幕”" },
});

/** 지금 기기의 iOS 대략 버전. Safari 는 Version/NN 이 실제 OS 와 맞고(iOS 26 은 OS 칸을 18 로 고정), 그 밖은 OS 칸. */
function iosMajorMinor(): [number, number] | null {
  if (!isIos()) return null;
  const ua = navigator.userAgent;
  const v = /Version\/(\d+)(?:\.(\d+))?/.exec(ua) || /OS (\d+)_(\d+)/.exec(ua);
  return v ? [Number(v[1]), Number(v[2] || 0)] : null;
}

export default function AppInstallDialog({ device: initial, where, onClose }: { device: AppDevice; where: string; onClose: () => void }) {
  const t = useT();
  const { lang } = useLang();
  const { user } = useAuth();
  const [email, setEmail] = useState("");
  const [err, setErr] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const [device, setDevice] = useState<AppDevice>(initial);
  const [installable, setInstallable] = useState(false);
  const [ios, setIos] = useState<[number, number] | null>(null);

  useEffect(() => {
    if (user?.email) setEmail((v) => v || user.email);
  }, [user?.email]);
  useEffect(() => {
    setIos(iosMajorMinor());
    setInstallable(canInstall());
    return onInstallChange(() => setInstallable(canInstall()));
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = email.trim();
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) { setErr(true); return; }
    setErr(false); setBusy(true);
    try {
      await joinAppWaitlist(v, where, device, lang);
      track("app_waitlist_join", { where, device, logged_in: !!user });
      setDone(v);
    } catch {
      setErr(true);
    } finally {
      setBusy(false);
    }
  };
  const pick = (d: AppDevice) => { setDevice(d); track("app_install_guide", { where, device: d }); };
  const addNow = async () => {
    track("app_install_prompt", { where });
    const ok = await promptInstall().catch(() => false);
    track("install_prompt_choice", { where, result: ok ? "accepted" : "dismissed" });
  };

  /* 내 폰 버전에 맞는 줄에 표시를 단다 */
  const iosRow = !ios ? null : ios[0] >= 26 ? 0 : (ios[0] > 16 || (ios[0] === 16 && ios[1] >= 4)) ? 1 : 2;
  const iosSteps: [string, string, string?][] = [
    ["iOS 26 이상", "Safari 오른쪽 아래 ⋯ → 공유 → ‘홈 화면에 추가’", "Chrome 등 다른 브라우저도 공유 메뉴에서 추가할 수 있어요."],
    ["iOS 16.4 ~ 18", "Safari 아래쪽 공유(□↑) → ‘홈 화면에 추가’", "Chrome은 주소창 오른쪽 공유 → ‘홈 화면에 추가’"],
    ["iOS 16.3 이하", "Safari에서만 돼요. Safari로 열고 공유(□↑) → ‘홈 화면에 추가’"],
  ];
  const androidSteps: [string, string][] = [
    ["크롬", "오른쪽 위 ⋮ → ‘홈 화면에 추가’ → ‘설치’"],
    ["삼성 인터넷", "아래 ≡ 메뉴 → ‘현재 페이지 추가’ → ‘홈 화면’"],
  ];

  return (
    <Dialog title={t("아직 앱 출시는 준비중이에요!")} className="wt-appdl" onClose={onClose} busy={busy}>
      <p className="wt-appdl-offer"><b>50C</b>{t("이메일을 알려주시면 출시날 50크레딧을 드릴게요!")}</p>
      {done ? <p className="wt-appdl-done" role="status">{t("신청했어요! 출시날 메일로 알려드릴게요.")}<small>{done}</small></p> : (
        <form className="wt-appdl-form" onSubmit={(e) => void submit(e)} noValidate>
          <input className="field" type="email" inputMode="email" autoComplete="email" placeholder={t("이메일")} aria-label={t("이메일")}
                 value={email} onChange={(e) => { setEmail(e.target.value); setErr(false); }} aria-invalid={err} />
          <button type="submit" className="btn btn-p" disabled={busy}>{t("알림 받기")}</button>
        </form>
      )}
      {err && <p className="wt-appdl-err" role="alert">{t("이메일 주소를 확인해 주세요.")}</p>}

      <div className="wt-appdl-home">
        <h3>{t("지금 홈 화면에 추가하기")}</h3>
        <div className="wt-appdl-tabs" role="tablist">
          <button type="button" role="tab" aria-selected={device === "ios"} className={device === "ios" ? "on" : ""} onClick={() => pick("ios")}><AppleMark />iPhone</button>
          <button type="button" role="tab" aria-selected={device === "android"} className={device === "android" ? "on" : ""} onClick={() => pick("android")}><AndroidMark />Android</button>
        </div>
        {device === "android" && installable && <button type="button" className="btn btn-w wt-appdl-now" onClick={() => void addNow()}>{t("바로 추가하기")}</button>}
        <ol className="wt-appdl-steps">
          {device === "ios"
            ? iosSteps.map(([ver, step, more], i) => <li key={ver} className={iosRow === i ? "mine" : ""}>
                <span className="ver">{t(ver)}{iosRow === i && <em>{t("내 폰")}</em>}</span>
                <span className="how">{t(step)}{more && <small>{t(more)}</small>}</span>
              </li>)
            : androidSteps.map(([app, step]) => <li key={app}>
                <span className="ver">{t(app)}</span>
                <span className="how">{t(step)}</span>
              </li>)}
        </ol>
      </div>
    </Dialog>
  );
}

export function AppleMark() {
  return <svg className="wt-os-mark" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="currentColor" d="M16.4 12.6c0-2.4 2-3.6 2.1-3.6-1.1-1.7-2.9-1.9-3.5-1.9-1.5-.2-2.9.9-3.7.9-.8 0-1.9-.9-3.2-.8-1.6 0-3.1 1-4 2.4-1.7 3-.4 7.4 1.2 9.8.8 1.2 1.8 2.5 3 2.4 1.2 0 1.7-.8 3.1-.8 1.5 0 1.9.8 3.2.8 1.3 0 2.1-1.2 2.9-2.4.9-1.4 1.3-2.7 1.3-2.8 0 0-2.4-1-2.4-4zM14 5.4c.7-.8 1.1-1.9 1-3-1 0-2.1.7-2.8 1.5-.6.7-1.2 1.8-1 2.9 1 .1 2.1-.6 2.8-1.4z"/></svg>;
}
export function AndroidMark() {
  return <svg className="wt-os-mark" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="currentColor" d="M17.6 9.5l1.7-3a.4.4 0 10-.7-.4l-1.8 3.1A10.6 10.6 0 0012 8.2c-1.7 0-3.3.4-4.8 1L5.4 6.1a.4.4 0 10-.7.4l1.7 3A9.6 9.6 0 001.5 17h21a9.6 9.6 0 00-4.9-7.5zM7 14.4a1 1 0 110-2 1 1 0 010 2zm10 0a1 1 0 110-2 1 1 0 010 2z"/></svg>;
}

/** 지금 기기로 어느 쪽 안내를 먼저 열지 */
export function guessDevice(): AppDevice {
  if (typeof navigator === "undefined") return "ios";
  return isIos() ? "ios" : /Android/i.test(navigator.userAgent) ? "android" : "ios";
}
