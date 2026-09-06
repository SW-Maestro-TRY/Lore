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

    private final Map<Long, State> byJob = new ConcurrentHashMap<>();

    /** 한 줄 들어왔다. */
    public void line(Long jobId, String line) {
        State state = byJob.computeIfAbsent(jobId, k -> new State());
        synchronized (state) {
            state.log.addLast(line);
            while (state.log.size() > MAX_LINES) {
                state.log.removeFirst();
            }
            Matcher art = ART.matcher(line);
            if (art.find()) {
                state.done = Integer.parseInt(art.group(1));
                state.total = Integer.parseInt(art.group(2));
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
            return new Snapshot(List.of(), "", 0, 0);
        }
        synchronized (state) {
            return new Snapshot(new ArrayList<>(state.log), state.say, state.done, state.total);
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
    }

    /**
     * @param done  지금까지 그린 장
     * @param total 그릴 장 (0 이면 아직 모른다)
     */
    public record Snapshot(List<String> log, String say, int done, int total) {
    }
}
