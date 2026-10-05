package com.lore.piecemaker.adreport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 운영 검증으로 확인한 연속 수집 구간. 설정이 없거나 구간 밖이면 신규 여부를 추측하지 않는다. */
@Component
public class MeasurementHistory {
    private final Instant verifiedFrom;
    private final Instant verifiedThrough;

    public MeasurementHistory(
            @Value("${lore.piece-maker.ad-report.history-verified-from:}") String from,
            @Value("${lore.piece-maker.ad-report.history-verified-through:}") String through) {
        try {
            verifiedFrom = from.isBlank() ? null : Instant.parse(from);
            verifiedThrough = through.isBlank() ? null : Instant.parse(through);
            if ((verifiedFrom == null) != (verifiedThrough == null)
                    || (verifiedFrom != null && (verifiedFrom.isBefore(Instant.EPOCH)
                    || !verifiedFrom.isBefore(verifiedThrough) || verifiedThrough.isAfter(Instant.now())))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("광고 수집 검증 구간은 빈 값 또는 과거의 from < through 시각 쌍이어야 합니다", e);
        }
    }

    public Instant verifiedFrom() { return verifiedFrom; }
    public Instant verifiedThrough() { return verifiedThrough; }

    public boolean covers(Instant from, Instant through) {
        return verifiedFrom != null && !from.isBefore(verifiedFrom) && !through.isAfter(verifiedThrough);
    }
}
