package com.lore.webtoon.work;

import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 비공개 · 휴지통 작품의 그림이 공개 자리에 남아 있으면 옮긴다 — 서버가 뜰 때 한 번(#638).
 *
 * 말풍선을 구운 그림은 예전에 공개 · 비공개를 안 따라갔다(늘 공개 자리에 올리고, 공개 전환 때도 안 옮김).
 * 지금은 둘 다 따라가지만, 이미 그렇게 남은 것은 누가 다시 공개 여부를 바꾸기 전까지 그대로라서 한 번 훑는다.
 * 이미 맞는 자리에 있는 그림은 DB 만 보고 건너뛰므로, 다음 기동부터는 사실상 아무 일도 안 한다.
 *
 * 서버 기동을 붙잡지 않게 따로 돈다. 실패해도 서버에는 영향이 없다.
 */
@Component
public class ClosedArtSweep {

    private static final Logger log = LoggerFactory.getLogger(ClosedArtSweep.class);

    private final WebtoonWorkRepository works;
    private final PageStore pages;
    private final com.lore.webtoon.art.PrivateArt art;
    /* 끌 수 있게 둔다 — 로컬에서 DB 사본으로 서버를 하나 더 띄우면, 그림 창고는 원래 서버와 같이 쓰는데 DB 만
       사본이라 옮긴 그림을 원래 DB 가 못 찾는다(2026-10-04 실제로 4편이 깨질 뻔했다). 배포 환경은 켠 채로 둔다. */
    private final boolean enabled;

    public ClosedArtSweep(WebtoonWorkRepository works, PageStore pages, com.lore.webtoon.art.PrivateArt art,
                          @org.springframework.beans.factory.annotation.Value("${lore.webtoon.closed-art-sweep:true}") boolean enabled) {
        this.works = works;
        this.pages = pages;
        this.art = art;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread t = new Thread(this::sweep, "closed-art-sweep");
        t.setDaemon(true);
        t.start();
    }

    /** -> 옮긴 그림 수 */
    int sweep() {
        int moved = 0;
        if (!enabled || !art.ready()) {
            return 0;                       // 그림 창고를 안 쓰는 환경(버킷 없음)
        }
        try {
            for (String runId : works.closedRunIds()) {
                try {
                    moved += pages.moveAll(runId, false);
                } catch (RuntimeException e) {
                    log.error("비공개 작품의 그림을 못 옮겼습니다 (run={})", runId, e);
                }
            }
        } catch (RuntimeException e) {
            log.error("비공개 작품 그림 자리를 훑지 못했습니다", e);
        }
        if (moved > 0) {
            log.info("공개 자리에 남아 있던 비공개 작품 그림 {}개를 옮겼습니다", moved);
        }
        return moved;
    }
}
