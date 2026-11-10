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
    /** 줄마다 "이 시각(nanoTime) 전에는 보내지 않는다". 0 = 막히지 않음. 429 를 받으면 올린다(설계 2-2). */
    private final Map<Lane, java.util.concurrent.atomic.AtomicLong> blockedUntil = new EnumMap<>(Lane.class);
    /** 429 에 Retry-After 가 없을 때 줄을 막는 시간. */
    private final Duration defaultBlock;

    /** 기본 차단 시간 60초. */
    public static final Duration DEFAULT_QUOTA_BLOCK = Duration.ofSeconds(60);

    public OpenAiGateway(Map<Lane, LaneConfig> configs) {
        this(configs, DEFAULT_QUOTA_BLOCK);
    }

    public OpenAiGateway(Map<Lane, LaneConfig> configs, Duration defaultBlock) {
        this(configs, defaultBlock, null);
    }

    /** @param shared 모든 줄이 같이 쓸 HttpClient(시험·옛 생성자용). null 이면 줄마다 새로 만든다. */
    OpenAiGateway(Map<Lane, LaneConfig> configs, Duration defaultBlock, HttpClient shared) {
        this.configs = new EnumMap<>(configs);
        this.defaultBlock = java.util.Objects.requireNonNull(defaultBlock);
        for (Lane lane : Lane.values()) {
            blockedUntil.put(lane, new java.util.concurrent.atomic.AtomicLong());
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
        return new OpenAiGateway(LaneConfig.defaults(), DEFAULT_QUOTA_BLOCK, java.util.Objects.requireNonNull(http));
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

    /** 그 줄이 429 로 막혀 있는 남은 시간. 막히지 않았으면 0. */
    public Duration blockedFor(Lane lane) {
        long until = blockedUntil.get(lane).get();
        long left = until == 0 ? 0 : until - System.nanoTime();
        return left > 0 ? Duration.ofNanos(left) : Duration.ZERO;
    }

    private void block(Lane lane, Duration retryAfter) {
        long until = System.nanoTime() + retryAfter.toNanos();
        blockedUntil.get(lane).accumulateAndGet(until, Math::max);
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

    /** 429, 또는 본문이 잔액·한도를 말하는 4xx. */
    static boolean quota(int status, String body) {
        if (status == 429) return true;
        if (status < 400 || status >= 500) return false;
        String b = body.toLowerCase(java.util.Locale.ROOT);
        return b.contains("insufficient_quota") || b.contains("billing_hard_limit_reached") || b.contains("quota_exceeded");
    }

    /** Retry-After(초, 소수 허용) · retry-after-ms. 없거나 못 읽으면 기본값. */
    static Duration retryAfter(HttpResponse<?> res, Duration fallback) {
        try {
            var ms = res.headers().firstValue("retry-after-ms");
            if (ms.isPresent()) return Duration.ofMillis(Math.max(0, (long) Double.parseDouble(ms.get().strip())));
            var sec = res.headers().firstValue("retry-after");
            if (sec.isPresent()) return Duration.ofMillis(Math.max(0, (long) (Double.parseDouble(sec.get().strip()) * 1000)));
        } catch (NumberFormatException ignored) {
            // HTTP 날짜 형식 등 — 기본값
        }
        return fallback;
    }

    private static String snippet(String body) {
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    /** 시작 행 커밋을 기다리는 상한. 넘으면 먼저 보낸다. */
    static final Duration STARTED_WAIT = Duration.ofMillis(500);

    /** 호출 하나. 많아야 한 번 보낸다. */
    public final class Call implements AutoCloseable {
        private final Lane lane;
        private final String model;
        private final ProviderUsageRecorder recorder;
        private final ProviderAttempt attempt;
        private boolean sent;

        private Call(Lane lane, String model, ProviderUsageRecorder recorder) {
            this.lane = lane;
            this.model = model;
            this.recorder = recorder;
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
                } catch (java.net.http.HttpTimeoutException e) {
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    throw e;
                } catch (java.io.IOException e) {
                    attempt.record(ProviderUsageRecorder.headers(model, 0, null));
                    throw new ProviderServerException(0, String.valueOf(e.getMessage()), e);
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
                    if (e.getCause() instanceof java.io.IOException io) {
                        throw new ProviderServerException(0, String.valueOf(io.getMessage()), io);
                    }
                    throw e;
                }
                return observed(res);
            } finally {
                leave();
            }
        }

        /**
         * 응답 머리(상태·요청 번호)를 기록하고, 한도·서버 오류를 정해진 예외로 올린다(설계 2-2).
         * 그 밖의 상태(200, 400 거부 등)는 그대로 돌려준다 — 거부 본문은 클라이언트가 위층에 넘긴다.
         */
        private HttpResponse<String> observed(HttpResponse<String> res) throws Exception {
            String requestId = res.headers().firstValue("x-request-id").orElse(null);
            int status = res.statusCode();
            attempt.record(ProviderUsageRecorder.headers(model, status, requestId));
            String body = res.body() == null ? "" : res.body();
            if (quota(status, body)) {
                Duration retryAfter = retryAfter(res, defaultBlock);
                block(lane, retryAfter);
                log.warn("OpenAI 한도 — lane={} HTTP {} · {}초 동안 이 줄을 막는다", lane.key(), status, retryAfter.toSeconds());
                throw new ProviderQuotaException(status, retryAfter, snippet(body));
            }
            if (status >= 500) {
                throw new ProviderServerException(status, snippet(body), null);
            }
            return res;
        }

        private boolean holding;

        private void enter() throws Exception {
            if (sent) throw new IllegalStateException("한 호출은 한 번만 보낸다");
            sent = true;
            refuseIfBlocked();
            LaneConfig c = configs.get(lane);
            long t0 = System.nanoTime();
            if (!permits.get(lane).acquire(CallPriority.current(), c.maxWaitMs())) {
                long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
                log.warn("OpenAI 줄 자리 없음 — lane={} waited={}ms model={}", lane.key(), waited, model);
                throw new LaneBusyException(lane, waited);
            }
            holding = true;
            // 자리를 기다리는 동안 다른 호출이 429 를 받았을 수 있다.
            try {
                refuseIfBlocked();
            } catch (ProviderQuotaException e) {
                leave();
                throw e;
            }
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            var meta = new ProviderCallLog.Call(attempt.callId(), lane.key(), model, waitedMs,
                    permits.get(lane).inFlight(), com.lore.zzal.generation.client.ProviderUsageContext.current());
            attempt.call(meta);
            if (recorder instanceof ProviderCallLog callLog) {
                logStarted(callLog, meta);
            }
        }

        /**
         * 시작 행을 따로 커밋한다(설계 1-3). ★ 유료 호출을 막지 않는다 — 기록이 실패하면 경고만,
         * DB 연결을 못 얻어 늦어지면 {@link #STARTED_WAIT} 만 기다리고 보낸다(기록은 뒤에서 마저 끝난다).
         * 트랜잭션 안에서 부른 경우에도 연결 1개를 쥔 채 두 번째 연결을 무한히 기다리지 않게 하려는 것이다.
         */
        private void logStarted(ProviderCallLog callLog, ProviderCallLog.Call meta) {
            java.util.concurrent.Future<?> f;
            try {
                f = executor.submit(() -> {
                    try {
                        callLog.started(meta);
                    } catch (Exception e) {
                        log.warn("호출 전 기록 실패 — 호출은 그대로 보낸다 callId={}", meta.callId(), e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                log.warn("호출 전 기록을 맡길 실행기가 닫혔다 — callId={}", meta.callId());
                return;
            }
            try {
                f.get(STARTED_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("호출 전 기록이 {}ms 안에 안 끝나 먼저 보낸다 callId={}", STARTED_WAIT.toMillis(), meta.callId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.warn("호출 전 기록 실패 — callId={}", meta.callId(), e.getCause());
            }
        }

        /** 줄이 막혀 있으면 보내지 않고 같은 예외로 돌려준다. */
        private void refuseIfBlocked() throws ProviderQuotaException {
            Duration left = blockedFor(lane);
            if (left.compareTo(Duration.ZERO) > 0) {
                throw new ProviderQuotaException(0, left, lane.key() + " 줄이 한도로 막혀 있어 보내지 않았다");
            }
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
