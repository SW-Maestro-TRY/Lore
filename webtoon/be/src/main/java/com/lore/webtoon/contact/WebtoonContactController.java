package com.lore.webtoon.contact;

import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    /** 우리 설비가 진짜 접속자 뒤에 덧붙인 X-Forwarded-For 칸 수. 운영은 CloudFront → ALB → 서버라 1(ALB). */
    private final int trustedHops;

    public WebtoonContactController(WebtoonContactService contact,
            @Value("${lore.webtoon.xff-trusted-hops:${app.zzal.hatch-limits.xff-trusted-hops:1}}") int trustedHops) {
        this.contact = contact;
        this.trustedHops = Math.max(0, trustedHops);
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

    /**
     * 1분에 한 통 제한을 걸 접속자 주소.
     *
     * {@code X-Forwarded-For} 의 <b>맨 앞은 브라우저가 적어 보낸 값</b>이라 그대로 믿으면 헤더 한 줄로
     * 제한을 피해 메일을 쏟아 보낼 수 있다(2026-10-25 리뷰). 줄은
     * {@code (적어 보낸 값), (진짜 접속자), (CloudFront)} 꼴이라, 우리 설비가 덧붙인 칸 수만큼
     * <b>뒤에서부터</b> 건너뛴 자리를 쓴다 — 짤의 {@code ClientIp} 와 같은 규칙이다.
     */
    private String clientIp(HttpServletRequest request) {
        String ip = fromForwarded(request.getHeader("X-Forwarded-For"), trustedHops);
        if (ip != null) {
            return ip;
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    static String fromForwarded(String forwardedFor, int trustedHops) {
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return null;
        }
        String[] parts = forwardedFor.split(",");
        if (parts.length == 0) {
            return null;
        }
        int index = Math.max(0, parts.length - 1 - trustedHops);
        String ip = parts[index].trim();
        return ip.isEmpty() || ip.length() > 64 ? null : ip;
    }

    public record Request(String email, String category, String message, Boolean privacyConsent, String website) {
    }
}
