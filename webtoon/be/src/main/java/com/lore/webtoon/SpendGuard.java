package com.lore.webtoon;

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
 * 하루에 나가는 돈에 상한을 건다.
 *
 * <h2>왜 이것이 진짜 방어선인가</h2>
 *
 * 웹툰은 <b>로그인 없이도 끝까지 만들 수 있다.</b> 그게 이 제품의 약속이라
 * 바꾸지 않는다. 그런데 만들기는 실제로 유료 모델을 부르고, 실측 한 편에
 * 1,148원이 나간다 — 그 돈은 우리 계정 키에서 나간다.
 *
 * 그 앞을 막던 것은 크레딧뿐인데, 크레딧은 <b>브라우저가 만든 uid</b> 로
 * 센다. localStorage 를 지우면 새 사람이고 uid 는 지어낼 수 있다. 그러니
 * 크레딧은 "정직한 사람에게 몇 편인지 알려 주는 것" 이지 지출을 막는 장치가
 * 아니다.
 *
 * 여기서 세는 것은 <b>사람이 아니라 총량</b>이다. 누가 몇 번을 어떻게 우회해도
 * 하루에 나가는 돈은 정해져 있다. 그래서 이것 하나만 확실하면 나머지(무료
 * 횟수·크레딧)는 안내에 가까워진다.
 *
 * <h2>시작하기 전에 본다</h2>
 *
 * 만들기가 시작된 뒤에 막으면 이미 돈이 나간 뒤다. 그래서 만들기 요청을
 * 하네스로 넘기기 <b>전에</b> 여기를 지나간다.
 *
 * <h2>넘겨도 진행 중인 것은 안 끊는다</h2>
 *
 * 상한은 <b>새로 시작하는 것</b>만 막는다. 이미 그리고 있는 작품을 중간에
 * 끊으면 이미 치른 값이 통째로 버려진다 — 아끼려다 더 버리는 셈이다.
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
        Instant from = startOfToday();
        Instant now = Instant.now(clock);

        long runs = usage.runsBetween(from, now);
        if (dailyRuns > 0 && runs >= dailyRuns) {
            log.warn("일일 편수 상한에 걸렸습니다 ({}/{}편)", runs, dailyRuns);
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }

        long krw = usage.krwBetween(from, now);
        if (dailyKrw > 0 && krw >= dailyKrw) {
            log.warn("일일 금액 상한에 걸렸습니다 ({}/{}원)", krw, dailyKrw);
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }
        return null;
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
