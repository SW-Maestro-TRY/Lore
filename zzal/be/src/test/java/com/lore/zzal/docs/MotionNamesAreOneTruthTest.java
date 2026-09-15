package com.lore.zzal.docs;

import com.lore.zzal.generation.HatchPostures;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 행동 16칸이 <b>이름과 그림까지 한 벌인가</b> — 카탈로그 · 설정 · 자세 매핑 · 프롬프트.
 *
 * <h3>★★ 왜 필요한가 — 어긋나도 아무 소리가 안 난다</h3>
 * 한 칸을 설명하는 곳이 넷인데, <b>서로를 안 본다.</b>
 * <ul>
 *   <li>{@code MotionCatalog} — 그림 <b>파일 이름</b>이자 후처리에 {@code --keys} 로 넘기는 이름</li>
 *   <li>{@code application.yml} 의 {@code app.zzal.hatch.states.v1} — 그 버전이 내놓기로 한 파일 이름</li>
 *   <li>{@code pipeline/v1/postures.txt} — 그 칸을 <b>어떻게 정렬할지</b>(서기·웅크림·눕기)</li>
 *   <li>{@code prompt/v1/grid.txt}·{@code grid2.txt} — 그 칸에 <b>무엇을 그릴지</b></li>
 * </ul>
 * 앞의 셋은 이름을 말하고 마지막 하나는 그림을 말한다. <b>이름이 바뀌어도 프롬프트는 안 따라간다</b> —
 * 실제로 카탈로그가 16종 교체판으로 바뀐 뒤 옛 프롬프트가 그대로 쓰이는 판이 있었고,
 * 그때 16칸 중 10칸이 "이름은 새것, 그림은 옛것" 이었다. 오류도 404 도 안 났다.
 * 빌드·배포·부화가 전부 성공하고, 화면을 눈으로 봐야만 드러난다.
 *
 * <h3>★ 순서까지 본다</h3>
 * 이 순서가 곧 격자 칸 번호다. 한 칸만 밀려도 여덟 칸이 전부 다른 자세로 저장된다.
 *
 * <h3>★ 프롬프트 쪽은 손으로 적어 둔 표와 맞춘다</h3>
 * 넷을 서로 대조만 하면 <b>넷이 같이 틀렸을 때</b> 아무도 못 잡는다. 그래서 검수를 마친 조합을
 * 이 시험 안에 독립된 기준으로 적어 둔다. 그릴 것을 바꾸려면 이 표도 같이 고쳐야 한다 —
 * 그 손길이 곧 "그림을 바꿨다" 는 표시다.
 */
@DisplayName("기본 행동 16칸 — 이름(카탈로그·설정·자세 매핑)과 그림(프롬프트)이 한 벌")
class MotionNamesAreOneTruthTest {

    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    /** {@code v1: base,eat,...} 처럼 적힌 줄. */
    private static final Pattern STATES = Pattern.compile("(?m)^\\s*v1:\\s*(\\S+)\\s*$");

    /** 프롬프트가 한 칸씩 시작하는 줄 — {@code 1-2 NEUTRAL: ...}. 앞의 두 수가 칸 번호다. */
    private static final Pattern CELL = Pattern.compile("(?m)^(\\d+)-(\\d+) ([A-Z][A-Z ]*[A-Z]):");

    /**
     * 검수를 마친 16칸 — <b>카탈로그 key ↔ 프롬프트 칸 제목</b>.
     *
     * ★ 여기가 이 시험의 유일한 독립 기준이다. 카탈로그·설정·자세 매핑은 서로 대조할 수 있지만
     *   "그 칸에 무엇을 그리는가" 는 프롬프트 안에만 있어서, 대조할 짝이 없으면 조용히 낡는다.
     */
    private static final List<String> LAYER1_CELLS = List.of(
            "base=NEUTRAL", "eat=EATING", "joy=HAPPY", "sad=UNHAPPY",
            "sick=SICK", "pet=PETTED", "hello=GREETING", "sleep=SLEEPING");

