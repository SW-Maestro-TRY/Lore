package com.lore.zzal.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 부화를 기다리는 동안 여울이 묻는 6문항의 답.
 *
 * <h3>★ 왜 users 가 아니라 여기인가</h3>
 * 계정({@code users})은 세 도메인이 함께 쓴다. 이 질문들은 <b>zzal 에서만</b> 뜻이 있다 —
 * /webtoon 은 다른 것을 묻고 싶을 수 있고, 그때 공용 표에 칸을 더하면 남의 도메인 질문이
 * 내 표에 쌓인다. 도메인마다 자기 프로필 표를 갖는다.
 *
 * <h3>★ 전부 비어 있을 수 있다</h3>
 * 여울이 하나씩 묻는 구조라 <b>중간에 나가는 사람이 반드시 생긴다.</b> 필수 항목을 두면
 * 그 사람들의 답이 통째로 버려진다. 그래서 모두 nullable 이고, 답한 데까지만 남는다.
 *
 * <h3>★ 왜 전부 varchar 인가</h3>
 * 선택지는 화면이 정한다(문구가 자주 바뀐다). 서버가 enum 으로 굳히면 문구를 하나 고칠 때마다
 * 배포가 필요하고, 옛 값이 들어오면 500 이 난다. 분석용 값이라 그 대가를 치를 이유가 없다.
 */
@Entity
@Table(name = "zzal_user_profiles")
public class ZzalUserProfile {

    /** 계정 번호가 그대로 이 표의 열쇠다 — 한 사람에 한 줄. */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * 아이가 사용자를 부르는 말.
     *
     * ★ 여섯 중 이것만 성격이 다르다 — 나머지는 분석용이지만 이것은 <b>게임 내내 대사에 쓰인다.</b>
     */
    @Column(length = 20)
    private String callMe;

    /** 주로 오는 시각(아침·낮·저녁·밤). 부름 시각을 나중에 조정할 때 쓴다. */
    @Column(length = 20)
    private String visitTime;

    /** 이 아이와의 사이(내 자캐·최애캐·선물받은 그림…). */
    @Column(length = 20)
    private String relation;

    /** 그림을 그리는 사람인가. 커미션 수요와 직결된다. */
    @Column(length = 20)
    private String draws;

    /** 나이대. */
    @Column(length = 20)
    private String ageBand;

    /** 서비스를 알게 된 경로. */
    @Column(length = 40)
    private String cameFrom;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ZzalUserProfile() {
    }

    private ZzalUserProfile(Long userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    public static ZzalUserProfile of(Long userId, Instant now) {
        return new ZzalUserProfile(userId, now);
    }

    /**
     * 보낸 항목만 덮어쓴다.
     *
     * ★ null 은 "지워 달라"가 아니라 "이번엔 안 보냈다"로 읽는다. 화면이 한 문항씩 보내므로
     *   보내지 않은 칸까지 null 로 밀면 <b>앞서 답한 것이 지워진다.</b>
     */
    public void patch(String callMe, String visitTime, String relation,
                      String draws, String ageBand, String cameFrom, Instant now) {
        if (callMe != null) this.callMe = callMe;
        if (visitTime != null) this.visitTime = visitTime;
        if (relation != null) this.relation = relation;
        if (draws != null) this.draws = draws;
        if (ageBand != null) this.ageBand = ageBand;
        if (cameFrom != null) this.cameFrom = cameFrom;
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCallMe() {
        return callMe;
    }

    public String getVisitTime() {
        return visitTime;
    }

    public String getRelation() {
        return relation;
    }

    public String getDraws() {
        return draws;
    }

    public String getAgeBand() {
        return ageBand;
    }

    public String getCameFrom() {
        return cameFrom;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
