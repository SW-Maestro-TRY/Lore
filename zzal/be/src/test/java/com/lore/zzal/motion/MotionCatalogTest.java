package com.lore.zzal.motion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 카탈로그 고정 18종과 설정 검증.
 *
 * ★ 여기서 잡으려는 것은 <b>설정 이름이 어긋난 채 조용히 뜨는 서버</b>다. 목록에 오타가 있어도
 *   부팅이 되면 그 동작은 영영 안 굽히고, 그 사실은 사용자가 밤을 기다린 뒤에야 드러난다.
 *   그래서 부팅 때 막히는지, 막힐 때 <b>어느 설정을 고쳐야 하는지</b> 말하는지를 확인한다.
 */
@DisplayName("동작 카탈로그 — 고정 18종과 설정 검증")
class MotionCatalogTest {

    @Test
    @DisplayName("18종 고정 — 1층 8 · 2층 8 · 선물 2, seq 는 13장 번호")
    void fixedEighteen() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.all()).hasSize(18);
        assertThat(catalog.basic()).hasSize(16);
        assertThat(catalog.gifts()).hasSize(2);
        assertThat(catalog.all().stream().filter(m -> m.layer() == MotionLayer.BASIC_1)).hasSize(8);
        assertThat(catalog.all().stream().filter(m -> m.layer() == MotionLayer.BASIC_2)).hasSize(8);
        assertThat(catalog.basicKeys()).containsExactly(
                "base", "eat", "joy", "sad", "sick", "practice", "shy", "call",
                "tilt", "wave", "sleep", "wash", "startle", "nod", "smile_idle", "sit");
        assertThat(catalog.bySeq(101)).map(MotionSpec::key).contains("roll");
        assertThat(catalog.bySeq(102)).map(MotionSpec::key).contains("fall_back");
    }

    @Test
    @DisplayName("2층 조건은 정본 6장 표 그대로")
    void layerTwoRules() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.byKey("tilt").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.CHAT_ANSWERS, 1));
        assertThat(catalog.byKey("nod").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.CHAT_ANSWERS, 12));
        assertThat(catalog.byKey("sleep").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.SLEEP_WAKE, 3));
        assertThat(catalog.byKey("sit").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.LAYER2_OPEN, 6));
        assertThat(catalog.byKey("tilt").orElseThrow().unlockRule().hint()).isEqualTo("채팅 응답 1회");
        assertThat(catalog.byKey("base").orElseThrow().unlockRule().hint()).isNull();
    }

    @Test
    @DisplayName("비어 있으면 아무것도 안 굽는다 — 그건 정상 상태다")
    void emptyIsNormal() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.advancedKeys()).isEmpty();
        assertThat(catalog.giftKeys()).isEmpty();
        assertThat(catalog.isBakeable("roll")).isFalse();
    }

    @Test
    @DisplayName("★ 실패 주입 — 모르는 key 가 설정에 있으면 부팅이 막히고, 어느 설정인지 말한다")
    void unknownKeyFailsBootWithPropertyName() {
        assertThatThrownBy(() -> new MotionCatalog("base,rolll", "", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.advanced-motions")
                .hasMessageContaining("rolll");
    }

    @Test
    @DisplayName("★ 실패 주입 — 선물 목록에 선물 아닌 동작이 섞이면 막힌다")
    void giftListRejectsNonGift() {
        assertThatThrownBy(() -> new MotionCatalog("", "base", "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.gift-motions")
                .hasMessageContaining("base");
    }

    @Test
    @DisplayName("★ 실패 주입 — 지시문 파일이 없으면 굽기 직전이 아니라 부팅 때 막힌다")
    void missingPromptFailsBoot() {
        // v1 프롬프트 폴더에는 아직 '구르기.txt' 가 없다(생성 세션 G-2 몫).
        assertThatThrownBy(() -> new MotionCatalog("", "roll", "v1"))
                .hasMessageContaining("zzal/prompt/v1/motions/구르기.txt")
                .hasMessageContaining("gift-motions");
    }

    @Test
    @DisplayName("카탈로그에 없는 이름의 지시문은 찾지 않는다 — 가능한 값을 말하며 거절(v1 옛 이름 폴백은 PR-3 에서 제거)")
    void unknownKeyIsRejected() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThatThrownBy(() -> catalog.block("교감1_머리쓰다듬"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("카탈로그에 없는 동작")
                .hasMessageContaining("base");
    }

    @Test
    @DisplayName("v1 부화 펫의 폴백 — legacyFile 매핑은 6종, 아픔·부르기는 없다")
    void legacyFileMapping() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        List<String> withLegacy = catalog.basic().stream()
                .filter(MotionSpec::hasLegacyFile).map(MotionSpec::key).toList();
        assertThat(withLegacy).containsExactly("base", "eat", "joy", "sad", "practice", "shy");
        assertThat(catalog.byKey("base").orElseThrow().legacyFile()).isEqualTo("idle");
        assertThat(catalog.byKey("shy").orElseThrow().legacyFile()).isEqualTo("pet");
    }
}
