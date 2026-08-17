package it.fleetpulse.processor.telemetry.persistence;

import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class TelemetrySampleWriter {

    private final TelemetrySampleRepository repository;

    public TelemetrySampleWriter(TelemetrySampleRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Transactional
    public TelemetrySampleEntity insert(TelemetrySampleEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");
        return repository.saveAndFlush(entity);
    }
}
