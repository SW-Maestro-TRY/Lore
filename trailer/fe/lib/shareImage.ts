import type { ShareContent } from "./sharing";

export const SHARE_IMAGE_WIDTH = 1080;
export const SHARE_IMAGE_HEIGHT = 1350;
const FONT_FAMILY = '"Noto Sans KR Variable"';
const FONT = `${FONT_FAMILY}, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif`;

type TextBlock = { lines: string[]; size: number; shortened: boolean };

function graphemes(text: string): string[] {
  return typeof Intl.Segmenter === "function"
    ? [...new Intl.Segmenter("ko", { granularity: "grapheme" }).segment(text)].map(value => value.segment)
    : [...text];
}

function wrapped(ctx: CanvasRenderingContext2D, text: string, width: number): string[] {
  const lines: string[] = [];
  const segments = graphemes(text);
  let line = "";
  for (const char of segments) {
    if (char === "\n") { lines.push(line.trimEnd()); line = ""; continue; }
    if (line && ctx.measureText(line + char).width > width) { lines.push(line.trimEnd()); line = char.trimStart(); }
    else line += char;
  }
  if (line) lines.push(line.trimEnd());
  return lines;
}

function fit(ctx: CanvasRenderingContext2D, text: string, width: number, maxLines: number, size: number, minSize: number, weight = 400): TextBlock {
  let lines: string[] = [];
  for (; size >= minSize; size -= 2) {
    ctx.font = `${weight} ${size}px ${FONT}`;
    lines = wrapped(ctx, text.replace(/\s+/g, " ").trim(), width);
    if (lines.length <= maxLines) return { lines, size, shortened: false };
  }
  size = minSize;
  ctx.font = `${weight} ${size}px ${FONT}`;
  lines = wrapped(ctx, text.replace(/\s+/g, " ").trim(), width).slice(0, maxLines);
  const last = graphemes(lines[maxLines - 1] || "");
  while (last.length && ctx.measureText(last.join("") + "…").width > width) last.pop();
  lines[maxLines - 1] = last.join("").trimEnd() + "…";
  return { lines, size, shortened: true };
}

/** 이미지 생성 API 없이 사용자의 브라우저 안에서 PNG를 조판한다. */
export async function makeShareImage(content: ShareContent): Promise<{ blob: Blob; shortened: boolean }> {
  if (document.fonts) {
    const text = `${content.title}${content.claim}${content.reason}${content.grade}내 가설 판정 이유 발췌 스포일러`;
    await Promise.all([400, 700].map(weight => document.fonts.load(`${weight} 34px ${FONT_FAMILY}`, text)));
  }
  const canvas = document.createElement("canvas");
  canvas.width = SHARE_IMAGE_WIDTH; canvas.height = SHARE_IMAGE_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("이미지를 만들 수 없는 브라우저입니다.");
  ctx.textBaseline = "top";
  const text = (value: string, x: number, y: number, size: number, color: string, weight = 400) => {
    ctx.font = `${weight} ${size}px ${FONT}`; ctx.fillStyle = color; ctx.fillText(value, x, y);
  };
  const block = (value: TextBlock, x: number, y: number, leading: number, color: string, weight = 400) => {
    value.lines.forEach((line, index) => text(line, x, y + index * leading, value.size, color, weight));
  };
  const rect = (x: number, y: number, w: number, h: number, color: string, border?: string) => {
    ctx.fillStyle = color; ctx.fillRect(x, y, w, h);
    if (border) { ctx.strokeStyle = border; ctx.lineWidth = 1; ctx.strokeRect(x, y, w, h); }
  };

  rect(0, 0, 1080, 1350, "#fcfaf4");
  ctx.strokeStyle = "#d3dcce"; ctx.lineWidth = 2; ctx.strokeRect(24, 24, 1032, 1302);
  text("MY THEORY", 78, 82, 25, "#244c3b", 700);
  ctx.textAlign = "right"; text(`ONE PIECE · CH.${content.chapter}`, 1002, 84, 23, "#6a786c"); ctx.textAlign = "left";
  rect(78, 147, 570, 56, "#f7f0de", "#d3c197");
  text(`원피스 ${content.chapter}화까지 · 스포일러 포함`, 98, 160, 26, "#8a6630");
  text("내 가설", 78, 279, 25, "#697e6c");
  const title = fit(ctx, content.title, 924, 3, 66, 44, 700);
  block(title, 78, 333, 76, "#203d32", 700);
  const claim = fit(ctx, content.claim, 924, 2, 32, 28);
  block(claim, 78, 570, 43, "#586b5b");
  rect(78, 706, 924, 447, "#eaf0e5", "#d1dbca");
  text("가설 판정", 120, 744, 25, "#5b715c");
  ctx.textAlign = "right"; text(`${content.chapter}화 기록 기준`, 960, 748, 21, "#5b715c"); ctx.textAlign = "left";
  text(content.grade, 120, 799, 68, "#365343", 700);
  const reason = fit(ctx, content.reason, 836, 5, 34, 28);
  block(reason, 120, 898, 44, "#3d5843");
  text("판정 이유 발췌 · 생략된 부분은 판정 화면에서 확인하세요.", 78, 1181, 23, "#7b8275");
  text("정답이나 확률을 뜻하지 않습니다.", 78, 1214, 23, "#7b8275");
  rect(78, 1254, 924, 1, "#d7dfd2");
  text("Piece Maker.", 78, 1275, 26, "#203d32", 700);
  ctx.textAlign = "right"; text("LORE", 1002, 1279, 20, "#8a8f81");
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("이미지를 저장하지 못했어요.")), "image/png"));
  return { blob, shortened: title.shortened || claim.shortened || reason.shortened };
}
