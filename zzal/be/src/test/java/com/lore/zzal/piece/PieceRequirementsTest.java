package com.lore.zzal.piece;

import com.lore.zzal.PetFixture;
import com.lore.zzal.PieceFixture;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 조각을 올리는 입구 <b>일곱 전부</b> — N-1 · N · N+1(M-18).
 *
 * <h3>★ 왜 이 시험이 필요한가</h3>
 * 입구가 일곱인데 지금까지 "발행을 확인하는" 시험은 <b>선물 경로 하나뿐</b>이었다. 나머지는
 * {@code count()} 의 반환값만 보거나 엔티티 규칙만 본다. 그래서 여덟 번째 입구가 생기는 날,
 * <b>화면상 네 칸은 찼는데 굽기 트리거가 영영 안 오는</b> 모양이 또 나온다 — 오늘 실제로 그랬다.
 *
 * <h3>★ 왜 요구량을 상수에서 읽나</h3>
 * 시험에 숫자를 박으면 정본이 바뀔 때 <b>시험이 같이 틀린다.</b> 여기서 보려는 것은
 * "요구량이 얼마냐" 가 아니라 <b>"그 요구량에 정확히 닿는 그 한 번이 도장을 찍는가"</b> 다.
 */
@DisplayName("조각 입구 일곱 — 요구량에 닿는 그 한 번")
class PieceRequirementsTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant T0 = LocalDateTime.parse("2026-09-05T12:00").atZone(KST).toInstant();
    private static final Long PET_ID = 1L;

    private final Map<Long, ZzalPiece> store = new HashMap<>();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PieceService service = PieceFixture.inMemory(store, events);

    /** 3층이 열린 펫. */
    private ZzalPet tierThree() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0.minusSeconds(3600));
        pet.markAlive("images/zzal/sheet", "생김새", T0.minusSeconds(3600));
        pet.skipTutorial(T0.minusSeconds(1800));
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        pet.enablePieces(T0);
        return pet;
    }

    /** 정본이 정한 그 입구의 요구량. */
    private static int required(PieceEvent event) {
        return switch (event) {
            case FEED -> ZzalRules.PIECE_FEEDS;
            case SNACK -> ZzalRules.PIECE_SNACKS;
            case GAME -> ZzalRules.PIECE_GAMES;
            case CLEAN -> ZzalRules.PIECE_CLEANS;
            case BATH -> ZzalRules.PIECE_BATHS;
            case PET -> ZzalRules.PIECE_PETS;
            case CHAT -> ZzalRules.PIECE_CHATS;
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PieceEvent.class)
    @DisplayName("★★ N-1 회까지는 도장이 안 찍히고, N 회째에 그 칸만 찍히고, N+1 회째는 더 안 쌓인다")
    void everyEntranceStampsExactlyAtItsTarget(PieceEvent event) {
        ZzalPet pet = tierThree();
        int n = required(event);

        for (int i = 1; i < n; i++) {
            assertThat(service.count(pet, event)).as("%d 번째까지는 아직", i).isFalse();
        }
        ZzalPiece row = service.find(PET_ID);
        assertThat(row.isDone(event.kind())).as("N-1 회에서는 아직 안 찍힌다").isFalse();

        service.count(pet, event);
        assertThat(row.isDone(event.kind())).as("정확히 N 회째에 찍힌다").isTrue();

        // 그 칸 하나만 찍혔다 — 한 행동이 두 칸을 올리면 요구량이 반으로 준다.
        for (PieceKind kind : PieceKind.values()) {
            assertThat(row.isDone(kind))
                    .as("%s 는 %s 만 올린다", event, event.kind())
                    .isEqualTo(kind == event.kind());
        }

        // N+1 회째는 이미 찍힌 칸이라 더 안 쌓인다.
        service.count(pet, event);
        assertThat(row.countOf(event)).as("찍힌 칸은 더 안 센다").isZero();
    }

    @Test
    @DisplayName("★★ 네 칸을 다 채우는 그 한 번이 PieceCompleted 를 정확히 1회 발행한다")
    void completingPublishesExactlyOnce() {
        ZzalPet pet = tierThree();

        fill(pet, PieceEvent.FEED);
        fill(pet, PieceEvent.GAME);
        fill(pet, PieceEvent.CLEAN);
        verify(events, never()).publishEvent(any(PieceCompleted.class));

        // 마지막 칸(교감)의 마지막 한 번.
        int n = required(PieceEvent.PET);
        for (int i = 1; i < n; i++) {
            service.count(pet, PieceEvent.PET);
        }
        verify(events, never()).publishEvent(any(PieceCompleted.class));

        assertThat(service.count(pet, PieceEvent.PET)).isTrue();
        verify(events, times(1)).publishEvent(new PieceCompleted(PET_ID));

        // 그 뒤로 아무리 더 불러도 다시 알리지 않는다 — 같은 완성으로 두 번 구우면 돈이 두 배다.
        for (int i = 0; i < 10; i++) {
            service.count(pet, PieceEvent.PET);
            service.count(pet, PieceEvent.CHAT);
            service.count(pet, PieceEvent.FEED);
        }
        verify(events, times(1)).publishEvent(new PieceCompleted(PET_ID));
    }

    @Test
    @DisplayName("★ 3층 전에는 어느 입구도 안 센다 — 열린 날 한 칸이 공짜로 차 있으면 안 된다")
    void beforeTierThreeNoEntranceCounts() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", T0.minusSeconds(3600));
        pet.markAlive("images/zzal/sheet", "생김새", T0.minusSeconds(3600));
        pet.skipTutorial(T0.minusSeconds(1800));
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        assertThat(pet.isPiecesEnabled()).isFalse();

        for (PieceEvent event : PieceEvent.values()) {
            for (int i = 0; i < required(event) + 2; i++) {
                assertThat(service.count(pet, event)).isFalse();
            }
        }
        assertThat(service.find(PET_ID)).as("줄조차 안 만든다").isNull();
        verify(events, never()).publishEvent(any(PieceCompleted.class));
    }

    @Test
    @DisplayName("★ 길이 둘인 칸 — 게임과 간식을 섞으면 어느 한 길이 제 목표를 채워야 한다")
    void twoRoadsToOneStampDoNotAddUp() {
        ZzalPet pet = tierThree();
        int games = required(PieceEvent.GAME);
        int snacks = required(PieceEvent.SNACK);

        for (int i = 0; i < games - 1; i++) {
            service.count(pet, PieceEvent.GAME);
        }
        for (int i = 0; i < snacks - 1; i++) {
            service.count(pet, PieceEvent.SNACK);
        }
        assertThat(service.find(PET_ID).isDone(PieceKind.PLAY))
                .as("둘을 합쳐 세면 절반씩만 해도 찍힌다")
                .isFalse();

        service.count(pet, PieceEvent.GAME);
        assertThat(service.find(PET_ID).isDone(PieceKind.PLAY)).isTrue();
    }

    private void fill(ZzalPet pet, PieceEvent event) {
        for (int i = 0; i < required(event); i++) {
            service.count(pet, event);
        }
    }
}
