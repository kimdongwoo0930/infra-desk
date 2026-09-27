package com.infradesk.alert;

/** Delivers alerts. Blocking; call off the EDT. */
public interface Notifier {

    /** @throws AlertException when delivery fails, with a user-facing message */
    void send(Alert alert);
}
