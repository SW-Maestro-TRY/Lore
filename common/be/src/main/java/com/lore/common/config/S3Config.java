package com.lore.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 presigned URL 발급기(S3Presigner) 빈.
 *
 * 자격증명은 DefaultCredentialsProvider 가 자동 탐색한다 — 코드에 키를 넣지 않는다:
 *   - EC2/운영: 인스턴스에 붙은 IAM 역할(lore-ec2-role)에서 임시 자격증명
 *   - 로컬 개발: 환경변수(AWS_ACCESS_KEY_ID …) 또는 ~/.aws/credentials
 *
 * presigned 발급만 백엔드가 하고, 실제 업로드는 브라우저 → S3 직접(PUT)이라
 * 서버는 파일 바이트를 만지지 않는다(t3.micro 보호).
 */
@Configuration
public class S3Config {

    @Value("${app.s3.region}")
    private String region;

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
