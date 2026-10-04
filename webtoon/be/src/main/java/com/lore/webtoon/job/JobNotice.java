package com.lore.webtoon.job;

import com.lore.common.email.EmailService;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 다 만들어지면 <b>메일로 알린다.</b>
 *
 * <h2>왜</h2>
 *
 * 한 편에 5~15분이 걸린다. 그동안 화면이 사람을 붙들고 있었고, 우리가 해 준
 * 말은 「나갔다 와도 이어집니다」뿐이었다 — <b>언제 돌아와야 하는지는 안
 * 알려 줬다.</b> 그래서 사람은 진행 막대를 보며 앉아 있거나, 나갔다가
 * 영영 안 돌아온다.
 *
 * 게스트는 더 나쁘다. 자기 작품을 브라우저 uid 로만 찾으므로 <b>다른 기기로
 * 들어오면 만든 것을 못 찾는다.</b> 메일에 담는 결과 링크가 그 사람이 자기
 * 작품으로 돌아오는 유일한 길이다.
 *
 * <h2>주소는 두 곳에서 온다</h2>
 *
 * <ul>
 *   <li><b>로그인한 사람</b> — 계정 이메일. 따로 안 묻는다. 자기가 시킨 일의
 *       결과를 받는 것이라 마케팅 수신과 성격이 다르다.</li>
 *   <li><b>게스트</b> — 직접 적어 넣은 주소({@code webtoon_job.notify_email}).
 *       <b>안 적으면 안 보낸다</b> — 선택이지 조건이 아니다.</li>
 * </ul>
 *
 * 계정 이메일을 작업에 베껴 두지 않는 이유: 베껴 두면 사람이 계정 이메일을
 * 바꾼 뒤에도 옛 주소로 나간다. 보낼 때 읽는다.
 *
 * <h2>보내기가 실패해도 만들기는 안 깨진다</h2>
 *
 * 이 클래스의 모든 바깥 문은 예외를 삼킨다. <b>메일이 안 가는 것보다 다 만든
 * 작품이 실패로 적히는 것이 훨씬 나쁘다</b> — SMTP 한 번 흔들렸다고 사람의
 * 크레딧이 환불되고 그림이 버려지면 안 된다. 못 보냈으면 로그에만 남는다.
 */
@Service
public class JobNotice {

    private static final Logger log = LoggerFactory.getLogger(JobNotice.class);

    /**
     * 주소처럼 보이는가.
     *
     * <b>엄밀히 검사하지 않는다.</b> 이메일 규격을 정규식으로 완전히 맞추는
     * 것은 사실상 불가능하고, 여기서 하려는 일은 "@ 도 없는 것을 담아 두고
     * 보낸 척하지 않기" 뿐이다. 진짜로 맞는지는 메일이 도착하는지로만 안다.
     */
    private static final Pattern LOOKS_LIKE = Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private final JobStore store;
    private final UserRepository users;
    private final StoryStore stories;
    private final EmailService mail;
    private final NotifySettingService settings;
    private final JobPush push;
    private final String site;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobNotice(JobStore store, UserRepository users, StoryStore stories,
                     EmailService mail, NotifySettingService settings, JobPush push,
                     @Value("${lore.webtoon.site-url:https://lorecomic.com}") String site) {
        this.store = store;
        this.users = users;
        this.stories = stories;
        this.mail = mail;
        this.settings = settings;
        this.push = push;
        this.site = site.endsWith("/") ? site.substring(0, site.length() - 1) : site;
    }

