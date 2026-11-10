package com.lore.zzal.chat.persona;

import com.lore.zzal.PetFixture;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.persona.FirstMeeting;
import com.lore.zzal.pet.persona.FirstMeetingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FirstMeetingLanguageTest {
    final Instant now = Instant.parse("2026-10-01T00:00:00Z");
    final ZzalPet pet = PetFixture.hatching(1L, "시온", null, "k", now);
    final PetService pets = mock(PetService.class);
    final ZzalPetRepository repository = mock(ZzalPetRepository.class);
    final ChatService chat = mock(ChatService.class);
    final FirstMeetingService service = new FirstMeetingService(pets, repository, chat);
    FirstMeetingLanguageTest() {
        pet.beginFirstMeeting();
        pet.advanceFirstMeeting(FirstMeeting.State.ADDRESS);
        when(repository.findByIdForUpdate(10L)).thenReturn(Optional.of(pet));
        when(pets.get(1L, 10L)).thenReturn(pet);
        when(chat.firstMeetingGreeting(pet)).thenReturn("");
    }
    final FirstMeeting.Request defer = new FirstMeeting.Request(FirstMeeting.Action.DEFER_ADDRESS, null);

    @ParameterizedTest @CsvSource({"ja,일본어", "en,영어"})
    void selectedLanguageIsVisibleBeforeTheFirstGeneration(String language, String label) {
        doAnswer(call -> {
            assertThat(PromptAssembler.system(new PersonaSheetBuilder(null).build(pet))).contains("기본 언어: " + label);
            return null;
        }).when(chat).prepareFirstMeetingGreeting(pet, now);
        service.act(1L, 10L, defer, now, language);
        var order = inOrder(repository, pets, chat);
        order.verify(repository).findByIdForUpdate(10L);
        order.verify(pets).get(1L, 10L);
        order.verify(pets).firstMeeting(1L, 10L, defer);
        order.verify(chat).prepareFirstMeetingGreeting(pet, now);
        assertThat(pet.getDiaryLanguage()).isEqualTo(language);
    }
    @Test void invalidOrMissingHeaderDoesNotResetPreviouslySelectedLanguage() {
        pet.diaryLanguage("ja");
        service.act(1L, 10L, defer, now, "invalid");
        assertThat(pet.getDiaryLanguage()).isEqualTo("ja");
        service.act(1L, 10L, defer, now);
        assertThat(pet.getDiaryLanguage()).isEqualTo("ja");
    }
    @Test void unauthorizedRequestCannotChangeLanguageOrGenerate() {
        when(pets.get(2L, 10L)).thenThrow(new IllegalArgumentException("not owner"));
        assertThatThrownBy(() -> service.act(2L, 10L, defer, now, "ja")).isInstanceOf(IllegalArgumentException.class);
        assertThat(pet.getDiaryLanguage()).isEqualTo("ko");
        verify(pets, never()).firstMeeting(any(), any(), any());
        verify(chat, never()).prepareFirstMeetingGreeting(any(), any());
    }
    @Test void getOnlyReadsTheStoredGreetingWithoutChangingLanguageOrGenerating() {
        pet.diaryLanguage("ja");
        when(chat.firstMeetingGreeting(pet)).thenReturn("保存された挨拶");
        assertThat(service.get(1L, 10L).greetingLine()).isEqualTo("保存された挨拶");
        assertThat(pet.getDiaryLanguage()).isEqualTo("ja");
        verifyNoInteractions(repository);
        verify(chat, never()).prepareFirstMeetingGreeting(any(), any());
    }
    @Test void completedGreetingRetryPreservesStoredTextAndNeverGeneratesAgain() {
        pet.advanceFirstMeeting(FirstMeeting.State.COMPLETE);
        pet.diaryLanguage("ja");
        when(chat.firstMeetingGreeting(pet)).thenReturn("保存された挨拶");
        assertThat(service.act(1L, 10L, defer, now, "en").greetingLine()).isEqualTo("保存された挨拶");
        assertThat(service.act(1L, 10L, defer, now, "en").greetingLine()).isEqualTo("保存された挨拶");
        assertThat(pet.getDiaryLanguage()).isEqualTo("ja");
        verify(chat, never()).prepareFirstMeetingGreeting(any(), any());
    }
}
