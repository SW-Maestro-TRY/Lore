"use client";

/**
 * 사용자 검증 설문의 문항(#471). 완성 직후 짧은 설문 · 마이페이지 전체 설문 · 관리자
 * 목록이 같은 문항을 쓴다. 질문의 뜻과 어느 가설을 재는지는
 * `webtoon/docs/validation.md` 「설문」 표에 있다.
 *
 * 서버는 기호(S1~S9)와 허용 값만 안다(`WebtoonFeedbackQuestion.java`). 여기 선택지 값을 바꾸면
 * 서버도 같이 바꿔야 한다 — 아니면 답이 조용히 버려진다.
 */
import { registerDict, useT } from "../lib/i18n";
import type { SurveyAnswers, SurveyKey, SurveyValue } from "../lib/api";
import "./Survey.css";

type Kind = "scale" | "choice" | "multi";
interface Question { text: string; kind: Kind; options?: [string, string][]; short?: string }

const YPN: [string, string][] = [["yes", "예"], ["partly", "일부"], ["no", "아니오"]];

export const SURVEY: Record<SurveyKey, Question> = {
  S1: { text: "다 읽고 나서 \"이거 내 캐릭터 얘기 맞네\" 싶었나요?", kind: "scale" },
  S2: { text: "첫 장면부터 마지막 장면까지 같은 캐릭터로 보였나요?", kind: "scale" },
  S3: { text: "내가 넣은 설정(이름·세계관·특징)이 그대로 들어갔나요?", kind: "choice", options: YPN },
  S4: { text: "캐릭터가 내가 생각한 성격대로 말하고 행동했나요?", kind: "scale" },
  S5: { text: "내가 적은 이야기가 웹툰에 들어갔나요?", kind: "choice", options: YPN },
  S6: { text: "캐릭터와 상관없이, 1화 자체가 재미있었나요?", kind: "scale" },
  S7: { text: "이 캐릭터의 다음 이야기도 보고 싶나요?", kind: "choice", options: [["yes", "예"], ["no", "아니오"]] },
  S8: { text: "다시 만들어 볼 건가요?", kind: "choice", options: [["yes", "예"], ["maybe", "아마도"], ["no", "아니오"]] },
  S9: {
    text: "기대와 가장 달랐던 곳은?", kind: "choice",
    options: [["look", "캐릭터 모습"], ["persona", "성격"], ["story", "이야기"], ["art", "그림"], ["wait", "기다린 시간"], ["none", "없음"]],
  },
  S10: {
    text: "이런 기능이 있으면 좋겠나요? 모두 골라 주세요.", kind: "multi",
    options: [
      ["multi_char", "웹툰에 캐릭터 여러 명 함께 넣기"],
      ["next_episode", "같은 캐릭터로 다음 화 이어 만들기"],
      ["scene_comic", "1화가 아니라 장면 하나만 넣으면 바로 만화로"],
      ["enough", "지금으로 충분해요"],
    ],
  },
};

