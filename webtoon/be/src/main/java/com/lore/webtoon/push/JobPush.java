package com.lore.webtoon.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.job.Refunded;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 작업이 <b>사람을 기다리기 시작하거나 끝났을 때</b> 웹푸시로 알린다.
 *
 * <h2>왜 메일과 따로인가</h2>
 *
 * 메일({@code JobNotice})은 완성·실패 때만 간다. 「2번 확인하며」는 중간에 사람이
 * 골라야 다음으로 넘어가는데(상대 인물 · 이야기 · 장면 · 시트), 화면을 닫은 사람은
 * 작업이 자기를 기다리는 줄 모른다. 푸시는 그 순간마다 간다.
 *
 * 메일이 쓰는 「보냈나」 표시({@code notified_at})는 안 쓴다 — 같이 쓰면 메일이
 * 먼저 나간 작업에는 푸시가 안 간다.
 *
 * <h2>화면을 보고 있으면 안 보낸다</h2>
 *
 * 진행 화면은 3초마다 상태를 묻는데, 화면이 앞에 떠 있을 때만 {@code watching} 을
 * 붙인다({@link #seen}). 최근 {@link #WATCH_WINDOW} 안에 그런 물음이 있었으면 사람이
 * 이미 보고 있는 것이라 건너뛴다. 브라우저 쪽에서 알림을 숨길 수는 없다 — 크롬·사파리는
 * 푸시를 받고 알림을 안 띄우면 구독을 끊을 수 있다.
 *
 * 이 기억은 서버 메모리에 있다. 서버가 여러 대가 되면 다른 서버가 받은 물음은 못
 * 보고 보낸다(알림이 하나 더 갈 뿐 빠지지는 않는다).
 *
 * <h2>보내기가 실패해도 만들기는 안 깨진다</h2>
 *
 * 모든 바깥 문은 예외를 삼키고, 실제 전송은 따로 도는 일꾼이 한다 — 푸시 서버가
 * 느려도 그림 그리는 줄이 기다리지 않는다.
 */
@Service
public class JobPush {

    private static final Logger log = LoggerFactory.getLogger(JobPush.class);

    static final Duration WATCH_WINDOW = Duration.ofSeconds(10);

    private final PushSubscriptionRepository subs;
    private final PushSender sender;
    private final StoryStore stories;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Long, Instant> watched = new ConcurrentHashMap<>();
    private final Executor out;

    /** ★ 생성자가 둘이라 {@code @Autowired} 가 없으면 스프링이 기본 생성자를 찾다 기동이 실패한다. */
    @Autowired
    public JobPush(PushSubscriptionRepository subs, PushSender sender, StoryStore stories) {
        this(subs, sender, stories, Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "webtoon-push");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 테스트가 보내기를 그 자리에서 돌리게 일꾼을 갈아 끼운다. */
    JobPush(PushSubscriptionRepository subs, PushSender sender, StoryStore stories, Executor out) {
        this.subs = subs;
        this.sender = sender;
        this.stories = stories;
        this.out = out;
    }

    /** 진행 화면이 앞에 떠서 이 작업을 보고 있다. */
    public void seen(Long jobId) {
        if (jobId != null) {
            watched.put(jobId, Instant.now());
        }
    }

    /** 사람이 답할 차례가 됐다. {@code AWAITING_*} 가 아니면 아무것도 안 한다. */
    public void awaiting(WebtoonJob job) {
        if (job == null) {
            return;
        }
        Kind kind = switch (job.getStatus()) {
            case AWAITING_CAST -> Kind.CAST;
            case AWAITING_PICK -> Kind.PICK;
            case AWAITING_SCENES -> Kind.SCENES;
            case AWAITING_SHEET -> job.isSheetBlocked() ? Kind.FIX : Kind.SHEET;   // 걸려서 멈춘 시트(#626)
            default -> null;
        };
        if (kind != null) {
            push(job, kind, null);
        }
    }

    public void finished(WebtoonJob job) {
        push(job, Kind.DONE, null);
    }

    public void failed(WebtoonJob job, Refunded back) {
        push(job, Kind.FAILED, back);
    }

    private void push(WebtoonJob job, Kind kind, Refunded back) {
        try {
            if (job == null || !sender.enabled()) {
                return;
            }
            if (watching(job.getId())) {
                return;                             // 이미 보고 있다
            }
            if (kind == Kind.DONE || kind == Kind.FAILED) {
                watched.remove(job.getId());
            }
            List<PushSubscription> to = recipientsOf(job);
            if (to.isEmpty()) {
                return;
            }
            String title = titleOf(job.getRunId());
            out.execute(() -> {
                for (PushSubscription sub : to) {
                    deliver(sub, PushMessages.of(kind, sub.getLang(), title, back), job, kind);
                }
            });
        } catch (Exception e) {                     // noqa: 알림이 만들기를 깨면 안 된다
            log.warn("푸시를 준비하지 못했습니다 (job={})", job == null ? null : job.getId(), e);
        }
    }

    boolean watching(Long jobId) {
        Instant at = watched.get(jobId);
        return at != null && at.isAfter(Instant.now().minus(WATCH_WINDOW));
    }

    /**
     * 누구에게 보내나.
     *
     * 로그인한 사람의 작업은 <b>그 계정으로 구독한 기기에만</b> 간다. 같은 브라우저라도
     * 다른 사람이 로그인해서 구독했으면 안 간다 — 공용 PC 에서 남의 작품 알림이 뜨면 안 된다.
     * 게스트의 작업은 그 브라우저(uid)로 구독한 기기에 간다.
     */
    List<PushSubscription> recipientsOf(WebtoonJob job) {
        if (job.getUserId() != null) {
            return subs.findByUserId(job.getUserId());
        }
        String uid = job.getBrowserUid();
        return uid == null || uid.isBlank() ? List.of() : subs.findByBrowserUid(uid);
    }

    private void deliver(PushSubscription sub, PushMessages.Message msg, WebtoonJob job, Kind kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", msg.title());
        body.put("body", msg.body());
        body.put("url", urlOf(job, kind));
        body.put("tag", "job-" + job.getPublicId());
        int code;
        try {
            code = sender.send(sub, json.writeValueAsString(body), job.getPublicId());
        } catch (Exception e) {
            log.warn("푸시 본문을 만들지 못했습니다 (job={})", job.getId(), e);
            return;
        }
        if (code == 404 || code == 410) {
            // 브라우저가 구독을 버렸다(앱 삭제 · 권한 끔 · 만료). 더 보내 봐야 소용없다.
            subs.deleteByEndpoint(sub.getEndpoint());
            log.info("끊긴 푸시 구독을 지웠습니다 (sub={})", sub.getId());
        } else if (code >= 200 && code < 300) {
            subs.touch(sub.getId(), Instant.now());
        }
    }

    /** 누르면 열 곳. 완성은 완성본, 나머지는 진행 화면(고를 것이나 실패 사유가 거기 있다). */
    static String urlOf(WebtoonJob job, Kind kind) {
        if (kind == Kind.DONE && job.getRunId() != null) {
            return "/webtoon?run=" + job.getRunId();
        }
        return "/webtoon?view=running&job=" + job.getPublicId();
    }

    /** 사람이 볼 작품 이름. 아직 못 정했으면 null — 문구가 알아서 무난한 말로 바꾼다. */
    private String titleOf(String runId) {
        if (runId == null) {
            return null;
        }
        try {
            return stories.chosenOf(runId)
                    .map(WebtoonStory::displayTitle)
                    .filter(s -> !s.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    enum Kind { CAST, PICK, SCENES, SHEET, FIX, DONE, FAILED }
}
