// 루와 놀기 — haeun/landing/web/lou-play.js 를 거의 그대로 옮겼다.
//
// 드래그·흔들기·연속 클릭 판정은 DOM 이벤트와 타이머를 직접 다루는 물리에
// 가까운 코드라, React 상태로 다시 짜면 오히려 원본과 미묘하게 달라질
// 위험이 크다 — 그래서 리팩터하지 않고 querySelector 로 DOM 을 직접 만지는
// 방식 그대로 옮기고, React 쪽에서는 useEffect 안에서 한 번 불러 주기만
// 한다. 그림은 apps/web/public/static/lou (haeun/landing/web/lou 를
// sync-landing.sh 가 그대로 옮겨 둔 것)에 있다 — 이 파일은 안 건드려도 된다.
//
// 원본과 다른 점: window 전역에 얹는 대신 export 하고, 정리(clearInterval·
// removeEventListener)를 위해 cleanup 함수를 돌려준다 — React 는 화면을
// 나가면 언마운트되고, 다음에 들어오면 새로 mount 되므로 원본(정적 페이지,
// 한 번만 뜨는 것)에는 없던 정리가 필요하다.

const ART = "/static/lou/react";

interface LouFrameSet {
  frames: string[];
  say?: string[];
}
type LouManifest = Record<string, LouFrameSet>;

const pick = <T,>(list: T[]): T => list[Math.floor(Math.random() * list.length)];

const ALT_CHANCE = 0.15;

