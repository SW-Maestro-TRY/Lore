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

    /** 운영 알림(#831). 상한에 걸리면 사용자가 막힌 것이라 운영자가 알아야 한다. 없으면 알리지 않는다. */
    private com.lore.webtoon.ops.OpsAlert opsAlert;

    @Autowired(required = false)
    void setOpsAlert(com.lore.webtoon.ops.OpsAlert opsAlert) {
        this.opsAlert = opsAlert;
    }

    /** 그날만 바꾼 상한(관리자 화면, #617). 없으면 설정값만 쓴다. */
    private SpendOverrides overrides;

    @Autowired(required = false)
    void setOverrides(SpendOverrides overrides) {
        this.overrides = overrides;
    }

    /** 알림에 붙일 관리자 화면 주소의 앞부분. */
    private String site = "https://lorecomic.com";

    @Value("${lore.webtoon.site-url:https://lorecomic.com}")
    void setSite(String site) {
        this.site = site == null || site.isBlank() ? this.site : site.replaceAll("/+$", "");
    }

    /** 오늘 처음 걸린 날. 그날 첫 번째만 사람을 부른다(@here). */
    private volatile LocalDate pinged;

    /*
     * 상한에 걸렸다고 알린다. <b>그날 처음 걸린 것만 급한 것(@here)으로 보낸다</b> — 더 받을지 사람이 정해야
     * 하는 순간이라 폰이 울려야 한다. 그 뒤는 지금처럼 10분에 한 번 조용히 「그 사이 N번 더」로 묶인다
     * (막힌 사람 수를 세는 자리다). 올리는 길은 관리자 화면이다 — 서버를 다시 띄우지 않는다.
     */
    private void alertBlocked(String what, long now, long cap) {
        if (opsAlert == null) {
            return;
        }
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZONE);
        boolean first = !day.equals(pinged);
        pinged = day;
        String body = "오늘 " + now + " / 상한 " + cap + " (아직 안 끝난 작업 몫 포함). 더 받으려면 관리자 화면 개요의 "
                + "「오늘 생성 상한」에서 오늘만 올리세요 — 서버를 다시 띄우지 않아도 됩니다.\n관리: " + site + "/webtoon?view=admin";
        if (first) {
            opsAlert.send(com.lore.webtoon.ops.OpsAlert.Level.URGENT, "spend-cap-first:" + day,
                    "하루 " + what + " 상한에 걸려 만들기가 막혔어요 — 오늘 처음", body);
        } else {
            opsAlert.send(com.lore.webtoon.ops.OpsAlert.Level.WARN, "spend-cap:" + what,
                    "하루 " + what + " 상한에 걸려 만들기가 막혔어요", body);
        }
    }

    /**
     * 오늘 쓰는 상한 — 그날만 바꾼 값이 있으면 그것, 아니면 설정값.
     *
     * @param runs        편수 상한 (0 이면 안 셈)
     * @param krw         금액 상한(원, 0 이면 안 셈)
     * @param defaultRuns 설정값 — 자정이 지나면 돌아갈 값
     * @param defaultKrw  설정값
     * @param today       오늘만 바꾼 값. 없으면 null
     */
    public record Caps(long runs, long krw, long defaultRuns, long defaultKrw, SpendOverrides.DayCap today) {
    }

    public Caps caps() {
        SpendOverrides.DayCap o = overrides == null ? null : overrides.today();
        if (o == null) {
            return new Caps(dailyRuns, dailyKrw, dailyRuns, dailyKrw, null);
        }
        return new Caps(o.runs() == null ? dailyRuns : o.runs(), o.krw() == null ? dailyKrw : o.krw(),
                dailyRuns, dailyKrw, o);
    }

    /**
     * 오늘(한국 자정까지)만 상한을 바꾼다 — 관리자 화면. 서버를 다시 띄우지 않고 바로 적용된다.
     * 운영 채널에 누가 무엇을 바꿨는지 남긴다.
     */
    public Caps setToday(long krw, long runs, Long by) {
        if (overrides == null) {
            throw new IllegalStateException("오늘만 바꾸는 저장소가 없습니다");
        }
        Caps before = caps();
        overrides.set(krw, runs, by);
        Caps after = caps();
        announce(before, after, by, "오늘 생성 상한을 바꿨어요");
        return after;
    }

    /** 오늘 바꾼 것을 무르고 설정값으로 돌아간다. */
    public Caps clearToday(Long by) {
        if (overrides == null) {
            throw new IllegalStateException("오늘만 바꾸는 저장소가 없습니다");
        }
        Caps before = caps();
        overrides.clear(by);
        Caps after = caps();
        announce(before, after, by, "오늘 생성 상한을 기본값으로 돌렸어요");
        return after;
    }

    private void announce(Caps before, Caps after, Long by, String title) {
        log.warn("{} — 금액 {} → {}원 · 편수 {} → {}편 (관리자 {})", title,
                before.krw(), after.krw(), before.runs(), after.runs(), by);
        if (opsAlert != null) {
            opsAlert.send(com.lore.webtoon.ops.OpsAlert.Level.OK,
                    "spend-override:" + after.krw() + ":" + after.runs() + ":" + (after.today() == null),
                    title,
                    "금액 " + before.krw() + " → " + after.krw() + "원 · 편수 " + before.runs() + " → " + after.runs() + "편"
                            + (after.today() == null ? "" : " · 오늘 자정(한국 시각)까지, 그 뒤 기본값 "
                            + after.defaultKrw() + "원 · " + after.defaultRuns() + "편")
                            + " · 관리자 #" + by);
        }
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
        Caps caps = caps();

        long runs = usage.runsBetween(from, now) + reserved.runs();
        if (caps.runs() > 0 && runs >= caps.runs()) {
            log.warn("일일 편수 상한에 걸렸습니다 ({}/{}편 · 예약 {}편)",
                    runs, caps.runs(), reserved.runs());
            alertBlocked("편수", runs, caps.runs());
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }

        long krw = usage.krwBetween(from, now) + reserved.krw();
        if (caps.krw() > 0 && krw >= caps.krw()) {
            log.warn("일일 금액 상한에 걸렸습니다 ({}/{}원 · 예약 {}원)",
                    krw, caps.krw(), reserved.krw());
            alertBlocked("금액", krw, caps.krw());
            return "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.";
        }
        return null;
    }

    /**
     * 돈만 본다 — 캐릭터 만들기처럼 <b>웹툰 한 편이 아닌</b> 호출이 묻는다(#444).
     *
     * 편수 상한은 웹툰 몫이라 여기서 안 본다. 웹툰 30편을 채운 날에도 캐릭터는
     * 만들 수 있어야 하지만, 하루 돈 상한을 넘겨서까지 그리면 안 된다.
     *
     * @return 막을 이유. 괜찮으면 {@code null}
     */
    @Transactional(readOnly = true)
    public String whyBlockedByMoney() {
        Instant from = startOfToday();
        long krw = usage.krwBetween(from, Instant.now(clock));
        long cap = caps().krw();
        if (cap > 0 && krw >= cap) {
            log.warn("일일 금액 상한에 걸렸습니다 — 캐릭터 ({}/{}원)", krw, cap);
            alertBlocked("금액", krw, cap);
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
        Caps caps = caps();
        return new Today(usage.runsBetween(from, now), caps.runs(),
                usage.krwBetween(from, now), caps.krw());
    }

    /**
     * 관리자 화면용 — 오늘 쓴 것 · 아직 안 끝난 몫 · 상한 · 막혔는지. <b>알림을 보내지 않는다</b>
     * ({@link #whyBlocked(Reserved)} 는 막히면 알린다 — 관리자가 화면을 볼 때마다 알림이 가면 안 된다).
     */
    @Transactional(readOnly = true)
    public Status status(Reserved reserved) {
        Instant from = startOfToday();
        Instant now = Instant.now(clock);
        Caps caps = caps();
        long runs = usage.runsBetween(from, now);
        long krw = usage.krwBetween(from, now);
        boolean blocked = (caps.runs() > 0 && runs + reserved.runs() >= caps.runs())
                || (caps.krw() > 0 && krw + reserved.krw() >= caps.krw());
        return new Status(LocalDate.ofInstant(now, ZONE), runs, krw, reserved, caps, blocked);
    }

    /**
     * @param day      한국 날짜
     * @param runs     오늘 만든 편수(적힌 것)
     * @param krw      오늘 나간 돈(원, 적힌 것)
     * @param reserved 아직 안 끝난 작업이 쓸 몫 — 상한은 이것까지 더해서 본다
     * @param caps     오늘 쓰는 상한
     * @param blocked  지금 새로 만들면 막히는가
     */
    public record Status(LocalDate day, long runs, long krw, Reserved reserved, Caps caps, boolean blocked) {
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
