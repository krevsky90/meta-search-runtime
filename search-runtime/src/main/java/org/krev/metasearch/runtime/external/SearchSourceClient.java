package org.krev.metasearch.runtime.external;

import org.krev.metasearch.runtime.model.Offer;
import org.krev.metasearch.runtime.model.Product;
import org.krev.metasearch.runtime.model.Review;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface SearchSourceClient {
    SourceCall<List<Product>> searchCatalog(String query);

    SourceCall<List<Offer>> searchOffers(String query);

    SourceCall<List<Review>> searchReviews(String query);
}
