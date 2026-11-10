package com.lore.webtoon.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 대화 캡처로 만드는 개인 테스트 웹툰(dev 전용, 비밀번호). 공개 작품 목록·크레딧과 섞이지 않는다.
 *
 * <p>흐름: 캡처 올리기 → 전사(--chat-extract) → 사람이 원문 확인 → 화·장면 나누기(--chat-prepare)
 * → 시트(보관함 우선, --chat-sheets → --sheet) → 사람이 시트 확인 → 화마다 표지부터 한 장씩 그리기 → 이어 붙이기.
 * 한 장이 안전 검사에 걸려도 작업 전체를 멈추지 않는다 — 몇 번 더 그려 보고, 그래도 안 되면 그 장만 보류하고
 * 다음 장을 그린다. 보류된 장과 마음에 안 드는 장은 메모를 붙여 다시 그린다.
 *
 * <p>켜는 법: 그 서버의 runs 폴더에 {@code _test-comic/config.json} ({"passcode": "…"}) 을 둔다. 파일이 없으면
 * 모든 주소가 404 다 — 공용 설정(compose·env)을 건드리지 않고, 저장소에 비밀번호가 남지 않는다.
 */
@RestController
@RequestMapping("/api/webtoon/v1/test-comic")
public class TestComicController {
    private static final Logger log = LoggerFactory.getLogger(TestComicController.class);
    private static final int MAX_CAPTURES = 40;
    /** 장 하나를 그리는 시도 횟수(하네스 안의 재시도와 별개). 안전 검사는 같은 프롬프트로도 다시 하면 통과하는 일이 많았다. */
    private static final int PAGE_ATTEMPTS = 3;
    /** gpt-image 프롬프트 한도 32,000자 — 장 프롬프트가 30k 안팎이라 메모가 붙으면 넘을 수 있다. */
    private static final int PROMPT_LIMIT = 31_900;
    private static final Map<String, String> ENV = Map.of("NH_STYLE", "webtoon_lock_bg", "OPENAI_IMAGE_QUALITY", "high",
            "PAGE_IMAGE_MODEL", "gpt-image-2", "NH_LANG", "ko");

    private final Path root;
    private final HarnessProcess harness;
    private final ObjectMapper mapper;
    private volatile String passcode = "";
    private volatile long configStamp = -1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();

    public TestComicController(HarnessProcess harness) {
        this.harness = harness;
        this.root = harness.runsDir();
        this.mapper = new ObjectMapper();
    }

    /** runs/_test-comic/config.json 을 읽는다(바뀌면 다시). 없거나 비밀번호가 비면 이 기능은 없는 것으로 본다. */
    private String passcode() {
        Path config = root.resolve("_test-comic").resolve("config.json");
        try {
            long stamp = Files.isRegularFile(config) ? Files.getLastModifiedTime(config).toMillis() : 0;
            if (stamp != configStamp) {
                String next = "";
                if (stamp > 0) {
                    Map<String, Object> c = mapper.readValue(config.toFile(), new TypeReference<>() {});
                    next = String.valueOf(c.getOrDefault("passcode", "")).strip();
                }
                passcode = next;
                configStamp = stamp;
            }
        } catch (IOException e) {
            passcode = "";
        }
        return passcode;
    }

    // ── 공통 ─────────────────────────────────────────────────────────

    private void check(String supplied, String ip) {
        String passcode = passcode();
        if (passcode.isBlank()) throw new TestError(404, "없는 페이지예요");
        List<Instant> recent = failures.computeIfAbsent(ip == null ? "unknown" : ip, unused -> new ArrayList<>());
        synchronized (recent) {
            recent.removeIf(at -> at.isBefore(Instant.now().minusSeconds(900)));
            if (recent.size() >= 5) throw new TestError(429, "잠시 뒤 다시 시도해 주세요");
            if (!MessageDigest.isEqual(passcode.getBytes(StandardCharsets.UTF_8),
                    (supplied == null ? "" : supplied).getBytes(StandardCharsets.UTF_8))) {
                recent.add(Instant.now());
                throw new TestError(401, "비밀번호가 맞지 않아요");
            }
            recent.clear();
        }
    }

