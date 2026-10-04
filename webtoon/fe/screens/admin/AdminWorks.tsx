"use client";

/**
 * 작품 관리(#638) — 관리자(`role = ADMIN`)만.
 *
 * 남의 작품을 처리하는 것은 그 작품의 결과 화면(관리자 칸)에서 한다. 여기는 지나간 처리를 보고, 관리자가 지운
 * 작품을 되살리는 자리다.
 *
 * - 작품 번호로 열기: 신고로 받은 링크 · 번호로 그 작품의 결과 화면을 연다.
 * - 처리 기록: 누가 언제 무엇을 왜 했고, 작가에게 메일이 갔는지.
 * - 관리자 휴지통: 관리자가 삭제 처리한 작품. 보관 기간이 지나면 영구 삭제된다. 그 전에는 되살릴 수 있다.
 * - 작가별 처리 수: 경고 · 비공개 · 삭제를 센다.
 *
 * 관리자용 운영 화면이라 문구를 번역하지 않는다.
 */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  adminModerate, adminModerationLog, adminOwnerCounts, adminRemovedWorks,
  type ModerationRow, type OwnerCountRow, type RemovedRow,
} from "../../lib/api";
import type { Go } from "../../lib/nav";
import { ACTION_TEXT } from "../result/AdminModeration";
import "./AdminExamples.css";

function when(at?: string | null): string {
  if (!at) return "";
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? at : d.toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
}

