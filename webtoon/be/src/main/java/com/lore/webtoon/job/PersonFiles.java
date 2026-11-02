package com.lore.webtoon.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 인물 확인에서 이름을 고치거나 주인공을 바꿀 때 작품 폴더를 맞춘다(Tosisusae 피드백 「주인공을 다른
 * 인물로 인식하고 고치기 어려워요」).
 *
 * <h2>다음 단계가 주인공을 어디서 읽나</h2>
 * <ul>
 *   <li>{@code input.json} 의 {@code name}·{@code description}·{@code photos}·{@code fields}·{@code card} —
 *       장면 나누기(「[캐릭터]」 절), 장 그림(주인공 설명 · 조연 목록에서 주인공 빼기), 시트 다시 그리기</li>
 *   <li>{@code persona.json} — 장면 나누기의 「주인공 카드」</li>
 *   <li>{@code cast.json} — 이야기·장면의 「인물들」, 조연 시트 이름</li>
 *   <li>{@code sheet.png}·{@code sheet_spec.json} — 장 그림의 주인공 참조. 조연은 {@code sheets/<이름>.png}</li>
 *   <li>{@code directions.json}·{@code scenes.json} — 이미 쓰인 글</li>
 * </ul>
 * 이름을 바꾸면 이 파일들의 글에서 이름을 같이 바꾸고({@link NameSwap}), 주인공을 바꾸면 위 자리를 두
 * 사람 사이에서 맞바꾼다. 화면이 「내가 적은 것」으로 보여 주는 DB 의 {@code input_json} 은 사람이 적은
 * 그대로 둔다.
 *
 * <p>함께 주인공(#899)은 이 파일들이 바꾸지 않는다. 그 사람의 이름은 그대로 두고, 그 사람 카드
 * ({@code personas/*.json}) 글 속에 나오는 다른 인물 이름만 따라간다.
 */
final class PersonFiles {

    private PersonFiles() {
    }

    /** 주인공 자리에만 있는 입력 칸 — 사람이 처음 적은 주인공의 설명·사진·카드. 주인공이 바뀌면 그 사람을 따라간다. */
    static final List<String> HERO_INPUT_KEYS = List.of("description", "photos", "fields", "card", "photo_note");
    /** 주인공 자리를 내준 사람의 입력 칸을 맡겨 두는 곳({@code input.json}). 다시 주인공이 되면 되찾는다. */
    static final String SWAPPED = "swapped_heroes";
    /** 주인공 자리를 내준 사람의 옛 시트 판들을 맡겨 두는 폴더. 조연 시트 목록({@code sheets/*.png})과 섞이지 않게 따로 둔다. */
    static final String HERO_SHEETS = "hero_sheets";

    private static final List<String> TEXT_FILES = List.of(
            "persona.json", "cast.json", "input.json", "directions.json", "pick.json", "scenes.json",
            "sheet_spec.json", "lore.json");
    /** 글을 바꾸지 않는 칸 — 파일 경로. */
    private static final List<String> SKIP = List.of("photos");

    /** 이름을 바꾼 결과 — 이름이 바뀐 조연(시트 줄도 옮겨야 한다), 이야기 글이 바뀌었나. */
    record Renamed(Map<String, String> cast, boolean story) {
    }

    /* ---- 이름 바꾸기 ---- */

    /**
     * 작품 폴더의 글에서 {@code olds} 를 {@code to} 로 바꾼다. 조연 이름이 같이 바뀌면(「한결 엄마」 →
     * 「도윤 엄마」) 그 조연의 시트 파일도 새 이름으로 옮긴다.
     */
    static Renamed rename(Path dir, ObjectMapper mapper, List<String> olds, String to) throws IOException {
        List<NameSwap.Pair> pairs = NameSwap.pairs(olds, to);
        if (pairs.isEmpty()) {
            return new Renamed(Map.of(), false);
        }
        List<String> castBefore = castNames(dir, mapper);
        /* 이름 치환은 글자로 하므로 함께 주인공 이름까지 건드릴 수 있다(「연화」 → 첫 주인공 「서연화」의 성 뺀 이름) */
        List<String> coBefore = coNames(dir, mapper);
        boolean story = false;
        for (String name : TEXT_FILES) {
            boolean changed = rewrite(dir.resolve(name), mapper, pairs);
            if (changed && (name.equals("directions.json") || name.equals("pick.json"))) {
                story = true;
            }
        }
        Path sheets = dir.resolve("sheets");
        if (Files.isDirectory(sheets)) {
            try (DirectoryStream<Path> found = Files.newDirectoryStream(sheets, "*.spec.json")) {
                for (Path one : found) {
                    rewrite(one, mapper, pairs);
                }
            }
        }
        restoreCoNames(dir, mapper, coBefore);
        rewritePersonas(dir, mapper, pairs);
        renameSwappedKeys(dir, mapper, pairs);
        List<String> castAfter = castNames(dir, mapper);
        Map<String, String> moved = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(castBefore.size(), castAfter.size()); i++) {
            String a = castBefore.get(i);
            String b = castAfter.get(i);
            if (!a.isBlank() && !b.isBlank() && !a.equals(b)) {
                moveCastSheet(dir, a, b);
                moved.put(a, b);
            }
        }
        return new Renamed(moved, story);
    }

    /** 함께 주인공 이름들 — {@code input.json} 의 {@code co}. 한 명짜리 작품이면 빈 목록. */
    static List<String> coNames(Path dir, ObjectMapper mapper) throws IOException {
        Path file = dir.resolve("input.json");
        if (!Files.isRegularFile(file) || !(readMap(file, mapper).get("co") instanceof List<?> co)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object one : co) {
            if (one instanceof Map<?, ?> m && !str(m.get("name")).isEmpty()) {
                out.add(str(m.get("name")));
            }
        }
        return out;
    }

    /** 글 치환이 건드린 함께 주인공 이름을 되돌린다. 자리는 그대로라 번호로 맞춘다. */
    private static void restoreCoNames(Path dir, ObjectMapper mapper, List<String> before) throws IOException {
        Path file = dir.resolve("input.json");
        if (before.isEmpty() || coNames(dir, mapper).equals(before)) {
            return;
        }
        Map<String, Object> input = readMap(file, mapper);
        if (input.get("co") instanceof List<?> co) {
            int i = 0;
            for (Object one : co) {
                if (one instanceof Map<?, ?> m && !str(m.get("name")).isEmpty() && i < before.size()) {
                    castMapInPlace(m).put("name", before.get(i++));
                }
            }
            write(file, mapper, input);
        }
    }

    /** 함께 주인공 카드 글 속의 이름을 따라 바꾼다. 카드 주인의 이름은 그대로. */
    private static void rewritePersonas(Path dir, ObjectMapper mapper, List<NameSwap.Pair> pairs) throws IOException {
        Path personas = dir.resolve("personas");
        if (!Files.isDirectory(personas)) {
            return;
        }
        try (DirectoryStream<Path> found = Files.newDirectoryStream(personas, "*.json")) {
            for (Path one : found) {
                Object own = readMap(one, mapper).get("name");
                if (rewrite(one, mapper, pairs)) {
                    Map<String, Object> card = readMap(one, mapper);
                    card.put("name", own);
                    write(one, mapper, card);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMapInPlace(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** 맡겨 둔 옛 주인공의 이름이 바뀌었으면 맡긴 칸·시트 폴더 이름도 같이. */
    private static void renameSwappedKeys(Path dir, ObjectMapper mapper, List<NameSwap.Pair> pairs) throws IOException {
        Path file = dir.resolve("input.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        Map<String, Object> input = readMap(file, mapper);
        if (!(input.get(SWAPPED) instanceof Map<?, ?> kept) || kept.isEmpty()) {
            return;
        }
        Map<String, Object> next = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : kept.entrySet()) {
            String old = String.valueOf(e.getKey());
            String now = NameSwap.replace(old, pairs);
            next.put(now, e.getValue());
            if (!now.equals(old)) {
                Path from = dir.resolve(HERO_SHEETS).resolve(RunArt.castSheetStem(old));
                Path into = dir.resolve(HERO_SHEETS).resolve(RunArt.castSheetStem(now));
                if (Files.isDirectory(from) && !Files.exists(into)) {
                    Files.move(from, into);
                }
            }
        }
        input.put(SWAPPED, next);
        write(file, mapper, input);
    }

    /** 조연 시트와 그 사양·프롬프트를 새 이름으로. 새 이름 파일이 이미 있으면 그대로 둔다. */
    static void moveCastSheet(Path dir, String oldName, String newName) throws IOException {
        Path sheets = dir.resolve("sheets");
        String a = RunArt.castSheetStem(oldName);
        String b = RunArt.castSheetStem(newName);
        if (a.isEmpty() || b.isEmpty() || a.equals(b) || !Files.isDirectory(sheets)) {
            return;
        }
        for (String ext : List.of(".png", ".spec.json", ".prompt.txt", ".spec_prompt.txt")) {
            Path from = sheets.resolve(a + ext);
            Path to = sheets.resolve(b + ext);
            if (Files.exists(from) && !Files.exists(to)) {
                Files.move(from, to);
            }
        }
    }

    /* ---- 주인공 바꾸기 ---- */

    /**
     * cast 의 {@code i} 번째 사람(0부터)을 주인공으로, 지금 주인공을 그 자리의 등장인물로 맞바꾼다.
     *
     * <ul>
     *   <li>카드 — {@code persona.json} 과 {@code cast.json} 의 그 칸을 통째로 맞바꾼다. 적힌 글은 한 글자도
     *       더하거나 빼지 않는다(설명에 없는 성격을 붙이지 않는다).</li>
     *   <li>입력 — 주인공 이름을 새 사람으로. 옛 주인공이 처음 적은 설명·사진·카드는 {@value #SWAPPED} 에
     *       맡기고, 새 주인공이 전에 맡긴 것이 있으면 되찾는다. 없으면 그 사람 카드의 역할·생김새·관계를
     *       설명으로 쓴다(사진 없음).</li>
     *   <li>시트 — 지금 주인공 시트는 그 사람의 조연 시트({@code sheets/<이름>.png})가 된다. 새 주인공이
     *       조연 시트를 갖고 있으면 그것이 주인공 시트가 된다. 없으면 {@code true} 를 돌려준다 — 부르는 쪽이
     *       주인공 시트를 새로 그린다.</li>
     *   <li>장면이 이미 나뉘었으면 장면의 등장인물 목록에 옛 주인공을 더한다(장 그림의 조연 생김새).</li>
     * </ul>
     *
     * @return 주인공 시트를 새로 그려야 하나
     */
    static boolean swapLead(Path dir, ObjectMapper mapper, int i) throws IOException {
        Path personaFile = dir.resolve("persona.json");
        Path castFile = dir.resolve("cast.json");
        Path inputFile = dir.resolve("input.json");
        if (!Files.isRegularFile(personaFile) || !Files.isRegularFile(inputFile)) {
            throw new IOException("주인공 카드가 없습니다");
        }
        Map<String, Object> persona = readMap(personaFile, mapper);
        List<Map<String, Object>> cast = Files.isRegularFile(castFile)
                ? mapper.readValue(castFile.toFile(), new TypeReference<List<Map<String, Object>>>() { })
                : new ArrayList<>();
        if (i < 0 || i >= cast.size()) {
            throw new IllegalArgumentException("그런 인물이 없습니다");
        }
        Map<String, Object> next = new LinkedHashMap<>(cast.get(i));
        String from = str(persona.get("name"));
        String to = str(next.get("name"));
        if (from.isEmpty() || to.isEmpty()) {
            throw new IllegalArgumentException("이름이 없는 인물입니다");
        }

        /* 카드 맞바꾸기 */
        Map<String, Object> old = new LinkedHashMap<>(persona);
        old.put("from_input", true);
        List<Map<String, Object>> newCast = new ArrayList<>(cast);
        newCast.set(i, old);
        mapper.writerWithDefaultPrettyPrinter().writeValue(castFile.toFile(), newCast);
        mapper.writerWithDefaultPrettyPrinter().writeValue(personaFile.toFile(), next);

        /* 입력 맞바꾸기 */
        Map<String, Object> input = readMap(inputFile, mapper);
        Map<String, Object> kept = input.get(SWAPPED) instanceof Map<?, ?> m
                ? new LinkedHashMap<>(castMap(m)) : new LinkedHashMap<>();
        Map<String, Object> mine = new LinkedHashMap<>();
        for (String k : HERO_INPUT_KEYS) {
            if (input.containsKey(k)) {
                mine.put(k, input.get(k));
            }
        }
        mine.put("name", str(input.get("name")));
        kept.put(from, mine);
        Object back = kept.remove(to);
        if (back instanceof Map<?, ?> was) {
            Map<String, Object> restore = castMap(was);
            for (String k : HERO_INPUT_KEYS) {
                input.put(k, restore.getOrDefault(k, emptyOf(k)));
            }
            input.put("photos", existing(restore.get("photos")));
        } else {
            input.put("description", describe(next));
            input.put("photos", List.of());
            input.put("fields", Map.of());
            input.put("card", Map.of());
            input.put("photo_note", "");
        }
        input.put("name", to);
        input.put(SWAPPED, kept);
        write(inputFile, mapper, input);

        /* 시트 맞바꾸기 */
        boolean needsSheet = swapSheets(dir, from, to);

        /* 장면의 등장인물 — 장 그림이 조연 생김새를 여기서 읽는다 */
        Path scenesFile = dir.resolve("scenes.json");
        if (Files.isRegularFile(scenesFile)) {
            Map<String, Object> scenes = readMap(scenesFile, mapper);
            List<Object> list = scenes.get("cast") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            boolean listed = list.stream().anyMatch(o -> o instanceof Map<?, ?> c && from.equals(str(c.get("name"))));
            String look = str(old.get("look"));
            if (!listed && !look.isEmpty()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", from);
                one.put("appearance", look);
                list.add(one);
                scenes.put("cast", list);
                write(scenesFile, mapper, scenes);
            }
        }
        return needsSheet;
    }

    /** 주인공 시트 ↔ 조연 시트. 새 주인공 시트가 없으면 true. */
    private static boolean swapSheets(Path dir, String from, String to) throws IOException {
        Path sheets = dir.resolve("sheets");
        Files.createDirectories(sheets);
        String a = RunArt.castSheetStem(from);
        String b = RunArt.castSheetStem(to);
        Path heroPng = dir.resolve("sheet.png");
        Path heroSpec = dir.resolve("sheet_spec.json");
        Path aPng = sheets.resolve(a + ".png");
        Path aSpec = sheets.resolve(a + ".spec.json");
        Path bPng = sheets.resolve(b + ".png");
        Path bSpec = sheets.resolve(b + ".spec.json");

        /* 새 주인공 시트를 먼저 비켜 둔다 — 옛 주인공 시트를 그 이름 자리로 옮기기 전에 */
        Path tmpPng = null;
        Path tmpSpec = null;
        if (Files.isRegularFile(bPng)) {
            tmpPng = dir.resolve(".lead-sheet.png");
            Files.move(bPng, tmpPng, StandardCopyOption.REPLACE_EXISTING);
            if (Files.isRegularFile(bSpec)) {
                tmpSpec = dir.resolve(".lead-sheet.spec.json");
                Files.move(bSpec, tmpSpec, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (Files.isRegularFile(heroPng)) {
            Files.move(heroPng, aPng, StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.isRegularFile(heroSpec)) {
            Files.move(heroSpec, aSpec, StandardCopyOption.REPLACE_EXISTING);
        }
        /* 옛 시트 판(「이전 시트」)은 그 사람 것이다 — 주인공 자리를 내주면 같이 맡겨 두고, 되찾으면 돌려놓는다 */
        Path keepA = dir.resolve(HERO_SHEETS).resolve(a);
        try (DirectoryStream<Path> found = Files.newDirectoryStream(dir, "sheet{.v*.png,_spec.v*.json}")) {
            for (Path one : found) {
                Files.createDirectories(keepA);
                Files.move(one, keepA.resolve(one.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path keepB = dir.resolve(HERO_SHEETS).resolve(b);
        if (Files.isDirectory(keepB)) {
            try (DirectoryStream<Path> found = Files.newDirectoryStream(keepB)) {
                for (Path one : found) {
                    Files.move(one, dir.resolve(one.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.deleteIfExists(keepB);
        }
        if (tmpPng != null) {
            Files.move(tmpPng, heroPng, StandardCopyOption.REPLACE_EXISTING);
            if (tmpSpec != null) {
                Files.move(tmpSpec, heroSpec, StandardCopyOption.REPLACE_EXISTING);
            }
            return false;
        }
        return true;
    }

    /**
     * 처음부터 주인공이 아니었던 사람의 「설명」 — 그 사람 카드에 이미 적힌 칸을 이름표와 함께 잇는다.
     * 새 글을 짓지 않는다.
     */
    static String describe(Map<String, Object> person) {
        List<String> lines = new ArrayList<>();
        for (String[] kv : new String[][]{{"role", "역할"}, {"look", "생김새"}, {"gap", "갭"},
                {"personality", "성격"}, {"situation", "처지"}, {"tie", "관계"}, {"wants", "원하거나 숨기는 것"}}) {
            String v = str(person.get(kv[0]));
            if (!v.isEmpty()) {
                lines.add(kv[1] + ": " + v);
            }
        }
        return String.join("\n", lines);
    }

    /* ---- 바닥 ---- */

    static List<String> castNames(Path dir, ObjectMapper mapper) {
        Path file = dir.resolve("cast.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<Map<String, Object>> cast = mapper.readValue(file.toFile(),
                    new TypeReference<List<Map<String, Object>>>() { });
            return cast.stream().map(c -> str(c.get("name"))).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 파일 안 글을 바꾼다. 바뀌었으면 true. 없거나 JSON 이 아니면 그냥 둔다. */
    private static boolean rewrite(Path file, ObjectMapper mapper, List<NameSwap.Pair> pairs) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        Object before;
        try {
            before = mapper.readValue(file.toFile(), Object.class);
        } catch (IOException e) {
            return false;
        }
        Object after = NameSwap.replaceIn(before, pairs, SKIP);
        if (after.equals(before)) {
            return false;
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), after);
        return true;
    }

    private static Map<String, Object> readMap(Path file, ObjectMapper mapper) throws IOException {
        return mapper.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private static void write(Path file, ObjectMapper mapper, Object value) throws IOException {
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    private static Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Object emptyOf(String key) {
        return switch (key) {
            case "photos" -> List.of();
            case "fields", "card" -> Map.of();
            default -> "";
        };
    }

    /** 맡겨 둔 사진 가운데 아직 있는 것만 — 장면을 나눈 뒤에는 사진을 지운다(dropPhotos). */
    private static List<String> existing(Object photos) {
        if (!(photos instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(p -> Files.isRegularFile(Path.of(p))).toList();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }
}
