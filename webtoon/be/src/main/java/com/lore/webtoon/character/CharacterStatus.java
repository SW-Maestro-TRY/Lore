package com.lore.webtoon.character;

/**
 * 캐릭터가 지금 어떤 상태인가.
 *
 * <b>그리는 데 1분쯤 걸린다.</b> 그동안 요청을 붙들고 있으면 배포에서 끊긴다 —
 * CloudFront 의 기본 응답 대기가 30초이고, 그 앞뒤로도 60초짜리 연결을 반겨
 * 주는 자리가 없다(로컬 개발 프록시에서 먼저 {@code socket hang up} 으로
 * 드러났다). 그래서 만들기는 곧바로 돌려주고, 그림은 뒤에서 그린다.
 */
public enum CharacterStatus {
    /** 만들어졌고 그리는 중. 화면은 이 자리를 「그리는 중」으로 보여준다. */
    DRAWING,
    /** 그림까지 다 됐다. */
    READY,
    /** 못 그렸다. 사유는 {@code error} 에 한글 한 줄로. */
    ERROR,
}
