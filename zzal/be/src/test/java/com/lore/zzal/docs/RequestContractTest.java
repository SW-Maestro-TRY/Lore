package com.lore.zzal.docs;

import com.lore.zzal.admin.dto.AdminRequests;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.feedback.dto.FeedbackRequests;
import com.lore.zzal.game.GameKind;
import com.lore.zzal.game.dto.GameRequests;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.pet.dto.PetRequests;
import com.lore.zzal.profile.dto.ProfileRequests;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요청 본문의 <b>길이·범위 경계</b> — 화면이 400 을 받는가 500 을 받는가(M-15 · M-27).
 *
 * <h3>★ 왜 필요한가</h3>
 * 서비스는 옳은 예외를 던지는데 <b>그 앞의 문(@Valid)</b> 이 어긋나 있으면, 잘못된 값이 DB 까지
 * 들어가거나 사용자가 <b>500</b> 을 본다. 컨트롤러를 참조하는 시험이 트리 전체에 0건이라
 * 이 문은 한 번도 점검된 적이 없었다.
 *
 * <h3>★ 왜 MockMvc 가 아니라 검증기를 직접 부르나</h3>
 * 경계는 <b>값마다 두 개씩</b>(안·밖)이라 스무 가지가 넘는다. 그걸 전부 HTTP 로 태우면 느리고,
 * 정작 보려는 것(어떤 값이 통과하고 어떤 값이 막히나)이 배관에 묻힌다. 여기서는 규칙만 재고,
 * <b>그 규칙이 실제 주소에 걸려 있는지</b>는 통합 시험이 따로 본다({@code it/HttpContractIT}).
 */
@DisplayName("요청 본문 계약 — 경계 안은 통과, 밖은 거절")
class RequestContractTest {

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

    private static boolean accepted(Object request) {
        return validator.validate(request).isEmpty();
    }

    private static String chars(int n) {
        return "가".repeat(n);
    }

    @Nested
    @DisplayName("펫")
    class Pet {

        @Test
        @DisplayName("★ 이름 — 1자·12자는 통과, 13자·빈 값·공백뿐은 거절")
        void name() {
            assertThat(accepted(character("여"))).isTrue();
            assertThat(accepted(character(chars(ZzalRules.NAME_MAX_CHARS)))).isTrue();
            assertThat(accepted(character(chars(ZzalRules.NAME_MAX_CHARS + 1)))).isFalse();
            assertThat(accepted(character(""))).isFalse();
            assertThat(accepted(character("   "))).isFalse();
            assertThat(accepted(character(null))).isFalse();
        }

        @Test
        @DisplayName("★ 그림 키 — 300자는 통과, 301자·빈 값은 거절")
        void imageKey() {
            assertThat(accepted(new PetRequests.Draft("a".repeat(300)))).isTrue();
            assertThat(accepted(new PetRequests.Draft("a".repeat(301)))).isFalse();
            assertThat(accepted(new PetRequests.Draft(""))).isFalse();
            assertThat(accepted(new PetRequests.Draft(null))).isFalse();
        }

        @Test
        @DisplayName("★★ 세계관은 <b>부르는 곳마다 길이가 다르다</b> — 이름 지을 때 100자, 성격 화면에서 40자")
        void worldHasTwoDifferentLimits() {
            assertThat(accepted(new PetRequests.Character("여울", null, null, chars(100), null, null, null))).isTrue();
            assertThat(accepted(new PetRequests.Character("여울", null, null, chars(101), null, null, null))).isFalse();

            assertThat(accepted(new PetRequests.PersonalityChoice(
                    null, List.of(Personality.GENTLE), chars(ZzalRules.WORLD_MAX_CHARS)))).isTrue();
            assertThat(accepted(new PetRequests.PersonalityChoice(
                    null, List.of(Personality.GENTLE), chars(ZzalRules.WORLD_MAX_CHARS + 1)))).isFalse();
        }

        @Test
        @DisplayName("돌보기 · 배경 · 설정 — 값이 없으면 거절(서비스까지 안 간다)")
        void requiredFields() {
            assertThat(accepted(new PetRequests.Care(CareAction.FEED))).isTrue();
            assertThat(accepted(new PetRequests.Care(null))).isFalse();

            assertThat(accepted(new PetRequests.Background("room"))).isTrue();
            assertThat(accepted(new PetRequests.Background(""))).isFalse();
            assertThat(accepted(new PetRequests.Background("a".repeat(33)))).isFalse();

            assertThat(accepted(new PetRequests.Settings(true))).isTrue();
            assertThat(accepted(new PetRequests.Settings(null))).isFalse();
        }

        private PetRequests.Character character(String name) {
            return new PetRequests.Character(name, null, null, null, null, null, null);
        }
    }

    @Nested
    @DisplayName("채팅")
    class Chat {

        @Test
        @DisplayName("★ 답 — 1자·40자는 통과, 41자·빈 값·공백뿐·없음은 거절")
        void answer() {
            assertThat(accepted(new ChatRequests.Answer("응"))).isTrue();
            assertThat(accepted(new ChatRequests.Answer(chars(ZzalRules.CHAT_MAX_CHARS)))).isTrue();
            assertThat(accepted(new ChatRequests.Answer(chars(ZzalRules.CHAT_MAX_CHARS + 1)))).isFalse();
            assertThat(accepted(new ChatRequests.Answer(""))).isFalse();
            assertThat(accepted(new ChatRequests.Answer("   "))).isFalse();
            assertThat(accepted(new ChatRequests.Answer(null))).isFalse();
        }
    }

