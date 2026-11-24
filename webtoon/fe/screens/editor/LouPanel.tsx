"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { notifyCreditsChanged } from "@common/api/credits";
import { authFetch } from "../../lib/api";
import { CREDIT_SHORT_EVENT, SCENE_CHANGED_EVENT } from "../../lib/editorCore";
import { useLang } from "../../lib/i18n";
import { track } from "../../lib/track";

/* 편집실 「루」 — 그림을 짚거나 한마디 하면 루가 고칠 범위를 먼저 보여 주고, 사람이 고르면 고친 뒤 전 · 후를 같이 본다.
 *
 * 서버 길(#1037): 묻기 POST /lou/turns · 화 검토 POST /lou/reviews · 골라 고치기 POST /lou/fixes · 고친 뒤 확인 POST /lou/checks ·
 * 다시 그리기 POST /scenes/{n}/regen(mode keep · text · textless) · 진행 GET /regens/{id}.
 * 장 그림을 바꾼 뒤에는 SCENE_CHANGED_EVENT 로 엔진(editorCore)에게 그 장을 새로 받으라고 알린다. */

const API = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon/v1";

const POLL_MS = 2000;

type Pin = { n: number; page: number; x: number; y: number };
type Plan = {
  id?: string; method: "text" | "keep" | "redraw" | "textless"; pages: number[]; changes: string; keeps: string;
  note: string; scene?: { where?: string | null; what?: string | null; acting?: string | null } | null;
  text?: { page: number; x: number; y: number; text: string } | null;
};
type AskResult = {
  reply: string; plans: Plan[]; ask_back?: string;
  cause?: { layer?: string; text?: string };
  found?: { pin: number; page: number; who?: string; where?: string }[];
};
type ReviewItem = {
  id: string; page: number; kind: string; sev: "P1" | "P2"; recommend: boolean; where?: string; what: string;
  method: string; note: string; instead_of_setting?: string; unverified?: boolean; why?: string;
};
type Setting = { id: string; who: string; now?: string; change: string; why?: string; pages: number[] };
type ReviewResult = { reply: string; items: ReviewItem[]; dropped: ReviewItem[]; settings: Setting[]; failed_pages?: number[] };
type Check = { ok?: boolean; reply?: string; changed?: string[]; unexpected?: string[]; advice?: string; attempts?: unknown[] };
type Fix = { page: number; regenId: string; status: "drawing" | "done" | "error"; error?: string;
             before?: number; current?: boolean; check?: Check | null; checking?: boolean; reverting?: boolean };
type Msg =
  | { id: string; who: "me"; text: string; pins: Pin[]; page: number }
  | { id: string; who: "lou"; kind: "wait"; text: string }
  | { id: string; who: "lou"; kind: "error"; text: string }
  | { id: string; who: "lou"; kind: "ask"; result: AskResult; done: Record<string, boolean> }
  | { id: string; who: "lou"; kind: "review"; reviewId: string; result: ReviewResult; sent: boolean }
  | { id: string; who: "lou"; kind: "fixes"; fixes: Fix[]; note: string };

type Turn = { id: string; kind: string; status: string; error?: string | null; request?: Record<string, unknown> | null;
              result?: Record<string, unknown> | null };

const runPath = (runId: string) => `${API}/runs/${encodeURIComponent(runId)}`;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
let seq = 0;
const uid = () => `m${Date.now().toString(36)}${(seq++).toString(36)}`;

async function readJson(res: Response): Promise<Record<string, unknown>> {
  try { return await res.json(); } catch { return {}; }
}
function errText(out: Record<string, unknown>, fallback: string): string {
  const e = out.error;
  if (typeof e === "string" && e) return e;
  if (e && typeof e === "object" && typeof (e as { message?: unknown }).message === "string") return (e as { message: string }).message;
  return typeof out.message === "string" && out.message ? out.message : fallback;
}

