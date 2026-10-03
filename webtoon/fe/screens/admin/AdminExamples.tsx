"use client";

/**
 * 예시 작품 관리(#619) — 관리자(`role = ADMIN`)만.
 *
 * 예시 작품은 저장소에 두지 않고 번들(zip)로 각 환경에 올린다(#614 · #616). 이 화면은 그 관리자 API 의 얼굴이다.
 *
 * - 올리기: 번들 여러 개를 한 번에. 운영·staging 은 CloudFront 앞단 WAF 가 큰 본문을 막아서 S3 로 직접 올리고
 *   서버가 키로 읽는다(`adminImportBundle`). 「검사만」을 켜면 아무것도 안 쓰고 심을 수 있는지만 본다.
 * - 목록: 표지 · 제목 · 쪽 · 공개 · 순서 · 번들 내보내기 · 내리기(예시 해제 + 비공개, 작품은 안 지움).
 * - 같은 환경에서 만든 작품은 그림을 옮길 필요 없이 작품 번호로 예시 지정.
 *
 * 관리자용 운영 화면이라 문구를 번역하지 않는다.
 */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  adminBundleUrl, adminExamples, adminImportBundle, adminTakeDownExample, adminUpdateExample, coverUrl,
  type AdminExampleRow,
} from "../../lib/api";
import type { Go } from "../../lib/nav";
import "./AdminExamples.css";

type UploadLine = { name: string; state: "wait" | "busy" | "ok" | "err"; text: string };

const STATUS_TEXT: Record<string, string> = {
  PLANTED: "심었어요",
  EXISTS: "이미 있어요(건드리지 않음)",
  DRY_RUN: "검사 통과(아직 안 심음)",
};

