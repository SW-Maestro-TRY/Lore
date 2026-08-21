/* LORE 편집실 — **목업입니다.**
 *
 * 아무것도 생성하지 않습니다. 이미 만들어 둔 1화(`/static/samples/mock.json`)를
 * 재료로 읽어서, 결과물을 웹툰처럼 내려 읽으면서
 *
 *   · 장마다 다시 그리기 (크레딧이 빠져나가는 것까지 흉내)
 *   · 말풍선 없이 다시 그리기
 *   · 스토리 / 연출 / 그림으로 나뉜 피드백
 *   · 그림 위에 말풍선·스티커·효과음 얹기
 *
 * 를 해 보는 화면입니다. 서버 상태가 필요 없어서 **한 번도 안 돌려도** 열립니다.
 *
 * 얹은 것과 피드백, 크레딧 잔액은 localStorage 에 남습니다 — 새로고침해도
 * 그대로입니다. 목업이라 서버로는 아무것도 안 보냅니다.
 */

const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const KEY = "lore_editor_mock_v1";

/* 크레딧 값은 전부 가짜입니다. 실제 과금과 무관합니다. */
const COST = { regen: 40, regenFeedback: 60, nobubble: 0 };
const START_CREDIT = 1240;

const BUBBLES = [
  ["normal",    "일반",    "여기 앉아도 돼?"],
  ["shout",     "외침",    "비켜!!"],
  ["whisper",   "속삭임",  "…아무한테도 말하지 마."],
  ["thought",   "속마음",  "이건 좀 아닌데."],
  ["narration", "나레이션", "그날 밤, 아무도 잠들지 못했다."],
  ["flash",     "회상",    "그때도 이랬지."],
];
// 꼬리를 가질 수 있는 말풍선. 나레이션은 상자라서 꼬리가 없고(화자가 없다),
// 회상은 흐려지는 테두리가 그 자리를 대신한다.
const TAILED = new Set(["normal", "shout", "whisper", "thought"]);

const STICKERS = ["💦", "❤️", "✨", "💢", "❗", "❓", "🌟", "🎵", "⚡", "💀", "😳", "🔥"];
const SFX = ["쿵", "우당탕", "스윽", "두근", "촤악", "번쩍", "탁", "위이잉—"];

let data = null;
let state = { credit: START_CREDIT, scenes: {}, ledger: [] };
let sel = null;          // 선택한 요소 { sceneNo, id }
let activeScene = 1;
let tab = "bubble";
let uid = Date.now();

/* ------------------------------------------------------------------ 저장 */

function load() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || "null");
    if (raw && typeof raw === "object") state = { ...state, ...raw };
  } catch { /* 망가졌으면 새로 시작한다 */ }
}
function save() {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { /* 용량 초과 */ }
}
function sc(no) {
  if (!state.scenes[no]) state.scenes[no] = { items: [], fb: {}, ver: 1, noBubble: false };
  return state.scenes[no];
}

/* ------------------------------------------------------------------ 크레딧 */

function spend(amount, label, fromEl) {
  state.credit = Math.max(0, state.credit - amount);
  state.ledger.unshift({ label, amount, at: new Date().toLocaleTimeString("ko-KR",
    { hour: "2-digit", minute: "2-digit" }) });
  state.ledger = state.ledger.slice(0, 12);
  save();
  paintCredit(true);
  paintLedger();
  if (fromEl) flyCredit(amount, fromEl);
}
function paintCredit(bump) {
  const el = $("#creditNum");
  el.textContent = state.credit.toLocaleString("ko-KR");
  if (bump) {
    const box = $("#creditBox");
    box.classList.remove("bump"); void box.offsetWidth; box.classList.add("bump");
  }
}
function flyCredit(amount, el) {
  const fly = $("#fly"), r = el.getBoundingClientRect();
  fly.textContent = `−${amount} C`;
  fly.style.left = `${r.left + r.width / 2 - 22}px`;
  fly.style.top = `${r.top - 8}px`;
  fly.hidden = false;
  fly.style.animation = "none"; void fly.offsetWidth; fly.style.animation = "";
  clearTimeout(fly._t);
  fly._t = setTimeout(() => { fly.hidden = true; }, 1150);
}
function paintLedger() {
  const ul = $("#ledgerList");
  if (!state.ledger.length) {
    ul.innerHTML = `<li class="ledger-empty">아직 쓴 크레딧이 없습니다.</li>`;
    return;
  }
  ul.innerHTML = state.ledger.map(x =>
    `<li><span>${x.at} · ${esc(x.label)}</span><b>−${x.amount}</b></li>`).join("");
}

