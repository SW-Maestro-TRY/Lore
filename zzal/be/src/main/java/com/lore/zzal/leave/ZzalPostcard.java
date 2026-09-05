package com.lore.zzal.leave;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 여행 중 보낸 엽서 한 장(정본 9장 "엽서 1장/일, 최대 3").
 *
 * ★ 장면과 같은 생각이다 — <b>그림이 아니라 레시피</b>(어디서·언제·몇 번째). 화면이 엽서 프레임에 얹는다.
 *
 * ★★ 문구에 원망이 없다. 엽서는 "잘 지내고 있어요" 를 전하는 물건이지 청구서가 아니다
 *    (자캐 커뮤니티 규범 — 사용자를 탓하는 대사 금지). 문구는 마지막에 금지 필터를 한 번 더 지난다.
 */
@Entity
@Table(name = "zzal_postcard", indexes = @Index(name = "idx_zzal_postcard_pet", columnList = "pet_id"))
public class ZzalPostcard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 몇 번째 엽서인가(1~3). 문구가 이 번호로 갈린다. */
    @Column(nullable = false)
    private int seq;

    /** 어디서 보냈나(배경 key). */
    @Column(nullable = false, length = 40)
    private String place;

    /** 언제 썼나(펫 시계). */
    @Column(nullable = false)
    private Instant writtenAt;

    /**
     * 재회 때 전달했나.
     *
     * ★ 전달 전에는 화면에 안 보인다 — 여행 중에 다 보여 주면 <b>부르러 갈 이유</b>가 없어진다.
     */
    @Column
    private Instant deliveredAt;

    protected ZzalPostcard() {
    }

    public static ZzalPostcard of(Long petId, int seq, String place, Instant writtenAt) {
        ZzalPostcard p = new ZzalPostcard();
        p.petId = petId;
        p.seq = seq;
        p.place = place;
        p.writtenAt = writtenAt;
        return p;
    }

    /** 재회했다 — 이제 앨범에 보인다. */
    public void deliver(Instant now) {
        if (deliveredAt == null) {
            deliveredAt = now;
        }
    }

    public boolean isDelivered() {
        return deliveredAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public int getSeq() {
        return seq;
    }

    public String getPlace() {
        return place;
    }

    public Instant getWrittenAt() {
        return writtenAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
