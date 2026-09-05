package com.lore.zzal.leave;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.pet.Chance;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 여행과 엽서(정본 9·16장).
 *
 * <h3>★★ 떠남은 벌이 아니다</h3>
 * 오래 안 왔다고 아이를 없애는 것이 아니라 <b>여행을 보내고, 부르면 돌아오게</b> 한다. 그래서 이 코드에는
 * 사용자를 탓하는 문구가 없고, 여행 중에도 소식(엽서)이 온다. 아예 겪고 싶지 않은 사람은 설정에서 끌 수 있다.
 *
 * <h3>엽서는 하루 한 장, 최대 세 장</h3>
 * 오래 비워도 세 장에서 멈춘다(정본 16장). 열흘 만에 와서 열 장을 받으면 그건 소식이 아니라 청구서다.
 */
@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    /** 엽서를 보내는 곳. 실물 배경 에셋 이름은 화면이 정하고, 서버는 key 만 고른다. */
    static final List<String> PLACES = List.of("sea", "forest", "city", "mountain", "island");

    private final ZzalPostcardRepository postcardRepository;

    public LeaveService(ZzalPostcardRepository postcardRepository) {
        this.postcardRepository = postcardRepository;
    }

    /**
     * 여행 중이면 <b>밀린 몫까지</b> 엽서를 채운다(정본 9장 "1장/일, 최대 3").
     *
     * <h3>★★ 왜 "오늘 한 장" 이 아니라 밀린 몫인가</h3>
     * 엽서는 <b>여행 중에 앱을 안 연 사람</b>을 위한 것이다. 그런데 "조회할 때 오늘 몫 한 장" 으로 만들면,
     * 정작 한 번도 안 열고 닷새 만에 부른 사람은 <b>엽서를 한 장도 못 받는다</b>(#235 리뷰 중-1 실측: 0장).
     * 그건 이 기능이 있는 이유를 정면으로 어긴다. 그래서 <b>출발한 날부터 지난 날수</b>로 몇 장이 왔어야 하는지를
     * 계산해 그만큼 채운다 — 조회를 했든 안 했든 결과가 같다(시계 엔진의 lazy settle 과 같은 생각).
     *
     * ★ 각 엽서의 시각은 <b>그 날짜</b>다(전부 지금이 아니라). 안 그러면 세 장이 같은 시각에 찍혀
     *   "하루 한 장" 이 기록에서 사라진다.
     *
     * @return 이번에 새로 쓴 장수(0~3)
     */
    public int fillPostcards(ZzalPet pet, Instant now) {
        if (!pet.isAlive() || !pet.isTraveling()) {
            return 0;
        }
        Instant start = pet.getTripStartedAt();
        long elapsedDays = ChronoUnit.DAYS.between(
                start.atZone(ZzalRules.ZONE).toLocalDate(), now.atZone(ZzalRules.ZONE).toLocalDate()) + 1;
        int target = (int) Math.min(ZzalRules.POSTCARD_MAX, Math.max(0, elapsedDays));
        int made = 0;
        for (int seq = pet.getPostcardCount() + 1; seq <= target; seq++) {
            Instant at = start.plus(Duration.ofDays(seq - 1L));
            if (at.isAfter(now)) {
                at = now;                       // 아직 안 온 날짜로는 안 적는다
            }
            postcardRepository.save(ZzalPostcard.of(pet.getId(), seq, place(pet, seq), at));
            pet.wrotePostcard(at);
            made++;
        }
        if (made > 0) {
            log.debug("엽서 — petId={} {}장 채움(누적 {})", pet.getId(), made, pet.getPostcardCount());
        }
        return made;
    }

    /** 재회 — 모아 둔 엽서를 한꺼번에 전달한다(정본 9장 "재회 시 3장 전달"). */
    public int deliverAll(Long petId, Instant now) {
        int delivered = 0;
        for (ZzalPostcard card : postcardRepository.findByPetIdOrderByWrittenAtAscIdAsc(petId)) {
            if (!card.isDelivered()) {
                card.deliver(now);
                delivered++;
            }
        }
        return delivered;
    }

    /** 전달된 엽서만(앨범). 여행 중인 엽서는 안 보인다 — 그게 부르러 갈 이유다. */
    public List<ZzalPostcard> delivered(Long petId) {
        return postcardRepository.findByPetIdOrderByWrittenAtAscIdAsc(petId).stream()
                .filter(ZzalPostcard::isDelivered)
                .toList();
    }

    /** 어디서 보냈나 — 결정적으로 뽑는다(같은 펫의 같은 번째 엽서는 언제나 같은 곳). */
    static String place(ZzalPet pet, int seq) {
        return PLACES.get((int) Chance.pick(PLACES.size(), "postcard-place", pet.chanceSeed(), seq));
    }

    /**
     * 엽서 한 줄 — <b>저장하지 않고 그때그때 만든다</b>(장면과 같은 이유: 문구는 계속 다듬는다).
     *
     * ★★ 사용자를 탓하는 말이 없다. "왜 안 왔어요" 가 아니라 "잘 지내고 있어요" 다.
     *   마지막 장이 "돌아가고 싶어요" 인 것은 재촉이 아니라 <b>부르러 갈 문</b>을 열어 두는 말이다.
     */
    public static String line(ZzalPostcard card) {
        String raw = switch (card.getSeq()) {
            case 1 -> "여기 도착했어요. 잘 지내고 있어요.";
            case 2 -> "오늘은 좋은 걸 봤어요. 같이 봤으면 좋았을 텐데요.";
            default -> "이제 슬슬 돌아가고 싶어요.";
        };
        return BanFilter.clean(raw);
    }
}