    private Path dir(String id) {
        if (id == null || !id.matches("test-[0-9a-f]{32}")) throw new TestError(404, "작업을 찾지 못했어요");
        Path path = root.resolve(id);
        if (!Files.isRegularFile(path.resolve("test-state.json"))) throw new TestError(404, "작업을 찾지 못했어요");
        return path;
    }

    private String epId(String id, int n) { return id + "-e" + n; }

    private Path ep(String id, int n) {
        if (n < 1 || n > 30) throw new TestError(404, "화를 찾지 못했어요");
        return root.resolve(epId(id, n));
    }

    private Map<String, Object> state(Path dir) {
        try { return mapper.readValue(dir.resolve("test-state.json").toFile(), new TypeReference<>() {}); }
        catch (IOException e) { throw new TestError(500, "작업 상태를 읽지 못했어요"); }
    }

    private synchronized void status(Path dir, String phase, String message) {
        Map<String, Object> state = state(dir);
        state.put("phase", phase);
        state.put("message", message);
        state.put("updatedAt", Instant.now().toString());
        write(dir.resolve("test-state.json"), state);
    }

    private synchronized void setHeld(Path dir, Collection<String> held) {
        Map<String, Object> state = state(dir);
        state.put("held", new ArrayList<>(held));
        write(dir.resolve("test-state.json"), state);
    }

    @SuppressWarnings("unchecked")
    private List<String> held(Path dir) {
        Object v = state(dir).get("held");
        return v instanceof List<?> l ? new ArrayList<>((List<String>) l) : new ArrayList<>();
    }

    private void write(Path path, Object value) {
        try { Files.createDirectories(path.getParent()); mapper.writeValue(path.toFile(), value); }
        catch (IOException e) { throw new TestError(500, "작업을 저장하지 못했어요"); }
    }

