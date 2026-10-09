package com.lore.zzal.chat.persona;

import com.lore.zzal.pet.Personality;

import java.util.List;

/**
 * 캐릭터 한 마리의 페르소나 시트 — 시스템 메시지 [너에 대해] 의 재료. 칸은 전부 "작가가 정한 것" 이다.
 *
 * <h3>★ v2 에서 칸이 늘어나는 자리</h3>
 * 예시 대사·금기 화제·안 쓰는 표현(작가가 길들인 것)이 여기 칸으로 붙는다. 빈 칸은 줄째 빠지므로 v1 지시문은 그대로다.
 * ★ 이 record 가 같으면 시스템 메시지도 같다 — {@code SystemPromptCache} 가 이 값으로 캐시를 가른다
 *   (호칭이 저장되면 시트가 바뀌어 시스템 메시지가 다시 만들어진다).
 *
 * @param name           이름
 * @param personalities  고른 성격 전부(맨 앞이 대표). 비면 성격 줄이 빠진다
 * @param tone           말투. 없으면 null("반말, 짧게")
 * @param world          세계관 원문(칩+글). 없으면 null
 * @param note           그 밖에(작가 메모) 원문. 없으면 null
 * @param appearance     외형(정체성 문단 가운데). 못 쓰면 null
 * @param callMe         아이가 사용자를 부르는 말. 모르면 null
 * @param callMeDeclined 사용자가 "이름 없이" 를 골랐다 — 부르지 않지만 다시 묻지도 않는다
 */
public record PersonaSheet(
        String name,
        List<Personality> personalities,
        String tone,
        String world,
        String note,
        String appearance,
        String callMe,
        boolean callMeDeclined) {

    /** 대표 성격. 안 골랐으면 null(폴백 문형의 "성격 없음"). */
    public Personality leadOrNull() {
        return personalities == null || personalities.isEmpty() ? null : personalities.getFirst();
    }

    /** 호칭 질문에 답이 있는가(저장됐거나 사양했다). */
    public boolean callMeSettled() {
        return callMe != null || callMeDeclined;
    }
}
