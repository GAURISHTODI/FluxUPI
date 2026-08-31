package com.fluxupi.creditline;

import com.fluxupi.TestFixtures;
import com.fluxupi.common.Money;
import com.fluxupi.common.exception.IllegalStateTransitionException;
import com.fluxupi.creditline.state.CreditLineStates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Exhaustive coverage of the credit line state machine.
 *
 * <p>The expected-transition table below is written out by hand and compared
 * against every one of the 49 (from, to) pairs. That is deliberate: if someone
 * derived the expectations from the same code under test, the test would
 * confirm nothing.
 */
class CreditLineStateMachineTest {

    /** The specification, independent of the implementation. */
    private static final Map<CreditLineStatus, Set<CreditLineStatus>> EXPECTED =
            new EnumMap<>(CreditLineStatus.class);

    static {
        EXPECTED.put(CreditLineStatus.PENDING,
                EnumSet.of(CreditLineStatus.APPROVED, CreditLineStatus.REJECTED));
        EXPECTED.put(CreditLineStatus.APPROVED,
                EnumSet.of(CreditLineStatus.ACTIVE, CreditLineStatus.CLOSED));
        EXPECTED.put(CreditLineStatus.REJECTED,
                EnumSet.noneOf(CreditLineStatus.class));
        EXPECTED.put(CreditLineStatus.ACTIVE,
                EnumSet.of(CreditLineStatus.FROZEN, CreditLineStatus.CLOSED, CreditLineStatus.DEFAULTED));
        EXPECTED.put(CreditLineStatus.FROZEN,
                EnumSet.of(CreditLineStatus.ACTIVE, CreditLineStatus.CLOSED, CreditLineStatus.DEFAULTED));
        EXPECTED.put(CreditLineStatus.CLOSED,
                EnumSet.noneOf(CreditLineStatus.class));
        EXPECTED.put(CreditLineStatus.DEFAULTED,
                EnumSet.of(CreditLineStatus.CLOSED));
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> allTransitionPairs() {
        return Stream.of(CreditLineStatus.values())
                .flatMap(from -> Stream.of(CreditLineStatus.values())
                        .map(to -> org.junit.jupiter.params.provider.Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allTransitionPairs")
    @DisplayName("every (from, to) pair matches the specification, including self-transitions")
    void transitionMatrixMatchesSpecification(CreditLineStatus from, CreditLineStatus to) {
        boolean expected = EXPECTED.get(from).contains(to);
        assertThat(CreditLineStates.of(from).canTransitionTo(to))
                .as("%s -> %s should be %s", from, to, expected ? "allowed" : "rejected")
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(CreditLineStatus.class)
    @DisplayName("no state may transition to itself")
    void selfTransitionsAreNeverAllowed(CreditLineStatus status) {
        assertThat(CreditLineStates.of(status).canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(CreditLineStatus.class)
    @DisplayName("every status resolves to a state object reporting its own status")
    void registryIsCompleteAndConsistent(CreditLineStatus status) {
        assertThat(CreditLineStates.of(status).status()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = CreditLineStatus.class, names = {"REJECTED", "CLOSED"})
    @DisplayName("REJECTED and CLOSED are terminal")
    void terminalStatesHaveNoExit(CreditLineStatus status) {
        assertThat(CreditLineStates.of(status).isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = CreditLineStatus.class,
            names = {"PENDING", "APPROVED", "ACTIVE", "FROZEN", "DEFAULTED"})
    @DisplayName("non-terminal states have at least one exit")
    void nonTerminalStatesHaveAnExit(CreditLineStatus status) {
        assertThat(CreditLineStates.of(status).isTerminal()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(CreditLineStatus.class)
    @DisplayName("ACTIVE is the only state that permits spending")
    void onlyActivePermitsSpending(CreditLineStatus status) {
        assertThat(CreditLineStates.of(status).allowsSpending())
                .isEqualTo(status == CreditLineStatus.ACTIVE);
    }

    @Nested
    @DisplayName("transitions driven through the entity")
    class ThroughTheEntity {

        @Test
        void happyPathIsPendingToApprovedToActive() {
            CreditLine line = TestFixtures.pendingCreditLine();
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.PENDING);

            line.approve(Money.of(60_000), "income check passed");
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.APPROVED);
            assertThat(line.getApprovedLimit()).isEqualByComparingTo(Money.of(60_000));
            assertThat(line.getAvailableLimit()).isEqualByComparingTo(Money.of(60_000));
            assertThat(line.getApprovedAt()).isNotNull();

            line.activate();
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.ACTIVE);
            assertThat(line.getActivatedAt()).isNotNull();
            assertThat(line.isSpendable()).isTrue();
        }

        @Test
        void freezeThenUnfreezeReturnsToActive() {
            CreditLine line = TestFixtures.activeCreditLine();

            line.freeze("suspected fraud");
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.FROZEN);
            assertThat(line.isSpendable()).isFalse();
            assertThat(line.getDecisionReason()).isEqualTo("suspected fraud");

            line.unfreeze();
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.ACTIVE);
            assertThat(line.isSpendable()).isTrue();
            assertThat(line.getDecisionReason()).isNull();
        }

        @Test
        void defaultedLineCanStillBeSettledIntoClosed() {
            CreditLine line = TestFixtures.creditLineIn(CreditLineStatus.DEFAULTED);

            assertDoesNotThrow(() -> line.close("settled after default"));
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.CLOSED);
            assertThat(line.getClosedAt()).isNotNull();
        }

        @Test
        void defaultedLineCanNeverBecomeSpendableAgain() {
            CreditLine line = TestFixtures.creditLineIn(CreditLineStatus.DEFAULTED);

            assertThatThrownBy(line::unfreeze)
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("DEFAULTED")
                    .hasMessageContaining("ACTIVE");
        }

        @Test
        void closedLineCannotBeRevived() {
            CreditLine line = TestFixtures.creditLineIn(CreditLineStatus.CLOSED);

            assertThatThrownBy(line::activate).isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(() -> line.freeze("nope")).isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(() -> line.markDefaulted("nope")).isInstanceOf(IllegalStateTransitionException.class);
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.CLOSED);
        }

        @Test
        void rejectedLineIsAFullStop() {
            CreditLine line = TestFixtures.creditLineIn(CreditLineStatus.REJECTED);

            assertThatThrownBy(() -> line.approve(Money.of(10_000), "changed our mind"))
                    .isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(line::activate).isInstanceOf(IllegalStateTransitionException.class);
            assertThatThrownBy(() -> line.close("nope")).isInstanceOf(IllegalStateTransitionException.class);
        }

        @Test
        void pendingLineCannotSkipStraightToActive() {
            CreditLine line = TestFixtures.pendingCreditLine();

            assertThatThrownBy(line::activate)
                    .isInstanceOf(IllegalStateTransitionException.class)
                    .hasMessageContaining("PENDING");
            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.PENDING);
        }

        @Test
        void approvingTwiceIsRejected() {
            CreditLine line = TestFixtures.creditLineIn(CreditLineStatus.APPROVED);

            assertThatThrownBy(() -> line.approve(Money.of(999_999), "double approval"))
                    .isInstanceOf(IllegalStateTransitionException.class);
            assertThat(line.getApprovedLimit()).isEqualByComparingTo(Money.of(100_000));
        }

        @Test
        void approvalWithANonPositiveLimitIsRefusedBeforeAnyStateChange() {
            CreditLine line = TestFixtures.pendingCreditLine();

            assertThatThrownBy(() -> line.approve(Money.of(0), "zero limit"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(line.getStatus())
                    .as("a rejected argument must not leave the line half-transitioned")
                    .isEqualTo(CreditLineStatus.PENDING);
        }

        @Test
        void aFailedTransitionLeavesTheLineExactlyAsItWas() {
            CreditLine line = TestFixtures.activeCreditLine();
            var before = line.getUpdatedAt();

            assertThatThrownBy(() -> line.approve(Money.of(1), "no"))
                    .isInstanceOf(IllegalStateTransitionException.class);

            assertThat(line.getStatus()).isEqualTo(CreditLineStatus.ACTIVE);
            assertThat(line.getUpdatedAt()).isEqualTo(before);
        }
    }
}
