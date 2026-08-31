package com.fluxupi.ledger;

import com.fluxupi.common.Money;
import com.fluxupi.common.exception.LedgerImbalanceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accounting rules, tested without a database. Every journal produced by
 * {@link Journal} must pass {@link LedgerService#requireBalanced}.
 */
class JournalTest {

    @Test
    @DisplayName("a spend debits the receivable and credits the merchant, equally")
    void spendJournalBalances() {
        List<LedgerPosting> postings = Journal.forSpend(Money.of(2_000), "shop@fluxbank");

        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).account()).isEqualTo(LedgerAccount.CUSTOMER_RECEIVABLE);
        assertThat(postings.get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(postings.get(1).account()).isEqualTo(LedgerAccount.MERCHANT_PAYABLE);
        assertThat(postings.get(1).direction()).isEqualTo(EntryDirection.CREDIT);
        assertThatDoesNotThrowRequireBalanced(postings);
    }

    @Test
    @DisplayName("a reversal is the spend journal with both sides flipped")
    void reversalJournalIsTheMirrorOfASpend() {
        List<LedgerPosting> spend = Journal.forSpend(Money.of(2_000), "shop@fluxbank");
        List<LedgerPosting> reversal = Journal.forReversal(Money.of(2_000), "shop@fluxbank", "dispute");

        assertThat(reversal.get(0).account()).isEqualTo(spend.get(1).account());
        assertThat(reversal.get(0).direction()).isEqualTo(spend.get(1).direction().opposite());
        assertThat(reversal.get(1).account()).isEqualTo(spend.get(0).account());
        assertThatDoesNotThrowRequireBalanced(reversal);
    }

    @Test
    @DisplayName("a repayment splits cash-in into principal, interest and fee credits")
    void repaymentJournalSplitsComponents() {
        List<LedgerPosting> postings = Journal.forRepayment(Money.of(2_000), Money.of(150), Money.of(25));

        assertThat(postings).hasSize(4);
        assertThat(postings.get(0)).satisfies(p -> {
            assertThat(p.account()).isEqualTo(LedgerAccount.SETTLEMENT_CASH);
            assertThat(p.direction()).isEqualTo(EntryDirection.DEBIT);
            assertThat(p.amount()).isEqualByComparingTo(Money.of(2_175));
        });
        assertThat(postings).extracting(LedgerPosting::account).contains(
                LedgerAccount.CUSTOMER_RECEIVABLE, LedgerAccount.INTEREST_INCOME, LedgerAccount.FEE_INCOME);
        assertThatDoesNotThrowRequireBalanced(postings);
    }

    @Test
    @DisplayName("a repayment with no interest or fee is a clean two-line entry")
    void repaymentWithPrincipalOnlyHasTwoLines() {
        List<LedgerPosting> postings = Journal.forRepayment(Money.of(2_000), Money.ZERO, Money.ZERO);

        assertThat(postings).hasSize(2);
        assertThatDoesNotThrowRequireBalanced(postings);
    }

    @Test
    @DisplayName("requireBalanced rejects a one-sided entry")
    void oneSidedEntryIsRejected() {
        List<LedgerPosting> lopsided = List.of(
                LedgerPosting.debit(LedgerAccount.CUSTOMER_RECEIVABLE, Money.of(100), "x"),
                LedgerPosting.credit(LedgerAccount.MERCHANT_PAYABLE, Money.of(90), "x"));

        assertThatThrownBy(() -> LedgerService.requireBalanced("txn-x", lopsided))
                .isInstanceOf(LedgerImbalanceException.class)
                .hasMessageContaining("100.00")
                .hasMessageContaining("90.00");
    }

    @Test
    @DisplayName("requireBalanced rejects a single-line entry")
    void singleLineEntryIsRejected() {
        List<LedgerPosting> single = List.of(
                LedgerPosting.debit(LedgerAccount.CUSTOMER_RECEIVABLE, Money.of(100), "x"));

        assertThatThrownBy(() -> LedgerService.requireBalanced("txn-x", single))
                .isInstanceOf(LedgerImbalanceException.class)
                .hasMessageContaining("at least two");
    }

    @Test
    @DisplayName("a posting amount must be positive")
    void postingAmountMustBePositive() {
        assertThatThrownBy(() -> LedgerPosting.debit(LedgerAccount.CUSTOMER_RECEIVABLE, Money.ZERO, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerPosting.credit(LedgerAccount.MERCHANT_PAYABLE, Money.of(-1), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("normal balances follow standard accounting")
    void accountNormalBalancesAreCorrect() {
        assertThat(LedgerAccount.CUSTOMER_RECEIVABLE.normalBalance()).isEqualTo(EntryDirection.DEBIT);
        assertThat(LedgerAccount.SETTLEMENT_CASH.normalBalance()).isEqualTo(EntryDirection.DEBIT);
        assertThat(LedgerAccount.MERCHANT_PAYABLE.normalBalance()).isEqualTo(EntryDirection.CREDIT);
        assertThat(LedgerAccount.LENDER_PAYABLE.normalBalance()).isEqualTo(EntryDirection.CREDIT);
        assertThat(LedgerAccount.INTEREST_INCOME.normalBalance()).isEqualTo(EntryDirection.CREDIT);
        assertThat(LedgerAccount.FEE_INCOME.normalBalance()).isEqualTo(EntryDirection.CREDIT);
    }

    private static void assertThatDoesNotThrowRequireBalanced(List<LedgerPosting> postings) {
        BigDecimal signedSum = postings.stream()
                .map(p -> p.isDebit() ? p.amount() : p.amount().negate())
                .reduce(Money.ZERO, BigDecimal::add);
        assertThat(signedSum).isEqualByComparingTo(Money.ZERO);
        LedgerService.requireBalanced("test", postings);
    }
}
