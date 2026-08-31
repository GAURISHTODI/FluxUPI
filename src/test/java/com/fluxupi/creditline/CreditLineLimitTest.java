package com.fluxupi.creditline;

import com.fluxupi.TestFixtures;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.common.exception.InsufficientCreditLimitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The limit arithmetic on {@link CreditLine}, tested in isolation from the
 * database. Concurrency around these same methods is covered by
 * {@code ConcurrentSpendIT}.
 */
class CreditLineLimitTest {

    @Test
    void spendingReducesAvailableLimitAndRaisesUtilisation() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));

        line.authorizeSpend(Money.of(2_500));

        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(7_500));
        assertThat(line.getUtilizedLimit()).isEqualByComparingTo(Money.of(2_500));
        assertThat(line.getApprovedLimit()).isEqualByComparingTo(Money.of(10_000));
    }

    @Test
    void spendingExactlyTheAvailableLimitIsAllowed() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));

        line.authorizeSpend(Money.of(10_000));

        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.ZERO);
        assertThat(line.getUtilizedLimit()).isEqualByComparingTo(Money.of(10_000));
    }

    @Test
    void spendingOnePaisaOverTheLimitIsRefused() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));

        assertThatThrownBy(() -> line.authorizeSpend(Money.of("10000.01")))
                .isInstanceOf(InsufficientCreditLimitException.class)
                .hasMessageContaining("10000.01");

        assertThat(line.getAvailableLimit())
                .as("a refused spend must not move the balance")
                .isEqualByComparingTo(Money.of(10_000));
    }

    @ParameterizedTest
    @EnumSource(value = CreditLineStatus.class,
            names = {"PENDING", "APPROVED", "REJECTED", "FROZEN", "CLOSED", "DEFAULTED"})
    @DisplayName("no state other than ACTIVE can authorise a spend")
    void nonActiveLinesCannotSpend(CreditLineStatus status) {
        CreditLine line = TestFixtures.creditLineIn(status);

        assertThatThrownBy(() -> line.authorizeSpend(Money.of(100)))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void repaymentRestoresHeadroom() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));
        line.authorizeSpend(Money.of(4_000));

        line.restoreLimit(Money.of(1_500));

        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(7_500));
        assertThat(line.getUtilizedLimit()).isEqualByComparingTo(Money.of(2_500));
    }

    @Test
    void restoringMoreThanWasDrawnIsClampedAtTheApprovedLimit() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));
        line.authorizeSpend(Money.of(1_000));

        line.restoreLimit(Money.of(5_000));

        assertThat(line.getAvailableLimit())
                .as("headroom must never exceed the sanctioned limit")
                .isEqualByComparingTo(Money.of(10_000));
        assertThat(line.getUtilizedLimit()).isEqualByComparingTo(Money.ZERO);
    }

    @Test
    void zeroAndNegativeAmountsAreRejectedOnBothSides() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));

        assertThatThrownBy(() -> line.authorizeSpend(Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.authorizeSpend(Money.of(-100))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.restoreLimit(Money.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> line.restoreLimit(Money.of(-100))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void amountsAreNormalisedToPaisaSoSubPaisaDustCannotAccumulate() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(10_000));

        line.authorizeSpend(new BigDecimal("1000.004"));

        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(9_000));
        assertThat(line.getAvailableLimit().scale()).isEqualTo(2);
    }

    @Test
    void manySmallSpendsSumExactlyWithNoFloatingPointDrift() {
        CreditLine line = TestFixtures.activeCreditLine(Money.of(1_000));
        for (int i = 0; i < 1_000; i++) {
            line.authorizeSpend(Money.of("0.10"));
        }

        assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(900));
        assertThat(line.getUtilizedLimit()).isEqualByComparingTo(Money.of(100));
    }
}
