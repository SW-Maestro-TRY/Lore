package com.lore.zzal.piece;

import com.lore.zzal.PetFixture;
import com.lore.zzal.PieceFixture;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조각 세기가 <b>펫의 상태와 어떻게 맞물리는가</b>.
 *
 * ★ 세는 규칙 자체는 {@link ZzalPieceTest} 가 본다. 여기서는 "언제 세기 시작하나 ·
 *   언제 되돌리나" 처럼 <b>펫 쪽 사정</b>이 걸린 것만 본다.
 */
@DisplayName("조각 세기 — 펫과 맞물리는 부분")
class PieceServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant T0 = at("2026-09-05 12:00");

    private static Instant at(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T')).atZone(KST).toInstant();
    }

    private ZzalPet child() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", at("2026-09-05 11:00"));
        pet.markAlive("images/zzal/sheet", "생김새", at("2026-09-05 11:00"));
        pet.skipTutorial(at("2026-09-05 11:30"));
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", 1L);   // DB 가 없으니 손으로
        return pet;
    }

    @Test
    @DisplayName("★★ 3층 전에는 아무것도 안 센다 — 열린 날 한 칸이 공짜로 차 있으면 안 된다")
    void nothingBeforeTierThree() {
        PieceService service = new PieceServiceProbe();
        ZzalPet pet = child();
        assertThat(pet.isPiecesEnabled()).isFalse();

        for (int i = 0; i < 10; i++) {
            service.count(pet, PieceEvent.FEED);
        }
        assertThat(service.find(pet.getId())).as("줄조차 안 만든다").isNull();
    }

    @Test
    @DisplayName("3층이 열린 뒤부터 센다")
    void countsAfterTierThree() {
        PieceService service = PieceFixture.inMemory();
        ZzalPet pet = child();
        pet.enablePieces(T0);

        for (int i = 0; i < 6; i++) {
            service.count(pet, PieceEvent.FEED);
        }
        assertThat(service.find(pet.getId()).isDone(PieceKind.FOOD)).isTrue();
    }

    @Test
    @DisplayName("★ 여섯 번째 밥이 네 칸을 채우면 true 를 돌려준다 — 굽기가 시작될 순간이다")
    void tellsWhenComplete() {
        PieceService service = PieceFixture.inMemory();
        ZzalPet pet = child();
        pet.enablePieces(T0);

        for (int i = 0; i < 5; i++) {
            service.count(pet, PieceEvent.GAME);
        }
        for (int i = 0; i < 2; i++) {
            service.count(pet, PieceEvent.BATH);
        }
        for (int i = 0; i < 5; i++) {
            service.count(pet, PieceEvent.CHAT);
        }
        boolean complete = false;
        for (int i = 0; i < 6; i++) {
            complete = service.count(pet, PieceEvent.FEED);
        }
        assertThat(complete).isTrue();
    }

    @Test
    @DisplayName("★★ 기상 쪽지 — 네 칸이 다 찬 판만 0 으로, 선물은 되돌린 뒤에 얹는다")
    void settleResetsThenGrants() {
        PieceService service = PieceFixture.inMemory();
        ZzalPet pet = child();
        pet.enablePieces(T0);
        for (PieceKind kind : PieceKind.values()) {
            service.open(pet.getId()).grant(kind);
        }
        assertThat(service.find(pet.getId()).isComplete()).isTrue();

        // 게이지를 채워 두고 자면 "기분 좋은 날" 이 되어 선물 쪽지도 함께 남는다
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "fullness", 4);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "happiness", 4);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "trash", 0);
        pet.sleep(at("2026-09-05 20:00"));
        pet.wake(at("2026-09-06 08:00"));

        service.settle(pet);

        ZzalPiece row = service.find(pet.getId());
        assertThat(row.doneCount())
                .as("★ 네 칸이 0 으로 돌아간 <b>뒤</b>에 선물 한 칸이 얹힌다 — 순서가 반대면 선물이 지워진다")
                .isEqualTo(1);
        assertThat(row.isDone(PieceKind.FOOD)).as("앞선 칸부터").isTrue();
        assertThat(pet.isPieceResetPending()).as("쪽지는 치운다").isFalse();
    }

    @Test
    @DisplayName("쪽지가 없으면 아무 일도 안 한다")
    void noNoteNoWork() {
        PieceService service = PieceFixture.inMemory();
        ZzalPet pet = child();
        pet.enablePieces(T0);
        service.open(pet.getId()).count(PieceEvent.FEED);

        service.settle(pet);

        assertThat(service.find(pet.getId()).countOf(PieceEvent.FEED)).isEqualTo(1);
    }

    /** 3층 전에는 줄을 만들지도 않는지 보려고 — 저장소가 비어 있는 진짜 서비스. */
    private static class PieceServiceProbe extends PieceService {
        PieceServiceProbe() {
            super(org.mockito.Mockito.mock(ZzalPieceRepository.class));
        }
    }
}
