package com.lore.webtoon;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 로그인 안 한 사람의 하루 몫.
 *
 * 저장소는 진짜 대신 지도 하나로 흉내 낸다 — 여기서 보고 싶은 것은 JPA 가
 * 아니라 <b>세는 규칙</b>이다.
 */
class GuestGateTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock DAY1 =
            Clock.fixed(Instant.parse("2026-09-06T05:00:00Z"), ZONE);
    private static final Clock DAY2 =
            Clock.fixed(Instant.parse("2026-09-07T05:00:00Z"), ZONE);

    /** (ip_hash, 날짜) -> 그 날 쓴 횟수. 진짜 표가 하는 일만 한다. */
    private final Map<String, GuestQuota> rows = new HashMap<>();

    /** 지도 하나로 흉내 낸 저장소. 진짜 표가 하는 일 중 쓰는 것은 두 가지뿐이다.
     *  JpaRepository 를 직접 구현하면 안 쓰는 메서드 수십 개를 같이 적어야 해서
     *  가짜를 세운다 — 여기서 보고 싶은 것은 JPA 가 아니라 세는 규칙이다. */
    private GuestQuotaRepository repo() {
        GuestQuotaRepository repo = mock(GuestQuotaRepository.class);
        when(repo.findByIpHashAndDay(anyString(), any())).thenAnswer(call ->
                Optional.ofNullable(rows.get(key(call.getArgument(0), call.getArgument(1)))));
        when(repo.save(any(GuestQuota.class))).thenAnswer(call -> {
            GuestQuota row = call.getArgument(0);
            rows.put(key(row.getIpHash(), row.getDay()), row);
            return row;
        });
        return repo;
    }

    private static String key(String ipHash, LocalDate day) {
        return ipHash + "@" + day;
    }

    private GuestGate gate(long free, Clock clock) {
        return new GuestGate(repo(), free, "소금", clock);
    }

    private HttpServletRequest from(String ip) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(ip);
        return req;
    }

    @AfterEach
    void 로그인_흔적을_지운다() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("무료 횟수만큼은 통과하고, 그 다음부터 막는다")
    void 하루_몫() {
        GuestGate gate = gate(2, DAY1);
        HttpServletRequest me = from("1.2.3.4");

        assertThat(gate.useOrBlock(me)).isNull();
        assertThat(gate.useOrBlock(me)).isNull();
        assertThat(gate.useOrBlock(me)).isNotNull();
    }

    @Test
    @DisplayName("날이 바뀌면 다시 채워진다 — IP 는 공유되므로 평생 잠그지 않는다")
    void 날마다_다시_채워진다() {
        HttpServletRequest me = from("1.2.3.4");
        GuestGate today = gate(1, DAY1);
        assertThat(today.useOrBlock(me)).isNull();
        assertThat(today.useOrBlock(me)).isNotNull();

        assertThat(gate(1, DAY2).useOrBlock(me)).isNull();
    }

    @Test
    @DisplayName("다른 곳에서 온 사람은 따로 센다")
    void 서로_다른_곳은_따로_센다() {
        GuestGate gate = gate(1, DAY1);
        assertThat(gate.useOrBlock(from("1.2.3.4"))).isNull();
        assertThat(gate.useOrBlock(from("1.2.3.4"))).isNotNull();
        assertThat(gate.useOrBlock(from("5.6.7.8"))).isNull();
    }

    @Test
    @DisplayName("로그인했으면 안 센다 — 계정은 크레딧으로 센다(#16)")
    void 로그인하면_안_센다() {
        GuestGate gate = gate(1, DAY1);
        HttpServletRequest me = from("1.2.3.4");
        assertThat(gate.useOrBlock(me)).isNull();
        assertThat(gate.useOrBlock(me)).isNotNull();   // 게스트로는 막힌다

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        assertThat(gate.useOrBlock(me)).isNull();      // 로그인하면 지나간다
    }

    @Test
    @DisplayName("0이면 안 센다 — 끄는 스위치다")
    void 영이면_안_센다() {
        GuestGate gate = gate(0, DAY1);
        HttpServletRequest me = from("1.2.3.4");
        for (int i = 0; i < 5; i++) {
            assertThat(gate.useOrBlock(me)).isNull();
        }
    }

    @Test
    @DisplayName("CloudFront 뒤에서는 X-Forwarded-For 를 본다 — 안 그러면 온 세상이 한 사람이 된다")
    void 프록시_뒤의_진짜_주소() {
        GuestGate gate = gate(1, DAY1);

        MockHttpServletRequest a = new MockHttpServletRequest();
        a.setRemoteAddr("10.0.0.1");                       // CloudFront
        a.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");

        MockHttpServletRequest b = new MockHttpServletRequest();
        b.setRemoteAddr("10.0.0.1");                       // 같은 CloudFront
        b.addHeader("X-Forwarded-For", "198.51.100.9, 10.0.0.1");

        assertThat(gate.useOrBlock(a)).isNull();
        assertThat(gate.useOrBlock(b)).isNull();           // 남이므로 따로 센다
        assertThat(gate.useOrBlock(a)).isNotNull();        // 아까 그 사람은 다 썼다
    }

    @Test
    @DisplayName("주소를 그대로 안 남긴다 — 표에는 해시만 있다")
    void 주소는_해시로만_남는다() {
        gate(2, DAY1).useOrBlock(from("203.0.113.7"));
        assertThat(rows).isNotEmpty();
        assertThat(rows.values()).allSatisfy(row ->
                assertThat(row.getIpHash()).doesNotContain("203.0.113.7"));
    }

    @Test
    @DisplayName("시작조차 못 했으면 도로 물린다 — 만든 적 없는 사람에게 「다 쓰셨어요」가 뜨면 안 된다")
    void 실패하면_도로_물린다() {
        GuestGate gate = gate(1, DAY1);
        HttpServletRequest me = from("1.2.3.4");

        assertThat(gate.useOrBlock(me)).isNull();
        gate.refund(me);                                   // 생성 서버가 안 받았다
        assertThat(gate.useOrBlock(me)).isNull();          // 그러니 다시 되어야 한다
        assertThat(gate.useOrBlock(me)).isNotNull();
    }

    @Test
    @DisplayName("안 센 것은 안 물린다 — 0 밑으로 내려가면 무한이 된다")
    void 안_센_것은_안_물린다() {
        GuestGate gate = gate(1, DAY1);
        HttpServletRequest me = from("1.2.3.4");

        gate.refund(me);
        gate.refund(me);
        assertThat(gate.useOrBlock(me)).isNull();
        assertThat(gate.useOrBlock(me)).isNotNull();       // 여전히 하루 1편이다
    }
}
