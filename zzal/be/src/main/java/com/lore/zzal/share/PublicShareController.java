package com.lore.zzal.share;

import com.lore.common.response.ApiResponse;
import com.lore.zzal.share.dto.ShareResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인 없이 열리는 유일한 zzal 주소.
 *
 * <h3>★ 주소에 {@code public} 이 들어가는 이유</h3>
 * 시큐리티가 {@code /api/zzal/v1/public/**} 만 GET 으로 연다. 공개 여부가 <b>주소에 드러나므로</b>
 * 로그인이 필요한 API 를 실수로 여는 일이 구조적으로 어려워진다.
 *
 * <h3>★ 여기서 나가는 것이 남에게 보이는 전부다</h3>
 * 움짤 하나 · 아이 이름 · 언제 공유됐나. 계정도, 다른 동작도, 진행 상황도 나가지 않는다.
 */
@Tag(name = "공유(공개)", description = "공유 링크로 진입한 사용자에게 제공하는 조회 API. 인증이 필요 없다")
@RestController
@RequestMapping("/api/zzal/v1/public/share")
public class PublicShareController {

    private final ShareService shareService;

    public PublicShareController(ShareService shareService) {
        this.shareService = shareService;
    }

    @Operation(summary = "공유 링크 조회", description = """
            공유된 동작 1건과 캐릭터 이름, 공유 시각을 반환한다. 인증이 필요 없다.

            응답에 포함되는 항목이 외부에 노출되는 전부이며 계정 정보·다른 동작·진행 상황은
            포함하지 않는다.

            존재하지 않는 토큰은 404(ZZAL_SHARE_NOT_FOUND)를 반환한다. 조회 권한이 없는 경우와
            구분하지 않는데, 구분하면 토큰 추측에 단서를 제공하기 때문이다.""")
    @GetMapping("/{token}")
    public ApiResponse<ShareResponses.Public> open(@PathVariable String token) {
        return ApiResponse.ok(shareService.open(token));
    }
}
