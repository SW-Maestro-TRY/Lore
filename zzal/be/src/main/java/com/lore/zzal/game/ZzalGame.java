package com.lore.zzal.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 좌·우 맞히기 한 판(다섯 번 겨룬다).
 *
 * <h3>★ 정답을 서버가 쥔다</h3>
 * 화면이 다섯 번을 혼자 치고 "이겼다" 만 보내는 쪽이 왕복이 적지만, 그러면
 * <b>개발자도구로 이겼다고 말하면 그만이다.</b> 지금은 보상이 없어 무해해 보여도,
 * 나중에 보상을 켜는 순간 그게 곧 무한 이득이 된다.
 *
 * 그래서 시작할 때 다섯 판의 답을 미리 뽑아 여기 적어 두고, 한 번에 한 판씩 답을 맞춰 본다.
 * 덤으로 "한 판씩 조마조마" 라는 이 놀이의 재미가 살아난다.
 *
 * <h3>★ 하루 횟수를 지금 제한한다</h3>
 * 보상이 {@code NONE} 인 지금은 필요 없어 보이지만, <b>나중에 값만 바꿔 켜는</b> 구조로
 * 두려면 제한이 그때 이미 있어야 한다. 나중에 넣으려면 코드를 고쳐야 하고,
 * 그 사이에 이미 무제한으로 받아 간 사람이 생긴다.
 */
@Entity
@Table(
        name = "zzal_game",
        indexes = {
                @Index(name = "idx_game_pet", columnList = "pet_id"),
                @Index(name = "idx_game_user_time", columnList = "user_id, started_at")
        })
public class ZzalGame {

    /** 한 판에 몇 번 겨루나. */
    public static final int ROUNDS = 5;

    /** 몇 번 이상 맞히면 이긴 것인가. */
    public static final int WIN_AT = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /**
     * 다섯 판의 답. 'L' 과 'R' 다섯 글자(예: "LRRLR").
     *
     * ★ 화면에 절대 안 나간다. 나가는 것은 방금 친 판의 결과뿐이다.
     */
    @Column(nullable = false, length = 10)
    private String answers;

    /** 지금까지 사용자가 고른 것. 답과 같은 길이가 되면 끝난 것이다. */
    @Column(nullable = false, length = 10)
    private String picks;

    /** 몇 번 맞혔나. */
    @Column(nullable = false)
    private int hits;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** 끝난 시각. 비어 있으면 아직 하는 중. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ZzalGame() {
    }

    public static ZzalGame start(Long userId, Long petId, String answers, Instant now) {
        ZzalGame g = new ZzalGame();
        g.userId = userId;
        g.petId = petId;
        g.answers = answers;
        g.picks = "";
        g.hits = 0;
        g.startedAt = now;
        return g;
    }

    /** 한 판 친다. 맞았는지 돌려준다. */
    public boolean guess(char pick, Instant now) {
        int round = picks.length();
        boolean hit = answers.charAt(round) == pick;
        picks = picks + pick;
        if (hit) {
            hits += 1;
        }
        if (picks.length() >= ROUNDS) {
            finishedAt = now;
        }
        return hit;
    }

    public boolean isFinished() {
        return finishedAt != null;
    }

    /** 지금 몇 번째 판인가(0부터). */
    public int round() {
        return picks.length();
    }

    public boolean isWin() {
        return hits >= WIN_AT;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getPetId() {
        return petId;
    }

    public int getHits() {
        return hits;
    }

    public String getPicks() {
        return picks;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
