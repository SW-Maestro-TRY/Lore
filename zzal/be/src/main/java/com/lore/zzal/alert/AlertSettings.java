package com.lore.zzal.alert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 경보 메일의 설정과 <b>보내도 되는가</b>의 판정.
 *
 * <h3>★★ 기본은 꺼짐이다</h3>
 * 설정이 하나도 없는 채로 배포돼도 <b>아무 일도 일어나지 않아야</b> 한다. 받는 주소가 비어 있는데
 * 켜져 있으면 SMTP 에 빈 수신자를 보내 예외만 쌓이고, 그 예외는 정작 보려던 사고(돈·고장)를
 * 로그에서 덮는다. 그래서 스위치와 주소 <b>둘 다</b> 있어야 보낸다.
 *
 * <h3>★ 숫자 둘은 0 이하로 두면 그 경보만 꺼진다</h3>
 * {@code HatchLimits.off} 와 같은 규칙이다 — "0 = 매번 보낸다" 로 읽으면 설정을 잘못 비운 날
 * 메일이 쏟아지고, 그게 바로 알림을 안 읽게 만드는 길이다.
 *
 * <h3>⚠️ 메일 비밀번호는 여기 없다</h3>
 * SMTP 계정은 {@code spring.mail.username} · {@code spring.mail.password}(환경변수 {@code MAIL_USERNAME} ·
 * {@code MAIL_PASSWORD})가 갖고 있고, 이 클래스도 로그도 그 값을 만지지 않는다.
 */
@Component
public class AlertSettings {

    private final boolean enabled;
    private final List<String> recipients;
    private final int costStepUsd;
    private final int hatchFailStreak;

    public AlertSettings(
            @Value("${app.zzal.alert.enabled:false}") boolean enabled,
            @Value("${app.zzal.alert.to:}") String to,
            @Value("${app.zzal.alert.cost-step-usd:10}") int costStepUsd,
            @Value("${app.zzal.alert.hatch-fail-streak:3}") int hatchFailStreak) {
        this.enabled = enabled;
        this.recipients = Arrays.stream(String.valueOf(to == null ? "" : to).split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        this.costStepUsd = costStepUsd;
        this.hatchFailStreak = hatchFailStreak;
    }

    /** 보내도 되면 {@code null}, 아니면 <b>안 보내는 이유</b>(설정 이름을 포함한 한 문장). */
    public String blockedReason() {
        if (!enabled) {
            return "app.zzal.alert.enabled (ZZAL_ALERT_ENABLED) 가 꺼져 있습니다";
        }
        if (recipients.isEmpty()) {
            return "app.zzal.alert.to (ZZAL_ALERT_TO) 가 비어 있습니다 — 받는 사람이 없으면 보내지 않습니다";
        }
        return null;
    }

    public boolean canSend() {
        return blockedReason() == null;
    }

    public List<String> recipients() {
        return recipients;
    }

    /** 비용 경보의 단위(달러). 0 이하면 비용 경보만 꺼진다. */
    public int costStepUsd() {
        return costStepUsd;
    }

    /** 몇 번 연달아 실패하면 알리나. 0 이하면 연속 실패 경보만 꺼진다. */
    public int hatchFailStreak() {
        return hatchFailStreak;
    }
}
