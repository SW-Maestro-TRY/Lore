// /zzal — 자캐 다마고치.
//
// 2026-08-30 시안을 스크랩북으로 확정하고 이 화면을 기반으로 개발에 들어갔다.
// 2026-09-06 클로드 디자인에서 새 UX(여울 시안)를 받아 **기본 화면이 여울로 바뀌었다.**
//   · 여울   = 온보딩 5칸 + 방 다섯 칸(식탁·욕실·놀이·침실·앨범) + 시트/패널. 지금은 프론트 전용(서버 안 붙음).
//   · 스크랩북 = 서버·목 서버에 붙어 도는 판. `/zzal?skin=scrapbook` 으로 그대로 남겨 뒀다.
//     e2e 검사(apps/web/e2e)는 전부 이쪽을 본다.
// 두 벌을 병행하는 기간은 여울의 배치가 확정될 때까지다. 확정되면 스크랩북을 접는다.
//
// ★ 스킨은 **서버에서** 고른다. 브라우저에서 주소를 보고 고르면 서버가 그린 것과 달라져
//   하이드레이션 경고가 뜨고, e2e 가 그 경고를 실패로 센다.
//
// 옛 랜딩(Hero·HowItWorks·CharacterCreator)은 2026-10-01 에 접었다(/zzal/landing → /zzal 로 이동).
//
// 글꼴 — 여울 시안은 손글씨(Gaegu)와 고운돋움 두 벌을 쓴다.
// ★ CSS 의 `@import` 로는 못 받는다. Next 가 여러 CSS 를 이어 붙이면서 @import 가 파일 맨 위를
//   벗어나면 브라우저가 통째로 무시한다(2026-09-07 실측: 폰트 요청이 아예 안 나갔다).
//   그래서 next/font 로 받아 CSS 변수로 내려 준다.
import type { Metadata } from 'next';
import { Gaegu, Gowun_Dodum } from 'next/font/google';
import TamagotchiScreen, { type SkinName } from '@zzal/tamagotchi/TamagotchiScreen';

// 링크 미리보기·검색 결과에 쓰이는 페이지 정보. 공통 레이아웃의 제목·설명은 모든 탭이 같고
// 옛 서비스 설명이라, /zzal 은 여기서 따로 덮어쓴다.
// ★ 주소는 절대 주소로 적는다 — 공통 레이아웃에 metadataBase 가 없어 상대 주소는 미리보기
//   크롤러가 해석하지 못한다. 공통 설정이 생기면 상대 주소로 줄여도 된다.
// ★ canonical 은 /zzal 하나다. `?skin=scrapbook` 같은 변형 주소가 따로 색인되지 않게 모은다.
// 대표 이미지(og:image)는 아직 없다. 1200×630 이미지가 정해지면 openGraph.images 를 채우고
// twitter.card 를 'summary_large_image' 로 바꾼다.
const ZZAL_URL = 'https://lorecomic.com/zzal';
const ZZAL_TITLE = '캐릭터 키우기 — 내가 그린 아이와 같이 지내는 다마고치 | Lore';
const ZZAL_DESCRIPTION =
  '그림 한 장이면, 내가 그린 아이랑 같이 지낼 수 있어요. 밥 · 목욕 · 놀이 · 잠을 함께하면 새로 배운 동작이 움짤로 앨범에 쌓여요.';

export const metadata: Metadata = {
  title: ZZAL_TITLE,
  description: ZZAL_DESCRIPTION,
  alternates: { canonical: ZZAL_URL },
  openGraph: {
    type: 'website',
    url: ZZAL_URL,
    siteName: 'Lore',
    locale: 'ko_KR',
    title: ZZAL_TITLE,
    description: ZZAL_DESCRIPTION,
  },
  twitter: {
    card: 'summary',
    title: ZZAL_TITLE,
    description: ZZAL_DESCRIPTION,
  },
};

const gaegu = Gaegu({ subsets: ['latin'], weight: ['400', '700'], variable: '--font-gaegu', display: 'swap' });
const gowun = Gowun_Dodum({ subsets: ['latin'], weight: '400', variable: '--font-gowun', display: 'swap' });

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = await searchParams;
  const skin: SkinName = sp.skin === 'scrapbook' ? 'scrapbook' : 'yeoul';
  return (
    <div className={`${gaegu.variable} ${gowun.variable}`}>
      <TamagotchiScreen name={skin} />
    </div>
  );
}
