package com.lore.zzal.docs;

import com.lore.zzal.generation.HatchPostures;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionLayer;
import com.lore.zzal.motion.MotionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
 * 기본 행동 16종의 이름이 <b>세 곳에서 같은가</b> — 카탈로그 · 설정 · 자세 매핑.
 *
 * <h3>★ 왜 필요한가 — 어긋나도 아무 소리가 안 난다</h3>
 * 이름을 정하는 곳이 셋이다.
 * <ul>
 *   <li>{@code MotionCatalog} — 화면이 그림 주소를 조립할 때 쓰는 이름이자, 후처리에 {@code --keys} 로 넘기는 이름</li>
 *   <li>{@code application.yml} 의 {@code app.zzal.hatch.states.v4} — 격자 한 장 폴백에서 기대하는 파일 이름</li>
 *   <li>{@code pipeline/v4/postures.txt} — 칸마다 어떻게 정렬할지를 이름으로 찾는다</li>
 * </ul>
 * 셋이 어긋나면 <b>빌드·배포·부화가 전부 성공</b>한다. 드러나는 것은 화면에 빈 그림이 뜰 때이거나,
 * 후처리가 엉뚱한 기준으로 정렬해 발이 떠 있는 그림이 나올 때다.
 *
 * <h3>★ 순서까지 본다</h3>
 * 이 순서가 곧 격자 칸 번호다. 한 칸만 밀려도 여덟 칸이 전부 다른 자세로 저장된다.
 */
@DisplayName("기본 행동 16종의 이름 — 카탈로그·설정·자세 매핑이 한 벌")
class MotionNamesAreOneTruthTest {

    private static final MotionCatalog CATALOG = new MotionCatalog("", "", "v1");

    /** {@code v4: base,eat,...} 처럼 적힌 줄. */
    private static final Pattern STATES_V4 = Pattern.compile("(?m)^\\s*v4:\\s*(\\S+)\\s*$");

    @Test
    @DisplayName("★★ app.zzal.hatch.states.v4 가 카탈로그 16종과 순서까지 같다")
    void hatchStatesV4MatchesCatalog() throws IOException {
        String configured = configuredStatesV4();

        assertThat(Arrays.asList(configured.split(",")))
                .as("설정과 카탈로그가 어긋나도 정상 경로에서는 아무 소리가 안 난다 — 여기가 유일하게 잡는 자리다")
                .containsExactlyElementsOf(CATALOG.basicKeys());
    }

    @Test
    @DisplayName("★★ pipeline/v4/postures.txt 의 칸 이름이 1층·2층과 정확히 같다")
    void posturesMatchCatalog() {
        HatchPostures postures = new HatchPostures();

        assertThat(keysOf(postures.forStep("v4", "grid")))
                .as("격자 1장의 칸 이름")
                .containsExactlyElementsOf(keysOfLayer(MotionLayer.BASIC_1));
        assertThat(keysOf(postures.forStep("v4", "grid2")))
                .as("격자 2장의 칸 이름")
                .containsExactlyElementsOf(keysOfLayer(MotionLayer.BASIC_2));
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

    private static List<String> keysOfLayer(MotionLayer layer) {
        return CATALOG.basic().stream().filter(m -> m.layer() == layer).map(MotionSpec::key).toList();
    }

    private String configuredStatesV4() throws IOException {
        String yml = Files.readString(repoRoot().resolve("apps/api/src/main/resources/application.yml"));
        Matcher m = STATES_V4.matcher(yml);
        assertThat(m.find()).as("application.yml 에 app.zzal.hatch.states.v4 줄이 없습니다").isTrue();
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
