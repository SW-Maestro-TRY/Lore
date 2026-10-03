"use client";

/**
 * 「이 기기로 알림 받기」(#599) — 진행 화면의 알림 칸과 마이페이지 설정 두 곳에만 둔다.
 *
 * 고를 차례가 되거나(상대 인물 · 이야기 · 장면 · 시트) 다 만들어지면 서버가 푸시를
 * 보낸다. 이 컴포넌트는 그 구독을 켜고 끄고, 못 받는 기기에는 이유와 방법을 알려 준다:
 * 아이폰 사파리 탭이면 「홈 화면에 추가」 안내, 안드로이드·크롬이면 설치 단추.
 *
 * 서버에 키가 없거나 브라우저가 푸시를 못 하면 <b>아무것도 안 그린다</b> — 안 되는
 * 단추를 보여 주지 않는다.
 */
import { useEffect, useState } from "react";
import { registerDict, useLang, useT } from "../lib/i18n";
import {
  canInstall, disablePush, enablePush, onInstallChange, promptInstall, pushState, type PushState,
} from "../lib/push";
import "./PushOptIn.css";

registerDict({
  "이 기기로 알림 받기": { en: "Get notifications on this device", ja: "この端末で通知を受け取る", zh: "在此设备接收通知" },
  "고를 차례가 되거나 다 만들어지면 알려 드려요.": { en: "We'll let you know when it's your turn to choose or when it's done.", ja: "選ぶ番になったときや完成したときにお知らせします。", zh: "轮到你选择或制作完成时会通知你。" },
  "이 기기로 알림을 받고 있어요.": { en: "Notifications are on for this device.", ja: "この端末で通知を受け取っています。", zh: "此设备已开启通知。" },
  "이 사이트 알림이 막혀 있어요. 브라우저 설정에서 허용해 주세요.": { en: "Notifications are blocked for this site. Allow them in your browser settings.", ja: "このサイトの通知がブロックされています。ブラウザの設定で許可してください。", zh: "此网站的通知已被屏蔽，请在浏览器设置中允许。" },
  "아이폰은 홈 화면에 추가해야 알림을 받을 수 있어요.": { en: "On iPhone, add LORE to your Home Screen to get notifications.", ja: "iPhoneではホーム画面に追加すると通知を受け取れます。", zh: "iPhone 需要添加到主屏幕才能接收通知。" },
  "사파리 아래 공유 버튼 → 「홈 화면에 추가」 → 홈 화면의 LORE 로 다시 열기": { en: "Tap Share in Safari → \"Add to Home Screen\" → open LORE from your Home Screen", ja: "Safariの共有ボタン →「ホーム画面に追加」→ ホーム画面のLOREから開き直す", zh: "点 Safari 的分享按钮 →「添加到主屏幕」→ 从主屏幕的 LORE 重新打开" },
  "홈 화면에 추가": { en: "Add to Home Screen", ja: "ホーム画面に追加", zh: "添加到主屏幕" },
  "앱처럼 바로 열 수 있어요.": { en: "Open it like an app.", ja: "アプリのようにすぐ開けます。", zh: "可以像应用一样直接打开。" },
  "이 기기로 푸시 알림": { en: "Push notifications on this device", ja: "この端末へのプッシュ通知", zh: "此设备的推送通知" },
  "고를 차례가 되거나 웹툰이 다 만들어지면 이 기기로 알려 드려요. 진행 화면을 보고 있을 때는 보내지 않아요.": { en: "We'll notify this device when it's your turn to choose or your webtoon is done. Not while you're watching the progress screen.", ja: "選ぶ番になったときやウェブトゥーンが完成したときにこの端末へお知らせします。進行画面を見ている間は送りません。", zh: "轮到你选择或网络漫画完成时会通知此设备。正在查看进度页面时不会发送。" },
  "바꾸지 못했어요": { en: "Couldn't change it", ja: "変更できませんでした", zh: "未能更改" },
});

export default function PushOptIn({ variant }: { variant: "progress" | "settings" }) {
  const t = useT();
  const { lang } = useLang();
  const [state, setState] = useState<PushState>("loading");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [installable, setInstallable] = useState(false);

  useEffect(() => {
    let alive = true;
    void pushState().then((s) => { if (alive) setState(s); });
    setInstallable(canInstall());
    const off = onInstallChange(() => setInstallable(canInstall()));
    return () => { alive = false; off(); };
  }, []);

  const toggle = async (on: boolean) => {
    setBusy(true);
    setErr("");
    try {
      setState(on ? await enablePush(lang) : await disablePush());
    } catch {
      setErr(t("바꾸지 못했어요"));
    } finally {
      setBusy(false);
    }
  };

  const install = installable && (
    <div className="wt-push-install">
      <button type="button" className="btn btn-w btn-sm" onClick={() => void promptInstall()}>{t("홈 화면에 추가")}</button>
      <span className="dim">{t("앱처럼 바로 열 수 있어요.")}</span>
    </div>
  );

  if (state === "loading" || (state === "off" && !installable)) return null;

  if (state === "ios-install") {
    return (
      <div className={`wt-push wt-push-${variant}`}>
        <b>{t("아이폰은 홈 화면에 추가해야 알림을 받을 수 있어요.")}</b>
        <span className="dim">{t("사파리 아래 공유 버튼 → 「홈 화면에 추가」 → 홈 화면의 LORE 로 다시 열기")}</span>
      </div>
    );
  }

  if (variant === "settings") {
    return (
      <div className="card wt-my-setting">
        {state !== "off" && (
          <div className="wt-my-setting-row">
            <div>
              <b>{t("이 기기로 푸시 알림")}</b>
              <span className="muted">
                {state === "denied"
                  ? t("이 사이트 알림이 막혀 있어요. 브라우저 설정에서 허용해 주세요.")
                  : t("고를 차례가 되거나 웹툰이 다 만들어지면 이 기기로 알려 드려요. 진행 화면을 보고 있을 때는 보내지 않아요.")}
              </span>
            </div>
            <button type="button" className={`sw${state === "on" ? "" : " off"}`} role="switch"
                    aria-checked={state === "on"} aria-label={t("이 기기로 푸시 알림")}
                    disabled={busy || state === "denied"} onClick={() => void toggle(state !== "on")}><i /></button>
          </div>
        )}
        {install}
        {err && <span className="wt-my-err">{err}</span>}
      </div>
    );
  }

  return (
    <div className={`wt-push wt-push-${variant}`}>
      {state === "on" && <span className="dim">{t("이 기기로 알림을 받고 있어요.")}</span>}
      {state === "denied" && <span className="dim">{t("이 사이트 알림이 막혀 있어요. 브라우저 설정에서 허용해 주세요.")}</span>}
      {state === "ready" && (
        <>
          <button type="button" className="btn btn-w" disabled={busy} onClick={() => void toggle(true)}>
            {t("이 기기로 알림 받기")}
          </button>
          <span className="dim">{t("고를 차례가 되거나 다 만들어지면 알려 드려요.")}</span>
        </>
      )}
      {install}
      {err && <span className="dim">{err}</span>}
    </div>
  );
}
