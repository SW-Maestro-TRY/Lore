// 8. 푸터.
import TabLink from "../../TabLink";
import styles from "../landing.module.css";
import { TABS, LEGAL_LINKS, CONTACT_CHANNEL } from "../../links";

export default function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={`${styles.container} ${styles.footerInner}`}>
        <span className={styles.footerMark}>
          LORE<span className={styles.markDot}>.</span>
        </span>

        <div className={styles.footerLinks}>
          {TABS.map((tab) => (
            <TabLink
              key={tab.href}
              href={tab.href}
              hardNav={tab.hardNav}
              className={styles.footerLink}
            >
              {tab.label}
            </TabLink>
          ))}
        </div>

        <span className={styles.copyright}>
          © 2026 LORE — 우리만의 캐릭터로 만드는 만화
        </span>

        {/* 약관·처리방침·문의는 도메인 탭과 성격이 달라 줄을 나눈다. 처리방침은
            게시 의무가 있어서, 홈에서 한 번에 닿는 자리가 있어야 한다. */}
        <div className={styles.footerLegal}>
          <a className={styles.footerLink} href={LEGAL_LINKS.terms}>
            이용약관
          </a>
          <a className={styles.footerLink} href={LEGAL_LINKS.privacy}>
            개인정보처리방침
          </a>
          <a
            className={styles.footerLink}
            href={CONTACT_CHANNEL}
            target="_blank"
            rel="noopener noreferrer"
          >
            1:1 문의
          </a>
        </div>
      </div>
    </footer>
  );
}
