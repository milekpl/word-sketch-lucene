package pl.marcinmilkowski.word_sketch.query;

import nl.inl.blacklab.queryParser.corpusql.CorpusQueryLanguageParser;
import nl.inl.blacklab.search.textpattern.TextPattern;
import nl.inl.blacklab.exceptions.InvalidQuery;

public class TestBCQLParser {
    public static void main(String[] args) {
        String[] testQueries = {
            "[pos=\"NN\"]",
            "[pos=\"NN\"] [lemma=\"test\"]",
            "\"test\" [pos=\"NN\"]",
            "[pos=\"ADJ\"]{2,3} \"man\"",
            "[lemma=\"test\"] [pos=\"NN\"]"
        };
        
        for (String query : testQueries) {
            System.out.println("\n=== Testing: " + query + " ===");
            try {
                TextPattern pattern = CorpusQueryLanguageParser.parse(query, "lemma");
                System.out.println("SUCCESS: " + pattern.getClass().getName());
                System.out.println("Pattern: " + pattern);
            } catch (InvalidQuery e) {
                System.out.println("FAILED: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getClass().getName() + ": " + e.getMessage());
            }
        }
    }
}
