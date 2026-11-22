package com.lore.webtoon.lou;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.WebtoonQuality;
import com.lore.webtoon.runs.RegenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EditorRegen {
    private final RegenService regen;
    private final CreditGate credits;
    private final LouRecords records;

    public EditorRegen(RegenService regen, CreditGate credits, LouRecords records) {
        this.records = records;
        this.regen = regen;
        this.credits = credits;
    }

    public record Price(String quality, int cost) {}

    public Price price(String run, int page, Long user, String requested) {
        String quality = WebtoonQuality.isKnown(requested) ? WebtoonQuality.normalize(requested) : null;
        int cost = regen.isUnsafe(run, page) ? 0 :
                WebtoonQuality.regenCreditsOf(quality == null ? regen.qualityOf(run) : quality);
        String blocked = cost > 0 ? credits.whyBlocked(user, cost) : null;
        if (blocked != null) throw new BusinessException(CreditGate.notEnough(), blocked);
        return new Price(quality, cost);
    }

    /** 여러 장을 한꺼번에 — 하나라도 안 되면 값도 기록도 다 없던 일로. */
    @Transactional
    public <T> T together(java.util.function.Supplier<T> jobs) {
        return jobs.get();
    }

    @Transactional
    public String enqueue(String run, int page, Long user, Price price, String note,
                          String mode, Integer basis, boolean verify) {
        Long payer = price.cost() > 0 ? user : null;
        boolean checked = verify || "keep".equals(mode) && records.madeByLou(run, page, note);
        String id = regen.queue(run, page, note, price.quality(), payer, mode, basis, checked);
        if (payer != null) credits.charge(user, price.cost(), RegenService.regenRef(run, page, id), "장 다시 그리기");
        regen.go(id);
        return id;
    }
}
