/* 판정 읽기 — 등급 이름, 편집본을 보여 줄지 가리기, 판정 상태 문구, 인용 카드 꺼내기. */
import type { Card, Grade, JudgeResult, Judgement, Presentation, PresentationSection } from "./api";
import { cleanCard } from "./draft";

export const GRADES: Record<Grade, string> = {
  likely: "가능성 있음",
  unlikely: "가능성 낮음",
  insufficient: "판정 보류",
};

/** 판정 버튼 아래에 나오는 한 줄. */
export const JUDGE_TEXT = {
  idle: "카드를 고르고 내 주장을 적은 뒤 판정을 맡기세요.",
  submitting: "가설을 맡기는 중입니다.",
  checking: "판정 상태를 확인하는 중입니다.",
  pending: "판정을 기다리는 중입니다. 사람이 돌려서 시간이 걸립니다. 페이지를 닫아도 됩니다 — 내 가설에서 다시 볼 수 있습니다.",
  loginToSee: "로그인하면 판정 결과를 볼 수 있습니다.",
  missing: "맡긴 가설을 찾을 수 없습니다. 다른 계정으로 맡겼거나 지워졌습니다. 새 가설을 쓸 수 있습니다.",
  fetchFailed: "판정 상태를 받지 못했습니다. 잠시 뒤 다시 확인합니다.",
  resolving: "근거 카드를 확인하는 중입니다.",
  done: "판정이 끝났습니다. 근거를 누르면 카드의 기록을 볼 수 있습니다.",
  judgeFailed: (message: string) => `판정을 완료하지 못했습니다. ${message}`,
  brokenResult: "판정은 끝났지만 근거 카드를 확인할 수 없어 보여 드리지 못합니다.",
  cardsFailed: "카드를 불러오지 못해 판정을 맡길 수 없습니다.",
  failed: (reason: string) => `판정을 맡기지 못했습니다. ${reason} 입력을 유지한 채 다시 시도할 수 있습니다.`,
} as const;

function sectionIsValid(section: PresentationSection | null | undefined): boolean {
  return Boolean(
    section &&
      typeof section.title === "string" &&
      section.title.trim() &&
      typeof section.text === "string" &&
      section.text.trim() &&
      Array.isArray(section.source_ids) &&
      section.source_ids.every((id) => typeof id === "string"),
  );
}

/** 편집본을 보여 줄 수 있으면 편집본을, 아니면 그 까닭을 돌려준다.
 *  편집본이 없거나 덜 됐으면 화면은 판정 원문을 보여 준다. 화면과 게시글이 함께 쓴다. */
export function readableJudgement(result: JudgeResult): { presentation: Presentation | null; notice: string } {
  const presentation = result.presentation;
  if (!presentation) return { presentation: null, notice: "" };
  if (
    presentation.status === "complete" &&
    typeof presentation.headline === "string" &&
    presentation.headline.trim() &&
    Array.isArray(presentation.sections) &&
    presentation.sections.length &&
    presentation.sections.every(sectionIsValid) &&
    Array.isArray(presentation.details) &&
    presentation.details.every(sectionIsValid)
  ) {
    return { presentation, notice: "" };
  }
  return {
    presentation: null,
    notice:
      presentation.status === "review_required"
        ? "편집본의 의미를 더 확인해야 해서 판정 원문을 보여드립니다."
        : "편집본을 준비하지 못해 판정 원문을 보여드립니다.",
  };
}

/** 판정이 함께 실어 온 인용 카드. 모양이 카드로 볼 수 없는 것은 뺀다. 없으면 빈 목록이다. */
export function citedCards(judgement: Judgement): Card[] {
  if (!Array.isArray(judgement.cited_cards)) return [];
  return judgement.cited_cards.map(cleanCard).filter((card): card is Card => card !== null);
}

/** 판정이 가리키는 카드의 번호(뒷받침과 반박). 모양이 틀린 판정은 빈 목록이다 — `checkJudgement` 가 그 뒤에 잡는다. */
export function citedIds(judgement: Judgement): string[] {
  if (!Array.isArray(judgement.support) || !Array.isArray(judgement.against)) return [];
  return [...new Set([...judgement.support, ...judgement.against].filter((id): id is string => typeof id === "string"))];
}

/** 판정의 모양을 확인한다. 판정이 가리키는 카드가 화면에 모두 있어야 한다. 아니면 오류를 던진다. */
export function checkJudgement(result: JudgeResult, hasCard: (id: string) => boolean): void {
  const value = result.judgement;
  if (
    !value ||
    !Object.prototype.hasOwnProperty.call(GRADES, value.grade) ||
    typeof value.reason !== "string" ||
    !Array.isArray(value.support) ||
    !Array.isArray(value.against) ||
    [...value.support, ...value.against].some((id) => !hasCard(id))
  ) {
    throw new Error("판정 응답의 근거를 확인할 수 없습니다.");
  }
}
