/* 장면 글(#548) — own 길은 「소제목\n글」을 빈 줄로 이어 적는다(서버 JobRunner.sceneCaptions,
 * 장면 확인 화면과 같은 모양). 소제목이 없는 옛 글(아이디어부터 길의 한 줄)은 한 덩어리로 본다. */
export const SCENE_LABELS = ["장소와 상황", "벌어지는 일", "행동과 표정", "겉모습", "끝나는 상태", "나레이션"];

export type ScenePart = { label: string; text: string };

/** 소제목별로 나눈다. 소제목이 하나도 없으면 [{label:"", text: 전체}]. */
export function sceneParts(text: string): ScenePart[] {
  const out: ScenePart[] = [];
  for (const line of (text || "").split("\n")) {
    const head = line.trim();
    if (SCENE_LABELS.includes(head)) { out.push({ label: head, text: "" }); continue; }
    if (!out.length) out.push({ label: "", text: "" });
    const last = out[out.length - 1];
    last.text += (last.text ? "\n" : "") + line;
  }
  return out.map((p) => ({ ...p, text: p.text.trim() })).filter((p) => p.label || p.text);
}

/** 그림 밑 한 줄 — 「장소와 상황」이 있으면 그것만, 없으면 전체. */
export function sceneShort(text: string): string {
  const parts = sceneParts(text);
  return (parts.find((p) => p.label === "장소와 상황") ?? parts[0])?.text ?? "";
}
