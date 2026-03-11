package pl.marcinmilkowski.word_sketch.config;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable grammar configuration value object holding the parsed relation data.
 *
 * <p>Instances are produced by {@link GrammarConfigLoader}. Separating the loaded data
 * from the loading mechanism lets callers depend only on the data they need, without
 * carrying a reference to the file-IO machinery.</p>
 */
public final class GrammarConfig {

    private final List<RelationConfig> relations;
    private final Map<String, RelationConfig> relationsById;
    private final String version;
    private final Path configPath;

    GrammarConfig(List<RelationConfig> relations, Map<String, RelationConfig> relationsById,
                  String version, Path configPath) {
        this.relations = Collections.unmodifiableList(relations);
        this.relationsById = Collections.unmodifiableMap(relationsById);
        this.version = version;
        this.configPath = configPath;
    }

    /** @return all grammar relations in declaration order; never null, may be empty */
    public List<RelationConfig> getRelations() {
        return relations;
    }

    /** @return the relation config for the given ID, or empty if no relation with that ID is registered */
    public Optional<RelationConfig> getRelation(String id) {
        return Optional.ofNullable(relationsById.get(id));
    }

    /**
     * @return the grammar version string from the config file (e.g. {@code "1.0"});
     *         never null — the loader rejects configs that omit the version field
     */
    public @org.jspecify.annotations.NonNull String getVersion() {
        return version;
    }

    /** @return the path to the config file this grammar was loaded from, or null when loaded from the classpath */
    public @org.jspecify.annotations.Nullable Path getConfigPath() {
        return configPath;
    }
}
