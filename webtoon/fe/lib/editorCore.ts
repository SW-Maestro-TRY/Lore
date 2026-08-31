// 편집실 엔진 — haeun/landing/web/editor.js 를 옮겼다.
//
// 원본은 `?run=` 이 있으면 실제 작품을 열어 진짜로 다시 그리고 서버에
// 저장한다. 백엔드가 없어서 그 경로는 안 옮기고, **`run` 없이 연 경우**
// (원본이 "샘플" 이라고 부르는 경로, /static/samples/mock.json)만 옮겼다 —
// 원본도 이 경로에서는 다시 그리기·저장·굽기를 전부 흉내(로컬 타이머·
// localStorage·토스트)로 막아 둔다. 실제 작품 열기·지난 판 목록·서버
// 저장·이미지로 뽑기는 전부 RUN_ID 가 있어야 뜻이 있는 코드라 옮기지
// 않았다 — 이 파일에는 그 분기 자체가 없다(원본처럼 "만약 RUN_ID 라면"
// 이 아니라, 애초에 그 코드가 없다).
//
// DOM 을 직접 만지는 이유는 mascotPlay.ts 와 같다 — 드래그·크기 조절·
// 회전·그 자리에서 글 고치기는 포인터 이벤트를 원본과 똑같이 다뤄야
// 정확히 같게 움직인다. React 상태로 다시 짜면 오히려 미묘하게 달라질
// 위험이 크다.

import config from "../demo-api/config.json";

const $ = (root: ParentNode, s: string): HTMLElement | null => root.querySelector(s);
const $$ = (root: ParentNode, s: string): HTMLElement[] => [...root.querySelectorAll<HTMLElement>(s)];

const COST = { regen: 40, regenFeedback: 60, nobubble: 0 };
const START_CREDIT = 1240;

const BUBBLES: [string, string, string][] = [
  ["normal", "일반", "여기 앉아도 돼?"],
  ["shout", "외침", "비켜!!"],
  ["whisper", "속삭임", "…아무한테도 말하지 마."],
  ["thought", "속마음", "이건 좀 아닌데."],
  ["narration", "나레이션", "그날 밤, 아무도 잠들지 못했다."],
  ["flash", "회상", "그때도 이랬지."],
];
const TAILED = new Set(["normal", "shout", "whisper", "thought"]);
const STICKERS = ["💦", "❤️", "✨", "💢", "❗", "❓", "🌟", "🎵", "⚡", "💀", "😳", "🔥"];
const SFX = ["쿵", "우당탕", "스윽", "두근", "촤악", "번쩍", "탁", "위이잉—"];

const GAP_NAMES = ["붙임", "한 박자", "쉼", "크게 쉼"];
const GAP_SCALE_DEFAULT = [0, 0.07, 0.26, 0.62];

const FB_KEYS = ["story", "direct", "art", "all"] as const;
const FB_LABEL: Record<string, string> = { story: "스토리", direct: "연출", art: "그림", all: "" };

const STORE_KEY = "lore_editor_v2:__mock__:ep1";

interface Cut {
  no: number;
  shot?: string;
  narration?: string;
  dialogue?: string;
  speaker?: string;
  thought?: string;
  sfx?: string;
  description?: string;
}
interface Scene {
  no: number;
  image: string;
  w: number;
  h: number;
  cuts: Cut[];
  gap_step?: number;
  width?: number;
}
interface EditorData {
  title: string;
  character: string;
  genre: string;
  style_label?: string;
  logline: string;
  episode?: number;
  scenes: Scene[];
}
interface Item {
  id: string;
  type: "bubble" | "sticker" | "sfx";
  variant: string;
  text: string;
  x: number;
  y: number;
  w: number;
  size: number;
  rot: number;
  tail: string;
}
interface SceneState {
  items: Item[];
  fb: Record<string, string>;
  ver: number;
  noBubble: boolean;
}
interface EditorState {
  credit: number;
  scenes: Record<number, SceneState>;
  ledger: { label: string; amount: number; at: string }[];
  gaps: Record<number, number>;
}

function esc(s: unknown): string {
  return String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c] as string));
}

