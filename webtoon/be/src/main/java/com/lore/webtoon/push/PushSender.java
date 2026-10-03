package com.lore.webtoon.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.time.Instant;

/**
 * 푸시 서버(크롬은 Google, 사파리는 Apple, 파이어폭스는 Mozilla)에 한 통을 보낸다.
 *
 * <h2>키가 없으면 꺼진다</h2>
 *
 * VAPID 키 한 쌍이 있어야 보낼 수 있다. 비밀값이라 코드 기본값에 둘 수 없고
 * 환경변수로만 받는다. <b>없으면 푸시만 조용히 꺼지고 나머지는 그대로다</b> —
 * 화면은 {@link #publicKey()} 가 비어 있는 것을 보고 「알림 받기」를 안 띄운다.
 *
 * <pre>
 *   LORE_WEBTOON_PUSH_PUBLIC_KEY   65바이트 공개키, base64url
 *   LORE_WEBTOON_PUSH_PRIVATE_KEY  32바이트 개인키, base64url
 * </pre>
 *
 * 새로 만들 때: {@code npx web-push generate-vapid-keys} 가 이 모양 그대로 낸다.
 * <b>한 번 정하면 바꾸지 않는다</b> — 바꾸면 그전에 받은 구독이 전부 못 쓰게 된다.
 */
@Component
public class PushSender {

    private static final Logger log = LoggerFactory.getLogger(PushSender.class);

    /** 푸시 서버가 기기에 못 전하면 이만큼 들고 있다가 버린다. 하루 지난 「골라 주세요」는 의미가 없다. */
    private static final int TTL_SECONDS = 24 * 60 * 60;

    private final String publicKey;
    private final ECPrivateKey privateKey;
    private final String subject;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public PushSender(@Value("${lore.webtoon.push.public-key:}") String publicKey,
                      @Value("${lore.webtoon.push.private-key:}") String privateKey,
                      @Value("${lore.webtoon.push.subject:mailto:lightbluue6@gmail.com}") String subject) {
        ECPrivateKey key = null;
        String pub = publicKey == null ? "" : publicKey.trim();
        if (!pub.isEmpty() && privateKey != null && !privateKey.isBlank()) {
            try {
                PushCrypto.publicKey(PushCrypto.unb64(pub));    // 모양이 맞는지만 본다
                key = PushCrypto.privateKey(PushCrypto.unb64(privateKey));
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                log.error("웹푸시 키를 읽지 못해 푸시를 끕니다", e);
                pub = "";
            }
        } else {
            pub = "";
        }
        this.publicKey = pub;
        this.privateKey = key;
        this.subject = subject;
        if (!enabled()) {
            log.info("웹푸시 키가 없어 푸시 알림은 꺼져 있습니다 (LORE_WEBTOON_PUSH_PUBLIC_KEY · _PRIVATE_KEY)");
        }
    }

    public boolean enabled() {
        return privateKey != null && !publicKey.isEmpty();
    }

    /** 화면이 구독할 때 쓰는 공개키. 꺼져 있으면 빈 글자. */
    public String publicKey() {
        return publicKey;
    }

    /**
     * 한 통 보낸다.
     *
     * @param tag 같은 작업의 알림끼리 묶는 이름 — 푸시 서버가 아직 못 전한 앞 알림을
     *            새 알림으로 갈아 끼운다(Topic). 「골라 주세요」 뒤에 「다 됐어요」가
     *            따라오면 앞의 것은 이미 쓸모가 없다
     * @return 푸시 서버의 응답 코드. 보내지도 못했으면 -1
     */
    int send(PushSubscription to, String json, String tag) {
        if (!enabled()) {
            return -1;
        }
        try {
            byte[] body = PushCrypto.encrypt(PushCrypto.unb64(to.getP256dh()), PushCrypto.unb64(to.getAuth()),
                    json.getBytes(StandardCharsets.UTF_8));
            URI endpoint = URI.create(to.getEndpoint());
            String audience = endpoint.getScheme() + "://" + endpoint.getHost()
                    + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
            long exp = Instant.now().plus(Duration.ofHours(12)).getEpochSecond();
            String jwt = PushCrypto.vapidJwt(audience, subject, privateKey, exp);

            HttpRequest.Builder req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("TTL", String.valueOf(TTL_SECONDS))
                    .header("Urgency", "high")
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("Authorization", "vapid t=" + jwt + ", k=" + publicKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (tag != null && tag.matches("[A-Za-z0-9_-]{1,32}")) {
                req.header("Topic", tag);
            }
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400 && res.statusCode() != 404 && res.statusCode() != 410) {
                log.warn("푸시 서버가 거절했습니다 ({} {}): {}", res.statusCode(), endpoint.getHost(),
                        res.body().length() > 300 ? res.body().substring(0, 300) : res.body());
            }
            return res.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.warn("푸시를 보내지 못했습니다 (sub={})", to.getId(), e);
            return -1;
        }
    }
}
