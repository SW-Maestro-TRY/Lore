package com.lore.webtoon.retention;

import com.lore.common.retention.BucketPresence;
import com.lore.common.retention.UserDataPurge;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.work.RunLikeRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 탈퇴한 사람이 웹툰에서 만든 것을 지운다.
 *
 * <h2>그림을 먼저 지운다</h2>
 *
 * S3 키는 DB 행에만 적혀 있다. 행을 먼저 지우면 <b>어느 그림을 지워야 하는지
 * 알 길이 사라져 그림이 영영 남는다.</b> 그래서 순서가 고정이다 —
 * 작품 번호 모으기 → 키 모으기 → S3 삭제 → DB 삭제.
 *
 * <h2>왜 접두사로 한 번에 못 지우나</h2>
 *
 * 키가 {@code images/webtoon/<UUID>} 처럼 <b>납작한 UUID</b> 라 작품 번호도
 * 사람도 담고 있지 않다. 주소를 못 맞히게 일부러 그렇게 만들었다
 * ({@code webtoon/ai/upload/s3_upload.py} 의 같은 주석). 값을 치른 만큼
 * 파기는 DB 를 훑어 키를 모으는 수밖에 없다.
 *
 * <h2>지우는 순서</h2>
 *
 * 작품에 딸린 것(장·구운 장·얹은 것·다시 그린 기록·이야기·사용량)을 먼저
 * 지우고, 그 다음에 작품과 기록을, 마지막에 사람에 직접 달린 것을 지운다.
 * 외래키가 없어서 DB 가 순서를 강제하지는 않지만, 중간에 끊겼을 때 <b>남은
 * 것이 여전히 찾아지는</b> 순서여야 다음 회차가 이어서 지울 수 있다.
 */
@Component
public class WebtoonPurge implements UserDataPurge {

    private final WebtoonPurgeRepository rows;
    private final RunLikeRepository likes;
    private final S3Storage storage;
    private final boolean hasBucket;

    public WebtoonPurge(WebtoonPurgeRepository rows, RunLikeRepository likes, S3Storage storage, BucketPresence bucket) {
        this.rows = rows;
        this.likes = likes;
        this.storage = storage;
        this.hasBucket = bucket.exists();
    }

    @Override
    public String domain() {
        return "webtoon";
    }

    @Override
    public int purge(Long userId) {
        List<String> runIds = runIdsOf(userId);

        // 1. 그림 먼저. 키는 행에만 있다.
        List<String> keys = new ArrayList<>(rows.characterKeys(userId));
        if (!runIds.isEmpty()) {
            keys.addAll(rows.pageKeys(runIds));
            keys.addAll(rows.bakedKeys(runIds));
        }
        if (hasBucket && !keys.isEmpty()) {
            storage.delete(keys);
        }

        // 2. 작품에 딸린 것.
        int n = 0;
        if (!runIds.isEmpty()) {
            n += rows.deletePages(runIds);
            n += rows.deleteBakedPages(runIds);
            n += rows.deleteOverlays(runIds);
            n += rows.deleteRegens(runIds);
            n += rows.deleteStories(runIds);
            n += likes.deleteByRunIds(runIds);   // 남이 내 작품에 한 찜도 작품과 함께 사라진다(#247)
            n += rows.deleteUsage(runIds);
        }

        // 3. 작품·기록·사람에 직접 달린 것.
        n += rows.deleteWorks(userId);
        n += rows.deleteJobs(userId);
        n += rows.deleteCharacters(userId);
        n += rows.deleteBrowserLinks(userId);
        n += rows.deleteNotifySettings(userId);
        return n;
    }

    /** 두 표에 흩어진 작품 번호를 합친다. 순서를 유지해 로그가 읽히게 둔다. */
    private List<String> runIdsOf(Long userId) {
        Set<String> all = new LinkedHashSet<>(rows.jobRunIdsOf(userId));
        all.addAll(rows.workRunIdsOf(userId));
        return List.copyOf(all);
    }
}
