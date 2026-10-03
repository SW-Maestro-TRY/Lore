package com.lore.webtoon.work;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.story.StoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 번들을 이 환경에 심는다(#614). 부팅 시드와 관리자 API 가 같은 코드를 쓰므로 여기서 한 번에 못 박는다.
 *
 * <ul>
 *   <li>그림은 공개 자리에 새 키로 올리고, <b>「이미 심었다」 표시(그림 기록)는 맨 마지막</b>에 적는다.</li>
 *   <li>같은 작품은 건드리지 않는다. 검사만(dryRun) 하면 아무것도 안 쓴다.</li>
 *   <li>만든 과정이 있으면 후보 넷과 고른 번호를 그대로 심고, 서버 작업 폴더에 풀되 덮어쓰지 않는다.</li>
 * </ul>
 */
class ExampleImporterTest {

    @TempDir
    Path tmp;

    private PrivateArt art;
    private PageStore pages;
    private StoryStore stories;
    private WorkLedger ledger;
    private WebtoonJobRepository jobs;
    private HarnessProcess harness;
    private ExampleImporter importer;

    @BeforeEach
    void 세운다() throws Exception {
        art = mock(PrivateArt.class);
        AtomicInteger n = new AtomicInteger();
        when(art.upload(any(), anyString(), anyBoolean())).thenAnswer(i -> "images/webtoon/key-" + n.incrementAndGet());
        pages = mock(PageStore.class);
        stories = mock(StoryStore.class);
        ledger = mock(WorkLedger.class);
        jobs = mock(WebtoonJobRepository.class);
        when(jobs.findByPublicId(anyString())).thenReturn(Optional.empty());
        harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));
        importer = new ExampleImporter(art, pages, stories, ledger, jobs, harness);
    }

    private static ExampleBundle bundle(Map<String, byte[]> run, Map<String, Object> input) throws Exception {
        return new ExampleBundle(
                new ExampleBundle.Manifest(ExampleBundlesTest.RUN, "카페 사장에게는 비밀이 많다", "느와르", "레나", "frost",
                        "한 줄", List.of("둘째 장", "셋째 장"), input),
                List.of(new ExampleBundle.Page(1, 320, ExampleBundlesTest.jpeg()),
                        new ExampleBundle.Page(1, 1080, ExampleBundlesTest.jpeg()),
                        new ExampleBundle.Page(2, 1080, ExampleBundlesTest.jpeg())),
                run);
    }

    @Test
    @DisplayName("새 작품: 그림을 공개 자리에 올리고, 이야기·작업·예시 표시 뒤에 그림 기록을 맨 마지막에 적는다")
    void 새로_심는다() throws Exception {
        ExampleImporter.Result got = importer.importBundle(bundle(Map.of(), null), false);

        assertThat(got.status()).isEqualTo(ExampleImporter.Status.PLANTED);
        assertThat(got.pages()).isEqualTo(2);
        verify(art, org.mockito.Mockito.times(3)).upload(any(), eq("image/jpeg"), eq(true));

        InOrder order = inOrder(art, stories, ledger, pages);
        order.verify(art, org.mockito.Mockito.atLeastOnce()).upload(any(), anyString(), anyBoolean());
        order.verify(stories).save(eq(ExampleBundlesTest.RUN), anyList());
        order.verify(ledger).markExample(ExampleBundlesTest.RUN, true, null);
        order.verify(pages).record(eq(ExampleBundlesTest.RUN), anyList(), eq(true));   // 마지막
    }

    @Test
    @DisplayName("만든 과정이 없으면 후보 하나만 심고 고른 것으로 둔다")
    void 후보_하나만() throws Exception {
        importer.importBundle(bundle(Map.of(), null), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> got = ArgumentCaptor.forClass(List.class);
        verify(stories).save(eq(ExampleBundlesTest.RUN), got.capture());
        assertThat(got.getValue()).hasSize(1);
        verify(stories).choose(ExampleBundlesTest.RUN, 1);
    }

    @Test
    @DisplayName("작업 줄에는 작품 번호와 실제 입력값을 적는다 — 입력값이 없으면 이름만")
    void 작업_줄() throws Exception {
        importer.importBundle(bundle(Map.of(), Map.of("name", "레나", "character", "조폭 보스")), false);

        ArgumentCaptor<WebtoonJob> job = ArgumentCaptor.forClass(WebtoonJob.class);
        verify(jobs).save(job.capture());
        assertThat(job.getValue().getRunId()).isEqualTo(ExampleBundlesTest.RUN);
        assertThat(job.getValue().getStyle()).isEqualTo("frost");
        assertThat(job.getValue().getInputJson()).contains("조폭 보스");
        assertThat(job.getValue().getPublicId()).isEqualTo("example-" + ExampleBundlesTest.RUN);
    }

    @Test
    @DisplayName("만든 과정에 후보 넷이 있으면 그대로 심고 고른 번호를 고른다 — 줄거리가 비면 소개글로")
    void 실제_후보를_심는다() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("directions.json", """
                [{"n":1,"title":"하나","genre":"g","intro":"소개 하나","plot":""},
                 {"n":2,"title":"둘","genre":"g","intro":"소개  둘","plot":"있는 줄거리"},
                 {"n":3,"title":"셋","genre":"g","intro":"소개 셋","plot":""},
                 {"n":4,"title":"넷","genre":"g","intro":"소개 넷","plot":""}]
                """.getBytes(StandardCharsets.UTF_8));
        run.put("pick.json", "{\"n\":3}".getBytes(StandardCharsets.UTF_8));

        importer.importBundle(bundle(run, null), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object>> got = ArgumentCaptor.forClass(List.class);
        verify(stories).save(eq(ExampleBundlesTest.RUN), got.capture());
        assertThat(got.getValue()).hasSize(4);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) got.getValue().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) got.getValue().get(1);
        assertThat(first.get("plot")).isEqualTo("소개 하나");              // 비어 있으면 소개글
        assertThat(second.get("plot")).isEqualTo("있는 줄거리");           // 있으면 그대로
        verify(stories).choose(ExampleBundlesTest.RUN, 3);
        verify(stories).setScenes(ExampleBundlesTest.RUN, List.of("둘째 장", "셋째 장"));
    }

    @Test
    @DisplayName("후보를 그대로 심어도 화면 제목·줄거리는 번들 값을 쓴다 — 후보 원래 제목과 다르면 고친 값 자리에")
    void 번들_제목이_정본이다() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("directions.json", "[{\"n\":1,\"title\":\"후보 원래 제목\",\"intro\":\"소개\"}]".getBytes(StandardCharsets.UTF_8));
        com.lore.webtoon.story.WebtoonStory chosen = com.lore.webtoon.story.WebtoonStory.of(
                ExampleBundlesTest.RUN, 1, "후보 원래 제목", "g", "소개", "[]", "[]", java.time.Instant.now());
        when(stories.chosenOf(ExampleBundlesTest.RUN)).thenReturn(Optional.of(chosen));

        importer.importBundle(bundle(run, null), false);

        verify(stories).editTitle(ExampleBundlesTest.RUN, "카페 사장에게는 비밀이 많다");
        verify(stories).editPlot(ExampleBundlesTest.RUN, "한 줄");
    }

    @Test
    @DisplayName("고른 번호가 기존 후보 줄에 없어 고른 이야기가 비면 첫 후보를 고른다 — 화면 제목이 비지 않게")
    void 고른_이야기가_비면_첫_후보() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("directions.json", "[{\"n\":1,\"title\":\"t\"}]".getBytes(StandardCharsets.UTF_8));
        run.put("pick.json", "{\"n\":3}".getBytes(StandardCharsets.UTF_8));
        when(stories.chosenOf(ExampleBundlesTest.RUN)).thenReturn(Optional.empty());

        importer.importBundle(bundle(run, null), false);

        verify(stories).choose(ExampleBundlesTest.RUN, 3);
        verify(stories).choose(ExampleBundlesTest.RUN, 1);
    }

    @Test
    @DisplayName("이미 있는 작품은 건드리지 않는다 — 예시 표시도 말없이 켜지 않는다(남의 작품일 수 있다)")
    void 이미_있으면_건드리지_않는다() throws Exception {
        when(pages.has(ExampleBundlesTest.RUN)).thenReturn(true);

        ExampleImporter.Result got = importer.importBundle(bundle(Map.of(), null), false);

        assertThat(got.status()).isEqualTo(ExampleImporter.Status.EXISTS);
        verify(art, never()).upload(any(), anyString(), anyBoolean());
        verify(stories, never()).save(anyString(), anyList());
        verify(pages, never()).record(anyString(), anyList(), anyBoolean());
        verify(ledger, never()).markExample(anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("검사만 하면(dryRun) 아무것도 쓰지 않는다")
    void 검사만_한다() throws Exception {
        Map<String, byte[]> run = Map.of("scenes.json", "{}".getBytes(StandardCharsets.UTF_8));

        ExampleImporter.Result got = importer.importBundle(bundle(run, null), true);

        assertThat(got.status()).isEqualTo(ExampleImporter.Status.DRY_RUN);
        verify(art, never()).upload(any(), anyString(), anyBoolean());
        verify(stories, never()).save(anyString(), anyList());
        verify(ledger, never()).markExample(anyString(), anyBoolean(), any());
        assertThat(tmp.resolve("runs")).doesNotExist();
    }

    @Test
    @DisplayName("그림을 못 올리면 던지고, 그림 기록(= 심었다는 표시)은 적지 않는다 — 다시 돌리면 이어서 한다")
    void 올리기가_실패하면_표시하지_않는다() throws Exception {
        when(art.upload(any(), anyString(), anyBoolean())).thenReturn(null);

        assertThatThrownBy(() -> importer.importBundle(bundle(Map.of(), null), false))
                .isInstanceOf(IllegalStateException.class);
        verify(pages, never()).record(anyString(), anyList(), anyBoolean());
        verify(stories, never()).save(anyString(), anyList());
    }

    @Test
    @DisplayName("만든 과정은 서버 작업 폴더에 풀고, 이미 있으면 덮지 않는다")
    void 작품_폴더를_풀되_덮지_않는다() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("scenes.json", "{\"scenes\":[]}".getBytes(StandardCharsets.UTF_8));
        run.put("input.json", "{\"name\":\"레나\"}".getBytes(StandardCharsets.UTF_8));

        ExampleImporter.Result first = importer.importBundle(bundle(run, null), false);
        Path scenes = tmp.resolve("runs").resolve(ExampleBundlesTest.RUN).resolve("scenes.json");
        assertThat(first.runFolderRestored()).isTrue();
        assertThat(scenes).exists();

        Files.writeString(scenes, "{\"scenes\":[\"고친 것\"]}");              // 심은 뒤 고쳤다
        when(pages.has(ExampleBundlesTest.RUN)).thenReturn(true);
        ExampleImporter.Result again = importer.importBundle(bundle(run, null), false);

        assertThat(again.runFolderRestored()).isFalse();
        assertThat(Files.readString(scenes)).contains("고친 것");              // 되돌아가지 않는다
    }

    @Test
    @DisplayName("작업 폴더 밖으로 나가는 경로는 풀지 않는다 — 번들 검사를 우회해 들어와도")
    void 폴더_밖으로_안_푼다() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("../evil.txt", new byte[]{1});

        ExampleImporter.Result got = importer.importBundle(bundle(run, null), false);

        assertThat(got.runFolderRestored()).isFalse();
        assertThat(tmp.resolve("runs").resolve("evil.txt")).doesNotExist();
    }
}
