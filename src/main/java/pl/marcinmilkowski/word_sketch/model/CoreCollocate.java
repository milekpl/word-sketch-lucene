package pl.marcinmilkowski.word_sketch.model;

/**
 * A collocate that defines the semantic class (shared by multiple discovered nouns).
 */
public class CoreCollocate {
    public final String collocate;
    public final int sharedByCount;    // How many discovered nouns share this collocate
    public final int totalNouns;       // Total discovered nouns
    public final double seedLogDice;   // LogDice with the seed word
    public final double avgLogDice;    // Average logDice across discovered nouns

    public CoreCollocate(String collocate, int sharedByCount, int totalNouns,
            double seedLogDice, double avgLogDice) {
        this.collocate = collocate;
        this.sharedByCount = sharedByCount;
        this.totalNouns = totalNouns;
        this.seedLogDice = seedLogDice;
        this.avgLogDice = avgLogDice;
    }

    /** Coverage ratio: how many of the discovered nouns share this collocate */
    public double getCoverage() {
        return totalNouns > 0 ? (double) sharedByCount / totalNouns : 0.0;
    }

    @Override
    public String toString() {
        return String.format("%s (in %d/%d nouns, avgLogDice=%.1f)",
            collocate, sharedByCount, totalNouns, avgLogDice);
    }
}
