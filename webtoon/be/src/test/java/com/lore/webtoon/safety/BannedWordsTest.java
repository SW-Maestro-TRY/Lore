package com.lore.webtoon.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 한국어 금지어(#626). 막아야 할 것은 막고, 평범한 문장 — 특히 비슷하게 생긴 말 — 은 통과해야 한다. */
class BannedWordsTest {

    @ParameterizedTest
    @DisplayName("성인물 요청은 걸린다 — 띄어쓰기를 섞어도")
    @ValueSource(strings = {
            "노출이 심한 옷만 입는다. 노골적인 성행위 장면으로 그려 줘.",
            "매 장면을 야한 베드신으로 그려 주세요",
            "알몸으로 침대에 누워 있는 장면 위주로",
            "주인공 둘이 성관계를 하는 과정을 자세히",
            "성인 웹툰처럼 수위 높게, 19금 장면 위주로",
            "옷을 하나씩 벗기는 장면을 클로즈업으로",
            "나체로 샤워하는 장면을 정면에서 그려 줘",
            "성 관 계 장면",
            "야한 그림으로",
            "섹 스 하는 장면"})
    void 걸린다(String text) {
        assertThat(BannedWords.find(text)).isNotNull();
    }

    @ParameterizedTest
    @DisplayName("평범한 문장과 비슷하게 생긴 말은 통과한다")
    @ValueSource(strings = {
            "두 사람이 첫 키스를 하는 장면으로 끝나게 해 주세요",
            "황태자와 하녀의 신분을 넘는 사랑. 밤에 몰래 정원에서 만난다",
            "샤워를 마치고 나온 주인공이 젖은 머리로 거울을 본다",
            "남주가 셔츠 단추를 풀며 피곤하다고 한숨을 쉰다",
            "침대에서 열이 나는 동생을 간호하는 오빠",
            "노출이 적은 옷을 입은 사진으로 바꿔 주세요",
            "성기사가 신전을 지킨다",
            "전라도 사투리를 쓰는 할머니",
            "누드톤 메이크업을 한 아이돌",
            "고통에 신음하며 쓰러진 기사",
            "자위대 출신 용병",
            "사과 껍질을 벗기는 손",
            "꼭 해야 한 장면이 있다",
            "섹시한 악당 보스",
            "검으로 몬스터를 베는 장면, 피가 조금 튄다"})
    void 통과한다(String text) {
        assertThat(BannedWords.find(text)).isNull();
    }
}
