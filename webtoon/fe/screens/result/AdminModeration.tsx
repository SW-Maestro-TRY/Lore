"use client";

/**
 * 결과 화면의 관리자 칸(#638) — 남의 작품을 비공개 · 삭제 · 경고 처리한다. 관리자 계정에게만 보인다.
 *
 * 비공개 · 삭제 · 경고는 사유가 꼭 있어야 하고 작가에게 메일로 그대로 간다. 다시 공개 · 되살리기는 처리 전
 * 상태로 돌아간다(서버 WorkModeration). 처리 기록 · 관리자 휴지통 · 작가별 처리 수는 「작품 관리」 화면에 있다.
 *
 * 관리자용 운영 화면이라 문구를 번역하지 않는다(예시 작품 관리와 같다).
 */
import { useCallback, useEffect, useState } from "react";
import { adminModerate, adminWorkState, type ModerationAction, type ModerationState } from "../../lib/api";
import type { Go } from "../../lib/nav";
import { Dialog } from "../../ui/Dialog";

/** 사유 고르기 — webtoon/docs/safety.md 의 금지 분류와 같은 갈래. */
export const REASONS = [
  "선정적인 내용",
  "미성년자 성적 묘사",
  "혐오 · 차별 표현",
  "괴롭힘 · 협박",
  "잔혹 · 자해 묘사",
  "저작권 침해 · 다른 작품 도용",
  "개인정보 · 실존 인물 노출",
  "기타",
] as const;

export const ACTION_TEXT: Record<ModerationAction, string> = {
  HIDE: "비공개",
  UNHIDE: "다시 공개",
  REMOVE: "삭제",
  RESTORE: "되살리기",
  WARN: "경고",
};

const ASK: Record<"HIDE" | "REMOVE" | "WARN", { title: string; sub: string; confirm: string }> = {
  HIDE: {
    title: "비공개 처리",
    sub: "둘러보기에서 빠지고 그림도 안 열려요. 작가는 마이페이지에서 계속 보지만 다시 공개로 못 바꿔요.",
    confirm: "비공개 처리",
  },
  REMOVE: {
    title: "삭제 처리",
    sub: "관리자 휴지통으로 가요. 작가는 되살릴 수 없고, 보관 기간이 지나면 영구 삭제돼요. 그 전에는 「작품 관리」에서 되살릴 수 있어요.",
    confirm: "삭제 처리",
  },
  WARN: {
    title: "경고",
    sub: "작품은 그대로 두고 작가에게 알리기만 해요. 기록에 남아 작가별로 세어요.",
    confirm: "경고 보내기",
  },
};

function when(at?: string | null): string {
  if (!at) return "";
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? at : d.toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
}

