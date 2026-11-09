package org.krev.metasearch.runtime.external;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Review;

/**
 * stub class
 */
public final class UnconfiguredSearchSourceClient implements SearchSourceClient {
    @Override
    public SourceCall<List<Product>> searchCatalog(String query) {
        return unavailable("catalog");
    }

    @Override
    public SourceCall<List<Offer>> searchOffers(String query) {
        return unavailable("offers");
    }

    @Override
    public SourceCall<List<Review>> searchReviews(String query) {
        return unavailable("reviews");
    }

    private <T> SourceCall<List<T>> unavailable(String source) {
        CompletableFuture<List<T>> result = CompletableFuture.failedFuture(
                new IllegalStateException(
                        "Search source is not configured: " + source
                )
        );

        return new HttpSourceCall<>(result, () -> {
            // No transport exists yet.
        });
    }
}