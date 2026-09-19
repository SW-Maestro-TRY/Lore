// 루와 놀기 — 만드는 중 화면의 「루와 놀기」 자리가 쓰는 물리.
//
// 드래그·흔들기·연속 클릭 판정은 DOM 이벤트와 타이머를 직접 다루는 물리에
// 가까운 코드라, React 상태로 다시 짜면 미묘하게 달라질 위험이 크다 — 그래서
// querySelector 로 DOM 을 직접 만지는 방식 그대로 두고, React 쪽에서는
// useEffect 안에서 한 번 불러 주기만 한다. 프레임과 대사는
// /static/lou/react/manifest.json, 팁은 /static/lou/tips.json 에 있고
// (원본은 webtoon/ai/assets/lou, sync-landing.sh 가 떠 온다) 이 파일은 그
// 목록을 읽을 뿐이라 반응을 늘리려면 manifest 쪽만 고치면 된다.
//
// 정리(clearInterval·removeEventListener)를 위해 cleanup 함수를 돌려준다 —
// 탭을 옮기면 언마운트되고 다시 들어오면 새로 mount 되기 때문이다.

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
  /* 흔들기는 드래그 중 방향을 빠르게 3번 뒤집는 것으로만 낸다(아래 onPointerMove) —
   * 실제 기기 흔들기(DeviceMotionEvent)와 그 권한 요청 버튼은 뺐다: 데스크톱에서는
   * 뜻 없는 버튼이고, 모바일에서도 권한 팝업까지 띄우면서 얻는 재미보다 그 팝업이
   * 주는 거부감이 더 크다는 판단. */
  const DRAG_SHAKE_FLIPS = 3;
  const DRAG_SHAKE_WINDOW = 900;
  const SHAKE_GAP = 2000;

  let shakeAt = 0;

  function canShake(): boolean {
    return !!art?.shake && Date.now() - shakeAt >= SHAKE_GAP;
  }

  function fireShake(hold = 1500, after?: () => void) {
    shakeAt = Date.now();
    touchedAt = Date.now();
    play(pickKind("shake"), 380, hold, after || rest);
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
  }

  setupLouInner();

  return () => {
    disposed = true;
    if (idleInterval) clearInterval(idleInterval);
    if (timer) clearTimeout(timer);
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
