"use client";

/**
 * 사용자 검증 설문의 문항(#471). 완성 직후 짧은 설문 · 마이페이지 전체 설문 · 관리자
 * 목록이 같은 문항을 쓴다. 질문의 뜻과 어느 가설을 재는지는
 * `webtoon/docs/validation.md` 「설문」 표에 있다.
 *
 * 서버는 기호와 허용 값만 안다(`WebtoonFeedbackQuestion.java`). 여기 선택지 값을 바꾸면
 * 서버도 같이 바꿔야 한다 — 아니면 답이 조용히 버려진다.
 *
 * 「아니오」를 고른 문항 몇 개는 그 아래에 이유를 더 묻는다(선택). 다른 답을 고르면 닫힌다.
 */
import { registerDict, useT } from "../lib/i18n";
import type { SurveyAnswers, SurveyKey, SurveyValue } from "../lib/api";
import "./Survey.css";

type Kind = "scale" | "choice" | "multi";
interface Question { text: string; kind: Kind; options?: [string, string][] }

const YPN: [string, string][] = [["yes", "예"], ["partly", "일부"], ["no", "아니오"]];

export const SURVEY: Record<SurveyKey, Question> = {
  S1: { text: "다 읽고 나서 \"이거 내 캐릭터 얘기 맞네\" 싶었나요?", kind: "scale" },
  S2: { text: "첫 장면부터 마지막 장면까지 같은 캐릭터로 보였나요?", kind: "scale" },
  S3: { text: "내가 넣은 설정(이름·세계관·특징)이 그대로 들어갔나요?", kind: "choice", options: YPN },
  S4: { text: "캐릭터가 내가 생각한 성격대로 말하고 행동했나요?", kind: "scale" },
  S5: { text: "내가 적은 이야기가 웹툰에 들어갔나요?", kind: "choice", options: YPN },
  S6: { text: "캐릭터와 상관없이, 1화 자체가 재미있었나요?", kind: "scale" },
  S7: { text: "이 캐릭터의 다음 이야기도 보고 싶나요?", kind: "choice", options: [["yes", "예"], ["no", "아니오"]] },
  S8: { text: "다시 만들어 볼 의향이 있으신가요?", kind: "choice", options: [["yes", "있어요"], ["maybe", "모르겠어요"], ["no", "없어요"]] },
  S10: {
    text: "추가로 어떤 기능이 있으면 좋을까요? 모두 골라 주세요.", kind: "multi",
    options: [
      ["multi_char", "웹툰에 캐릭터 여러 명 함께 넣기"],
      ["next_episode", "같은 캐릭터로 다음 화 이어 만들기"],
      ["scene_comic", "1화가 아니라 장면 하나만 넣으면 바로 만화로"],
      ["enough", "지금으로 충분해요"],
    ],
  },
};

/** 「다음 이야기가 안 궁금해요」의 이유. 서버 `S7_WHY` 와 같은 값. */
const S7_WHY: [string, string][] = [
  ["not_fun", "재미가 없었어요"],
  ["not_curious", "다음 내용이 궁금하지 않아요"],
  ["enough", "1화만으로 충분해요"],
];

/** 관리자 목록에서 기호 옆에 붙이는 짧은 이름. 「아니오」 뒤 추가 답도 여기서 이름을 받는다. */
export const SURVEY_SHORT: Record<string, string> = {
  S1: "내 캐릭터 얘기", S2: "같은 캐릭터", S3: "설정 그대로", S3_note: "달라진 점", S4: "성격대로",
  S5: "적은 이야기", S6: "1화 재미", S7: "다음 이야기", S7_why: "안 보고 싶은 이유", S7_note: "그 밖의 이유",
  S8: "다시 만들 의향", S10: "원하는 기능",
};

export const SURVEY_KEYS = Object.keys(SURVEY) as SurveyKey[];

/** 보상 표시 — 크레딧 수와 그걸로 무엇을 할 수 있는지. 팝업 · 마이페이지 설문 · 완성 직후가 같이 쓴다. */
export function RewardBadge({ amount }: { amount: number }) {
  const t = useT();
  return (
    <div className="wt-reward">
      <span className="wt-reward-amt">◈ {amount}<small>{t("크레딧")}</small></span>
      <span className="wt-reward-what">{t("웹툰 1편을 더 만들 수 있어요")}</span>
    </div>
  );
}

function Options({ items, value, onPick }: {
  items: [SurveyValue, string][];
  value: SurveyValue | undefined;
  onPick: (v: SurveyValue) => void;
}) {
  return (
    <>
      {items.map(([v, label]) => (
        <button type="button" key={String(v)} className={value === v ? "on" : ""}
                aria-pressed={value === v} onClick={() => onPick(v)}>{label}</button>
      ))}
    </>
  );
}

