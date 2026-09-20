package com.lore.zzal.archive;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 장부를 DB 표({@code zzal_event_archive_part})로 적는다.
 *
 * ★ {@code @Transactional} 대신 {@link TransactionTemplate} 을 쓰는 이유 — 경계가 눈에 보인다.
 *   같은 클래스 안에서 부르면 {@code @Transactional} 이 조용히 안 걸리는 함정이 있는데,
 *   여기서는 그 함정이 생길 자리 자체를 없앤다.
 */
@Component
public class JpaEventArchiveLedger implements EventArchiveLedger {

    private final ZzalEventArchivePartRepository parts;
    private final TransactionTemplate tx;

    public JpaEventArchiveLedger(ZzalEventArchivePartRepository parts, TransactionTemplate tx) {
        this.parts = parts;
        this.tx = tx;
    }

    @Override
    public long watermark() {
        Long max = parts.maxToEventId();
        return max == null ? 0L : max;
    }

    @Override
    public void record(List<ZzalEventArchivePart> rows) {
        if (rows.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> parts.saveAll(rows));
    }
}
