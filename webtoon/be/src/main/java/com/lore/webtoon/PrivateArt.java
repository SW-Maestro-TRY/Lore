package com.lore.webtoon;

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
 *
 * <h2>어디에 두느냐로 가른다</h2>
 *
 * 실측으로 확인한 것(2026-09-06):
 *
 * <pre>
 *   S3 주소로 직접                    403   버킷이 밖에서 완전히 막혀 있다
 *   CloudFront /images/...            200   여기만 내준다
 *   CloudFront /private/...           404   안 내준다
 * </pre>
 *
 * 그래서 <b>공개는 {@code images/} 에, 비공개는 {@code private/} 에</b> 둔다.
 * 공개 작품은 지금처럼 CloudFront 가 바로 내주므로 서버를 안 거치고(캐시도
 * 그대로 산다), 비공개는 CloudFront 로는 열 길이 아예 없다.
 *
 * <h2>비공개는 잠깐만 열리는 주소로 준다</h2>
 *
 * 주인이 맞는지 확인한 뒤 <b>시간이 지나면 닫히는 주소</b>를 만들어 준다.
 * 서버가 그림을 통째로 받아 다시 내보낼 수도 있지만, 그러면 한 장에 수백 KB
 * 씩 서버를 두 번 지나간다 — 읽는 사람이 늘수록 그게 그대로 비용이 된다.
 *
 * 잠깐 열린다는 것은 <b>그 사이에 주소를 넘기면 남도 볼 수 있다</b>는 뜻이다.
 * 링크를 넘기는 것까지 막으려면 그림마다 로그인을 확인해야 하는데, 그건
 * CloudFront 를 못 쓴다는 뜻이라 공개 작품까지 느려진다. 여기서는 <b>주소가
 * 새어도 곧 닫히는</b> 데까지 한다.
 *
 * <h2>공개/비공개를 바꾸면 옮긴다</h2>
 *
 * 자리로 가르므로 바꾸면 실제로 옮겨야 한다. 옮기는 값이 싸지 않지만(복사 후
 * 삭제), 사람이 자주 누르는 단추가 아니고 무엇보다 <b>안 옮기면 비공개로
 * 내려도 계속 열린다.</b>
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

    static boolean isPrivate(String key) {
        return key != null && key.startsWith(PRIVATE_PREFIX);
    }

    /** 같은 파일 이름을 유지한 채 자리만 바꾼 키. */
    static String moved(String key, boolean toPublic) {
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
