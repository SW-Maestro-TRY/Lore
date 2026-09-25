"use client";

import { useState } from "react";
import { likeRun, unlikeRun } from "../lib/api";
import { useT } from "../lib/i18n";
import { track } from "../lib/track";
import { IconHeart } from "./Icons";
import "./LikeButton.css";

/* 찜 하트(#247) — 둘러보기 카드와 완성본이 같이 쓴다.
 * 로그인 안 했으면 누를 때 안내 한 줄만 보여 준다(로그인 창은 위쪽 헤더 것이다).
 * 서버 답을 받은 뒤에 칠한다 — 먼저 칠했다가 되돌리면 하트가 깜빡인다. */
export default function LikeButton({ runId, liked, count, authenticated, onChange, small = false }: {
  runId: string; liked: boolean; count?: number; authenticated: boolean;
  onChange: (on: boolean, likes: number) => void; small?: boolean;
}) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState("");
  const toggle = async () => {
    if (!authenticated) {
      setNote(t("로그인하면 찜할 수 있어요"));
      setTimeout(() => setNote(""), 2400);
      return;
    }
    const want = !liked;
    track("like_toggle", { run: runId, result: want ? "on" : "off" });
    setBusy(true);
    try {
      const out = await (want ? likeRun(runId) : unlikeRun(runId));
      onChange(out.liked, out.likes);
    } catch {
      /* 그대로 둔다 — 다음에 누르면 다시 시도된다 */
    } finally {
      setBusy(false);
    }
  };
  return (
    <span className={`wt-like${small ? " sm" : ""}`}>
      <button type="button" className={`wt-like-btn${liked ? " on" : ""}`} disabled={busy} onClick={() => void toggle()}
              aria-pressed={liked} aria-label={liked ? t("찜 취소") : t("찜하기")}>
        <IconHeart size={small ? 15 : 17} />{count != null && <span>{count}</span>}
      </button>
      {note && <span className="wt-like-note">{note}</span>}
    </span>
  );
}
