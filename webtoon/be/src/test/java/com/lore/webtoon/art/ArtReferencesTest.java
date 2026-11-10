package com.lore.webtoon.art;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 글자에서 그림 주소를 뽑는 규칙. 덜 뽑으면 살아 있는 그림이 지워진다. */
class ArtReferencesTest {

    @Test
    @DisplayName("JSON · 화면 주소 · 문장 속 주소를 모두 뽑는다")
    void findsKeysInAnyText() {
        Set<String> found = new HashSet<>();
        ArtReferences.collect("{\"cover\":\"/images/webtoon/3f9a2c?w=320\",\"n\":1}", found);
        ArtReferences.collect("캐릭터 그림은 private/webtoon/char/ab12.png.", found);
        ArtReferences.collect("[\"images/webtoon/a1\",\"images/webtoon/b2\"]", found);

        assertThat(found).contains(
                "images/webtoon/3f9a2c",             // 쿼리(?w=320) 앞에서 끊는다
                "private/webtoon/char/ab12.png",     // 문장 끝 마침표는 뗀 것도 담는다
                "images/webtoon/a1", "images/webtoon/b2");
    }

    @Test
    @DisplayName("다른 서비스의 주소나 빈 값은 안 담는다")
    void ignoresOthers() {
        Set<String> found = new HashSet<>();
        ArtReferences.collect("images/zzal/x1 · images/piece-maker/y2", found);
        ArtReferences.collect(null, found);

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("표·칸 이름은 따옴표로 감싸고 안의 따옴표는 겹쳐 쓴다")
    void quotesNames() {
        assertThat(ArtReferences.quoted("webtoon_page")).isEqualTo("\"webtoon_page\"");
        assertThat(ArtReferences.quoted("a\"b")).isEqualTo("\"a\"\"b\"");
    }
}