/* ------------------------------------------------------------------ 장 그리기 */

function render() {
  $("#edTitle").textContent = data.title;
  $("#edMeta").textContent =
    `${data.character} · 1화 · ${data.scenes.length}장 / ` +
    `${data.scenes.reduce((n, s) => n + s.cuts.length, 0)}컷 · 한 장에 ${data.cuts_per_sheet}컷`;
  $("#edGenre").textContent = `${data.genre} · ${data.style_label}`;
  $("#edEpisode").textContent = data.title;
  $("#edLogline").textContent = data.logline;

  $("#scenes").innerHTML = data.scenes.map(s => sceneCard(s)).join("");
  data.scenes.forEach(s => { paintItems(s.no); paintFeedback(s.no); });
  wireScenes();
}

function sceneCard(s) {
  const st = sc(s.no);
  const cuts = s.cuts.map(c => c.no).join("·");
  return `
  <section class="scene" data-scene="${s.no}" id="scene-${s.no}">
    <div class="scene-head">
      <span class="scene-no">${s.no}번째 장</span>
      <span>컷 ${cuts}</span>
      <span class="ver" data-ver>v${st.ver}</span>
      <span class="flag" data-nobub ${st.noBubble ? "" : "hidden"}>말풍선 없음</span>
    </div>

    <div class="stage-wrap" data-wrap style="aspect-ratio:${s.w}/${s.h}">
      <!-- width/height 를 박아 자리를 미리 잡는다. 안 그러면 lazy 이미지가
           뜨기 전까지 높이가 0 이라 카드가 납작해졌다가 튄다. -->
      <img src="${s.image}" alt="${s.no}번째 장" width="${s.w}" height="${s.h}" loading="lazy">
      <div class="overlay" data-overlay></div>
    </div>

    <div class="scene-tools">
      <button type="button" class="btn btn-quiet btn-sm" data-act="regen">
        다시 그리기 <span class="cost">−${COST.regen} C</span>
      </button>
      <label class="chk">
        <input type="checkbox" data-nobubble ${st.noBubble ? "checked" : ""}>
        말풍선 없이
      </label>
      <span class="spacer"></span>
      <button type="button" class="btn btn-quiet btn-sm" data-act="fb">피드백</button>
    </div>

    <div class="fb" data-fb>
      <div class="fb-grid">
        <label class="fb-cell fb-story">
          <span>📖 스토리<small>대사가 어색하다 / 이 장면 필요 없다 / 훅이 약하다</small></span>
          <textarea data-fbk="story" placeholder="이야기 자체에 대한 말"></textarea>
        </label>
        <label class="fb-cell fb-direct">
          <span>🎬 연출<small>컷을 더 붙여라 / 여기서 끊어라 / 클로즈업으로</small></span>
          <textarea data-fbk="direct" placeholder="컷 나누기·카메라·리듬에 대한 말"></textarea>
        </label>
        <label class="fb-cell fb-art">
          <span>🎨 그림<small>옷이 다르다 / 얼굴이 작다 / 서술과 다르게 그려졌다</small></span>
          <textarea data-fbk="art" placeholder="그림에 대한 말"></textarea>
        </label>
      </div>

      <div class="fb-cuts">
        ${s.cuts.map(c => `
          <div class="fb-cut">
            <i>CUT ${String(c.no).padStart(2, "0")}${c.shot ? " · " + esc(c.shot) : ""}</i>
            ${c.narration ? ` ${esc(c.narration)}` : ""}
            ${c.dialogue ? ` <b>${esc(c.speaker || "?")}</b> ${esc(c.dialogue)}` : ""}
            ${c.thought ? ` (${esc(c.thought)})` : ""}
            ${c.sfx ? ` <b>${esc(c.sfx)}</b>` : ""}
          </div>`).join("")}
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

function wireScenes() {
  $$(".scene").forEach(el => {
    const no = +el.dataset.scene;

    el.addEventListener("pointerdown", () => setActive(no), true);

    $("[data-act='fb']", el).addEventListener("click", () =>
      $("[data-fb]", el).classList.toggle("is-open"));

    $("[data-act='regen']", el).addEventListener("click", e =>
      regen(no, e.currentTarget, COST.regen, []));

    $("[data-act='fbregen']", el).addEventListener("click", e => {
      const notes = ["story", "direct", "art"]
        .map(k => [k, ($(`[data-fbk='${k}']`, el).value || "").trim()])
        .filter(([, v]) => v);
      if (!notes.length) return toast("피드백을 한 칸이라도 적어 주세요.");
      regen(no, e.currentTarget, COST.regenFeedback, notes);
    });

    $("[data-act='fbclear']", el).addEventListener("click", () => {
      ["story", "direct", "art"].forEach(k => { $(`[data-fbk='${k}']`, el).value = ""; });
      sc(no).fb = {}; save();
    });

    $$("[data-fbk]", el).forEach(t => t.addEventListener("input", () => {
      sc(no).fb[t.dataset.fbk] = t.value; save();
    }));

    $("[data-nobubble]", el).addEventListener("change", e => {
      sc(no).noBubble = e.target.checked; save();
      toast(e.target.checked
        ? "다음에 다시 그릴 때 말풍선 없이 그립니다. 대사는 오른쪽에서 얹으세요."
        : "말풍선을 그림 안에 그립니다.");
    });
  });
  setActive(activeScene);
}

function setActive(no) {
  activeScene = no;
  $$(".scene").forEach(el => el.classList.toggle("is-active", +el.dataset.scene === no));
  $("#activeSceneLabel").textContent = `${no}번째 장`;
}

/* ------------------------------------------------------------------ 다시 그리기 (흉내) */

function regen(no, btn, cost, notes) {
  const el = $(`#scene-${no}`), wrap = $("[data-wrap]", el);
  if (state.credit < cost) return toast("크레딧이 모자랍니다. (목업이라 충전은 없습니다)");

  const st = sc(no);
  const what = [
    st.noBubble ? "말풍선 없이" : "말풍선 포함",
    ...notes.map(([k, v]) => `${{ story: "스토리", direct: "연출", art: "그림" }[k]}: ${v}`),
  ];

  const veil = document.createElement("div");
  veil.className = "regen-veil";
  veil.innerHTML = `<div class="spin"></div><div>${no}번째 장을 다시 그리는 중…<br>
    <small style="color:#9a9aa5">${esc(what.join(" · ").slice(0, 90))}</small></div>`;
  wrap.append(veil);

  spend(cost, `${no}번째 장 다시 그리기`, btn);

  // 실제 생성은 여기에 연결됩니다. 지금은 기다리는 모습만 보여줍니다.
  setTimeout(() => {
    veil.remove();
    st.ver += 1; save();
    $("[data-ver]", el).textContent = `v${st.ver}`;
    $("[data-nobub]", el).hidden = !st.noBubble;
    toast(`목업입니다 — 실제로는 여기서 ${no}번째 장이 v${st.ver} 로 다시 그려집니다.`);
  }, 1800 + Math.random() * 900);
}

