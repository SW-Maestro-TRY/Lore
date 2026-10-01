package com.lore.piecemaker.credit;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditEventRepository;
import com.lore.common.credit.CreditReason;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 가설 접수와 차감, 판정 실패와 환급을 각각 같은 트랜잭션으로 처리한다.
 * 조회는 공통 Repository를 재사용하고 기록은 PieceMaker 전용 Repository가 맡는다.
 * 호출자의 트랜잭션이 없으면 실행하지 않는다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PieceMakerCreditService {

    private final CreditEventRepository events;
    private final PieceMakerCreditRepository writer;

    public PieceMakerCreditService(CreditEventRepository events, PieceMakerCreditRepository writer) {
        this.events = events;
        this.writer = writer;
    }

    public int spendInCurrentTransaction(Long userId, int amount, String refId, String memo) {
        writer.lockAccount(userId);
        if (amount <= 0 || events.existsByUserIdAndReasonAndDomainAndRefId(
                userId, CreditReason.SPEND, CreditDomain.PIECE_MAKER, refId)) {
            return 0;
        }
        int have = events.balanceOf(userId);
        if (have < amount) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH,
                    "크레딧이 모자랍니다 (필요 " + amount + " · 보유 " + have + ")");
        }
        writer.append(userId, -amount, CreditReason.SPEND.name(), refId, memo, Instant.now());
        return amount;
    }

    public int refundInCurrentTransaction(Long userId, String refId, String memo) {
        writer.lockAccount(userId);
        if (events.existsByUserIdAndReasonAndDomainAndRefId(
                userId, CreditReason.REFUND, CreditDomain.PIECE_MAKER, refId)) {
            return 0;
        }
        int paid = events.findByUserIdAndReasonAndDomainAndRefId(
                        userId, CreditReason.SPEND, CreditDomain.PIECE_MAKER, refId)
                .stream().mapToInt(row -> -row.getDelta()).sum();
        if (paid <= 0) return 0;
        writer.append(userId, paid, CreditReason.REFUND.name(), refId, memo, Instant.now());
        return paid;
    }
}
