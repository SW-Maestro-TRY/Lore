package com.lore.zzal.chat.persona;

import com.lore.zzal.pet.Personality;

import java.util.List;

/**
 * 캐릭터 한 마리의 페르소나 시트 — 대사를 만드는 재료 중 "이 아이가 누구인가".
 *
 * <h3>★ 칸은 전부 "작가가 정한 것" 이다</h3>
 * 이름·성격·말투·장르·세계관·그 밖에는 온보딩에서 사용자가 적은 값, 외형은 등록한 그림에서 뽑은 문단을
 * 손질한 것, 호칭은 사용자 설문의 답이다. 서비스가 지어낸 칸은 없다(자캐 규범 — 캐붕 방지).
 *
 * <h3>★ v2 에서 칸이 늘어나는 자리</h3>
 * 예시 대사·금기 화제·안 쓰는 표현(작가가 길들인 것)이 여기 칸으로 붙는다. 지시문 조립은
 * 비어 있는 칸을 건너뛰므로 칸이 늘어도 v1 지시문은 그대로다.
 *
 * @param name          이름(필수)
 * @param personalities 고른 성격 전부, 맨 앞이 대표. 비면 온순으로 본다
 * @param tone          말투(칩 + 직접 쓴 말). 없으면 null
 * @param genre         장르. 없으면 null
 * @param world         세계관. 없으면 null
 * @param note          그 밖에(작가 메모). 없으면 null
 * @param callMe        아이가 사용자를 부르는 호칭. 없거나 "부르지 말 것" 이면 null
 * @param callMeAmbiguous 호칭이 "언니/오빠" 처럼 둘 중 하나라 아직 못 정한 상태인가
 * @param appearance    외형(그림에서 읽은 영어 메모, 안내문 걷어 냄). 못 쓰면 null
 */
public record PersonaSheet(
        String name,
        List<Personality> personalities,
        String tone,
        String genre,
        String world,
        String note,
        String callMe,
        boolean callMeAmbiguous,
        String appearance) {

    /** 대표 성격. 안 골랐으면 온순(템플릿과 같은 규칙). */
    public Personality lead() {
        return personalities == null || personalities.isEmpty() ? Personality.GENTLE : personalities.getFirst();
    }
}
