package com.lore.common.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 콘텐츠 이미지 업로드용 presigned PUT URL 발급.
 *
 * 팀은 이 서비스를 import 해서 발급 URL 만 받으면 된다 — AWS SDK 를 몰라도 됨.
 * 실제 업로드는 프론트가 이 URL 로 S3 에 직접 PUT 하므로 서버는 파일을 안 만진다.
 */
@Service
public class S3Service {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration expiry;

    public S3Service(
            S3Presigner presigner,
            @Value("${app.s3.content-bucket}") String bucket,
            @Value("${app.s3.presign-expiry-minutes}") long expiryMinutes) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.expiry = Duration.ofMinutes(expiryMinutes);
    }

    /**
     * 업로드용 presigned PUT URL 을 발급한다.
     *
     * @param domain      키 경로 구분용 폴더(comic/story/trailer 등)
     * @param contentType 업로드할 파일의 MIME 타입(image/png 등)
     * @return 발급된 S3 key 와 presigned URL
     */
    public PresignedUpload createUploadUrl(String domain, String contentType) {
        // 키 = 도메인 폴더 + 랜덤 UUID. 파일명 충돌·추측을 막는다.
        String key = "%s/%s".formatted(domain, UUID.randomUUID());

        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);

        return new PresignedUpload(key, presigned.url().toString());
    }

    /** 발급 결과 — 프론트는 url 로 PUT 한 뒤 key 를 자기 도메인 API 에 저장한다. */
    public record PresignedUpload(String key, String url) {}
}