/* ------------------------------------------------------------------ 얹는 것 */

function tailOf(it) {
  if (it.type !== "bubble" || !TAILED.has(it.variant)) return "none";
  return it.tail || "left";
}

function itemHTML(it) {
  const inner =
    it.type === "bubble"
      ? `<div class="bub bub-${it.variant} tail-${tailOf(it)}" style="font-size:${it.size}px">${esc(it.text)}</div>`
      : it.type === "sticker"
        ? `<div class="stk" style="font-size:${it.size * 2.2}px">${it.text}</div>`
        : `<div class="sfx" style="font-size:${it.size * 2}px">${esc(it.text)}</div>`;
  return `<div class="item ${sel && sel.id === it.id ? "sel" : ""}" data-id="${it.id}"
    style="left:${it.x}%; top:${it.y}%; width:${it.w}%; transform:rotate(${it.rot}deg)">
    ${inner}<button type="button" class="kill" aria-label="삭제">✕</button>
    <div class="handle"></div></div>`;
}

function paintItems(no) {
  const layer = $(`#scene-${no} [data-overlay]`);
  if (!layer) return;
  layer.innerHTML = sc(no).items.map(itemHTML).join("");
  layer.classList.toggle("is-hidden", !$("#showOverlay").checked);
  $$(".item", layer).forEach(el => wireItem(no, el));
}

