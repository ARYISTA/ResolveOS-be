package com.resolvos.engine.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Empty gRPC service adapter for BackendCallback.
 * Active under the "grpc" profile.
 * Per PRD Section 10: "BackendCallbackGrpcService methods return gRPC UNIMPLEMENTED.
 * This is the only place an empty implementation is allowed."
 */
@Component
@Profile("grpc")
public class BackendCallbackGrpcService {

    private static final Logger log = LoggerFactory.getLogger(BackendCallbackGrpcService.class);

    public void getCaseContext() {
        log.warn("BackendCallback.GetCaseContext called on empty adapter");
        throw new UnsupportedOperationException("BackendCallback.GetCaseContext UNIMPLEMENTED");
    }

    public void validateCase() {
        log.warn("BackendCallback.ValidateCase called on empty adapter");
        throw new UnsupportedOperationException("BackendCallback.ValidateCase UNIMPLEMENTED");
    }

    public void searchPolicyChunks() {
        log.warn("BackendCallback.SearchPolicyChunks called on empty adapter");
        throw new UnsupportedOperationException("BackendCallback.SearchPolicyChunks UNIMPLEMENTED");
    }

    public void enforceDecision() {
        log.warn("BackendCallback.EnforceDecision called on empty adapter");
        throw new UnsupportedOperationException("BackendCallback.EnforceDecision UNIMPLEMENTED");
    }

    public void executeRefund() {
        log.warn("BackendCallback.ExecuteRefund called on empty adapter");
        throw new UnsupportedOperationException("BackendCallback.ExecuteRefund UNIMPLEMENTED");
    }
}
