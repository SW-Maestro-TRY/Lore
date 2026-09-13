package com.lore.webtoon.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 하루에 나가는 돈에 상한을 건다. 우리 서비스가 하루에 실제로 얼마까지 돈을 쓸지 막는 쪽
 */
@Service
public class SpendGuard {

    private static final Logger log = LoggerFactory.getLogger(SpendGuard.class);

    /** 하루가 언제 바뀌는가. 보는 사람이 한국에 있으므로 한국 자정 기준이다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final UsageRepository usage;
    private final long dailyRuns;
    private final long dailyKrw;
    private final Clock clock;

    /* 생성자가 둘이라(아래 하나는 검사에서 시계를 갈아 끼우려고 둔 것)
       스프링이 어느 것으로 만들지 못 고른다 — 표시가 없으면 인자 없는
       생성자를 찾다가 서버가 아예 안 뜬다. 검사만으로는 안 잡힌다: 검사는
       이 클래스를 손으로 만들거나 가짜로 바꿔치기하므로 스프링이 고를 일이
       없다. 실제 DB 로 띄워 보고서야 나왔다. */
    @Autowired
    public SpendGuard(UsageRepository usage,
                      @Value("${lore.webtoon.spend.daily-runs:30}") long dailyRuns,
                      @Value("${lore.webtoon.spend.daily-krw:40000}") long dailyKrw) {
        this(usage, dailyRuns, dailyKrw, Clock.system(ZONE));
    }

    SpendGuard(UsageRepository usage, long dailyRuns, long dailyKrw, Clock clock) {
        this.usage = usage;
        this.dailyRuns = dailyRuns;
        this.dailyKrw = dailyKrw;
        this.clock = clock;
    }

    /** 오늘 자정부터 지금까지. */
    private Instant startOfToday() {
        return LocalDate.now(clock).atStartOfDay(ZONE).toInstant();
    }

    /**
     * 지금 새로 만들어도 되는가.
     *
     * @return 막을 이유(사람이 읽을 한 줄). 괜찮으면 {@code null}
     */
    @Transactional(readOnly = true)
    public String whyBlocked() {
        return whyBlocked(Reserved.NONE);
    }

    /**
     * 지금 새로 만들어도 되는가 — <b>아직 안 적힌 몫까지 세어서.</b>
     *
     * <h2>왜 예약이 필요한가</h2>
     *
     * 여기서 보는 것은 DB 에 <b>적힌</b> 지출이다. 그런데 지출은 걸음이 끝나야
     * 적힌다 — 지금 도는 작업이 쓸 돈은 아직 어디에도 없다. 한 줄로 한 편씩만
     * 돌 때는 그 틈이 한 편이라 눈감을 수 있었다.
     *
     * <b>나란히 둘을 돌리기 시작하면 눈감을 수 없다.</b> 상한까지 한 편이 남았을
     * 때 둘이 같이 물으면 <b>둘 다 통과한다</b> — 둘 다 아직 아무것도 안 썼기
     * 때문이다. 그러면 상한을 넘겨서 시작하고, 우리는 그걸 다 쓴 뒤에야 안다.
     *
     * 그래서 아직 안 끝난 작업이 쓸 돈을 <b>미리 잡아 둔다.</b> 도는 중인 작업은
     * 이미 쓴 만큼이 적혔을 수 있어 조금 겹쳐 세지만, 상한은 <b>안전선</b>이므로
     * 넉넉히 막는 쪽이 맞다.
     *
     * @param reserved 아직 안 끝난 작업들이 쓸 몫
     */
    @Transactional(readOnly = true)
    public String whyBlocked(Reserved reserved) {
        Instant from = startOfToday();
        Instant now = Instant.now(clock);

        long runs = usage.runsBetween(from, now) + reserved.runs();
        if (dailyRuns > 0 && runs >= dailyRuns) {
            log.warn("일일 편수 상한에 걸렸습니다 ({}/{}편 · 예약 {}편)",
                    runs, dailyRuns, reserved.runs());
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }

        long krw = usage.krwBetween(from, now) + reserved.krw();
        if (dailyKrw > 0 && krw >= dailyKrw) {
            log.warn("일일 금액 상한에 걸렸습니다 ({}/{}원 · 예약 {}원)",
                    krw, dailyKrw, reserved.krw());
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }
        return null;
    }

    /**
     * 아직 안 끝난 작업이 쓸 몫.
     *
     * @param runs 몇 편
     * @param krw  얼마(원)
     */
    public record Reserved(long runs, long krw) {
        public static final Reserved NONE = new Reserved(0, 0);
    }

    /** 오늘 여기까지 왔다. 화면에 보여 줄 값. */
    @Transactional(readOnly = true)
    public Today today() {
        Instant from = startOfToday();
        Instant now = Instant.now(clock);
        return new Today(usage.runsBetween(from, now), dailyRuns,
                usage.krwBetween(from, now), dailyKrw);
    }

    /**
     * @param runs     오늘 만든 편수
     * @param runLimit 편수 상한 (0 이면 안 셈)
     * @param krw      오늘 나간 돈(원)
     * @param krwLimit 금액 상한 (0 이면 안 셈)
     */
    public record Today(long runs, long runLimit, long krw, long krwLimit) {
    }
}
