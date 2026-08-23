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
const nextConfig = {
  output: 'standalone',
};

export default nextConfig;
