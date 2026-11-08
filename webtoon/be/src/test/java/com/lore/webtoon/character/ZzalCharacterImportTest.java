package com.lore.webtoon.character;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 짤 설정이 웹툰 설정 글로 옮겨지는 모양. 짤에 적은 말은 고치지 않고 머리말만 단다. */
class ZzalCharacterImportTest {

    private final ZzalCharacterImport sut =
            new ZzalCharacterImport(null, null, null, null, null, false, "");

    @Test
    @DisplayName("새 온보딩 캐릭터는 작성 원문을 칸 순서대로 옮기고, 건너뛴 칸은 뺀다")
    void 작성원문() {
        String authored = """
                {"version":1,"fields":[
                  {"fieldId":"world","chips":["카페"],"rawText":"골목 끝 작은 원두 가게","skipped":false},
                  {"fieldId":"persona","chips":["다정함","장난기"],"rawText":"","skipped":false},
                  {"fieldId":"job","chips":[],"rawText":"바리스타","skipped":false},
                  {"fieldId":"dislikes","chips":["비"],"rawText":"","skipped":true}
                ]}""";
        String out = sut.describe(authored, "LIVELY", null, null, "~다냥 하고 끝맺음", null, null);
        assertThat(out).isEqualTo("""
                성격: 다정함, 장난기
                세계관: 카페. 골목 끝 작은 원두 가게
                직업: 바리스타
                말투: ~다냥 하고 끝맺음""");
    }

    @Test
    @DisplayName("예전 캐릭터는 성격 값을 짤 화면의 이름으로 바꾸고 예전 칸을 옮긴다")
    void 예전칸() {
        String out = sut.describe(null, "SHY", "COOL,GENTLE", "마법 학교", null, "판타지", "커피를 좋아함");
        assertThat(out).isEqualTo("""
                성격: 수줍음, 시크, 온순
                세계관: 마법 학교
                장르: 판타지
                그 밖에: 커피를 좋아함""");
    }

    @Test
    @DisplayName("적은 것이 없으면 빈 글이다")
    void 빈칸() {
        assertThat(sut.describe(null, null, null, null, null, null, null)).isEmpty();
    }
}
