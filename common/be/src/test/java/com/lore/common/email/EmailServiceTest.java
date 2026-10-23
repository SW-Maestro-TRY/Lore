package com.lore.common.email;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 보낸사람(lore.mail.from · from-name) 규칙 테스트.
 *
 * 주소가 있으면 그 주소로, 비어 있으면 지금까지처럼 지정하지 않는다(SMTP 계정으로 나감).
 */
class EmailServiceTest {

    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final EmailService service = new EmailService(sender);

    @Test
    @DisplayName("주소와 이름이 설정돼 있으면 글자 메일에 둘 다 실린다")
    void textMailUsesConfiguredFrom() {
        ReflectionTestUtils.setField(service, "from", "lore@lorecomic.com");
        ReflectionTestUtils.setField(service, "fromName", "LORE");

        service.send("user@example.com", "제목", "본문");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isEqualTo("LORE <lore@lorecomic.com>");
    }

    @Test
    @DisplayName("주소가 비어 있으면 이름이 있어도 보낸사람을 지정하지 않는다")
    void textMailLeavesFromEmptyWhenBlank() {
        ReflectionTestUtils.setField(service, "from", "");
        ReflectionTestUtils.setField(service, "fromName", "LORE");

        service.send("user@example.com", "제목", "본문");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        assertThat(captor.getValue().getFrom()).isNull();
    }

    @Test
    @DisplayName("HTML 메일에도 이름과 주소가 실린다")
    void htmlMailUsesConfiguredFrom() throws Exception {
        ReflectionTestUtils.setField(service, "from", "lore@lorecomic.com");
        ReflectionTestUtils.setField(service, "fromName", "LORE");
        MimeMessage message = new MimeMessage((Session) null);
        when(sender.createMimeMessage()).thenReturn(message);

        service.sendHtml("user@example.com", "제목", "본문", "<p>본문</p>");

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("lore@lorecomic.com");
        assertThat(from.getPersonal()).isEqualTo("LORE");
    }

    @Test
    @DisplayName("이름 없이 주소만 있으면 주소만 실린다")
    void htmlMailWithAddressOnly() throws Exception {
        ReflectionTestUtils.setField(service, "from", "lore@lorecomic.com");
        ReflectionTestUtils.setField(service, "fromName", "");
        MimeMessage message = new MimeMessage((Session) null);
        when(sender.createMimeMessage()).thenReturn(message);

        service.sendHtml("user@example.com", "제목", "본문", "<p>본문</p>");

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("lore@lorecomic.com");
        assertThat(from.getPersonal()).isNull();
    }
}
