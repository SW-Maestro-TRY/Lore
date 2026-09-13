import {
  QUALITY_INFO, STYLE_INFO,
  type WizardForm, type WizardMode, type WizardQuality,
} from "../../../lib/wizardData";

const cut = (v: string, n: number) => {
  const t = v.trim();
  if (!t) return null;
  return t.length > n ? `${t.slice(0, n)}…` : t;
};

/* 5 · 바닥 — 갈림길(빠르게/전문 모드) + 요약 + 저작권 확인.
 * 원본은 갈림길을 고르는 순간 요약이 열린다 — 여기서도 같다(항상 렌더링,
 * form.mode 로 어느 카드가 눌렸는지만 표시). */
export default function Step5Review({
  form,
  onChange,
  qualities,
}: {
  form: WizardForm;
  onChange: (patch: Partial<WizardForm>) => void;
  /** 화질별 크레딧. **서버가 정한다**(WebtoonQuality) — 못 받아 오면 값을
   *  안 그린다. 틀린 값을 적느니 안 적는 게 낫다. */
  qualities?: { key: string; label: string; credits: number }[];
}) {
  const styleLabel = STYLE_INFO.find(([key]) => key === form.style)?.[1];
  const auto = <i className="wiz-auto">루가 정합니다</i>;

  const creditsOf = (key: string) => qualities?.find((q) => q.key === key)?.credits;
  const chosen = QUALITY_INFO.find((q) => q.key === form.quality) ?? QUALITY_INFO[1];
  const chosenCredits = creditsOf(chosen.key);
  const qualityRow = chosenCredits == null
    ? chosen.label
    : `${chosen.label} · ${chosenCredits}크레딧`;

  const rows: [string, React.ReactNode][] = [
    ["캐릭터", form.name.trim() || auto],
    ["사진", form.photos.length ? `${form.photos.length}장` : <i className="wiz-auto">없음</i>],
    ["설명", cut(form.character, 34) ?? auto],
    ["이야기", cut(form.story, 34) ?? auto],
    ["장르", form.genre.trim() || auto],
    ["그림체", styleLabel ?? auto],
    ["그림 밀도", qualityRow],
    ["보는 방식", form.mode === "expert" ? "2번 확인하며" : "빠르게 결과부터"],
  ];

  const pick = (mode: WizardMode) => onChange({ mode });
  const pickQuality = (quality: WizardQuality) => onChange({ quality });

  return (
    <section className="wiz-step wiz-step-fork" data-step="5">
      {/* 걸음마다 "말"(눈금·제목·안내)과 "손이 닿는 것"(입력 카드)을
          한 겹으로 묶어 둔다. 폰에서는 위아래로 그냥 흐르고, PC 에서는
          이 덩어리가 왼쪽 칸으로 간다(webtoon.css 의 .wiz-say). */}
      <div className="wiz-say">
        <p className="wiz-eyebrow">바닥 · STEP 5 / 5</p>
        <h3 className="wiz-title">어떻게 그릴까요?</h3>
      </div>

      {/* 얼마나 촘촘히 — 시간과 크레딧이 같이 움직인다.
          시간은 **줄이 비었을 때** 기준이라 "약" 을 붙인다(만들기는 한 번에
          한 편씩 돈다 — 앞에 사람이 있으면 그만큼 더 걸린다). */}
      <div className="fork fork-3">
        {QUALITY_INFO.map((q) => {
          const credits = creditsOf(q.key);
          return (
            <button
              type="button"
              key={q.key}
              className="fork-card"
              aria-pressed={form.quality === q.key}
              onClick={() => pickQuality(q.key)}
            >
              <h4>{q.label}</h4>
              <p className="fork-meta">
                {q.lede}
                {credits != null && <span className="fork-credit">{credits}크레딧</span>}
              </p>
              <p className="fork-lede">
                {q.desc.map((line, i) => (
                  <span key={line}>
                    {i > 0 && <br />}
                    {line}
                  </span>
                ))}
              </p>
            </button>
          );
        })}
      </div>

      <p className="wiz-subtitle">그리고 어떻게 볼까요?</p>

      <div className="fork">
        <button
          type="button"
          className="fork-card"
          aria-pressed={form.mode === "simple"}
          onClick={() => pick("simple")}
        >
          <h4>빠르게 결과부터</h4>
          <p className="fork-lede">
            루가 알아서 그려 옵니다.
            <br />
            중간에 안 멈춥니다.
          </p>
        </button>
        <button
          type="button"
          className="fork-card"
          aria-pressed={form.mode === "expert"}
          onClick={() => pick("expert")}
        >
          {/* 멈추는 자리는 **둘**이다 — 캐릭터 시트와 이야기. 한때 콘티가 그
              사이에 있어서 셋이었는데, 콘티 단계가 없어진 뒤로도 문구가
              "3번만 · 이야기 · 콘티 · 그림" 그대로 남아 있었다. */}
          <h4>2번 확인하며</h4>
          <p className="fork-lede">
            캐릭터 시트와 이야기,
            <br />
            두 곳에서 보고 넘어갑니다.
          </p>
        </button>
      </div>

      <div className="wiz-after">
        <div className="wiz-card wiz-summary">
          {rows.map(([k, v]) => (
            <div className="wiz-row" key={k}>
              <span>{k}</span>
              <b>{v}</b>
            </div>
          ))}
        </div>
      </div>

      <div className="ip-agree">
        <label className="agree-line">
          <input
            type="checkbox"
            checked={form.agreeIp}
            onChange={(e) => onChange({ agreeIp: e.target.checked })}
            required
          />
          <span>
            업로드한 사진·설정에 대한 저작권 문제가 없음을 확인합니다 <em className="req">필수</em>
          </span>
        </label>
        <details className="terms-box">
          <summary>이용약관 요약 보기</summary>
          <ul>
            <li>업로드한 사진·설정은 본인이 저작권을 가지고 있거나 사용 권한이 있는 것이어야 합니다.</li>
            <li>다른 사람의 캐릭터·작품을 무단으로 써서 문제가 생기면 책임은 올린 사람에게 있습니다.</li>
            <li>LORE는 만들어진 결과물의 저작권 분쟁에 대해 책임지지 않습니다.</li>
          </ul>
        </details>
      </div>
    </section>
  );
}