/** 공유 링크(`/webtoon?run=…`)를 붙여 넣어도 작품 번호만 꺼낸다. */
function runIdOf(text: string): string {
  const t = text.trim();
  const m = t.match(/[?&]run=([^&#\s]+)/);
  return m ? decodeURIComponent(m[1]) : t;
}

export default function AdminWorks({ go }: { go: Go }) {
  const { status, user } = useAuth();
  const isAdmin = status === "authenticated" && user?.role === "ADMIN";

  const [log, setLog] = useState<ModerationRow[] | null>(null);
  const [removed, setRemoved] = useState<RemovedRow[] | null>(null);
  const [owners, setOwners] = useState<OwnerCountRow[] | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [rowErr, setRowErr] = useState("");
  const [open, setOpen] = useState("");

  const load = useCallback(async () => {
    setLoadErr("");
    try {
      const [a, b, c] = await Promise.all([adminModerationLog(200), adminRemovedWorks(), adminOwnerCounts()]);
      setLog(a.rows);
      setRemoved(b.rows);
      setOwners(c.rows);
    } catch (e) {
      setLoadErr(e instanceof Error ? e.message : "못 불러왔어요");
    }
  }, []);

  useEffect(() => { if (isAdmin) void load(); }, [isAdmin, load]);

  const restore = async (runId: string) => {
    setBusy(runId);
    setRowErr("");
    try {
      await adminModerate(runId, "RESTORE", "");
      await load();
    } catch (e) {
      setRowErr(e instanceof Error ? e.message : "되살리지 못했어요");
    } finally {
      setBusy(null);
    }
  };

  if (status === "loading") {
    return <div className="wt-wrap wt-page wt-adm" aria-busy="true" />;
  }
  if (!isAdmin) {
    return (
      <div className="wt-wrap wt-page wt-adm">
        <h1>작품 관리</h1>
        <p className="muted">관리자 계정으로 로그인해야 볼 수 있어요.</p>
        <div><button type="button" className="btn btn-w btn-sm" onClick={() => go("landing")}>웹툰 첫 화면으로</button></div>
      </div>
    );
  }

  return (
    <div className="wt-wrap wt-page wt-adm">
      <div className="wt-adm-head">
        <h1>작품 관리</h1>
        <p className="muted">
          작품을 비공개 · 경고 · 삭제 처리하려면 그 작품의 결과 화면을 여세요. 관리자 칸이 보여요. 처리하면 작가에게 사유가 메일로 가요.
        </p>
      </div>

      <section className="card wt-adm-card">
        <h2>작품 열기</h2>
        <div className="wt-adm-row">
          <input className="field" value={open} placeholder="작품 번호나 공유 링크" aria-label="작품 번호나 공유 링크"
                 onChange={(e) => setOpen(e.target.value)}
                 onKeyDown={(e) => { if (e.key === "Enter" && open.trim()) go("result", { run: runIdOf(open) }); }} />
          <button type="button" className="btn btn-w btn-sm" disabled={!open.trim()} onClick={() => go("result", { run: runIdOf(open) })}>
            열기
          </button>
        </div>
      </section>

      {loadErr && <p className="wt-adm-err">{loadErr}</p>}
      {rowErr && <p className="wt-adm-err">{rowErr}</p>}

      <section className="card wt-adm-card">
        <div className="wt-adm-listhead">
          <h2>관리자 휴지통 {removed ? `${removed.length}편` : ""}</h2>
          <button type="button" className="btn btn-w btn-sm" onClick={() => void load()}>새로고침</button>
        </div>
        {removed && removed.length === 0 && <p className="muted">관리자가 삭제한 작품이 없어요.</p>}
        {removed && removed.length > 0 && (
          <table className="wt-adm-table">
            <thead><tr><th>표지</th><th>제목 · 사유</th><th>작가</th><th>영구 삭제</th><th /></tr></thead>
            <tbody>
              {removed.map((r) => (
                <tr key={r.run_id} aria-busy={busy === r.run_id}>
                  <td>
                    {r.cover_url && (
                      /* eslint-disable-next-line @next/next/no-img-element */
                      <img className="wt-adm-cover" src={r.cover_url} alt="" loading="lazy" />
                    )}
                  </td>
                  <td>
                    <b>{r.title}</b>
                    <div className="dim wt-adm-sub">{r.reason} · {when(r.removed_at)}</div>
                  </td>
                  <td className="wt-adm-sub">{r.owner_email ?? "게스트"}</td>
                  <td className="wt-adm-sub">{when(r.purge_at)}</td>
                  <td className="wt-adm-acts">
                    <button type="button" className="btn btn-w btn-sm" disabled={busy !== null} onClick={() => void restore(r.run_id)}>되살리기</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card wt-adm-card">
        <h2>처리 기록 {log ? `${log.length}건` : ""}</h2>
        {log && log.length === 0 && <p className="muted">아직 처리한 작품이 없어요.</p>}
        {log && log.length > 0 && (
          <table className="wt-adm-table">
            <thead><tr><th>시각</th><th>작품</th><th>처리</th><th>사유</th><th>작가</th><th>메일</th></tr></thead>
            <tbody>
              {log.map((r) => (
                <tr key={r.id}>
                  <td className="wt-adm-sub">{when(r.at)}</td>
                  <td>
                    <button type="button" className="linkish" onClick={() => go("result", { run: r.run_id })}>{r.title || r.run_id}</button>
                  </td>
                  <td><b>{ACTION_TEXT[r.action]}</b><div className="dim wt-adm-sub">{r.admin_email}</div></td>
                  <td className="wt-adm-sub">{r.reason || "—"}</td>
                  <td className="wt-adm-sub">{r.owner_email ?? (r.owner_user_id ? `#${r.owner_user_id}` : "게스트")}</td>
                  <td className="wt-adm-sub">
                    {["HIDE", "REMOVE", "WARN"].includes(r.action) ? (r.notified ? "보냄" : "못 보냄") : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card wt-adm-card">
        <h2>작가별 처리 수</h2>
        <p className="muted">경고 · 비공개 · 삭제를 셉니다. 게스트 작품은 계정이 없어 빠져요.</p>
        {owners && owners.length === 0 && <p className="muted">아직 없어요.</p>}
        {owners && owners.length > 0 && (
          <table className="wt-adm-table">
            <thead><tr><th>작가</th><th>경고</th><th>비공개</th><th>삭제</th><th>합계</th></tr></thead>
            <tbody>
              {owners.map((o) => (
                <tr key={o.user_id}>
                  <td className="wt-adm-sub">{o.email ?? `#${o.user_id}`}</td>
                  <td>{o.warn}</td><td>{o.hide}</td><td>{o.remove}</td><td><b>{o.total}</b></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
