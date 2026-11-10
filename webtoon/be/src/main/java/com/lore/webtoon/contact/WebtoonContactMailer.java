package com.lore.webtoon.contact;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 문의 메일에 첨부 파일을 포함한다. 공용 메일 API는 변경하지 않는다. */
@Component
public class WebtoonContactMailer {

    private final JavaMailSender sender;
    private final String from;
    private final String fromName;

    public WebtoonContactMailer(
            JavaMailSender sender,
            @Value("${lore.mail.from:}") String from,
            @Value("${lore.mail.from-name:}") String fromName) {
        this.sender = sender;
        this.from = from;
        this.fromName = fromName;
    }

    public void send(String to, String subject, String text, String html, List<MultipartFile> files) {
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(from)) {
                if (StringUtils.hasText(fromName)) helper.setFrom(from, fromName);
                else helper.setFrom(from);
            }
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            for (MultipartFile file : files) {
                helper.addAttachment(file.getOriginalFilename(), file::getInputStream, file.getContentType());
            }
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException("문의 메일을 꾸리지 못했습니다", e);
        }
        sender.send(message);
    }
}
