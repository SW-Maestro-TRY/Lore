package com.lore.webtoon.job;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 지금 줄에 있는 작업들을 <b>미리 굴려 본다</b> — 「내 작품이 언제 끝나나」를 앞사람 수로 어림하지 않고
 * 실제 줄 서기 규칙대로 센다(#836).
 *
 * <h2>왜 굴리나</h2>
 * 걸음이 끝날 때마다 줄 끝에 다시 선다(#509). 그래서 내 뒤에 선 사람의 앞 걸음도 내 다음 걸음 앞에 끼어든다.
 * 「내 앞사람이 남긴 일」만 세면 사람이 몰릴 때 실제보다 8~10분 짧게 나왔고(12명 동시, 시뮬레이터 비교),
 * 그림 자리를 앞사람마다 나눠 세던 옛 계산은 같은 사람이 32분 ↔ 65분으로 튀었다.
 *
 * <h2>흉내 내는 구조</h2> ({@code JobRunner} · {@code webtoon/tools/loadtest/sim.py} 와 같다)
 * <ul>
 *   <li>일꾼 {@code workers} 개가 줄 앞부터 하나씩 꺼낸다. 이야기 → (시트를 기다리며) 고른 뒤 저장 → 장면 나누기 →
 *       장 그림 → 검수·마무리. 걸음 하나가 끝나면 일꾼을 놓고 줄 끝에 다시 선다.</li>
 *   <li>장 그림 걸음은 일꾼을 붙잡은 채 장을 그림 자리({@code pageWorkers}, 서버 전체가 나눠 씀)에 넣고,
 *       다 그리면 검수·마무리까지 하고 놓는다.</li>
 *   <li>시트는 따로 바로 그린다(줄을 안 선다).</li>
 * </ul>
 * 걸음 시간은 {@link JobEta} 의 실측값을 쓴다. 사람이 확인하는 자리는 바로 누른다고 본다 — 그동안은 줄에 없다.
 */
final class JobLineSim {

    enum Phase { STORY, PICK, SCENES, PAGES, TAIL }

    /**
     * 줄에 있는 한 편.
     *
     * @param running   지금 일꾼이 붙어 있나 — 그러면 {@code phaseLeft} 뒤에 그 걸음이 끝난다
     * @param phaseLeft 도는 중인 걸음의 남은 초(줄에 서 있으면 무시)
     * @param pagesLeft 아직 안 그린 장 수
     * @param sheetLeft 시트가 다 그려지기까지 남은 초(이미 끝났으면 0)
     */
    record Item(long id, Phase phase, boolean running, long phaseLeft, int pagesLeft, double quality,
                long sheetLeft) {
    }

    /** @param start 다음에 일꾼이 붙는 때(초, 지금 도는 중이면 0) · @param finish 다 끝나는 때(초) */
    record When(long start, long finish) {
    }

    private record Ev(long t, long seq, Runnable fn) {
    }

    private final int workers;
    private final int pageWorkers;
    private final PriorityQueue<Ev> events = new PriorityQueue<>(
            (a, b) -> a.t != b.t ? Long.compare(a.t, b.t) : Long.compare(a.seq, b.seq));
    private final Deque<Object[]> lineQ = new ArrayDeque<>();
    private final Deque<Object[]> paintQ = new ArrayDeque<>();
    private final Map<Long, Long> start = new HashMap<>();
    private final Map<Long, Long> finish = new HashMap<>();
    private long now;
    private long seq;
    private int lineFree;
    private int paintFree;

    private JobLineSim(int workers, int pageWorkers) {
        this.workers = Math.max(1, workers);
        this.pageWorkers = Math.max(1, pageWorkers);
        this.lineFree = this.workers;
        this.paintFree = this.pageWorkers;
    }

    /** 줄 순서대로 넣은 편들이 각각 언제 시작하고 끝나나. 도는 것을 앞에, 줄에 선 것은 선 순서대로 넣는다. */
    static Map<Long, When> run(List<Item> line, int workers, int pageWorkers) {
        JobLineSim sim = new JobLineSim(workers, pageWorkers);
        for (Item one : line) {
            if (one.running() && sim.lineFree > 0) {
                sim.lineFree--;
                sim.start.put(one.id(), 0L);
                sim.resume(one);
            } else {
                sim.lineQ.add(new Object[]{one, one.phase()});
            }
        }
        sim.kickLine();
        int guard = 0;
        while (!sim.events.isEmpty() && guard++ < 1_000_000) {
            Ev e = sim.events.poll();
            sim.now = e.t;
            e.fn.run();
        }
        Map<Long, When> out = new HashMap<>();
        for (Item one : line) {
            out.put(one.id(), new When(sim.start.getOrDefault(one.id(), 0L),
                    sim.finish.getOrDefault(one.id(), sim.now)));
        }
        return out;
    }

    private void at(long dt, Runnable fn) {
        events.add(new Ev(now + Math.max(0, dt), seq++, fn));
    }

    private void kickLine() {
        while (lineFree > 0 && !lineQ.isEmpty()) {
            Object[] next = lineQ.poll();
            Item one = (Item) next[0];
            lineFree--;
            start.putIfAbsent(one.id(), now);
            begin(one, (Phase) next[1]);
        }
    }

    private void releaseThen(Item one, Phase next) {
        lineFree++;
        if (next != null) {
            lineQ.add(new Object[]{one, next});
        }
        kickLine();
    }

    /** 이미 일꾼이 붙어 도는 걸음을 남은 만큼 이어 간다. */
    private void resume(Item one) {
        switch (one.phase()) {
            case PAGES -> drawThenTail(one, one.pagesLeft());
            case TAIL -> at(one.phaseLeft(), () -> done(one));
            default -> at(one.phaseLeft(), () -> releaseThen(one, after(one.phase())));
        }
    }

    /** 줄에서 꺼내 그 걸음을 처음부터 한다. */
    private void begin(Item one, Phase phase) {
        long elapsed = now;
        switch (phase) {
            case STORY -> at(JobEta.STORY, () -> releaseThen(one, Phase.PICK));
            case PICK -> at(Math.max(1, one.sheetLeft() - elapsed), () -> releaseThen(one, Phase.SCENES));
            case SCENES -> at(JobEta.SCENES, () -> releaseThen(one, Phase.PAGES));
            case PAGES -> drawThenTail(one, one.phase() == Phase.PAGES ? one.pagesLeft() : JobEta.GUESS_PAGES + 1);
            case TAIL -> at(JobEta.REVIEW + JobEta.FINISH, () -> done(one));
        }
    }

    private static Phase after(Phase p) {
        return switch (p) {
            case STORY -> Phase.PICK;
            case PICK -> Phase.SCENES;
            case SCENES -> Phase.PAGES;
            default -> null;
        };
    }

    /** 장을 그림 자리에 넣고(일꾼은 붙잡은 채) 다 그리면 검수·마무리까지 한다. */
    private void drawThenTail(Item one, int pages) {
        long per = Math.round(JobEta.PAGE * one.quality());
        int[] left = {Math.max(0, pages)};
        if (left[0] == 0) {
            at(JobEta.REVIEW + JobEta.FINISH, () -> done(one));
            return;
        }
        for (int i = 0; i < pages; i++) {
            paintQ.add(new Object[]{per, (Runnable) () -> {
                if (--left[0] == 0) {
                    at(JobEta.REVIEW + JobEta.FINISH, () -> done(one));
                }
            }});
        }
        kickPaint();
    }

    private void kickPaint() {
        while (paintFree > 0 && !paintQ.isEmpty()) {
            Object[] next = paintQ.poll();
            paintFree--;
            Runnable then = (Runnable) next[1];
            at((Long) next[0], () -> {
                paintFree++;
                then.run();
                kickPaint();
            });
        }
    }

    private void done(Item one) {
        finish.put(one.id(), now);
        releaseThen(one, null);
    }
}
