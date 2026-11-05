"use client";

/* 둘러보기 맨 위 띠의 두 번째 장 — LORE 앱 설치하기(2026-11-06).
 * 앱은 아직 없다. 단추를 누르면 출시 알림(50크레딧) + 지금 쓸 수 있는 홈 화면 추가 안내 창이 열린다.
 * 시안 비교 뒤 결정(2026-11-06): PC 는 왼쪽 글 + 오른쪽 비스듬한 폰 두 대(둘러보기 탭 · 웹툰 보기 화면 캡처, 배치 시안 art 1~4),
 * 폰은 가운데 정렬(아이콘 · 제목 · 50크레딧 · 단추). 같은 마크업을 CSS 로 바꿔 보인다. */
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { AndroidMark, AppleMark, type AppDevice } from "../../ui/AppInstallDialog";
import "./AppBanner.css";

export type AppBannerArt = 1 | 2 | 3 | 4;

export default function AppBanner({ art = 1, onOpen }: { art?: AppBannerArt; onOpen: (d: AppDevice) => void }) {
  const t = useT();
  const open = (d: AppDevice) => { track("app_banner_click", { device: d }); onOpen(d); };
  return (
    <div className={`wt-appb wt-appb-art${art}`}>
      <div className="copy">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="icon" src="/icons/icon-192.png" alt="" />
        <span className="kicker"><i aria-hidden="true" />LORE APP</span>
        <h2>{t("LORE 앱 설치하기")}</h2>
        <p><b>50C</b>{t("출시 알림 신청하면 50크레딧")}</p>
        <div className="btns">
          <button type="button" onClick={() => open("android")}><AndroidMark />Android</button>
          <button type="button" onClick={() => open("ios")}><AppleMark />iPhone</button>
        </div>
      </div>
      {/* 폰 두 대 — 둘러보기 탭과 웹툰 보는 화면(실제 화면 캡처). 배치 시안은 art 1~4 */}
      <div className="phones" aria-hidden="true">
        <div className="phone browse">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/static/gallery/app-phone-browse.jpg" alt="" />
        </div>
        <div className="phone read">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src="/static/gallery/app-phone-read.jpg" alt="" />
        </div>
      </div>
    </div>
  );
}
