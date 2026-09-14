package com.lore.zzal.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 경보 한 종류에 대해 <b>마지막으로 알린 지점</b>.
 *
 * <h3>★★ 이 표가 있는 이유 하나 — 재시작해도 같은 메일이 다시 가지 않게</h3>
 * 비용 알림은 "$10 단위를 넘을 때마다" 라서 <b>어디까지 알렸는지</b>를 기억해야 한다. 그 기억을
 * 메모리에 두면 배포(=재시작)마다 0 에서 다시 세어 이미 보낸 $10·$20·$30 이 한 번 더 나간다.
 * 하루에도 몇 번씩 오는 알림은 곧 안 읽히고, 그러면 <b>진짜 급한 알림도 같이 안 읽힌다</b> —
 * 알림 체계가 죽는 방식은 대개 이쪽이다.
 *
 * <h3>★ 이력을 쌓지 않는다</h3>
 * 종류마다 한 줄이고 마지막 값만 덮어쓴다. 보낸 이력은 메일함에 남는다. 여기 필요한 것은
 * "또 보내도 되나" 하나뿐이라, 줄이 늘어나면 그 질문에 답하기만 더 번거로워진다.
 *
 * <h3>★★ 값의 뜻은 종류마다 다르다</h3>
 * 그래서 판정도 종류마다 다르다 — 비용은 <b>더 큰 금액일 때만</b>, 나머지는 <b>값이 달라졌을 때만</b>
 * 보낸다({@link ZzalAlertStateRepository}).
 */
@Entity
@Table(name = "zzal_alert_state")
public class ZzalAlertState {

    /** 경보 종류. {@link AlertKeys} 의 상수 중 하나. */
    @Id
    @Column(name = "alert_key", nullable = false, length = 40)
    private String alertKey;

    /** 마지막으로 알린 지점(종류마다 뜻이 다르다 — {@link AlertKeys} 주석). */
    @Column(name = "last_value", nullable = false, length = 60)
    private String lastValue;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    protected ZzalAlertState() {
    }

    public String getAlertKey() {
        return alertKey;
    }

    public String getLastValue() {
        return lastValue;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
