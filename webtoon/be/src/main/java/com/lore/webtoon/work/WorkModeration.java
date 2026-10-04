package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.JobNotice;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자가 남의 작품을 처리한다(#638) — 비공개 · 다시 공개 · 삭제 · 되살리기 · 경고.
 *
 * <h2>무엇이 어떻게 되나</h2>
 *
 * <ul>
 *   <li><b>비공개</b>: 둘러보기에서 빠지고 그림도 안 열리는 자리로 옮긴다. 작가는 마이페이지에서 계속 보지만
 *       <b>다시 공개로 못 바꾼다</b>(MyWebtoonService.setVisibility).</li>
 *   <li><b>삭제</b>: 관리자 휴지통. 작가의 휴지통에도 보이지만 <b>작가는 못 되살린다</b>(RunTrash.restore).
 *       기간이 지나면 다른 휴지통 작품과 같이 영구 삭제된다.</li>
 *   <li><b>경고</b>: 작품은 그대로 두고 작가에게 알리기만 한다. 기록에 남아 작가별로 센다.</li>
 *   <li>다시 공개 · 되살리기는 <b>처리 전 상태</b>로 돌린다 — 원래 비공개였던 작품이 공개로 풀리면 안 된다.</li>
 * </ul>
 *
 * 비공개 · 삭제 · 경고는 작가에게 메일로 알린다(약관의 조치 통지). 다시 공개 · 되살리기는 안 알린다.
 * 예시 작품은 여기서 처리하지 않는다 — 예시 관리(ExampleAdmin)에서 내린다.
 */
@Service
public class WorkModeration {

    private static final Logger log = LoggerFactory.getLogger(WorkModeration.class);

    /** 작품 상태 값. */
    public static final String HIDDEN = "HIDDEN";
    public static final String REMOVED = "REMOVED";

    /** 기록에 남는 일. */
    public static final String HIDE = "HIDE";
    public static final String UNHIDE = "UNHIDE";
    public static final String REMOVE = "REMOVE";
    public static final String RESTORE = "RESTORE";
    public static final String WARN = "WARN";

    static final int REASON_MAX = 500;

    private final WebtoonWorkRepository works;
    private final ModerationLogRepository logs;
    private final ModerationNotes notes;
    private final PageStore pages;
    private final JobNotice notice;
    private final StoryStore stories;
    private final UserRepository users;
    private final RunTrash trash;

    public WorkModeration(WebtoonWorkRepository works, ModerationLogRepository logs, ModerationNotes notes,
                          PageStore pages, JobNotice notice, StoryStore stories, UserRepository users, RunTrash trash) {
        this.works = works;
        this.logs = logs;
        this.notes = notes;
        this.pages = pages;
        this.notice = notice;
        this.stories = stories;
        this.users = users;
        this.trash = trash;
    }

    /* ---- 처리 ------------------------------------------------------------- */

    @Transactional
    public Map<String, Object> hide(Long adminId, String runId, String reason) {
        WebtoonWork work = mustFind(runId);
        String why = mustReason(reason);
        notExample(work);
        if (work.isTrashed()) {
            throw bad("휴지통에 있는 작품입니다");
        }
        if (work.isHiddenByAdmin()) {
            throw bad("이미 비공개 처리한 작품입니다");
        }
        work.moderate(HIDDEN, why, Instant.now());
        work.setPublic(false);
        works.save(work);
        moveArt(runId, false);
        record(work, adminId, HIDE, why, true);
        return state(runId);
    }

    @Transactional
    public Map<String, Object> unhide(Long adminId, String runId, String reason) {
        WebtoonWork work = mustFind(runId);
        if (!work.isHiddenByAdmin()) {
            throw bad("관리자가 비공개 처리한 작품이 아닙니다");
        }
        boolean back = work.wasPublicBeforeModeration();
        work.clearModeration();
        work.setPublic(back);
        works.save(work);
        if (back) {
            moveArt(runId, true);
        }
        record(work, adminId, UNHIDE, clean(reason), false);
        return state(runId);
    }

    @Transactional
    public Map<String, Object> remove(Long adminId, String runId, String reason) {
        WebtoonWork work = mustFind(runId);
        String why = mustReason(reason);
        notExample(work);
        if (work.isRemovedByAdmin()) {
            throw bad("이미 삭제 처리한 작품입니다");
        }
        work.moderate(REMOVED, why, Instant.now());
        work.setPublic(false);
        if (!work.isTrashed()) {
            work.trash(Instant.now());
        }
        works.save(work);
        moveArt(runId, false);
        record(work, adminId, REMOVE, why, true);
        return state(runId);
    }

    @Transactional
    public Map<String, Object> restore(Long adminId, String runId, String reason) {
        WebtoonWork work = mustFind(runId);
        if (!work.isRemovedByAdmin()) {
            throw bad("관리자가 삭제 처리한 작품이 아닙니다");
        }
        boolean back = work.wasPublicBeforeModeration();
        work.clearModeration();
        work.untrash();
        work.setPublic(back);
        works.save(work);
        if (back) {
            moveArt(runId, true);
        }
        record(work, adminId, RESTORE, clean(reason), false);
        return state(runId);
    }

    @Transactional
    public Map<String, Object> warn(Long adminId, String runId, String reason) {
        WebtoonWork work = mustFind(runId);
        String why = mustReason(reason);
        record(work, adminId, WARN, why, true);
        return state(runId);
    }

    /* ---- 보기 ------------------------------------------------------------- */

    /** 작품 하나의 처리 상태 · 작가 · 지난 처리. 결과 화면의 관리자 칸이 읽는다. */
    @Transactional(readOnly = true)
    public Map<String, Object> state(String runId) {
        WebtoonWork work = mustFind(runId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("title", titleOf(runId));
        out.put("public", work.isPublic());
        out.put("trashed", work.isTrashed());
        out.put("example", work.isExample());
        Map<String, Object> note = notes.of(runId);
        out.put("moderation", note);
        out.put("owner", ownerOf(work.getUserId()));
        out.put("history", logs.findByRunIdOrderByCreatedAtDescIdDesc(runId).stream().map(this::row).toList());
        return out;
    }

    /** 최근 처리 기록 — 최근부터. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recent(int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        List<ModerationLog> found = logs.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, n));
        Map<Long, String> emails = emailsOf(found.stream().map(ModerationLog::getOwnerUserId).collect(Collectors.toSet()));
        Map<Long, String> admins = emailsOf(found.stream().map(ModerationLog::getAdminUserId).collect(Collectors.toSet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModerationLog one : found) {
            Map<String, Object> row = row(one);
            row.put("title", titleOf(one.getRunId()));
            row.put("owner_email", one.getOwnerUserId() == null ? null : emails.get(one.getOwnerUserId()));
            row.put("admin_email", admins.get(one.getAdminUserId()));
            out.add(row);
        }
        return out;
    }

    /** 관리자 휴지통 — 관리자가 삭제 처리한 작품들. 표지는 잠깐 열리는 주소로 준다(휴지통 작품은 장 주소가 404). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> removed() {
        List<WebtoonWork> found = works.findByModerationOrderByModeratedAtDesc(REMOVED);
        Map<Long, String> emails = emailsOf(found.stream().map(WebtoonWork::getUserId).collect(Collectors.toSet()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebtoonWork work : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            String runId = work.getRunId();
            row.put("run_id", runId);
            row.put("title", titleOf(runId));
            row.put("reason", work.getModerationReason());
            row.put("removed_at", work.getModeratedAt() == null ? null : work.getModeratedAt().toString());
            Instant from = work.getDeletedAt() == null ? work.getModeratedAt() : work.getDeletedAt();
            row.put("purge_at", from == null ? null : from.plus(Duration.ofDays(trash.keepDays())).toString());
            row.put("owner_email", work.getUserId() == null ? null : emails.get(work.getUserId()));
            List<Integer> nums = pages.pageNumbersOf(runId);
            row.put("cover_url", nums.isEmpty() ? null : pages.urlOf(runId, nums.getFirst(), 320));
            out.add(row);
        }
        return out;
    }

    /** 작가별 처리 수 — 경고 · 비공개 · 삭제를 센다. 많은 사람부터. 게스트 작품은 계정이 없어 빠진다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> owners() {
        Map<Long, Map<String, Long>> by = new LinkedHashMap<>();
        for (Object[] r : logs.countsByOwner()) {
            Long owner = ((Number) r[0]).longValue();
            by.computeIfAbsent(owner, k -> new HashMap<>()).put((String) r[1], ((Number) r[2]).longValue());
        }
        Map<Long, String> emails = emailsOf(by.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : by.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            long w = e.getValue().getOrDefault(WARN, 0L);
            long h = e.getValue().getOrDefault(HIDE, 0L);
            long d = e.getValue().getOrDefault(REMOVE, 0L);
            row.put("user_id", e.getKey());
            row.put("email", emails.get(e.getKey()));
            row.put("warn", w);
            row.put("hide", h);
            row.put("remove", d);
            row.put("total", w + h + d);
            out.add(row);
        }
        out.sort((a, b) -> Long.compare((long) b.get("total"), (long) a.get("total")));
        return out;
    }

    /* ---- 안쪽 ------------------------------------------------------------- */

    private void record(WebtoonWork work, Long adminId, String action, String reason, boolean tellOwner) {
        ModerationLog one = logs.save(ModerationLog.of(work.getRunId(), work.getUserId(), adminId, action, reason, Instant.now()));
        if (tellOwner && notice.moderated(work.getUserId(), work.getRunId(), action, reason, trash.keepDays())) {
            one.markNotified();
            logs.save(one);
        }
        log.info("관리자 처리 (run={}, action={}, admin={}, owner={})", work.getRunId(), action, adminId, work.getUserId());
    }

    /** 그림 자리를 옮긴다. 공개 전환과 같은 이유로 실패해도 처리는 그대로 둔다. */
    private void moveArt(String runId, boolean toPublic) {
        try {
            pages.moveAll(runId, toPublic);
        } catch (RuntimeException e) {
            log.error("관리자 처리는 했는데 그림을 못 옮겼습니다 (run={}, public={})", runId, toPublic, e);
        }
    }

    private Map<String, Object> row(ModerationLog one) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", one.getId());
        row.put("run_id", one.getRunId());
        row.put("action", one.getAction());
        row.put("reason", one.getReason());
        row.put("notified", one.isNotified());
        row.put("owner_user_id", one.getOwnerUserId());
        row.put("at", one.getCreatedAt().toString());
        return row;
    }

    private Map<String, Object> ownerOf(Long userId) {
        if (userId == null) {
            return null;                    // 게스트 작품 — 알릴 방법이 없다
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user_id", userId);
        out.put("email", users.findById(userId).map(User::getEmail).orElse(null));
        out.put("counts", logs.countByOwnerUserIdAndActionIn(userId, List.of(WARN, HIDE, REMOVE)));
        return out;
    }

    private Map<Long, String> emailsOf(Set<Long> ids) {
        ids.remove(null);
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (User u : users.findAllById(ids)) {
            out.put(u.getId(), u.getEmail());
        }
        return out;
    }

    private String titleOf(String runId) {
        return stories.chosenOf(runId).map(WebtoonStory::displayTitle).filter(s -> !s.isBlank()).orElse(runId);
    }

    private WebtoonWork mustFind(String runId) {
        return works.findFirstByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다"));
    }

    private static void notExample(WebtoonWork work) {
        if (work.isExample()) {
            throw bad("예시 작품은 예시 작품 관리에서 내려 주세요");
        }
    }

    private static String mustReason(String reason) {
        String why = clean(reason);
        if (why.isEmpty()) {
            throw bad("사유를 적어 주세요 — 작가에게 그대로 보입니다");
        }
        return why;
    }

    private static String clean(String reason) {
        String why = reason == null ? "" : reason.strip();
        return why.length() > REASON_MAX ? why.substring(0, REASON_MAX) : why;
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }
}
