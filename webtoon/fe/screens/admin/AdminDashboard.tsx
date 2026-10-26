"use client";

/**
 * 관리자 대시보드(#779) — 관리자(`role = ADMIN`)만. 옛 관리자 홈(실시간 패널)을 대신한다.
 *
 * - 개요: 관리자 · 멘토 · 지인을 뺀 방문 · 가입 · 만들기 · 완성과 최소 KPI 세 개(webtoon/docs/analytics.md).
 *   「완성」은 작업이 실제로 끝난 것(job DONE)이다 — 편집실 「이미지로 뽑기」가 아니다.
 * - 작품: 비공개 · 관리자 처리 · 휴지통까지 전부. 누르면 결과 화면이 열리고, 거기 관리자 칸에서 처리한다.
 * - 캐릭터: 남의 비공개 캐릭터까지 전부. 누르면 넣은 글과 카드 글을 다 본다.
 *
 * 칸은 주소(`tab`)가 정한다 — 작품을 열었다가 뒤로 오면 보던 칸으로 돌아온다.
 * 관리자 운영 화면이라 문구를 번역하지 않는다.
 */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  adminCharacter, adminCharacterList, adminOverview, adminWorkList, WebtoonApiError,
  type AdminCharacterDetail, type AdminCharacterRow, type AdminOverview, type AdminPage, type AdminWho,
  type AdminWorkRow, type AdminWorkState,
} from "../../lib/api";
import type { Go } from "../../lib/nav";
import { Dialog } from "../../ui/Dialog";
import "./AdminExamples.css";
import "./AdminDashboard.css";

type Tab = "overview" | "works" | "characters";

function kstToday(): string {
  /* 지금의 KST 벽시계 날짜. 로컬 TZ 가 어디든 UTC+9 로 옮긴 「가짜 UTC」 로 자르면 YYYY-MM-DD 가 나온다. */
  return new Date(Date.now() + 9 * 60 * 60_000).toISOString().slice(0, 10);
}

