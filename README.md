# Meta Search Runtime

A Java 25 Maven multi-module project for experimenting with a meta-search runtime.

## Modules

| Module | Responsibility |
|---|---|
| `search-api` | Shared API contracts; the home for Protobuf/gRPC definitions |
| `search-runtime` | Meta-search orchestration and runtime logic |
| `mock-text-service` | Mock downstream text-search service |
| `mock-recommendation-service` | Mock downstream recommendation service |
| `config-publisher` | Configuration publication component |

## Requirements

- JDK 25
- Maven 3.9.9 or newer

## Build

Run from the repository root:

```bash
mvn clean verify
```

## Project coordinates

- Group ID: `org.krev.metasearch`
- Artifact ID: `meta-search-runtime`
- Version: `1.0-SNAPSHOT`
 