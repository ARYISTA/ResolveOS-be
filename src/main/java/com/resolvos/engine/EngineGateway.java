package com.resolvos.engine;

public interface EngineGateway {
    void resolve(ResolveCaseRequest request, EngineEventListener listener);
    void resume(ResumeCaseRequest request, EngineEventListener listener);
}
