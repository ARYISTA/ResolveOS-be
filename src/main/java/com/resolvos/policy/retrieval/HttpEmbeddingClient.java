package com.resolvos.policy.retrieval;

import com.resolvos.common.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "resolvos.embedding.provider", havingValue = "openai")
public class HttpEmbeddingClient implements EmbeddingClient {

    private final RestClient restClient;
    private final String model;
    private final int dimensions;

    public HttpEmbeddingClient(EmbeddingProperties properties) {
        this.model = properties.model();
        this.dimensions = properties.dimensions();
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .build();
    }

    record EmbeddingRequest(List<String> input, String model) {}
    record EmbeddingData(int index, List<Float> embedding) {}
    record EmbeddingResponse(List<EmbeddingData> data) {}

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        EmbeddingResponse resp = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingRequest(texts, model))
                .retrieve()
                .body(EmbeddingResponse.class);

        if (resp == null || resp.data() == null) {
            throw new RuntimeException("Empty response from embedding provider");
        }

        List<float[]> results = new ArrayList<>(texts.size());
        for (EmbeddingData item : resp.data()) {
            float[] vec = new float[item.embedding().size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = item.embedding().get(i);
            }
            results.add(vec);
        }
        return results;
    }

    @Override
    public float[] embedQuery(String text) {
        List<float[]> list = embed(List.of(text));
        return list.isEmpty() ? new float[dimensions] : list.get(0);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
