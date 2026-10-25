"use client";

// 로그인 · 회원가입 모달. 탭 하나로 두 화면을 오간다.
//
// ★ 별도 페이지가 아니라 모달인 이유(2026-09-03 결정)
//   로그인은 대개 뭔가 하려다 막혀서 하는 일이다. 랜딩을 보다가, 펫을 만들려다가.
//   페이지로 넘겨 버리면 돌아왔을 때 보던 자리도 스크롤도 사라져 흐름이 끊긴다.
//   모달은 뒤 화면을 그대로 둔 채 위에 얹혔다 사라지므로 "하던 일" 이 유지된다.
//
// ★ document.body 로 포털을 쏘는 이유
//   이 모달을 부르는 건 헤더인데, 헤더에 backdrop-filter 가 걸려 있다.
//   backdrop-filter 가 있는 요소는 position:fixed 자손의 기준 상자가 되어 버려서,
//   포털 없이 그리면 전체 화면 오버레이가 헤더 높이 안에 갇힌다.

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { createPortal, flushSync } from "react-dom";
import { ApiError } from "../api/client";
import { track } from "../analytics";
import { LEGAL_LINKS } from "../links";
import { SessionCheckError, signIn, signUp } from "./useAuth";
import { useVisualViewport } from "../viewport/useVisualViewport";
import styles from "./AuthModal.module.css";


/** Opt-in copy only. Existing callers keep Korean and the same authentication flow. */
export type AuthLocale = "ko" | "en" | "ja";
const AUTH_COPY: Record<"en" | "ja", Record<string, string>> = {
  "en": {
    "로그인": "Log in",
    "회원가입": "Sign up",
    "이전": "Back",
    "닫기": "Close",
    "로그인 또는 회원가입": "Log in or sign up",
    "닫기 확인": "Confirm closing",
    "닫을까요? 입력한 내용이 사라져요": "Close this form? Your entries will be lost.",
    "계속": "Keep editing",
    "이메일": "Email",
    "비밀번호": "Password",
    "비밀번호 확인": "Confirm password",
    "비밀번호 숨기기": "Hide password",
    "비밀번호 보기": "Show password",
    "8자 이상": "At least 8 characters",
    "약관 동의": "Agreements",
    "전체 동의": "Agree to all",
    "[필수]": "[Required]",
    "[선택]": "[Optional]",
    "만 14세 이상입니다": "I am 14 or older",
    "이용약관에 동의합니다": "Terms of Service",
    "개인정보 처리방침에 동의합니다": "Privacy Policy",
    "마케팅 정보 수신에 동의합니다": "Receive marketing messages",
    "보기": "View (Korean)",
    "처리 중…": "Processing…",
    "가입됐어요. 방금 만든 비밀번호로 로그인해 주세요": "Your account is ready. Log in with the password you just created.",
    "다음": "Next",
    "가입하고 시작하기": "Sign up and start",
    "이메일을 입력해 주세요": "Enter your email address.",
    "이메일을 적어 주세요": "Enter your email to continue.",
    "이미 계정이 있어요": "Already have an account?",
    "계정이 없어요": "New here?",
    "이메일 주소를 다시 확인해 주세요": "Check your email address.",
    "비밀번호는 8자 이상이어야 합니다": "Use at least 8 characters for your password.",
    "비밀번호는 72자까지 쓸 수 있습니다": "Use no more than 72 characters for your password.",
    "비밀번호가 서로 다릅니다": "The passwords do not match.",
    "이메일과 비밀번호를 입력해 주세요": "Enter your email and password.",
    "만 14세 이상만 가입할 수 있습니다": "You must be 14 or older to sign up.",
    "필수 약관에 동의해 주세요": "Agree to the required terms to continue.",
    "가입은 됐어요. 로그인 상태를 확인하지 못했어요 — 로그인을 눌러 다시 시도해 주세요": "Your account was created, but we could not confirm your login. Select Log in to try again.",
    "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요": "We could not complete the request. Please try again shortly.",
    "로그인 상태를 확인하지 못했어요. 다시 시도해 주세요": "We could not confirm your login. Please try again.",
    "이미 가입된 이메일입니다": "This email is already registered. Try logging in.",
    "이메일 또는 비밀번호가 올바르지 않습니다": "Your email or password is incorrect."
  },
  "ja": {
    "로그인": "ログイン",
    "회원가입": "新規登録",
    "이전": "戻る",
    "닫기": "閉じる",
    "로그인 또는 회원가입": "ログインまたは新規登録",
    "닫기 확인": "閉じる確認",
    "닫을까요? 입력한 내용이 사라져요": "閉じますか？入力内容は失われます。",
    "계속": "入力を続ける",
    "이메일": "メールアドレス",
    "비밀번호": "パスワード",
    "비밀번호 확인": "パスワードの確認",
    "비밀번호 숨기기": "パスワードを隠す",
    "비밀번호 보기": "パスワードを表示",
    "8자 이상": "8文字以上",
    "약관 동의": "規約への同意",
    "전체 동의": "すべてに同意する",
    "[필수]": "[必須]",
    "[선택]": "[任意]",
    "만 14세 이상입니다": "14歳以上です",
    "이용약관에 동의합니다": "利用規約に同意します",
    "개인정보 처리방침에 동의합니다": "プライバシーポリシーに同意します",
    "마케팅 정보 수신에 동의합니다": "マーケティング情報の受信に同意します",
    "보기": "表示（韓国語）",
    "처리 중…": "処理中…",
    "가입됐어요. 방금 만든 비밀번호로 로그인해 주세요": "登録できました。設定したパスワードでログインしてください。",
    "다음": "次へ",
    "가입하고 시작하기": "登録して始める",
    "이메일을 입력해 주세요": "メールアドレスを入力してください。",
    "이메일을 적어 주세요": "続けるにはメールアドレスを入力してください。",
    "이미 계정이 있어요": "アカウントをお持ちの方",
    "계정이 없어요": "アカウントがない方",
    "이메일 주소를 다시 확인해 주세요": "メールアドレスを確認してください。",
    "비밀번호는 8자 이상이어야 합니다": "パスワードは8文字以上にしてください。",
    "비밀번호는 72자까지 쓸 수 있습니다": "パスワードは72文字以内にしてください。",
    "비밀번호가 서로 다릅니다": "パスワードが一致しません。",
    "이메일과 비밀번호를 입력해 주세요": "メールアドレスとパスワードを入力してください。",
    "만 14세 이상만 가입할 수 있습니다": "登録には14歳以上である必要があります。",
    "필수 약관에 동의해 주세요": "必須の規約に同意してください。",
    "가입은 됐어요. 로그인 상태를 확인하지 못했어요 — 로그인을 눌러 다시 시도해 주세요": "登録は完了しましたが、ログイン状態を確認できませんでした。ログインを押して再試行してください。",
    "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요": "リクエストを処理できませんでした。少し待ってから再試行してください。",
    "로그인 상태를 확인하지 못했어요. 다시 시도해 주세요": "ログイン状態を確認できませんでした。もう一度お試しください。",
    "이미 가입된 이메일입니다": "このメールアドレスは登録済みです。ログインしてください。",
    "이메일 또는 비밀번호가 올바르지 않습니다": "メールアドレスまたはパスワードが正しくありません。"
  }
};
function authText(locale: AuthLocale, text: string): string {
  return locale === "ko" ? text : AUTH_COPY[locale][text] ?? text;
}

