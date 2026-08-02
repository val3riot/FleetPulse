package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");

    @Mock
    private VehicleRepository repository;

    @Mock
    private VehicleMapper mapper;

    private VehicleService service;

    /**
     * Crea il service con un clock fisso per rendere deterministici i test.
     */
    @BeforeEach
    void setUp() {
        service = new VehicleService(repository, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Verifica l'intero flusso applicativo di creazione con dati validi.
     */
    @Test
    @DisplayName("Crea un veicolo ACTIVE usando il clock e restituisce la response mappata")
    void createsValidVehicle() {
        CreateVehicleRequest request = request();
        VehicleEntity mappedEntity = entity("VAN-001", "FP001AA");
        VehicleEntity savedEntity = entity("VAN-001", "FP001AA");
        VehicleResponse expected = response();
        when(mapper.toEntity(request, NOW, VehicleStatus.ACTIVE)).thenReturn(mappedEntity);
        when(repository.save(mappedEntity)).thenReturn(savedEntity);
        when(mapper.toResponse(savedEntity)).thenReturn(expected);

        VehicleResponse actual = service.create(request);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<Instant> instant = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).toEntity(eq(request), instant.capture(), eq(VehicleStatus.ACTIVE));
        assertThat(instant.getValue()).isEqualTo(NOW);
        verify(repository).save(mappedEntity);
        verify(mapper).toResponse(savedEntity);
    }

    /**
     * Verifica che un codice esterno duplicato interrompa la creazione prima del salvataggio.
     */
    @Test
    @DisplayName("Rifiuta un codice esterno duplicato senza salvare")
    void rejectsDuplicateExternalCode() {
        CreateVehicleRequest request = request();
        when(repository.existsByExternalCode(request.externalCode())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT));

        verify(repository, never()).existsByPlate(anyString());
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(mapper);
    }

    /**
     * Verifica che una targa duplicata interrompa la creazione prima del salvataggio.
     */
    @Test
    @DisplayName("Rifiuta una targa duplicata senza salvare")
    void rejectsDuplicatePlate() {
        CreateVehicleRequest request = request();
        when(repository.existsByExternalCode(request.externalCode())).thenReturn(false);
        when(repository.existsByPlate(request.plate())).thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VEHICLE_PLATE_CONFLICT));

        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(mapper);
    }

    /**
     * Verifica che il dettaglio esistente venga convertito nella response prevista.
     */
    @Test
    @DisplayName("Restituisce il dettaglio di un veicolo esistente")
    void returnsExistingVehicle() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        VehicleEntity entity = entity("VAN-001", "FP001AA");
        VehicleResponse expected = response();
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(entity)).thenReturn(expected);

        assertThat(service.findById(id)).isSameAs(expected);
    }

    /**
     * Verifica che il dettaglio assente produca il codice applicativo documentato.
     */
    @Test
    @DisplayName("Segnala VEHICLE_NOT_FOUND per un veicolo assente")
    void rejectsMissingVehicle() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND));

        verifyNoInteractions(mapper);
    }

    /**
     * Costruisce una request valida condivisa dai test del service.
     */
    private CreateVehicleRequest request() {
        return new CreateVehicleRequest("VAN-001", "FP001AA", 15_000, 90_000L);
    }

    /**
     * Costruisce un'entity coerente con i dati applicativi del test.
     */
    private VehicleEntity entity(String externalCode, String plate) {
        return new VehicleEntity(externalCode, plate, VehicleStatus.ACTIVE, 15_000, 90_000L, NOW);
    }

    /**
     * Costruisce la response deterministica attesa dai test.
     */
    private VehicleResponse response() {
        return new VehicleResponse(
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                "VAN-001",
                "FP001AA",
                VehicleStatus.ACTIVE,
                15_000,
                90_000L,
                NOW
        );
    }
}
