package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
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
    @DisplayName("버전 키로 고른다 — v1 은 8종, v2 는 카탈로그 key 16종")
    void picksByVersion() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.zzal.hatch.states.v1", "idle,eat,hungry,clean,happy,sad,pet,train")
                .withProperty("app.zzal.hatch.states.v2",
                        "base,eat,joy,sad,sick,practice,shy,call,tilt,wave,sleep,wash,startle,nod,smile_idle,sit");

        assertThat(GenerationConfig.hatchStates(env, "v1")).hasSize(8).startsWith("idle");
        assertThat(GenerationConfig.hatchStates(env, "v2")).hasSize(16).startsWith("base").endsWith("sit");
    }

    @Test
    @DisplayName("★ 실패 주입 — 쓰는 버전의 목록이 없으면 설정 이름을 말하며 막힌다")
    void missingListNamesTheProperty() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.zzal.hatch.states.v1", "idle,eat");

        assertThatThrownBy(() -> GenerationConfig.hatchStates(env, "v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.hatch.states.v2");
    }

    @Test
    @DisplayName("★ 실패 주입 — 그 버전의 목록이 비면 내려받기 전에 막힌다(0종 완료로 조용히 성공하는 길을 막는다)")
    void processorRejectsEmptyStates() {
        PipelineScripts scripts = mock(PipelineScripts.class);
        PythonPostProcessor p = new PythonPostProcessor(mock(S3Storage.class), scripts, "python3", 60,
                v -> v.equals("v1") ? List.of("idle") : List.of());

        assertThatThrownBy(() -> p.split("images/zzal/pets/7/grid.png", "images/zzal/pets/7", "v2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.zzal.hatch.states.v2");
        assertThatThrownBy(() -> p.split("images/zzal/pets/7/grid.png", "images/zzal/pets/7", "v2", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
