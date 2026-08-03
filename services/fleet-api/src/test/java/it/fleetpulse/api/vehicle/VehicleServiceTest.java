package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.PagedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
     * Verifica che la ricerca deleghi filtri e pageable e mappi la pagina risultante.
     */
    @Test
    @DisplayName("Cerca e mappa una pagina di veicoli")
    void searchesVehicles() {
        VehicleSearchCriteria criteria = new VehicleSearchCriteria("van", VehicleStatus.ACTIVE);
        Pageable pageable = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.ASC, "externalCode")
        );
        VehicleEntity entity = entity("VAN-001", "FP001AA");
        VehicleResponse mapped = response();
        when(repository.findAll(anySpecification(), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 3));
        when(mapper.toResponse(entity)).thenReturn(mapped);

        PagedResponse<VehicleResponse> result = service.search(criteria, pageable);

        assertThat(result.content()).containsExactly(mapped);
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(3);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.first()).isFalse();
        assertThat(result.last()).isTrue();
        verify(repository).findAll(anySpecification(), eq(pageable));
        verify(mapper).toResponse(entity);
    }

    /**
     * Verifica la transizione da ACTIVE a DISABLED e la response mappata.
     */
    @Test
    @DisplayName("Disabilita un veicolo esistente")
    void disablesExistingVehicle() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        VehicleEntity entity = entity("VAN-001", "FP001AA", VehicleStatus.ACTIVE);
        ChangeVehicleStatusRequest request = new ChangeVehicleStatusRequest(VehicleStatus.DISABLED);
        VehicleResponse expected = response(VehicleStatus.DISABLED);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(expected);

        VehicleResponse actual = service.changeStatus(id, request);

        assertThat(actual).isSameAs(expected);
        assertThat(entity.getStatus()).isEqualTo(VehicleStatus.DISABLED);
        verify(repository).save(entity);
        verify(mapper).toResponse(entity);
    }

    /**
     * Verifica la transizione da DISABLED ad ACTIVE.
     */
    @Test
    @DisplayName("Riattiva un veicolo disabilitato")
    void activatesDisabledVehicle() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        VehicleEntity entity = entity("VAN-001", "FP001AA", VehicleStatus.DISABLED);
        ChangeVehicleStatusRequest request = new ChangeVehicleStatusRequest(VehicleStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(response(VehicleStatus.ACTIVE));

        service.changeStatus(id, request);

        assertThat(entity.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        verify(repository).save(entity);
    }

    /**
     * Verifica che impostare lo stato corrente resti un'operazione valida e idempotente.
     */
    @Test
    @DisplayName("Mantiene lo stato quando è già quello richiesto")
    void keepsCurrentStatusIdempotently() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        VehicleEntity entity = entity("VAN-001", "FP001AA", VehicleStatus.ACTIVE);
        ChangeVehicleStatusRequest request = new ChangeVehicleStatusRequest(VehicleStatus.ACTIVE);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(entity)).thenReturn(entity);
        when(mapper.toResponse(entity)).thenReturn(response(VehicleStatus.ACTIVE));

        VehicleResponse actual = service.changeStatus(id, request);

        assertThat(entity.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
        assertThat(actual.status()).isEqualTo(VehicleStatus.ACTIVE);
        verify(repository).save(entity);
    }

    /**
     * Verifica l'errore documentato quando il veicolo da aggiornare non esiste.
     */
    @Test
    @DisplayName("Cambio stato assente produce VEHICLE_NOT_FOUND")
    void rejectsStatusChangeForMissingVehicle() {
        UUID id = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(
                id,
                new ChangeVehicleStatusRequest(VehicleStatus.DISABLED)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND));

        verify(repository, never()).save(any());
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
        return entity(externalCode, plate, VehicleStatus.ACTIVE);
    }

    /**
     * Costruisce un'entity con lo stato richiesto dallo scenario.
     */
    private VehicleEntity entity(String externalCode, String plate, VehicleStatus status) {
        return new VehicleEntity(externalCode, plate, status, 15_000, 90_000L, NOW);
    }

    /**
     * Costruisce la response deterministica attesa dai test.
     */
    private VehicleResponse response() {
        return response(VehicleStatus.ACTIVE);
    }

    /**
     * Costruisce la response deterministica con lo stato richiesto.
     */
    private VehicleResponse response(VehicleStatus status) {
        return new VehicleResponse(
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                "VAN-001",
                "FP001AA",
                status,
                15_000,
                90_000L,
                NOW
        );
    }

    /**
     * Fornisce un matcher tipizzato per la specification passata al repository.
     */
    private Specification<VehicleEntity> anySpecification() {
        return any();
    }
}
