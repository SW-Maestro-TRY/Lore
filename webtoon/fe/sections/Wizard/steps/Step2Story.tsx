import type { WizardForm } from "../../../lib/wizardData";

/* 2 · 항해 — 어떤 이야기를 만들까요. */
export default function Step2Story({
  form,
  onChange,
}: {
  form: WizardForm;
  onChange: (patch: Partial<WizardForm>) => void;
}) {
  return (
    <section className="wiz-step" data-step="2">
      <p className="wiz-eyebrow">항해 · STEP 2 / 5</p>
      <h3 className="wiz-title">
        어떤 이야기를
        <br />
        만들까요?
      </h3>
      <p className="wiz-sub">비우면 루가 캐릭터를 보고 짭니다.</p>

      <div className="wiz-card">
        <label className="field big">
          <span className="sr-only">이야기</span>
          <textarea
            rows={5}
            placeholder={"한 줄이어도 되고 줄거리여도 됩니다.\n예) 평범한 회사원인데 로맨스 판타지 소설에 빙의했다"}
            value={form.story}
            onChange={(e) => onChange({ story: e.target.value })}
          />
        </label>
      </div>
    </section>
  );
}
