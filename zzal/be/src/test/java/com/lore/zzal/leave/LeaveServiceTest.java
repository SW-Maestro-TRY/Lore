package com.lore.zzal.leave;

import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 엽서 — <b>날짜 경계 · 재진입 · 두 번째 여행</b>(M-23).
 *
 * <h3>★ 왜 이 시험이 필요한가</h3>
 * 리포지터리 주석({@code ZzalPostcardRepository:10~12})이 "두 번째 여행의 순서가 섞였다" 는
 * 실측을 적어 두었다. 그 자리가 다시 깨져도 <b>예외도 로그도 안 난다</b> — 사용자에게는 그냥
 * 세 번째 엽서가 첫 번째 자리에 오는 것으로 보인다.
 *
 * <h3>★ 무엇을 재나</h3>
 * 엽서는 "조회할 때 오늘 몫 한 장" 이 아니라 <b>밀린 몫까지</b> 채운다 — 한 번도 안 열고 닷새 만에
 * 부른 사람이 한 장도 못 받으면 이 기능이 있는 이유를 정면으로 어긴다. 그래서 <b>출발일부터 지난
 * 날수</b>로 목표 장수를 정하고, 같은 시각에 두 번 불러도 더 안 쓰는지를 본다.
 */
@DisplayName("엽서 — 하루 한 장, 최대 세 장")
class LeaveServiceTest {

    /** 여행을 떠난 순간(KST). 날짜 경계를 보려고 정오로 잡는다. */
    private static final Instant DEPART = kst("2026-09-05 12:00");
    private static final Long PET_ID = 7L;

    private final List<ZzalPostcard> stored = new ArrayList<>();
    private ZzalPostcardRepository repository;
    private LeaveService service;

    @BeforeEach
    void setUp() {
        stored.clear();
        repository = mock(ZzalPostcardRepository.class);
        when(repository.save(any())).thenAnswer(i -> {
            ZzalPostcard card = i.getArgument(0);
            ReflectionTestUtils.setField(card, "id", (long) (stored.size() + 1));
            stored.add(card);
            return card;
        });
        when(repository.findByPetIdOrderByWrittenAtAscIdAsc(any())).thenAnswer(i -> stored.stream()
                .sorted(Comparator.comparing(ZzalPostcard::getWrittenAt).thenComparing(ZzalPostcard::getId))
                .toList());
        service = new LeaveService(repository);
    }