function addDays(ymd: string, days: number): string {
  const d = new Date(ymd + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function when(at?: string | null): string {
  if (!at) return "";
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? at : d.toLocaleString("ko-KR", { dateStyle: "short", timeStyle: "short" });
}

function errText(e: unknown): string {
  if (e instanceof WebtoonApiError && (e.status === 401 || e.status === 403)) return "로그인이 풀렸어요. 다시 로그인한 뒤 새로고침하세요.";
  return e instanceof Error ? e.message : "못 불러왔어요";
}

export default function AdminDashboard({ go, tab }: { go: Go; tab?: string }) {
  const { status, user } = useAuth();
  const isAdmin = status === "authenticated" && user?.role === "ADMIN";
  const now: Tab = tab === "works" || tab === "characters" ? tab : "overview";

  if (status === "loading") return <div className="wt-wrap wt-page wt-adm" aria-busy="true" />;
  if (!isAdmin) {
    return (
      <div className="wt-wrap wt-page wt-adm">
        <h1>관리자</h1>
        <p className="muted">관리자 계정으로 로그인해야 볼 수 있어요.</p>
        <div><button type="button" className="btn btn-w btn-sm" onClick={() => go("landing")}>웹툰 첫 화면으로</button></div>
      </div>
    );
  }

  return (
    <div className="wt-wrap wt-page wt-adm wt-dash">
      <div className="wt-dash-head">
        <h1>관리자</h1>
        <nav className="wt-dash-links" aria-label="다른 관리 화면">
          <button type="button" className="btn btn-w btn-sm" onClick={() => go("admin-works")}>처리 기록 · 관리자 휴지통</button>
          <button type="button" className="btn btn-w btn-sm" onClick={() => go("admin-examples")}>예시 작품</button>
          <button type="button" className="btn btn-w btn-sm" onClick={() => go("admin-payments")}>결제</button>
          <button type="button" className="btn btn-w btn-sm" onClick={() => go("mypage", { tab: "settings" })}>설문</button>
        </nav>
      </div>

      <div className="wt-dash-tabs" role="tablist">
        {([["overview", "개요"], ["works", "작품"], ["characters", "캐릭터"]] as const).map(([k, label]) => (
          <button key={k} type="button" role="tab" aria-selected={now === k} className={`wt-dash-tab${now === k ? " on" : ""}`}
                  onClick={() => go("admin", k === "overview" ? {} : { tab: k }, { replace: true })}>
            {label}
          </button>
        ))}
      </div>

      {now === "overview" && <Overview />}
      {now === "works" && <Works go={go} />}
      {now === "characters" && <Characters />}
    </div>
  );
}

/* ---- 개요 ------------------------------------------------------------------ */

const WHO: [AdminWho, string][] = [["external", "외부 사용자만"], ["internal", "우리 쪽만"], ["all", "전체"]];

function Overview() {
  const today = kstToday();
  const [from, setFrom] = useState(addDays(today, -6));
  const [to, setTo] = useState(today);
  const [who, setWho] = useState<AdminWho>("external");
  const [data, setData] = useState<AdminOverview | null>(null);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await adminOverview(from, to, who));
      setErr("");
    } catch (e) {
      setErr(errText(e));
    } finally {
      setLoading(false);
    }
  }, [from, to, who]);

  useEffect(() => { void load(); }, [load]);

  const preset = (days: number) => { setFrom(addDays(today, -(days - 1))); setTo(today); };
  const t = data?.totals;
  const peak = data ? Math.max(1, ...data.daily.map((d) => d.visitors)) : 1;

  return (
    <>
      <section className="card wt-adm-card">
        <div className="wt-dash-filters">
          <div className="wt-dash-seg" role="group" aria-label="누구를 셀까">
            {WHO.map(([k, label]) => (
              <button key={k} type="button" className={`tag${who === k ? " on" : ""}`} onClick={() => setWho(k)}>{label}</button>
            ))}
          </div>
          <div className="wt-dash-range">
            <input className="field" type="date" value={from} max={to} aria-label="시작 날짜" onChange={(e) => setFrom(e.target.value)} />
            <span className="muted">~</span>
            <input className="field" type="date" value={to} min={from} max={today} aria-label="끝 날짜" onChange={(e) => setTo(e.target.value)} />
            <button type="button" className="tag" onClick={() => preset(1)}>오늘</button>
            <button type="button" className="tag" onClick={() => preset(7)}>7일</button>
            <button type="button" className="tag" onClick={() => preset(30)}>30일</button>
            <button type="button" className="tag" onClick={() => void load()} disabled={loading}>{loading ? "불러오는 중" : "새로고침"}</button>
          </div>
        </div>
        <p className="muted wt-dash-note">
          날짜는 한국 시간 기준이에요. 사람은 로그인했으면 계정으로, 아니면 브라우저로 셉니다.
          {data && who === "external" && <> 같은 기간 우리 쪽(관리자 · 멘토 · 지인) {data.internalVisitors}명은 뺐어요.</>}
        </p>
        {err && <p className="wt-adm-err">{err}</p>}
      </section>

      {t && data && (
        <>
          <div className="wt-dash-stats">
            <Stat label="들어온 사람" value={t.visitors} />
            <Stat label="새로 가입" value={t.signups} />
            <Stat label="만들기를 시작한 사람" value={t.createStarted} />
            <Stat label="완성된 웹툰" value={t.jobsDone} sub={`시작된 작업 ${t.jobs}개 · 실패 ${t.jobsFailed}개`} />
            <Stat label="결제하기를 누른 사람" value={t.chargeSubmit} sub={`${t.chargeSubmitClicks}번 누름 · 충전 창 연 사람 ${t.chargeOpen}명`} />
            <Stat label="지금 진행 중인 작업" value={data.runningJobs} sub="기간과 상관없이 지금" />
          </div>

          <section className="card wt-adm-card">
            <h2>핵심 지표</h2>
            <div className="wt-dash-kpis">
              {data.kpi.map((k) => (
                <div key={k.key} className="wt-dash-kpi">
                  <span className="muted">{k.label}</span>
                  <b>{k.pct == null ? "–" : `${k.pct}%`}</b>
                  <span className="wt-dash-kpi-base">{k.hit} / {k.base}명</span>
                  <span className="muted wt-dash-kpi-def">{k.definition}</span>
                  {k.note && <span className="muted wt-dash-kpi-def">{k.note}</span>}
                </div>
              ))}
            </div>
          </section>

          <section className="card wt-adm-card">
            <h2>날마다</h2>
            <table className="wt-adm-table wt-dash-daily">
              <thead><tr><th>날짜</th><th>들어온 사람</th><th>만들기 시작</th><th>완성</th></tr></thead>
              <tbody>
                {[...data.daily].reverse().map((d) => (
                  <tr key={d.day}>
                    <td>{d.day.slice(5).replace("-", ".")}</td>
                    <td>
                      <span className="wt-dash-bar"><i style={{ width: `${(100 * d.visitors) / peak}%` }} /></span>
                      {d.visitors}
                    </td>
                    <td>{d.createStarted}</td>
                    <td>{d.jobsDone}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </>
      )}
    </>
  );
}

function Stat({ label, value, sub }: { label: string; value: number; sub?: string }) {
  return (
    <div className="card wt-dash-stat">
      <span className="muted">{label}</span>
      <b>{value.toLocaleString("ko-KR")}</b>
      {sub && <span className="muted wt-dash-stat-sub">{sub}</span>}
    </div>
  );
}

/* ---- 목록 공통 -------------------------------------------------------------- */

function useList<T>(fetcher: (filter: string, q: string, page: number) => Promise<AdminPage<T>>, filter: string, q: string, page: number) {
  const [data, setData] = useState<AdminPage<T> | null>(null);
  const [err, setErr] = useState("");
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    let live = true;
    setLoading(true);
    fetcher(filter, q, page)
      .then((d) => { if (live) { setData(d); setErr(""); } })
      .catch((e) => { if (live) setErr(errText(e)); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [fetcher, filter, q, page]);
  return { data, err, loading };
}

function ListBar({ filters, filter, setFilter, setQ, placeholder, total }: {
  filters: [string, string][]; filter: string; setFilter: (f: string) => void;
  setQ: (q: string) => void; placeholder: string; total?: number;
}) {
  const [text, setText] = useState("");
  return (
    <section className="card wt-adm-card">
      <div className="wt-dash-filters">
        <div className="wt-dash-seg" role="group" aria-label="고르기">
          {filters.map(([k, label]) => (
            <button key={k} type="button" className={`tag${filter === k ? " on" : ""}`} onClick={() => setFilter(k)}>{label}</button>
          ))}
        </div>
        <form className="wt-dash-search" onSubmit={(e) => { e.preventDefault(); setQ(text.trim()); }}>
          <input className="field" value={text} placeholder={placeholder} aria-label={placeholder} onChange={(e) => setText(e.target.value)} />
          <button type="submit" className="btn btn-w btn-sm">찾기</button>
        </form>
      </div>
      {total != null && <p className="muted wt-dash-note">{total.toLocaleString("ko-KR")}개</p>}
    </section>
  );
}

function Pager({ page, total, size, setPage }: { page: number; total: number; size: number; setPage: (p: number) => void }) {
  const last = Math.max(0, Math.ceil(total / size) - 1);
  if (last === 0) return null;
  return (
    <div className="wt-dash-pager">
      <button type="button" className="btn btn-w btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button>
      <span className="muted">{page + 1} / {last + 1}</span>
      <button type="button" className="btn btn-w btn-sm" disabled={page >= last} onClick={() => setPage(page + 1)}>다음</button>
    </div>
  );
}

/* ---- 작품 ------------------------------------------------------------------ */

const WORK_FILTERS: [string, string][] = [
  ["all", "전체"], ["public", "공개"], ["private", "비공개"], ["hidden", "관리자 비공개"],
  ["removed", "관리자 삭제"], ["trash", "작가 휴지통"], ["guest", "로그인 안 함"],
];

const STATE_TEXT: Record<AdminWorkState, string> = {
  public: "공개", private: "비공개", hidden: "관리자 비공개", removed: "관리자 삭제", trash: "작가 휴지통",
};

function Works({ go }: { go: Go }) {
  const [filter, setFilter] = useState("all");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const { data, err, loading } = useList(adminWorkList, filter, q, page);

  return (
    <>
      <ListBar filters={WORK_FILTERS} filter={filter} setFilter={(f) => { setFilter(f); setPage(0); }}
               setQ={(v) => { setQ(v); setPage(0); }} placeholder="작가 이메일 · 제목 · 작품 번호" total={data?.total} />
      {err && <p className="wt-adm-err">{err}</p>}
      {data && data.rows.length === 0 && <p className="muted">해당하는 작품이 없어요.</p>}
      <div className="wt-dash-grid" aria-busy={loading}>
        {data?.rows.map((w: AdminWorkRow) => (
          <button key={w.run_id} type="button" className="card wt-dash-item" onClick={() => go("result", { run: w.run_id })}>
            {w.cover_url
              // eslint-disable-next-line @next/next/no-img-element
              ? <img className="wt-dash-work-img" src={w.cover_url} alt="" loading="lazy" />
              : <span className="wt-dash-work-img wt-dash-noimg">그림 없음</span>}
            <span className={`wt-dash-state is-${w.state}`}>{STATE_TEXT[w.state]}</span>
            <b className="wt-dash-item-title">{w.title || w.run_id}</b>
            <span className="muted wt-dash-item-sub">{w.guest ? "로그인 안 함" : w.owner_email}</span>
            <span className="muted wt-dash-item-sub">{when(w.created_at)} · {w.pages}장</span>
            {w.moderation_reason && <span className="muted wt-dash-item-sub">사유: {w.moderation_reason}</span>}
          </button>
        ))}
      </div>
      {data && <Pager page={page} total={data.total} size={data.pageSize} setPage={setPage} />}
    </>
  );
}

/* ---- 캐릭터 ---------------------------------------------------------------- */

const CHAR_FILTERS: [string, string][] = [
  ["all", "전체"], ["private", "공유 안 함"], ["shared", "커뮤니티 공유"], ["blocked", "공유 막힘"],
  ["failed", "못 그림"], ["guest", "로그인 안 함"],
];

const SOURCE_TEXT: Record<string, string> = { PHOTO: "사진에서", PROMPT: "글로만", BUILTIN: "기본 제공" };

function Characters() {
  const [filter, setFilter] = useState("all");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const { data, err, loading } = useList(adminCharacterList, filter, q, page);

  return (
    <>
      <ListBar filters={CHAR_FILTERS} filter={filter} setFilter={(f) => { setFilter(f); setPage(0); }}
               setQ={(v) => { setQ(v); setPage(0); }} placeholder="이름 · 작가 이메일 · 설명" total={data?.total} />
      {err && <p className="wt-adm-err">{err}</p>}
      {data && data.rows.length === 0 && <p className="muted">해당하는 캐릭터가 없어요.</p>}
      <div className="wt-dash-grid" aria-busy={loading}>
        {data?.rows.map((c: AdminCharacterRow) => (
          <button key={c.id} type="button" className="card wt-dash-item" onClick={() => setOpen(c.id)}>
            {c.art_url
              // eslint-disable-next-line @next/next/no-img-element
              ? <img className="wt-dash-char-img" src={c.art_url} alt="" loading="lazy" />
              : <span className="wt-dash-char-img wt-dash-noimg">{c.status === "ERROR" ? "못 그림" : "그림 없음"}</span>}
            <span className="wt-dash-tags">
              {c.shared && <span className="wt-dash-state is-public">공유</span>}
              {c.blocked && <span className="wt-dash-state is-removed">막힘</span>}
              {c.status === "ERROR" && <span className="wt-dash-state is-hidden">못 그림</span>}
            </span>
            <b className="wt-dash-item-title">{c.name}</b>
            <span className="muted wt-dash-item-sub">{c.guest ? "로그인 안 함" : c.owner_email}</span>
            <span className="muted wt-dash-item-sub">
              {[c.source && (SOURCE_TEXT[c.source] ?? c.source), c.world, when(c.created_at)].filter(Boolean).join(" · ")}
            </span>
          </button>
        ))}
      </div>
      {data && <Pager page={page} total={data.total} size={data.pageSize} setPage={setPage} />}
      {open && <CharacterDialog id={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function CharacterDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const [c, setC] = useState<AdminCharacterDetail | null>(null);
  const [err, setErr] = useState("");
  useEffect(() => {
    adminCharacter(id).then(setC).catch((e) => setErr(errText(e)));
  }, [id]);

  const rows: [string, string | null | undefined][] = c ? [
    ["만든 사람", c.guest ? "로그인 안 함" : c.owner_email],
    ["만든 때", when(c.created_at)],
    ["만든 방식", c.source && (SOURCE_TEXT[c.source] ?? c.source)],
    ["넣은 이름", c.asked_name],
    ["넣은 세계관", c.asked_world],
    ["설명", c.description],
    ["세계관 · 장르", [c.world, c.genre].filter(Boolean).join(" · ")],
    ["종", c.species],
    ["맡은 자리", c.role],
    ["반전 한 줄", c.twist],
    ["대사", c.quote],
    ["그림체", c.style],
    ["오류", c.error],
  ] : [];

  return (
    <Dialog title={c?.name ?? "캐릭터"} onClose={onClose} wide>
      {err && <p className="wt-adm-err">{err}</p>}
      {c && (
        <div className="wt-dash-char">
          {c.art_url
            // eslint-disable-next-line @next/next/no-img-element
            ? <img src={c.art_url} alt={`${c.name} 그림`} />
            : <span className="wt-dash-noimg">그림 없음</span>}
          <dl>
            {rows.filter(([, v]) => v).map(([k, v]) => (
              <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
            ))}
            <div><dt>공유</dt><dd>{c.blocked ? "공유 막힘" : c.shared ? "커뮤니티에 공유" : "공유 안 함"}</dd></div>
          </dl>
        </div>
      )}
      <div className="wt-dash-dialog-acts"><button type="button" className="btn btn-w btn-sm" onClick={onClose}>닫기</button></div>
    </Dialog>
  );
}
