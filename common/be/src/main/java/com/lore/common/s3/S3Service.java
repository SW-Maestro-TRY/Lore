package com.lore.common.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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

    private static final Logger log = LoggerFactory.getLogger(S3Service.class);

    /** CloudFront 의 `/images/*` 동작과 맞춰야 하는 키 접두사. 바꾸려면 CloudFront 도 같이 바꾼다. */
    static final String KEY_PREFIX = "images";

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

        // 로컬 개발은 이 값 없이도 서버를 띄울 수 있어야 하므로 부팅을 막지는 않는다.
        // 대신 시작 시점에 크게 남긴다 — 값이 비면 업로드가 통째로 죽는데,
        // 그 사실이 실제 호출 때까지 안 드러나는 것이 2026-08-25 사고의 원인이었다.
        if (!StringUtils.hasText(bucket)) {
            log.warn("CONTENT_S3_BUCKET 이 비어 있어 이미지 업로드가 동작하지 않습니다. "
                    + "서버라면 /etc/lore/lore.env 를 확인하세요.");
        }
    }

    /**
     * 업로드용 presigned PUT URL 을 발급한다.
     *
     * @param domain      키 경로 구분용 폴더(comic/story/trailer 등)
     * @param contentType 업로드할 파일의 MIME 타입(image/png 등)
     * @return 발급된 S3 key 와 presigned URL
     */
    public PresignedUpload createUploadUrl(String domain, String contentType) {
        // AWS SDK 가 내는 "Bucket cannot be empty" 는 어디를 고쳐야 할지 안 알려준다.
        // 설정이 원인일 때는 설정 이름을 그대로 말해준다.
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException(
                    "CONTENT_S3_BUCKET 환경변수가 설정되지 않아 업로드 URL 을 발급할 수 없습니다.");
        }

        // 키 = images/ + 도메인 폴더 + 랜덤 UUID. UUID 는 파일명 충돌·추측을 막는다.
        //
        // ★ images/ 접두사는 장식이 아니라 CloudFront 와 맺은 계약이다.
        //   CloudFront 는 `/images/*` 요청만 S3 로 보내고, 그때 경로를 그대로 S3 키로 쓴다.
        //   따라서 키가 `images/` 로 시작하지 않으면 올리기는 되지만 읽을 때 403 이 난다.
        //   (2026-08-25 실제 사고: 키가 `comic/<uuid>` 라 CloudFront 가 `images/comic/<uuid>`
        //    를 찾다가 못 찾았다. 규칙을 바꾸려면 CloudFront 동작도 같이 바꿔야 한다.)
        String key = "%s/%s/%s".formatted(KEY_PREFIX, domain, UUID.randomUUID());

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

    /**
     * 발급 결과 — 프론트는 url 로 PUT 한 뒤 key 를 자기 도메인 API 에 저장한다.
     *
     * 화면에 띄울 때는 S3 주소가 아니라 `/{key}` 상대경로로 부른다(CloudFront 가 받아준다).
     * S3 직접 주소를 DB 에 넣으면 계정을 옮길 때 쌓인 URL 이 전부 무효가 된다.
     */
    public record PresignedUpload(String key, String url) {}
}
