// 홈 화면에 추가했을 때의 앱 정보(#599). Next 가 /manifest.webmanifest 로 내보내고
// <link rel="manifest"> 도 알아서 붙인다.
//
// ★ 아이폰은 홈 화면에 추가해서 연 앱에서만 웹푸시를 받는다(iOS 16.4+). 그래서 이 파일이
//   웹툰 완성·「골라 주세요」 알림의 전제다 — display 를 standalone 에서 바꾸면 아이폰
//   알림이 통째로 안 된다.
//
// 아이콘은 임시로 사이트 파비콘(app/icon.svg, #560)을 키운 것이다. 최종본이 오면
// public/icons/ 안 파일만 같은 이름으로 바꾼다(아이폰은 app/apple-icon.png).
import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "LORE",
    short_name: "LORE",
    description: "사진 한 장에서 캐릭터를 뽑고, 그 캐릭터로 웹툰까지 이어서 만듭니다.",
    start_url: "/webtoon",
    scope: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icons/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  };
}
