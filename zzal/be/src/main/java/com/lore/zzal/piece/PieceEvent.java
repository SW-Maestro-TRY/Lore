package com.lore.zzal.piece;

/**
 * 조각을 올릴 수 있는 행동(정본 6장). <b>성공한 행동만</b> 여기로 온다 —
 * 거절된 돌보기("배가 불러요" · "이미 깨끗해요" · "오늘은 목욕했어요")는 예외로 끝나므로 애초에 오지 않는다.
 *
 * <h3>★ 왜 조각이 아니라 행동으로 받나</h3>
 * 한 조각을 채우는 길이 둘이다(놀이 = 게임 <b>또는</b> 간식). 부르는 쪽이 "어느 조각인가" 를 정하면
 * 그 대응이 부르는 곳마다 흩어지고, 정본에서 길을 하나 더 열 때 고칠 자리가 늘어난다.
 * 부르는 쪽은 <b>무슨 일이 있었는지</b>만 말하고, 어느 칸이 오르는지는 여기 한 곳이 정한다.
 */
public enum PieceEvent {

    /** 밥을 먹였다 */
    FEED(PieceKind.FOOD),
    /** 간식을 줬다 — ★ 배탈이 난 그 간식(그날 5개째부터)은 오지 않는다(정본 1.9) */
    SNACK(PieceKind.PLAY),
    /** 미니게임 한 판을 시작했다(매치 기준, 승패 무관) */
    GAME(PieceKind.PLAY),
    /** 청소했다 */
    CLEAN(PieceKind.CLEAN),
    /** 목욕시켰다 */
    BATH(PieceKind.CLEAN),
    /** 쓰다듬었다 — ★ 하루 3회까지만 온다(친밀도와 같은 선) */
    PET(PieceKind.BOND),
    /** 부름에 답했다 */
    CHAT(PieceKind.BOND);

    private final PieceKind kind;

    PieceEvent(PieceKind kind) {
        this.kind = kind;
    }

    /** 이 행동이 올리는 칸. */
    public PieceKind kind() {
        return kind;
    }
}