export default function AdminExamples({ go }: { go: Go }) {
  const { status, user } = useAuth();
  const isAdmin = status === "authenticated" && user?.role === "ADMIN";

  const [rows, setRows] = useState<AdminExampleRow[] | null>(null);
  const [loadErr, setLoadErr] = useState("");
  const [rowBusy, setRowBusy] = useState<string | null>(null);
  const [rowErr, setRowErr] = useState("");

  const load = useCallback(async () => {
    setLoadErr("");
    try {
      setRows((await adminExamples()).examples);
    } catch (e) {
      setLoadErr(e instanceof Error ? e.message : "목록을 못 불러왔어요");
    }
  }, []);

  useEffect(() => { if (isAdmin) void load(); }, [isAdmin, load]);

  /* ---- 올리기 ---- */
  const [files, setFiles] = useState<File[]>([]);
  const [dryRun, setDryRun] = useState(true);
  const [lines, setLines] = useState<UploadLine[]>([]);
  const [uploading, setUploading] = useState(false);

  const upload = async () => {
    if (!files.length || uploading) return;
    setUploading(true);
    const next: UploadLine[] = files.map((f) => ({ name: f.name, state: "wait", text: "기다리는 중" }));
    setLines([...next]);
    /* 하나씩 차례로 — 한꺼번에 올리면 서버가 동시에 그림 수십 장을 올리느라 느려지고, 어느 것이 실패했는지 섞인다. */
    for (let i = 0; i < files.length; i++) {
      next[i] = { ...next[i], state: "busy", text: dryRun ? "검사하는 중" : "올리는 중" };
      setLines([...next]);
      try {
        const got = await adminImportBundle(files[i], dryRun);
        next[i] = { ...next[i], state: "ok", text: `${STATUS_TEXT[got.status] ?? got.status} · ${got.title} · ${got.pages}쪽` };
      } catch (e) {
        next[i] = { ...next[i], state: "err", text: e instanceof Error ? e.message : "실패했어요" };
      }
      setLines([...next]);
    }
    setUploading(false);
    if (!dryRun) void load();
  };

  /* ---- 목록에서 바꾸기 ---- */
  const change = async (runId: string, act: () => Promise<unknown>) => {
    setRowBusy(runId);
    setRowErr("");
    try {
      await act();
      await load();
    } catch (e) {
      setRowErr(e instanceof Error ? e.message : "바꾸지 못했어요");
    } finally {
      setRowBusy(null);
    }
  };

  /* ---- 작품 번호로 지정 ---- */
  const [pickRun, setPickRun] = useState("");

  if (status === "loading") {
    return <div className="wt-wrap wt-page wt-adm" aria-busy="true" />;
  }
  if (!isAdmin) {
    return (
      <div className="wt-wrap wt-page wt-adm">
        <h1>예시 작품 관리</h1>
        <p className="muted">관리자 계정으로 로그인해야 볼 수 있어요.</p>
        <div><button type="button" className="btn btn-w btn-sm" onClick={() => go("landing")}>웹툰 첫 화면으로</button></div>
      </div>
    );
  }

  return (
    <div className="wt-wrap wt-page wt-adm">
      <div className="wt-adm-head">
        <h1>예시 작품 관리</h1>
        <p className="muted">
          예시 작품은 저장소에 두지 않고 번들(zip)로 이 환경에 올립니다. 같은 작품 번호가 이미 있으면 건드리지 않아요.
        </p>
      </div>

      <section className="card wt-adm-card">
        <h2>번들 올리기</h2>
        <div className="wt-adm-row">
          <input type="file" accept=".zip,application/zip" multiple aria-label="번들 zip 고르기"
                 onChange={(e) => { setFiles(Array.from(e.target.files ?? [])); setLines([]); }} />
          <label className="wt-adm-check">
            <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} />
            검사만 하기(아무것도 안 씀)
          </label>
          <button type="button" className="btn btn-p btn-sm" disabled={!files.length || uploading} onClick={() => void upload()}>
            {uploading ? "진행 중…" : dryRun ? `${files.length}개 검사` : `${files.length}개 올리기`}
          </button>
        </div>
        {lines.length > 0 && (
          <ul className="wt-adm-lines">
            {lines.map((l) => (
              <li key={l.name} className={`is-${l.state}`}>
                <b>{l.name}</b><span>{l.text}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card wt-adm-card">
        <h2>같은 환경의 작품을 예시로</h2>
        <p className="muted">이 환경에서 만든 작품은 그림을 옮길 필요 없이 작품 번호로 지정하면 됩니다.</p>
        <div className="wt-adm-row">
          <input className="field" value={pickRun} placeholder="작품 번호(run_id)" aria-label="작품 번호"
                 onChange={(e) => setPickRun(e.target.value.trim())} />
          <button type="button" className="btn btn-w btn-sm" disabled={!pickRun || rowBusy !== null}
                  onClick={() => void change(pickRun, async () => { await adminUpdateExample(pickRun, { example: true }); setPickRun(""); })}>
            예시로 지정
          </button>
        </div>
      </section>

      <section className="card wt-adm-card">
        <div className="wt-adm-listhead">
          <h2>예시 작품 {rows ? `${rows.length}편` : ""}</h2>
          <button type="button" className="btn btn-w btn-sm" onClick={() => void load()}>새로고침</button>
        </div>
        {loadErr && <p className="wt-adm-err">{loadErr}</p>}
        {rowErr && <p className="wt-adm-err">{rowErr}</p>}
        {rows && rows.length === 0 && <p className="muted">아직 예시가 없어요. 위에서 번들을 올려 주세요.</p>}
        {rows && rows.length > 0 && (
          <table className="wt-adm-table">
            <thead>
              <tr><th>표지</th><th>제목</th><th>쪽</th><th>공개</th><th>순서</th><th /></tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.runId} aria-busy={rowBusy === r.runId}>
                  <td>
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img className="wt-adm-cover" src={coverUrl(r.runId, 1)} alt="" loading="lazy" />
                  </td>
                  <td>
                    <button type="button" className="linkish" onClick={() => go("result", { run: r.runId })}>{r.title || r.runId}</button>
                    <div className="dim wt-adm-sub">{r.genre}{r.genre ? " · " : ""}{r.runId}</div>
                  </td>
                  <td>{r.pages}</td>
                  <td>
                    <button type="button" className={`sw${r.isPublic ? "" : " off"}`} role="switch" aria-checked={r.isPublic}
                            aria-label={`${r.title} 공개`} disabled={rowBusy !== null}
                            onClick={() => void change(r.runId, () => adminUpdateExample(r.runId, { public: !r.isPublic }))}><i /></button>
                  </td>
                  <td>
                    <input className="field wt-adm-order" type="number" min={0} defaultValue={r.order ?? ""} aria-label={`${r.title} 순서`}
                           disabled={rowBusy !== null}
                           onBlur={(e) => {
                             const v = e.target.value.trim();
                             const next = v === "" ? null : Number(v);
                             if (next === r.order || (next !== null && (!Number.isInteger(next) || next < 0))) return;
                             void change(r.runId, () => adminUpdateExample(r.runId, { example: true, order: next }));
                           }} />
                  </td>
                  <td className="wt-adm-acts">
                    <a className="btn btn-w btn-sm" href={adminBundleUrl(r.runId)} download>번들</a>
                    <button type="button" className="btn btn-w btn-sm" disabled={rowBusy !== null}
                            onClick={() => void change(r.runId, () => adminTakeDownExample(r.runId))}>내리기</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
