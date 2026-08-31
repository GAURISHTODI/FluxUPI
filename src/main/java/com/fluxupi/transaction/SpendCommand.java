package com.fluxupi.transaction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A request to draw down against a credit line.
 *
 * @param idempotencyKey client-generated; replaying it returns the original
 *                       result instead of spending twice
 */
public record SpendCommand(UUID creditLineId,
                           BigDecimal amount,
                           String payeeVpa,
                           String description,
                           String idempotencyKey) {
}
