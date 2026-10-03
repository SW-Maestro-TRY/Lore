package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 알림 메일 문구 — 빈 칸을 안 보내고, 받침에 맞는 조사를 쓰고, 이름에 든 꺾쇠가 HTML 을 깨지 않는다. */
class NoticeMailTest {

    private static final String SITE = "https://lorecomic.com";
    private static final String COVER = SITE + "/api/webtoon/v1/runs/r1/page/1";

    @Test
    @DisplayName("완성 메일 — 제목·장르·캐릭터·링크가 두 본문에 다 들어간다")
    void 완성_메일() {
        NoticeMail.Body b = NoticeMail.finished("봉인의 기억", "판타지", "루다", SITE + "/webtoon?run=r1", COVER, SITE);

        assertThat(b.text()).contains("기다리던 웹툰 「봉인의 기억」이 완성되었어요.")
                .contains("판타지 이야기를 담은 「봉인의 기억」")
                .contains("「루다」가 지금 당신을 기다리고 있어요.")
                .contains(SITE + "/webtoon?run=r1");
        assertThat(b.html()).contains("href=\"" + SITE + "/webtoon?run=r1\"")
                .contains("웹툰 보러가기")
                .contains(SITE + "/static/badges/asm-icon.png");
    }

    @Test
    @DisplayName("완성 메일 머리는 민트 하나 — 표지·루·문구를 싣고, 실패 메일은 시무룩한 루")
    void 머리_모양() {
        String html = NoticeMail.finished("바다", "", "", SITE, COVER, SITE).html();
        assertThat(html).contains("src=\"" + COVER + "\"")
                .contains(SITE + "/static/lou/hero-whale1.png")
                .contains("살아 움직이게.").contains(NoticeMail.SERVICE)
                .contains("#d9ebe5").doesNotContain("#f5e7d3");
        assertThat(NoticeMail.failed("바다", Refunded.CREDIT, SITE, SITE).html())
                .contains(SITE + "/static/lou/art/error-2.png").contains("이번엔 루가");
    }

    @Test
    @DisplayName("장르·캐릭터 이름이 비면 그 줄을 뺀다 — 빈 「」 를 보내지 않는다")
    void 빈_줄은_뺀다() {
        NoticeMail.Body b = NoticeMail.finished("바다", "", " ", SITE + "/webtoon", COVER, SITE);

        assertThat(b.text()).contains("「바다」가 완성되었어요.")
                .doesNotContain("이야기를 담은")
                .doesNotContain("「」");
    }

    @Test
    @DisplayName("바닥글은 주소만 — 사업자 정보는 싣지 않는다")
    void 바닥글() {
        NoticeMail.Body b = NoticeMail.finished("바다", "", "", SITE, COVER, SITE);

        assertThat(b.text()).contains("LORE\n개인 IP 중심 AI 웹툰 스튜디오").contains(NoticeMail.ADDRESS)
                .contains("AI·SW마에스트로 17기").doesNotContain("AI 웹툰 서비스").doesNotContain("사업자");
        assertThat(b.html()).contains("<div>개인 IP 중심 AI 웹툰 스튜디오</div>").contains("마포대로 89")
                .doesNotContain("AI 웹툰 서비스").doesNotContain("사업자");
    }

    @Test
    @DisplayName("실패 메일 — 돌려준 것만 적고, 제목이 없으면 「웹툰을」")
    void 실패_메일() {
        NoticeMail.Body credit = NoticeMail.failed("봉인의 기억", Refunded.CREDIT, SITE + "/webtoon", SITE);
        assertThat(credit.text()).contains("요청하신 「봉인의 기억」을 완성하지 못했어요.")
                .contains("크레딧은 환불되었어요");

        NoticeMail.Body none = NoticeMail.failed("", Refunded.NONE, SITE + "/webtoon", SITE);
        assertThat(none.text()).contains("요청하신 웹툰을 완성하지 못했어요.")
                .doesNotContain("환불").doesNotContain("복구");
    }

    @Test
    @DisplayName("이름에 든 꺾쇠·따옴표는 HTML 로 새지 않는다")
    void 이스케이프() {
        NoticeMail.Body b = NoticeMail.finished("<b>제목</b>", "", "\"루\"", SITE, COVER, SITE);

        assertThat(b.html()).doesNotContain("<b>제목</b>").contains("&lt;b&gt;제목&lt;/b&gt;")
                .contains("&quot;루&quot;");
    }

    @Test
    @DisplayName("조사는 받침을 따르고, 한글로 안 끝나면 둘 다 적는다")
    void 조사() {
        assertThat(NoticeMail.iGa("기억")).isEqualTo("이");
        assertThat(NoticeMail.iGa("바다")).isEqualTo("가");
        assertThat(NoticeMail.iGa("LORE")).isEqualTo("이(가)");
        assertThat(NoticeMail.eulReul("기억")).isEqualTo("을");
        assertThat(NoticeMail.eulReul("바다")).isEqualTo("를");
    }
}
