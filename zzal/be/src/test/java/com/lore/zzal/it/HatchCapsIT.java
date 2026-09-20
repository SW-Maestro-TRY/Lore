package com.lore.zzal.it;

import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.guard.ClientIp;
import com.lore.zzal.guard.HatchBlockLog;
import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 하루 상한 둘과 IP 상한 — <b>숫자를 작게 줄여</b> 실제로 닿게 만든 시험.
 *
 * <h3>★★ 왜 숫자를 바꾸나</h3>
 * 운영 기본값은 서비스 전체 하루 40회다. 그 문에 닿으려면 한 시험 안에서 40번을 구워야 하는데,
 * 그건 시험이 아니라 부하 시험이 된다. 그래서 <b>상한이 설정이라는 사실 자체</b>를 이용해
 * 3회로 줄여 문에 닿게 한다 — 판정하는 코드는 운영과 한 글자도 다르지 않다.
 *
 * <h3>★ 누적 상한을 열어 두는 이유</h3>
 * 기본값에서는 누적(3)과 하루(3)가 같아 <b>하루 상한이 사실상 안 걸린다</b>(누적이 먼저 걸린다 —
 * {@code HatchGuard} 주석). 그 문이 진짜로 도는지 보려면 누적을 열어 둬야 한다.
 * 순서 자체는 {@code HatchGuardTest} 가 따로 못 박는다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.hatch-limits.max-total=99",
        "app.zzal.hatch-limits.per-user-daily=2",
        "app.zzal.hatch-limits.service-daily=3",
        "app.zzal.hatch-limits.ip-per-window=2",
        "app.zzal.hatch-limits.ip-window-minutes=60",
        // 이 시험의 XFF 는 "진짜 접속자" 한 칸만 보낸다 — 우리 설비가 덧붙인 칸이 없다.
        "app.zzal.hatch-limits.xff-trusted-hops=0",
})
@DisplayName("부화 막기 — 하루 상한 둘과 IP 상한")
class HatchCapsIT extends ZzalItSupport {

    private static final String DRAFT = "/api/zzal/v1/me/pets/draft";

    @Autowired GenJobRepository jobRepository;

    private MvcResult draft(Long userId) throws Exception {
        return postAs(userId, DRAFT, Map.of("imageKey", newUploadedImageKey(userId)));
    }

