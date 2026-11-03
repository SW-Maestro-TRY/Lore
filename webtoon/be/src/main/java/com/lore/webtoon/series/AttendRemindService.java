package com.lore.webtoon.series;

import com.lore.common.credit.CreditAttendanceService;
import com.lore.common.credit.CreditService;
import com.lore.common.email.EmailService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.job.JobNotice;
import com.lore.webtoon.job.NotifySettingService;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.push.AttendPush;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 「출석으로 모아서 다음 화 만들기」 알림(2026-11-03).
 *
 * <h2>무엇을 하나</h2>
 *
 * 다음 화 화면에서 크레딧이 모자란 사람에게 두 갈래(충전 · 출석)를 보여 준다. 출석을 고르면
 * 여기에 한 줄이 생기고, 날마다 {@link #CRON} 에 한 번 본다.
 * <ul>
 *   <li>잔액이 그 화 값 이상이면 「N화를 만들 수 있어요」 — 푸시와 메일 한 번씩, 그리고 끝</li>
 *   <li>모자라고 오늘 아직 출석 안 했으면 「오늘 출석 +2C」 푸시 — 하루 한 번</li>
 *   <li>{@link #KEEP} 이 지나도록 못 모았으면 그만 보낸다</li>
 * </ul>
 *
 * 메일은 다 모였을 때만 보낸다. 날마다 메일을 보내면 사람이 고른 것보다 많이 간다.
 * 메일 주소와 「알림 메일 받기」 설정은 작업 완성 메일({@link JobNotice})과 같은 것을 본다.
 *
 * 보내기가 실패해도 다음 줄은 계속 본다. 한 사람 때문에 나머지가 안 받으면 안 된다.
 */
@Service
public class AttendRemindService {

    private static final Logger log = LoggerFactory.getLogger(AttendRemindService.class);

    /** 낮 12시. 출석은 자정에 새로 열리니 하루의 가운데에 한 번 알린다. */
    static final String CRON = "0 0 12 * * *";
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 고른 뒤 이만큼 지나면 그만 보낸다. 출석만으로 한 편(20C)을 모으는 데 한 주 남짓 걸린다. */
    static final Duration KEEP = Duration.ofDays(14);

    private static final Pattern RUN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final AttendRemindRepository reminds;
    private final CreditService credits;
    private final CreditAttendanceService attendance;
    private final AttendPush push;
    private final EmailService mail;
    private final UserRepository users;
    private final NotifySettingService settings;
    private final WebtoonJobRepository jobs;
    private final SeriesChapterRepository chapters;
    private final String site;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public AttendRemindService(AttendRemindRepository reminds, CreditService credits,
                               CreditAttendanceService attendance, AttendPush push, EmailService mail,
                               UserRepository users, NotifySettingService settings,
                               WebtoonJobRepository jobs, SeriesChapterRepository chapters,
                               @Value("${lore.webtoon.site-url:https://lorecomic.com}") String site,
                               @Value("${lore.webtoon.attend-remind.enabled:true}") boolean enabled) {
        this(reminds, credits, attendance, push, mail, users, settings, jobs, chapters, site, enabled, Clock.system(ZONE));
    }

    AttendRemindService(AttendRemindRepository reminds, CreditService credits,
                        CreditAttendanceService attendance, AttendPush push, EmailService mail,
                        UserRepository users, NotifySettingService settings,
                        WebtoonJobRepository jobs, SeriesChapterRepository chapters, String site, boolean enabled,
                        Clock clock) {
        this.reminds = reminds;
        this.credits = credits;
        this.attendance = attendance;
        this.push = push;
        this.mail = mail;
        this.users = users;
        this.settings = settings;
        this.jobs = jobs;
        this.chapters = chapters;
        this.site = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
        this.enabled = enabled;
        this.clock = clock;
    }

    /** 지금 상태. 화면이 「알림 받는 중」을 그릴 때 쓴다. */
    @Transactional(readOnly = true)
    public Map<String, Object> state(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        AttendRemind row = userId == null ? null : reminds.findById(userId).orElse(null);
        boolean on = row != null && row.isActive();
        out.put("on", on);
        out.put("run", on ? row.getRunId() : null);
        out.put("episode", on ? row.getEpisode() : null);
        out.put("push", push.enabled());
        out.put("device", userId != null && push.hasDevice(userId));
        return out;
    }

    /** 이 화로 알림을 건다. 다른 화에 걸어 둔 것이 있으면 이 화로 바꾼다. */
    @Transactional
    public Map<String, Object> on(Long userId, String runId, int episode, int cost) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (runId == null || !RUN.matcher(runId).matches() || episode < 2 || episode > 999 || cost < 1 || cost > 999) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알림을 걸 화를 알 수 없습니다");
        }
        AttendRemind row = reminds.findById(userId).orElseGet(() -> new AttendRemind(userId));
        row.plan(runId, episode, cost, Instant.now(clock));
        reminds.save(row);
        return state(userId);
    }

    /** 알림을 끈다. */
    @Transactional
    public Map<String, Object> off(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        reminds.findById(userId).filter(AttendRemind::isActive).ifPresent(r -> {
            r.done(Instant.now(clock));
            reminds.save(r);
        });
        return state(userId);
    }

    @Scheduled(cron = CRON, zone = "Asia/Seoul")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {          // 시각 트리거에서 예외가 새면 다음부터 안 돈다
            log.warn("출석 알림을 돌리지 못했습니다", e);
        }
    }

    /** 걸려 있는 줄을 한 번씩 본다. 몇 통을 보냈는지 돌려준다(테스트·로그용). */
    int sweep() {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);
        int sent = 0;
        for (AttendRemind row : reminds.findByDoneAtIsNull()) {
            try {
                sent += one(row, now, today);
            } catch (RuntimeException e) {
                log.warn("출석 알림 한 줄을 처리하지 못했습니다 (user={})", row.getUserId(), e);
            }
        }
        if (sent > 0) {
            log.info("출석 알림 {}통을 보냈습니다", sent);
        }
        return sent;
    }

    private int one(AttendRemind row, Instant now, LocalDate today) {
        Long userId = row.getUserId();
        if (alreadyMade(row)) {                 // 그 화를 이미 이어 만들었다 — 알릴 것이 없다
            row.done(now);
            reminds.save(row);
            return 0;
        }
        int balance = credits.balance(userId);
        if (balance >= row.getCost()) {
            int n = push.ready(userId, row.getEpisode(), row.getRunId()) + mailReady(row);
            row.done(now);
            reminds.save(row);
            return n;
        }
        if (row.getCreatedAt().plus(KEEP).isBefore(now)) {
            row.done(now);
            reminds.save(row);
            return 0;
        }
        if (today.equals(row.getLastSentOn())) {
            return 0;
        }
        CreditAttendanceService.Status card = attendance.status(userId);
        if (!card.open() || card.claimed()) {
            return 0;
        }
        int n = push.attend(userId, row.getEpisode(), card.reward(), row.getCost() - balance);
        row.sentOn(today);
        reminds.save(row);
        return n;
    }

    private boolean alreadyMade(AttendRemind row) {
        return jobs.findFirstByRunIdOrderByIdDesc(row.getRunId())
                .map(j -> chapters.existsBySourceJob(j.getPublicId()))
                .orElse(false);
    }

    /** 다 모였다는 메일. 보낼 데가 없거나 메일 알림을 꺼 두었으면 0. */
    private int mailReady(AttendRemind row) {
        if (!settings.isOn(row.getUserId())) {
            return 0;
        }
        String to = users.findById(row.getUserId()).map(User::getEmail).map(JobNotice::clean).orElse(null);
        if (to == null) {
            return 0;
        }
        String link = site + "/webtoon?view=next&run=" + row.getRunId();
        String subject = "[LORE] " + row.getEpisode() + "화를 만들 크레딧이 모였어요";
        String text = row.getEpisode() + "화를 만들 크레딧이 다 모였어요.\n\n이어 만들기: " + link + "\n";
        String html = "<p>" + row.getEpisode() + "화를 만들 크레딧이 다 모였어요.</p>"
                + "<p><a href=\"" + link + "\">" + row.getEpisode() + "화 이어 만들기</a></p>";
        try {
            mail.sendHtml(to, subject, text, html);
            return 1;
        } catch (RuntimeException e) {
            log.warn("출석 알림 메일을 보내지 못했습니다 (user={})", row.getUserId(), e);
            return 0;
        }
    }
}
