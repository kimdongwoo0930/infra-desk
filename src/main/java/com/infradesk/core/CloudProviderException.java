package com.infradesk.core;

/** provider SDK 실패를 감싸서, 호출하는 쪽이 SDK 예외 타입에 의존하지 않게 한다. */
public class CloudProviderException extends RuntimeException {

    public CloudProviderException(String message) {
        super(message);
    }

    public CloudProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
