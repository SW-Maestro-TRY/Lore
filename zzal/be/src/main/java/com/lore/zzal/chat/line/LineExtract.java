package com.lore.zzal.chat.line;

/**
 * 모델이 대사와 함께 돌려주는 추출 칸(#709) — 대사가 아니라 <b>상대 말에서 읽은 것</b>.
 *
 * @param callMe    상대 말에서 읽은 호칭·이름(없으면 null). 코드 패턴({@code CallMeExtractor})과 함께 본다
 * @param userSaid  이번에 답한 질문 항목에 대한 상대 답의 요지(없으면 null). v2 기억의 재료로 턴 행에 남긴다
 * @param askedBack 상대가 되물었나(코드 정규식과 OR 해 턴 행에 남긴다)
 */
public record LineExtract(String callMe, String userSaid, boolean askedBack) {

    public static final LineExtract NONE = new LineExtract(null, null, false);
}
