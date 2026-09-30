/* 게시글 글 — 가설을 다른 곳에 붙여 넣을 글로 바꾼다.
 * 카드는 초안이 통째로 담고 있다. 판정은 화면과 같은 `readableJudgement` 로 읽는다. 화면이 편집본을 보여 주면 게시글도 편집본을 쓴다. */
import type { JudgeResult } from "./api";
import type { Draft } from "./draft";
import { GRADES, readableJudgement } from "./judgement";

export function postText(draft: Draft, chapter: number, maxChapter: number, judged: JudgeResult | null): string {
  const parts = [
    draft.title.trim() || "제목 없는 가설",
    `[원피스 · ${chapter}화 누적 장부 기준]`,
    "",
    "내 주장",
    draft.claim.trim() || "(미작성)",
    "",
    "근거와 나의 해석",
  ];
  draft.cards.forEach((card, index) => {
    parts.push(
      "",
      `${index + 1}. ${card.title} (${card.chapter}화 / ${card.id})`,
      `추출 기록: ${card.fact}`,
      `나의 해석: ${draft.notes[card.id]?.trim() || "(미작성)"}`,
      `출처: 복선 ${card.id}`,
    );
  });
  if (judged) {
    const value = judged.judgement;
    const { presentation, notice } = readableJudgement(judged);
    parts.push("", `가설 판정: ${GRADES[value.grade]}`);
    if (presentation) {
      parts.push(presentation.headline);
      presentation.sections.forEach((section) => parts.push("", section.title, section.text));
      if (presentation.details.length) {
        parts.push("", "추가 검토");
        presentation.details.forEach((section) => parts.push("", section.title, section.text));
      }
    } else {
      if (notice) parts.push(notice);
      parts.push(value.reason);
    }
    parts.push("", `뒷받침: ${value.support.join(", ") || "없음"}`, `반박: ${value.against.join(", ") || "없음"}`);
  }
  parts.push(
    "",
    "※ 카드 본문은 장부의 추출 기록입니다. 해석과 주장은 독자의 의견이며, 판정은 확률이나 정답을 뜻하지 않습니다.",
    `${maxChapter}화 누적 장부의 추출 기록입니다.`,
  );
  return parts.join("\n");
}
