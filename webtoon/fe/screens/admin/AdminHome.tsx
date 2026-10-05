"use client";

/**
 * 관리자 홈 — 운영 중에 「지금 몇 명이 들어와 있나 · 어디서 왔나」를 보는 자리다.
 *
 * - 바로가기: 작품 관리 · 예시 작품 관리 · 설문(마이페이지의 「설문 답 모아 보기」)
 * - 실시간 패널: 최근 5분 활성 uid · 지금 돌고 있는 작업 · 날짜 범위 안의 유입·시작·완성
 * - 유입 분석: UTM source · medium · campaign · ref_host 별 unique uid 와 깔때기
 * - UTM 링크 생성기: /webtoon 또는 /webtoon?run=<id> 뒤에 utm_* 를 붙여 한 줄 복사
 *
 * 로그인 안 했거나 관리자가 아니면 백엔드가 401/403 — 그걸 그대로 보여 준다.
 * 관리자 운영 화면이라 문구를 번역하지 않는다.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import type { Go } from "../../lib/nav";
import { adminLive, type AdminLive } from "../../lib/api";
import "./AdminHome.css";

const REFRESH_MS = 20_000;

function today(): string {
  /* 지금의 KST 벽시계 날짜. 로컬 TZ 가 어디든 UTC+9 로 옮긴 「가짜 UTC」 로 자르면 YYYY-MM-DD 가 나온다. */
  const kst = new Date(Date.now() + 9 * 60 * 60_000);
  return kst.toISOString().slice(0, 10);
}

