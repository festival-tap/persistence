package com.festivaltap.persistence;

import com.festivaltap.core.model.Tap;
import com.festivaltap.core.model.TapEvent;
import com.festivaltap.core.ports.out.TapEventFetchingPort;
import com.festivaltap.core.ports.out.TapEventPublishingPort;
import com.festivaltap.core.ports.out.TapRegistryPort;
import com.festivaltap.persistence.adapter.EngineTapEventStoreAdapter;
import com.festivaltap.persistence.adapter.EngineTapRegistryAdapter;
import com.sugarcoatlabs.persistence.Jdk;
import com.sugarcoatlabs.persistence.PersistenceEngines;
import com.sugarcoatlabs.persistence.dynamodb.DynamoDb;
import com.sugarcoatlabs.persistence.dynamodb.DynamoDbCodec;
import com.sugarcoatlabs.persistence.event.EventStreamEngine;
import com.sugarcoatlabs.persistence.state.StateStoreEngine;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.Objects;
import java.util.UUID;

/**
 * Unified public factory for instantiating swappable {@code festival-tap} outbound persistence
 * adapters backed by either pure-JDK in-memory storage engines or AWS DynamoDB.
 *
 * <p>This class serves as the single entry point exported by the {@code com.festivaltap.persistence}
 * JPMS module. Internal adapter implementations remain encapsulated, ensuring consuming modules
 * ({@code core-domain}, {@code ingress}, {@code cloud-deploy}) depend strictly on the outbound
 * port contracts defined in {@code com.festivaltap.core.ports.out}:</p>
 * <ul>
 *   <li>{@link TapRegistryPort} — Static dispenser asset registration and lookup.</li>
 *   <li>{@link TapEventFetchingPort} — Chronological event stream replay and sequence queries.</li>
 *   <li>{@link TapEventPublishingPort} — Atomic event appending with optimistic concurrency control.</li>
 * </ul>
 *
 * <h2>Quick Start: Pure-JDK In-Memory Backend</h2>
 * <p>Ideal for domain behavioral suites, consumer-driven contract tests, and local development
 * without external infrastructure:</p>
 * {@snippet :
 * Persistence.Adapters persistence = Persistence.jdk();
 *
 * TapService tapService = new TapService(persistence.tapRegistry(), idGenerator);
 * TapEventIngressService ingressService = new TapEventIngressService(
 *         persistence.tapRegistry(),
 *         persistence.tapEventFetching(),
 *         persistence.tapEventPublishing(),
 *         idGenerator
 * );
 * }
 *
 * <h2>Production: AWS DynamoDB Backend</h2>
 * <p>Backed by {@link DynamoDb#client(DynamoDbClient)} for durable, horizontally scalable
 * deployments:</p>
 * {@snippet :
 * try (DynamoDbClient dynamoClient = DynamoDbClient.create()) {
 *     Persistence.Adapters persistence = Persistence.aws(
 *             dynamoClient,
 *             "festival-tap-registry",
 *             "festival-tap-events",
 *             tapCodec,
 *             tapEventCodec
 *     );
 *
 *     TapRegistryPort registry = persistence.tapRegistry();
 *     TapEventPublishingPort publisher = persistence.tapEventPublishing();
 * }
 * }
 *
 * @see Adapters
 * @see TapRegistryPort
 * @see TapEventFetchingPort
 * @see TapEventPublishingPort
 */
public final class Persistence {

    private Persistence() {
    }

    /**
     * Immutable bundle of wired outbound persistence ports sharing consistent underlying
     * storage engines.
     *
     * <p>Within a single {@code Adapters} instance, {@link #tapEventFetching()} and
     * {@link #tapEventPublishing()} operate against the exact same underlying event stream
     * engine so appended events are immediately visible to subsequent stream reads.</p>
     *
     * @param tapRegistry        outbound port for storing and querying {@link Tap} configurations
     * @param tapEventFetching   outbound read-side port for streaming {@link TapEvent} history
     * @param tapEventPublishing outbound write-side port for atomically appending {@link TapEvent} records
     */
    public record Adapters(
            TapRegistryPort tapRegistry,
            TapEventFetchingPort tapEventFetching,
            TapEventPublishingPort tapEventPublishing
    ) {
        /**
         * Validates that all wired port references are non-null.
         *
         * @throws NullPointerException if any port reference is {@code null}
         */
        public Adapters {
            Objects.requireNonNull(tapRegistry, "tapRegistry must not be null");
            Objects.requireNonNull(tapEventFetching, "tapEventFetching must not be null");
            Objects.requireNonNull(tapEventPublishing, "tapEventPublishing must not be null");
        }
    }

