package com.lore.webtoon.play;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.character.CharacterStatus;
import com.lore.webtoon.character.WebtoonCharacter;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.WebtoonLanguage;
import com.lore.webtoon.lorebook.LoreBook;
import com.lore.webtoon.lorebook.PrivateLorebookService;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.usage.UsageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 세계관 만들기(2026-11-03). 사용자가 세계 · 등장인물 · 사건 · 예시 캐릭터(최대 둘)를 정해 저장하고, 「모두에게」로
 * 두면 세계관 체험 목록에 올라간다. 체험하는 사람은 그 세계의 예시 캐릭터를 바로 넣어 볼 수 있다.
 *
 * <ul>
 *   <li>직접 적기 — {@link #save}. 화면이 보낸 칸을 그대로 저장한다.</li>
 *   <li>AI 로 채우기 · 다시 만들기 — {@link #make}. 적은 칸과 참고할 내 설정집으로 비어 있는 칸을 짓고(하네스
 *       {@code run.py --world-make}, 글 호출 한 번), 모자란 예시 캐릭터를 새로 그린다(캐릭터 만들기와 같은 줄).
 *       같은 세계로 다시 부르면 앞서 지은 것과 다르게 짓고, 앞서 그려 준 예시 캐릭터는 지운다.</li>
 *   <li>참고한 설정집은 고른 때의 사본을 둔다. 화면에는 내주지 않고(설정집은 소유자 전용), 이 세계로 만드는
 *       웹툰의 설정집으로만 쓴다.</li>
 * </ul>
 */
@Service
public class PlayWorldService {

    private static final Logger log = LoggerFactory.getLogger(PlayWorldService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    static final int MAX_EXAMPLES = 2, MAX_EVENTS = 12, MAX_CAST = 12;
    /** 만들기가 이만큼 넘게 RUNNING 이면 서버가 그사이 꺼진 것이다 — 멈춘 것으로 본다. */
    private static final long STALE_SECONDS = 300;

    private final PlayWorldRepository rows;
    private final PrivateLorebookService books;
    private final CharacterService characters;
    private final HarnessProcess harness;
    private final SafetyGuard safety;
    private final SpendGuard guard;
    private final UsageService usage;
    private final int daily;
    private final Semaphore slots = new Semaphore(2);
    private final ObjectMapper json = new ObjectMapper();

    public PlayWorldService(PlayWorldRepository rows, PrivateLorebookService books, CharacterService characters,
                            HarnessProcess harness, SafetyGuard safety, SpendGuard guard, UsageService usage,
                            @Value("${lore.webtoon.play.world-per-day:5}") int daily) {
        this.rows = rows;
        this.books = books;
        this.characters = characters;
        this.harness = harness;
        this.safety = safety;
        this.guard = guard;
        this.usage = usage;
        this.daily = Math.max(0, daily);
    }

    /** AI 로 채우기 요청. {@code id} 가 있으면 그 세계를 다시 만든다. {@code draft} 는 저장 본문과 같은 모양. */
    public record MakeForm(String id, Map<String, Object> draft, String lorebook_id, String note, String language) {
    }

    private LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private static Long requireUser(Long user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "세계관을 만들려면 로그인해 주세요.");
        }
        return user;
    }

    private PlayWorld owned(String id, Long user) {
        return rows.findByIdAndOwnerId(id, requireUser(user))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 세계관이 없어요."));
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    // ───────────────────────── 직접 적어 저장 ─────────────────────────

    /** 저장. {@code id} 가 없으면 새로 만든다. visibility 가 public 이면 올린다. */
    @Transactional
    public Map<String, Object> save(String id, Long user, Map<String, Object> body) {
        Long owner = requireUser(user);
        Map<String, Object> raw = body == null ? Map.of() : body;
        Instant now = Instant.now();
        PlayWorld row = id == null ? new PlayWorld(newId(), owner, now) : owned(id, owner);
        settle(row);
        if (PlayWorld.RUNNING.equals(row.making)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 세계관을 AI 가 채우고 있어요. 다 채운 뒤 저장해 주세요.");
        }
        Map<String, Object> doc = cleanDoc(raw, row.id);
        boolean pub = "public".equals(doc.get("visibility"));
        check(doc);
        attachImages(doc, read(row.docJson), owner, row.id, pub);
        checkAllExamples(doc, owner);
        if (pub && (text(doc.get("name"), 40).isEmpty() || list(doc.get("events")).isEmpty())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "세계 이름과 사건을 하나 이상 적어 주세요.");
        }
        String bookId = text(doc.get("lorebook_id"), 40);
        String hadBook = text(read(row.docJson).get("lorebook_id"), 40);
        if (!bookId.equals(hadBook) || row.madeAt == null && !bookId.isEmpty()) {
            row.booksJson = write(bookId.isEmpty() ? List.of() : List.of(snapshot(books.read(bookId, owner))));
            LoreBook.clean(lorebook(doc, readList(row.booksJson)), true);           // 너무 크면 여기서 막는다
        }
        Set<String> before = allExamples(read(row.docJson));
        row.docJson = write(doc);
        row.status = pub ? PlayWorld.PUBLISHED : PlayWorld.DRAFT;
        if (pub && row.publishedAt == null) {
            row.publishedAt = now;
        }
        if (row.madeAt == null) {
            row.madeAt = now;
        }
        row.updatedAt = now;
        rows.save(row);
        before.addAll(allExamples(doc));
        syncShown(owner, before);
        return view(row, owner);
    }

    /**
     * 그림 칸을 창고 키로 바꾸고, 사건의 예시 인물을 캐릭터로 둔다(생성 호출 없음).
     * <ul>
     *   <li>새 그림(data URL · presign 키)이 오면 올린다. 지금 그림 주소({name}_url)만 오면 전에 저장한 키를 그대로 둔다.
     *       둘 다 없으면 그림을 뺀 것이다.</li>
     *   <li>예시 인물(events[].example_people)은 사진 · 이름 · 역할 · 설정 그대로 이 세계의 캐릭터가 되고, 그 id 가
     *       사건 examples 앞에 선다(둘까지). 세계에 딸린 캐릭터 중 어디에도 안 쓰인 것은 지운다.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void attachImages(Map<String, Object> doc, Map<String, Object> old, Long owner, String worldId, boolean shown) {
        doc.put("cover_art", art(doc, text(old.get("cover_art"), 600), owner, "cover", false));
        Map<String, Map<?, ?>> oldCast = byId(old.get("cast"));
        for (Object c : list(doc.get("cast"))) {
            Map<String, Object> m = (Map<String, Object>) c;
            Map<?, ?> was = oldCast.getOrDefault(text(m.get("id"), 40), Map.of());
            m.put("face_art", art(m, text(was.get("face_art"), 600), owner, "face", true));
        }
        Map<String, Map<?, ?>> oldEvents = byId(old.get("events"));
        Set<String> used = new LinkedHashSet<>();
        int n = 0;
        for (Object e : list(doc.get("events"))) {
            Map<String, Object> ev = (Map<String, Object>) e;
            n++;
            Map<?, ?> was = oldEvents.getOrDefault(text(ev.get("id"), 60), Map.of());
            ev.put("image_art", art(ev, text(was.get("image_art"), 600), owner, "image", false));
            List<String> fromPeople = new ArrayList<>();
            int k = 0;
            for (Object x : list(ev.get("example_people"))) {
                Map<String, Object> p = (Map<String, Object>) x;
                k++;
                String pid = text(p.get("id"), 40);
                if (pid.isEmpty()) {
                    pid = "p" + n + "-" + k;
                    p.put("id", pid);
                }
                String face = storeOrNull(p, owner, "face", true);     // 없으면 전 사진 그대로(글만 있던 사람은 글만)
                String cid = personId(worldId, pid);
                String role = text(p.get("role"), 60);
                String desc = text(p.get("desc"), 600);
                WebtoonCharacter c = characters.putPlayPerson(cid, owner, worldId, text(p.get("name"), 30),
                        role.isEmpty() ? desc : desc.isEmpty() ? role : role + ". " + desc, face, shown);
                p.put("character_id", c.getPublicId());
                strip(p, "face");
                fromPeople.add(c.getPublicId());
                used.add(c.getPublicId());
            }
            List<String> examples = new ArrayList<>(fromPeople);
            for (String id : ids(ev.get("examples"))) {
                if (!examples.contains(id)) {
                    examples.add(id);
                }
            }
            ev.put("examples", examples);
            used.addAll(examples);
        }
        used.addAll(ids(doc.get("examples")));
        for (WebtoonCharacter c : characters.playDrawn(worldId)) {             // 세계에 딸린 캐릭터 중 안 쓰는 것
            if (!used.contains(c.getPublicId())) {
                characters.dropPlayExamples(worldId, c.getPublicId());
            }
        }
    }

    /** 그림 칸 하나 — 새 그림이면 올린 키, 지금 그림을 둔다면 전 키, 아니면 빈 값. 입력 칸은 지운다. */
    private String art(Map<String, Object> m, String before, Long owner, String name, boolean face) {
        String fresh = storeOrNull(m, owner, name, face);
        boolean keep = m.get(name + "_url") instanceof String s && !s.isBlank() || m.get(name + "_art") instanceof String a && !a.isBlank();
        strip(m, name);
        return fresh != null ? fresh : keep ? before : "";
    }

    private String storeOrNull(Map<String, Object> m, Long owner, String name, boolean face) {
        return characters.storeWorldImage(owner, (String) m.get(name), (String) m.get(name + "_key"), face);
    }

    private static void strip(Map<String, Object> m, String name) {
        m.remove(name);
        m.remove(name + "_key");
        m.remove(name + "_url");
        m.remove(name + "_art");
    }

    private static final Set<String> PICTURE_FIELDS = Set.of("cover", "cover_key", "cover_url", "cover_art",
            "face", "face_key", "face_url", "face_art", "image", "image_key", "image_url", "image_art");

    /** 글만 남긴 사본 — 안전 검사에 그림(data URL)을 보내지 않는다. */
    static Object withoutPictures(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (!PICTURE_FIELDS.contains(String.valueOf(k))) {
                    out.put(String.valueOf(k), withoutPictures(v));
                }
            });
            return out;
        }
        if (value instanceof List<?> l) {
            return l.stream().map(PlayWorldService::withoutPictures).toList();
        }
        return value;
    }

    private static Map<String, Map<?, ?>> byId(Object value) {
        Map<String, Map<?, ?>> out = new LinkedHashMap<>();
        for (Object x : list(value)) {
            if (x instanceof Map<?, ?> m && !text(m.get("id"), 60).isEmpty()) {
                out.put(text(m.get("id"), 60), m);
            }
        }
        return out;
    }

    /** 예시 인물 캐릭터 id — 세계와 인물 id 로 정해져 다시 저장해도 같은 캐릭터를 쓴다. */
    static String personId(String worldId, String personId) {
        return "pw" + PlayPeople.idOf(worldId, "", personId).substring(2);
    }

    /** 예시 캐릭터는 내가 만든 캐릭터만, 둘까지. 남의 것·없는 것은 조용히 뺀다. */
    private List<String> checkExamples(List<?> raw, Long owner) {
        List<String> out = new ArrayList<>();
        for (Object x : raw) {
            String id = text(x, 40);
            if (!id.isEmpty() && !out.contains(id) && out.size() < MAX_EXAMPLES && characters.ownedBy(id, owner) != null) {
                out.add(id);
            }
        }
        return out;
    }

    /** 세계의 예시와 사건마다의 예시를 내 캐릭터만 남게 거른다. */
    @SuppressWarnings("unchecked")
    private void checkAllExamples(Map<String, Object> doc, Long owner) {
        doc.put("examples", checkExamples(list(doc.get("examples")), owner));
        for (Object e : list(doc.get("events"))) {
            Map<String, Object> ev = (Map<String, Object>) e;
            ev.put("examples", checkExamples(list(ev.get("examples")), owner));
        }
    }

    /** 이 세계에 걸린 예시 캐릭터 전부 — 세계 것과 사건마다의 것. */
    static Set<String> allExamples(Map<String, Object> doc) {
        Set<String> out = new LinkedHashSet<>(ids(doc.get("examples")));
        for (Object e : list(doc.get("events"))) {
            if (e instanceof Map<?, ?> v) {
                out.addAll(ids(v.get("examples")));
            }
        }
        return out;
    }

    private void check(Map<String, Object> doc) {
        try {
            safety.checkText("webtoon-world-save", json.writeValueAsString(withoutPictures(doc)));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 이 사람이 올린 세계들의 예시 캐릭터만 남에게 열고 나머지는 닫는다. 한 캐릭터를 두 세계에 예시로 썼을 때
     * 한쪽을 내려도 다른 쪽이 올라가 있으면 열린 채로 둔다.
     */
    private void syncShown(Long owner, Set<String> touched) {
        Set<String> shown = new HashSet<>();
        for (PlayWorld w : rows.findAllByOwnerIdOrderByUpdatedAtDesc(owner)) {
            if (w.published()) {
                shown.addAll(allExamples(read(w.docJson)));
            }
        }
        characters.showInPlay(touched, shown);
    }

    // ───────────────────────── AI 로 채우기 · 다시 만들기 ─────────────────────────

    @Transactional
    public Map<String, Object> make(Long user, MakeForm form) {
        Long owner = requireUser(user);
        Map<String, Object> raw = form.draft() == null ? Map.of() : form.draft();
        String note = text(form.note(), 500);
        Instant now = Instant.now();
        PlayWorld row = form.id() == null || form.id().isBlank() ? new PlayWorld(newId(), owner, now) : owned(form.id(), owner);
        settle(row);
        if (PlayWorld.RUNNING.equals(row.making)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 세계관을 만들고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        Map<String, Object> draft = cleanDoc(raw, row.id);
        draft.put("visibility", row.published() ? "public" : "private");
        try {
            safety.checkText("webtoon-world-make", json.writeValueAsString(withoutPictures(draft)), note);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        String bookId = text(form.lorebook_id() == null ? draft.get("lorebook_id") : form.lorebook_id(), 40);
        draft.put("lorebook_id", bookId);
        List<Map<String, Object>> picked = bookId.isEmpty() ? List.of() : List.of(snapshot(books.read(bookId, owner)));
        LoreBook.clean(lorebook(draft, picked), true);
        String blocked = guard.whyBlockedByMoney();
        if (blocked != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, blocked);
        }
        if (daily == 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "세계관 AI 채우기를 잠시 쉬고 있어요. 직접 적어 저장할 수 있어요.");
        }
        if (!slots.tryAcquire()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "다른 세계관을 만들고 있어요. 잠시 후 다시 시도해 주세요.");
        }
        boolean registered = false;
        try {
            if (rows.reserve(owner, today(), daily) == 0) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "오늘 세계관 AI 채우기 횟수를 모두 사용했어요. 직접 적어 저장할 수 있어요.");
            }
            attachImages(draft, read(row.docJson), owner, row.id, row.published());
            checkAllExamples(draft, owner);
            String lang = WebtoonLanguage.normalize(form.language());
            Map<String, Object> previous = row.madeAt == null ? null : read(row.docJson);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("draft", draft);
            input.put("note", note);
            input.put("language", lang);
            row.inputJson = write(input);
            row.booksJson = write(picked);
            row.docJson = write(draft);                // 적은 것은 먼저 남긴다 — 짓다 죽어도 사라지지 않게
            row.making = PlayWorld.RUNNING;
            row.error = null;
            row.updatedAt = now;
            rows.save(row);
            String id = row.id;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Thread.ofVirtual().start(() -> perform(id, owner, draft, picked, note, previous, lang));
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        slots.release();
                    }
                }
            });
            registered = true;
            return view(row, owner);
        } finally {
            if (!registered) {
                slots.release();
            }
        }
    }

    private void perform(String id, Long owner, Map<String, Object> draft, List<Map<String, Object>> picked,
                         String note, Map<String, Object> previous, String lang) {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lore-world-");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("draft", draft);
            input.put("books", picked);
            input.put("note", note);
            input.put("previous", previous);
            json.writeValue(dir.resolve("input.json").toFile(), input);
            if (harness.worldMake(dir, lang) != 0) {
                throw new IllegalStateException("world make failed");
            }
            Map<String, Object> made = json.readValue(dir.resolve("result.json").toFile(), new TypeReference<>() {});
            safety.checkText("webtoon-world-result", json.writeValueAsString(made));
            PlayWorld row = rows.findById(id).orElseThrow();
            Map<String, Object> doc = merge(draft, made, id);
            Set<String> kept = allExamples(doc);                         // 사용자가 남긴 예시
            for (WebtoonCharacter old : characters.playDrawn(id)) {      // 앞서 그려 준 것 중 안 남긴 것은 지운다
                if (!kept.contains(old.getPublicId())) {
                    characters.dropPlayExamples(id, old.getPublicId());
                }
            }
            List<?> madeEvents = list(made.get("events"));
            List<?> events = list(doc.get("events"));
            for (int i = 0; i < events.size(); i++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = (Map<String, Object>) events.get(i);
                List<String> examples = new ArrayList<>(ids(ev.get("examples")));
                List<?> wanted = i < madeEvents.size() && madeEvents.get(i) instanceof Map<?, ?> m ? list(m.get("examples")) : List.of();
                for (Object x : wanted) {
                    if (examples.size() >= MAX_EXAMPLES) {
                        break;
                    }
                    if (x instanceof Map<?, ?> e) {
                        String name = text(e.get("name"), 30);
                        String desc = text(e.get("description"), 1500);
                        String cid = characters.drawPlayExample(owner, id, name, desc, row.published(), lang).getPublicId();
                        examples.add(cid);
                        List<Object> people = new ArrayList<>(list(ev.get("example_people")));
                        Map<String, Object> person = new LinkedHashMap<>();
                        person.put("id", "ai-" + cid);
                        person.put("name", name);
                        person.put("role", "");
                        person.put("desc", desc);
                        person.put("character_id", cid);
                        people.add(person);
                        ev.put("example_people", people);
                    }
                }
                ev.put("examples", examples);
            }
            row.docJson = write(doc);
            row.making = PlayWorld.IDLE;
            row.error = null;
            row.madeAt = row.updatedAt = Instant.now();
            rows.save(row);
        } catch (Exception e) {
            log.warn("세계관을 만들지 못했습니다: {}", id, e);
            rows.findById(id).ifPresent(row -> {
                row.making = PlayWorld.ERROR;
                row.error = e instanceof BusinessException b && b.getMessage() != null
                        ? text(b.getMessage(), 300)
                        : "세계관을 채우지 못했어요. 적은 내용은 그대로 있어요. 다시 눌러 주세요.";
                row.updatedAt = Instant.now();
                rows.save(row);
            });
        } finally {
            if (dir != null) {
                try {
                    usage.ingestMetaFile("world-make-" + id + "-" + System.currentTimeMillis(), dir.resolve("meta.json"));
                } catch (Exception error) {
                    log.warn("세계관 만들기 원가를 적지 못했습니다: {}", id, error);
                }
                try (var files = Files.walk(dir)) {
                    for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                } catch (Exception error) {
                    log.warn("세계관 만들기 임시 폴더를 못 치웠습니다: {}", id, error);
                }
            }
            slots.release();
        }
    }

    /** 사용자가 적은 칸은 그대로, 비어 있던 칸은 지은 것으로. 하네스가 이미 같은 규칙으로 합쳐 준다 — 여기는 모양만. */
    static Map<String, Object> merge(Map<String, Object> draft, Map<String, Object> made, String worldId) {
        Map<String, Object> raw = new LinkedHashMap<>(draft);
        raw.put("name", made.get("name"));
        raw.put("genre", made.get("genre"));
        raw.put("one", made.get("one"));
        List<String> rules = new ArrayList<>();
        for (Object r : list(made.get("rules"))) {
            String s = text(r, 600);
            if (!s.isEmpty()) {
                rules.add(s);
            }
        }
        raw.put("rule", String.join("\n", rules));
        Map<String, String> castIds = new LinkedHashMap<>();
        Map<String, Object> castFaces = new LinkedHashMap<>();
        for (Object c : list(draft.get("cast"))) {
            if (c instanceof Map<?, ?> p) {
                castIds.put(text(p.get("name"), 30), text(p.get("id"), 40));
                castFaces.put(text(p.get("name"), 30), p.get("face_art"));
            }
        }
        List<Map<String, Object>> cast = new ArrayList<>();
        int n = 0;
        for (Object c : list(made.get("cast"))) {
            if (c instanceof Map<?, ?> p) {
                Map<String, Object> m = new LinkedHashMap<>();
                String name = text(p.get("name"), 30);
                String had = castIds.getOrDefault(name, "");
                m.put("id", had.isEmpty() ? "c" + (++n) : had);
                m.put("name", name);
                m.put("role", p.get("role"));
                m.put("desc", p.get("desc"));
                if (castFaces.get(name) != null) {
                    m.put("face_art", castFaces.get(name));
                }
                cast.add(m);
            }
        }
        raw.put("cast", cast);
        List<?> mine = list(draft.get("events"));
        List<Map<String, Object>> events = new ArrayList<>();
        int i = 0;
        for (Object e : list(made.get("events"))) {
            if (e instanceof Map<?, ?> v) {
                Map<String, Object> m = new LinkedHashMap<>();
                Map<?, ?> had = i < mine.size() && mine.get(i) instanceof Map<?, ?> h ? h : Map.of();
                m.put("id", had.get("id"));
                m.put("title", v.get("title"));
                m.put("line", v.get("line"));
                m.put("situation", v.get("situation"));
                m.put("place", v.get("place"));
                m.put("spot", had.get("spot") == null ? "" : had.get("spot"));   // 자리는 사용자가 적었을 때만
                m.put("cast", had.get("cast") == null ? List.of() : had.get("cast"));
                m.put("examples", had.get("examples") == null ? List.of() : had.get("examples"));
                m.put("example_people", had.get("example_people") == null ? List.of() : had.get("example_people"));
                if (had.get("image_art") != null) {
                    m.put("image_art", had.get("image_art"));
                }
                events.add(m);
                i++;
            }
        }
        raw.put("events", events);
        Map<String, Object> out = cleanDoc(raw, worldId);
        out.put("cover_art", draft.get("cover_art"));
        return out;
    }

    /** 서버가 만들기 중에 꺼졌으면 RUNNING 이 남는다 — 오래됐으면 멈춘 것으로 바꾼다. */
    private void settle(PlayWorld row) {
        if (PlayWorld.RUNNING.equals(row.making) && row.updatedAt.isBefore(Instant.now().minusSeconds(STALE_SECONDS))) {
            row.making = PlayWorld.ERROR;
            row.error = "세계관 만들기가 중단됐어요. 다시 눌러 주세요.";
            rows.save(row);
        }
    }

    @Transactional
    public void delete(String id, Long user) {
        PlayWorld row = owned(id, user);
        if (PlayWorld.RUNNING.equals(row.making)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 세계관을 만들고 있어요. 다 만든 뒤 지워 주세요.");
        }
        Set<String> touched = allExamples(read(row.docJson));
        characters.dropPlayExamples(id, null);
        rows.delete(row);
        rows.flush();
        syncShown(row.ownerId, touched);
    }

    // ───────────────────────── 읽기 ─────────────────────────

    @Transactional
    public Map<String, Object> read(String id, Long user) {
        PlayWorld row = rows.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 세계관이 없어요."));
        boolean mine = user != null && user.equals(row.ownerId);
        if (!mine && !row.published()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 세계관이 없어요.");
        }
        if (mine) {
            settle(row);
        }
        return view(row, user);
    }

    @Transactional
    public List<Map<String, Object>> mine(Long user) {
        if (user == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlayWorld row : rows.findAllByOwnerIdOrderByUpdatedAtDesc(user)) {
            settle(row);
            out.add(view(row, user));
        }
        return out;
    }

    /** 세계관 목록에 붙는 것 — 내 세계(사건이 있는 것)와 남이 올린 세계. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listed(Long user) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (user != null) {
            for (PlayWorld row : rows.findAllByOwnerIdOrderByUpdatedAtDesc(user)) {
                if (!list(read(row.docJson).get("events")).isEmpty()) {
                    out.add(view(row, user));
                }
            }
        }
        for (PlayWorld row : rows.findAllByStatusOrderByPublishedAtDesc(PlayWorld.PUBLISHED)) {
            if (user == null || !user.equals(row.ownerId)) {
                out.add(view(row, user));
            }
        }
        return out;
    }

    /**
     * 세계관 체험 시작에 쓸 세계 — 올라간 것, 또는 내가 만든 것. 운영 세계(worlds.json)와 같은 모양에 설정집이 붙고,
     * 사건의 상황에는 장소 · 주인공 자리 · 나오는 인물을 붙인다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> playable(String id, Long user) {
        PlayWorld row = rows.findById(id).orElse(null);
        if (row == null || !row.published() && (user == null || !user.equals(row.ownerId))) {
            return null;
        }
        Map<String, Object> doc = read(row.docJson);
        Map<String, String> names = new LinkedHashMap<>();
        for (Object c : list(doc.get("cast"))) {
            if (c instanceof Map<?, ?> p) {
                names.put(text(p.get("id"), 40), text(p.get("name"), 30));
            }
        }
        List<Map<String, Object>> events = new ArrayList<>();
        for (Object e : list(doc.get("events"))) {
            if (e instanceof Map<?, ?> v) {
                Map<String, Object> m = new LinkedHashMap<>();
                v.forEach((k, val) -> m.put(String.valueOf(k), val));
                m.put("situation", situation(v, names));
                events.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>(doc);
        out.put("id", row.id);
        out.put("events", events);
        out.put("lorebook", lorebook(doc, readList(row.booksJson)));
        return out;
    }

    static String situation(Map<?, ?> event, Map<String, String> castNames) {
        StringBuilder s = new StringBuilder(text(event.get("situation"), 1500));
        String place = text(event.get("place"), 60);
        String spot = text(event.get("spot"), 60);
        List<String> who = new ArrayList<>();
        for (Object c : list(event.get("cast"))) {
            String name = castNames.get(text(c, 40));
            if (name != null && !name.isEmpty()) {
                who.add(name);
            }
        }
        if (!place.isEmpty()) {
            s.append("\n장소: ").append(place);
        }
        if (!spot.isEmpty()) {
            s.append("\n주인공의 자리: ").append(spot);
        }
        if (!who.isEmpty()) {
            s.append("\n이 사건에 나오는 인물: ").append(String.join(", ", who));
        }
        return s.toString();
    }

    // ───────────────────────── 모양 ─────────────────────────

    private Map<String, Object> view(PlayWorld row, Long user) {
        boolean mine = user != null && user.equals(row.ownerId);
        Map<String, Object> doc = read(row.docJson);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id);
        out.put("name", text(doc.get("name"), 40));
        out.put("genre", text(doc.get("genre"), 40));
        out.put("one", text(doc.get("one"), 300));
        out.put("rule", text(doc.get("rule"), 4000));
        List<?> events = list(doc.get("events"));
        out.put("spot", events.isEmpty() || !(events.get(0) instanceof Map<?, ?> first) ? "" : text(first.get("spot"), 60));
        List<Map<String, Object>> cast = new ArrayList<>();
        for (Object c : list(doc.get("cast"))) {
            if (c instanceof Map<?, ?> v) {
                Map<String, Object> m = new LinkedHashMap<>();
                v.forEach((k, val) -> m.put(String.valueOf(k), val));
                m.put("face_url", characters.imageUrl(text(m.remove("face_art"), 600)));
                cast.add(m);
            }
        }
        out.put("cast", cast);
        Map<String, Map<String, Object>> known = new LinkedHashMap<>();   // 보여 줄 수 있는 예시 캐릭터
        String cover = null;
        for (String cid : allExamples(doc)) {
            WebtoonCharacter c = characters.ownedBy(cid, row.ownerId);
            if (c == null || !mine && c.getStatus() != CharacterStatus.READY) {
                continue;
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", cid);
            x.put("name", c.getName());
            x.put("description", c.getDescription() == null ? "" : c.getDescription());
            String art = characters.artUrl(c);
            x.put("art_url", art);
            x.put("status", c.getStatus().name().toLowerCase());
            if (mine) {
                x.put("error", c.getError());
            }
            if (cover == null && c.getStatus() == CharacterStatus.READY && art != null) {
                cover = art;
            }
            known.put(cid, x);
        }
        List<Map<String, Object>> shownEvents = new ArrayList<>();
        for (Object e : events) {
            if (e instanceof Map<?, ?> v) {
                Map<String, Object> m = new LinkedHashMap<>();
                v.forEach((k, val) -> m.put(String.valueOf(k), val));
                m.put("examples", ids(v.get("examples")).stream().filter(known::containsKey).toList());
                String image = characters.imageUrl(text(m.remove("image_art"), 600));
                if (image != null) {
                    m.put("image_url", image);
                }
                List<Map<String, Object>> people = new ArrayList<>();
                for (Object x : list(v.get("example_people"))) {
                    if (x instanceof Map<?, ?> pp) {
                        Map<String, Object> who = known.get(text(pp.get("character_id"), 40));
                        if (who == null) {
                            continue;                                      // 남에게는 다 그려진 것만
                        }
                        Map<String, Object> q = new LinkedHashMap<>();
                        pp.forEach((k, val) -> q.put(String.valueOf(k), val));
                        q.put("face_url", who.get("art_url"));
                        people.add(q);
                    }
                }
                m.put("example_people", people);
                shownEvents.add(m);
            }
        }
        out.put("events", shownEvents);
        out.put("examples", ids(doc.get("examples")).stream().filter(known::containsKey).toList());
        out.put("example_characters", new ArrayList<>(known.values()));
        String coverArt = characters.imageUrl(text(doc.get("cover_art"), 600));
        out.put("cover_url", coverArt != null ? coverArt : cover);
        out.put("mine", mine);
        out.put("visibility", row.published() ? "public" : "private");
        if (mine) {
            out.put("lorebook_id", text(doc.get("lorebook_id"), 40));
            out.put("making", row.making.toLowerCase());
            out.put("error", row.error);
            out.put("made_today", rows.used(user, today()));
            out.put("make_limit", daily);
        }
        return out;
    }

    /** 이 세계로 웹툰을 만들 때 넘기는 설정집 — 세계(규칙 줄 · 등장인물) 한 권 + 고른 내 설정집 사본. */
    static Map<String, Object> lorebook(Map<String, Object> doc, List<Map<String, Object>> picked) {
        String name = text(doc.get("name"), 40);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : text(doc.get("rule"), 4000).split("\n")) {
            if (!line.isBlank()) {
                entries.add(entry(name.isEmpty() ? "세계" : name, line.trim(), "world", List.of(), true));
            }
        }
        for (Object c : list(doc.get("cast"))) {
            if (c instanceof Map<?, ?> p && !text(p.get("name"), 30).isEmpty()) {
                String who = text(p.get("name"), 30);
                String role = text(p.get("role"), 60);
                String desc = text(p.get("desc"), 600);
                entries.add(entry(who, role.isEmpty() ? desc : desc.isEmpty() ? role : role + " — " + desc,
                        "person", List.of(who), false));
            }
        }
        Map<String, Object> world = new LinkedHashMap<>();
        world.put("name", name.isEmpty() ? "세계" : name);
        world.put("source", "experience");
        world.put("confirmed", true);
        world.put("fixed_settings", text(doc.get("one"), 300));
        world.put("progress_state", "");
        world.put("entries", entries);
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(world);
        all.addAll(picked);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", true);
        out.put("books", all);
        return out;
    }

    private static Map<String, Object> entry(String title, String content, String kind, List<String> keys, boolean always) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("title", title);
        e.put("content", content);
        e.put("kind", kind);
        e.put("keys", keys);
        e.put("always", always);
        return e;
    }

    /** 고른 설정집 한 권의 사본 — 보관함 표시(library_id 등)는 빼고, 사용자가 고른 것이니 확인된 것으로 둔다. */
    private static Map<String, Object> snapshot(Map<String, Object> book) {
        Map<String, Object> out = new LinkedHashMap<>(book);
        out.remove("library_id");
        out.remove("library_owner");
        out.put("confirmed", true);
        return out;
    }

    /**
     * 저장하는 세계(화면이 보내는 모양 그대로). 사건 id 는 세계 id 를 앞에 붙여 다른 세계의 사건과 겹치지 않게 한다 —
     * 화면이 사건 id 로 세계를 찾는다. 이미 그 모양이면 그대로 둔다.
     */
    static Map<String, Object> cleanDoc(Map<String, Object> raw, String worldId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", text(raw.get("name"), 40));
        out.put("genre", text(raw.get("genre"), 40));
        out.put("one", text(raw.get("one"), 300));
        out.put("rule", text(raw.get("rule"), 4000));
        out.put("visibility", "public".equals(raw.get("visibility")) ? "public" : "private");
        carry(raw, out, "cover");
        out.put("lorebook_id", text(raw.get("lorebook_id"), 40));
        List<Map<String, Object>> cast = new ArrayList<>();
        Set<String> castIds = new HashSet<>();
        int c = 0;
        for (Object x : cap(list(raw.get("cast")), MAX_CAST)) {
            if (x instanceof Map<?, ?> p && !text(p.get("name"), 30).isEmpty()) {
                String id = text(p.get("id"), 40);
                if (id.isEmpty() || !castIds.add(id)) {
                    do {
                        id = "c" + (++c);
                    } while (!castIds.add(id));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", text(p.get("name"), 30));
                m.put("role", text(p.get("role"), 60));
                m.put("desc", text(p.get("desc"), 600));
                carry(p, m, "face");
                cast.add(m);
            }
        }
        out.put("cast", cast);
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Object x : cap(list(raw.get("events")), MAX_EVENTS)) {
            if (!(x instanceof Map<?, ?> v)) {
                continue;
            }
            String id = text(v.get("id"), 60);
            if (id.startsWith(worldId + "-")) {
                used.add(id);
            }
        }
        int n = 0, next = 0;
        for (Object x : cap(list(raw.get("events")), MAX_EVENTS)) {
            if (!(x instanceof Map<?, ?> v) || text(v.get("situation"), 1500).isEmpty()) {
                continue;
            }
            n++;
            String id = text(v.get("id"), 60);
            if (!id.startsWith(worldId + "-")) {
                do {
                    id = worldId + "-e" + (++next);
                } while (used.contains(id));
                used.add(id);
            }
            String situation = text(v.get("situation"), 1500);
            String line = text(v.get("line"), 120);
            List<String> who = new ArrayList<>();
            for (Object w : list(v.get("cast"))) {
                String cid = text(w, 40);
                if (castIds.contains(cid) && !who.contains(cid)) {
                    who.add(cid);
                }
            }
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("id", id);
            ev.put("no", String.format("EP %02d", n));
            String title = text(v.get("title"), 60);
            ev.put("title", title.isEmpty() ? String.format("EP %02d", n) : title);
            ev.put("line", line.isEmpty() ? situation.lines().findFirst().orElse("") : line);
            ev.put("situation", situation);
            ev.put("place", text(v.get("place"), 60));
            ev.put("spot", text(v.get("spot"), 60));
            ev.put("cast", who);
            ev.put("examples", ids(v.get("examples")).stream().limit(8).toList());   // 둘까지 고르는 것은 내 캐릭터인지 본 뒤에
            List<Map<String, Object>> people = new ArrayList<>();
            for (Object x2 : cap(list(v.get("example_people")), MAX_EXAMPLES)) {
                if (x2 instanceof Map<?, ?> p && !text(p.get("name"), 30).isEmpty()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", text(p.get("id"), 40));
                    m.put("name", text(p.get("name"), 30));
                    m.put("role", text(p.get("role"), 60));
                    m.put("desc", text(p.get("desc"), 600));
                    String cid = text(p.get("character_id"), 40);
                    if (!cid.isEmpty()) {
                        m.put("character_id", cid);
                    }
                    carry(p, m, "face");
                    people.add(m);
                }
            }
            ev.put("example_people", people);
            carry(v, ev, "image");
            ev.put("tried", 0);
            events.add(ev);
        }
        out.put("events", events);
        out.put("examples", ids(raw.get("examples")));
        return out;
    }

    /**
     * 그림 칸 하나를 옮겨 둔다 — 새로 넣은 그림({name}: data URL · {name}_key: presign 키), 지금 그림을 그대로 둔다는
     * 표시({name}_url), 저장된 창고 키({name}_art). {@link #attachImages} 가 이것으로 창고 키만 남긴다.
     */
    private static void carry(Map<?, ?> from, Map<String, Object> to, String name) {
        for (String k : List.of(name, name + "_key", name + "_url", name + "_art")) {
            Object v = from.get(k);
            if (v instanceof String s && !s.isBlank()) {
                to.put(k, k.equals(name) ? s : text(s, 600));
            }
        }
    }

    static String text(Object value, int max) {
        String s = value == null ? "" : String.valueOf(value).trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    static List<String> ids(Object value) {
        List<String> out = new ArrayList<>();
        for (Object x : list(value)) {
            String s = text(x, 40);
            if (!s.isEmpty() && !out.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<?> cap(List<?> l, int max) {
        return l.size() > max ? l.subList(0, max) : l;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private Map<String, Object> read(String value) {
        try {
            return value == null || value.isBlank() ? new LinkedHashMap<>() : json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private List<Map<String, Object>> readList(String value) {
        try {
            return value == null || value.isBlank() ? List.of() : json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
