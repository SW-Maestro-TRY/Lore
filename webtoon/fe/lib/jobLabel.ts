/* 만들던 작업 한 줄(#548) — 첫 화면의 「만들던 웹툰」 알약과 마이페이지 「만드는 중」
 * 목록이 같은 말을 쓴다. 사람이 답할 차례면 무엇을 기다리는지, 아니면 어디까지
 * 그렸는지. 「검수」라는 말은 화면에 쓰지 않는다. */
import type { NhJob } from "./api";
import { registerDict } from "./i18n";

type Tr = (s: string, p?: Record<string, string | number>) => string;

export function activeJobLabel(job: NhJob, t: Tr): string {
  switch (job.status) {
    case "awaiting_pick": return t("이야기 고르는 중");
    case "awaiting_cast": return t("인물 확인 중");
    case "awaiting_sheet": return t("캐릭터 확인 중");
    case "awaiting_scenes": return t("장면 확인 중");
    default:
      return job.art?.total
        ? t("{done} / {total}장", { done: job.art.done, total: job.art.total })
        : t(job.stage_label);
  }
}

/** 작업의 제목 — own 길이면 다듬은 이야기 제목, quick 길이면 고른 후보 제목. 아직 없으면 빈 문자열. */
export function activeJobTitle(job: NhJob): string {
  if (job.story?.title) return job.story.title;
  if (job.pick != null) return job.directions.find((d) => d.n === job.pick)?.title ?? "";
  return "";
}

registerDict({
  "이야기 고르는 중": { en: "Picking a story", ja: "ストーリー選択中", zh: "正在选故事" },
  "인물 확인 중": { en: "Checking the characters", ja: "登場人物を確認中", zh: "正在确认人物" },
  "캐릭터 확인 중": { en: "Checking the character", ja: "キャラクター確認中", zh: "正在确认角色" },
  "장면 확인 중": { en: "Checking the scenes", ja: "場面を確認中", zh: "正在确认场景" },
  "{done} / {total}장": { en: "{done} / {total} pages", ja: "{done} / {total}枚", zh: "{done} / {total} 页" },
  "만드는 중": { en: "In progress", ja: "作成中", zh: "制作中" },
  "이어서 만들기": { en: "Continue", ja: "続きを作る", zh: "继续制作" },
});
