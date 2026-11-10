package com.lore.zzal.chat;

import com.lore.zzal.pet.AwakeClock;
import com.lore.zzal.pet.ZzalPet;

import java.time.LocalDate;

/**
 * 부화일로 찾는 판(연속 대화 {@code CONTINUOUS}·튜토리얼 부름 {@code BABY})의 {@code day_of} 열쇠.
 *
 * <h3>★★ 왜 펫 시간대가 아니라 KST 로 고정하나 (2026-10-26 시간대 2단계)</h3>
 * 이 날짜는 "그날" 이 아니라 <b>펫 한 마리의 판을 찾는 열쇠</b>다 — 연속 대화는 펫당 한 판이고 평생 이어진다.
 * 열쇠를 펫의 <b>지금</b> 시간대로 계산하면, 시간대를 처음 관측한 펫(NULL → America/Los_Angeles 등)은
 * 부화 시각의 날짜가 하루 당겨지는 경우가 많고(한국 00~16시 부화 = 미 서부 전날), 그 순간부터
 * 기존 판을 <b>영영</b> 못 찾는다(전환일 하루가 아니라 평생 — 대화가 처음부터 다시 시작된다).
 * 부화 시각은 바뀌지 않으므로 KST 로 고정하면 시간대와 상관없이 늘 같은 열쇠가 된다(지금까지 적힌 값과도 같다).
 */
public final class ChatSessionKeys {

    private ChatSessionKeys() {
    }

    /** 부화일(KST). 연속 대화·튜토리얼 부름 판의 열쇠. */
    public static LocalDate hatchDay(ZzalPet pet) {
        return AwakeClock.dateOf(pet.getHatchedAt());
    }
}
