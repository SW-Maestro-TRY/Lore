package com.lore.webtoon.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OpsAlertTest {

    /** 보내기를 그 자리에서 돌린다 — 순서대로 확인하려고. */
    static final class Now extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() { }
        public List<Runnable> shutdownNow() { return List.of(); }
        public boolean isShutdown() { return false; }
        public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    static final class MovingClock extends Clock {
        Instant at = Instant.parse("2026-10-26T12:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return at; }
    }

    private final List<String> sent = new ArrayList<>();
    private final MovingClock clock = new MovingClock();

    private OpsAlert alert(String webhook) {
        return new OpsAlert(webhook, "prod", Duration.ofMinutes(10), clock, (url, json) -> sent.add(json), new Now());
    }

    @Test
    void 주소가_없으면_아무것도_안_보낸다() {
        OpsAlert a = alert("");
        a.send(OpsAlert.Level.ERROR, "k", "제목", null);
        assertThat(a.enabled()).isFalse();
        assertThat(sent).isEmpty();
    }

    @Test
    void 같은_일은_조용히_할_시간_안에_한_번만_보내고_그_사이_수를_붙인다() {
        OpsAlert a = alert("https://discord.example/hook");
        a.send(OpsAlert.Level.ERROR, "k", "서버 오류", "A");
        a.send(OpsAlert.Level.ERROR, "k", "서버 오류", "B");
        a.send(OpsAlert.Level.ERROR, "k", "서버 오류", "C");
        a.send(OpsAlert.Level.ERROR, "other", "다른 일", null);
        assertThat(sent).hasSize(2);

        clock.at = clock.at.plus(Duration.ofMinutes(11));
        a.send(OpsAlert.Level.ERROR, "k", "서버 오류", "D");
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2)).contains("2번 더");
    }

    @Test
    void 급한_것만_here_를_붙인다() {
        OpsAlert a = alert("https://discord.example/hook");
        assertThat(a.format(OpsAlert.Level.URGENT, "DB", null, 0)).startsWith("@here 🚨 **[prod] DB**");
        assertThat(a.format(OpsAlert.Level.ERROR, "오류", "본문", 0)).doesNotContain("@here").contains("```\n본문\n```");
    }

    @Test
    void 충전_링크는_코드_블록_밖에_둔다() {
        OpsAlert a = alert("https://discord.example/hook");
        String s = a.format(OpsAlert.Level.URGENT, "잔액", "기준 아래\n충전: https://openrouter.ai/settings/credits", 0);
        assertThat(s).endsWith("```\n충전: https://openrouter.ai/settings/credits");
    }

    @Test
    void 디스코드_글자_상한을_넘지_않는다() {
        OpsAlert a = alert("https://discord.example/hook");
        assertThat(a.format(OpsAlert.Level.ERROR, "t", "가".repeat(5000), 0).length()).isLessThanOrEqualTo(1900);
    }

    @Test
    void 주소의_작품_작업_번호는_묶는_열쇠에서_지운다() {
        assertThat(ServerErrorAlertFilter.shape("/api/webtoon/v1/runs/20261003T213320-5297d4/page/3"))
                .isEqualTo("/api/webtoon/v1/runs/{id}/page/{id}");
        assertThat(ServerErrorAlertFilter.shape("/api/webtoon/v1/nh/jobs/cc3e333954da4507b146"))
                .isEqualTo("/api/webtoon/v1/nh/jobs/{id}");
        assertThat(ServerErrorAlertFilter.shape("/api/webtoon/v1/runs")).isEqualTo("/api/webtoon/v1/runs");
    }

    @Test
    void 모델_크레딧_소진을_알아본다() {
        assertThat(com.lore.webtoon.job.JobRunner.outOfCredits(
                "RateLimitError: Error code: 429 - {'error': {'message': 'You have no credits remaining.")).isTrue();
        assertThat(com.lore.webtoon.job.JobRunner.outOfCredits("Error code: 402 - insufficient credits")).isTrue();
        assertThat(com.lore.webtoon.job.JobRunner.outOfCredits("TimeoutError: read timed out")).isFalse();
    }
}
