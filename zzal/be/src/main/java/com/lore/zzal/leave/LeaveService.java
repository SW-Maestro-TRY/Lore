package com.lore.zzal.leave;

import com.lore.zzal.chat.BanFilter;
import com.lore.zzal.pet.Chance;
import com.lore.zzal.pet.ZzalPet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
     * 여행 중이면 오늘 몫의 엽서를 쓴다.
     *
     * ★ 며칠이 한꺼번에 지났어도 <b>한 번에 한 장</b>만 쓴다. "하루 한 장" 이 정본이고, 어차피 세 장에서
     *   멈추므로 다음 조회에서 마저 채워진다. 몰아서 쓰면 날짜가 겹친 엽서가 생긴다.
     *
     * @return 이번에 쓴 장수(0 또는 1)
     */
    public int writePostcard(ZzalPet pet, Instant now) {
        if (!pet.canWritePostcard(now)) {
            return 0;
        }
        int seq = pet.getPostcardCount() + 1;
        postcardRepository.save(ZzalPostcard.of(pet.getId(), seq, place(pet, seq), now));
        pet.wrotePostcard(now);
        log.debug("엽서 — petId={} {}번째", pet.getId(), seq);
        return 1;
    }

    /** 재회 — 모아 둔 엽서를 한꺼번에 전달한다(정본 9장 "재회 시 3장 전달"). */
    public int deliverAll(Long petId, Instant now) {
        int delivered = 0;
        for (ZzalPostcard card : postcardRepository.findByPetIdOrderBySeqAsc(petId)) {
            if (!card.isDelivered()) {
                card.deliver(now);
                delivered++;
            }
        }
        return delivered;
    }

    /** 전달된 엽서만(앨범). 여행 중인 엽서는 안 보인다 — 그게 부르러 갈 이유다. */
    public List<ZzalPostcard> delivered(Long petId) {
        return postcardRepository.findByPetIdOrderBySeqAsc(petId).stream()
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
