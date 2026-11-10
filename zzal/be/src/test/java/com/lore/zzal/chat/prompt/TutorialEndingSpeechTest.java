package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.pet.dto.PetRequests;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class TutorialEndingSpeechTest {
    @Test void fallbackKeepsCanonicalAddressOnceAtStartInEveryLocaleAndRespectsDeclining() {
        for (String language : List.of("ko", "en", "ja")) {
            var sheet = new PersonaSheet("아이", List.of(), null, null, null, "상훈님", false, null, language);
            String line = TutorialEndingSpeech.fallback(ConversationScene.Step.CHAT_INVITE, sheet);
            assertThat(line).startsWith("상훈님");
            assertThat(line.indexOf("상훈님")).isEqualTo(line.lastIndexOf("상훈님"));
            assertThat(line).doesNotContain("네 이야기", "あなたの話");
            var declined = new PersonaSheet("아이", List.of(), null, null, null, null, true, null, language);
            assertThat(TutorialEndingSpeech.fallback(ConversationScene.Step.CHAT_INVITE, declined))
                    .doesNotContain("사용자님", "User", "あなた", "null");
        }
        var missing = new PersonaSheet("아이", List.of(), null, null, null, null, false);
        assertThat(TutorialEndingSpeech.fallback(ConversationScene.Step.CHAT_INVITE, missing)).startsWith("사용자님, ");
    }

    @Test void endingScenesCannotEnterOrdinaryChatOrPretendToFollowAnAction() {
        assertThat(TutorialEndingSpeech.supports(null)).isFalse();
        assertThatThrownBy(() -> new ConversationScene(ConversationScene.Phase.CONTINUOUS, ConversationScene.Step.CHAT_INTRO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConversationScene(ConversationScene.Phase.TUTORIAL_STAGE,
                ConversationScene.Step.FINISH_THANKS, ConversationScene.Beat.AFTER_ACTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void openApiSceneIsOptionalAndOnlyTheFivePresentationKeysAreDocumented() {
        var schemas = ModelConverters.getInstance().readAll(PetRequests.TutorialSeen.class);
        var request = schemas.get("TutorialSeen");
        assertThat(request.getRequired()).contains("topic").doesNotContain("scene");
        var scene = (io.swagger.v3.oas.models.media.Schema<?>) request.getProperties().get("scene");
        assertThat(scene.getEnum()).extracting(Object::toString).containsExactly(
                "FINISH_THANKS", "FINISH_TOMORROW", "CHAT_INTRO", "CHAT_MEMORY", "CHAT_INVITE");
    }
}
