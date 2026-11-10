package com.lore.webtoon.series;
import com.lore.webtoon.credit.CreditGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SeriesBilling {
    private final CreditGate gate;
    private final com.lore.common.credit.CreditService credits;
    private final SeriesChapterRepository chapters;
    public SeriesBilling(CreditGate gate, com.lore.common.credit.CreditService credits, SeriesChapterRepository chapters) { this.gate=gate;this.credits=credits;this.chapters=chapters; }
    /** 같은 계정의 서로 다른 연재도 잔액 확인부터 차감 완료까지 순서대로 처리한다. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public int charge(Long user,int cost,String job,String memo) {
        chapters.lockSeriesCredit(user);
        return credits.spend(user,com.lore.common.credit.CreditDomain.WEBTOON,cost,job,memo);
    }
    /** 공용 장부가 별도 트랜잭션으로 차감하므로 생성 저장 롤백 때 보상한다. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void refund(Long user, String job) { gate.refund(user,job); }
}
