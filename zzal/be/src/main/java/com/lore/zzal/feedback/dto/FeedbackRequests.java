package com.lore.zzal.feedback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 후기 API 가 받는 것들. */
public final class FeedbackRequests {

    private FeedbackRequests() {
    }

    /**
     * 고를 수 있는 칩.
     *
     * <h3>★ 왜 자유 문자열이 아니라 정해진 값인가</h3>
     * 자유 문자열이면 같은 뜻이 "움직임이 어색"·"동작 이상함"·"어색해요" 로 갈라져
     * <b>세는 순간 쓸모가 없어진다.</b> 칩은 세려고 두는 것이고, 못 세는 칩은 자유 글과 다르지 않다.
     * 정해진 값이라 없는 값이 오면 400 으로 되돌아간다(CareAction 과 같은 방식).
     *
     * <h3>★ 좋다·아쉽다를 값 안에 담는다</h3>
     * 구 랜딩의 칩은 {@code 그림체 · 대사 · 컷 구성 · 속도} 처럼 <b>주제만</b> 있었다. 그러면
     * "그림체" 를 고른 사람이 칭찬한 것인지 불만인지 알 수 없고, 별점으로 짐작하는 수밖에 없다.
     * 여기서는 한 칩이 곧 한 문장이라 세면 그대로 답이 된다.
     *
     * <h3>★ 왜 이 여섯인가</h3>
     * 지금 서비스가 파는 것이 <b>내 그림이 그대로 움직이는 것</b>이고, 우리가 아직 모르는 것도
     * 거기에 몰려 있다. 그래서 (1) 그림이 보존됐는가 (2) 움직임이 자연스러운가 두 축을
     * 좋다·아쉽다 양쪽으로 두고, 나머지 둘은 기다림과 다음 요구를 받는다.
     * 구 랜딩의 {@code 대사}·{@code 컷 구성} 은 웹툰의 칸이라 여기에 없다.
     */
    public enum Tag {

        /** 내 그림 그대로예요. 자캐를 맡기는 사람이 가장 먼저 확인하는 것. */
        LOOKS_SAME,

        /** 캐릭터가 안 닮았어요. 구 랜딩의 "캐릭터 안 닮음" 과 같은 자리. */
        LOOKS_OFF,

        /** 움직임이 자연스러워요. */
        MOTION_GOOD,

        /** 움직임이 어색해요. */
        MOTION_ODD,

        /** 기다리는 시간이 길어요. 구 랜딩의 "속도". */
        TOO_SLOW,

        /** 동작이 더 다양했으면. 구 랜딩의 "더 다양하게". */
        WANT_MORE
    }

    /**
     * 후기 한 번.
     *
     * <h3>★ 이메일 칸이 없다</h3>
     * 가입할 때 이미 받았다. 같은 정보를 두 곳에 두면 지켜야 할 곳이 하나 더 늘고,
     * 파기 시점도 따로 관리해야 한다. 구 랜딩(CharacterCreator.tsx)에 이메일 칸이 있는 것은
     * <b>로그인이 없던 시절</b>의 화면이라 그 자리 말고는 사람을 다시 찾을 길이 없었기 때문이다.
     */
    @Schema(description = "후기 — 별점은 필수, 칩과 자유 글은 선택")
    public record Submit(

            @Schema(description = "별점 1~5", example = "4")
            @Min(1) @Max(5) int rating,

            @Schema(description = "고른 칩들. 없어도 된다. 정해진 값만 받는다",
                    example = "[\"LOOKS_SAME\", \"MOTION_ODD\"]")
            List<Tag> tags,

            /*
             * ★ 길이를 막아 두는 이유 — 저장되는 칸이 text 라 길이 제한이 없다. 막지 않으면
             *   한 번의 호출로 수 MB 가 그대로 들어가고, 그건 화면에서 실수로 붙여 넣기만 해도 일어난다.
             *   500 자는 "한 화면에 읽히는 분량" 이고, 화면의 글자 수 표시와 같은 값이다.
             */
            @Schema(description = "자유롭게 쓴 말. 없어도 된다", example = "움직임이 생각보다 자연스러웠어요")
            @Size(max = 500) String text) {
    }
}
