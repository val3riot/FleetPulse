package it.fleetpulse.api.vehicle;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(VehicleApiIntegrationTest.FixedClockConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VehicleApiIntegrationTest extends PostgreSqlIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");
    private static final String VEHICLES_PATH = "/api/v1/vehicles";

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private VehicleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Ripristina lo spy e pulisce i dati per rendere indipendente ogni test.
     */
    @BeforeEach
    void setUp() {
        Mockito.reset(repository);
        repository.deleteAll();
    }

    /**
     * Rimuove eventuali stub dallo spy dopo ogni scenario.
     */
    @AfterEach
    void tearDown() {
        Mockito.reset(repository);
    }

    /**
     * Verifica la registrazione reale attraverso tutti i layer fino a PostgreSQL.
     */
    @Test
    @DisplayName("POST reale persiste il veicolo e restituisce body e Location")
    void createsVehicleThroughAllLayers() throws Exception {
        MvcResult result = mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("VAN-API-1", "FP201AA")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalCode").value("VAN-API-1"))
                .andExpect(jsonPath("$.plate").value("FP201AA"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").value(NOW.toString()))
                .andReturn();

        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost/api/v1/vehicles/" + id);
        assertThat(repository.findById(UUID.fromString(id))).isPresent();
    }

    /**
     * Verifica il dettaglio reale di un veicolo già persistito.
     */
    @Test
    @DisplayName("GET reale restituisce il veicolo persistito")
    void returnsPersistedVehicle() throws Exception {
        VehicleEntity saved = repository.saveAndFlush(entity("VAN-API-2", "FP202AA"));

        mockMvc.perform(get(VEHICLES_PATH + "/{vehicleId}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(saved.getId().toString()))
                .andExpect(jsonPath("$.externalCode").value("VAN-API-2"))
                .andExpect(jsonPath("$.plate").value("FP202AA"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    /**
     * Verifica che una collection senza risultati restituisca una pagina valida.
     */
    @Test
    @DisplayName("GET lista restituisce una pagina vuota valida")
    void returnsEmptyVehiclePage() throws Exception {
        mockMvc.perform(get(VEHICLES_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    /**
     * Verifica la ricerca case-insensitive su codice esterno e targa reali.
     */
    @Test
    @DisplayName("GET lista cerca codice esterno e targa ignorando il case")
    void searchesExternalCodeAndPlateCaseInsensitively() throws Exception {
        repository.saveAllAndFlush(List.of(
                entity("Delivery-North", "FP301AA"),
                entity("VAN-SOUTH", "MiXeD302"),
                entity("TRUCK-WEST", "FP303AA")
        ));

        mockMvc.perform(get(VEHICLES_PATH)
                        .param("query", "DeLiVeRy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].externalCode").value("Delivery-North"));

        mockMvc.perform(get(VEHICLES_PATH)
                        .param("query", "mixed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].plate").value("MiXeD302"));
    }

    /**
     * Verifica il filtro per stato e la combinazione con la ricerca testuale.
     */
    @Test
    @DisplayName("GET lista combina query e status")
    void filtersByQueryAndStatus() throws Exception {
        repository.saveAllAndFlush(List.of(
                entity("VAN-ACTIVE", "FP304AA", VehicleStatus.ACTIVE, NOW),
                entity("VAN-DISABLED", "FP305AA", VehicleStatus.DISABLED, NOW),
                entity("TRUCK-DISABLED", "FP306AA", VehicleStatus.DISABLED, NOW)
        ));

        mockMvc.perform(get(VEHICLES_PATH)
                        .param("query", "van")
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].externalCode").value("VAN-DISABLED"))
                .andExpect(jsonPath("$.content[0].status").value("DISABLED"));
    }

    /**
     * Verifica paginazione e ordinamento stabile attraversando due pagine reali.
     */
    @Test
    @DisplayName("GET lista pagina i risultati con ordering deterministico")
    void paginatesWithDeterministicOrdering() throws Exception {
        repository.saveAllAndFlush(List.of(
                entity("VAN-C", "FP307AA"),
                entity("VAN-A", "FP308AA"),
                entity("VAN-D", "FP309AA"),
                entity("VAN-B", "FP310AA"),
                entity("VAN-E", "FP311AA")
        ));

        MvcResult firstPage = mockMvc.perform(get(VEHICLES_PATH)
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "externalCode,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andReturn();
        MvcResult secondPage = mockMvc.perform(get(VEHICLES_PATH)
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "externalCode,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false))
                .andReturn();

        List<String> firstCodes = JsonPath.read(
                firstPage.getResponse().getContentAsString(),
                "$.content[*].externalCode"
        );
        List<String> secondCodes = JsonPath.read(
                secondPage.getResponse().getContentAsString(),
                "$.content[*].externalCode"
        );
        assertThat(firstCodes).containsExactly("VAN-A", "VAN-B");
        assertThat(secondCodes).containsExactly("VAN-C", "VAN-D");
        assertThat(firstCodes).doesNotContainAnyElementsOf(secondCodes);
    }

    /**
     * Verifica il tie-breaker UUID quando il campo sort ha valori uguali.
     */
    @Test
    @DisplayName("GET lista usa UUID come tie-breaker stabile")
    void usesStableIdTieBreaker() throws Exception {
        List<VehicleEntity> saved = repository.saveAllAndFlush(List.of(
                entity("TIE-A", "FP312AA"),
                entity("TIE-B", "FP313AA"),
                entity("TIE-C", "FP314AA")
        ));
        List<String> expectedIds = saved.stream()
                .map(VehicleEntity::getId)
                .map(UUID::toString)
                .sorted()
                .toList();

        MvcResult result = mockMvc.perform(get(VEHICLES_PATH)
                        .param("sort", "status,asc"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> actualIds = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.content[*].id"
        );
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    /**
     * Forza il constraint reale sul codice esterno oltre il pre-check e verifica il 409.
     */
    @Test
    @DisplayName("Il constraint reale sul codice esterno diventa 409")
    void mapsRealExternalCodeConstraint() throws Exception {
        repository.saveAndFlush(entity("VAN-API-DUP", "FP203AA"));
        doReturn(false).when(repository).existsByExternalCode("VAN-API-DUP");

        expectConflict(
                mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("VAN-API-DUP", "FP204AA"))),
                "VEHICLE_EXTERNAL_CODE_CONFLICT"
        );
    }

    /**
     * Forza il constraint reale sulla targa oltre il pre-check e verifica il 409.
     */
    @Test
    @DisplayName("Il constraint reale sulla targa diventa 409")
    void mapsRealPlateConstraint() throws Exception {
        repository.saveAndFlush(entity("VAN-API-3", "FP205AA"));
        doReturn(false).when(repository).existsByPlate("FP205AA");

        expectConflict(
                mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("VAN-API-4", "FP205AA"))),
                "VEHICLE_PLATE_CONFLICT"
        );
    }

    /**
     * Sincronizza due create oltre i pre-check e verifica una sola riga persistita.
     */
    @Test
    @DisplayName("Due create concorrenti producono una 201 e una 409")
    void handlesConcurrentCreatesDeterministically() throws Exception {
        String externalCode = "VAN-CONCURRENT";
        CountDownLatch bothPastExternalCodeCheck = new CountDownLatch(2);
        CountDownLatch releaseChecks = new CountDownLatch(1);
        doAnswer(invocation -> {
            bothPastExternalCodeCheck.countDown();
            if (!bothPastExternalCodeCheck.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Le richieste non hanno raggiunto insieme il pre-check");
            }
            releaseChecks.await(10, TimeUnit.SECONDS);
            return false;
        }).when(repository).existsByExternalCode(eq(externalCode));
        doReturn(false).when(repository).existsByPlate("FP206AA");
        doReturn(false).when(repository).existsByPlate("FP207AA");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> first = executor.submit(() -> create(externalCode, "FP206AA"));
            Future<MvcResult> second = executor.submit(() -> create(externalCode, "FP207AA"));
            assertThat(bothPastExternalCodeCheck.await(10, TimeUnit.SECONDS)).isTrue();
            releaseChecks.countDown();

            List<Integer> statuses = List.of(
                    first.get(15, TimeUnit.SECONDS).getResponse().getStatus(),
                    second.get(15, TimeUnit.SECONDS).getResponse().getStatus()
            );

            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from vehicles where external_code = ?",
                    Long.class,
                    externalCode
            )).isEqualTo(1L);
        } finally {
            releaseChecks.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * Esegue una create concorrente e restituisce il risultato MVC osservabile.
     */
    private MvcResult create(String externalCode, String plate) throws Exception {
        return mockMvc.perform(post(VEHICLES_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(externalCode, plate)))
                .andReturn();
    }

    /**
     * Verifica il formato dell'errore prodotto da una violazione database reale.
     */
    private void expectConflict(
            org.springframework.test.web.servlet.ResultActions result,
            String code
    ) throws Exception {
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.path").value(VEHICLES_PATH))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    /**
     * Costruisce il payload nominale della create API.
     */
    private String json(String externalCode, String plate) {
        return """
                {"externalCode":"%s","plate":"%s","serviceIntervalKm":15000,"nextServiceAtKm":90000}
                """.formatted(externalCode, plate);
    }

    /**
     * Costruisce un'entity valida per predisporre gli scenari API.
     */
    private VehicleEntity entity(String externalCode, String plate) {
        return entity(externalCode, plate, VehicleStatus.ACTIVE, NOW);
    }

    /**
     * Costruisce un'entity con stato e timestamp scelti dallo scenario.
     */
    private VehicleEntity entity(
            String externalCode,
            String plate,
            VehicleStatus status,
            Instant createdAt
    ) {
        return new VehicleEntity(externalCode, plate, status, 15_000, 90_000L, createdAt);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        /**
         * Sostituisce il clock applicativo con un istante fisso nei test API.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }
}
