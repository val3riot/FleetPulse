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
        return new VehicleEntity(externalCode, plate, VehicleStatus.ACTIVE, 15_000, 90_000L, NOW);
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
