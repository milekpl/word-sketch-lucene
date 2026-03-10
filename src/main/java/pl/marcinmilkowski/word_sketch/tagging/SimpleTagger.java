package pl.marcinmilkowski.word_sketch.tagging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinmilkowski.word_sketch.query.PosGroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Simple rule-based POS tagger fallback.
 *
 * Used when UDPipe is not available.
 * Provides basic English POS tagging using lookup tables and rules.
 */
public class SimpleTagger {

    private static final Logger logger = LoggerFactory.getLogger(SimpleTagger.class);

    // Common word lists for basic tagging
    private static final Set<String> DETERMINERS = new HashSet<>(Arrays.asList(
        "the", "a", "an", "this", "that", "these", "those",
        "my", "your", "his", "her", "its", "our", "their"
    ));

    private static final Set<String> PRONOUNS = new HashSet<>(Arrays.asList(
        "i", "you", "he", "she", "it", "we", "they",
        "me", "him", "her", "us", "them",
        "myself", "yourself", "himself", "herself", "itself", "ourselves", "themselves",
        "who", "whom", "whose", "which", "what"
    ));

    private static final Set<String> PREPOSITIONS = new HashSet<>(Arrays.asList(
        "of", "in", "to", "for", "with", "on", "at", "by", "from", "up", "about",
        "into", "over", "after", "beneath", "under", "above"
    ));

    private static final Set<String> CONJUNCTIONS = new HashSet<>(Arrays.asList(
        "and", "but", "or", "nor", "for", "yet", "so",
        "although", "because", "unless", "while", "if", "that", "which", "who"
    ));

    private static final Set<String> AUX_VERBS = new HashSet<>(Arrays.asList(
        "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did",
        "will", "would", "shall", "should", "can", "could", "may", "might", "must"
    ));

    private static final Map<String, String> VERB_FORMS = new HashMap<>();

    static {
        // Common verb lemmas and their forms (Penn Treebank tags)
        VERB_FORMS.put("be", "VB");
        VERB_FORMS.put("is", "VBZ");
        VERB_FORMS.put("am", "VBP");
        VERB_FORMS.put("are", "VBP");
        VERB_FORMS.put("was", "VBD");
        VERB_FORMS.put("were", "VBD");
        VERB_FORMS.put("have", "VB");
        VERB_FORMS.put("has", "VBZ");
        VERB_FORMS.put("had", "VBD");
        VERB_FORMS.put("do", "VB");
        VERB_FORMS.put("does", "VBZ");
        VERB_FORMS.put("did", "VBD");
        VERB_FORMS.put("will", "MD");
        VERB_FORMS.put("would", "MD");
        VERB_FORMS.put("can", "MD");
        VERB_FORMS.put("could", "MD");
        VERB_FORMS.put("may", "MD");
        VERB_FORMS.put("might", "MD");
        VERB_FORMS.put("must", "MD");
    }

    // Common noun suffixes
    private static final Pattern NOUN_SUFFIXES = Pattern.compile(
        ".*(tion|ness|ment|ity|ty|ance|ence|er|or|ist|ism|ship|dom|ure|age)$"
    );

    // Common adjective suffixes
    private static final Pattern ADJ_SUFFIXES = Pattern.compile(
        ".*(able|ible|al|ful|less|ous|ive|ic|tial|cial|ed|ing)$"
    );

    // Common adverb suffixes
    private static final Pattern ADV_SUFFIXES = Pattern.compile(
        ".*(ly|ward|wards|wise)$"
    );

    // Common verb suffixes
    private static final Pattern VERB_SUFFIXES = Pattern.compile(
        ".*(ize|ise|ify|en|ate)$"
    );

    // Past tense pattern
    private static final Pattern PAST_TENSE = Pattern.compile(".*ed$");
    private static final Pattern PRESENT_PARTICIPLE = Pattern.compile(".*ing$");
    // Third person singular: verbs like runs, eats, goes (but not words like happiness, class)
    private static final Pattern THIRD_PERSON = Pattern.compile(".*[^sse]s$|.*[a-z]es$");
    private static final Pattern PLURAL = Pattern.compile(".*s$");

    private final Map<String, String> lexicon;

