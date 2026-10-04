package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 예시 작품 관리(#614) — 목록 · 지정/해제 · 공개/비공개 · 순서 · 내리기.
 *
 * <h2>같은 환경에서 만든 작품은 값 하나로 예시가 된다</h2>
 *
 * 운영에서 관리자 계정으로 만든 작품을 예시로 올릴 때는 그림을 옮길 일이 없다. {@code is_example} 만 켜면
 * 된다. 다른 환경에서 만든 작품은 번들({@link ExampleBundle})로 옮긴다({@link ExampleImporter}).
 *
 * <h2>비공개로 돌리면 그림도 옮긴다</h2>
 *
 * 공개/비공개는 DB 값과 <b>그림이 놓인 자리</b>(PrivateArt)가 같이 정한다. DB 만 바꾸면 비공개라면서 그림은
 * CloudFront 가 계속 내준다 — 사용자의 공개 전환({@code MyWebtoonService#setVisibility})과 같은 규칙이다.
 *
 * 권한 확인은 부르는 쪽(컨트롤러)이 한다.
 */
@Service
public class ExampleAdmin {

    private static final Logger log = LoggerFactory.getLogger(ExampleAdmin.class);

    /** 목록의 한 줄. */
    public record Row(String runId, String title, String genre, boolean isPublic, Integer order, int pages,
                      boolean seeded, Instant createdAt) {
    }

    private final WorkLedger ledger;
    private final WebtoonWorkRepository works;
    private final PageStore pages;
    private final StoryStore stories;

    public ExampleAdmin(WorkLedger ledger, WebtoonWorkRepository works, PageStore pages, StoryStore stories) {
        this.ledger = ledger;
        this.works = works;
        this.pages = pages;
        this.stories = stories;
    }

    /** 예시 작품들 — 순서대로. 비공개로 내려 둔 것도 보인다(다시 올릴 수 있어야 하므로). */
    public List<Row> list() {
        return ledger.examples().stream().filter(w -> w.getRunId() != null).map(this::rowOf).toList();
    }

    /**
     * 지정/해제 · 공개 여부 · 순서를 한꺼번에 바꾼다. 보내지 않은(null) 값은 그대로 둔다.
     *
     * 예시로 지정하면서 공개 여부를 안 정하면 <b>공개로 둔다</b> — 예시는 누구나 보는 것이다.
     */
    public Row update(String runId, Boolean example, Boolean isPublic, Integer order) {
        WebtoonWork work = find(runId);
        boolean nowExample = work.isExample();
        if (example != null) {
            nowExample = example;
        }
        if (order != null && !nowExample) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "예시가 아닌 작품에는 순서를 줄 수 없습니다");
        }
        if (order != null && order < 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "순서는 0 이상이어야 합니다");
        }
        if (example != null || order != null) {
            Integer keep = order != null ? order : work.getExampleOrder();
            ledger.markExample(runId, nowExample, nowExample ? keep : null);
        }
        Boolean visible = isPublic != null ? isPublic : (example != null && example && !work.isPublic() ? Boolean.TRUE : null);
        if (visible != null) {
            setVisibility(runId, visible);
        }
        return rowOf(find(runId));
    }

    /** 내리기 — 예시를 해제하고 비공개로 돌린다. 작품을 지우지는 않는다. */
    public Row takeDown(String runId) {
        find(runId);
        ledger.markExample(runId, false, null);
        setVisibility(runId, false);
        return rowOf(find(runId));
    }

    private void setVisibility(String runId, boolean isPublic) {
        ledger.setPublic(runId, isPublic);
        try {
            pages.moveAll(runId, isPublic);
        } catch (RuntimeException e) {
            // 이미 DB 는 바뀌었다. 남은 것은 「주소를 아는 사람에게 아직 열린다」이고, 크게 남겨 다시 시도한다.
            log.error("공개 여부는 바꿨는데 그림을 못 옮겼습니다 (run={}, public={})", runId, isPublic, e);
        }
    }

    private WebtoonWork find(String runId) {
        return works.findFirstByRunId(runId).filter(w -> !w.isTrashed())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다"));
    }

    private Row rowOf(WebtoonWork w) {
        String runId = w.getRunId();
        WebtoonStory story = stories.chosenOf(runId).orElse(null);
        return new Row(runId, story == null ? "" : story.displayTitle(), story == null ? "" : story.getGenre(),
                w.isPublic(), w.getExampleOrder(), pages.pageNumbersOf(runId).size(),
                ExampleWorks.SEED_UID.equals(w.getBrowserUid()), w.getCreatedAt());
    }
}
