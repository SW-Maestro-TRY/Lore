"use client";

/* 무료 편수가 다 찼거나 오늘 전체가 마감됐을 때 — 캔버스 GuestLimit · MGuestLimit.
 * 만들기 시작이 막혔을 때 위자드가 띄울 수 있게 따로 둔다.
 *   loggedIn=false → 게스트(로그인하면 이어서 만들 수 있다)
 *   loggedIn=true  → 전체 마감(로그인해도 안 풀린다 — 둘러보기 하나만) */
import type { Go } from "../../lib/nav";
import { louArt } from "../../lib/louArt";
import { MobileTop } from "../../ui/TopNav";
import { useT } from "../../lib/i18n";
import "./i18n";
import "./Progress.css";

export default function GuestLimit({ reason, loggedIn, go }: { reason: string; loggedIn: boolean; go: Go }) {
  const art = louArt("empty");
  const t = useT();
  return (
    <div className="wt-prog">
      <MobileTop back={{ href: "", onClick: () => go("landing") }} title={t("만들기")} />
      <div className="wt-wrap wt-page">
        <div className="wt-prog-limit">
          {!loggedIn ? (
            <div className="card">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={art} alt="" />
              <span className="num">{t("게스트")}</span>
              <h2>{t("오늘 무료 편수를 다 썼어요")}</h2>
              <span className="muted">
                {reason ? `${reason} ` : ""}{t("게스트는 하루 2편까지 무료예요. 로그인하면 크레딧으로 바로 이어서 만들 수 있어요. 지금까지 만든 것은 그대로 남아요.")}
              </span>
              <button type="button" className="btn btn-p" onClick={() => go("mypage")}>{t("로그인하고 이어서 만들기")}</button>
              <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기 하며 내일 다시")}</button>
            </div>
          ) : (
            <div className="card">
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={art} alt="" style={{ opacity: 0.7 }} />
              <span className="num" style={{ color: "#a13a2e" }}>{t("전체 마감")}</span>
              <h2>{t("오늘은 여기까지예요")}</h2>
              <span className="muted">{reason || t("오늘 만들 수 있는 전체 편수가 찼어요. 자정이 지나면 다시 만들 수 있어요.")}</span>
              <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기 하며 기다리기")}</button>
            </div>
          )}
        </div>
      </div>
      <div className="mfoot">
        {!loggedIn ? (
          <>
            <button type="button" className="btn btn-p" onClick={() => go("mypage")}>{t("로그인하고 이어서 만들기")}</button>
            <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기 하며 내일 다시")}</button>
          </>
        ) : (
          <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기 하며 기다리기")}</button>
        )}
      </div>
    </div>
  );
}
