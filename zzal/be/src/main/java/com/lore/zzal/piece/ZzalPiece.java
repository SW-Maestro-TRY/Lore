package com.lore.zzal.piece;

import com.lore.zzal.pet.ZzalRules;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.EnumMap;
import java.util.Map;

/**
 * 조각 네 칸의 진행(정본 6장 · 세는 법은 1.9). 3층부터만 쓴다 — 한 펫에 한 줄.
 *
 * <h3>★ 요구량이 이틀치다</h3>
 * 숫자를 "하루에 할 수 있는 최대치보다 크게" 잡았다(채팅은 하루 3회인데 5회를 요구하는 식).
 * 그래서 <b>규칙에 "이틀" 이라는 말이 한 번도 안 나오는데도</b> 자연히 이틀이 걸린다.
 * 날짜를 세는 자리가 없으니 "하루라도 빠지면 0부터" 같은 벌도 생길 수가 없다 —
 * 안 온 날은 <b>늦어질 뿐</b>이다(정본 1.8 에서 "이틀 연속" 을 없앤 이유).
 *
 * <h3>★★ 길마다 따로 센다</h3>
 * 한 칸에 길이 둘이다 — 청결은 <b>목욕 2회 또는 청소 5회</b>. 한 칸에 횟수를 하나만 두면
 * <b>청소 3회 + 목욕 1회 = 4회</b>가 목욕 목표(2)를 넘겨 도장이 찍힌다. 목욕은 한 번밖에 안 했는데도.
 * 그래서 길마다 횟수를 따로 세고, <b>어느 한 길이 제 목표에 닿으면</b> 그 칸이 찍힌다.
 *
 * <h3>★ 세는 법 — 넘친 만큼은 버린다 (1.9)</h3>
 * 요구량에 닿는 순간 <b>도장이 찍히고 그 칸의 횟수는(두 길 모두) 0</b>이 된다. 넘친 만큼은 다음
 * 도장으로 넘어가지 않는다. 상훈님 표현대로 "1일차에 4회, 2일차에 4회를 해도 <b>5회 쓰고 3회가 남는</b>
 * 것이 아니라 도장 하나로 끝난다."
 *
 * <h3>★ 도장이 찍힌 칸은 더 안 센다</h3>
 * 그러지 않으면 이미 찍은 칸에 계속 쌓여, 다음 판이 시작될 때 한 칸이 공짜로 차 있게 된다.
 *
 * <h3>★ 네 칸이 다 차면 — 그날은 찍힌 채로 보인다</h3>
 * 굽기는 그 순간 시작하지만 도장은 지우지 않는다. <b>다음 기상</b>에 네 칸이 모두 0 으로 돌아간다(1.9).
 * 그날 안에 "다 했다" 를 보여 주고, 다음 날 새 판을 시작하게 하려는 것이다.
 * 그 사이(네 칸이 다 찬 뒤 다음 기상까지)에는 아무것도 세지 않는다 — 네 칸이 전부 찍혀 있으므로
 * 위의 "찍힌 칸은 더 안 센다" 규칙이 그대로 그 일을 한다.
 */
@Entity
@Table(name = "zzal_piece")
public class ZzalPiece {

    /** 펫 번호가 그대로 이 표의 열쇠다 — 한 펫에 한 줄. */
    @Id
    @Column(name = "pet_id")
    private Long petId;

    // 길마다 하나씩. 도장이 찍히면 그 칸의 길이 모두 0 으로 돌아간다.
    @Column(nullable = false)
    private int feedCount;

    @Column(nullable = false)
    private int snackCount;

    @Column(nullable = false)
    private int gameCount;

    @Column(nullable = false)
    private int cleanCount;

    @Column(nullable = false)
    private int bathCount;

    @Column(nullable = false)
    private int petCount;

    @Column(nullable = false)
    private int chatCount;

    @Column(nullable = false)
    private boolean foodDone;

    @Column(nullable = false)
    private boolean playDone;

    @Column(nullable = false)
    private boolean cleanDone;

    @Column(nullable = false)
    private boolean bondDone;

    protected ZzalPiece() {
    }

    public static ZzalPiece of(Long petId) {
        ZzalPiece row = new ZzalPiece();
        row.petId = petId;
        return row;
    }

    // ── 세기 ──────────────────────────────────────────────────────────────

    /**
     * 행동 하나를 센다. 그 행동이 올리는 칸이 <b>이미 찍혔으면 아무 일도 없다.</b>
     *
     * @return 이 한 번으로 도장이 <b>새로</b> 찍혔으면 그 칸, 아니면 null
     */
    public PieceKind count(PieceEvent event) {
        PieceKind kind = event.kind();
        if (isDone(kind)) {
            return null;
        }
        int next = countOf(event) + 1;
        setCount(event, next);
        if (next >= target(event)) {
            stamp(kind);
            return kind;
        }
        return null;
    }

