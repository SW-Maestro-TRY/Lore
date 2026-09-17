package com.lore.zzal.guard;

import com.lore.common.exception.BusinessException;

/**
 * 부화가 <b>막혔다</b>. 평범한 업무 예외와 같은 길로 나가지만({@code GlobalExceptionHandler} 가
 * 409 + 오류 코드로 바꾼다), 어느 사유였는지를 들고 있는다.
 *
 * <h3>★★ 왜 사유를 들고 다니나 — 기록을 오류 코드 문자열로 되짚지 않으려고</h3>
 * 막힌 사실은 {@code zzal_event} 에 남겨야 하는데, 그 기록은 <b>막은 트랜잭션 안에서 할 수 없다</b>
 * (거절은 롤백이라 같이 지워진다). 그래서 트랜잭션 밖(컨트롤러)에서 남기는데, 거기서
 * "무슨 코드였더라" 를 이름 문자열로 되짚으면 코드가 하나 늘 때 <b>그 줄만 조용히 빠진다</b>.
 * 사유를 예외에 실어 보내면 되짚을 일이 없다.
 */
public class HatchBlockedException extends BusinessException {

    private final transient HatchBlock block;

    public HatchBlockedException(HatchBlock block, String message) {
        super(block.errorCode(), message);
        this.block = block;
    }

    public HatchBlock getBlock() {
        return block;
    }
}
