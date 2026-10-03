package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 나눈 장면이 고른 이야기의 장면 줄에 옮겨 적히는가(#607).
 *
 * 옮겨 적지 않으면 편집실에 「이 장의 장면 설명이 없어요」가 뜬다. 예전에는 own 길이 장면 확인을
 * 마칠 때만 적어서 「확인하고 만들기」·「바로 만들기」 작품은 전부 비어 있었다.
 */
class SceneCaptionSyncTest {

    @TempDir
    Path tmp;

    private StoryStore stories;
    private JobRunner runner;

    @BeforeEach
    void 세운다() throws Exception {
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));
        stories = mock(StoryStore.class);
        runner = new JobRunner(harness, mock(JobProgress.class), mock(JobStore.class),
                stories, mock(AfterRun.class), mock(WorkLedger.class),
                mock(CreditGate.class), mock(GuestGate.class), mock(JobNotice.class),
                1, 1, tmp.resolve("jobs").toString());
        Path run = Files.createDirectories(tmp.resolve("runs").resolve("run-1"));
        Files.writeString(run.resolve("scenes.json"), """
                {"scenes": [
                  {"n": 1, "where": "비 오는 골목", "what": "첫 일", "ends": "문 앞"},
                  {"n": 2, "user_text": "사람이 고친 둘째 장면", "where": "무시됨"}
                ]}
                """);
    }

    @Test
    @DisplayName("장면이 있으면 고른 이야기의 장면 줄로 옮겨 적는다 — 고친 글이 있으면 그것이 이긴다")
    @SuppressWarnings("unchecked")
    void 옮겨_적는다() {
        runner.syncSceneCaptions("run-1");

        ArgumentCaptor<List<String>> got = ArgumentCaptor.forClass(List.class);
        verify(stories).setScenes(anyString(), got.capture());
        assertThat(got.getValue()).hasSize(2);
        assertThat(got.getValue().get(0)).contains("비 오는 골목").contains("첫 일");
        assertThat(got.getValue().get(1)).isEqualTo("사람이 고친 둘째 장면");
    }

    @Test
    @DisplayName("장면 파일이 없으면 아무것도 적지 않는다")
    void 파일이_없으면_건너뛴다() {
        runner.syncSceneCaptions("없는-작품");
        verify(stories, never()).setScenes(anyString(), any());
    }

    @Test
    @DisplayName("옛 작품 채우기: 장면 줄이 빈 작품만 작품 폴더의 장면으로 채운다")
    void 옛_작품을_채운다() throws Exception {
        when(stories.runIdsWithoutScenes()).thenReturn(List.of("run-1", "폴더-없음"));

        new SceneCaptionBackfill(stories, runner, true).run(null);

        verify(stories).setScenes(org.mockito.ArgumentMatchers.eq("run-1"), any());
        verify(stories, never()).setScenes(org.mockito.ArgumentMatchers.eq("폴더-없음"), any());
    }

    @Test
    @DisplayName("채우기를 껐으면 아무것도 안 한다")
    void 끄면_안_한다() {
        new SceneCaptionBackfill(stories, runner, false).run(null);
        verify(stories, never()).runIdsWithoutScenes();
    }
}
