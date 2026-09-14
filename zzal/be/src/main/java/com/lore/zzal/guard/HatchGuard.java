package com.lore.zzal.guard;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 부화를 <b>시작해도 되는가</b> — 돈이 나가기 직전의 마지막 문.
 *
 * <h3>★★ 왜 이 문이 필요한가 (2026-09-14, 알파 공개 9/18)</h3>
 * 운영에 실제 그림 생성이 켜져 있고, 부화 한 번이 약 $0.25, 잔액이 $70 이다. 지금까지는
 * <b>사람당 펫 수도, 하루 상한도 없었다</b> — 한 사람이 알을 계속 만들면 만드는 만큼 나간다.
 * 여기서 막는 다섯이 그 길을 전부 닫는다.
 *
 * <ol>
 *   <li>{@link HatchBlock#PET_LIMIT} — 동시 1마리 · 누적 3마리</li>
 *   <li>{@link HatchBlock#DAILY_CAP} — 사람당 하루 3회</li>
 *   <li>{@link HatchBlock#SERVICE_CAP} — 서비스 전체 하루 40회(잔액 방벽)</li>
 *   <li>{@link HatchBlock#IP_RATE} — 한 집에서 몰아치는 것(완화 수준)</li>
 *   <li>{@link HatchBlock#QUOTA} — 바깥이 한도(429)로 막은 동안</li>
 * </ol>
 *
 * <h3>★★ 세는 기준은 "굽기 기록" 이다 — 펫 줄이 아니다</h3>
 * 목적이 <b>돈</b>이기 때문이다. 펫 줄로 세면 실패 뒤의 자동 재시도({@code max-hatch-attempts})가
 * 안 보인다 — 그때 돈은 또 나갔는데 펫은 여전히 한 마리다. {@code zzal_gen_job} 은 시도마다
 * 한 줄이라 실패도 재시도도 버린 초안도 전부 세어진다. 자세한 근거는
 * {@link GenJobRepository#countHatchesOfUser}.
 *
 * <h3>★★ 날짜 경계는 한국 시각 자정이다</h3>
 * 서버가 UTC 로 돌아도 사용자의 "오늘" 은 KST 다. 경계 계산은 이 서비스의 다른 코드와
 * <b>같은 자</b>({@link AwakeClock#dateOf} · {@link ZzalRules#ZONE})를 쓴다 — 여기만 따로
 * {@code LocalDate.now()} 를 부르면 개발 시계로 시험할 수 없고, 규칙이 두 벌이 된다.
 *
 * <h3>★ "지금" 을 직접 읽지 않는다</h3>
 * {@code Instant.now()} 는 이 클래스 어디에도 없다. 부르는 쪽이 준다 — zzal 의 다른 서비스가
 * 전부 그렇게 되어 있고({@code PetService} 주석 "시각은 두 벌이다"), 그래야 시험이 자정 경계를
 * 만들어 볼 수 있다.
 */
@Component
public class HatchGuard {

    private static final Logger log = LoggerFactory.getLogger(HatchGuard.class);

    private final HatchUserLockRepository userLocks;
    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final HatchLimits limits;
    private final IpRateLimiter ipRateLimiter;
    private final QuotaBreaker quotaBreaker;

    public HatchGuard(HatchUserLockRepository userLocks,
                      ZzalPetRepository petRepository,
                      GenJobRepository jobRepository,
                      HatchLimits limits,
                      IpRateLimiter ipRateLimiter,
                      QuotaBreaker quotaBreaker) {
        this.userLocks = userLocks;
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.limits = limits;
        this.ipRateLimiter = ipRateLimiter;
        this.quotaBreaker = quotaBreaker;
    }

    /**
     * 이 사람의 부화 요청을 <b>한 줄로 세운다</b>. 세기 전에 반드시 먼저 부른다.
     *
     * ★ 여기서 사람이 없으면 뒤의 셈이 전부 0 이 되어 <b>없는 사람이 무한히 통과</b>한다.
     *   그래서 없는 것을 조용히 넘기지 않는다.
     */
    public void lockUser(Long userId) {
        userLocks.lockForHatch(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 굽기를 시작해도 되나. 안 되면 {@link HatchBlockedException} 을 던진다.
     *
     * <h3>★ 묻는 순서가 곧 사용자가 보는 이유다</h3>
     * 가장 <b>개인적이고 확실한 것</b>부터 묻는다. 이미 아이가 있는 사람에게 "서비스가 바쁘다" 고
     * 답하면, 기다리면 될 줄 알고 계속 새로고침한다(그 사람은 영원히 안 풀린다).
     * IP 는 마지막이다 — 같은 집의 다른 사람 때문에 막히는 것이 가장 억울한 사유라,
     * 자기 사정으로 설명되는 것이 하나라도 있으면 그쪽을 먼저 말한다.
     *
     * <h3>★★ 누적을 하루보다 <b>먼저</b> 묻는다 — 거짓 약속을 하지 않으려고</h3>
     * 둘 다 걸린 사람에게 "내일 다시 만나요"({@link HatchBlock#DAILY_CAP})라고 답하면, 내일 와도
     * 안 되는 사람에게 <b>거짓말</b>을 한 것이 된다. 누적은 기다려도 안 풀리는 사유라 먼저 말한다.
     * <p>
     * ⚠️ 그 결과, 기본 설정(누적 3 · 하루 3)에서는 <b>하루 상한이 사실상 안 걸린다</b> —
     * 하루 3번을 채운 사람은 누적도 3번이기 때문이다. 하루 상한은 누적을 올려 준 뒤
     * (운영에서 {@code max-total} 을 키우는 날) 비로소 일하는 문이다. 두 숫자가 같은 것은
     * 명세가 그렇게 정한 것이고, 이 문장은 그때 무엇이 먼저 걸리는지를 못 박아 둔 것이다.
     *
     * @param clientIp 접속자 주소. {@code null} 이면 IP 상한만 건너뛴다({@link ClientIp} 주석)
     */
    public void check(Long userId, String clientIp, Instant now) {
        checkPetLimit(userId);
        checkDailyCap(userId, now);
        checkServiceCap(now);
        checkQuota(now);
        checkIpRate(clientIp, now);
    }

    /** 굽기가 실제로 시작됐다 — IP 자국을 남긴다. ★ 막힌 시도는 세지 않는다({@link IpRateLimiter}). */
    public void recordStarted(String clientIp, Instant now) {
        ipRateLimiter.mark(clientIp, limits.ipWindow(), now);
    }

    // ── 다섯 개의 문 ──────────────────────────────────────────────────────

    private void checkPetLimit(Long userId) {
        long alive = petRepository.countByUserIdAndPhaseIn(userId, PetPhase.OCCUPYING_SLOT);
        if (!HatchLimits.off(limits.maxAlive()) && alive >= limits.maxAlive()) {
            throw blocked(HatchBlock.PET_LIMIT,
                    "한 번에 %d명까지 함께 지낼 수 있어요(지금 %d명)".formatted(limits.maxAlive(), alive));
        }
        long total = jobRepository.countHatchesOfUser(userId, GenKind.HATCH);
        if (!HatchLimits.off(limits.maxTotal()) && total >= limits.maxTotal()) {
            throw blocked(HatchBlock.PET_LIMIT,
                    "지금까지 %d번 만들 수 있고 %d번을 다 썼어요".formatted(limits.maxTotal(), total));
        }
    }

    private void checkDailyCap(Long userId, Instant now) {
        if (HatchLimits.off(limits.perUserDaily())) {
            return;
        }
        long today = jobRepository.countHatchesOfUserSince(userId, GenKind.HATCH, startOfDay(now));
        if (today >= limits.perUserDaily()) {
            throw blocked(HatchBlock.DAILY_CAP,
                    "오늘 몫 %d번을 다 썼어요(한국 시각 자정에 초기화)".formatted(limits.perUserDaily()));
        }
    }

    private void checkServiceCap(Instant now) {
        if (HatchLimits.off(limits.serviceDaily())) {
            return;
        }
        long today = jobRepository.countHatchesSince(GenKind.HATCH, startOfDay(now));
        if (today >= limits.serviceDaily()) {
            log.warn("서비스 하루 상한 — 오늘 {}회로 상한 {}회에 닿았다", today, limits.serviceDaily());
            throw blocked(HatchBlock.SERVICE_CAP,
                    "오늘 서비스 전체 몫 %d번을 다 썼어요(한국 시각 자정에 초기화)".formatted(limits.serviceDaily()));
        }
    }

    private void checkQuota(Instant now) {
        Instant opensAt = quotaBreaker.opensAt(limits.quotaCooldown(), now);
        if (opensAt != null) {
            throw blocked(HatchBlock.QUOTA,
                    "그림을 만드는 곳이 한도로 막고 있어요(%d분 뒤에 다시 열려요)"
                            .formatted(Math.max(1, java.time.Duration.between(now, opensAt).toMinutes() + 1)));
        }
    }

    private void checkIpRate(String clientIp, Instant now) {
        if (clientIp == null || HatchLimits.off(limits.ipPerWindow())) {
            return;
        }
        int within = ipRateLimiter.countWithin(clientIp, limits.ipWindow(), now);
        if (within >= limits.ipPerWindow()) {
            throw blocked(HatchBlock.IP_RATE,
                    "같은 곳에서 %d분 동안 %d번까지 시작할 수 있어요(지금 %d번)"
                            .formatted(limits.ipWindow().toMinutes(), limits.ipPerWindow(), within));
        }
    }

    // ── 잔손질 ────────────────────────────────────────────────────────────

    /** 이 시각이 속한 <b>한국 날짜</b>의 0시. 하루 상한 둘이 여기서 갈린다. */
    static Instant startOfDay(Instant now) {
        return AwakeClock.dateOf(now).atStartOfDay(ZzalRules.ZONE).toInstant();
    }

    private HatchBlockedException blocked(HatchBlock block, String message) {
        return new HatchBlockedException(block, message);
    }
}