    /**
     * 그 칸을 채운 것으로 친다 — 기분 좋은 날의 선물(정본 6장).
     * 이미 찍힌 칸에는 아무 일도 없다.
     */
    public boolean grant(PieceKind kind) {
        if (isDone(kind)) {
            return false;
        }
        stamp(kind);
        return true;
    }

    /** 기분 좋은 날의 선물이 채울 칸 — 아직 안 찬 것 중 <b>앞선 것</b>(정본 6장). 다 찼으면 null. */
    public PieceKind firstOpen() {
        for (PieceKind kind : PieceKind.values()) {
            if (!isDone(kind)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * 다음 기상 — 네 칸이 다 찍혀 있었으면 전부 0 으로 돌린다(1.9).
     *
     * ★ 다 안 찼으면 그대로 둔다. 요구량이 이틀치라 하루마다 지우면 영원히 못 채운다.
     *
     * @return 실제로 되돌렸으면 true
     */
    public boolean resetOnWakeIfComplete() {
        if (!isComplete()) {
            return false;
        }
        for (PieceKind kind : PieceKind.values()) {
            setDone(kind, false);
        }
        for (PieceEvent event : PieceEvent.values()) {
            setCount(event, 0);
        }
        return true;
    }

    public boolean isComplete() {
        return foodDone && playDone && cleanDone && bondDone;
    }

    public int doneCount() {
        return (foodDone ? 1 : 0) + (playDone ? 1 : 0) + (cleanDone ? 1 : 0) + (bondDone ? 1 : 0);
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    public Long getPetId() {
        return petId;
    }

    public boolean isDone(PieceKind kind) {
        return switch (kind) {
            case FOOD -> foodDone;
            case PLAY -> playDone;
            case CLEAN -> cleanDone;
            case BOND -> bondDone;
        };
    }

    public int countOf(PieceEvent event) {
        return switch (event) {
            case FEED -> feedCount;
            case SNACK -> snackCount;
            case GAME -> gameCount;
            case CLEAN -> cleanCount;
            case BATH -> bathCount;
            case PET -> petCount;
            case CHAT -> chatCount;
        };
    }

    /**
     * 화면에 그릴 "몇 번 중 몇 번" — 칸마다 <b>가장 많이 간 길</b> 하나.
     *
     * ★ 길이 둘인 칸에서 둘을 다 보여 주면 사용자가 "이 중 뭘 해야 하지" 를 계산하게 된다.
     *   이미 걸어온 길을 보여 주는 편이 "조금만 더 하면 된다" 로 읽힌다.
     */
    public Map<PieceKind, int[]> progress() {
        Map<PieceKind, int[]> out = new EnumMap<>(PieceKind.class);
        for (PieceKind kind : PieceKind.values()) {
            out.put(kind, isDone(kind) ? new int[]{1, 1} : nearest(kind));
        }
        return out;
    }

    /** 그 칸에서 가장 가까이 간 길의 {지금, 목표}. */
    private int[] nearest(PieceKind kind) {
        int[] best = null;
        double bestRatio = -1;
        for (PieceEvent event : PieceEvent.values()) {
            if (event.kind() != kind) {
                continue;
            }
            int current = countOf(event);
            int target = target(event);
            double ratio = (double) current / target;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = new int[]{current, target};
            }
        }
        return best;
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /** 그 행동으로 채우려면 몇 번이 필요한가(정본 6장 표). */
    private static int target(PieceEvent event) {
        return switch (event) {
            case FEED -> ZzalRules.PIECE_FEEDS;
            case SNACK -> ZzalRules.PIECE_SNACKS;
            case GAME -> ZzalRules.PIECE_GAMES;
            case CLEAN -> ZzalRules.PIECE_CLEANS;
            case BATH -> ZzalRules.PIECE_BATHS;
            case PET -> ZzalRules.PIECE_PETS;
            case CHAT -> ZzalRules.PIECE_CHATS;
        };
    }

    /** 도장 + 그 칸의 <b>두 길 모두</b> 0. 넘친 만큼은 버린다(1.9). */
    private void stamp(PieceKind kind) {
        setDone(kind, true);
        for (PieceEvent event : PieceEvent.values()) {
            if (event.kind() == kind) {
                setCount(event, 0);
            }
        }
    }

    private void setCount(PieceEvent event, int value) {
        switch (event) {
            case FEED -> feedCount = value;
            case SNACK -> snackCount = value;
            case GAME -> gameCount = value;
            case CLEAN -> cleanCount = value;
            case BATH -> bathCount = value;
            case PET -> petCount = value;
            case CHAT -> chatCount = value;
        }
    }

    private void setDone(PieceKind kind, boolean value) {
        switch (kind) {
            case FOOD -> foodDone = value;
            case PLAY -> playDone = value;
            case CLEAN -> cleanDone = value;
            case BOND -> bondDone = value;
        }
    }
}
