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

import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from "react";
import { createPortal } from "react-dom";
import { ApiError } from "../api/client";
import { track } from "../analytics";
import { LEGAL_LINKS } from "../links";
import { signIn, signUpAndSignIn } from "./useAuth";
import styles from "./AuthModal.module.css";

export type AuthTab = "login" | "signup";

export interface AuthModalProps {
  open: boolean;
  /** 닫기 요청. 성공해서 닫히든 사용자가 물러나든 같은 콜백으로 온다. */
  onClose: () => void;
  /** 열 때 보여줄 탭. 기본은 로그인 — 대부분은 이미 계정이 있는 사람이다. */
  initialTab?: AuthTab;
}

/** 서버(SignUp 요청 DTO)와 같은 기준. 여기서 먼저 걸러야 왕복 한 번을 아낀다. */
const PASSWORD_MIN = 8;
const PASSWORD_MAX = 72;

/** 서버가 문구를 안 준 경우에만 쓰는 기본값. 평소에는 서버의 한국어 문구를 그대로 띄운다. */
const FALLBACK_MESSAGE = "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요";

interface AgreementState {
  /** 화면에서만 막는다. 서버 스키마(AgreementType)에 없는 값이라 보내지 않는다. */
  age: boolean;
  terms: boolean;
  privacy: boolean;
  marketing: boolean;
}

const NO_AGREEMENT: AgreementState = { age: false, terms: false, privacy: false, marketing: false };

/**
 * 기록에 남길 실패 코드. 문구가 아니라 코드만 남긴다 —
 * 문구는 서버가 바꾸면 통계가 끊기고, 무엇보다 이메일·비밀번호는 절대 실려 나가면 안 된다.
 */
function errorCodeOf(e: unknown): string {
  if (e instanceof ApiError) return e.code ?? `http_${e.status}`;
  return "network_error";
}

function messageOf(e: unknown): string {
  return e instanceof ApiError && e.message ? e.message : FALLBACK_MESSAGE;
}

/** Tab 순환에 걸릴 것들. 모달 밖으로 포커스가 새는 걸 막는 데 쓴다. */
const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

