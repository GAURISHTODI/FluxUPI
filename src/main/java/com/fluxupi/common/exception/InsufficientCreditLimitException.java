package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientCreditLimitException extends FluxUpiException {

    public InsufficientCreditLimitException(UUID creditLineId, BigDecimal requested, BigDecimal available) {
        super("Credit line %s has %s available but %s was requested"
                        .formatted(creditLineId, available, requested),
                HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_CREDIT_LIMIT");
    }
}
