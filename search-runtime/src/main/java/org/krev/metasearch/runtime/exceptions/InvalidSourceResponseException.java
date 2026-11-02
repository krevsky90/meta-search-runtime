package org.krev.metasearch.runtime.exceptions;

public class InvalidSourceResponseException extends RuntimeException {

    public InvalidSourceResponseException(String message) {
        super(message);
    }

    public InvalidSourceResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}