package com.lore.common.retention;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 그림을 올려 둔 곳이 실제로 있는가.
 *
 * <h2>왜 이걸 묻는가 — 로컬에서는 안 지운다</h2>
 *
 * 버킷이 빈 값이면({@code CONTENT_S3_BUCKET} 미설정) <b>올라간 곳이 없으므로
 * 지우는 순간 영영 사라진다.</b> 로컬은 만든 것을 들여다보며 작업하는 자리라
 * 원본이 그대로 있어야 한다. {@code RunFiles} 가 같은 가드를 쓴다.
 *
 * 값이 비어 있을 때 {@code S3Storage} 를 부르면 버킷 이름 없이 요청이 나가
 * 엉뚱한 곳을 건드리거나 예외로 터진다. 부르기 전에 막는 편이 낫다.
 */
@Component
public class BucketPresence {

    private final boolean exists;

    public BucketPresence(@Value("${app.s3.content-bucket:}") String bucket) {
        this.exists = bucket != null && !bucket.isBlank();
    }

    public boolean exists() {
        return exists;
    }
}
