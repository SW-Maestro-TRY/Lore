"use client";

/**
 * 오늘 생성 상한(#617) — 관리자 개요 맨 위 칸.
 *
 * 사람이 몰려 하루 상한에 걸린 날, 서버를 다시 띄우지 않고 그날(한국 자정까지)만 올린다. 자정이 지나면 서버가
 * 저절로 설정값(Parameter Store)으로 돌아간다. 상한에 처음 걸리면 디스코드에 @here 알림이 오고, 그 알림의
 * 「관리:」 주소가 이 화면이다. 숫자 고르는 기준은 webtoon/docs/capacity.md 「사용자가 몰렸을 때」.
 *
 * 바꾸기 전에 한 번 더 묻는다 — 폰으로 급하게 누르는 자리라 돈 상한이 실수로 바뀌면 안 된다.
 */
import { useCallback, useEffect, useState } from "react";
import { adminClearSpend, adminSetSpend, adminSpend, WebtoonApiError, type AdminSpend } from "../../lib/api";
import "./SpendCap.css";

/** 파도 한 편 원가 어림 — 운영 실측(capacity.md 10/26). 고르는 사람이 「몇 편쯤」을 바로 보게 한다. */
const SURF_KRW = 2_400;
/** capacity.md 「하루 상한을 얼마로 하면」 표와 같은 값 */
const PRESETS = [
  { krw: 100_000, runs: 50 },
  { krw: 150_000, runs: 70 },
  { krw: 300_000, runs: 140 },
];
/** 서버(AdminSpendController)와 같은 선 */
const MAX_KRW = 2_000_000;
const MAX_RUNS = 1_000;
const REFRESH_MS = 60_000;

type Ask = { kind: "set"; krw: number; runs: number } | { kind: "clear" };

const won = (n: number) => `${n.toLocaleString("ko-KR")}원`;
const short = (n: number) => (n >= 10_000 && n % 10_000 === 0 ? `${(n / 10_000).toLocaleString("ko-KR")}만 원` : won(n));
const digits = (s: string) => s.replace(/[^0-9]/g, "");

function errText(e: unknown): string {
  if (e instanceof WebtoonApiError && (e.status === 401 || e.status === 403)) return "로그인이 풀렸어요. 다시 로그인한 뒤 새로고침하세요.";
  return e instanceof Error ? e.message : "못 불러왔어요";
}

function clock(at: string): string {
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? at : d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });
}

function Meter({ label, used, reserved, cap, fmt }: {
  label: string; used: number; reserved: number; cap: number; fmt: (n: number) => string;
}) {
  const total = used + reserved;
  const pct = cap > 0 ? Math.min(100, Math.round((total / cap) * 100)) : 0;
  return (
    <div className="wt-spend-meter">
      <div className="wt-spend-meter-top">
        <span className="muted">{label}</span>
        <b>{fmt(total)} <span className="muted">/ {cap > 0 ? fmt(cap) : "상한 없음"}</span></b>
      </div>
      <span className={`wt-spend-bar${pct >= 100 ? " full" : pct >= 80 ? " near" : ""}`} aria-hidden="true">
        <i style={{ width: `${pct}%` }} />
      </span>
      <span className="muted wt-spend-sub">
        {reserved > 0 ? `아직 안 끝난 작업 몫 ${fmt(reserved)} 포함` : "도는 작업 없음"}
      </span>
    </div>
  );
}