    /** 주소처럼 보이는 것만 받는다. 아니면 {@code null} — 담아 두고 보낸 척하지 않는다. */
    public static String clean(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty() || trimmed.length() > 255 || !LOOKS_LIKE.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /**
     * 이 작업의 결과를 어디로 보낼까. 보낼 데가 없으면 {@code null}.
     *
     * 게스트가 적은 주소가 <b>계정 이메일을 이긴다</b> — 로그인한 사람이
     * 굳이 다른 주소를 적었다면 그쪽으로 받고 싶다는 뜻이다. 그래서
     * {@link NotifySettingService}는 <b>계정 이메일로 떨어지는 자리에서만</b>
     * 본다 — 이번만 다른 주소로 받겠다는 명시적 선택까지 막으면 안 된다.
     */
    public String addressOf(WebtoonJob job) {
        if (job == null) {
            return null;
        }
        String typed = clean(job.getNotifyEmail());
        if (typed != null) {
            return typed;
        }
        if (job.getUserId() == null || !settings.isOn(job.getUserId())) {
            return null;
        }
        return users.findById(job.getUserId()).map(User::getEmail).map(JobNotice::clean).orElse(null);
    }

    /**
     * 다 만들어졌다고 알린다.
     *
     * <b>실패해도 조용하다.</b> 여기서 예외가 새면 방금 다 만든 작품이
     * 실패로 적힌다.
     */
    public void finished(Long jobId) {
        try {
            WebtoonJob job = store.byId(jobId);
            push.finished(job);             // 푸시는 메일과 따로 간다 — 메일 받을 데가 없어도 보낸다(#599)
            String to = addressOf(job);
            if (to == null || !store.claimNotice(jobId)) {
                return;                     // 받을 사람이 없거나, 이미 보냈다
            }
            String title = titleOf(job.getRunId());
            NoticeMail.Body body = NoticeMail.finished(title, genreOf(job.getRunId()), nameOf(job),
                    resultLink(job.getRunId()), coverOf(job.getRunId()), site);
            mail.sendHtml(to, "[LORE] 「" + title + "」 웹툰이 다 만들어졌어요", body.text(), body.html());
            log.info("완성 알림을 보냈습니다 (job={}, run={})", jobId, job.getRunId());
        } catch (Exception e) {             // noqa: 메일이 만들기를 깨면 안 된다
            log.error("완성 알림을 못 보냈습니다 (job={})", jobId, e);
        }
    }

    /**
     * 못 만들었다고 알린다.
     *
     * <b>주소를 적어 준 사람에게 아무 말도 안 하는 것이 제일 나쁘다.</b>
     * 기다리라고 해 놓고 영영 안 오면, 그 사람은 우리가 돈만 받고 사라진
     * 줄 안다. 무엇을 돌려줬는지까지 적는다.
     *
     * <b>사람이 스스로 그만둔 것은 안 알린다</b> — 자기가 누른 것을 메일로
     * 또 알려 주는 것은 알림이 아니라 잔소리다(부르는 쪽이 거른다).
     */
    public void failed(Long jobId, String why, Refunded back) {
        try {
            WebtoonJob job = store.byId(jobId);
            push.failed(job, back);
            String to = addressOf(job);
            if (to == null || !store.claimNotice(jobId)) {
                return;
            }
            /* 사유(why)는 메일에 안 적는다 — 내부 문구라 받는 사람에게는 뜻이 없다. 로그에는 남는다. */
            /* 안전 기준·글 모델 거절이면 이유를 적는다(#626) — 「잠시 후 다시」는 같은 내용이면 또 걸린다. */
            boolean unfixable = "image_safety".equals(job.getFailCode()) || "text_refusal".equals(job.getFailCode());
            NoticeMail.Body body = NoticeMail.failed(chosenTitleOf(job.getRunId()), back, site + "/webtoon", site,
                    unfixable ? why : null);
            mail.sendHtml(to, "[LORE] 웹툰을 다 만들지 못했어요", body.text(), body.html());
            log.info("실패 알림을 보냈습니다 (job={}, why={})", jobId, why);
        } catch (Exception e) {             // noqa: 실패를 적는 길에서 또 죽으면 안 된다
            log.error("실패 알림을 못 보냈습니다 (job={})", jobId, e);
        }
    }

    /**
     * 사람이 고쳐야 이어 갈 수 있다고 알린다(#626) — 시트가 안전 기준에 걸려 멈췄을 때.
     *
     * {@link #finished}·{@link #failed} 와 달리 <b>한 번만 보내기(claimNotice)를 쓰지 않는다</b> — 그 표시를
     * 여기서 써 버리면 고쳐서 다 만든 뒤의 완성 메일이 안 간다. 다시 그리기는 세 번까지라 많아야 몇 통이다.
     * 푸시는 {@link JobStore#sheetBlocked} 가 이미 보냈다.
     */
    public void needsFix(Long jobId, String why) {
        try {
            WebtoonJob job = store.byId(jobId);
            String to = addressOf(job);
            if (to == null) {
                return;
            }
            String link = site + "/webtoon?view=running&job=" + job.getPublicId();
            NoticeMail.Body body = NoticeMail.needsFix(chosenTitleOf(job.getRunId()), why,
                    "캐릭터 다시 그리러 가기 →", link, site);
            mail.sendHtml(to, "[LORE] 캐릭터를 다시 그려 주세요 — 이야기는 그대로 있어요", body.text(), body.html());
            log.info("고쳐 달라는 알림을 보냈습니다 (job={})", jobId);
        } catch (Exception e) {             // noqa: 알림이 만들기를 깨면 안 된다
            log.error("고쳐 달라는 알림을 못 보냈습니다 (job={})", jobId, e);
        }
    }

    /**
     * 다 만들었지만 장면 몇 장이 안전 기준에 걸려 빈 장으로 남았다고 알린다(#626). 완성 메일 대신 간다 —
     * 「다 만들어졌어요」만 보내면 빈 장을 보고 놀란다. 실패 메일과 같은 머리에 고칠 방법을 적고,
     * 버튼은 편집실로 보낸다. 완성처럼 한 번만 보낸다(claimNotice). 푸시는 완성 푸시를 그대로 보낸다.
     */
    public void partial(Long jobId, String why) {
        try {
            WebtoonJob job = store.byId(jobId);
            push.finished(job);
            String to = addressOf(job);
            if (to == null || !store.claimNotice(jobId)) {
                return;
            }
            String link = site + "/webtoon?view=editor&run=" + job.getRunId();
            NoticeMail.Body body = NoticeMail.needsFix(titleOf(job.getRunId()), why, "편집실에서 다시 그리기 →", link, site);
            mail.sendHtml(to, "[LORE] 「" + titleOf(job.getRunId()) + "」 장면 몇 장을 다시 그려 주세요", body.text(), body.html());
            log.info("일부 장면이 빠진 완성 알림을 보냈습니다 (job={}, run={})", jobId, job.getRunId());
        } catch (Exception e) {             // noqa: 메일이 만들기를 깨면 안 된다
            log.error("일부 장면이 빠진 완성 알림을 못 보냈습니다 (job={})", jobId, e);
        }
    }

    /** 사람이 볼 작품 이름. 아직 못 정했으면 무난한 말로. */
    private String titleOf(String runId) {
        if (runId == null) {
            return "내 웹툰";
        }
        return stories.chosenOf(runId)
                .map(WebtoonStory::displayTitle)
                .filter(s -> !s.isBlank())
                .orElse("내 웹툰");
    }

    /**
     * 관리자 처리를 작가에게 알린다(#638). -> 보냈으면 true
     *
     * <b>알림 설정(완성 메일 끄기)을 안 본다</b> — 이것은 자기가 시킨 일의 결과가 아니라 약관이 정한 조치
     * 통지라서, 끈 사람에게도 간다. 게스트 작품(계정 없음) · 탈퇴 계정 · 주소가 이상한 계정이면 안 보낸다.
     * 실패해도 던지지 않는다 — 처리 자체는 이미 끝났고, 못 보낸 것은 기록(notified=false)에 남는다.
     *
     * @param action HIDE · REMOVE · WARN
     */
    public boolean moderated(Long ownerUserId, String runId, String action, String reason, int keepDays) {
        if (ownerUserId == null) {
            return false;
        }
        try {
            User owner = users.findById(ownerUserId).orElse(null);
            if (owner == null || owner.getStatus() != com.lore.common.user.UserStatus.ACTIVE) {
                return false;
            }
            String to = clean(owner.getEmail());
            if (to == null) {
                return false;
            }
            String title = chosenTitleOf(runId);
            NoticeMail.Body body = NoticeMail.moderated(title, action, reason, keepDays, site + "/webtoon?view=mypage", site);
            String subject = switch (action) {
                case "HIDE" -> "[LORE] 작품이 비공개 처리되었어요";
                case "REMOVE" -> "[LORE] 작품이 삭제 처리되었어요";
                default -> "[LORE] 운영 정책 경고 안내";
            };
            mail.sendHtml(to, subject, body.text(), body.html());
            log.info("관리자 처리 안내를 보냈습니다 (run={}, action={})", runId, action);
            return true;
        } catch (Exception e) {             // noqa: 메일이 처리를 깨면 안 된다
            log.error("관리자 처리 안내를 못 보냈습니다 (run={}, action={})", runId, action, e);
            return false;
        }
    }

    /** 고른 이야기의 제목. 아직 못 정했으면 빈 값 — 실패 메일은 그때 「웹툰」이라고만 쓴다. */
    private String chosenTitleOf(String runId) {
        if (runId == null) {
            return "";
        }
        return stories.chosenOf(runId).map(WebtoonStory::displayTitle).orElse("");
    }

    private String genreOf(String runId) {
        if (runId == null) {
            return "";
        }
        return stories.chosenOf(runId).map(WebtoonStory::getGenre).orElse("");
    }

    /** 만들 때 적은 캐릭터 이름. 못 읽으면 빈 값 — 그 줄만 빠진다. */
    private String nameOf(WebtoonJob job) {
        String json = job.getInputJson();
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            return mapper.readTree(json).path("name").asText("");
        } catch (Exception e) {             // noqa: 이름 하나 때문에 메일이 안 가면 안 된다
            return "";
        }
    }

    /**
     * 표지(1장) 그림 주소. 그림 자리를 직접 적지 않고 서버 주소를 적는다 — 서버가 열 때마다
     * 그때의 그림 자리로 넘겨 주므로(RunController#page), 며칠 뒤에 메일을 열어도 그림이 뜬다.
     */
    private String coverOf(String runId) {
        return site + WebtoonApi.V1 + "/runs/" + runId + "/page/1";
    }

    /** 결과 화면 주소. <b>게스트가 자기 작품으로 돌아오는 유일한 길이다.</b> */
    private String resultLink(String runId) {
        return runId == null ? site + "/webtoon" : site + "/webtoon?run=" + runId;
    }
}
