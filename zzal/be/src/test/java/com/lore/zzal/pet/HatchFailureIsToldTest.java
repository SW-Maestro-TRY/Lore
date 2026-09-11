package com.lore.zzal.pet;

import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 이름 짓는 동안 굽기가 실패하면 <b>죽는다. 대신 그 사실이 제대로 전해져야 한다</b>(1.9).
 *
 * <h3>★ 죽는 것 자체는 맞다</h3>
 * 이름은 그림 생성에 안 들어간다. 그래서 이름을 기다렸다 다시 굽는 것은
 * <b>돈을 늦게 쓸 뿐 이득이 없다</b>(상훈님 2026-09-11). 두 번 실패하면 거기서 끝난다.
 *
 * <h3>★★ 문제는 어떻게 알려지느냐였다</h3>
 * 그림을 올리는 순간부터 굽기 때문에, 이름을 짓는 <b>2~3분</b>(실측 2분 54초)이 굽기와 겹친다.
 * 그 사이에 조용히 실패하면 사용자는 이름을 다 짓고 제출하는 순간 이런 답을 받았다.
 *
 * <pre>409 ZZAL_PET_NOT_DRAFT  "이미 이름을 지은 아이예요"</pre>
 *
 * <b>사실과 정반대다.</b> 이름을 방금 처음 지은 사람에게 이미 지었다고 말하고,
 * 무슨 일이 일어났는지도 다음에 뭘 해야 하는지도 알 수 없다.
 *
 * <h3>★ 문구는 우리 탓으로 말한다</h3>
 * "얼굴이 안 보여서" · "품질이 낮아서" 처럼 <b>사용자의 그림을 탓하는 말을 쓰지 않는다</b>(자캐 커뮤니티 규범).
 */
@DisplayName("굽기 실패는 죽되, 사용자가 그 사실을 알 수 있어야 한다")
class HatchFailureIsToldTest {

    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");

    private ZzalPet draft() {
        return ZzalPet.draft(1L, "images/zzal/src", T0);
    }

    @Test
    @DisplayName("이름 전에 실패하면 초안도 FAILED 가 된다 — 기다렸다 다시 굽는 것은 이득이 없다")
    void draftDiesOnFailure() {
        ZzalPet pet = draft();

        pet.markHatchFailed();

        assertThat(pet.getPhase()).isEqualTo(PetPhase.FAILED);
        assertThat(pet.getDeathReason()).isEqualTo(DeathReason.HATCH_FAILED);
    }

    @Test
    @DisplayName("★★ 실패한 펫에게 쓸 오류는 '이미 이름을 지었다'가 아니다")
    void failedPetGetsItsOwnError() {
        // 두 코드가 갈려 있지 않으면 이름을 처음 지은 사람에게 사실과 반대로 말하게 된다.
        assertThat(ErrorCode.ZZAL_PET_HATCH_FAILED)
                .isNotEqualTo(ErrorCode.ZZAL_PET_NOT_DRAFT);
        assertThat(ErrorCode.ZZAL_PET_HATCH_FAILED.getDefaultMessage())
                .isNotEqualTo(ErrorCode.ZZAL_PET_NOT_DRAFT.getDefaultMessage());
    }

    @Test
    @DisplayName("★ 실패 문구가 사용자의 그림을 탓하지 않는다")
    void messageDoesNotBlameTheDrawing() {
        String message = ErrorCode.ZZAL_PET_HATCH_FAILED.getDefaultMessage();

        assertThat(message).contains("다시");                 // 다음에 할 일을 말한다
        assertThat(message).doesNotContain("품질");
        assertThat(message).doesNotContain("얼굴");
        assertThat(message).doesNotContain("나쁜");
        assertThat(message).doesNotContain("부적절");
    }

    @Nested
    @DisplayName("다시 올릴 수 있어야 한다")
    class CanStartOver {

        @Test
        @DisplayName("★★ FAILED 는 슬롯을 먹지 않는다 — 여기 들어가면 사용자가 갇힌다")
        void failedDoesNotOccupySlot() {
            // 슬롯이 하나인 사용자가 실패한 뒤 새 그림을 올릴 수 있어야 한다.
            // FAILED 가 이 목록에 들어가면 "자리가 없다" 로 막혀 아무것도 못 하게 된다.
            assertThat(PetPhase.OCCUPYING_SLOT).doesNotContain(PetPhase.FAILED);
            assertThat(PetPhase.OCCUPYING_SLOT)
                    .containsExactlyInAnyOrder(PetPhase.DRAFT, PetPhase.HATCHING, PetPhase.ALIVE);
        }

