/** @type {import('next').NextConfig} */
// Next.js 설정 자리 (지금은 기본값만, 추후 이미지 도메인/환경변수 등 추가).
//
// 각 도메인의 fe/ 가 이 폴더 바깥에 있지만 별도 설정은 필요 없다.
// 경로 별칭(@story, @comic, ...)은 tsconfig.json 의 paths 에서 처리하고,
// react/next 패키지 해석은 루트 package.json 의 npm workspaces 가 처리한다.
const nextConfig = {};

export default nextConfig;
