package com.lore.zzal.chat.prompt;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.pet.persona.AuthoredPersona;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class SharedPersonaLanguageTest {
    private PersonaSheet sheet(String language, String call, String text) {
        return new PersonaSheet("시온", List.of(), null, null, null, call, false,
                new AuthoredPersona(1, List.of(new AuthoredPersona.Field("persona", List.of("시크한"), text, false))), language);
    }
    @Test void preferredLanguageDoesNotOverrideCurrentConversation() {
        String prompt = PromptAssembler.system(sheet("ja", "작가님", "차분하게 존댓말"));
        assertThat(prompt).contains("기본 언어: 일본어", "언어 전환 요청이 가장 우선", "이번 사용자 말과 이어지는 대화의 언어",
                "일본어나 영어 대화를 한국어로 되돌리지", "과거 원문이나 인용문을 번역·고쳐 쓰라는 뜻이 아니다",
                "차분하게 존댓말", "시크한", "상대를 부르는 말: 작가님");
        assertThat(PromptAssembler.system(sheet("en", "작가님", "차분하게 존댓말"))).contains("기본 언어: 영어");
        assertThat(PromptAssembler.system(sheet("unsupported", null, ""))).contains("기본 언어: 한국어", "사용자님");
    }
    @Test void nextTurnRebuildsCacheWithoutRewritingHistoricalMaterial() {
        SystemPromptCache cache = new SystemPromptCache();
        PersonaSheet old = sheet("ko", "예전호칭", "조용한 말투");
        String historicalPrompt = cache.get(10L, old);
        assertThat(cache.get(10L, old)).isEqualTo(historicalPrompt);
        assertThat(cache.builds()).isEqualTo(1);
        String japanese = cache.get(10L, sheet("ja", "예전호칭", "조용한 말투"));
        assertThat(cache.builds()).isEqualTo(2);
        assertThat(japanese).contains("기본 언어: 일본어");
        String next = cache.get(10L, sheet("ja", "현재호칭", "장난스럽지만 예의 있게"));
        assertThat(cache.builds()).isEqualTo(3);
        assertThat(next).contains("현재호칭", "장난스럽지만 예의 있게").doesNotContain("예전호칭", "조용한 말투");
        assertThat(historicalPrompt).contains("기본 언어: 한국어", "예전호칭", "조용한 말투");
    }
}
