package com.lore.zzal.chat.persona;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.profile.ZzalUserProfile;
import com.lore.zzal.profile.ZzalUserProfileRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 펫 + 사용자 프로필 → {@link PersonaSheet}. 재료를 읽는 곳은 여기 한 곳이다.
 *
 * ★ 외형({@code identity_text})은 읽지 않는다(#709) — 그림 생성용 영어 지시문이고, 대화 화면에는 움짤이 보인다.
 */
@Component
public class PersonaSheetBuilder {

    /** 호칭으로 쓰면 안 되는 답 — 설문의 "이름 없이" 칩. */
    private static final Set<String> NO_CALL = Set.of("이름 없이", "이름없이");

    @Nullable
    private final ZzalUserProfileRepository profiles;

    public PersonaSheetBuilder(@Nullable ZzalUserProfileRepository profiles) {
        this.profiles = profiles;
    }

    /**
     * 펫 + 프로필 → 시트.
     * ★ 호칭은 펫 칸({@code zzal_pet.call_me}, 대화에서 뽑은 것)이 먼저, 없으면 설문의 {@code callMe}.
     */
    public PersonaSheet build(ZzalPet pet) {
        String profileCall = null;
        if (profiles != null && pet.getUserId() != null) {
            profileCall = profiles.findById(pet.getUserId()).map(ZzalUserProfile::getCallMe).orElse(null);
        }
        String petCall = blank(pet.getCallMe());
        String call = petCall != null ? petCall : callMe(profileCall);
        boolean declined = petCall == null && blank(profileCall) != null && NO_CALL.contains(blank(profileCall));
        return new PersonaSheet(
                pet.getName() == null ? "" : pet.getName().strip(),
                pet.getPersonalities(),
                blank(pet.getTone()),
                blank(pet.getWorld()),
                blank(pet.getNote()),
                call,
                declined);
    }

    /**
     * 설문의 호칭 답 → 지시문에 넣을 호칭.
     * "이름 없이"·빈칸이면 null. "언니/오빠" 처럼 둘 중 하나인 칩도 null — 어느 쪽인지 대화에서 다시 묻는다.
     */
    static String callMe(String raw) {
        String v = blank(raw);
        if (v == null || NO_CALL.contains(v) || v.contains("/")) {
            return null;
        }
        return v;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
