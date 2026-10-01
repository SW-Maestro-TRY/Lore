package com.lore.zzal.it;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 동작 요청 — "이런 동작도 보고 싶어요" 한 줄이 <b>실제 주소</b>에서 어떻게 다뤄지나.
 *
 * <h3>★ 왜 통합이어야 하나</h3>
 * 여기서 보려는 넷 중 셋은 서비스 시험이 원리적으로 못 본다.
 * <ul>
 *   <li><b>로그인 없이 부르면 막히는가</b> — 판정하는 것은 시큐리티 설정이지 서비스가 아니다.
 *       주소가 인증 밖으로 새어도 서비스 시험은 전부 초록이다</li>
 *   <li><b>잘못된 값이 400 인가 500 인가</b> — {@code @Valid} 가 안 걸려 있으면 서비스는
 *       옳게 도는데 사용자만 500 을 본다</li>
 *   <li><b>글이 기록층으로 새지 않는가</b> — 표 두 개를 같이 봐야만 드러난다</li>
 * </ul>
 */
@ZzalIntegrationTest
@DisplayName("동작 요청 — 남기면 204, 글은 표에만 남는다")
class MotionWishIT extends ZzalItSupport {

    private static final String PATH = "/api/zzal/v1/me/pets/%d/motion-wish";

