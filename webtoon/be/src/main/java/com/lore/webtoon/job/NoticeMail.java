package com.lore.webtoon.job;

/**
 * 완성·실패 알림 메일의 문구와 모양. 보낼지 말지는 {@link JobNotice} 가 정하고,
 * 여기는 무엇을 적는지만 맡는다.
 *
 * <h2>HTML 과 글자 본문을 같이 만든다</h2>
 *
 * 메일 앱마다 HTML 을 그리기도 하고 안 그리기도 한다. 둘 다 같은 말을 해야
 * 하므로 한 자리에서 같이 만든다.
 *
 * <h2>HTML 은 표와 인라인 스타일로만</h2>
 *
 * 메일 앱은 {@code <style>}·flex·grid 를 버리는 곳이 많다. 옛 방식(표 + style 속성)이
 * 어디서나 같은 모양으로 나온다.
 *
 * <h2>소마 로고는 사이트에서 불러온다</h2>
 *
 * 메일 안의 그림은 전체 주소여야 한다 — 상대경로는 메일 앱이 못 찾는다. 랜딩 아래쪽이
 * 쓰는 {@code /static/badges/asm-icon.png} 를 그대로 쓴다. 로컬에서 보낸 메일은
 * localhost 를 가리켜 로고가 안 나오는데, 배포 서버에서는 나온다.
 */
final class NoticeMail {

    /** 사람이 읽는 본문 둘. */
    record Body(String text, String html) {
    }

    /** 실패 메일에서 무엇을 돌려줬는지. */
    static String refundLine(Refunded back) {
        if (back == Refunded.CREDIT) {
            return "사용하신 크레딧은 환불되었어요.";
        }
        if (back == Refunded.FREE) {
            return "사용하신 무료 생성 횟수는 복구되었어요.";
        }
        return "";
    }

    private static final String INK = "#0f333f";
    private static final String DIM = "#6b7f86";
    private static final String LINE = "#e3ebe8";
    private static final String BG = "#f4f8f6";

    static final String CONTACT = "lightbluue6@gmail.com";
    static final String ADDRESS = "서울특별시 마포구 마포대로 89 포스트타워 7층, 12층 (우편번호 04156)";

    private NoticeMail() {
    }

    static final String SERVICE = "개인 IP 창작 서비스, LORE";

    /** 바닥글 LORE 아래 한 줄. */
    static final String TAGLINE = "개인 IP 중심 AI 웹툰 스튜디오";

    /**
     * 다 만들어졌다. 장르나 캐릭터 이름이 비면 그 줄만 뺀다 — 빈 「」 를 보내지 않는다.
     *
     * @param cover 표지(1장) 그림의 전체 주소
     */
    static Body finished(String title, String genre, String name, String link, String cover, String site) {
        String t = "「" + title + "」";
        String g = genre == null ? "" : genre.trim();
        String n = name == null ? "" : name.trim();

        StringBuilder text = new StringBuilder();
        text.append("안녕하세요! 루예요. 👋\n\n");
        text.append("내 캐릭터가 웹툰으로!\n\n");
        text.append("기다리던 웹툰 ").append(t).append(iGa(title)).append(" 완성되었어요.\n");
        if (!g.isEmpty()) {
            text.append(g).append(" 이야기를 담은 ").append(t).append(", 이제 웹툰으로 만나보세요!\n");
        }
        if (!n.isEmpty()) {
            text.append("\n「").append(n).append("」").append(iGa(n)).append(" 지금 당신을 기다리고 있어요.\n");
        }
        text.append("\n웹툰 보러가기 → ").append(link).append("\n\n");
        text.append("루와 함께,\n당신의 캐릭터로 새로운 이야기를 만들어보세요. ✨\n");
        text.append(textFooter());

        StringBuilder html = new StringBuilder();
        html.append(p("안녕하세요! 루예요. 👋"));
        html.append(heading("내 캐릭터가 웹툰으로!"));
        StringBuilder lead = new StringBuilder();
        lead.append("기다리던 웹툰 <b>").append(esc(t)).append("</b>").append(iGa(title)).append(" 완성되었어요.");
        if (!g.isEmpty()) {
            lead.append("<br><b>").append(esc(g)).append(" 이야기를 담은 ").append(esc(t))
                    .append("</b>, 이제 웹툰으로 만나보세요!");
        }
        html.append(p(lead.toString()));
        if (!n.isEmpty()) {
            html.append(p("<b>「" + esc(n) + "」</b>" + iGa(n) + " 지금 당신을 기다리고 있어요."));
        }
        html.append(button("웹툰 보러가기 →", link));
        html.append(p("루와 함께,<br>당신의 캐릭터로 새로운 이야기를 만들어보세요. ✨"));
        return new Body(text.toString(), page(mintHead(cover, site), html.toString(), site));
    }

