package com.lore.common.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;
import software.amazon.awssdk.services.cloudfront.model.Paths;

import java.util.List;
import java.util.UUID;

/**
 * CloudFront 무효화. 배포 ID({@code app.cdn.distribution-id}, env {@code CLOUDFRONT_DISTRIBUTION_ID})가 비어 있으면
 * 아무것도 안 하고 로그만 남긴다 — dev 는 CloudFront 가 없다(직결, deploy.yml 의 cf_dist 와 같은 규칙).
 *
 * ★ 서버 IAM 역할에 {@code cloudfront:CreateInvalidation} 권한이 있어야 한다. 없으면 경고 로그만 남는다(던지지 않는다).
 */
@Component
public class CloudFrontInvalidator implements CdnInvalidator {

    private static final Logger log = LoggerFactory.getLogger(CloudFrontInvalidator.class);

    private final String distributionId;
    private volatile CloudFrontClient client;

    public CloudFrontInvalidator(@Value("${app.cdn.distribution-id:}") String distributionId) {
        this.distributionId = distributionId == null ? "" : distributionId.strip();
    }

    @Override
    public void invalidate(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        if (distributionId.isEmpty()) {
            log.info("CDN 무효화 생략(배포 ID 없음 — app.cdn.distribution-id) — {}", paths);
            return;
        }
        try {
            String id = client().createInvalidation(CreateInvalidationRequest.builder()
                    .distributionId(distributionId)
                    .invalidationBatch(InvalidationBatch.builder()
                            .callerReference(UUID.randomUUID().toString())
                            .paths(Paths.builder().quantity(paths.size()).items(paths).build())
                            .build())
                    .build()).invalidation().id();
            log.info("CDN 무효화 요청 — {} ({})", paths, id);
        } catch (RuntimeException e) {
            log.warn("CDN 무효화 실패(반영은 그대로) — {} : {}", paths, String.valueOf(e));
        }
    }

    private CloudFrontClient client() {
        CloudFrontClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = CloudFrontClient.builder().region(Region.AWS_GLOBAL).build();
                }
                c = client;
            }
        }
        return c;
    }
}
