package com.lore.webtoon.job;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 진행 화면이 그대로 먹는 모양.
 *
 * <b>파이썬 서버가 내보내던 것과 한 글자도 다르면 안 된다.</b> 화면은
 * 프로토타입에서 옮겨 온 것이라 이 이름들을 그대로 읽는다 — 하나만 어긋나도
 * 진행 막대가 멈추거나 "알 수 없는 오류" 가 뜬다. 그래서 자바 관례(낙타)가
 * 아니라 <b>파이썬이 쓰던 이름(밑줄)</b>을 그대로 쓴다.
 *
 * 옮겨 가는 동안 두 서버가 같은 화면을 먹여야 하므로, 이 모양이 곧 계약이다.
 */
public record JobView(
        String id,
        String status,
        String run_id,
        String error,
        /* 실패했을 때 **실제로** 돌려준 것 — "credit" · "free" · "none".
           안 끝났거나 잘 끝난 작업에서는 없다.

           화면이 실패 안내의 마지막 한 줄을 이걸로 고른다. 크레딧이 없는
           게스트에게 "크레딧을 환불했어요" 라고 적으면 없는 것을 돌려줬다는
           말이 되고, 로그인한 사람에게 "무료 횟수를 복구했어요" 도 마찬가지다.

           **파이썬 서버는 이 칸을 안 보낸다.** 화면은 없으면 그 줄을 그냥 안
           그린다 — 위 머리말의 "한 글자도 다르면 안 된다" 는 있는 이름을 두고
           하는 말이고, 무는 쪽은 더해도 된다. */
        String refunded,
        List<Map<String, Object>> directions,
        Integer pick,
        String style,
        String style_label,
        String stage,
        int stage_index,
        List<String> stages,
        String stage_label,
        String say,
        boolean checkpoints,
        int pct,
        Art art,
        List<String> log,
        double elapsed) {

    /**
     * @param total      0 이면 아직 몇 장인지 모른다 — 그때는 통째로 안 보낸다.
     * @param retry_page 지금 걸려서 다시 그리는 중인 장 번호. 0 이면 없다. 새로
     *                   더한 칸이라 옛 프로토타입 서버는 안 보낸다 — 화면은 없는
     *                   값으로 읽고 그냥 무시한다(위 머리말의 "무는 쪽은 더해도
     *                   된다"). 다른 칸과 같이 밑줄 이름을 그대로 쓴다.
     */
    public record Art(int done, int total, int retry_page) {
    }

    static JobView of(WebtoonJob job, JobProgress.Snapshot now,
                      List<Map<String, Object>> directions, String styleLabel,
                      String stageLabel) {
        int stageIndex = job.getStage().order();
        double frac = now.total() > 0 ? (double) now.done() / now.total() : 0.0;
        int pct = job.getStatus() == JobStatus.DONE
                ? 100
                : (int) Math.round((stageIndex + frac) / JobStage.count() * 100);

        return new JobView(
                job.getPublicId(),
                job.getStatus().wire(),
                job.getRunId(),
                job.getError(),
                job.getRefunded() == null ? null : job.getRefunded().wire(),
                directions,
                job.getPicked(),
                job.getStyle(),
                styleLabel,
                job.getStage().wire(),
                stageIndex,
                List.of("story", "sheet", "board", "pages"),
                stageLabel,
                now.say(),
                job.isCheckpoints(),
                Math.max(0, Math.min(100, pct)),
                now.total() > 0 ? new Art(now.done(), now.total(), now.retryPage()) : null,
                now.log(),
                elapsed(job));
    }

    /** 시작하고 얼마나 지났나(초). 끝난 것은 끝난 시각까지만 센다. */
    private static double elapsed(WebtoonJob job) {
        Instant until = job.getStatus().isOver() ? job.getUpdatedAt() : Instant.now();
        double seconds = (until.toEpochMilli() - job.getCreatedAt().toEpochMilli()) / 1000.0;
        return Math.round(Math.max(0, seconds) * 10) / 10.0;
    }
}
