package com.lore.common.credit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 7일 연속 출석 규칙.
 *
 * 저장소는 목록으로 흉내 낸다. 보고 싶은 것은 「며칠째로 찍히나」와
 * 「보상이 몇 번 들어가나」다.
 */
class CreditAttendanceServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDate OPENS = LocalDate.of(2026, 10, 28);
    private static final LocalDate HALLOWEEN_UNTIL = LocalDate.of(2026, 11, 3);
    private static final long ME = 1L;

    private final List<CreditAttendance> attendRows = new ArrayList<>();
    private final List<CreditEvent> creditRows = new ArrayList<>();

    private CreditAttendanceRepository days;
    private CreditEventRepository events;

    @BeforeEach
    void 저장소를_세운다() {
        days = mock(CreditAttendanceRepository.class);
        when(days.findByUserIdAndDay(anyLong(), any())).thenAnswer(c -> attendRows.stream()
                .filter(a -> a.getUserId().equals(c.<Long>getArgument(0))
                        && a.getDay().equals(c.getArgument(1)))
                .findFirst());
        when(days.insertOnce(anyLong(), any(), anyInt(), any())).thenAnswer(c -> {
            Long who = c.getArgument(0);
            LocalDate day = c.getArgument(1);
            if (attendRows.stream().anyMatch(a -> a.getUserId().equals(who) && a.getDay().equals(day))) {
                return 0;
            }
            attendRows.add(CreditAttendance.of(who, day, c.getArgument(2), c.getArgument(3)));
            return 1;
        });

        events = mock(CreditEventRepository.class);
        when(events.balanceOf(anyLong())).thenAnswer(c -> creditRows.stream()
                .filter(e -> e.getUserId().equals(c.<Long>getArgument(0)))
                .mapToInt(CreditEvent::getDelta).sum());
        when(events.existsByUserIdAndReasonAndDomainAndRefId(anyLong(), any(), any(), any()))
                .thenAnswer(c -> creditRows.stream().anyMatch(
                        e -> e.getUserId().equals(c.<Long>getArgument(0))
                                && e.getReason() == c.getArgument(1)
                                && e.getDomain() == c.getArgument(2)
                                && e.getRefId().equals(c.getArgument(3))));
        when(events.saveAndFlush(any(CreditEvent.class))).thenAnswer(c -> {
            creditRows.add(c.getArgument(0));
            return c.getArgument(0);
        });
    }

    private static Clock on(LocalDate day) {
        return Clock.fixed(day.atTime(12, 0).atZone(ZONE).toInstant(), ZONE);
    }

    private CreditAttendanceService service(LocalDate day) {
        Clock clock = on(day);
        CreditService credits = new CreditService(events, new CreditLedger(events, clock), 15, 0, clock);
        return new CreditAttendanceService(days, credits, 5, 20, OPENS, HALLOWEEN_UNTIL, clock);
    }

    private int balance() {
        return events.balanceOf(ME);
    }

    private List<String> rewardKeys() {
        return creditRows.stream().filter(e -> e.getReason() == CreditReason.REWARD)
                .map(CreditEvent::getRefId).toList();
    }

    @Test
    @DisplayName("7일 연속이면 4일째 5C, 7일째 20C — 열쇠는 카드 시작일로")
    void 칠일_연속() {
        for (int i = 0; i < 7; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        assertThat(balance()).isEqualTo(25);
        assertThat(rewardKeys()).containsExactly("attend-2026-10-28-d4", "attend-2026-10-28-d7");
    }

    @Test
    @DisplayName("같은 날 여러 번 받아도 한 번만 찍히고 보상도 한 번")
    void 같은_날_두_번() {
        for (int i = 0; i < 3; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        var first = service(OPENS.plusDays(3)).claim(ME);
        var again = service(OPENS.plusDays(3)).claim(ME);

        assertThat(first.granted()).isEqualTo(5);
        assertThat(again.granted()).isZero();
        assertThat(again.today()).isEqualTo(4);
        assertThat(attendRows).hasSize(4);
        assertThat(balance()).isEqualTo(5);
    }

    @Test
    @DisplayName("하루 쉬면 1일째부터 — 쉰 날 전 카드의 보상 열쇠와 겹치지 않는다")
    void 하루_쉬면_처음부터() {
        for (int i = 0; i < 3; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        LocalDate back = OPENS.plusDays(4);             // 10.31 을 쉬고 11.01
        var s = service(back).claim(ME);
        assertThat(s.today()).isEqualTo(1);
        assertThat(s.stamped()).isEqualTo(1);

        for (int i = 1; i < 4; i++) {
            service(back.plusDays(i)).claim(ME);
        }
        assertThat(rewardKeys()).containsExactly("attend-2026-11-01-d4");
    }

    @Test
    @DisplayName("7일째를 받은 다음 날은 새 카드의 1일째")
    void 새_카드() {
        for (int i = 0; i < 7; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        LocalDate day8 = OPENS.plusDays(7);
        var before = service(day8).status(ME);
        assertThat(before.stamped()).isZero();
        assertThat(before.today()).isEqualTo(1);

        assertThat(service(day8).claim(ME).today()).isEqualTo(1);
    }

    @Test
    @DisplayName("상태만 물으면 아무것도 안 적는다")
    void 보기만() {
        var s = service(OPENS).status(ME);
        assertThat(s.claimed()).isFalse();
        assertThat(s.today()).isEqualTo(1);
        assertThat(s.next()).isEqualTo(new CreditAttendanceService.Next(4, 5, 3));
        assertThat(attendRows).isEmpty();
        assertThat(creditRows).isEmpty();
    }

    @Test
    @DisplayName("다음 보상까지 남은 날 — 받은 뒤엔 내일부터 센다")
    void 다음_보상() {
        for (int i = 0; i < 3; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        // 3일째 받은 날: 내일 4일째 5C
        assertThat(service(OPENS.plusDays(2)).status(ME).next())
                .isEqualTo(new CreditAttendanceService.Next(4, 5, 1));
        // 다음 날 받기 전: 오늘 받으면 5C
        assertThat(service(OPENS.plusDays(3)).status(ME).next())
                .isEqualTo(new CreditAttendanceService.Next(4, 5, 0));
    }

    @Test
    @DisplayName("7일째를 받은 날의 다음 보상은 다음 카드의 4일째")
    void 칠일째_뒤_다음_보상() {
        for (int i = 0; i < 7; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        assertThat(service(OPENS.plusDays(6)).status(ME).next())
                .isEqualTo(new CreditAttendanceService.Next(4, 5, 4));
    }

    @Test
    @DisplayName("열리기 전에는 받기를 눌러도 찍히지 않는다")
    void 열리기_전() {
        var s = service(OPENS.minusDays(1)).claim(ME);
        assertThat(s.open()).isFalse();
        assertThat(s.claimed()).isFalse();
        assertThat(attendRows).isEmpty();
    }

    @Test
    @DisplayName("테마는 11.03 까지 할로윈, 11.04 부터 가면무도회 — 일차는 이어진다")
    void 테마가_바뀌어도_이어진다() {
        for (int i = 0; i < 3; i++) {
            service(LocalDate.of(2026, 11, 1).plusDays(i)).claim(ME);   // 11.01~11.03
        }
        assertThat(service(HALLOWEEN_UNTIL).status(ME).theme())
                .isEqualTo(CreditAttendanceService.Theme.HALLOWEEN);

        var s = service(LocalDate.of(2026, 11, 4)).claim(ME);
        assertThat(s.theme()).isEqualTo(CreditAttendanceService.Theme.MASQUERADE);
        assertThat(s.today()).isEqualTo(4);
        assertThat(s.granted()).isEqualTo(5);
    }

    @Test
    @DisplayName("줄은 찍혔는데 보상이 끊겼으면 다시 받기로 보상만 채운다")
    void 끊긴_보상_다시() {
        for (int i = 0; i < 3; i++) {
            service(OPENS.plusDays(i)).claim(ME);
        }
        LocalDate day4 = OPENS.plusDays(3);
        attendRows.add(CreditAttendance.of(ME, day4, 4, Instant.now()));   // 줄만 있고 보상은 없음

        var s = service(day4).claim(ME);
        assertThat(s.granted()).isEqualTo(5);
        assertThat(rewardKeys()).containsExactly("attend-2026-10-28-d4");
    }

    @Test
    @DisplayName("거의 동시에 들어와 줄을 못 넣었으면 먼저 넣은 쪽의 일차를 쓴다")
    void 동시에_두_번() {
        doAnswer(c -> {
            attendRows.add(CreditAttendance.of(ME, c.getArgument(1), 1, Instant.now()));
            return 0;                                    // 다른 요청이 먼저 넣었다
        }).when(days).insertOnce(anyLong(), any(), anyInt(), any());
        var s = service(OPENS).claim(ME);
        assertThat(s.today()).isEqualTo(1);
        assertThat(attendRows).hasSize(1);
        assertThat(days.findByUserIdAndDay(ME, OPENS)).isEqualTo(Optional.of(attendRows.get(0)));
    }
}
