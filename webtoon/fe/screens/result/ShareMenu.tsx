"use client";

import { useEffect, useState } from "react";
import { useT } from "../../lib/i18n";
import { copyLink, shareNative, SHARE_TARGETS, shareUrl, type ShareTarget } from "../../lib/share";
import { IconShare } from "../../ui/Icons";
import { track } from "../../lib/track";
import "./i18n";

/* 「공유」 단추 하나. 폰이 자기 공유 화면을 들고 있으면 그것을 먼저 띄우고,
 * 없을 때만(대개 PC) 우리 목록을 편다. 완성본과 편집실이 같이 쓴다. */
export default function ShareMenu({
  runId,
  episode = 1,
  title,
  character,
  className = "btn btn-w",
  iconOnly = false,
}: {
  runId: string;
  episode?: number;
  title?: string;
  character?: string;
  className?: string;
  iconOnly?: boolean;
}) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [said, setSaid] = useState("");

  useEffect(() => {
    if (!said) return;
    const t = window.setTimeout(() => setSaid(""), 2400);
    return () => window.clearTimeout(t);
  }, [said]);

  const url = shareUrl(runId, episode);
  const text = title ? (character ? `${character} · ${title}` : title) : t("LORE 로 만든 웹툰");

  /* 폰의 공유 화면은 사용자가 닫아도 「됐다」고 돌려준다(share.ts). 그래서 공유가
     실제로 됐는지는 모르고, 「어디로 보내려고 눌렀나」까지만 남긴다(#413). */
  const start = async () => {
    const native = await shareNative(url, text);
    track("share_click", { run: runId, ep: episode, target: native ? "native" : "menu" });
    if (native) return;
    setOpen((was) => !was);
  };

  const pick = async (tg: ShareTarget) => {
    track("share_click", { run: runId, ep: episode, target: tg.key });
    if (tg.href) {
      window.open(tg.href(url, text), "_blank", "noopener,noreferrer");
      setOpen(false);
      const copied = await copyLink(url);
      setSaid(tg.key === "postype" && copied ? t("링크를 복사했어요 — 글에 붙여 넣으세요") : "");
      return;
    }
    if (tg.run) {
      setOpen(false);
      const ok = await tg.run(url, text);
      setSaid(ok ? "" : t("카카오톡 공유를 열지 못했어요"));
      return;
    }
    setOpen(false);
    const copied = await copyLink(url);
    setSaid(copied ? t("링크를 복사했어요") : t("복사하지 못했어요 — 주소창을 그대로 쓰세요"));
  };

  return (
    <span className="wt-share">
      <button type="button" className={className} onClick={start} aria-expanded={open}
              aria-label={t("공유")} title={t("공유")}>
        <IconShare size={iconOnly ? 18 : 18} />{!iconOnly && ` ${t("공유")}`}
      </button>
      {open && (
        <span className="wt-share-list" role="menu">
          {SHARE_TARGETS.map((tg) => (
            <button key={tg.key} type="button" role="menuitem" className="wt-share-one"
                    onClick={() => pick(tg)}>
              {t(tg.label)}
            </button>
          ))}
        </span>
      )}
      {said && <span className="wt-share-toast" role="status">{said}</span>}
    </span>
  );
}
