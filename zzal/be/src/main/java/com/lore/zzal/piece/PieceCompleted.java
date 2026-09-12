package com.lore.zzal.piece;

/**
 * 조각 네 칸이 <b>방금 다 찼다</b> — 굽기를 시작할 순간이다(정본 1.8).
 *
 * <h3>★ 왜 곧바로 안 부르고 알림을 띄우나</h3>
 * 두 가지 때문이다.
 * <ul>
 *   <li><b>돌보기가 롤백되면 굽지 않아야 한다.</b> 커밋 전에 굽기를 시작하면
 *       "밥을 안 줬는데 돈은 나간" 상태가 생긴다</li>
 *   <li><b>고리를 끊는다.</b> 굽기 계획({@code NightPlanner})은 조각을 읽어야 하므로
 *       조각이 굽기를 직접 부르면 서로를 참조하게 된다</li>
 * </ul>
 */
public record PieceCompleted(Long petId) {
}
