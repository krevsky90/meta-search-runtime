package org.krev.metasearch.runtime.error_model;

public enum ProcessingErrorCode {
    INTERNAL_ERROR,
    INVALID_INPUT,
    TIMEOUT     // самостоятельный timeout для CPU-операции
    // В дальнейшем сюда можно добавить:
    // DEDUPLICATE_PRODUCTS;
    // MERGE_RESULTS;
    // ENRICH_RESULTS.
}
