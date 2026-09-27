package com.infradesk.alert;

/** Delivery failure with a Korean message. Never contains the webhook URL. */
public class AlertException extends RuntimeException {

    public AlertException(String message) {
        super(message);
    }

    public AlertException(String message, Throwable cause) {
        super(message, cause);
    }
}