export default function SpendCap() {
  const [data, setData] = useState<AdminSpend | null>(null);
  const [err, setErr] = useState("");
  const [krw, setKrw] = useState("");
  const [runs, setRuns] = useState("");
  const [ask, setAsk] = useState<Ask | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState("");

  const load = useCallback(async () => {
    try {
      const d = await adminSpend();
      setData(d);
      setErr("");
      return d;
    } catch (e) {
      setErr(errText(e));
      return null;
    }
  }, []);

  useEffect(() => {
    void load().then((d) => {
      if (d) { setKrw(String(d.cap.krw)); setRuns(String(d.cap.runs)); }
    });
    /* 붐비는 날 띄워 두고 보는 칸이라 1분마다 다시 읽는다. 다른 탭에 가 있으면 안 읽는다. */
    const id = window.setInterval(() => { if (document.visibilityState === "visible") void load(); }, REFRESH_MS);
    return () => window.clearInterval(id);
  }, [load]);

  const k = Number(digits(krw));
  const r = Number(digits(runs));
  const bad = !k || !r ? "금액과 편수를 둘 다 적어 주세요"
    : k > MAX_KRW ? `금액은 ${short(MAX_KRW)}까지예요`
    : r > MAX_RUNS ? `편수는 ${MAX_RUNS}편까지예요` : "";

  const apply = async () => {
    if (!ask) return;
    setBusy(true);
    try {
      const d = ask.kind === "set" ? await adminSetSpend(ask.krw, ask.runs) : await adminClearSpend();
      setData(d);
      setKrw(String(d.cap.krw));
      setRuns(String(d.cap.runs));
      setDone(ask.kind === "set" ? "바꿨어요. 지금부터 적용돼요." : "설정값으로 돌렸어요.");
      setErr("");
      setAsk(null);
    } catch (e) {
      setErr(errText(e));
    } finally {
      setBusy(false);
    }
  };

  const pick = (p: { krw: number; runs: number }) => {
    setKrw(String(p.krw));
    setRuns(String(p.runs));
    setDone("");
    setAsk({ kind: "set", krw: p.krw, runs: p.runs });
  };

  return (
    <section className="card wt-adm-card wt-spend" aria-label="오늘 생성 상한">
      <div className="wt-spend-head">
        <div className="wt-spend-title">
          <h2>오늘 생성 상한</h2>
          {data && (
            <span className={`wt-spend-state ${data.blocked ? "blocked" : "open"}`}>
              {data.blocked ? "지금 막혀 있어요" : "열려 있어요"}
            </span>
          )}
        </div>
        <button type="button" className="tag" onClick={() => { setDone(""); void load(); }}>새로고침</button>
      </div>

      {err && <p className="wt-adm-err" role="alert">{err}</p>}

      {data && (
        <>
          <div className="wt-spend-meters">
            <Meter label="금액" used={data.used.krw} reserved={data.reserved.krw} cap={data.cap.krw} fmt={won} />
            <Meter label="편수" used={data.used.runs} reserved={data.reserved.runs} cap={data.cap.runs} fmt={(n) => `${n}편`} />
          </div>

          <p className="muted wt-spend-note">
            {data.today
              ? <>오늘만 바꾼 값이에요({clock(data.today.setAt)}에 바꿈). 오늘 자정(한국 시각)이 지나면 설정값 {short(data.defaults.krw)} · {data.defaults.runs}편으로 돌아가요.</>
              : <>설정값 {short(data.defaults.krw)} · {data.defaults.runs}편을 쓰는 중이에요. 상한에 닿으면 만들기 마지막 단계에서 「오늘 만들 수 있는 몫이 다 찼어요」가 떠요.</>}
          </p>

          <div className="wt-spend-form">
            <label>
              <span className="muted">금액 상한(원)</span>
              <input className="field" inputMode="numeric" value={krw ? Number(digits(krw)).toLocaleString("ko-KR") : ""}
                     onChange={(e) => { setKrw(digits(e.target.value)); setAsk(null); setDone(""); }} aria-label="금액 상한(원)" />
            </label>
            <label>
              <span className="muted">편수 상한</span>
              <input className="field" inputMode="numeric" value={runs}
                     onChange={(e) => { setRuns(digits(e.target.value)); setAsk(null); setDone(""); }} aria-label="편수 상한" />
            </label>
            <button type="button" className="btn btn-p btn-sm" disabled={!!bad || busy}
                    onClick={() => { setDone(""); setAsk({ kind: "set", krw: k, runs: r }); }}>
              오늘만 바꾸기
            </button>
            {data.today && (
              <button type="button" className="btn btn-w btn-sm" disabled={busy}
                      onClick={() => { setDone(""); setAsk({ kind: "clear" }); }}>
                설정값으로 돌리기
              </button>
            )}
          </div>
          {bad && (krw || runs) && <p className="wt-adm-err">{bad}</p>}

          <div className="wt-spend-presets">
            <span className="muted wt-spend-sub">빠르게 고르기</span>
            {PRESETS.map((p) => (
              <button key={p.krw} type="button" className="tag" disabled={busy} onClick={() => pick(p)}>
                {short(p.krw)} · {p.runs}편
              </button>
            ))}
          </div>

          {ask && (
            <div className="wt-spend-ask" role="group" aria-label="바꾸기 전에 확인">
              <p>
                {ask.kind === "set"
                  ? <>오늘 자정(한국 시각)까지 <b>{short(ask.krw)} · {ask.runs}편</b>으로 바꿀까요? 파도로 약 {Math.floor(ask.krw / SURF_KRW)}편까지 만들 수 있어요. 서버를 다시 띄우지 않고 바로 적용돼요.</>
                  : <>설정값 <b>{short(data.defaults.krw)} · {data.defaults.runs}편</b>으로 돌릴까요? 오늘 이미 그보다 많이 썼으면 바로 막혀요.</>}
              </p>
              <button type="button" className="btn btn-p btn-sm" disabled={busy} onClick={() => void apply()}>
                {busy ? "바꾸는 중" : ask.kind === "set" ? "바꾸기" : "돌리기"}
              </button>
              <button type="button" className="btn btn-w btn-sm" disabled={busy} onClick={() => setAsk(null)}>취소</button>
            </div>
          )}
          {done && <p className="wt-spend-done" role="status">{done}</p>}
        </>
      )}
    </section>
  );
}
