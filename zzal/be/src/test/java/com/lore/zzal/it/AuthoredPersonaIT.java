package com.lore.zzal.it;

import com.lore.zzal.chat.persona.PersonaSheetBuilder;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.pet.PetPhase;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 HTTP 진입/소유권·검증·DB JSON 변환/재조회→채팅 의미 보존. 생성과 LLM은 대역이다. */
@ZzalIntegrationTest
class AuthoredPersonaIT extends ZzalItSupport {
    private Long draft(Long userId) {
        return transactions.execute(status -> petRepository.save(
                ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())).getId());
    }
    private Map<String, Object> original(String text) {
        return Map.of("version", 1, "fields", List.of(
                Map.of("fieldId", "world", "chips", List.of("현대"), "rawText", "도시의 작은 공방에서 지내는 악마", "skipped", false),
                Map.of("fieldId", "persona", "chips", List.of("다정함", "장난기", "능청스러움"), "rawText", text, "skipped", false),
                Map.of("fieldId", "extra", "chips", List.of(), "rawText", "", "skipped", true)));
    }

    @Test void savesAndReadsUncutOriginalThroughHttpAndRealDatabase() throws Exception {
        Long user = newUserId(), pet = draft(user);
        String text = "  처음 조용하지만 친해지면 장난을 친다. 무례하지 않다.\n" + "자세한 원문 ".repeat(80);
        var request = Map.of("name", "공방 친구", "personality", "COOL", "world", "잘못된 legacy 세계",
                "note", "호환용 짧은 투영", "authoredPersona", original(text));
        var result = postAs(user, "/api/zzal/v1/me/pets/draft/" + pet + "/character", request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        var read = getJson(user, "/api/zzal/v1/me/pets/" + pet + "/persona").path("data").path("authoredPersona");
        assertThat(read).isEqualTo(json.valueToTree(original(text)));
        assertThat(jdbc.queryForObject("select authored_persona from zzal_pet where id=?", String.class, pet)).contains("장난기");
        ZzalPet reloaded = petRepository.findById(pet).orElseThrow();
        String prompt = PromptAssembler.system(new PersonaSheetBuilder(null).build(reloaded));
        assertThat(prompt).contains("다정함", "장난기", "도시의 작은 공방에서 지내는 악마", "무례하지 않다");
        assertThat(prompt).doesNotContain("잘못된 legacy 세계", "호환용 짧은 투영");
        // 응답유실 뒤 제출을 반복해도 원문/phase를 바꾸지 않고 조회로 원성공을 확인할 수 있다.
        assertThat(postAs(user, "/api/zzal/v1/me/pets/draft/" + pet + "/character", request)
                .getResponse().getStatus()).isEqualTo(409);
        assertThat(getJson(user, "/api/zzal/v1/me/pets/" + pet + "/persona").path("data").path("authoredPersona"))
                .isEqualTo(read);
    }

    @Test void nestedInvalidInputDoesNotStartHatching() throws Exception {
        Long user = newUserId(), pet = draft(user);
        var invalid = Map.of("name", "친구", "authoredPersona", Map.of("version", 1, "fields", List.of(
                Map.of("fieldId", "not_a_field", "chips", List.of(), "rawText", "x", "skipped", false))));
        assertThat(postAs(user, "/api/zzal/v1/me/pets/draft/" + pet + "/character", invalid)
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(petRepository.findById(pet).orElseThrow().getPhase()).isEqualTo(PetPhase.DRAFT);
        assertThat(jdbc.queryForObject("select authored_persona from zzal_pet where id=?", String.class, pet)).isNull();
    }

    @Test void otherAccountCannotReadOrWriteOriginal() throws Exception {
        Long user = newUserId(), other = newUserId(), pet = draft(user);
        assertThat(mockMvc.perform(MockMvcRequestBuilders.get("/api/zzal/v1/me/pets/" + pet + "/persona")
                .with(asUser(other))).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(other, "/api/zzal/v1/me/pets/draft/" + pet + "/character",
                Map.of("name", "다른 사람", "authoredPersona", original("원문")))
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(petRepository.findById(pet).orElseThrow().getPhase()).isEqualTo(PetPhase.DRAFT);
    }

    @Test void legacyRequestAndDatabaseNullRemainSupported() throws Exception {
        Long user = newUserId(), pet = draft(user);
        assertThat(postAs(user, "/api/zzal/v1/me/pets/draft/" + pet + "/character",
                Map.of("name", "기존 친구", "note", "기존 설정"))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(getJson(user, "/api/zzal/v1/me/pets/" + pet + "/persona").path("data").path("authoredPersona").isNull()).isTrue();
        assertThat(PromptAssembler.system(new PersonaSheetBuilder(null).build(petRepository.findById(pet).orElseThrow())))
                .contains("기존 설정");
    }
}
