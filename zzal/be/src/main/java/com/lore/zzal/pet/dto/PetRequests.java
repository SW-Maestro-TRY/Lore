package com.lore.zzal.pet.dto;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 펫 API 가 받는 것들(api-v2.md 1절). */
public final class PetRequests {

    private PetRequests() {
    }

    @Schema(description = "그림 등록 — 이것 하나로 캐릭터 시트 굽기가 시작된다")
    public record Draft(

            @Schema(description = "업로드한 그림의 S3 key. presign 으로 발급받은 것이어야 한다",
                    example = "images/zzal/a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @NotBlank @Size(max = 300) String imageKey) {
    }

    /**
     * 캐릭터 정보. <b>이름 말고는 전부 선택</b>이다.
     *
     * ★ 그림 생성에 들어가는 것은 {@code note} 뿐이다(정본 1.6). 성격·말투·장르·세계관은
     *   <b>대사 톤에만</b> 쓰인다.
     */
    @Schema(description = "캐릭터 정보 — 이것을 보내면 격자 생성이 시작된다")
    public record Character(

            @Schema(description = "펫 이름. 12자(정본 15장)", example = "여울")
            @NotBlank @Size(max = ZzalRules.NAME_MAX_CHARS) String name,

            @Schema(description = "성격. 대사 톤에 쓰인다", example = "LIVELY")
            Personality personality,

            @Schema(description = "세계관·설정. 자유 입력", example = "비 오는 도시의 탐정")
            @Size(max = 100) String world,

            @Schema(description = "그 밖에 알려 주고 싶은 것. ★ 이것만 그림 생성에 참고된다",
                    example = "왼쪽 눈에 흉터")
            @Size(max = 200) String note) {
    }

    @Schema(description = "돌봄 요청 — 무엇을 눌렀는지만 보낸다. 수치가 얼마나 오르는지는 서버가 정한다")
    public record Care(

            @Schema(description = "FEED(밥) · SNACK(간식) · PET(쓰다듬기) · CLEAN(청소) · BATH(목욕) · MEDICINE(약)",
                    example = "FEED")
            @NotNull CareAction action) {
    }

    @Schema(description = "성격 고르기 — 12장 12분에 캐릭터가 묻는다. 언제든 바꾼다")
    public record PersonalityChoice(

            @Schema(description = "GENTLE(온순) · LIVELY(활발) · SHY(수줍음) · CLINGY(응석) · COOL(시크)", example = "LIVELY")
            @NotNull Personality personality,

            @Schema(description = "세계관 한 줄. 비워도 된다", example = "구름 위 마을에 사는 고양이")
            @Size(max = ZzalRules.WORLD_MAX_CHARS) String world) {
    }

    @Schema(description = "배경 바꾸기 — 프론트 배경 16종 key. 서버는 값을 검증하지 않는다(해석 6)")
    public record Background(

            @Schema(example = "window_day")
            @NotBlank @Size(max = 32) String background) {
    }

    @Schema(description = "다운로드·공유 — 서버는 횟수만 센다")
    public record Share(

            @Schema(description = "열린 동작의 key", example = "base")
            @NotBlank @Size(max = 32) String motionKey,

            @Schema(description = "DOWNLOAD · SHARE", example = "DOWNLOAD")
            @NotNull ShareKind kind) {

        public enum ShareKind { DOWNLOAD, SHARE }
    }

    /**
     * 떠남 켜기·끄기(정본 9장 "설정에서 떠남 끄기 가능").
     *
     * ★★ 이 스위치가 있는 이유 — 떠남은 이야기지만 <b>누군가에게는 상처</b>다(자캐 커뮤니티 규범).
     *   끄면 예고도 여행도 없고, 우리가 다시 켜라고 설득하지 않는다.
     */
    @Schema(description = "펫 설정")
    public record Settings(

            @Schema(description = "떠남을 켤 것인가. 끄면 예고 중이던 것도 즉시 사라진다", example = "true")
            @NotNull Boolean leaveEnabled) {
    }
}
