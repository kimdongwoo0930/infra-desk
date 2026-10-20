package com.infradesk.alert;

/** 한국어 메시지가 달린 전송 실패. 웹훅 URL은 절대 담지 않는다. */
public class AlertException extends RuntimeException {

    public AlertException(String message) {
        super(message);
    }

    public AlertException(String message, Throwable cause) {
        super(message, cause);
    }
}
