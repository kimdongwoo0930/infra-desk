package com.infradesk.ssh;

/** 사용자에게 보여줄 한국어 메시지가 달린 SSH 실패. */
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
