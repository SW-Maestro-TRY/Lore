package com.lore.common.auth.dto;

import com.lore.common.user.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** 인증 API 가 돌려주는 것들. */
public final class AuthResponses {

    private AuthResponses() {
    }

    /**
     * 내 정보.
     *
     * ★ 토큰은 응답 본문에 넣지 않는다. 쿠키로만 나간다 — 본문에 담으면 자바스크립트가
     *   읽을 수 있게 되어 HttpOnly 로 막은 의미가 사라진다.
     */
    @Schema(description = "내 계정 정보")
    public record Me(

            @Schema(description = "계정 번호", example = "1") Long userId,

            @Schema(description = "이메일", example = "yeoul@example.com") String email,

            @Schema(description = "권한. USER 또는 ADMIN", example = "USER") String role,

            @Schema(description = "가입 시각") Instant createdAt) {

        public static Me from(User user) {
            return new Me(user.getId(), user.getEmail(), user.getRole().name(), user.getCreatedAt());
        }
    }
}