export type AuthTab = "login" | "signup";

export interface AuthModalProps {
  open: boolean;
  /** Zzal opts into its document language; other services retain the Korean default. */
  locale?: AuthLocale | "document";
  /** 닫기 요청. 성공해서 닫히든 사용자가 물러나든 같은 콜백으로 온다. */
  onClose: () => void;
  /** 열 때 보여줄 탭. 기본은 로그인 — 대부분은 이미 계정이 있는 사람이다. */
  initialTab?: AuthTab;
  /**
   * 로그인·가입에 **성공했을 때만** 부른다. onClose 로는 이걸 알 수 없다 —
   * 성공해서 닫히든 사용자가 물러나든 같은 콜백이라, 성공만 붙잡고 싶은
   * 쪽(예: 웹툰 탭이 마이페이지로 보내는 것)이 구분할 방법이 없었다.
   *
   * 안 넘기면 아무 일도 안 일어난다 — 지금 부르는 자리는 전부 그대로다.
   */
  onSuccess?: (how: "login" | "signup") => void;
}

/** 서버(SignUp 요청 DTO)와 같은 기준. 여기서 먼저 걸러야 왕복 한 번을 아낀다. */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;

/** 서버가 문구를 안 준 경우에만 쓰는 기본값. 평소에는 서버의 한국어 문구를 그대로 띄운다. */
const FALLBACK_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요";

interface AgreementState {
  /** 만 14세 이상. 법이 요구하는 별도 사실이라 서버에도 따로 기록된다(AGE_14). */
  age: boolean;
  terms: boolean;
  privacy: boolean;
  marketing: boolean;
}

const NO_AGREEMENT: AgreementState = { age: false, terms: false, privacy: false, marketing: false };

/**
 * 오류 문구를 어느 칸 밑에 띄울지(2026-10-08 #690).
 *
 * ★ 왜 칸 밑인가 — 예전엔 문구 하나를 폼 맨 끝(주 버튼 바로 위)에 띄웠다. 작은 폰에서는
 *   가입 탭이 길어 그 자리가 **화면 밖이거나 바닥에 붙은 주 버튼 뒤**였다(320·360·390 세 기기
 *   모두 "비밀번호가 서로 다릅니다" 가 안 보였음, 실측). 눌러도 아무 일 없는 것처럼 보여
 *   운영에서 불일치 실패 8건이 그대로 이탈이 됐다. 문제가 난 칸 바로 밑에 두고 그 칸으로 데려간다.
 */
type ErrorSpot = "email" | "password" | "confirm" | "agree" | "form";

/** 화면에서 먼저 막는 코드 → 문구를 붙일 칸. 코드 이름(이벤트 값)은 그대로 둔다. */
const SPOT_OF_CODE: Record<string, ErrorSpot> = {
  client_empty_email: "email",
  client_password_short: "password",
  client_password_long: "password",
  client_password_mismatch: "confirm",
  client_age_unchecked: "agree",
  client_required_agreement: "agree",
};

/**
 * ★ 폰 단계형(2026-10-08 #690 2차 — 상훈님 판정 "키보드가 올라오면 모달에 이메일 하나만 보이고,
 *   쓰고 나면 밑으로 내려야 해서 엄청 불편하다").
 *
 * 키보드가 오른 화면(320×308 · 360×380 · 390×508)을 기준 화면으로 잡으면, 칸 셋·약관·버튼을 한 판에
 * 두는 한 무엇을 해도 굴려야 한다(가입 탭 내용 높이 약 600px). 그래서 폰에서는 **한 화면에 한 칸**:
 * 지금 칸 + 오류 한 줄 + 다음/제출 버튼 하나만 둔다. 데스크톱(마우스)은 지금 한 판 그대로.
 *
 * ★ 모든 칸은 늘 같은 <form> 안에 그려 두고 지금 칸이 아닌 것만 눈에서 숨긴다(display:none 아님).
 *   iOS·크롬 비밀번호 관리자는 같은 폼 안의 이메일 칸 + 비밀번호 칸을 짝으로 보고 자동 입력·저장을
 *   하기 때문이다 — 단계마다 칸을 빼 버리면 "비밀번호 저장" 이 안 뜬다.
 */
// ★ 2026-10-26 비밀번호와 비밀번호 확인을 한 단계에 둔다(상훈님 결정) — 폰 가입은 3단계.
type Step = "email" | "password" | "agree";
const LOGIN_STEPS: Step[] = ["email", "password"];
const SIGNUP_STEPS: Step[] = ["email", "password", "agree"];
const STEP_OF_SPOT: Partial<Record<ErrorSpot, Step>> = {
  email: "email", password: "password", confirm: "password", agree: "agree",
};
const STEP_MEDIA = "(pointer: coarse) and (max-width: 480px)";
const EMAIL_SHAPE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** 폰(터치 + 좁은 폭)인가. 바뀌면(가로 회전 등) 따라간다. */
function useStepMode(): boolean {
  const [on, setOn] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia(STEP_MEDIA);
    const sync = () => setOn(mq.matches);
    sync();
    mq.addEventListener("change", sync);
    return () => mq.removeEventListener("change", sync);
  }, []);
  return on;
}

function EyeIcon({ open }: { open: boolean }) {
  // 그림 문자 대신 SVG — 기기마다 모양이 달라지지 않게.
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
      {!open && <path d="M4 4l16 16" />}
    </svg>
  );
}

/**
 * 기록에 남길 실패 코드. 문구가 아니라 코드만 남긴다 —
 * 문구는 서버가 바꾸면 통계가 끊기고, 무엇보다 이메일·비밀번호는 절대 실려 나가면 안 된다.
 */
function errorCodeOf(e: unknown): string {
  if (e instanceof SessionCheckError) return e.code;
  if (e instanceof ApiError) return e.code ?? `http_${e.status}`;
  return "network_error";
}

function messageOf(e: unknown, locale: AuthLocale): string {
  if (locale === "ko") {
    if (e instanceof SessionCheckError) return e.message;
    return e instanceof ApiError && e.message ? e.message : FALLBACK_MESSAGE;
  }
  if (e instanceof SessionCheckError) return authText(locale, "로그인 상태를 확인하지 못했어요. 다시 시도해 주세요");
  if (e instanceof ApiError && e.code === "EMAIL_ALREADY_EXISTS") return authText(locale, "이미 가입된 이메일입니다");
  if (e instanceof ApiError && e.code === "LOGIN_FAILED") return authText(locale, "이메일 또는 비밀번호가 올바르지 않습니다");
  return authText(locale, FALLBACK_MESSAGE);
}

/** Tab 순환에 걸릴 것들. 모달 밖으로 포커스가 새는 걸 막는 데 쓴다. */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

