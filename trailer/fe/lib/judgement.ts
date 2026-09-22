/* 판정 읽기 — 등급 이름, 편집본을 보여 줄지 가리기, 판정 상태 문구. */
import type { Grade, JudgeResult, Presentation, PresentationSection } from "./api";

export const GRADES: Record<Grade, string> = {
  likely: "가능성 있음",
  unlikely: "가능성 낮음",
  insufficient: "판정 보류",
};

/** 판정 버튼 아래에 나오는 한 줄. */
export const JUDGE_TEXT = {
  idle: "카드를 고르고 내 주장을 적은 뒤 판정하세요.",
  changed: "입력이 바뀌었습니다. 다시 판정하면 수정한 내용을 대조합니다.",
  waiting: "기존 예측 모델로 근거를 대조하고 있습니다. 잠시 기다려 주세요.",
  done: "입력을 수정하면 이 판정은 해제됩니다.",
  cardsFailed: "카드를 불러오지 못해 판정을 시작할 수 없습니다.",
  failed: (reason: string) => `판정을 완료하지 못했습니다. ${reason} 입력을 유지한 채 다시 시도할 수 있습니다.`,
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
