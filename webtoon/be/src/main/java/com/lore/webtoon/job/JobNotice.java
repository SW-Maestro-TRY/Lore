package com.lore.webtoon.job;

import com.lore.common.email.EmailService;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
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
    private final String site;

    public JobNotice(JobStore store, UserRepository users, StoryStore stories,
                     EmailService mail, NotifySettingService settings,
                     @Value("${lore.webtoon.site-url:https://lorecomic.com}") String site) {
        this.store = store;
        this.users = users;
        this.stories = stories;
        this.mail = mail;
        this.settings = settings;
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
            String to = addressOf(job);
            if (to == null || !store.claimNotice(jobId)) {
                return;                     // 받을 사람이 없거나, 이미 보냈다
            }
            String title = titleOf(job.getRunId());
            mail.send(to,
                    "[LORE] 「" + title + "」 웹툰이 다 만들어졌어요",
                    """
                    안녕하세요, 루예요.

                    부탁하신 웹툰 「%s」 이(가) 다 만들어졌어요.
                    아래 주소에서 바로 볼 수 있어요.

                    %s

                    이 링크는 기기가 달라도 열려요 — 다른 기기에서 만드셨어도
                    여기로 들어오시면 그 작품이 그대로 있어요.

                    — LORE
                    """.formatted(title, resultLink(job.getRunId())));
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
            String to = addressOf(job);
            if (to == null || !store.claimNotice(jobId)) {
                return;
            }
            String refundLine = back == Refunded.CREDIT
                    ? "사용된 크레딧은 자동으로 환불했어요."
                    : back == Refunded.FREE
                    ? "사용한 무료 생성 횟수는 자동으로 복구했어요."
                    : "";
            mail.send(to,
                    "[LORE] 웹툰을 다 만들지 못했어요",
                    """
                    안녕하세요, 루예요.

                    부탁하신 웹툰을 만들다가 멈췄어요.
                    %s

                    %s
                    다시 시도해 주시면 처음부터 새로 그려 드려요.

                    %s/webtoon

                    — LORE
                    """.formatted(
                            why == null || why.isBlank() ? "" : "사유: " + why,
                            refundLine, site));
            log.info("실패 알림을 보냈습니다 (job={})", jobId);
        } catch (Exception e) {             // noqa: 실패를 적는 길에서 또 죽으면 안 된다
            log.error("실패 알림을 못 보냈습니다 (job={})", jobId, e);
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

    /** 결과 화면 주소. <b>게스트가 자기 작품으로 돌아오는 유일한 길이다.</b> */
    private String resultLink(String runId) {
        return runId == null ? site + "/webtoon" : site + "/webtoon?run=" + runId;
    }
}
