package com.lore.zzal.archive;

import java.util.List;

/** 영수증 장부 — "어디까지 올렸나" 를 묻고, 한 판의 영수증을 <b>함께</b> 적는다. */
public interface EventArchiveLedger {

    /** 마지막으로 올린 {@code zzal_event.id}. 한 번도 안 올렸으면 0. */
    long watermark();

    /** 한 판의 영수증을 한 트랜잭션으로. 중간에 실패하면 한 장도 안 적힌다(그 판을 통째로 다시 한다). */
    void record(List<ZzalEventArchivePart> parts);
}
