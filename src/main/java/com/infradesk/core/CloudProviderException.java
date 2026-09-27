package com.infradesk.core;

/** Wraps provider SDK failures so callers never depend on SDK exception types. */
public class CloudProviderException extends RuntimeException {

    public CloudProviderException(String message) {
        super(message);
    }

    public CloudProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
