import { GENRE_NOTE, GENRE_NOTE_EMPTY, GENRE_QUICK, type WizardForm } from "../../../lib/wizardData";

/* 3 · 깊은 바다 — 장르. 고른 것을 다시 누르면 풀린다. */
export default function Step3Genre({
  form,
  onChange,
}: {
  form: WizardForm;
  onChange: (patch: Partial<WizardForm>) => void;
}) {
  const now = form.genre.trim();
  const note = now ? (GENRE_NOTE[now] ?? `「${now}」 그대로 갑니다 — 목록에 없는 장르도 루가 낱말을 보고 맞춥니다.`) : GENRE_NOTE_EMPTY;
  const noteState = now ? (GENRE_NOTE[now] ? "known" : "custom") : "empty";

  return (
    <section className="wiz-step" data-step="3">
      <p className="wiz-eyebrow">깊은 바다 · STEP 3 / 5</p>
      <h3 className="wiz-title">
        어떤 장르로
        <br />
        그릴까요?
      </h3>
      <p className="wiz-sub">안 고르면 루가 이야기에 맞춰 정합니다.</p>

      <div className="wiz-card">
        <div className="genre-quick">
          {GENRE_QUICK.map((g) => (
            <button
              key={g}
              type="button"
              aria-pressed={now === g}
              onClick={() => onChange({ genre: now === g ? "" : g })}
            >
              {g}
            </button>
          ))}
        </div>
        <label className="field">
          <span>
            직접 쓰기 <small>선택</small>
          </span>
          <input
            type="text"
            list="genreList"
            placeholder="예: 무협 / 로맨스 판타지"
            value={form.genre}
            onChange={(e) => onChange({ genre: e.target.value })}
          />
          <datalist id="genreList">
            {GENRE_QUICK.map((g) => (
              <option value={g} key={g} />
            ))}
          </datalist>
        </label>
        <p className="genre-note" data-state={noteState}>
          {note}
        </p>
      </div>
    </section>
  );
}
