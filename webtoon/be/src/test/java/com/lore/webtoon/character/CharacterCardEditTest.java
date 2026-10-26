package com.lore.webtoon.character;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.CreditGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 캐릭터 카드 고치기 — 사용자가 고친 값이 그대로 남고, AI 가 지은 원래 값이 다시 섞이지 않는가.
 */
class CharacterCardEditTest {

    @TempDir Path dir;
    final WebtoonCharacterRepository repo = mock(WebtoonCharacterRepository.class);
    final CharacterMaker maker = mock(CharacterMaker.class);
    CharacterService service;

    @BeforeEach
    void setup() throws Exception {
        service = new CharacterService(repo, maker, mock(CharacterOwner.class), mock(PrivateArt.class),
                mock(CreditGate.class), mock(ShareReward.class), dir.toString(), 3, 2, false, Clock.systemUTC());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        Path worlds = dir.resolve("worlds.json");
        Files.writeString(worlds, """
                {"presets": {"romance_novel": {"label": "로판"}, "hunter": {"label": "헌터"}}}
                """);
        when(maker.worldsFile()).thenReturn(worlds);
    }

    static WebtoonCharacter aiCard(Long owner) {
        Instant at = Instant.parse("2026-10-27T00:00:00Z");
        WebtoonCharacter one = WebtoonCharacter.drawing("card-1", owner, null, "몽이", "", at);
        one.drewPanel("private/char/x.png", CharacterSource.PROMPT,
                new WebtoonCharacter.Card("romance_novel", "로판", "로맨스", "악역 영애", "곁",
                        "몽이는 이 로맨스 웹툰에서, 진짜 강아지인 악역 영애예요", "몽이: 멍!\n황태자: 저 개는 뭐지?",
                        List.of(new WebtoonCharacter.DialogueLine("몽이", true, "left", "멍!"),
                                new WebtoonCharacter.DialogueLine("황태자", false, "right", "저 개는 뭐지?")),
                        List.of("황태자와 춤을 춘다", "파혼 소식이 들려온다"), "romance_fantasy", false, "강아지"), at);
        return one;
    }

    @Test
    @DisplayName("고친 값이 그대로 남고, 지운 칸은 빈 칸으로 남는다")
    void 고친_값_그대로() {
        WebtoonCharacter one = aiCard(42L);
        when(repo.findByPublicId("card-1")).thenReturn(Optional.of(one));

        WebtoonCharacter out = service.editCard("card-1", 42L, List.of(), "몽이", "간식을 좋아한다",
                new WebtoonCharacter.CardEdit("hunter", "헌터", "액션", "강아지", "S급 헌터", "중심",
                        "", List.of("게이트 앞에서 짖는다", " "),
                        List.of(new WebtoonCharacter.DialogueEdit("몽이", "멍멍!"),
                                new WebtoonCharacter.DialogueEdit("길드장", "저 개가 S급이라고?"),
                                new WebtoonCharacter.DialogueEdit("", "  "))));

        assertThat(out.getDescription()).isEqualTo("간식을 좋아한다");
        assertThat(out.getWorld()).isEqualTo("hunter");
        assertThat(out.getWorldLabel()).isEqualTo("헌터");
        assertThat(out.getRoleName()).isEqualTo("S급 헌터");
        assertThat(out.getRoleTier()).isEqualTo("중심");
        assertThat(out.getTwist()).isNull();
        assertThat(out.fateLines()).containsExactly("게이트 앞에서 짖는다");
        assertThat(out.getQuote()).isEqualTo("몽이: 멍멍!\n길드장: 저 개가 S급이라고?");
        assertThat(out.dialogueLines()).hasSize(2);
        assertThat(out.dialogueLines().get(0).mine()).isTrue();
        assertThat(out.dialogueLines().get(0).side()).isEqualTo("left");
        assertThat(out.dialogueLines().get(1).mine()).isFalse();
        // 반전 한 줄을 지워도 카드는 카드다 — 카드 화면·공유 링크가 그대로 열린다
        assertThat(out.hasCard()).isTrue();
        assertThat(out.isCardEdited()).isTrue();
        // 설정 글은 사람이 적은 것 그대로 — AI 반전이 다시 붙지 않는다
        assertThat(out.settingsText()).isEqualTo("간식을 좋아한다").doesNotContain("악역 영애");
    }

    @Test
    @DisplayName("운명·대사를 다 지우면 빈 칸이 되고, 설정 글이 비면 고친 카드 칸으로만 이어 붙인다")
    void 전부_지우기() {
        WebtoonCharacter one = aiCard(42L);
        when(repo.findByPublicId("card-1")).thenReturn(Optional.of(one));

        WebtoonCharacter out = service.editCard("card-1", 42L, List.of(), null, null,
                new WebtoonCharacter.CardEdit(null, null, null, null, "", "", "새 소개", List.of(), List.of()));

        assertThat(out.getName()).isEqualTo("몽이");
        assertThat(out.fateLines()).isEmpty();
        assertThat(out.dialogueLines()).isEmpty();
        assertThat(out.getQuote()).isNull();
        assertThat(out.getRoleName()).isNull();
        assertThat(out.getWorld()).isEqualTo("romance_novel");
        assertThat(out.settingsText()).contains("새 소개").doesNotContain("악역 영애").doesNotContain("진짜 강아지인");
    }

    @Test
    @DisplayName("남의 캐릭터 · 카드 없는 캐릭터 · 모르는 값은 고치지 못한다")
    void 막는_것() {
        WebtoonCharacter other = aiCard(7L);
        when(repo.findByPublicId("card-1")).thenReturn(Optional.of(other));
        WebtoonCharacter.CardEdit edit = new WebtoonCharacter.CardEdit(null, null, null, null, null, null,
                "x", null, null);
        assertThatThrownBy(() -> service.editCard("card-1", 42L, List.of(), "몽이", "", edit))
                .isInstanceOf(BusinessException.class);

        WebtoonCharacter mine = aiCard(42L);
        when(repo.findByPublicId("card-1")).thenReturn(Optional.of(mine));
        assertThatThrownBy(() -> service.editCard("card-1", 42L, List.of(), "몽이", "",
                new WebtoonCharacter.CardEdit(null, null, null, null, null, "주연급", null, null, null)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.editCard("card-1", 42L, List.of(), "몽이", "",
                new WebtoonCharacter.CardEdit("no_such_world", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class);

        WebtoonCharacter portrait = WebtoonCharacter.drawing("p-1", 42L, null, "차사", "", Instant.now());
        portrait.drewArt("k", CharacterSource.PROMPT, Instant.now());
        when(repo.findByPublicId("p-1")).thenReturn(Optional.of(portrait));
        assertThatThrownBy(() -> service.editCard("p-1", 42L, List.of(), "차사", "", edit))
                .isInstanceOf(BusinessException.class);
        verify(repo, never()).save(any());
        assertThat(mine.isCardEdited()).isFalse();
    }
}