export default function LouPanel({ runId, active, regenCost, onPick, visible = true }:
  { runId: string; active: number; regenCost: number | null; onPick: (no: number) => void; visible?: boolean }) {
  const { t } = useLang();
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [text, setText] = useState("");
  const [pins, setPins] = useState<Pin[]>([]);
  const [pinMode, setPinMode] = useState(false);
  const [busy, setBusy] = useState(false);
  const logRef = useRef<HTMLDivElement>(null);
  const alive = useRef(true);
  /* 개발 모드는 화면을 한 번 내렸다 다시 올린다 — 올릴 때 다시 켜야 기다리던 답을 계속 받는다. */
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);

  const push = useCallback((m: Msg) => setMsgs((xs) => [...xs, m]), []);
  const patch = useCallback((id: string, f: (m: Msg) => Msg) => setMsgs((xs) => xs.map((m) => (m.id === id ? f(m) : m))), []);
  const drop = useCallback((id: string) => setMsgs((xs) => xs.filter((m) => m.id !== id)), []);

  useEffect(() => { logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: "smooth" }); }, [msgs.length]);

  /* 꾸미기로 옮기면 짚기를 끈다 — 숨은 채로 그림 누르기를 가로채면 말풍선을 못 끈다. */
  useEffect(() => { if (!visible) setPinMode(false); }, [visible]);

  /* ---------------------------------------------------------------- 그림 짚기 */
  useEffect(() => {
    const host = document.getElementById("scenes");
    if (!host) return;
    host.classList.toggle("lou-pinning", pinMode);
    if (!pinMode) return;
    /* 좁은 화면에서는 서랍(바텀시트)이 그림을 덮는다 — 짚기를 켜면 서랍을 닫는다. */
    if (window.matchMedia("(max-width: 879px)").matches) document.getElementById("dockScrim")?.click();
    /* 창 맨 앞(capture)에서 받는다 — 엔진이 장 그림 누르기를 먼저 잡아 말풍선 고르기로 쓴다. */
    const onDown = (e: PointerEvent) => {
      if (!e.isTrusted || !host.contains(e.target as Node)) return;
      const wrap = (e.target as HTMLElement | null)?.closest<HTMLElement>("[data-wrap]");
      const section = wrap?.closest<HTMLElement>("[data-scene]");
      if (!wrap || !section) return;
      e.preventDefault();
      e.stopImmediatePropagation();
      const r = wrap.getBoundingClientRect();
      const x = Math.round(((e.clientX - r.left) / r.width) * 1000) / 10;
      const y = Math.round(((e.clientY - r.top) / r.height) * 1000) / 10;
      const page = Number(section.dataset.scene);
      setPins((ps) => (ps.length >= 8 ? ps : [...ps, { n: (ps.at(-1)?.n || 0) + 1, page, x, y }]));
      onPick(page);
    };
    window.addEventListener("pointerdown", onDown, true);
    return () => { window.removeEventListener("pointerdown", onDown, true); host.classList.remove("lou-pinning"); };
  }, [pinMode, onPick]);

  /* 핀은 장 그림 위에 겹쳐 그린다 — 장 카드는 엔진 것이라 바뀔 때마다 다시 붙인다. */
  useEffect(() => {
    const paint = () => {
      document.querySelectorAll("#scenes .lou-pin").forEach((el) => el.remove());
      for (const p of pins) {
        const wrap = document.querySelector<HTMLElement>(`#scene-${p.page} [data-wrap]`);
        if (!wrap) continue;
        const dot = document.createElement("span");
        dot.className = "lou-pin";
        dot.textContent = String(p.n);
        dot.style.left = `${p.x}%`;
        dot.style.top = `${p.y}%`;
        wrap.append(dot);
      }
    };
    paint();
    const host = document.getElementById("scenes");
    if (!host || !pins.length) return;
    const mo = new MutationObserver((rec) => {
      if (rec.some((r) => [...r.removedNodes].some((n) => n instanceof HTMLElement && !n.classList.contains("lou-pin")))) paint();
    });
    mo.observe(host, { childList: true, subtree: true });
    return () => { mo.disconnect(); document.querySelectorAll("#scenes .lou-pin").forEach((el) => el.remove()); };
  }, [pins]);

  /* ---------------------------------------------------------------- 서버 */
  const waitTurn = useCallback(async (id: string): Promise<Turn> => {
    for (let i = 0; i < 400 && alive.current; i++) {
      await sleep(POLL_MS);
      try {
        const res = await authFetch(`${runPath(runId)}/lou/turns/${id}`);
        if (!res.ok) continue;
        const d = (await res.json()) as Turn;
        if (d.status !== "queued" && d.status !== "running") return d;
      } catch { /* 잠깐 끊겨도 다음 번에 */ }
    }
    return { id, kind: "", status: "error", error: t("루의 답을 기다리다 멈췄어요. 잠시 뒤 다시 보내 주세요.") };
  }, [runId, t]);

  const start = useCallback(async (path: string, body: unknown): Promise<string> => {
    const res = await authFetch(`${runPath(runId)}${path}`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body ?? {}),
    });
    const out = await readJson(res);
    if (!res.ok) throw new Error(errText(out, t("루에게 보내지 못했어요")));
    return String(out.id || "");
  }, [runId, t]);

  /* ---------------------------------------------------------------- 묻기 */
  const send = async () => {
    const said = text.trim();
    if (busy || (!said && !pins.length)) return;
    /* 「7장 1컷 …」처럼 글에 장 번호를 적었으면 그 장을 본다 — 지금 화면에 걸린 장으로 보내면 엉뚱한 장을 고친다. */
    const named = Number(/(\d{1,3})\s*(?:장|번째\s*장)/.exec(said)?.[1] || 0);
    const onPage = (n: number) => !!document.getElementById(`scene-${n}`);
    const page = pins[0]?.page || (named && onPage(named) ? named : active);
    const mine: Msg = { id: uid(), who: "me", text: said, pins, page };
    const wait: Msg = { id: uid(), who: "lou", kind: "wait", text: t("살펴보는 중이에요…") };
    push(mine); push(wait);
    setText(""); setPins([]); setPinMode(false); setBusy(true);
    track("lou_ask", { run: runId, pins: pins.length });
    try {
      const id = await start("/lou/turns", {
        text: said, active_page: page, pins: pins.map((p) => ({ n: p.n, page: p.page, x: p.x, y: p.y, note: "" })),
      });
      const turn = await waitTurn(id);
      drop(wait.id);
      if (turn.status !== "done" || !turn.result) push({ id: uid(), who: "lou", kind: "error", text: turn.error || t("확인하지 못했어요. 다시 보내 주세요.") });
      else push({ id: uid(), who: "lou", kind: "ask", result: turn.result as unknown as AskResult, done: {} });
    } catch (err) {
      drop(wait.id);
      push({ id: uid(), who: "lou", kind: "error", text: (err as Error).message });
    } finally { setBusy(false); }
  };

  /* ---------------------------------------------------------------- 화 검토 */
  const review = async () => {
    if (busy) return;
    const wait: Msg = { id: uid(), who: "lou", kind: "wait", text: t("이 화를 처음부터 읽는 중이에요. 2~3분 걸려요.") };
    push({ id: uid(), who: "me", text: t("이 화 전부 검토해 줘"), pins: [], page: active });
    push(wait);
    setBusy(true);
    track("lou_review", { run: runId });
    try {
      const id = await start("/lou/reviews", {});
      const turn = await waitTurn(id);
      drop(wait.id);
      if (turn.status !== "done" || !turn.result) push({ id: uid(), who: "lou", kind: "error", text: turn.error || t("검토하지 못했어요. 다시 눌러 주세요.") });
      else push({ id: uid(), who: "lou", kind: "review", reviewId: id, result: turn.result as unknown as ReviewResult, sent: false });
    } catch (err) {
      drop(wait.id);
      push({ id: uid(), who: "lou", kind: "error", text: (err as Error).message });
    } finally { setBusy(false); }
  };

  /* ---------------------------------------------------------------- 고치기 진행 */
  const followFix = useCallback(async (msgId: string, fix: Fix, method: string) => {
    const set = (f: Partial<Fix>) => patch(msgId, (m) => (m.who === "lou" && m.kind === "fixes"
      ? { ...m, fixes: m.fixes.map((x) => (x.regenId === fix.regenId ? { ...x, ...f } : x)) } : m));
    for (let i = 0; i < 600 && alive.current; i++) {
      await sleep(POLL_MS);
      let s: { status?: string; error?: string; versions?: { version: number }[]; before_version?: number; current?: boolean };
      try { s = await (await authFetch(`${API}/regens/${fix.regenId}`)).json(); } catch { continue; }
      if (!alive.current) return;
      if (s.status === "done") {
        if (s.current !== false) window.dispatchEvent(new CustomEvent(SCENE_CHANGED_EVENT, { detail: { no: fix.page, versions: s.versions } }));
        /* 고치기 전 판은 서버가 그 다시 그리기에 적어 둔 것 — 가장 큰 판 번호로 짐작하지 않는다. */
        set({ status: "done", before: s.before_version, current: s.current !== false, checking: true });
        const check = await checkOf(fix.regenId, fix.page, method);
        if (alive.current) set({ check, checking: false });
        return;
      }
      if (s.status === "error" || s.status === "cancelled") {
        notifyCreditsChanged();          // 실패하면 돌려받는다
        set({ status: "error", error: s.error || t("고치지 못했어요 — 원래 그림은 그대로예요") });
        return;
      }
    }
    if (alive.current) set({ status: "error", error: t("진행을 더 못 받았어요. 장 그림을 새로 고쳐 확인해 주세요.") });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [patch, t]);

  /* 고친 뒤 확인 — 루 지시로 고친 장은 서버가 이미 확인해 둔다(자동 확인 기록). 없으면 지금 묻는다. */
  const checkOf = async (regenId: string, page: number, method: string): Promise<Check | null> => {
    for (let i = 0; i < 3 && alive.current; i++) {
      try {
        const res = await authFetch(`${runPath(runId)}/lou/turns`);
        const turns = (await res.json()) as Turn[];
        const auto = [...(Array.isArray(turns) ? turns : [])].reverse()
          .find((x) => x.kind === "check" && x.status === "done" && x.request?.regen_id === regenId);
        if (auto?.result) return auto.result as Check;
      } catch { /* 아래에서 직접 묻는다 */ }
      await sleep(800);
    }
    if (!alive.current) return null;
    try {
      const id = await start("/lou/checks", { page, regen_id: regenId, method });
      const turn = await waitTurn(id);
      return turn.status === "done" ? (turn.result as Check) : { ok: false, reply: turn.error || t("확인하지 못했어요.") };
    } catch (err) { return { ok: false, reply: (err as Error).message }; }
  };

  const startRegen = async (page: number, body: Record<string, unknown>): Promise<string> => {
    const res = await authFetch(`${runPath(runId)}/scenes/${page}/regen`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
    });
    const out = await readJson(res);
    if (res.status === 402) {
      window.dispatchEvent(new CustomEvent(CREDIT_SHORT_EVENT, { detail: errText(out, "") }));
      throw new Error(t("크레딧이 모자라요"));
    }
    if (!res.ok) throw new Error(errText(out, t("시작하지 못했습니다")));
    notifyCreditsChanged();
    return String(out.id || "");
  };

  const applyPlan = async (askId: string, key: string, plan: Plan) => {
    if (busy) return;
    patch(askId, (m) => (m.who === "lou" && m.kind === "ask" ? { ...m, done: { ...m.done, [key]: true } } : m));
    track("lou_apply", { run: runId, method: plan.method, pages: plan.pages.length });
    const jobs: { page: number; body: Record<string, unknown> }[] = [];
    if (plan.method === "text" && plan.text) {
      jobs.push({ page: plan.text.page, body: { mode: "text", items: [{ x: plan.text.x, y: plan.text.y, text: plan.text.text }] } });
    } else {
      for (const page of plan.pages) {
        if (plan.method === "keep") jobs.push({ page, body: { mode: "keep", feedback: plan.note } });
        else if (plan.method === "textless") jobs.push({ page, body: { textless: true, feedback: plan.note || "" } });
        else {
          const scene = plan.scene && (plan.scene.where || plan.scene.what)
            ? { where: plan.scene.where || undefined, what: plan.scene.what || undefined } : undefined;
          jobs.push({ page, body: { feedback: plan.note, ...(scene ? { scene } : {}) } });
        }
      }
    }
    const fixes: Fix[] = [];
    const msgId = uid();
    for (const j of jobs) {
      try {
        fixes.push({ page: j.page, regenId: await startRegen(j.page, j.body), status: "drawing" });
      } catch (err) {
        /* 이미 접수된 장은 다시 보내지 않는다 — 단추를 다시 열면 그 장부터 또 값을 받는다. 못 보낸 장만 알린다. */
        push({ id: uid(), who: "lou", kind: "error", text: `${t("{n}장", { n: j.page })} · ${(err as Error).message}` });
      }
    }
    if (!fixes.length) {
      patch(askId, (m) => (m.who === "lou" && m.kind === "ask" ? { ...m, done: { ...m.done, [key]: false } } : m));
      return;
    }
    push({ id: msgId, who: "lou", kind: "fixes", fixes, note: plan.changes });
    fixes.forEach((f) => { void followFix(msgId, f, plan.method); });
  };

  const sendFixes = async (msg: Extract<Msg, { kind: "review" }>, ids: string[]) => {
    if (busy || !ids.length) return;
    setBusy(true);
    track("lou_fixes", { run: runId, items: ids.length });
    try {
      const res = await authFetch(`${runPath(runId)}/lou/fixes`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ review_id: msg.reviewId, item_ids: ids }),
      });
      const out = await readJson(res);
      if (res.status === 402) {
        window.dispatchEvent(new CustomEvent(CREDIT_SHORT_EVENT, { detail: errText(out, "") }));
        return;
      }
      if (!res.ok) throw new Error(errText(out, t("고치기를 시작하지 못했어요")));
      patch(msg.id, (m) => (m.who === "lou" && m.kind === "review" ? { ...m, sent: true } : m));
      notifyCreditsChanged();
      const jobs = (out.jobs as { page: number; regen_id: string; method: string }[]) || [];
      const msgId = uid();
      const fixes: Fix[] = jobs.map((j) => ({ page: j.page, regenId: j.regen_id, status: "drawing" }));
      push({ id: msgId, who: "lou", kind: "fixes", fixes, note: t("고른 {n}곳", { n: ids.length }) });
      jobs.forEach((j, i) => { void followFix(msgId, fixes[i], j.method); });
    } catch (err) {
      push({ id: uid(), who: "lou", kind: "error", text: (err as Error).message });
    } finally { setBusy(false); }
  };

  const reverting = useRef(new Set<string>());
  const revert = async (msgId: string, fix: Fix) => {
    if (fix.before == null || reverting.current.has(fix.regenId)) return;
    reverting.current.add(fix.regenId);
    const set = (f: Partial<Fix>) => patch(msgId, (m) => (m.who === "lou" && m.kind === "fixes"
      ? { ...m, fixes: m.fixes.map((x) => (x.regenId === fix.regenId ? { ...x, ...f } : x)) } : m));
    set({ reverting: true });
    try {
      const res = await authFetch(`${runPath(runId)}/scenes/${fix.page}/revert`, {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ version: fix.before }),
      });
      const out = await readJson(res);
      if (!res.ok) { push({ id: uid(), who: "lou", kind: "error", text: errText(out, t("되돌리지 못했습니다")) }); return; }
      window.dispatchEvent(new CustomEvent(SCENE_CHANGED_EVENT, { detail: { no: fix.page, versions: out.versions } }));
      set({ current: false });
      push({ id: uid(), who: "lou", kind: "wait", text: t("{n}번째 장을 고치기 전으로 되돌렸어요.", { n: fix.page }) });
    } catch {
      push({ id: uid(), who: "lou", kind: "error", text: t("되돌리지 못했습니다") });
    } finally {
      reverting.current.delete(fix.regenId);
      set({ reverting: false });
    }
  };

  /* ---------------------------------------------------------------- 그리기 */
  const cost = (plan: Plan) => {
    if (plan.method === "text") return t("무료");
    const n = plan.pages.length || 1;
    return regenCost != null ? `${regenCost * n}C` : t("값 확인 중");
  };
  /* 글자만 고치기는 무료라 값을 몰라도 된다. 나머지는 이 작품 값을 알기 전에는 누를 수 없다. */
  const priced = (plan: Plan) => plan.method === "text" || regenCost != null;
  const methodLabel = (m: string) => ({
    text: t("글자만 고치기"), keep: t("구도 그대로 고치기"), redraw: t("이 장 새로 그리기"), textless: t("글자 없이 그리기"),
  } as Record<string, string>)[m] || m;

  return (
    <div className="lou">
      <div className="lou-log" ref={logRef} aria-live="polite">
        {msgs.length === 0 && (
          <div className="lou-hello">
            <p>{t("고칠 곳을 그림에서 짚거나, 한마디로 말해 주세요. 무엇을 어떻게 바꿀지 먼저 보여 드리고, 누르면 고쳐요.")}</p>
            <button type="button" className="btn btn-w lou-review-btn" onClick={review} disabled={busy}>{t("이 화 전부 검토하기")}</button>
          </div>
        )}
        {msgs.map((m) => {
          if (m.who === "me") return (
            <div key={m.id} className="lou-me">
              <p>{m.text || t("짚은 곳 봐 줘")}</p>
              {m.pins.length > 0 && <small>{m.pins.map((p) => t("{page}장 {n}번", { page: p.page, n: p.n })).join(" · ")}</small>}
            </div>
          );
          if (m.kind === "wait") return <div key={m.id} className="lou-say lou-wait"><span className="lou-dot" />{m.text}</div>;
          if (m.kind === "error") return <div key={m.id} className="lou-say lou-err">{m.text}</div>;
          if (m.kind === "ask") return (
            <div key={m.id} className="lou-say">
              <p>{m.result.reply}</p>
              {m.result.ask_back && <p className="lou-askback">{m.result.ask_back}</p>}
              {m.result.plans.map((plan, i) => {
                const key = plan.id || String(i);
                return (
                  <div key={key} className="lou-plan">
                    <div className="lou-plan-head">
                      <b>{methodLabel(plan.method)}</b>
                      <span>{(plan.text ? [plan.text.page] : plan.pages).map((p) => t("{n}장", { n: p })).join(" · ")}</span>
                    </div>
                    {plan.changes && <p><span className="lou-tag">{t("바뀌는 것")}</span>{plan.changes}</p>}
                    {plan.keeps && <p><span className="lou-tag lou-tag-keep">{t("그대로")}</span>{plan.keeps}</p>}
                    {plan.method === "text" && plan.text && <p className="lou-quote">「{plan.text.text}」</p>}
                    <button type="button" className="btn btn-p lou-go" disabled={busy || !!m.done[key] || !priced(plan)} onClick={() => applyPlan(m.id, key, plan)}>
                      {m.done[key] ? t("고치는 중이에요") : <>{t("이대로 고치기")} <span className="lou-cost">{cost(plan)}</span></>}
                    </button>
                  </div>
                );
              })}
            </div>
          );
          if (m.kind === "review") return <ReviewCard key={m.id} msg={m} busy={busy} regenCost={regenCost} onPick={onPick}
                                                     onSend={(ids) => sendFixes(m, ids)} />;
          return (
            <div key={m.id} className="lou-say">
              <p>{m.fixes.some((f) => f.status === "drawing")
                ? t("{n}장을 고치는 중이에요. 한 장에 1~2분 걸려요.", { n: m.fixes.length })
                : m.fixes.every((f) => f.status === "error") ? t("고친 결과를 받지 못했어요. 장마다 아래를 봐 주세요.")
                : t("다 고쳤어요. 고치기 전과 나란히 봐 주세요.")}</p>
              {m.fixes.map((f) => <FixCard key={f.regenId} runId={runId} fix={f} onRevert={() => revert(m.id, f)} onPick={onPick} />)}
            </div>
          );
        })}
      </div>

      <div className="lou-compose">
        <div className="lou-chips">
          <button type="button" className={`lou-chip${pinMode ? " on" : ""}`} aria-pressed={pinMode} onClick={() => setPinMode((v) => !v)}>
            {pinMode ? t("짚는 중 — 그림을 누르세요") : t("그림 짚기")}
          </button>
          {pins.length > 0
            ? <button type="button" className="lou-chip" onClick={() => setPins([])}>{t("핀 {n}개 지우기", { n: pins.length })}</button>
            : <span className="lou-chip lou-chip-page">{t("{n}번째 장", { n: active })}</span>}
          {msgs.length > 0 && <button type="button" className="lou-chip" onClick={review} disabled={busy}>{t("화 검토")}</button>}
        </div>
        <div className="lou-input">
          <textarea value={text} rows={2} maxLength={500} placeholder={t("고칠 곳을 짚거나 말해 주세요")}
                    onChange={(e) => setText(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void send(); } }} />
          <button type="button" className="btn btn-p lou-send" onClick={() => void send()} disabled={busy || (!text.trim() && !pins.length)}
                  aria-label={t("보내기")}>↑</button>
        </div>
        <p className="lou-foot">{t("고치기 전에 바뀌는 것과 값을 먼저 보여 드려요")}</p>
      </div>
    </div>
  );
}

