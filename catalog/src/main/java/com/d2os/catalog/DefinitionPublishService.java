package com.d2os.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Publishes a Draft definition (T012, T4-a). Computes a SHA-256 checksum of the body at the moment
 * of publish and flips status to Published; the DB trigger (V3) then makes the row immutable.
 */
@Service
public class DefinitionPublishService {

    private final DefinitionAssetRepository repository;

    public DefinitionPublishService(DefinitionAssetRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DefinitionAsset publish(UUID definitionId) {
        DefinitionAsset asset = repository.findById(definitionId)
                .orElseThrow(() -> new NoSuchElementException("definition " + definitionId));
        if (!DefinitionAsset.Status.Draft.name().equals(asset.getStatus())) {
            throw new IllegalStateException(
                    "Only Draft definitions can be published; " + asset.getKey() + " is " + asset.getStatus());
        }
        asset.markPublished(sha256(asset.getBody()));
        return repository.save(asset);
    }

    private static String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
