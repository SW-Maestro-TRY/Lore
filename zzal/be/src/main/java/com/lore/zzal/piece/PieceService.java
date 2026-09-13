package com.lore.zzal.piece;

import com.lore.zzal.pet.ZzalPet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 조각을 세는 한 곳(정본 6장 · 1.9).
 *
 * <h3>★ 왜 엔티티가 아니라 여기인가</h3>
 * 조각은 {@code zzal_piece} 라는 제 표를 갖고, zzal 의 엔티티들은 서로를 참조하지 않는다
 * (전부 id + 리포지토리). 그래서 {@code ZzalPet.feed()} 가 조각 줄에 손을 댈 수 없다.
 * 대신 <b>규칙은 전부 {@link ZzalPiece} 안에</b> 두고, 이 클래스는 "줄을 찾아 건네주는" 일만 한다 —
 * 부르는 곳이 일곱이어도 규칙이 일곱 벌로 갈라지지 않는다.
 *
 * <h3>★ 3층 전에는 아무 일도 안 한다</h3>
 * 조각 칸이 화면에 없는데 뒤에서 숫자가 쌓이면, 3층이 열린 날 한 칸이 공짜로 차 있게 된다.
 * 정본 16장이 "그때부터 세기 시작한다(그 전의 돌보기는 소급하지 않는다)" 라고 못 박았다.
 */
@Service
public class PieceService {

    private static final Logger log = LoggerFactory.getLogger(PieceService.class);

    private final ZzalPieceRepository repository;
    private final ApplicationEventPublisher events;

    public PieceService(ZzalPieceRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    /**
     * 성공한 행동 하나를 센다. 3층 전이면 아무 일도 없다.
     *
     * @return 이 한 번으로 네 칸이 <b>다 찼으면</b> true — 굽기가 시작될 순간이다(붙이는 것은 다음 단계)
     */
    public boolean count(ZzalPet pet, PieceEvent event) {
        if (!pet.isPiecesEnabled()) {
            return false;
        }
        ZzalPiece row = rowOf(pet);
        PieceKind stamped = row.count(event);
        if (stamped == null) {
            return false;
        }
        log.debug("조각 도장 — petId={} kind={} ({}개째)", pet.getId(), stamped, row.doneCount());
        if (!row.isComplete()) {
            return false;
        }
        // ★ 네 칸이 방금 찼다 = 굽기를 시작할 순간(정본 1.8). 커밋 뒤에 시작한다 —
        //   돌보기가 롤백되면 굽지 않아야 하고, 굽는 스레드가 아직 없는 줄을 읽으면 안 된다.
        events.publishEvent(new PieceCompleted(pet.getId()));
        return true;
    }

    /**
     * 정산 훅 — 기상에 네 칸을 되돌리고, 기분 좋은 날의 선물을 얹는다.
     *
     * ★ 엔티티가 표를 모르므로 {@code ZzalPet} 은 "되돌릴 때가 됐다 · 선물을 줄 때가 됐다" 는
     *   쪽지만 남기고, 실제 줄은 여기서 만진다({@code pendingNightSceneAt} 과 같은 방식).
     */
    public void settle(ZzalPet pet) {
        if (!pet.isPiecesEnabled()) {
            return;
        }
        boolean reset = pet.isPieceResetPending();
        boolean bonus = pet.isPieceBonusPending();
        if (!reset && !bonus) {
            return;
        }
        // ★ 순서가 중요하다 — 되돌린 <b>뒤에</b> 선물을 얹는다. 반대로 하면 방금 준 선물이 지워진다.
        ZzalPiece row = rowOf(pet);
        if (reset && row.resetOnWakeIfComplete()) {
            log.debug("조각 네 칸 초기화 — petId={}", pet.getId());
        }
        if (bonus) {
            PieceKind kind = row.firstOpen();
            if (kind != null && row.grant(kind)) {
                log.debug("기분 좋은 날 선물 조각 — petId={} kind={}", pet.getId(), kind);
                // ★★ 선물이 <b>마지막 칸</b>을 채울 수 있다. 그때도 굽기가 시작돼야 한다 —
                //   count() 에서만 알리면, 선물로 찬 판은 다음 돌보기(또는 꺼져 있는 23:00 스위프)까지
                //   아무 일도 안 일어난다. 사용자는 네 칸이 다 찬 화면을 보면서 굽기를 기다리게 된다.
                if (row.isComplete()) {
                    events.publishEvent(new PieceCompleted(pet.getId()));
                }
            }
        }
        pet.clearPiecePending();
    }

    /** 화면에 그릴 진행도. 3층 전이면 null. */
    public ZzalPiece find(Long petId) {
        return repository.findById(petId).orElse(null);
    }

    /** 3층이 열릴 때 줄을 만든다. 이미 있으면 그대로. */
    public ZzalPiece open(Long petId) {
        return repository.findById(petId).orElseGet(() -> repository.save(ZzalPiece.of(petId)));
    }

    private ZzalPiece rowOf(ZzalPet pet) {
        return open(pet.getId());
    }
}
