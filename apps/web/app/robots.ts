// /robots.txt — 검색 로봇 안내. 관리 화면과 API 는 수집 대상에서 뺀다.
// ★ dev.lorecomic.com 은 같은 코드로 뜨므로 여기서 호스트를 가려 막지 못한다.
//   dev 색인 제외가 필요하면 배포 환경변수나 CloudFront 응답 헤더(X-Robots-Tag)로 다룬다.
import type { MetadataRoute } from "next";

export default function robots(): MetadataRoute.Robots {
  return {
    rules: [{ userAgent: "*", allow: "/", disallow: ["/zzal/admin", "/api/"] }],
    sitemap: "https://lorecomic.com/sitemap.xml",
  };
}
