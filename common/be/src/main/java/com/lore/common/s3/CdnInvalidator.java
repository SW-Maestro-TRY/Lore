package com.lore.common.s3;

import java.util.List;

/**
 * CDN(CloudFront) 캐시 무효화. 같은 S3 키를 덮어쓴 뒤 부른다 — 그러지 않으면 CDN 이 옛 그림을 계속 준다.
 *
 * ★ 경로는 CloudFront 경로({@code /images/...}, 끝에 {@code *} 허용)다. S3 키 앞에 {@code /} 를 붙인 모양.
 * ★ 실패해도 던지지 않는다 — 무효화는 덮어쓰기의 뒷정리라, 이것 때문에 이미 끝난 반영을 실패로 만들지 않는다.
 */
public interface CdnInvalidator {

    void invalidate(List<String> paths);
}
