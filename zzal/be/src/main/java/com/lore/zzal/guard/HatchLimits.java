package com.lore.zzal.guard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 부화 상한 — <b>설정값 한 자리</b>. 기본값이 곧 알파 공개(9/18)의 방벽이다.
 *
 * <h3>★ 왜 상수가 아니라 설정인가</h3>
 * 잔액이 $70 이고 부화 한 번이 약 $0.25 다. 사람이 몰리면 <b>그날 안에</b> 낮춰야 하고,
 * 반대로 조용하면 올려야 한다. 상수로 박으면 그때마다 빌드·배포가 필요하다.
 *
 * <h3>★ 0 이나 음수를 주면 그 상한은 <b>꺼진다</b></h3>
 * "0 = 아무것도 못 만든다" 로 읽으면, 설정을 잘못 비운 날 서비스가 통째로 멈추고
 * 그 이유가 어디에도 안 남는다. 정말 멈추고 싶으면 1 을 주고 그 한 명이 쓰게 하는 편이
 * 실수와 의도를 가른다. 꺼진 상한은 기동 로그에 남긴다.
 */
@Component
public class HatchLimits {

    /** 동시에 데리고 있을 수 있는 아이 수(자리를 차지하는 단계만 센다). */
    private final int maxAlive;

    /** 한 사람이 <b>평생</b> 시작할 수 있는 부화 수. */
    private final int maxTotal;

    /** 한 사람이 <b>하루</b>에 시작할 수 있는 부화 수. 경계는 한국 시각 자정. */
    private final int perUserDaily;

    /** 서비스 전체가 <b>하루</b>에 시작할 수 있는 부화 수. 경계는 한국 시각 자정. */
    private final int serviceDaily;

    /** 한 IP 가 창 하나 동안 시작할 수 있는 부화 수. */
    private final int ipPerWindow;

    /** 그 창의 길이. */
    private final Duration ipWindow;

    /** 바깥이 429 로 막았을 때 굽기 시작을 멈춰 두는 시간. */
    private final Duration quotaCooldown;

    public HatchLimits(
            @Value("${app.zzal.hatch-limits.max-alive:1}") int maxAlive,
            @Value("${app.zzal.hatch-limits.max-total:3}") int maxTotal,
            @Value("${app.zzal.hatch-limits.per-user-daily:3}") int perUserDaily,
            @Value("${app.zzal.hatch-limits.service-daily:40}") int serviceDaily,
            @Value("${app.zzal.hatch-limits.ip-per-window:10}") int ipPerWindow,
            @Value("${app.zzal.hatch-limits.ip-window-minutes:60}") long ipWindowMinutes,
            @Value("${app.zzal.hatch-limits.quota-cooldown-minutes:30}") long quotaCooldownMinutes) {
        this.maxAlive = maxAlive;
        this.maxTotal = maxTotal;
        this.perUserDaily = perUserDaily;
        this.serviceDaily = serviceDaily;
        this.ipPerWindow = ipPerWindow;
        this.ipWindow = Duration.ofMinutes(Math.max(1, ipWindowMinutes));
        this.quotaCooldown = Duration.ofMinutes(Math.max(1, quotaCooldownMinutes));
    }

    public int maxAlive() {
        return maxAlive;
    }

    public int maxTotal() {
        return maxTotal;
    }

    public int perUserDaily() {
        return perUserDaily;
    }

    public int serviceDaily() {
        return serviceDaily;
    }

    public int ipPerWindow() {
        return ipPerWindow;
    }

    public Duration ipWindow() {
        return ipWindow;
    }

    public Duration quotaCooldown() {
        return quotaCooldown;
    }

    /** 꺼진 상한인가. 판정하는 쪽이 매번 {@code > 0} 을 적지 않게 여기 한 곳에 둔다. */
    public static boolean off(int limit) {
        return limit <= 0;
    }
}
