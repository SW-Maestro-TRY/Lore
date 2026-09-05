/* 그림체 카드에 뜨는 예시 그림.
 *
 * **여덟 그림체 모두 실제로 그 그림체로 뽑아 둔 것**이다. 한동안 파스텔·
 * 느와르·순정은 예시가 없어서 루 그림으로 자리만 채웠는데, 그러면 카드를
 * 보고 고르는 일이 성립하지 않는다 — 고르는 근거가 그림인데 그 그림이
 * 그 그림체가 아니었다.
 *
 * 주소는 원본(app.js 의 STYLE_THUMB)과 **같은 자리**를 가리킨다. 한동안
 * 여기로 사본을 떠 왔는데, 원본에서 그림을 바꿔도 사본이 안 따라와서 카드에
 * 옛 그림이 남았다(실제로 두 장이 그랬다). /static 은 sync-landing.sh 가
 * 빌드마다 원본에서 떠 오므로, 그 자리를 그대로 부르면 갈라질 일이 없다.
 */
export const STYLE_THUMB: Record<string, string> = {
  webtoon:   "/static/samples/ex-webtoon-1.jpg",
  romance:   "/static/samples/ex-romance-1.png",
  shoujo:    "/static/samples/ex-shoujo-1.jpg",
  frost:     "/static/samples/ex-frost-1.jpg",
  pastel:    "/static/samples/ex-pastel-1.jpg",
  noir:      "/static/samples/ex-noir-1.jpg",
  cinematic: "/static/samples/ex-cinematic-1.jpg",
  game:      "/static/samples/ex-game-1.jpg",
};
