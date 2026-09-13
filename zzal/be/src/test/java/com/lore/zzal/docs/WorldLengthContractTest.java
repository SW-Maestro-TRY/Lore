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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 세계관 한 줄의 상한이 <b>세 곳에서 같은 값</b>인지 지킨다.
 *
 * <h3>★ 무엇이 잘못됐었나</h3>
 * 같은 값의 상한이 세 곳에 따로 적혀 있었고 전부 달랐다.
 * <ul>
 *   <li>캐릭터 정보 요청 — {@code @Size(max = 100)}</li>
 *   <li>규칙 상수 — {@code WORLD_MAX_CHARS = 40}</li>
 *   <li>DB 칸 — {@code varchar(40)}</li>
 * </ul>
 *
 * <p>그래서 41~100자를 보내면 <b>검증은 통과하고 저장에서 터진다.</b> 사용자에게는
 * "세계관이 너무 깁니다" 가 아니라 그냥 500 이 간다 — 무엇을 고쳐야 하는지 알 길이 없고,
 * 짧게 줄이면 되는 입력인데 앱이 고장 난 것처럼 보인다.
 *
 * <h3>★ 왜 경계값만이 아니라 DB 까지 보나</h3>
 * 검증만 시험하면 <b>이 버그가 그대로 통과한다</b> — 망가져 있던 시절에도 100자는 검증을
 * 통과했다. 터지는 곳은 DB 라서, 칸 길이까지 같은 시험 안에서 맞춰 봐야 다시 어긋났을 때
 * 잡힌다. 서버를 띄우지 않고 마이그레이션 파일을 읽는 것은 DB 없이도 매번 돌게 하기 위해서다.
 */
@DisplayName("세계관 한 줄 — 상한이 세 곳에서 같다")
class WorldLengthContractTest {

    /** 베이스라인이 칸을 만들 때의 모습 — {@code world character varying(40),} */
    private static final Pattern CREATE = Pattern.compile("\\bworld\\s+character varying\\((\\d+)\\)");

    /** 나중에 넓힐 때의 모습 — {@code ALTER COLUMN world TYPE character varying(100)} */
    private static final Pattern ALTER =
            Pattern.compile("ALTER COLUMN\\s+world\\s+TYPE\\s+character varying\\((\\d+)\\)",
                    Pattern.CASE_INSENSITIVE);

    /** 파일 이름 앞머리의 판 번호 — {@code V12__...} 의 12. */
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
        String world = "가".repeat(ZzalRules.WORLD_MAX_CHARS);

        assertThat(violatedFields(new PetRequests.Character("여울", null, null, world, null, null, null)))
                .as("상한 길이는 받아 줘야 합니다")
                .isEmpty();
        assertThat(violatedFields(new PetRequests.PersonalityChoice(null, null, world)))
                .as("성격 등록도 같은 상한이어야 합니다")
                .isEmpty();
    }

    @Test
    @DisplayName("★ 상한 +1 은 검증에서 거절한다 — DB 까지 가서 500 이 나면 안 된다")
    void oneOverTheLimitIsRejected() {
        String world = "가".repeat(ZzalRules.WORLD_MAX_CHARS + 1);

        assertThat(violatedFields(new PetRequests.Character("여울", null, null, world, null, null, null)))
                .as("상한을 넘겼는데 검증을 통과하면, 터지는 곳은 DB 이고 사용자는 500 만 봅니다")
                .contains("world");
        assertThat(violatedFields(new PetRequests.PersonalityChoice(null, null, world)))
                .as("상한을 넘겼는데 검증을 통과하면, 터지는 곳은 DB 이고 사용자는 500 만 봅니다")
                .contains("world");
    }

    @Test
    @DisplayName("★ DB 칸도 같은 길이다 — 여기가 어긋나 있어서 검증을 통과한 입력이 저장에서 터졌다")
    void databaseColumnMatchesTheRule() throws Exception {
        assertThat(latestColumnLengthInMigrations())
                .as("마이그레이션의 world 칸 길이가 WORLD_MAX_CHARS 와 달라, 검증을 통과한 입력이 저장에서 터집니다")
                .isEqualTo(ZzalRules.WORLD_MAX_CHARS);

        assertThat(ZzalPet.class.getDeclaredField("world").getAnnotation(Column.class).length())
                .as("엔티티가 말하는 칸 길이도 같아야 합니다")
                .isEqualTo(ZzalRules.WORLD_MAX_CHARS);
    }

    private Set<String> violatedFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 마이그레이션을 <b>판 번호 순서대로</b> 읽어, {@code zzal_pet.world} 에 마지막으로 정해진
     * 길이를 돌려준다. 파일 이름 순서로 읽으면 {@code V10} 이 {@code V9} 앞에 와서 틀린다.
     */
    private int latestColumnLengthInMigrations() throws IOException {
        Path dir = repoRoot().resolve("apps/api/src/main/resources/db/migration");
        List<Path> ordered;
        try (Stream<Path> files = Files.list(dir)) {
            ordered = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparingInt(WorldLengthContractTest::versionOf))
                    .toList();
        }

        int length = -1;
        for (Path file : ordered) {
            String sql = Files.readString(file);
            Integer found = lastMatch(CREATE, sql);
            if (found != null) {
                length = found;
            }
            found = lastMatch(ALTER, sql);
            if (found != null) {
                length = found;
            }
        }
        assertThat(length).as("마이그레이션 어디에도 zzal_pet.world 칸이 없습니다").isNotEqualTo(-1);
        return length;
    }

    private static Integer lastMatch(Pattern pattern, String sql) {
        Matcher m = pattern.matcher(sql);
        Integer last = null;
        while (m.find()) {
            last = Integer.parseInt(m.group(1));
        }
        return last;
    }

    private static int versionOf(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        if (!m.find()) {
            throw new IllegalStateException("판 번호가 없는 마이그레이션입니다: " + file.getFileName());
        }
        return Integer.parseInt(m.group(1));
    }

    /** 테스트는 레포 어디서 돌든 루트를 찾아야 한다. */
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