/** 관리자 목록에서 기호 옆에 붙이는 짧은 이름. */
export const SURVEY_SHORT: Record<SurveyKey, string> = {
  S1: "내 캐릭터 얘기", S2: "같은 캐릭터", S3: "설정 그대로", S4: "성격대로", S5: "적은 이야기",
  S6: "1화 재미", S7: "다음 이야기", S8: "다시 만들기", S9: "기대와 다른 곳", S10: "원하는 기능",
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

export function SurveyQuestion({ q, value, onChange }: {
  q: SurveyKey;
  value: SurveyValue | undefined;
  onChange: (v: SurveyValue) => void;
}) {
  const t = useT();
  const def = SURVEY[q];
  const choices: [number | string, string][] = def.kind === "scale"
    ? [1, 2, 3, 4, 5].map((n) => [n, String(n)])
    : def.options!.map(([v, label]) => [v, t(label)]);
  if (def.kind === "multi") {
    const picked = Array.isArray(value) ? value : [];
    const toggle = (v: string) => {
      if (v === "enough") return onChange(picked.includes(v) ? [] : [v]);
      const rest = picked.filter((x) => x !== "enough");
      onChange(rest.includes(v) ? rest.filter((x) => x !== v) : [...rest, v]);
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
  return (
    <fieldset className="wt-survey-q">
      <legend>{t(def.text)}</legend>
      <div className={`wt-survey-opts${def.kind === "scale" ? " scale" : ""}`}>
        {choices.map(([v, label]) => (
          <button type="button" key={String(v)} className={value === v ? "on" : ""}
                  aria-pressed={value === v} onClick={() => onChange(v)}>{label}</button>
        ))}
      </div>
      {def.kind === "scale" && (
        <div className="wt-survey-ends"><span>{t("전혀 아니에요")}</span><span>{t("정말 그래요")}</span></div>
      )}
    </fieldset>
  );
}

/** 관리자 목록에서 답 한 줄을 사람이 읽는 말로. */
export function answerLabel(q: SurveyKey, v: SurveyValue | undefined, t: (s: string) => string): string {
  if (v === undefined) return "—";
  if (Array.isArray(v)) return v.map((x) => answerLabel(q, x, t)).join(", ");
  if (typeof v === "number") return String(v);
  const hit = SURVEY[q].options?.find(([key]) => key === v);
  return hit ? t(hit[1]) : String(v);
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
  [SURVEY.S8.text]: { en: "Will you make another one?", ja: "また作ってみますか？", zh: "还会再做一个吗？" },
  [SURVEY.S9.text]: { en: "What was most different from what you expected?", ja: "期待といちばん違ったところは？", zh: "和期待最不一样的是哪里？" },
  "예": { en: "Yes", ja: "はい", zh: "是" },
  "일부": { en: "Partly", ja: "一部", zh: "部分" },
  "아니오": { en: "No", ja: "いいえ", zh: "否" },
  "아마도": { en: "Maybe", ja: "たぶん", zh: "也许" },
  "캐릭터 모습": { en: "Character's look", ja: "キャラの見た目", zh: "角色外观" },
  "성격": { en: "Personality", ja: "性格", zh: "性格" },
  "이야기": { en: "Story", ja: "ストーリー", zh: "故事" },
  "그림": { en: "Art", ja: "絵", zh: "画面" },
  "기다린 시간": { en: "Waiting time", ja: "待ち時間", zh: "等待时间" },
  "없음": { en: "None", ja: "なし", zh: "没有" },
  "크레딧": { en: "credits", ja: "クレジット", zh: "积分" },
  "웹툰 1편을 더 만들 수 있어요": { en: "Enough for one more webtoon", ja: "ウェブトゥーンをもう1話作れます", zh: "可以再做一部漫画" },
  [SURVEY.S10.text]: { en: "Which of these features would you like? Pick all that apply.", ja: "こんな機能があったらいいですか？当てはまるものをすべて選んでください。", zh: "希望有哪些功能？请全部勾选。" },
  "웹툰에 캐릭터 여러 명 함께 넣기": { en: "Put several characters in one webtoon", ja: "1つのウェブトゥーンに複数のキャラを入れる", zh: "在一部漫画中放入多个角色" },
  "같은 캐릭터로 다음 화 이어 만들기": { en: "Make the next episode with the same character", ja: "同じキャラで次の話を続けて作る", zh: "用同一个角色接着做下一话" },
  "1화가 아니라 장면 하나만 넣으면 바로 만화로": { en: "Turn a single scene into a comic right away, not a whole episode", ja: "1話ではなく、場面ひとつを入れるだけですぐ漫画に", zh: "不做整话，只输入一个场景就直接变成漫画" },
  "지금으로 충분해요": { en: "It's fine as it is", ja: "今のままで十分", zh: "现在就够了" },
  "전혀 아니에요": { en: "Not at all", ja: "まったく違う", zh: "完全不是" },
  "정말 그래요": { en: "Totally", ja: "まさにそう", zh: "完全是" },
});
