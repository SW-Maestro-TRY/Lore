package com.lore.zzal.alert;

import java.util.ArrayList;
import java.util.List;

/**
 * 메일을 <b>실제로 안 보내는</b> 발송기. 무엇을 보내려 했는지만 적어 둔다.
 *
 * ★★ 시험에서 진짜 SMTP 로 나가는 길을 타입으로 막는다 — 켜고 끄는 설정에 기대면
 *   설정 한 줄이 바뀌는 날 조용히 진짜 메일이 나간다.
 */
public class FakeAlertMailer implements AlertMailer {

    /** 보내려 한 것들. {@code [받는사람, 제목, 본문]} */
    public final List<String[]> sent = new ArrayList<>();

    /** true 면 보내려 할 때마다 터진다 — "발송이 터져도 서비스가 안 멈춘다" 를 보는 자리. */
    public boolean explode;

    /** 이 주소로 보낼 때만 터진다 — "한 사람이 실패해도 나머지는 간다" 를 보는 자리. */
    public String explodeFor;

    @Override
    public void send(String to, String subject, String body) {
        if (explode || to.equals(explodeFor)) {
            throw new IllegalStateException("SMTP 가 죽었다(시험이 일부러 일으킨 실패)");
        }
        sent.add(new String[]{to, subject, body});
    }

    public int count() {
        return sent.size();
    }

    public String lastSubject() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1)[1];
    }

    public String lastBody() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1)[2];
    }

    public void clear() {
        sent.clear();
    }
}
