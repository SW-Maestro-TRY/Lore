/* LORE 서비스워커 (#599) — 웹푸시를 받아 알림을 띄우고, 누르면 그 화면을 연다.
 *
 * ★ 페이지 요청(fetch)은 가로채지 않는다. 범위가 "/" 라 웹툰 말고 다른 탭(짤·예고편)
 *   까지 걸리는데, 캐시를 넣으면 배포한 화면이 옛것으로 남는 사고가 날 수 있다.
 *   여기는 알림만 한다.
 *
 * 푸시 본문은 서버(webtoon/be 의 JobPush)가 보내는 JSON 이다:
 *   { title, body, url, tag }
 */

self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = { body: event.data ? event.data.text() : "" };
  }
  /* 받았으면 반드시 알림을 띄운다 — 안 띄우면 크롬·사파리가 구독을 끊을 수 있다. */
  event.waitUntil(
    self.registration.showNotification(data.title || "LORE", {
      body: data.body || "",
      tag: data.tag || undefined,       // 같은 작업의 알림은 새것으로 갈아 끼운다
      renotify: !!data.tag,
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      data: { url: data.url || "/webtoon" },
    }),
  );
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(event.notification.data?.url || "/webtoon", self.location.origin).href;
  event.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    /* 이미 열린 LORE 창이 있으면 그 창을 그 주소로 옮긴다 — 창이 계속 늘어나지 않게. */
    for (const w of wins) {
      if (new URL(w.url).origin === self.location.origin && "focus" in w) {
        await w.focus();
        if ("navigate" in w) await w.navigate(target);
        return;
      }
    }
    await self.clients.openWindow(target);
  })());
});
