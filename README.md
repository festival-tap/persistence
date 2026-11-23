# festival-tap/persistence

Swappable **Java 24** persistence adapters implementing the outbound repository and event-store ports of the **festival-tap** platform for both **pure-JDK in-memory** and **AWS DynamoDB** backends.

This module acts as the single persistence boundary for the entire platform—wrapping `sugarcoat-labs/persistence-engines-core` (`Jdk.inMemory()`) and `sugarcoat-labs/persistence-engines-dynamodb` (`DynamoDb.client(...)`) once behind clean `com.festivaltap.core.ports.out` adapters so `core-domain`, `ingress`, and `cloud-deploy` never couple directly to storage engines or AWS SDK internals.

---

## Architectural Role in the Hexagon

```text
  festival-tap/core-ports        (Domain models, exceptions, outbound port contracts)
          ▲
          │  (requires transitive)
          │
  festival-tap/persistence       (Implements TapRegistryPort, TapEventFetchingPort, TapEventPublishingPort)
          │
          ├──▶ Persistence.jdk()              ──▶ Wraps persistence-engines-core (Jdk.inMemory())
          └──▶ Persistence.aws(client, ...)   ──▶ Wraps persistence-engines-dynamodb (DynamoDb.client(...))
```

### Design Principles
1. **One Behavioral Contract, Two Realizations:** Both `Persistence.jdk()` and `Persistence.aws(...)` adapt the exact same `EventStreamEngine` and `StateStoreEngine` contracts from `sugarcoat-labs`, guaranteeing 100% behavioral parity between local tests and AWS production deployments.
2. **Strict JPMS Encapsulation:** Internal adapter and codec classes remain unexported; consumers interact only with `Persistence` and the `core-ports` interfaces (`TapRegistryPort`, `TapEventFetchingPort`, `TapEventPublishingPort`).
3. **Domain Exception Translation:** Storage-level concurrency and duplicate errors (`OptimisticConcurrencyException`, `DuplicateEventException`, `DuplicateEntityException`) are caught and translated into `com.festivaltap.core.exception.SequenceConflictException` (or `InvalidTapCommandException`) at the adapter boundary.
4. **Zero-Framework Serialization for DynamoDB:** Uses lightweight `DynamoDbCodec<Tap>` and `DynamoDbCodec<TapEvent>` codecs without introducing Jackson or reflection dependencies into the core hexagon.

---

## Implemented Outbound Ports

| Port Interface (`core-ports`) | Underlying Sugarcoat Engine | Responsibility |
| :--- | :--- | :--- |
| `TapRegistryPort` | `StateStoreEngine<String, Tap>` | Stores and retrieves static `Tap` dispenser configurations (`save`, `findById`, `findAll`, `exists`). |
| `TapEventFetchingPort` | `EventStreamEngine<String, UUID, TapEvent>` | Streams chronological `TapEvent` history and queries the current sequence number per tap. |
| `TapEventPublishingPort` | `EventStreamEngine<String, UUID, TapEvent>` | Atomically appends `TapEvent` records with optimistic concurrency checks (`expectedSequenceNumber`). |

---

## Usage

### 1. Pure-JDK In-Memory Backend (Unit, Contract & Local Integration Tests)
```java
Persistence.Adapters adapters = Persistence.jdk();

TapRegistryPort registry = adapters.tapRegistry();
TapEventFetchingPort eventFetching = adapters.tapEventFetching();
TapEventPublishingPort eventPublishing = adapters.tapEventPublishing();
```

### 2. AWS DynamoDB Backend (Production & Cloud E2E Suites)
```java
DynamoDbClient dynamoDbClient = DynamoDbClient.create();
Persistence.Adapters adapters = Persistence.aws(dynamoDbClient, "tap-configs", "tap-events");

TapRegistryPort registry = adapters.tapRegistry();
TapEventFetchingPort eventFetching = adapters.tapEventFetching();
TapEventPublishingPort eventPublishing = adapters.tapEventPublishing();
```

---

## Repository Structure

```text
festival-tap/persistence/
├── pom.xml                                                 # Java 24, core-ports, persistence-engines-core & dynamodb
├── README.md
└── src/
    ├── main/java/
    │   ├── module-info.java                                # module com.festivaltap.persistence
    │   └── com/festivaltap/persistence/
    │       ├── Persistence.java                            # Public entry point: Persistence.jdk() & Persistence.aws(...)
    │       └── adapter/                                    # Internal engine adapters & codecs (not exported)
    │           ├── EngineTapRegistryAdapter.java
    │           ├── EngineTapEventStoreAdapter.java
    │           └── TapDynamoDbCodecs.java
    └── test/java/
        └── com/festivaltap/persistence/
            └── PersistenceIntegrationTest.java             # Integration verification for JDK & DynamoDB adapters
```

---

## Build & Local Installation

Ensure `festival-tap/core` (`core-ports`), `sugarcoat-labs/persistence-engines-core`, and `sugarcoat-labs/persistence-engines-dynamodb` are installed in `~/.m2/repository`, then build and install `persistence`:

```bash
mvn clean install
```
