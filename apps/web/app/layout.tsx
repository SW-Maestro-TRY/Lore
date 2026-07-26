// 앱 전체 공통 레이아웃.
// 여기도 "연결 파일" 성격이라, 공용 내비게이션은 common/fe 에서 가져다 쓴다.
import type { Metadata } from "next";
import Navigation from "@common/Navigation";
import "./globals.css";

export const metadata: Metadata = {
  title: "Lore",
  description: "사진을 업로드하면 캐릭터 카드/웹툰을 만들어주는 창작 플랫폼",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ko">
      <body>
        {/* 공용 상단 내비게이션 */}
        <Navigation />
        {/* 각 도메인 페이지 내용이 들어갈 자리 */}
        <main style={{ padding: "1.5rem" }}>{children}</main>
      </body>
    </html>
  );
}
