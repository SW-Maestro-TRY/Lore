// /webtoon 전용 레이아웃 — 앰플리튜드를 웹툰 화면에서만 켜려고 둔다(#966).
import { Amplitude } from "./amplitude";

export default function WebtoonLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <Amplitude />
      {children}
    </>
  );
}