function ReviewCard({ msg, busy, regenCost, onPick, onSend }:
  { msg: Extract<Msg, { kind: "review" }>; busy: boolean; regenCost: number | null; onPick: (no: number) => void;
    onSend: (ids: string[]) => void }) {
  const { t } = useLang();
  const r = msg.result;
  const [picked, setPicked] = useState<Set<string>>(() => new Set(r.items.filter((it) => it.recommend).map((it) => it.id)));
  const [showDropped, setShowDropped] = useState(false);
  const pages = [...new Set(r.items.map((it) => it.page))].sort((a, b) => a - b);
  const pickedPages = new Set(r.items.filter((it) => picked.has(it.id)).map((it) => it.page));
  const toggle = (id: string) => setPicked((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  return (
    <div className="lou-say">
      <p>{r.reply}</p>
      {r.settings?.length > 0 && r.settings.map((s) => (
        <div key={s.id} className="lou-setting">
          <b>{t("설정을 고치자는 제안")}</b>
          <p>{s.why}</p>
          <p><span className="lou-tag">{s.who}</span>{s.change}</p>
          <small>{t("설정 바꾸기는 아직 이 화면에서 안 돼요")}</small>
        </div>
      ))}
      {pages.map((page) => (
        <div key={page} className="lou-rv-page">
          <button type="button" className="lou-rv-pageno" onClick={() => onPick(page)}>{t("{n}장", { n: page })}</button>
          {r.items.filter((it) => it.page === page).map((it) => (
            <label key={it.id} className={`lou-rv-item${it.sev === "P1" ? " p1" : ""}`}>
              <input type="checkbox" checked={picked.has(it.id)} disabled={msg.sent} onChange={() => toggle(it.id)} />
              <span>
                {it.what}
                {it.instead_of_setting && <small>{t("위 설정 제안과 반대 방향이라 권하지 않아요")}</small>}
                {it.unverified && <small>{t("다시 확인하지 못한 지적이에요")}</small>}
              </span>
            </label>
          ))}
        </div>
      ))}
      {r.dropped?.length > 0 && (
        <button type="button" className="lou-link" onClick={() => setShowDropped((v) => !v)}>
          {showDropped ? t("뺀 지적 접기") : t("다시 보고 뺀 지적 {n}개", { n: r.dropped.length })}
        </button>
      )}
      {showDropped && r.dropped.map((it) => (
        <p key={it.id} className="lou-dropped">{t("{n}장", { n: it.page })} · {it.what}{it.why ? <small>{it.why}</small> : null}</p>
      ))}
      {r.items.length > 0 && (
        <button type="button" className="btn btn-p lou-go" disabled={busy || msg.sent || picked.size === 0 || regenCost == null}
                onClick={() => onSend([...picked])}>
          {msg.sent ? t("고치기를 보냈어요")
            : <>{t("고른 {n}곳 고치기", { n: picked.size })} <span className="lou-cost">
                {regenCost != null ? `${regenCost * pickedPages.size}C` : t("값 확인 중")}</span></>}
        </button>
      )}
    </div>
  );
}

function FixCard({ runId, fix, onRevert, onPick }: { runId: string; fix: Fix; onRevert: () => void; onPick: (no: number) => void }) {
  const { t } = useLang();
  const base = runPath(runId);
  const [stamp] = useState(() => Date.now());
  if (fix.status === "drawing") return (
    <div className="lou-fix"><button type="button" className="lou-link" onClick={() => onPick(fix.page)}>{t("{n}장", { n: fix.page })}</button>
      <span className="lou-wait"><span className="lou-dot" />{t("그리는 중")}</span></div>
  );
  if (fix.status === "error") return (
    <div className="lou-fix lou-err"><b>{t("{n}장", { n: fix.page })}</b> {fix.error}</div>
  );
  const c = fix.check;
  return (
    <div className="lou-fix">
      <div className="lou-fix-head">
        <button type="button" className="lou-link" onClick={() => onPick(fix.page)}>{t("{n}장 — 고치기 전 · 후", { n: fix.page })}</button>
        {c && <span className={`lou-mark ${c.ok ? "ok" : "no"}`}>{c.ok ? t("확인됨") : t("볼 것 있음")}</span>}
      </div>
      <div className="lou-pair">
        {fix.before != null && (
          <figure><img src={`${base}/scenes/${fix.page}/versions/${fix.before}?w=480`} alt={t("고치기 전")} loading="lazy" />
            <figcaption>{t("전")}</figcaption></figure>
        )}
        <figure><img src={`${base}/page/${fix.page}?raw=1&w=1080&ep=1&t=${stamp}`} alt={t("고친 뒤")} loading="lazy" />
          <figcaption>{t("후")}</figcaption></figure>
      </div>
      {fix.checking && <p className="lou-wait"><span className="lou-dot" />{t("루가 전 · 후를 견주는 중이에요")}</p>}
      {c?.reply && <p>{c.reply}</p>}
      {c?.unexpected && c.unexpected.length > 0 && (
        <ul className="lou-unexpected">{c.unexpected.map((u, i) => <li key={i}>{u}</li>)}</ul>
      )}
      {c?.advice === "redraw" && <p className="lou-askback">{t("두 번 고쳐도 남았어요. 이 장을 새로 그리는 게 나을 수 있어요.")}</p>}
      {fix.current === false && <p className="lou-askback">{t("이 장은 그 뒤에 또 바뀌었어요. 「후」는 지금 걸린 그림이에요.")}</p>}
      {fix.before != null && (
        <button type="button" className="lou-link" onClick={onRevert} disabled={fix.reverting}>
          {fix.reverting ? t("되돌리는 중…") : t("고치기 전으로 되돌리기")}
        </button>
      )}
    </div>
  );
}
