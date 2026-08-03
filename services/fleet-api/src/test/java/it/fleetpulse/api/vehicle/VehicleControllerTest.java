package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.DatabaseAvailabilityClassifier;
import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.GlobalExceptionHandler;
import it.fleetpulse.api.common.PagedResponse;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VehicleController.class)
@Import({
        GlobalExceptionHandler.class,
        DatabaseConstraintErrorResolver.class,
        DatabaseAvailabilityClassifier.class,
        VehiclePageableFactory.class,
        VehicleControllerTest.BindingTestController.class,
        VehicleControllerTest.FixedClockConfiguration.class
})
class VehicleControllerTest {

    private static final UUID ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");
    private static final String VEHICLES_PATH = "/api/v1/vehicles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleService service;

    /**
     * Verifica status, body e Location della registrazione nominale.
     */
    @Test
    @DisplayName("POST registra un veicolo e restituisce 201 con Location")
    void createsVehicle() throws Exception {
        when(service.create(new CreateVehicleRequest("VAN-001", "FP001AA", 15_000, 90_000L)))
                .thenReturn(response());

        mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/vehicles/" + ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.externalCode").value("VAN-001"))
                .andExpect(jsonPath("$.plate").value("FP001AA"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.serviceIntervalKm").value(15_000))
                .andExpect(jsonPath("$.nextServiceAtKm").value(90_000))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()));
    }

    /**
     * Verifica il contratto JSON del dettaglio esistente.
     */
    @Test
    @DisplayName("GET restituisce il dettaglio completo")
    void returnsVehicleDetail() throws Exception {
        when(service.findById(ID)).thenReturn(response());

        mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.externalCode").value("VAN-001"))
                .andExpect(jsonPath("$.plate").value("FP001AA"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.serviceIntervalKm").value(15_000))
                .andExpect(jsonPath("$.nextServiceAtKm").value(90_000))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()));
    }

    /**
     * Verifica in modo parametrizzato tutti i vincoli field-level della request.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("POST converte Bean Validation in REQUEST_INVALID")
    void rejectsInvalidRequests(String description, String json, String field) throws Exception {
        ResultActions result = mockMvc.perform(post(VEHICLES_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));

        expectError(result, 400, ErrorCode.REQUEST_INVALID, VEHICLES_PATH)
                .andExpect(jsonPath("$.details[*].field", hasItem(field)));
    }

    /**
     * Verifica che un body assente sia classificato come JSON non leggibile.
     */
    @Test
    @DisplayName("POST senza body restituisce REQUEST_MALFORMED_JSON")
    void rejectsMissingBody() throws Exception {
        expectError(
                mockMvc.perform(post(VEHICLES_PATH).contentType(MediaType.APPLICATION_JSON)),
                400,
                ErrorCode.REQUEST_MALFORMED_JSON,
                VEHICLES_PATH
        ).andExpect(jsonPath("$.details").isEmpty());
    }

    /**
     * Verifica che un JSON sintatticamente invalido sia classificato correttamente.
     */
    @Test
    @DisplayName("POST con JSON malformato restituisce REQUEST_MALFORMED_JSON")
    void rejectsMalformedJson() throws Exception {
        expectError(
                mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"externalCode\":")),
                400,
                ErrorCode.REQUEST_MALFORMED_JSON,
                VEHICLES_PATH
        );
    }

    /**
     * Verifica che un tipo numerico incompatibile sia classificato come body non leggibile.
     */
    @Test
    @DisplayName("POST con numero non convertibile restituisce REQUEST_MALFORMED_JSON")
    void rejectsNonConvertibleNumber() throws Exception {
        String json = validJson().replace("15000", "\"many\"");

        expectError(
                mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)),
                400,
                ErrorCode.REQUEST_MALFORMED_JSON,
                VEHICLES_PATH
        );
    }

    /**
     * Verifica che un UUID non valido produca un errore field-level.
     */
    @Test
    @DisplayName("GET con UUID non valido restituisce REQUEST_INVALID")
    void rejectsInvalidUuid() throws Exception {
        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/not-a-uuid")),
                400,
                ErrorCode.REQUEST_INVALID,
                VEHICLES_PATH + "/not-a-uuid"
        ).andExpect(jsonPath("$.details[0].field").value("vehicleId"));
    }

    /**
     * Verifica il mapping uniforme del media type non supportato.
     */
    @Test
    @DisplayName("POST con media type non supportato restituisce 415")
    void rejectsUnsupportedMediaType() throws Exception {
        expectError(
                mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(validJson())),
                415,
                ErrorCode.REQUEST_UNSUPPORTED_MEDIA_TYPE,
                VEHICLES_PATH
        ).andExpect(header().string("Accept", org.hamcrest.Matchers.containsString("application/json")));
    }

    /**
     * Verifica il mapping uniforme del metodo HTTP non supportato.
     */
    @Test
    @DisplayName("Metodo HTTP non supportato restituisce 405 e Allow")
    void rejectsUnsupportedMethod() throws Exception {
        expectError(
                mockMvc.perform(put(VEHICLES_PATH + "/{vehicleId}", ID)),
                405,
                ErrorCode.REQUEST_METHOD_NOT_ALLOWED,
                VEHICLES_PATH + "/" + ID
        ).andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")));
    }

    /**
     * Verifica il conflitto applicativo sul codice esterno.
     */
    @Test
    @DisplayName("Codice esterno duplicato restituisce il 409 documentato")
    void mapsExternalCodeConflict() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ApplicationException(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT));

        expectError(postValidVehicle(), 409, ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT, VEHICLES_PATH);
    }

    /**
     * Verifica il conflitto applicativo sulla targa.
     */
    @Test
    @DisplayName("Targa duplicata restituisce il 409 documentato")
    void mapsPlateConflict() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new ApplicationException(ErrorCode.VEHICLE_PLATE_CONFLICT));

        expectError(postValidVehicle(), 409, ErrorCode.VEHICLE_PLATE_CONFLICT, VEHICLES_PATH);
    }

    /**
     * Verifica il mapping del dettaglio assente.
     */
    @Test
    @DisplayName("Dettaglio assente restituisce VEHICLE_NOT_FOUND")
    void mapsVehicleNotFound() throws Exception {
        when(service.findById(ID)).thenThrow(new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                404,
                ErrorCode.VEHICLE_NOT_FOUND,
                VEHICLES_PATH + "/" + ID
        );
    }

    /**
     * Verifica che l'impossibilità di aprire una transazione sia un errore temporaneo.
     */
    @Test
    @DisplayName("CannotCreateTransactionException restituisce SERVICE_UNAVAILABLE")
    void mapsCannotCreateTransaction() throws Exception {
        when(service.findById(ID)).thenThrow(new CannotCreateTransactionException("database unavailable"));

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                503,
                ErrorCode.SERVICE_UNAVAILABLE,
                VEHICLES_PATH + "/" + ID
        );
    }

    /**
     * Verifica che una failure JDBC di connessione sia un errore temporaneo.
     */
    @Test
    @DisplayName("DataAccessResourceFailureException di connessione restituisce SERVICE_UNAVAILABLE")
    void mapsDataAccessResourceFailure() throws Exception {
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException(
                "database unavailable",
                new SQLException("connection refused", "08006")
        );
        when(service.findById(ID)).thenThrow(exception);

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                503,
                ErrorCode.SERVICE_UNAVAILABLE,
                VEHICLES_PATH + "/" + ID
        );
    }

    /**
     * Verifica che gli errori inattesi non espongano dettagli tecnici.
     */
    @Test
    @DisplayName("Errore inatteso restituisce INTERNAL_ERROR senza dettagli tecnici")
    void mapsUnexpectedError() throws Exception {
        when(service.findById(ID)).thenThrow(new IllegalStateException("secret technical detail"));

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                500,
                ErrorCode.INTERNAL_ERROR,
                VEHICLES_PATH + "/" + ID
        ).andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret technical detail"))));
    }

    /**
     * Verifica la conversione delle constraint violation prodotte fuori dal body binding.
     */
    @Test
    @DisplayName("Constraint violation restituisce REQUEST_INVALID")
    void mapsConstraintViolation() throws Exception {
        when(service.findById(ID)).thenThrow(new ConstraintViolationException(java.util.Set.of()));

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                400,
                ErrorCode.REQUEST_INVALID,
                VEHICLES_PATH + "/" + ID
        );
    }

    /**
     * Verifica la risposta uniforme per un query parameter obbligatorio assente.
     */
    @Test
    @DisplayName("Query parameter obbligatorio assente restituisce REQUEST_INVALID")
    void mapsMissingRequestParameter() throws Exception {
        expectError(
                mockMvc.perform(get("/test/required-parameter")),
                400,
                ErrorCode.REQUEST_INVALID,
                "/test/required-parameter"
        ).andExpect(jsonPath("$.details[0].field").value("value"));
    }

    /**
     * Verifica la risposta uniforme per una path variable dichiarata ma non disponibile.
     */
    @Test
    @DisplayName("Path variable obbligatoria assente restituisce REQUEST_INVALID")
    void mapsMissingPathVariable() throws Exception {
        expectError(
                mockMvc.perform(get("/test/missing-path-variable")),
                400,
                ErrorCode.REQUEST_INVALID,
                "/test/missing-path-variable"
        ).andExpect(jsonPath("$.details[0].field").value("missing"));
    }

    /**
     * Verifica che un constraint database sconosciuto non venga classificato come conflitto.
     */
    @Test
    @DisplayName("Constraint database sconosciuto restituisce INTERNAL_ERROR")
    void mapsUnknownDatabaseConstraintToInternalError() throws Exception {
        when(service.create(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new DataIntegrityViolationException("unknown constraint"));

        expectError(postValidVehicle(), 500, ErrorCode.INTERNAL_ERROR, VEHICLES_PATH);
    }

    /**
     * Verifica che un errore dati non legato alla connessione non diventi un 503.
     */
    @Test
    @DisplayName("Data access non di connessione restituisce INTERNAL_ERROR")
    void mapsNonConnectionDataAccessErrorToInternalError() throws Exception {
        when(service.findById(ID)).thenThrow(new DataRetrievalFailureException("query failed"));

        expectError(
                mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", ID)),
                500,
                ErrorCode.INTERNAL_ERROR,
                VEHICLES_PATH + "/" + ID
        );
    }

    /**
     * Verifica il contratto nominale del cambio stato.
     */
    @Test
    @DisplayName("PATCH cambia stato e restituisce il veicolo aggiornato")
    void changesVehicleStatus() throws Exception {
        VehicleResponse disabled = response(VehicleStatus.DISABLED);
        when(service.changeStatus(ID, new ChangeVehicleStatusRequest(VehicleStatus.DISABLED)))
                .thenReturn(disabled);

        mockMvc.perform(patch(statusPath(ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.externalCode").value("VAN-001"))
                .andExpect(jsonPath("$.plate").value("FP001AA"))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.serviceIntervalKm").value(15_000))
                .andExpect(jsonPath("$.nextServiceAtKm").value(90_000))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andExpect(header().doesNotExist("Location"));

        verify(service).changeStatus(ID, new ChangeVehicleStatusRequest(VehicleStatus.DISABLED));
    }

    /**
     * Verifica Bean Validation quando lo stato non è valorizzato.
     */
    @Test
    @DisplayName("PATCH con stato nullo restituisce REQUEST_INVALID")
    void rejectsNullStatus() throws Exception {
        expectError(
                mockMvc.perform(patch(statusPath(ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":null}")),
                400,
                ErrorCode.REQUEST_INVALID,
                statusPath(ID)
        ).andExpect(jsonPath("$.details[0].field").value("status"));
    }

    /**
     * Verifica la classificazione del body assente nel cambio stato.
     */
    @Test
    @DisplayName("PATCH senza body restituisce REQUEST_MALFORMED_JSON")
    void rejectsMissingStatusBody() throws Exception {
        expectError(
                mockMvc.perform(patch(statusPath(ID)).contentType(MediaType.APPLICATION_JSON)),
                400,
                ErrorCode.REQUEST_MALFORMED_JSON,
                statusPath(ID)
        );
    }

    /**
     * Verifica la classificazione di un valore enum sconosciuto.
     */
    @Test
    @DisplayName("PATCH con enum sconosciuto restituisce REQUEST_MALFORMED_JSON")
    void rejectsUnknownStatusValue() throws Exception {
        expectError(
                mockMvc.perform(patch(statusPath(ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"UNKNOWN\"}")),
                400,
                ErrorCode.REQUEST_MALFORMED_JSON,
                statusPath(ID)
        );
    }

    /**
     * Verifica la conversione dell'UUID non valido presente nel path.
     */
    @Test
    @DisplayName("PATCH con UUID non valido restituisce REQUEST_INVALID")
    void rejectsInvalidStatusPathUuid() throws Exception {
        String path = VEHICLES_PATH + "/not-a-uuid/status";
        expectError(
                mockMvc.perform(patch(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}")),
                400,
                ErrorCode.REQUEST_INVALID,
                path
        ).andExpect(jsonPath("$.details[0].field").value("vehicleId"));
    }

    /**
     * Verifica l'errore documentato per il cambio stato di un veicolo assente.
     */
    @Test
    @DisplayName("PATCH su veicolo assente restituisce VEHICLE_NOT_FOUND")
    void mapsMissingVehicleDuringStatusChange() throws Exception {
        when(service.changeStatus(ID, new ChangeVehicleStatusRequest(VehicleStatus.DISABLED)))
                .thenThrow(new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));

        expectError(
                mockMvc.perform(patch(statusPath(ID))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}")),
                404,
                ErrorCode.VEHICLE_NOT_FOUND,
                statusPath(ID)
        );
    }

    /**
     * Applica una POST valida riutilizzata dai test degli errori applicativi.
     */
    private ResultActions postValidVehicle() throws Exception {
        return mockMvc.perform(post(VEHICLES_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson()));
    }

    /**
     * Verifica la struttura comune e deterministica di una risposta di errore.
     */
    private ResultActions expectError(
            ResultActions result,
            int status,
            ErrorCode code,
            String path
    ) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getDefaultMessage()))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    /**
     * Verifica la risposta paginata e i parametri di default senza Content-Type.
     */
    @Test
    @DisplayName("GET lista usa i default e non richiede Content-Type")
    void listsVehiclesWithDefaults() throws Exception {
        PagedResponse<VehicleResponse> page = new PagedResponse<>(
                List.of(response()), 0, 20, 1, 1, true, true
        );
        PageRequest expectedPageable = PageRequest.of(
                0,
                20,
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id"))
        );
        when(service.search(new VehicleSearchCriteria(null, null), expectedPageable))
                .thenReturn(page);

        mockMvc.perform(get(VEHICLES_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));

        verify(service).search(new VehicleSearchCriteria(null, null), expectedPageable);
    }

    /**
     * Verifica il passaggio di ricerca, stato, pagina e sort al servizio.
     */
    @Test
    @DisplayName("GET lista propaga filtri e paginazione")
    void listsVehiclesWithFilters() throws Exception {
        PageRequest expectedPageable = PageRequest.of(
                2,
                50,
                Sort.by(Sort.Direction.ASC, "plate")
                        .and(Sort.by(Sort.Direction.ASC, "id"))
        );
        VehicleSearchCriteria criteria = new VehicleSearchCriteria("fp", VehicleStatus.ACTIVE);
        when(service.search(criteria, expectedPageable))
                .thenReturn(new PagedResponse<>(List.of(), 2, 50, 0, 0, false, true));

        mockMvc.perform(get(VEHICLES_PATH)
                        .param("query", "fp")
                        .param("status", "ACTIVE")
                        .param("page", "2")
                        .param("size", "50")
                        .param("sort", "plate,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(50));

        verify(service).search(criteria, expectedPageable);
    }

    /**
     * Verifica che i parametri lista non validi producano REQUEST_INVALID.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidListParameters")
    @DisplayName("GET lista rifiuta paginazione e sort non validi")
    void rejectsInvalidListParameters(String name, String parameter, String value) throws Exception {
        expectError(
                mockMvc.perform(get(VEHICLES_PATH).param(parameter, value)),
                400,
                ErrorCode.REQUEST_INVALID,
                VEHICLES_PATH
        );
    }

    /**
     * Verifica che uno status sconosciuto produca REQUEST_INVALID.
     */
    @Test
    @DisplayName("GET lista rifiuta uno status sconosciuto")
    void rejectsUnknownStatus() throws Exception {
        expectError(
                mockMvc.perform(get(VEHICLES_PATH).param("status", "UNKNOWN")),
                400,
                ErrorCode.REQUEST_INVALID,
                VEHICLES_PATH
        );
    }

    /**
     * Fornisce i parametri lista non validi coperti dalla slice MVC.
     */
    private static Stream<Arguments> invalidListParameters() {
        return Stream.of(
                Arguments.of("page negativa", "page", "-1"),
                Arguments.of("size zero", "size", "0"),
                Arguments.of("size oltre il massimo", "size", "101"),
                Arguments.of("sort malformato", "sort", "plate"),
                Arguments.of("campo sort sconosciuto", "sort", "odometer,asc"),
                Arguments.of("direzione sort sconosciuta", "sort", "plate,sideways")
        );
    }

    /**
     * Fornisce tutti i payload non validi e il relativo campo atteso nei dettagli.
     */
    private static Stream<Arguments> invalidRequests() {
        String tooLongExternalCode = "X".repeat(65);
        String tooLongPlate = "P".repeat(17);
        return Stream.of(
                Arguments.of("externalCode nullo", json("null", "\"FP001AA\"", "15000", "90000"), "externalCode"),
                Arguments.of("externalCode blank", json("\"   \"", "\"FP001AA\"", "15000", "90000"), "externalCode"),
                Arguments.of("externalCode oltre 64", json("\"" + tooLongExternalCode + "\"", "\"FP001AA\"", "15000", "90000"), "externalCode"),
                Arguments.of("plate nulla", json("\"VAN-001\"", "null", "15000", "90000"), "plate"),
                Arguments.of("plate blank", json("\"VAN-001\"", "\"   \"", "15000", "90000"), "plate"),
                Arguments.of("plate oltre 16", json("\"VAN-001\"", "\"" + tooLongPlate + "\"", "15000", "90000"), "plate"),
                Arguments.of("serviceIntervalKm nullo", json("\"VAN-001\"", "\"FP001AA\"", "null", "90000"), "serviceIntervalKm"),
                Arguments.of("serviceIntervalKm zero", json("\"VAN-001\"", "\"FP001AA\"", "0", "90000"), "serviceIntervalKm"),
                Arguments.of("serviceIntervalKm negativo", json("\"VAN-001\"", "\"FP001AA\"", "-1", "90000"), "serviceIntervalKm"),
                Arguments.of("nextServiceAtKm nullo", json("\"VAN-001\"", "\"FP001AA\"", "15000", "null"), "nextServiceAtKm"),
                Arguments.of("nextServiceAtKm negativo", json("\"VAN-001\"", "\"FP001AA\"", "15000", "-1"), "nextServiceAtKm")
        );
    }

    /**
     * Costruisce un payload JSON con valori già serializzati.
     */
    private static String json(String externalCode, String plate, String interval, String nextService) {
        return """
                {"externalCode":%s,"plate":%s,"serviceIntervalKm":%s,"nextServiceAtKm":%s}
                """.formatted(externalCode, plate, interval, nextService);
    }

    /**
     * Restituisce il payload nominale privo del campo status.
     */
    private static String validJson() {
        return json("\"VAN-001\"", "\"FP001AA\"", "15000", "90000");
    }

    /**
     * Costruisce la response nominale usata dal controller mockato.
     */
    private static VehicleResponse response() {
        return response(VehicleStatus.ACTIVE);
    }

    /**
     * Costruisce la response nominale con lo stato richiesto.
     */
    private static VehicleResponse response(VehicleStatus status) {
        return new VehicleResponse(ID, "VAN-001", "FP001AA", status, 15_000, 90_000L, NOW);
    }

    /**
     * Costruisce il path del cambio stato per il veicolo indicato.
     */
    private static String statusPath(UUID id) {
        return VEHICLES_PATH + "/" + id + "/status";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        /**
         * Fornisce il clock UTC fisso alla gestione degli errori MVC.
         */
        @Bean
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class BindingTestController {

        /**
         * Espone un parametro obbligatorio per verificare il relativo errore MVC.
         */
        @org.springframework.web.bind.annotation.GetMapping("/test/required-parameter")
        String requiredParameter(
                @org.springframework.web.bind.annotation.RequestParam String value
        ) {
            return value;
        }

        /**
         * Dichiara una variabile non presente nel mapping per esercitare l'handler dedicato.
         */
        @org.springframework.web.bind.annotation.GetMapping("/test/missing-path-variable")
        String missingPathVariable(
                @org.springframework.web.bind.annotation.PathVariable String missing
        ) {
            return missing;
        }
    }
}