    /**
     * 못 만들었다. 제목을 아직 못 정했으면 「내 웹툰」 대신 그냥 「웹툰」이라고 쓴다.
     */
    static Body failed(String title, Refunded back, String link, String site) {
        return failed(title, back, link, site, null);
    }

    /**
     * @param reason 다시 해도 같은 결과가 날 실패(안전 기준 · 글 모델 거절)의 사람용 이유(#626). 있으면
     *               「잠시 후 다시 한 번 시도해 주세요!」 대신 이 문장을 적는다 — 같은 내용이면 또 걸린다.
     */
    static Body failed(String title, Refunded back, String link, String site, String reason) {
        String refund = refundLine(back);
        boolean titled = title != null && !title.isBlank();

        StringBuilder text = new StringBuilder();
        text.append("안녕하세요! 루예요. 👋\n\n");
        text.append("웹툰을 만들던 중 아쉽게도 문제가 발생했어요.\n\n");
        text.append(titled ? "요청하신 「" + title + "」" + eulReul(title) : "요청하신 웹툰을")
                .append(" 완성하지 못했어요.\n");
        if (!refund.isEmpty()) {
            text.append(refund).append("\n");
        }
        text.append(reason == null || reason.isBlank() ? "잠시 후 다시 한 번 시도해 주세요!" : reason.trim()).append("\n\n");
        text.append("다시 시도하기 → ").append(link).append("\n\n");
        text.append("이번에는 루가 더 잘 만들어볼게요. ✨\n");
        text.append(textFooter());

        StringBuilder html = new StringBuilder();
        html.append(p("안녕하세요! 루예요. 👋"));
        html.append(p("웹툰을 만들던 중 <b>아쉽게도 문제가 발생했어요.</b>"));
        StringBuilder lead = new StringBuilder();
        lead.append(titled ? "요청하신 <b>「" + esc(title) + "」</b>" + eulReul(title) : "요청하신 웹툰을")
                .append(" 완성하지 못했어요.");
        if (!refund.isEmpty()) {
            lead.append("<br>").append(esc(refund));
        }
        lead.append("<br>").append(reason == null || reason.isBlank() ? "잠시 후 다시 한 번 시도해 주세요!" : esc(reason.trim()));
        html.append(p(lead.toString()));
        html.append(button("다시 시도하기 →", link));
        html.append(p("이번에는 루가 더 잘 만들어볼게요. ✨"));
        return new Body(text.toString(), page(failHead(site), html.toString(), site));
    }

    /**
     * 다 못 만들었지만 <b>사람이 고치면 이어 갈 수 있다</b>(#626) — 캐릭터 시트가 안전 기준에 걸려
     * 멈췄거나, 장면 몇 장만 걸려 빠진 채 완성됐을 때. 실패 메일과 같은 머리를 쓰되, 「잠시 후 다시
     * 시도해 주세요」가 아니라 무엇을 고치면 되는지(why)를 적는다 — 같은 내용으로 다시 하면 또 걸린다.
     *
     * @param why  사람에게 보여 준 문장 그대로(진행 화면과 같은 말)
     * @param button 버튼 글자 · 예: 「캐릭터 다시 그리러 가기 →」
     */
    static Body needsFix(String title, String why, String button, String link, String site) {
        boolean titled = title != null && !title.isBlank();
        String reason = why == null ? "" : why.trim();
        StringBuilder text = new StringBuilder();
        text.append("안녕하세요! 루예요. 👋\n\n");
        text.append(titled ? "요청하신 「" + title + "」" + eulReul(title) : "요청하신 웹툰을")
                .append(" 만들던 중 확인이 필요한 일이 생겼어요.\n\n");
        text.append(reason).append("\n\n");
        text.append(button).append(" ").append(link).append("\n");
        text.append(textFooter());
        StringBuilder html = new StringBuilder();
        html.append(p("안녕하세요! 루예요. 👋"));
        html.append(p((titled ? "요청하신 <b>「" + esc(title) + "」</b>" + eulReul(title) : "요청하신 웹툰을")
                + " 만들던 중 <b>확인이 필요한 일이 생겼어요.</b>"));
        html.append(p(esc(reason)));
        html.append(button(button, link));
        return new Body(text.toString(), page(failHead(site), html.toString(), site));
    }

