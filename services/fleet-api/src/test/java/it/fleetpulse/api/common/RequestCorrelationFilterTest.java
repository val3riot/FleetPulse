package it.fleetpulse.api.common;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearContext() {
        MDC.clear();
    }

    @Test
    void reusesValidIdAndRestoresPreviousContext() throws Exception {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, id);
        var response = new MockHttpServletResponse();
        MDC.put("requestId", "outer");
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(id);
            response.setStatus(404);
        });
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(MDC.get("requestId")).isEqualTo("outer");
    }

    @Test
    void generatesIdsForMissingInvalidAndRepeatedHeaders() throws Exception {
        for (String[] headers : new String[][]{{}, {"invalid"}, {"1-1-1-1-1"},
                {UUID.randomUUID().toString(), UUID.randomUUID().toString()}}) {
            var request = new MockHttpServletRequest();
            for (String header : headers) {
                request.addHeader(RequestCorrelationFilter.HEADER, header);
            }
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response,
                    (req, res) -> assertThat(MDC.get("requestId")).isNotBlank());
            String id = response.getHeader(RequestCorrelationFilter.HEADER);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(MDC.get("requestId")).isNull();
        }
    }

    @Test
    void cleansContextAndReturnsHeaderWhenChainFails() {
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response,
                (req, res) -> {
                    throw new ServletException("private-payload");
                }))
                .isInstanceOf(ServletException.class);
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isNotBlank();
        assertThat(MDC.get("requestId")).isNull();
    }
    @Test
    void concurrentRequestsKeepIndependentContexts() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentRequest(barrier));
            var second = executor.submit(() -> concurrentRequest(barrier));
            assertThat(first.get(5, TimeUnit.SECONDS))
                    .isNotEqualTo(second.get(5, TimeUnit.SECONDS));
        }
    }

    private String concurrentRequest(CyclicBarrier barrier) throws Exception {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader(RequestCorrelationFilter.HEADER, id);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            try {
                barrier.await(3, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new ServletException(failure);
            }
            assertThat(MDC.get("requestId")).isEqualTo(id);
        });
        assertThat(MDC.get("requestId")).isNull();
        return response.getHeader(RequestCorrelationFilter.HEADER);
    }

}
