package com.lore.zzal.dev;

import com.lore.zzal.pet.dto.PetResponses;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 개발 리모컨이 돌려주는 것들. */
public final class DevResponses {

    private DevResponses() {
    }

    /** 리모컨을 그려도 되는가 — 이 주소가 200 이면 dev 도구가 켜진 서버다(꺼진 서버는 404). */
    @Schema(description = "개발 도구 켜짐 확인. 꺼진 서버에는 이 주소가 없다(404)")
    public record DevPing(
            @Schema(description = "늘 true(꺼져 있으면 응답 자체가 없다)") boolean devTools,
            @Schema(description = "진짜 그림 생성 스위치(ZZAL_GENERATION_REAL). false 면 진짜 굽기를 거절한다")
            boolean generationReal) {
    }

    /**
     * 리모컨 조작 한 번의 결과.
     *
     * ★ {@code after} 를 화면에 그대로 얹는다 — 다시 조회(GET /me/pets/{id})하면 그 조회가 <b>방문</b>으로 처리돼
     *   방금 켠 떠남 예고가 그 자리에서 취소된다(운영 규칙 "예고 중 접속 = 즉시 취소"). 응답은 방문 없이 정산한 서버 상태다.
     */
    @Schema(description = "리모컨 조작 결과 — 이번에 일어난 일 · 바꾸기 전과 뒤의 캐릭터 상태 · 내부 칸 한 벌")
    public record DevResult(
            @Schema(description = "이번에 일어난 일(바뀐 칸마다 한 줄, '배부름: 3 → 0'). 아무것도 안 바뀌면 빈 목록")
            List<String> effects,
            @Schema(description = "바꾸기 전 캐릭터 상태") PetResponses.Detail before,
            @Schema(description = "바꾼 뒤 캐릭터 상태(방문 없이 정산한 값)") PetResponses.Detail after,
            @Schema(description = "바꾼 뒤 내부 칸 한 벌(케어 미스·오늘 한도·평생 횟수·조각 칸·동작 행 등)")
            Map<String, Object> state) {
    }

    /** 내부 칸 한 벌만(읽기). */
    @Schema(description = "내부 칸 한 벌(리모컨 상태 패널)")
    public record DevState(Map<String, Object> state, PetResponses.Detail detail) {
    }

    /** 굽기 시작. 진행은 {@code GET .../bake/{jobId}} 로 본다. */
    @Schema(description = "굽기 시작 결과")
    public record DevBake(
            @Schema(description = "진행 조회 열쇠(hatch-{genJobId} · layer2-{초} · gift-{seq}-{초})", example = "hatch-12")
            String jobId,
            @Schema(description = "hatch · layer2 · gift") String kind,
            @Schema(description = "진짜 그림 생성으로 굽는가") boolean real,
            @Schema(description = "예상 비용(달러). 가짜면 0", example = "0.19") BigDecimal estimatedCostUsd,
            @Schema(description = "사람 말 한 줄") String note,
            @Schema(description = "시작 직후 상태") DevResult result) {
    }

    /** 굽기 진행. */
    @Schema(description = "굽기 진행")
    public record DevBakeProgress(
            String jobId,
            String kind,
            @Schema(description = "지금 상태(부화=phase · 2층=layer2Status · 선물=동작 status)") String status,
            @Schema(description = "끝난 단계 수(부화만)") Integer done,
            @Schema(description = "전체 단계 수(부화만)") Integer total,
            @Schema(description = "이번 굽기에서 지금까지 기록된 비용(달러)") BigDecimal costUsd,
            @Schema(description = "끝났나(성공·실패 모두)") boolean finished) {
    }
}
