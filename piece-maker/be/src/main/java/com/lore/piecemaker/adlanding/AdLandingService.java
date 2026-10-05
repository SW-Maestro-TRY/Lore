package com.lore.piecemaker.adlanding;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.retention.MeasurementRetention;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class AdLandingService {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern AD_ID = Pattern.compile("[0-9]{1,64}");
    private final AdLandingRepository repository;
    private final boolean enabled;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public AdLandingService(AdLandingRepository repository, @Value("${app.analytics.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    public void requireEnabled() {
        if (!enabled) throw new BusinessException(ErrorCode.PIECE_MAKER_AD_MEASUREMENT_DISABLED);
    }

    @Transactional
    public AdLandingResponse capture(String anonId, Long userId, AdLandingRequests.Capture input) {
        requireEnabled();
        validate(input);
        requireExpectedUser(userId, input.expectedUserId());
        requireRate(anonId);
        repository.insert(anonId, userId, input);
        var landing = repository.lockRequest(anonId, input.requestKey());
        if (!landing.landedAt().isAfter(MeasurementRetention.availableSince(Instant.now()))) throw missing();
        if (!landing.attribution().equals(input.attribution())) throw conflict();
        return new AdLandingResponse(landing.id(), landing.landedAt());
    }

    /** 소유자 충돌 표시가 409와 함께 롤백되지 않도록 한다. DB 실패는 그대로 롤백한다. */
    @Transactional(noRollbackFor = BusinessException.class)
    public AdLandingResponse.Claim claim(String anonId, Long userId, String id, Long expectedUserId) {
        requireEnabled();
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!userId.equals(expectedUserId)) throw conflict();
        if (anonId == null) throw missing();
        UUID key;
        try { key = UUID.fromString(id); } catch (IllegalArgumentException e) { throw missing(); }
        requireRate(anonId);
        var landing = repository.lockLanding(anonId, key).orElseThrow(AdLandingService::missing);
        if (!landing.landedAt().isAfter(MeasurementRetention.availableSince(Instant.now()))) throw missing();
        bind(landing, userId);
        return new AdLandingResponse.Claim(true, landing.landedAt());
    }

    private void bind(AdLandingRepository.Landing landing, long userId) {
        if (landing.owner() != null && landing.owner() != userId) {
            repository.markAmbiguous(landing.id());
            throw conflict();
        }
        if (landing.ambiguous()) throw conflict();
        if (landing.owner() == null) repository.claim(landing.id(), userId);
    }

    static void validate(AdLandingRequests.Capture input) {
        AdLandingRequests.Attribution a = input == null ? null : input.attribution();
        if (input == null || input.requestKey() == null || a == null
                || !"facebook".equals(a.utmSource()) || !"paid_social".equals(a.utmMedium())
                || !token(a.utmCampaign()) || !token(a.placement())
                || a.utmContent() == null || !AD_ID.matcher(a.utmContent()).matches()
                || a.firstAdLandedAt() == null || a.firstAdLandedAt() <= 0
                || a.firstAdLandedAt() <= MeasurementRetention.availableSince(Instant.now()).toEpochMilli()
                || a.firstAdLandedAt() > Instant.now().plusSeconds(300).toEpochMilli()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "광고 유입 정보가 올바르지 않습니다");
        }
    }

    static void requireExpectedUser(Long userId, Long expectedUserId) {
        if (!Objects.equals(userId, expectedUserId)) throw conflict();
    }

    private static boolean token(String value) { return value != null && TOKEN.matcher(value).matches(); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.PIECE_MAKER_AD_LANDING_NOT_FOUND); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.PIECE_MAKER_AD_LANDING_CONFLICT); }

    private void requireRate(String anonId) {
        if (anonId == null) throw missing();
        long minute = System.currentTimeMillis() / 60_000;
        if (windows.size() >= 10_000) windows.entrySet().removeIf(e -> e.getValue().minute < minute);
        // 저장소를 무한히 늘리지 않는다. 쿠키별 제한은 악의적 쿠키 교체를 완전히 방지하지 않는다.
        if (windows.size() >= 10_000 && !windows.containsKey(anonId)) throw new BusinessException(ErrorCode.PIECE_MAKER_AD_LANDING_RATE_LIMIT);
        if (windows.computeIfAbsent(anonId, k -> new Window(minute)).hit(minute) > 60) {
            throw new BusinessException(ErrorCode.PIECE_MAKER_AD_LANDING_RATE_LIMIT);
        }
    }

    private static final class Window {
        private volatile long minute;
        private int count;
        private Window(long minute) { this.minute = minute; }
        private synchronized int hit(long now) { if (minute != now) { minute = now; count = 0; } return ++count; }
    }
}
