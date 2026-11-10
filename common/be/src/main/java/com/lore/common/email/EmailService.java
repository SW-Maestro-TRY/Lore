package com.lore.common.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    /**
     * 받는 사람에게 보이는 보낸사람 주소(예: {@code lore@lorecomic.com}).
     * 비어 있으면 지정하지 않고, 그러면 SMTP 로그인 계정(MAIL_USERNAME)으로 나간다.
     * ★ Gmail SMTP 는 그 계정의 「다른 주소에서 메일 보내기」에 등록·인증된 주소만 그대로 두고,
     *   등록 안 된 주소는 로그인 계정으로 바꿔 보낸다.
     * ★ 이름과 주소를 한 값({@code LORE <...>})으로 받지 않는다. 서버의 /etc/lore/secrets.env 는
     *   따옴표 없이 쓰이고 bash 로 source 하는 도구도 있어(webtoon/tools/examples/db-sql.sh),
     *   공백·{@code <}가 든 값은 거기서 깨진다.
     */
    @Value("${lore.mail.from:}")
    private String from;

    /** 보낸사람 이름(예: {@code LORE}). 주소가 비어 있으면 쓰지 않는다. */
    @Value("${lore.mail.from-name:}")
    private String fromName;

    public void send(String to, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (StringUtils.hasText(from)) {
            message.setFrom(StringUtils.hasText(fromName) ? fromName + " <" + from + ">" : from);
        }
        message.setTo(to);
        message.setSubject(subject);
        message.setText(text);

        mailSender.send(message);
    }

    /**
     * 글자 본문과 HTML 본문을 같이 싣는다. 메일 앱이 HTML 을 그리면 HTML 을,
     * 못 그리면(또는 꺼 두었으면) 글자 본문을 보여 준다.
     */
    public void sendHtml(String to, String subject, String text, String html) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(from)) {
                if (StringUtils.hasText(fromName)) {
                    helper.setFrom(from, fromName);
                } else {
                    helper.setFrom(from);
                }
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("메일을 꾸리지 못했습니다", e);
        }
        mailSender.send(message);
    }
}
