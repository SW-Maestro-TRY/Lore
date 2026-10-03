package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 장면 하나 다시 뽑기의 이전 판(#548).
 *
 * 하네스는 다시 뽑을 때 지금 판을 {@code history} 에 쌓는다. <b>이 파일이 지키는 것은
 * 「화면이 이전 판을 받고, 되돌려도 아무 판도 사라지지 않으며, 뒤 장면이 되돌린 판의 끝에서
 * 시작한다」다.</b>
 */
class SceneHistoryTest {

    @TempDir
    Path tmp;

    private JobRunner runner;
    private Path scenes;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void 세운다() throws Exception {
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));
        runner = new JobRunner(harness, mock(JobProgress.class), mock(JobStore.class),
                mock(StoryStore.class), mock(AfterRun.class), mock(WorkLedger.class),
                mock(CreditGate.class), mock(GuestGate.class), mock(JobNotice.class),
                1, 1, tmp.resolve("jobs").toString());
        Path run = Files.createDirectories(tmp.resolve("runs").resolve("run-1"));
        scenes = run.resolve("scenes.json");
        Files.writeString(scenes, """
                {"scenes": [
                  {"n": 1, "where": "1곳", "ends": "1끝"},
                  {"n": 2, "prev": "1끝", "where": "새 곳", "ends": "새 끝",
                   "history": [{"n": 2, "prev": "1끝", "where": "처음 곳", "ends": "처음 끝"}]},
                  {"n": 3, "prev": "새 끝", "where": "3곳", "ends": "3끝"}
                ]}
                """);
    }

    @Test
    @DisplayName("화면은 장면마다 이전 판을 받는다")
    @SuppressWarnings("unchecked")
    void 이전_판을_내려준다() {
        List<Map<String, Object>> view = runner.scenesOf("run-1");
        List<Map<String, Object>> history = (List<Map<String, Object>>) view.get(1).get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("text").toString()).contains("처음 곳");
        assertThat((List<?>) view.get(0).get("history")).isEmpty();
        assertThat(history.get(0).get("ver")).isEqualTo(1);       // 번호가 없던 판은 쌓인 순서로
        assertThat(view.get(1).get("ver")).isEqualTo(2);
    }

    @Test
    @DisplayName("되돌리면 고른 판이 지금 판이 되고, 지금 판은 판 목록 끝에 남는다 — 뒤 장면은 되돌린 끝에서 시작")
    void 되돌린다() throws Exception {
        runner.restoreScene("run-1", 2, 1);

        JsonNode s = mapper.readTree(scenes.toFile()).path("scenes");
        assertThat(s.get(1).path("where").asText()).isEqualTo("처음 곳");
        assertThat(s.get(1).path("history")).hasSize(1);
        assertThat(s.get(1).path("history").get(0).path("where").asText()).isEqualTo("새 곳");
        assertThat(s.get(1).path("history").get(0).has("history")).isFalse();
        assertThat(s.get(2).path("prev").asText()).isEqualTo("처음 끝");
        // 판 이름은 글을 따라간다 — 처음 판은 1, 다시 뽑은 판은 2
        assertThat(s.get(1).path("ver").asInt()).isEqualTo(1);
        assertThat(s.get(1).path("history").get(0).path("ver").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("없는 판은 거절한다")
    void 없는_판() {
        assertThatThrownBy(() -> runner.restoreScene("run-1", 2, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.restoreScene("run-1", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
