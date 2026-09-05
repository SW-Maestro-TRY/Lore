package com.lore.webtoon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
 * 로그인 안 한 사람이 오늘 몇 편이나 만들었나.
 *
 * <h2>왜 uid 로는 안 되는가</h2>
 *
 * 만들기는 로그인 없이 되고, 그게 이 제품의 약속이다. 지금 그 앞을 세는 것은
 * 크레딧인데 크레딧은 <b>브라우저가 만든 uid</b> 로 센다 —
 * {@code localStorage} 를 지우면 새 사람이고, uid 는 아무 값이나 지어낼 수
 * 있다. 즉 세는 시늉만 하고 실제로는 아무나 무한히 만들 수 있다. 한 편에
 * 1,148원이 실제로 나가는데 그렇다.
 *
 * 그래서 지어낼 수 없는 값을 하나 더 본다. 완벽하지 않다 — IP 는 공유되고
 * (카페 · 회사 · 이동통신), 바꿀 수도 있다. 그래서 이것은 <b>담장이지 벽이
 * 아니다.</b> 벽은 {@link SpendGuard} 의 전체 일일 상한이고, 이쪽은 "한
 * 사람이 대충 눌러 대는 것" 을 막는다. 둘이 겹쳐야 뜻이 있다.
 *
 * <h2>날마다 다시 채워진다</h2>
 *
 * 평생 N회가 아니라 하루 N회다. IP 는 공유되므로 평생으로 잠그면 같은 건물에
 * 있는 남 때문에 못 쓰는 사람이 생기고, 그 사람에게는 그냥 고장난 서비스로
 * 보인다. 하루로 두면 그런 사람도 내일은 쓸 수 있고, 하루에 나갈 수 있는
 * 총액은 어차피 전체 상한이 잡는다.
 *
 * <h2>주소를 그대로 안 적는다</h2>
 *
 * IP 는 개인정보다. 세는 데는 "같은 곳인가" 만 알면 되고 그 값이 무엇인지는
 * 알 필요가 없으므로, 소금을 섞어 해시한 값만 남긴다. 표를 열어 봐도 누구인지
 * 알 수 없고, 소금을 바꾸면 옛 기록은 아무와도 안 이어진다.
 */
@Entity
@Table(
        name = "webtoon_guest_quota",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_guest_quota_day", columnNames = {"ip_hash", "day"}))
public class GuestQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소금 섞어 해시한 접속 주소. 원래 주소는 어디에도 안 남는다. */
    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    /** 어느 날인가. 한국 시간 기준 — 사람이 "오늘" 이라고 부르는 날과 같아야 한다. */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** 그 날 이 주소에서 시작한 만들기 횟수. */
    @Column(name = "used", nullable = false)
    private int used;

    protected GuestQuota() {
    }

    GuestQuota(String ipHash, LocalDate day) {
        this.ipHash = ipHash;
        this.day = day;
        this.used = 0;
    }

    public Long getId() {
        return id;
    }

    public String getIpHash() {
        return ipHash;
    }

    public LocalDate getDay() {
        return day;
    }

    public int getUsed() {
        return used;
    }

    void use() {
        this.used++;
    }
}
