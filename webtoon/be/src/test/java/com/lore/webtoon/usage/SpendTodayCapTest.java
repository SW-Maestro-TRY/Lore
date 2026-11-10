package com.lore.webtoon.usage;

import com.lore.webtoon.ops.OpsAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 하루 상한을 오늘만 바꾸는 자리(#617).
 *
 * 지키는 것은 넷이다. 바꾼 값이 <b>재시작 없이 바로</b> 먹는다. <b>자정이 지나면</b> 저절로 설정값으로 돌아간다.
 * 상한에 걸린 알림은 <b>그날 처음만</b> 사람을 부른다. 관리자가 화면을 볼 때는 알림이 안 간다.
 */
class SpendTodayCapTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    /** 한국시간 2026-10-28 14시 */
    private static final Clock DAY1 = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZONE);
    /** 한국시간 2026-10-29 00시 05분 — 자정을 막 넘김 */
    private static final Clock DAY2 = Clock.fixed(Instant.parse("2026-10-28T15:05:00Z"), ZONE);

    /** 메모리에 쌓는 저장소. 몇 번 읽었는지도 센다. */
    static final class MemStore implements SpendOverrides.Store {
        final List<SpendOverrides.DayCap> rows = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        boolean broken;

        @Override
        public SpendOverrides.DayCap latest(LocalDate day) {
            reads.incrementAndGet();
            if (broken) {
                throw new IllegalStateException("db down");
            }
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (rows.get(i).day().equals(day)) {
                    return rows.get(i);
                }
            }
            return null;
        }

        @Override
        public void add(LocalDate day, Long krw, Long runs, Long by) {
            rows.add(new SpendOverrides.DayCap(day, krw, runs, by, Instant.EPOCH));
        }
    }

    private static SpendGuard guard(long runs, long krw, SpendOverrides overrides, OpsAlert alert, Clock clock) {
        UsageRepository usage = mock(UsageRepository.class);
        when(usage.runsBetween(any(), any())).thenReturn(runs);
        when(usage.krwBetween(any(), any())).thenReturn(krw);
        SpendGuard g = new SpendGuard(usage, 30, 50_000, clock);
        g.setOverrides(overrides);
        g.setOpsAlert(alert);
        return g;
    }

    @Test
    @DisplayName("오늘만 올린 값이 재시작 없이 바로 먹는다 — 설정값 5만 원에 막히던 것이 풀린다")
    void 올리면_바로_풀린다() {
        SpendOverrides overrides = new SpendOverrides(new MemStore(), DAY1);
        SpendGuard g = guard(12, 52_000, overrides, null, DAY1);
        assertThat(g.whyBlocked()).isNotNull();

        g.setToday(150_000, 70, 1L);

        assertThat(g.whyBlocked()).isNull();
        assertThat(g.caps().krw()).isEqualTo(150_000);
        assertThat(g.caps().runs()).isEqualTo(70);
        assertThat(g.caps().defaultKrw()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("자정이 지나면 저절로 설정값으로 돌아간다 — 되돌리는 것을 잊어도 다음 날은 기본값")
    void 자정이_지나면_설정값() {
        MemStore store = new MemStore();
        new SpendOverrides(store, DAY1).set(150_000L, 70L, 1L);

        SpendOverrides nextDay = new SpendOverrides(store, DAY2);
        SpendGuard g = guard(0, 52_000, nextDay, null, DAY2);

        assertThat(nextDay.today()).isNull();
        assertThat(g.caps().krw()).isEqualTo(50_000);
        assertThat(g.whyBlocked()).isNotNull();
    }

    @Test
    @DisplayName("서버가 다시 떠도 그날 올린 값이 남는다 — DB 에서 다시 읽는다")
    void 다시_떠도_남는다() {
        MemStore store = new MemStore();
        new SpendOverrides(store, DAY1).set(150_000L, 70L, 1L);

        SpendOverrides afterRestart = new SpendOverrides(store, DAY1);
        assertThat(afterRestart.today()).isNotNull();
        assertThat(afterRestart.today().krw()).isEqualTo(150_000);
    }

    @Test
    @DisplayName("마지막 줄이 이기고, 무르면 설정값 — 기록은 지우지 않고 쌓인다")
    void 무르면_설정값() {
        MemStore store = new MemStore();
        SpendOverrides overrides = new SpendOverrides(store, DAY1);
        SpendGuard g = guard(0, 0, overrides, null, DAY1);

        g.setToday(150_000, 70, 1L);
        g.setToday(100_000, 50, 1L);
        assertThat(g.caps().krw()).isEqualTo(100_000);

        g.clearToday(1L);
        assertThat(g.caps().today()).isNull();
        assertThat(g.caps().krw()).isEqualTo(50_000);
        assertThat(store.rows).hasSize(3);
    }

    @Test
    @DisplayName("30초는 DB 를 다시 안 읽는다 — 만들기 화면이 열릴 때마다 묻는 자리다")
    void 삼십초는_기억한다() {
        MemStore store = new MemStore();
        SpendOverrides overrides = new SpendOverrides(store, DAY1);
        overrides.today();
        overrides.today();
        overrides.today();
        assertThat(store.reads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("못 읽으면 마지막으로 읽은 값을 쓴다 — 이 표 하나로 만들기가 500 이 되면 안 된다")
    void 못_읽으면_마지막_값() {
        MemStore store = new MemStore();
        Clock[] now = {DAY1};
        Clock moving = new Clock() {
            @Override public ZoneId getZone() { return ZONE; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0].instant(); }
        };
        SpendOverrides overrides = new SpendOverrides(store, moving);
        overrides.set(150_000L, 70L, 1L);

        store.broken = true;
        now[0] = Clock.offset(DAY1, java.time.Duration.ofMinutes(1));   // 30초가 지나 다시 읽으려 한다

        assertThat(overrides.today()).isNotNull();
        assertThat(overrides.today().krw()).isEqualTo(150_000);
    }

    @Test
    @DisplayName("상한에 걸린 알림은 그날 처음만 사람을 부른다 — 그 뒤는 조용히 묶인다")
    void 처음만_부른다() {
        OpsAlert alert = mock(OpsAlert.class);
        SpendGuard g = guard(12, 52_000, null, alert, DAY1);

        g.whyBlocked();
        g.whyBlocked();

        verify(alert, times(1)).send(eq(OpsAlert.Level.URGENT), eq("spend-cap-first:2026-10-28"), anyString(), anyString());
        verify(alert, times(1)).send(eq(OpsAlert.Level.WARN), eq("spend-cap:금액"), anyString(), anyString());
    }

    @Test
    @DisplayName("알림 본문은 관리자 화면으로 안내한다 — 서버를 다시 띄우라고 하지 않는다")
    void 알림은_관리자_화면으로() {
        OpsAlert alert = mock(OpsAlert.class);
        SpendGuard g = guard(12, 52_000, null, alert, DAY1);
        g.setSite("https://dev.lorecomic.com/");

        g.whyBlocked();

        verify(alert).send(eq(OpsAlert.Level.URGENT), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(body -> body.contains("관리: https://dev.lorecomic.com/webtoon?view=admin")
                        && !body.contains("Parameter Store")));
    }

    @Test
    @DisplayName("관리자 화면이 상태를 볼 때는 알림이 안 간다")
    void 상태는_알리지_않는다() {
        OpsAlert alert = mock(OpsAlert.class);
        SpendGuard g = guard(12, 52_000, null, alert, DAY1);

        SpendGuard.Status s = g.status(SpendGuard.Reserved.NONE);

        assertThat(s.blocked()).isTrue();
        assertThat(s.day()).isEqualTo(LocalDate.of(2026, 10, 28));
        verify(alert, never()).send(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("상태는 아직 안 끝난 몫까지 더해서 막힘을 본다 — 만들기와 같은 자")
    void 상태는_예약까지() {
        SpendGuard g = guard(10, 40_000, null, null, DAY1);
        assertThat(g.status(SpendGuard.Reserved.NONE).blocked()).isFalse();
        assertThat(g.status(new SpendGuard.Reserved(2, 10_000)).blocked()).isTrue();
    }

    @Test
    @DisplayName("바꾸면 운영 채널에 남긴다 — 누가 언제 얼마로")
    void 바꾸면_알린다() {
        OpsAlert alert = mock(OpsAlert.class);
        SpendGuard g = guard(0, 0, new SpendOverrides(new MemStore(), DAY1), alert, DAY1);

        g.setToday(150_000, 70, 7L);

        verify(alert).send(eq(OpsAlert.Level.OK), startsWith("spend-override:"), eq("오늘 생성 상한을 바꿨어요"),
                org.mockito.ArgumentMatchers.argThat(body -> body.contains("50000 → 150000원") && body.contains("관리자 #7")));
    }
}
