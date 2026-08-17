package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import it.fleetpulse.processor.telemetry.UnsupportedTelemetryEventVersionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.time.Clock;
import java.util.Objects;

public final class TelemetryDeadLetterEventFactory {
    private final Clock clock;
    private final KafkaOriginalPayloadResolver payloadResolver;
    private final KafkaDeliveryAttemptResolver attemptResolver;

    public TelemetryDeadLetterEventFactory(Clock clock,
        KafkaOriginalPayloadResolver payloadResolver,
        KafkaDeliveryAttemptResolver attemptResolver) {
        this.clock = Objects.requireNonNull(clock);
        this.payloadResolver = Objects.requireNonNull(payloadResolver);
        this.attemptResolver = Objects.requireNonNull(attemptResolver);
    }

    public TelemetryDeadLetterEvent create(ConsumerRecord<?, ?> record, Exception failure) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(failure, "failure must not be null");

        Throwable rootCause = rootCause(failure);

        return new TelemetryDeadLetterEvent(clock.instant(), record.topic(), record.partition(),
            record.offset(), attemptResolver.resolve(record), errorCode(failure),
            errorMessage(rootCause), Objects.toString(record.key(), null),
            payloadResolver.resolve(record));
    }

    private static String errorCode(Throwable failure) {
        if (contains(failure, UnsupportedTelemetryEventVersionException.class)) {
            return "UNSUPPORTED_EVENT_VERSION";
        }

        if (contains(failure, DeserializationException.class)) {
            return "DESERIALIZATION_FAILED";
        }

        return "PROCESSING_RETRIES_EXHAUSTED";
    }

    private static boolean contains(Throwable failure, Class<? extends Throwable> expectedType) {
        Throwable current = failure;

        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    private static String errorMessage(Throwable failure) {
        String message = failure.getMessage();

        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

}