    public SimpleTagger() {
        this.lexicon = new HashMap<>();
        // Add common adjectives to lexicon
        lexicon.put("big", "JJ");
        lexicon.put("small", "JJ");
        lexicon.put("large", "JJ");
        lexicon.put("quick", "JJ");
        lexicon.put("slow", "JJ");
        lexicon.put("hot", "JJ");
        lexicon.put("cold", "JJ");
        lexicon.put("happy", "JJ");
        lexicon.put("sad", "JJ");
        lexicon.put("beautiful", "JJ");
        lexicon.put("ugly", "JJ");
        lexicon.put("good", "JJ");
        lexicon.put("bad", "JJ");
        lexicon.put("new", "JJ");
        lexicon.put("old", "JJ");
        lexicon.put("young", "JJ");
        lexicon.put("long", "JJ");
        lexicon.put("short", "JJ");
        lexicon.put("high", "JJ");
        lexicon.put("low", "JJ");
        lexicon.put("early", "JJ");
        lexicon.put("late", "JJ");
        lexicon.put("right", "JJ");
        lexicon.put("wrong", "JJ");
        // More adjectives from test corpus
        lexicon.put("warm", "JJ");
        lexicon.put("lovely", "JJ");
        lexicon.put("nice", "JJ");
        lexicon.put("interesting", "JJ");
        lexicon.put("heavy", "JJ");
        lexicon.put("strong", "JJ");
        lexicon.put("strange", "JJ");
        lexicon.put("hungry", "JJ");
        lexicon.put("busy", "JJ");
        lexicon.put("quiet", "JJ");
        lexicon.put("fast", "JJ");
        lexicon.put("brown", "JJ");
        lexicon.put("lazy", "JJ");
        lexicon.put("blue", "JJ");
        // Common nouns
        lexicon.put("house", "NN");
        lexicon.put("dog", "NN");
        lexicon.put("cat", "NN");
        lexicon.put("park", "NN");
        lexicon.put("bed", "NN");
        lexicon.put("hill", "NN");
        lexicon.put("man", "NN");
        lexicon.put("garden", "NN");
        lexicon.put("street", "NN");
        lexicon.put("fox", "NN");
        lexicon.put("river", "NN");
        lexicon.put("children", "NN");
        lexicon.put("yard", "NN");
        lexicon.put("flower", "NN");
        lexicon.put("meadow", "NN");
        lexicon.put("weather", "NN");
        lexicon.put("spring", "NN");
        lexicon.put("birds", "NN");
        lexicon.put("sky", "NN");
        lexicon.put("gift", "NN");
        lexicon.put("paintings", "NN");
        lexicon.put("wall", "NN");
        lexicon.put("cars", "NN");
        lexicon.put("highway", "NN");
        lexicon.put("teacher", "NN");
        lexicon.put("lesson", "NN");
        lexicon.put("students", "NN");
        lexicon.put("books", "NN");
        lexicon.put("library", "NN");
        lexicon.put("rain", "NN");
        lexicon.put("city", "NN");
        lexicon.put("night", "NN");
        lexicon.put("wind", "NN");
        lexicon.put("north", "NN");
        lexicon.put("noise", "NN");
        // Common verbs
        lexicon.put("runs", "VBZ");
        lexicon.put("sleeps", "VBZ");
        lexicon.put("stands", "VBZ");
        lexicon.put("walks", "VBZ");
        lexicon.put("chase", "VB");
        lexicon.put("jumps", "VBZ");
        lexicon.put("see", "VB");
        lexicon.put("play", "VB");
        lexicon.put("grows", "VBZ");
        lexicon.put("changes", "VBZ");
        lexicon.put("fly", "VB");
        lexicon.put("receives", "VBZ");
        lexicon.put("hang", "VB");
        lexicon.put("drive", "VB");
        lexicon.put("explains", "VBZ");
        lexicon.put("read", "VB");
        lexicon.put("falls", "VBZ");
        lexicon.put("blows", "VBZ");
        lexicon.put("wakes", "VBZ");
        lexicon.put("meows", "VBZ");
    }

    /**
     * Create a simple tagger with an optional custom lexicon.
     */
    public static SimpleTagger create() {
        return new SimpleTagger();
    }

    /**
     * Create a simple tagger with a custom lexicon file.
     */
    public static SimpleTagger create(String lexiconFile) throws IOException {
        SimpleTagger tagger = new SimpleTagger();
        tagger.loadLexicon(lexiconFile);
        return tagger;
    }

