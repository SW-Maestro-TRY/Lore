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
    @DisplayName("18종 고정 — 1층 8 · 2층 8 · 선물 2. ★ 순서가 곧 격자 칸 순서다")
    void fixedEighteen() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.all()).hasSize(18);
        assertThat(catalog.basic()).hasSize(16);
        assertThat(catalog.gifts()).hasSize(2);
        assertThat(catalog.all().stream().filter(m -> m.layer() == MotionLayer.BASIC_1)).hasSize(8);
        assertThat(catalog.all().stream().filter(m -> m.layer() == MotionLayer.BASIC_2)).hasSize(8);
        // ★★ containsExactly — 한 칸만 밀려도 후처리가 전부 다른 자세로 저장하고, 그것은 화면을 봐야만 드러난다.
        assertThat(catalog.basicKeys()).containsExactly(
                "base", "eat", "joy", "sad", "sick", "pet", "hello", "sleep",
                "eat_rice", "eat_snack", "sweep", "wash", "reply", "petted", "startle", "wake_up");
        assertThat(catalog.bySeq(101)).map(MotionSpec::key).contains("roll");
        assertThat(catalog.bySeq(102)).map(MotionSpec::key).contains("fall_back");
    }

    @Test
    @DisplayName("★ 빠진 여덟 이름은 카탈로그에 없다 — 화면이 없는 그림을 가리키지 않게")
    void retiredKeysAreGone() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(List.of("practice", "call", "tilt", "nod", "smile_idle", "sit", "wave", "shy"))
                .allSatisfy(key -> assertThat(catalog.byKey(key))
                        .as("%s 는 카탈로그에서 빠졌다", key)
                        .isEmpty());
    }

    @Test
    @DisplayName("★ 2층 여덟의 조건 — 전부 그 행동 자체다. 다른 행동으로 열리는 자세가 없다")
    void layerTwoRules() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.byKey("eat_rice").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.FEEDS, 9));
        assertThat(catalog.byKey("eat_snack").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.SNACKS, 9));
        assertThat(catalog.byKey("sweep").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.CLEANS, 13));
        assertThat(catalog.byKey("wash").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.BATH, 3));
        assertThat(catalog.byKey("reply").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.CHAT_ANSWERS, 4));
        assertThat(catalog.byKey("petted").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.PET_COUNT, 4));
        assertThat(catalog.byKey("startle").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.GAME_STARTS, 4));
        assertThat(catalog.byKey("wake_up").orElseThrow().unlockRule())
                .isEqualTo(UnlockRule.of(UnlockRule.Kind.WAKES, 4));

        assertThat(catalog.byKey("eat_rice").orElseThrow().unlockRule().hint()).isEqualTo("밥 주기 9회");
        assertThat(catalog.byKey("wake_up").orElseThrow().unlockRule().hint()).isEqualTo("깨우기 4회");
        assertThat(catalog.byKey("base").orElseThrow().unlockRule().hint()).isNull();
    }

    @Test
    @DisplayName("★ 1층 여덟은 전부 처음부터 열려 있다 — 조건이 붙은 칸이 하나도 없다")
    void layerOneIsAlwaysOpen() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        assertThat(catalog.basic().stream().filter(m -> m.layer() == MotionLayer.BASIC_1))
                .hasSize(8)
                .allSatisfy(m -> assertThat(m.unlockRule().kind())
                        .as("%s", m.key()).isEqualTo(UnlockRule.Kind.ALWAYS));
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
        // 3층 심화 동작의 지시문은 아직 하나도 안 들어왔다(기본 행동 16종 = 검수 대기).
        assertThatThrownBy(() -> new MotionCatalog("hello", "", "v1"))
                .hasMessageContaining("zzal/prompt/v1/motions/인사.txt")
                .hasMessageContaining("advanced-motions");
    }

    @Test
    @DisplayName("★ 선물 2종 — 지시문이 들어왔으므로 gift-motions 에 올려도 부팅이 되고 블록이 읽힌다")
    void giftMotionsAreBakeable() {
        // 2026-09-12 구르기 확정 · 뒤로넘어짐 v6b 확정.
        // 지시문 = prompt/v1/motions/{구르기,뒤로넘어짐}.txt
        MotionCatalog catalog = new MotionCatalog("", "roll,fall_back", "v1");

        assertThat(catalog.giftKeys()).containsExactly("roll", "fall_back");
        assertThat(catalog.isBakeable("roll")).isTrue();
        assertThat(catalog.isBakeable("fall_back")).isTrue();
        // 16프레임 골격이 {MOTION} 자리에 끼울 블록 — 형식이 깨지면 모델이 16칸을 제멋대로 채운다.
        assertThat(catalog.block("roll"))
                .startsWith("TASK:").contains("MUST CHANGE").contains("NEVER CHANGE");
        assertThat(catalog.block("fall_back")).startsWith("TASK:");
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
    @DisplayName("v1 부화 펫의 폴백 — legacyFile 매핑은 5종, v1 에 없던 자세는 화면 폴백")
    void legacyFileMapping() {
        MotionCatalog catalog = new MotionCatalog("", "", "v1");

        List<String> withLegacy = catalog.basic().stream()
                .filter(MotionSpec::hasLegacyFile).map(MotionSpec::key).toList();
        assertThat(withLegacy).containsExactly("base", "eat", "joy", "sad", "pet");
        assertThat(catalog.byKey("base").orElseThrow().legacyFile()).isEqualTo("idle");
        // ★ 교감 자세는 옛 shy 가 쓰던 v1 파일(pet)을 그대로 물려받는다 — v1 펫이 계속 설명된다.
        assertThat(catalog.byKey("pet").orElseThrow().legacyFile()).isEqualTo("pet");
    }
}
