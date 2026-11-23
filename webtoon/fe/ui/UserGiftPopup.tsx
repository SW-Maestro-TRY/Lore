"use client";

/**
 * 개별 사용자 사과·보상 크레딧 팝업.
 *
 * 로그인한 계정에 미수령 보상(webtoon_user_gift)이 있으면 딱 한 번 안내 팝업을 띄우고,
 * 「크레딧 받기」를 누르면 즉시 지급한 뒤 헤더 잔액까지 갱신한다.
 */
import { useEffect, useState } from "react";
import { notifyCreditsChanged } from "@common/api/credits";
import { claimUserGift, pendingUserGifts, type UserGift } from "../lib/api";
import type { View } from "../lib/nav";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";
import "./UserGiftPopup.css";

/* 작업 중이거나 관리자 화면에서는 가로막지 않는다. */
const QUIET: View[] = ["running", "editor", "create", "admin", "admin-examples", "admin-works", "admin-payments"];

export default function UserGiftPopup({ authenticated, view }: { authenticated: boolean; view: View }) {
  const [gifts, setGifts] = useState<UserGift[]>([]);
  const [dismissed, setDismissed] = useState<number[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");

  useEffect(() => {
    if (!authenticated) {
      setGifts([]);
      return;
    }
    let alive = true;
    pendingUserGifts()
      .then((res) => {
        if (alive && Array.isArray(res?.gifts)) setGifts(res.gifts);
      })
      .catch(() => { /* 못 읽어도 화면을 막지 않는다 */ });
    return () => { alive = false; };
  }, [authenticated]);

  useEffect(() => {
    if (!toast) return;
    const id = window.setTimeout(() => setToast(""), 3200);
    return () => window.clearTimeout(id);
  }, [toast]);

  const active = QUIET.includes(view) ? null : gifts.find((g) => !dismissed.includes(g.id)) ?? null;

  const close = () => {
    if (!active || busy) return;
    setDismissed((prev) => [...prev, active.id]);
    setError("");
  };

  const claim = async () => {
    if (!active || busy) return;
    setBusy(true);
    setError("");
    try {
      const res = await claimUserGift(active.id);
      notifyCreditsChanged(res.balance);
      track("user_gift_claim", { ref: active.ref_key, credits: res.granted });
      setGifts((prev) => prev.filter((g) => g.id !== active.id));
      setToast(`${res.granted > 0 ? `${res.granted} 크레딧이 지급되었어요` : "이미 수령한 보상이에요"}`);
    } catch {
      setError("받지 못했어요. 잠시 뒤 다시 눌러 주세요.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {toast && <div className="wt-gift-toast" role="status">{toast}</div>}
      {active && (
        <Dialog className="wt-gift-dialog" title={active.title} onClose={close} busy={busy}>
          <p className="wt-gift-msg">{active.message}</p>
          <div className="wt-gift-badge">
            <span className="wt-gift-badge-lbl">보상 크레딧</span>
            <strong className="wt-gift-badge-val">+{active.credits}C</strong>
          </div>
          {error && <p className="wt-dialog-err">{error}</p>}
          <div className="wt-dialog-actions">
            <button type="button" className="btn btn-w" disabled={busy} onClick={close}>나중에</button>
            <button type="button" className="btn btn-p" disabled={busy} autoFocus onClick={claim}>
              {busy ? "지급 중…" : `${active.credits} 크레딧 받기`}
            </button>
          </div>
        </Dialog>
      )}
    </>
  );
}
