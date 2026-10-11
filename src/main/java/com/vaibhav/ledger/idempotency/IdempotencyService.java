package com.vaibhav.ledger.idempotency;

import com.vaibhav.ledger.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an action at most once per Idempotency-Key.
 * The first request runs the action and stores its response. A retry with the same key
 * and the same request gets the stored response back and moves no money.
 */
@Service
public class IdempotencyService {

    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyRepository repository;
    private final JsonMapper jsonMapper;

    public IdempotencyService(IdempotencyRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Everything here runs in ONE database transaction together with the action, so the key,
     * the stored response and the ledger writes commit together or not at all.
     */
    @Transactional
    public <T> IdempotentResult<T> execute(
            String key, String endpoint, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key header must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        String requestHash = fingerprint(endpoint, request);

        if (repository.tryClaim(key, requestHash)) {
            T response = action.get();
            repository.saveResponse(key, jsonMapper.writeValueAsString(response));
            return new IdempotentResult<>(response, false);
        }

        // Someone already used this key, and their transaction has committed.
        IdempotencyRepository.StoredKey stored = repository.find(key)
                .orElseThrow(() -> new IllegalStateException("Claimed key vanished: " + key));
        if (!stored.requestHash().equals(requestHash)) {
            throw ApiException.unprocessable("Idempotency-Key was already used with a different request");
        }
        return new IdempotentResult<>(jsonMapper.readValue(stored.responseBody(), responseType), true);
    }

    /** SHA-256 of the endpoint and the request body, so a reused key with a different request is caught. */
    private String fingerprint(String endpoint, Object request) {
        String canonical = endpoint + "\n" + jsonMapper.writeValueAsString(request);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}