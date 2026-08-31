package com.fluxupi.repayment;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves an {@link InterestStrategyType} to the matching {@link InterestStrategy}
 * bean.
 *
 * <p>Spring injects every {@code InterestStrategy} on the classpath; the map is
 * built once and asserted complete, so a new strategy type without an
 * implementation fails at startup rather than when the first loan of that type
 * is priced.
 */
@Component
public class InterestStrategyFactory {

    private final Map<InterestStrategyType, InterestStrategy> byType =
            new EnumMap<>(InterestStrategyType.class);

    public InterestStrategyFactory(List<InterestStrategy> strategies) {
        for (InterestStrategy strategy : strategies) {
            InterestStrategy previous = byType.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two InterestStrategy beans claim type " + strategy.type());
            }
        }
        for (InterestStrategyType type : InterestStrategyType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("No InterestStrategy bean for type " + type);
            }
        }
    }

    public InterestStrategy forType(InterestStrategyType type) {
        return byType.get(type);
    }
}
