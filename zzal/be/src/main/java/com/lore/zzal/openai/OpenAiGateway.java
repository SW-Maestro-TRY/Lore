package com.lore.zzal.openai;

import com.lore.zzal.generation.client.ProviderAttempt;
import com.lore.zzal.generation.client.ProviderUsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI 통합 창구(설계 2-1~2-4). zzal 이 OpenAI 로 보내는 모든 HTTP 요청은 여기서만 나간다.
 *
 * <h3>창구가 맡는 것</h3>
 * <ul>
 *   <li>줄({@link Lane})마다 동시 실행 상한 — 자리를 얻어야 나간다({@link LanePermits})</li>
 *   <li>줄마다 HttpClient 하나 — 대사 2초·그림 20초 연결 제한이 한 클라이언트에 섞이지 않게</li>
 *   <li>기록 — 한 호출 = 사용량 한 줄({@link ProviderAttempt})</li>
 *   <li>가상 스레드 실행기 — 생성 단계가 여기서 돈다(설계 3-8, 무제한 감시 풀 대체)</li>
 * </ul>
 * 클라이언트 셋({@code OpenAiImageClient}·{@code OpenAiTextClient}·{@code OpenAiChatLineClient})은
 * 요청을 만들고 응답을 해석하는 일만 한다.
 *
 * <h3>★ 창구 안에 {@code synchronized} 를 쓰지 않는다</h3>
 * 가상 스레드가 {@code synchronized} 안에서 기다리면 운반 스레드에 핀 고정된다. 자리는
 * {@link LanePermits}(ReentrantLock), 셈은 원자 변수로만 다룬다.
 *
 * <h3>★ 자동으로 다시 보내지 않는다</h3>
 * 한 {@link Call} 은 많아야 한 번 보낸다. 다시 할지는 부르는 쪽이 기록을 남기며 정한다
 * (그림 클라이언트의 "돈은 두 번 나가고 기록은 한 줄" 원칙).
 */
