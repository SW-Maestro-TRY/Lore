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

/** `/characters/{id}/card/preview` 가 주는 것. 방문 보상을 안 세는 미리보기 전용 자리다. */
interface CardPreview {
  twist?: string;
  name?: string;
  has_art?: boolean;
}

/** 카드 링크 미리보기의 설명 줄. 카드 문장 뒤에 붙어 "무엇을 하는 곳인지" 를 알린다. */
const CARD_TAGLINE = "AI 캐릭터 & 웹툰 생성 서비스, Lore. 나도 만들러 가기";

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
 * 캐릭터 카드 공유 링크(`/webtoon?card=<id>`)도 같은 이유로 카드 문장·그림을 채운다
 * (cardMetadata).
 *
 * 둘 다 없으면(그냥 `/webtoon`) 빈 것을 돌려준다 — 그러면 상위(app/layout.tsx)
 * 의 기본 메타데이터가 그대로 쓰인다.
 */
export async function generateMetadata(
  { searchParams }: { searchParams: Promise<{ run?: string; card?: string }> },
): Promise<Metadata> {
  const { run, card } = await searchParams;
  if (!run) return card ? cardMetadata(card) : {};

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

/**
 * 캐릭터 카드 링크의 미리보기 — 제목은 카드 문장 한 줄, 설명은 서비스 한 줄, 그림은 카드 그림.
 *
 * `/card` 가 아니라 `/card/preview` 를 부른다. `/card` 는 부를 때마다 공유 방문 보상(#332)을
 * 세서, 여기서 부르면 화면 서버가 "남이 연 것" 으로 잡힌다. 그림은 `/card/art` 를 가리킨다 —
 * 카드 그림은 10분짜리 서명 주소라 og:image 에 그대로 적으면 미리보기를 캐시한 앱에서 깨지고,
 * `/card/art` 는 열 때마다 새 주소로 넘겨 준다.
 */
async function cardMetadata(card: string): Promise<Metadata> {
  const base = await origin();
  const id = encodeURIComponent(card);
  try {
    const res = await fetch(`${base}/api/webtoon/v1/characters/${id}/card/preview`, { cache: "no-store" });
    if (!res.ok) return {};
    const data = (await res.json()) as CardPreview;

    const title = data.twist?.trim() || data.name?.trim() || "LORE 캐릭터 카드";
    const images = data.has_art ? [`${base}/api/webtoon/v1/characters/${id}/card/art`] : [];
    return {
      title,
      description: CARD_TAGLINE,
      openGraph: { title, description: CARD_TAGLINE, images },
      twitter: { card: "summary_large_image", title, description: CARD_TAGLINE, images },
    };
  } catch {
    return {};
  }
}

export default WebtoonPage;