export function mountEditor(root: HTMLElement): () => void {
  let disposed = false;
  const timers: Array<ReturnType<typeof setTimeout>> = [];
  const cleanupFns: Array<() => void> = [];

  let data: EditorData | null = null;
  let state: EditorState = { credit: START_CREDIT, scenes: {}, ledger: [], gaps: {} };
  let sel: { sceneNo: number; id: string } | null = null;
  let activeScene = 1;
  let tab: "bubble" | "sticker" | "sfx" = "bubble";
  let uid = Date.now();
  let askCtx: { no: number; btn: HTMLElement; cost: number } | null = null;
  const sceneTags: { id: string; label: string }[] = (config as any).feedback_tags?.scene || [];

  function load() {
    state = { credit: START_CREDIT, scenes: {}, ledger: [], gaps: {} };
    try {
      const raw = JSON.parse(localStorage.getItem(STORE_KEY) || "null");
      if (raw && typeof raw === "object") state = { ...state, ...raw };
    } catch {
      /* 망가졌으면 새로 시작한다 */
    }
  }
  function save() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify(state));
    } catch {
      /* 용량 초과 */
    }
  }
  function sc(no: number): SceneState {
    if (!state.scenes[no]) state.scenes[no] = { items: [], fb: {}, ver: 1, noBubble: false };
    return state.scenes[no];
  }

  // ---- 크레딧 -------------------------------------------------------------
  function spend(amount: number, label: string, fromEl?: HTMLElement) {
    state.credit = Math.max(0, state.credit - amount);
    state.ledger.unshift({
      label,
      amount,
      at: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }),
    });
    state.ledger = state.ledger.slice(0, 12);
    save();
    paintCredit(true);
    paintLedger();
    if (fromEl) flyCredit(amount, fromEl);
  }
  function paintCredit(bump?: boolean) {
    const el = $(root, "#creditNum");
    if (!el) return;
    el.textContent = state.credit.toLocaleString("ko-KR");
    if (bump) {
      const box = $(root, "#creditBox");
      if (box) {
        box.classList.remove("bump");
        void box.offsetWidth;
        box.classList.add("bump");
      }
    }
  }
  function flyCredit(amount: number, el: HTMLElement) {
    const fly = $(root, "#fly");
    if (!fly) return;
    const r = el.getBoundingClientRect();
    fly.textContent = `−${amount} C`;
    fly.style.left = `${r.left + r.width / 2 - 22}px`;
    fly.style.top = `${r.top - 8}px`;
    fly.hidden = false;
    fly.style.animation = "none";
    void fly.offsetWidth;
    fly.style.animation = "";
    const t = setTimeout(() => {
      fly.hidden = true;
    }, 1150);
    timers.push(t);
  }
  function paintLedger() {
    const ul = $(root, "#ledgerList");
    if (!ul) return;
    if (!state.ledger.length) {
      ul.innerHTML = `<li class="ledger-empty">아직 쓴 크레딧이 없습니다.</li>`;
      return;
    }
    ul.innerHTML = state.ledger.map((x) => `<li><span>${x.at} · ${esc(x.label)}</span><b>−${x.amount}</b></li>`).join("");
  }

  // ---- 대사 스크립트 -------------------------------------------------------
  function scriptCut(c: Cut): string {
    const lines: string[] = [];
    if (c.narration) lines.push(`<p class="script-line narration">${esc(c.narration)}</p>`);
    if (c.dialogue) lines.push(`<p class="script-line"><span class="who">${esc(c.speaker || "?")}</span> ${esc(c.dialogue)}</p>`);
    if (c.thought) lines.push(`<p class="script-line thought">(${esc(c.thought)})</p>`);
    if (c.sfx) lines.push(`<p class="script-line sfx">${esc(c.sfx)}</p>`);
    if (!lines.length) lines.push(`<p class="script-line narration">— 대사 없음</p>`);
    return `<div class="script-cut">
      <div class="script-no">CUT ${String(c.no).padStart(2, "0")}${c.shot ? ` · ${esc(c.shot)}` : ""}</div>
      ${lines.join("")}
      <p class="script-desc">${esc(c.description)}</p>
    </div>`;
  }
  function paintScript() {
    const body = $(root, "#scriptBody");
    if (!body || !data) return;
    body.innerHTML = data.scenes
      .map(
        (s) => `
      <div class="script-page">
        <div class="script-page-no">${s.no}번째 장 · 컷 ${s.cuts.map((c) => c.no).join("·")}</div>
        ${s.cuts.map(scriptCut).join("")}
      </div>`
      )
      .join("");
  }

  // ---- 장 여백 --------------------------------------------------------------
  function gapScale(step: number): number {
    return GAP_SCALE_DEFAULT[step] || 0;
  }
  function gapStep(s: Scene): number {
    const v = state.gaps?.[s.no];
    return Number.isInteger(v) ? (v as number) : +(s.gap_step || 0);
  }
  function gapBar(s: Scene, last: boolean): string {
    if (last) return "";
    const step = gapStep(s);
    return `<div class="scene-gap" data-gap="${s.no}"
        style="padding-top:${(gapScale(step) * 100).toFixed(2)}%"
        title="끌어서 여백을 고칩니다">
        <span data-gap-label>${GAP_NAMES[step]}</span>
        <div class="gap-steps">${GAP_NAMES.map(
          (n, i) => `<button type="button" class="gap-dot${i === step ? " is-on" : ""}" data-gap-set="${i}" title="${n}"></button>`
        ).join("")}</div>
      </div>`;
  }
  function wireGaps() {
    $$(root, "[data-gap]").forEach((bar) => {
      const no = Number(bar.dataset.gap);
      const setStep = (v: number) => {
        const step = Math.max(0, Math.min(3, v));
        state.gaps = state.gaps || {};
        state.gaps[no] = step;
        bar.style.paddingTop = `${(gapScale(step) * 100).toFixed(2)}%`;
        const label = $(bar, "[data-gap-label]");
        if (label) label.textContent = GAP_NAMES[step];
        $$(bar, ".gap-dot").forEach((d, i) => d.classList.toggle("is-on", i === step));
        return step;
      };

      $$(bar, ".gap-dot").forEach((d) =>
        d.addEventListener("click", (ev) => {
          ev.stopPropagation();
          setStep(Number(d.dataset.gapSet));
          save();
        })
      );

      bar.addEventListener("pointerdown", (ev) => {
        if ((ev.target as HTMLElement).closest(".gap-dot")) return;
        ev.preventDefault();
        const start = ev.clientY;
        const from = gapStep({ no, gap_step: 0 } as Scene);
        const unit = Math.max(24, bar.getBoundingClientRect().width * 0.1);
        bar.setPointerCapture(ev.pointerId);
        bar.classList.add("is-dragging");
        const move = (e: PointerEvent) => setStep(from + Math.round((e.clientY - start) / unit));
        const up = () => {
          bar.classList.remove("is-dragging");
          save();
          bar.removeEventListener("pointermove", move);
          bar.removeEventListener("pointerup", up);
        };
        bar.addEventListener("pointermove", move);
        bar.addEventListener("pointerup", up);
      });
    });
  }

  // ---- 장 카드 ---------------------------------------------------------------
  function sceneCard(s: Scene): string {
    const st = sc(s.no);
    const cuts = s.cuts.map((c) => c.no).join("·");
    const widthStyle = (+(s.width || 1) as number) < 1 ? ` style="width:${(+(s.width as number) * 100).toFixed(2)}%;margin-left:auto;margin-right:auto"` : "";
    return `
    <section class="scene" data-scene="${s.no}" id="scene-${s.no}"${widthStyle}>
      <div class="scene-head">
        <span class="scene-no">${s.no}번째 장</span>
        <span>컷 ${cuts}</span>
        <span class="ver" data-ver>v${st.ver}</span>
        <span class="flag" data-nobub ${st.noBubble ? "" : "hidden"}>말풍선 없음</span>
      </div>

      <div class="stage-wrap" data-wrap style="aspect-ratio:${s.w}/${s.h}">
        <img src="${s.image}" alt="${s.no}번째 장" width="${s.w}" height="${s.h}" loading="lazy">
        <div class="overlay" data-overlay></div>
      </div>

      <div class="scene-tools">
        <button type="button" class="btn btn-quiet btn-sm" data-act="regen">
          다시 그리기 <span class="cost">−${COST.regen} C</span>
        </button>
        <span class="spacer"></span>
        <button type="button" class="btn btn-quiet btn-sm" data-act="fb">피드백</button>
      </div>

      <div class="page-versions" data-versions></div>

      <div class="fb" data-fb>
        <div class="fb-grid">
          <label class="fb-cell fb-story">
            <span>📖 스토리<small>대사가 어색하다 / 이 장면 필요 없다 / 훅이 약하다</small></span>
            <textarea maxlength="160" data-fbk="story" placeholder="이야기 자체에 대한 말"></textarea>
          </label>
          <label class="fb-cell fb-direct">
            <span>🎬 연출<small>컷을 더 붙여라 / 여기서 끊어라 / 클로즈업으로</small></span>
            <textarea maxlength="160" data-fbk="direct" placeholder="컷 나누기·카메라·리듬에 대한 말"></textarea>
          </label>
          <label class="fb-cell fb-art">
            <span>🎨 그림<small>옷이 다르다 / 얼굴이 작다 / 서술과 다르게 그려졌다</small></span>
            <textarea maxlength="160" data-fbk="art" placeholder="그림에 대한 말"></textarea>
          </label>
        </div>

        <label class="fb-cell fb-all">
          <span>💬 전체<small>어디에 넣을지 애매한 말 · 이 장 전체에 대한 말</small></span>
          <textarea maxlength="320" data-fbk="all" placeholder="예: 이 장은 통째로 다시 갔으면 좋겠어요 / 앞 장이랑 분위기가 안 이어져요"></textarea>
        </label>

        <div class="fb-cuts">
          ${s.cuts
            .map(
              (c) => `
            <div class="fb-cut">
              <i>CUT ${String(c.no).padStart(2, "0")}${c.shot ? ` · ${esc(c.shot)}` : ""}</i>
              ${c.narration ? ` ${esc(c.narration)}` : ""}
              ${c.dialogue ? ` <b>${esc(c.speaker || "?")}</b> ${esc(c.dialogue)}` : ""}
              ${c.thought ? ` (${esc(c.thought)})` : ""}
              ${c.sfx ? ` <b>${esc(c.sfx)}</b>` : ""}
            </div>`
            )
            .join("")}
        </div>

        <div class="fb-send">
          <button type="button" class="btn btn-quiet btn-sm" data-act="fbclear">비우기</button>
          <button type="button" class="btn btn-primary btn-sm" data-act="fbregen">
            피드백 반영해 다시 그리기 <span class="cost">−${COST.regenFeedback} C</span>
          </button>
        </div>
      </div>
    </section>`;
  }

  function readNotes(el: HTMLElement): [string, string][] {
    return FB_KEYS.map((k) => [k, ($(el, `[data-fbk='${k}']`) as HTMLTextAreaElement | null)?.value.trim() || ""]).filter(
      ([, v]) => v
    ) as [string, string][];
  }
  function notesToText(notes: [string, string][]): string {
    return notes.map(([k, v]) => (FB_LABEL[k] ? `${FB_LABEL[k]}: ${v}` : v)).join(" / ");
  }

  function wireScenes() {
    $$(root, ".scene").forEach((el) => {
      const no = +(el.dataset.scene || 0);

      el.addEventListener("pointerdown", () => setActive(no), true);

      $(el, "[data-act='fb']")?.addEventListener("click", () => $(el, "[data-fb]")?.classList.toggle("is-open"));

      $(el, "[data-act='regen']")?.addEventListener("click", (e) => askRegen(no, e.currentTarget as HTMLElement, COST.regen, []));
      $(el, "[data-act='fbregen']")?.addEventListener("click", (e) =>
        askRegen(no, e.currentTarget as HTMLElement, COST.regenFeedback, readNotes(el))
      );
      $(el, "[data-act='fbclear']")?.addEventListener("click", () => {
        FB_KEYS.forEach((k) => {
          const t = $(el, `[data-fbk='${k}']`) as HTMLTextAreaElement | null;
          if (t) t.value = "";
        });
        sc(no).fb = {};
        save();
      });

      $$(el, "[data-fbk]").forEach((t) =>
        t.addEventListener("input", () => {
          sc(no).fb[(t as HTMLTextAreaElement).dataset.fbk as string] = (t as HTMLTextAreaElement).value;
          save();
        })
      );
    });
    setActive(activeScene);
  }

  function setActive(no: number) {
    activeScene = no;
    $$(root, ".scene").forEach((el) => el.classList.toggle("is-active", +(el.dataset.scene || 0) === no));
    const label = $(root, "#activeSceneLabel");
    if (label) label.textContent = `${no}번째 장`;
  }

  // ---- 다시 그리기 확인 창 (샘플이라 흉내만) ---------------------------------
  function paintAskTags() {
    const wrap = $(root, "#regenAskTags");
    if (!wrap) return;
    wrap.replaceChildren(
      ...sceneTags.map((t) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "fb-tag";
        b.dataset.tagId = t.id;
        b.textContent = t.label;
        b.setAttribute("aria-pressed", "false");
        b.addEventListener("click", () => b.setAttribute("aria-pressed", b.getAttribute("aria-pressed") === "true" ? "false" : "true"));
        return b;
      })
    );
  }

  function askRegen(no: number, btn: HTMLElement, cost: number, notes: [string, string][]) {
    askCtx = { no, btn, cost };
    const st = sc(no);
    const title = $(root, "#regenAskTitle");
    if (title) title.textContent = `${no}번째 장 다시 그리기`;
    const sub = $(root, "#regenAskSub");
    if (sub) sub.textContent = "샘플이라 실제로 그리지는 않습니다 — 화면만 흉내 냅니다.";
    const warn = $(root, ".ask-warn");
    if (warn) warn.hidden = true;
    paintAskTags();
    const text = $(root, "#regenAskText") as HTMLTextAreaElement | null;
    if (text) text.value = notesToText(notes);
    const textless = $(root, "#regenAskTextless") as HTMLInputElement | null;
    if (textless) textless.checked = !!st.noBubble;
    const modal = $(root, "#regenAsk");
    if (modal) modal.hidden = false;
    text?.focus();
  }
  function closeAsk() {
    const modal = $(root, "#regenAsk");
    if (modal) modal.hidden = true;
    askCtx = null;
  }
  function confirmAsk() {
    if (!askCtx) return;
    const { no, btn, cost } = askCtx;
    const feedback = (($(root, "#regenAskText") as HTMLTextAreaElement | null)?.value || "").trim();
    const textless = !!($(root, "#regenAskTextless") as HTMLInputElement | null)?.checked;
    const st = sc(no);
    st.noBubble = textless;
    save();
    const el = $(root, `#scene-${no}`);
    if (el) {
      const flag = $(el, "[data-nobub]");
      if (flag) flag.hidden = !textless;
    }
    closeAsk();
    regen(no, btn, cost, { feedback, textless });
  }

  function regen(no: number, btn: HTMLElement, cost: number, body: { feedback: string; textless: boolean }) {
    const el = $(root, `#scene-${no}`);
    const wrap = el && $(el, "[data-wrap]");
    if (!el || !wrap) return;
    const st = sc(no);
    const what = [body.textless ? "글자 없이" : "글자 포함", body.feedback].filter(Boolean);

    const veil = document.createElement("div");
    veil.className = "regen-veil";
    veil.innerHTML = `<div class="spin"></div><div data-veil-msg>${no}번째 장을 다시 그리는 중…<br>
      <small class="veil-what">${esc(what.join(" · ").slice(0, 90))}</small></div>`;
    wrap.append(veil);

    if (state.credit < cost) {
      veil.remove();
      return toast("크레딧이 모자랍니다. (샘플이라 충전은 없습니다)");
    }
    spend(cost, `${no}번째 장 다시 그리기`, btn);
    const t = setTimeout(
      () => {
        if (disposed) return;
        veil.remove();
        st.ver += 1;
        save();
        const verEl = $(el, "[data-ver]");
        if (verEl) verEl.textContent = `v${st.ver}`;
        const flag = $(el, "[data-nobub]");
        if (flag) flag.hidden = !st.noBubble;
        toast(`샘플입니다 — 실제 작품을 열면 여기서 진짜로 다시 그립니다.`);
      },
      1800 + Math.random() * 900
    );
    timers.push(t);
  }

  // ---- 얹는 것 (말풍선·스티커·효과음) -----------------------------------------
  function tailOf(it: Item): string {
    if (it.type !== "bubble" || !TAILED.has(it.variant)) return "none";
    return it.tail || "left";
  }
  function itemHTML(it: Item): string {
    const ed = it.type !== "sticker" ? ` data-edit spellcheck="false"` : "";
    const inner =
      it.type === "bubble"
        ? `<div class="bub bub-${it.variant} tail-${tailOf(it)}" style="font-size:${it.size}px"${ed}>${esc(it.text)}</div>`
        : it.type === "sticker"
          ? `<div class="stk" style="font-size:${it.size * 2.2}px">${it.text}</div>`
          : `<div class="sfx" style="font-size:${it.size * 2}px"${ed}>${esc(it.text)}</div>`;
    return `<div class="item ${sel && sel.id === it.id ? "sel" : ""}" data-id="${it.id}"
      data-type="${it.type}"
      style="left:${it.x}%; top:${it.y}%; width:${it.w}%; transform:rotate(${it.rot}deg)">
      ${inner}
      <div class="handle handle-rot" data-rot title="돌리기"></div>
      <div class="handle handle-size" title="폭"></div></div>`;
  }
  function paintItems(no: number) {
    const layer = $(root, `#scene-${no} [data-overlay]`);
    if (!layer) return;
    layer.innerHTML = sc(no).items.map(itemHTML).join("");
    const showOverlay = $(root, "#showOverlay") as HTMLInputElement | null;
    layer.classList.toggle("is-hidden", !(showOverlay?.checked ?? true));
    $$(layer, ".item").forEach((el) => wireItem(no, el));
    paintProps();
  }
  function paintFeedback(no: number) {
    const el = $(root, `#scene-${no}`);
    if (!el) return;
    const fb = sc(no).fb || {};
    FB_KEYS.forEach((k) => {
      const t = $(el, `[data-fbk='${k}']`) as HTMLTextAreaElement | null;
      if (t && fb[k]) {
        t.value = fb[k];
        $(el, "[data-fb]")?.classList.add("is-open");
      }
    });
  }
  function addItem(type: Item["type"], variant: string, text: string) {
    const no = activeScene;
    const st = sc(no);
    const it: Item = {
      id: `i${++uid}`,
      type,
      variant,
      text,
      x: 22,
      y: 30 + (st.items.length % 5) * 9,
      w: type === "bubble" ? 44 : 16,
      size: type === "bubble" ? 15 : 16,
      rot: type === "sfx" ? -7 : 0,
      tail: type === "bubble" ? "left" : "none",
    };
    st.items.push(it);
    save();
    sel = { sceneNo: no, id: it.id };
    paintItems(no);
    paintProps();
    root.querySelector(`#scene-${no}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
  }
  function findItem(): Item | null {
    if (!sel) return null;
    return sc(sel.sceneNo).items.find((i) => i.id === sel!.id) || null;
  }

  function wireItem(no: number, el: HTMLElement) {
    const id = el.dataset.id as string;
    const wrap = el.closest("[data-wrap]") as HTMLElement | null;
    if (!wrap) return;

    const pick = () => {
      sel = { sceneNo: no, id };
      $$(el.parentElement!, ".item").forEach((n) => n.classList.toggle("sel", n.dataset.id === id));
      paintProps();
    };

    const text = $(el, "[data-edit]") as HTMLElement | null;

    function enterEdit() {
      if (!text || el.classList.contains("editing")) return;
      el.classList.add("editing");
      text.contentEditable = "plaintext-only";
      text.focus();
      const r = document.createRange();
      r.selectNodeContents(text);
      r.collapse(false);
      const sl = getSelection();
      sl?.removeAllRanges();
      if (sl) sl.addRange(r);
    }
    el.addEventListener("dblclick", () => {
      pick();
      enterEdit();
    });

    el.addEventListener("pointerdown", (ev) => {
      if (el.classList.contains("editing") && ev.target === text) return;
      const again = !!(sel && sel.id === id) && !(ev.target as HTMLElement).classList.contains("handle");
      let moved = false;
      ev.preventDefault();
      pick();
      const it = sc(no).items.find((i) => i.id === id);
      if (!it) return;
      const box = wrap.getBoundingClientRect();
      const rot = (ev.target as HTMLElement).dataset.rot !== undefined;
      const resizing = !rot && (ev.target as HTMLElement).classList.contains("handle");
      const sx = ev.clientX;
      const sy = ev.clientY;
      const ox = it.x;
      const oy = it.y;
      const ow = it.w;
      const orot = it.rot;
      const r0 = el.getBoundingClientRect();
      const cx = r0.left + r0.width / 2;
      const cy = r0.top + r0.height / 2;
      const a0 = Math.atan2(sy - cy, sx - cx);
      el.classList.add("dragging");
      try {
        el.setPointerCapture(ev.pointerId);
      } catch {
        /* 안 잡혀도 끈다 */
      }

      const move = (e: PointerEvent) => {
        const dx = ((e.clientX - sx) / box.width) * 100;
        const dy = ((e.clientY - sy) / box.height) * 100;
        if (rot) {
          const deg = ((Math.atan2(e.clientY - cy, e.clientX - cx) - a0) * 180) / Math.PI;
          let v = orot + deg;
          if (e.shiftKey) v = Math.round(v / 15) * 15;
          it.rot = Math.max(-180, Math.min(180, Math.round(v)));
          el.style.transform = `rotate(${it.rot}deg)`;
          return;
        }
        if (Math.abs(dx) > 0.4 || Math.abs(dy) > 0.4) moved = true;
        if (resizing) it.w = Math.max(5, Math.min(96, ow + dx));
        else {
          it.x = Math.max(-6, Math.min(98, ox + dx));
          it.y = Math.max(-4, Math.min(97, oy + dy));
        }
        el.style.left = `${it.x}%`;
        el.style.top = `${it.y}%`;
        el.style.width = `${it.w}%`;
      };
      const up = () => {
        el.classList.remove("dragging");
        save();
        paintProps();
        if (again && !moved) enterEdit();
        el.removeEventListener("pointermove", move as EventListener);
        el.removeEventListener("pointerup", up);
      };
      el.addEventListener("pointermove", move as EventListener);
      el.addEventListener("pointerup", up);
    });

    if (text) {
      text.addEventListener("input", () => {
        const it = sc(no).items.find((i) => i.id === id);
        if (it) {
          it.text = text.innerText;
          save();
        }
      });
      text.addEventListener("keydown", (e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          text.blur();
        }
        e.stopPropagation();
      });
      text.addEventListener("blur", () => {
        el.classList.remove("editing");
        text.contentEditable = "false";
        const it = sc(no).items.find((i) => i.id === id);
        if (it && !text.innerText.trim()) {
          const st = sc(no);
          st.items = st.items.filter((i) => i.id !== id);
          if (sel && sel.id === id) sel = null;
          save();
          paintItems(no);
          return;
        }
        save();
        paintItems(no);
      });
    }
  }

  // ---- 도구 패널 ---------------------------------------------------------
  function paintDock() {
    $$(root, ".dock-tab").forEach((b) => b.classList.toggle("is-on", b.dataset.tab === tab));
    const grid = $(root, "#dockGrid");
    if (!grid) return;
    if (tab === "bubble") {
      grid.innerHTML = BUBBLES.map(
        ([v, label, sample]) => `
        <button type="button" class="dock-item" data-add="bubble" data-variant="${v}" data-text="${esc(sample)}">
          <div class="prev"><div class="bub bub-${v}">${esc(sample.slice(0, 7))}</div></div>
          <span>${label}</span>
        </button>`
      ).join("");
    } else if (tab === "sticker") {
      grid.innerHTML = STICKERS.map(
        (s) => `
        <button type="button" class="dock-item" data-add="sticker" data-text="${s}">
          <div class="prev"><div class="stk">${s}</div></div>
        </button>`
      ).join("");
    } else {
      grid.innerHTML = SFX.map(
        (s) => `
        <button type="button" class="dock-item" data-add="sfx" data-text="${esc(s)}">
          <div class="prev"><div class="sfx">${esc(s)}</div></div>
        </button>`
      ).join("");
    }
    $$(grid, "[data-add]").forEach((b) =>
      b.addEventListener("click", () => addItem(b.dataset.add as Item["type"], b.dataset.variant || "", b.dataset.text || ""))
    );
  }

  function setDock(open: boolean) {
    const dock = $(root, "#edDock");
    const opener = $(root, "#dockOpen");
    const scrim = $(root, "#dockScrim");
    if (!dock) return;
    dock.classList.toggle("is-open", open);
    root.classList.toggle("dock-open-on", open);
    if (scrim) scrim.hidden = !open;
    if (opener) opener.setAttribute("aria-expanded", open ? "true" : "false");
  }
  function setLedger(open: boolean) {
    const ledger = $(root, "#dockLedger");
    if (ledger) ledger.hidden = !open;
    for (const s of ["#dockTabs", "#dockHint", "#dockGrid"]) {
      const el = $(root, s);
      if (el) el.hidden = open;
    }
    if (open) clearSel();
    if (open) setDock(true);
  }

  const BAR_ID = "itemBar";
  function killBar() {
    root.querySelector(`#${BAR_ID}`)?.remove();
  }
  function barHTML(it: Item): string {
    const b = (act: string, label: string, title: string, cls = "") => `<button type="button" class="ib ${cls}" data-act="${act}" title="${title}">${label}</button>`;
    const tailed = it.type === "bubble" && TAILED.has(it.variant);
    const cur = tailOf(it);
    const tailBtn = (v: string, label: string) =>
      `<button type="button" class="ib${cur === v ? " is-on" : ""}" data-tail="${v}" title="꼬리 ${label}">${label}</button>`;
    return [
      b("smaller", "ᴀ⁻", "글자 작게"),
      b("bigger", "ᴀ⁺", "글자 크게"),
      tailed ? `<span class="ib-sep"></span>${tailBtn("left", "◀")}${tailBtn("right", "▶")}${tailBtn("none", "✕")}` : "",
      `<span class="ib-sep"></span>`,
      b("front", "⬆", "맨 앞으로"),
      b("dup", "⧉", "복제"),
      b("del", "🗑", "삭제", "is-danger"),
    ].join("");
  }
  function paintProps() {
    const it = findItem();
    killBar();
    if (!it || !sel) return;
    const el = root.querySelector(`#scene-${sel.sceneNo} .item[data-id="${sel.id}"]`) as HTMLElement | null;
    const layer = el?.parentElement;
    if (!layer || !el) return;

    const bar = document.createElement("div");
    bar.id = BAR_ID;
    bar.className = "item-bar";
    bar.innerHTML = barHTML(it);
    layer.appendChild(bar);

    const box = layer.getBoundingClientRect();
    const r = el.getBoundingClientRect();
    const above = r.top - box.top > 46;
    bar.style.left = `${((r.left + r.width / 2 - box.left) / box.width) * 100}%`;
    bar.style.top = above ? `${((r.top - box.top) / box.height) * 100}%` : `${((r.bottom - box.top) / box.height) * 100}%`;
    bar.classList.toggle("is-below", !above);

    bar.addEventListener("pointerdown", (e) => e.stopPropagation());
    bar.addEventListener("click", (e) => {
      const t = (e.target as HTMLElement).closest("[data-tail]") as HTMLElement | null;
      if (t) return setTail(t.dataset.tail as string);
      const b = (e.target as HTMLElement).closest("[data-act]") as HTMLElement | null;
      if (b) ACTS[b.dataset.act as keyof typeof ACTS]?.();
    });
  }

  const ACTS = {
    bigger: () => bumpSize(2),
    smaller: () => bumpSize(-2),
    front: () => {
      const it = findItem();
      if (!it || !sel) return;
      const st = sc(sel.sceneNo);
      st.items = [...st.items.filter((i) => i.id !== it.id), it];
      save();
      paintItems(sel.sceneNo);
    },
    dup: () => {
      const it = findItem();
      if (!it || !sel) return;
      const copy: Item = { ...it, id: `i${++uid}`, x: Math.min(90, it.x + 5), y: Math.min(92, it.y + 5) };
      sc(sel.sceneNo).items.push(copy);
      sel = { sceneNo: sel.sceneNo, id: copy.id };
      save();
      paintItems(sel.sceneNo);
    },
    del: () => {
      const it = findItem();
      if (!it || !sel) return;
      const st = sc(sel.sceneNo);
      st.items = st.items.filter((i) => i.id !== it.id);
      const no = sel.sceneNo;
      sel = null;
      save();
      paintItems(no);
    },
  };

  function bumpSize(d: number) {
    const it = findItem();
    if (!it || !sel) return;
    it.size = Math.max(6, Math.min(70, it.size + d));
    save();
    paintItems(sel.sceneNo);
  }
  function setTail(v: string) {
    const it = findItem();
    if (!it || !sel) return;
    it.tail = v;
    save();
    paintItems(sel.sceneNo);
  }
  function clearSel() {
    const no = sel?.sceneNo;
    sel = null;
    if (no) paintItems(no);
    else paintProps();
  }

  // ---- 제목 고치기 (샘플은 저장할 곳이 없다) ---------------------------------
  function setupTitleEdit() {
    const h = $(root, "[data-title-edit]");
    if (!h) return;
    const commit = () => {
      h.contentEditable = "false";
      h.classList.remove("is-editing");
      if (!data) return;
      h.textContent = data.title;
      toast("샘플입니다 — 제목은 실제 작품에서만 바뀝니다.");
    };
    const enter = () => {
      if (!data || h.isContentEditable) return;
      h.contentEditable = "plaintext-only";
      h.classList.add("is-editing");
      h.focus();
      const r = document.createRange();
      r.selectNodeContents(h);
      const sl = getSelection();
      sl?.removeAllRanges();
      if (sl) sl.addRange(r);
    };
    h.addEventListener("click", enter);
    h.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (!h.isContentEditable) {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          enter();
        }
        return;
      }
      if (e.key === "Enter") {
        e.preventDefault();
        h.blur();
      }
      if (e.key === "Escape") {
        e.preventDefault();
        if (data) h.textContent = data.title;
        h.blur();
      }
    });
    h.addEventListener("blur", commit);
  }

  // ---- 토스트 --------------------------------------------------------------
  let toastT: ReturnType<typeof setTimeout> | null = null;
  function toast(msg: string) {
    const el = $(root, "#toast");
    if (!el) return;
    el.textContent = msg;
    el.hidden = false;
    if (toastT) clearTimeout(toastT);
    toastT = setTimeout(() => {
      el.hidden = true;
    }, 3200);
  }

  // ---- 작품 목록 — 샘플 하나뿐이다 -------------------------------------------
  function paintWorks() {
    const host = $(root, "#worksList");
    if (!host) return;
    host.innerHTML =
      `<button type="button" class="work-card" data-run="" aria-current="true">` +
      `<span class="work-thumb is-empty" aria-hidden="true">◇</span>` +
      `<span><span class="work-name">샘플 보기</span>` +
      `<span class="work-sub">목업 — 서버 없이도 열립니다</span></span></button>`;
  }
  function setupWorksToggle() {
    const btn = $(root, "#worksToggle");
    const body = $(root, ".ed-body");
    if (!btn || !body) return;
    let off = true;
    const apply = (v: boolean) => {
      body.classList.toggle("works-off", v);
      btn.setAttribute("aria-expanded", v ? "false" : "true");
    };
    apply(off);
    const onClick = () => {
      off = !off;
      apply(off);
      if (!off) window.scrollTo({ top: 0 });
    };
    btn.addEventListener("click", onClick);
    cleanupFns.push(() => btn.removeEventListener("click", onClick));
  }

  // ---- 장 그리기 -------------------------------------------------------------
  function render() {
    const d = data;
    if (!d) return;
    const ep = d.episode || 1;
    const edTitle = $(root, "#edTitle");
    if (edTitle) edTitle.textContent = d.title;
    const edMeta = $(root, "#edMeta");
    if (edMeta) {
      edMeta.textContent = `${d.character} · ${ep}화 · ${d.scenes.length}장 ${d.scenes.reduce((n, s) => n + s.cuts.length, 0)}컷`;
    }
    const edGenre = $(root, "#edGenre");
    if (edGenre) edGenre.textContent = [d.genre, d.style_label].filter(Boolean).join(" · ");
    const edEpisode = $(root, "#edEpisode");
    if (edEpisode) edEpisode.textContent = d.title;
    const edLogline = $(root, "#edLogline");
    if (edLogline) edLogline.textContent = d.logline;
    const edFootNote = $(root, "#edFootNote");
    if (edFootNote) edFootNote.textContent = `여기까지가 ${ep}화입니다.`;

    const scenesHost = $(root, "#scenes");
    if (scenesHost) {
      scenesHost.innerHTML = d.scenes.map((s, i) => sceneCard(s) + gapBar(s, i === d.scenes.length - 1)).join("");
    }
    d.scenes.forEach((s) => {
      paintItems(s.no);
      paintFeedback(s.no);
    });
    wireScenes();
    wireGaps();
    paintScript();
  }

  // ---- 시작 ------------------------------------------------------------------
  async function boot() {
    load();
    paintCredit();
    paintLedger();
    paintDock();
    setupTitleEdit();
    paintWorks();
    setupWorksToggle();

    try {
      const res = await fetch("/static/samples/mock.json");
      if (!res.ok) throw new Error(await res.text());
      data = await res.json();
    } catch {
      const stage = $(root, "#stageCol");
      const html = `<div class="lou-note"><p>샘플 데이터를 읽지 못했어요.</p></div>`;
      if (stage) stage.innerHTML = html;
      return;
    }
    if (disposed) return;

    render();

    $$(root, ".dock-tab").forEach((b) =>
      b.addEventListener("click", () => {
        tab = b.dataset.tab as typeof tab;
        paintDock();
        setDock(true);
        const dock = $(root, "#edDock");
        const grid = $(root, "#dockGrid");
        if (!dock || !grid) return;
        const g = grid.getBoundingClientRect();
        const d = dock.getBoundingClientRect();
        if (g.top > d.bottom - 60) dock.scrollTop += g.top - d.top - 8;
      })
    );
    $(root, "#dockFold")?.addEventListener("click", () => setDock(false));
    $(root, "#dockOpen")?.addEventListener("click", () => setDock(true));
    $(root, "#dockScrim")?.addEventListener("click", () => setDock(false));

    const onDocPointerDown = (e: PointerEvent) => {
      if (!sel) return;
      if ((e.target as HTMLElement).closest(".item, .item-bar, .dock-item, #edDock")) return;
      clearSel();
    };
    document.addEventListener("pointerdown", onDocPointerDown);
    cleanupFns.push(() => document.removeEventListener("pointerdown", onDocPointerDown));

    $(root, "#showOverlay")?.addEventListener("change", () => data?.scenes.forEach((s) => paintItems(s.no)));

    $(root, "#ledgerBtn")?.addEventListener("click", () => setLedger(!!$(root, "#dockLedger")?.hidden));
    $(root, "#ledgerClose")?.addEventListener("click", () => setLedger(false));

    $(root, "#scriptBtn")?.addEventListener("click", () => {
      const panel = $(root, "#scriptPanel");
      if (panel) panel.hidden = !panel.hidden;
    });
    $(root, "#scriptClose")?.addEventListener("click", () => {
      const panel = $(root, "#scriptPanel");
      if (panel) panel.hidden = true;
    });

    $(root, "#saveBtn")?.addEventListener("click", () => {
      save();
      toast("샘플입니다 — 얹은 것은 이 브라우저에만 저장됩니다.");
    });
    $(root, "#bakeBtn")?.addEventListener("click", () => {
      toast("샘플에는 구울 그림이 없습니다. 실제 작품이 있어야 이미지로 뽑을 수 있습니다.");
    });

    $(root, "#regenAskCancel")?.addEventListener("click", closeAsk);
    $(root, "#regenAskGo")?.addEventListener("click", confirmAsk);
    $(root, "#regenAsk")?.addEventListener("click", (e) => {
      if ((e.target as HTMLElement).id === "regenAsk") closeAsk();
    });

    const onKeyDown = (e: KeyboardEvent) => {
      const askModal = $(root, "#regenAsk");
      if (askModal && !askModal.hidden) {
        if (e.key === "Escape") {
          e.preventDefault();
          closeAsk();
        }
        return;
      }
      const activeTag = (document.activeElement as HTMLElement | null)?.tagName || "";
      if ((e.key === "Delete" || e.key === "Backspace") && sel && !/^(INPUT|TEXTAREA)$/.test(activeTag)) {
        e.preventDefault();
        ACTS.del();
      }
      if (e.key === "Escape") {
        if (sel) clearSel();
        else setDock(false);
      }
    };
    document.addEventListener("keydown", onKeyDown);
    cleanupFns.push(() => document.removeEventListener("keydown", onKeyDown));
  }

  boot();

  return () => {
    disposed = true;
    for (const t of timers) clearTimeout(t);
    if (toastT) clearTimeout(toastT);
    for (const fn of cleanupFns) fn();
  };
}
