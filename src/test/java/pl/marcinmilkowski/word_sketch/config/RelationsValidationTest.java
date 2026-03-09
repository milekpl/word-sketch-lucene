package pl.marcinmilkowski.word_sketch.config;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Quick validation test for relations.json patterns.
 */
public class RelationsValidationTest {

    public static void main(String[] args) throws IOException {
        Path grammarPath = Paths.get("grammars/relations.json");
        GrammarConfigLoader loader = new GrammarConfigLoader(grammarPath);
        List<GrammarConfigLoader.RelationConfig> relations = loader.getRelations();

        System.out.println("Total relations: " + relations.size());

        int withPattern = 0;
        int withoutPattern = 0;

        for (GrammarConfigLoader.RelationConfig rel : relations) {
            if (rel.pattern() != null && !rel.pattern().isBlank()) {
                withPattern++;
            } else {
                withoutPattern++;
                System.out.println("MISSING PATTERN: " + rel.id());
            }
        }

        System.out.println("\nWith pattern: " + withPattern);
        System.out.println("Without pattern: " + withoutPattern);

        if (withoutPattern > 0) {
            System.exit(1);
        }
    }
}
