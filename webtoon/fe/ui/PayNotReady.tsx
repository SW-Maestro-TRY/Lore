"use client";

/**
 * 결제가 아직 안 열렸을 때의 안내 팝업(2026-10-26). 충전 창에서 결제하기를 누르면 창 안 오류 한 줄 대신 이 창을
 * 하나 더 띄워 1:1 문의로 보낸다 — 카카오톡 문의는 바로 열고, 이메일 문의는 부른 쪽이 문의 창으로 잇는다.
 */
import { CONTACT_CHANNEL } from "@common/links";
import { registerDict, useT } from "../lib/i18n";
import { track } from "../lib/track";
import { Dialog } from "./Dialog";
import { IconChevronRight, IconMail } from "./Icons";
import { KakaoTalkMark } from "./BrandMarks";
import "../screens/mypage/MyPage.css";

const SUPPORT_EMAIL = "lore@lorecomic.com";

registerDict({
  "결제 준비 중": { en: "Payments coming soon", ja: "決済準備中", zh: "支付准备中" },
  "현재 결제 서비스가 아직 준비 중에 있어요.": { en: "Payments aren't available yet.", ja: "現在、決済サービスはまだ準備中です。", zh: "支付服务仍在准备中。" },
  "1:1 문의하기를 통해 결제 문의를 진행해 주세요!": { en: "Please contact us 1:1 about payments!", ja: "1:1お問い合わせから決済についてご相談ください！", zh: "请通过一对一咨询联系我们处理支付问题！" },
});

export default function PayNotReady({ where, onEmail, onClose }: { where: string; onEmail: () => void; onClose: () => void }) {
  const t = useT();
  return (
    <Dialog className="wt-contact-dialog" title={t("결제 준비 중")} onClose={onClose}
            sub={<>{t("현재 결제 서비스가 아직 준비 중에 있어요.")}<br />{t("1:1 문의하기를 통해 결제 문의를 진행해 주세요!")}</>}>
      <div className="wt-my-contact">
        <a className="wt-contact-row" href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer"
           onClick={() => { track("contact_pick", { where, channel: "kakao", reason: "pay_not_ready" }); onClose(); }}>
          <span className="wt-contact-rowmark kakao"><KakaoTalkMark size={22} /></span>
          <span className="wt-contact-rowcopy"><b>{t("카카오톡 문의")}</b><small>{t("채팅으로 편하게 문의하기")}</small></span>
          <IconChevronRight size={19} />
        </a>
        <button type="button" className="wt-contact-row"
                onClick={() => { track("contact_pick", { where, channel: "email", reason: "pay_not_ready" }); onEmail(); }}>
          <span className="wt-contact-rowmark mail"><IconMail size={21} /></span>
          <span className="wt-contact-rowcopy"><b>{t("이메일 문의")}</b><small>{SUPPORT_EMAIL}</small></span>
          <IconChevronRight size={19} />
        </button>
      </div>
    </Dialog>
  );
}
