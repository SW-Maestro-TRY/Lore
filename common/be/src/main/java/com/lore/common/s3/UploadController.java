package com.lore.common.s3;

import com.lore.common.response.ApiResponse;
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
 *   3) 돌려받은 key 를 자기 도메인 API(comic/story/trailer)에 저장한다.
 *
 * 서버는 파일 바이트를 만지지 않으므로 t3.micro 가 업로드로 멈출 위험이 없다.
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final S3Service s3Service;

    public UploadController(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @PostMapping("/presign")
    public ApiResponse<S3Service.PresignedUpload> presign(@Valid @RequestBody PresignRequest request) {
        return ApiResponse.ok(s3Service.createUploadUrl(request.domain(), request.contentType()));
    }

    /** 발급 요청 본문. domain = 키 폴더(comic/story/trailer), contentType = image/png 등. */
    public record PresignRequest(
            @NotBlank String domain,
            @NotBlank String contentType) {
    }
}
