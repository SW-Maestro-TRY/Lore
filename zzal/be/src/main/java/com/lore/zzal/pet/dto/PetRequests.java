package com.lore.zzal.pet.dto;

import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 펫 API 가 받는 것들(api-v2.md 1절). */
public final class PetRequests {

    private PetRequests() {
    }

    @Schema(description = "이미지 등록 요청. 이 요청으로 캐릭터 시트 생성이 시작된다")
    public record Draft(

            @Schema(description = "업로드한 그림의 S3 key. presign API 로 발급받은 미사용 키여야 한다",
                    example = "images/zzal/a1b2c3d4-e5f6-7890-abcd-ef1234567890")
            @NotBlank @Size(max = 300) String imageKey) {
    }

    /**
     * 캐릭터 정보. <b>이름 말고는 전부 선택</b>이다.
     *
     * ★ 그림 생성에 들어가는 것은 {@code note} 뿐이다(정본 1.6). 성격·말투·장르·세계관은
     *   <b>대사 톤에만</b> 쓰인다.
     */
    @Schema(description = "캐릭터 정보 등록 요청. 이름 외 항목은 선택이다")
    public record Character(

            @Schema(description = "캐릭터 이름. 최대 12자", example = "여울")
            @NotBlank @Size(max = ZzalRules.NAME_MAX_CHARS) String name,

            @Schema(description = "성격 하나. personalities 를 보내면 그쪽이 이긴다", example = "LIVELY")
            Personality personality,

            @Schema(description = "고른 성격 전부. 맨 앞이 대표이고 대사 톤은 대표만 본다",
                    example = "[\"LIVELY\", \"SHY\"]")
            List<Personality> personalities,

            @Schema(description = "세계관·설정. 자유 입력이며 대사 생성에만 사용한다", example = "비 오는 도시의 탐정")
            @Size(max = 100) String world,

            @Schema(description = "추가 정보. 저장만 하고 나중에 채팅에서 쓴다", example = "왼쪽 눈에 흉터")
            @Size(max = 200) String note) {

        /** 고른 성격 전부. 맨 앞이 대표. 아무것도 안 골랐으면 빈 목록. */
        public List<Personality> picked() {
            return Picks.of(personality, personalities);
        }
    }

    @Schema(description = "돌보기 요청. 수행할 행동만 전달하고 수치 변화는 서버가 결정한다")
    public record Care(

            @Schema(description = "FEED(급식) · SNACK(간식) · PET(쓰다듬기) · CLEAN(청소) · BATH(목욕) · MEDICINE(투약)",
                    example = "FEED")
            @NotNull CareAction action) {
    }

    /**
     * 성격 등록·수정. 수면 중을 포함해 언제든 바꿀 수 있다(정본 0장 6).
     *
     * <h3>★ 칸이 둘인 이유 (1.9)</h3>
     * 여러 개를 받기로 했지만({@code personalities}) 화면은 아직 하나만 보낸다({@code personality}).
     * 둘 다 받아 두어 <b>화면이 한 줄만 고치면 넘어오게</b> 한다.
     * 화면이 넘어오면 {@code personality} 를 지운다 — 그때까지 남겨 둔 다리다.
     */
    @Schema(description = "성격 등록·수정 요청. 수면 중을 포함해 언제든 변경할 수 있다")
    public record PersonalityChoice(

            @Schema(description = "성격 하나. personalities 를 보내면 그쪽이 이긴다", example = "LIVELY")
            Personality personality,

            @Schema(description = "고른 성격 전부. 맨 앞이 대표이고 대사 톤은 대표만 본다. "
                    + "GENTLE(온순) · LIVELY(활발) · SHY(수줍음) · CLINGY(응석) · COOL(시크)",
                    example = "[\"LIVELY\", \"SHY\"]")
            List<Personality> personalities,

            @Schema(description = "세계관 한 줄. 생략할 수 있다", example = "구름 위 마을에 사는 고양이")
            @Size(max = ZzalRules.WORLD_MAX_CHARS) String world) {

        /** 고른 성격 전부. 맨 앞이 대표. 하나도 없으면 빈 목록 — 서비스가 막는다. */
        public List<Personality> picked() {
            return Picks.of(personality, personalities);
        }
    }

    /**
     * 성격 칸 둘을 하나로 읽는다.
     *
     * ★ 한 곳에 둔 이유 — 두 요청이 같은 규칙을 쓰는데 각자 적으면 한쪽만 고쳐도 아무 오류가 안 난다.
     */
    private static final class Picks {

        private Picks() {
        }

        static List<Personality> of(Personality single, List<Personality> many) {
            if (many != null && !many.isEmpty()) {
                return many.stream().filter(java.util.Objects::nonNull).toList();
            }
            return single == null ? List.of() : List.of(single);
        }
    }

    @Schema(description = "배경 변경 요청. 배경 key 는 화면이 정의하며 서버는 값을 검증하지 않는다")
    public record Background(

            @Schema(example = "window_day")
            @NotBlank @Size(max = 32) String background) {
    }

    @Schema(description = "공유 링크 발급 요청")
    public record Share(

            @Schema(description = "해금된 동작의 key", example = "base")
            @NotBlank @Size(max = 32) String motionKey,

            @Schema(description = "DOWNLOAD(저장) · SHARE(공유). 집계 구분에만 사용한다", example = "DOWNLOAD")
            @NotNull ShareKind kind) {

        public enum ShareKind { DOWNLOAD, SHARE }
    }

    /**
     * 떠남 켜기·끄기(정본 9장 "설정에서 떠남 끄기 가능").
     *
     * ★★ 이 스위치가 있는 이유 — 떠남은 이야기지만 <b>누군가에게는 상처</b>다(자캐 커뮤니티 규범).
     *   끄면 예고도 여행도 없고, 우리가 다시 켜라고 설득하지 않는다.
     */
    @Schema(description = "캐릭터 설정")
    public record Settings(

            @Schema(description = "떠남 기능 사용 여부. 끄면 예고 상태도 즉시 해제된다", example = "true")
            @NotNull Boolean leaveEnabled) {
    }
}