    /** 소유권만 보는 주소라 부화까지 갈 필요가 없다 — 내 이름으로 만들어진 줄 하나면 충분하다. */
    private ZzalPet myPet(Long userId) {
        Instant now = Instant.now();
        return transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), now)));
    }

    private MvcResult submit(Long userId, Long petId, String text) throws Exception {
        return postAs(userId, PATH.formatted(petId), Map.of("text", text));
    }

    private List<Map<String, Object>> wishRows(Long petId) {
        return jdbc.queryForList("select text, user_id from zzal_motion_wish where pet_id = ? order by id", petId);
    }

    // ══ 1. 제대로 남기는 길 ═══════════════════════════════════════════════

    @Test
    @DisplayName("★ 204 로 답하고 본문이 비어 있다 — 글은 앞뒤 공백을 떼고 한 줄 들어간다")
    void aWishIsStoredAndAnsweredWithNoContent() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        MvcResult result = submit(userId, petId, "  기지개 켜는 모습이 보고 싶어요  ");

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();

        assertThat(wishRows(petId)).singleElement().satisfies(row -> {
            assertThat(row.get("text")).isEqualTo("기지개 켜는 모습이 보고 싶어요");
            assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(userId);
        });
    }

    @Test
    @DisplayName("★★ 여러 번 남길 수 있다 — 후기와 달리 (사람, 펫) 한 번이 아니다")
    void manyWishesPerPetAreAllowed() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        for (String text : List.of("구르기", "기지개", "손 흔들기")) {
            assertThat(submit(userId, petId, text).getResponse().getStatus()).isEqualTo(204);
        }

        assertThat(wishRows(petId)).hasSize(3);
    }

    @Test
    @DisplayName("★★ 사람이 쓴 글은 기록층으로 새지 않는다 — 고정된 이름 한 줄만 남는다")
    void theFreeTextNeverReachesTheEventLog() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();
        String text = "비밀스러운 자유 글";

        assertThat(submit(userId, petId, text).getResponse().getStatus()).isEqualTo(204);

        List<Map<String, Object>> events = jdbc.queryForList(
                "select name, props, user_id from zzal_event where user_id = ?", userId);
        assertThat(events).singleElement().satisfies(row -> {
            assertThat(row.get("name")).isEqualTo("motion_wish_submitted");
            // props 가 비어 있어야 한다 — 글을 실으면 허용 키 밖이라 버려지지만, 버려지는 것에
            // 기대는 대신 애초에 안 싣는다.
            assertThat((String) row.get("props")).isNullOrEmpty();
        });

        String dump = jdbc.queryForList("select * from zzal_event").toString();
        assertThat(dump).as("기록 표 어디에도 글이 없어야 한다").doesNotContain(text);
    }

    // ══ 2. 잘못된 값 — 400 이어야 한다(500 이 아니라) ═════════════════════

    @Test
    @DisplayName("★ 공백뿐인 글은 400 — 빈 줄이 표에 쌓이면 '몇 명이 남겼나' 를 셀 수 없다")
    void whitespaceOnlyIsRejected() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        assertThat(submit(userId, petId, "   ").getResponse().getStatus()).isEqualTo(400);
        assertThat(submit(userId, petId, "").getResponse().getStatus()).isEqualTo(400);
        assertThat(wishRows(petId)).isEmpty();
    }

    @Test
    @DisplayName("★ 60자는 통과, 61자는 400 — 경계가 DB 칸 길이와 같은 자리다")
    void theLengthBoundaryIsSixty() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        String exact = "가".repeat(ZzalRules.MOTION_WISH_MAX_CHARS);
        assertThat(submit(userId, petId, exact).getResponse().getStatus()).isEqualTo(204);

        MvcResult tooLong = submit(userId, petId, "가".repeat(ZzalRules.MOTION_WISH_MAX_CHARS + 1));
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(errorCode(tooLong)).isEqualTo("INVALID_INPUT");

        assertThat(wishRows(petId)).hasSize(1);
    }

    // ══ 3. 남의 것 · 로그인 ═══════════════════════════════════════════════

    @Test
    @DisplayName("★★ 남의 펫에는 못 남긴다 — 403 이 아니라 404 다(번호를 훑어 남의 펫을 셀 수 없게)")
    void someoneElsesPetIsNotFound() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = myPet(owner).getId();

        MvcResult result = submit(stranger, petId, "구르기");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(result)).isEqualTo("ZZAL_PET_NOT_FOUND");
        assertThat(wishRows(petId)).isEmpty();
    }

    @Test
    @DisplayName("★ 로그인 없이 부르면 401 — 이 주소가 인증 밖으로 새면 여기서 빨개진다")
    void loginIsRequired() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        int status = mockMvc.perform(post(PATH.formatted(petId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("text", "구르기"))))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(401);
        assertThat(wishRows(petId)).isEmpty();
    }

    // ══ 4. 하루 상한 ══════════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 하루 20줄까지 — 스물한 번째는 409(ZZAL_MOTION_WISH_DAILY_LIMIT)")
    void theDailyCapStopsTheTwentyFirst() throws Exception {
        Long userId = newUserId();
        Long petId = myPet(userId).getId();

        for (int i = 0; i < ZzalRules.MOTION_WISH_DAILY_LIMIT; i++) {
            assertThat(submit(userId, petId, "동작 " + i).getResponse().getStatus())
                    .as("%d번째", i + 1)
                    .isEqualTo(204);
        }

        MvcResult over = submit(userId, petId, "한 줄 더");
        assertThat(over.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(over)).isEqualTo("ZZAL_MOTION_WISH_DAILY_LIMIT");

        assertThat(wishRows(petId)).hasSize(ZzalRules.MOTION_WISH_DAILY_LIMIT);
    }

    @Test
    @DisplayName("★ 상한은 아이마다 따로 센다 — 한 아이가 다 써도 다른 아이는 멀쩡하다")
    void theCapIsCountedPerPet() throws Exception {
        Long userId = newUserId();
        Long full = myPet(userId).getId();
        Long other = myPet(userId).getId();

        for (int i = 0; i < ZzalRules.MOTION_WISH_DAILY_LIMIT; i++) {
            submit(userId, full, "동작 " + i);
        }

        assertThat(submit(userId, full, "한 줄 더").getResponse().getStatus()).isEqualTo(409);
        assertThat(submit(userId, other, "구르기").getResponse().getStatus()).isEqualTo(204);
    }
}
