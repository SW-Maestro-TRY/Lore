package com.lore.zzal.alert;

import com.lore.common.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 공통 메일 기능({@link EmailService}, Gmail SMTP)에 얹어 경보를 내보낸다.
 *
 * <h3>★★ 부르는 스레드를 붙잡지 않는다</h3>
 * 경보를 부르는 자리는 <b>부화 실행기 스레드(3개)와 사용자 요청 스레드</b>다. SMTP 는 바깥 연결이라
 * 느리거나 아예 안 끊기는 일이 있는데, 그 자리에서 기다리면 <b>메일 서버가 아픈 동안 부화가 멈춘다</b>.
 * 곁다리 기능이 본 기능을 세우는 일은 어떤 경우에도 맞지 않는다. 그래서 한 줄짜리 큐에 넘기고 돌아온다.
 *
 * <h3>★ 넘치면 버린다(막지 않는다)</h3>
 * 큐가 차면 그 통은 로그만 남기고 버린다. 경보가 밀릴 정도라면 이미 로그에 같은 사고가 쌓여 있고,
 * 그때 부르는 쪽을 기다리게 하는 것은 정확히 피하려던 그 일이다.
 *
 * <h3>★ 새 메일 기능을 만들지 않았다</h3>
 * 보내는 일 자체는 {@code common/be/.../email/EmailService} 가 이미 한다. 여기서는 그것을
 * <b>부르기만</b> 한다 — 공통은 이 작업에서 한 줄도 고치지 않는다.
 */
@Component
public class SmtpAlertMailer implements AlertMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpAlertMailer.class);

    private final EmailService emailService;

    /**
     * 메일 전용 한 줄. 스레드 하나·대기 20통.
     *
     * ★ 데몬 스레드다 — 종료할 때 보낼 것이 남아 있다고 프로세스가 안 끝나면 배포가 멈춘다.
     */
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(20),
            r -> {
                Thread t = new Thread(r, "zzal-alert-mail");
                t.setDaemon(true);
                return t;
            },
            (r, e) -> log.warn("경보 메일 큐가 찼습니다 — 이 통은 버립니다(로그에는 남습니다)"));

    public SmtpAlertMailer(EmailService emailService) {
        this.emailService = emailService;
    }

    @Override
    public void send(String to, String subject, String body) {
        sender.execute(() -> {
            try {
                emailService.send(to, subject, body);
                log.info("경보 메일 보냄 — {}", subject);
            } catch (RuntimeException | Error e) {
                // ★ 여기서 터져도 서비스는 아무 영향을 받지 않는다(남의 스레드다). 원인만 남긴다.
                //   ⚠️ 예외 메시지에 계정·비밀번호를 덧붙이지 않는다 — 로그가 곧 유출 경로가 된다.
                log.error("경보 메일 실패 — {}", subject, e);
            }
        });
    }
}
