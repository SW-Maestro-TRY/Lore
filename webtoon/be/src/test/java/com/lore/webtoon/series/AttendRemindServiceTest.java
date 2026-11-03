package com.lore.webtoon.series;

import com.lore.common.credit.CreditAttendanceService;
import com.lore.common.credit.CreditService;
import com.lore.common.email.EmailService;
import com.lore.common.exception.BusinessException;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.job.NotifySettingService;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.push.AttendPush;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「출석으로 모아서 다음 화 만들기」 알림 — <b>언제 무엇을 보내고, 언제 그만두나.</b>
 *
 * <ul>
 *   <li>다 모였으면 푸시·메일 한 번, 그리고 끝</li>
 *   <li>모자라고 오늘 출석 안 했으면 출석 푸시 하루 한 번</li>
 *   <li>오늘 출석했으면 안 보낸다</li>
 *   <li>14일이 지나면 그만</li>
 * </ul>
 */
class AttendRemindServiceTest {

    private static final Long USER = 7L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-05T03:00:00Z");     // 11-05 12:00 KST

    private AttendRemindRepository reminds;
    private CreditService credits;
    private CreditAttendanceService attendance;
    private AttendPush push;
    private EmailService mail;
    private UserRepository users;
    private NotifySettingService settings;
    private WebtoonJobRepository jobs;
    private SeriesChapterRepository chapters;
    private AttendRemindService service;

    @BeforeEach
    void setUp() {
        reminds = mock(AttendRemindRepository.class);
        credits = mock(CreditService.class);
        attendance = mock(CreditAttendanceService.class);
        push = mock(AttendPush.class);
        mail = mock(EmailService.class);
        users = mock(UserRepository.class);
        settings = mock(NotifySettingService.class);
        jobs = mock(WebtoonJobRepository.class);
        chapters = mock(SeriesChapterRepository.class);
        WebtoonJob source = mock(WebtoonJob.class);
        when(source.getPublicId()).thenReturn("src1");
        when(jobs.findFirstByRunIdOrderByIdDesc(anyString())).thenReturn(Optional.of(source));
        when(push.attend(anyLong(), anyInt(), anyInt(), anyInt())).thenReturn(1);
        when(push.ready(anyLong(), anyInt(), anyString())).thenReturn(1);
        service = new AttendRemindService(reminds, credits, attendance, push, mail, users, settings, jobs, chapters,
                "https://lorecomic.com/", true, Clock.fixed(NOW, ZONE));
    }

    private AttendRemind row(Instant createdAt, LocalDate lastSent) {
        AttendRemind r = new AttendRemind(USER);
        r.plan("20261103T101010-abcdef", 2, 20, createdAt);
        if (lastSent != null) {
            r.sentOn(lastSent);
        }
        when(reminds.findByDoneAtIsNull()).thenReturn(List.of(r));
        return r;
    }

    private static CreditAttendanceService.Status card(boolean claimed, int reward) {
        return new CreditAttendanceService.Status(true, CreditAttendanceService.Theme.MASQUERADE, claimed,
                3, reward, 2, null, List.of(), 0);
    }

    @Test
    @DisplayName("다 모였으면 푸시와 메일을 한 번 보내고 끝낸다")
    void readyOnce() {
        AttendRemind r = row(NOW.minus(Duration.ofDays(3)), null);
        when(credits.balance(USER)).thenReturn(22);
        when(settings.isOn(USER)).thenReturn(true);
        User u = mock(User.class);
        when(u.getEmail()).thenReturn("reader@example.com");
        when(users.findById(USER)).thenReturn(Optional.of(u));

        assertThat(service.sweep()).isEqualTo(2);

        verify(push).ready(USER, 2, "20261103T101010-abcdef");
        verify(mail).sendHtml(eq("reader@example.com"), anyString(), anyString(), anyString());
        assertThat(r.isActive()).isFalse();
    }

    @Test
    @DisplayName("메일 알림을 꺼 두었으면 다 모여도 메일은 안 보낸다")
    void readyNoMailWhenOff() {
        row(NOW.minus(Duration.ofDays(1)), null);
        when(credits.balance(USER)).thenReturn(20);
        when(settings.isOn(USER)).thenReturn(false);

        service.sweep();

        verify(push).ready(USER, 2, "20261103T101010-abcdef");
        verify(mail, never()).sendHtml(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("모자라고 오늘 출석 안 했으면 출석 푸시를 보내고 오늘 날짜를 적는다")
    void attendPush() {
        AttendRemind r = row(NOW.minus(Duration.ofDays(1)), null);
        when(credits.balance(USER)).thenReturn(6);
        when(attendance.status(USER)).thenReturn(card(false, 2));

        assertThat(service.sweep()).isEqualTo(1);

        verify(push).attend(USER, 2, 2, 14);
        assertThat(r.getLastSentOn()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(r.isActive()).isTrue();
    }

    @Test
    @DisplayName("같은 날 두 번 보내지 않는다")
    void oncePerDay() {
        row(NOW.minus(Duration.ofDays(1)), LocalDate.of(2026, 11, 5));
        when(credits.balance(USER)).thenReturn(6);

        assertThat(service.sweep()).isZero();
        verify(push, never()).attend(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("오늘 이미 출석했으면 안 보낸다")
    void skipWhenClaimed() {
        row(NOW.minus(Duration.ofDays(1)), null);
        when(credits.balance(USER)).thenReturn(6);
        when(attendance.status(USER)).thenReturn(card(true, 2));

        assertThat(service.sweep()).isZero();
        verify(push, never()).attend(anyLong(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("그 화를 이미 이어 만들었으면 아무것도 안 보내고 끝낸다")
    void alreadyMade() {
        AttendRemind r = row(NOW.minus(Duration.ofDays(1)), null);
        when(chapters.existsBySourceJob("src1")).thenReturn(true);
        when(credits.balance(USER)).thenReturn(30);

        assertThat(service.sweep()).isZero();
        verify(push, never()).ready(anyLong(), anyInt(), anyString());
        assertThat(r.isActive()).isFalse();
    }

    @Test
    @DisplayName("14일이 지나도록 못 모았으면 그만 보낸다")
    void expires() {
        AttendRemind r = row(NOW.minus(Duration.ofDays(15)), null);
        when(credits.balance(USER)).thenReturn(6);

        assertThat(service.sweep()).isZero();
        assertThat(r.isActive()).isFalse();
    }

    @Test
    @DisplayName("로그인 안 했거나 화 정보가 이상하면 받지 않는다")
    void rejectsBadInput() {
        assertThatThrownBy(() -> service.on(null, "run", 2, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.on(USER, "../x", 2, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.on(USER, "run", 1, 20)).isInstanceOf(BusinessException.class);
    }
}
