// 푸터 — haeun/landing/web/index.html 의 <footer class="foot"> 를 그대로 옮겼다.
//
// 만든 곳 표시는 "이게 뭐지, 믿어도 되나" 피드백에 대응해 들어간 것이다.
// 사업자등록번호는 없다 — AI SW 마에스트로 과정의 프로젝트라 별도 법인이
// 아니고, 있지도 않은 번호를 지어 넣지 않는다.

import { LEGAL_LINKS, CONTACT_CHANNEL } from "@common/links";

const ORGS: [src: string, label: string, brand: boolean][] = [
  ["/static/badges/asm-icon.png", "AI SW MAESTRO", true],
  ["/static/badges/msit-icon.png", "과학기술정보통신부", false],
  ["/static/badges/iitp-icon.png", "정보통신기획평가원(IITP)", false],
];

export default function Foot() {
  return (
    <footer className="foot">
      <div className="foot-brand">
        <span>LORE 웹툰 스튜디오</span>
        <span className="foot-note">
          고래 <b>루</b>가 안내합니다 · 사진 한 장이 웹툰 한 화가 됩니다
        </span>
        <nav className="foot-legal">
          <a href={LEGAL_LINKS.terms} target="_blank" rel="noopener noreferrer">이용약관</a>
          <a href={LEGAL_LINKS.privacy} target="_blank" rel="noopener noreferrer">개인정보처리방침</a>
          <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">1:1 문의</a>
        </nav>
      </div>
      <div className="foot-org">
        {ORGS.map(([src, label, brand]) => (
          <p className={`foot-org-line${brand ? " foot-org-brand" : ""}`} key={label}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={src} alt="" className="foot-org-icon" />
            <span>{label}</span>
          </p>
        ))}
      </div>
    </footer>
  );
}
