package com.lore.common.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>탈퇴하고 30일이 지난 계정을 실제로 지운다.</b>
 *
 * <h2>왜 필요한가</h2>
 *
 * 탈퇴는 표시만 남긴다({@code User.withdraw()} — status=DELETED, deletedAt=now).
 * 실수로 탈퇴한 사람의 작품이 즉시 사라지면 되돌릴 수 없어서다. 그 대신
 * <b>30일이 지나면 진짜로 지우겠다고 개인정보처리방침 제11조에 적었다.</b>
 * 이 클래스가 그 약속을 지키는 자리다. 2026-09-23 이전에는 적어만 놓고
 * 지우는 코드가 없었다.
 *
 * <h2>지우는 순서가 중요하다</h2>
 *
 * <ol>
 *   <li><b>도메인 먼저</b> — 각 도메인이 자기 표와 S3 객체를 지운다
 *       ({@link UserDataPurge}). 그림을 먼저 지워야 한다. DB 행을 먼저 지우면
 *       어느 키를 지워야 하는지 알 길이 사라져 그림이 영영 남는다.</li>
 *   <li><b>계정에 딸린 것</b> — 동의 기록·자격증명·토큰. 이 셋은 users 를
 *       가리키는 외래키가 있어서, 안 지우면 DB 가 마지막 단계를 막는다.</li>
 *   <li><b>계정</b></li>
 * </ol>
 *
 * <h2>여러 번 돌아도 괜찮다</h2>
 *
 * 한 사람을 지우다 중간에 끊겨도 그 사람은 여전히 "지울 때가 된" 목록에
 * 남는다(계정을 맨 마지막에 지우므로). 다음 날 다시 돌면 남은 것부터 이어서
 * 지운다. 그래서 각 단계가 <b>이미 없는 것에 다시 불려도 조용해야 한다.</b>
 *
 * <h2>기본으로 꺼져 있다</h2>
 *
 * 되돌릴 수 없는 일을 하는 작업이라, 켜는 것을 명시적으로 만들었다. 서버를
 * 여러 대로 띄울 때 <b>한 대에서만</b> 켜야 하는 이유도 있다 — 이 저장소에는
 * 분산 락이 없고, 시각 트리거를 설정으로 한 대만 켜는 것이 관례다
 * ({@code NightSweep} 의 같은 주석).
 */
@Component
public class AccountPurge {

    private static final Logger log = LoggerFactory.getLogger(AccountPurge.class);

    /** 새벽 4시 50분. 04:30(웹툰 청소)·05:10(짤 보관)과 겹치지 않게 사이에 둔다. */
    static final String CRON = "0 50 4 * * *";

    /** ★ zone 을 빼면 UTC 로 돌아 한낮에 표를 훑는다. 반드시 명시한다. */
    static final String ZONE = "Asia/Seoul";

    /** 한 번에 처리할 사람 수. 오래 도는 작업이 DB 연결을 붙잡고 있지 않게 끊는다. */
    private static final int BATCH = 200;

    private final PurgeableUserRepository users;
    private final AccountPurgeStep step;
    private final boolean enabled;

    /** 겹침 방지. 앞 회차가 아직 도는데 다음 트리거가 오면 그냥 건너뛴다. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AccountPurge(PurgeableUserRepository users,
                        AccountPurgeStep step,
                        @Value("${lore.retention.account-purge-enabled:false}") boolean enabled) {
        this.users = users;
        this.step = step;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void scheduled() {
        runOnce(Instant.now());
    }

    /**
     * 한 회차. 시각을 밖에서 받는다 — 보존기간 경계를 테스트에서 직접 물어볼 수 있다.
     *
     * ★ 예외를 한 개도 밖으로 안 내보낸다. 시각 트리거에서 예외가 새면 그
     *   뒤로 이 작업이 다시 안 도는데, <b>조용히 안 도는 정리 작업은 아무도
     *   모른다.</b>
     */
    public Result runOnce(Instant now) {
        if (!enabled) {
            return Result.skipped("lore.retention.account-purge-enabled 가 꺼져 있습니다");
        }
        if (!running.compareAndSet(false, true)) {
            return Result.skipped("앞 회차가 아직 돌고 있습니다");
        }
        try {
            Instant cut = now.minus(RetentionPolicy.AFTER_WITHDRAWAL);
            List<Long> ids = users.idsToPurge(cut);
            if (ids.isEmpty()) {
                return new Result(0, 0, 0);
            }
            int done = 0;
            int rows = 0;
            int failed = 0;
            for (Long id : ids.subList(0, Math.min(BATCH, ids.size()))) {
                try {
                    rows += step.purge(id);
                    done++;
                } catch (RuntimeException e) {
                    // 한 사람의 데이터가 이상해서 나머지 전부가 안 지워지면 안 된다.
                    failed++;
                    log.warn("계정을 못 지웠습니다 (user={})", id, e);
                }
            }
            log.info("탈퇴 {}일이 지난 계정 {}명을 지웠습니다 (행 {}개, 실패 {}명, 남은 대상 {}명)",
                    RetentionPolicy.AFTER_WITHDRAWAL.toDays(), done, rows, failed,
                    Math.max(0, ids.size() - done - failed));
            return new Result(done, rows, failed);
        } catch (RuntimeException e) {
            log.warn("계정 파기가 통째로 실패했습니다", e);
            return new Result(0, 0, 0);
        } finally {
            running.set(false);
        }
    }

    /** 이번 회차에 무엇을 했는가. 테스트와 로그가 같은 것을 본다. */
    public record Result(int accounts, int rows, int failed) {

        static Result skipped(String why) {
            log.debug("계정 파기를 건너뜁니다 — {}", why);
            return new Result(0, 0, 0);
        }
    }
}
