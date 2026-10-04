package com.lore.webtoon.push;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * 구독을 받고 끊는다.
 *
 * <h2>아무 주소나 안 받는다</h2>
 *
 * 서버가 이 주소로 직접 요청을 보낸다. 아무 주소나 받으면 바깥 사람이 우리
 * 서버를 시켜 내부망(메타데이터 서버 · DB 등)에 요청을 보내게 할 수 있다. 그래서
 * 브라우저 회사의 푸시 서버 주소만 받는다.
 */
@Service
public class PushSubscriptions {

    /** 크롬·엣지(구글) · 파이어폭스 · 사파리 · 윈도우 엣지 옛 판. */
    private static final List<String> PUSH_HOSTS = List.of(
            "fcm.googleapis.com", "push.services.mozilla.com", "push.apple.com", "notify.windows.com");

    private final PushSubscriptionRepository subs;

    public PushSubscriptions(PushSubscriptionRepository subs) {
        this.subs = subs;
    }

    /** 이 기기를 받는 곳으로 적는다. 같은 기기면 고쳐 쓴다. */
    @Transactional
    public void subscribe(String endpoint, String p256dh, String auth, Long userId, String uid, String lang) {
        String ep = checkEndpoint(endpoint);
        checkKey(p256dh, 65, "p256dh");
        checkKey(auth, 16, "auth");
        String cleanUid = uid == null || uid.isBlank() || uid.length() > 64 ? null : uid.trim();
        if (userId == null && cleanUid == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "누구의 기기인지 알 수 없습니다");
        }
        Instant now = Instant.now();
        PushSubscription row = subs.findByEndpoint(ep).orElseGet(() -> new PushSubscription(ep, now));
        row.update(p256dh.trim(), auth.trim(), userId, cleanUid, PushMessages.normalize(lang), now);
        subs.save(row);
    }

    @Transactional
    public void unsubscribe(String endpoint) {
        if (endpoint != null && !endpoint.isBlank()) {
            subs.deleteByEndpoint(endpoint.trim());
        }
    }

    static String checkEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > 1024) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 주소가 없습니다");
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 주소 모양이 아닙니다");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        boolean known = PUSH_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        if (!"https".equals(uri.getScheme()) || !known || uri.getUserInfo() != null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 알림 주소입니다");
        }
        return endpoint.trim();
    }

    private static void checkKey(String value, int bytes, String name) {
        try {
            if (value != null && PushCrypto.unb64(value).length == bytes) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // 아래에서 같이 거절한다
        }
        throw new BusinessException(ErrorCode.INVALID_INPUT, "알림 키(" + name + ") 모양이 아닙니다");
    }
}
