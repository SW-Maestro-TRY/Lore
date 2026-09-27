package com.lore.webtoon.feedback;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.JobStatus;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.runs.RunService;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WebtoonWorkRepository;
import com.lore.webtoon.work.WorkLedger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 검증 설문(#471). 무엇을 왜 묻는지는 {@code webtoon/docs/validation.md} 「설문」.
 *
 * <h2>세 곳</h2>
 * <ul>
 *   <li><b>완성 직후(짧은 설문)</b> — 작품 주인에게 한 작품당 한 번. 핵심 질문 하나는 고정하고
 *       나머지에서 1~2개를 무작위로 더한다. 완성하는 사람이 몇 명 안 되는데 여러 개에서
 *       무작위로만 뽑으면 핵심 질문을 받는 사람이 한두 명뿐이라 판정을 못 한다.</li>
 *   <li><b>마이페이지(전체 설문)</b> — 로그인한 사람. S1~S10 전부와 자유 의견. 끝까지 답하면
 *       웹툰 한 편 값의 크레딧을 <b>계정당 한 번</b> 준다. 답의 내용과는 상관없다.</li>
 *   <li><b>다시 온 사람 안내</b> — 한 편 이상 완성하고, 그 뒤 다른 날 다시 온 사람. 화면이
 *       {@link #status} 를 보고 한 번 띄운다.</li>
 * </ul>
 */
@Service
public class WebtoonFeedbackService {

    /** 보상에 붙는 이름. 같은 이름으로는 한 계정에 한 번만 들어간다({@code grantOnce}). */
    static final String REWARD_REF = "webtoon-feedback-survey";

    static final int MAX_COMMENT = 2000;
    static final int MAX_CONTACT = 200;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebtoonFeedbackRepository feedback;
    private final RunService runs;
    private final WorkLedger ledger;
    private final WebtoonWorkRepository works;
    private final WebtoonJobRepository jobs;
    private final CreditService credits;
    private final CreditGate gate;
    private final Clock clock;
    private final Random random;

    @Autowired
    public WebtoonFeedbackService(WebtoonFeedbackRepository feedback, RunService runs, WorkLedger ledger,
                           WebtoonWorkRepository works, WebtoonJobRepository jobs,
                           CreditService credits, CreditGate gate) {
        this(feedback, runs, ledger, works, jobs, credits, gate, Clock.systemUTC(), new Random());
    }

    WebtoonFeedbackService(WebtoonFeedbackRepository feedback, RunService runs, WorkLedger ledger,
                    WebtoonWorkRepository works, WebtoonJobRepository jobs,
                    CreditService credits, CreditGate gate, Clock clock, Random random) {
        this.feedback = feedback;
        this.runs = runs;
        this.ledger = ledger;
        this.works = works;
        this.jobs = jobs;
        this.credits = credits;
        this.gate = gate;
        this.clock = clock;
        this.random = random;
    }

    /* ---- 완성 직후 ---------------------------------------------------------- */

    /** 짧은 설문에 무엇을 물을지. 이미 답했거나 주인이 아니면 빈 목록. */
    @Transactional(readOnly = true)
    public ShortQuestions shortQuestions(String runId, Long userId, String uid) {
        Profile p = profileOf(runId, userId, uid);
        if (p == null || feedback.answeredShort(runId, userId, blankToNull(uid))) {
            return new ShortQuestions(List.of(), false);
        }
        WebtoonFeedbackQuestion fixed = p.own ? WebtoonFeedbackQuestion.S1 : WebtoonFeedbackQuestion.S6;
        List<WebtoonFeedbackQuestion> pool = new ArrayList<>(p.applicable);
        pool.remove(fixed);
        Collections.shuffle(pool, random);
        int extra = Math.min(pool.size(), 1 + random.nextInt(2));
        List<String> asked = new ArrayList<>();
        asked.add(fixed.name());
        pool.subList(0, extra).forEach(q -> asked.add(q.name()));
        return new ShortQuestions(asked, p.own);
    }

    /** 짧은 설문 답. 같은 작품에 두 번 내면 두 번째는 저장하지 않는다. */
    @Transactional
    public boolean saveShort(String runId, Long userId, String uid, Map<String, Object> raw) {
        Profile p = profileOf(runId, userId, uid);
        if (p == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품에만 답할 수 있어요");
        }
        if (feedback.answeredShort(runId, userId, blankToNull(uid))) {
            return false;
        }
        Map<String, Object> answers = clean(raw, p.applicable);
        if (answers.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "답이 없어요");
        }
        feedback.save(WebtoonFeedback.of(WebtoonFeedback.Kind.SHORT, runId, userId, blankToNull(uid),
                toJson(answers), null, false, null, Instant.now(clock)));
        return true;
    }

    /* ---- 마이페이지 --------------------------------------------------------- */

    /**
     * 전체 설문을 냈나, 보상은 얼마인가, 다시 온 사람 안내를 띄울 차례인가, 무엇을 물을지.
     * 물을 질문은 가장 최근에 완성한 작품에 넣은 것으로 정한다 — 설명을 안 적은 사람에게
     * 「성격대로 행동했나」를 묻지 않으려고. 완성한 작품이 없으면 빈 목록이다.
     */
    @Transactional(readOnly = true)
    public Status status(Long userId) {
        boolean done = feedback.existsByKindAndUserId(WebtoonFeedback.Kind.FULL, userId);
        boolean prompt = false;
        if (!done) {
            Optional<Instant> firstDone = firstFinished(userId);
            LocalDate today = LocalDate.now(clock.withZone(KST));
            prompt = firstDone.map(at -> today.isAfter(at.atZone(KST).toLocalDate())).orElse(false);
        }
        return new Status(done, gate.cost(), prompt, fullQuestions(latestDone(userId)).stream().map(Enum::name).toList());
    }

    /**
     * 전체 설문. 모든 문항(S1~S10)에 답해야 받는다(넣은 것이 없어 답할 수 없는 문항은 「해당 없음」).
     * 처음 낸 사람에게만 크레딧을 준다 — 다시 내는 것은 받지만 보상은 없다.
     */
    @Transactional
    public Full saveFull(Long userId, String uid, Map<String, Object> raw, String comment,
                         boolean wantsInterview, String contact) {
        String runId = latestDone(userId);
        Set<WebtoonFeedbackQuestion> asked = fullQuestions(runId);
        if (asked.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "웹툰을 한 편 완성한 뒤에 답할 수 있어요");
        }
        Map<String, Object> answers = clean(raw, asked);
        if (asked.stream().anyMatch(q -> !answers.containsKey(q.name()))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "모든 문항에 답해 주세요");
        }
        String note = trimTo(comment, MAX_COMMENT);
        String reach = wantsInterview ? trimTo(contact, MAX_CONTACT) : null;
        WebtoonFeedback row = feedback.save(WebtoonFeedback.of(WebtoonFeedback.Kind.FULL, runId, userId,
                blankToNull(uid), toJson(answers), note, wantsInterview, reach, Instant.now(clock)));
        int given = credits.grantOnce(userId, gate.cost(), CreditReason.REWARD, CreditDomain.WEBTOON, REWARD_REF);
        row.rewarded(given);
        return new Full(given, credits.balance(userId));
    }

    /* ---- 관리자 ------------------------------------------------------------- */

    @Transactional(readOnly = true)
    public List<Map<String, Object>> latest(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebtoonFeedback f : feedback.latest(PageRequest.of(0, Math.max(1, Math.min(limit, 500))))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("kind", f.getKind().name());
            m.put("run_id", f.getRunId());
            m.put("user_id", f.getUserId());
            m.put("answers", fromJson(f.getAnswers()));
            m.put("comment", f.getComment());
            m.put("wants_interview", f.isWantsInterview());
            m.put("contact", f.getContact());
            m.put("rewarded", f.getRewarded());
            m.put("created_at", f.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /* ---- 안쪽 --------------------------------------------------------------- */

    /**
     * 이 사람이 이 작품의 주인이면, 그 작품을 만들 때 무엇을 넣었는지. 주인이 아니면 null.
     * 계정이 같거나(이어진 브라우저 포함), 로그인 없이 만든 브라우저가 같으면 주인이다.
     */
    private Profile profileOf(String runId, Long userId, String uid) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        RunService.Inputs in = runs.inputsOf(runId);
        if (in == null) {
            return null;
        }
        boolean owner = (userId != null && (userId.equals(in.userId()) || ledger.mayChange(runId, userId)))
                || (uid != null && !uid.isBlank() && uid.equals(in.browserUid()));
        return owner ? profileFrom(in) : null;
    }

    /** 만들 때 넣은 것으로, 이 작품에 물을 수 있는 질문을 고른다. */
    private static Profile profileFrom(RunService.Inputs in) {
        Map<String, Object> v = in.values();
        boolean hasPhoto = Boolean.TRUE.equals(v.get("has_photo"));
        boolean hasName = !text(v.get("name")).isBlank();
        boolean hasDesc = !text(v.get("character")).isBlank();
        boolean hasStory = !text(v.get("story")).isBlank();
        boolean own = hasPhoto || hasName || hasDesc;

        Set<WebtoonFeedbackQuestion> applicable = EnumSet.of(WebtoonFeedbackQuestion.S2, WebtoonFeedbackQuestion.S6,
                WebtoonFeedbackQuestion.S7, WebtoonFeedbackQuestion.S8);
        if (own) {
            applicable.add(WebtoonFeedbackQuestion.S1);
            applicable.add(WebtoonFeedbackQuestion.S3);
        }
        if (hasDesc) {
            applicable.add(WebtoonFeedbackQuestion.S4);
        }
        if (hasStory) {
            applicable.add(WebtoonFeedbackQuestion.S5);
        }
        return new Profile(own, applicable);
    }

    private record Profile(boolean own, Set<WebtoonFeedbackQuestion> applicable) {
    }

    /** 받을 수 있는 질문·값만 남긴다. 순서는 S1 → S10. 「아니오」 뒤에 더 묻는 것은 끝에 붙는다. */
    private static Map<String, Object> clean(Map<String, Object> raw, Set<WebtoonFeedbackQuestion> allowed) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (WebtoonFeedbackQuestion q : WebtoonFeedbackQuestion.values()) {
            if (!allowed.contains(q)) {
                continue;
            }
            Object v = q.accept(raw.get(q.name()));
            if (v != null) {
                out.put(q.name(), v);
            }
        }
        // 「아니오」 뒤에 더 묻는 것 — 본 답이 아니오일 때만 받는다. 세는 값(out.size)에는 안 넣는다.
        Map<String, Object> extra = new LinkedHashMap<>();
        if ("no".equals(out.get("S3"))) {
            putNote(extra, "S3_note", raw.get("S3_note"));
        }
        if ("no".equals(out.get("S7"))) {
            Object why = raw.get("S7_why");
            if (why instanceof String w && WebtoonFeedbackQuestion.S7_WHY.contains(w)) {
                extra.put("S7_why", w);
            }
            putNote(extra, "S7_note", raw.get("S7_note"));
        }
        out.putAll(extra);
        return out;
    }

    private static void putNote(Map<String, Object> out, String key, Object raw) {
        String note = raw instanceof String s ? trimTo(s, WebtoonFeedbackQuestion.MAX_NOTE) : null;
        if (note != null) {
            out.put(key, note);
        }
    }

    /** 이 계정의 작품 중 처음 완성된 시각. */
    private Optional<Instant> firstFinished(Long userId) {
        return works.ownedBy(userId).stream()
                .map(WebtoonWork::getJobId)
                .filter(Objects::nonNull)
                .map(jobs::findByPublicId)
                .flatMap(Optional::stream)
                .filter(j -> j.getStatus() == JobStatus.DONE && j.getFinishedAt() != null)
                .map(WebtoonJob::getFinishedAt)
                .min(Instant::compareTo);
    }

    /** 가장 최근에 완성한 내 작품. 전체 설문은 이 작품과 잇는다. 없으면 null. */
    private String latestDone(Long userId) {
        for (WebtoonWork w : works.ownedBy(userId)) {          // 새 것부터
            boolean done = w.getJobId() != null && jobs.findByPublicId(w.getJobId())
                    .map(j -> j.getStatus() == JobStatus.DONE).orElse(false);
            if (done) {
                return w.getRunId();
            }
        }
        return null;
    }

    /** 전체 설문에 물을 질문 — 그 작품에 맞는 S1~S8 과 원하는 기능(S10). 작품이 없으면 빈 것. */
    private Set<WebtoonFeedbackQuestion> fullQuestions(String runId) {
        if (runId == null) {
            return EnumSet.noneOf(WebtoonFeedbackQuestion.class);
        }
        RunService.Inputs in = runs.inputsOf(runId);
        Set<WebtoonFeedbackQuestion> asked = in == null
                ? EnumSet.of(WebtoonFeedbackQuestion.S2, WebtoonFeedbackQuestion.S6, WebtoonFeedbackQuestion.S7,
                             WebtoonFeedbackQuestion.S8)
                : EnumSet.copyOf(profileFrom(in).applicable());
        asked.add(WebtoonFeedbackQuestion.S10);
        return asked;
    }

    private static String toJson(Map<String, Object> m) {
        try {
            return JSON.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> fromJson(String s) {
        try {
            return JSON.readValue(s, new TypeReference<>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trimTo(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    public record ShortQuestions(List<String> questions, boolean own) {
    }

    public record Status(boolean done, int reward, boolean prompt, List<String> questions) {
    }

    public record Full(int rewarded, int balance) {
    }

    /** 컨트롤러가 쓰는 지금 계정. */
    static Long currentUser() {
        return CreditGate.currentUser();
    }
}
