package com.lore.webtoon.job;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 도는 동안 화면에 보여 줄 것 — 로그 · 몇 장 그렸나 · 지금 무슨 검수 중인가.
 *
 * <h2>왜 DB 에 안 넣나</h2>
 *
 * 한 편이 도는 몇 분 동안 화면은 0.8 초마다 묻고, 파이썬은 수백 줄을 뱉는다.
 * 그걸 다 DB 에 쓰면 <b>만드는 것보다 적는 데 더 바쁘다.</b> 그리고 잃어도
 * 되는 값이다 — 잃으면 진행 막대가 덜 촘촘해질 뿐, 만들어진 작품은 그대로다.
 * 진짜 상태(어느 걸음인가 · 끝났나 · 작품 번호)는 DB 에 있다({@link WebtoonJob}).
 *
 * 끝난 작업은 잊는다. 안 잊으면 서버가 오래 뜰수록 계속 쌓인다.
 */
@Component
public class JobProgress {

    /** 화면에 보낼 줄 수. 넘으면 오래된 것부터 버린다 — 사람이 보는 것은 끝쪽이다. */
    private static final int MAX_LINES = 60;

    /** "[페이지 3/7] ..." · "[장면 2/5] ..." — 몇 장까지 그렸는지 알려 주는 줄. */
    private static final Pattern ART = Pattern.compile("\\[(?:페이지|장면)\\s*(\\d+)\\s*/\\s*(\\d+)]");

    /** "[페이지 3/7] 다시 그리는 중 (1/2) — ..." — 그 장이 걸려서 다시 그리는 중
     *  (pageart.py 의 PAGE_RETRIES). 사람이 봐야 할 것은 "지금 3번째 장이 걸려서
     *  다시 그리고 있다" 이지 원인 문구(안전 필터 사유 등)가 아니라, 여기서는
     *  몇 번째 장인지만 뽑는다 — 나머지는 log 에 그대로 남아 있다. */
    private static final Pattern RETRY =
            Pattern.compile("\\[페이지\\s*(\\d+)\\s*/\\s*\\d+]\\s*다시 그리는 중");

    private final Map<Long, State> byJob = new ConcurrentHashMap<>();