    private Object readJson(Path path) {
        if (!Files.isRegularFile(path)) return null;
        // JsonNode 로 돌려주면 응답 직렬화(스프링 쪽 Jackson)가 그것을 빈(bean)으로 펼쳐 버린다 — 맵·리스트로 읽는다.
        try { return mapper.readValue(path.toFile(), Object.class); } catch (IOException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> episodes(Path dir) throws IOException {
        Path plan = dir.resolve("plan.json");
        if (!Files.isRegularFile(plan)) return List.of();
        Map<String, Object> p = mapper.readValue(plan.toFile(), new TypeReference<>() {});
        return (List<Map<String, Object>>) p.getOrDefault("episodes", List.of());
    }

    /** 인물·역할·시트 자리(people.json — chat_comic.use_library 가 쓴다). 시트가 지금 있는지는 파일로 다시 본다. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> people(String id) throws IOException {
        Path file = root.resolve(id).resolve("people.json");
        if (!Files.isRegularFile(file)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> saved = mapper.readValue(file.toFile(), new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> one : saved) {
            Map<String, Object> p = new LinkedHashMap<>(one);
            p.put("has_sheet", Files.isRegularFile(sheetPath(id, String.valueOf(p.get("sheet")))));
            out.add(p);
        }
        return out;
    }

    private Path sheetPath(String id, String rel) {
        Path e1 = ep(id, 1).normalize();
        Path path = e1.resolve(rel).normalize();
        if (!path.startsWith(e1)) throw new TestError(400, "시트 자리가 잘못됐어요");
        return path;
    }

    private int pagesOf(Map<String, Object> episode) { return ((Number) episode.get("pages")).intValue(); }

    private boolean run(List<String> args, Map<String, String> env) throws IOException, InterruptedException {
        return harness.run(null, args, env, line -> {}) == 0;
    }

    private void mustRun(List<String> args, Map<String, String> env) throws IOException, InterruptedException {
        if (!run(args, env)) throw new IOException("생성 단계 실패: " + String.join(" ", args));
    }

    // ── 화면이 부르는 곳 ─────────────────────────────────────────────

    /** 이 서버에 켜져 있는지만 알려 준다(비밀번호 없이). 꺼져 있으면 404 — 화면이 「없는 페이지」로 보인다. */
    @GetMapping("/status")
    public Map<String, Object> enabled() {
        if (passcode().isBlank()) throw new TestError(404, "없는 페이지예요");
        return Map.of("ok", true);
    }

    @PostMapping("/check")
    public Map<String, Object> unlock(@RequestHeader("X-Test-Passcode") String pass,
                                      jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/drafts")
    public Map<String, Object> create(@RequestHeader("X-Test-Passcode") String pass,
                                      jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        String id = "test-" + UUID.randomUUID().toString().replace("-", "");
        Path path = root.resolve(id);
        write(path.resolve("test-state.json"), new LinkedHashMap<>(Map.of(
                "id", id, "phase", "upload", "message", "캡처를 올려 주세요", "createdAt", Instant.now().toString())));
        return state(path);
    }

    @GetMapping("/drafts/{id}")
    public Map<String, Object> get(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                   jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        Map<String, Object> out = new LinkedHashMap<>(state(path));
        Object transcript = readJson(path.resolve("transcript.json"));
        if (transcript != null) out.put("transcript", transcript);
        Object source = readJson(path.resolve("sheet_source.json"));
        if (source != null) out.put("sheetSource", source);
        Object saved = readJson(path.resolve("sheet_saved.json"));
        if (saved != null) out.put("sheetSaved", saved);
        List<Map<String, Object>> eps = new ArrayList<>();
        for (Map<String, Object> e : episodes(path)) {
            int n = ((Number) e.get("n")).intValue();
            Map<String, Object> one = new LinkedHashMap<>(e);
            Path pages = ep(id, n).resolve("pages");
            List<String> drawn = List.of();
            if (Files.isDirectory(pages)) {
                try (var files = Files.list(pages)) {
                    drawn = files.map(p -> p.getFileName().toString()).filter(s -> s.matches("page[0-9]{2}\\.png")).sorted().toList();
                }
            }
            one.put("drawn", drawn);
            one.put("stitched", Files.isRegularFile(ep(id, n).resolve("episode.png")));
            Object scenes = readJson(ep(id, n).resolve("scenes.json"));
            if (scenes != null) one.put("scenesJson", scenes);
            eps.add(one);
        }
        out.put("episodes", eps);
        out.put("people", people(id));
        return out;
    }

    @PostMapping(value = "/drafts/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> image(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                     @RequestParam("kind") String kind, @RequestParam("image") MultipartFile file,
                                     jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        String phase = String.valueOf(state(path).get("phase"));
        // 캡처는 읽기 전에만, 인물 사진은 원문 확인 단계에서도(인물 칸에서 고르므로) 받는다.
        boolean profile = !"capture".equals(kind);
        if (!("upload".equals(phase) || (profile && "review".equals(phase)))) throw new TestError(409, "이미 만들기를 시작했어요");
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024) throw new TestError(400, "이미지는 8MB 이하로 올려 주세요");
        BufferedImage decoded = ImageIO.read(file.getInputStream());
        if (decoded == null || (long) decoded.getWidth() * decoded.getHeight() > 12_000_000)
            throw new TestError(400, "이미지를 읽을 수 없어요");
        Path target;
        if ("capture".equals(kind)) {
            Path folder = path.resolve("captures");
            Files.createDirectories(folder);
            long count;
            try (var files = Files.list(folder)) { count = files.count(); }
            if (count >= MAX_CAPTURES) throw new TestError(400, "캡처는 " + MAX_CAPTURES + "장까지 올릴 수 있어요");
            target = folder.resolve("capture%02d.jpg".formatted(count + 1));
        } else if ("main".equals(kind) || "co".equals(kind) || kind.matches("p([0-9]|1[01])")) {
            target = path.resolve("profiles").resolve(kind + ".jpg");
            Files.createDirectories(target.getParent());
        } else throw new TestError(400, "이미지 종류가 잘못됐어요");
        BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D draw = rgb.createGraphics();
        draw.setColor(java.awt.Color.WHITE);
        draw.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        draw.drawImage(decoded, 0, 0, null);
        draw.dispose();
        if (!ImageIO.write(rgb, "jpg", target.toFile())) throw new TestError(400, "이미지를 저장하지 못했어요");
        return Map.of("ok", true, "name", target.getFileName().toString());
    }

    /** 캡처 대신(또는 캡처를 고친 뒤) 대화 글을 직접 붙여 넣는 길 — 전사 오류가 없다. */
    @PostMapping("/drafts/{id}/text")
    public Map<String, Object> text(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                    @RequestBody Map<String, Object> body,
                                    jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!List.of("upload", "review").contains(String.valueOf(state(path).get("phase"))))
            throw new TestError(409, "이미 만들기를 시작했어요");
        if (!(body.get("dialogue") instanceof List<?> dialogue) || dialogue.isEmpty())
            throw new TestError(400, "대화가 비어 있어요");
        write(path.resolve("transcript.json"), Map.of("dialogue", dialogue, "characters", body.getOrDefault("characters", List.of())));
        status(path, "review", "대화를 확인하고 고쳐 주세요");
        return state(path);
    }

    @PostMapping("/drafts/{id}/extract")
    public Map<String, Object> extract(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                       jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!"upload".equals(state(path).get("phase"))) throw new TestError(409, "이미 분석을 시작했어요");
        if (!Files.isDirectory(path.resolve("captures"))) throw new TestError(400, "대화 캡처를 올려 주세요");
        status(path, "extracting", "대화를 읽고 있어요");
        worker.submit(() -> {
            try {
                mustRun(List.of("--run-id", id, "--chat-extract"), Map.of());
                status(path, "review", "대화를 확인하고 고쳐 주세요");
            } catch (Exception e) {
                log.warn("비공개 캡처 전사 실패 (id={})", id, e);
                status(path, "error", "캡처를 읽지 못했어요. 이미지를 확인해 주세요");
            }
        });
        return state(path);
    }