    private static final List<String> LAYER2_CELLS = List.of(
            "eat_rice=EATING A MEAL", "eat_snack=EATING A SNACK", "sweep=SWEEPING", "wash=WASHING",
            "reply=ANSWERING", "petted=BEING PETTED", "startle=STARTLED", "wake_up=YAWNING AWAKE");

    @Test
    @DisplayName("★★ app.zzal.hatch.states.v1 가 카탈로그 16종과 순서까지 같다")
    void hatchStatesMatchCatalog() throws IOException {
        String configured = configuredStates();

        assertThat(Arrays.asList(configured.split(",")))
                .as("설정과 카탈로그가 어긋나도 정상 경로에서는 아무 소리가 안 난다 — 여기가 유일하게 잡는 자리다")
                .containsExactlyElementsOf(CATALOG.basicKeys());
    }

    @Test
    @DisplayName("★★ pipeline/v1/postures.txt 의 칸 이름이 1층·2층과 정확히 같다")
    void posturesMatchCatalog() {
        HatchPostures postures = new HatchPostures();

        assertThat(keysOf(postures.forStep("v1", "grid")))
                .as("격자 1장의 칸 이름")
                .containsExactlyElementsOf(keysOfLayer(MotionLayer.BASIC_1));
        assertThat(keysOf(postures.forStep("v1", "grid2")))
                .as("격자 2장의 칸 이름")
                .containsExactlyElementsOf(keysOfLayer(MotionLayer.BASIC_2));
    }

    @Test
    @DisplayName("★★ 프롬프트가 그리는 16칸이 카탈로그 16종과 이름·순서까지 짝이 맞는다")
    void promptCellsMatchCatalog() throws IOException {
        // 카탈로그가 새 이름으로 바뀌었는데 프롬프트가 옛 그림을 그리고 있으면 여기서 빨개진다.
        // 그 어긋남은 예외도 404 도 안 내고, 다 구워진 그림을 눈으로 봐야만 드러난다.
        assertCells("zzal/prompt/v1/grid.txt", MotionLayer.BASIC_1, LAYER1_CELLS);
        assertCells("zzal/prompt/v1/grid2.txt", MotionLayer.BASIC_2, LAYER2_CELLS);
    }

    @Test
    @DisplayName("★★ 서 있지 않은 칸은 프롬프트도 그렇게 그린다 — 정렬 기준과 그림이 갈리면 발이 뜬다")
    void nonStandingCellsAreDrawnThatWay() throws IOException {
        // 후처리는 웅크린 칸·누운 칸을 <b>본체</b> 기준으로 맞춘다. 프롬프트가 그 칸을 서 있게 그리면
        // 정렬 기준이 통째로 어긋나는데, 파이썬도 자바도 아무 소리를 안 낸다.
        assertPostureIsDrawn("zzal/prompt/v1/grid.txt", "grid", LAYER1_CELLS);
        assertPostureIsDrawn("zzal/prompt/v1/grid2.txt", "grid2", LAYER2_CELLS);
    }

