package org.krev.metasearch.runtime.model;
import java.util.List;

public record SearchResult(
        List<Product> products,
        List<Offer> offers,
        List<Review> reviews
) {
    public SearchResult {
        products = List.copyOf(products);
        offers = List.copyOf(offers);
        reviews = List.copyOf(reviews);
    }
}
