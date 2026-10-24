"use client";
import { useEffect, useRef, useState } from "react";
import { useT } from "../../lib/i18n";
import "./i18n";
import * as api from "../../lib/community";
import { IconBell } from "../Icons";
import CommunityInbox from "./CommunityInbox";
import "./Community.css";

/* 내 작업실의 종 단추 — 안 읽은 소식 수를 붙이고, 누르면 그 아래에 작은 창(CommunityInbox)을 띄운다.
 * 창 밖을 누르거나 Esc 를 누르면 닫히고, 닫힐 때 종 단추로 초점을 돌려 준다. */
export default function CommunityBell({ admin = false }: { admin?: boolean }) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  // 닫혀 있을 때도 숫자는 보여야 한다 — 알림만 가볍게 한 번 읽는다
  useEffect(() => {
    let alive = true;
    api.notices().then((n) => { if (alive) setUnread(n.filter((x) => !x.read_at).length); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  useEffect(() => {
    if (!open) return;
    const away = (e: MouseEvent) => { if (wrap.current && !wrap.current.contains(e.target as Node)) setOpen(false); };
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") { setOpen(false); button.current?.focus(); } };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", esc);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", esc); };
  }, [open]);
  const label = unread ? t("소식 · 안 읽은 소식 {n}개", { n: unread }) : t("소식");
  return (
    <div className="wt-bell-wrap" ref={wrap}>
      <button ref={button} type="button" className="wt-bell" aria-label={label} title={label}
              aria-expanded={open} aria-haspopup="dialog" onClick={() => setOpen((o) => !o)}>
        <IconBell size={19} />
        {unread > 0 && <span className="wt-bell-dot" aria-hidden="true">{unread > 99 ? "99+" : unread}</span>}
      </button>
      {open && (
        <div className="wt-bell-pop" role="dialog" aria-label={t("소식")}>
          <CommunityInbox admin={admin} onUnread={setUnread} />
        </div>
      )}
    </div>
  );
}
