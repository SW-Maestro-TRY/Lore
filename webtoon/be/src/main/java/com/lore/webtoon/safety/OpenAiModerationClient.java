package com.lore.webtoon.safety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI 의 moderation 으로 묻는다.
 *
 * <h2>왜 단어 목록이 아니라 이것인가</h2>
 *
 * 금지어 목록은 한국어·영어·오타·띄어쓰기·은어 앞에서 금방 구멍이 난다. 이 모델은
 * 여러 언어로 선정성·미성년 성적 묘사·혐오·폭력 묘사·자해를 분류하고, <b>호출에
 * 돈이 들지 않는다</b>(생성 모델과 달리 무료 엔드포인트). 이미지도 같이 볼 수 있어서
 * 사진 검사로 넓힐 자리도 여기다.
 *
 * <h2>열쇠</h2>
 *
 * 생성 파이프라인이 쓰는 {@code WEBTOON_API_KEY} 를 그대로 쓴다. 자바가 파이썬에
 * 넘겨 주는 그 값이라 새로 넣을 것이 없다. 비어 있으면 {@link SafetyGuard} 가
 * "검사 못 함" 으로 다룬다.
 */
@Component
public class OpenAiModerationClient implements ModerationClient {

    static final String ENDPOINT = "https://api.openai.com/v1/moderations";
    static final String MODEL = "omni-moderation-latest";

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String apiKey;
    private final Duration timeout;

    public OpenAiModerationClient(
            @Value("${lore.webtoon.safety.api-key:${WEBTOON_API_KEY:}}") String apiKey,
            @Value("${lore.webtoon.safety.timeout-seconds:8}") int timeoutSeconds) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean ready() {
        return !apiKey.isEmpty();
    }

    @Override
    public Verdict moderate(String text) throws ModerationUnavailable {
        if (!ready()) {
            throw new ModerationUnavailable("WEBTOON_API_KEY 가 없어 검사를 못 합니다", null);
        }
        try {
            String body = json.writeValueAsString(Map.of("model", MODEL, "input", text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() / 100 != 2) {
                throw new ModerationUnavailable("moderation HTTP " + res.statusCode() + ": " + head(res.body()), null);
            }
            return parse(res.body());
        } catch (IOException e) {
            throw new ModerationUnavailable("moderation 통신 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModerationUnavailable("moderation 중단", e);
        }
    }

    Verdict parse(String body) throws ModerationUnavailable {
        try {
            JsonNode r = json.readTree(body).path("results").path(0);
            if (r.isMissingNode()) {
                throw new ModerationUnavailable("moderation 응답에 results 가 없음: " + head(body), null);
            }
            Map<String, Boolean> cats = new LinkedHashMap<>();
            r.path("categories").fields().forEachRemaining(f -> cats.put(f.getKey(), f.getValue().asBoolean(false)));
            Map<String, Double> scores = new LinkedHashMap<>();
            r.path("category_scores").fields().forEachRemaining(f -> scores.put(f.getKey(), f.getValue().asDouble(0)));
            return new Verdict(r.path("flagged").asBoolean(false), cats, scores);
        } catch (IOException e) {
            throw new ModerationUnavailable("moderation 응답을 못 읽음", e);
        }
    }

    private static String head(String s) {
        return s == null ? "" : s.length() > 200 ? s.substring(0, 200) : s;
    }
}
