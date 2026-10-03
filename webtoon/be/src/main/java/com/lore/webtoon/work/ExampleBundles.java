package com.lore.webtoon.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 번들({@link ExampleBundle})을 읽고 쓴다.
 *
 * <h2>받은 파일은 믿지 않는다</h2>
 *
 * 관리자만 올릴 수 있지만 zip 은 어디서 만들어졌는지 모른다. 그래서 읽는 쪽이 전부 막는다.
 * <ul>
 *   <li><b>경로 이탈</b> — {@code ../} · 절대경로 · 역슬래시 · 이상한 글자가 든 이름은 거절한다.
 *       {@code run/} 은 서버의 작업 폴더에 풀리므로 여기서 안 막으면 서버 파일을 덮어쓸 수 있다.</li>
 *   <li><b>용량 폭탄</b> — 압축 크기 말고 <b>실제로 읽은 바이트</b>를 센다. 항목 수 · 항목 하나 · 전체 ·
 *       {@code run/} 합계에 상한이 있다.</li>
 *   <li><b>그림</b> — JPEG 이고 실제로 열려야 하며 크기가 너무 크면 안 된다.</li>
 *   <li><b>{@code run/} 의 파일 종류</b> — 글·JSON·그림만 받는다. 스크립트나 클래스 파일은 거절한다.</li>
 * </ul>
 * 사진 경로({@code photos})는 서버에 없는 경로라 {@code run/input.json} 에서 비운다.
 */
public final class ExampleBundles {

    /** 한 번들의 실제 풀린 크기 합 상한. */
    static final long MAX_TOTAL_BYTES = 120L * 1024 * 1024;
    /** 쪽 그림 한 장 상한. 1080 폭 한 장이 보통 0.5MB 안팎이다. */
    static final long MAX_PAGE_BYTES = 6L * 1024 * 1024;
    /** {@code run/} 안 파일 한 개 상한. 캐릭터 시트가 2MB 안팎이다. */
    static final long MAX_RUN_FILE_BYTES = 12L * 1024 * 1024;
    /** {@code run/} 합계 상한. */
    static final long MAX_RUN_BYTES = 25L * 1024 * 1024;
    static final long MAX_MANIFEST_BYTES = 512L * 1024;
    static final int MAX_ENTRIES = 500;
    static final int MAX_PAGES = 120;
    static final int MAX_IMAGE_SIDE = 8000;

    static final Pattern RUN_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{5,63}$");
    private static final Pattern PAGE_FILE = Pattern.compile("^p(\\d{1,3})-w(\\d{2,4})\\.(jpe?g)$");
    private static final Pattern SAFE_RUN_PATH = Pattern.compile("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+){0,2}$");
    private static final Set<String> RUN_EXTENSIONS = Set.of("json", "txt", "md", "png", "jpg", "jpeg", "webp");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExampleBundles() {
    }

    /* ---- 읽기 ------------------------------------------------------------------------------ */

