package com.lore.webtoon.community;
import com.lore.webtoon.character.*;
import com.lore.webtoon.credit.BrowserLinkRepository;
import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicCharactersTest {
    WebtoonCharacterRepository rows=mock(WebtoonCharacterRepository.class);
    BrowserLinkRepository links=mock(BrowserLinkRepository.class);
    PublicCharacters service=new PublicCharacters(rows,mock(CharacterService.class),links);
    WebtoonCharacter c;
    @BeforeEach void setup() {
        c=WebtoonCharacter.drawing("c",1L,"browser","캐릭터","설명",Instant.now());
        c.drewArt("images/c.png",CharacterSource.PROMPT,Instant.now());
        when(rows.lockForPublication("c")).thenReturn(Optional.of(c));
    }
    @Test void guestCannotPublish() {
        assertThatThrownBy(()->service.publish("c",null,true,true)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(rows);
    }
    @Test void anotherUserCannotPublishEvenWithBrowserLink() {
        when(links.existsByUserIdAndBrowserUid(2L,"browser")).thenReturn(true);
        assertThatThrownBy(()->service.publish("c",2L,true,true)).isInstanceOf(BusinessException.class);
        verify(rows,never()).save(any());
    }
    @Test void requiresExplicitConsent() {
        assertThatThrownBy(()->service.publish("c",1L,true,false)).isInstanceOf(BusinessException.class);
        assertThat(c.isCommunityPublic()).isFalse();
    }
    @Test void publishesThenRevokesWithoutErasingCharacter() {
        assertThat(service.publish("c",1L,true,true).get("published")).isEqualTo(true);
        assertThat(service.publish("c",1L,false,false).get("published")).isEqualTo(false);
        assertThat(c.getArtKey()).isEqualTo("images/c.png");
        assertThat(c.getOwnerId()).isEqualTo(1L);
    }
    @Test void drawingCannotBePublished() {
        c=WebtoonCharacter.drawing("c",1L,"browser","캐릭터","설명",Instant.now());
        when(rows.lockForPublication("c")).thenReturn(Optional.of(c));
        assertThatThrownBy(()->service.publish("c",1L,true,true)).isInstanceOf(BusinessException.class);
    }
    @Test void trustedLinkedGuestCharacterCanBeClaimed() {
        c=WebtoonCharacter.drawing("c",null,"browser","캐릭터","설명",Instant.now());
        c.drewArt("images/c.png",CharacterSource.PROMPT,Instant.now());
        when(rows.lockForPublication("c")).thenReturn(Optional.of(c));
        when(links.existsByUserIdAndBrowserUid(1L,"browser")).thenReturn(true);
        service.publish("c",1L,true,true);
        assertThat(c.getOwnerId()).isEqualTo(1L);
    }
}
