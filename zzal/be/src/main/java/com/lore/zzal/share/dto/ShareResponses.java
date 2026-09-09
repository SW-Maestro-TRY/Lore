package com.lore.zzal.share.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public final class ShareResponses {

    private ShareResponses() {
    }

    /** 링크를 발급한 결과. 같은 동작을 다시 공유하면 <b>있던 것을 그대로</b> 준다. */
    @Schema(description = "공유 링크")
    public record Issued(

            @Schema(description = "주소에 실리는 값", example = "9xQm2Rk7pLvN0aBcDeFgHi")
            String token,

            @Schema(description = "그대로 붙여넣어 쓰는 전체 주소", example = "https://lorecomic.com/zzal/s/9xQm2Rk7pLvN0aBcDeFgHi")
            String url) {
    }

    /**
     * 로그인 없이 열리는 화면에 들어가는 것.
     *
     * ★ 여기에 담기는 것이 곧 <b>남에게 보이는 전부</b>다. 계정·이메일·다른 동작은 담지 않는다.
     */
    @Schema(description = "공유 링크로 보이는 것")
    public record Public(

            @Schema(description = "아이 이름", example = "이두나")
            String petName,

            @Schema(description = "무슨 동작인가", example = "손 흔들며 인사")
            String motionLabel,

            @Schema(description = "움짤 주소", example = "images/zzal/pets/12/basic/wave.webp")
            String imageKey,

            @Schema(description = "언제 공유됐나")
            Instant sharedAt) {
    }
}
