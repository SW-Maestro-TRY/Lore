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

    @BeforeEach
    void 세운다() {
        works = mock(WebtoonWorkRepository.class);
        jobs = mock(WebtoonJobRepository.class);
        stories = mock(StoryStore.class);
        pages = mock(PageStore.class);
        runs = new RunService(works, jobs, stories, pages);
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

        WebtoonStory story = mock(WebtoonStory.class);
        when(story.getTitle()).thenReturn("얼음 왕자의 계약");
        when(story.displayTitle()).thenReturn("얼음 왕자의 계약");
        when(story.getGenre()).thenReturn("로맨스 판타지");
        when(story.getPlot()).thenReturn("계약으로 시작된 관계가 진심이 된다");
        when(stories.chosenOf(runId)).thenReturn(Optional.of(story));
        return work;
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
                .containsEntry("gap", 0).containsEntry("width", 1));
        assertThat(out).containsEntry("logline", "계약으로 시작된 관계가 진심이 된다")
                .containsEntry("page_count", 3)
                // 이 길에는 「앞 몇 장만」이 없다 — 둘이 같아야 화면이 그 말을 안 적는다.
                .containsEntry("planned_pages", 3)
                .containsEntry("preview", false)
                .containsEntry("episode", 1);
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
    @DisplayName("그림이 없으면 완성본도 없다 — 부르는 쪽이 404 를 낸다")
    void 없는_완성본() {
        when(pages.pageNumbersOf(anyString())).thenReturn(List.of());
        assertThat(runs.result("없는작품")).isNull();
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
}
