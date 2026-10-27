package com.lore.common.credit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 7일 연속 출석 — 매일 무료 크레딧을 바꾼 것(2026-10-27).
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>출석은 로그인한 사람이 「오늘 받기」를 누르는 것이다. 하루 한 번, 한국 시각 자정 기준</li>
 *   <li>카드는 7칸. 4일째 5C, 7일째 20C. 7일째를 받으면 다음 날이 새 카드의 1일째</li>
 *   <li>하루라도 빠지면 다음 출석이 1일째부터</li>
 * </ul>
 *
 * <h2>왜 잔액을 볼 때 자동으로 찍지 않나</h2>
 *
 * 매일 20C 는 잔액을 물을 때 저절로 들어갔다. 출석은 이벤트라 「사탕 받기」를 누르는
 * 순간이 곧 참여다 — 팝업에서 「오늘 그만 보기」를 고른 날은 출석이 아니다.
 *
 * <h2>테마</h2>
 *
 * 할로윈(10.28~11.03)과 가면무도회(11.04~)는 겉만 다르다. 연속 일수와 보상 열쇠
 * (refId {@code attend-<카드 시작일>-d4})는 테마와 상관없이 이어진다 — 11.03 에 3일째였던
 * 사람은 11.04 에 4일째를 받는다.
 */