export function setupLou(): () => void {
  let disposed = false;
  let idleInterval: ReturnType<typeof setInterval> | null = null;

  let art: LouManifest | null = null;
  let busy = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  let idleFrame = 0;
  let idleKind = "idle";
  let asleep = false;
  let touchedAt = 0;

  function pickKind(base: string): string {
    const alt = `${base}_alt`;
    return art && art[alt] && Math.random() < ALT_CHANCE ? alt : base;
  }

  function say(text: string) {
    const el = document.querySelector<HTMLElement>("#playSay");
    if (el) el.textContent = text;
  }

  function show(kind: string, i: number) {
    const set = art?.[kind];
    const img = document.querySelector<HTMLImageElement>("#mascotImg");
    if (!set || !img) return;
    const n = Math.max(0, Math.min(i, set.frames.length - 1));
    img.src = `${ART}/${kind}/${set.frames[n]}`;
    if (set.say && set.say.length) {
      say(set.say.length === set.frames.length ? set.say[n] : pick(set.say));
    }
  }

  function play(kind: string, step = 520, hold = 1200, after: () => void = rest) {
    const set = art?.[kind];
    if (!set) return;
    busy = true;
    const box = document.querySelector<HTMLElement>("#mascot");
    if (box) box.dataset.react = kind;
    if (timer) clearTimeout(timer);
    let i = 0;
    const next = () => {
      show(kind, i);
      if (i < set.frames.length - 1) {
        i += 1;
        timer = setTimeout(next, step);
        return;
      }
      timer = setTimeout(after, hold);
    };
    next();
  }

  function one(kind: string, hold = 1500) {
    const set = art?.[kind];
    if (!set) return;
    busy = true;
    const box = document.querySelector<HTMLElement>("#mascot");
    if (box) box.dataset.react = kind;
    if (timer) clearTimeout(timer);
    show(kind, Math.floor(Math.random() * set.frames.length));
    timer = setTimeout(rest, hold);
  }

  function rest() {
    busy = false;
    const box = document.querySelector<HTMLElement>("#mascot");
    if (box) delete box.dataset.react;
    say("루를 눌러 보세요");
    idleFrame = -1;
    idleKind = "idle";
    idleTick();
  }

  const IDLE_TO_SLEEP = 20000;

  function idleTick() {
    if (busy || !art) return;
    const box = document.querySelector<HTMLElement>("#mascot");
    if (!box || !box.offsetParent) return;
    asleep = Date.now() - touchedAt > IDLE_TO_SLEEP && !!art.sleep;
    const kind = asleep ? "sleep" : "idle";
    const set = art[kind];
    if (!set) return;
    if (kind !== idleKind) {
      idleKind = kind;
      idleFrame = -1;
    }
    idleFrame = asleep
      ? Math.min(idleFrame + 1, set.frames.length - 1)
      : (idleFrame + 1) % set.frames.length;
    const img = document.querySelector<HTMLImageElement>("#mascotImg");
    if (img) img.src = `${ART}/${kind}/${set.frames[idleFrame]}`;
  }

  let preloaded = false;
  function preload(kinds: string[]) {
    if (!art) return;
    for (const kind of kinds) {
      const set = art[kind];
      if (!set) continue;
      for (const f of set.frames) {
        const im = new Image();
        im.src = `${ART}/${kind}/${f}`;
      }
    }
  }
  function preloadRest() {
    if (preloaded || !art) return;
    preloaded = true;
    preload(Object.keys(art).filter((k) => k !== "idle" && k !== "sleep"));
  }

  // ---- 흔들기 ------------------------------------------------------------
  const DRAG_SHAKE_FLIPS = 3;
  const DRAG_SHAKE_WINDOW = 900;
  const HINT_BASE = "누르기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기 · 잡고 흔들기";
  const SHAKE_JERK = 16;
  const SHAKE_GAP = 2000;

  let shakeOn = false;
  let shakeAsked = false;
  let shakeAt = 0;
  let lastAcc: number[] | null = null;

  function canShake(): boolean {
    return !!art?.shake && Date.now() - shakeAt >= SHAKE_GAP;
  }

  function fireShake(hold = 1500, after?: () => void) {
    shakeAt = Date.now();
    touchedAt = Date.now();
    play(pickKind("shake"), 380, hold, after || rest);
  }

  function onShake(e: DeviceMotionEvent) {
    const g = e.acceleration;
    const a = g && g.x !== null ? g : e.accelerationIncludingGravity;
    if (!a) return;
    const now = [a.x || 0, a.y || 0, a.z || 0];
    if (!lastAcc) {
      lastAcc = now;
      return;
    }
    const jerk =
      Math.abs(now[0] - lastAcc[0]) + Math.abs(now[1] - lastAcc[1]) + Math.abs(now[2] - lastAcc[2]);
    lastAcc = now;
    if (jerk < SHAKE_JERK || !canShake()) return;
    fireShake();
  }

  function startShake() {
    if (shakeOn) return;
    shakeOn = true;
    window.addEventListener("devicemotion", onShake);
    const btn = document.querySelector<HTMLElement>("#shakeAllow");
    if (btn) btn.hidden = true;
    const hint = document.querySelector<HTMLElement>("#playHint");
    if (hint) hint.textContent = `${HINT_BASE} · 폰 흔들기`;
  }

  async function askShake() {
    if (shakeOn || shakeAsked) return;
    shakeAsked = true;
    const DME = (window as any).DeviceMotionEvent;
    if (!DME) return;
    if (typeof DME.requestPermission !== "function") {
      startShake();
      return;
    }
    try {
      if ((await DME.requestPermission()) === "granted") return startShake();
    } catch {
      /* 아래에서 버튼을 보여 준다 */
    }
    const b = document.querySelector<HTMLElement>("#shakeAllow");
    if (b) b.hidden = false;
  }

  function setupShake() {
    const DME = (window as any).DeviceMotionEvent;
    const btn = document.querySelector<HTMLButtonElement>("#shakeAllow");
    if (!DME) return;
    if (typeof DME.requestPermission !== "function") {
      startShake();
      return;
    }
    if (!btn) return;
    btn.hidden = false;
    const onClick = async () => {
      shakeAsked = true;
      try {
        if ((await DME.requestPermission()) === "granted") startShake();
      } catch {
        /* 거절하면 그냥 흔들기만 없다 */
      }
    };
    btn.addEventListener("click", onClick);
    cleanupFns.push(() => btn.removeEventListener("click", onClick));
  }

  const cleanupFns: Array<() => void> = [];

  async function setupLouInner() {
    const box = document.querySelector<HTMLElement>("#mascot");
    const stage = document.querySelector<HTMLElement>("#mascotStage");
    if (!box || !stage) return;
    try {
      const res = await fetch(`${ART}/manifest.json`);
      if (!res.ok) throw new Error("no manifest");
      art = await res.json();
    } catch {
      return; // 그림이 없으면 만지기 자체를 끈다 (조용히)
    }
    if (disposed) return;
    preload(["idle", "sleep"]);
    touchedAt = Date.now();
    idleInterval = setInterval(idleTick, 1500);
    idleTick();

    let down = false;
    let sx = 0;
    let sy = 0;
    let ox = 0;
    let oy = 0;
    let at = 0;
    let far = 0;
    let turns = 0;
    let lastDir = 0;
    let dragging = false;
    let clicks = 0;
    let clickWindow: ReturnType<typeof setTimeout> | null = null;
    let holdTimer: ReturnType<typeof setTimeout> | null = null;
    let multiclickKind = "multiclick";
    let dragKind = "drag";
    let flips: number[] = [];
    let dragShaking = false;

    const home = () => {
      box.dataset.home = "1";
      box.style.left = "";
      box.style.top = "";
      setTimeout(() => {
        delete box.dataset.home;
      }, 500);
    };

    const tap = () => {
      if (asleep) {
        asleep = false;
        clicks = 0;
        play("wake", 700, 1300);
        return;
      }
      clicks += 1;
      if (clickWindow) clearTimeout(clickWindow);
      clickWindow = setTimeout(() => {
        clicks = 0;
        multiclickKind = "multiclick";
      }, 1500);
      if (clicks === 1) {
        one("click");
        return;
      }
      if (clicks === 2) multiclickKind = pickKind("multiclick");
      if (timer) clearTimeout(timer);
      busy = true;
      box.dataset.react = multiclickKind;
      show(multiclickKind, Math.min(clicks - 1, art![multiclickKind].frames.length - 1));
      timer = setTimeout(rest, 1800);
    };

    const onPointerDown = (e: PointerEvent) => {
      box.setPointerCapture(e.pointerId);
      down = true;
      sx = e.clientX;
      sy = e.clientY;
      at = Date.now();
      far = 0;
      turns = 0;
      lastDir = 0;
      dragging = false;
      flips = [];
      dragShaking = false;
      touchedAt = Date.now();
      const r = box.getBoundingClientRect();
      ox = e.clientX - (r.left + r.width / 2);
      oy = e.clientY - (r.top + r.height / 2);
      if (holdTimer) clearTimeout(holdTimer);
      holdTimer = setTimeout(() => {
        if (!down || far > 14 || dragging || asleep) return;
        play("longpress", 900, 900);
      }, 550);
      preloadRest();
      askShake();
    };

    const onPointerMove = (e: PointerEvent) => {
      if (!down) return;
      const dx = e.clientX - sx;
      const dy = e.clientY - sy;
      far = Math.max(far, Math.hypot(dx, dy));
      const dir = Math.sign(e.movementX || 0);
      const prevDir = lastDir;
      const flipped = !!(dir && prevDir && dir !== prevDir);
      if (flipped) turns += 1;
      if (dir) lastDir = dir;
      touchedAt = Date.now();

      if (far > 60) {
        if (!dragging) {
          dragging = true;
          dragKind = pickKind("drag");
          if (holdTimer) clearTimeout(holdTimer);
          if (timer) clearTimeout(timer);
          busy = true;
          box.dataset.grab = "1";
          box.dataset.react = dragKind;
          show(dragKind, 0);
        }
        const sr = stage.getBoundingClientRect();
        const w = box.offsetWidth / 2;
        const h = box.offsetHeight / 2;
        box.style.left = `${Math.max(w, Math.min(e.clientX - sr.left - ox, sr.width - w))}px`;
        box.style.top = `${Math.max(h, Math.min(e.clientY - sr.top - oy, sr.height - h))}px`;
        if (flipped) {
          const t = e.timeStamp || Date.now();
          flips.push(t);
          while (flips.length && t - flips[0] > DRAG_SHAKE_WINDOW) flips.shift();
          if (flips.length >= DRAG_SHAKE_FLIPS && !dragShaking && canShake()) {
            flips = [];
            dragShaking = true;
            fireShake(500, () => {
              dragShaking = false;
              if (!down || !dragging) return rest();
              busy = true;
              box.dataset.react = dragKind;
              show(dragKind, Math.min(1, art![dragKind].frames.length - 1));
            });
          }
        }
      } else if (turns >= 3 && !dragging) {
        turns = 0;
        if (holdTimer) clearTimeout(holdTimer);
        play(pickKind("pet"), 620, 1400);
      }
    };

    const onPointerUp = () => {
      if (!down) return;
      down = false;
      if (holdTimer) clearTimeout(holdTimer);
      delete box.dataset.grab;
      const held = Date.now() - at;
      touchedAt = Date.now();

      if (dragging) {
        home();
        if (dragShaking) return;
        show(dragKind, art![dragKind].frames.length - 1);
        if (timer) clearTimeout(timer);
        timer = setTimeout(rest, 1400);
        return;
      }
      if (busy && held > 550) return;
      if (far > 14) return;
      tap();
    };

    const onPointerCancel = () => {
      down = false;
      delete box.dataset.grab;
      home();
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Enter" && e.key !== " ") return;
      e.preventDefault();
      preloadRest();
      touchedAt = Date.now();
      tap();
    };

    box.addEventListener("pointerdown", onPointerDown);
    stage.addEventListener("pointermove", onPointerMove);
    stage.addEventListener("pointerup", onPointerUp);
    stage.addEventListener("pointercancel", onPointerCancel);
    box.addEventListener("keydown", onKeyDown);
    cleanupFns.push(() => {
      box.removeEventListener("pointerdown", onPointerDown);
      stage.removeEventListener("pointermove", onPointerMove);
      stage.removeEventListener("pointerup", onPointerUp);
      stage.removeEventListener("pointercancel", onPointerCancel);
      box.removeEventListener("keydown", onKeyDown);
      if (clickWindow) clearTimeout(clickWindow);
      if (holdTimer) clearTimeout(holdTimer);
    });

    setupShake();
  }

  setupLouInner();

  return () => {
    disposed = true;
    if (idleInterval) clearInterval(idleInterval);
    if (timer) clearTimeout(timer);
    window.removeEventListener("devicemotion", onShake);
    for (const fn of cleanupFns) fn();
  };
}

