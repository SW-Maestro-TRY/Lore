package com.lore.zzal.alert;

/**
 * 경보 한 통을 내보내는 자리.
 *
 * <h3>★ 왜 {@code EmailService} 를 바로 안 부르고 이 이음매를 두나</h3>
 * 하나 — 시험에서 <b>진짜로 메일이 나가지 않는다</b>는 것을 이름이 아니라 타입으로 못 박을 수 있다.
 * 둘 — 공통({@code common/be})은 읽기만 하는 영역이라, 감싸는 쪽을 zzal 안에 둔다.
 */
public interface AlertMailer {

    /** 받는 사람 한 명에게 한 통. 실패하면 예외를 던져도 된다 — 삼키는 곳은 {@link ZzalAlerts} 다. */
    void send(String to, String subject, String body);
}
