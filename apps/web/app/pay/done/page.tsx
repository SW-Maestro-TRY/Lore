import type { Metadata } from "next";
import PayDone from "./PayDone";

/**
 * 페이앱 결제창에서 돌아오는 자리(returnurl, #155).
 *
 * ★ 여기서 크레딧을 넣지 않는다. 이 주소는 누구나 열 수 있다 — 크레딧은 페이앱 →
 *   서버 통보로만 들어간다. 이 화면은 "끝났으니 창을 닫으라" 거나, 팝업이 막혀 같은
 *   탭으로 결제하러 갔던 사람에게 결과를 보여 주고 원래 자리로 돌려보낼 뿐이다.
 */
export const metadata: Metadata = {
  title: "결제 확인 — LORE",
  robots: { index: false, follow: false },
};

export default function Page() {
  return <PayDone />;
}
