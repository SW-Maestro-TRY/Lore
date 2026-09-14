package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.guard.HatchBlockLog;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부화 막기 — <b>기본 설정 그대로</b>, 진짜 DB·진짜 요청.
 *
 * <h3>왜 이 시험이 있나</h3>
 * 알파 공개(9/18) 시점에 운영에는 실제 그림 생성이 켜져 있고 잔액이 $70, 부화 한 번이 약 $0.25 다.
 * 막기가 <b>실제 요청 경로에서</b> 도는지는 단위 시험으로 확인할 수 없다 — 컨트롤러 배선, 트랜잭션
 * 경계, 그리고 <b>막힌 뒤에도 기록이 남는가</b>(롤백에 같이 지워지지 않는가)가 전부 여기서만 보인다.
 *
 * <h3>★ 숫자를 바꾸지 않는다</h3>
 * 이 클래스는 {@code application.yml} 의 기본값(동시 1 · 누적 3)으로 돈다. 그래서 <b>운영에 올라갈
 * 그 숫자</b>가 실제로 막는지를 본다. 숫자를 조정해야 보이는 문(하루·전체·IP)은 {@link HatchCapsIT}.
 */
@ZzalIntegrationTest
@DisplayName("부화 막기 — 기본 설정에서 실제로 막힌다")
class HatchLimitsIT extends ZzalItSupport {

    private static final String DRAFT = "/api/zzal/v1/me/pets/draft";

    @Autowired GenJobRepository jobRepository;

    private MvcResult draft(Long userId) throws Exception {
        return postAs(userId, DRAFT, Map.of("imageKey", newUploadedImageKey(userId)));
    }

