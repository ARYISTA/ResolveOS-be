package com.resolvos.policy.retrieval;

import java.util.List;

public interface EmbeddingClient {
    List<float[]> embed(List<String> texts);
    float[] embedQuery(String text);
    int dimensions();
}