function paintFeedback(no) {
  const el = $(`#scene-${no}`), fb = sc(no).fb || {};
  ["story", "direct", "art"].forEach(k => {
    const t = $(`[data-fbk='${k}']`, el);
    if (t && fb[k]) { t.value = fb[k]; $("[data-fb]", el).classList.add("is-open"); }
  });
}

function addItem(type, variant, text) {
  const no = activeScene, st = sc(no);
  const it = {
    id: `i${++uid}`, type, variant, text,
    x: 22, y: 30 + (st.items.length % 5) * 9, w: type === "bubble" ? 44 : 16,
    size: type === "bubble" ? 15 : 16, rot: type === "sfx" ? -7 : 0,
    tail: type === "bubble" ? "left" : "none",
  };
  st.items.push(it); save();
  sel = { sceneNo: no, id: it.id };
  paintItems(no); paintProps();
  document.getElementById(`scene-${no}`).scrollIntoView({ behavior: "smooth", block: "center" });
}

function findItem() {
  if (!sel) return null;
  return sc(sel.sceneNo).items.find(i => i.id === sel.id) || null;
}

function wireItem(no, el) {
  const id = el.dataset.id;
  const wrap = el.closest("[data-wrap]");

  // 선택만 바꾼다 — 여기서 paintItems() 를 부르면 안 된다.
  //
  // paintItems() 는 layer.innerHTML 을 통째로 새로 그린다. pointerdown 안에서
  // 그걸 부르면 지금 잡고 있는 el 이 그 순간 DOM 에서 떨어져 나가고, 바로 뒤에
  // 거는 setPointerCapture / pointermove / pointerup 이 전부 **유령 노드**에
  // 걸린다. 그래서 넣기와 선택은 되는데 끌기와 크기 조절만 통째로 죽어 있었다.
  const pick = () => {
    sel = { sceneNo: no, id };
    $$(".item", el.parentElement).forEach(n =>
      n.classList.toggle("sel", n.dataset.id === id));
    paintProps();
  };

  el.addEventListener("pointerdown", ev => {
    if (ev.target.classList.contains("kill")) return;
    ev.preventDefault(); pick();
    const it = sc(no).items.find(i => i.id === id);
    const box = wrap.getBoundingClientRect();
    const resizing = ev.target.classList.contains("handle");
    const sx = ev.clientX, sy = ev.clientY, ox = it.x, oy = it.y, ow = it.w;
    el.classList.add("dragging");
    el.setPointerCapture(ev.pointerId);

    const move = e => {
      const dx = (e.clientX - sx) / box.width * 100;
      const dy = (e.clientY - sy) / box.height * 100;
      if (resizing) it.w = Math.max(5, Math.min(96, ow + dx));
      else { it.x = Math.max(-6, Math.min(98, ox + dx)); it.y = Math.max(-4, Math.min(97, oy + dy)); }
      el.style.left = `${it.x}%`; el.style.top = `${it.y}%`; el.style.width = `${it.w}%`;
    };
    const up = () => {
      el.classList.remove("dragging"); save();
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
    };
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
  });

  el.addEventListener("dblclick", () => { pick(); $("#propText")?.focus(); });
  $(".kill", el).addEventListener("click", ev => {
    ev.stopPropagation();
    const st = sc(no);
    st.items = st.items.filter(i => i.id !== id);
    if (sel && sel.id === id) sel = null;
    save(); paintItems(no); paintProps();
  });
}