/**
 * 문항 하나. 답은 `answers` 에서 읽고 `set` 으로 바꾼다 — 「아니오」 뒤의 추가 답
 * (`S3_note` · `S7_why` · `S7_note`)도 같은 묶음에 들어간다.
 */
export function SurveyQuestion({ q, answers, set }: {
  q: SurveyKey;
  answers: SurveyAnswers;
  set: (key: string, v: SurveyValue | undefined) => void;
}) {
  const t = useT();
  const def = SURVEY[q];
  const value = answers[q];

  if (def.kind === "multi") {
    const picked = Array.isArray(value) ? value : [];
    const toggle = (v: string) => {
      if (v === "enough") return set(q, picked.includes(v) ? [] : [v]);
      const rest = picked.filter((x) => x !== "enough");
      set(q, rest.includes(v) ? rest.filter((x) => x !== v) : [...rest, v]);
    };
    return (
      <fieldset className="wt-survey-q">
        <legend>{t(def.text)}</legend>
        <div className="wt-survey-opts multi">
          {def.options!.map(([v, label]) => (
            <button type="button" key={v} className={picked.includes(v) ? "on" : ""}
                    aria-pressed={picked.includes(v)} onClick={() => toggle(v)}>
              {picked.includes(v) ? "✓ " : ""}{t(label)}
            </button>
          ))}
        </div>
      </fieldset>
    );
  }

  const items: [SurveyValue, string][] = def.kind === "scale"
    ? [1, 2, 3, 4, 5].map((n) => [n, String(n)])
    : def.options!.map(([v, label]) => [v, t(label)]);
  return (
    <fieldset className="wt-survey-q">
      <legend>{t(def.text)}</legend>
      <div className={`wt-survey-opts${def.kind === "scale" ? " scale" : ""}`}>
        <Options items={items} value={value} onPick={(v) => set(q, v)} />
      </div>
      {def.kind === "scale" && (
        <div className="wt-survey-ends"><span>{t("전혀 아니에요")}</span><span>{t("정말 그래요")}</span></div>
      )}
      {q === "S3" && value === "no" && (
        <div className="wt-survey-follow">
          <label>{t("어떤 점이 달라졌는지 알려 주시면 더 좋은 결과물로 보답할게요! (선택)")}</label>
          <textarea value={String(answers.S3_note ?? "")} maxLength={500}
                    onChange={(e) => set("S3_note", e.target.value)} />
        </div>
      )}
      {q === "S7" && value === "no" && (
        <div className="wt-survey-follow">
          <label>{t("어떤 이유였나요? (선택)")}</label>
          <div className="wt-survey-opts">
            <Options items={S7_WHY.map(([v, label]) => [v, t(label)])} value={answers.S7_why}
                     onPick={(v) => set("S7_why", answers.S7_why === v ? undefined : v)} />
          </div>
          <textarea value={String(answers.S7_note ?? "")} maxLength={500}
                    onChange={(e) => set("S7_note", e.target.value)} />
        </div>
      )}
    </fieldset>
  );
}

/** 관리자 목록에서 답 하나를 사람이 읽는 말로. */
export function answerLabel(key: string, v: SurveyValue | undefined, t: (s: string) => string): string {
  if (v === undefined) return "—";
  if (Array.isArray(v)) return v.map((x) => answerLabel(key, x, t)).join(", ");
  if (typeof v === "number") return String(v);
  const options = key === "S7_why" ? S7_WHY : (SURVEY as Record<string, Question>)[key]?.options;
  const hit = options?.find(([k]) => k === v);
  return hit ? t(hit[1]) : String(v);
}

/** 답 하나를 바꾼 새 묶음. 본 답이 「아니오」가 아니게 되면 그 아래 추가 답은 버린다. */
export function withAnswer(a: SurveyAnswers, key: string, v: SurveyValue | undefined): SurveyAnswers {
  const next: Record<string, SurveyValue> = { ...(a as Record<string, SurveyValue>) };
  if (v === undefined || v === "") delete next[key]; else next[key] = v;
  if (key === "S3" && v !== "no") delete next.S3_note;
  if (key === "S7" && v !== "no") { delete next.S7_why; delete next.S7_note; }
  return next as SurveyAnswers;
}

export function answeredAll(keys: SurveyKey[], answers: SurveyAnswers): boolean {
  return keys.every((k) => {
    const v = answers[k];
    return Array.isArray(v) ? v.length > 0 : v !== undefined;
  });
}