    /** 여행 중인 펫 하나. */
    private ZzalPet traveling(Instant departedAt) {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "images/zzal/abc", departedAt.minus(Duration.ofDays(10)));
        pet.markAlive("images/zzal/sheet", "생김새", departedAt.minus(Duration.ofDays(10)));
        pet.skipTutorial(departedAt.minus(Duration.ofDays(10)));
        ReflectionTestUtils.setField(pet, "id", PET_ID);
        depart(pet, departedAt);
        return pet;
    }

    /**
     * 떠나보낸다 — {@code ZzalPet.judgeLeaving} 의 출발 블록과 <b>같은 네 칸</b>을 놓는다.
     *
     * ★ 진짜로 떠나게 하려면 미방문 5일 + 예고 2일을 실제로 흘려야 하고, 그동안의 게이지가
     *   이 시험이 보려는 것(엽서 날짜)을 가린다. 출발 상태만 만들고 그 뒤는 전부 진짜 코드다.
     *   두 번째 여행에서 {@code postcardCount} 가 0 으로 돌아가는 것도 여기에 그대로 옮겨 둔다.
     */
    private static void depart(ZzalPet pet, Instant at) {
        ReflectionTestUtils.setField(pet, "tripStartedAt", at);
        ReflectionTestUtils.setField(pet, "leaveNoticeAt", null);
        ReflectionTestUtils.setField(pet, "postcardCount", 0);
        ReflectionTestUtils.setField(pet, "lastPostcardDate", null);
    }

    // ── 날짜 경계 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 출발 당일 1장 · 2일째 2장 · 3일째 3장 · 4일째도 3장 — 상한에서 멈춘다")
    void oneADayUpToThree() {
        ZzalPet pet = traveling(DEPART);

        assertThat(service.fillPostcards(pet, kst("2026-09-05 13:00"))).isEqualTo(1);
        assertThat(pet.getPostcardCount()).isEqualTo(1);

        assertThat(service.fillPostcards(pet, kst("2026-09-06 13:00"))).isEqualTo(1);
        assertThat(pet.getPostcardCount()).isEqualTo(2);

        assertThat(service.fillPostcards(pet, kst("2026-09-07 13:00"))).isEqualTo(1);
        assertThat(pet.getPostcardCount()).isEqualTo(3);

        assertThat(service.fillPostcards(pet, kst("2026-09-08 13:00")))
                .as("넷째 날에는 더 안 온다 — 열흘 만에 와서 열 장을 받으면 소식이 아니라 청구서다")
                .isZero();
        assertThat(stored).hasSize(ZzalRules.POSTCARD_MAX);
    }

    @Test
    @DisplayName("★★ 한 번도 안 열고 사흘 만에 와도 세 장이 한꺼번에 채워진다 — 이 기능이 있는 이유다")
    void backfillsEverythingAtOnce() {
        ZzalPet pet = traveling(DEPART);

        assertThat(service.fillPostcards(pet, kst("2026-09-08 13:00"))).isEqualTo(3);

        assertThat(stored).extracting(ZzalPostcard::getSeq).containsExactly(1, 2, 3);
        assertThat(stored).extracting(ZzalPostcard::getWrittenAt)
                .as("★ 세 장이 같은 시각에 찍히면 '하루 한 장' 이 기록에서 사라진다")
                .containsExactly(DEPART, DEPART.plus(Duration.ofDays(1)), DEPART.plus(Duration.ofDays(2)));
    }

    @Test
    @DisplayName("★ 같은 시각에 두 번 불러도 더 안 쓴다 — 조회가 잦은 화면이 엽서를 찍어내면 안 된다")
    void callingTwiceAtTheSameMomentAddsNothing() {
        ZzalPet pet = traveling(DEPART);
        Instant at = kst("2026-09-06 13:00");

        assertThat(service.fillPostcards(pet, at)).isEqualTo(2);
        assertThat(service.fillPostcards(pet, at)).isZero();
        assertThat(service.fillPostcards(pet, at)).isZero();

        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("★ 아직 안 온 날짜로는 안 적는다 — 자정을 갓 넘겨 부르면 그 장은 '지금' 으로 눌린다")
    void futureDatesAreClampedToNow() {
        // 23:30 에 떠나 40분 뒤(다음 날 00:10)에 불렀다 — 달력으로는 이틀째라 두 장이지만,
        // 둘째 장의 날짜(다음 날 23:30)는 아직 오지 않았다.
        ZzalPet pet = traveling(kst("2026-09-05 23:30"));
        Instant now = kst("2026-09-06 00:10");

        assertThat(service.fillPostcards(pet, now)).isEqualTo(2);

        assertThat(stored.get(1).getWrittenAt())
                .as("미래 시각이 기록에 남으면 정렬이 뒤집힌다")
                .isEqualTo(now);
        assertThat(stored.get(1).getWrittenAt()).isBeforeOrEqualTo(now);
    }

    // ── 여행이 아닐 때 ────────────────────────────────────────────────────

    @Test
    @DisplayName("여행 중이 아니면 한 장도 안 쓴다")
    void notTravelingWritesNothing() {
        ZzalPet pet = PetFixture.hatching(1L, "여울", null, "k", DEPART);
        pet.markAlive("s", "i", DEPART);

        assertThat(service.fillPostcards(pet, kst("2026-09-08 13:00"))).isZero();
        assertThat(stored).isEmpty();
    }

    // ── 재회 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 재회는 모아 둔 것을 한 번에 전달 — 두 번 불러도 두 번째는 0")
    void reunionDeliversOnlyOnce() {
        ZzalPet pet = traveling(DEPART);
        service.fillPostcards(pet, kst("2026-09-08 13:00"));
        Instant reunion = kst("2026-09-08 14:00");

        assertThat(service.deliverAll(PET_ID, reunion)).isEqualTo(3);
        assertThat(service.deliverAll(PET_ID, reunion)).isZero();
    }

    @Test
    @DisplayName("★ 여행 중인 엽서는 앨범에 안 보인다 — 그게 부르러 갈 이유다")
    void undeliveredIsInvisible() {
        ZzalPet pet = traveling(DEPART);
        service.fillPostcards(pet, kst("2026-09-08 13:00"));

        assertThat(service.delivered(PET_ID)).isEmpty();

        service.deliverAll(PET_ID, kst("2026-09-08 14:00"));
        assertThat(service.delivered(PET_ID)).hasSize(3);
    }

    // ── 두 번째 여행 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("★★ 두 번째 여행은 seq 1 부터 다시 — 그리고 앨범 순서가 섞이지 않는다(#235 리뷰 하-2)")
    void secondTripStartsAtSeqOneAndKeepsOrder() {
        ZzalPet pet = traveling(DEPART);
        service.fillPostcards(pet, kst("2026-09-08 13:00"));
        service.deliverAll(PET_ID, kst("2026-09-08 14:00"));
        pet.callBack(kst("2026-09-08 14:00"));

        // 열흘 뒤에 다시 떠난다.
        Instant again = kst("2026-09-18 12:00");
        depart(pet, again);
        assertThat(pet.getPostcardCount()).as("출발이 장수를 0 으로 되돌린다").isZero();

        assertThat(service.fillPostcards(pet, kst("2026-09-20 13:00"))).isEqualTo(3);
        service.deliverAll(PET_ID, kst("2026-09-20 14:00"));

        List<ZzalPostcard> album = service.delivered(PET_ID);
        assertThat(album).hasSize(6);
        assertThat(album).extracting(ZzalPostcard::getSeq)
                .as("첫 여행 셋이 먼저, 두 번째 여행 셋이 뒤 — 번호는 각 여행 안에서 1·2·3")
                .containsExactly(1, 2, 3, 1, 2, 3);
        assertThat(album).extracting(ZzalPostcard::getWrittenAt).isSorted();
    }

    // ── 보내는 곳 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("어디서 보냈나는 결정적 — 같은 펫의 같은 번째는 언제나 같은 곳")
    void placeIsDeterministic() {
        ZzalPet pet = traveling(DEPART);

        for (int seq = 1; seq <= ZzalRules.POSTCARD_MAX; seq++) {
            assertThat(LeaveService.place(pet, seq)).isEqualTo(LeaveService.place(pet, seq));
            assertThat(LeaveService.PLACES).contains(LeaveService.place(pet, seq));
        }
    }
}
