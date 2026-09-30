package com.lore.webtoon.job;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * <b>지금까지 얼마나 했고 앞으로 얼마나 남았나</b> — 진행 화면의 경과 시간·남은 시간·
 * 진행률이 전부 여기서 나온다.
 *
 * <h2>왜 새로 만들었나 (#509)</h2>
 *
 * 전에는 남은 시간이 「한 편 5분 47초 − 시작한 뒤 지난 시간, 최소 1분」이었고
 * 진행률은 네 걸음을 25%씩 나눈 값이었다. 실제로 한 편을 지켜보니 셋 다 틀렸다
 * (2026-09-30, job 48 · 12분 52초).
 *
 * <ul>
 *   <li>시작 시각이 이야기 단계라 <b>사람이 이야기를 고르고 시트를 확인한 시간</b>까지
 *       지난 시간으로 빠졌다. 그림을 그리기 시작할 즈음엔 이미 「약 1분」이었다.</li>
 *   <li>검수 단계에 들어가면 「그린 장 7/7」이 남아서 <b>바로 100%</b>가 됐다. 검수 뒤
 *       다시 그리는 4분 동안 100%·1분으로 멈춰 있었다.</li>
 *   <li>5분 47초는 옛 실측이다. 같은 날 세 편은 고른 뒤 기계 시간만 5분 14초 · 9분
 *       26초 · 6분 48초였다.</li>
 * </ul>
 *
 * 그래서 <b>기계가 일한 시간</b>(사람을 기다린 시간을 뺀 것)을 세고, 남은 시간은
 * 걸음마다 실측한 시간과 남은 장 수로 합산한다. 진행률은 그 둘의 비율이다 —
 * 남은 시간과 진행률이 서로 다른 말을 하지 않게.
 *
 * <h2>숫자의 출처</h2>
 *
 * 아래 초는 2026-09-30 로컬에서 파도(surf)로 만든 세 편의 {@code meta.json} 호출
 * 시각에서 잰 것이다. 표본이 작아서 임시다 — 걸음별 시간이 쌓이면 DB 평균으로
 * 갈아 끼운다. 그림 걸음(시트·장)만 화질에 따라 늘리고 줄인다.
 */
final class JobEta {

    private JobEta() {
    }

    /** 이야기 후보 넷 + 고르기 전 검수. 실측 65~78초. */
    static final long STORY = 75;
    /** 시트 사양 + 시트 그림. 실측 38~47초. */
    static final long SHEET = 45;
    /** 장면 나누기. 실측 23~32초. */
    static final long SCENES = 30;
    /** 그림 자리 하나가 장 하나를 그리고 검수까지 하는 시간. 장 그림 약 45초 + 검수 약 7초 + 가끔 다시 그리기. */
    static final long PAGE = 70;
    /** 화 전체 검수 한 번. gpt-5.1 로 실측 20~36초. */
    static final long REVIEW = 35;
    /** 검수 뒤 걸린 장 하나를 다시 그리는 시간. 실측 약 57초. */
    static final long REDRAW = 60;
    /** 이어 붙이고 창고에 올리기. */
    static final long FINISH = 15;
    /** 장면을 나누기 전이라 몇 장인지 모를 때 — 표지 1 + 장면 대개 6~7. */
    static final int GUESS_PAGES = 8;

    /** 그림 걸음에 곱하는 화질 비율(파도 기준). 옛 표의 파도·너울 비율과 같다. */
    private static final Map<String, Double> QUALITY = Map.of(
            "wave", 384.0 / 486.0,
            "surf", 1.0,
            "swell", 879.0 / 486.0);

    /**
     * @param work     기계가 일한 시간(초) — 줄 선 시간은 넣고, 사람을 기다린 시간은 뺀다
     * @param left     남은 시간(초). 모르면 -1 (예상을 넘겨 마무리 중이거나, 끝났거나)
     * @param humanTurn 지금 사람이 답할 차례인가 — 그러면 남은 시간을 화면에 안 적는다
     */
    record Eta(long work, long left, boolean humanTurn) {

        /** 화면이 적는 「약 N분」. 사람 차례이거나 모르면 {@code null}. */
        Integer minutes() {
            if (humanTurn || left < 0) {
                return null;
            }
            return (int) Math.max(1, Math.ceil(left / 60.0));
        }

        /**
         * 진행률. <b>끝나기 전에는 100 이 아니다</b> — 99 에서 멈춘다.
         *
         * 남은 시간을 모르면(예상을 넘겼으면) 99 다. 화면은 이때 「마무리하고 있어요」를
         * 적으므로 두 말이 어긋나지 않는다.
         */
        int pct(boolean done) {
            if (done) {
                return 100;
            }
            if (left < 0) {
                return 99;
            }
            long whole = work + left;
            if (whole <= 0) {
                return 0;
            }
            return (int) Math.max(0, Math.min(99, Math.round(100.0 * work / whole)));
        }
    }

    /**
     * @param job       작업
     * @param now       진행 상태(몇 장 그렸나 · 다시 그리는 중인가)
     * @param slots     이 작업이 쓸 수 있는 그림 자리 — 다른 편과 나눠 쓰면 줄어든다
     * @param queueWait 줄에 서 있으면 내 차례까지 예상 초. 아니면 0
     * @param at        지금
     */
    static Eta of(WebtoonJob job, JobProgress.Snapshot now, int slots, long queueWait, Instant at) {
        long work = work(job, at);
        JobStatus status = job.getStatus();
        if (status == JobStatus.DONE || status == JobStatus.ERROR) {
            // 끝났다 — 남은 시간을 적을 것이 없다(0 으로 두면 「약 1분」으로 올려 적힌다).
            return new Eta(work, -1, false);
        }
        double f = QUALITY.getOrDefault(WebtoonQuality.normalize(job.getQuality()), 1.0);
        int s = Math.max(1, slots);
        JobStage stage = job.getStage();
        int pages = now.total() > 0 ? now.total() : GUESS_PAGES;

        if (status == JobStatus.QUEUED) {
            return new Eta(work, queueWait + whole(stage, pages, s, f) + after(stage, pages, s, f), false);
        }
        if (status == JobStatus.AWAITING_PICK || status == JobStatus.AWAITING_SHEET) {
            // 사람을 기다리는 동안은 남은 시간을 안 적는다. 진행률에 쓸 값만 센다 —
            // 사람이 답하면 바로 다음 걸음부터 돈다.
            JobStage next = status == JobStatus.AWAITING_PICK ? JobStage.SHEET : JobStage.PAGES;
            return new Eta(work, whole(next, pages, s, f) + after(next, pages, s, f), true);
        }

        // RUNNING — 지금 걸음에서 남은 것 + 뒤 걸음들.
        long inStage = seconds(job.getStageAt(), at);
        long sincePhase = now.phaseAt() == null ? inStage : seconds(now.phaseAt(), at);
        long here;
        boolean overdue;
        switch (stage) {
            case STORY -> {
                here = STORY - inStage;
                overdue = here <= 0;
            }
            case SHEET -> {
                here = Math.round(SHEET * f) - inStage;
                overdue = here <= 0;
            }
            case PAGES -> {
                if (now.total() <= 0) {
                    // 아직 장면을 나누는 중이다.
                    here = Math.max(0, SCENES - inStage) + waves(GUESS_PAGES, s) * Math.round(PAGE * f);
                    overdue = false;
                } else {
                    int rest = Math.max(0, now.total() - now.done());
                    here = drawLeft(rest, now.inflight(), s, Math.round(PAGE * f), sincePhase, at);
                    overdue = here <= 0;
                }
            }
            default -> {                                        // BIND
                int rest = Math.max(0, now.redraw().size() - now.redrawDone());
                if (rest > 0) {
                    here = drawLeft(rest, now.inflight(), s, Math.round(REDRAW * f), sincePhase, at);
                    // 다시 그린 뒤엔 검수를 한 번 더 돈다.
                    here = Math.max(0, here) + REVIEW + FINISH;
                    overdue = false;
                } else {
                    here = REVIEW + FINISH - sincePhase;
                    overdue = here <= 0;
                }
            }
        }
        long later = after(stage, pages, s, f);
        if (overdue && later == 0) {
            // 마지막 걸음인데 예상을 넘겼다 — 「1분」이라고 말하지 않는다.
            return new Eta(work, -1, false);
        }
        return new Eta(work, Math.max(0, here) + later, false);
    }

    /** 기계가 일한 시간(초) — 만든 때부터 지금(끝났으면 끝난 때)까지, 사람을 기다린 시간은 뺀다. */
    static long work(WebtoonJob job, Instant at) {
        Instant until = job.getStatus().isOver()
                ? (job.getFinishedAt() != null ? job.getFinishedAt() : job.getUpdatedAt())
                : at;
        long all = seconds(job.getCreatedAt(), until);
        return Math.max(0, all - job.pausedSecondsAt(until));
    }

    /** 그 걸음 하나를 처음부터 끝까지 하는 시간. */
    private static long whole(JobStage stage, int pages, int slots, double f) {
        return switch (stage) {
            case STORY -> STORY;
            case SHEET -> Math.round(SHEET * f);
            case PAGES -> SCENES + waves(pages, slots) * Math.round(PAGE * f);
            case BIND -> REVIEW + FINISH;
        };
    }

    /** 그 걸음 <b>뒤</b>에 남은 걸음들. */
    private static long after(JobStage stage, int pages, int slots, double f) {
        long sum = 0;
        for (JobStage one : JobStage.values()) {
            if (one.order() > stage.order()) {
                sum += whole(one, pages, slots, f);
            }
        }
        return sum;
    }

    /**
     * 남은 장 {@code rest} 개를 다 그리기까지 몇 초인가.
     *
     * <b>그리는 중인 장은 남은 몫만 센다.</b> 「남은 장 ÷ 자리 × 한 장」으로만 세면 한 장이
     * 끝나는 순간 반쯤 그린 옆 장들까지 처음부터 다시 세어서, 남은 시간이 4분→5분으로
     * 늘었다(2026-09-30). 그래서 자리마다 언제 비는지를 두고, 아직 시작 안 한 장을
     * 먼저 비는 자리부터 차례로 넣어 마지막 자리가 비는 때를 센다.
     *
     * 시작 시각을 모르면(한 프로세스로 차례로 그리는 경우) 예전처럼 센다.
     */
    static long drawLeft(int rest, java.util.List<Instant> inflight, int slots, long per,
                         long sincePhase, Instant at) {
        if (rest <= 0) {
            return 0;
        }
        if (inflight == null || inflight.isEmpty()) {
            return waves(rest, slots) * per - sincePhase;
        }
        java.util.PriorityQueue<Long> free = new java.util.PriorityQueue<>();
        for (Instant started : inflight) {
            // 예상보다 오래 걸리는 장도 곧 끝난다고만 본다 — 0 이면 「끝났다」가 된다.
            free.add(Math.max(10, per - seconds(started, at)));
        }
        for (int i = inflight.size(); i < slots; i++) {
            free.add(0L);                               // 놀고 있는 자리
        }
        long last = free.stream().mapToLong(Long::longValue).max().orElse(0);
        int notStarted = Math.max(0, rest - inflight.size());
        for (int i = 0; i < notStarted; i++) {
            long ends = free.poll() + per;
            free.add(ends);
            last = Math.max(last, ends);
        }
        // 그리는 중인 장만 남았으면 그중 가장 늦게 끝나는 것까지다.
        if (notStarted == 0) {
            last = 0;
            for (Instant started : inflight) {
                last = Math.max(last, Math.max(10, per - seconds(started, at)));
            }
        }
        return last;
    }

    /** 장 n 개를 자리 s 개로 그리면 몇 차례인가. */
    private static long waves(int n, int s) {
        return n <= 0 ? 0 : (n + s - 1) / s;
    }

    private static long seconds(Instant from, Instant to) {
        if (from == null || to == null) {
            return 0;
        }
        return Math.max(0, Duration.between(from, to).getSeconds());
    }
}
