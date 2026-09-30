import type { JudgeResult } from "./api";
import type { Draft } from "./draft";
import { GRADES, readableJudgement } from "./judgement";

export type ShareContent = { chapter: number; title: string; claim: string; grade: string; reason: string };

/** 받은 판정을 그대로 사용한다. 새 요약이나 판정을 생성하지 않는다. */
export function shareContent(draft: Draft, result: JudgeResult): ShareContent {
  return {
    chapter: draft.chapter,
    title: draft.title.trim() || "제목 없는 가설",
    claim: draft.claim.trim(),
    grade: GRADES[result.judgement.grade],
    reason: readableJudgement(result).presentation?.headline.trim() || result.judgement.reason.trim(),
  };
}

export function excerpt(text: string, limit: number): string {
  const chars = [...text.replace(/\s+/g, " ").trim()];
  return chars.length > limit ? chars.slice(0, limit).join("").trimEnd() + "…" : chars.join("");
}

export function shareCaption(content: ShareContent): string {
  return [`[원피스 ${content.chapter}화까지 · 스포일러]`, "", content.title, "",
    `내 가설: ${excerpt(content.claim, 140)}`, "", `판정 결과: ${content.grade}`,
    `판정 이유 (발췌): ${excerpt(content.reason, 180)}`, "", "#원피스 #PieceMaker"].join("\n");
}

// SNS에서는 사용자가 이미지를 첨부하고 최종 게시한다. 개인 가설의 공개 URL은 만들지 않는다.
export const SOCIAL_CHANNELS = [
  { id: "instagram", name: "Instagram", href: "https://www.instagram.com/" },
  { id: "facebook", name: "Facebook", href: "https://www.facebook.com/" },
  { id: "threads", name: "Threads", href: "https://www.threads.com/" },
  { id: "x", name: "X / 트위터", href: "https://x.com/" },
] as const;
