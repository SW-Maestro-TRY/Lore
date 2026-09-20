// 8. 푸터.
import TabLink from "../../TabLink";
import styles from "../landing.module.css";
import { TABS } from "../../links";

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
      </div>
    </footer>
  );
}
