package com.lore.webtoon.art;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 창고에는 있는데 DB 어디에도 없는 웹툰 그림(주인 없는 그림)을 찾는다 — 관리자 점검.
 *
 * <h2>왜 필요한가</h2>
 *
 * 대기열({@link ArtDiscards})은 앞으로 생기는 옛 그림만 잡는다. 그 전에 다시 그리기·다시 굽기로
 * 이름이 바뀐 그림은 이미 아무 데서도 안 가리키는 채로 쌓여 있다(2026-10-27 운영: 창고 21,213장,
 * DB 가 아는 주소 2,274개). 작품을 지우거나 탈퇴해도 이것들은 안 지워지고, 공개였던 작품을
 * 비공개로 바꿔도 공개 자리에 그대로 남는다.
 *
 * <h2>세기만 하는 것이 기본이다</h2>
 *
 * 기본은 세어서 보여 주기만 한다. 넣기(apply)를 해도 바로 지우지 않고 대기열에 적을 뿐이라,
 * 실제 삭제는 7일 뒤 {@link ArtDiscardSweep} 이 웹툰 표를 다시 본 다음에 한다.
 *
 * <h2>무엇을 주인 없는 것으로 보나</h2>
 *
 * <ul>
 *   <li>{@code images/webtoon/} · {@code private/webtoon/} 아래 파일 가운데</li>
 *   <li>DB 의 어느 글자 칸에도 그 주소가 안 나오고({@link ArtReferences})</li>
 *   <li>올라온 지 {@link #MIN_AGE} 이상 지났고 — 막 올라와 아직 DB 에 적히기 전인 것을 피한다</li>
 *   <li>대기열에 아직 없는 것</li>
 * </ul>
 */
@Service
public class ArtOrphanScan {

    private static final Logger log = LoggerFactory.getLogger(ArtOrphanScan.class);

    static final List<String> PREFIXES = List.of("images/webtoon/", "private/webtoon/");

    /** 이보다 최근에 올라온 것은 건드리지 않는다. */
    static final Duration MIN_AGE = Duration.ofDays(2);

    /** 보고에 담는 예시 수. */
    static final int SAMPLE = 30;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ArtInventory inventory;
    private final ArtReferences references;
    private final ArtDiscardRepository queue;
    private final ArtDiscards discards;

    public ArtOrphanScan(ArtInventory inventory, ArtReferences references,
                         ArtDiscardRepository queue, ArtDiscards discards) {
        this.inventory = inventory;
        this.references = references;
        this.queue = queue;
        this.discards = discards;
    }

    /**
     * @param files       창고에 있던 파일 수
     * @param bytes       그 용량
     * @param referenced  DB 어딘가에 주소가 있는 파일 수
     * @param recent      주인은 없지만 너무 최근이라 넘긴 수
     * @param queued      이미 대기열에 있던 수
     * @param orphans     이번에 찾은 주인 없는 파일 수
     * @param orphanBytes 그 용량
     * @param added       대기열에 새로 적은 수(세기만 했으면 0)
     * @param byMonth     주인 없는 파일의 올라온 달(KST)별 수
     * @param sample      주인 없는 파일 예시(최근 것부터)
     */
    public record Report(int files, long bytes, int referenced, int recent, int queued,
                         int orphans, long orphanBytes, int added,
                         Map<String, Integer> byMonth, List<Map<String, Object>> sample) {
    }

    public boolean ready() {
        return inventory.ready();
    }

    /**
     * 세고, {@code apply} 면 대기열에 적는다.
     *
     * 창고나 DB 를 끝까지 못 읽으면 예외를 그대로 올린다 — 절반만 보고 적으면 못 본 쪽의
     * 그림이 지워진다.
     */
    public Report run(boolean apply, Instant now) {
        if (!inventory.ready()) {
            throw new IllegalStateException("그림 창고(버킷)가 없는 환경입니다");
        }
        List<ArtInventory.Item> files = new ArrayList<>();
        for (String prefix : PREFIXES) {
            files.addAll(inventory.list(prefix));
        }
        Set<String> used = references.everywhere();
        Set<String> inQueue = new HashSet<>(queue.allKeys());
        Instant cut = now.minus(MIN_AGE);

        long bytes = 0;
        int referenced = 0;
        int recent = 0;
        int queued = 0;
        List<ArtInventory.Item> orphans = new ArrayList<>();
        for (ArtInventory.Item one : files) {
            bytes += one.bytes();
            if (used.contains(one.key())) {
                referenced++;
            } else if (inQueue.contains(one.key())) {
                queued++;
            } else if (one.modified() == null || one.modified().isAfter(cut)) {
                recent++;
            } else {
                orphans.add(one);
            }
        }

        int added = 0;
        if (apply && !orphans.isEmpty()) {
            added = discards.discardAll(orphans.stream().map(ArtInventory.Item::key).toList(),
                    ArtDiscards.ORPHAN, now);
        }

        long orphanBytes = orphans.stream().mapToLong(ArtInventory.Item::bytes).sum();
        Map<String, Integer> byMonth = new TreeMap<>();
        for (ArtInventory.Item one : orphans) {
            byMonth.merge(one.modified().atZone(KST).toLocalDate().toString().substring(0, 7), 1, Integer::sum);
        }
        List<Map<String, Object>> sample = orphans.stream()
                .sorted(Comparator.comparing(ArtInventory.Item::modified).reversed())
                .limit(SAMPLE)
                .map(one -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", one.key());
                    row.put("bytes", one.bytes());
                    row.put("modified", one.modified().toString());
                    return row;
                })
                .toList();

        log.info("주인 없는 그림 점검 — 창고 {}장, DB 에 있는 것 {}장, 주인 없는 것 {}장({}MB), "
                        + "최근이라 넘긴 것 {}장, 이미 대기열 {}장, {}",
                files.size(), referenced, orphans.size(), orphanBytes / (1024 * 1024),
                recent, queued, apply ? "대기열에 " + added + "장 적음" : "세기만 함");
        return new Report(files.size(), bytes, referenced, recent, queued,
                orphans.size(), orphanBytes, added, byMonth, sample);
    }
}