// ---- 팁 · TMI ---------------------------------------------------------
function shuffled<T>(items: T[], avoidFirst?: T): T[] {
  const a = items.slice();
  for (let i = a.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  if (avoidFirst !== undefined && a.length > 1 && a[0] === avoidFirst) {
    [a[0], a[1]] = [a[1], a[0]];
  }
  return a;
}

interface TipItem {
  kind?: string;
  text: string;
}

export function setupTips(): () => void {
  let disposed = false;
  let timer: ReturnType<typeof setInterval> | null = null;
  let fadeTimer: ReturnType<typeof setTimeout> | null = null;
  let clickHandler: (() => void) | null = null;

  async function run() {
    const box = document.querySelector<HTMLElement>("#tips");
    const kindEl = document.querySelector<HTMLElement>("#tipKind");
    const textEl = document.querySelector<HTMLElement>("#tipText");
    if (!box || !kindEl || !textEl) return;

    let data: { items?: TipItem[]; seconds?: number };
    try {
      const res = await fetch("/static/lou/tips.json");
      if (!res.ok) throw new Error("no tips");
      data = await res.json();
    } catch {
      return; // 팁이 없으면 그 줄 자체를 안 보여 준다
    }
    if (disposed) return;
    const items = (data.items || []).filter((x) => x && x.text);
    if (!items.length) return;

    let order = shuffled(items);
    let i = 0;

    const paint = (it: TipItem) => {
      box.dataset.kind = it.kind || "팁";
      kindEl.textContent = it.kind || "팁";
      textEl.textContent = it.text;
    };

    const step = () => {
      i += 1;
      if (i >= order.length) {
        order = shuffled(items, order[order.length - 1]);
        i = 0;
      }
      box.dataset.fade = "1";
      fadeTimer = setTimeout(() => {
        paint(order[i]);
        delete box.dataset.fade;
      }, 300);
    };

    const restart = () => {
      if (timer) clearInterval(timer);
      timer = setInterval(step, Math.max(3, data.seconds || 9) * 1000);
    };

    paint(order[0]);
    box.hidden = false;
    box.style.cursor = "pointer";
    box.title = "눌러서 다음 팁 보기";
    clickHandler = () => {
      step();
      restart();
    };
    box.addEventListener("click", clickHandler);
    restart();
  }

  run();

  return () => {
    disposed = true;
    if (timer) clearInterval(timer);
    if (fadeTimer) clearTimeout(fadeTimer);
    if (clickHandler) {
      document.querySelector<HTMLElement>("#tips")?.removeEventListener("click", clickHandler);
    }
  };
}
