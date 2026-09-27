package com.infradesk.ssh;

/** SSH failure with a user-facing Korean message. */
public class SshException extends RuntimeException {

    public enum Kind { CONNECT, HOST_KEY_REJECTED, HOST_KEY_CHANGED, AUTH, KEY_FORMAT, CHANNEL }

    private final Kind kind;

    public SshException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public SshException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