/** 사유 고르기 + 자세히. 고른 갈래와 적은 글을 「갈래 — 글」 한 줄로 합친다. */
export function ReasonDialog({ kind, busy, error, onSubmit, onClose }: {
  kind: "HIDE" | "REMOVE" | "WARN";
  busy: boolean;
  error: string;
  onSubmit: (reason: string) => void;
  onClose: () => void;
}) {
  const [pick, setPick] = useState<string>("");
  const [detail, setDetail] = useState("");
  const ask = ASK[kind];
  const text = [pick, detail.trim()].filter(Boolean).join(" — ");
  const ready = !!pick && (pick !== "기타" || !!detail.trim());
  return (
    <Dialog title={ask.title} sub={ask.sub} onClose={onClose} busy={busy}>
      <div className="wt-mod-form">
        <label className="wt-mod-label" htmlFor="wt-mod-pick">사유</label>
        <select id="wt-mod-pick" className="field" value={pick} onChange={(e) => setPick(e.target.value)} disabled={busy}>
          <option value="">고르세요</option>
          {REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
        <label className="wt-mod-label" htmlFor="wt-mod-detail">자세히 {pick === "기타" ? "(꼭 적어 주세요)" : "(선택)"}</label>
        <textarea id="wt-mod-detail" className="field" rows={3} maxLength={400} value={detail} disabled={busy}
                  placeholder="예: 3쪽 그림" onChange={(e) => setDetail(e.target.value)} />
        <p className="dim wt-mod-preview">작가에게 보이는 사유: {text || "—"}</p>
      </div>
      {error && <p className="wt-dialog-err">{error}</p>}
      <div className="wt-dialog-actions">
        <button type="button" className="btn btn-w" disabled={busy} onClick={onClose}>취소</button>
        <button type="button" className="btn btn-p" disabled={busy || !ready} onClick={() => onSubmit(text)}>
          {busy ? "처리하는 중…" : ask.confirm}
        </button>
      </div>
    </Dialog>
  );
}

export default function AdminModeration({ runId, go, onChanged }: {
  runId: string;
  go: Go;
  /** 처리 뒤 결과 화면이 다시 읽게 한다 */
  onChanged: () => void;
}) {
  const [state, setState] = useState<ModerationState | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [ask, setAsk] = useState<"HIDE" | "REMOVE" | "WARN" | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  const load = useCallback(async () => {
    setLoadErr("");
    try {
      setState(await adminWorkState(runId));
    } catch (e) {
      setLoadErr(e instanceof Error ? e.message : "처리 상태를 못 불러왔어요");
    }
  }, [runId]);

  useEffect(() => { void load(); }, [load]);

  const act = async (action: ModerationAction, reason = "") => {
    setBusy(true);
    setErr("");
    try {
      const next = await adminModerate(runId, action, reason);
      setAsk(null);
      if (action === "REMOVE") {
        go("admin-works");                 // 휴지통에 들어간 작품은 결과 화면이 404 다
        return;
      }
      setState(next);
      onChanged();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "처리하지 못했어요");
    } finally {
      setBusy(false);
    }
  };

  if (loadErr) {
    return <div className="card wt-mod"><b>관리자</b><p className="err">{loadErr}</p></div>;
  }
  if (!state) {
    return <div className="card wt-mod" aria-busy="true"><b>관리자</b></div>;
  }

  const hidden = state.moderation?.state === "HIDDEN";
  return (
    <div className="card wt-mod">
      <div className="wt-mod-head">
        <b>관리자</b>
        <button type="button" className="linkish" onClick={() => go("admin-works")}>작품 관리 →</button>
      </div>
      <dl className="wt-mod-facts">
        <dt>작가</dt>
        <dd>{state.owner ? `${state.owner.email ?? `#${state.owner.user_id}`} · 처리 받은 수 ${state.owner.counts}` : "게스트 작품 — 알릴 방법이 없어요"}</dd>
        <dt>상태</dt>
        <dd>
          {hidden ? "관리자 비공개" : state.public ? "공개" : "비공개(작가가 내림)"}
          {state.moderation?.reason ? ` · 사유: ${state.moderation.reason}` : ""}
        </dd>
      </dl>
      {state.example ? (
        <p className="dim">예시 작품은 「예시 작품 관리」에서 내려 주세요.</p>
      ) : (
        <div className="wt-mod-acts">
          {hidden ? (
            <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => void act("UNHIDE")}>다시 공개</button>
          ) : (
            <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => { setErr(""); setAsk("HIDE"); }}>비공개 처리</button>
          )}
          <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => { setErr(""); setAsk("WARN"); }}>경고</button>
          <button type="button" className="btn btn-w btn-sm wt-mod-danger" disabled={busy} onClick={() => { setErr(""); setAsk("REMOVE"); }}>삭제 처리</button>
        </div>
      )}
      {err && !ask && <p className="err">{err}</p>}
      {state.history.length > 0 && (
        <ul className="wt-mod-history">
          {state.history.map((h) => (
            <li key={h.id}>
              <span className="dim">{when(h.at)}</span> <b>{ACTION_TEXT[h.action]}</b>
              {h.reason ? ` · ${h.reason}` : ""}
              {["HIDE", "REMOVE", "WARN"].includes(h.action) && <span className="dim"> · {h.notified ? "메일 보냄" : "메일 못 보냄"}</span>}
            </li>
          ))}
        </ul>
      )}
      {ask && (
        <ReasonDialog kind={ask} busy={busy} error={err} onClose={() => setAsk(null)}
                      onSubmit={(reason) => void act(ask, reason)} />
      )}
    </div>
  );
}
