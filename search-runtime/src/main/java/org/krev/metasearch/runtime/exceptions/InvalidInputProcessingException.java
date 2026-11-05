package org.krev.metasearch.runtime.exceptions;

public class InvalidInputProcessingException extends RuntimeException {
    public InvalidInputProcessingException(String message) {
        super(message);
    }

    public InvalidInputProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