export default function AuthModal({ open, onClose, onSuccess, initialTab = "login", locale = "ko" }: AuthModalProps) {
  const [documentLocale, setDocumentLocale] = useState<AuthLocale>("ko");
  useLayoutEffect(() => {
    if (locale !== "document") return;
    const sync = () => { const value = document.documentElement.lang; setDocumentLocale(value === "en" || value === "ja" ? value : "ko"); };
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });
    return () => observer.disconnect();
  }, [locale]);
  const resolvedLocale = locale === "document" ? documentLocale : locale;
  const t = (text: string) => authText(resolvedLocale, text);
  const [tab, setTab] = useState<AuthTab>(initialTab);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [agree, setAgree] = useState<AgreementState>(NO_AGREEMENT);
  const [formError, setFormError] = useState<string | null>(null);
  /** 오류 문구를 붙일 칸. formError 와 늘 같이 바뀐다. */
  const [errorSpot, setErrorSpot] = useState<ErrorSpot>("form");
  /** 오류를 띄운 횟수. 같은 문구가 반복돼도 새 오류로 알리는 데 쓴다(H14). */
  const [errorSeq, setErrorSeq] = useState(0);
  const stepMode = useStepMode();
  const [step, setStep] = useState<Step>("email");
  /** 비밀번호 보기. 가입 탭에서는 두 칸에 같이 걸린다 — 친 것을 눈으로 맞춰 볼 수 있게. */
  const [showPassword, setShowPassword] = useState(false);
  /** 오류가 아닌 안내(가입 완료 등). 탭을 옮길 때 지운다. */
  const [info, setInfo] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  /** 입력이 있는 채 뒤로가기를 눌렀을 때 띄우는 "닫을까요?" (H6). */
  const [confirmClose, setConfirmClose] = useState(false);
  /** 요청이 나가는 중인가 — 같은 틱 재진입까지 막는 잠금(H15). */
  const submittingRef = useRef(false);

  // 포털은 DOM 이 있어야 쏜다. 서버 렌더에는 document 가 없다.
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
  }, []);
  // 키보드 숫자는 공용 훅 한 곳에서 잰다(--vvh·--vvt·data-vv-keyboard, `?vvdebug=1` 숫자 줄도 거기).
  useVisualViewport(open);
  /** 빈 칸에서 "다음" 을 눌렀을 때의 회색 안내(오류가 아니다). */
  const [softHint, setSoftHint] = useState<{ spot: ErrorSpot; text: string } | null>(null);
  /** 8자 전에 "다음" 을 눌렀다 — 규칙 줄을 강조한다. 치기 시작하면 꺼진다. */
  const [shortBlocked, setShortBlocked] = useState(false);
  /** 이번에 연 뒤 이미 포커스 기록을 남긴 칸. */
  const focusedFieldsRef = useRef<Set<string>>(new Set());

  const dialogRef = useRef<HTMLDivElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLInputElement>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const errorRef = useRef<HTMLParagraphElement>(null);
  /** 다음 렌더 뒤에 포커스를 줄 칸. 오류·가입 완료 뒤에 쓴다(탭이 바뀌면 칸이 새로 그려져서). */
  const focusNextRef = useRef<"email" | "password" | "confirm" | null>(null);
  /** 모달을 열기 직전에 포커스가 있던 곳. 닫을 때 되돌려 준다. */
  const openerRef = useRef<Element | null>(null);
  /**
   * 이번에 연 뒤로 제출을 한 번이라도 눌렀는가.
   * "아무것도 안 하고 닫은" 이탈만 따로 세기 위한 표시다.
   */
  const submittedRef = useRef(false);

  const titleId = useId();
  const panelId = useId();
  const loginTabId = useId();
  const signupTabId = useId();
  const passwordHintId = useId();
  const passwordId = useId();
  const confirmId = useId();
  const errorId = useId();

  const isLogin = tab === "login";

  // 열릴 때마다 처음 상태로 되돌린다. 지난번에 치다 만 비밀번호가 남아 있으면 안 된다.
  // ★ useLayoutEffect 인 이유(2026-10-08 #690) — useEffect 면 다시 연 첫 화면 한 장이 **지난 상태로**
  //   그려진 뒤에 지워져, 다시 열린 화면에 지난 입력이 잠깐 비칠 수 있다. 그리기 전에 되돌린다.
  useLayoutEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setStep("email");
    setEmail("");
    setPassword("");
    setPasswordConfirm("");
    setAgree(NO_AGREEMENT);
    setFormError(null);
    setErrorSpot("form");
    setShowPassword(false);
    setInfo(null);
    setSubmitting(false);
    submittingRef.current = false;
    setConfirmClose(false);
    setSoftHint(null);
    setShortBlocked(false);
    focusedFieldsRef.current = new Set();
    submittedRef.current = false;
    track("auth_modal_opened", { tab: initialTab });
  }, [open, initialTab]);

  // 뒤 화면 스크롤 잠금. 모달 안에서 굴렸는데 뒤가 움직이면 위치를 잃는다.
  useEffect(() => {
    if (!open) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previous;
    };
  }, [open]);

  // 열면 첫 칸에 포커스, 닫으면 부른 자리(헤더 버튼)로 되돌린다.
  // 되돌리지 않으면 키보드 사용자가 문서 맨 처음으로 튕긴다.
  useEffect(() => {
    if (!open) return;
    openerRef.current = document.activeElement;
    // ★ 폰에서도 이메일 칸에 포커스를 준다(2026-10-26). 예전엔 창에 줘서 키보드가 안 떴고, 빈 칸 "다음" 이
    //   꾸중만 했다. 탭 없이 준 포커스라 iOS 는 키보드를 안 띄울 수 있지만, 칸을 누르거나 "다음" 을 누르는
    //   순간 그 동작 안에서 키보드가 오른다. 키보드 표시(data-vv-keyboard)는 사용자가 누른 포커스만 센다.
    emailRef.current?.focus({ preventScroll: true });
    return () => {
      const opener = openerRef.current;
      if (opener instanceof HTMLElement) opener.focus();
    };
  }, [open]);

  // ★ 폰에서는 뒤 문서를 position:fixed 로 묶는다(2026-10-08 #690 2차). overflow:hidden 만으로는
  //   iOS 가 포커스 때 문서를 밀어 올려(칸을 키보드 위로 보이려고) 시트가 뒤 화면과 같이 흔들린다.
  //   묶은 자리(scrollY)는 닫을 때 되돌린다. 데스크톱은 건드리지 않는다(스크롤바 자리 등).
  useEffect(() => {
    if (!open || !window.matchMedia(STEP_MEDIA).matches) return;
    const body = document.body;
    const y = window.scrollY;
    const prev = { position: body.style.position, top: body.style.top, left: body.style.left, right: body.style.right, width: body.style.width };
    body.style.position = "fixed";
    body.style.top = `-${y}px`;
    body.style.left = "0";
    body.style.right = "0";
    body.style.width = "100%";
    return () => {
      Object.assign(body.style, prev);
      window.scrollTo(0, y);
    };
  }, [open]);

  // 오류가 뜨면 그 칸으로 데려가고 문구가 보이게 굴린다. 비밀번호 칸은 내용을 선택해 두어
  // 바로 다시 치면 덮어써지게 한다(지우고 다시 쓰기).
  useEffect(() => {
    const target = focusNextRef.current;
    focusNextRef.current = null;
    const input =
      target === "email" ? emailRef.current
      : target === "password" ? passwordRef.current
      : target === "confirm" ? confirmRef.current
      : null;
    if (input) {
      input.focus({ preventScroll: true });
      if (input.type !== "email") input.select();
    }
    const msg = errorRef.current;
    if (msg) requestAnimationFrame(() => msg.scrollIntoView({ block: "nearest" }));
    else if (input) requestAnimationFrame(() => input.scrollIntoView({ block: "nearest" }));
  }, [formError, errorSpot, tab, info, errorSeq]);

  /** 오류 하나를 칸에 붙여 띄운다. 입력칸이면 그 칸으로 포커스를 옮긴다. */
  const showError = (spot: ErrorSpot, message: string) => {
    setErrorSeq((n) => n + 1);
    setErrorSpot(spot);
    setFormError(message);
    // 단계형이면 문제가 난 칸의 단계로 데려간다(가입 제출 때 막힌 경우 등).
    const at = STEP_OF_SPOT[spot];
    if (at) setStep(at);
    if (spot === "email" || spot === "password" || spot === "confirm") focusNextRef.current = spot;
  };

  /**
   * ★ 폰 뒤로가기 = 창 닫기(2026-10-08 #690).
   *
   * 창을 열 때 history 에 한 칸을 쌓고, 뒤로가기(popstate)가 오면 창만 닫는다. 예전엔 창이
   * history 를 안 써서 뒤로가기가 **페이지를 떠났다** — 치던 것도, 보던 화면도 같이 사라졌다.
   * - 쌓는 값은 지금 state 를 그대로 복사해 표시만 더한다. Next 라우터가 자기 state(__NA 등)가
   *   없는 칸으로 돌아오면 새로고침해 버리므로, 빈 state 를 쌓으면 안 된다.
   * - 입력이 있으면 바깥 탭과 같은 규칙으로 닫지 않고 칸을 다시 쌓는다(뒤로가기를 한 번 무른다).
   * - 닫기 버튼·Esc·바깥 탭으로 닫히면 쌓은 칸을 걷는다(history.back). 성공해서 닫힐 때는
   *   부르는 쪽이 곧장 다른 주소로 보낼 수 있어서(웹툰 → 마이페이지), **먼저** 걷고 나서 알린다.
   */
  const historyPushedRef = useRef(false);
  /** 닫힘 기록에 실을 지금 단계 번호(1부터). 렌더마다 맞춘다. */
  const stepNoRef = useRef(1);
  const stepModeRef = useRef(false);
  const dirtyRef = useRef(false);
  dirtyRef.current = Boolean(email || password || passwordConfirm);
  const requestCloseRef = useRef<(reason: "esc" | "backdrop" | "button" | "back") => void>(() => {});

  useEffect(() => {
    if (!open) return;
    const push = () =>
      window.history.pushState({ ...(window.history.state ?? {}), loreAuthModal: true }, "");
    push();
    historyPushedRef.current = true;
    const onPop = () => {
      if (!historyPushedRef.current) return; // 우리가 걷은 것
      if (dirtyRef.current) {
        // ★ 무르기만 하면 화면이 그대로라 먹통처럼 보였다(합본 H6). 칸은 다시 쌓되, 닫을지 묻는다.
        push();
        setConfirmClose(true);
        return;
      }
      historyPushedRef.current = false;
      requestCloseRef.current("back");
    };
    window.addEventListener("popstate", onPop);
    return () => {
      window.removeEventListener("popstate", onPop);
      if (historyPushedRef.current) {
        historyPushedRef.current = false;
        if (window.history.state?.loreAuthModal) window.history.back();
      }
    };
  }, [open]);

  /** 성공 직전에 쌓은 칸을 걷고 돌아온 것을 기다린다(늦어도 0.4초). */
  const popOwnHistory = () =>
    new Promise<void>((resolve) => {
      if (!historyPushedRef.current || !window.history.state?.loreAuthModal) {
        historyPushedRef.current = false;
        resolve();
        return;
      }
      historyPushedRef.current = false;
      let finished = false;
      const done = () => {
        if (finished) return;
        finished = true;
        window.removeEventListener("popstate", done);
        resolve();
      };
      window.addEventListener("popstate", done);
      window.history.back();
      window.setTimeout(done, 400);
    });

  const requestClose = useCallback(
    (reason: "esc" | "backdrop" | "button" | "back") => {
      // 성공해서 닫히는 경우는 이 함수를 안 거친다. 여기 오는 건 전부 물러난 것이다.
      if (!submittedRef.current) track("auth_modal_dismissed", stepModeRef.current ? { tab, reason, step: stepNoRef.current } : { tab, reason });
      onClose();
    },
    [onClose, tab],
  );
  requestCloseRef.current = requestClose;

  const handleKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      requestClose("esc");
      return;
    }
    if (e.key !== "Tab") return;

    // 포커스 가두기. 모달이 떠 있는 동안 뒤 화면의 링크로 넘어가면
    // 화면에는 안 보이는 곳을 조작하게 된다.
    const nodes = dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
    if (!nodes || nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const switchTab = (next: AuthTab) => {
    if (next === tab) return;
    track("auth_tab_switched", { from: tab, to: next });
    setTab(next);
    setStep("email");
    // 이메일은 남긴다. 로그인에 실패해 가입으로 넘어오는 흐름이 가장 흔한데 거기서 다시 치게 하면
    // 그 자리에서 그만둔다. ★ 비밀번호·확인은 비운다(합본 H20) — 로그인에 쳤던(틀렸을 수도 있는)
    //   비밀번호가 가입으로 따라와, 9자 이상이면 비밀번호 단계를 건너뛰고 3/4 로 뛰었다.
    setPassword("");
    setPasswordConfirm("");
    setFormError(null);
    setInfo(null);
    setSoftHint(null);
    setShortBlocked(false);
  };

  /**
   * 키보드의 "이동/완료" 를 눌렀을 때. 마지막 칸이 아니면 제출하지 말고 다음 칸으로.
   * 예전엔 이메일 칸에서 누르면 바로 제출돼 "비밀번호 8자 이상" 같은 오류부터 봤다.
   */
  const enterToNext = (next: React.RefObject<HTMLInputElement | null>) =>
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key !== "Enter" || e.nativeEvent.isComposing) return;
      e.preventDefault();
      next.current?.focus();
    };

  const steps = isLogin ? LOGIN_STEPS : SIGNUP_STEPS;
  const stepIndex = Math.max(0, steps.indexOf(step));
  const lastStep = stepIndex === steps.length - 1;
  stepNoRef.current = stepIndex + 1;
  stepModeRef.current = stepMode;
  const inputOf = (st: Step) =>
    st === "email" ? emailRef.current
    : st === "password" ? passwordRef.current
    : null;
  /** 이 단계에 사는 칸들(비밀번호 단계는 비밀번호 + 확인). */
  const inputsOf = (st: Step): (HTMLInputElement | null)[] =>
    st === "password" && !isLogin ? [passwordRef.current, confirmRef.current] : [inputOf(st)];

  /**
   * 단계 이동. ★ 다음 칸으로의 포커스는 **누른 그 자리에서 동기로** 준다(flushSync 로 먼저 그린 뒤).
   * iOS 는 사용자 동작 밖에서(useEffect 등) 준 포커스로는 키보드를 띄우지 않아서, 단계가 넘어갈 때
   * 키보드가 내려갔다 다시 눌러야 올라오는 일이 생긴다.
   */
  const moveTo = (next: Step) => {
    flushSync(() => {
      setStep(next);
      setFormError(null);
      setSoftHint(null);
    });
    const input = inputOf(next);
    if (input) input.focus({ preventScroll: true });
    else if (document.activeElement instanceof HTMLElement) document.activeElement.blur();
  };

  /**
   * 단계 안에서 막는 것(2026-10-26). 제출 실패(auth_*_failed)와 따로 센다 — 보내 보지도 않은 막힘이
   * 실패 통계에 섞여 "제출 없는 실패" 로 보였다(ux/05). 막힌 칸으로 **누른 그 자리에서** 포커스를 준다
   * (iOS 가 키보드를 띄우게). error 가 null 이면 빨간 오류 없이, hint 가 있으면 회색 한 줄만.
   */
  const blockStep = (code: string, spot: ErrorSpot, error: string | null, hint: string | null = null) => {
    track("auth_step_blocked", { tab, step: stepIndex + 1, code });
    if (error) {
      setErrorSeq((n) => n + 1);
      setErrorSpot(spot);
      setFormError(error);
    } else {
      setFormError(null);
    }
    setSoftHint(hint ? { spot, text: hint } : null);
    const input = spot === "email" ? emailRef.current : spot === "password" ? passwordRef.current
      : spot === "confirm" ? confirmRef.current : null;
    input?.focus({ preventScroll: true });
  };

  /**
   * "다음". 지금 단계를 확인한다. 이메일 단계에서 비밀번호 관리자가 뒤 칸까지 채웠으면 바로 다음 빈 단계로.
   */
  const goNext = () => {
    const trimmedEmail = email.trim();
    const agreed = agree.age && agree.terms && agree.privacy;
    if (step === "email") {
      if (!trimmedEmail) return blockStep("client_empty_email", "email", null, "이메일을 적어 주세요");
      if (!EMAIL_SHAPE.test(trimmedEmail)) return blockStep("client_email_shape", "email", "이메일 주소를 다시 확인해 주세요");
      if (isLogin) return password ? submitAll() : moveTo("password");
      const passwordOk = password.length >= PASSWORD_MIN && password.length <= PASSWORD_MAX && password === passwordConfirm;
      if (!passwordOk) return moveTo("password");
      return agreed ? submitAll() : moveTo("agree");
    }
    if (step === "password" && !isLogin) {
      if (password.length < PASSWORD_MIN) {
        setShortBlocked(true);
        return blockStep("client_password_short", "password", null);
      }
      if (password.length > PASSWORD_MAX) {
        return blockStep("client_password_long", "password", `비밀번호는 ${PASSWORD_MAX}자까지 쓸 수 있습니다`);
      }
      if (!passwordConfirm) {
        // 비밀번호 칸에서 Enter·"다음" 을 친 것은 확인 칸으로 가는 자연스러운 흐름이다(막힘으로 안 센다).
        if (document.activeElement === passwordRef.current) {
          confirmRef.current?.focus({ preventScroll: true });
          return;
        }
        return blockStep("client_empty_confirm", "confirm", null);
      }
      if (password !== passwordConfirm) {
        // ★ 이전 단계로 안 보낸다. 확인 칸만 비우고 거기서 다시 치게 한다(결정 1).
        setPasswordConfirm("");
        return blockStep("client_password_mismatch", "confirm", "비밀번호가 서로 다릅니다");
      }
      return agreed ? submitAll() : moveTo("agree");
    }
  };

  const goBack = () => {
    if (stepIndex > 0) moveTo(steps[stepIndex - 1]);
  };

  /** 키보드 Enter 키 모양. 단계형은 마지막 단계만 go, 나머지는 next(H43·H58). 한 판 폼은 예전 그대로. */
  const keyHint = (st: Step, flat: "next" | "go" | "done") =>
    stepMode ? (steps[steps.length - 1] === st ? "go" : "next") : flat;

  /** 단계형에서 지금 단계가 아닌 칸은 눈에서만 숨긴다(폼에는 남는다 — 자동 입력 짝). */
  const hiddenUnless = (st: Step) => (stepMode && step !== st ? ` ${styles.stepHidden}` : "");
  const hiddenProps = (st: Step) =>
    stepMode && step !== st ? ({ tabIndex: -1, "aria-hidden": true } as const) : {};

  const toggleAll = () => {
    const next = !(agree.age && agree.terms && agree.privacy && agree.marketing);
    setAgree({ age: next, terms: next, privacy: next, marketing: next });
  };

  const setOne = (key: keyof AgreementState) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const checked = e.target.checked;
    setAgree((prev) => ({ ...prev, [key]: checked }));
  };

  async function handleLogin(trimmedEmail: string) {
    track("auth_login_submitted");

    if (!trimmedEmail || !password) {
      track("auth_login_failed", { code: "client_empty_field" });
      showError(trimmedEmail ? "password" : "email", "이메일과 비밀번호를 입력해 주세요");
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    setFormError(null);
    try {
      await signIn({ email: trimmedEmail, password });
      track("auth_login_succeeded");
      await popOwnHistory();
      onSuccess?.("login");
      onClose();
    } catch (e) {
      track("auth_login_failed", { code: errorCodeOf(e) });
      // 로그인 실패는 대개 비밀번호 오타다 — 비밀번호 칸 밑에 두고 그 칸을 선택해 둔다.
      // (확인 실패(me_failed)도 같은 자리 — 같은 칸에서 다시 누르면 된다.)
      showError("password", messageOf(e, resolvedLocale));
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  async function handleSignUp(trimmedEmail: string) {
    track("auth_signup_submitted");

    // 화면에서 먼저 막는 것들. 서버도 같은 기준으로 다시 보지만,
    // 왕복 한 번을 기다렸다가 "8자 이상" 을 듣는 건 사람을 두 번 일하게 하는 것이다.
    const blocked =
      !trimmedEmail ? ["client_empty_email", "이메일을 입력해 주세요"]
      : password.length < PASSWORD_MIN ? ["client_password_short", `비밀번호는 ${PASSWORD_MIN}자 이상이어야 합니다`]
      : password.length > PASSWORD_MAX ? ["client_password_long", `비밀번호는 ${PASSWORD_MAX}자까지 쓸 수 있습니다`]
      : password !== passwordConfirm ? ["client_password_mismatch", "비밀번호가 서로 다릅니다"]
      : !agree.age ? ["client_age_unchecked", "만 14세 이상만 가입할 수 있습니다"]
      : !agree.terms || !agree.privacy ? ["client_required_agreement", "필수 약관에 동의해 주세요"]
      : null;

    if (blocked) {
      // 실패로 함께 센다. 보내 보지도 못하고 여기서 그만두는 사람이
      // 서버가 거절한 사람만큼이나 중요한 이탈이라서다.
      track("auth_signup_failed", { code: blocked[0] });
      showError(SPOT_OF_CODE[blocked[0]] ?? "form", blocked[1]);
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    setFormError(null);
    setInfo(null);
    try {
      await signUp({
        email: trimmedEmail,
        password,
        // 서버는 Map<AgreementType, Boolean> 을 받는다.
        // ★ 연령(AGE_14)도 함께 보낸다 — 약관을 한 문서로 합치더라도 나이는 법이 요구하는
        //   별도 사실이라, 이용약관 동의에 묻히면 "언제 무엇에 동의했나" 를 답할 수 없다.
        // MARKETING 은 안 눌러도 false 를 담아 보낸다. 안 물어본 것과 거부한 것은 다른 사실이고,
        // 서버가 그 차이를 기록한다.
        agreements: {
          AGE_14: agree.age, TERMS: agree.terms, PRIVACY: agree.privacy, MARKETING: agree.marketing,
        },
      });
      track("auth_signup_succeeded");
    } catch (e) {
      track("auth_signup_failed", { code: errorCodeOf(e) });
      // 이미 가입된 이메일은 이메일 칸의 문제다 — 단계형이면 이메일 단계로 돌아간다.
      showError(errorCodeOf(e) === "EMAIL_ALREADY_EXISTS" ? "email" : "form", messageOf(e, resolvedLocale));
      submittingRef.current = false;
      setSubmitting(false);
      return;
    }

    // ★ 가입 뒤 바로 로그인(2026-10-26 결정 4, 화면만). 서버 가입 정책(가입은 토큰을 안 준다)은 그대로 두고,
    //   방금 친 이메일·비밀번호로 로그인 요청을 한 번 이어서 보낸다. 사람은 비밀번호를 다시 치지 않는다.
    //   실패하면 예전처럼 로그인 단계로 넘기되 비밀번호를 비우지 않아 [로그인] 한 번이면 된다.
    track("auth_login_submitted", { reason: "after_signup" });
    try {
      await signIn({ email: trimmedEmail, password });
      track("auth_login_succeeded", { reason: "after_signup" });
      await popOwnHistory();
      onSuccess?.("login");
      onClose();
    } catch (e) {
      track("auth_login_failed", { code: errorCodeOf(e), reason: "after_signup" });
      setTab("login");
      setStep("password");
      setPasswordConfirm("");
      setInfo("가입은 됐어요. 로그인 상태를 확인하지 못했어요 — 로그인을 눌러 다시 시도해 주세요");
      onSuccess?.("signup");
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  /**
   * ★ 한글 조합 중 Enter 는 무시한다(합본 H3). 조합을 확정하는 Enter 가 폼의 암묵 제출까지 일으켜,
   *   이메일 끝에 "ㅎ" 이 붙은 채 다음 단계로 넘어갔다(크롬 CDP 재현: `a@b.coㅎ`).
   *   keyCode 229 는 조합 중 키를 isComposing 없이 보내는 브라우저(구형 사파리·일부 안드로이드)용.
   */
  const handleFormKeyDown = (e: React.KeyboardEvent<HTMLFormElement>) => {
    // 조합 중 다른 키(229)는 건드리지 않는다 — 막으면 글자 입력 자체가 끊긴다.
    if (e.key !== "Enter") return;
    if (e.nativeEvent.isComposing || e.keyCode === 229) {
      e.preventDefault();
      return;
    }
    // ★ 단계형은 Enter 를 직접 받아 다음으로 보낸다(합본 H43·H58). 폼의 암묵 제출에 기대면
    //   키 종류(next·done·go)와 브라우저에 따라 Enter 가 submit 으로 안 이어지는 경우가 갈린다.
    if (stepMode && e.target instanceof HTMLInputElement && e.target.type !== "checkbox") {
      e.preventDefault();
      advance();
    }
  };

  /**
   * 안드로이드 키보드의 "다음"(enterKeyHint=next)은 Enter 가 아니라 **포커스를 다음 칸으로 옮기는**
   * 동작으로 오는 경우가 있다(H43). 그 칸이 아직 숨은 단계면 아무 일도 안 일어난 것처럼 보이므로,
   * 손가락 없이 포커스가 숨은 칸으로 넘어오면 "다음" 으로 읽는다. moveTo 는 단계를 먼저 바꾼 뒤
   * 포커스를 주므로 여기에 안 걸린다.
   */
  /** 폼 안을 손가락·마우스로 누른 시각. 누른 지 0.6초 안의 포커스 이동은 사용자가 고른 칸이다. */
  const formPointerAtRef = useRef(0);
  const handleFormFocus = (e: React.FocusEvent<HTMLFormElement>) => {
    const t: EventTarget = e.target;
    const field = t === emailRef.current ? "email" : t === passwordRef.current ? "password" : t === confirmRef.current ? "confirm" : null;
    // 칸마다 이번에 연 뒤 첫 포커스만 남긴다(키보드가 떴다는 대략의 표시). 허용 키에 field 가 없어 action 에 싣는다.
    if (field && !focusedFieldsRef.current.has(field)) {
      focusedFieldsRef.current.add(field);
      track("auth_field_focused", { tab, step: stepIndex + 1, action: field });
    }
    if (!stepMode || Date.now() - formPointerAtRef.current < 600) return;
    if (!(t instanceof HTMLInputElement) || t.type === "checkbox") return;
    const here = inputsOf(step);
    const from = e.relatedTarget;
    if (!here.includes(t) && from instanceof HTMLInputElement && here.includes(from)) {
      from.focus({ preventScroll: true });
      advance();
    }
  };

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    advance();
  };

  /** 다음(단계형, 마지막 전) 또는 제출. 버튼·Enter·키보드 "다음" 이 모두 이리 온다. */
  function advance() {
    // 중복 제출 방지. 버튼도 잠그지만 엔터로도 들어온다. ★ state 만으로는 같은 틱 두 번째 누름
    // (다시 그리기 전)을 못 막아서 ref 로도 잠근다(합본 H15).
    if (submitting || submittingRef.current) return;
    if (stepMode && !lastStep) {
      goNext();
      return;
    }
    submitAll();
  }

  function submitAll() {
    submittedRef.current = true;
    const trimmedEmail = email.trim();
    void (isLogin ? handleLogin(trimmedEmail) : handleSignUp(trimmedEmail));
  }

  // 단계마다 한 번(폰 단계형만). 어느 단계에서 몇 명이 남는지 본다.
  useEffect(() => {
    if (!open || !stepMode) return;
    track("auth_step_shown", { tab, step: stepIndex + 1 });
  }, [open, stepMode, tab, stepIndex]);

  if (!open || !mounted) return null;

  const allAgreed = agree.age && agree.terms && agree.privacy && agree.marketing;
  /**
   * 단계형 탭 줄은 첫 단계에만 둔다. ★ 단, 로그인이 비밀번호 단계에서 막혔으면 다시 보인다(합본 H42) —
   * 로그인에 실패한 사람의 다음 행동이 "아, 가입을 안 했구나" 인데, 그 자리에 "회원가입" 이 없었다.
   */
  // ★ 2026-10-26 폰에서는 탭 두 칸 대신 아래 한 줄("이미 계정이 있어요 · 로그인")로 바꾼다 — 키보드 위 약 60px.
  //   탭 줄은 DOM 에 남긴다(눈에서만 숨김): 피스메이커가 `:has(> [role="tablist"])` 로 이 창을 찾아 옷을 입힌다.
  const tabsHidden = stepMode;
  /** 한 줄 전환은 첫 단계, 또는 로그인이 비밀번호 단계에서 막혔을 때(합본 H42 — "아, 가입을 안 했구나"). */
  const switchShown = stepMode && (step === "email" || (isLogin && formError !== null && errorSpot === "password"));
  /** 지금 단계가 채워졌는가. 안 채워졌으면 "다음" 을 흐리게 둔다(누를 수는 있다). */
  const ready =
    step === "email" ? email.trim() !== ""
    : step === "password" ? (isLogin ? password !== "" : password.length >= PASSWORD_MIN && passwordConfirm !== "")
    : agree.age && agree.terms && agree.privacy;
  const revealButton = (hidden: boolean) => (
    <button
      type="button"
      className={styles.reveal}
      {...(hidden ? ({ tabIndex: -1, "aria-hidden": true } as const) : {})}
      onClick={() => {
        const next = !showPassword;
        track("auth_password_reveal", { action: next ? "show" : "hide" });
        setShowPassword(next);
      }}
      aria-label={showPassword ? t("비밀번호 숨기기") : t("비밀번호 보기")}
      aria-pressed={showPassword}
    >
      <EyeIcon open={showPassword} />
    </button>
  );

  /** spot 칸의 오류 문구. role="alert" — 생기면 화면 낭독기가 즉시 읽는다. */
  const errorAt = (spot: ErrorSpot) =>
    formError && errorSpot === spot ? (
      // ★ key 가 오류마다 바뀐다(H14) — 같은 문구가 다시 나와도 새 요소로 붙어 낭독기가 다시 읽고,
      //   위 효과도 다시 돌아 칸 포커스·선택이 돌아온다. 예전엔 같은 값이라 아무 반응이 없어 보였다.
      <p key={errorSeq} ref={errorRef} id={errorId} className={styles.error} role="alert">
        {t(formError)}
      </p>
    ) : null;
  const invalid = (spot: ErrorSpot) => Boolean(formError) && errorSpot === spot;
  const describedBy = (...ids: (string | false | undefined)[]) =>
    ids.filter(Boolean).join(" ") || undefined;

  return createPortal(
    <div
      className={styles.overlay}
      // 배경을 눌러 닫는다. mousedown 을 보는 이유는, 입력 칸에서 드래그하다
      // 배경에서 손을 떼는 경우까지 "닫기" 로 읽히는 걸 막기 위해서다.
      ref={overlayRef}
      onMouseDown={(e) => {
        if (e.target !== e.currentTarget) return;
        // ★ 뭔가 쳐 둔 게 있으면 배경을 눌러도 안 닫는다(2026-10-08 #690). 폰에서는 키보드
        //   가장자리·창 위 여백을 스치기만 해도 닫혀 친 것이 통째로 사라졌다(실측: 390 가입 탭
        //   포함 4/6 조합에서 닫힘). 닫기 버튼과 Esc 는 그대로 닫는다.
        if (email || password || passwordConfirm) return;
        requestClose("backdrop");
      }}
    >
      <div
        ref={dialogRef}
        className={`${styles.dialog}${stepMode ? ` ${styles.stepMode}` : ""}`}
        data-step-mode={stepMode ? step : undefined}
        role="dialog"
        lang={resolvedLocale}
        aria-modal="true"
        aria-labelledby={titleId}
        // 폰에서 처음 열 때 포커스를 받는 자리(칸에 주면 키보드 없이 시트가 위로 간다 — 위 효과).
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        <div className={styles.head}>
          {stepMode && stepIndex > 0 && (
            <button type="button" className={styles.stepBack} onClick={goBack}
                    aria-label={t("이전")} data-action="auth-step-back">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M15 5l-7 7 7 7" />
              </svg>
            </button>
          )}
          <h2 id={titleId} className={styles.title}>
            {isLogin ? t("로그인") : t("회원가입")}
          </h2>
          {stepMode && (
            <span className={styles.progress} data-part="auth-step-progress">
              {stepIndex + 1} / {steps.length}
            </span>
          )}
          <button
            type="button"
            className={styles.close}
            onClick={() => requestClose("button")}
            aria-label={t("닫기")}
          >
            <span aria-hidden="true">×</span>
          </button>
        </div>

        {/* 단계형에서는 첫 단계에만 탭을 보인다 — 키보드가 오른 화면에서 한 줄(52px)이 아깝다.
            ★ 빼지 않고 눈에서만 숨긴다: 피스메이커가 `:has(> [role="tablist"])` 로 이 창을 찾아 옷을
              입히므로(piece-maker.css), 탭이 DOM 에서 빠지면 둘째 단계부터 옷이 벗겨졌다(실측). */}
        <div className={`${styles.tabs}${tabsHidden ? ` ${styles.stepHidden}` : ""}`}
             role="tablist" aria-label={t("로그인 또는 회원가입")}
             aria-hidden={tabsHidden ? true : undefined}>
          <button
            type="button"
            role="tab"
            id={loginTabId}
            aria-selected={isLogin}
            aria-controls={panelId}
            className={`${styles.tab} ${isLogin ? styles.tabActive : ""}`}
            onClick={() => switchTab("login")}
            tabIndex={tabsHidden ? -1 : undefined}
          >
            {t("로그인")}
          </button>
          <button
            type="button"
            role="tab"
            id={signupTabId}
            aria-selected={!isLogin}
            aria-controls={panelId}
            className={`${styles.tab} ${!isLogin ? styles.tabActive : ""}`}
            onClick={() => switchTab("signup")}
            tabIndex={tabsHidden ? -1 : undefined}
          >
            {t("회원가입")}
          </button>
        </div>

        {confirmClose && (
          <div className={styles.confirm} role="group" aria-label={t("닫기 확인")} data-part="auth-close-confirm">
            <p className={styles.confirmText}>{t("닫을까요? 입력한 내용이 사라져요")}</p>
            <div className={styles.confirmActions}>
              <button type="button" className={styles.confirmStay} data-action="auth-close-stay"
                      onClick={() => { setConfirmClose(false); inputOf(step)?.focus({ preventScroll: true }); }}>
                {t("계속")}
              </button>
              <button type="button" className={styles.confirmLeave} data-action="auth-close-leave"
                      onClick={() => requestClose("back")}>
                {t("닫기")}
              </button>
            </div>
          </div>
        )}

        <form
          id={panelId}
          role="tabpanel"
          aria-labelledby={isLogin ? loginTabId : signupTabId}
          ref={formRef}
          className={styles.form}
          onSubmit={handleSubmit}
          onKeyDown={handleFormKeyDown}
          onFocus={handleFormFocus}
          onPointerDown={() => { formPointerAtRef.current = Date.now(); }}
          // 브라우저 기본 말풍선을 끄고 우리 문구로 통일한다.
          noValidate
        >
          <label className={`${styles.field}${hiddenUnless("email")}`}>
            <span className={styles.label}>{t("이메일")}</span>
            <input
              ref={emailRef}
              {...hiddenProps("email")}
              className={styles.input}
              type="email"
              inputMode="email"
              autoComplete="email"
              autoCapitalize="none"
              spellCheck={false}
              enterKeyHint={keyHint("email", "next")}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              onKeyDown={stepMode ? undefined : enterToNext(passwordRef)}
              aria-invalid={invalid("email") || undefined}
              aria-describedby={describedBy(invalid("email") && errorId)}
              placeholder="you@example.com"
            />
          </label>
          {errorAt("email")}
          {!formError && softHint?.spot === "email" && <p className={styles.hint} data-part="auth-soft-hint">{t(softHint.text)}</p>}

          {/* 보기 버튼이 들어가서 label 로 감싸지 않고 htmlFor 로 잇는다 —
              label 안의 버튼은 브라우저마다 누름이 입력칸 포커스로 새기도 한다. */}
          <div className={`${styles.field}${hiddenUnless("password")}`}>
            <label htmlFor={passwordId} className={styles.label}>{t("비밀번호")}</label>
            <span className={styles.inputWrap}>
              <input
                ref={passwordRef}
                id={passwordId}
                {...hiddenProps("password")}
                className={`${styles.input} ${styles.inputWithReveal}`}
                type={showPassword ? "text" : "password"}
                autoComplete={isLogin ? "current-password" : "new-password"}
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                enterKeyHint={keyHint("password", isLogin ? "go" : "next")}
                value={password}
                onChange={(e) => { setPassword(e.target.value); setShortBlocked(false); }}
                onKeyDown={isLogin || stepMode ? undefined : enterToNext(confirmRef)}
                aria-invalid={invalid("password") || undefined}
                aria-describedby={describedBy(!isLogin && passwordHintId, invalid("password") && errorId)}
              />
              {revealButton(stepMode && step !== "password")}
            </span>
            {!isLogin && (
              // 치기 전부터 규칙을 보이고, 8자가 되면 체크로 바뀐다. 8자 전에 "다음" 을 누르면 강조한다.
              <span id={passwordHintId} className={styles.hint} data-part="auth-password-rule"
                    data-state={password.length >= PASSWORD_MIN ? "ok" : shortBlocked ? "blocked" : undefined}>
                {password.length >= PASSWORD_MIN && (
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3"
                       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M5 12l5 5L20 7" /></svg>
                )}
                {t(`${PASSWORD_MIN}자 이상`)}
              </span>
            )}
          </div>
          {errorAt("password")}

          {!isLogin && (
            <>
              <div className={`${styles.field}${hiddenUnless("password")}`}>
                <label htmlFor={confirmId} className={styles.label}>{t("비밀번호 확인")}</label>
                <span className={styles.inputWrap}>
                <input
                  ref={confirmRef}
                  id={confirmId}
                  {...hiddenProps("password")}
                  className={`${styles.input} ${styles.inputWithReveal}`}
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  enterKeyHint={stepMode ? "next" : "done"}
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                  aria-invalid={invalid("confirm") || undefined}
                  aria-describedby={describedBy(invalid("confirm") && errorId)}
                />
                {revealButton(stepMode && step !== "password")}
                </span>
              </div>
              {errorAt("confirm")}

              <fieldset className={`${styles.agreements}${hiddenUnless("agree")}`}>
                <legend className={styles.srOnly}>{t("약관 동의")}</legend>

                <label className={`${styles.agreeLabel} ${styles.agreeAll}`}>
                  <input type="checkbox" checked={allAgreed} onChange={toggleAll} />
                  <span>{t("전체 동의")}</span>
                </label>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.age} onChange={setOne("age")} />
                    <span className={styles.tagRequired}>{t("[필수]")}</span>
                    <span>{t("만 14세 이상입니다")}</span>
                  </label>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.terms} onChange={setOne("terms")} />
                    <span className={styles.tagRequired}>{t("[필수]")}</span>
                    <span>{t("이용약관에 동의합니다")}</span>
                  </label>
                  {/* 링크를 label 밖에 두는 이유: 안에 있으면 "보기" 를 누른 것이
                      체크 토글로도 읽히는 브라우저가 있다. */}
                  <a
                    className={styles.agreeLink}
                    href={LEGAL_LINKS.terms}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {t("보기")}
                  </a>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.privacy} onChange={setOne("privacy")} />
                    <span className={styles.tagRequired}>{t("[필수]")}</span>
                    <span>{t("개인정보 처리방침에 동의합니다")}</span>
                  </label>
                  <a
                    className={styles.agreeLink}
                    href={LEGAL_LINKS.privacy}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {t("보기")}
                  </a>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input
                      type="checkbox"
                      checked={agree.marketing}
                      onChange={setOne("marketing")}
                    />
                    <span className={styles.tagOptional}>{t("[선택]")}</span>
                    <span>{t("마케팅 정보 수신에 동의합니다")}</span>
                  </label>
                </div>
              </fieldset>
              {errorAt("agree")}
            </>
          )}

          {/* 칸에 붙일 수 없는 오류(서버 거절 등)는 예전처럼 주 버튼 바로 위. */}
          {errorAt("form")}

          {/* 오류가 아닌 안내(가입 완료). status 로 읽어 주되 오류처럼 다급하게 읽지 않는다. */}
          {!formError && info && (
            <p className={styles.info} role="status">
              {t(info)}
            </p>
          )}

          <button type="submit" className={styles.submit} disabled={submitting}
                  data-ready={stepMode ? String(ready) : undefined}
                  data-action={stepMode && !lastStep ? "auth-step-next" : "auth-submit"}>
            {submitting ? t("처리 중…")
              : stepMode && !lastStep ? t("다음")
              : isLogin ? t("로그인") : t("가입하고 시작하기")}
          </button>
        </form>
        {switchShown && (
          <p className={styles.switchLine}>
            {t(isLogin ? "계정이 없어요" : "이미 계정이 있어요")}
            <button type="button" className={styles.switchLink} data-action="auth-switch-tab"
                    onClick={() => switchTab(isLogin ? "signup" : "login")}>
              {t(isLogin ? "회원가입" : "로그인")}
            </button>
          </p>
        )}
      </div>
    </div>,
    document.body,
  );
}