    /**
     * 이미 <b>함께 지내는</b> 아이 하나. 굽기 기록도 한 줄 함께 남긴다(실제로 그렇게 생긴다).
     *
     * ★ 초안(DRAFT) 으로 두면 안 된다 — 초안이 있는 사람의 두 번째 요청은 막히는 것이 아니라
     *   <b>그 초안을 그대로 돌려받는다</b>(나갔다 올 때마다 굽지 않기 위한 규칙). 그건 막기가
     *   아니라 재사용이라, 여기서 쓰면 무엇을 보는 시험인지가 흐려진다.
     */
    private void alivePet(Long userId) {
        transactions.executeWithoutResult(status -> {
            Instant at = Instant.now();
            ZzalPet pet = petRepository.save(ZzalPet.draft(userId, "images/zzal/alive-%d".formatted(userId), at));
            pet.character("여울", null, null, null, null, null, at);
            pet.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", at);
            jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, "v1", at));
        });
    }

    /** 그 사람이 <b>지금까지 구운 횟수</b>만 만들어 둔다 — 펫은 실패로 끝나 자리를 안 먹는다. */
    private void pretendBaked(Long userId, int times) {
        transactions.executeWithoutResult(status -> {
            Instant at = Instant.now();
            ZzalPet pet = petRepository.save(ZzalPet.draft(userId, "images/zzal/old-%d".formatted(userId), at));
            ReflectionTestUtils.setField(pet, "phase", PetPhase.FAILED);
            for (int i = 1; i <= times; i++) {
                jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, i, "v1", at));
            }
        });
    }

    /** 남은 막힘 기록의 사유들. */
    private List<String> blockReasons() {
        return jdbc.queryForList(
                "select props from zzal_event where name = ? order by id", String.class, HatchBlockLog.EVENT);
    }

    // ══ 1. 동시 1마리 ══════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 아이가 이미 있으면 새로 못 만든다 — 그리고 <b>막혔다는 기록</b>이 남는다")
    void oneAliveBlocksAndIsRecorded() throws Exception {
        Long userId = newUserId();
        alivePet(userId);

        MvcResult second = draft(userId);

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(second)).isEqualTo("ZZAL_HATCH_BLOCKED_PET_LIMIT");
        assertThat(body(second).path("error").path("message").asText())
                .as("문구는 화면이 그대로 쓰지 않지만, 무엇이 얼마나 걸렸는지가 보여야 한다")
                .contains("1명");
        // ★★ 막기는 예외로 끝나 그 트랜잭션이 롤백된다 — 기록을 서비스 안에서 적었다면 여기서
        //   함께 지워져 아무 줄도 안 남는다(막혔는데 왜 막혔는지 아무도 모르는 상태).
        assertThat(blockReasons()).singleElement().asString().contains("pet_limit");
        assertThat(petRepository.findByUserIdOrderByIdDesc(userId)).as("두 번째는 만들어지지 않았다").hasSize(1);
        assertThat(jobRepository.countHatchesOfUser(userId, GenKind.HATCH))
                .as("굽기 기록도 안 늘었다 — 늘었다면 돈이 나갔다는 뜻이다").isEqualTo(1);
    }

    // ══ 2. 누적 3마리 ══════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 누적 3번을 다 쓰면 아이가 없어도 못 만든다 — 세는 것은 펫이 아니라 <b>구운 횟수</b>다")
    void lifetimeCapCountsBakesNotPets() throws Exception {
        Long userId = newUserId();
        pretendBaked(userId, 3);                 // 펫은 한 마리(실패), 구운 것은 세 번

        MvcResult result = draft(userId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("ZZAL_HATCH_BLOCKED_PET_LIMIT");
        assertThat(petRepository.findByUserIdOrderByIdDesc(userId))
                .as("펫 줄로 셌다면 한 마리뿐이라 통과했을 자리다 — 그 사이 돈은 세 번 나갔다")
                .hasSize(1);
        assertThat(blockReasons()).singleElement().asString().contains("pet_limit");
    }

    @Test
    @DisplayName("★ 누적 2번까지는 통과한다 — 부등호가 밀리면 한 번을 덜 주거나 더 준다")
    void twoBakesStillAllowsOneMore() throws Exception {
        Long userId = newUserId();
        pretendBaked(userId, 2);

        assertThat(draft(userId).getResponse().getStatus()).isEqualTo(200);
        assertThat(blockReasons()).as("통과했으면 막힘 기록도 없어야 한다").isEmpty();
    }

    // ══ 5. 바깥 한도(429) ══════════════════════════════════════════════

    @Test
    @DisplayName("★★ 바깥이 한도로 막는 동안에는 굽기를 시작조차 하지 않는다 — QUOTA")
    void quotaBreakerBlocksNewBakes() throws Exception {
        Long userId = newUserId();
        quotaBreaker.trip(Instant.now());

        MvcResult result = draft(userId);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(result)).isEqualTo("ZZAL_HATCH_BLOCKED_QUOTA");
        assertThat(blockReasons()).singleElement().asString().contains("quota_429");
        assertThat(jobRepository.countHatchesSince(GenKind.HATCH, Instant.now().minus(Duration.ofDays(1))))
                .as("굽기 기록이 한 줄도 안 생겨야 한다 — 생겼다면 돈이 나갔다는 뜻이다")
                .isZero();
    }

    @Test
    @DisplayName("★ 차단기를 풀면 곧바로 다시 된다 — 사람이 손대야만 회복되는 장치가 아니다")
    void resettingTheBreakerOpensItAgain() throws Exception {
        Long userId = newUserId();
        quotaBreaker.trip(Instant.now());
        assertThat(draft(userId).getResponse().getStatus()).isEqualTo(409);

        quotaBreaker.reset();

        assertThat(draft(userId).getResponse().getStatus()).isEqualTo(200);
    }

    // ══ 기록 ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("★★ 막힘 기록은 누가·언제·왜를 함께 남긴다 — 나중에 상한을 조정할 유일한 근거다")
    void theRecordKnowsWhoAndWhen() throws Exception {
        Long userId = newUserId();
        alivePet(userId);
        Instant before = Instant.now().minusSeconds(5);

        draft(userId);

        JsonNode row = json.readTree(jdbc.queryForObject(
                "select json_build_object('props', props, 'user_id', user_id, 'anon_id', anon_id,"
                        + " 'occurred_at', occurred_at, 'path', path)::text"
                        + " from zzal_event where name = ? order by id desc limit 1",
                String.class, HatchBlockLog.EVENT));

        assertThat(row.path("props").asText()).contains("pet_limit");
        assertThat(row.path("user_id").asLong()).isEqualTo(userId);
        assertThat(row.path("anon_id").asText()).as("익명 번호 칸은 비울 수 없다(저장이 통째로 실패한다)").isNotBlank();
        assertThat(row.path("path").asText()).isEqualTo(DRAFT);
        assertThat(java.time.OffsetDateTime.parse(row.path("occurred_at").asText()).toInstant())
                .as("언제 막혔나").isAfter(before);
    }
}
