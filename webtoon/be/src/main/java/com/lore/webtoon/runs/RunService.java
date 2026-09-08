package com.lore.webtoon.runs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.job.WebtoonStyles;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
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
    private final ObjectMapper mapper = new ObjectMapper();

    public RunService(WebtoonWorkRepository works, WebtoonJobRepository jobs,
                      StoryStore stories, PageStore pages) {
        this.works = works;
        this.jobs = jobs;
        this.stories = stories;
        this.pages = pages;
    }

    /**
     * 둘러보기 목록.
     *
     * <b>그림이 한 장도 없는 것은 빼고 준다.</b> 만들다 만 작품이 목록에 뜨면
     * 눌러도 빈 화면이 열린다 — 여는 사람에게는 그냥 고장으로 보인다.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> browse() {
        return cardsOf(works.onGallery(), false);
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
        for (WebtoonWork one : found) {
            Map<String, Object> card = card(one, withPublic);
            if (card != null) {
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
                .map(work -> card(work, false))
                .orElse(null);
    }

    /** 카드 하나. 그림이 없으면 {@code null} — 목록에 안 올린다. */
    private Map<String, Object> card(WebtoonWork work, boolean withPublic) {
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
        card.put("genre", chosen.map(WebtoonStory::getGenre).orElse(""));
        // 한 편짜리다 — 이어그리기가 붙으면 여기가 늘어난다.
        card.put("episodes", List.of(1));
        card.put("cover_episode", 1);
        card.put("cover_page", numbers.getFirst());
        card.put("page_count", numbers.size());
        card.put("style_label", job == null ? "" : WebtoonStyles.labelOf(job.getStyle()));
        if (withPublic) {
            card.put("public", work.isPublic());
        }
        return card;
    }

    /**
     * 완성본 한 편. 없으면 {@code null} — 부르는 쪽이 404 를 낸다.
     *
     * {@code gap} 과 {@code width} 는 늘 0 과 1 이다. 이 하네스는 페이지 한
     * 장이 카드 한 장이라 여백·지면 폭 개념이 없다({@code stitch.py} 가 그냥
     * 이어 붙인다). 화면은 옛 흐름과 같은 코드로 그리므로 값을 비워 두지 않고
     * 그 뜻대로 채워 준다.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> result(String runId) {
        List<Integer> numbers = pages.pageNumbersOf(runId);
        if (numbers.isEmpty()) {
            return null;
        }
        WebtoonWork work = works.findFirstByRunId(runId).orElse(null);
        WebtoonJob job = work == null ? null
                : jobs.findByPublicId(work.getJobId()).orElse(null);
        Optional<WebtoonStory> chosen = stories.chosenOf(runId);
        List<String> scenes = stories.scenesOf(runId);

        List<Map<String, Object>> sheets = new ArrayList<>();
        for (int no : numbers) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("no", no);
            one.put("gap", 0);
            one.put("width", 1);
            one.put("caption", captionOf(scenes, no));
            sheets.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", runId);
        out.put("character", characterOf(job));
        out.put("title", chosen.map(WebtoonStory::displayTitle).filter(s -> !s.isBlank())
                .orElse(NO_TITLE));
        out.put("genre", chosen.map(WebtoonStory::getGenre).orElse(""));
        out.put("style_label", job == null ? "" : WebtoonStyles.labelOf(job.getStyle()));
        out.put("logline", chosen.map(WebtoonStory::getPlot).orElse(""));
        out.put("episode", 1);
        out.put("pages", sheets);
        out.put("page_count", numbers.size());
        /* 그리기로 한 장 수와 실제로 그린 장 수가 같다 — 이 길은 한 편을
           끝까지 그리거나 실패하거나 둘 중 하나이고, 앞 몇 장만 그리는
           「미리보기」가 없다. 화면은 이 둘이 다를 때만 "앞 몇 장만" 을 적는다. */
        out.put("planned_pages", numbers.size());
        out.put("preview", false);
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
