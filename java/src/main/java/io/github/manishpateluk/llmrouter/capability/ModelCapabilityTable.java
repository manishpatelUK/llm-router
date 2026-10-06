package io.github.manishpateluk.llmrouter.capability;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;
import io.github.manishpateluk.llmrouter.provider.Provider;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Thread-safe, in-memory registry of {@link ModelEntry} rows describing every model the
 * router knows about across supported providers.
 *
 * <p>The table is seeded once, at class-load time, from {@code model-capability-table.json}
 * on the classpath — see {@code MODEL_CAPABILITY_HEURISTICS.md} in the project root for how
 * that seed data is sourced and scored. It is mutable at runtime via {@link #registerModel}
 * and {@link #removeModel}: per {@code LIBRARY_SPEC.md} §7.1, pricing and model lineups change
 * faster than this library can be re-released, so callers are expected to keep it current.
 *
 * <p>Callers never obtain a live reference to the internal list: every read accessor returns
 * an independent, immutable snapshot, and every mutation goes through this class's static
 * methods under a single write lock — reads never block on that lock, and are always safe to
 * call from multiple threads (e.g. concurrent request-routing threads in a Spring application)
 * while a mutation is in progress.
 */
public final class ModelCapabilityTable {

    private static final String RESOURCE_PATH = "/model-capability-table.json";

    private static final CapabilityTableFile SEED = loadSeed();

    private static final List<ModelEntry> MODELS = new CopyOnWriteArrayList<>(
            SEED.getModels() == null ? List.of() : SEED.getModels());

    /** Embedding models (§7.3) — priced separately and never offered to chat routing. */
    private static final List<EmbeddingModelEntry> EMBEDDING_MODELS = new CopyOnWriteArrayList<>(
            SEED.getEmbeddingModels() == null ? List.of() : SEED.getEmbeddingModels());

    /** Guards compound add/replace/remove operations; the lists themselves are safe for concurrent reads. */
    private static final Object WRITE_LOCK = new Object();

    private ModelCapabilityTable() {
    }

    /**
     * Returns every model currently registered, across all providers.
     *
     * @return an immutable snapshot; later mutations are not reflected in it
     */
    public static List<ModelEntry> listModels() {
        return List.copyOf(MODELS);
    }

    /**
     * Returns every model currently registered for a single provider.
     *
     * @return an immutable snapshot, possibly empty; never {@code null}
     */
    public static List<ModelEntry> listModels(Provider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        return MODELS.stream()
                .filter(entry -> entry.getProvider() == provider)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Convenience overload taking a canonical provider id string (see {@code LIBRARY_SPEC.md}
     * §12.1) instead of a {@link Provider}.
     *
     * @throws IllegalArgumentException if {@code providerId} isn't a canonical provider id
     */
    public static List<ModelEntry> listModels(String providerId) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        return listModels(Provider.fromId(providerId));
    }

    /**
     * Looks up a single model by its {@code provider}+{@code model} primary key.
     */
    public static Optional<ModelEntry> findModel(Provider provider, String model) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        return MODELS.stream()
                .filter(entry -> matches(entry, provider, model))
                .findFirst();
    }

    /** Convenience overload taking a canonical provider id string. */
    public static Optional<ModelEntry> findModel(String providerId, String model) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        return findModel(Provider.fromId(providerId), model);
    }

    /**
     * Adds a new model, or replaces the existing entry with the same {@code provider}+{@code
     * model} primary key.
     */
    public static void registerModel(ModelEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        synchronized (WRITE_LOCK) {
            MODELS.removeIf(existing -> matches(existing, entry.getProvider(), entry.getModel()));
            MODELS.add(entry);
        }
    }

    /**
     * Removes the model with the given {@code provider}+{@code model} primary key, if present.
     *
     * @return {@code true} if a matching entry was removed, {@code false} if none existed
     */
    public static boolean removeModel(Provider provider, String model) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        synchronized (WRITE_LOCK) {
            return MODELS.removeIf(existing -> matches(existing, provider, model));
        }
    }

    /** Convenience overload taking a canonical provider id string. */
    public static boolean removeModel(String providerId, String model) {
        Objects.requireNonNull(providerId, "providerId must not be null");
        return removeModel(Provider.fromId(providerId), model);
    }

    /**
     * @return the total number of registered models, across all providers
     */
    public static int size() {
        return MODELS.size();
    }

    // ---- Embedding models (§7.3) ----

    /**
     * Returns every embedding model currently registered. These rows price {@code LlmRouter.embed}
     * calls; they are never returned by {@link #listModels()} or considered by chat routing.
     *
     * @return an immutable snapshot; later mutations are not reflected in it
     */
    public static List<EmbeddingModelEntry> listEmbeddingModels() {
        return List.copyOf(EMBEDDING_MODELS);
    }

    /** Looks up one embedding model by its {@code provider}+{@code model} primary key. */
    public static Optional<EmbeddingModelEntry> findEmbeddingModel(Provider provider, String model) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        return EMBEDDING_MODELS.stream()
                .filter(entry -> entry.getProvider() == provider && entry.getModel().equals(model))
                .findFirst();
    }

    /**
     * Adds a new embedding model, or replaces the existing entry with the same {@code provider}+
     * {@code model} primary key — e.g. to price a model the seed table doesn't list.
     */
    public static void registerEmbeddingModel(EmbeddingModelEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        synchronized (WRITE_LOCK) {
            EMBEDDING_MODELS.removeIf(existing -> existing.getProvider() == entry.getProvider()
                    && existing.getModel().equals(entry.getModel()));
            EMBEDDING_MODELS.add(entry);
        }
    }

    /**
     * Removes the embedding model with the given {@code provider}+{@code model} primary key, if present.
     *
     * @return {@code true} if a matching entry was removed, {@code false} if none existed
     */
    public static boolean removeEmbeddingModel(Provider provider, String model) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
        synchronized (WRITE_LOCK) {
            return EMBEDDING_MODELS.removeIf(existing -> existing.getProvider() == provider && existing.getModel().equals(model));
        }
    }

    private static boolean matches(ModelEntry entry, Provider provider, String model) {
        return entry.getProvider() == provider && entry.getModel().equals(model);
    }

    private static CapabilityTableFile loadSeed() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ModelCapabilityTable.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Could not find " + RESOURCE_PATH + " on the classpath");
            }
            return mapper.readValue(in, CapabilityTableFile.class);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to load model capability table from " + RESOURCE_PATH, e);
        }
    }

    /**
     * Mirrors the top-level shape of {@code model-capability-table.json}. Exists purely to
     * deserialize the file — it is not part of this class's public API.
     */
    @Value
    @Builder
    @Jacksonized
    private static class CapabilityTableFile {
        int version;
        Instant lastUpdated;
        String notes;
        List<ModelEntry> models;
        List<EmbeddingModelEntry> embeddingModels;
    }
}
