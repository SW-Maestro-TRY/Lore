// /sitemap.xml — 검색 로봇에 알리는 고정 주소 목록.
// 공유 링크(/webtoon?run=…)처럼 사용자마다 생기는 주소와 언어 접두어(/ko·/en·/ja)는
// 실제 라우팅·색인 기준이 정해진 뒤 넣는다.
import type { MetadataRoute } from "next";

const BASE = "https://lorecomic.com";

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();
  return [
    { url: `${BASE}/`, lastModified: now, changeFrequency: "weekly", priority: 1 },
    { url: `${BASE}/zzal`, lastModified: now, changeFrequency: "weekly", priority: 0.9 },
    { url: `${BASE}/webtoon`, lastModified: now, changeFrequency: "weekly", priority: 0.8 },
    // staging·운영 라우트는 아직 /trailer. piece-maker 승격 뒤 /trailer→308 이동이므로 그때까지 이 주소를 알린다
    { url: `${BASE}/trailer`, lastModified: now, changeFrequency: "weekly", priority: 0.8 },
  ];
}
