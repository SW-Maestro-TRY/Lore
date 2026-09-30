package com.lore.piecemaker.credit;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import com.lore.piecemaker.support.PieceMakerItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@PieceMakerIntegrationTest
class PieceMakerCreditServiceIT extends PieceMakerItSupport {

    @Autowired PieceMakerCreditService pieceMakerCredits;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void writesRequireACallerTransaction() {
        Long user = newUserId();
        fund(user, 5);

        assertThatThrownBy(() -> pieceMakerCredits.spendInCurrentTransaction(user, 5, "no-tx", "검사"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> pieceMakerCredits.refundInCurrentTransaction(user, "no-tx", "검사"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(balance(user)).isEqualTo(5);
        assertThat(creditRows(user, "SPEND")).isZero();
        assertThat(creditRows(user, "REFUND")).isZero();
    }

    @Test
    void refundRollsBackWithCallerAndCanThenBeRetriedOnce() {
        Long user = newUserId();
        fund(user, 5);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(tx -> pieceMakerCredits.spendInCurrentTransaction(user, 5, "refund-check", "판정"));

        transaction.executeWithoutResult(tx -> {
            assertThat(pieceMakerCredits.refundInCurrentTransaction(user, "refund-check", "판정 실패")).isEqualTo(5);
            tx.setRollbackOnly();
        });
        assertThat(balance(user)).isZero();
        assertThat(creditRows(user, "REFUND")).isZero();

        transaction.executeWithoutResult(tx -> {
            assertThat(pieceMakerCredits.refundInCurrentTransaction(user, "refund-check", "판정 실패")).isEqualTo(5);
            assertThat(pieceMakerCredits.refundInCurrentTransaction(user, "refund-check", "판정 실패")).isZero();
        });
        assertThat(balance(user)).isEqualTo(5);
        assertThat(credits.history(user, 50))
                .filteredOn(row -> row.getReason() == CreditReason.REFUND)
                .singleElement().satisfies(row -> {
                    assertThat(row.getDomain()).isEqualTo(CreditDomain.PIECE_MAKER);
                    assertThat(row.getRefId()).isEqualTo("refund-check");
                    assertThat(row.getMemo()).isEqualTo("판정 실패");
                    assertThat(row.getCreatedAt()).isNotNull();
                });
    }

    @Test
    void concurrentDifferentChargesCannotSpendTheSameBalance() throws Exception {
        Long user = newUserId();
        fund(user, 5);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> spendWhenReleased(user, "first", ready, start));
            var second = executor.submit(() -> spendWhenReleased(user, "second", ready, start));
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(balance(user)).isZero();
        assertThat(creditRows(user, "SPEND")).isEqualTo(1);
    }

    private boolean spendWhenReleased(Long user, String refId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("동시 요청을 시작하지 못했습니다");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    tx -> pieceMakerCredits.spendInCurrentTransaction(user, 5, refId, "동시 접수"));
            return true;
        } catch (BusinessException error) {
            if (error.getErrorCode() != ErrorCode.CREDIT_NOT_ENOUGH) throw error;
            return false;
        }
    }
}
