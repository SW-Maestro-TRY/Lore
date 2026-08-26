package com.lore.common.s3;

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

    @Mock
    S3Presigner presigner;

    @Test
    @DisplayName("업로드 키는 images/ 로 시작한다 — CloudFront 의 /images/* 규칙과 맞추기 위해")
    void keyStartsWithImagesPrefix() {
        S3Service service = serviceReturningUrl("bucket-a");

        String key = service.createUploadUrl("zzal", "image/png").key();

        assertThat(key).startsWith("images/");
    }

    @Test
    @DisplayName("업로드 키는 images/{도메인}/{UUID} 형식이다")
    void keyFollowsExpectedShape() {
        S3Service service = serviceReturningUrl("bucket-a");

        String key = service.createUploadUrl("story", "image/png").key();

        assertThat(key).matches("^images/story/[0-9a-f-]{36}$");
    }

    @Test
    @DisplayName("도메인이 달라도 접두사는 그대로다")
    void prefixIsIndependentOfDomain() {
        S3Service service = serviceReturningUrl("bucket-a");

        assertThat(service.createUploadUrl("zzal", "image/png").key()).startsWith("images/zzal/");
        assertThat(service.createUploadUrl("trailer", "image/png").key()).startsWith("images/trailer/");
    }

    @Test
    @DisplayName("같은 도메인으로 두 번 발급해도 키가 겹치지 않는다")
    void keysDoNotCollide() {
        S3Service service = serviceReturningUrl("bucket-a");

        String first = service.createUploadUrl("zzal", "image/png").key();
        String second = service.createUploadUrl("zzal", "image/png").key();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("버킷 설정이 비면 설정 이름을 알려주며 실패한다 — SDK 의 모호한 메시지 대신")
    void failsLoudlyWhenBucketMissing() {
        S3Service service = new S3Service(presigner, "", 10);

        assertThatThrownBy(() -> service.createUploadUrl("zzal", "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTENT_S3_BUCKET");
    }

    private S3Service serviceReturningUrl(String bucket) {
        PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
        when(presigner.presignPutObject(any(software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest.class)))
                .thenReturn(presigned);
        when(presigned.url()).thenReturn(urlOf("https://example.s3.ap-northeast-2.amazonaws.com/x"));
        return new S3Service(presigner, bucket, 10);
    }

    private static java.net.URL urlOf(String s) {
        try {
            return URI.create(s).toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
