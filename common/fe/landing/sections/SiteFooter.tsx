// 8. 푸터.
import TabLink from "../../TabLink";
import styles from "../landing.module.css";
import { TABS, LEGAL_LINKS, CONTACT_CHANNEL } from "../../links";
import { currentLang, translator } from "../i18n";

// tab.label 은 안 옮긴다 — TABS 는 공용 헤더(SiteHeader)도 같이 쓰는 값이라
// 여기서만 번역하면 헤더와 푸터가 서로 다른 언어로 갈린다.
const DICT = {
  "© 2026 LORE — 우리만의 캐릭터로 만드는 만화": {
    en: "© 2026 LORE — Comics made with our own characters",
    ja: "© 2026 LORE — 自分だけのキャラクターで作る漫画",
  },
  "이용약관": { en: "Terms of Service", ja: "利用規約" },
  "개인정보처리방침": { en: "Privacy Policy", ja: "プライバシーポリシー" },
  "1:1 문의": { en: "Contact us", ja: "お問い合わせ" },
};

export default async function SiteFooter() {
  const t = translator(await currentLang(), DICT);
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
          {t("© 2026 LORE — 우리만의 캐릭터로 만드는 만화")}
        </span>

        {/* 약관·처리방침·문의는 도메인 탭과 성격이 달라 줄을 나눈다. 처리방침은
            게시 의무가 있어서, 홈에서 한 번에 닿는 자리가 있어야 한다. */}
        <div className={styles.footerLegal}>
          <a className={styles.footerLink} href={LEGAL_LINKS.terms}>
            {t("이용약관")}
          </a>
          <a className={styles.footerLink} href={LEGAL_LINKS.privacy}>
            {t("개인정보처리방침")}
          </a>
          <a
            className={styles.footerLink}
            href={CONTACT_CHANNEL}
            target="_blank"
            rel="noopener noreferrer"
          >
            {t("1:1 문의")}
          </a>
        </div>
      </div>
    </footer>
  );
}
