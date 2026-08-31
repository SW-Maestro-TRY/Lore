"use client";

import { useState } from "react";
import wizLouImg from "../../assets/logo-2-curious.png";
import config from "../../demo-api/config.json";
import { WIZ_LAST, WIZ_NAMES, emptyWizardForm, type WizardForm } from "../../lib/wizardData";
import Step1Photo from "./steps/Step1Photo";
import Step2Story from "./steps/Step2Story";
import Step3Genre from "./steps/Step3Genre";
import Step4Style from "./steps/Step4Style";
import Step5Review from "./steps/Step5Review";

/* 만들기 화면 — haeun/landing 의 5걸음 위자드.
 *
 * 원본(app.js 의 wizGo/setupWizard)은 DOM 을 직접 감췄다 켰다 하지만, 여기는
 * React 상태(step)로 어느 <section> 을 보여줄지 정한다. 동작은 최대한
 * 그대로 옮겼다 — 1걸음(사진·이름) 필수 검사, 「이전」이 1걸음에서는 홈으로
 * 나가는 것, 갈림길(5걸음)에서 기본값이 "빠르게" 인 것 등.
 *
 * 실제 생성(/api/create)은 아직 연결 전이다 — 백엔드가 없다. 제출을 누르면
 * onSubmit 으로 폼 값을 올려 보내고, WebtoonPage 가 진행 화면(Progress)으로
 * 넘긴다 — 그 화면도 실제 파이프라인 상태가 아니라 로컬 타이머로 흉내 낸
 * 것이다(Progress/useFakeProgress 참고). */
export default function Wizard({
  onClose,
  onSubmit,
}: {
  onClose: () => void;
  onSubmit: (form: WizardForm) => void;
}) {
  const [step, setStep] = useState(1);
  const [form, setForm] = useState<WizardForm>(emptyWizardForm());
  const [note, setNote] = useState("사진과 이름만 있으면 시작합니다.");
  const [noteError, setNoteError] = useState(false);

  const patch = (p: Partial<WizardForm>) => setForm((f) => ({ ...f, ...p }));

  const step1Ok = () => {
    if (!form.photos.length) {
      setNote("캐릭터 사진을 올려주세요");
      setNoteError(true);
      return false;
    }
    if (!form.name.trim()) {
      setNote("이름을 적어주세요");
      setNoteError(true);
      return false;
    }
    return true;
  };

  const goNext = () => {
    if (step === 1 && !step1Ok()) return;
    setNote("사진과 이름만 있으면 시작합니다.");
    setNoteError(false);
    setStep((s) => Math.min(WIZ_LAST, s + 1));
  };

  const goPrev = () => {
    if (step === 1) {
      onClose();
      return;
    }
    setStep((s) => Math.max(1, s - 1));
  };

  const atEnd = step === WIZ_LAST;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.agreeIp) {
      setNote("저작권 확인에 동의해야 만들 수 있습니다");
      setNoteError(true);
      return;
    }
    // TODO: /api/create 연결 — 지금은 백엔드가 없어서 진행 화면은 로컬로
    // 흉내 낸 상태(Progress/useFakeProgress)를 보여준다.
    onSubmit(form);
  };

  return (
    <section className="create">
      <div className="studio">
        <form className="wizard" onSubmit={handleSubmit}>
          <div className="wiz-head">
            <button type="button" className="wiz-back" aria-label="이전 걸음" onClick={goPrev}>
              <svg viewBox="0 0 24 24" width={22} height={22} fill="none" stroke="currentColor" strokeWidth={1.9} strokeLinecap="round" strokeLinejoin="round">
                <path d="M15 18l-6-6 6-6" />
              </svg>
            </button>
            <ol className="wiz-gauge" aria-label="진행">
              {WIZ_NAMES.map((name, i) => {
                const n = i + 1;
                const state = n < step ? "done" : n === step ? "on" : "";
                return <li key={name} className={`wiz-tick ${state}`} title={`${n}. ${name}`} />;
              })}
            </ol>
            <button type="button" className="wiz-skip" hidden={atEnd} onClick={goNext}>
              건너뛰기
            </button>
          </div>

          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="wiz-lou" src={wizLouImg.src} alt="" aria-hidden="true" />

          {step === 1 && <Step1Photo form={form} onChange={patch} />}
          {step === 2 && <Step2Story form={form} onChange={patch} />}
          {step === 3 && <Step3Genre form={form} onChange={patch} />}
          {step === 4 && <Step4Style form={form} onChange={patch} />}
          {step === 5 && <Step5Review form={form} onChange={patch} />}

          <div className="wiz-actions">
            <div className="wiz-foot">
              <button type="button" className="btn btn-quiet wiz-prev" hidden={step === 1} onClick={goPrev}>
                이전
              </button>
              {!atEnd && (
                <button type="button" className="btn btn-primary wiz-next" onClick={goNext}>
                  다음
                </button>
              )}
              {atEnd && (
                <button type="submit" className="btn btn-primary wiz-go">
                  웹툰 만들기 <span className="cost-chip">−{config.credit_cost.full}크레딧</span>
                </button>
              )}
            </div>
            <p className={`submit-note${noteError ? " is-error" : ""}`}>{note}</p>
          </div>
        </form>
      </div>
    </section>
  );
}
