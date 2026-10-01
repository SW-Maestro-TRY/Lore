package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 튜토리얼 졸업 축하 — "봤다" 를 <b>서버가</b> 기억하는가.
 *
 * <h3>★ 무엇을 고치려고 만든 주소인가</h3>
 * 축하 창을 봤다는 사실을 화면이 {@code sessionStorage} 에 두었더니 새 탭·재시작마다 <b>같은 창이
 * 다시 떴다</b>. 기억을 서버로 옮기고, 그 기억이 아이에게 붙는지를 여기서 본다.
 *
 * <h3>★ 왜 통합이어야 하나</h3>
 * 여기서 보려는 것 대부분을 서비스 시험이 원리적으로 못 본다.
 * <ul>
 *   <li><b>로그인 없이 부르면 막히는가</b> — 판정하는 것은 시큐리티 설정이지 서비스가 아니다</li>
 *   <li><b>정말 204 이고 본문이 비어 있는가</b> — 공통 봉투({@code ApiResponse})를 타지 않는
 *       유일한 모양이라, 반환형 하나만 잘못 써도 서비스 시험은 전부 초록이다</li>
 *   <li><b>적힌 시각이 조회 응답으로 나오는가</b> — 엔티티·DTO·컬럼 셋이 이어져야 보인다</li>
 *   <li><b>자는 아이·여행 중인 아이도 되는가</b> — 거절은 서비스가 아니라 공통 문({@code alive})이
 *       한다. 그 문을 거치게 바꿔도 단위 시험은 아무 말을 안 한다</li>
 * </ul>
 */
@ZzalIntegrationTest
@DisplayName("졸업 축하 봤음 — 서버가 기억하고, 몇 번을 불러도 같은 답이다")
class GraduationSeenIT extends ZzalItSupport {

    private static final String SEEN = "/api/zzal/v1/me/pets/%d/graduation-seen";
    private static final String DETAIL = "/api/zzal/v1/me/pets/%d";

    // ── 재료 ──────────────────────────────────────────────────────────────

    /** 튜토리얼까지 끝낸, 함께 지내는 중인 아이. 축하 창을 방금 본 사람의 자리다. */
    private Long graduatedPet(Long userId) {
        return aliveLayerTwoPet(userId).getId();
    }

