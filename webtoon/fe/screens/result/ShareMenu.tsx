"use client";

import { useEffect, useState } from "react";
import { copyLink, shareNative, SHARE_TARGETS, shareUrl, type ShareTarget } from "../../lib/share";
import { IconShare } from "../../ui/Icons";

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
  const [open, setOpen] = useState(false);
  const [said, setSaid] = useState("");

  useEffect(() => {
    if (!said) return;
    const t = window.setTimeout(() => setSaid(""), 2400);
    return () => window.clearTimeout(t);
  }, [said]);

  const url = shareUrl(runId, episode);
  const text = title ? (character ? `${character} · ${title}` : title) : "LORE 로 만든 웹툰";

  const start = async () => {
    if (await shareNative(url, text)) return;
    setOpen((was) => !was);
  };

  const pick = async (t: ShareTarget) => {
    if (t.href) {
      window.open(t.href(url, text), "_blank", "noopener,noreferrer");
      setOpen(false);
      const copied = await copyLink(url);
      setSaid(t.key === "postype" && copied ? "링크를 복사했어요 — 글에 붙여 넣으세요" : "");
      return;
    }
    if (t.run) {
      setOpen(false);
      const ok = await t.run(url, text);
      setSaid(ok ? "" : "카카오톡 공유를 열지 못했어요");
      return;
    }
    setOpen(false);
    const copied = await copyLink(url);
    setSaid(copied ? "링크를 복사했어요" : "복사하지 못했어요 — 주소창을 그대로 쓰세요");
  };

  return (
    <span className="wt-share">
      <button type="button" className={className} onClick={start} aria-expanded={open}
              aria-label="공유" title="공유">
        <IconShare size={iconOnly ? 18 : 18} />{!iconOnly && " 공유"}
      </button>
      {open && (
        <span className="wt-share-list" role="menu">
          {SHARE_TARGETS.map((t) => (
            <button key={t.key} type="button" role="menuitem" className="wt-share-one"
                    onClick={() => pick(t)}>
              {t.label}
            </button>
          ))}
        </span>
      )}
      {said && <span className="wt-share-toast" role="status">{said}</span>}
    </span>
  );
}
