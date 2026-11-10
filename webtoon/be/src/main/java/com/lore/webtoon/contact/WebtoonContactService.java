package com.lore.webtoon.contact;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** 외부 입력을 고정된 운영 수신처에 전달한다. 입력은 제목이나 수신 주소로 쓰지 않는다. */
@Service
public class WebtoonContactService {

    private static final int MAX_MESSAGE = 3000;
    private static final int MAX_FILES = 3;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = MAX_FILES * MAX_FILE_BYTES;
    private static final Map<String, String> ALLOWED_FILES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "webp", "image/webp", "pdf", "application/pdf");
    private static final long SEND_INTERVAL_MILLIS = 60_000;
    private static final Map<String, String> CATEGORIES = Map.of(
            "WEBTOON", "웹툰 제작",
            "ACCOUNT_CREDITS", "계정·크레딧",
            "ERROR", "오류 제보",
            "OTHER", "기타 문의");

    private final WebtoonContactMailer mail;
    private final String recipient;
    private final ConcurrentHashMap<String, Long> lastSentByIp = new ConcurrentHashMap<>();

    public WebtoonContactService(
            WebtoonContactMailer mail,
            @Value("${lore.webtoon.contact-email:lore@lorecomic.com}") String recipient) {
        this.mail = mail;
        this.recipient = recipient;
    }

    public void send(WebtoonContactController.Request request, MultipartFile[] uploads, String clientIp) {
        if (request == null) {
            throw invalid("문의 내용을 적어 주세요");
        }
        // 자동 입력 봇은 성공 응답만 받고 실제 메일은 만들지 않는다.
        if (StringUtils.hasText(request.website())) {
            return;
        }
        if (!Boolean.TRUE.equals(request.privacyConsent())) {
            throw invalid("개인정보 수집 및 이용에 동의해 주세요");
        }

        String email = cleanEmail(request.email());
        String category = request.category() == null ? null : CATEGORIES.get(request.category());
        String message = request.message() == null ? "" : request.message().trim();
        if (category == null) {
            throw invalid("문의 유형을 골라 주세요");
        }
        if (message.length() < 5 || message.length() > MAX_MESSAGE) {
            throw invalid("문의 내용은 5자 이상 3,000자 이하로 적어 주세요");
        }
        List<MultipartFile> files = validateFiles(uploads);

        reserve(clientIp);
        Instant consentedAt = Instant.now();
        String subject = "[LORE 웹툰 문의] " + category;
        String text = "LORE 웹툰 서비스 문의\n\n문의 유형: " + category
                + "\n답변 이메일: " + email + "\n개인정보 수집 및 이용 동의: 동의"
                + "\n동의 시각: " + consentedAt + "\n첨부 파일: " + (files.isEmpty() ? "없음" : files.size() + "개")
                + "\n\n문의 내용:\n" + message;
        String html = "<h2>LORE 웹툰 서비스 문의</h2>"
                + "<p><strong>문의 유형:</strong> " + escape(category) + "</p>"
                + "<p><strong>답변 이메일:</strong> " + escape(email) + "</p>"
                + "<p><strong>개인정보 수집 및 이용 동의:</strong> 동의 (" + consentedAt + ")</p>"
                + "<p><strong>첨부 파일:</strong> " + (files.isEmpty() ? "없음" : files.size() + "개") + "</p>"
                + "<p><strong>문의 내용:</strong></p><p style=\"white-space:pre-wrap\">"
                + escape(message) + "</p>";
        try {
            mail.send(recipient, subject, text, html, files);
        } catch (RuntimeException failure) {
            lastSentByIp.remove(rateKey(clientIp));
            throw failure;
        }
    }

    private static List<MultipartFile> validateFiles(MultipartFile[] uploads) {
        if (uploads == null || uploads.length == 0) return List.of();
        if (uploads.length > MAX_FILES) throw invalid("첨부 파일은 최대 3개까지 올릴 수 있어요");
        long total = 0;
        List<MultipartFile> files = new java.util.ArrayList<>();
        for (MultipartFile file : uploads) {
            if (file == null || file.isEmpty()) continue;
            if (file.getSize() > MAX_FILE_BYTES) throw invalid("파일 하나는 1MB 이하로 첨부해 주세요");
            total += file.getSize();
            if (total > MAX_TOTAL_BYTES) throw invalid("첨부 파일 전체 용량은 3MB 이하로 올려 주세요");
            String filename = file.getOriginalFilename();
            String safeName = filename == null ? "" : filename.replace('\\', '/');
            safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
            int dot = safeName.lastIndexOf('.');
            String extension = dot < 0 ? "" : safeName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            String expectedType = ALLOWED_FILES.get(extension);
            if (expectedType == null || !expectedType.equalsIgnoreCase(file.getContentType())) {
                throw invalid("PNG, JPG, WEBP 이미지와 PDF 파일만 첨부할 수 있어요");
            }
            try {
                if (!hasExpectedSignature(expectedType, file.getBytes())) {
                    throw invalid("파일 형식을 확인할 수 없어요. 다시 선택해 주세요");
                }
            } catch (java.io.IOException unreadable) {
                throw invalid("첨부 파일을 읽지 못했어요. 다시 선택해 주세요");
            }
            files.add(new RenamedMultipartFile(file, safeName));
        }
        return List.copyOf(files);
    }

    private static boolean hasExpectedSignature(String contentType, byte[] bytes) {
        if ("image/png".equals(contentType)) {
            return bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e
                    && bytes[3] == 0x47 && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
        }
        if ("image/jpeg".equals(contentType)) {
            return bytes.length >= 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
        }
        if ("image/webp".equals(contentType)) {
            return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        }
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-';
    }

    /** 경로와 줄바꿈이 포함된 원본 파일명이 메일 헤더에 들어가지 않도록 파일명만 교체한다. */
    private record RenamedMultipartFile(MultipartFile source, String name) implements MultipartFile {
        @Override public String getName() { return source.getName(); }
        @Override public String getOriginalFilename() { return name.replaceAll("[\\r\\n\\\"\\\\]", "_"); }
        @Override public String getContentType() { return source.getContentType(); }
        @Override public boolean isEmpty() { return source.isEmpty(); }
        @Override public long getSize() { return source.getSize(); }
        @Override public byte[] getBytes() throws java.io.IOException { return source.getBytes(); }
        @Override public java.io.InputStream getInputStream() throws java.io.IOException { return source.getInputStream(); }
        @Override public void transferTo(java.io.File dest) throws java.io.IOException, IllegalStateException { source.transferTo(dest); }
    }

    private static String cleanEmail(String raw) {
        String email = raw == null ? "" : raw.trim();
        if (email.length() > 254 || email.contains("\r") || email.contains("\n")) {
            throw invalid("이메일 주소를 다시 확인해 주세요");
        }
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            if (!email.equals(address.getAddress()) || !email.contains("@")) {
                throw invalid("이메일 주소를 다시 확인해 주세요");
            }
        } catch (AddressException invalidAddress) {
            throw invalid("이메일 주소를 다시 확인해 주세요");
        }
        return email;
    }

    private void reserve(String clientIp) {
        long now = Instant.now().toEpochMilli();
        String key = rateKey(clientIp);
        boolean[] accepted = {false};
        lastSentByIp.compute(key, (ignored, previous) -> {
            if (previous == null || now - previous >= SEND_INTERVAL_MILLIS) {
                accepted[0] = true;
                return now;
            }
            return previous;
        });
        if (lastSentByIp.size() > 1000) {
            long cutoff = now - SEND_INTERVAL_MILLIS * 60;
            lastSentByIp.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        }
        if (!accepted[0]) {
            throw invalid("문의는 잠시 뒤 다시 보낼 수 있어요");
        }
    }

    private static String rateKey(String clientIp) {
        return clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_INPUT, message);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
