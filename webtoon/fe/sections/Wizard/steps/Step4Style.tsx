import { STYLE_INFO, type WizardForm } from "../../../lib/wizardData";
import { STYLE_THUMB } from "../../../lib/styleThumbs";

/* 4 · 심해 — 그림체. 라디오를 다시 누르면 풀린다(원본의 "이미 켜진 걸
 * 다시 누르면 끈다" 패턴 — 여긴 React 상태라 그냥 토글로 옮겼다). */
export default function Step4Style({
  form,
  onChange,
}: {
  form: WizardForm;
  onChange: (patch: Partial<WizardForm>) => void;
}) {
  return (
    <section className="wiz-step" data-step="4">
      <p className="wiz-eyebrow">심해 · STEP 4 / 5</p>
      <h3 className="wiz-title">
        어떤 그림체로
        <br />
        그릴까요?
      </h3>

      <div className="wiz-card">
        <div className="styles">
          {STYLE_INFO.map(([key, label, desc]) => (
            <label className="style-opt" key={key}>
              <input
                type="radio"
                name="style"
                value={key}
                checked={form.style === key}
                onClick={() => {
                  if (form.style === key) onChange({ style: "" });
                }}
                onChange={() => onChange({ style: key })}
              />
              <span className="style-box">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img className="style-thumb" src={STYLE_THUMB[key]} alt="" />
                <b>{label}</b>
                <small>{desc}</small>
              </span>
            </label>
          ))}
        </div>
      </div>
    </section>
  );
}
