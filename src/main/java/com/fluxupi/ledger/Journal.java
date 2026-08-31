package com.fluxupi.ledger;

import com.fluxupi.common.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The accounting rules of FluxUPI, written out as three small methods.
 *
 * <p>This is the file to read when someone asks "what actually happens to the
 * books when a user spends?". Nothing else in the codebase decides which
 * accounts move — services decide <em>whether</em> a movement happens, this
 * class decides <em>what</em> it looks like.
 */
public final class Journal {

    private Journal() {
    }

    /**
     * A drawdown. The borrower now owes us the money (asset up) and we now owe
     * the merchant the same amount (liability up).
     *
     * <pre>
     *   DEBIT   CUSTOMER_RECEIVABLE   2000.00
     *   CREDIT  MERCHANT_PAYABLE      2000.00
     * </pre>
     */
    public static List<LedgerPosting> forSpend(BigDecimal amount, String payeeVpa) {
        String narrative = payeeVpa == null ? "UPI spend" : "UPI spend to " + payeeVpa;
        return List.of(
                LedgerPosting.debit(LedgerAccount.CUSTOMER_RECEIVABLE, amount, narrative),
                LedgerPosting.credit(LedgerAccount.MERCHANT_PAYABLE, amount, narrative)
        );
    }

    /**
     * Unwinding a spend. Exactly {@link #forSpend} with both sides flipped —
     * and written as new rows, never by deleting the originals.
     */
    public static List<LedgerPosting> forReversal(BigDecimal amount, String payeeVpa, String reason) {
        String narrative = "Reversal" + (reason == null ? "" : ": " + reason)
                + (payeeVpa == null ? "" : " (" + payeeVpa + ")");
        return List.of(
                LedgerPosting.debit(LedgerAccount.MERCHANT_PAYABLE, amount, narrative),
                LedgerPosting.credit(LedgerAccount.CUSTOMER_RECEIVABLE, amount, narrative)
        );
    }

    /**
     * A repayment, split into what it settles. Cash comes in as one debit; the
     * credits recognise principal recovery and income separately, because
     * "₹2,150 received" tells you nothing useful while "₹2,000 principal +
     * ₹150 interest" tells you the whole story.
     *
     * <pre>
     *   DEBIT   SETTLEMENT_CASH        2150.00
     *   CREDIT  CUSTOMER_RECEIVABLE    2000.00
     *   CREDIT  INTEREST_INCOME         150.00
     * </pre>
     *
     * Zero-valued components are omitted rather than written as zero rows.
     */
    public static List<LedgerPosting> forRepayment(BigDecimal principal, BigDecimal interest, BigDecimal fees) {
        BigDecimal principalPart = Money.normalize(principal == null ? Money.ZERO : principal);
        BigDecimal interestPart = Money.normalize(interest == null ? Money.ZERO : interest);
        BigDecimal feePart = Money.normalize(fees == null ? Money.ZERO : fees);
        BigDecimal total = Money.normalize(principalPart.add(interestPart).add(feePart));

        if (!Money.isPositive(total)) {
            throw new IllegalArgumentException("A repayment must settle a positive amount");
        }

        String narrative = "Repayment received";
        List<LedgerPosting> postings = new ArrayList<>(4);
        postings.add(LedgerPosting.debit(LedgerAccount.SETTLEMENT_CASH, total, narrative));
        if (Money.isPositive(principalPart)) {
            postings.add(LedgerPosting.credit(LedgerAccount.CUSTOMER_RECEIVABLE, principalPart, "Principal recovered"));
        }
        if (Money.isPositive(interestPart)) {
            postings.add(LedgerPosting.credit(LedgerAccount.INTEREST_INCOME, interestPart, "Interest earned"));
        }
        if (Money.isPositive(feePart)) {
            postings.add(LedgerPosting.credit(LedgerAccount.FEE_INCOME, feePart, "Fees earned"));
        }
        return List.copyOf(postings);
    }
}
