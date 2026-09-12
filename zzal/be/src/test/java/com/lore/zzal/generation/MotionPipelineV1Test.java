package com.lore.zzal.generation;

import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심화·선물 동작(16프레임) 파이프라인 v1 — 2026-09-12 구르기 확정분.
 *
 * <h3>★ 이 테스트가 지키는 것 = 이름이 어긋나 조용히 죽는 길을 막는 것</h3>
 * 16프레임 쪽은 조각이 셋으로 나뉘어 있다 — 골격(grid16.txt) · 동작 블록(motions/*.txt) ·
 * 후처리 스크립트. 셋이 각자 멀쩡해도 <b>서로를 가리키는 이름</b>이 어긋나면 굽기는 성공하고
 * 그림만 이상해진다. 그 길들을 여기서 묶는다.
 *   1) 카탈로그가 만드는 지시문 경로 ↔ 실제 파일 자리
 *   2) 골격의 {IDENT}·{MOTION} 자리 ↔ MotionGridStep 이 갈아끼우는 자리
 *   3) 서비스 후처리가 부르는 스크립트 ↔ 판정본을 만든 그 스크립트·그 옵션
 */
@DisplayName("모션 파이프라인 v1 — 16프레임 골격·구르기 지시문·후처리")
class MotionPipelineV1Test {

    private static final String VERSION = "v1";

    private static String resource(String path) throws IOException {
        return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("★ 카탈로그가 가리키는 자리에 구르기 지시문이 실제로 있다")
    void rollPromptSitsWhereTheCatalogLooks() throws Exception {
        MotionSpec roll = new MotionCatalog("", "", VERSION).byKey("roll").orElseThrow();

        // 카탈로그의 promptFile 과 파일 이름이 어긋나면 밤 큐에 올린 순간 부팅이 막힌다.
        String path = "zzal/prompt/%s/motions/%s.txt".formatted(VERSION, roll.promptFile());
        assertThat(new ClassPathResource(path).exists()).as(path).isTrue();
        assertThat(roll.promptFile()).isEqualTo("구르기");
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
    @DisplayName("★ 조립 결과 = 판정받은 전달본 — 골격에 구르기 블록을 끼운 그 글자 그대로")
    void assembledPromptMatchesTheJudgedShape() throws Exception {
        String skeleton = resource("zzal/prompt/v1/grid16.txt");
        String block = new MotionCatalog("", "roll", VERSION).block("roll");

        // MotionGridStep 과 같은 순서·같은 다듬기(trim)로 끼운다.
        String assembled = skeleton.replace("{IDENT}", "IDENT_HERE").replace("{MOTION}", block.trim());

        assertThat(assembled).doesNotContain("{IDENT}").doesNotContain("{MOTION}");
        // 판정본 5판의 실제 전달본은 이 조립과 글자 단위로 같았다(2026-09-12 실측).
        // 블록이 통째로 들어갔는지만 여기서 지킨다 — 길이가 줄면 문장이 잘린 것이다.
        assertThat(assembled).contains("ROLL ANCHOR").contains("THE BALL (cells 3-13)");
        assertThat(assembled.length()).isEqualTo(skeleton.length() - "{IDENT}".length() - "{MOTION}".length()
                + "IDENT_HERE".length() + block.trim().length());
    }

    @Test
    @DisplayName("★ 서비스 후처리가 부르는 것 = 판정본 v02 를 만든 state16_v2 · --align foot")
    void motionPostUsesTheJudgedScriptAndOption() throws Exception {
        String service = resource("zzal/pipeline/v1/service_motion_post.py");

        // v1(state16_post)은 격자점을 다 못 지워 상훈님이 '오른쪽 아래 검은 점'을 잡으셨다.
        // 여기가 도로 v1 을 부르면 그 점이 되살아나는데, 그건 화면을 확대해 봐야만 드러난다.
        assertThat(service).contains("import state16_v2");
        assertThat(service).contains("ALIGN = \"foot\"");
        assertThat(service).contains("state16_v2.main(str(work_grid), align=ALIGN, duration=FRAME_MS)");

        // 스크립트가 다른 파일을 import 하므로 그 파일들도 같이 배포돼야 한다.
        for (String need : new String[]{"state16_v2.py", "state8_v3.py", "state8_v4.py", "state8_v5.py"}) {
            assertThat(new ClassPathResource("zzal/pipeline/v1/" + need).exists()).as(need).isTrue();
        }
    }

    @Test
    @DisplayName("자바가 찾는 결과물 이름과 스크립트가 쓰는 이름이 같다")
    void outputNameMatches() throws Exception {
        assertThat(resource("zzal/pipeline/v1/service_motion_post.py"))
                .contains("OUTPUT_NAME = \"motion.webp\"");
    }
}
