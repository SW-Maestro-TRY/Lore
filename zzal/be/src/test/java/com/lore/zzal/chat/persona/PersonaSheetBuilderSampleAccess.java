package com.lore.zzal.chat.persona;

/** 시험 패키지 밖(샘플 뽑기)에서 외형 손질을 같은 함수로 쓰려고 연다. */
public final class PersonaSheetBuilderSampleAccess {

    private PersonaSheetBuilderSampleAccess() {
    }

    public static String appearance(String identity) {
        return PersonaSheetBuilder.appearance(identity);
    }
}
