import type { ShareContent } from "./sharing";

export const SHARE_IMAGE_WIDTH = 1080;
export const SHARE_IMAGE_HEIGHT = 1350;
const FONT_FAMILY = '"Noto Sans KR Variable"';
const FONT = `${FONT_FAMILY}, "Apple SD Gothic Neo", "Malgun Gothic", sans-serif`;
const DISPLAY_FONT_FAMILY = '"Jua"';
const DISPLAY_FONT = `${DISPLAY_FONT_FAMILY}, ${FONT}`;
// Canvas에는 CSS 변수가 상속되지 않으므로 SD 화면과 같은 팔레트를 명시한다.
const SD = {
  hero: "#fff3d6",
  surface: "#fffef8",
  ink: "#2e3535",
  muted: "#68706c",
  border: "#cabfa5",
  primary: "#b6382e",
  warm: "#f8d768",
  sea: "#3a706c",
};

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

function fit(ctx: CanvasRenderingContext2D, text: string, width: number, maxLines: number, size: number, minSize: number, weight = 400, font = FONT): TextBlock {
  let lines: string[] = [];
  for (; size >= minSize; size -= 2) {
    ctx.font = `${weight} ${size}px ${font}`;
    lines = wrapped(ctx, text.replace(/\s+/g, " ").trim(), width);
    if (lines.length <= maxLines) return { lines, size, shortened: false };
  }
  size = minSize;
  ctx.font = `${weight} ${size}px ${font}`;
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
    await Promise.all([
      ...[400, 700].map(weight => document.fonts.load(`${weight} 34px ${FONT_FAMILY}`, text)),
      document.fonts.load(`400 66px ${DISPLAY_FONT_FAMILY}`, `${content.title}Piece Maker.`),
    ]);
  }
  const canvas = document.createElement("canvas");
  canvas.width = SHARE_IMAGE_WIDTH; canvas.height = SHARE_IMAGE_HEIGHT;
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("이미지를 만들 수 없는 브라우저입니다.");
  ctx.textBaseline = "top";
  const text = (value: string, x: number, y: number, size: number, color: string, weight = 400, font = FONT) => {
    ctx.font = `${weight} ${size}px ${font}`; ctx.fillStyle = color; ctx.fillText(value, x, y);
  };
  const block = (value: TextBlock, x: number, y: number, leading: number, color: string, weight = 400, font = FONT) => {
    value.lines.forEach((line, index) => text(line, x, y + index * leading, value.size, color, weight, font));
  };
  const rect = (x: number, y: number, w: number, h: number, color: string, border?: string) => {
    ctx.fillStyle = color; ctx.fillRect(x, y, w, h);
    if (border) { ctx.strokeStyle = border; ctx.lineWidth = 1; ctx.strokeRect(x, y, w, h); }
  };

  rect(0, 0, 1080, 1350, SD.hero);
  ctx.strokeStyle = SD.ink; ctx.lineWidth = 4.5; ctx.strokeRect(24, 24, 1032, 1302);
  text("MY THEORY", 78, 82, 25, SD.primary, 700);
  ctx.textAlign = "right"; text(`ONE PIECE · CH.${content.chapter}`, 1002, 84, 23, SD.sea); ctx.textAlign = "left";
  rect(78, 147, 570, 56, SD.warm, SD.ink);
  text(`원피스 ${content.chapter}화까지 · 스포일러 포함`, 98, 160, 26, SD.ink);
  text("내 가설", 78, 279, 25, SD.sea);
  const title = fit(ctx, content.title, 924, 3, 66, 44, 400, DISPLAY_FONT);
  block(title, 78, 333, 76, SD.ink, 400, DISPLAY_FONT);
  const claim = fit(ctx, content.claim, 924, 2, 32, 28);
  block(claim, 78, 570, 43, SD.ink);
  rect(78, 706, 924, 447, SD.surface, SD.ink);
  text("가설 판정", 120, 744, 25, SD.sea);
  ctx.textAlign = "right"; text(`${content.chapter}화 기록 기준`, 960, 748, 21, SD.sea); ctx.textAlign = "left";
  rect(110, 790, 470, 90, SD.warm);
  text(content.grade, 120, 799, 68, SD.ink, 700);
  const reason = fit(ctx, content.reason, 836, 5, 34, 28);
  block(reason, 120, 898, 44, SD.ink);
  text("판정 이유 발췌 · 생략된 부분은 판정 화면에서 확인하세요.", 78, 1181, 23, SD.muted);
  text("정답이나 확률을 뜻하지 않습니다.", 78, 1214, 23, SD.muted);
  rect(78, 1254, 924, 1, SD.border);
  text("Piece Maker.", 78, 1275, 26, SD.ink, 400, DISPLAY_FONT);
  ctx.textAlign = "right"; text("LORE", 1002, 1279, 20, SD.muted);
  const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error("이미지를 저장하지 못했어요.")), "image/png"));
  return { blob, shortened: title.shortened || claim.shortened || reason.shortened };
}