    /** 확인한 대화 → 화·장면 나누기 → 시트(보관함 우선). 시트 확인 단계에서 멈춘다. */
    @SuppressWarnings("unchecked")
    @PostMapping("/drafts/{id}/generate")
    public Map<String, Object> generate(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                        @RequestBody Map<String, Object> draft,
                                        jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!"review".equals(state(path).get("phase"))) throw new TestError(409, "먼저 대화를 확인해 주세요");
        if (!(draft.get("dialogue") instanceof List<?> dialogue) || dialogue.isEmpty())
            throw new TestError(400, "대화가 비어 있어요");
        // 다음 화 이어 붙이기: 앞 작품(같은 제목) 화 번호를 이어 세고, 마지막 장면이 끝난 상태에서 시작한다.
        String prev = String.valueOf(draft.getOrDefault("continue_of", "")).strip();
        Map<String, Object> saved = new LinkedHashMap<>(draft);
        if (!prev.isBlank()) {
            Path before = dir(prev);
            try {
                Map<String, Object> plan = mapper.readValue(before.resolve("plan.json").toFile(), new TypeReference<>() {});
                String title = String.valueOf(plan.getOrDefault("title", ""));
                int last = 0;
                String lastEnd = "";
                for (Map<String, Object> w : (List<Map<String, Object>>) works(pass, request).get("works")) {
                    if (!title.equals(w.get("title"))) continue;
                    for (Map<String, Object> e : (List<Map<String, Object>>) w.get("episodes")) last = Math.max(last, ((Number) e.get("number")).intValue());
                    Map<String, Object> wp = mapper.readValue(root.resolve(String.valueOf(w.get("id"))).resolve("plan.json").toFile(), new TypeReference<>() {});
                    lastEnd = String.valueOf(wp.getOrDefault("last_end", lastEnd));
                }
                saved.put("title", title);
                saved.put("start_number", last + 1);
                saved.put("prev_end", lastEnd);
            } catch (IOException e) { throw new TestError(400, "이어 붙일 작품을 읽지 못했어요"); }
        }
        write(path.resolve("draft.json"), saved);
        status(path, "generating", "화와 장면을 나누고 있어요");
        worker.submit(() -> {
            try {
                mustRun(List.of("--run-id", id, "--chat-prepare"), ENV);
                if (!prev.isBlank()) inheritSheets(prev, id);
                mustRun(List.of("--run-id", id, "--chat-sheets"), ENV);
                status(path, "generating", "인물 시트를 준비하고 있어요");
                mustRun(List.of("--run-id", epId(id, 1), "--sheet"), ENV);
                status(path, "sheet_review", "인물 시트를 확인해 주세요");
            } catch (Exception e) {
                log.warn("비공개 웹툰 준비 실패 (id={})", id, e);
                status(path, "error", "장면이나 시트를 만들지 못했어요. 작업 번호: " + id);
            }
        });
        return state(path);
    }

    /** 시트 하나를 메모를 붙여 다시 만든다. 사양부터 다시 쓴다(사양이 남아 있으면 메모가 안 들어간다). */
    @PostMapping("/drafts/{id}/sheet")
    public Map<String, Object> redoSheet(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                         @RequestBody Map<String, Object> body,
                                         jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!"sheet_review".equals(state(path).get("phase"))) throw new TestError(409, "시트 확인 단계가 아니에요");
        int who = ((Number) body.getOrDefault("who", 0)).intValue();
        String note = String.valueOf(body.getOrDefault("note", "")).strip();
        if (note.length() > 300) throw new TestError(400, "메모는 300자 안으로 적어 주세요");
        List<Map<String, Object>> ps = people(id);
        if (who < 0 || who >= ps.size()) throw new TestError(404, "인물을 찾지 못했어요");
        if (!"main".equals(ps.get(who).get("role")))
            throw new TestError(400, "조연은 시트 보관함에 그림을 올려 바꿔 주세요");
        Path e1 = ep(id, 1);
        Path png = sheetPath(id, String.valueOf(ps.get(who).get("sheet")));
        Path spec = who == 0 ? e1.resolve("sheet_spec.json")
                : png.resolveSibling(png.getFileName().toString().replaceFirst("\\.png$", ".spec.json"));
        Path versions = e1.resolve("sheet-versions");
        Files.createDirectories(versions);
        if (Files.isRegularFile(png)) Files.move(png, versions.resolve(Instant.now().toEpochMilli() + "-" + png.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(spec);
        status(path, "generating", "시트를 다시 그리고 있어요");
        worker.submit(() -> {
            try {
                List<String> args = new ArrayList<>(List.of("--run-id", epId(id, 1), "--sheet"));
                if (!note.isBlank()) { args.add("--note"); args.add(note); }
                mustRun(args, ENV);
                status(path, "sheet_review", "인물 시트를 확인해 주세요");
            } catch (Exception e) {
                log.warn("비공개 시트 다시 그리기 실패 (id={})", id, e);
                status(path, "sheet_review", "시트를 다시 그리지 못했어요. 메모를 바꿔 다시 해 보세요");
            }
        });
        return state(path);
    }

    /** 확인한 시트를 보관함에 넣는다 — 다음에 같은 인물이면 그대로 쓴다. */
    @PostMapping("/drafts/{id}/library")
    public Map<String, Object> saveLibrary(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                           jakarta.servlet.http.HttpServletRequest request) throws Exception {
        check(pass, request.getRemoteAddr());
        dir(id);
        mustRun(List.of("--run-id", id, "--chat-save-sheets"), ENV);
        return get(id, pass, request);
    }

    /** 둘러보기 — 만든 작품(화가 나뉜 작업) 전부. 같은 제목은 화면이 한 작품으로 묶는다(이어 붙인 다음 화). */
    @GetMapping("/works")
    public Map<String, Object> works(@RequestHeader("X-Test-Passcode") String pass,
                                     jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        List<Map<String, Object>> out = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path d : dirs.filter(x -> x.getFileName().toString().matches("test-[0-9a-f]{32}")).toList()) {
                if (!Files.isRegularFile(d.resolve("plan.json")) || !Files.isRegularFile(d.resolve("test-state.json"))) continue;
                String id = d.getFileName().toString();
                Map<String, Object> plan = mapper.readValue(d.resolve("plan.json").toFile(), new TypeReference<>() {});
                Map<String, Object> st = state(d);
                List<Map<String, Object>> eps = new ArrayList<>();
                for (Map<String, Object> e : episodes(d)) {
                    int n = ((Number) e.get("n")).intValue();
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("n", n);
                    one.put("number", e.getOrDefault("number", n));
                    one.put("subtitle", e.getOrDefault("subtitle", ""));
                    one.put("pages", e.get("pages"));
                    one.put("cover", Files.isRegularFile(ep(id, n).resolve("pages/page01.png")));
                    one.put("stitched", Files.isRegularFile(ep(id, n).resolve("episode.png")));
                    eps.add(one);
                }
                out.add(Map.of("id", id, "title", String.valueOf(plan.getOrDefault("title", "")),
                        "createdAt", String.valueOf(st.getOrDefault("createdAt", "")),
                        "phase", String.valueOf(st.getOrDefault("phase", "")), "episodes", eps));
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt"))));
        return Map.of("works", out);
    }

    /** 보관함에 든 인물 목록(이름 → 그림체들). */
    @GetMapping("/library")
    public Map<String, Object> library(@RequestHeader("X-Test-Passcode") String pass,
                                       jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path lib = root.resolve("_test-comic").resolve("sheets");
        List<Map<String, Object>> out = new ArrayList<>();
        if (Files.isDirectory(lib)) {
            try (var people = Files.list(lib)) {
                for (Path person : people.filter(Files::isDirectory).sorted().toList()) {
                    List<String> styles;
                    try (var s = Files.list(person)) {
                        styles = s.filter(p -> Files.isRegularFile(p.resolve("sheet.png"))).map(p -> p.getFileName().toString()).sorted().toList();
                    }
                    if (!styles.isEmpty()) out.add(Map.of("name", person.getFileName().toString(), "styles", styles));
                }
            }
        }
        return Map.of("people", out);
    }

    /** 이미 다듬어 둔 시트(그림 + 사양)를 보관함에 직접 넣는다 — 저장소·공용 버킷을 거치지 않는다. */
    @PostMapping(value = "/library", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadLibrary(@RequestHeader("X-Test-Passcode") String pass,
                                             @RequestParam("name") String name,
                                             @RequestParam(name = "style", defaultValue = "webtoon_lock_bg") String style,
                                             @RequestParam("sheet") MultipartFile sheet,
                                             @RequestParam(name = "spec", required = false) MultipartFile spec,
                                             jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        String person = name.strip().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (person.isBlank() || person.length() > 40 || person.startsWith(".")) throw new TestError(400, "인물 이름을 확인해 주세요");
        if (!style.matches("[a-z0-9_]{1,40}")) throw new TestError(400, "그림체 이름이 잘못됐어요");
        // 서버 업로드 한도(파일당 1MB) 때문에 화면이 고화질 JPEG 로 줄여 보낸다 — 하네스가 sheet.png 를 찾으므로 PNG 로 바꿔 둔다.
        BufferedImage picture = sheet.isEmpty() ? null : ImageIO.read(sheet.getInputStream());
        if (picture == null) throw new TestError(400, "시트 그림을 확인해 주세요");
        // 사양은 주인공급에만 필요하다. 조연(원작 그림 등)은 그림만 올려도 된다.
        boolean hasSpec = spec != null && !spec.isEmpty();
        if (hasSpec) {
            if (spec.getSize() > 256 * 1024) throw new TestError(400, "사양 파일(json)을 확인해 주세요");
            try { mapper.readTree(spec.getInputStream()); } catch (IOException e) { throw new TestError(400, "사양 파일이 JSON 이 아니에요"); }
        }
        Path dest = root.resolve("_test-comic").resolve("sheets").resolve(person).resolve(style);
        Files.createDirectories(dest);
        if (!ImageIO.write(picture, "png", dest.resolve("sheet.png").toFile())) throw new TestError(500, "시트를 저장하지 못했어요");
        if (hasSpec) spec.transferTo(dest.resolve("spec.json"));
        return library(pass, request);
    }

    /** 화마다 표지부터 한 장씩. 걸린 장은 보류하고 계속 간다. */
    @PostMapping("/drafts/{id}/continue")
    public Map<String, Object> continueDrawing(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                               jakarta.servlet.http.HttpServletRequest request) {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!"sheet_review".equals(state(path).get("phase"))) throw new TestError(409, "먼저 인물 시트를 확인해 주세요");
        status(path, "drawing", "표지부터 그리고 있어요");
        worker.submit(() -> {
            try {
                mustRun(List.of("--run-id", id, "--chat-share-sheets"), ENV);
                List<Map<String, Object>> eps = episodes(path);
                Set<String> held = new LinkedHashSet<>(held(path));
                for (Map<String, Object> e : eps) {
                    int en = ((Number) e.get("n")).intValue();
                    int total = pagesOf(e);
                    for (int n = 1; n <= total; n++) {
                        status(path, "drawing", en + "화 " + total + "장 중 " + n + "장을 그리고 있어요");
                        if (!drawPage(id, en, n, "")) held.add(en + "/" + n); else held.remove(en + "/" + n);
                        setHeld(path, held);
                    }
                    stitchIfComplete(id, en, total);
                }
                status(path, "done", held.isEmpty() ? "웹툰이 완성됐어요"
                        : "다 그렸어요. 안전 검사에 걸린 장 " + held.size() + "개는 메모를 붙여 다시 그려 주세요");
            } catch (Exception e) {
                log.warn("비공개 웹툰 생성 실패 (id={})", id, e);
                status(path, "error", "그리는 중 문제가 생겼어요. 작업 번호: " + id);
            }
        });
        return state(path);
    }

    /** 장 하나를 메모를 붙여 다시 그린다(보류된 장, 마음에 안 드는 장). */
    @PostMapping("/drafts/{id}/redraw")
    public Map<String, Object> redraw(@PathVariable String id, @RequestHeader("X-Test-Passcode") String pass,
                                      @RequestBody Map<String, Object> body,
                                      jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        if (!"done".equals(state(path).get("phase"))) throw new TestError(409, "다 그린 뒤에 다시 그릴 수 있어요");
        int en = ((Number) body.getOrDefault("episode", 1)).intValue();
        int n = ((Number) body.getOrDefault("page", 1)).intValue();
        String note = String.valueOf(body.getOrDefault("note", "")).strip();
        if (note.length() > 150) throw new TestError(400, "메모는 150자 안으로 적어 주세요(그림 모델 한도 때문)");
        Map<String, Object> e = episodes(path).stream().filter(x -> ((Number) x.get("n")).intValue() == en)
                .findFirst().orElseThrow(() -> new TestError(404, "화를 찾지 못했어요"));
        if (n < 1 || n > pagesOf(e)) throw new TestError(404, "장을 찾지 못했어요");
        Path page = ep(id, en).resolve("pages/page%02d.png".formatted(n));
        Path keep = ep(id, en).resolve("pages/versions/page%02d.%d.png".formatted(n, Instant.now().toEpochMilli()));
        Files.createDirectories(keep.getParent());
        if (Files.isRegularFile(page)) Files.move(page, keep);
        status(path, "drawing", en + "화 " + n + "장을 다시 그리고 있어요");
        worker.submit(() -> {
            String msg;
            try {
                Set<String> held = new LinkedHashSet<>(held(path));
                if (drawPage(id, en, n, note)) { held.remove(en + "/" + n); msg = en + "화 " + n + "장을 다시 그렸어요"; }
                else {
                    if (Files.isRegularFile(keep)) Files.copy(keep, page, StandardCopyOption.REPLACE_EXISTING);
                    else held.add(en + "/" + n);
                    msg = en + "화 " + n + "장을 다시 그리지 못했어요(안전 검사·한도). 메모를 바꿔 보세요";
                }
                setHeld(path, held);
                Files.deleteIfExists(ep(id, en).resolve("episode.png"));
                stitchIfComplete(id, en, pagesOf(e));
            } catch (Exception ex) {
                log.warn("비공개 장 다시 그리기 실패 (id={})", id, ex);
                msg = "다시 그리는 중 문제가 생겼어요";
            }
            status(path, "done", msg);
        });
        return state(path);
    }

    private boolean drawPage(String id, int en, int n, String note) throws IOException, InterruptedException {
        String rid = epId(id, en);
        Path png = ep(id, en).resolve("pages/page%02d.png".formatted(n));
        if (Files.isRegularFile(png)) return true;
        List<String> args = new ArrayList<>(List.of("--run-id", rid, "--detail-pages", "--page", String.valueOf(n), "--no-page-review"));
        if (n == 1) args.add("--cover-first");
        if (!note.isBlank()) { args.add("--note"); args.add(note); }
        List<String> dry = new ArrayList<>(args);
        dry.add("--dry-run");
        run(dry, ENV);
        Path prompt = ep(id, en).resolve("pages/page%02d.txt".formatted(n));
        if (!Files.isRegularFile(prompt) || Files.readString(prompt).length() > PROMPT_LIMIT) {
            log.warn("장 프롬프트가 한도를 넘음 (id={}, {}화 {}장)", id, en, n);
            return false;
        }
        for (int attempt = 1; attempt <= PAGE_ATTEMPTS && !Files.isRegularFile(png); attempt++) run(args, ENV);
        return Files.isRegularFile(png);
    }

    /** 앞 작품 1화의 시트를 새 작품 1화에 깐다(보관함에 같은 시트가 있으면 --chat-sheets 가 그것으로 덮는다). */
    private void inheritSheets(String fromId, String toId) throws IOException {
        Path from = ep(fromId, 1), to = ep(toId, 1);
        for (String f : List.of("sheet.png", "sheet_spec.json"))
            if (Files.isRegularFile(from.resolve(f))) Files.copy(from.resolve(f), to.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        Path sheets = from.resolve("sheets");
        if (Files.isDirectory(sheets)) {
            Files.createDirectories(to.resolve("sheets"));
            try (var files = Files.list(sheets)) {
                for (Path f : files.filter(x -> x.getFileName().toString().matches(".+\\.(png|spec\\.json)")).toList())
                    Files.copy(f, to.resolve("sheets").resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void stitchIfComplete(String id, int en, int total) throws IOException, InterruptedException {
        for (int n = 1; n <= total; n++)
            if (!Files.isRegularFile(ep(id, en).resolve("pages/page%02d.png".formatted(n)))) return;
        if (harness.stitch(epId(id, en), ENV, line -> {}) != 0) log.warn("이어 붙이기 실패 (id={}, {}화)", id, en);
    }

    @GetMapping(value = "/drafts/{id}/art/{file}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> art(@PathVariable String id, @PathVariable String file,
                                      @RequestParam(name = "e", defaultValue = "1") int en,
                                      @RequestHeader("X-Test-Passcode") String pass,
                                      jakarta.servlet.http.HttpServletRequest request) throws IOException {
        check(pass, request.getRemoteAddr());
        Path path = dir(id);
        Path e1 = ep(id, 1);
        String coName = "";
        if ("co-sheet.png".equals(file) && Files.isRegularFile(path.resolve("draft.json"))) {
            Map<String, Object> draft = mapper.readValue(path.resolve("draft.json").toFile(), new TypeReference<>() {});
            coName = String.valueOf(draft.getOrDefault("co_name", "")).trim().replace('/', '_').replace('\\', '_');
        }
        if (file.matches("person-[0-9]{1,2}\\.png")) {
            int n = Integer.parseInt(file.substring(7, file.length() - 4));
            List<Map<String, Object>> ps = people(id);
            if (n >= ps.size()) throw new TestError(404, "그림을 찾지 못했어요");
            Path sheet = sheetPath(id, String.valueOf(ps.get(n).get("sheet")));
            if (!Files.isRegularFile(sheet)) throw new TestError(404, "그림을 찾지 못했어요");
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Files.readAllBytes(sheet));
        }
        Path image = switch (file) {
            case "sheet.png" -> e1.resolve("sheet.png");
            case "co-sheet.png" -> coName.isBlank() ? null : e1.resolve("sheets").resolve(coName + ".png");
            case "episode.png" -> ep(id, en).resolve("episode.png");
            default -> file.matches("page[0-9]{2}\\.png") ? ep(id, en).resolve("pages").resolve(file) : null;
        };
        if (image == null || !Files.isRegularFile(image)) throw new TestError(404, "그림을 찾지 못했어요");
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Files.readAllBytes(image));
    }

    @ExceptionHandler(TestError.class)
    public ResponseEntity<Map<String, String>> error(TestError error) {
        return ResponseEntity.status(error.code).body(Map.of("error", error.getMessage()));
    }

    private static class TestError extends RuntimeException {
        final int code;
        TestError(int code, String message) { super(message); this.code = code; }
    }
}
