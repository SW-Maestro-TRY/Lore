package com.lore.webtoon.art;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 더 안 쓰는 그림을 대기열에 적는다. 지우는 것은 {@link ArtDiscardSweep} 이 7일 뒤에 한다.
 *
 * <h2>왜 바로 안 지우나</h2>
 *
 * 그림을 새 이름으로 바꿔 적는 일(다시 그리기·다시 굽기)은 같은 트랜잭션 안에서 일어난다.
 * 여기서 바로 S3 를 지우면, 그 뒤에 트랜잭션이 되돌려졌을 때 DB 는 옛 이름을 들고 있는데
 * 그림은 이미 없는 상태가 된다. 적기만 하면 같이 되돌려진다.
 *
 * 화면이 옛 주소를 들고 열려 있는 동안 그림이 깨지지 않게 하려는 뜻도 있다.
 */
@Service
public class ArtDiscards {

    /** 같은 자리(작품·장·폭)에 새 그림이 올라와 이름이 바뀌었다. */
    public static final String REPLACED = "replaced";
    /** 구운 장을 거뒀다 — 얹은 것을 다 지웠거나 밑그림을 다시 그렸다. */
    public static final String DROPPED = "dropped";
    /** 관리자 점검({@link ArtOrphanScan})이 DB 어디에도 없는 것을 찾았다. */
    public static final String ORPHAN = "orphan";

    /** 이미 적힌 것을 한 번에 몇 개씩 찾나. 문장 하나에 넣는 값의 수를 넉넉히 아래로 둔다. */
    private static final int CHUNK = 1000;

    private final ArtDiscardRepository repo;

    public ArtDiscards(ArtDiscardRepository repo) {
        this.repo = repo;
    }

    /** 한 장. 빈 키는 넘긴다. */
    @Transactional
    public void discard(String key, String reason, Instant at) {
        discardAll(key == null ? List.of() : List.of(key), reason, at);
    }

    /**
     * 여러 장. -> 새로 적은 수
     *
     * 이미 적힌 것은 다시 적지 않는다 — 시각을 새로 찍으면 지울 날이 그만큼 밀린다.
     */
    @Transactional
    public int discardAll(Collection<String> keys, String reason, Instant at) {
        List<String> real = new ArrayList<>();
        for (String k : new LinkedHashSet<>(keys)) {
            if (k != null && !k.isBlank()) {
                real.add(k);
            }
        }
        List<ArtDiscard> fresh = new ArrayList<>();
        for (int from = 0; from < real.size(); from += CHUNK) {
            List<String> chunk = real.subList(from, Math.min(from + CHUNK, real.size()));
            Set<String> had = new HashSet<>();
            repo.findAllById(chunk).forEach(d -> had.add(d.getS3Key()));
            for (String k : chunk) {
                if (!had.contains(k)) {
                    fresh.add(new ArtDiscard(k, reason, at));
                }
            }
        }
        repo.saveAll(fresh);
        return fresh.size();
    }
}
