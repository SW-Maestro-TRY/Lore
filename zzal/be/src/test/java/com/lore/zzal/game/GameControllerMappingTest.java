package com.lore.zzal.game;

import com.lore.zzal.game.dto.GameRequests;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 달리기를 끝낼 주소가 실제로 열려 있는가(P-1).
 *
 * <h3>★ 왜 반사(reflection)로 보나</h3>
 * {@code GameService.finish} 에는 시험이 넉넉히 붙어 있었는데 <b>그것을 부르는 주소가 없었다.</b>
 * 서비스를 직접 부르는 시험은 그 구멍을 원리적으로 못 본다 — 주소가 없어도 초록이기 때문이다.
 * 그래서 매핑 자체를 확인하고, 찾은 메서드를 <b>그 매핑으로 불러</b> 서비스까지 이어지는지 본다.
 * 메서드 이름을 직접 적지 않으므로 이 시험은 고치기 전 코드에서도 컴파일되고, 그때는 빨갛다.
 */
@DisplayName("미니게임 HTTP 계약 — 달리기 종료 주소")
class GameControllerMappingTest {

    private static final String PATH = "/{gameId}/finish";

    @Test
    @DisplayName("★ POST /{gameId}/finish 매핑이 있고 GameService.finish 로 이어진다")
    void finishIsMappedAndDelegates() throws Exception {
        Method mapped = Arrays.stream(GameController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(PostMapping.class))
                .filter(m -> Arrays.asList(m.getAnnotation(PostMapping.class).value()).contains(PATH))
                .findFirst()
                .orElse(null);
        assertThat(mapped).as("POST %s 매핑이 없다 — 달리기를 시작하면 끝낼 길이 없다", PATH).isNotNull();

        GameService gameService = mock(GameService.class);
        PetService petService = mock(PetService.class);
        ZzalPet pet = mock(ZzalPet.class);
        ZzalGame game = ZzalGame.start(1L, 7L, GameKind.RUN, "", Instant.now());
        when(petService.get(anyLong(), anyLong())).thenReturn(pet);
        when(gameService.remainingToday(any())).thenReturn(2);
        when(gameService.finish(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(new GameService.RunResult(game, true, List.of(), true));

        GameController controller = new GameController(gameService, petService);
        Object[] args = argsFor(mapped);
        assertThat(mapped.invoke(controller, args)).isNotNull();

        // 화면이 보낸 생존 시간이 그대로 서비스까지 간다
        verify(gameService).finish(eq(1L), eq(7L), eq(12L), eq(30_000L), any());
    }

    /** 매핑된 메서드의 인자 자리를 타입으로 채운다 — 순서가 바뀌어도 이 시험은 계속 돈다. */
    private static Object[] argsFor(Method mapped) {
        Class<?>[] types = mapped.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean petIdFilled = false;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == GameRequests.Finish.class) {
                args[i] = new GameRequests.Finish(30_000L);
            } else if (types[i] == Long.class) {
                // userId · petId · gameId 순 (@LoginUser · @PathVariable 둘)
                args[i] = i == 0 ? 1L : (petIdFilled ? 12L : 7L);
                petIdFilled = petIdFilled || i > 0;
            } else {
                args[i] = Optional.empty();
            }
        }
        return args;
    }
}
