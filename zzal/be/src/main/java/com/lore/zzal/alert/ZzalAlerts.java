package com.lore.zzal.alert;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.night.NightSweep;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 운영자에게 <b>돈과 고장</b>을 알리는 넷. 알파 공개(9/18)를 사람 없이 넘기기 위한 장치다.
 *
 * <h3>무엇을 알리나 — 넷뿐이다</h3>
 * <ol>
 *   <li>{@link #generationFinished} — 누적 생성 비용이 $10 단위를 넘을 때마다(잔액 $70)</li>
 *   <li>{@link #hatchFinallyFailed} — 부화가 연달아 실패할 때(기본 3연속)</li>
 *   <li>{@link #serviceDailyCapReached} — 서비스 하루 상한(40회)에 닿은 순간, 그날 한 번</li>
 *   <li>{@link #nightBakeFailed} — 밤 굽기가 실패했을 때, 그 밤 한 번</li>
 * </ol>
 *
 * <h3>★★ "서버가 떴습니다" 는 <b>넣지 않는다</b></h3>
 * 배포가 곧 재시작이라 하루에도 여러 통이 온다. 그렇게 온 메일은 곧 안 읽히고, 그러면 위의 넷도
 * 같은 메일함에서 같이 안 읽힌다. <b>에러와 실패에만</b> 보낸다. 서버가 통째로 죽은 경우는 안에서
 * 알릴 수가 없으므로(죽은 프로세스는 메일도 못 보낸다) 바깥 감시의 몫이다 — 여기서 다루지 않는다.
 *
 * <h3>★★ 어떤 경우에도 서비스를 멈추지 않는다</h3>
 * 모든 진입점이 {@code try/catch(RuntimeException | Error)} 로 끝을 낸다. 경보는 곁다리고,
 * 곁다리가 부화·놀이를 되돌리거나 세우는 거래는 어떤 경우에도 맞지 않는다. 트랜잭션에 얹히지
 * 않는 근거는 {@link JpaAlertLedger} 주석에 있고(REQUIRES_NEW), 발송이 스레드를 붙잡지 않는
 * 근거는 {@link SmtpAlertMailer} 에 있다(한 줄짜리 큐).
 *
 * <h3>★★ 기록을 먼저 남기고 보낸다</h3>
 * 순서가 반대면(보내고 나서 기록) 기록에 실패한 순간 <b>같은 메일이 계속</b> 간다. 지금 순서에서
 * 최악은 "그 한 통을 잃는 것" 인데, 비용은 다음 $10 단위에서, 나머지는 다음 사건에서 다시 알린다.
 * 안 읽히는 알림함이 되는 쪽이 한 통을 잃는 쪽보다 훨씬 나쁘다.
 *
 * <h3>★ 시각은 부르는 쪽이 준다</h3>
 * 이 클래스에 {@code Instant.now()} 는 없다 — zzal 의 다른 코드와 같은 규칙이고, 그래야 시험이
 * 자정 경계나 밤을 만들어 볼 수 있다.
 */
@Component
public class ZzalAlerts {

    private static final Logger log = LoggerFactory.getLogger(ZzalAlerts.class);

    /** 제목 앞머리. 메일함에서 한눈에 걸러지게 고정한다. */
    static final String PREFIX = "[zzal 경보]";

    /**
     * 연속 실패를 셀 때 거슬러 보는 최대 줄 수.
     *
     * ★ 이만큼이 전부 실패면 구간의 시작을 못 찾아 같은 사고로 한 통이 더 갈 수 있다.
     *   50연속 실패는 이미 서비스가 통째로 멈춘 상태라, 그때 한 통 더 오는 것은 문제가 아니다.
     */
    private static final int STREAK_WINDOW = 50;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZzalRules.ZONE);

    private final AlertSettings settings;
    private final AlertMailer mailer;
    private final AlertLedger ledger;
    private final GenJobRepository jobs;
    private final ZzalPetRepository pets;

    public ZzalAlerts(AlertSettings settings, AlertMailer mailer, AlertLedger ledger,
                      GenJobRepository jobs, ZzalPetRepository pets) {
        this.settings = settings;
        this.mailer = mailer;
        this.ledger = ledger;
        this.jobs = jobs;
        this.pets = pets;
    }

    // ── 1) 돈 ─────────────────────────────────────────────────────────────

    /**
     * 굽기 한 판이 끝났다 — 누적 비용이 다음 $10 단위를 넘었으면 알린다.
     *
     * <h3>★★ 왜 부화만이 아니라 <b>생성 전부</b>를 세나</h3>
     * 알고 싶은 것은 "부화 표의 합계" 가 아니라 <b>잔액이 얼마나 줄었나</b>다. 밤 굽기(심화 행동)도
     * 같은 계정에서 같은 돈을 쓴다 — 한 밤에 200장이면 약 $20 이라 부화보다 클 수 있다. 부화만 세면
     * 그쪽으로 새는 돈은 <b>한 번도 안 알린다.</b> 대신 메일 본문에서 부화·심화를 갈라 보여 준다.
     *
     * <h3>★ 왜 {@code zzal_gen_job.total_cost_usd} 인가 — 단계 표가 아니라</h3>
     * 단계 기록({@code zzal_gen_step})은 <b>지워진다</b>. 거부·격자 이상으로 다시 구울 때 성공한
     * 단계를 폐기하기 때문이다({@code GenerationRecorder.discardSucceeded}). 그러면 이미 나간 돈이
     * 합계에서 빠져 <b>누적이 줄어든다.</b> job 줄은 시도마다 하나씩 남고 지워지지 않아, 실패한
     * 시도의 값도(실패해도 200 을 받은 단계의 돈은 나갔다) 그대로 들어 있다.
     * 이어받은 단계는 다시 굽지 않으므로 두 번 세지도 않는다.
     */
    public void generationFinished(Instant now) {
        try {
            if (!settings.canSend() || settings.costStepUsd() <= 0) {
                return;
            }
            BigDecimal total = zeroIfNull(jobs.sumAllCost());
            int step = settings.costStepUsd();
            int reached = total.divideToIntegralValue(BigDecimal.valueOf(step)).intValue() * step;
            if (reached < step) {
                return;                                  // 아직 첫 임계 전
            }
            if (!ledger.claimCostThreshold(reached, now)) {
                return;                                  // 그 임계는 이미 알렸다(재시작해도 표에 남아 있다)
            }
            BigDecimal today = zeroIfNull(jobs.sumCostSince(startOfDay(now)));
            BigDecimal hatch = zeroIfNull(jobs.sumCostByKind(GenKind.HATCH));
            BigDecimal motion = zeroIfNull(jobs.sumCostByKind(GenKind.MOTION));
            send("생성 비용 누적 $%d 돌파 — 오늘 $%s".formatted(reached, money(today)),
                    """
                    생성에 쓴 돈이 누적 $%d 을(를) 넘었습니다.

                      누적 합계   $%s   (부화 $%s · 심화 $%s)
                      오늘(KST)   $%s
                      기준 시각   %s KST

                    다음 알림은 누적 $%d 을(를) 넘을 때입니다.
                    줄이려면 app.zzal.hatch-limits.service-daily (ZZAL_HATCH_SERVICE_DAILY, 지금 하루 상한)를 낮추고,
                    밤 굽기는 app.zzal.night.max-bakes (ZZAL_NIGHT_MAX_BAKES)를 봅니다."""
                            .formatted(reached, money(total), money(hatch), money(motion),
                                    money(today), WHEN.format(now), reached + step));
        } catch (RuntimeException | Error e) {
            swallow("비용", e);
        }
    }

    // ── 2) 부화 연속 실패 ─────────────────────────────────────────────────

    /**
     * 부화 한 마리가 <b>끝내</b> 실패했다(재시도까지 다 쓴 뒤). 연달아 N번째면 알린다.
     *
     * <h3>★★ 왜 3연속인가</h3>
     * 한 마리는 이미 안에서 두 번 굽는다({@code app.zzal.max-hatch-attempts=2}). 그리고 실패 하나는
     * 평상시에도 난다 — 거부(moderation)는 그림에 따라 그냥 일어나는 일이다. 반면 <b>3마리가
     * 연달아</b> 실패했다면 시도가 최소 6번 실패한 것이고(약 $1.5 를 버렸고, 하루 상한 40의 7.5%),
     * 그 모양은 그림 탓이 아니라 <b>열쇠·잔액·파이프라인</b>이 통째로 막힌 쪽에 가깝다.
     * 1~2연속에서 부르면 사람이 곧 무시하게 되고, 4~5까지 기다리면 그만큼 돈과 시간을 더 버린다.
     * 숫자는 {@code app.zzal.alert.hatch-fail-streak} 로 바꾼다.
     *
     * <h3>★★ 한 구간에 한 통 — 무엇을 기억하나</h3>
     * "연속 3번" 에서 멈추지 않고 4번째·5번째가 이어질 때 매번 보내면 사고 한 건에 메일이 쏟아진다.
     * 그래서 <b>그 구간의 첫 펫 id</b> 를 기억한다 — 구간이 이어지는 동안 이 값은 안 바뀌므로
     * 두 번째 통부터는 막히고, 성공이 한 번 끼어 구간이 끊기면 값이 달라져 다시 알린다.
     *
     * @param petId 방금 실패한 알(기록·로그용. 판정은 표를 다시 읽어서 한다 — 메모리에 세지 않는다)
     */
    public void hatchFinallyFailed(Long petId, Instant now) {
        try {
            if (!settings.canSend() || settings.hatchFailStreak() <= 0) {
                return;
            }
            // ★ 끝난 것만 본다 — 굽는 중(DRAFT·HATCHING)은 아직 성공도 실패도 아니다.
            List<ZzalPet> recent = pets.findByPhaseInOrderByIdDesc(
                    List.of(PetPhase.ALIVE, PetPhase.FAILED), PageRequest.of(0, STREAK_WINDOW));
            int streak = 0;
            while (streak < recent.size() && recent.get(streak).getPhase() == PetPhase.FAILED) {
                streak++;
            }
            if (streak < settings.hatchFailStreak()) {
                return;
            }
            Long streakStart = recent.get(streak - 1).getId();
            if (!ledger.claimOnce(AlertKeys.HATCH_FAIL_STREAK, String.valueOf(streakStart), now)) {
                return;                                  // 이 구간은 이미 알렸다
            }
            List<ZzalPet> failed = recent.subList(0, streak);
            send("부화 %d연속 실패 — 굽기가 막혔을 수 있습니다".formatted(streak),
                    """
                    부화가 %d번 연달아 실패했습니다(가장 최근이 petId=%d).

                      실패한 알   %s
                      마지막 사유 %s
                      기준 시각   %s KST

                    한 마리가 안에서 이미 2번 굽습니다 — 연속 실패는 그림 탓보다 열쇠·잔액·파이프라인 쪽입니다.
                    서버 로그에서 'HTTP 429' · 'insufficient_quota' · 'moderation' 중 무엇인지 먼저 봅니다.
                    성공이 한 번 나오면 이 알림은 다시 열립니다."""
                            .formatted(streak, petId, ids(failed), lastErrors(failed), WHEN.format(now)));
        } catch (RuntimeException | Error e) {
            swallow("부화 연속 실패", e);
        }
    }

    // ── 3) 하루 상한 ──────────────────────────────────────────────────────

    /**
     * 서비스 전체 하루 상한에 닿았다 — <b>그날 한 번만</b> 알린다.
     *
     * ★ 닿은 뒤에는 오는 요청마다 이 자리를 지난다(전부 거절된다). 매번 보내면 그날 수십 통이 된다.
     *   기억하는 값이 <b>날짜</b>라서 같은 날의 두 번째부터는 막히고, 자정이 지나면 다시 열린다.
     */
    public void serviceDailyCapReached(long usedToday, int cap, Instant now) {
        try {
            if (!settings.canSend()) {
                return;
            }
            LocalDate day = AwakeClock.dateOf(now);
            if (!ledger.claimOnce(AlertKeys.SERVICE_DAILY_CAP, day.toString(), now)) {
                return;
            }
            BigDecimal today = zeroIfNull(jobs.sumCostSince(startOfDay(now)));
            send("오늘 하루 상한 %d회 도달 — 새 부화가 막힙니다".formatted(cap),
                    """
                    서비스 전체 하루 상한에 닿았습니다. 지금부터 오늘 안의 새 부화는 전부 거절됩니다.

                      날짜(KST)   %s
                      오늘 시작   %d회 / 상한 %d회
                      오늘 비용   $%s
                      기준 시각   %s KST

                    한국 시각 자정에 저절로 풀립니다.
                    더 열려면 app.zzal.hatch-limits.service-daily (ZZAL_HATCH_SERVICE_DAILY)를 올립니다 —
                    그 숫자가 곧 하루에 나갈 수 있는 돈의 상한입니다(1회 약 $0.25)."""
                            .formatted(day, usedToday, cap, money(today), WHEN.format(now)));
        } catch (RuntimeException | Error e) {
            swallow("하루 상한", e);
        }
    }

    // ── 4) 밤 굽기 ────────────────────────────────────────────────────────

    /**
     * 밤 굽기(심화 행동)가 실패했다 — <b>그 밤 한 번만</b> 알린다.
     *
     * ★ 한 밤에 최대 200장을 굽는다. 장마다 보내면 사고 한 번에 메일이 200통이다. 첫 통만 보내고
     *   나머지는 로그에 남긴다 — 사람이 알아야 하는 것은 "오늘 밤 굽기가 깨졌다" 하나이고,
     *   몇 장이 깨졌는지는 관리자 화면과 로그에서 본다.
     * ★ 밤의 경계는 스위프와 같은 자를 쓴다({@link NightSweep#nightOf}) — 새벽 1시의 실패는
     *   <b>어제 밤</b>의 실패다. 여기서 따로 날짜를 계산하면 규칙이 두 벌이 된다.
     */
    public void nightBakeFailed(Long motionId, String reason, Instant now) {
        try {
            if (!settings.canSend()) {
                return;
            }
            LocalDate night = NightSweep.nightOf(now);
            if (!ledger.claimOnce(AlertKeys.NIGHT_BAKE_FAILED, night.toString(), now)) {
                return;
            }
            send("밤 굽기 실패 — %s 밤".formatted(night),
                    """
                    밤에 굽던 심화 행동이 실패했습니다.

                      밤(KST)     %s
                      동작 번호   motionId=%s
                      사유        %s
                      기준 시각   %s KST

                    이 밤의 추가 실패는 메일로 보내지 않습니다(로그와 관리자 화면에 남습니다).
                    실패한 자리는 조각을 쓰지 않고 다음 밤에 다시 오릅니다 — 사용자 쪽은 하루 밀립니다."""
                            .formatted(night, motionId, blankToDash(reason), WHEN.format(now)));
        } catch (RuntimeException | Error e) {
            swallow("밤 굽기", e);
        }
    }

    // ── 잔손질 ────────────────────────────────────────────────────────────

    /**
     * 받는 사람 전부에게 한 통씩. <b>한 사람이 실패해도 나머지는 간다.</b>
     *
     * ★ 발송을 먼저 하고 기록하는 것이 아니라, 부르는 쪽이 이미 기록을 마치고 여기로 온다.
     */
    private void send(String subject, String body) {
        String full = PREFIX + " " + subject;
        for (String to : settings.recipients()) {
            try {
                mailer.send(to, full, body);
            } catch (RuntimeException | Error e) {
                log.error("경보 메일을 넘기지 못했습니다 — 제목={} (서비스는 그대로 진행합니다)", full, e);
            }
        }
    }

    /** 경보 쪽에서 무슨 일이 나든 부르는 쪽으로 넘기지 않는다. 원인은 로그에만 남긴다. */
    private void swallow(String what, Throwable e) {
        log.error("경보 처리 실패({}) — 서비스는 그대로 진행합니다", what, e);
    }

    private static Instant startOfDay(Instant now) {
        return AwakeClock.dateOf(now).atStartOfDay(ZzalRules.ZONE).toInstant();
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 돈은 두 자리까지만 보여 준다 — 표에는 네 자리로 남아 있다. */
    private static String money(BigDecimal v) {
        return zeroIfNull(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String ids(List<ZzalPet> petsInStreak) {
        return petsInStreak.stream().map(p -> String.valueOf(p.getId())).reduce((a, b) -> a + ", " + b).orElse("-");
    }

    /** 실패한 알들의 마지막 시도가 남긴 사유. 무엇을 볼지 정하는 단서다. */
    private String lastErrors(List<ZzalPet> petsInStreak) {
        return petsInStreak.stream()
                .map(p -> jobs.findFirstByPetIdOrderByIdDesc(p.getId())
                        .map(GenJob::getErrorCode)
                        .map(String::valueOf)
                        .orElse("기록 없음"))
                .reduce((a, b) -> a + ", " + b)
                .orElse("-");
    }

    private static String blankToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
