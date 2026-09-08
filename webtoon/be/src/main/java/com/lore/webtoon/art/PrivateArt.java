package com.lore.webtoon.art;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.UUID;

/**
 * 나만 보는 그림을 나만 보게 한다.
 */
@Service
public class PrivateArt {

    private static final Logger log = LoggerFactory.getLogger(PrivateArt.class);

    /** CloudFront 가 내주는 자리. */
    static final String PUBLIC_PREFIX = "images/webtoon/";

    /** CloudFront 가 안 내주는 자리. */
    static final String PRIVATE_PREFIX = "private/webtoon/";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    public PrivateArt(S3Client s3, S3Presigner presigner,
                      @Value("${app.s3.content-bucket:}") String bucket,
                      @Value("${app.s3.presign-expiry-minutes:10}") int minutes) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket == null ? "" : bucket;
        this.ttl = Duration.ofMinutes(Math.max(1, minutes));
    }

    public static boolean isPrivate(String key) {
        return key != null && key.startsWith(PRIVATE_PREFIX);
    }

    /** 같은 파일 이름을 유지한 채 자리만 바꾼 키. 위 isPrivate 과 같은 성격이라 같이 연다. */
    public static String moved(String key, boolean toPublic) {
        if (key == null) {
            return null;
        }
        String name = key.substring(key.lastIndexOf('/') + 1);
        return (toPublic ? PUBLIC_PREFIX : PRIVATE_PREFIX) + name;
    }

    /** 버킷을 안 정해 두면 S3 를 아예 안 쓴다(로컬). */
    public boolean ready() {
        return !bucket.isEmpty();
    }

    /**
     * 그림 한 장을 올린다. -> 올린 키. 못 올리면 {@code null}
     *
     * <h2>왜 자바가 올리나</h2>
     *
     * 웹툰 페이지는 파이썬이 올린다({@code s3_upload.py}) — 원본을 줄이고 여러
     * 폭으로 만들어 한꺼번에 올리는 일이라 그 코드가 이미 거기 있고, 자바로
     * 다시 쓰면 두 벌이 된다.
     *
     * 캐릭터 그림은 <b>한 장이고 줄일 것도 없다.</b> 그 한 장을 올리자고
     * 파이썬을 한 번 더 부르면, 값 계산과 주인 확인이 이미 끝난 자리에서
     * 프로세스를 하나 더 띄우는 셈이 된다. 여기서는 자바가 그대로 올린다.
     *
     * <h2>자리</h2>
     *
     * 캐릭터 그림은 만든 사람만 본다 — 그래서 <b>안 열리는 자리</b>에 둔다.
     * 화면은 잠깐 열리는 주소({@link #temporaryUrl})로 받아 간다. 기본 제공
     * 캐릭터처럼 누구나 봐도 되는 것은 {@link #move} 로 옮긴다.
     */
    public String upload(byte[] body, String contentType, boolean isPublic) {
        if (!ready() || body == null || body.length == 0) {
            return null;
        }
        String key = (isPublic ? PUBLIC_PREFIX : PRIVATE_PREFIX)
                + "char/" + UUID.randomUUID().toString().replace("-", "")
                + extensionOf(contentType);
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket).key(key)
                            .contentType(contentType == null ? "image/png" : contentType)
                            // 키에 임의의 이름이 들어가 같은 주소가 다른 그림이 될 일이
                            // 없으므로 오래 담아 둬도 된다(s3_upload.py 와 같은 규칙).
                            .cacheControl("public, max-age=31536000, immutable")
                            .build(),
                    RequestBody.fromBytes(body));
            return key;
        } catch (RuntimeException e) {
            log.error("캐릭터 그림을 못 올렸습니다 (key={})", key, e);
            return null;
        }
    }

    private static String extensionOf(String contentType) {
        if (contentType == null) {
            return ".png";
        }
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    /** 올려 둔 그림을 도로 읽는다. 없거나 못 읽으면 {@code null}. */
    public byte[] read(String key) {
        if (!ready() || key == null || key.isBlank()) {
            return null;
        }
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket).key(key).build()).asByteArray();
        } catch (RuntimeException e) {
            log.error("그림을 못 읽었습니다 (key={})", key, e);
            return null;
        }
    }

    /**
     * 잠깐 열리는 주소. 주인 확인은 <b>부르는 쪽이</b> 먼저 한다 — 여기까지
     * 오면 이미 봐도 되는 사람이다.
     */
    public String temporaryUrl(String key) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build()).url().toString();
    }

    /**
     * 자리를 옮긴다(복사 후 삭제). -> 옮긴 뒤의 키. 못 옮기면 {@code null}
     *
     * <b>지우는 데 실패해도 옮긴 것으로 친다.</b> 새 자리에 이미 있으므로 화면은
     * 맞게 돌고, 남은 것은 쓰레기일 뿐이다. 반대로 복사가 실패하면 옮기지
     * 않는다 — 옛것을 지운 뒤 실패하면 그림이 아예 사라진다.
     */
    public String move(String key, boolean toPublic) {
        String to = moved(key, toPublic);
        if (to == null || to.equals(key)) {
            return key;
        }
        try {
            s3.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(key)
                    .destinationBucket(bucket).destinationKey(to)
                    .build());
        } catch (RuntimeException e) {
            log.error("그림을 옮기지 못했습니다 ({} -> {})", key, to, e);
            return null;
        }
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            // 옮긴 것은 맞다. 옛 자리에 남은 것은 쓰레기지만, 공개 -> 비공개일
            // 때는 그 쓰레기가 **계속 열린다** — 그래서 조용히 넘기지 않는다.
            log.error("옛 자리의 그림을 못 지웠습니다 — 공개 자리에 남아 있으면 계속 열립니다 ({})",
                    key, e);
        }
        return to;
    }
}