    /**
     * 저장소의 예시 폴더에서. 폴더 이름이 작품 번호다. 폴더는 우리가 커밋한 것이라 신뢰하지만 같은 검사를
     * 거친다 — 잘못 넣은 파일이 부팅 때 서버에 풀리지 않게.
     */
    public static ExampleBundle fromFolder(Path folder) throws IOException {
        String runId = folder.getFileName().toString();
        Path metaFile = Files.isRegularFile(folder.resolve("manifest.json"))
                ? folder.resolve("manifest.json") : folder.resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            throw bad("manifest.json(또는 meta.json)이 없습니다: " + runId);
        }
        JsonNode meta = readJson(Files.readAllBytes(metaFile));
        List<ExampleBundle.Page> pages = new ArrayList<>();
        Counter total = new Counter(MAX_TOTAL_BYTES, "번들");
        try (var found = Files.list(folder)) {
            for (Path file : found.sorted().toList()) {
                Matcher m = PAGE_FILE.matcher(file.getFileName().toString());
                if (m.matches() && Files.isRegularFile(file)) {
                    pages.add(page(m, Files.readAllBytes(file), total));
                }
            }
        }
        Map<String, byte[]> run = new LinkedHashMap<>();
        Path runDir = folder.resolve("run");
        if (Files.isDirectory(runDir)) {
            Counter runTotal = new Counter(MAX_RUN_BYTES, "run/");
            try (var walk = Files.walk(runDir)) {
                for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                    String rel = runDir.relativize(file).toString().replace('\\', '/');
                    byte[] bytes = Files.readAllBytes(file);
                    putRunFile(run, rel, bytes, runTotal);
                }
            }
        }
        return build(runId, meta, pages, run);
    }

    /**
     * zip 파일에서. {@link ZipFile} 로 읽는다 — 중앙 목록을 먼저 보고 항목 수를 센 뒤, 항목마다 <b>실제로
     * 읽은 바이트</b>를 세며 상한을 넘으면 거기서 멈춘다.
     */
    public static ExampleBundle fromZip(Path zip) throws IOException {
        JsonNode meta = null;
        List<ExampleBundle.Page> pages = new ArrayList<>();
        Map<String, byte[]> run = new LinkedHashMap<>();
        Counter total = new Counter(MAX_TOTAL_BYTES, "번들");
        Counter runTotal = new Counter(MAX_RUN_BYTES, "run/");
        try (ZipFile file = new ZipFile(zip.toFile())) {
            if (file.size() > MAX_ENTRIES) {
                throw bad("파일이 너무 많습니다(" + MAX_ENTRIES + "개까지)");
            }
            Enumeration<? extends ZipEntry> all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = safeName(entry.getName());
                if (name.equals("manifest.json") || name.equals("meta.json")) {
                    meta = readJson(readBounded(file, entry, MAX_MANIFEST_BYTES, total));
                    continue;
                }
                if (name.startsWith("run/")) {
                    putRunFile(run, name.substring("run/".length()),
                            readBounded(file, entry, MAX_RUN_FILE_BYTES, total), runTotal);
                    continue;
                }
                String leaf = name.startsWith("pages/") ? name.substring("pages/".length()) : name;
                Matcher m = PAGE_FILE.matcher(leaf);
                if (m.matches()) {
                    pages.add(page(m, readBounded(file, entry, MAX_PAGE_BYTES, total), null));
                    continue;
                }
                throw bad("알 수 없는 파일입니다: " + name);
            }
        }
        if (meta == null) {
            throw bad("manifest.json 이 없습니다");
        }
        String runId = meta.path("run_id").asText("");
        return build(runId, meta, pages, run);
    }

    private static ExampleBundle build(String runIdGiven, JsonNode meta, List<ExampleBundle.Page> pages,
                                       Map<String, byte[]> run) {
        String runId = runIdGiven == null || runIdGiven.isBlank() ? meta.path("run_id").asText("") : runIdGiven;
        if (!RUN_ID.matcher(runId).matches()) {
            throw bad("작품 번호가 올바르지 않습니다: " + runId);
        }
        String title = meta.path("title").asText("").trim();
        if (title.isEmpty()) {
            throw bad("제목이 없습니다");
        }
        if (pages.isEmpty()) {
            throw bad("쪽 그림이 하나도 없습니다");
        }
        if (pages.size() > MAX_PAGES * 2) {
            throw bad("쪽 그림이 너무 많습니다");
        }
        // 같은 장 같은 폭이 둘이면 어느 것이 맞는지 알 수 없다.
        Set<String> seen = new java.util.HashSet<>();
        for (ExampleBundle.Page p : pages) {
            if (!seen.add(p.pageNo() + ":" + p.width())) {
                throw bad("같은 쪽·폭이 두 번 있습니다: p" + p.pageNo() + "-w" + p.width());
            }
        }
        List<String> captions = new ArrayList<>();
        for (JsonNode c : meta.path("captions")) {
            captions.add(c.asText(""));
        }
        Map<String, Object> input = null;
        if (meta.path("input").isObject()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> got = MAPPER.convertValue(meta.path("input"), LinkedHashMap.class);
            input = got;
        }
        return new ExampleBundle(
                new ExampleBundle.Manifest(runId, title, meta.path("genre").asText(""),
                        meta.path("character").asText(""), meta.path("style").asText(""),
                        meta.path("logline").asText(""), captions, input),
                pages.stream().sorted(Comparator.comparingInt(ExampleBundle.Page::pageNo)
                        .thenComparingInt(ExampleBundle.Page::width)).toList(),
                sanitizedRun(run));
    }

    /** 서버에 없는 사진 경로({@code photos})를 비운다 — 안 비우면 작품 폴더에 죽은 경로가 남는다. */
    private static Map<String, byte[]> sanitizedRun(Map<String, byte[]> run) {
        byte[] input = run.get("input.json");
        if (input == null) {
            return run;
        }
        try {
            JsonNode node = MAPPER.readTree(input);
            if (node instanceof ObjectNode obj && obj.has("photos")) {
                obj.putArray("photos");
                Map<String, byte[]> copy = new LinkedHashMap<>(run);
                copy.put("input.json", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj));
                return copy;
            }
        } catch (IOException e) {
            throw bad("run/input.json 을 읽지 못했습니다");
        }
        return run;
    }

    /* ---- 쓰기 ------------------------------------------------------------------------------ */

    /** 번들을 zip 으로 쓴다. {@link #fromZip} 이 그대로 읽는 모양이다. */
    public static void writeZip(ExampleBundle bundle, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Map<String, Object> meta = new LinkedHashMap<>();
            ExampleBundle.Manifest m = bundle.manifest();
            meta.put("run_id", m.runId());
            meta.put("title", m.title());
            meta.put("genre", m.genre());
            meta.put("character", m.character());
            meta.put("style", m.style());
            meta.put("logline", m.logline());
            meta.put("pages", bundle.pages().stream().map(ExampleBundle.Page::pageNo).distinct().toList());
            meta.put("captions", m.captions());
            if (m.input() != null) {
                meta.put("input", m.input());
            }
            put(zip, "manifest.json", MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta));
            for (ExampleBundle.Page p : bundle.pages()) {
                put(zip, "pages/p%02d-w%d.jpg".formatted(p.pageNo(), p.width()), p.bytes());
            }
            for (Map.Entry<String, byte[]> f : bundle.runFiles().entrySet()) {
                put(zip, "run/" + f.getKey(), f.getValue());
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /* ---- 검사 ------------------------------------------------------------------------------ */

    private static ExampleBundle.Page page(Matcher m, byte[] bytes, Counter total) {
        if (total != null) {
            total.add(bytes.length);
        }
        int pageNo = Integer.parseInt(m.group(1));
        int width = Integer.parseInt(m.group(2));
        if (pageNo < 1 || pageNo > MAX_PAGES) {
            throw bad("쪽 번호가 올바르지 않습니다: " + m.group(0));
        }
        if (bytes.length > MAX_PAGE_BYTES) {
            throw bad("그림이 너무 큽니다: " + m.group(0));
        }
        requireJpeg(bytes, m.group(0));
        return new ExampleBundle.Page(pageNo, width, bytes);
    }

    /** 맨 앞 세 바이트가 JPEG 이고 실제로 열리며 크기가 상식 안인가. */
    static void requireJpeg(byte[] b, String name) {
        boolean magic = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        if (!magic) {
            throw bad("JPEG 그림이 아닙니다: " + name);
        }
        try {
            var img = ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) {
                throw bad("열 수 없는 그림입니다: " + name);
            }
            if (img.getWidth() > MAX_IMAGE_SIDE || img.getHeight() > MAX_IMAGE_SIDE) {
                throw bad("그림 크기가 너무 큽니다: " + name);
            }
        } catch (IOException e) {
            throw bad("열 수 없는 그림입니다: " + name);
        }
    }

    private static void putRunFile(Map<String, byte[]> run, String rel, byte[] bytes, Counter runTotal) {
        String name = rel.replace('\\', '/');
        if (!SAFE_RUN_PATH.matcher(name).matches() || name.contains("..")) {
            throw bad("run/ 안의 파일 이름이 올바르지 않습니다: " + rel);
        }
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (name.lastIndexOf('.') < 0 || !RUN_EXTENSIONS.contains(ext)) {
            throw bad("run/ 에 둘 수 없는 파일입니다: " + rel);
        }
        if (bytes.length > MAX_RUN_FILE_BYTES) {
            throw bad("run/ 파일이 너무 큽니다: " + rel);
        }
        runTotal.add(bytes.length);
        run.put(name, bytes);
    }

    /** zip 항목 이름을 검사한다. 안전하면 그대로, 아니면 거절. */
    static String safeName(String raw) {
        String name = raw == null ? "" : raw;
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("..")
                || name.contains("\u0000") || name.length() > 200) {
            throw bad("파일 이름이 올바르지 않습니다: " + name);
        }
        return name;
    }

    private static JsonNode readJson(byte[] bytes) {
        try {
            return MAPPER.readTree(bytes);
        } catch (IOException e) {
            throw bad("manifest.json 을 읽지 못했습니다");
        }
    }

    /** 항목을 읽되 {@code limit} 바이트를 넘으면 멈춘다. 압축 크기가 아니라 실제로 풀린 바이트를 센다. */
    private static byte[] readBounded(ZipFile file, ZipEntry entry, long limit, Counter total) throws IOException {
        try (InputStream in = file.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                read += n;
                if (read > limit) {
                    throw bad("파일이 너무 큽니다: " + entry.getName());
                }
                total.add(n);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, "예시 번들이 올바르지 않습니다 — " + message);
    }

    /** 합계 상한을 세는 작은 통. */
    private static final class Counter {
        private final long limit;
        private final String what;
        private long sum;

        Counter(long limit, String what) {
            this.limit = limit;
            this.what = what;
        }

        void add(long n) {
            sum += n;
            if (sum > limit) {
                throw bad(what + " 전체 크기가 상한(" + (limit / 1024 / 1024) + "MB)을 넘습니다");
            }
        }
    }
}
