package com.lore.zzal.archive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * S3 로 올린 덩어리 한 장의 영수증.
 *
 * <h3>★ 이 표가 곧 "어디까지 올렸나"</h3>
 * 기준점은 {@code max(to_event_id)} 하나로 나온다. 커서를 따로 한 줄 두는 방법도 있지만,
 * 그러면 <b>커서와 실제로 올라간 것</b>이 어긋날 수 있고 어긋난 사실이 아무 소리도 안 낸다.
 * 영수증에서 기준점을 뽑으면 둘이 어긋날 자리 자체가 없다.
 *
 * <h3>★★ 올린 <b>뒤에</b> 적는다 — 순서가 뒤바뀌면 기록이 사라진다</h3>
 * 적고 나서 올리면, 그 사이에 서버가 죽었을 때 "올렸다" 고 적힌 구간이 S3 에 없다.
 * 그 구간은 기준점을 넘어섰으므로 <b>다시는 안 올라간다.</b> 반대 순서면 최악이 "같은 파일을
 * 한 번 더 올림" 인데, 키가 id 구간에서 결정적으로 나오므로 같은 자리에 덮어써서 흔적도 안 남는다.
 *
 * <h3>★ 한 판(페이지)의 영수증은 함께 적는다</h3>
 * 한 번에 읽은 줄이 날짜 경계를 걸치면 파일이 둘로 나뉜다. 그 둘을 따로 커밋하면, 앞의 것만
 * 적힌 채 죽었을 때 기준점이 뒤 파일의 id 까지 넘어가 <b>뒤 파일 몫이 통째로 빠진다.</b>
 */
@Entity
@Table(
        name = "zzal_event_archive_part",
        indexes = @Index(name = "idx_zzal_event_archive_part_to", columnList = "to_event_id"))
public class ZzalEventArchivePart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 이 덩어리가 속한 날(KST). S3 경로의 {@code dt=} 와 같은 값. */
    @Column(nullable = false)
    private LocalDate dt;

    @Column(name = "from_event_id", nullable = false)
    private long fromEventId;

    @Column(name = "to_event_id", nullable = false)
    private long toEventId;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    /** 압축 뒤 바이트. 보관 비용을 세려면 이 숫자가 필요하다. */
    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    /** ★ 버킷도 같이 적는다 — 계정을 옮기면 이름이 바뀌고, 그때 옛 영수증이 어디를 가리켰는지 알아야 한다. */
    @Column(nullable = false, length = 120)
    private String bucket;

    @Column(name = "s3_key", nullable = false, length = 300)
    private String s3Key;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(length = 60)
    private String server;

    protected ZzalEventArchivePart() {
    }

    public static ZzalEventArchivePart of(LocalDate dt, long fromEventId, long toEventId, int rowCount,
                                          long byteSize, String bucket, String s3Key,
                                          Instant uploadedAt, String server) {
        ZzalEventArchivePart p = new ZzalEventArchivePart();
        p.dt = dt;
        p.fromEventId = fromEventId;
        p.toEventId = toEventId;
        p.rowCount = rowCount;
        p.byteSize = byteSize;
        p.bucket = bucket;
        p.s3Key = s3Key;
        p.uploadedAt = uploadedAt;
        p.server = server;
        return p;
    }

    public Long getId() {
        return id;
    }

    public LocalDate getDt() {
        return dt;
    }

    public long getFromEventId() {
        return fromEventId;
    }

    public long getToEventId() {
        return toEventId;
    }

    public int getRowCount() {
        return rowCount;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getBucket() {
        return bucket;
    }

    public String getS3Key() {
        return s3Key;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public String getServer() {
        return server;
    }
}
