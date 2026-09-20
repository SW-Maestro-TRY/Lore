package com.lore.zzal.docs;

import com.lore.common.exception.BusinessException;
import com.lore.zzal.chat.ChatService;
import com.lore.zzal.game.GameService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거절당해도 시간이 되감기지 않는다 — 쓰는 트랜잭션 전부의 규약(P-5 · #225 리뷰 하-1).
 *
 * <h3>★★ 왜 반사(reflection)로 재나</h3>
 * 롤백은 <b>진짜 트랜잭션이 있어야만</b> 일어난다. 목(mock)으로 짠 시험은 롤백이 아예 안 일어나므로
 * {@code noRollbackFor} 를 지워도 <b>전부 초록이다</b> — 그래서 {@code GameService}·{@code ChatService} 에서
 * 이 어노테이션이 빠진 채로 아무도 몰랐다. 통합 하네스는 DB 가 있어야 돌아 늘 켜 두지 못한다.
 * 그래서 "무엇이 붙어 있어야 하나" 를 계약으로 못 박아, DB 없이도 빠지는 순간 빨개지게 한다.
 *
 * <h3>왜 이 둘인가 — 본은 {@code PetService} 다</h3>
 * {@code PetService} 의 <b>돌보기 12개</b>가 이미 이 규약을 달고 있다(388줄 주석이 이유를 못 박았다).
 * {@code GameService}·{@code ChatService} 도 같은 모양이다 — {@code petService.awake}/{@code alive} 를
 * 부르고, 그 안의 {@code touch()} 가 <b>정산·장면 저장·엽서·도착</b>을 끝낸 뒤에 "할 수 있나" 를 묻는다.
 * 그런데 이 둘만 맨 {@code @Transactional} 이었다.
 *
 * ★ {@code PetService} 를 통째로 넣지 않은 이유 — 거기에는 사용자 시간과 무관한 쓰기도 있다
 * ({@code draft}·{@code character}·개발 시계·관리자 {@code refreshAll}). 그쪽은 거절되면 되돌아가는 것이 맞고,
 * 어느 것을 넣을지는 이 수정의 범위 밖이다. 여기서는 <b>규약이 깨진 두 곳</b>만 못 박는다.
 */
@DisplayName("쓰는 트랜잭션은 거절에 되감기지 않는다")
class NoRollbackContractTest {

    @Test
    @DisplayName("★★ Game·Chat 의 쓰는 @Transactional 은 전부 noRollbackFor = BusinessException")
    void writingTransactionsDeclareNoRollback() {
        for (Class<?> type : List.of(GameService.class, ChatService.class)) {
            List<String> missing = writingTransactions(type).stream()
                    .filter(m -> !declaresNoRollback(m.getAnnotation(Transactional.class)))
                    .map(m -> type.getSimpleName() + "." + m.getName())
                    .sorted()
                    .toList();
            assertThat(missing)
                    .as("거절 한 번에 정산이 되감긴다 — noRollbackFor 가 빠진 자리")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("두 클래스 모두 실제로 쓰는 트랜잭션을 들고 있다 — 규약이 빈 목록을 훑고 통과하지 않게")
    void eachServiceActuallyHasWritingTransactions() {
        for (Class<?> type : List.of(GameService.class, ChatService.class)) {
            assertThat(writingTransactions(type)).as("%s", type.getSimpleName()).isNotEmpty();
        }
    }

    /** 읽기 전용이 아닌 {@code @Transactional} public 메서드. 읽기 전용은 되돌릴 것이 없다. */
    private static List<Method> writingTransactions(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .filter(m -> !m.getAnnotation(Transactional.class).readOnly())
                .toList();
    }

    private static boolean declaresNoRollback(Transactional tx) {
        return Arrays.asList(tx.noRollbackFor()).contains(BusinessException.class)
                || Arrays.asList(tx.noRollbackForClassName()).contains(BusinessException.class.getName());
    }
}
