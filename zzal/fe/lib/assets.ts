// 서버가 준 에셋 키를 화면이 쓸 주소로 바꾸는 자리. **규칙을 여기 한 곳에만 둔다.**
//
// ★ 왜 이 함수가 따로 있어야 하는가
//   CDN 앞머리와 서버 키가 **둘 다** `images` 를 말한다.
//     constants.ts  `const CDN = process.env.NEXT_PUBLIC_CDN_BASE ?? '/images'`
//     서버 키       `images/zzal/pets/23/motions/9/motion.webp`  ← 이미 images/ 로 시작
//   그대로 이으면 배포에서 `/images/images/zzal/...` 가 되어 404 다. 그런데 로컬에서는
//   CDN 이 빈 문자열이라 `/images/zzal/...` 가 되어 **겹친 것이 눈에 안 띈다.**
//   부르는 쪽마다 손으로 자르게 두면 언젠가 한 곳이 틀리고, 그 어긋남은 배포한 뒤에야 드러난다.
//
// ★ 규칙: **서버 키의 `images/` 앞머리는 CDN 값이 이미 담고 있는 몫이다.** 그래서 떼고 잇는다.
//     배포  CDN='/images' + 'zzal/pets/23/…'  →  /images/zzal/pets/23/…   (CloudFront → S3 키와 일치)
//     로컬  CDN=''        + 'zzal/pets/23/…'  →  /zzal/pets/23/…          (apps/web/public/zzal/… 와 일치)
//   즉 폴더 이름은 로컬과 S3 가 같고(zzal/assets·bg·demo), 다른 것은 `images/` 를 누가
//   붙이느냐뿐이다 — constants.ts 가 `${CDN}/zzal/demo/idle.webp` 로 쓰는 것과 같은 약속이다.

/** 정적 에셋이 사는 곳. constants.ts 의 CDN 과 **같은 값**이어야 한다(한쪽만 바꾸면 조용히 어긋난다). */
const CDN = process.env.NEXT_PUBLIC_CDN_BASE ?? '/images';

/**
 * 서버가 준 키를 화면에 붙일 주소로 바꾼다.
 *
 * ★ 시그니처를 바꾸지 말 것 — 도감·다운로드 등 여러 갈래가 이 하나를 함께 쓴다.
 *
 * @param serverKey 서버가 준 키(예: `images/zzal/pets/23/motions/9/motion.webp`).
 *                  비어 있으면 빈 문자열을 돌려준다 — `<img src="">` 는 아무것도 안 그리지만,
 *                  `undefined` 가 문자열로 끼면 `/images/undefined` 를 요청해 404 가 쌓인다.
 * @returns 화면에 그대로 넣을 수 있는 주소.
 */
export function assetUrl(serverKey: string): string {
  if (!serverKey) return '';
  // 이미 완성된 주소(외부 URL 이나 절대경로)면 손대지 않는다. constants.ts 가 만들어 둔
  // 값(`/zzal/demo/idle.webp`)이 그대로 들어와도 안전해야, 부르는 쪽이 출처를 안 가려도 된다.
  if (/^(https?:)?\/\//.test(serverKey) || serverKey.startsWith('/')) return serverKey;

  return `${CDN}/${serverKey.replace(/^images\//, '')}`;
}