    /** 한 줄 들어왔다. */
    public void line(Long jobId, String line) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.log.addLast(line);
            while (state.log.size() > MAX_LINES) {
                state.log.removeFirst();
            }
            if (state.counted) {
                /* 몇 장 그렸는지는 자바가 센다({@link #drew}) — 장면을 동시에
                   그리는 중이다. 파이썬 줄은 그린 순서대로 오지 않아서, 그대로
                   받으면 진행 막대가 5 에서 2 로 되돌아간다. */
                return;
            }
            Matcher retry = RETRY.matcher(line);
            if (retry.find()) {
                // 다시 그리는 줄도 "[페이지 N/M]" 모양이라 아래 ART 에도 걸리는데,
                // 그러면 "다시 그리는 중" 표시가 이 줄 하나로 바로 지워진다.
                // 그래서 여기서 잡히면 ART 쪽은 안 본다.
                state.retryPage = Integer.parseInt(retry.group(1));
                return;
            }
            Matcher art = ART.matcher(line);
            if (art.find()) {
                state.done = Integer.parseInt(art.group(1));
                state.total = Integer.parseInt(art.group(2));
                // 정상적으로 다음 걸음을 알리는 줄이 왔다 — 걸렸던 것은 풀렸다.
                state.retryPage = 0;
            }
        }
    }

    /**
     * 몇 장 중 몇 장을 그렸는지를 <b>부르는 쪽이 정한다.</b>
     *
     * 장면을 동시에 그릴 때 쓴다 — 그림은 여러 프로세스에서 제각기 끝나므로
     * 파이썬이 내는 「장면 N/M」 줄로는 순서를 셀 수 없다. 한 번이라도 이걸
     * 부르면 그 작업은 그때부터 자바가 센 값만 쓴다.
     */
    public void drew(Long jobId, int done, int total) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.counted = true;
            state.done = done;
            state.total = total;
            state.retryPage = 0;
            state.phaseAt = Instant.now();
        }
    }

    /**
     * {@code page} 번째 장이 다 그려졌다. 몇 장째인지와 <b>어느 장인지</b>를 같이 센다.
     *
     * 장면을 동시에 그리면 5쪽이 2쪽보다 먼저 끝난다. 개수만 넘기면 화면은
     * 「1~N쪽이 있다」고 읽고 아직 없는 2쪽을 불러 깨진 그림을 띄웠다(#509).
     */
    /**
     * {@code page} 번째 장을 <b>그리기 시작했다.</b> 남은 시간을 셀 때 이 장은 처음부터가
     * 아니라 남은 몫만 센다 — 안 그러면 한 장이 끝날 때마다 반쯤 그린 옆 장들까지
     * 처음부터 다시 세어 남은 시간이 4분→5분으로 늘었다(#509).
     */
    public void startedPage(Long jobId, int page) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.inflight.put(page, Instant.now());
        }
    }

    public void drewPage(Long jobId, int page, int total) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.inflight.remove(page);
            state.counted = true;
            state.drawn.add(page);
            state.done = state.drawn.size();
            state.total = total;
            state.retryPage = 0;
            state.phaseAt = Instant.now();
        }
    }

    /**
     * 화 전체 검수가 걸린 장들을 <b>다시 그리기 시작한다.</b>
     *
     * 이게 없을 때는 검수 뒤 다시 그리는 몇 분 동안 화면이 「검수하고 있어요 ·
     * 7번째 장을 그리고 있어요」로 멈춰 있었다(#509).
     */
    public void redrawing(Long jobId, List<Integer> pages) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.redraw = List.copyOf(pages);
            state.redrawDone = 0;
            state.inflight.clear();
            state.phaseAt = Instant.now();
        }
    }

    /** 다시 그리던 장 하나가 끝났다. */
    public void redrew(Long jobId, int page) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.inflight.remove(page);
            state.redrawDone++;
            state.phaseAt = Instant.now();
        }
    }

    /** 화 전체 검수를 (다시) 돌린다 — 다시 그리던 것은 끝났다. */
    public void reviewing(Long jobId) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.redraw = List.of();
            state.redrawDone = 0;
            state.inflight.clear();
            state.phaseAt = Instant.now();
        }
    }

    /** 검수가 도는 동안 띄울 한 줄. 비우면 단계 기본 문구가 나간다. */
    public void say(Long jobId, String say) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.say = say;
        }
    }

    public Snapshot of(Long jobId) {
        State state = byJob.get(jobId);
        if (state == null) {
            return new Snapshot(List.of(), "", 0, 0, 0, List.of(), List.of(), 0, null, List.of());
        }
        synchronized (state) {
            return new Snapshot(new ArrayList<>(state.log), state.say, state.done, state.total,
                    state.retryPage, new ArrayList<>(state.drawn), state.redraw, state.redrawDone,
                    state.phaseAt, new ArrayList<>(state.inflight.values()));
        }
    }

    /** 끝난 작업은 잊는다. */
    public void forget(Long jobId) {
        byJob.remove(jobId);
    }

    private static final class State {
        private final Deque<String> log = new ArrayDeque<>();
        private String say = "";
        private int done;
        private int total;
        /** 지금 다시 그리는 중인 장 번호. 0 이면 없다. */
        private int retryPage;
        /** 몇 장 그렸는지를 자바가 세고 있는가(장면을 동시에 그리는 중). */
        private boolean counted;
        /** 다 그려진 장 번호. 동시에 그리면 순서대로 안 끝난다. */
        private final java.util.TreeSet<Integer> drawn = new java.util.TreeSet<>();
        /** 화 전체 검수 뒤 지금 다시 그리는 장들. 비었으면 다시 그리는 중이 아니다. */
        private List<Integer> redraw = List.of();
        private int redrawDone;
        /** 마지막으로 무언가 끝난 때(장 하나 · 다시 그리기 시작 · 검수 시작). */
        private Instant phaseAt;
        /** 지금 그리는 중인 장 -> 그리기 시작한 때. */
        private final Map<Integer, Instant> inflight = new java.util.HashMap<>();
    }

    /**
     * @param done      지금까지 그린 장
     * @param total     그릴 장 (0 이면 아직 모른다)
     * @param retryPage 지금 걸려서 다시 그리는 중인 장 번호. 0 이면 없다.
     * @param drawn     다 그려진 장 번호(오름차순). 한 프로세스로 차례로 그렸으면 비어 있다
     * @param redraw    화 전체 검수 뒤 다시 그리는 장들. 비었으면 다시 그리는 중이 아니다
     * @param redrawDone 그중 끝난 수
     * @param phaseAt   마지막으로 무언가 끝난 때. 모르면 {@code null}
     * @param inflight  지금 그리는 중인 장들의 시작 시각
     */
    public record Snapshot(List<String> log, String say, int done, int total, int retryPage,
                           List<Integer> drawn, List<Integer> redraw, int redrawDone,
                           Instant phaseAt, List<Instant> inflight) {

        /** 어느 장인지·다시 그리기를 모르는 옛 모양(한 프로세스로 차례로 그릴 때와 같다). */
        public Snapshot(List<String> log, String say, int done, int total, int retryPage) {
            this(log, say, done, total, retryPage, List.of(), List.of(), 0, null, List.of());
        }

        /** 그리는 중인 장의 시작 시각을 모를 때. */
        public Snapshot(List<String> log, String say, int done, int total, int retryPage,
                        List<Integer> drawn, List<Integer> redraw, int redrawDone, Instant phaseAt) {
            this(log, say, done, total, retryPage, drawn, redraw, redrawDone, phaseAt, List.of());
        }
    }
}