        @Test
        @DisplayName("실패한 펫은 초안 재사용 대상이 아니다 — 새 그림으로 시작한다")
        void failedIsNotReusedAsDraft() {
            ZzalPet pet = draft();
            pet.markHatchFailed();

            assertThat(pet.isDraft()).isFalse();
        }

        /**
         * ★★ 이름에는 중복 제약이 없다. <b>그게 의도다.</b>
         *
         * <h3>왜 이 시험이 있나</h3>
         * 실패했을 때 사용자가 친 이름을 서버가 들고 있지 않기로 했다(상훈님 결정 — 화면이 들고 있다가
         * 새 그림과 함께 다시 보낸다). 그러면 <b>방금 실패한 그 이름이 그대로 다시 들어온다.</b>
         * 여기에 "이름은 유일해야지" 를 걸면 <b>다시 만들 길이 막힌다.</b>
         *
         * <p>서로 다른 사용자가 같은 이름을 쓰는 것도 막으면 안 된다 —
         * 상훈님: <i>"사용자마다 이름이 같을 수 있어 충분히."</i> 흔한 이름을 먼저 쓴 사람이 가져가는 구조는
         * 자기 캐릭터에 제 이름을 못 붙이게 하는 것이다.
         *
         * <p>이 시험은 <b>제약이 없다는 사실</b>을 지킨다. 누가 유일 제약을 걸면 여기가 먼저 깨진다.
         */
        @Test
        @DisplayName("★★ 이름은 겹쳐도 된다 — 실패한 그 이름으로 다시 만들 수 있어야 한다")
        void sameNameCanBeUsedAgain() {
            ZzalPet failed = draft();
            failed.character("여울", null, List.of(Personality.GENTLE), null, T0);
            failed.markHatchFailed();

            // 같은 사람이 같은 이름으로 새 초안을 만든다
            ZzalPet again = draft();
            assertThatCode(() -> again.character("여울", null, List.of(Personality.GENTLE), null, T0))
                    .doesNotThrowAnyException();
            assertThat(again.getName()).isEqualTo("여울");

            // 다른 사람이 같은 이름을 쓰는 것도 막지 않는다
            ZzalPet other = ZzalPet.draft(999L, "images/zzal/other", T0);
            assertThatCode(() -> other.character("여울", null, List.of(Personality.GENTLE), null, T0))
                    .doesNotThrowAnyException();
        }

        /**
         * ★★ 위 시험은 객체 수준이라 <b>DB 제약을 못 잡는다.</b>
         *
         * 마이그레이션으로 {@code unique(name)} 을 걸어도 위 시험은 그대로 통과하고,
         * 막히는 것은 배포 뒤 실제 저장 시점이다. 그래서 마이그레이션 글을 직접 읽는다.
         *
         * ★ 지금 없는 것이 <b>우연이 아니라 결정</b>이다 — 걸면 두 길이 함께 막힌다.
         * 굽기가 실패한 사람이 같은 이름으로 다시 만드는 길과, 서로 다른 사용자가 같은 이름을 쓰는 길.
         */
        @Test
        @DisplayName("★★ 마이그레이션에도 이름 유일 제약이 없다 — 걸면 사용자가 갇힌다")
        void noUniqueConstraintInMigrations() throws IOException {
            Path dir = repoRoot().resolve("apps/api/src/main/resources/db/migration");
            // zzal_pet 과 name 과 unique 가 한 문장(;) 안에 함께 있으면 잡는다.
            Pattern suspicious = Pattern.compile("[^;]*unique[^;]*;", Pattern.DOTALL);

            try (Stream<Path> files = Files.list(dir)) {
                List<String> offenders = files
                        .filter(f -> f.toString().endsWith(".sql"))
                        .filter(f -> {
                            String sql = read(f).toLowerCase(Locale.ROOT);
                            return suspicious.matcher(sql).results()
                                    .map(java.util.regex.MatchResult::group)
                                    .anyMatch(stmt -> stmt.contains("zzal_pet") && stmt.contains("name"));
                        })
                        .map(f -> f.getFileName().toString())
                        .toList();

                assertThat(offenders)
                        .as("""
                                펫 이름에 유일 제약이 생겼습니다. 지우기 전에 두 가지를 먼저 정하세요 —
                                굽기가 실패한 사람이 같은 이름으로 다시 만들 수 있는가,
                                서로 다른 사용자가 같은 이름을 쓸 수 있는가. 둘 다 사용자가 갇히는 자리입니다.""")
                        .isEmpty();
            }
        }

        private String read(Path path) {
            try {
                return Files.readString(path);
            } catch (IOException e) {
                throw new IllegalStateException(path.toString(), e);
            }
        }

        /** 시험이 레포 어디서 돌든 루트를 찾는다. */
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
}
