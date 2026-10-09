package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.processor.telemetry.TelemetryEventHandler;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class RawTelemetryEventListener {
    public static final String LISTENER_ID = "fleetpulse-raw-telemetry";

    private static final Logger log = LoggerFactory.getLogger(RawTelemetryEventListener.class);

    private final TelemetryEventHandler handler;

    public RawTelemetryEventListener(TelemetryEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
    }

    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${fleetpulse.kafka.topics.raw}")
    public void onTelemetry(ConsumerRecord<String, TelemetryEvent> record) {
        Objects.requireNonNull(record, "record must not be null");
        TelemetryEvent event = Objects.requireNonNull(record.value(),
                "record value must not be null");
        log.atDebug().addKeyValue("event.action", "kafka.telemetry.record.received")
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("vehicleId", event.vehicleId())
                .addKeyValue("messageId", event.messageId())
                .log("Kafka telemetry record received: topic={}, partition={}, offset={}, " +
                        "messageId={}", record.topic(), record.partition(),
                        record.offset(),
                        event.messageId());
        TelemetrySource source = new TelemetrySource(record.topic(), record.partition(),
                record.offset());
        handler.handle(event, source);
        log.atInfo().addKeyValue("event.action", "kafka.telemetry.record.handled")
                .addKeyValue("topic", record.topic())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .addKeyValue("vehicleId", event.vehicleId())
                .addKeyValue("messageId", event.messageId())
                .log("Kafka telemetry record handled: topic={}, partition={}, offset={}, " +
                        "messageId={}", record.topic(), record.partition(),
                        record.offset(),
                        event.messageId());

    }
}
