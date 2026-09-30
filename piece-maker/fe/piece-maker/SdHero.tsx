import Image from "next/image";
import adventurers from "../assets/sd-adventurers.png";

/** 승인한 SD 일러스트 한 장을 재사용하는 탐색 안내. */
export default function SdHero() {
  return (
    <section className="sd-hero" aria-labelledby="piece-maker-hero-title" data-part="sd-hero">
      <div className="sd-hero-copy">
        <p className="sd-hero-eyebrow">흩어진 단서가, 하나의 가설로.</p>
        <h1 id="piece-maker-hero-title">기억나는 단서를 모아<br />나만의 가설을 세워보세요.</h1>
        <p className="sd-hero-description">읽은 회차까지의 복선을 찾아, 내 생각과 연결해 보세요.</p>
      </div>
      <Image className="sd-hero-art" src={adventurers} alt="" width={264} height={120} priority />
    </section>
  );
}
