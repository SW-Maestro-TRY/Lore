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

export function shareCaption(content: ShareContent): string {
  // 구분 표식을 글에 포함해 복사·SNS 전달 후에도 각 문단의 역할을 드러낸다.
  return [
    `[원피스 ${content.chapter}화까지 · 스포일러]`,
    `【내 가설】 → ${content.title}\n【판정 결과】 → ${content.grade}`,
    `【가설 내용】\n${content.claim}`,
    `【판정 이유】\n${content.reason}`,
    "【Piece Maker】 → https://lorecomic.com/piece-maker\n#원피스 #PieceMaker",
  ].join("\n\n");
}

// X·Threads는 작성창으로 글을 넘기고, Facebook은 복사·직접 게시 안내 후 이동한다.
export const SOCIAL_CHANNELS = [
  { id: "facebook", name: "Facebook", href: "https://www.facebook.com/" },
  { id: "threads", name: "Threads", href: "https://www.threads.com/intent/post" },
  { id: "x", name: "X / 트위터", href: "https://x.com/intent/tweet" },
] as const;

export function shareChannelHref(channel: (typeof SOCIAL_CHANNELS)[number], caption: string): string {
  if (channel.id === "facebook") return channel.href;
  return `${channel.href}?${new URLSearchParams({ text: caption }).toString()}`;
}