registerDict({
  [SURVEY.S1.text]: { en: "After reading, did it feel like \"yes, this is my character's story\"?", ja: "読み終えて「これは自分のキャラの話だ」と感じましたか？", zh: "读完后，你觉得“这就是我的角色的故事”吗？" },
  [SURVEY.S2.text]: { en: "Did the character look like the same person from the first scene to the last?", ja: "最初の場面から最後まで同じキャラに見えましたか？", zh: "从第一幕到最后一幕，看起来是同一个角色吗？" },
  [SURVEY.S3.text]: { en: "Did the settings you put in (name, world, traits) come through as-is?", ja: "入れた設定（名前・世界観・特徴）はそのまま入っていましたか？", zh: "你输入的设定（名字、世界观、特点）原样保留了吗？" },
  [SURVEY.S4.text]: { en: "Did the character speak and act like the personality you had in mind?", ja: "キャラは思っていた性格どおりに話し、行動しましたか？", zh: "角色的言行符合你设想的性格吗？" },
  [SURVEY.S5.text]: { en: "Did the story you wrote make it into the webtoon?", ja: "書いたストーリーはウェブトゥーンに入っていましたか？", zh: "你写的故事进入漫画了吗？" },
  [SURVEY.S6.text]: { en: "Regardless of the character, was the episode itself fun?", ja: "キャラとは関係なく、1話そのものは面白かったですか？", zh: "不考虑角色，这一话本身有趣吗？" },
  [SURVEY.S7.text]: { en: "Would you like to see this character's next story?", ja: "このキャラの次の話も見たいですか？", zh: "想看这个角色的下一个故事吗？" },
  [SURVEY.S8.text]: { en: "Would you make another one?", ja: "また作ってみたいですか？", zh: "还想再做一个吗？" },
  [SURVEY.S10.text]: { en: "What other features would you like? Pick all that apply.", ja: "ほかにどんな機能があるといいですか？当てはまるものをすべて選んでください。", zh: "还希望有哪些功能？请全部勾选。" },
  "예": { en: "Yes", ja: "はい", zh: "是" },
  "일부": { en: "Partly", ja: "一部", zh: "部分" },
  "아니오": { en: "No", ja: "いいえ", zh: "否" },
  "있어요": { en: "Yes", ja: "あります", zh: "有" },
  "모르겠어요": { en: "Not sure", ja: "わかりません", zh: "不确定" },
  "없어요": { en: "No", ja: "ありません", zh: "没有" },
  "웹툰에 캐릭터 여러 명 함께 넣기": { en: "Put several characters in one webtoon", ja: "1つのウェブトゥーンに複数のキャラを入れる", zh: "在一部漫画中放入多个角色" },
  "같은 캐릭터로 다음 화 이어 만들기": { en: "Make the next episode with the same character", ja: "同じキャラで次の話を続けて作る", zh: "用同一个角色接着做下一话" },
  "1화가 아니라 장면 하나만 넣으면 바로 만화로": { en: "Turn a single scene into a comic right away, not a whole episode", ja: "1話ではなく、場面ひとつを入れるだけですぐ漫画に", zh: "不做整话，只输入一个场景就直接变成漫画" },
  "지금으로 충분해요": { en: "It's fine as it is", ja: "今のままで十分", zh: "现在就够了" },
  "어떤 점이 달라졌는지 알려 주시면 더 좋은 결과물로 보답할게요! (선택)": { en: "Tell us what came out differently and we'll make it better! (optional)", ja: "どこが違っていたか教えていただければ、もっと良い仕上がりでお返しします！（任意）", zh: "告诉我们哪里不一样，我们会做出更好的作品！（可选）" },
  "어떤 이유였나요? (선택)": { en: "Why not? (optional)", ja: "理由を教えてください（任意）", zh: "是什么原因呢？（可选）" },
  "재미가 없었어요": { en: "It wasn't fun", ja: "面白くなかった", zh: "不好玩" },
  "다음 내용이 궁금하지 않아요": { en: "I'm not curious what happens next", ja: "続きが気にならない", zh: "不好奇后续" },
  "1화만으로 충분해요": { en: "One episode is enough", ja: "1話だけで十分", zh: "一话就够了" },
  "크레딧": { en: "credits", ja: "クレジット", zh: "积分" },
  "웹툰 1편을 더 만들 수 있어요": { en: "Enough for one more webtoon", ja: "ウェブトゥーンをもう1話作れます", zh: "可以再做一部漫画" },
  "전혀 아니에요": { en: "Not at all", ja: "まったく違う", zh: "完全不是" },
  "정말 그래요": { en: "Totally", ja: "まさにそう", zh: "完全是" },
});
