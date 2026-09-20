package com.lore.common.s3;

import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 업로드 키 규칙 테스트.
 *
 * 이 테스트가 지키는 것은 "코드가 안 터지는가"가 아니라
 * **CloudFront 와 맺은 약속을 코드가 계속 지키는가**이다.
 *
 * 2026-08-25 에 키가 `comic/<uuid>` 로 나가는 바람에, 업로드는 200 인데
 * 화면에서 이미지를 부르면 403 이 나는 상태로 며칠을 보냈다.
 * 배포도 성공하고 서버도 멀쩡히 떠 있어서 아무도 몰랐다.
 * 누군가 이 규칙을 되돌리면 여기서 먼저 걸리게 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class S3ServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    S3Presigner presigner;

    /** 발급 기록 저장은 이 테스트의 관심사가 아니다 — 키 규칙만 본다. */
    @Mock
    UploadTicketRepository ticketRepository;

    @Test
    @DisplayName("업로드 키는 images/ 로 시작한다 — CloudFront 의 /images/* 규칙과 맞추기 위해")
    void keyStartsWithImagesPrefix() {
        S3Service service = serviceReturningUrl("bucket-a");

        String key = service.createUploadUrl(USER_ID, "zzal", "image/png").key();

        assertThat(key).startsWith("images/");
    }

    @Test
    @DisplayName("업로드 키는 images/{도메인}/{UUID} 형식이다")
    void keyFollowsExpectedShape() {
        S3Service service = serviceReturningUrl("bucket-a");

        String key = service.createUploadUrl(USER_ID, "webtoon", "image/png").key();

        assertThat(key).matches("^images/webtoon/[0-9a-f-]{36}$");
    }

    @Test
    @DisplayName("도메인이 달라도 접두사는 그대로다")
    void prefixIsIndependentOfDomain() {
        S3Service service = serviceReturningUrl("bucket-a");

        assertThat(service.createUploadUrl(USER_ID, "zzal", "image/png").key()).startsWith("images/zzal/");
        assertThat(service.createUploadUrl(USER_ID, "trailer", "image/png").key()).startsWith("images/trailer/");
    }

    @Test
    @DisplayName("같은 도메인으로 두 번 발급해도 키가 겹치지 않는다")
    void keysDoNotCollide() {
        S3Service service = serviceReturningUrl("bucket-a");

        String first = service.createUploadUrl(USER_ID, "zzal", "image/png").key();
        String second = service.createUploadUrl(USER_ID, "zzal", "image/png").key();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("버킷 설정이 비면 설정 이름을 알려주며 실패한다 — SDK 의 모호한 메시지 대신")
    void failsLoudlyWhenBucketMissing() {
        S3Service service = new S3Service(presigner, ticketRepository, "", 10);

        assertThatThrownBy(() -> service.createUploadUrl(USER_ID, "zzal", "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTENT_S3_BUCKET");
    }

    private S3Service serviceReturningUrl(String bucket) {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presigned);
        when(presigned.url()).thenReturn(urlOf("https://example.s3.ap-northeast-2.amazonaws.com/x"));
        return new S3Service(presigner, ticketRepository, bucket, 10);
    }

    private static java.net.URL urlOf(String s) {
        try {
            return URI.create(s).toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("허용 목록에 없는 도메인은 거부한다 — 오타로 엉뚱한 경로에 파일이 쌓이는 것을 막는다")
    void rejectsUnknownDomain() {
        S3Service service = serviceReturningUrl("bucket-a");

        assertThatThrownBy(() -> service.createUploadUrl(USER_ID, "zzl", "image/png"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("zzl");
    }

    /*
     * 게스트(비로그인) presign — 2026-09-17, 게스트가 사진을 올릴 때 base64 로
     * 요청 본문에 실어 보내다가 CloudFront 앞단 WAF(SizeRestrictions_BODY)에
     * 막히던 것을 고치며 추가했다. 계정이 없으니 GuestGate 의 IP 해시로
     * 티켓을 묶는데, 이 테스트는 그 묶임이 로그인 사람의 것과 똑같이
     * "발급받은 사람만 그 키를 쓸 수 있다"를 지키는지 본다.
     */

    @Test
    @DisplayName("게스트 발급 키도 images/{도메인}/{UUID} 형식이다")
    void guestKeyFollowsExpectedShape() {
        S3Service service = serviceReturningUrl("bucket-a");

        String key = service.createUploadUrlForGuest("guest-abc", "webtoon", "image/png").key();

        assertThat(key).matches("^images/webtoon/[0-9a-f-]{36}$");
    }

    @Test
    @DisplayName("게스트 열쇠가 없으면 발급을 거부한다")
    void rejectsGuestUploadWithoutKey() {
        S3Service service = serviceReturningUrl("bucket-a");

        assertThatThrownBy(() -> service.createUploadUrlForGuest("", "webtoon", "image/png"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("게스트는 자기가 받은 티켓을 쓸 수 있다")
    void guestCanConsumeOwnTicket() {
        S3Service service = new S3Service(presigner, ticketRepository, "bucket-a", 10);
        UploadTicket ticket = UploadTicket.issueForGuest("guest-abc", "images/webtoon/x", "webtoon",
                "image/png", Instant.now());
        when(ticketRepository.findByS3Key("images/webtoon/x")).thenReturn(Optional.of(ticket));

        service.consumeGuest("guest-abc", "images/webtoon/x", Instant.now());

        assertThat(ticket.isUsed()).isTrue();
    }

    @Test
    @DisplayName("다른 게스트의 키는 못 쓴다 — IP 해시가 달라도 없는 키와 같은 오류를 준다")
    void rejectsAnotherGuestsKey() {
        S3Service service = new S3Service(presigner, ticketRepository, "bucket-a", 10);
        UploadTicket ticket = UploadTicket.issueForGuest("guest-abc", "images/webtoon/x", "webtoon",
                "image/png", Instant.now());
        when(ticketRepository.findByS3Key("images/webtoon/x")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.consumeGuest("guest-other", "images/webtoon/x", Instant.now()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("로그인 사람 티켓은 게스트 경로로 못 쓴다 — 계정 티켓과 게스트 티켓은 서로 남의 것이다")
    void loggedInUserTicketIsNotConsumableAsGuest() {
        S3Service service = new S3Service(presigner, ticketRepository, "bucket-a", 10);
        UploadTicket ticket = UploadTicket.issue(USER_ID, "images/webtoon/x", "webtoon",
                "image/png", Instant.now());
        when(ticketRepository.findByS3Key("images/webtoon/x")).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.consumeGuest("guest-abc", "images/webtoon/x", Instant.now()))
                .isInstanceOf(BusinessException.class);
    }
}
