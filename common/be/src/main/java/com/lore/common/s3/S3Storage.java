package com.lore.common.s3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /** S3 배치 삭제가 한 요청에 받는 최대 개수. */
    private static final int BATCH = 1000;

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

    /**
     * <b>지운다.</b> 보존기간이 지난 것을 파기할 때 쓴다.
     *
     * ★ 한 번에 1000개씩 끊는다 — S3 의 배치 삭제가 한 요청에 1000개까지만
     *   받는다. 그보다 많이 보내면 요청 전체가 거부되므로, 지울 것이 많을수록
     *   조용히 아무것도 안 지워지는 쪽으로 틀어진다.
     *
     * ★ 없는 키를 보내도 성공으로 친다(S3 가 그렇게 답한다). 파기는 여러 번
     *   돌 수 있어야 하고, 앞선 회차가 중간에 끊겨 절반만 지워졌더라도 다시
     *   돌렸을 때 나머지가 지워져야 한다.
     *
     * @return 실제로 요청을 보낸 키 수(빈 키는 빼고 센다)
     */
    public int delete(List<String> keys) {
        List<String> real = new ArrayList<>();
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                real.add(k);
            }
        }
        for (int from = 0; from < real.size(); from += BATCH) {
            List<String> chunk = real.subList(from, Math.min(from + BATCH, real.size()));
            client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder()
                            .objects(chunk.stream()
                                    .map(k -> ObjectIdentifier.builder().key(k).build())
                                    .toList())
                            .build())
                    .build());
        }
        return real.size();
    }
}
