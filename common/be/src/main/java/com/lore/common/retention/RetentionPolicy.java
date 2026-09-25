package com.lore.common.retention;

import java.time.Duration;

/**
 * 개인정보를 얼마나 두는가.
 *
 * <h2>왜 한 곳에 모아 두는가</h2>
 *
 * 이 숫자들은 <b>개인정보처리방침에 적혀 있는 약속</b>이다
 * ({@code apps/web/content/legal/개인정보처리방침.md} 제4조). 문서와 코드가
 * 다른 숫자를 보면, 문서에 적은 그것이 그대로 위반 사실이 된다. 기간을 고칠
 * 때는 <b>반드시 문서도 같이 고친다</b> — 문서 쪽 원본은
 * {@code webtoon/docs/legal/개인정보처리방침-v1-초안.md} 이고,
 * {@code webtoon/docs/legal/게시본-뽑기.py} 로 게시본을 다시 뽑는다.
 *
 * <h2>크레딧 기록이 여기 없는 이유</h2>
 *
 * 크레딧은 <b>기간이 지나도 행을 못 지운다.</b> 잔액이 따로 저장된 값이 아니라
 * 움직인 것 전부의 합이라서다({@code CreditEventRepository.balanceOf} 의
 * {@code sum(delta)}). 지난 행을 지우면 잔액이 그만큼 틀어지고, 게다가
 * {@code uk_credit_event_once} 가 중복 지급 방지를 겸하고 있어서 지우면 같은
 * {@code refId} 로 가입 축하·매일 크레딧이 다시 지급될 수 있다.
 *
 * 그래서 크레딧은 개인정보성인 {@code memo}(작품 이름 같은 자유 문구)만 비우고,
 * 처리방침에는 "정산과 부정 이용 방지를 위해 계정이 있는 동안 보관" 으로 적었다.
 * 지킬 수 있는 것만 적는다.
 */
public final class RetentionPolicy {

    /** 탈퇴한 계정과 그에 딸린 사진·생성물을 지우기까지. */
    public static final Duration AFTER_WITHDRAWAL = Duration.ofDays(30);

    /** 생성 기록에서 사용자가 적은 말(이름·이야기 소재 등)을 지우기까지. */
    public static final Duration GENERATION_RECORD = Duration.ofDays(365);

    /** 게스트 하루 이용 횟수(IP 해시)를 지우기까지. */
    public static final Duration GUEST_COUNTER = Duration.ofDays(90);

    /** 완성 알림을 보낸 뒤 그 주소를 지우기까지. */
    public static final Duration NOTIFY_EMAIL_AFTER_SENT = Duration.ofDays(30);

    private RetentionPolicy() {
    }
}
