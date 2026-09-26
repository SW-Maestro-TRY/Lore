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
        /**
         * 줄에서의 자리. <b>내 차례면 {@code null}</b> — 적을 것이 없다.
         *
         * 이게 없을 때 화면은 「루가 그림을 그리고 있어요」만 보여 줬고,
         * 앞에 세 명이 있어도 내 그림이 그려지는 줄 알았다.
         */
        Queue queue,
        /**
         * <b>다 되면 어디로 알릴 것인가.</b> 진행 화면이 이걸로 안내를 고른다 —
         * 로그인한 사람에게는 「완성되면 …로 알림을 드릴게요」, 게스트에게는
         * 이메일 칸. 화면이 로그인 여부를 자기가 판단하면 두 화면이 갈린다.
         */
        /* 이름이 {@code notify} 가 아닌 이유: 레코드 칸은 {@code Object.notify()}
           와 이름이 겹칠 수 없다(자바가 막는다). */
        Notice notice,
        /**
         * 결과를 보기까지 앞으로 몇 분. <b>사람이 답할 차례이거나 끝났으면 없다.</b>
         *
         * 이 값이 있어야 기다리는 사람이 <b>나갔다 올지</b>를 정할 수 있다.
         * 화면이 자기 시계로 세지 않는 이유: 새로고침할 때마다 값이 뛴다.
         */
        Integer minutes_left,
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
    /**
     * @param ahead   앞에 몇 명
     * @param minutes 내 차례까지 예상 분 (줄에 선 것만 센 값)
     * @param line    화면이 그대로 적는 한 줄
     */
    public record Queue(int ahead, int minutes, String line) {
    }

    /**
     * 알림을 어디로 보낼까.
     *
     * @param logged_in 로그인했나. 참이면 화면은 <b>안 묻는다</b> — 계정 주소로 보낸다
     * @param email     실제로 보낼 주소. 없으면 아직 받을 데가 없다는 뜻이라
     *                  화면이 게스트에게 입력 칸을 띄운다.
     *                  <b>자기 주소를 자기에게 보여 주는 것</b>이라 그대로 적는다 —
     *                  가려 놓으면 오타를 냈는지 확인할 길이 없다
     * @param sent      이미 보냈나. 참이면 주소를 못 바꾼다(그 메일은 이미 나갔다)
     */
    public record Notice(boolean logged_in, String email, boolean sent) {
    }

    public record Art(int done, int total, int retry_page) {
    }

    static JobView of(WebtoonJob job, JobProgress.Snapshot now,
                      List<Map<String, Object>> directions, String styleLabel,
                      String stageLabel, JobQueue.Spot spot,
                      String notifyEmail, Integer minutesLeft) {
        int stageIndex = job.getStage().order();
        double frac = now.total() > 0 ? (double) now.done() / now.total() : timeFrac(job);
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
                List.of("story", "sheet", "pages", "bind"),
                stageLabel,
                now.say(),
                job.isCheckpoints(),
                spot == null ? null
                        : new Queue(spot.ahead(), spot.minutes(), spot.line()),
                new Notice(job.getUserId() != null, notifyEmail, job.getNotifiedAt() != null),
                minutesLeft,
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

    /**
     * story·sheet·bind 는 하네스를 한 번 통짜로 부르고 끝날 때까지 done/total 을
     * 셀 지점이 없다({@code JobRunner}). 그래서 실제 세부 진행이 없는 동안 이
     * 단계 안에서 얼마나 지났는지(초)로 진행률을 대신 채운다 — 값을 앞서 보여줄
     * 뿐 서버가 아는 진짜 값을 넘지는 않는다(다음 단계로 못 넘어가면 이 단계
     * 폭의 85% 에서 멈춘다). 표는 실측이 아니라 대략치다 — 표본이 쌓이면
     * {@code JobQueue.SECONDS} 처럼 실측으로 갈아 끼운다.
     */
    private static final Map<JobStage, Double> ASSUMED_STAGE_SECONDS = Map.of(
            JobStage.STORY, 50.0,
            JobStage.SHEET, 100.0,
            JobStage.BIND, 45.0);

    private static double timeFrac(WebtoonJob job) {
        Double assumed = ASSUMED_STAGE_SECONDS.get(job.getStage());
        if (assumed == null) {
            return 0.0;
        }
        double sinceStage = (Instant.now().toEpochMilli() - job.getUpdatedAt().toEpochMilli()) / 1000.0;
        return Math.max(0.0, Math.min(0.85, sinceStage / assumed));
    }
}
