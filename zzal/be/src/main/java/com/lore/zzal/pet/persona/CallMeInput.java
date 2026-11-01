package com.lore.zzal.pet.persona;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;

/**
 * 사용자가 <b>직접 적은</b> 호칭의 모양 검사. 지금 부르는 곳은 설정 편집({@code PetService.editPersona}) 하나다.
 * 첫 만남 호칭(SET_ADDRESS)은 이번 범위에서 일부러 넣지 않았다(2026-11-01 결정).
 *
 * <h3>★★ 왜 막나 — 직접 적은 호칭은 거르지 않고 그대로 부른다</h3>
 * 채팅 시트({@code PersonaSheetBuilder})는 "언니/오빠"·"이름 없이"를 <b>설문 쪽 호칭</b>에서만 "호칭 아님"으로
 * 거른다. 펫 칸 호칭(EXPLICIT)은 그대로 쓰므로, 여기서 받아 두면 캐릭터가 "언니/오빠"를 글자 그대로 부른다.
 * 이름 없이 부르길 원하면 건너뛰기(DEFERRED)가 그 길이다.
 *
 * ★ 채팅 중 정정 경로({@code ChatService})는 여기를 부르지 않는다 — 예외가 채팅 턴을 깨운다.
 * ★ 빈칸·길이 검사는 각 경로가 이미 한다. 여기는 그 다음에 부른다.
 */
public final class CallMeInput {

    private CallMeInput() {
    }

    /** "/" 가 들어 있거나 "이름 없이"(띄어쓰기 무관)면 400 {@code INVALID_INPUT}. null 은 통과(검사할 값이 없다). */
    public static void requireSingle(String callMe) {
        if (callMe == null) return;
        String v = callMe.strip();
        if (v.contains("/"))
            throw new BusinessException(ErrorCode.INVALID_INPUT, "한 가지만 적어 주세요. 예: 언니");
        if (v.replaceAll("\\s+", "").equals("이름없이"))
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "부르는 이름을 적어 주세요. 이름 없이 부르길 원하면 상세 설정에서 건너뛰기를 골라 주세요.");
    }
}
