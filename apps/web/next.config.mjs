/** @type {import('next').NextConfig} */
//
// output: 'standalone'
//   배포 산출물을 .next/standalone 하나로 모은다. 서버에 node_modules 를
//   통째로 올리지 않아도 되고(수백 MB → 수십 MB), 서버에서 npm install 을
//   돌릴 필요가 없어 배포가 빨라진다.
//
//   ※ API 호출은 상대경로(/api/...)로 한다. 운영에서 CloudFront 가
//     같은 도메인 아래에서 /api/* 만 백엔드로 보내주므로 CORS 가 없다.
//     로컬 개발에서 백엔드가 8080 이면 그때만 rewrites 를 켜면 된다.
//
// rewrites — /webtoon 은 화면마다 다르다.
//   홈(`/webtoon` 그 자체)은 webtoon/fe/WebtoonPage.tsx 로 옮겨진 React
//   화면이다(2026-08-27, 담당: 하은) — 그래서 그 한 자리만 아래 목록에서
//   뺐다. 나머지(둘러보기·마이페이지·결과·편집실 등)는 아직 haeun/landing
//   의 프로토타입(순수 HTML/CSS/JS)이고, webtoon/fe/sync-landing.sh 가
//   public/static 으로 떠 온다. 화면을 하나씩 React 로 옮길 때마다 그
//   자리를 여기서도 빼면 된다.
const nextConfig = {
  output: 'standalone',

  // redirects — 옛 주소와 다른 로마자 표기를 살려 둔다.
  //   /comic 은 2026-08-26 에 /zzal 로 바뀌었다. 그 전에 공유된 링크가 죽지 않게 한다.
  //   /jjal 은 '짤'의 다른 로마자 표기다. 둘 다 실제로 쓰이므로 한쪽으로 모은다.
  async redirects() {
    return [
      { source: '/comic', destination: '/zzal', permanent: true },
      { source: '/jjal',  destination: '/zzal', permanent: true },
    ];
  },

  async rewrites() {
    // 프로토타입은 제 주소를 스스로 읽어 첫 화면을 고른다(web/app.js).
    // 그래서 화면마다 진짜 주소가 하나씩 있어야 한다 — 주소창에 그대로 뜨고,
    // 새로고침·뒤로가기·링크 공유가 된다.
    //
    // 세 화면(둘러보기·마이페이지·결과)이 같은 index.html 로 가는 것은
    // 원래 그렇게 만들어진 것이다. 한 파일 안에 여러 화면이 들어 있고
    // app.js 가 주소를 보고 그중 하나를 띄운다.
    const page = (path, file) => ({ source: `/webtoon${path}`, destination: `/static/${file}` });

    return {
      // beforeFiles — 라우트가 있든 없든 이쪽이 먼저 잡는다.
      // 나중에 /webtoon 아래에 React 화면이 생겨도 이 규칙이 계속 유효하도록.
      beforeFiles: [
        // page('', 'index.html') — 홈은 뺐다. React 화면(app/(domains)/webtoon)이 대신 잡는다.
        page('/works',        'index.html'),   // 둘러보기 (?run= 이 붙으면 그 작품)
        page('/mypage',       'index.html'),   // 마이페이지
        page('/result',       'index.html'),   // 마지막 결과
        page('/demo',         'demo.html'),    // 기다리는 화면 (목업)
        page('/demo/result',  'index.html'),   // 결과 (목업)
        page('/demo/mypage',  'index.html'),   // 마이페이지 (목업)
        page('/editor',       'editor.html'),  // 편집실
      ],
    };
  },
};

export default nextConfig;
