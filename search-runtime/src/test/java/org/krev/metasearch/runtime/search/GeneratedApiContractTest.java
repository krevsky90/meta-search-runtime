package org.krev.metasearch.runtime.search;

import io.grpc.Channel;
import org.junit.jupiter.api.Test;

import org.krev.metasearch.proto.common.v1.Product;
import org.krev.metasearch.proto.common.v1.ScoredProduct;
import org.krev.metasearch.proto.search.v1.SearchRequest;
import org.krev.metasearch.proto.search.v1.SearchResponse;
import org.krev.metasearch.proto.search.v1.SearchServiceGrpc;
import org.krev.metasearch.proto.textsearch.v1.TextSearchServiceGrpc;
import org.krev.metasearch.proto.recommendations.v1.RecommendationsServiceGrpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GeneratedApiContractTest {
    @Test
    void shouldBuildSearchRequestAndResponse() {
        SearchRequest request = SearchRequest.newBuilder()
                .setQuery("wireless headphones")
                .setLimit(10)
                .build();

        Product product = Product.newBuilder()
                .setId("product-123")
                .setName("Wireless Headphones")
                .build();

        ScoredProduct scoredProduct = ScoredProduct.newBuilder()
                .setProduct(product)
                .setScore(0.95)
                .build();

        SearchResponse response = SearchResponse.newBuilder()
                .addResults(scoredProduct)
                .build();

        assertThat(request.getQuery()).isEqualTo("wireless headphones");
        assertThat(request.getLimit()).isEqualTo(10);

        assertThat(response.getResultsCount()).isEqualTo(1);
        assertThat(response.getResults(0).getProduct().getId())
                .isEqualTo("product-123");
        assertThat(response.getResults(0).getProduct().getName())
                .isEqualTo("Wireless Headphones");
        assertThat(response.getResults(0).getScore())
                .isEqualTo(0.95);
    }

    @Test
    void shouldCreateGrpcStubsFromAnotherModule() {
        Channel channel = mock(Channel.class);

        SearchServiceGrpc.SearchServiceBlockingStub searchStub = SearchServiceGrpc.newBlockingStub(channel);
        TextSearchServiceGrpc.TextSearchServiceBlockingStub textSearchStub = TextSearchServiceGrpc.newBlockingStub(channel);
        RecommendationsServiceGrpc.RecommendationsServiceBlockingStub recommendationsStub = RecommendationsServiceGrpc.newBlockingStub(channel);

        assertThat(searchStub).isNotNull();
        assertThat(textSearchStub).isNotNull();
        assertThat(recommendationsStub).isNotNull();
    }
}