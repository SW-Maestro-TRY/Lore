package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.job.WebtoonQuality;
import com.lore.webtoon.job.WebtoonStyles;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import com.lore.webtoon.work.ExampleWorks;
import com.lore.webtoon.work.RunLikeRepository;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WebtoonWorkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 다 만들어진 작품을 보여 준다 — 둘러보기 목록과 완성본.
 *
 * <h2>하네스 폴더를 안 읽는다</h2>
 *
 * 파이썬은 이 둘을 작품 폴더의 파일 여럿을 이어 붙여 만들었다
 * ({@code newharness_pipeline.result_by_run}: input.json · pick.json ·
 * directions.json · style.txt · 그림 파일 목록). 그 폴더가 없어지면 다 만든
 * 작품이 통째로 안 보인다 — 그 폴더는 작업대지 창고가 아니다.
 *
 * 여기서는 <b>이미 DB 에 옮겨 담아 둔 것</b>만 본다.
 *
 * <pre>
 *   webtoon_work   누가 만들었나 · 둘러보기에 걸려 있나
 *   webtoon_job    무슨 그림체로 · 어떤 캐릭터로
 *   webtoon_story  제목 · 장르 · 줄거리 · 장면
 *   webtoon_page   몇 장이 실제로 그려졌나 · 그림이 S3 어디에 있나
 * </pre>
 *
 * <h2>화면이 읽던 이름을 그대로 쓴다</h2>
 *
 * 완성본·둘러보기 화면은 프로토타입에서 옮겨 온 것이라 파이썬이 내보내던
 * 이름(밑줄)을 그대로 읽는다. 그래서 봉투를 안 씌우고 {@code Map} 으로
 * 내보낸다 — {@code JobController} 와 같은 이유다.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    /** 제목이 없을 때. 파이썬이 쓰던 것과 같은 말이다. */
    private static final String NO_TITLE = "1화";

    private final WebtoonWorkRepository works;
    private final WebtoonJobRepository jobs;
    private final StoryStore stories;
    private final PageStore pages;
    private final RunLikeRepository likes;
    private com.lore.webtoon.series.SeriesChapterRepository series;
    private OverlayStore overlays;
    /* 편집실에서 고친 여백을 공개본에 싣는다. 검사에서 손으로 만들 때는 없어도 된다(여백 0). */
    @org.springframework.beans.factory.annotation.Autowired
    public void setOverlays(OverlayStore overlays) { this.overlays = overlays; }
    @org.springframework.beans.factory.annotation.Autowired
    public void setSeries(com.lore.webtoon.series.SeriesChapterRepository series) { this.series=series; }
    private RunViews views;
    /* 둘러보기 조회순(2026-10-26). 검사에서 손으로 만들 때는 없어도 된다(조회수 0). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setViews(RunViews views) { this.views = views; }
    private String rootJobOf(WebtoonJob job) {
        if (job == null || series == null) return job == null ? null : job.getPublicId();
        return series.findById(job.getPublicId()).map(com.lore.webtoon.series.SeriesChapter::rootJob)
                .orElse(job.getPublicId());
    }

    private WebtoonWork rootWorkOf(WebtoonWork work) {
        WebtoonJob job = jobs.findByPublicId(work.getJobId()).orElse(null);
        String rootJob = rootJobOf(job);
        if (rootJob == null || rootJob.equals(work.getJobId())) return work;
        return works.findByJobId(rootJob)
                .filter(root -> root.getRunId() != null && !root.isTrashed())
                .orElse(work);
    }

    private List<Integer> episodesOf(String rootJob, boolean publicOnly) {
        List<Integer> episodes = new ArrayList<>();
        episodes.add(1);
        if (series == null || rootJob == null) return episodes;
        for (var chapter : series.findByRootJobOrderByEpisodeNumberAsc(rootJob)) {
            if (works.findByJobId(chapter.jobId())
                    .filter(work -> work.getRunId() != null && !work.isTrashed())
                    .filter(work -> !publicOnly || work.isPublic())
                    .filter(work -> !pages.pageNumbersOf(work.getRunId()).isEmpty())
                    .isPresent()) {
                episodes.add(chapter.episodeNumber());
            }
        }
        return episodes;
    }

    /** 연재 카드에서 회차 버튼이 실제 작품을 열 수 있도록 회차별 run_id 를 붙인다. */
    private Map<String, String> episodeRunIdsOf(String rootJob, String rootRunId, boolean publicOnly) {
        Map<String, String> runIds = new LinkedHashMap<>();
        runIds.put("1", rootRunId);
        if (series == null || rootJob == null) return runIds;
        for (var chapter : series.findByRootJobOrderByEpisodeNumberAsc(rootJob)) {
            works.findByJobId(chapter.jobId())
                    .filter(work -> work.getRunId() != null && !work.isTrashed())
                    .filter(work -> !publicOnly || work.isPublic())
                    .filter(work -> !pages.pageNumbersOf(work.getRunId()).isEmpty())
                    .ifPresent(work -> runIds.put(String.valueOf(chapter.episodeNumber()), work.getRunId()));
        }
        return runIds;
    }
    /**
     * 연재 2화부터의 작품 제목 — 1화 제목이다(2026-10-26). 화마다 AI 가 새 제목을 지어서 같은 작품이 화마다
     * 다른 이름으로 보였다. 이 화가 지은 제목은 소제목(subtitle)으로 옮긴다. 연재가 아니거나 1화면 null.
     */
    private String seriesTitleOf(WebtoonJob job) {
        String root = rootJobOf(job);
        if (job == null || root == null || root.equals(job.getPublicId())) return null;
        return works.findByJobId(root).map(WebtoonWork::getRunId).filter(java.util.Objects::nonNull)
                .flatMap(stories::chosenOf).map(WebtoonStory::displayTitle)
                .filter(t -> !t.isBlank()).orElse(null);
    }

    /** title 을 작품 제목으로, 이 화의 제목을 subtitle 로 — 연재 2화부터만. */
    private void putSeriesTitle(Map<String, Object> out, WebtoonJob job) {
        String series = seriesTitleOf(job);
        if (series != null) {
            out.put("subtitle", out.get("title"));
            out.put("title", series);
        }
    }

    private int episodeOf(WebtoonJob job) {
        return job == null || series == null ? 1 : series.episodeOf(job.getPublicId()).orElse(1);
    }
    private final ObjectMapper mapper = new ObjectMapper();

    public RunService(WebtoonWorkRepository works, WebtoonJobRepository jobs,
                      StoryStore stories, PageStore pages, RunLikeRepository likes) {
        this.works = works;
        this.jobs = jobs;
        this.stories = stories;
        this.pages = pages;
        this.likes = likes;
    }

    /**
     * 둘러보기 목록.
     *
     * <b>그림이 한 장도 없는 것은 빼고 준다.</b> 만들다 만 작품이 목록에 뜨면
     * 눌러도 빈 화면이 열린다 — 여는 사람에게는 그냥 고장으로 보인다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> browse() {
        /* 연재는 카드 하나로 묶는다 — 회차마다 카드가 따로 뜨면 한 작품이 여러 작품처럼
           보인다. 1화 카드에 공개된 회차 번호를 모으고, 회차 단추가 그 회차를 연다.
           1화가 비공개·삭제면 묶을 자리가 없으니 회차를 따로 둔다. */
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        /* 1화 카드는 연재마다 한 번만 만든다 — 회차마다 다시 만들면 23화 연재 하나에
           회차 조회가 수천 번 나간다. 1화에 그림이 없어 카드가 안 나오면(null) 기억해 두고
           회차를 따로 낸다. */
        Map<String, Map<String, Object>> rootCards = new java.util.HashMap<>();
        for (WebtoonWork one : works.onGallery()) {
            if (one.isTrashed()) {
                continue;
            }
            WebtoonJob job = jobs.findByPublicId(one.getJobId()).orElse(null);
            String rootJob = rootJobOf(job);
            WebtoonWork root = rootJob == null ? null
                    : rootJob.equals(one.getJobId()) ? one
                    : works.findByJobId(rootJob)
                        .filter(r -> r.getRunId() != null && !r.isTrashed() && r.isPublic())
                        .orElse(null);
            Map<String, Object> card = null;
            if (root != null) {
                card = rootCards.computeIfAbsent(rootJob, k -> {
                    Map<String, Object> c = cardIgnoringTrash(root, false, true, true);
                    return c == null ? Map.of() : c;
                });
                if (card.isEmpty()) {
                    card = null;
                }
            }
            if (card == null) {
                card = cardIgnoringTrash(one, false, false, false);
            }
            if (card != null && seen.add(String.valueOf(card.get("run_id")))) {
                out.add(card);
            }
        }
        putViews(out);
        return out;
    }

    /** 조회수 — 연재 카드는 실린 회차를 본 수를 더한다. 1화 카드는 묶음 사이에 같이 쓰므로 새 지도로 바꿔 싣는다. */
    private void putViews(List<Map<String, Object>> cards) {
        Map<String, Long> seenBy = views == null ? Map.of() : views.all();
        for (int i = 0; i < cards.size(); i++) {
            Map<String, Object> card = new LinkedHashMap<>(cards.get(i));
            java.util.Collection<?> runIds = card.get("episode_run_ids") instanceof Map<?, ?> m && !m.isEmpty()
                    ? m.values() : List.of(card.get("run_id"));
            long n = 0;
            for (Object id : runIds) {
                n += seenBy.getOrDefault(String.valueOf(id), 0L);
            }
            card.put("views", n);
            cards.set(i, card);
        }
    }

    /**
     * 이 브라우저가 만든 것 — <b>비공개도 포함한다.</b> 내 목록이라서다.
     *
     * ⚠️ 이것은 소유 증명이 아니다. uid 는 브라우저가 들고 다니는 값이라 남의
     * 것을 적어 보낼 수 있다({@code BrowserLink} 머리 주석). 목록에 모아
     * 보여 주는 것까지고, 바꾸는 일은 로그인을 거친다({@code /my/...}).
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> madeBy(String browserUid) {
        if (browserUid == null || browserUid.isBlank()) {
            return List.of();
        }
        return cardsOf(works.madeBy(browserUid.trim()), true);
    }

    private List<Map<String, Object>> cardsOf(List<WebtoonWork> found, boolean withPublic) {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WebtoonWork one : found) {
            Map<String, Object> card = card(one, withPublic, withPublic);
            if (card != null && (!withPublic || seen.add(String.valueOf(card.get("run_id"))))) {
                out.add(card);
            }
        }
        return out;
    }

    /**
     * 작품 번호 하나로 카드 하나. 아직 DB 에 안 옮겨 온 작품이면 {@code null}
     * — 그때는 부르는 쪽이 예전처럼 하네스에게 묻는다.
     *
     * 목록을 거치지 않고 한 편만 필요한 자리가 있다(마이페이지가 계정에 이어진
     * 브라우저별로 모을 때). 카드 만드는 규칙이 두 벌이 되면 같은 작품이
     * 둘러보기와 마이페이지에서 다르게 보인다 — 실제로 마이페이지 카드에는
     * 캐릭터 이름과 그림체 딱지가 늘 비어 있었다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cardOf(String runId) {
        return works.findFirstByRunId(runId)
                .map(work -> card(work, false, true))
                .orElse(null);
    }

    /**
     * 목록 하나를 만들 때 쓰는 {@link #cardOf(String)} — 연재 1화 카드를 {@code roots} 에 들고 있다가
     * 같은 연재의 다른 화에서는 그 카드를 그대로 준다(2026-10-27).
     *
     * 마이페이지는 작품마다 카드를 만든 뒤 같은 연재를 하나로 합친다. 연재 카드는 만들 때마다 회차를
     * 전부 조회하므로, 34화 연재(멘토님 「슈퍼 세컨드브레인」)면 34 × 34 번 조회가 나갔다.
     * 둘러보기({@link #browse()})의 rootCards 와 같은 방법이다. {@code roots} 는 부르는 쪽이 한 번 쓰고 버린다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cardOf(String runId, Map<String, Map<String, Object>> roots) {
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        if (work == null || work.isTrashed()) {
            return null;
        }
        WebtoonWork root = rootWorkOf(work);
        String key = root.getRunId();
        if (!roots.containsKey(key)) {
            roots.put(key, cardIgnoringTrash(root, false, true));
        }
        return roots.get(key);
    }

    /**
     * 휴지통에 든 작품인가(#157). 들었으면 결과·회차·장 주소가 모두 없는 작품처럼
     * 404 를 낸다 — 공유 링크로 들어온 사람에게도 지운 작품이 안 보여야 한다.
     */
    @Transactional(readOnly = true)
    public boolean isTrashed(String runId) {
        return works.findFirstByRunId(runId).map(WebtoonWork::isTrashed).orElse(false);
    }

    /** 휴지통 카드. 목록 카드와 같은 모양에 지운 시각이 붙는다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trashCards(List<WebtoonWork> trashed) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebtoonWork one : trashed) {
            Map<String, Object> card = cardIgnoringTrash(one, true);
            if (card != null) {
                card.put("deleted_at", one.getDeletedAt().toString());
                out.add(card);
            }
        }
        return out;
    }

    /** 카드 하나. 그림이 없으면 {@code null} — 목록에 안 올린다. */
    private Map<String, Object> card(WebtoonWork work, boolean withPublic, boolean groupSeries) {
        if (work.isTrashed()) {
            return null;
        }
        return cardIgnoringTrash(groupSeries ? rootWorkOf(work) : work, withPublic, groupSeries);
    }

    private Map<String, Object> cardIgnoringTrash(WebtoonWork work, boolean withPublic) {
        return cardIgnoringTrash(work, withPublic, false);
    }

    private Map<String, Object> cardIgnoringTrash(WebtoonWork work, boolean withPublic, boolean groupSeries) {
        return cardIgnoringTrash(work, withPublic, groupSeries, false);
    }

    /** {@code publicOnly} — 남이 보는 목록이라 비공개 회차는 번호도 안 싣는다. */
    private Map<String, Object> cardIgnoringTrash(WebtoonWork work, boolean withPublic, boolean groupSeries,
                                                  boolean publicOnly) {
        String runId = work.getRunId();
        List<Integer> numbers = pages.pageNumbersOf(runId);
        if (numbers.isEmpty()) {
            return null;
        }
        WebtoonJob job = jobs.findByPublicId(work.getJobId()).orElse(null);
        Optional<WebtoonStory> chosen = stories.chosenOf(runId);

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("run_id", runId);
        card.put("character", characterOf(job));
        card.put("title", chosen.map(WebtoonStory::displayTitle).filter(s -> !s.isBlank())
                .orElse(NO_TITLE));
        putSeriesTitle(card, job);
        card.put("genre", chosen.map(WebtoonStory::getGenre).orElse(""));
        // 연재는 첫 작품 카드 하나에 완성된 회차 번호를 모아 보여 준다.
        card.put("episodes", groupSeries ? episodesOf(rootJobOf(job), publicOnly) : List.of(episodeOf(job)));
        card.put("episode_run_ids", groupSeries
                ? episodeRunIdsOf(rootJobOf(job), runId, publicOnly)
                : Map.of(String.valueOf(episodeOf(job)), runId));
        card.put("episode", episodeOf(job));
        card.put("cover_episode", 1);
        card.put("cover_page", numbers.getFirst());
        card.put("page_count", numbers.size());
        card.put("style_label", job == null ? "" : WebtoonStyles.labelOf(job.getStyle()));
        // 찜 수(#247). 「내가 찜했나」는 로그인이 있어야 알 수 있어 부르는 쪽이 따로 붙인다.
        card.put("likes", likes.countByRunId(runId));
        if (withPublic) {
            card.put("public", work.isPublic());
        }
        return card;
    }

    /**
     * 완성본 한 편. 없으면 {@code null} — 부르는 쪽이 404 를 낸다.
     *
     * {@code width} 는 늘 1 이다. 이 하네스는 페이지 한 장이 카드 한 장이라
     * 지면 폭 개념이 없다. {@code gap}(장 뒤 여백, 폭의 몇 배)은 하네스가 안
     * 정하고 <b>편집실에서 사람이 고친 값</b>만 있다 — 안 고친 장은 0 이다.
     */
    /**
     * 아직 안 그린 장 번호 — 장면은 있는데 그림이 없는 장. 표지가 1장이고 장면 n 은 n+1 장이다.
     * 장면을 모르면(옛 작품) 빈 목록이다.
     */
    static List<Integer> pendingPages(List<Integer> drawn, List<String> scenes) {
        List<Integer> out = new ArrayList<>();
        if (scenes == null || scenes.isEmpty()) {
            return out;
        }
        for (int no = 1; no <= scenes.size() + 1; no++) {
            if (!drawn.contains(no)) {
                out.add(no);
            }
        }
        return out;
    }

    /** 이 작품에서 아직 안 그린 장 번호. 이어 그리기가 본다. */
    @Transactional(readOnly = true)
    public List<Integer> pendingPages(String runId) {
        return pendingPages(pages.pageNumbersOf(runId), stories.scenesOf(runId));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> result(String runId) {
        List<Integer> numbers = pages.pageNumbersOf(runId);
        if (numbers.isEmpty()) {
            return null;
        }
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        if (work != null && work.isTrashed()) {
            return null;
        }
        WebtoonJob job = work == null ? null
                : jobs.findByPublicId(work.getJobId()).orElse(null);
        Optional<WebtoonStory> chosen = stories.chosenOf(runId);
        List<String> scenes = stories.scenesOf(runId);

        /* 여백은 편집실에서 사람이 고친 것만 있다 — 하네스는 장 사이를 그냥 잇는다. */
        Map<Integer, Integer> gaps = overlays == null ? Map.of() : overlays.gapsOf(runId, 1);
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (int no : numbers) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("no", no);
            one.put("gap", OverlayStore.gapRatio(gaps.getOrDefault(no, 0)));
            one.put("width", 1);
            one.put("caption", captionOf(scenes, no));
            sheets.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("character", characterOf(job));
        out.put("title", chosen.map(WebtoonStory::displayTitle).filter(s -> !s.isBlank())
                .orElse(NO_TITLE));
        putSeriesTitle(out, job);
        out.put("genre", chosen.map(WebtoonStory::getGenre).orElse(""));
        out.put("style_label", job == null ? "" : WebtoonStyles.labelOf(job.getStyle()));
        out.put("logline", chosen.map(WebtoonStory::displayPlot).orElse(""));
        out.put("episode", episodeOf(job));
        /* 이 회차의 찜 수 — 둘러보기는 연재를 1화 카드로 묶어서 2화 이후의 수를 거기서 못 얻는다 */
        out.put("likes", likes.countByRunId(runId));
        out.put("pages", sheets);
        out.put("page_count", numbers.size());
        /* 그리기로 한 장 수(표지 + 장면 수)와 실제로 그린 장 수. 한 편 값은 기본 장 수까지라, 크레딧이
           모자라면 뒤 장을 안 그린 채 완성한다 — 그 장 번호와 한 장 값을 같이 줘서 화면이 「이어 그리기」를
           띄운다. 「미리보기」(preview)는 여전히 없다 — 공유·공개는 그린 장만으로 그대로 한다. */
        List<Integer> pending = pendingPages(numbers, scenes);
        out.put("planned_pages", numbers.size() + pending.size());
        out.put("pending_pages", pending);
        /* 잠긴 장도 웹툰 흐름 안에서 「다음 장면」으로 보이게 — 그린 장의 caption 과 같은 장면 한 줄이다 */
        List<Map<String, Object>> pendingScenes = new ArrayList<>();
        for (int no : pending) {
            pendingScenes.add(Map.of("no", no, "caption", captionOf(scenes, no)));
        }
        out.put("pending_scenes", pendingScenes);
        out.put("extra_page_credits", WebtoonQuality.extraPageCreditsOf(job == null ? null : job.getQuality()));
        out.put("base_pages", WebtoonQuality.BASE_PAGES);
        out.put("preview", false);
        out.put("example", ExampleWorks.isExample(work));
        return out;
    }

    /**
     * 편집실 화면이 그대로 먹는 모양 — 죽은 파이썬 프록시가 맡던
     * {@code newharness_pipeline.editor_data} 를 그대로 옮긴다(#281).
     *
     * <h2>컷은 왜 늘 하나뿐인가</h2>
     *
     * 편집실은 원래 "장(scene) 하나 = 컷 여럿"을 다루게 만들어졌지만, 지금
     * 그림 파이프라인은 <b>페이지 하나를 한 번에 그린다</b> — 컷을 몇 개로
     * 나눌지, 대사를 어디에 앉힐지는 전부 이미지 모델이 그림 안에서 스스로
     * 정하고, 하네스는 그 경계를 따로 기록하지 않는다(디테일 직행 흐름,
     * {@code detailart.py} 참고). 그래서 컷 목록에 실을 것이 원래 없다 —
     * 페이지당 컷을 하나로 두고, 그 안에 이 페이지가 그린 장면 한 줄만
     * {@code description} 에 실어 대사 패널이 완전히 비지는 않게 한다. 나머지
     * 칸(shot·beat·speaker·dialogue…)은 파이썬 쪽도 늘 빈 채로 내보내던
     * 것과 같다 — 화면이 그 칸이 있다고 가정하고 읽는 자리가 있어서, 아예
     * 없애면 그 자리가 깨진다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> episode(String runId, int ep) {
        List<Integer> numbers = pages.pageNumbersOf(runId);
        if (numbers.isEmpty()) {
            return null;
        }
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        if (work != null && work.isTrashed()) {
            return null;
        }
        WebtoonJob job = work == null ? null
                : jobs.findByPublicId(work.getJobId()).orElse(null);
        Optional<WebtoonStory> chosen = stories.chosenOf(runId);
        List<String> scenes = stories.scenesOf(runId);
        Map<Integer, String> keys = pages.keysOf(runId);

        List<Map<String, Object>> sceneList = new ArrayList<>();
        for (int no : numbers) {
            int[] dim = pages.dimensionsOf(keys.get(no));
            String note = no == 1 ? "표지" : captionOf(scenes, no);

            Map<String, Object> cut = new LinkedHashMap<>();
            cut.put("no", 1);
            cut.put("shot", "");
            cut.put("beat", "");
            cut.put("speaker", "");
            cut.put("dialogue", "");
            cut.put("narration", "");
            cut.put("thought", "");
            cut.put("sfx", "");
            cut.put("description", note);
            cut.put("lines", List.of());

            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("no", no);
            // 편집실 코드(editorCore.ts 의 atApi)가 알아보는 짧은 모양 그대로
            // 준다 — 스프링 접두사(/api/webtoon/v1)를 여기서 붙이면 편집실이
            // 그 앞에 또 한 번 붙여 주소가 두 겹이 된다.
            scene.put("image", "/api/runs/" + runId + "/page/" + no + "?w=1080");
            scene.put("w", dim[0]);
            scene.put("h", dim[1]);
            scene.put("cuts", List.of(cut));
            // 표지 뒤 기본 여백 — 편집실이 고친 값이 없을 때 보여 줄 단(OverlayStore.COVER_GAP_STEP)
            scene.put("gap_step", no == 1 ? OverlayStore.COVER_GAP_STEP : 0);
            sceneList.add(scene);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("character", characterOf(job));
        out.put("title", chosen.map(WebtoonStory::displayTitle).filter(s -> !s.isBlank())
                .orElse(NO_TITLE));
        out.put("genre", chosen.map(WebtoonStory::getGenre).orElse(""));
        out.put("style_label", job == null ? "" : WebtoonStyles.labelOf(job.getStyle()));
        out.put("logline", chosen.map(WebtoonStory::displayPlot).orElse(""));
        // 한 편짜리다 — 이어그리기가 붙으면 여기가 늘어난다(카드가 쓰는 것과 같은 값).
        out.put("episode", episodeOf(job));
        out.put("episodes", List.of(1));
        // 여백 눈금 — 편집실이 그리는 간격과 공개본·내려받은 파일의 간격이 같은 표를 본다.
        Map<String, Double> scale = new LinkedHashMap<>();
        for (int i = 0; i < OverlayStore.GAP_RATIO.length; i++) {
            scale.put(String.valueOf(i), OverlayStore.GAP_RATIO[i]);
        }
        out.put("gap_scale", scale);
        out.put("scenes", sceneList);
        out.put("page_count", numbers.size());
        return out;
    }


    /**
     * 이 장이 그린 장면 한 줄.
     *
     * <b>1장은 표지다.</b> 장면을 그리지 않고 제목만 얹으므로 캡션이 없다 —
     * 있는 척하면 2장의 장면이 1장 것으로 밀린다. 그래서 2장이 첫 장면이다.
     * (파이썬 {@code _caption} 과 같은 규칙이다.)
     */
    private static String captionOf(List<String> scenes, int pageNo) {
        int i = pageNo - 2;
        return i >= 0 && i < scenes.size() ? scenes.get(i) : "";
    }

    /**
     * 만들 때 사람이 넣은 설정(#329). 완성본 옆에 「넣은 것」을 나란히 보여 주려고
     * 꺼낸다. <b>사람이 쓴 글이 그대로 들어 있으니 주인에게만 보낸다</b> — 그 판단은
     * 컨트롤러가 한다. 없으면 null.
     *
     * @return job 의 브라우저 uid(주인 판정용)와 입력 값들
     */
    public Inputs inputsOf(String runId) {
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        WebtoonJob job = work == null ? null : jobs.findByPublicId(work.getJobId()).orElse(null);
        if (job == null || job.getInputJson() == null || job.getInputJson().isBlank()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(job.getInputJson());
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("name", root.path("name").asText(""));
            in.put("character", root.path("character").asText(""));
            in.put("genre", root.path("genre").asText(""));
            in.put("story", root.path("story").asText(""));
            in.put("photo_note", root.path("photo_note").asText(""));
            in.put("has_photo", root.path("photos_data").size() > 0 || root.path("photo_keys").size() > 0
                    || !root.path("character_id").asText("").isBlank());
            in.put("style", WebtoonStyles.labelOf(job.getStyle()));
            return new Inputs(job.getBrowserUid(), work.getUserId(), in);
        } catch (Exception e) {          // noqa: 넣은 것을 못 읽어도 완성본은 보여야 한다
            log.warn("넣은 설정을 못 읽었습니다 (job={})", job.getPublicId(), e);
            return null;
        }
    }

    public record Inputs(String browserUid, Long userId, Map<String, Object> values) {
    }

    /** 이 작품의 주인공 이름. 만들 때 받은 폼에 있다. */
    private String characterOf(WebtoonJob job) {
        if (job == null || job.getInputJson() == null || job.getInputJson().isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(job.getInputJson());
            return root.path("name").asText("");
        } catch (Exception e) {          // noqa: 이름 하나 때문에 목록이 통째로 죽으면 안 된다
            log.warn("캐릭터 이름을 못 읽었습니다 (job={})", job.getPublicId(), e);
            return "";
        }
    }
}