@Service
public class CreditAttendanceService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 카드 한 장의 칸 수. */
    static final int CARD = 7;

    private final CreditAttendanceRepository days;
    private final CreditService credits;
    private final List<Reward> rewards;
    private final LocalDate opensOn;
    private final LocalDate halloweenUntil;
    private final Clock clock;

    @Autowired
    public CreditAttendanceService(CreditAttendanceRepository days, CreditService credits,
                                   @Value("${lore.credit.attendance.day4:5}") int day4,
                                   @Value("${lore.credit.attendance.day7:20}") int day7,
                                   @Value("${lore.credit.attendance.opens-on:2026-10-28}") LocalDate opensOn,
                                   @Value("${lore.credit.attendance.halloween-until:2026-11-03}") LocalDate halloweenUntil) {
        this(days, credits, day4, day7, opensOn, halloweenUntil, Clock.system(ZONE));
    }

    CreditAttendanceService(CreditAttendanceRepository days, CreditService credits,
                            int day4, int day7, LocalDate opensOn, LocalDate halloweenUntil,
                            Clock clock) {
        this.days = days;
        this.credits = credits;
        this.rewards = List.of(new Reward(4, day4), new Reward(CARD, day7));
        this.opensOn = opensOn;
        this.halloweenUntil = halloweenUntil;
        this.clock = clock;
    }

    /** 지금 카드 상태. 아무것도 적지 않는다. */
    @Transactional(readOnly = true)
    public Status status(Long userId) {
        return statusOf(userId, LocalDate.now(clock), 0);
    }

    /**
     * 오늘 출석한다. 같은 날 몇 번을 불러도 한 번만 찍히고 보상도 한 번만 들어간다.
     *
     * 줄을 넣은 뒤 보상을 주다 끊겨도, 다시 부르면 이미 찍힌 줄의 일차로 보상을 다시
     * 시도한다 — 보상은 refId 로 한 번만 들어가므로 두 번 받을 일은 없다.
     */
    @Transactional
    public Status claim(Long userId) {
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(opensOn)) {
            return statusOf(userId, today, 0);
        }
        int streak = days.findByUserIdAndDay(userId, today)
                .map(CreditAttendance::getStreak)
                .orElseGet(() -> stamp(userId, today));
        int granted = 0;
        for (Reward r : rewards) {
            if (r.day() == streak) {
                LocalDate cardStart = today.minusDays(streak - 1L);
                granted += credits.grantOnce(userId, r.amount(), CreditReason.REWARD,
                        CreditDomain.COMMON, "attend-" + cardStart + "-d" + r.day());
            }
        }
        return statusOf(userId, today, granted);
    }

    /** 오늘 줄을 넣고 그 일차를 돌려준다. 거의 동시에 두 번 오면 먼저 넣은 쪽의 값을 읽는다. */
    private int stamp(Long userId, LocalDate today) {
        int streak = days.findByUserIdAndDay(userId, today.minusDays(1))
                .map(y -> y.getStreak() % CARD + 1)
                .orElse(1);
        if (days.insertOnce(userId, today, streak, Instant.now(clock)) == 1) {
            return streak;
        }
        return days.findByUserIdAndDay(userId, today)
                .map(CreditAttendance::getStreak)
                .orElse(streak);
    }

    private Status statusOf(Long userId, LocalDate today, int granted) {
        boolean open = !today.isBefore(opensOn);
        Optional<CreditAttendance> mine = days.findByUserIdAndDay(userId, today);
        boolean claimed = mine.isPresent();
        int stamped;
        int day;
        if (claimed) {
            stamped = mine.get().getStreak();
            day = stamped;
        } else {
            /* 어제 7일째를 받았으면 오늘은 새 카드라 빈 칸부터. 어제 안 왔으면 처음부터. */
            int yesterday = days.findByUserIdAndDay(userId, today.minusDays(1))
                    .map(CreditAttendance::getStreak)
                    .filter(s -> s < CARD)
                    .orElse(0);
            stamped = yesterday;
            day = yesterday + 1;
        }
        return new Status(open, themeOf(today), claimed, day, stamped,
                claimed ? nextReward(day + 1, 1) : nextReward(day, 0), rewards, granted);
    }

    /**
     * 앞으로 받을 첫 보상. {@code inDays} 는 오늘부터 며칠 뒤에 받는가(0 = 오늘 받으면).
     * 오늘 7일째를 받은 사람에게는 다음 카드의 4일째가 온다.
     *
     * @param fromDay 세기 시작할 일차(8 이면 다음 카드의 1일째)
     * @param offset  그 일차가 오늘부터 며칠 뒤인가 — 오늘 이미 받았으면 1(내일부터)
     */
    private Next nextReward(int fromDay, int offset) {
        for (int step = 0; step < CARD; step++) {
            int d = (fromDay - 1 + step) % CARD + 1;
            for (Reward r : rewards) {
                if (r.day() == d && r.amount() > 0) {
                    return new Next(d, r.amount(), step + offset);
                }
            }
        }
        return null;
    }

    private Theme themeOf(LocalDate today) {
        return today.isAfter(halloweenUntil) ? Theme.MASQUERADE : Theme.HALLOWEEN;
    }

    /** 화면의 겉. 규칙은 같다. */
    public enum Theme {
        /** 10.28~11.03 「TRICK or TOON」 — 사탕·호박 바구니 */
        HALLOWEEN,
        /** 11.04~ 가면무도회 — 가면·초대장 */
        MASQUERADE
    }

    /**
     * @param day    카드의 몇 일째에
     * @param amount 몇 크레딧
     */
    public record Reward(int day, int amount) {
    }

    /**
     * @param day    카드의 몇 일째 보상인가
     * @param amount 몇 크레딧
     * @param inDays 오늘부터 며칠 뒤 출석에 받나. 0 이면 오늘 받기를 누르면 받는다
     */
    public record Next(int day, int amount, int inDays) {
    }

    /**
     * @param open     이벤트가 열렸나. 열리기 전에는 받기를 눌러도 찍히지 않는다
     * @param theme    화면 겉
     * @param claimed  오늘 받았나
     * @param today    오늘이 카드의 몇 일째인가(받았으면 받은 일차, 안 받았으면 받으면 될 일차)
     * @param stamped  지금 카드에 채워진 칸 수(0~7)
     * @param next     앞으로 받을 첫 보상. 보상이 모두 0 이면 null
     * @param rewards  카드의 보상 칸 전부
     * @param granted  이번 받기로 들어온 크레딧. 상태만 물었으면 0
     */
    public record Status(boolean open, Theme theme, boolean claimed, int today, int stamped,
                         Next next, List<Reward> rewards, int granted) {
    }
}
