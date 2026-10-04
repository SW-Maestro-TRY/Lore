package com.lore.webtoon.runs;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 내려받는 파일 이름(#64) — {@code LORE_제목_1화.png}, 장 하나면 {@code LORE_제목_1화_3.png}.
 *
 * 예전에는 작품 번호({@code 20260919T153345-f366ce.png})만 썼다. 제목을 쓰면 한글·따옴표가
 * 브라우저마다 다르게 저장되기 때문이었다. 이제 두 이름을 함께 적는다 — 옛 브라우저가 읽는
 * {@code filename} 에는 지금처럼 작품 번호(영문)를, 요즘 브라우저가 먼저 읽는 {@code filename*}
 * 에는 UTF-8 로 인코딩한 한글 이름을 둔다(RFC 6266 · 5987). 같은 작품을 두 번 받아 이름이
 * 겹치면 브라우저가 알아서 번호를 붙인다.
 */
final class DownloadName {

    /** 제목이 너무 길면 자른다 — 파일 이름 길이 제한(보통 255 바이트)에 걸리지 않게. */
    static final int MAX_TITLE = 40;

    private DownloadName() {
    }

    /** {@code Content-Disposition} 머리 값 전체. {@code page} 가 null 이면 한 편, 아니면 그 장. */
    static String header(String runId, String title, Integer page) {
        String ascii = page == null ? runId + ".png" : runId + "-" + page + ".png";
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encode(name(title, page));
    }

    /** 사람이 보는 이름. 제목이 비면 {@code LORE_1화.png}. */
    static String name(String title, Integer page) {
        String t = clean(title);
        String base = t.isEmpty() ? "LORE_1화" : "LORE_" + t + "_1화";
        return page == null ? base + ".png" : base + "_" + page + ".png";
    }

    /** 파일 이름에 못 쓰는 글자(경로 구분자 · 따옴표 · 제어 문자 등)는 빼고, 공백은 하나로. */
    static String clean(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.codePointCount(0, t.length()) > MAX_TITLE) {
            t = t.substring(0, t.offsetByCodePoints(0, MAX_TITLE)).trim();
        }
        return t;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
