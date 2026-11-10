import type { Metadata } from "next";
import TestComic from "./test-comic";

export const metadata: Metadata = {
  title: "웹툰 테스트용 | LORE",
  robots: { index: false, follow: false },
};

export default function Page() { return <TestComic />; }
