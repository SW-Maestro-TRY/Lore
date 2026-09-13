package com.lore.zzal.generation;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 심화·선물 동작(16프레임) 파이프라인 v1 — 2026-09-12 선물 2종 확정분(구르기 · 뒤로넘어짐 v6b).
 *
 * <h3>★ 이 테스트가 지키는 것 = 이름이 어긋나 조용히 죽는 길을 막는 것</h3>
 * 16프레임 쪽은 조각이 셋으로 나뉘어 있다 — 골격(grid16.txt) · 동작 블록(motions/*.txt) ·
 * 후처리 스크립트. 셋이 각자 멀쩡해도 <b>서로를 가리키는 이름</b>이 어긋나면 굽기는 성공하고
 * 그림만 이상해진다. 그 길들을 여기서 묶는다.
 *   1) 카탈로그가 만드는 지시문 경로 ↔ 실제 파일 자리
 *   2) 골격의 {IDENT}·{MOTION} 자리 ↔ MotionGridStep 이 갈아끼우는 자리
 *   3) 서비스 후처리가 부르는 스크립트 ↔ 확정본을 만든 그 스크립트·그 옵션
 *   4) 동작별 후처리 프로파일 표 ↔ 확정된 그 조건 (표에 없는 동작은 굽지 않는다)
 */
@DisplayName("모션 파이프라인 v1 — 16프레임 골격·선물 2종 지시문·동작별 후처리")
class MotionPipelineV1Test {

    private static final String VERSION = "v1";

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ 카탈로그가 가리키는 자리에 선물 2종 지시문이 실제로 있다")
    void giftPromptsSitWhereTheCatalogLooks() {
        MotionCatalog catalog = new MotionCatalog("", "", VERSION);

        // 카탈로그의 promptFile 과 파일 이름이 어긋나면 밤 큐에 올린 순간 부팅이 막힌다.
        for (String key : new String[]{"roll", "fall_back"}) {
            MotionSpec spec = catalog.byKey(key).orElseThrow();
            String path = "zzal/prompt/%s/motions/%s.txt".formatted(VERSION, spec.promptFile());
            assertThat(new ClassPathResource(path).exists()).as(path).isTrue();
        }
        assertThat(catalog.byKey("roll").orElseThrow().promptFile()).isEqualTo("구르기");
        assertThat(catalog.byKey("fall_back").orElseThrow().promptFile()).isEqualTo("뒤로넘어짐");
    }

    @Test
    @DisplayName("★ 16프레임 골격 — {IDENT}·{MOTION} 두 자리와 게이트 사양 줄이 있다")
    void grid16HasBothSlots() throws Exception {
        String skeleton = resource("zzal/prompt/v1/grid16.txt");

        // 자리 이름이 어긋나면 치환이 안 된 채로 나가고, 모델은 "{MOTION}" 이라는 글자를 그리려 든다.
        assertThat(skeleton).contains("{IDENT}").contains("{MOTION}");
        assertThat(skeleton).contains("# GRID_SPEC: marks=25 layout=lattice_5x5");
    }

    @Test
    @DisplayName("★ 조립 결과 = 확정된 전달본 — 골격에 구르기 블록을 끼운 그 글자 그대로")
    void assembledPromptMatchesTheJudgedShape() throws Exception {
        String skeleton = resource("zzal/prompt/v1/grid16.txt");
        String block = new MotionCatalog("", "roll,fall_back", VERSION).block("roll");

        // MotionGridStep 과 같은 순서·같은 다듬기(trim)로 끼운다.
        String assembled = skeleton.replace("{IDENT}", "IDENT_HERE").replace("{MOTION}", block.trim());

        assertThat(assembled).doesNotContain("{IDENT}").doesNotContain("{MOTION}");
        // 확정본 5판의 실제 전달본은 이 조립과 글자 단위로 같았다(2026-09-12 실측).
        // 블록이 통째로 들어갔는지만 여기서 지킨다 — 길이가 줄면 문장이 잘린 것이다.
        assertThat(assembled).contains("ROLL ANCHOR").contains("THE BALL (cells 3-13)");
        assertThat(assembled.length()).isEqualTo(skeleton.length() - "{IDENT}".length() - "{MOTION}".length()
                + "IDENT_HERE".length() + block.trim().length());
    }

    @Test
    @DisplayName("★ 후처리 프로파일 = 확정된 그 스크립트·그 옵션 (구르기=발 · 넘어짐=접지앵커)")
    void profilesMatchTheJudgedConditions() {
        MotionPostProfiles profiles = new MotionPostProfiles();

        // 구르기 v02 를 만든 조건. state16_v2 의 자체 기본값은 align=none 이라 꼭 적혀 있어야 한다.
        assertThat(profiles.forMotion(VERSION, "roll"))
                .isEqualTo("script=state16_v2, align=foot");
        // 뒤로넘어짐 v6b 를 만든 조건. 뒤로 눕는 칸에서 발 기준이 통째로 오판되므로 접지앵커로 맞춘다.
        assertThat(profiles.forMotion(VERSION, "fall_back"))
                .isEqualTo("script=state16_v3, cut=marks, align=seat, seat-lock=global");
    }

    @Test
    @DisplayName("★★ 표에 없는 동작은 기본값으로 굽지 않는다 — 파일 이름과 빠진 key 를 말하며 멈춘다")
    void unlistedMotionRefusesToBake() {
        MotionPostProfiles profiles = new MotionPostProfiles();

        // 기본 프로파일을 두면, 새 동작을 확정하고 줄을 깜빡했을 때 검수를 거치지 않은 후처리로
        // 구워진 그림이 그대로 나간다. 굽기는 성공하고 로그도 깨끗하다.
        assertThatThrownBy(() -> profiles.forMotion(VERSION, "sit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zzal/pipeline/v1/motion_post_profiles.txt")
                .hasMessageContaining("sit");
    }

    @Test
    @DisplayName("★ 후처리 스크립트가 프로파일을 요구하고, 두 후처리만 부를 수 있다")
    void motionPostRequiresAProfile() throws Exception {
        String service = resource("zzal/pipeline/v1/service_motion_post.py");

        // v1(state16_post)은 격자점을 다 못 지워 '오른쪽 아래 검은 점'이 검수에서 잡혔다.
        // 여기가 도로 v1 을 부르거나 기본값으로 떨어지면 그 점이 되살아나는데,
        // 그건 화면을 확대해 봐야만 드러난다.
        assertThat(service).contains("ALLOWED_SCRIPTS = (\"state16_v2\", \"state16_v3\")");
        assertThat(service).contains("module.main(str(work_grid), duration=FRAME_MS, **options)");
        assertThat(service).contains("--profile 이 없습니다");

        // 프로파일이 고를 수 있는 스크립트와 그것들이 import 하는 파일이 전부 배포돼야 한다.
        for (String need : new String[]{"state16_v2.py", "state16_v3.py",
                "state8_v3.py", "state8_v4.py", "state8_v5.py"}) {
            assertThat(new ClassPathResource("zzal/pipeline/v1/" + need).exists()).as(need).isTrue();
        }
    }

    @Test
    @DisplayName("★ 프로파일이 고르는 script 는 전부 실제로 있는 파일이다")
    void everyProfileScriptExists() {
        MotionPostProfiles profiles = new MotionPostProfiles();

        for (String key : new String[]{"roll", "fall_back"}) {
            String script = java.util.Arrays.stream(profiles.forMotion(VERSION, key).split(","))
                    .map(String::trim).filter(t -> t.startsWith("script="))
                    .map(t -> t.substring("script=".length())).findFirst().orElseThrow();
            assertThat(new ClassPathResource("zzal/pipeline/v1/" + script + ".py").exists())
                    .as("%s → %s.py", key, script).isTrue();
        }
    }

    @Test
    @DisplayName("자바가 찾는 결과물 이름과 스크립트가 쓰는 이름이 같다")
    void outputNameMatches() throws Exception {
        assertThat(resource("zzal/pipeline/v1/service_motion_post.py"))
                .contains("OUTPUT_NAME = \"motion.webp\"");
    }
}
