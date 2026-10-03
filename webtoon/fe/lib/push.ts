/* 웹푸시와 홈 화면 바로가기 — 브라우저 쪽 일(#599).
 *
 * 서버(webtoon/be 의 JobPush)는 작업이 사람을 기다리기 시작하거나(상대 인물 · 이야기 ·
 * 장면 · 시트) 끝나면 이 기기로 알림을 보낸다. 여기서는 그걸 받을 준비를 한다:
 * 서비스워커(public/sw.js) 등록 · 권한 묻기 · 구독을 서버에 적기 · 설치 안내.
 *
 * ★ 아이폰은 홈 화면에 추가해서 연 앱에서만 푸시를 받는다(iOS 16.4+). 사파리 탭에서는
 *   PushManager 자체가 없다. 그래서 그때는 「홈 화면에 추가」 안내를 대신 띄운다.
 *
 * ★ 권한은 사람이 단추를 누른 그 순간에만 물을 수 있다(특히 아이폰). 화면이 열리자마자
 *   묻지 않는다 — 그러면 거절당하고, 한 번 거절되면 브라우저 설정에서만 되돌릴 수 있다. */
import { pushKey, pushSubscribe, pushUnsubscribe } from "./api";

export type PushState =
  | "loading"
  | "off"            // 서버에 키가 없거나 브라우저가 못 한다 — 아무것도 안 띄운다
  | "ios-install"    // 아이폰 사파리 탭 — 홈 화면에 추가해야 받을 수 있다
  | "denied"         // 이 사이트 알림을 막아 뒀다
  | "ready"          // 단추를 누르면 받을 수 있다
  | "on";            // 이 기기로 받고 있다

const SW_URL = "/sw.js";

function hasWindow(): boolean {
  return typeof window !== "undefined";
}

/** 아이폰·아이패드. 아이패드는 데스크톱 사파리인 척해서 터치 여부로 가린다. */
export function isIos(): boolean {
  if (!hasWindow()) return false;
  const ua = navigator.userAgent;
  return /iPhone|iPad|iPod/.test(ua) || (ua.includes("Macintosh") && navigator.maxTouchPoints > 1);
}

/** 홈 화면에서 앱으로 열렸나. */
export function isStandalone(): boolean {
  if (!hasWindow()) return false;
  return window.matchMedia?.("(display-mode: standalone)").matches
    || (navigator as Navigator & { standalone?: boolean }).standalone === true;
}

function pushSupported(): boolean {
  return hasWindow() && "serviceWorker" in navigator && "PushManager" in window && "Notification" in window;
}

let keyCache: Promise<string> | null = null;
function serverKey(): Promise<string> {
  keyCache ??= pushKey().then((r) => r.key || "").catch(() => "");
  return keyCache;
}

async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!pushSupported()) return null;
  try {
    return await navigator.serviceWorker.register(SW_URL, { scope: "/" });
  } catch {
    return null;
  }
}

function keyBytes(b64url: string): Uint8Array<ArrayBuffer> {
  const pad = "=".repeat((4 - (b64url.length % 4)) % 4);
  const raw = atob((b64url + pad).replace(/-/g, "+").replace(/_/g, "/"));
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/** 지금 이 기기의 상태. 화면이 단추를 무엇으로 띄울지 정한다. */
export async function pushState(): Promise<PushState> {
  if (!hasWindow()) return "loading";
  if (!pushSupported()) return isIos() && !isStandalone() ? "ios-install" : "off";
  if (!(await serverKey())) return "off";
  if (Notification.permission === "denied") return "denied";
  const reg = await registration();
  if (!reg) return "off";
  const sub = await reg.pushManager.getSubscription();
  return sub && Notification.permission === "granted" ? "on" : "ready";
}

/**
 * 알림을 켠다 — <b>반드시 단추를 누른 자리에서 부른다.</b>
 * @returns 켜진 뒤의 상태. 사람이 거절하면 "denied".
 */
export async function enablePush(lang: string): Promise<PushState> {
  const key = await serverKey();
  const reg = await registration();
  if (!key || !reg) return "off";
  const perm = await Notification.requestPermission();
  if (perm !== "granted") return perm === "denied" ? "denied" : "ready";
  const sub = (await reg.pushManager.getSubscription())
    ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) }));
  await pushSubscribe(sub.toJSON(), lang);
  return "on";
}

/** 이 기기 알림을 끈다. 서버 기록과 브라우저 구독을 둘 다 지운다. */
export async function disablePush(): Promise<PushState> {
  const reg = await registration();
  const sub = await reg?.pushManager.getSubscription();
  if (sub) {
    await pushUnsubscribe(sub.endpoint).catch(() => {});
    await sub.unsubscribe().catch(() => false);
  }
  return "ready";
}

/**
 * 이미 받고 있는 기기면 서버 기록을 새로 맞춘다 — 웹툰 화면이 열릴 때 한 번.
 *
 * 로그인·로그아웃·언어를 바꾼 뒤에도 알림이 맞는 사람에게 맞는 말로 가게 하고,
 * 오래 안 쓰인 구독을 서버가 지우는 기준(마지막 확인 시각)도 이걸로 새로 찍힌다.
 */
export async function syncPush(lang: string): Promise<void> {
  try {
    if (!pushSupported() || Notification.permission !== "granted" || !(await serverKey())) return;
    const reg = await registration();
    const sub = await reg?.pushManager.getSubscription();
    if (sub) await pushSubscribe(sub.toJSON(), lang);
  } catch {
    /* 못 맞춰도 화면은 그대로 쓴다 */
  }
}

/* ---- 안드로이드 · 데스크톱 크롬의 「앱 설치」 ---------------------------------
 *
 * 크롬은 설치할 수 있는 사이트면 beforeinstallprompt 를 <b>한 번</b> 쏜다. 그때 잡아 두지
 * 않으면 놓친다 — 그래서 이 파일을 불러오는 순간(모듈이 실행될 때) 바로 귀를 연다. */

interface InstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

let installEvent: InstallPromptEvent | null = null;
const installListeners = new Set<() => void>();

if (hasWindow()) {
  window.addEventListener("beforeinstallprompt", (e) => {
    e.preventDefault();                 // 크롬이 제멋대로 띄우는 막대 대신 우리 단추로
    installEvent = e as InstallPromptEvent;
    installListeners.forEach((f) => f());
  });
  window.addEventListener("appinstalled", () => {
    installEvent = null;
    installListeners.forEach((f) => f());
  });
}

export function canInstall(): boolean {
  return !!installEvent && !isStandalone();
}

export function onInstallChange(f: () => void): () => void {
  installListeners.add(f);
  return () => { installListeners.delete(f); };
}

export async function promptInstall(): Promise<boolean> {
  const e = installEvent;
  if (!e) return false;
  await e.prompt();
  const { outcome } = await e.userChoice;
  installEvent = null;
  installListeners.forEach((f) => f());
  return outcome === "accepted";
}
