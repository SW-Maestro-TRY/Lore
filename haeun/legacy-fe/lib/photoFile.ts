/* 고른 사진을 <b>서버가 열 수 있는 그림</b>으로 바꿔서 읽는다.
 *
 * 자바의 `ImageIO` 는 PNG·JPEG 만 읽는다. 아이폰이 주는 HEIC 도, 고르개의
 * accept 에 버젓이 적혀 있던 WebP 도 못 읽어서 — 고르기까지는 되고 만들기를
 * 누르는 순간 "사진을 열지 못했습니다. 아이폰 사진(HEIC)이면 JPG 나 PNG 로
 * 바꿔서 올려 주세요" 로 되돌아왔다. 사람에게 파일 변환을 시키는 안내였다.
 *
 * 브라우저는 제가 화면에 띄울 수 있는 것은 다 디코딩한다. 그러니 여기서 한
 * 번 그려서 JPEG 로 다시 내보내면, 서버는 손대지 않고도 읽을 수 있는 것이
 * 된다. 굽는 쪽이 어차피 1400px 로 줄이므로(JobService.PHOTO_WIDTH) 그
 * 폭까지만 그린다.
 *
 * <b>PNG·JPEG 는 손대지 않는다</b> — 다시 인코딩해 봐야 화질만 잃는다. 다만
 * 6MB 가 넘으면 그것도 다시 그린다. 요즘 폰 사진은 그냥도 6MB 를 넘는데,
 * 예전에는 그걸 "너무 큽니다" 로 막기만 했다.
 *
 * <b>남는 한계</b>: 브라우저가 못 여는 것은 여기서도 못 연다. HEIC 는
 * 사파리·iOS 가 열고 윈도우 크롬은 못 연다 — 그때는 아래 오류 문구가 뜬다.
 * 그 경우가 실제로 걸리면 서버에 libheif 를 붙이는 것이 다음 수순이다.
 */

/** 고르개에 적을 형식. HEIC 를 적어 둬야 iOS 사진첩이 흐리게 두지 않는다. */
export const PHOTO_ACCEPT =
  "image/png,image/jpeg,image/webp,image/heic,image/heif,image/*";

export const MAX_PHOTO_BYTES = 6 * 1024 * 1024;

/** 다시 그리지 않아도 서버가 읽는 형식. */
const READABLE = new Set(["image/png", "image/jpeg"]);

/** 서버가 어차피 이 폭으로 줄인다 — 더 크게 보내 봐야 버려진다. */
const MAX_WIDTH = 1400;

export class PhotoError extends Error {}

export async function readPhoto(file: File): Promise<string> {
  if (READABLE.has(file.type) && file.size <= MAX_PHOTO_BYTES) {
    return asDataUrl(file);
  }
  const url = await redraw(file);
  // 다시 그렸는데도 넘치면 그때는 정말 큰 것이다.
  if (bytesOf(url) > MAX_PHOTO_BYTES) {
    throw new PhotoError("사진이 너무 큽니다 (6MB 까지)");
  }
  return url;
}

async function redraw(file: File): Promise<string> {
  const img = await decode(file);
  const scale = Math.min(1, MAX_WIDTH / img.width);
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(img.width * scale));
  canvas.height = Math.max(1, Math.round(img.height * scale));
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new PhotoError("사진을 열지 못했습니다");
  // JPEG 에는 투명이 없다. 흰 바탕을 먼저 깔지 않으면 투명했던 자리가
  // 새까맣게 나온다(배경을 지운 PNG 가 6MB 를 넘겨 이 길로 올 때).
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  if ("close" in img) img.close();
  return canvas.toDataURL("image/jpeg", 0.92);
}

/** 브라우저에게 디코딩을 맡긴다. `createImageBitmap` 이 거절하면 `<img>` 로
 *  한 번 더 — 사파리가 HEIC 를 여는 길이 둘 중 하나뿐인 판이 있다. */
async function decode(file: File): Promise<ImageBitmap | HTMLImageElement> {
  try {
    return await createImageBitmap(file);
  } catch {
    return await viaImgTag(file);
  }
}

function viaImgTag(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const src = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(src); resolve(img); };
    img.onerror = () => {
      URL.revokeObjectURL(src);
      reject(new PhotoError(
        "이 브라우저가 못 여는 사진입니다. JPG 나 PNG 로 바꿔서 올려 주세요"));
    };
    img.src = src;
  });
}

function asDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const fr = new FileReader();
    fr.onload = () => resolve(String(fr.result));
    fr.onerror = () => reject(new PhotoError("사진을 읽지 못했습니다"));
    fr.readAsDataURL(file);
  });
}

function bytesOf(dataUrl: string): number {
  const b64 = dataUrl.slice(dataUrl.indexOf(",") + 1);
  return Math.floor(b64.length * 3 / 4);
}
