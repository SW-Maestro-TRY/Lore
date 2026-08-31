import cinematic from "../assets/ex-cinematic-1.jpg";
import lineart from "../assets/ex-lineart-2.jpg";
import romance from "../assets/ex-romance-1.jpg";
import webtoon from "../assets/ex-webtoon-1.jpg";
import pastel from "../assets/world-begins.png";
import noir from "../assets/world-depth.png";
import shoujo from "../assets/guide-2.png";

// 그림체 카드 썸네일. pastel·noir·shoujo 는 아직 그 그림체로 뽑아 둔 예시가
// 없어서 루 그림으로 자리만 채운다 (원본 app.js의 STYLE_THUMB과 같은 사정).
export const STYLE_THUMB: Record<string, string> = {
  cinematic: cinematic.src,
  lineart: lineart.src,
  romance: romance.src,
  webtoon: webtoon.src,
  pastel: pastel.src,
  noir: noir.src,
  shoujo: shoujo.src,
};