    /* ---- 아래 공통 ---------------------------------------------------------- */

    /**
     * 관리자 처리 안내(#638) — 비공개 · 삭제 · 경고. 약관의 「조치 사실을 이용자에게 통지」 자리다.
     *
     * 루가 말하는 다른 메일과 달리 <b>운영팀이 말한다</b> — 잘못을 알리는 글에 마스코트가 웃고 있으면 가볍게 읽힌다.
     *
     * @param action HIDE · REMOVE · WARN
     * @param keepDays 삭제 처리 뒤 완전히 지우기까지 남는 날 수(REMOVE 일 때만 씀)
     */
    static Body moderated(String title, String action, String reason, int keepDays, String link, String site) {
        boolean titled = title != null && !title.isBlank();
        String work = titled ? "「" + title + "」" : "웹툰";
        String head;
        String lead;
        String after;
        switch (action) {
            case "HIDE" -> {
                head = "작품이 비공개 처리되었어요";
                lead = "회원님의 웹툰 " + work + (titled ? iGa(title) : "이") + " 운영 정책에 따라 비공개 처리되었어요.";
                after = "지금은 다른 사람에게 보이지 않고, 회원님은 마이페이지에서 계속 볼 수 있어요.";
            }
            case "REMOVE" -> {
                head = "작품이 삭제 처리되었어요";
                lead = "회원님의 웹툰 " + work + (titled ? iGa(title) : "이") + " 운영 정책에 따라 삭제 처리되었어요.";
                after = "작품은 " + keepDays + "일 동안 보관된 뒤 완전히 지워져요. 그 안에 알려 주시면 다시 살펴볼게요.";
            }
            default -> {
                head = "운영 정책 경고 안내";
                lead = "회원님의 웹툰 " + work + "에 대해 운영 정책 경고를 드려요.";
                after = "같은 일이 반복되면 이용약관에 따라 작품이 비공개 · 삭제되거나 서비스 이용이 제한될 수 있어요.";
            }
        }
        String why = reason == null || reason.isBlank() ? "" : reason.trim();
        String ask = "처리에 이의가 있으시면 이 메일에 답장하시거나 " + CONTACT + " 로 알려 주세요.";

        StringBuilder text = new StringBuilder();
        text.append("안녕하세요, LORE 운영팀입니다.\n\n");
        text.append(lead).append("\n");
        if (!why.isEmpty()) {
            text.append("사유: ").append(why).append("\n");
        }
        text.append("\n").append(after).append("\n");
        text.append(ask).append("\n\n");
        text.append("마이페이지에서 보기 → ").append(link).append("\n");
        text.append(textFooter());

        StringBuilder html = new StringBuilder();
        html.append(p("안녕하세요, LORE 운영팀입니다."));
        html.append(p(esc(lead).replace(esc(work), "<b>" + esc(work) + "</b>")));
        if (!why.isEmpty()) {
            html.append("<p style=\"margin:0 0 18px;padding:14px 16px;background:" + BG + ";border-radius:10px;\">"
                    + "<b>사유</b><br>" + esc(why) + "</p>");
        }
        html.append(p(esc(after)));
        html.append(p("처리에 이의가 있으시면 이 메일에 답장하시거나 <a href=\"mailto:" + CONTACT + "\" style=\"color:" + INK + ";\">"
                + CONTACT + "</a> 로 알려 주세요."));
        html.append(button("마이페이지에서 보기 →", link));
        return new Body(text.toString(), page(plainHead(head), html.toString(), site));
    }