/* ------------------------------------------------------------------ 도구 패널 */

function paintDock() {
  $$(".dock-tab").forEach(b => b.classList.toggle("is-on", b.dataset.tab === tab));
  const grid = $("#dockGrid");
  if (tab === "bubble") {
    grid.innerHTML = BUBBLES.map(([v, label, sample]) => `
      <button type="button" class="dock-item" data-add="bubble" data-variant="${v}"
              data-text="${esc(sample)}">
        <div class="prev"><div class="bub bub-${v}">${esc(sample.slice(0, 7))}</div></div>
        <span>${label}</span>
      </button>`).join("");
  } else if (tab === "sticker") {
    grid.innerHTML = STICKERS.map(s => `
      <button type="button" class="dock-item" data-add="sticker" data-text="${s}">
        <div class="prev"><div class="stk">${s}</div></div>
      </button>`).join("");
  } else {
    grid.innerHTML = SFX.map(s => `
      <button type="button" class="dock-item" data-add="sfx" data-text="${esc(s)}">
        <div class="prev"><div class="sfx">${esc(s)}</div></div>
      </button>`).join("");
  }
  $$("[data-add]", grid).forEach(b => b.addEventListener("click", () =>
    addItem(b.dataset.add, b.dataset.variant || "", b.dataset.text)));
}

function paintProps() {
  const it = findItem();
  $("#dockProps").hidden = !it;
  if (!it) return;
  $("#propTitle").textContent =
    { bubble: "말풍선", sticker: "스티커", sfx: "효과음" }[it.type] || "요소";
  $("#propTextField").hidden = it.type === "sticker";
  $("#propText").value = it.text;
  $("#propSize").value = it.size;
  $("#propRot").value = it.rot;
  $("#propWide").value = Math.round(it.w);
  // 꼬리 칸은 꼬리를 가질 수 있는 말풍선에만 보인다 — 나레이션 상자와 스티커·
  // 효과음에 "꼬리 왼쪽" 을 물어보면 안 되는 것을 물어보는 것이다.
  const tailed = it.type === "bubble" && TAILED.has(it.variant);
  $("#propTailField").hidden = !tailed;
  if (tailed) {
    const cur = tailOf(it);
    $$("#propTail button").forEach(b =>
      b.classList.toggle("is-on", b.dataset.tail === cur));
  }
}

/* ------------------------------------------------------------------ 잡동사니 */

