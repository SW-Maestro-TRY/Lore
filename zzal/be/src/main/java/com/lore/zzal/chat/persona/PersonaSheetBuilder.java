package com.lore.zzal.chat.persona;

import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.profile.ZzalUserProfile;
import com.lore.zzal.profile.ZzalUserProfileRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 펫 + 사용자 프로필 → {@link PersonaSheet}. 재료를 읽는 곳은 여기 한 곳이다.
 *
 * <h3>★ 외형 문단을 손질한다</h3>
 * {@code identity_text} 는 그림 생성용 영어 지시문이라 앞머리("…the ONLY character identity/style reference.
 * Preserve exactly the same…")와 꼬리("Ignore the sheet's text…")가 붙어 있다(운영 51마리 중 50).
 * 가운데 외형만 남긴다. 외형이 아닌 문단(모델이 되묻는 문장 — "Please upload…")은 통째로 뺀다.
 */
@Component
public class PersonaSheetBuilder {

    /** 외형으로 넘기는 최대 글자 수. 운영 평균 668자 — 안내문을 걷으면 대개 이 안이다. */
    static final int APPEARANCE_MAX = 600;

    private static final Pattern HEAD = Pattern.compile(
            "^(Input image 1:\\s*)?the (ONLY|only|sole) (character )?identity/style reference\\.\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD2 = Pattern.compile(
            "^(Preserve|Reproduce)( and reproduce|/Reproduce|/reproduce)? exactly the same\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAIL = Pattern.compile("[;.]\\s*Ignore (all|the sheet).*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 외형이 아니라 모델의 되묻기·사과인 문단의 표지. */
    private static final List<String> NOT_APPEARANCE = List.of(
            "please upload", "please paste", "i'll then", "i can't", "i cannot", "i'm sorry", "i am sorry");

    /** 호칭으로 쓰면 안 되는 답 — 설문의 "이름 없이" 칩. */
    private static final Set<String> NO_CALL = Set.of("이름 없이", "이름없이");

    @Nullable
    private final ZzalUserProfileRepository profiles;

    public PersonaSheetBuilder(@Nullable ZzalUserProfileRepository profiles) {
        this.profiles = profiles;
    }

    public PersonaSheet build(ZzalPet pet) {
        String rawCall = null;
        if (profiles != null && pet.getUserId() != null) {
            rawCall = profiles.findById(pet.getUserId()).map(ZzalUserProfile::getCallMe).orElse(null);
        }
        String call = callMe(rawCall);
        return new PersonaSheet(
                pet.getName() == null ? "" : pet.getName().strip(),
                pet.getPersonalities(),
                blank(pet.getTone()),
                blank(pet.getGenre()),
                blank(pet.getWorld()),
                blank(pet.getNote()),
                call,
                call != null && call.contains("/"),
                appearance(pet.getIdentityText()));
    }

    /** 설문의 호칭 답 → 지시문에 넣을 호칭. "이름 없이"·빈칸이면 null(부르지 않고 말한다). */
    static String callMe(String raw) {
        String v = blank(raw);
        if (v == null || NO_CALL.contains(v)) {
            return null;
        }
        return v;
    }

    /** 정체성 문단 → 외형 메모. 외형이 아니면 null. */
    static String appearance(String identity) {
        String t = blank(identity);
        if (t == null) {
            return null;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (NOT_APPEARANCE.stream().anyMatch(lower::contains)) {
            return null;
        }
        t = HEAD.matcher(t).replaceFirst("");
        t = HEAD2.matcher(t).replaceFirst("");
        t = TAIL.matcher(t).replaceFirst("");
        t = t.strip();
        if (t.length() > APPEARANCE_MAX) {
            t = t.substring(0, APPEARANCE_MAX);
        }
        return t.isEmpty() ? null : t;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
