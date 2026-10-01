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

    /**
     * 다 만들어졌다. 장르나 캐릭터 이름이 비면 그 줄만 뺀다 — 빈 「」 를 보내지 않는다.
     */
    static Body finished(String title, String genre, String name, String link, String site) {
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
        return new Body(text.toString(), page(html.toString(), site));
    }

    /**
     * 못 만들었다. 제목을 아직 못 정했으면 「내 웹툰」 대신 그냥 「웹툰」이라고 쓴다.
     */
    static Body failed(String title, Refunded back, String link, String site) {
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
        text.append("잠시 후 다시 한 번 시도해 주세요!\n\n");
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
        lead.append("<br>잠시 후 다시 한 번 시도해 주세요!");
        html.append(p(lead.toString()));
        html.append(button("다시 시도하기 →", link));
        html.append(p("이번에는 루가 더 잘 만들어볼게요. ✨"));
        return new Body(text.toString(), page(html.toString(), site));
    }

    /* ---- 아래 공통 ---------------------------------------------------------- */

    private static String textFooter() {
        return "\n---\n"
                + "LORE\nAI 웹툰 서비스\n\n"
                + "문의: " + CONTACT + "\n"
                + "AI·SW마에스트로 17기\n"
                + "주소: " + ADDRESS + "\n"
                + "© 2026 LORE. All rights reserved.\n";
    }

    private static String page(String body, String site) {
        String logo = site + "/static/badges/asm-icon.png";
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>"
                + "<body style=\"margin:0;padding:0;background:" + BG + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + BG + ";\">"
                + "<tr><td align=\"center\" style=\"padding:32px 16px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:560px;"
                + "background:#ffffff;border:1px solid " + LINE + ";border-radius:16px;\">"
                + "<tr><td style=\"padding:36px 32px 28px;font-family:-apple-system,BlinkMacSystemFont,'Apple SD Gothic Neo',"
                + "'Malgun Gothic',sans-serif;color:" + INK + ";font-size:15px;line-height:1.7;\">"
                + body
                + "</td></tr>"
                + "<tr><td style=\"padding:20px 32px 28px;border-top:1px solid " + LINE + ";font-family:-apple-system,"
                + "BlinkMacSystemFont,'Apple SD Gothic Neo','Malgun Gothic',sans-serif;color:" + DIM + ";font-size:12px;line-height:1.7;\">"
                + "<div style=\"color:" + INK + ";font-size:14px;font-weight:800;\">LORE</div>"
                + "<div>AI 웹툰 서비스</div>"
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
