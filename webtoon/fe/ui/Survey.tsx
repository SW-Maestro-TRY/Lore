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
/** ends — 1~5 양끝에 붙는 말. 없으면 「전혀 아니에요 · 정말 그래요」 */
interface Question { text: string; kind: Kind; options?: [string, string][]; ends?: [string, string] }

const YPN: [string, string][] = [["yes", "예"], ["partly", "일부"], ["no", "아니오"]];

export const SURVEY: Record<SurveyKey, Question> = {
  S0: { text: "LORE 서비스, 전체적으로 만족하셨나요?", kind: "scale", ends: ["전혀 만족하지 않아요", "정말 만족해요"] },
  S1: { text: "다 읽고 나서 \"이거 내 캐릭터 이야기 맞네\"라는 생각이 들었나요?", kind: "scale" },
  S2: { text: "첫 장면부터 마지막 장면까지 같은 캐릭터로 보였나요?", kind: "scale" },
  S3: { text: "내가 넣은 설정이 웹툰에 잘 반영되었나요?", kind: "choice", options: YPN },
  S4: { text: "캐릭터가 내가 생각한 성격대로 말하고 행동했나요?", kind: "scale" },
  S5: { text: "내가 적은 이야기가 웹툰에 잘 반영되었나요?", kind: "choice", options: YPN },
  S6: { text: "캐릭터와 상관없이, 1화 자체가 재미있었나요?", kind: "scale", ends: ["전혀 재미없어요", "정말 재미있어요"] },
  S7: { text: "이 캐릭터의 다음 이야기도 보고 싶나요?", kind: "choice", options: [["yes", "예"], ["no", "아니오"]] },
  S8: { text: "앞으로도 다시 웹툰을 만들어 볼 의향이 있으신가요?", kind: "choice", options: [["yes", "있어요"], ["maybe", "잘 모르겠어요"], ["no", "없어요"]] },
  S10: {
    text: "추가로 어떤 기능이 있으면 좋을까요? 있다면 모두 골라 주세요. (선택)", kind: "multi",
    options: [
      ["multi_char", "여러 캐릭터 함께 넣기"],
      ["next_episode", "다음 화 이어 만들기"],
      ["cut_image", "컷마다 그림 따로 그리기"],
      ["trailer_share", "완성된 웹툰을 숏츠로 만들기"],
      ["character_lend", "내 캐릭터 공개 기능"],
      ["community", "커뮤니티 기능"],
      ["style_add", "그림체 생성 및 추가 기능"],
      ["lorebook", "로어북 기능"],
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
  S0: "만족도", S1: "내 캐릭터 얘기", S2: "같은 캐릭터", S3: "설정 그대로", S3_note: "달라진 점", S4: "성격대로",
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
    const toggle = (v: string) => set(q, picked.includes(v) ? picked.filter((x) => x !== v) : [...picked, v]);
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
        <div className="wt-survey-ends"><span>{t(def.ends?.[0] ?? "전혀 아니에요")}</span><span>{t(def.ends?.[1] ?? "정말 그래요")}</span></div>
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
          <label>{t("왜 다음 이야기를 보고 싶지 않았나요? (선택)")}</label>
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

/** 설문 끝의 자유 의견 한 칸. 짧은 설문 · 마이페이지 설문이 같이 쓴다. */
export function FreeNote({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const t = useT();
  return (
    <label className="wt-survey-note">
      <b>{t("더 하고 싶은 말이 있다면 자유롭게 남겨 주세요.")}</b>
      <textarea value={value} maxLength={2000} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

/** 설문에서 뺀 S10 후보. 예전에 받은 답을 관리자 목록에서 읽을 수 있게 이름만 남긴다. */
const S10_RETIRED: [string, string][] = [
  ["my_style", "내가 넣은 그림체 그대로 웹툰 만들기"],
  ["scene_comic", "1화가 아니라 장면 하나만 넣으면 바로 만화로"],
  ["script_cut_edit", "대사·컷 구성을 내가 직접 설계하기"],
  ["enough", "지금으로 충분해요"],
];

/** 답하지 않아도 되는 문항. 원하는 기능이 없는 사람도 있다. 서버 `WebtoonFeedbackService.OPTIONAL` 과 같다. */
export const OPTIONAL: SurveyKey[] = ["S10"];

/** 관리자 목록에서 답 하나를 사람이 읽는 말로. */
export function answerLabel(key: string, v: SurveyValue | undefined, t: (s: string) => string): string {
  if (v === undefined) return "—";
  if (Array.isArray(v)) return v.map((x) => answerLabel(key, x, t)).join(", ");
  if (typeof v === "number") return String(v);
  const options = key === "S7_why" ? S7_WHY
    : key === "S10" ? [...SURVEY.S10.options!, ...S10_RETIRED]
    : (SURVEY as Record<string, Question>)[key]?.options;
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
  return keys.filter((k) => !OPTIONAL.includes(k)).every((k) => {
    const v = answers[k];
    return Array.isArray(v) ? v.length > 0 : v !== undefined;
  });
}

registerDict({
  [SURVEY.S0.text]: { en: "Overall, how satisfied are you with LORE?", ja: "LOREのサービスに全体的に満足しましたか？", zh: "总体来说，你对 LORE 的服务满意吗？" },
  "더 하고 싶은 말이 있다면 자유롭게 남겨 주세요.": { en: "Anything else you'd like to tell us?", ja: "ほかに伝えたいことがあれば、自由にお書きください。", zh: "还有想说的，请随意留言。" },
  "전혀 만족하지 않아요": { en: "Not satisfied at all", ja: "まったく満足していない", zh: "完全不满意" },
  "정말 만족해요": { en: "Very satisfied", ja: "とても満足", zh: "非常满意" },
  "전혀 재미없어요": { en: "Not fun at all", ja: "まったく面白くない", zh: "完全没意思" },
  "정말 재미있어요": { en: "Really fun", ja: "とても面白い", zh: "非常有趣" },
  "잘 모르겠어요": { en: "Not sure", ja: "よくわかりません", zh: "不太确定" },
  "왜 다음 이야기를 보고 싶지 않았나요? (선택)": { en: "Why not? (optional)", ja: "次の話を見たくない理由は？（任意）", zh: "为什么不想看下一个故事？（可选）" },
  [SURVEY.S1.text]: { en: "After reading, did you think \"yes, this is my character's story\"?", ja: "読み終えて「これは自分のキャラの話だ」と思いましたか？", zh: "读完后，你觉得“这就是我的角色的故事”吗？" },
  [SURVEY.S2.text]: { en: "Did the character look like the same person from the first scene to the last?", ja: "最初の場面から最後まで同じキャラに見えましたか？", zh: "从第一幕到最后一幕，看起来是同一个角色吗？" },
  [SURVEY.S3.text]: { en: "Were the settings you put in reflected well in the webtoon?", ja: "入れた設定はウェブトゥーンにしっかり反映されましたか？", zh: "你输入的设定在漫画中体现得好吗？" },
  [SURVEY.S4.text]: { en: "Did the character speak and act like the personality you had in mind?", ja: "キャラは思っていた性格どおりに話し、行動しましたか？", zh: "角色的言行符合你设想的性格吗？" },
  [SURVEY.S5.text]: { en: "Was the story you wrote reflected well in the webtoon?", ja: "書いたストーリーはウェブトゥーンにしっかり反映されましたか？", zh: "你写的故事在漫画中体现得好吗？" },
  [SURVEY.S6.text]: { en: "Regardless of the character, was the episode itself fun?", ja: "キャラとは関係なく、1話そのものは面白かったですか？", zh: "不考虑角色，这一话本身有趣吗？" },
  [SURVEY.S7.text]: { en: "Would you like to see this character's next story?", ja: "このキャラの次の話も見たいですか？", zh: "想看这个角色的下一个故事吗？" },
  [SURVEY.S8.text]: { en: "Would you make another webtoon in the future?", ja: "これからもまたウェブトゥーンを作ってみたいですか？", zh: "以后还想再做漫画吗？" },
  [SURVEY.S10.text]: { en: "What other features would you like? Pick any that apply. (optional)", ja: "ほかにどんな機能があるといいですか？あればすべて選んでください。（任意）", zh: "还希望有哪些功能？如果有请全部勾选。（可选）" },
  "예": { en: "Yes", ja: "はい", zh: "是" },
  "일부": { en: "Partly", ja: "一部", zh: "部分" },
  "아니오": { en: "No", ja: "いいえ", zh: "否" },
  "있어요": { en: "Yes", ja: "あります", zh: "有" },
  "모르겠어요": { en: "Not sure", ja: "わかりません", zh: "不确定" },
  "없어요": { en: "No", ja: "ありません", zh: "没有" },
  "웹툰에 캐릭터 여러 명 함께 넣기": { en: "Put several characters in one webtoon", ja: "1つのウェブトゥーンに複数のキャラを入れる", zh: "在一部漫画中放入多个角色" },
  "같은 캐릭터로 다음 화 이어 만들기": { en: "Make the next episode with the same character", ja: "同じキャラで次の話を続けて作る", zh: "用同一个角色接着做下一话" },
  "1화가 아니라 장면 하나만 넣으면 바로 만화로": { en: "Turn a single scene into a comic right away, not a whole episode", ja: "1話ではなく、場面ひとつを入れるだけですぐ漫画に", zh: "不做整话，只输入一个场景就直接变成漫画" },
  "여러 캐릭터 함께 넣기": { en: "Put several characters together", ja: "複数のキャラを一緒に入れる", zh: "多个角色一起出场" },
  "다음 화 이어 만들기": { en: "Continue with the next episode", ja: "次の話を続けて作る", zh: "接着做下一话" },
  "컷마다 그림 따로 그리기": { en: "Draw each panel separately", ja: "コマごとに絵を別々に描く", zh: "每个分格单独画" },
  "완성된 웹툰을 숏츠로 만들기": { en: "Turn the finished webtoon into Shorts", ja: "完成したウェブトゥーンをショート動画にする", zh: "把完成的漫画做成短视频" },
  "내 캐릭터 공개 기능": { en: "Share my character publicly", ja: "自分のキャラを公開する機能", zh: "公开我的角色" },
  "커뮤니티 기능": { en: "Community features", ja: "コミュニティ機能", zh: "社区功能" },
  "그림체 생성 및 추가 기능": { en: "Create and add art styles", ja: "絵柄の生成・追加機能", zh: "生成和添加画风" },
  "로어북 기능": { en: "Lorebook", ja: "ロアブック機能", zh: "设定集（Lorebook）功能" },
  "컷마다 그림을 한 장씩 따로 그리기": { en: "Draw each panel as its own separate image", ja: "コマごとに絵を1枚ずつ別々に描く", zh: "每个分格单独画成一张图" },
  "내가 넣은 그림체 그대로 웹툰 만들기": { en: "Make the webtoon in the exact art style I provide", ja: "自分が入れた絵柄そのままでウェブトゥーンを作る", zh: "完全按我提供的画风来做漫画" },
  "대사·컷 구성을 내가 직접 설계하기": { en: "Design the dialogue and panel layout myself", ja: "セリフ・コマ構成を自分で設計する", zh: "自己设计台词和分镜构成" },
  "완성한 웹툰을 짧은 영상으로 만들어 SNS에 공유하기": { en: "Turn the finished webtoon into a short video to share on social media", ja: "完成したウェブトゥーンを短い動画にしてSNSで共有する", zh: "把完成的漫画做成短视频分享到社交媒体" },
  "내 캐릭터를 공개해서 다른 사람이 그 캐릭터로 새 이야기 만들기": { en: "Make my character public so others can create new stories with it", ja: "自分のキャラを公開して、他の人がそのキャラで新しい話を作れるようにする", zh: "公开我的角色，让别人用这个角色创作新故事" },
  "댓글·작가 홈·인기순 같은 커뮤니티 기능": { en: "Community features like comments, author pages, and rankings", ja: "コメント・作家ホーム・人気順のようなコミュニティ機能", zh: "评论、作者主页、人气排行等社区功能" },
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
