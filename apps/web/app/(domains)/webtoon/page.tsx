// /webtoon 라우트 연결 파일.
// Next.js App Router 규칙상 이 위치에 page.tsx 가 있어야 URL 이 잡히기 때문에 존재하는 파일이다.
// 화면 내용은 webtoon/fe 에 있으니 이 파일은 건드릴 일이 거의 없다.
import type { Metadata } from "next";
import { headers } from "next/headers";
import WebtoonPage from "@webtoon/WebtoonPage";

/** `/runs/{id}/result` 가 주는 것 중 미리보기에 쓰는 것만. (전체 모양은
 *  webtoon/fe/lib/nhApi.ts 의 RunResult 참고 — 봉투 없는 원시 JSON이다.) */
interface RunPreview {
  title?: string;
  character?: string;
}

/** 지금 요청이 온 주소. 이 함수는 서버에서 도는 자리라 `window.location`
 *  이 없다 — 대신 요청에 실려 온 host 헤더로 지금 도메인을 알아낸다.
 *  공유 미리보기(og:image)는 상대 주소가 안 통하므로 절대 주소가 필요하다. */
async function origin(): Promise<string> {
  const h = await headers();
  const host = h.get("host") ?? "";
  const proto = host.startsWith("localhost") || host.startsWith("127.0.0.1") ? "http" : "https";
  return `${proto}://${host}`;
}

/**
 * `/webtoon?run=<id>` 로 들어오면(공유 링크가 이 길이다) 제목·표지를 채운다.
 *
 * **채우기 전에는 링크를 붙였을 때 아무 미리보기도 안 떴다** — 카카오톡·X 같은
 * 곳은 og:image 를 못 찾으면 카드를 아예 안 그리거나 사이트 기본 아이콘만
 * 보여준다. 만든 사람이 보내고 싶어할 바로 그 순간(share.ts 의 ShareBar)에
 * 받는 사람 쪽에서는 글자만 보이면, 눌러 볼 이유가 줄어든다.
 *
 * `run` 이 없으면(그냥 `/webtoon`) 빈 것을 돌려준다 — 그러면 상위(app/layout.tsx)
 * 의 기본 메타데이터가 그대로 쓰인다.
 */
export async function generateMetadata(
  { searchParams }: { searchParams: Promise<{ run?: string }> },
): Promise<Metadata> {
  const { run } = await searchParams;
  if (!run) return {};

  const base = await origin();
  try {
    const res = await fetch(
      `${base}/api/webtoon/v1/runs/${encodeURIComponent(run)}/result`,
      { cache: "no-store" },
    );
    if (!res.ok) return {};
    const data = (await res.json()) as RunPreview;

    const title = data.title?.trim() || "LORE 웹툰";
    const displayTitle = data.character?.trim()
      ? `${data.character.trim()} · ${title}`
      : title;
    // 표지 = 1장. 화면 크기가 아니라 카드용이라 기본 폭(1080)을 그대로 쓴다.
    const cover = `${base}/api/webtoon/v1/runs/${encodeURIComponent(run)}/page/1`;

    return {
      title: displayTitle,
      openGraph: { title: displayTitle, images: [cover] },
      twitter: { card: "summary_large_image", title: displayTitle, images: [cover] },
    };
  } catch {
    // 하네스가 없거나(로컬) 응답이 이상해도 화면 자체는 떠야 한다 — 미리보기 하나
    // 없다고 링크를 통째로 막을 이유가 없다.
    return {};
  }
}

export default WebtoonPage;
