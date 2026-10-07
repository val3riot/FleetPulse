package it.fleetpulse.processor.telemetry;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.stream.Stream;

import it.fleetpulse.processor.telemetry.TelemetryProcessingMetrics.Outcome;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingResultInvariantTest {
    @ParameterizedTest
    @MethodSource("validResults")
    void acceptsConsistentResults(Outcome outcome, TelemetrySampleEntity sample) throws Exception {
        assertThat(constructor().newInstance(outcome, sample)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("invalidResults")
    void rejectsInconsistentResults(Outcome outcome, TelemetrySampleEntity sample,
            Class<? extends Throwable> expectedCause) throws Exception {
        var constructor = constructor();
        assertThatThrownBy(() -> constructor.newInstance(outcome, sample))
            .isInstanceOf(InvocationTargetException.class)
            .hasCauseInstanceOf(expectedCause)
            .satisfies(failure -> assertThat(failure.getCause().getMessage())
                .doesNotContain("private-payload-marker", "vehicleId", "latitude", "longitude"));
    }

    private static Stream<Arguments> validResults() {
        return Stream.of(
            Arguments.of(Outcome.PERSISTED, sample()),
            Arguments.of(Outcome.DUPLICATE, null),
            Arguments.of(Outcome.REJECTED, null),
            Arguments.of(Outcome.FAILED, null));
    }

    private static Stream<Arguments> invalidResults() {
        var sample = sample();
        return Stream.of(
            Arguments.of(null, null, NullPointerException.class),
            Arguments.of(null, sample, NullPointerException.class),
            Arguments.of(Outcome.PERSISTED, null, IllegalArgumentException.class),
            Arguments.of(Outcome.DUPLICATE, sample, IllegalArgumentException.class),
            Arguments.of(Outcome.REJECTED, sample, IllegalArgumentException.class),
            Arguments.of(Outcome.FAILED, sample, IllegalArgumentException.class));
    }

    private static TelemetrySampleEntity sample() {
        return mock(TelemetrySampleEntity.class, withSettings().name("private-payload-marker"));
    }

    private static Constructor<?> constructor() throws Exception {
        // Validate the private invariant without widening the production type's visibility.
        Class<?> result = Arrays.stream(TelemetryEventProcessingService.class.getDeclaredClasses())
            .filter(type -> type.getSimpleName().equals("ProcessingResult"))
            .findFirst().orElseThrow();
        var constructor = result.getDeclaredConstructor(Outcome.class, TelemetrySampleEntity.class);
        constructor.setAccessible(true);
        return constructor;
    }
}
