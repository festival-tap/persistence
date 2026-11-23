/**
 * Provides swappable pure-JDK and AWS DynamoDB persistence adapters implementing the
 * outbound repository and event-store ports of the {@code festival-tap} hexagon.
 */
module com.festivaltap.persistence {
    requires transitive com.festivaltap.core.ports;
    requires transitive software.amazon.awssdk.services.dynamodb;
    requires com.sugarcoatlabs.persistence;
    requires com.sugarcoatlabs.persistence.dynamodb;

    exports com.festivaltap.persistence;
}
