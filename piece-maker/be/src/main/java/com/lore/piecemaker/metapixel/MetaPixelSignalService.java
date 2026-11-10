package com.lore.piecemaker.metapixel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.lore.piecemaker.retention.MeasurementRetention;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 광고 출처와 무관한 첫 정상 열람 신호. 내부 24시간 신규 성과 집계와 구분한다. */
@Service
public class MetaPixelSignalService {
    public static final String EVENT_NAME = "PieceMakerFirstResultViewed";
    private final MetaPixelSettings settings;
    private final JdbcTemplate jdbc;
    private final Set<Long> excluded;
    private final boolean reviewed;

    public MetaPixelSignalService(MetaPixelSettings settings, JdbcTemplate jdbc,
            @Value("${lore.piece-maker.ad-report.excluded-user-ids:}") String excluded,
            @Value("${lore.piece-maker.ad-report.test-exclusions-reviewed:false}") boolean reviewed) {
        this.settings = settings;
        this.jdbc = jdbc;
        this.excluded = Arrays.stream(excluded.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
        this.reviewed = reviewed;
    }

    /** F12가 DB의 계정별 첫 기록을 새로 만든 요청에서만 부른다. 재요청에는 신호를 재발급하지 않는다. */
    public MetaPixelEvent forFirstView(long userId) {
        if (!settings.enabled() || !reviewed || excluded.contains(userId)) return null;
        boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from users u where u.id = ? and u.role = 'USER'
                      and u.status = 'ACTIVE' and u.deleted_at is null
                      and u.created_at > ? and u.created_at <= clock_timestamp()
                      and not exists (select 1 from piece_maker_measurement_gap g where g.user_id = u.id)
                )
                """, Boolean.class, userId, Timestamp.from(MeasurementRetention.availableSince(Instant.now()))));
        if (!eligible) return null;
        // 회원 번호나 가설 번호를 외부 이벤트 식별자로 사용하지 않는다.
        return new MetaPixelEvent(settings.pixelId(), settings.siteOrigin(), EVENT_NAME, UUID.randomUUID().toString());
    }
}
