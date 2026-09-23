// Trailer 탭의 실제 화면. (담당: 병연)
// 라우팅 파일(apps/web/app/trailer/page.tsx)은 이 컴포넌트를 불러다 렌더링만 하므로,
// 화면 작업은 이 폴더(trailer/fe) 안에서만 하면 된다.
//
// 이 파일은 서버 컴포넌트다. 스타일을 불러오고 화면을 그리기만 한다.
// 화면의 동작은 모두 piece-maker/ 안의 클라이언트 컴포넌트에 있다.
import "./trailer.css";
import PieceMaker from "./piece-maker/PieceMaker";

export default function TrailerPage() {
  return <PieceMaker />;
}