    /** 그림 없는 머리글 — 운영 안내용. */
    private static String plainHead(String headline) {
        return "<tr><td style=\"padding:0;\">"
                + "<div style=\"background:#e4ece9;border-radius:16px 16px 0 0;padding:26px 32px 22px;" + FONT + "color:" + INK + ";\">"
                + "<div style=\"font-size:12px;font-weight:700;letter-spacing:.08em;color:#3f7d6d;\">LORE</div>"
                + "<div style=\"font-size:22px;font-weight:800;line-height:1.35;margin-top:10px;\">" + esc(headline) + "</div>"
                + "</div></td></tr>";
    }

    private static String textFooter() {
        return "\n---\n"
                + "LORE\n" + TAGLINE + "\n\n"
                + "문의: " + CONTACT + "\n"
                + "AI·SW마에스트로 17기\n"
                + "주소: " + ADDRESS + "\n"
                + "© 2026 LORE. All rights reserved.\n";
    }

    /* ---- 머리 ---------------------------------------------------------------
       메일 앱에서 겹치기·그라데이션·배경 그림은 깨지므로 표와 그림만 쓴다. 루 그림은
       화면과 같은 /static/lou/ 에서 부른다. */

    private static final String FONT = "font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;";

    private static String mintHead(String cover, String site) {
        return "<tr><td style=\"padding:0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#d9ebe5;"
                + "border-radius:16px 16px 0 0;\"><tr>"
                + "<td width=\"200\" style=\"padding:32px 0 32px 32px;vertical-align:top;\">"
                + "<img src=\"" + esc(cover) + "\" width=\"168\" height=\"252\" alt=\"\" style=\"display:block;border:0;"
                + "width:168px;height:252px;border-radius:12px;\"></td>"
                + "<td style=\"padding:36px 28px 20px 24px;vertical-align:top;" + FONT + "color:" + INK + ";\">"
                + "<div style=\"font-size:12px;font-weight:700;letter-spacing:.08em;color:#3f7d6d;\">LORE</div>"
                + "<div style=\"font-size:24px;font-weight:800;line-height:1.35;margin-top:10px;\">내 캐릭터를<br>살아 움직이게.</div>"
                + "<div style=\"font-size:13px;color:#3f6a60;margin-top:8px;\">" + esc(SERVICE) + "</div>"
                + "<img src=\"" + esc(site + "/static/lou/hero-whale1.png") + "\" width=\"210\" alt=\"루\" style=\"display:block;"
                + "border:0;width:210px;height:auto;margin-top:22px;\"></td>"
                + "</tr></table></td></tr>";
    }

    private static String failHead(String site) {
        /* **휴대폰에서는 루가 위, 문구가 아래(사용자 요청).** 예전에는 표 한 줄에 그림 칸(250px)과 문구 칸을
           나란히 두어서, 좁은 화면에서 둘이 억지로 붙어 깨졌다. 두 칸을 inline-block 으로 두면 넓은 화면에서는
           나란히, 자리가 모자라면 그림 아래로 문구가 내려간다 — 미디어 쿼리를 무시하는 메일 앱에서도.
           가운데 정렬만 page() 의 미디어 쿼리(.lore-head)가 맡는다. */
        return "<tr><td style=\"padding:0;\">"
                + "<div class=\"lore-head\" style=\"background:#e4ece9;border-radius:16px 16px 0 0;"
                + "padding:26px 20px 22px;font-size:0;text-align:left;\">"
                + "<div class=\"lore-head-art\" style=\"display:inline-block;vertical-align:middle;width:230px;max-width:100%;\">"
                + "<img src=\"" + esc(site + "/static/lou/art/error-2.png") + "\" width=\"210\" alt=\"루\" style=\"display:inline-block;"
                + "border:0;width:210px;max-width:100%;height:auto;\"></div>"
                + "<div class=\"lore-head-text\" style=\"display:inline-block;vertical-align:middle;width:260px;max-width:100%;"
                + "padding:10px 8px 4px;box-sizing:border-box;text-align:left;" + FONT + "color:" + INK + ";\">"
                + "<div style=\"font-size:12px;font-weight:700;letter-spacing:.08em;color:#3f7d6d;\">LORE</div>"
                + "<div style=\"font-size:22px;font-weight:800;line-height:1.35;margin-top:10px;\">이번엔 루가<br>완성하지 못했어요.</div>"
                + "<div style=\"font-size:13px;color:#3f6a60;margin-top:8px;\">" + esc(SERVICE) + "</div></div>"
                + "</div></td></tr>";
    }