    @Nested
    @DisplayName("미니게임")
    class Game {

        @Test
        @DisplayName("★★ 달리기 — 0 · 30000 · 60000 은 통과, -1 · 60001 · 없음은 거절 (M-13)")
        void runFinishBounds() {
            assertThat(accepted(new GameRequests.Finish(0L))).isTrue();
            assertThat(accepted(new GameRequests.Finish(29_999L))).isTrue();
            assertThat(accepted(new GameRequests.Finish(30_000L))).isTrue();
            assertThat(accepted(new GameRequests.Finish(30_001L))).isTrue();
            assertThat(accepted(new GameRequests.Finish(60_000L))).isTrue();

            assertThat(accepted(new GameRequests.Finish(-1L))).isFalse();
            assertThat(accepted(new GameRequests.Finish(60_001L))).isFalse();
            assertThat(accepted(new GameRequests.Finish(null))).isFalse();
        }

        @Test
        @DisplayName("★ 서비스 시험이 쓰는 70,000 은 HTTP 로는 들어올 수 없는 값이다 — 운영과 다른 길을 통과하고 있었다")
        void seventyThousandCannotArriveOverHttp() {
            assertThat(accepted(new GameRequests.Finish(70_000L))).isFalse();
        }

        @Test
        @DisplayName("시작 · 좌우 고르기 — 값이 없으면 거절")
        void requiredFields() {
            assertThat(accepted(new GameRequests.Start(GameKind.RUN))).isTrue();
            assertThat(accepted(new GameRequests.Start(null))).isFalse();
            assertThat(accepted(new GameRequests.Guess(GameRequests.Side.LEFT))).isTrue();
            assertThat(accepted(new GameRequests.Guess(null))).isFalse();
        }
    }

    @Nested
    @DisplayName("후기")
    class Feedback {

        @Test
        @DisplayName("★ 별점 — 1·5 는 통과, 0·6 은 거절")
        void rating() {
            assertThat(accepted(submit(1, null))).isTrue();
            assertThat(accepted(submit(5, null))).isTrue();
            assertThat(accepted(submit(0, null))).isFalse();
            assertThat(accepted(submit(6, null))).isFalse();
        }

        @Test
        @DisplayName("★ 자유 글 — 500자는 통과, 501자는 거절(저장 칸이 text 라 막지 않으면 수 MB 가 들어간다)")
        void text() {
            assertThat(accepted(submit(4, chars(499)))).isTrue();
            assertThat(accepted(submit(4, chars(500)))).isTrue();
            assertThat(accepted(submit(4, chars(501)))).isFalse();
        }

        private FeedbackRequests.Submit submit(int rating, String text) {
            return new FeedbackRequests.Submit(rating, List.of(), text);
        }
    }

    @Nested
    @DisplayName("관리자 업로드")
    class AdminUpload {

        @Test
        @DisplayName("★★ 빈 후보 목록은 문에서 막힌다 — 여기가 뚫리면 서비스에서 500 이었다 (M-27)")
        void emptyCandidateListIsRejected() {
            assertThat(accepted(new AdminRequests.Upload(List.of()))).isFalse();
            assertThat(accepted(new AdminRequests.Upload(null))).isFalse();
        }

        @Test
        @DisplayName("★ 1판은 통과, 7판까지 통과, 8판은 거절")
        void perRoundBounds() {
            assertThat(accepted(new AdminRequests.Upload(candidates(1)))).isTrue();
            assertThat(accepted(new AdminRequests.Upload(candidates(7)))).isTrue();
            assertThat(accepted(new AdminRequests.Upload(candidates(8)))).isFalse();
        }

        @Test
        @DisplayName("★ 판 안쪽도 본다 — 그림 키가 비어 있으면 목록 길이가 맞아도 거절")
        void candidatesAreValidatedToo() {
            assertThat(accepted(new AdminRequests.Upload(
                    List.of(new AdminRequests.Candidate("", null, null))))).isFalse();
            assertThat(accepted(new AdminRequests.Upload(
                    List.of(new AdminRequests.Candidate("a".repeat(301), null, null))))).isFalse();
        }

        private List<AdminRequests.Candidate> candidates(int n) {
            return java.util.stream.IntStream.range(0, n)
                    .mapToObj(i -> new AdminRequests.Candidate("images/zzal/tmp/%d.webp".formatted(i), null, null))
                    .toList();
        }
    }

    @Nested
    @DisplayName("프로필")
    class Profile {

        @Test
        @DisplayName("★ 여섯 칸 — 전부 비어도 통과(부분 갱신), 20자·40자 상한을 넘으면 거절")
        void patchFields() {
            assertThat(accepted(new ProfileRequests.Patch(null, null, null, null, null, null))).isTrue();
            assertThat(accepted(new ProfileRequests.Patch(chars(20), chars(20), chars(20), chars(20), chars(20), chars(40)))).isTrue();
            assertThat(accepted(new ProfileRequests.Patch(chars(21), null, null, null, null, null))).isFalse();
            assertThat(accepted(new ProfileRequests.Patch(null, null, null, null, null, chars(41)))).isFalse();
        }
    }
}
