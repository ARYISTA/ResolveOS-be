package com.resolvos.engine.grpc;

import com.resolvos.AbstractIntegrationTest;
import com.resolvos.engine.EngineGateway;
import com.resolvos.engine.ResolveCaseRequest;
import com.resolvos.engine.ResumeCaseRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("grpc")
class GrpcAdapterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EngineGateway engineGateway;

    @Autowired
    private BackendCallbackGrpcService backendCallbackGrpcService;

    @Test
    @DisplayName("B8 - GrpcEngineGateway is loaded and throws UnsupportedOperationException")
    void testGrpcEngineGatewayThrowsUnsupportedOperationException() {
        assertThat(engineGateway).isInstanceOf(GrpcEngineGateway.class);

        ResolveCaseRequest resolveRequest = new ResolveCaseRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Refund request",
                BigDecimal.valueOf(100),
                "trc-123"
        );

        assertThatThrownBy(() -> engineGateway.resolve(resolveRequest, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("gRPC engine gateway not implemented");

        ResumeCaseRequest resumeRequest = new ResumeCaseRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "trc-123",
                "Reviewer",
                "APPROVED",
                BigDecimal.valueOf(100),
                "Approved comment"
        );

        assertThatThrownBy(() -> engineGateway.resume(resumeRequest, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("gRPC engine gateway not implemented");
    }

    @Test
    @DisplayName("B8 - BackendCallbackGrpcService methods throw UNIMPLEMENTED")
    void testBackendCallbackGrpcServiceUnimplemented() {
        assertThat(backendCallbackGrpcService).isNotNull();

        assertThatThrownBy(() -> backendCallbackGrpcService.getCaseContext())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UNIMPLEMENTED");

        assertThatThrownBy(() -> backendCallbackGrpcService.validateCase())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UNIMPLEMENTED");

        assertThatThrownBy(() -> backendCallbackGrpcService.searchPolicyChunks())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UNIMPLEMENTED");

        assertThatThrownBy(() -> backendCallbackGrpcService.enforceDecision())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UNIMPLEMENTED");

        assertThatThrownBy(() -> backendCallbackGrpcService.executeRefund())
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("UNIMPLEMENTED");
    }
}
