/* LORE 편집실
 *
 * `?run=<run_id>&ep=<N>` 로 열면 **그 작품의 그 회차**를 열고, 다시 그리기는
 * 진짜로 그립니다(장 단위 · `/api/runs/{run}/scenes/{n}/regen`). `ep` 를 빼면
 * 1화입니다. run 없이 열면 예전처럼 `/static/samples/mock.json` 목업이라 서버가
 * 없어도 화면을 볼 수 있습니다.
 *
 * 여기서 하는 일:
 *   · 회차 고르기 (여러 편을 만든 작품)
 *   · 장마다 다시 그리기 — 확인 창에서 항목·말을 받고 나서 굽습니다
 *   · 지난 판을 나란히 놓고 눌러서 바꾸기
 *   · 그림 위에 말풍선·스티커·효과음 얹기
 *
 * ── 얹은 것은 어디에 남는가 ────────────────────────────────────────────
 * 실제 작품(`?run=`)을 열었으면 **작품 폴더에 저장됩니다**(`overlay.json`).
 * 브라우저를 비워도, 다른 기기에서 열어도 그대로 있습니다. localStorage 는 그
 * 앞단의 임시 칸으로만 남깁니다 — 서버가 잠깐 안 되어도 하던 작업이 안 날아가게.
 *
 * 그리고 **그림으로 구울 수 있습니다** ("이미지로 뽑기"). 원본은 그대로 두고
 * `baked/scene{n}.png` 와 `episode_baked.png` 를 따로 만듭니다 — 말풍선을 옮긴 뒤
 * 다시 구우려면 밑그림이 깨끗해야 하기 때문입니다.
 *
 * 샘플(`run` 없음)은 예전처럼 이 브라우저에만 남습니다. 구울 그림이 없습니다.
 *
 * 저장 칸은 **작품마다 따로**입니다(`lore_editor_v2:<run_id>`). 예전에는 열쇠가
 * 하나뿐이라, A 작품에 얹은 스티커가 B 작품을 열었을 때 그대로 따라왔습니다 —
 * 장 번호만 같으면 남의 그림 위에 얹혔습니다. 이제 작품을 바꾸면 그 작품의
 * 것만 보입니다(처음 여는 작품이면 비어 있습니다).
 */

const $  = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];

/* 지금 열고 있는 작품과 회차. 저장 열쇠와 API 주소가 전부 이 두 값에 매인다.
 *
 * 회차를 안 보내면 서버가 1화로 알아듣는다 — 그래서 2화를 이어 만들어 놓고도
 * 편집실은 늘 1화만 열었고, 거기서 다시 그리면 **1화 그림을 덮어썼다.**
 * 저장 열쇠에도 회차를 넣는다: 장 번호가 회차마다 1부터 다시 시작하므로,
 * 열쇠가 회차를 모르면 2화 3번 장에 얹은 말풍선이 1화 3번 장에도 뜬다. */
let RUN_ID = "";
let EPISODE = 1;
function storeKey() { return `lore_editor_v2:${RUN_ID || "__mock__"}:ep${EPISODE}`; }
/* API 주소에 붙일 회차 꼬리. 물음표가 이미 있는 주소에는 &, 없으면 ?. */
function epq(sep = "?") { return `${sep}ep=${EPISODE}`; }

/* 크레딧은 아직 안 붙었습니다 (#16). 목업에서만 흉내로 셉니다. */
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

/* 작품이 정해진 **뒤에** 부른다 — 열쇠가 run_id 에 매여 있어서, 먼저 부르면
   앞 작품 칸을 읽는다. */
function load() {
  state = { credit: START_CREDIT, scenes: {}, ledger: [] };
  try {
    const raw = JSON.parse(localStorage.getItem(storeKey()) || "null");
    if (raw && typeof raw === "object") state = { ...state, ...raw };
  } catch { /* 망가졌으면 새로 시작한다 */ }
}
function save() {
  try { localStorage.setItem(storeKey(), JSON.stringify(state)); } catch { /* 용량 초과 */ }
  pushSoon();
}

/* ── 서버로 올리기 ──────────────────────────────────────────────────────
   끌 때마다 올리면 초당 수십 번이 된다. 손을 멈추고 600ms 뒤에 한 번만 올린다.
   실패해도 조용히 넘어간다 — localStorage 에는 이미 들어갔고, "이미지로 뽑기"
   가 어차피 지금 상태를 통째로 다시 올린다. 여기서 토스트를 띄우면 서버가
   잠깐 느릴 때마다 편집을 방해한다. */
