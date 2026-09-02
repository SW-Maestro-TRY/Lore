package com.lore.common.auth.dto;

import com.lore.common.user.AgreementType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** 인증 API 가 받는 것들. */
public final class AuthRequests {

    private AuthRequests() {
    }

    /**
     * 회원가입.
     *
     * 이 창은 그림을 올리려다 만나는 창이라 칸이 하나 늘 때마다 이탈이 생긴다.
     * 그래서 닉네임을 받지 않는다 — 실제로 필요해지는 건 친구 기능이고, 그때 물어도 늦지 않다.
     */
    @Schema(description = "회원가입 요청")
    public record SignUp(

            @Schema(description = "이메일. 로그인 아이디이자 연락처", example = "yeoul@example.com")
            @NotBlank @Email @Size(max = 255) String email,

            @Schema(description = "비밀번호. 8자 이상", example = "mypassword123", minLength = 8, maxLength = 72)
            @NotBlank @Size(min = 8, max = 72) String password,

            @Schema(description = """
                    약관 동의. TERMS·PRIVACY 는 필수라 true 가 아니면 가입이 거부된다.
                    MARKETING 은 선택이며 false 도 기록으로 남는다 — 안 물어본 것과 거부한 것은 다르다.""",
                    example = "{\"TERMS\": true, \"PRIVACY\": true, \"MARKETING\": false}")
            @NotNull Map<AgreementType, Boolean> agreements) {
    }

    @Schema(description = "로그인 요청")
    public record Login(

            @Schema(description = "가입한 이메일", example = "yeoul@example.com")
            @NotBlank @Email String email,

            @Schema(description = "비밀번호", example = "mypassword123")
            @NotBlank String password) {
    }
}
