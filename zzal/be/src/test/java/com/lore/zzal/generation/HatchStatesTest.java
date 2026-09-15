package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.client.PythonPostProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 부화 후처리 출력 목록(설정 주입) 검증.
 *
 * ★ 2026-08-25 의 병 — 설정 이름이 어긋나면 빌드·배포·기동이 전부 통과한 채 실제 호출 때만 터진다.
 *   여기서는 목록이 비었을 때 <b>부팅이 막히고 어느 설정을 고쳐야 하는지 말하는가</b>를 확인한다.
 */
@DisplayName("부화 후처리 출력 목록 — 설정 주입")
class HatchStatesTest {

    @Test
    @DisplayName("버전 키로 고른다 — 그 버전 줄만 읽는다")
    void picksByVersion() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.zzal.hatch.states.v1",
                        "base,eat,joy,sad,sick,pet,hello,sleep,"
                                + "eat_rice,eat_snack,sweep,wash,reply,petted,startle,wake_up")
                .withProperty("app.zzal.hatch.states.옛것", "idle,eat,hungry,clean,happy,sad,pet,train");

        assertThat(GenerationConfig.hatchStates(env, "v1")).hasSize(16).startsWith("base").endsWith("wake_up");
        assertThat(GenerationConfig.hatchStates(env, "옛것")).hasSize(8).startsWith("idle");
    }

    @Test
    @DisplayName("★ 실패 주입 — 쓰는 버전의 목록이 없으면 설정 이름을 말하며 막힌다")
    void missingListNamesTheProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.zzal.hatch.states.옛것", "idle,eat");

        assertThatThrownBy(() -> GenerationConfig.hatchStates(env, "v1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.hatch.states.v1");
    }

    @Test
    @DisplayName("★ 실패 주입 — 그 버전의 목록이 비면 내려받기 전에 막힌다(0종 완료로 조용히 성공하는 길을 막는다)")
    void processorRejectsEmptyStates() throws Exception {
        PipelineScripts scripts = mock(PipelineScripts.class);
        PythonPostProcessor p = new PythonPostProcessor(mock(S3Storage.class), scripts, "python3", 60,
                v -> v.equals("v1") ? List.of("base") : List.of());

        try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "없는버전")) {
            assertThatThrownBy(() -> s.split("images/zzal/pets/7/grid.png", List.of("base")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.zzal.hatch.states.없는버전");
            assertThatThrownBy(() -> s.split("images/zzal/pets/7/grid.png", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("★★ 실패 주입 — 자를 이름이 설정 목록에 없으면 내려받기 전에 멈춘다")
    void keysNotDeclaredInStatesAreRejected() throws Exception {
        // 카탈로그(자를 이름)와 설정(내놓기로 한 이름)이 갈리면 후처리는 성공하는데 화면이
        // 조립하는 주소에 파일이 없다. 오류가 안 나는 어긋남이라 여기서 막는다.
        PythonPostProcessor p = new PythonPostProcessor(mock(S3Storage.class), mock(PipelineScripts.class),
                "python3", 60, v -> List.of("base", "eat"));

        // ★ 앵커를 요구하지 않는 이름으로 연다 — 세션을 닫을 때의 앵커 검사가 이 시험의 관심사가 아니다.
        try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "옛버전")) {
            assertThatThrownBy(() -> s.split("images/zzal/pets/7/grid.png", List.of("base", "옛이름")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.zzal.hatch.states.옛버전")
                    .hasMessageContaining("옛이름");
        }
    }
}
