# Meta Search Runtime

A Java-based meta-search runtime that orchestrates multiple search and recommendation services.

## Project structure

The project is organized as a Maven multi-module application.

| Module | Responsibility |
|---|---|
| `search-api` | Shared Protobuf contracts and generated gRPC API |
| `search-runtime` | Search orchestration and result processing |
| `mock-text-service` | Mock downstream text-search service |
| `mock-recommendation-service` | Mock downstream recommendation service |
| `config-publisher` | Configuration publishing component |

## API contracts

All Protobuf contracts are defined in `search-api/src/main/proto`.

```text
search-api/src/main/proto/
├── common/v1/
│   └── common.proto
├── search/v1/
│   └── search.proto
├── textsearch/v1/
│   └── text_search.proto
└── recommendations/v1/
    └── recommendations.proto
```

### Shared messages

`common.proto` defines reusable message types:

- `Product` — product identifier and name.
- `ScoredProduct` — a product with its relevance score.

These types are shared by the external Search API and both downstream APIs.

### Search API

The external API exposes the `SearchService` gRPC service.

```protobuf
service SearchService {
  rpc Search(SearchRequest) returns (SearchResponse);
}
```

Example request:

```json
{
  "query": "wireless headphones",
  "limit": 10
}
```

Example response:

```json
{
  "results": [
    {
      "product": {
        "id": "product-123",
        "name": "Wireless Headphones"
      },
      "score": 0.95
    },
    {
      "product": {
        "id": "product-456",
        "name": "Bluetooth Headset"
      },
      "score": 0.87
    }
  ]
}
```

### Text Search API

The `TextSearchService` provides text-based product search.

```protobuf
service TextSearchService {
  rpc Search(TextSearchRequest) returns (TextSearchResponse);
}
```

Example request:

```json
{
  "query": "wireless headphones",
  "limit": 10
}
```

Example response:

```json
{
  "results": [
    {
      "product": {
        "id": "product-123",
        "name": "Wireless Headphones"
      },
      "score": 0.95
    }
  ]
}
```

### Recommendations API

The `RecommendationsService` provides query-based product recommendations.

```protobuf
service RecommendationsService {
  rpc Recommend(RecommendationsRequest) returns (RecommendationsResponse);
}
```

Example request:

```json
{
  "query": "wireless headphones",
  "limit": 5
}
```

Example response:

```json
{
  "results": [
    {
      "product": {
        "id": "product-789",
        "name": "Noise Cancelling Headphones"
      },
      "score": 0.91
    }
  ]
}
```

The examples above illustrate the JSON representation of Protobuf messages. They are contract examples, not serialized gRPC wire messages.

## Protobuf and gRPC code generation

The `search-api` module generates Java sources from `.proto` files during the Maven build.

Two generation goals are configured:

| Maven goal | Generated code |
|---|---|
| `protobuf:compile` | Protobuf message classes |
| `protobuf:compile-custom` | gRPC services and client/server stubs |

Generated sources are placed under:

```text
search-api/target/generated-sources/protobuf/
├── java/
└── grpc-java/
```

The generated sources are build artifacts and should not be edited manually or committed to version control.

The build downloads the required `protoc` and gRPC compiler executables automatically. No manual Protobuf compiler installation is required.

## Build

Requirements:

- JDK 25
- Maven

Build the entire project:

```bash
mvn clean verify
```

Build only the API module:

```bash
mvn -pl search-api clean compile
```

Build a module together with its required upstream modules:

```bash
mvn -pl search-runtime -am clean verify
```

## Architecture principles

- API contracts are owned by `search-api`.
- Runtime and downstream services depend on shared API contracts.
- API contracts do not depend on runtime implementation.
- Shared Protobuf messages are defined once and imported by other contracts.
- Protobuf packages and Java packages are explicitly versioned with `v1`.
- Generated Java sources are produced during the Maven build.