package com.fluxupi.repayment;

/**
 * Turns a set of {@link RepaymentTerms} into a {@link RepaymentPlan}.
 *
 * <p>The two implementations model the two ways consumer credit is priced in
 * practice:
 * <ul>
 *   <li>{@link FlatRateStrategy} — interest on the full original principal for
 *       every month, so the headline "1.5% per month" costs far more than it
 *       sounds;</li>
 *   <li>{@link ReducingBalanceStrategy} — interest only on what is still owed,
 *       the arithmetic behind a normal amortised EMI.</li>
 * </ul>
 *
 * <p>Strategies are chosen per lender via {@link InterestStrategyFactory}, so a
 * lender's pricing model is configuration rather than a branch in a service.
 */
public interface InterestStrategy {

    InterestStrategyType type();

    RepaymentPlan generate(RepaymentTerms terms);
}
