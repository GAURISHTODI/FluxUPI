package com.fluxupi.common.exception;

import com.fluxupi.common.FluxUpiException;
import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends FluxUpiException {

    public ResourceNotFoundException(String resource, Object id) {
        super("%s not found: %s".formatted(resource, id), HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
