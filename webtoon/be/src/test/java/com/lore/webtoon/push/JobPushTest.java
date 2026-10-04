package com.lore.webtoon.push;

import com.lore.webtoon.job.JobStatus;
import com.lore.webtoon.job.Refunded;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 웹푸시 — <b>누구에게, 언제 보내는가.</b>
 *
 * <ul>
 *   <li>사람이 답할 차례(AWAITING_*)·완성·실패에만 보낸다.</li>
 *   <li>진행 화면을 보고 있으면 안 보낸다.</li>
 *   <li>로그인한 사람의 작업은 그 계정의 기기에만, 게스트 작업은 그 브라우저에만 간다.</li>
 *   <li>브라우저가 버린 구독(404·410)은 지운다.</li>
 * </ul>
 */
class JobPushTest {

    private PushSubscriptionRepository subs;
    private PushSender sender;
    private JobPush push;

    @BeforeEach
    void setUp() {
        subs = mock(PushSubscriptionRepository.class);
        sender = mock(PushSender.class);
        StoryStore stories = mock(StoryStore.class);
        when(stories.chosenOf(anyString())).thenReturn(Optional.empty());
        when(sender.enabled()).thenReturn(true);
        when(sender.send(any(), anyString(), anyString())).thenReturn(201);
        push = new JobPush(subs, sender, stories, Runnable::run);
    }

    private static WebtoonJob job(Long id, Long userId, String uid, JobStatus status) {
        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getId()).thenReturn(id);
        when(job.getPublicId()).thenReturn("abc" + id);
        when(job.getRunId()).thenReturn("run" + id);
        when(job.getUserId()).thenReturn(userId);
        when(job.getBrowserUid()).thenReturn(uid);
        when(job.getStatus()).thenReturn(status);
        return job;
    }

    private static PushSubscription sub(String endpoint, String lang) {
        PushSubscription s = new PushSubscription(endpoint, Instant.now());
        s.update("k", "a", null, "u1", lang, Instant.now());
        return s;
    }

    @Test
    @DisplayName("이야기 고를 차례가 되면 그 브라우저로 「이야기를 확인해 주세요」가 간다")
    void awaitingPickSends() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/x", "ko")));

        push.awaiting(job(1L, null, "u1", JobStatus.AWAITING_PICK));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), body.capture(), eq("abc1"));
        assertThat(body.getValue())
                .contains("이야기를 확인해 주세요")
                .contains("/webtoon?view=running&job=abc1");
    }

    @Test
    @DisplayName("시트가 안전 기준에 걸려 멈추면 「확인해 주세요」가 아니라 「다시 그려 주세요」가 간다(#626)")
    void blockedSheetSendsFix() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/x", "ko")));
        WebtoonJob job = job(1L, null, "u1", JobStatus.AWAITING_SHEET);
        when(job.isSheetBlocked()).thenReturn(true);

        push.awaiting(job);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), body.capture(), eq("abc1"));
        assertThat(body.getValue())
                .contains("캐릭터를 다시 그려 주세요")
                .contains("이야기는 그대로")
                .contains("/webtoon?view=running&job=abc1");
    }

    @Test
    @DisplayName("기다리는 상태가 아니면(도는 중) 안 보낸다")
    void runningDoesNotSend() {
        push.awaiting(job(1L, null, "u1", JobStatus.RUNNING));
        verify(sender, never()).send(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("진행 화면을 보고 있으면 안 보낸다")
    void watchingSkips() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/x", "ko")));
        push.seen(1L);

        push.awaiting(job(1L, null, "u1", JobStatus.AWAITING_SCENES));
        push.finished(job(1L, null, "u1", JobStatus.DONE));

        verify(sender, never()).send(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("로그인한 사람의 작업은 계정 기기에만 — 같은 브라우저 uid 로는 안 찾는다")
    void loggedInGoesToAccountOnly() {
        when(subs.findByUserId(7L)).thenReturn(List.of());

        push.finished(job(1L, 7L, "u1", JobStatus.DONE));

        verify(subs).findByUserId(7L);
        verify(subs, never()).findByBrowserUid(anyString());
        verify(sender, never()).send(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("완성 알림은 완성본으로 열리고 구독 언어로 쓴다")
    void doneOpensResultInLang() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/x", "en")));

        push.finished(job(1L, null, "u1", JobStatus.DONE));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), body.capture(), anyString());
        assertThat(body.getValue()).contains("Your webtoon is ready").contains("/webtoon?run=run1");
    }

    @Test
    @DisplayName("실패 알림은 무엇을 돌려줬는지 적는다")
    void failedSaysRefund() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/x", "ko")));

        push.failed(job(1L, null, "u1", JobStatus.ERROR), Refunded.CREDIT);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(sender).send(any(), body.capture(), anyString());
        assertThat(body.getValue()).contains("웹툰을 다 만들지 못했어요").contains("크레딧은 자동으로 환불");
    }

    @Test
    @DisplayName("브라우저가 버린 구독(410)은 지운다")
    void goneIsDeleted() {
        when(subs.findByBrowserUid("u1")).thenReturn(List.of(sub("https://fcm.googleapis.com/gone", "ko")));
        when(sender.send(any(), anyString(), anyString())).thenReturn(410);

        push.finished(job(1L, null, "u1", JobStatus.DONE));

        verify(subs).deleteByEndpoint("https://fcm.googleapis.com/gone");
    }

    @Test
    @DisplayName("키가 없으면(꺼짐) 아무도 안 찾는다")
    void disabledDoesNothing() {
        when(sender.enabled()).thenReturn(false);

        push.finished(job(1L, null, "u1", JobStatus.DONE));

        verify(subs, never()).findByBrowserUid(anyString());
    }

    @Test
    @DisplayName("푸시 서버가 아닌 주소는 구독으로 안 받는다")
    void rejectsUnknownEndpoint() {
        assertThat(PushSubscriptions.checkEndpoint("https://fcm.googleapis.com/fcm/send/x"))
                .isEqualTo("https://fcm.googleapis.com/fcm/send/x");
        assertThat(PushSubscriptions.checkEndpoint("https://web.push.apple.com/abc")).isNotNull();
        for (String bad : List.of("http://fcm.googleapis.com/x", "https://169.254.169.254/latest",
                "https://evil.com/fcm.googleapis.com", "https://fcm.googleapis.com.evil.com/x")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PushSubscriptions.checkEndpoint(bad))
                    .as(bad).isInstanceOf(RuntimeException.class);
        }
    }
}
