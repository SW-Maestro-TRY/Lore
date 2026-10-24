package com.lore.webtoon.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.character.WebtoonCharacter;
import com.lore.webtoon.character.WebtoonCharacterRepository;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WebtoonWorkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 관리자가 남의 작품 · 캐릭터를 <b>비공개까지</b> 훑어보는 목록(#779). 이상한 것을 만들지 않는지 보려는 자리다.
 *
 * 그림 주소는 주인 화면과 같은 규칙으로 만든다 — 공개 자리는 상대경로, 비공개 자리는 잠깐 열리는 주소
 * ({@link PageStore#urlOf}, {@link CharacterService#artUrl}). 이 서비스는 관리자 확인을 마친 컨트롤러만 부른다.
 *
 * 목록은 번호(id)만 SQL 로 거르고, 줄은 엔티티로 다시 읽어 만든다 — 거르는 조건이 늘어도 줄 모양은 한 곳에 있다.
 */
@Service
public class AdminCatalog {

    static final int PAGE_SIZE = 40;
    private static final int COVER_WIDTH = 320;

    private final WebtoonWorkRepository works;
    private final WebtoonCharacterRepository characters;
    private final CharacterService characterService;
    private final PageStore pages;
    private final StoryStore stories;
    private final UserRepository users;

    @PersistenceContext
    private EntityManager em;

    public AdminCatalog(WebtoonWorkRepository works, WebtoonCharacterRepository characters,
                        CharacterService characterService, PageStore pages, StoryStore stories, UserRepository users) {
        this.works = works;
        this.characters = characters;
        this.characterService = characterService;
        this.pages = pages;
        this.stories = stories;
        this.users = users;
    }

    /**
     * 작품 목록. {@code filter}: all · public · private(작가가 비공개) · hidden(관리자 비공개) · removed(관리자 삭제)
     * · trash(작가가 지움) · guest(로그인 안 하고 만듦). 예시 작품은 빼고 센다 — 예시는 예시 관리에서 본다.
     * {@code q} 는 작가 이메일 · 제목 · 작품 번호 중 하나에 들어 있으면 고른다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> works(String filter, String q, int page) {
        StringBuilder where = new StringBuilder(" where not w.is_example");
        switch (filter == null ? "all" : filter) {
            case "public" -> where.append(" and w.is_public and w.deleted_at is null and w.moderation is null");
            case "private" -> where.append(" and not w.is_public and w.deleted_at is null and w.moderation is null");
            case "hidden" -> where.append(" and w.moderation = 'HIDDEN'");
            case "removed" -> where.append(" and w.moderation = 'REMOVED'");
            case "trash" -> where.append(" and w.deleted_at is not null and w.moderation is null");
            case "guest" -> where.append(" and w.user_id is null");
            default -> { }
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append("""
                     and (lower(coalesce(u.email, '')) like :q or lower(coalesce(w.run_id, '')) like :q
                          or exists (select 1 from webtoon_story s where s.run_id = w.run_id
                                     and (lower(coalesce(s.user_title, '')) like :q or lower(coalesce(s.title, '')) like :q)))
                    """);
        }
        String from = " from webtoon_work w left join users u on u.id = w.user_id" + where;
        long total = AdminOverview.num(bind(em.createNativeQuery("select count(*)" + from), search, q).getSingleResult());
        List<Long> ids = ids(bind(em.createNativeQuery("select w.id" + from + " order by w.created_at desc, w.id desc"), search, q), page);

        List<WebtoonWork> found = ordered(ids, works.findAllById(ids), WebtoonWork::getId);
        Map<Long, String> emails = emailsOf(found.stream().map(WebtoonWork::getUserId).collect(Collectors.toSet()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WebtoonWork w : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            String runId = w.getRunId();
            row.put("run_id", runId);
            row.put("title", runId == null ? null
                    : stories.chosenOf(runId).map(WebtoonStory::displayTitle).filter(s -> !s.isBlank()).orElse(null));
            row.put("owner_email", w.getUserId() == null ? null : emails.get(w.getUserId()));
            row.put("guest", w.getUserId() == null);
            row.put("state", stateOf(w));
            row.put("moderation_reason", w.getModerationReason());
            row.put("created_at", iso(w.getCreatedAt()));
            List<Integer> nums = runId == null ? List.of() : pages.pageNumbersOf(runId);
            row.put("pages", nums.size());
            row.put("cover_url", nums.isEmpty() ? null : pages.urlOf(runId, nums.getFirst(), COVER_WIDTH));
            rows.add(row);
        }
        return page(rows, total, page);
    }

    /**
     * 캐릭터 목록. {@code filter}: all · private(공유 안 함) · shared(커뮤니티 공유) · blocked(공유 막힘)
     * · failed(못 그림) · guest. 우리가 심은 기본 제공은 뺀다. {@code q} 는 이름 · 작가 이메일 · 설명.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> characters(String filter, String q, int page) {
        StringBuilder where = new StringBuilder(" where (c.owner_id is not null or c.browser_uid is not null)");
        switch (filter == null ? "all" : filter) {
            case "private" -> where.append(" and not c.community_public");
            case "shared" -> where.append(" and c.community_public and not c.community_blocked");
            case "blocked" -> where.append(" and c.community_blocked");
            case "failed" -> where.append(" and c.status = 'ERROR'");
            case "guest" -> where.append(" and c.owner_id is null");
            default -> { }
        }
        boolean search = q != null && !q.isBlank();
        if (search) {
            where.append("""
                     and (lower(coalesce(c.name, '')) like :q or lower(coalesce(u.email, '')) like :q
                          or lower(coalesce(c.description, '')) like :q)
                    """);
        }
        String from = " from webtoon_character c left join users u on u.id = c.owner_id" + where;
        long total = AdminOverview.num(bind(em.createNativeQuery("select count(*)" + from), search, q).getSingleResult());
        List<Long> ids = ids(bind(em.createNativeQuery("select c.id" + from + " order by c.created_at desc, c.id desc"), search, q), page);

        List<WebtoonCharacter> found = ordered(ids, characters.findAllById(ids), WebtoonCharacter::getId);
        Map<Long, String> emails = emailsOf(found.stream().map(WebtoonCharacter::getOwnerId).collect(Collectors.toSet()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WebtoonCharacter c : found) {
            rows.add(characterRow(c, emails, false));
        }
        return page(rows, total, page);
    }

    /** 캐릭터 하나 — 목록 줄에 사람이 넣은 글 전부를 더한다. */
    @Transactional(readOnly = true)
    public Map<String, Object> character(String publicId) {
        WebtoonCharacter c = byPublicId(publicId);
        Set<Long> owner = new HashSet<>();
        owner.add(c.getOwnerId());
        Map<Long, String> emails = emailsOf(owner);
        Map<String, Object> m = characterRow(c, emails, true);
        return m;
    }

    private WebtoonCharacter byPublicId(String publicId) {
        @SuppressWarnings("unchecked")
        List<Number> id = em.createNativeQuery("select id from webtoon_character where public_id = :p")
                .setParameter("p", publicId).getResultList();
        if (id.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다");
        }
        return characters.findById(id.getFirst().longValue())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다"));
    }

    private Map<String, Object> characterRow(WebtoonCharacter c, Map<Long, String> emails, boolean full) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getPublicId());
        row.put("name", c.getName());
        row.put("owner_email", c.getOwnerId() == null ? null : emails.get(c.getOwnerId()));
        row.put("guest", c.getOwnerId() == null);
        row.put("source", c.getSource() == null ? null : c.getSource().name());
        row.put("status", c.getStatus() == null ? null : c.getStatus().name());
        row.put("shared", c.isCommunityPublic());
        row.put("blocked", c.isCommunityBlocked());
        row.put("world", c.getWorldLabel());
        row.put("genre", c.getGenre());
        row.put("created_at", iso(c.getCreatedAt()));
        row.put("art_url", characterService.artUrl(c));
        row.put("description", full ? c.getDescription() : clip(c.getDescription(), 120));
        if (full) {
            row.put("error", c.getError());
            row.put("species", c.getSpecies());
            row.put("role", c.getRoleName());
            row.put("twist", c.getTwist());
            row.put("quote", c.getQuote());
            row.put("style", c.getStyle());
            row.put("asked_name", c.getAskedName());
            row.put("asked_world", c.getAskedWorld());
        }
        return row;
    }

    /** 작품의 지금 상태 한 낱말 — 화면이 딱지로 옮긴다. 관리자 처리가 작가 설정보다 앞선다. */
    static String stateOf(WebtoonWork w) {
        if (w.isRemovedByAdmin()) {
            return "removed";
        }
        if (w.isHiddenByAdmin()) {
            return "hidden";
        }
        if (w.getDeletedAt() != null) {
            return "trash";
        }
        return w.isPublic() ? "public" : "private";
    }

    private static Query bind(Query query, boolean search, String q) {
        return search ? query.setParameter("q", "%" + q.trim().toLowerCase() + "%") : query;
    }

    private static List<Long> ids(Query query, int page) {
        @SuppressWarnings("unchecked")
        List<Number> got = query.setFirstResult(Math.max(0, page) * PAGE_SIZE).setMaxResults(PAGE_SIZE).getResultList();
        return got.stream().map(Number::longValue).toList();
    }

    /** findAllById 는 순서를 안 지킨다 — SQL 이 정한 순서로 되돌린다. */
    private static <T> List<T> ordered(List<Long> ids, Collection<T> found, Function<T, Long> idOf) {
        Map<Long, T> by = new HashMap<>();
        for (T t : found) {
            by.put(idOf.apply(t), t);
        }
        return ids.stream().map(by::get).filter(Objects::nonNull).toList();
    }

    private Map<Long, String> emailsOf(Set<Long> ids) {
        ids.remove(null);
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : users.findAllById(ids)) {
                out.put(u.getId(), u.getEmail());
            }
        }
        return out;
    }

    private static Map<String, Object> page(List<Map<String, Object>> rows, long total, int page) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total);
        out.put("page", Math.max(0, page));
        out.put("pageSize", PAGE_SIZE);
        return out;
    }

    private static String clip(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    private static String iso(Instant at) {
        return at == null ? null : at.toString();
    }
}
