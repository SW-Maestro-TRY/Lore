package com.lore.common.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 서버가 직접 파일을 올리고 받는 곳.
 *
 * ★ presign(S3Service)과 역할이 다르다.
 *     S3Service   사용자가 올릴 주소를 발급한다. 파일은 브라우저 → S3 직접
 *     S3Storage   **서버가 만들어 낸 것**을 올리고, 처리하려고 받는다
 *
 *   생성 결과물(캐릭터 시트·격자·움짤 8종)은 서버가 만든 것이라 서버가 올려야 한다.
 *
 * ★ 캐시는 1년으로 박는다. 펫의 그림은 한 번 만들어지면 바뀌지 않고, 다시 구우면
 *   새 펫이거나 새 파일 이름이 된다. 정적 에셋과 같은 규칙이다.
 */
@Component
public class S3Storage {

    private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final S3Client client;
    private final String bucket;

    public S3Storage(S3Client client, @Value("${app.s3.content-bucket}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * S3 에서 받아 이 경로에 쓴다.
     *
     * ★ {@code to} 가 이미 있어도(예: {@code Files.createTempFile} 로 미리
     *   만들어 둔 자리) 그대로 덮어쓴다. SDK 의 {@code getObject(request, path)}
     *   는 파일이 이미 있으면 쓰기를 거부하는데, 그때 예외가 "Unable to
     *   unmarshall response" 로만 나와서 원인(파일이 이미 있어서)을
     *   알아채기 어렵다 — 실제로 한 편 내려받기가 이 때문에 장마다 전부
     *   실패했다. 먼저 지워 두면 SDK 가 새로 만들어 쓴다.
     */
    public void download(String key, Path to) {
        try {
            Files.deleteIfExists(to);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), to);
    }

    public void upload(String key, Path from, String contentType) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl(CACHE_CONTROL)
                        .build(),
                RequestBody.fromFile(from));
    }
}
