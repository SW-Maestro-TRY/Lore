package com.lore.webtoon.runs;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WebtoonWorkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 다 만든 작품을 <b>하네스 폴더 없이</b> 보여 줄 수 있는가.
 *
 * 그게 이 클래스의 존재 이유다 — 그 폴더가 없어져도 목록과 완성본이 그대로
 * 나와야 그 폴더를 작업대로 다룰 수 있다.
 */
class RunServiceTest {

    private WebtoonWorkRepository works;
    private WebtoonJobRepository jobs;
    private StoryStore stories;
    private PageStore pages;
    private RunService runs;
    private com.lore.webtoon.series.SeriesChapterRepository series;

    @BeforeEach
    void 세운다() {
        works = mock(WebtoonWorkRepository.class);
        jobs = mock(WebtoonJobRepository.class);
        stories = mock(StoryStore.class);
        pages = mock(PageStore.class);
        series = mock(com.lore.webtoon.series.SeriesChapterRepository.class);
        when(pages.dimensionsOf(org.mockito.ArgumentMatchers.any())).thenReturn(new int[]{0, 0});
        runs = new RunService(works, jobs, stories, pages, mock(com.lore.webtoon.work.RunLikeRepository.class));
    }

    private WebtoonWork 작품(String runId, boolean isPublic) {
        WebtoonWork work = mock(WebtoonWork.class);
        when(work.getRunId()).thenReturn(runId);
        when(work.getJobId()).thenReturn("job-" + runId);
        when(work.isPublic()).thenReturn(isPublic);
        when(works.findFirstByRunId(runId)).thenReturn(Optional.of(work));

        WebtoonJob job = mock(WebtoonJob.class);
        when(job.getStyle()).thenReturn("romance_fantasy");
        when(job.getInputJson()).thenReturn("{\"name\":\"유리엘\",\"genre\":\"로판\"}");
        when(jobs.findByPublicId("job-" + runId)).thenReturn(Optional.of(job));
        when(job.getPublicId()).thenReturn("job-" + runId);

        WebtoonStory story = mock(WebtoonStory.class);
        when(story.getTitle()).thenReturn("얼음 왕자의 계약");
        when(story.displayTitle()).thenReturn("얼음 왕자의 계약");
        when(story.getGenre()).thenReturn("로맨스 판타지");
        when(story.displayPlot()).thenReturn("계약으로 시작된 관계가 진심이 된다");
        when(stories.chosenOf(runId)).thenReturn(Optional.of(story));
        return work;
    }

