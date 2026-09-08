package com.lore.common.email;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EmailTestController {

    private final EmailService emailService;

    @GetMapping("/api/test/email")
    public String sendTestEmail(@RequestParam String to) {
        emailService.send(
                to,
                "Lore SMTP 테스트",
                "Gmail SMTP 연결 테스트 메일입니다."
        );

        return "메일 발송 성공";
    }
}