package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

/** Something about a repayment or schedule request does not add up. */
public class RepaymentException extends FluxUpiException {

    public RepaymentException(String message) {
        super(message, HttpStatus.UNPROCESSABLE_ENTITY, "REPAYMENT_ERROR");
    }
}
