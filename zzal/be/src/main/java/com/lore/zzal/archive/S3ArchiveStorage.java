package com.lore.zzal.archive;

import com.lore.common.s3.S3Storage;

import java.nio.file.Path;

/**
 * {@link ArchiveStorage} 의 실제 구현 — 공용 {@code S3Storage} 에 그대로 넘긴다.
 *
 * ★ 여기 버킷 이름이 없다. 버킷은 넘겨받은 {@code S3Storage} 안에 들어 있다
 *   ({@link EventArchiveConfig} 가 보관 버킷으로 하나 만들어 준다). 이름을 두 곳에 적으면
 *   한쪽만 고쳐졌을 때 조용히 어긋난다.
 */
public class S3ArchiveStorage implements ArchiveStorage {

    /** gzip 파일이다. 나중에 콘솔에서 받을 때 브라우저가 풀어 버리지 않게 정확히 적는다. */
    private static final String CONTENT_TYPE = "application/gzip";

    private final S3Storage s3;

    public S3ArchiveStorage(S3Storage s3) {
        this.s3 = s3;
    }

    @Override
    public void upload(String key, Path file) {
        s3.upload(key, file, CONTENT_TYPE);
    }
}
