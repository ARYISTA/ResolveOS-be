package com.resolvos.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringFilter.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String headerValue = response.getHeader(RequestIdFilter.HEADER);
        assertThat(headerValue).isNotBlank();
        assertThat(UUID.fromString(headerValue)).isNotNull();
        assertThat(mdcDuringFilter.get()).isEqualTo(headerValue);
        // Cleaned up after chain
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void preservesExistingRequestId() throws ServletException, IOException {
        String existingId = "client-trace-12345";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, existingId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringFilter.set(MDC.get(RequestIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(existingId);
        assertThat(mdcDuringFilter.get()).isEqualTo(existingId);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
