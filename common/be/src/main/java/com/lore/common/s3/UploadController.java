package com.lore.common.s3;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘텐츠 이미지 업로드용 presigned URL 발급 API (팀 공용).
 *
 * 흐름:
 *   1) 프론트가 여기서 presigned URL 을 받고
 *   2) 그 URL 로 S3 에 이미지를 직접 PUT 한 뒤
 *   3) 돌려받은 key 를 자기 도메인 API(zzal/webtoon/trailer)에 저장한다.
 *
 * 서버는 파일 바이트를 만지지 않으므로 t3.micro 가 업로드로 멈출 위험이 없다.
 */
@Tag(name = "업로드", description = "이미지 업로드용 주소 발급 (팀 공용)")
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final S3Service s3Service;

    public UploadController(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @Operation(summary = "이미지 업로드 주소 발급", description = """
            S3 에 직접 올릴 수 있는 임시 주소를 받는다. 서버는 파일을 만지지 않는다.

            1. 이 API 로 url 과 key 를 받는다
            2. 그 url 로 이미지를 PUT 한다 (브라우저 → S3 직접)
            3. 받은 key 를 자기 도메인 API 에 저장한다

            발급된 주소는 10분간 유효하다.""")
    @PostMapping("/presign")
    public ApiResponse<S3Service.PresignedUpload> presign(@LoginUser Long userId,
                                                         @Valid @RequestBody PresignRequest request) {
        return ApiResponse.ok(s3Service.createUploadUrl(userId, request.domain(), request.contentType()));
    }

    /** 발급 요청 본문. domain = 키 폴더(zzal/webtoon/trailer), contentType = image/png 등. */
    public record PresignRequest(
            @Schema(description = "키 폴더. zzal · webtoon · trailer · common 만 허용", example = "zzal")
            @NotBlank String domain,

            @Schema(description = "올릴 파일의 MIME 타입", example = "image/png")
            @NotBlank String contentType) {
    }
}