    /** 아직 튜토리얼 중인 아이 — {@code clockStartedAt} 이 비어 있다. */
    private Long petStillInTutorial(Long userId) {
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now));
            created.character("여울", null, null, null, null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", now);
            return created;
        });
        assertThat(pet.isInTutorial()).as("이 재료는 튜토리얼 중이어야 뜻이 있다").isTrue();
        return pet.getId();
    }

    private MvcResult seen(Long userId, Long petId) throws Exception {
        return postAs(userId, SEEN.formatted(petId), null);
    }

    /**
     * DB 에 적힌 시각. <b>조회 API 를 안 거친다</b> — 조회는 정산·방문을 함께 돌려서,
     * 그 부수 효과가 여기서 보려는 값을 가릴 수 있다.
     */
    private Timestamp storedMoment(Long petId) {
        return jdbc.queryForObject(
                "select graduation_seen_at from zzal_pet where id = ?", Timestamp.class, petId);
    }

    // ══ 1. 적히는 길 ══════════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 처음 부르면 204(본문 없음)이고, 그다음 조회에 시각이 실려 온다")
    void theFirstCallStoresTheMomentAndTheDetailCarriesIt() throws Exception {
        Long userId = newUserId();
        Long petId = graduatedPet(userId);

        JsonNode before = getJson(userId, DETAIL.formatted(petId)).path("data");
        assertThat(before.hasNonNull("graduationSeenAt"))
                .as("아직 안 봤으면 null 이어야 한다 — 화면은 이 값으로 축하 창을 띄운다")
                .isFalse();

        MvcResult result = seen(userId, petId);

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString())
                .as("공통 봉투를 타지 않는 유일한 모양이다 — 본문이 있으면 계약이 깨진다")
                .isEmpty();

        assertThat(storedMoment(petId)).as("DB 에 시각이 찍혀야 한다").isNotNull();

        JsonNode after = getJson(userId, DETAIL.formatted(petId)).path("data");
        assertThat(after.hasNonNull("graduationSeenAt"))
                .as("엔티티·DTO·컬럼이 이어져야 여기가 초록이 된다")
                .isTrue();
        assertThat(Instant.parse(after.path("graduationSeenAt").asText()))
                .isEqualTo(storedMoment(petId).toInstant());
    }

    @Test
    @DisplayName("★★ 두 번째도 204 — 시각이 움직이지 않는다(몇 번을 불러도 같은 답)")
    void callingAgainAnswersTheSameAndNeverMovesTheMoment() throws Exception {
        Long userId = newUserId();
        Long petId = graduatedPet(userId);

        assertThat(seen(userId, petId).getResponse().getStatus()).isEqualTo(204);
        Timestamp first = storedMoment(petId);
        assertThat(first).isNotNull();

        // 시각이 "덮어쓰기" 로 밀리는지 보려면 사이에 시간이 흘러야 한다.
        Thread.sleep(1_100);

        MvcResult again = seen(userId, petId);
        assertThat(again.getResponse().getStatus()).as("이미 봤어도 거절이 아니라 204 다").isEqualTo(204);
        assertThat(again.getResponse().getContentAsString()).isEmpty();

        assertThat(storedMoment(petId))
                .as("처음 본 시각이 그대로여야 한다 — 밀리면 '언제 처음 봤나' 를 못 읽는다")
                .isEqualTo(first);
    }

    // ══ 2. 거절하지 않는 자리 — 거절하면 축하 창이 다시 뜬다 ══════════════

    @Test
    @DisplayName("★★ 자고 있어도 204 — 조회성 표시라 재우고 말고와 무관하다")
    void aSleepingPetIsStillRecorded() throws Exception {
        Long userId = newUserId();
        Long petId = graduatedPet(userId);

        // 실제 재우기 경로(밤잠 창 19:00~23:00)를 그대로 쓴다. 정산을 태우지 않으므로
        // 이 시험은 몇 시에 돌리든 같은 자리에 선다.
        transactions.executeWithoutResult(status ->
                petRepository.findByIdForUpdate(petId).orElseThrow().sleep(kstToday(21, 0)));
        boolean sleeping = Boolean.TRUE.equals(transactions.execute(status ->
                petRepository.findById(petId).orElseThrow().isSleeping()));
        assertThat(sleeping).isTrue();

        assertThat(seen(userId, petId).getResponse().getStatus()).isEqualTo(204);
        assertThat(storedMoment(petId)).isNotNull();
    }

    @Test
    @DisplayName("★★ 여행 중이어도 204 — 공통 문(alive)을 일부러 안 거친다")
    void aTravelingPetIsStillRecorded() throws Exception {
        Long userId = newUserId();
        Long petId = graduatedPet(userId);

        transactions.executeWithoutResult(status -> ReflectionTestUtils.setField(
                petRepository.findByIdForUpdate(petId).orElseThrow(), "tripStartedAt", Instant.now()));
        boolean traveling = Boolean.TRUE.equals(transactions.execute(status ->
                petRepository.findById(petId).orElseThrow().isTraveling()));
        assertThat(traveling).isTrue();

        // ★ 공통 문을 거치면 여기가 409(ZZAL_TRAVELING)로 빨개진다. 그때 사용자는 이미 본
        //   축하 창을 다음 접속에 다시 본다 — 그래서 이 시험이 그 문을 막고 있다.
        assertThat(seen(userId, petId).getResponse().getStatus()).isEqualTo(204);
        assertThat(storedMoment(petId)).isNotNull();
    }

    @Test
    @DisplayName("★ 서버가 보는 튜토리얼이 아직 안 끝났어도 204 — 기준은 화면이 판을 닫은 시점이다")
    void aPetStillInTutorialIsStillRecorded() throws Exception {
        Long userId = newUserId();
        Long petId = petStillInTutorial(userId);

        assertThat(seen(userId, petId).getResponse().getStatus()).isEqualTo(204);
        assertThat(storedMoment(petId)).isNotNull();
    }

    // ══ 3. 남의 것 · 로그인 ═══════════════════════════════════════════════

    @Test
    @DisplayName("★★ 남의 펫은 404 — 403 이 아니다(번호를 훑어 남의 펫을 셀 수 없게)")
    void someoneElsesPetIsNotFound() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = graduatedPet(owner);

        MvcResult result = seen(stranger, petId);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(result)).isEqualTo("ZZAL_PET_NOT_FOUND");
        assertThat(storedMoment(petId)).as("남이 부른 것으로 주인의 축하가 꺼지면 안 된다").isNull();
    }

    @Test
    @DisplayName("★ 로그인 없이 부르면 401 — 이 주소가 인증 밖으로 새면 여기서 빨개진다")
    void loginIsRequired() throws Exception {
        Long userId = newUserId();
        Long petId = graduatedPet(userId);

        int status = mockMvc.perform(post(SEEN.formatted(petId))).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(storedMoment(petId)).isNull();
    }
}