export default function AuthModal({ open, onClose, initialTab = "login" }: AuthModalProps) {
  const [tab, setTab] = useState<AuthTab>(initialTab);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirm, setPasswordConfirm] = useState("");
  const [agree, setAgree] = useState<AgreementState>(NO_AGREEMENT);
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 포털은 DOM 이 있어야 쏜다. 서버 렌더에는 document 가 없다.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  const dialogRef = useRef<HTMLDivElement>(null);
  const emailRef = useRef<HTMLInputElement>(null);
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

  const isLogin = tab === "login";

  // 열릴 때마다 처음 상태로 되돌린다. 지난번에 치다 만 비밀번호가 남아 있으면 안 된다.
  useEffect(() => {
    if (!open) return;
    setTab(initialTab);
    setEmail("");
    setPassword("");
    setPasswordConfirm("");
    setAgree(NO_AGREEMENT);
    setFormError(null);
    setSubmitting(false);
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
    emailRef.current?.focus();
    return () => {
      const opener = openerRef.current;
      if (opener instanceof HTMLElement) opener.focus();
    };
  }, [open]);

  const requestClose = useCallback(
    (reason: "esc" | "backdrop" | "button") => {
      // 성공해서 닫히는 경우는 이 함수를 안 거친다. 여기 오는 건 전부 물러난 것이다.
      if (!submittedRef.current) track("auth_modal_dismissed", { tab, reason });
      onClose();
    },
    [onClose, tab],
  );

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
    // 이메일·비밀번호는 남긴다. 로그인에 실패해 가입으로 넘어오는 흐름이 가장 흔한데
    // 거기서 다시 치게 하면 그 자리에서 그만둔다. 오류 문구만 지운다.
    setFormError(null);
  };

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
      setFormError("이메일과 비밀번호를 입력해 주세요");
      return;
    }

    setSubmitting(true);
    setFormError(null);
    try {
      await signIn({ email: trimmedEmail, password });
      track("auth_login_succeeded");
      onClose();
    } catch (e) {
      track("auth_login_failed", { code: errorCodeOf(e) });
      setFormError(messageOf(e));
    } finally {
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
      setFormError(blocked[1]);
      return;
    }

    setSubmitting(true);
    setFormError(null);
    try {
      await signUpAndSignIn({
        email: trimmedEmail,
        password,
        // 서버는 Map<AgreementType, Boolean> 을 받는다. 연령 확인은 여기 없다 —
        // 서버 스키마에 없는 값이라 보내면 400 이 된다.
        // MARKETING 은 안 눌러도 false 를 담아 보낸다. 안 물어본 것과 거부한 것은 다른 사실이고,
        // 서버가 그 차이를 기록한다.
        agreements: { TERMS: agree.terms, PRIVACY: agree.privacy, MARKETING: agree.marketing },
      });
      track("auth_signup_succeeded");
      onClose();
    } catch (e) {
      track("auth_signup_failed", { code: errorCodeOf(e) });
      setFormError(messageOf(e));
    } finally {
      setSubmitting(false);
    }
  }

  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submitting) return; // 중복 제출 방지. 버튼도 잠그지만 엔터로도 들어온다.
    submittedRef.current = true;
    const trimmedEmail = email.trim();
    void (isLogin ? handleLogin(trimmedEmail) : handleSignUp(trimmedEmail));
  };

  if (!open || !mounted) return null;

  const allAgreed = agree.age && agree.terms && agree.privacy && agree.marketing;

  return createPortal(
    <div
      className={styles.overlay}
      // 배경을 눌러 닫는다. mousedown 을 보는 이유는, 입력 칸에서 드래그하다
      // 배경에서 손을 떼는 경우까지 "닫기" 로 읽히는 걸 막기 위해서다.
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) requestClose("backdrop");
      }}
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={handleKeyDown}
      >
        <div className={styles.head}>
          <h2 id={titleId} className={styles.title}>
            {isLogin ? "로그인" : "회원가입"}
          </h2>
          <button
            type="button"
            className={styles.close}
            onClick={() => requestClose("button")}
            aria-label="닫기"
          >
            <span aria-hidden="true">×</span>
          </button>
        </div>

        <div className={styles.tabs} role="tablist" aria-label="로그인 또는 회원가입">
          <button
            type="button"
            role="tab"
            id={loginTabId}
            aria-selected={isLogin}
            aria-controls={panelId}
            className={`${styles.tab} ${isLogin ? styles.tabActive : ""}`}
            onClick={() => switchTab("login")}
          >
            로그인
          </button>
          <button
            type="button"
            role="tab"
            id={signupTabId}
            aria-selected={!isLogin}
            aria-controls={panelId}
            className={`${styles.tab} ${!isLogin ? styles.tabActive : ""}`}
            onClick={() => switchTab("signup")}
          >
            회원가입
          </button>
        </div>

        <form
          id={panelId}
          role="tabpanel"
          aria-labelledby={isLogin ? loginTabId : signupTabId}
          className={styles.form}
          onSubmit={handleSubmit}
          // 브라우저 기본 말풍선을 끄고 우리 문구로 통일한다.
          noValidate
        >
          <label className={styles.field}>
            <span className={styles.label}>이메일</span>
            <input
              ref={emailRef}
              className={styles.input}
              type="email"
              inputMode="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
            />
          </label>

          <label className={styles.field}>
            <span className={styles.label}>비밀번호</span>
            <input
              className={styles.input}
              type="password"
              autoComplete={isLogin ? "current-password" : "new-password"}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              aria-describedby={isLogin ? undefined : passwordHintId}
            />
            {!isLogin && (
              <span id={passwordHintId} className={styles.hint}>
                {PASSWORD_MIN}자 이상
              </span>
            )}
          </label>

          {!isLogin && (
            <>
              <label className={styles.field}>
                <span className={styles.label}>비밀번호 확인</span>
                <input
                  className={styles.input}
                  type="password"
                  autoComplete="new-password"
                  value={passwordConfirm}
                  onChange={(e) => setPasswordConfirm(e.target.value)}
                />
              </label>

              <fieldset className={styles.agreements}>
                <legend className={styles.srOnly}>약관 동의</legend>

                <label className={`${styles.agreeLabel} ${styles.agreeAll}`}>
                  <input type="checkbox" checked={allAgreed} onChange={toggleAll} />
                  <span>전체 동의</span>
                </label>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.age} onChange={setOne("age")} />
                    <span className={styles.tagRequired}>[필수]</span>
                    <span>만 14세 이상입니다</span>
                  </label>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.terms} onChange={setOne("terms")} />
                    <span className={styles.tagRequired}>[필수]</span>
                    <span>이용약관에 동의합니다</span>
                  </label>
                  {/* 링크를 label 밖에 두는 이유: 안에 있으면 "보기" 를 누른 것이
                      체크 토글로도 읽히는 브라우저가 있다. */}
                  <a
                    className={styles.agreeLink}
                    href={LEGAL_LINKS.terms}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    보기
                  </a>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input type="checkbox" checked={agree.privacy} onChange={setOne("privacy")} />
                    <span className={styles.tagRequired}>[필수]</span>
                    <span>개인정보 처리방침에 동의합니다</span>
                  </label>
                  <a
                    className={styles.agreeLink}
                    href={LEGAL_LINKS.privacy}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    보기
                  </a>
                </div>

                <div className={styles.agreeRow}>
                  <label className={styles.agreeLabel}>
                    <input
                      type="checkbox"
                      checked={agree.marketing}
                      onChange={setOne("marketing")}
                    />
                    <span className={styles.tagOptional}>[선택]</span>
                    <span>마케팅 정보 수신에 동의합니다</span>
                  </label>
                </div>
              </fieldset>
            </>
          )}

          {/* role="alert" — 오류가 생기면 화면 낭독기가 즉시 읽어 준다. */}
          {formError && (
            <p className={styles.error} role="alert">
              {formError}
            </p>
          )}

          <button type="submit" className={styles.submit} disabled={submitting}>
            {submitting ? "처리 중…" : isLogin ? "로그인" : "가입하고 시작하기"}
          </button>
        </form>
      </div>
    </div>,
    document.body,
  );
}
