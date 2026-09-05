import cinematic from "../assets/ex-cinematic-1.jpg";
import frost from "../assets/ex-frost-1.jpg";
import game from "../assets/ex-game-1.jpg";
import noir from "../assets/ex-noir-1.jpg";
import pastel from "../assets/ex-pastel-1.jpg";
import romance from "../assets/ex-romance-1.png";
import shoujo from "../assets/ex-shoujo-1.jpg";
import webtoon from "../assets/ex-webtoon-1.jpg";

/* 그림체 카드에 뜨는 예시 그림.
 *
 * **여덟 그림체 모두 실제로 그 그림체로 뽑아 둔 것**이다. 한동안 파스텔·
 * 느와르·순정은 예시가 없어서 루 그림으로 자리만 채웠는데, 그러면 카드를
 * 보고 고르는 일이 성립하지 않는다 — 고르는 근거가 그림인데 그 그림이
 * 그 그림체가 아니었다.
 *
 * 원본(app.js 의 STYLE_THUMB)은 /static/samples/ 를 주소로 부르고, 여기서는
 * 번들러가 해시를 붙이도록 import 한다 — 파일은 같은 것이다.
 */
export const STYLE_THUMB: Record<string, string> = {
  webtoon: webtoon.src,
  romance: romance.src,
  shoujo: shoujo.src,
  frost: frost.src,
  pastel: pastel.src,
  noir: noir.src,
  cinematic: cinematic.src,
  game: game.src,
};