let pushT = null, pushing = false, pushDirty = false;

/* 그림이 화면에서 몇 px 로 보이는가 — 글자 크기를 그림 해상도로 옮길 때 쓴다.
   퍼센트인 자리(x·y·w)와 달리 size 는 CSS 픽셀이라 기준 폭이 있어야 한다. */
function refWidth(no) {
  const wrap = document.querySelector(`#scene-${no} [data-wrap]`);
  const w = wrap ? Math.round(wrap.getBoundingClientRect().width) : 0;
  return w > 0 ? w : 720;
}

function overlayPayload() {
  const scenes = {};
  for (const s of (data?.scenes || [])) {
    const st = state.scenes[s.no];
    // 한 번도 안 건드린 장은 안 보낸다. 빈 배열을 보내면 서버가 "여기 있던 것을
    // 지웠다" 로 읽어서, 다른 기기에서 얹어 둔 것이 사라진다.
    if (!st || !Array.isArray(st.items)) continue;
    scenes[s.no] = { ref_w: refWidth(s.no), items: st.items };
  }
  return { scenes };
}

async function pushNow() {
  if (!RUN_ID) return;                       // 샘플은 올릴 곳이 없다
  if (pushing) { pushDirty = true; return; }
  pushing = true;
  try {
    await fetch(`/api/runs/${encodeURIComponent(RUN_ID)}/overlay${epq()}`,
      { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(overlayPayload()) });
  } catch { /* 아래에서 다시 시도된다 */ }
  pushing = false;
  if (pushDirty) { pushDirty = false; pushSoon(); }
}

function pushSoon() {
  if (!RUN_ID) return;
  clearTimeout(pushT);
  pushT = setTimeout(pushNow, 600);
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
  const ep = data.episode || EPISODE;
  $("#edTitle").textContent = data.title;
  $("#edMeta").textContent =
    `${data.character} · ${ep}화 · ${data.scenes.length}장 / ` +
    `${data.scenes.reduce((n, s) => n + s.cuts.length, 0)}컷 · 한 장에 ${data.cuts_per_sheet}컷`;
  $("#edGenre").textContent = `${data.genre} · ${data.style_label}`;
  $("#edEpisode").textContent = data.title;
  $("#edLogline").textContent = data.logline;
  $("#edFootNote").textContent =
    `여기까지가 ${ep}화입니다. 장 사이는 완성본과 같이 틈 없이 이어집니다.`;
  paintEpTabs();

  $("#scenes").innerHTML = data.scenes.map(s => sceneCard(s)).join("");
  data.scenes.forEach(s => { paintItems(s.no); paintFeedback(s.no); });
  wireScenes();
  // 지난 판은 서버에만 있다 — 목업에는 없다.
  if (RUN_ID) data.scenes.forEach(s => paintVersions(s.no));
}

/* 회차 고르개. 한 편뿐이면 안 그린다 — 고를 것이 없는 자리에 고르개를 두면
   "여기 뭔가 더 있나" 하고 누르게 된다. 고르면 주소를 바꾸고 새로 연다:
   얹은 것이 회차마다 다른 칸에 저장돼 있어서(storeKey), 화면만 갈아 끼우면 앞
   회차의 state 가 남는다. */
