package com.lore.common.s3;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.transaction.annotation.Transactional;
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
import java.time.Instant;
import java.util.Set;
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

    /**
     * 올릴 수 있는 도메인 폴더. 여기 없는 값은 거부한다.
     *
     * ★ 검증이 필요한 이유 — 이 값이 그대로 S3 키가 되므로, 오타 하나(`zzl`)면 아무도 모르는
     *   경로에 파일이 쌓이고 나중에 찾을 수도 지울 수도 없다. 게다가 캐시 정책과 접근 권한을
     *   이 경로를 기준으로 나눌 것이라, 규칙 밖의 경로가 생기는 순간 그 규칙이 무의미해진다.
     *
     * 새 도메인이 생기면 여기에 추가한다(팀 공용이므로 변경 시 공유 필요).
     */
    static final Set<String> ALLOWED_DOMAINS = Set.of("zzal", "webtoon", "trailer", "common");

    private final S3Presigner presigner;
    private final UploadTicketRepository ticketRepository;
    private final String bucket;
    private final Duration expiry;

    public S3Service(
            S3Presigner presigner,
            UploadTicketRepository ticketRepository,
            @Value("${app.s3.content-bucket}") String bucket,
            @Value("${app.s3.presign-expiry-minutes}") long expiryMinutes) {
        this.presigner = presigner;
        this.ticketRepository = ticketRepository;
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
     * 발급 기록을 {@link UploadTicket} 에 남긴다 — 업로드는 브라우저가 S3 로 직접 하므로
     * 서버가 그 순간을 못 본다. 나중에 "이 키로 만들어 주세요" 가 왔을 때 대조할 근거가 필요하다.
     *
     * @param userId      발급받는 사람
     * @param domain      키 경로 구분용 폴더(zzal/webtoon/trailer 등)
     * @param contentType 업로드할 파일의 MIME 타입(image/png 등)
     * @return 발급된 S3 key 와 presigned URL
     */
    @Transactional
    public PresignedUpload createUploadUrl(Long userId, String domain, String contentType) {
        // AWS SDK 가 내는 "Bucket cannot be empty" 는 어디를 고쳐야 할지 안 알려준다.
        // 설정이 원인일 때는 설정 이름을 그대로 말해준다.
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException(
                    "CONTENT_S3_BUCKET 환경변수가 설정되지 않아 업로드 URL 을 발급할 수 없습니다.");
        }

        if (!ALLOWED_DOMAINS.contains(domain)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "지원하지 않는 도메인입니다: %s (가능한 값: %s)".formatted(domain, ALLOWED_DOMAINS));
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

        ticketRepository.save(UploadTicket.issue(userId, key, domain, contentType, Instant.now()));

        return new PresignedUpload(key, presigned.url().toString());
    }

    /**
     * 키가 이 사람이 발급받은 것이고 아직 안 쓴 것인지 확인하고, 썼다고 표시한다.
     *
     * 도메인(zzal 등)이 "이 키로 만들어 주세요" 를 받으면 반드시 이걸 먼저 통과해야 한다.
     * 통과 못 하는 경우 셋 — 표에 없는 키 · 남의 키 · 이미 쓴 키.
     */
    @Transactional
    public void consume(Long userId, String s3Key, Instant now) {
        UploadTicket ticket = ticketRepository.findByS3Key(s3Key)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_UPLOAD_KEY));
        if (!ticket.isOwnedBy(userId)) {
            // 남의 키를 넣은 것이지만, 그렇다고 알려주지 않는다 — 알려주면 남의 키가
            // 존재한다는 사실 자체가 확인된다. 없는 키와 같은 응답을 준다.
            throw new BusinessException(ErrorCode.INVALID_UPLOAD_KEY);
        }
        if (ticket.isUsed()) {
            throw new BusinessException(ErrorCode.UPLOAD_KEY_ALREADY_USED);
        }
        ticket.markUsed(now);
    }

    /**
     * 발급 결과 — 프론트는 url 로 PUT 한 뒤 key 를 자기 도메인 API 에 저장한다.
     *
     * 화면에 띄울 때는 S3 주소가 아니라 `/{key}` 상대경로로 부른다(CloudFront 가 받아준다).
     * S3 직접 주소를 DB 에 넣으면 계정을 옮길 때 쌓인 URL 이 전부 무효가 된다.
     */
    public record PresignedUpload(String key, String url) {}
}
