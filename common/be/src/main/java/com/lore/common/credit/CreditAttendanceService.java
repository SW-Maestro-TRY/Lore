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
import java.util.stream.IntStream;

/**
 * 7일 연속 출석 — 매일 무료 크레딧을 바꾼 것(2026-10-27).
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>출석은 로그인한 사람이 「오늘 받기」를 누르는 것이다. 하루 한 번, 한국 시각 자정 기준</li>
 *   <li>카드는 7칸. 받는 날마다 그날 몫이 들어온다 — 1·2·3·5·6일째 2C, 4일째 5C, 7일째 10C
 *       (한 주 25C). 7일째를 받으면 다음 날이 새 카드의 1일째</li>
 *   <li>하루라도 빠지면 다음 출석이 1일째부터</li>
 * </ul>
 *
 * <h2>왜 날마다 주나</h2>
 *
 * 처음에는 4일째 5C · 7일째 20C 만 줬다. 그러면 1~3일째에는 들어올 이유가 없다. 같은 총량(25C)을
 * 날마다 나눠, 그날 들어오면 그날 무언가를 받게 했다. 매일 5C 씩 주면 사흘마다 파도 한 편이
 * 공짜가 되어서, 날마다 몫은 작게 두고 4일째 · 7일째를 크게 뒀다.
 *
 * <h2>왜 잔액을 볼 때 자동으로 찍지 않나</h2>
 *
 * 매일 20C 는 잔액을 물을 때 저절로 들어갔다. 출석은 이벤트라 「사탕 받기」를 누르는
 * 순간이 곧 참여다 — 팝업에서 「오늘 그만 보기」를 고른 날은 출석이 아니다.
 *
 * <h2>테마</h2>
 *
 * 할로윈(10.28~11.03)과 가면무도회(11.04~)는 겉만 다르다. 연속 일수와 보상 열쇠
 * (refId {@code attend-<카드 시작일>-d1} … {@code -d7})는 테마와 상관없이 이어진다 — 11.03 에
 * 3일째였던 사람은 11.04 에 4일째를 받는다.
 */
@Service
public class CreditAttendanceService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 카드 한 장의 칸 수. */
    static final int CARD = 7;

    /** 큰 보상이 있는 날. 화면이 「다음 큰 보상까지 며칠」을 보여 줄 때 이 날만 센다. */
    private static final List<Integer> MILESTONES = List.of(4, CARD);

    /** 출석 보상의 refId 머리. 뒤에 카드 시작일과 일차가 붙는다 — {@code attend-2026-10-28-d1}. */
    static final String REF_PREFIX = "attend-";

    /**
     * 내역에 보일 한 줄. 이유는 다른 활동과 같은 REWARD(「활동 보상」)라, 이 말이 없으면
     * 사용자가 출석으로 받은 것인지 알아보지 못한다(2026-10-28 운영 확인).
     */
    static final String MEMO = "출석 보상";

    /** 출석으로 받은 줄인가. 이유가 아니라 refId 로 가린다 — REWARD 는 다른 활동도 쓴다. */
    static boolean isAttendance(CreditReason reason, String refId) {
        return reason == CreditReason.REWARD && refId != null && refId.startsWith(REF_PREFIX);
    }

    private final CreditAttendanceRepository days;
    private final CreditService credits;
    private final List<Reward> rewards;
    private final LocalDate opensOn;
    private final LocalDate halloweenUntil;
    private final Clock clock;

    @Autowired
    public CreditAttendanceService(CreditAttendanceRepository days, CreditService credits,
                                   @Value("${lore.credit.attendance.daily:2}") int daily,
                                   @Value("${lore.credit.attendance.day4:5}") int day4,
                                   @Value("${lore.credit.attendance.day7:10}") int day7,
                                   @Value("${lore.credit.attendance.opens-on:2026-10-28}") LocalDate opensOn,
                                   @Value("${lore.credit.attendance.halloween-until:2026-11-03}") LocalDate halloweenUntil) {
        this(days, credits, daily, day4, day7, opensOn, halloweenUntil, Clock.system(ZONE));
    }

    CreditAttendanceService(CreditAttendanceRepository days, CreditService credits,
                            int daily, int day4, int day7, LocalDate opensOn, LocalDate halloweenUntil,
                            Clock clock) {
        this.days = days;
        this.credits = credits;
        this.rewards = IntStream.rangeClosed(1, CARD)
                .mapToObj(d -> new Reward(d, d == 4 ? day4 : d == CARD ? day7 : daily))
                .toList();
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
        LocalDate cardStart = today.minusDays(streak - 1L);
        int granted = credits.grantOnce(userId, amountOf(streak), CreditReason.REWARD,
                CreditDomain.COMMON, REF_PREFIX + cardStart + "-d" + streak, MEMO);
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
        return new Status(open, themeOf(today), claimed, day, amountOf(day), stamped,
                nextMilestone(day + 1), rewards, granted);
    }

    /** 카드의 그 일차에 받는 크레딧. */
    private int amountOf(int day) {
        return rewards.get(day - 1).amount();
    }

    /**
     * 내일부터 세어 처음 오는 큰 보상(4일째 · 7일째). {@code inDays} 는 오늘부터 며칠 뒤인가.
     * 오늘 7일째인 사람에게는 다음 카드의 4일째가 온다.
     *
     * @param fromDay 내일의 일차(8 이면 다음 카드의 1일째)
     */
    private Next nextMilestone(int fromDay) {
        for (int step = 0; step < CARD; step++) {
            int d = (fromDay - 1 + step) % CARD + 1;
            if (MILESTONES.contains(d) && amountOf(d) > 0) {
                return new Next(d, amountOf(d), step + 1);
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
     * @param inDays 오늘부터 며칠 뒤 출석에 받나(1 = 내일)
     */
    public record Next(int day, int amount, int inDays) {
    }

    /**
     * @param open     이벤트가 열렸나. 열리기 전에는 받기를 눌러도 찍히지 않는다
     * @param theme    화면 겉
     * @param claimed  오늘 받았나
     * @param today    오늘이 카드의 몇 일째인가(받았으면 받은 일차, 안 받았으면 받으면 될 일차)
     * @param reward   오늘 받는(받은) 크레딧
     * @param stamped  지금 카드에 채워진 칸 수(0~7)
     * @param next     내일부터 처음 오는 큰 보상(4일째 · 7일째). 둘 다 0 이면 null
     * @param rewards  카드의 일곱 칸 보상
     * @param granted  이번 받기로 들어온 크레딧. 상태만 물었으면 0
     */
    public record Status(boolean open, Theme theme, boolean claimed, int today, int reward, int stamped,
                         Next next, List<Reward> rewards, int granted) {
    }
}
