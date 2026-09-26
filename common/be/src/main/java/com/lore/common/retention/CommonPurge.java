package com.lore.common.retention;

import com.lore.common.s3.S3Storage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 공용 표에서 탈퇴한 사람의 것을 지운다 — 올린 사진과 크레딧 기록.
 *
 * <h2>사진을 먼저 지운다</h2>
 *
 * {@code upload_ticket} 행에만 S3 키가 적혀 있다. 행을 먼저 지우면 <b>어느
 * 그림을 지워야 하는지 알 길이 사라져서 그림이 영영 남는다.</b> 키를 먼저
 * 모아 S3 에서 지우고, 그 다음에 행을 지운다.
 *
 * <h2>크레딧은 여기서 행째 지운다</h2>
 *
 * 기간 파기와 다르다. 계정이 사라지면 그 사람의 잔액을 물을 일이 없고, 중복
 * 지급 방지도 의미가 없어진다({@code uk_credit_event_once} 는 사람별이다).
 * 잔액이 틀어질 걱정은 <b>계정이 남아 있을 때</b>의 이야기다.
 */
@Component
public class CommonPurge implements UserDataPurge {

    private final CommonPurgeRepository rows;
    private final S3Storage storage;
    private final boolean hasBucket;

    public CommonPurge(CommonPurgeRepository rows, S3Storage storage, BucketPresence bucket) {
        this.rows = rows;
        this.storage = storage;
        this.hasBucket = bucket.exists();
    }

    @Override
    public String domain() {
        return "common";
    }

    @Override
    public int purge(Long userId) {
        List<String> keys = rows.uploadKeysOf(userId);
        if (hasBucket && !keys.isEmpty()) {
            storage.delete(keys);
        }
        return rows.deleteUploadTickets(userId) + rows.deleteCreditEvents(userId);
    }
}
