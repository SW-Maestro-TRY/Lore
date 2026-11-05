"use client";

/* 둘러보기 맨 위 띠의 두 번째 장 — LORE 앱 설치하기(2026-11-06).
 * 앱은 아직 없다. 단추를 누르면 출시 알림(50크레딧) + 지금 쓸 수 있는 홈 화면 추가 안내 창이 열린다.
 * 시안 비교 뒤 결정(2026-11-06): PC 는 왼쪽 글 + 오른쪽 폰 두 대. 폰 배치는 문피아 「크리에이터 작가앱 오픈」 배너를 따른다
 * (앞 웹툰 홈은 거의 정면, 뒤 웹툰 보기는 왼쪽으로 크게 돌려 세우고, 둘 사이에 앱 아이콘). 바탕·글은 LORE 어두운 띠 그대로,
 * 폰은 가운데 정렬(아이콘 · 제목 · 50크레딧 · 단추). 같은 마크업을 CSS 로 바꿔 보인다. */
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { AndroidMark, AppleMark, guessDevice, type AppDevice } from "../../ui/AppInstallDialog";
import "./AppBanner.css";

/** 실제 폰처럼 — 금속 테두리 · 옆 단추 · 상태 줄(시각 · 다이내믹 아일랜드 · 배터리) */
function Phone({ className, src }: { className: string; src: string }) {
  return (
    <div className={`phone ${className}`}>
      <div className="screen">
        <span className="status"><b>9:41</b><i className="island" /><i className="batt" /></span>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={src} alt="" />
      </div>
    </div>
  );
}

export default function AppBanner({ onOpen }: { onOpen: (d: AppDevice) => void }) {
  const t = useT();
  const open = (d: AppDevice, where = "button") => { track("app_banner_click", { device: d, where }); onOpen(d); };
  /* 띠 어디를 눌러도 창이 열린다 — 단추가 아닌 곳은 지금 기기를 짐작해 그 안내부터 연다 */
  const openAny = () => open(guessDevice(), "banner");
  return (
    <div className="wt-appb" role="button" tabIndex={0} aria-label={t("LORE 앱 설치하기")} onClick={openAny}
         onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openAny(); } }}>
      <div className="copy">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="icon" src="/icons/icon-192.png" alt="" />
        <span className="kicker"><i aria-hidden="true" />LORE APP</span>
        <h2>{t("LORE 앱 설치하기")}</h2>
        <p><b>50C</b>{t("출시 알림 신청하면 50크레딧")}</p>
        <div className="btns">
          <button type="button" onClick={(e) => { e.stopPropagation(); open("android"); }}><AndroidMark />Android</button>
          <button type="button" onClick={(e) => { e.stopPropagation(); open("ios"); }}><AppleMark />iPhone</button>
        </div>
      </div>
      {/* 폰 두 대 + 앱 아이콘 — 앞 왼쪽 웹툰 홈, 뒤 오른쪽 웹툰 보기(컷 확대). 실제 화면 캡처. 띠 아래로 잘려 나간다 */}
      <div className="phones" aria-hidden="true">
        <Phone className="read" src="/static/gallery/app-phone-read.jpg" />
        <Phone className="home" src="/static/gallery/app-phone-home.jpg" />
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="app" src="/icons/icon-192.png" alt="" />
      </div>
    </div>
  );
}
