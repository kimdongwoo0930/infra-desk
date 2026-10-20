package com.infradesk.storage;

/** 로컬 설정이나 비밀값을 읽거나 쓰는 데 실패했다. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