function esc(s) {
  return String(s ?? "").replace(/[&<>"]/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
}
let toastT = null;
function toast(msg) {
  const el = $("#toast");
  el.textContent = msg; el.hidden = false;
  clearTimeout(toastT); toastT = setTimeout(() => { el.hidden = true; }, 3200);
}

/* ---- run 고르개 — 어떤 작품을 편집할지 -------------------------------- */
async function paintRunPicker(current) {
  const host = $(".ed-top-right");
  if (!host) return;
  let runs = [];
  try { runs = (await (await fetch("/api/runs")).json()).runs || []; } catch { return; }
  if (!runs.length) return;
  const sel = document.createElement("select");
  sel.className = "run-pick";
  sel.innerHTML =
    `<option value="">목업 보기</option>` +
    runs.map(r => `<option value="${esc(r.run_id)}"${r.run_id === current ? " selected" : ""}>`
      + `${esc(r.character || "?")} — ${esc(r.title || "")}</option>`).join("");
  sel.addEventListener("change", () => {
    location.search = sel.value ? `?run=${encodeURIComponent(sel.value)}` : "";
  });
  host.prepend(sel);
}

/* ------------------------------------------------------------------ 시작 */

document.addEventListener("DOMContentLoaded", async () => {
  load(); paintCredit(); paintLedger(); paintDock();

  // ?run=<run_id> 가 있으면 **그 run 의 1화**를 연다. 없으면 예전처럼 목업이다.
  // 편집기는 "이미 그려진 것을 고치는 자리" 라서, 랜딩에서 만든 것이든 하네스를
  // 직접 돌린 것이든 똑같이 열려야 한다.
  const runId = new URLSearchParams(location.search).get("run");
  const src = runId ? `/api/runs/${encodeURIComponent(runId)}/episode`
                    : "/static/samples/mock.json";
  try {
    const res = await fetch(src);
    if (!res.ok) throw new Error(await res.text());
    data = await res.json();
  } catch (err) {
    document.body.innerHTML =
      `<p style="padding:60px;text-align:center;color:#969aa8">` +
      (runId ? `<b>${esc(runId)}</b> 을(를) 열지 못했습니다.<br>`
             + `그 run 에 1화 컷과 그려진 장이 있어야 합니다.`
             : `목업 데이터를 읽지 못했습니다 (web/samples/mock.json).`) +
      `<br><br><a href="/editor" style="color:#7aa2ff">목업으로 돌아가기</a></p>`;
    return;
  }
  // 목업이 아니면 배지를 지우고 어떤 run 인지 밝힌다.
  if (runId) {
    document.querySelector(".mock-badge")?.remove();
    const meta = $("#edMeta");
    if (meta) meta.textContent = `${data.character || ""} · ${runId}`;
  }
  render();
  paintRunPicker(runId);

  $$(".dock-tab").forEach(b => b.addEventListener("click", () => {
    tab = b.dataset.tab; paintDock();
  }));
  $("#showOverlay").addEventListener("change", () =>
    data.scenes.forEach(s => paintItems(s.no)));

  $("#propText").addEventListener("input", e => {
    const it = findItem(); if (!it) return;
    it.text = e.target.value; save(); paintItems(sel.sceneNo);
  });
  $("#propSize").addEventListener("input", e => {
    const it = findItem(); if (!it) return;
    it.size = +e.target.value; save(); paintItems(sel.sceneNo);
  });
  $("#propWide").addEventListener("input", e => {
    const it = findItem(); if (!it) return;
    it.w = +e.target.value; save(); paintItems(sel.sceneNo); paintProps();
  });
  $("#propTail").addEventListener("click", e => {
    const b = e.target.closest("button[data-tail]");
    const it = findItem(); if (!b || !it) return;
    it.tail = b.dataset.tail; save(); paintItems(sel.sceneNo); paintProps();
  });
  $("#propRot").addEventListener("input", e => {
    const it = findItem(); if (!it) return;
    it.rot = +e.target.value; save(); paintItems(sel.sceneNo);
  });
  $("#propFront").addEventListener("click", () => {
    const it = findItem(); if (!it) return;
    const st = sc(sel.sceneNo);
    st.items = [...st.items.filter(i => i.id !== it.id), it];
    save(); paintItems(sel.sceneNo);
  });
  $("#propDup").addEventListener("click", () => {
    const it = findItem(); if (!it) return;
    const copy = { ...it, id: `i${++uid}`, x: Math.min(90, it.x + 5), y: Math.min(92, it.y + 5) };
    sc(sel.sceneNo).items.push(copy);
    sel = { sceneNo: sel.sceneNo, id: copy.id };
    save(); paintItems(sel.sceneNo); paintProps();
  });
  $("#propDel").addEventListener("click", () => {
    const it = findItem(); if (!it) return;
    const st = sc(sel.sceneNo);
    st.items = st.items.filter(i => i.id !== it.id);
    const no = sel.sceneNo; sel = null;
    save(); paintItems(no); paintProps();
  });
  $("#propClose").addEventListener("click", () => {
    const no = sel && sel.sceneNo; sel = null;
    if (no) paintItems(no);
    paintProps();
  });

  $("#ledgerBtn").addEventListener("click", () =>
    { $("#dockLedger").hidden = !$("#dockLedger").hidden; });
  $("#ledgerClose").addEventListener("click", () => { $("#dockLedger").hidden = true; });

  $("#saveBtn").addEventListener("click", () =>
    toast("목업입니다 — 얹은 것과 피드백은 이 브라우저에만 저장됩니다."));

  document.addEventListener("keydown", e => {
    if ((e.key === "Delete" || e.key === "Backspace") && sel &&
        !/^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName)) {
      e.preventDefault(); $("#propDel").click();
    }
    if (e.key === "Escape") $("#propClose").click();
  });
});
