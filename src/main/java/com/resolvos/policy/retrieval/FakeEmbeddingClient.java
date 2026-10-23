package com.resolvos.policy.retrieval;

import com.resolvos.common.config.EmbeddingProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic hash-based fake embedding client used for testing and simulation.
 * Normalizes vectors to unit L2 norm so cosine distance is consistent and valid.
 */
@Component
@ConditionalOnProperty(name = "resolvos.embedding.provider", havingValue = "sim", matchIfMissing = true)
public class FakeEmbeddingClient implements EmbeddingClient {

    private final int dimensions;

    public FakeEmbeddingClient(EmbeddingProperties properties) {
        this.dimensions = properties.dimensions();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embedQuery(text));
        }
        return result;
    }

    @Override
    public float[] embedQuery(String text) {
        float[] vector = new float[dimensions];
        if (text == null || text.isBlank()) {
            return vector;
        }

        String[] words = text.toLowerCase().trim().split("[^a-zA-Z0-9]+");
        int validWords = 0;
        for (String word : words) {
            if (word.isBlank()) continue;
            validWords++;
            long seed = hashText(word);
            Random random = new Random(seed);
            for (int i = 0; i < dimensions; i++) {
                vector[i] += (float) random.nextGaussian();
            }
        }

        if (validWords == 0) {
            long seed = hashText(text);
            Random random = new Random(seed);
            for (int i = 0; i < dimensions; i++) {
                vector[i] = (float) random.nextGaussian();
            }
        }

        // Normalize to unit length
        double sumSquares = 0.0;
        for (int i = 0; i < dimensions; i++) {
            sumSquares += vector[i] * vector[i];
        }
        float norm = (float) Math.sqrt(sumSquares);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private long hashText(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            long val = 0;
            for (int i = 0; i < 8; i++) {
                val = (val << 8) | (digest[i] & 0xFF);
            }
            return val;
        } catch (NoSuchAlgorithmException e) {
            return text.hashCode();
        }
    }
}
