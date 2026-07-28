// 8. 푸터.
import Link from "next/link";
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
            <Link key={tab.href} href={tab.href} className={styles.footerLink}>
              {tab.label}
            </Link>
          ))}
        </div>

        <span className={styles.copyright}>
          © 2026 LORE — 우리만의 캐릭터로 만드는 만화
        </span>
      </div>
    </footer>
  );
}
