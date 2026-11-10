package com.lore.webtoon.art;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 창고에 실제로 무엇이 있나 — 접두사 아래 파일을 전부 센다.
 *
 * 관리자 점검({@link ArtOrphanScan})만 쓴다. 평소 화면은 DB 에 적힌 이름으로만 그림을 찾는다.
 */
@Component
public class ArtInventory {

    /** 창고의 파일 하나. */
    public record Item(String key, long bytes, Instant modified) {
    }

    private final S3Client s3;
    private final String bucket;

    public ArtInventory(S3Client s3, @Value("${app.s3.content-bucket:}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket == null ? "" : bucket.trim();
    }

    /** 버킷을 안 정해 두면 S3 를 아예 안 쓴다(로컬). */
    public boolean ready() {
        return !bucket.isEmpty();
    }

    /** 이 접두사 아래 전부. 한 번에 1000개씩 끊어 오는 것을 끝까지 따라간다. */
    public List<Item> list(String prefix) {
        List<Item> out = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket).prefix(prefix).build();
        for (S3Object one : s3.listObjectsV2Paginator(request).contents()) {
            out.add(new Item(one.key(), one.size() == null ? 0 : one.size(), one.lastModified()));
        }
        return out;
    }
}