    @Test
    @DisplayName("★ 마이그레이션 판 번호가 겹치지 않는다 — 겹치면 서버가 아예 안 뜬다")
    void migrationVersionsAreUnique() throws IOException {
        // Flyway 는 classpath:db/migration 한 곳만 보고, 빌드가 모듈들의 resources 를 그 한 곳으로 합친다.
        // 같은 번호가 둘이면 "Found more than one migration with version N" 으로 기동을 거부하는데,
        // 빌드도 시험도 전부 통과한 뒤 기동에서만 드러난다.
        Map<Integer, List<String>> byVersion = migrationFiles().stream()
                .collect(Collectors.groupingBy(MotionNamesAreOneTruthTest::versionOf));

        assertThat(byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1).toList())
                .as("같은 판 번호의 마이그레이션이 둘 이상이면 서버가 안 뜬다")
                .isEmpty();
    }

    // ── 거들기 ───────────────────────────────────────────────────────────

    private static List<String> keysOf(String posturesSpec) {
        // "base=standing,eat=standing,…" → [base, eat, …]
        return Arrays.stream(posturesSpec.split("\\s*,\\s*"))
                .filter(s -> !s.isBlank())
                .map(s -> s.split("=")[0].trim())
                .toList();
    }

    /** 그 프롬프트의 칸 제목들이 표와 같은가 — 칸 번호(1-2, 3-4 …)와 카탈로그 순서까지. */
    private void assertCells(String promptPath, MotionLayer layer, List<String> expected) throws IOException {
        List<String> keys = keysOfLayer(layer);
        assertThat(keys).as("한 층은 8칸이다").hasSize(8);
        assertThat(expected.stream().map(e -> e.split("=")[0]).toList())
                .as("이 시험의 표가 카탈로그와 어긋났다 — 표를 고칠지 카탈로그를 고칠지 정하세요")
                .containsExactlyElementsOf(keys);

        List<String> cells = new java.util.ArrayList<>();
        Matcher m = CELL.matcher(resource(promptPath));
        int n = 1;
        while (m.find()) {
            assertThat(m.group(1) + "-" + m.group(2))
                    .as("%s 의 칸 번호가 1-2, 3-4 … 순서가 아니다".formatted(promptPath))
                    .isEqualTo((n * 2 - 1) + "-" + (n * 2));
            cells.add(m.group(3));
            n++;
        }

        assertThat(cells)
                .as("""
                        %s 가 그리는 칸이 카탈로그 8종과 짝이 안 맞습니다.
                          그림을 바꾼 것이면 이 시험의 표(LAYER1_CELLS·LAYER2_CELLS)도 같이 고치세요.
                          이름만 바뀐 것이면 프롬프트가 아직 옛 그림을 그리고 있습니다.""".formatted(promptPath))
                .containsExactlyElementsOf(expected.stream().map(e -> e.split("=")[1]).toList());
    }

    /** 자세 매핑이 서기가 아닌 칸은, 프롬프트도 그 자세로 그리라고 적혀 있어야 한다. */
    private void assertPostureIsDrawn(String promptPath, String step, List<String> cells) throws IOException {
        String prompt = resource(promptPath);
        Map<String, String> posture = Arrays.stream(new HatchPostures().forStep("v1", step).split("\\s*,\\s*"))
                .map(e -> e.split("="))
                .collect(Collectors.toMap(e -> e[0].trim(), e -> e[1].trim()));

        for (String cell : cells) {
            String key = cell.split("=")[0];
            String title = cell.split("=")[1];
            String kind = posture.get(key);
            if ("standing".equals(kind)) {
                continue;
            }
            String body = prompt.substring(prompt.indexOf(title + ":"));
            List<String> words = "lying".equals(kind)
                    ? List.of("lies on its side", "lying down")
                    : List.of("crouch", "sitting in", "sits");
            assertThat(words).anySatisfy(w -> assertThat(body).containsIgnoringCase(w));
        }
    }

    private static List<String> keysOfLayer(MotionLayer layer) {
        return CATALOG.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String configuredStates() throws IOException {
        String yml = Files.readString(repoRoot().resolve("apps/api/src/main/resources/application.yml"));
        Matcher m = STATES.matcher(yml);
        assertThat(m.find()).as("application.yml 에 app.zzal.hatch.states.v1 줄이 없습니다").isTrue();
        return m.group(1);
    }

    private List<String> migrationFiles() throws IOException {
        try (Stream<Path> files = Files.walk(repoRoot())) {
            return files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .filter(p -> p.getParent().toString().endsWith("db/migration"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .map(p -> p.getFileName().toString())
                    .toList();
        }
    }

    private static int versionOf(String fileName) {
        return Integer.parseInt(fileName.substring(1, fileName.indexOf("__")));
    }

    /** 시험은 레포 어디서 돌든 루트를 찾아야 한다. */
    private Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("레포 루트를 못 찾았습니다");
        }
        return p;
    }
}