function paintEpTabs() {
  const host = $("#edEpTabs");
  const eps = (data && data.episodes) || [];
  if (!RUN_ID || eps.length < 2) { host.hidden = true; host.innerHTML = ""; return; }
  const cur = data.episode || EPISODE;
  host.hidden = false;
  host.innerHTML = eps.map(n =>
    `<button type="button" class="ep-tab" data-ep="${n}"` +
    `${n === cur ? ' aria-current="true"' : ""}>${n}화</button>`).join("");
  $$(".ep-tab", host).forEach(b => b.addEventListener("click", () => {
    if (b.getAttribute("aria-current") === "true") return;
    location.search = `?run=${encodeURIComponent(RUN_ID)}&ep=${b.dataset.ep}`;
  }));
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
        다시 그리기${RUN_ID ? "" : ` <span class="cost">−${COST.regen} C</span>`}
      </button>
      <label class="chk">
        <input type="checkbox" data-nobubble ${st.noBubble ? "checked" : ""}>
        글자 없이
      </label>
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

      <!-- 세 칸에 나눠 담기 어려운 말을 받는 자리. 셋으로만 물으면 "그냥 이
           장이 통째로 별로다" 같은 말을 적을 곳이 없어서 아무 데나 끼워 넣게
           되고, 그러면 프롬프트에 엉뚱한 이름표(스토리:/연출:)가 붙는다. -->
      <label class="fb-cell fb-all">
        <span>💬 전체<small>어디에 넣을지 애매한 말 · 이 장 전체에 대한 말</small></span>
        <textarea maxlength="320" data-fbk="all"
          placeholder="예: 이 장은 통째로 다시 갔으면 좋겠어요 / 앞 장이랑 분위기가 안 이어져요"></textarea>
      </label>

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
          피드백 반영해 다시 그리기${RUN_ID ? "" : ` <span class="cost">−${COST.regenFeedback} C</span>`}
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

    // 두 단추 다 확인 창을 거친다 — 굽는 데 1~2분과 실제 비용이 들어서, 잘못
    // 누른 것을 되돌릴 길이 없다. 다른 점은 창을 열 때 무엇이 채워져 있느냐뿐이다.
    $("[data-act='regen']", el).addEventListener("click", e =>
      askRegen(no, e.currentTarget, COST.regen, []));

    $("[data-act='fbregen']", el).addEventListener("click", e =>
      askRegen(no, e.currentTarget, COST.regenFeedback, readNotes(el)));

    $("[data-act='fbclear']", el).addEventListener("click", () => {
      FB_KEYS.forEach(k => { $(`[data-fbk='${k}']`, el).value = ""; });
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

/* ------------------------------------------------------------------ 다시 그리기
 *
 * 작품을 열고 있으면(RUN_ID) **진짜로 그린다.** 결과 화면과 같은 API 를 쓴다 —
 * 굽기 전에 지금 그림을 판본으로 뜨고, 실패하면 서버가 되돌려 놓는다.
 * 목업일 때만 기다리는 모습만 흉내낸다. */

/* 장 밑 피드백 칸에 적힌 것. 사람에게는 갈래로 나눠 물었지만 프롬프트에는 한
   줄로 간다 — run.py 의 {extra} 자리는 문장 하나를 받는다. "전체" 는 갈래가
   아니라서 이름표 없이 그대로 싣는다. */
const FB_KEYS = ["story", "direct", "art", "all"];
const FB_LABEL = { story: "스토리", direct: "연출", art: "그림", all: "" };

function readNotes(el) {
  return FB_KEYS
    .map(k => [k, ($(`[data-fbk='${k}']`, el)?.value || "").trim()])
    .filter(([, v]) => v);
}

function notesToText(notes) {
  return notes.map(([k, v]) => (FB_LABEL[k] ? `${FB_LABEL[k]}: ${v}` : v)).join(" / ");
}

/* ---- 다시 그리기 확인 창 -----------------------------------------------
 *
 * 예전에는 단추를 누른 순간 바로 구웠다. 한 장에 1~2분과 실제 생성 비용이 드는
 * 일이라 잘못 누른 것을 되돌릴 길이 없었고, **왜** 다시 그리는지를 적을 자리도
 * 그 흐름에는 없었다(장 밑 피드백 칸을 미리 펴 두는 사람은 드물다). 여기서 한
 * 번 멈춰서 항목·말·글자 여부를 받고, 취소할 길을 준다. 다 비워 둔 채 눌러도
 * 된다 — 그러면 같은 조건으로 한 번 더 그린다. */

let askCtx = null;                 // { no, btn, cost }
let sceneTags = [];                // /api/config 의 feedback_tags.scene

async function loadSceneTags() {
  try {
    const cfg = await (await fetch("/api/config")).json();
    sceneTags = (cfg.feedback_tags || {}).scene || [];
  } catch { /* 못 받으면 자유 입력만 남는다 — 다시 그리기 자체는 막지 않는다 */ }
}

function paintAskTags() {
  const wrap = $("#regenAskTags");
  wrap.replaceChildren(...sceneTags.map(t => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "fb-tag";
    b.dataset.tagId = t.id;
    b.textContent = t.label;
    b.setAttribute("aria-pressed", "false");
    b.addEventListener("click", () => b.setAttribute("aria-pressed",
      b.getAttribute("aria-pressed") === "true" ? "false" : "true"));
    return b;
  }));
}

function askRegen(no, btn, cost, notes) {
  askCtx = { no, btn, cost };
  const st = sc(no);
  $("#regenAskTitle").textContent = `${no}번째 장 다시 그리기`;
  $("#regenAskSub").textContent = RUN_ID
    ? "이 장만 새로 굽습니다. 지금 그림은 지난 판으로 남아서 언제든 되돌릴 수 있습니다."
    : "샘플이라 실제로 그리지는 않습니다 — 화면만 흉내 냅니다.";
  paintAskTags();
  // 장 밑에 이미 적어 둔 것이 있으면 그대로 실어 준다. 여기서 고쳐도 되고,
  // 다 지우고 눌러도 된다.
  $("#regenAskText").value = notesToText(notes);
  $("#regenAskTextless").checked = !!st.noBubble;
  $("#regenAsk").hidden = false;
  $("#regenAskText").focus();
}

function closeAsk() {
  $("#regenAsk").hidden = true;
  askCtx = null;
}

function confirmAsk() {
  if (!askCtx) return;
  const { no, btn, cost } = askCtx;
  const tags = [...document.querySelectorAll('#regenAskTags .fb-tag[aria-pressed="true"]')]
    .map(b => b.dataset.tagId);
  const feedback = $("#regenAskText").value.trim();
  const textless = $("#regenAskTextless").checked;
  // 확인 창에서 바꾼 "글자 없이" 는 그 장의 설정이 된다 — 창을 닫자마자
  // 장 머리의 표시와 갈리면 어느 쪽이 참인지 알 수 없다.
  const st = sc(no);
  st.noBubble = textless; save();
  const el = $(`#scene-${no}`);
  $("[data-nobubble]", el).checked = textless;
  $("[data-nobub]", el).hidden = !textless;
  closeAsk();
  regen(no, btn, cost, { feedback, textless, tags });
}

function regen(no, btn, cost, body) {
  const el = $(`#scene-${no}`), wrap = $("[data-wrap]", el);
  const st = sc(no);
  // 기다리는 동안 **무엇을 반영해서** 그리는 중인지 보여 준다. 항목만 고르고
  // 아무 말도 안 적는 사람이 대부분이라, 고른 항목도 같이 적는다.
  const picked = (body.tags || [])
    .map(id => (sceneTags.find(t => t.id === id) || {}).label).filter(Boolean);
  const what = [body.textless ? "글자 없이" : "글자 포함",
                ...picked, body.feedback].filter(Boolean);

  const veil = document.createElement("div");
  veil.className = "regen-veil";
  veil.innerHTML = `<div class="spin"></div><div data-veil-msg>${no}번째 장을 다시 그리는 중…<br>
    <small style="color:#9a9aa5">${esc(what.join(" · ").slice(0, 90))}</small></div>`;
  wrap.append(veil);

  if (!RUN_ID) {
    // 목업 — 서버가 없다. 크레딧 흉내와 기다리는 모습만.
    if (state.credit < cost) { veil.remove(); return toast("크레딧이 모자랍니다. (목업이라 충전은 없습니다)"); }
    spend(cost, `${no}번째 장 다시 그리기`, btn);
    setTimeout(() => {
      veil.remove();
      st.ver += 1; save();
      $("[data-ver]", el).textContent = `v${st.ver}`;
      $("[data-nobub]", el).hidden = !st.noBubble;
      toast(`목업입니다 — 실제 작품을 열면 여기서 진짜로 다시 그립니다.`);
    }, 1800 + Math.random() * 900);
    return;
  }
  realRegen(no, btn, body, veil, el);
}

async function realRegen(no, btn, body, veil, el) {
  const msg = $("[data-veil-msg]", veil);
  btn.disabled = true;
  let job;
  try {
    const res = await fetch(
      `/api/runs/${encodeURIComponent(RUN_ID)}/scenes/${no}/regen${epq()}`,
      { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body) });
    job = await res.json();
    if (!res.ok) throw new Error(job.error || "시작하지 못했습니다");
  } catch (err) {
    veil.remove(); btn.disabled = false;
    return toast(err.message);
  }

  // 한 장 굽는 데 1~2분이라 2초 간격이면 충분하다.
  while (true) {
    await new Promise(r => setTimeout(r, 2000));
    let s;
    try { s = await (await fetch(`/api/regens/${job.id}`)).json(); }
    catch { continue; }                       // 잠깐 끊겨도 다음 번에 이어진다
    if (msg && s.note) msg.innerHTML =
      `${no}번째 장을 다시 그리는 중…<br><small style="color:#9a9aa5">${esc(s.note.slice(0, 90))}</small>`;
    if (s.status === "done") {
      veil.remove();
      bustScene(no);
      paintVersions(no, s.versions);
      toast(`${no}번째 장을 다시 그렸습니다`);
      break;
    }
    if (s.status === "error" || s.status === "cancelled") {
      // 실패해도 원래 그림은 서버가 되돌려 놓는다. 화면은 그대로 두면 된다.
      veil.remove();
      toast(s.error || "다시 그리지 못했습니다 — 원래 그림은 그대로입니다");
      break;
    }
  }
  btn.disabled = false;
}

/* 브라우저가 같은 주소를 캐시하므로, 새로 그려도 주소가 같으면 옛 그림이 뜬다. */
const sceneBust = {};
function bustScene(no) {
  sceneBust[no] = Date.now();
  const img = $(`#scene-${no} [data-wrap] img`);
  if (img) img.src = `/api/runs/${encodeURIComponent(RUN_ID)}/page/${no}?w=1080${epq("&")}&t=${sceneBust[no]}`;
}

/* 지난 판 — 결과 화면과 같이 작은 그림으로 늘어놓고, 눌러서 그때그때 바꾼다. */
async function paintVersions(no, versions) {
  const slot = $(`#scene-${no} [data-versions]`);
  if (!slot || !RUN_ID) return;
  if (!versions) {
    try {
      versions = (await (await fetch(
        `/api/runs/${encodeURIComponent(RUN_ID)}/scenes/${no}/versions${epq()}`)).json()).versions;
    } catch { return; }
  }
  if (!versions || !versions.length) { slot.innerHTML = ""; return; }
  const cur = `
    <span class="ver-thumb is-current" title="지금 걸린 그림">
      <img src="/api/runs/${encodeURIComponent(RUN_ID)}/page/${no}?w=160${epq('&')}&t=${sceneBust[no] || 0}"
           alt="지금 그림" loading="lazy">
      <span class="ver-label">지금</span>
    </span>`;
  const past = versions.map(v => `
    <button type="button" class="ver-thumb js-revert" data-v="${v.version}" title="이 판으로 바꾸기">
      <img src="/api/runs/${encodeURIComponent(RUN_ID)}/scenes/${no}/versions/${v.version}?w=160${epq('&')}"
           alt="v${v.version}" loading="lazy">
      <span class="ver-label">v${v.version}</span>
    </button>`).join("");
  slot.innerHTML =
    `<span class="ver-strip-label">지난 판 — 눌러서 바꿔 보기</span>
     <div class="ver-strip">${cur}${past}</div>`;
  $$(".js-revert", slot).forEach(b => b.addEventListener("click", async () => {
    b.disabled = true;
    try {
      const res = await fetch(
        `/api/runs/${encodeURIComponent(RUN_ID)}/scenes/${no}/revert${epq()}`,
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ version: Number(b.dataset.v) }) });
      const out = await res.json();
      if (!res.ok) throw new Error(out.error || "되돌리지 못했습니다");
      bustScene(no);
      paintVersions(no, out.versions);
      toast(`${no}번째 장을 v${b.dataset.v} 로 바꿨습니다`);
    } catch (err) { toast(err.message); }
    b.disabled = false;
  }));
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
  FB_KEYS.forEach(k => {
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
/* 구운 결과 — 몇 장을 구웠고 무엇이 빠졌는지 말하고, 내려받을 자리를 준다.
   토스트 한 줄로 끝내지 않는 이유: 스티커가 빠졌거나 안 그려진 장이 있으면
   그것을 알아야 하고, 그건 한 줄에 안 들어간다. */
function showBaked(out) {
  const box = $("#bakeResult");
  if (!box) return toast(`${out.scenes.length}장을 구웠습니다`);
  const gone = (out.missing || []).length
    ? `<p class="bake-warn">아직 안 그려진 장 ${out.missing.join(", ")}번은 빠졌습니다.</p>` : "";
  const skip = (out.skipped || []).length
    ? `<p class="bake-warn">못 그린 것: ${out.skipped.map(esc).join(", ")}`
      + ` — 이모지 글꼴이 없는 서버에서는 스티커가 빠집니다.</p>` : "";
  box.innerHTML = `
    <div class="bake-head">
      <b>${out.scenes.length}장을 구웠습니다</b>
      <span>${out.width}×${out.height}px · 얹은 것 ${out.items}개</span>
    </div>
    ${gone}${skip}
    <div class="bake-acts">
      <a class="btn btn-primary btn-sm" href="${out.url}" download>내려받기</a>
      <a class="btn btn-quiet btn-sm" href="${out.url}" target="_blank" rel="noopener">새 탭에서 보기</a>
      <button type="button" class="btn btn-quiet btn-sm" id="bakeClose">닫기</button>
    </div>`;
  box.hidden = false;
  $("#bakeClose").addEventListener("click", () => { box.hidden = true; });
}

let toastT = null;
function toast(msg) {
  const el = $("#toast");
  el.textContent = msg; el.hidden = false;
  clearTimeout(toastT); toastT = setTimeout(() => { el.hidden = true; }, 3200);
}

/* ---- 작품 고르개 — 어떤 웹툰을 편집할지 -------------------------------
 *
 * 고르면 주소를 바꾸고 새로 연다. 페이지를 다시 여는 이유: 얹은 것·피드백이
 * 작품마다 다른 칸에 저장돼 있어서(storeKey), 화면만 갈아 끼우면 앞 작품의
 * state 가 남는다. 새로 열면 load() 가 그 작품 칸을 처음부터 읽는다. */
/* 한 작품 = 카드 한 장. 표지 그림은 그 작품에 **실제로 그려진** 장에서 가져온다
   (1번 장이 있다고 칠 수 없다 — 3·4번만 뽑아 둔 run 이 흔하다. 그래서 서버가
   cover_page 를 같이 준다). 그림이 아직 없으면 자리만 비워 두고 카드는 남긴다. */
function workCard(r, current) {
  const on = r.run_id === current;
  const thumb = r.cover_page
    ? `<img class="work-thumb" loading="lazy" alt=""`
      + ` src="/api/runs/${encodeURIComponent(r.run_id)}/page/${r.cover_page}`
      + `?w=160&ep=${r.cover_episode || 1}">`
    : `<span class="work-thumb is-empty" aria-hidden="true">🖼</span>`;
  // "2화" 만 쓰면 **2편이 있다**는 뜻인지 **2화를 보고 있다**는 뜻인지 갈린다.
  // 여러 편이면 범위로 적어서(1~3화) 그 애매함을 없앤다.
  const eps = r.episodes || [];
  const epLabel = eps.length > 1 ? `${eps[0]}~${eps[eps.length - 1]}화`
                                 : `${eps[0] || 1}화`;
  const sub = [
    epLabel,
    r.page_count ? `${r.page_count}장` : "",
    r.genre || "",
  ].filter(Boolean).join(" · ");
  // 그 작품에서 **실제로 그려진 첫 회차**를 연다. 1화를 못 그리고 2화만 남은
  // run 이 있어서, 늘 1화로 보내면 열자마자 "열지 못했습니다"가 뜬다.
  return `<button type="button" class="work-card" data-run="${esc(r.run_id)}"`
    + ` data-ep="${eps[0] || 1}"`
    + `${on ? ' aria-current="true"' : ""}>`
    + thumb
    + `<span><span class="work-name">${esc(r.character || "이름 없음")}`
    + `${r.title ? " · " + esc(r.title) : ""}</span>`
    + `<span class="work-sub">${esc(sub)}</span></span></button>`;
}

/* ---- 작품 목록 — 어떤 웹툰을 편집할지 ---------------------------------
 *
 * 고르면 주소를 바꾸고 새로 연다. 페이지를 다시 여는 이유: 얹은 것·피드백이
 * 작품마다 다른 칸에 저장돼 있어서(storeKey), 화면만 갈아 끼우면 앞 작품의
 * state 가 남는다. 새로 열면 load() 가 그 작품 칸을 처음부터 읽는다. */
async function paintWorks(current) {
  const host = $("#worksList");
  if (!host) return;
  let runs = null;                    // null = 못 물어봄, [] = 물어봤는데 없음
  try { runs = (await (await fetch("/api/runs")).json()).runs || []; } catch { /* 아래에서 */ }

  if (runs === null) {
    host.innerHTML = `<p class="ed-works-empty">작품 목록을 불러오지 못했습니다.`
      + ` 서버(serve.py)가 떠 있는지 확인해 주세요.</p>`;
    return;
  }
  if (!runs.length) {
    // 목록을 아예 지우지 않는다 — 자리가 사라지면 기능이 없는 것과 구별이 안 된다.
    host.innerHTML = `<p class="ed-works-empty">아직 만든 웹툰이 없습니다.`
      + `<br><a href="/">첫 작품 만들러 가기</a></p>`;
    return;
  }
  host.innerHTML = runs.map(r => workCard(r, current)).join("")
    + `<button type="button" class="work-card" data-run=""`
    + `${current ? "" : ' aria-current="true"'}>`
    + `<span class="work-thumb is-empty" aria-hidden="true">◇</span>`
    + `<span><span class="work-name">샘플 보기</span>`
    + `<span class="work-sub">목업 — 서버 없이도 열립니다</span></span></button>`;

  host.addEventListener("click", e => {
    const card = e.target.closest(".work-card");
    if (!card || card.getAttribute("aria-current") === "true") return;
    location.search = card.dataset.run
      ? `?run=${encodeURIComponent(card.dataset.run)}&ep=${card.dataset.ep || 1}` : "";
  });
}

/* 목록을 접었는지는 기기마다 기억한다 — 좁은 화면에서 매번 접는 것은 일이다. */
function setupWorksToggle() {
  const btn = $("#worksToggle"), body = $(".ed-body");
  if (!btn || !body) return;
  const apply = off => {
    body.classList.toggle("works-off", off);
    btn.setAttribute("aria-expanded", off ? "false" : "true");
  };
  let off = false;
  try { off = localStorage.getItem("lore_editor_works") === "off"; } catch { /* 비공개 창 */ }
  apply(off);
  btn.addEventListener("click", () => {
    off = !off;
    apply(off);
    try { localStorage.setItem("lore_editor_works", off ? "off" : "on"); } catch { /* 무시 */ }
  });
}

/* ------------------------------------------------------------------ 시작 */

document.addEventListener("DOMContentLoaded", async () => {
  // ?run=<run_id> 가 있으면 그 작품을, ?ep=<N> 이 있으면 그 회차를 연다
  // (없으면 1화). run 이 없으면 목업이다. 편집기는 "이미 그려진 것을 고치는
  // 자리" 라서, 랜딩에서 만든 것이든 하네스를 직접 돌린 것이든 똑같이 열려야 한다.
  const params = new URLSearchParams(location.search);
  RUN_ID = params.get("run") || "";
  EPISODE = Number(params.get("ep")) || 1;
  // load() 는 RUN_ID·EPISODE 가 정해진 **뒤에** 부른다 — 열쇠가 거기 매여 있다.
  load(); paintCredit(); paintLedger(); paintDock();
  loadSceneTags();
  // 작품 목록은 **여는 데 실패해도** 남아 있어야 한다. 한 작품이 안 열린다고
  // 목록까지 사라지면 다른 작품으로 건너갈 길이 없어서 주소를 직접 고쳐야 한다.
  paintWorks(RUN_ID);
  setupWorksToggle();

  const src = RUN_ID ? `/api/runs/${encodeURIComponent(RUN_ID)}/episode${epq()}`
                     : "/static/samples/mock.json";
  try {
    const res = await fetch(src);
    if (!res.ok) throw new Error(await res.text());
    data = await res.json();
  } catch (err) {
    // 무대만 갈아 끼운다 (예전에는 document.body 를 통째로 덮었다 — 그러면
    // 왼쪽 목록도 같이 지워져서 다른 작품을 고를 수가 없었다).
    const stage = $("#stageCol");
    const html =
      `<p style="padding:60px;text-align:center;color:#969aa8">` +
      (RUN_ID ? `<b>${esc(RUN_ID)}</b> 의 ${EPISODE}화를 열지 못했습니다.<br>`
              + `그 회차에 콘티와 그려진 장이 있어야 합니다.`
              : `목업 데이터를 읽지 못했습니다 (web/samples/mock.json).`) +
      `<br><br>왼쪽 목록에서 다른 작품을 골라 보세요.` +
      `<br><a href="/editor" style="color:#7aa2ff">샘플로 돌아가기</a></p>`;
    if (stage) stage.innerHTML = html;
    else document.body.innerHTML = html;
    return;
  }
  // 실제 작품이면 목업 배지를 지운다 — 여기서부터는 진짜로 그린다.
  if (RUN_ID) {
    document.querySelector(".mock-badge")?.remove();
    document.querySelector("#creditBox")?.remove();
    document.querySelector("#ledgerBtn")?.remove();
  }
  // 서버에 저장된 것이 **기준**이다. 다른 기기에서 얹은 것도 여기서 따라온다.
  // 서버가 조용하면(못 읽으면) localStorage 에 있던 것을 그대로 쓴다 — 하던
  // 작업이 통신 한 번 실패했다고 사라지면 안 된다.
  if (RUN_ID) {
    try {
      const got = await (await fetch(
        `/api/runs/${encodeURIComponent(RUN_ID)}/overlay${epq()}`)).json();
      for (const [no, sp] of Object.entries(got.scenes || {})) {
        if (Array.isArray(sp.items)) sc(Number(no)).items = sp.items.map(
          (it, i) => ({ ...it, id: it.id || `s${no}_${i}_${++uid}` }));
      }
    } catch { /* localStorage 것을 쓴다 */ }
  }

  render();

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

  $("#ledgerBtn")?.addEventListener("click", () =>
    { $("#dockLedger").hidden = !$("#dockLedger").hidden; });
  $("#ledgerClose").addEventListener("click", () => { $("#dockLedger").hidden = true; });

  // 저장은 항목을 건드릴 때마다 자동으로 된다(save() → pushSoon()). 이 단추는
  // **지금 당장** 올리고 그 결과를 말해 주는 자리다 — 자동 저장은 조용해서,
  // 창을 닫기 전에 확인하고 싶을 때 누를 곳이 있어야 한다.
  $("#saveBtn").addEventListener("click", async () => {
    save();
    if (!RUN_ID) return toast("샘플입니다 — 얹은 것은 이 브라우저에만 저장됩니다.");
    clearTimeout(pushT);
    try {
      const res = await fetch(`/api/runs/${encodeURIComponent(RUN_ID)}/overlay${epq()}`,
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(overlayPayload()) });
      const out = await res.json();
      if (!res.ok) throw new Error(out.error || "저장하지 못했습니다");
      toast(`작품 폴더에 저장했습니다 — 얹은 것 ${out.items}개`);
    } catch (err) { toast(err.message); }
  });

  // 이미지로 뽑기 — 얹은 것을 그림에 구워서 내려받는다.
  // 저장과 굽기를 한 번에 보낸다. 따로 왕복하면 그 사이에 실패했을 때 화면에
  // 보이는 것과 구운 것이 갈린다.
  $("#bakeBtn")?.addEventListener("click", async () => {
    if (!RUN_ID) return toast("샘플에는 구울 그림이 없습니다. 실제 작품을 열어 주세요.");
    const btn = $("#bakeBtn");
    btn.disabled = true;
    const was = btn.textContent;
    btn.textContent = "굽는 중…";
    clearTimeout(pushT);
    try {
      const res = await fetch(`/api/runs/${encodeURIComponent(RUN_ID)}/bake${epq()}`,
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(overlayPayload()) });
      const out = await res.json();
      if (!res.ok) throw new Error(out.error || "굽지 못했습니다");
      showBaked(out);
    } catch (err) { toast(err.message); }
    btn.disabled = false;
    btn.textContent = was;
  });

  // 다시 그리기 확인 창. 바깥을 눌러도 닫힌다 — 실수로 연 것을 닫는 데
  // 단추를 찾아야 하면 그 자체가 성가시다.
  $("#regenAskCancel").addEventListener("click", closeAsk);
  $("#regenAskGo").addEventListener("click", confirmAsk);
  $("#regenAsk").addEventListener("click", e => {
    if (e.target.id === "regenAsk") closeAsk();
  });

  document.addEventListener("keydown", e => {
    // 확인 창이 열려 있으면 그 창부터 받는다 — 뒤에 있는 선택 해제나 삭제가
    // 먼저 먹으면 창을 띄워 둔 채로 그림이 지워진다.
    if (!$("#regenAsk").hidden) {
      if (e.key === "Escape") { e.preventDefault(); closeAsk(); }
      return;
    }
    if ((e.key === "Delete" || e.key === "Backspace") && sel &&
        !/^(INPUT|TEXTAREA)$/.test(document.activeElement.tagName)) {
      e.preventDefault(); $("#propDel").click();
    }
    if (e.key === "Escape") $("#propClose").click();
  });
});
