package com.lore.webtoon.contact;

import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹툰 서비스 문의를 운영진 메일로 전달한다. */
@Tag(name = "Webtoon", description = "문의")
@RestController
@RequestMapping(WebtoonApi.V1 + "/contact")
public class WebtoonContactController {

    private final WebtoonContactService contact;

    public WebtoonContactController(WebtoonContactService contact) {
        this.contact = contact;
    }

    @Operation(summary = "웹툰 문의 보내기", description = "허용된 문의 유형과 답변 이메일, 본문을 운영진 메일로 전달한다.")
    @PostMapping
    public Map<String, Boolean> send(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "privacyConsent", required = false) Boolean privacyConsent,
            @RequestParam(value = "website", required = false) String website,
            @RequestParam(value = "files", required = false) MultipartFile[] files,
            HttpServletRequest request) {
        contact.send(new Request(email, category, message, privacyConsent, website), files, clientIp(request));
        return Map.of("sent", true);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    public record Request(String email, String category, String message, Boolean privacyConsent, String website) {
    }
}
