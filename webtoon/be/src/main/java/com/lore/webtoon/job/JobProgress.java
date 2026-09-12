package com.lore.webtoon.job;

import org.springframework.stereotype.Component;

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
            return new Snapshot(List.of(), "", 0, 0, 0);
        }
        synchronized (state) {
            return new Snapshot(new ArrayList<>(state.log), state.say, state.done, state.total,
                    state.retryPage);
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
    }

    /**
     * @param done      지금까지 그린 장
     * @param total     그릴 장 (0 이면 아직 모른다)
     * @param retryPage 지금 걸려서 다시 그리는 중인 장 번호. 0 이면 없다.
     */
    public record Snapshot(List<String> log, String say, int done, int total, int retryPage) {
    }
}
