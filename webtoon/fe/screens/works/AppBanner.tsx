"use client";

/* 둘러보기 맨 위 띠의 두 번째 장 — LORE 앱 설치하기(2026-11-06).
 * 앱은 아직 없다. 단추를 누르면 출시 알림(50크레딧) + 지금 쓸 수 있는 홈 화면 추가 안내 창이 열린다.
 * 시안 셋(variant a·b·c)은 구성이 다르다 — a 가운데 정렬(참고 이미지 구성), b 왼쪽 글 + 오른쪽 폰 홈 화면,
 * c 50크레딧 동전을 앞세운 가로 띠. 고르면 나머지는 지운다. */
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { AndroidMark, AppleMark, type AppDevice } from "../../ui/AppInstallDialog";
import "./AppBanner.css";

export type AppBannerVariant = "a" | "b" | "c";

function Buttons({ open }: { open: (d: AppDevice) => void }) {
  return (
    <div className="wt-appb-btns">
      <button type="button" onClick={() => open("android")}><AndroidMark />Android</button>
      <button type="button" onClick={() => open("ios")}><AppleMark />iPhone</button>
    </div>
  );
}

export default function AppBanner({ variant, onOpen }: { variant: AppBannerVariant; onOpen: (d: AppDevice) => void }) {
  const t = useT();
  const open = (d: AppDevice) => { track("app_banner_click", { variant, device: d }); onOpen(d); };

  if (variant === "b") return (
    <div className="wt-appb wt-appb-b">
      <div className="copy">
        <span className="kicker"><i aria-hidden="true" />LORE APP</span>
        <h2>{t("LORE 앱 설치하기")}</h2>
        <p><b>50C</b>{t("출시 알림 신청하면 50크레딧")}</p>
        <Buttons open={open} />
      </div>
      <div className="phone" aria-hidden="true">
        <div className="screen">
          <span className="clock">9:41</span>
          <div className="apps">
            {Array.from({ length: 11 }, (_, i) => <i key={i} />)}
            <span className="lore">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src="/icons/icon-192.png" alt="" />
              <small>LORE</small>
            </span>
          </div>
        </div>
      </div>
    </div>
  );

  if (variant === "c") return (
    <div className="wt-appb wt-appb-c">
      <span className="coin" aria-hidden="true"><b>50</b><small>CREDIT</small></span>
      <div className="copy">
        <span className="kicker">LORE APP · COMING SOON</span>
        <h2>{t("앱 출시 알림 받고 50크레딧")}</h2>
      </div>
      <Buttons open={open} />
    </div>
  );

  return (
    <div className="wt-appb wt-appb-a">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="icon" src="/icons/icon-192.png" alt="" />
      <h2>{t("LORE 앱 설치하기")}</h2>
      <p>{t("출시 알림 신청하면 50크레딧")}</p>
      <Buttons open={open} />
    </div>
  );
}