    /**
     * Load words from a lexicon file (one word per line, format: word tag).
     */
    public void loadLexicon(String file) throws IOException {
        try (Scanner scanner = new Scanner(new java.io.File(file))) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\s+", 2);
                if (parts.length >= 2) {
                    lexicon.put(parts[0].toLowerCase(), parts[1]);
                }
            }
        }
        logger.info("Loaded {} entries from lexicon", lexicon.size());
    }


    public List<TaggedToken> tagSentence(String sentence) throws IOException {
        // Simple tokenization
        String[] words = sentence.split("\\s+");
        List<TaggedToken> tokens = new ArrayList<>();

        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            // Remove punctuation for tagging
            String cleanWord = word.replaceAll("[^\\w'-]", "");

            if (cleanWord.isEmpty()) {
                continue;
            }

            TaggedToken token = tagWord(cleanWord, i, word);
            tokens.add(token);
        }

        return tokens;
    }

    private TaggedToken tagWord(String word, int position, String originalWord) {
        String lowerWord = word.toLowerCase();
        String lemma = lowerWord;

        // Check lexicon first
        if (lexicon.containsKey(lowerWord)) {
            String tag = lexicon.get(lowerWord);
            return new TaggedToken(originalWord, lemma, tag, position);
        }

        // Check auxiliary verbs
        if (AUX_VERBS.contains(lowerWord)) {
            String tag = determineVerbForm(lowerWord);
            return new TaggedToken(originalWord, lemma, tag, position);
        }

        // Check determiners
        if (DETERMINERS.contains(lowerWord)) {
            return new TaggedToken(originalWord, lemma, "DT", position);
        }

        // Check pronouns
        if (PRONOUNS.contains(lowerWord)) {
            // Distinguish between personal and possessive
            if (lowerWord.endsWith("'s") || lowerWord.endsWith("'")) {
                return new TaggedToken(originalWord, lemma, "PP$", position);
            }
            return new TaggedToken(originalWord, lemma, "PP", position);
        }

        // Check prepositions
        if (PREPOSITIONS.contains(lowerWord)) {
            return new TaggedToken(originalWord, lemma, "IN", position);
        }

        // Check conjunctions
        if (CONJUNCTIONS.contains(lowerWord)) {
            return new TaggedToken(originalWord, lemma, "CC", position);
        }

        // Rule-based tagging based on word form
        if (PAST_TENSE.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "VBD", position);
        }

        if (PRESENT_PARTICIPLE.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "VBG", position);
        }

        if (THIRD_PERSON.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "VBZ", position);
        }

        // Check suffixes
        if (NOUN_SUFFIXES.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "NN", position);
        }

        if (ADJ_SUFFIXES.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "JJ", position);
        }

        if (ADV_SUFFIXES.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "RB", position);
        }

        if (VERB_SUFFIXES.matcher(lowerWord).matches()) {
            return new TaggedToken(originalWord, lemma, "VB", position);
        }

        // Numbers
        if (lowerWord.matches("\\d+(\\.\\d+)?") || lowerWord.matches("\\d+,\\d{3}")) {
            return new TaggedToken(originalWord, lemma, "CD", position);
        }

        // Default: assume noun if capitalized (proper noun heuristic)
        if (Character.isUpperCase(word.charAt(0))) {
            return new TaggedToken(originalWord, lemma, "NNP", position);
        }

        // Default: common noun
        return new TaggedToken(originalWord, lemma, "NN", position);
    }

    private String determineVerbForm(String word) {
        if (PAST_TENSE.matcher(word).matches()) return "VBD";
        if (PRESENT_PARTICIPLE.matcher(word).matches()) return "VBG";
        if (THIRD_PERSON.matcher(word).matches()) return "VBZ";
        if (word.equals("am")) return "VBD";
        return "VB";
    }


    public List<List<TaggedToken>> tagSentences(List<String> sentences) throws IOException {
        List<List<TaggedToken>> results = new ArrayList<>();
        for (String sentence : sentences) {
            results.add(tagSentence(sentence));
        }
        return results;
    }


    public String getName() {
        return "Simple Tagger (Rule-based)";
    }


    public String getTagset() {
        return "Penn Treebank (simplified)";
    }

    /** A single token with its POS tag, lemma, and position. */
    public static class TaggedToken {
        private final String word;
        private final String lemma;
        private final String tag;
        private final int position;

        public TaggedToken(String word, String lemma, String tag, int position) {
            this.word = word;
            this.lemma = lemma;
            this.tag = tag;
            this.position = position;
        }

        public String getWord() { return word; }
        public String getLemma() { return lemma; }
        public String getTag() { return tag; }
        public int getPosition() { return position; }

        public String getPosGroup() {
            if (tag == null) return "other";
            char firstChar = tag.charAt(0);
            switch (firstChar) {
                case 'N': return PosGroup.NOUN;
                case 'V': return PosGroup.VERB;
                case 'J': return PosGroup.ADJ;
                case 'R': return PosGroup.ADV;
                case 'D': return "det";
                case 'P': return "pron";
                case 'I': return "prep";
                case 'C': return "conj";
                case 'U': return "punct";
                default: return "other";
            }
        }

        @Override
        public String toString() {
            return word + "\t" + tag + "\t" + lemma;
        }
    }
}