    @Test
    @DisplayName("마이페이지 카드는 같은 연재의 완성 회차를 한 작품으로 묶는다")
    void 연재_회차를_작품_카드에_묶는다() {
        WebtoonWork root = 작품("run-1", false);
        WebtoonWork next = mock(WebtoonWork.class);
        when(next.getRunId()).thenReturn("run-2");
        when(next.getJobId()).thenReturn("job-run-2");
        when(next.isTrashed()).thenReturn(false);
        when(works.findFirstByRunId("run-2")).thenReturn(Optional.of(next));
        when(works.findByJobId("job-run-1")).thenReturn(Optional.of(root));
        when(works.findByJobId("job-run-2")).thenReturn(Optional.of(next));
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2));
        when(pages.pageNumbersOf("run-2")).thenReturn(List.of(1, 2));
        WebtoonJob nextJob = mock(WebtoonJob.class);
        when(nextJob.getPublicId()).thenReturn("job-run-2");
        when(nextJob.getStyle()).thenReturn("romance_fantasy");
        when(jobs.findByPublicId("job-run-2")).thenReturn(Optional.of(nextJob));
        var chapter = mock(com.lore.webtoon.series.SeriesChapter.class);
        when(chapter.jobId()).thenReturn("job-run-2");
        when(chapter.rootJob()).thenReturn("job-run-1");
        when(chapter.episodeNumber()).thenReturn(2);
        when(series.findById("job-run-2")).thenReturn(Optional.of(chapter));
        when(series.findByRootJobOrderByEpisodeNumberAsc("job-run-1")).thenReturn(List.of(chapter));
        runs.setSeries(series);

        Map<String, Object> card = runs.cardOf("run-2");

        assertThat(card).containsEntry("run_id", "run-1")
                .containsEntry("episodes", List.of(1, 2))
                .containsEntry("episode_run_ids", Map.of("1", "run-1", "2", "run-2"));

        // 목록용(마이페이지 · 찜 · 댓글): 같은 연재의 다른 화는 들고 있던 1화 카드를 그대로 받는다
        Map<String, Map<String, Object>> roots = new java.util.HashMap<>();
        Map<String, Object> first = runs.cardOf("run-2", roots);
        assertThat(first).containsEntry("run_id", "run-1");
        assertThat(runs.cardOf("run-1", roots)).isSameAs(first);
        assertThat(roots).hasSize(1);
    }

    @Test
    @DisplayName("둘러보기도 연재를 1화 카드 하나로 묶고, 비공개 회차는 번호를 싣지 않는다")
    void 둘러보기는_연재를_묶는다() {
        WebtoonWork root = 작품("run-1", true);
        WebtoonWork two = 작품("run-2", true);
        WebtoonWork three = 작품("run-3", false);
        when(works.findByJobId("job-run-1")).thenReturn(Optional.of(root));
        when(works.findByJobId("job-run-2")).thenReturn(Optional.of(two));
        when(works.findByJobId("job-run-3")).thenReturn(Optional.of(three));
        for (String r : List.of("run-1", "run-2", "run-3")) {
            when(pages.pageNumbersOf(r)).thenReturn(List.of(1, 2));
        }
        var c2 = mock(com.lore.webtoon.series.SeriesChapter.class);
        when(c2.jobId()).thenReturn("job-run-2");
        when(c2.rootJob()).thenReturn("job-run-1");
        when(c2.episodeNumber()).thenReturn(2);
        var c3 = mock(com.lore.webtoon.series.SeriesChapter.class);
        when(c3.jobId()).thenReturn("job-run-3");
        when(c3.rootJob()).thenReturn("job-run-1");
        when(c3.episodeNumber()).thenReturn(3);
        when(series.findById("job-run-1")).thenReturn(Optional.empty());
        when(series.findById("job-run-2")).thenReturn(Optional.of(c2));
        when(series.findById("job-run-3")).thenReturn(Optional.of(c3));
        when(series.findByRootJobOrderByEpisodeNumberAsc("job-run-1")).thenReturn(List.of(c2, c3));
        runs.setSeries(series);
        // 공개된 것만 둘러보기에 온다 — 2화가 1화보다 먼저 와도 카드는 하나다.
        when(works.onGallery()).thenReturn(List.of(two, root));

        List<Map<String, Object>> cards = runs.browse();

        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst()).containsEntry("run_id", "run-1")
                .containsEntry("episodes", List.of(1, 2))
                .containsEntry("episode_run_ids", Map.of("1", "run-1", "2", "run-2"));
    }

    @Test
    @DisplayName("1화에 그림이 없으면 공개된 회차를 따로 낸다 — 연재가 통째로 사라지면 안 된다")
    void 그림_없는_1화() {
        WebtoonWork root = 작품("run-1", true);
        WebtoonWork two = 작품("run-2", true);
        when(works.findByJobId("job-run-1")).thenReturn(Optional.of(root));
        when(works.findByJobId("job-run-2")).thenReturn(Optional.of(two));
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of());
        when(pages.pageNumbersOf("run-2")).thenReturn(List.of(1, 2));
        var c2 = mock(com.lore.webtoon.series.SeriesChapter.class);
        when(c2.jobId()).thenReturn("job-run-2");
        when(c2.rootJob()).thenReturn("job-run-1");
        when(c2.episodeNumber()).thenReturn(2);
        when(series.findById("job-run-1")).thenReturn(Optional.empty());
        when(series.findById("job-run-2")).thenReturn(Optional.of(c2));
        when(series.findByRootJobOrderByEpisodeNumberAsc("job-run-1")).thenReturn(List.of(c2));
        runs.setSeries(series);
        when(works.onGallery()).thenReturn(List.of(two, root));

        assertThat(runs.browse()).extracting(c -> c.get("run_id")).containsExactly("run-2");
    }

    @Test
    @DisplayName("연재 2화는 작품 제목이 1화 제목이고, 이 화가 지은 제목은 소제목이다")
    void 연재_소제목() {
        WebtoonWork root = 작품("run-1", true);
        WebtoonWork two = 작품("run-2", true);
        WebtoonStory own = mock(WebtoonStory.class);
        when(own.displayTitle()).thenReturn("교무실 앞에서 울린 이름");
        when(stories.chosenOf("run-2")).thenReturn(Optional.of(own));
        when(works.findByJobId("job-run-1")).thenReturn(Optional.of(root));
        when(pages.pageNumbersOf("run-2")).thenReturn(List.of(1, 2));
        var c2 = mock(com.lore.webtoon.series.SeriesChapter.class);
        when(c2.rootJob()).thenReturn("job-run-1");
        when(series.findById("job-run-1")).thenReturn(Optional.empty());
        when(series.findById("job-run-2")).thenReturn(Optional.of(c2));
        runs.setSeries(series);
        when(two.getDeletedAt()).thenReturn(java.time.Instant.now());

        Map<String, Object> card = runs.trashCards(List.of(two)).getFirst();

        assertThat(card).containsEntry("title", "얼음 왕자의 계약")
                .containsEntry("subtitle", "교무실 앞에서 울린 이름");
    }

    @Test
    @DisplayName("목록 카드를 DB 만으로 채운다 — 그림체 딱지까지")
    void 목록() {
        /* 짜 넣는 것을 먼저 다 만들고 나서 stub 한다 — when(...) 안에서 또
           when(...) 을 부르면 Mockito 가 앞의 것을 못 끝낸 것으로 본다. */
        WebtoonWork work = 작품("run-1", true);
        when(works.onGallery()).thenReturn(List.of(work));
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3));

        Map<String, Object> card = runs.browse().getFirst();

        assertThat(card).containsEntry("run_id", "run-1")
                .containsEntry("character", "유리엘")
                .containsEntry("title", "얼음 왕자의 계약")
                .containsEntry("genre", "로맨스 판타지")
                .containsEntry("style_label", "로맨스 판타지")
                .containsEntry("page_count", 3)
                .containsEntry("cover_page", 1);
        // 둘러보기 카드에는 공개 여부를 안 싣는다 — 거기 있는 것은 다 공개다.
        assertThat(card).doesNotContainKey("public");
    }

    @Test
    @DisplayName("그림이 한 장도 없는 작품은 목록에서 뺀다 — 눌러도 빈 화면이 열린다")
    void 그림_없는_것은_뺀다() {
        WebtoonWork work = 작품("run-1", true);
        when(works.onGallery()).thenReturn(List.of(work));
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of());

        assertThat(runs.browse()).isEmpty();
    }

    @Test
    @DisplayName("내 목록에는 비공개도 나오고, 걸려 있는지도 같이 준다")
    void 내_목록() {
        WebtoonWork work = 작품("run-1", false);
        when(works.madeBy("uid-1")).thenReturn(List.of(work));
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2));

        assertThat(runs.madeBy("uid-1").getFirst()).containsEntry("public", false);
    }

    @Test
    @DisplayName("uid 가 없으면 빈 목록 — 남의 것을 대신 보여 주지 않는다")
    void uid_없으면_빈_것() {
        assertThat(runs.madeBy(null)).isEmpty();
        assertThat(runs.madeBy("  ")).isEmpty();
    }

    @Test
    @DisplayName("완성본의 캡션은 2장부터 — 1장은 표지라 장면이 없다")
    void 완성본_캡션() {
        작품("run-1", true);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3));
        when(stories.scenesOf("run-1")).thenReturn(List.of("첫 장면", "둘째 장면"));

        Map<String, Object> out = runs.result("run-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets = (List<Map<String, Object>>) out.get("pages");
        assertThat(sheets).extracting(p -> p.get("caption"))
                .containsExactly("", "첫 장면", "둘째 장면");
        assertThat(sheets).allSatisfy(p -> assertThat(p)
                .containsEntry("gap", 0.0).containsEntry("width", 1));
        assertThat(out).containsEntry("logline", "계약으로 시작된 관계가 진심이 된다")
                .containsEntry("page_count", 3)
                // 이 길에는 「앞 몇 장만」이 없다 — 둘이 같아야 화면이 그 말을 안 적는다.
                .containsEntry("planned_pages", 3)
                .containsEntry("preview", false)
                .containsEntry("episode", 1);
    }

    @Test
    @DisplayName("예시 작품만 example 이 참이다 — 화면은 예시일 때만 남의 작품에도 내려받기를 보인다")
    void 예시_작품_표시() {
        WebtoonWork mine = 작품("run-1", true);
        when(mine.getBrowserUid()).thenReturn("browser-1");
        WebtoonWork seeded = 작품("run-2", true);
        when(seeded.getBrowserUid()).thenReturn("lore-example-seed");
        when(pages.pageNumbersOf(anyString())).thenReturn(List.of(1));

        assertThat(runs.result("run-1")).containsEntry("example", false);
        assertThat(runs.result("run-2")).containsEntry("example", true);
    }

    @Test
    @DisplayName("장면이 장 수보다 적어도 안 죽는다 — 빈 캡션으로 둔다")
    void 장면이_모자랄_때() {
        작품("run-1", true);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3, 4));
        when(stories.scenesOf("run-1")).thenReturn(List.of("첫 장면"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets =
                (List<Map<String, Object>>) runs.result("run-1").get("pages");

        assertThat(sheets).extracting(p -> p.get("caption"))
                .containsExactly("", "첫 장면", "", "");
    }

    @Test
    @DisplayName("편집실에서 고친 여백이 공개본 장 사이 간격으로 나간다")
    void 고친_여백() {
        작품("run-1", true);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3));
        when(stories.scenesOf("run-1")).thenReturn(List.of());
        OverlayStore overlays = mock(OverlayStore.class);
        when(overlays.gapsOf("run-1", 1)).thenReturn(Map.of(1, 3, 2, 1));
        runs.setOverlays(overlays);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sheets =
                (List<Map<String, Object>>) runs.result("run-1").get("pages");

        assertThat(sheets).extracting(p -> p.get("gap")).containsExactly(0.62, 0.07, 0.0);
    }

    @Test
    @DisplayName("그림이 없으면 완성본도 없다 — 부르는 쪽이 404 를 낸다")
    void 없는_완성본() {
        when(pages.pageNumbersOf(anyString())).thenReturn(List.of());
        assertThat(runs.result("없는작품")).isNull();
    }

    @Test
    @DisplayName("편집실 데이터 — 페이지당 컷 하나, 1장은 표지")
    void 편집실_데이터() {
        작품("run-1", true);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3));
        when(stories.scenesOf("run-1")).thenReturn(List.of("첫 장면", "둘째 장면"));

        Map<String, Object> out = runs.episode("run-1", 1);

        assertThat(out).containsEntry("run_id", "run-1")
                .containsEntry("character", "유리엘")
                .containsEntry("title", "얼음 왕자의 계약")
                .containsEntry("episode", 1)
                .containsEntry("episodes", List.of(1))
                .containsEntry("gap_scale", Map.of("0", 0.0, "1", 0.07, "2", 0.26, "3", 0.62))
                .containsEntry("page_count", 3);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scenes = (List<Map<String, Object>>) out.get("scenes");
        assertThat(scenes).hasSize(3);
        assertThat(scenes).extracting(s -> s.get("no")).containsExactly(1, 2, 3);
        assertThat(scenes).extracting(s -> s.get("image"))
                .containsExactly(
                        "/api/runs/run-1/page/1?w=1080",
                        "/api/runs/run-1/page/2?w=1080",
                        "/api/runs/run-1/page/3?w=1080");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstCuts = (List<Map<String, Object>>) scenes.get(0).get("cuts");
        assertThat(firstCuts).singleElement().satisfies(cut ->
                assertThat(cut).containsEntry("no", 1).containsEntry("description", "표지"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondCuts = (List<Map<String, Object>>) scenes.get(1).get("cuts");
        assertThat(secondCuts).singleElement().satisfies(cut ->
                assertThat(cut).containsEntry("description", "첫 장면"));
    }

    @Test
    @DisplayName("그림이 없으면 편집실도 없다 — 부르는 쪽이 404 를 낸다")
    void 없는_편집실() {
        when(pages.pageNumbersOf(anyString())).thenReturn(List.of());
        assertThat(runs.episode("없는작품", 1)).isNull();
    }

    @Test
    @DisplayName("이야기를 아직 못 옮겨 온 옛 작품도 열린다 — 제목만 기본값이다")
    void 이야기가_없어도_열린다() {
        WebtoonWork work = mock(WebtoonWork.class);
        when(work.getRunId()).thenReturn("옛작품");
        when(work.getJobId()).thenReturn("job-옛");
        when(works.findFirstByRunId("옛작품")).thenReturn(Optional.of(work));
        when(jobs.findByPublicId("job-옛")).thenReturn(Optional.empty());
        when(stories.chosenOf("옛작품")).thenReturn(Optional.empty());
        when(stories.scenesOf("옛작품")).thenReturn(List.of());
        when(pages.pageNumbersOf("옛작품")).thenReturn(List.of(1));

        assertThat(runs.result("옛작품"))
                .containsEntry("title", "1화")
                .containsEntry("character", "")
                .containsEntry("style_label", "")
                .containsEntry("logline", "");
    }

    @Test
    @DisplayName("휴지통에 든 작품은 결과·회차·카드가 모두 없는 작품처럼 null 이다(#157)")
    void 휴지통_작품은_안_보인다() {
        WebtoonWork work = 작품("run-1", true);
        when(work.isTrashed()).thenReturn(true);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2));

        assertThat(runs.isTrashed("run-1")).isTrue();
        assertThat(runs.result("run-1")).isNull();
        assertThat(runs.episode("run-1", 1)).isNull();
        assertThat(runs.cardOf("run-1")).isNull();
    }

    @Test
    @DisplayName("장면은 있는데 그림이 없는 장을 이어 그릴 장으로 준다 — 표지가 1장, 장면 n 은 n+1 장")
    void 안_그린_장() {
        List<String> scenes = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");   // 장면 10 = 11장

        assertThat(RunService.pendingPages(List.of(1, 2, 3, 4, 5, 6, 7, 8), scenes))
                .containsExactly(9, 10, 11);
        assertThat(RunService.pendingPages(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), scenes)).isEmpty();
        // 장면을 모르는 옛 작품은 이어 그릴 것이 없다
        assertThat(RunService.pendingPages(List.of(1, 2, 3), List.of())).isEmpty();
    }
}
