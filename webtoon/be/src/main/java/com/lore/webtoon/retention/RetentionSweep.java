package com.lore.webtoon.retention;

import com.lore.common.retention.CommonPurgeRepository;
import com.lore.common.retention.RetentionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>기간이 지난 것을 지운다.</b> 계정이 살아 있어도 도는 쪽이다.
 *
 * <h2>{@code AccountPurge} 와 무엇이 다른가</h2>
 *
 * 저쪽은 <b>사람이 떠났을 때</b> 그 사람 것을 전부 지운다. 이쪽은 <b>계정이
 * 그대로 있어도</b> 오래된 것만 골라 비운다. 처리방침 제4조의 기간표가 이
 * 둘로 나뉜다.
 *
 * <h2>지우는 것과 남기는 것</h2>
 *
 * <table>
 *   <tr><th>대상</th><th>기간</th><th>어떻게</th></tr>
 *   <tr><td>게스트 하루 이용 횟수(IP 해시)</td><td>90일</td><td>행째 삭제</td></tr>
 *   <tr><td>완성 알림 주소</td><td>보낸 뒤 30일</td><td>주소만 비움</td></tr>
 *   <tr><td>생성 기록의 자유 입력</td><td>1년</td><td>칸만 비움</td></tr>
 *   <tr><td>크레딧 기록의 메모</td><td>1년</td><td>칸만 비움</td></tr>
 * </table>
 *
 * 왜 어떤 것은 행째 지우고 어떤 것은 칸만 비우는지는 각 질의의 주석에 적었다.
 * 요지는 <b>지우면 깨지는 것이 있는 자리는 안 지운다</b> 이다 — 중복 발송을
 * 막는 표시, 잔액 합산, 결과 화면의 주소 같은 것들.
 *
 * <h2>기본으로 꺼져 있다</h2>
 *
 * 서버를 여러 대로 띄울 때 <b>한 대에서만</b> 켠다. 이 저장소에는 분산 락이
 * 없고, 시각 트리거를 설정으로 한 대만 켜는 것이 관례다({@code NightSweep}).
 * 여러 대가 같이 돌아도 지우는 문들이 전부 "이미 비워진 것은 안 건드린다"
 * 조건을 달고 있어 결과는 같지만, 같은 표를 동시에 밀 이유가 없다.
 */
@Component
public class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /** 새벽 5시 40분. 04:30(웹툰 청소)·04:50(계정 파기)·05:10(짤 보관) 뒤에 둔다. */
    static final String CRON = "0 40 5 * * *";

    /** ★ zone 을 빼면 UTC 로 돌아 한낮에 표를 훑는다. 반드시 명시한다. */
    static final String ZONE = "Asia/Seoul";

    private final WebtoonPurgeRepository webtoon;
    private final CommonPurgeRepository common;
    private final Clock clock;
    private final boolean enabled;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * ★ {@code @Autowired} 를 반드시 붙인다. 생성자가 둘인데 표시가 없으면 스프링이
     *   기본 생성자를 찾다가 <b>기동이 통째로 실패한다</b>("No default constructor
     *   found"). 단위 테스트는 생성자를 직접 부르므로 이 사고를 못 잡는다 —
     *   2026-09-23 에 실제로 테스트 전부 통과한 채 서버가 안 떴다.
     */
    @Autowired
    public RetentionSweep(WebtoonPurgeRepository webtoon,
                          CommonPurgeRepository common,
                          @Value("${lore.retention.sweep-enabled:false}") boolean enabled) {
        this(webtoon, common, Clock.system(ZoneId.of(ZONE)), enabled);
    }

    /** 테스트가 시계를 갈아 끼울 수 있게 열어 둔다. */
    RetentionSweep(WebtoonPurgeRepository webtoon, CommonPurgeRepository common,
                   Clock clock, boolean enabled) {
        this.webtoon = webtoon;
        this.common = common;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void scheduled() {
        runOnce(Instant.now(clock));
    }

    /**
     * 한 회차. 시각을 밖에서 받는다 — 경계를 테스트에서 직접 물어볼 수 있다.
     *
     * ★ 예외를 한 개도 밖으로 안 내보낸다. 시각 트리거에서 예외가 새면 그
     *   뒤로 이 작업이 다시 안 돈다. 조용히 안 도는 정리 작업은 디스크가
     *   찰 때까지, 혹은 감사를 받을 때까지 아무도 모른다.
     */
    @Transactional
    public Result runOnce(Instant now) {
        if (!enabled) {
            log.debug("기간 파기를 건너뜁니다 — lore.retention.sweep-enabled 가 꺼져 있습니다");
            return Result.NOTHING;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("기간 파기를 건너뜁니다 — 앞 회차가 아직 돌고 있습니다");
            return Result.NOTHING;
        }
        try {
            // 게스트 표에는 시각 칸이 없고 날짜만 있다. 그래서 날짜로 잰다.
            LocalDate guestCut = LocalDate.ofInstant(now, ZoneId.of(ZONE))
                    .minusDays(RetentionPolicy.GUEST_COUNTER.toDays());

            Result r = new Result(
                    webtoon.deleteOldGuestQuota(guestCut),
                    webtoon.clearOldNotifyEmails(now.minus(RetentionPolicy.NOTIFY_EMAIL_AFTER_SENT)),
                    webtoon.clearOldJobInputs(now.minus(RetentionPolicy.GENERATION_RECORD)),
                    common.clearOldCreditMemos(now.minus(RetentionPolicy.GENERATION_RECORD)));

            if (r.any()) {
                log.info("기간이 지난 것을 지웠습니다 — 게스트 횟수 {}행, 알림 주소 {}개, 생성 기록 {}건, 크레딧 메모 {}건",
                        r.guestQuota(), r.notifyEmails(), r.jobInputs(), r.creditMemos());
            }
            return r;
        } catch (RuntimeException e) {
            log.warn("기간 파기가 실패했습니다", e);
            return Result.NOTHING;
        } finally {
            running.set(false);
        }
    }

    /** 이번 회차에 무엇을 했는가. 테스트와 로그가 같은 것을 본다. */
    public record Result(int guestQuota, int notifyEmails, int jobInputs, int creditMemos) {

        static final Result NOTHING = new Result(0, 0, 0, 0);

        boolean any() {
            return guestQuota + notifyEmails + jobInputs + creditMemos > 0;
        }
    }
}
