package com.lore.zzal.archive;

import java.nio.file.Path;

/**
 * 보관 파일을 <b>보관 버킷</b>에 올리는 문.
 *
 * <h3>★ 왜 {@code S3Storage} 를 직접 주입받지 않고 문을 하나 더 두나</h3>
 * {@code S3Storage} 는 <b>그림 버킷</b>({@code app.s3.content-bucket})에 묶여 만들어진 빈이다.
 * 그걸 그대로 주입받으면 행동 기록이 CloudFront 가 공개로 내보내는 버킷으로 간다.
 * 그래서 보관용으로는 <b>버킷이 다른 {@code S3Storage} 를 따로 만들어</b> 이 문 뒤에 둔다
 * ({@link EventArchiveConfig}). 공용 코드는 한 글자도 고치지 않는다.
 *
 * <p>문을 따로 두면 시험도 쉬워진다 — 시험은 이미 있는 메모리 저장소를 이 문 뒤에 끼운다.
 */
public interface ArchiveStorage {

    /** 올린다. 같은 key 로 다시 올리면 덮어쓴다(그게 같은 구간을 다시 올려도 안전한 이유다). */
    void upload(String key, Path file);
}