public class OpenAiGateway implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenAiGateway.class);

    private final Map<Lane, LaneConfig> configs;
    private final Map<Lane, LanePermits> permits = new EnumMap<>(Lane.class);
    private final Map<Lane, HttpClient> clients = new EnumMap<>(Lane.class);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public OpenAiGateway(Map<Lane, LaneConfig> configs) {
        this(configs, null);
    }

    /** @param shared 모든 줄이 같이 쓸 HttpClient(시험·옛 생성자용). null 이면 줄마다 새로 만든다. */
    OpenAiGateway(Map<Lane, LaneConfig> configs, HttpClient shared) {
        this.configs = new EnumMap<>(configs);
        for (Lane lane : Lane.values()) {
            LaneConfig c = this.configs.get(lane);
            if (c == null) throw new IllegalArgumentException("lane config missing: " + lane.key());
            permits.put(lane, new LanePermits(c.maxConcurrent()));
            clients.put(lane, shared != null ? shared : HttpClient.newBuilder()
                    .connectTimeout(c.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build());
        }
    }

    /**
     * 혼자 쓰는 창구 — 클라이언트의 옛 생성자(시험용 HTTP 이음새)가 쓴다. 운영 빈은 이걸 쓰지 않는다.
     * 줄 상한은 기본값, HttpClient 는 넘겨받은 것 하나.
     */
    public static OpenAiGateway standalone(HttpClient http) {
        return new OpenAiGateway(LaneConfig.defaults(), java.util.Objects.requireNonNull(http));
    }

    /** 생성 단계가 도는 가상 스레드 실행기. 단계마다 스레드 하나, 상한은 줄 자리가 건다. */
    public ExecutorService executor() {
        return executor;
    }

    public LaneConfig config(Lane lane) {
        return configs.get(lane);
    }

    /** 지금 그 줄에서 나가 있는 요청 수. */
    public int inFlight(Lane lane) {
        return permits.get(lane).inFlight();
    }

    /** 지금 그 줄에서 자리를 기다리는 요청 수. */
    public int waiting(Lane lane) {
        return permits.get(lane).waiting();
    }

    /**
     * 호출 하나를 연다. 보내기 전후의 기록이 이 안에 모인다 — 닫을 때 사용량 한 줄을 남긴다.
     *
     * @param model    요청 모델(기록용)
     * @param recorder 사용량 기록. 기록이 실패해도 호출은 막지 않는다
     */
    public Call call(Lane lane, String model, ProviderUsageRecorder recorder) {
        return new Call(lane, model, recorder);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /** 호출 하나. 많아야 한 번 보낸다. */
    public final class Call implements AutoCloseable {
        private final Lane lane;
        private final String model;
        private final ProviderAttempt attempt;
        private boolean sent;

        private Call(Lane lane, String model, ProviderUsageRecorder recorder) {
            this.lane = lane;
            this.model = model;
            this.attempt = new ProviderAttempt(recorder, model);
        }

        /**
         * 자리를 얻고 보낸 뒤 응답 전체를 받는다. 시간 제한은 {@link HttpRequest#timeout()} 이 건다.
         *
         * @throws LaneBusyException 자리 대기 상한을 넘었다(보내지 않았다)
         * @throws InterruptedException 끼어들기 — JDK HttpClient 가 진행 중 요청을 취소한다
         */
        public HttpResponse<String> send(HttpRequest req) throws Exception {
            enter();
            try {
                attempt.start();
                HttpResponse<String> res;
                try {
                    res = clients.get(lane).send(req, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    throw e;
                }
                return observed(res);
            } finally {
                leave();
            }
        }

        /**
         * 대사용 — 전체 시간을 한 번 더 묶는다. 요청 timeout 은 응답 머리까지만 세기 때문이다.
         * 시간을 넘기면 요청을 취소({@code cancel(true)})하고 {@link TimeoutException}.
         */
        public HttpResponse<String> sendWithin(HttpRequest req, Duration total) throws Exception {
            enter();
            try {
                attempt.start();
                CompletableFuture<HttpResponse<String>> f = clients.get(lane).sendAsync(req, HttpResponse.BodyHandlers.ofString());
                HttpResponse<String> res;
                try {
                    res = f.get(total.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    throw e;
                } catch (InterruptedException e) {
                    f.cancel(true);
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    throw e;
                } catch (ExecutionException e) {
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                        throw new TimeoutException("응답 시간 초과");
                    }
                    throw e;
                }
                return observed(res);
            } finally {
                leave();
            }
        }

        /** 응답 머리(상태·요청 번호)를 기록한다. */
        private HttpResponse<String> observed(HttpResponse<String> res) {
            String requestId = res.headers().firstValue("x-request-id").orElse(null);
            attempt.record(ProviderUsageRecorder.headers(model, res.statusCode(), requestId));
            return res;
        }

        private boolean holding;

        private void enter() throws Exception {
            if (sent) throw new IllegalStateException("한 호출은 한 번만 보낸다");
            sent = true;
            LaneConfig c = configs.get(lane);
            long t0 = System.nanoTime();
            if (!permits.get(lane).acquire(CallPriority.current(), c.maxWaitMs())) {
                long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                log.warn("OpenAI 줄 자리 없음 — lane={} waited={}ms model={}", lane.key(), waited, model);
                throw new LaneBusyException(lane, waited);
            }
            holding = true;
        }

        private void leave() {
            if (holding) {
                holding = false;
                permits.get(lane).release();
            }
        }

        /** 응답 몸을 읽은 결과(사용량)를 기록한다. */
        public void record(ProviderUsageRecorder.Observation observation) {
            attempt.record(observation);
        }

        public void estimatedCost(java.math.BigDecimal cost) {
            attempt.estimatedCost(cost);
        }

        public void success() {
            attempt.success();
        }

        public Lane lane() {
            return lane;
        }

        /** 사용량 한 줄을 남긴다(보낸 적이 없으면 남기지 않는다). */
        @Override
        public void close() {
            leave();
            attempt.close();
        }
    }
}
