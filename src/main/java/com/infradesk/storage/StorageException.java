package com.infradesk.storage;

/** Failure reading or writing local settings or secrets. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
