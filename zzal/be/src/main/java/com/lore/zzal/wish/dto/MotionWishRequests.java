package com.lore.zzal.wish.dto;

import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 동작 요청 API 가 받는 것. */
public final class MotionWishRequests {

    private MotionWishRequests() {
    }

    /**
     * 보고 싶은 동작 한 줄.
     *
     * <h3>★ 칩이 아니라 자유 글인 이유</h3>
     * 후기의 칩은 <b>세려고</b> 두는 것이라 값이 미리 정해져 있어야 했다. 여기서 알고 싶은 것은
     * 반대다 — <b>우리가 아직 목록에 없는 동작</b>이 무엇인지다. 정해진 값만 받으면 그 질문에
     * 영영 답할 수 없다. 대신 세는 일은 이 글이 아니라 "몇 명이 남겼나"(행동 기록)가 맡는다.
     *
     * <h3>★ 공백뿐인 글은 안 쓴 것이다</h3>
     * {@code @NotBlank} 가 받는 자리에서 막는다. 안 막으면 {@code ""} 와 {@code "   "} 이
     * 섞여 저장되고, "몇 명이 무엇을 바랐나" 를 볼 때마다 세는 조건이 달라진다.
     */
    // ★★ 문서에 쓸 이름을 직접 준다 — 후기에도 {@code Submit} 이라는 record 가 있어서, 이름을 안 주면
    //   OpenAPI 문서에서 <b>한쪽이 다른 쪽을 덮는다</b>(문서의 스키마 이름은 클래스의 짧은 이름이다).
    //   컴파일도 되고 서버도 뜨고 스웨거도 열리는데 문서에 적힌 모양만 남의 것이 되어, 그 문서로
    //   만든 프론트 타입을 믿은 쪽이 뒤늦게 헤맨다. 실제로 이름을 안 줬을 때 후기의 별점·칩이
    //   명세에서 사라지고 이 record 의 칸으로 바뀌었다(스냅샷 실측).
    @Schema(name = "MotionWishSubmit",
            description = "보고 싶은 동작 한 줄 — 앞뒤 공백은 서버가 떼고, 공백뿐이면 거절한다")
    public record Submit(

            // ★ 애노테이션 값은 컴파일 상수여야 한다 — formatted() 는 못 쓰고, 상수 이어 붙이기는 된다.
            //   그래서 상한이 바뀌면 설명도 자동으로 따라간다(손으로 적은 숫자가 낡지 않는다).
            @Schema(description = "보고 싶은 동작. 1~" + ZzalRules.MOTION_WISH_MAX_CHARS + "자",
                    example = "기지개 켜는 모습이 보고 싶어요")
            @NotBlank @Size(max = ZzalRules.MOTION_WISH_MAX_CHARS) String text) {
    }
}