    /**
     * Creates a fresh, isolated set of pure-JDK in-memory persistence adapters backed by
     * {@link Jdk#inMemory()}.
     *
     * <p>Each invocation provisions independent in-memory state store and event stream
     * instances, making it safe to call inside JUnit {@code @BeforeEach} setup methods for
     * complete test isolation without shared state across test cases.</p>
     *
     * <h3>Example</h3>
     * {@snippet :
     * Persistence.Adapters adapters = Persistence.jdk();
     * adapters.tapRegistry().save(new Tap("tap-01", 0.05));
     * boolean exists = adapters.tapRegistry().exists("tap-01"); // true
     * }
     *
     * @return a newly initialized {@link Adapters} bundle backed by thread-safe in-memory engines
     */
    public static Adapters jdk() {
        StateStoreEngine<String, Tap> stateStore = PersistenceEngines.using(Jdk.inMemory()).stateStore();
        EventStreamEngine<String, UUID, TapEvent> eventStream = PersistenceEngines.using(Jdk.inMemory()).eventStream();

        EngineTapRegistryAdapter registryAdapter = new EngineTapRegistryAdapter(stateStore);
        EngineTapEventStoreAdapter eventStoreAdapter = new EngineTapEventStoreAdapter(eventStream);

        return new Adapters(registryAdapter, eventStoreAdapter, eventStoreAdapter);
    }

    /**
     * Creates a set of AWS DynamoDB persistence adapters backed by
     * {@link DynamoDb#client(DynamoDbClient)}.
     *
     * <p>Caller retains lifecycle ownership of the supplied {@link DynamoDbClient} and is
     * responsible for closing it on application shutdown. The target DynamoDB tables must
     * already exist and match the key schema expected by {@code persistence-engines-dynamodb}.</p>
     *
     * <h3>Example</h3>
     * {@snippet :
     * DynamoDbClient client = DynamoDbClient.create();
     * Persistence.Adapters adapters = Persistence.aws(
     *         client,
     *         "tap-configs",
     *         "tap-events",
     *         tapCodec,
     *         tapEventCodec
     * );
     * }
     *
     * @param client                  configured AWS SDK v2 {@link DynamoDbClient} instance
     * @param tapRegistryTableName    DynamoDB table name storing {@link Tap} configurations
     * @param tapEventStreamTableName DynamoDB table name storing append-only {@link TapEvent} streams
     * @param tapCodec                serialization codec converting {@link Tap} records to/from DynamoDB attributes
     * @param tapEventCodec           serialization codec converting {@link TapEvent} records to/from DynamoDB attributes
     * @return an {@link Adapters} bundle wired to the specified AWS DynamoDB tables
     * @throws NullPointerException if any parameter is {@code null}
     */
    public static Adapters aws(
            DynamoDbClient client,
            String tapRegistryTableName,
            String tapEventStreamTableName,
            DynamoDbCodec<Tap> tapCodec,
            DynamoDbCodec<TapEvent> tapEventCodec
    ) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(tapRegistryTableName, "tapRegistryTableName must not be null");
        Objects.requireNonNull(tapEventStreamTableName, "tapEventStreamTableName must not be null");
        Objects.requireNonNull(tapCodec, "tapCodec must not be null");
        Objects.requireNonNull(tapEventCodec, "tapEventCodec must not be null");

        StateStoreEngine<String, Tap> stateStore = PersistenceEngines.using(DynamoDb.client(client))
                .table(tapRegistryTableName)
                .stateStore(DynamoDbCodec.strings(), tapCodec);

        EventStreamEngine<String, UUID, TapEvent> eventStream = PersistenceEngines.using(DynamoDb.client(client))
                .table(tapEventStreamTableName)
                .eventStream(DynamoDbCodec.strings(), DynamoDbCodec.uuids(), tapEventCodec);

        EngineTapRegistryAdapter registryAdapter = new EngineTapRegistryAdapter(stateStore);
        EngineTapEventStoreAdapter eventStoreAdapter = new EngineTapEventStoreAdapter(eventStream);

        return new Adapters(registryAdapter, eventStoreAdapter, eventStoreAdapter);
    }
}
