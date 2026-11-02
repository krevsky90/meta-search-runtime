package org.krev.metasearch.runtime.exceptions;

import org.krev.metasearch.runtime.error_model.SourceError;

import java.util.List;

public class AllSourcesUnavailableException extends RuntimeException {

    private final List<SourceError> errors;

    public AllSourcesUnavailableException(List<SourceError> errors) {
        super("All search sources are unavailable");
        this.errors = List.copyOf(errors);
    }

    public List<SourceError> getErrors() {
        return errors;
    }
}