function addDays(ymd: string, days: number): string {
  const d = new Date(ymd + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export default function AdminHome({ go }: { go: Go }) {
  const [from, setFrom] = useState<string>(today());
  const [to, setTo] = useState<string>(today());
  const [data, setData] = useState<AdminLive | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const d = await adminLive(from, to);
      setData(d);
      setErr(null);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [from, to]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => {
    const t = setInterval(load, REFRESH_MS);
    return () => clearInterval(t);
  }, [load]);

  const setToday = () => { const t = today(); setFrom(t); setTo(t); };
  const setLast7 = () => {
    const t = today();
    setFrom(addDays(t, -6));
    setTo(t);
  };

  return (
    <div className="card wt-admin-home">
      <div className="wt-admin-head">
        <h1>관리자 홈</h1>
        <div className="wt-admin-shortcuts">
          <button type="button" className="btn btn-w" onClick={() => go("admin-works")}>작품 관리</button>
          <button type="button" className="btn btn-w" onClick={() => go("admin-examples")}>예시 작품 관리</button>
          <button type="button" className="btn btn-w" onClick={() => go("mypage", { tab: "settings" })}>설문 모아보기</button>
        </div>
      </div>

      <section className="wt-admin-section">
        <div className="wt-admin-row">
          <label>처음<input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>끝<input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
          <button type="button" className="btn btn-g" onClick={setToday}>오늘</button>
          <button type="button" className="btn btn-g" onClick={setLast7}>최근 7일</button>
          <button type="button" className="btn btn-g" onClick={load} disabled={loading}>
            {loading ? "불러오는 중" : "새로 고침"}
          </button>
          <span className="wt-admin-note">{REFRESH_MS / 1000}초마다 자동 갱신 · KST 기준</span>
        </div>
        {err && <div className="wt-admin-err">불러오지 못했습니다: {err}</div>}
      </section>

      {data && (
        <>
          <section className="wt-admin-section">
            <h2>발표 7번 장 — 가설 체크보드</h2>
            <HypothesisBoard data={data} />
            <div className="wt-admin-note">
              범위: {data.from} ~ {data.to} · 이벤트는 unique uid · 설문은 긍정(5점 척도 4~5, YES/부분/NO 는 YES) ÷ 전체 응답
            </div>
          </section>

          <section className="wt-admin-section">
            <h2>지금</h2>
            <div className="wt-admin-stats wt-admin-stats-top">
              <Stat label={`최근 ${data.activeMinutes}분 활성`} value={data.activeUids} />
              <Stat label="지금 돌고 있는 작업" value={data.runningJobs} />
              <Stat label="범위 안 총 유입" value={data.funnel.landing} />
            </div>
          </section>

          <section className="wt-admin-section">
            <h2>UTM source 별 (상위 10)</h2>
            <SourceTable rows={data.sources} />
          </section>

          <section className="wt-admin-section wt-admin-2col">
            <div>
              <h2>UTM medium 별</h2>
              <SourceTable rows={data.mediums} />
            </div>
            <div>
              <h2>UTM campaign 별</h2>
              <SourceTable rows={data.campaigns} />
            </div>
          </section>

          <section className="wt-admin-section">
            <h2>UTM 없는 외부 유입 (레퍼러 호스트)</h2>
            {data.refHosts.length === 0 ? (
              <p className="wt-admin-note">없음</p>
            ) : (
              <table className="wt-admin-table">
                <thead><tr><th>호스트</th><th className="num">방문자</th></tr></thead>
                <tbody>
                  {data.refHosts.map((r) => (
                    <tr key={r.host}><td>{r.host}</td><td className="num">{r.visitors}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <details className="wt-admin-section wt-admin-details">
            <summary><h2 style={{ display: "inline" }}>자세한 지표 (허영 지표 · 디버깅용)</h2></summary>
            <h3>세션·공유·에러</h3>
            <div className="wt-admin-stats">
              <Stat label="새 방문자" value={data.funnel.newSessions} />
              <Stat label="재방문자" value={data.funnel.returningSessions} />
              <Stat label="로그인" value={data.funnel.signedIn} />
              <Stat label="생성 시작" value={data.funnel.createStarted} />
              <Stat label="완성" value={data.funnel.baked} />
              <Stat label="끝까지 봄" value={data.funnel.readEnd} />
              <Stat label="다음 화 클릭" value={data.funnel.nextEpisodeClicked} />
              <Stat label="카카오 공유" value={data.funnel.kakaoShared} />
              <Stat label="내려받기" value={data.funnel.downloadClicked} />
              <Stat label="생성 실패" value={data.funnel.createFailed} />
              <Stat label="검열 차단" value={data.funnel.createBlocked} />
            </div>
            <h3 style={{ marginTop: 16 }}>모든 이벤트 (상위 50)</h3>
            {data.events.length === 0 ? (
              <p className="wt-admin-note">없음</p>
            ) : (
              <table className="wt-admin-table">
                <thead><tr><th>이름</th><th className="num">unique uid</th><th className="num">총 횟수</th></tr></thead>
                <tbody>
                  {data.events.map((r) => (
                    <tr key={r.name}><td>{r.name}</td><td className="num">{r.uids}</td><td className="num">{r.total}</td></tr>
                  ))}
                </tbody>
              </table>
            )}
          </details>
        </>
      )}

      <section className="wt-admin-section">
        <h2>UTM 링크 생성기</h2>
        <UtmBuilder />
      </section>
    </div>
  );
}

/* 발표 7번 장이 묻는 다섯 가지를 한 표로 — 각 줄에 데이터 출처와 지금 수치, 신호 표시. */
function HypothesisBoard({ data }: { data: AdminLive }) {
  const landing = data.funnel.landing;
  const createStarted = data.funnel.createStarted;
  const baked = data.funnel.baked;
  const readEnd = data.funnel.readEnd;
  const rows: Array<{ q: string; src: string; result: React.ReactNode; signal: string }> = [
    {
      q: "외부 10명 유입",
      src: "session_start (전체 범위, 지인/팀원 제외는 UTM 표에서)",
      result: <><b>{landing}</b>명</>,
      signal: landing >= 10 ? "충분" : landing > 0 ? "모자람" : "없음",
    },
    {
      q: "생성을 시작하는가 → 끝까지 가는가",
      src: "create_started · bake · read_end",
      result: (
        <>
          시작 <b>{createStarted}</b> → 완성 <b>{baked}</b> ({pct(baked, createStarted)})
          {" · "}끝까지 봄 <b>{readEnd}</b> ({pct(readEnd, baked)})
        </>
      ),
      signal: baked > 0 ? "일부 확인" : createStarted > 0 ? "완성 없음" : "없음",
    },
    {
      q: "결과를 어느 단계까지 손대나",
      src: "story_retry · sheet_fix/restore · scene_retry/restore · regen_start",
      result: (
        <>
          이야기 <b>{data.revisions.story}</b> · 시트 <b>{data.revisions.sheet}</b>
          {" · "}장면 <b>{data.revisions.scene}</b> · 장 <b>{data.revisions.panel}</b>
        </>
      ),
      signal: Object.values(data.revisions).some((n) => n > 0) ? "일부 확인" : "없음",
    },
    {
      q: "다음 화를 보고 싶어하나",
      src: "next_episode_click · 설문 S7 (yes/partly/no)",
      result: (
        <>
          클릭 <b>{data.funnel.nextEpisodeClicked}</b>
          {" · "}
          S7 YES <b>{data.survey.S7?.positive ?? 0}</b> / {data.survey.S7?.total ?? 0}
        </>
      ),
      signal: data.funnel.nextEpisodeClicked > 0 || (data.survey.S7?.total ?? 0) > 0 ? "일부 확인" : "없음",
    },
    {
      q: "「내 캐릭터 얘기 같다」 (H2)",
      src: "설문 S1 긍정 · S2 일관성 · S4 성격 · S3 설정 · S5 줄거리",
      result: <SurveyLine survey={data.survey} />,
      signal: hasSurvey(data.survey) ? "일부 확인" : "없음",
    },
    {
      q: "1화 자체 재미 (H3)",
      src: "설문 S6",
      result: <SurveyCell cell={data.survey.S6} />,
      signal: (data.survey.S6?.total ?? 0) > 0 ? "일부 확인" : "없음",
    },
  ];
  return (
    <table className="wt-admin-table wt-admin-hypo">
      <thead>
        <tr><th>묻는 것</th><th>지금</th><th>어떻게 재나</th><th>상태</th></tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.q}>
            <td><b>{r.q}</b></td>
            <td>{r.result}</td>
            <td className="wt-admin-hypo-src">{r.src}</td>
            <td><span className={"wt-admin-badge " + badgeClass(r.signal)}>{r.signal}</span></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function pct(n: number, d: number): string {
  if (!d) return "–";
  return Math.round((n / d) * 100) + "%";
}
function hasSurvey(s: Record<string, { total: number }>): boolean {
  return ["S1", "S2", "S3", "S4", "S5"].some((k) => (s[k]?.total ?? 0) > 0);
}
function badgeClass(signal: string): string {
  if (signal === "충분") return "ok";
  if (signal === "일부 확인") return "part";
  if (signal === "모자람" || signal === "완성 없음") return "warn";
  return "none";
}

function SurveyCell({ cell }: { cell?: { positive: number; total: number } }) {
  if (!cell || cell.total === 0) return <span className="muted">응답 없음</span>;
  return <>긍정 <b>{cell.positive}</b> / {cell.total} ({pct(cell.positive, cell.total)})</>;
}

function SurveyLine({ survey }: { survey: Record<string, { positive: number; total: number }> }) {
  const keys: Array<[string, string]> = [["S1", "내 캐릭터"], ["S2", "일관성"], ["S4", "성격"], ["S3", "설정"], ["S5", "줄거리"]];
  const any = keys.some(([k]) => (survey[k]?.total ?? 0) > 0);
  if (!any) return <span className="muted">응답 없음</span>;
  return (
    <>
      {keys.map(([k, lbl], i) => {
        const c = survey[k];
        if (!c || c.total === 0) return <span key={k} className="muted">{i > 0 && " · "}{lbl} –</span>;
        return (
          <span key={k}>{i > 0 && " · "}{lbl} <b>{c.positive}</b>/{c.total}</span>
        );
      })}
    </>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div className="wt-admin-stat">
      <div className="wt-admin-stat-value">{value}</div>
      <div className="wt-admin-stat-label">{label}</div>
    </div>
  );
}

function SourceTable({ rows }: { rows: { key: string; visitors: number; started: number; baked: number }[] }) {
  if (rows.length === 0) return <p className="wt-admin-note">없음</p>;
  return (
    <table className="wt-admin-table">
      <thead>
        <tr>
          <th>값</th><th className="num">방문</th><th className="num">생성 시작</th>
          <th className="num">완성</th><th className="num">완성률</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.key}>
            <td>{r.key}</td>
            <td className="num">{r.visitors}</td>
            <td className="num">{r.started}</td>
            <td className="num">{r.baked}</td>
            <td className="num">{r.visitors > 0 ? Math.round((r.baked / r.visitors) * 100) + "%" : "–"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/* ── UTM 링크 생성기 ─────────────────────────────────────────────────
 * docs/validation.md 의 UTM 규칙을 그대로 UI 로 꺼낸다.
 *   base   : /webtoon(랜딩) 또는 /webtoon?run=<id>(완성본)
 *   source : community-ai-<이름> · community-creator-<이름> · pinterest 등
 *   medium : post · friend · pin
 *   campaign: sprint-0928 (기본)
 *   content: msg-a-photo · msg-b-mychar · msg-c-fave */
function UtmBuilder() {
  const base = typeof window !== "undefined" ? window.location.origin : "https://lorecomic.com";
  const [page, setPage] = useState<"landing" | "run">("landing");
  const [runId, setRunId] = useState("");
  const [source, setSource] = useState("");
  const [medium, setMedium] = useState("post");
  const [campaign, setCampaign] = useState("sprint-0928");
  const [content, setContent] = useState("");
  const [copied, setCopied] = useState(false);

  const url = useMemo(() => {
    const q = new URLSearchParams();
    if (page === "run" && runId.trim()) q.set("run", runId.trim());
    if (source.trim()) q.set("utm_source", source.trim());
    if (medium.trim()) q.set("utm_medium", medium.trim());
    if (campaign.trim()) q.set("utm_campaign", campaign.trim());
    if (content.trim()) q.set("utm_content", content.trim());
    const qs = q.toString();
    return `${base}/webtoon${qs ? "?" + qs : ""}`;
  }, [base, page, runId, source, medium, campaign, content]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      /* 클립보드 거부 — 선택해서 복사하라고 띄우기만. */
    }
  };

  return (
    <div className="wt-admin-utm">
      <div className="wt-admin-utm-row">
        <label>
          어디로
          <select value={page} onChange={(e) => setPage(e.target.value as "landing" | "run")}>
            <option value="landing">랜딩 (/webtoon)</option>
            <option value="run">완성본 (/webtoon?run=…)</option>
          </select>
        </label>
        {page === "run" && (
          <label className="wt-admin-utm-grow">
            run id<input type="text" value={runId} onChange={(e) => setRunId(e.target.value)} placeholder="20261003T204446-99f4c3a" />
          </label>
        )}
      </div>

      <div className="wt-admin-utm-row">
        <label className="wt-admin-utm-grow">
          utm_source
          <input type="text" value={source} onChange={(e) => setSource(e.target.value)}
                 placeholder="pinterest · community-ai-<이름> · community-creator-<이름> · friends" />
        </label>
        <label>
          utm_medium
          <select value={medium} onChange={(e) => setMedium(e.target.value)}>
            <option value="post">post (커뮤니티 글)</option>
            <option value="pin">pin (핀터레스트)</option>
            <option value="friend">friend (지인·팀원)</option>
            <option value="social">social</option>
            <option value="">(비움)</option>
          </select>
        </label>
      </div>

      <div className="wt-admin-utm-row">
        <label>
          utm_campaign
          <input type="text" value={campaign} onChange={(e) => setCampaign(e.target.value)} />
        </label>
        <label>
          utm_content
          <select value={content} onChange={(e) => setContent(e.target.value)}>
            <option value="">(비움)</option>
            <option value="msg-a-photo">msg-a-photo</option>
            <option value="msg-b-mychar">msg-b-mychar</option>
            <option value="msg-c-fave">msg-c-fave</option>
          </select>
        </label>
      </div>

      <div className="wt-admin-utm-out">
        <input type="text" readOnly value={url} onFocus={(e) => e.currentTarget.select()} />
        <button type="button" className="btn btn-b" onClick={copy}>{copied ? "복사됨" : "복사"}</button>
      </div>
    </div>
  );
}
