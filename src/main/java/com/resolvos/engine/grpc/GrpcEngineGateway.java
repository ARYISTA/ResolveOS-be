package com.resolvos.engine.grpc;

import com.resolvos.engine.EngineEventListener;
import com.resolvos.engine.EngineGateway;
import com.resolvos.engine.ResolveCaseRequest;
import com.resolvos.engine.ResumeCaseRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Empty gRPC adapter implementing EngineGateway.
 * Active under the "grpc" profile.
 * Per PRD Section 10: "The adapter classes exist but are empty:
 * GrpcEngineGateway throws UnsupportedOperationException, BackendCallbackGrpcService methods return gRPC UNIMPLEMENTED."
 */
@Service
@Profile("grpc")
public class GrpcEngineGateway implements EngineGateway {

    private static final Logger log = LoggerFactory.getLogger(GrpcEngineGateway.class);

    @Override
    public void resolve(ResolveCaseRequest request, EngineEventListener listener) {
        log.warn("gRPC engine gateway resolve invoked for case {}; Python engine not attached", request.caseId());
        throw new UnsupportedOperationException("gRPC engine gateway not implemented; use profile 'sim'");
    }

    @Override
    public void resume(ResumeCaseRequest request, EngineEventListener listener) {
        log.warn("gRPC engine gateway resume invoked for case {}; Python engine not attached", request.caseId());
        throw new UnsupportedOperationException("gRPC engine gateway not implemented; use profile 'sim'");
    }
}