    private static String page(String head, String body, String site) {
        String logo = site + "/static/badges/asm-icon.png";
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                /* 좁은 화면에서 실패 메일 머리(루 · 문구)를 가운데로. 지원 안 하는 앱에서도 위아래로는 쌓인다. */
                + "<style>@media only screen and (max-width:520px){.lore-head{text-align:center!important;}"
                + ".lore-head-art,.lore-head-text{display:block!important;width:100%!important;margin:0 auto!important;}"
                + ".lore-head-text{text-align:center!important;padding-top:14px!important;}}</style></head>"
                + "<body style=\"margin:0;padding:0;background:" + BG + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + BG + ";\">"
                + "<tr><td align=\"center\" style=\"padding:32px 16px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;"
                + "background:#ffffff;border:1px solid " + LINE + ";border-radius:16px;\">"
                + head
                + "<tr><td style=\"padding:32px 32px 28px;font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',"
                + "'Malgun Gothic',sans-serif;color:" + INK + ";font-size:15px;line-height:1.7;\">"
                + body
                + "</td></tr>"
                + "<tr><td style=\"padding:20px 32px 28px;border-top:1px solid " + LINE + ";font-family:-apple-system,"
                + "BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;color:" + DIM + ";font-size:12px;line-height:1.7;\">"
                + "<div style=\"color:" + INK + ";font-size:14px;font-weight:800;\">LORE</div>"
                + "<div>" + esc(TAGLINE) + "</div>"
                + "<div style=\"margin-top:8px;\">문의: <a href=\"mailto:" + CONTACT + "\" style=\"color:" + DIM + ";\">"
                + CONTACT + "</a></div>"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin-top:14px;\"><tr>"
                + "<td style=\"padding-right:10px;vertical-align:middle;\"><img src=\"" + esc(logo) + "\" width=\"61\" height=\"22\""
                + " alt=\"AI·SW마에스트로\" style=\"display:block;border:0;\"></td>"
                + "<td style=\"vertical-align:middle;color:" + DIM + ";font-size:12px;\">AI·SW마에스트로 17기</td>"
                + "</tr></table>"
                + "<div style=\"margin-top:8px;\">주소: " + esc(ADDRESS) + "</div>"
                + "<div style=\"margin-top:8px;\">© 2026 LORE. All rights reserved.</div>"
                + "</td></tr></table>"
                + "</td></tr></table></body></html>";
    }

    private static String p(String inner) {
        return "<p style=\"margin:0 0 18px;\">" + inner + "</p>";
    }

    private static String heading(String s) {
        return "<p style=\"margin:0 0 18px;font-size:20px;font-weight:800;\">" + esc(s) + "</p>";
    }

    private static String button(String label, String href) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:6px 0 24px;\"><tr>"
                + "<td style=\"background:" + INK + ";border-radius:999px;\">"
                + "<a href=\"" + esc(href) + "\" style=\"display:inline-block;padding:13px 26px;color:#ffffff;"
                + "font-size:15px;font-weight:700;text-decoration:none;\">" + esc(label) + "</a>"
                + "</td></tr></table>";
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /* ---- 조사 ---------------------------------------------------------------
       제목은 사람이 정한 글이라 받침이 있을지 없을지 모른다. 마지막 글자가 한글이
       아니면(영문·숫자·기호) 받침을 알 수 없어 「이(가)」처럼 둘 다 적는다. */

    static String iGa(String word) {
        Boolean b = batchim(word);
        return b == null ? "이(가)" : b ? "이" : "가";
    }

    static String eulReul(String word) {
        Boolean b = batchim(word);
        return b == null ? "을(를)" : b ? "을" : "를";
    }

    private static Boolean batchim(String word) {
        if (word == null) {
            return null;
        }
        String w = word.strip();
        if (w.isEmpty()) {
            return null;
        }
        char c = w.charAt(w.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) {
            return null;
        }
        return (c - 0xAC00) % 28 != 0;
    }
}
