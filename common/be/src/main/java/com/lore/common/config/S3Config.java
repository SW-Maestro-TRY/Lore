package com.lore.common.config;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigned URL 발급기(S3Presigner) 빈.
 *
 * 자격증명은 DefaultCredentialsProvider 가 자동 탐색한다 — 코드에 키를 넣지 않는다:
 *   - EC2/운영: 인스턴스에 붙은 IAM 역할(lore-ec2-role)에서 임시 자격증명
 *   - 로컬 개발: 환경변수(AWS_ACCESS_KEY_ID …) 또는 ~/.aws/credentials
 *   - 개발 도커(MinIO): 환경변수(AWS_ACCESS_KEY_ID/…)로 MinIO 루트 키를 그대로 넘긴다
 *
 * 사용자 업로드는 브라우저 → S3 직접(PUT)이라 서버가 파일 바이트를 만지지 않는다.
 * 다만 **서버가 만들어 낸 것**(캐릭터 시트·격자·움짤)은 서버가 직접 올려야 하므로
 * S3Client 도 함께 둔다(2026-09-02).
 *
 * ★ app.s3.endpoint (기본값 빈 문자열)
 *   비어 있으면 지금까지처럼 실제 AWS S3 를 쓴다(동작 변화 없음·하위호환).
 *   값이 있으면 그 주소를 S3 로 쓴다 — 개발 도커의 MinIO(S3 호환) 대체용.
 *   MinIO 는 가상호스트 방식(bucket.host…)을 못 받으므로 경로 방식(host/bucket/key)을
 *   강제한다. 이 레포의 AWS SDK v2(2.28.16) 에서 S3Presigner.Builder 에는
 *   forcePathStyle(...) 이 없고 serviceConfiguration(pathStyleAccessEnabled) 만 있어,
 *   S3Client·S3Presigner 양쪽에 같은 방식(serviceConfiguration)으로 건다.
 */
@Configuration
public class S3Config {

    @Value("${app.s3.region}")
    private String region;

    /** 선택적 S3 엔드포인트 재지정. 비어 있으면 실제 AWS S3(기존 동작), 값이 있으면 MinIO 등. */
    @Value("${app.s3.endpoint:}")
    private String endpoint;

    /** 서버가 직접 올리고 받을 때 쓴다 — 생성 결과물(시트·격자·움짤). */
    @Bean
    public S3Client s3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (StringUtils.hasText(endpoint)) {
            builder.endpointOverride(URI.create(endpoint))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build());
        }
        return builder.build();
    }
}
