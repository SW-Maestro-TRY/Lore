package com.lore.zzal.docs;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.pet.dto.PetRequests;
import jakarta.persistence.Column;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 말투·장르 칸의 상한이 <b>세 곳에서 같은 값</b>인지 지킨다 — 요청 검증 · 엔티티 · DB.
 *
 * <h3>★ 왜 또 쓰는가</h3>
 * 세계관 칸에서 이미 한 번 난 병이다({@code WorldLengthContractTest}). 상한이 세 곳에 따로 적혀
 * 있었고 전부 달라서, 중간 길이를 보내면 <b>검증은 통과하고 저장에서 터졌다.</b> 사용자에게는
 * "너무 깁니다" 가 아니라 그냥 500 이 간다 — 짧게 줄이면 되는 입력인데 앱이 고장 난 것처럼 보인다.
 * 새 칸을 더할 때마다 같은 병이 다시 생길 수 있으므로 같은 방식으로 못 박는다.
 *
 * <h3>★ 검증만 보면 이 버그가 그대로 통과한다</h3>
 * 망가져 있던 시절에도 상한 길이는 검증을 통과했다. 터지는 곳은 DB 라서 칸 길이까지 같은 시험
 * 안에서 맞춰 봐야 한다. 서버를 띄우지 않고 마이그레이션 파일을 읽는 것은 DB 없이도 매번 돌게 하기 위해서다.
 */
@DisplayName("말투·장르 — 상한이 세 곳에서 같다")
class ToneAndGenreLengthContractTest {

    /** {@code ADD COLUMN tone character varying(32)} 같은 줄. */
    private static final Pattern ADD_COLUMN =
            Pattern.compile("ADD COLUMN\\s+%s\\s+character varying\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    @DisplayName("상한 길이는 그대로 통과한다")
    void atTheLimitPasses() {
        assertThat(violatedFields(character("가".repeat(ZzalRules.TONE_MAX_CHARS),
                "나".repeat(ZzalRules.GENRE_MAX_CHARS))))
                .as("상한 길이는 받아 줘야 합니다")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 상한 +1 은 검증에서 거절한다 — DB 까지 가서 500 이 나면 안 된다")
    void oneOverTheLimitIsRejected() {
        assertThat(violatedFields(character("가".repeat(ZzalRules.TONE_MAX_CHARS + 1), null)))
                .contains("tone");
        assertThat(violatedFields(character(null, "나".repeat(ZzalRules.GENRE_MAX_CHARS + 1))))
                .contains("genre");
    }

    @Test
    @DisplayName("★ 엔티티 칸·DB 칸도 같은 길이다 — 여기가 어긋나면 검증을 통과한 입력이 저장에서 터진다")
    void entityAndDatabaseMatchTheRule() throws Exception {
        assertThat(ZzalPet.class.getDeclaredField("tone").getAnnotation(Column.class).length())
                .isEqualTo(ZzalRules.TONE_MAX_CHARS);
        assertThat(ZzalPet.class.getDeclaredField("genre").getAnnotation(Column.class).length())
                .isEqualTo(ZzalRules.GENRE_MAX_CHARS);

        assertThat(columnLengthInMigrations("tone")).isEqualTo(ZzalRules.TONE_MAX_CHARS);
        assertThat(columnLengthInMigrations("genre")).isEqualTo(ZzalRules.GENRE_MAX_CHARS);
    }

    @Test
    @DisplayName("★ 빈 줄은 없음으로 저장한다 — 화면이 \"없음\" 과 \"빈 줄\" 을 가르지 않아도 되게")
    void blankIsStoredAsAbsent() {
        ZzalPet pet = ZzalPet.draft(1L, "images/zzal/x.png", java.time.Instant.EPOCH);

        pet.character("여울", null, List.of(), null, "  ", "", java.time.Instant.EPOCH);

        assertThat(pet.getTone()).isNull();
        assertThat(pet.getGenre()).isNull();
    }

    private static PetRequests.Character character(String tone, String genre) {
        return new PetRequests.Character("여울", null, null, null, tone, genre, null);
    }

    private Set<String> violatedFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    /** 마이그레이션을 판 번호 순서대로 읽어 그 칸에 마지막으로 정해진 길이를 돌려준다. */
    private int columnLengthInMigrations(String column) throws IOException {
        Path dir = repoRoot().resolve("apps/api/src/main/resources/db/migration");
        List<Path> ordered;
        try (Stream<Path> files = Files.list(dir)) {
            ordered = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(ToneAndGenreLengthContractTest::versionOf))
                    .toList();
        }
        Pattern pattern = Pattern.compile(ADD_COLUMN.pattern().formatted(column), Pattern.CASE_INSENSITIVE);
        int length = -1;
        for (Path file : ordered) {
            Matcher m = pattern.matcher(Files.readString(file));
            while (m.find()) {
                length = Integer.parseInt(m.group(1));
            }
        }
        assertThat(length).as("마이그레이션 어디에도 zzal_pet.%s 칸이 없습니다", column).isNotEqualTo(-1);
        return length;
    }

    private static int versionOf(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        if (!m.find()) {
            throw new IllegalStateException("판 번호가 없는 마이그레이션입니다: " + file.getFileName());
        }
        return Integer.parseInt(m.group(1));
    }

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
