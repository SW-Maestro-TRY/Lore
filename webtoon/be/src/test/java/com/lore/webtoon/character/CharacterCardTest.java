package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「캐릭터 만들어보기」의 카드 — 하네스가 준 한 줄을 캐릭터에 어떻게 얹는가.
 *
 * 스프링을 안 띄운다. 여기서 보는 것은 "하네스 출력 → 카드" 와 "카드 → 캐릭터
 * 칸" 의 모양뿐이라 가짜가 필요 없다.
 */
class CharacterCardTest {

    private static final String PANEL_LINE = """
            {"out": "/x/panel.png", "name": "", "named": "몽이", "species": "강아지",
             "world": "romance_novel", "world_label": "사교계", "genre": "로맨스",
             "role": "악역 영애", "role_tier": "곁", "twist": "몽이는 이 로맨스 웹툰에서, 진짜 강아지인 악역 영애예요",
             "quote": "몽이: 멍!\\n황태자: 저 개는 뭐지?",
             "dialogue": [{"who": "몽이", "mine": true, "side": "left", "text": "멍!"},
                          {"who": "황태자", "mine": false, "side": "right", "text": "저 개는 뭐지?"}],
             "fate": ["무도회장에서 모두의 시선을 받는다", "황태자가 경계한다"],
             "style": "romance_fantasy", "source": "prompt", "calls": []}
            """;

    @Test
    @DisplayName("한 컷 출력에서 카드를 읽는다 — 반전·대사·운명·그림체")
    void 카드_읽기() throws Exception {
        WebtoonCharacter.Card card = CharacterMaker.cardOf(new ObjectMapper().readTree(PANEL_LINE));

        assertThat(card).isNotNull();
        assertThat(card.twist()).contains("악역 영애");
        assertThat(card.roleTier()).isEqualTo("곁");
        assertThat(card.quote()).startsWith("몽이: 멍!");
        assertThat(card.dialogue()).hasSize(2);
        assertThat(card.dialogue().get(0).mine()).isTrue();
        assertThat(card.dialogue().get(1).side()).isEqualTo("right");
        assertThat(card.fate()).hasSize(2);
        assertThat(card.style()).isEqualTo("romance_fantasy");
        assertThat(card.world()).isEqualTo("romance_novel");
    }

    @Test
    @DisplayName("초상 한 장 출력에는 카드가 없다 — 예전 길은 그대로")
    void 초상은_카드_없음() throws Exception {
        String portrait = """
                {"out": "/x/art.png", "named": "차사", "name": "", "source": "photo", "calls": []}
                """;
        assertThat(CharacterMaker.cardOf(new ObjectMapper().readTree(portrait))).isNull();
    }

    @Test
    @DisplayName("카드를 얹으면 그림도 같이 다 된 것이고, 운명은 줄 단위로 돌아온다")
    void 카드_얹기() {
        Instant at = Instant.parse("2026-09-19T00:00:00Z");
        WebtoonCharacter one = WebtoonCharacter.drawing("abc", null, "uid-1", "몽이", "", at);
        assertThat(one.hasCard()).isFalse();

        one.drewPanel("private/char/x.png", CharacterSource.PROMPT,
                new WebtoonCharacter.Card("romance_novel", "사교계", "로맨스", "악역 영애", "곁",
                        "몽이는 이 로맨스 웹툰에서, 진짜 강아지인 악역 영애예요", "몽이: 멍!",
                        List.of(new WebtoonCharacter.DialogueLine("몽이", true, "left", "멍!"),
                                new WebtoonCharacter.DialogueLine("황태자", false, "right", "저 개는\t뭐지?")),
                        List.of("첫 줄", " 둘째 줄 ", ""), "romance_fantasy", "강아지"), at);

        assertThat(one.getStatus()).isEqualTo(CharacterStatus.READY);
        assertThat(one.getArtKey()).isEqualTo("private/char/x.png");
        assertThat(one.getSpecies()).isEqualTo("강아지");
        assertThat(one.hasCard()).isTrue();
        assertThat(one.getRoleTier()).isEqualTo("곁");
        assertThat(one.dialogueLines()).hasSize(2);
        assertThat(one.dialogueLines().get(1).text()).isEqualTo("저 개는 뭐지?");
        assertThat(one.dialogueLines().get(0).mine()).isTrue();
        assertThat(one.fateLines()).containsExactly("첫 줄", "둘째 줄");
        assertThat(one.getRoleName()).isEqualTo("악역 영애");
    }

    @Test
    @DisplayName("긴 값은 칸 길이에 맞춰 자른다 — DB 에서 터지는 것보다 잘리는 편이 낫다")
    void 긴_값_자르기() {
        Instant at = Instant.now();
        WebtoonCharacter one = WebtoonCharacter.drawing("abc", 1L, null, "x", "", at);
        one.drewPanel("k", CharacterSource.PHOTO,
                new WebtoonCharacter.Card("", "아주아주아주아주아주아주아주아주아주긴세계관이름", "",
                        "", "", "t", "", List.of(), List.of(), "", ""), at);
        assertThat(one.getWorldLabel()).hasSize(20);
        assertThat(one.getWorld()).isNull();
    }
}