    /** 그 집에서 온 요청으로 만든다 — {@code X-Forwarded-For} 가 유일한 출처다. */
    private MvcResult draftFrom(Long userId, String ip) throws Exception {
        return mockMvc.perform(post(DRAFT).with(asUser(userId))
                .header(ClientIp.HEADER, ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("imageKey", newUploadedImageKey(userId)))))
                .andReturn();
    }

    /** 그 사람이 <b>그 시각에</b> 구운 것으로 둔다. 자리는 안 먹는다(실패로 끝난 판). */
    private void bakedAt(Long userId, Instant... times) {
        transactions.executeWithoutResult(status -> {
            ZzalPet pet = petRepository.save(
                    ZzalPet.draft(userId, "images/zzal/old-%d".formatted(userId), Instant.now()));
            ReflectionTestUtils.setField(pet, "phase", PetPhase.FAILED);
            int attempt = 1;
            for (Instant at : times) {
                jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, attempt++, "v1", at));
            }
        });
    }

    private List<String> blockReasons() {
        return jdbc.queryForList(
                "select props from zzal_event where name = ? order by id", String.class, HatchBlockLog.EVENT);
    }

    /** 오늘(한국 시각) 0시. 하루 상한의 경계다. */
    private static Instant koreanMidnight() {
        return AwakeClock.dateOf(Instant.now()).atStartOfDay(ZzalRules.ZONE).toInstant();
    }

    // ══ 3. 사람당 하루 ═════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 오늘 몫을 다 쓰면 막힌다 — DAILY_CAP")
    void perUserDailyCap() throws Exception {
        Long userId = newUserId();
        bakedAt(userId, koreanMidnight(), koreanMidnight().plusSeconds(3600));

        MvcResult result = draft(userId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("ZZAL_HATCH_BLOCKED_DAILY_CAP");
        assertThat(body(result).path("error").path("message").asText()).contains("자정");
        assertThat(blockReasons()).singleElement().asString().contains("daily_cap");
    }

    @Test
    @DisplayName("★★ 날짜 경계는 <b>한국 시각 자정</b>이다 — 자정 1초 전 것은 어제 몫이라 오늘을 안 먹는다")
    void theDayStartsAtKoreanMidnight() throws Exception {
        Long userId = newUserId();
        Instant midnight = koreanMidnight();
        bakedAt(userId, midnight.minusSeconds(1), midnight.minusSeconds(2));

        assertThat(draft(userId).getResponse().getStatus())
                .as("UTC 자정으로 셌다면 여기가 409 다 — 한국 사용자가 오전 내내 못 만든다")
                .isEqualTo(200);
    }

    // ══ 4. 서비스 전체 하루 ════════════════════════════════════════════

    @Test
    @DisplayName("★★ 서비스 전체 몫을 다 쓰면 <b>아무나</b> 막힌다 — 잔액 방벽")
    void serviceWideDailyCap() throws Exception {
        // 남들이 오늘 세 번 구웠다(사람마다 한 번씩 — 사람당 상한에는 안 걸린다)
        for (int i = 0; i < 3; i++) {
            bakedAt(newUserId(), koreanMidnight().plusSeconds(60L * i));
        }
        Long freshUser = newUserId();

        MvcResult result = draft(freshUser);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result))
                .as("이 사람은 오늘 한 번도 안 만들었다 — 막는 이유는 서비스 전체 사정이다")
                .isEqualTo("ZZAL_HATCH_BLOCKED_SERVICE_CAP");
        assertThat(blockReasons()).singleElement().asString().contains("service_cap");
    }

    // ══ 5. 한 집에서 몰아치기 ══════════════════════════════════════════

    @Test
    @DisplayName("★★ 같은 집에서 계정을 갈아 가며 몰아쳐도 창 안에서는 못 넘는다 — IP_RATE")
    void oneHouseCannotSpinUpAccounts() throws Exception {
        String house = "203.0.113.7";
        assertThat(draftFrom(newUserId(), house).getResponse().getStatus()).isEqualTo(200);
        assertThat(draftFrom(newUserId(), house).getResponse().getStatus()).isEqualTo(200);

        MvcResult third = draftFrom(newUserId(), house);

        assertThat(third.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(third)).isEqualTo("ZZAL_HATCH_BLOCKED_IP_RATE");
        assertThat(blockReasons()).singleElement().asString().contains("ip_rate");
    }

    @Test
    @DisplayName("★ 다른 집에서 온 요청은 안 막힌다 — 상한이 IP 별로 세어지는지")
    void anotherHouseIsNotAffected() throws Exception {
        String house = "203.0.113.7";
        draftFrom(newUserId(), house);
        draftFrom(newUserId(), house);

        assertThat(draftFrom(newUserId(), "198.51.100.9").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("★★ 막힌 시도는 IP 자국을 남기지 않는다 — 거절이 거절을 부르면 영영 못 만든다")
    void blockedAttemptsDoNotCountTowardTheIpLimit() throws Exception {
        String house = "203.0.113.7";
        Long blocked = newUserId();
        alivePetWithoutBakeRecord(blocked);

        // 이미 아이가 있어 계속 막히는 사람. 자국이 남는다면 이 다섯 번으로 집이 잠긴다.
        for (int i = 0; i < 5; i++) {
            assertThat(errorCode(draftFrom(blocked, house))).isEqualTo("ZZAL_HATCH_BLOCKED_PET_LIMIT");
        }

        assertThat(draftFrom(newUserId(), house).getResponse().getStatus()).isEqualTo(200);
        assertThat(draftFrom(newUserId(), house).getResponse().getStatus())
                .as("막힌 다섯 번이 세어졌다면 이 집은 이미 상한(2)을 넘겨 409 다")
                .isEqualTo(200);
    }

    /**
     * 이미 함께 지내는 아이 하나 — <b>굽기 기록은 안 만든다</b>.
     *
     * ★ 여기서 굽기 기록까지 만들면 서비스 전체 상한(이 시험에서는 3)을 미리 한 칸 먹어,
     *   IP 상한을 보려던 자리에서 {@code SERVICE_CAP} 이 먼저 걸린다. 보려는 문이 아니다.
     */
    private void alivePetWithoutBakeRecord(Long userId) {
        transactions.executeWithoutResult(status -> {
            Instant at = Instant.now();
            ZzalPet pet = petRepository.save(ZzalPet.draft(userId, "images/zzal/alive-%d".formatted(userId), at));
            pet.character("여울", null, null, null, null, null, at);
            pet.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", at);
        });
    }
}
