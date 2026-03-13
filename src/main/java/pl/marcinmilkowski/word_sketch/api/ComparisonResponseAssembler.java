package pl.marcinmilkowski.word_sketch.api;

import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pl.marcinmilkowski.word_sketch.api.model.CollocateProfileEntry;
import pl.marcinmilkowski.word_sketch.api.model.ComparisonResponse;
import pl.marcinmilkowski.word_sketch.api.model.EdgeEntry;
import pl.marcinmilkowski.word_sketch.model.exploration.CollocateProfile;
import pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult;
import pl.marcinmilkowski.word_sketch.model.exploration.Edge;
import pl.marcinmilkowski.word_sketch.model.exploration.ExplorationOptions;
import pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType;
import pl.marcinmilkowski.word_sketch.utils.MathUtils;

/**
 * Converts {@link ComparisonResult} objects into {@link ComparisonResponse} API responses
 * and their associated {@link Edge} lists for visualization.
 *
 * <p>Separated from {@link ExploreResponseAssembler} so that single/multi-seed exploration
 * assembly and noun-comparison assembly each have a single, focused responsibility.</p>
 */
final class ComparisonResponseAssembler {

    private ComparisonResponseAssembler() {}

    /**
     * Builds {@link RelationEdgeType#MODIFIER} edges for collocate-noun pairs with positive
     * logDice scores from a comparison result.
     */
    static @NonNull List<Edge> buildComparisonEdges(@NonNull ComparisonResult result) {
        List<Edge> edges = new ArrayList<>();
        for (CollocateProfile collocate : result.collocates()) {
            for (Map.Entry<String, Double> entry : collocate.nounScores().entrySet()) {
                if (entry.getValue() > 0) {
                    edges.add(new Edge(collocate.collocate(), entry.getKey(), entry.getValue(), RelationEdgeType.MODIFIER));
                }
            }
        }
        return edges;
    }

    /**
     * Builds a fully-typed {@link ComparisonResponse} for the noun-comparison endpoint.
     *
     * @param seeds         the seed nouns being compared
     * @param relationType  resolved relation identifier
     * @param params        shared exploration parameters
     * @param result        comparison result from the exploration service
     * @return fully populated {@link ComparisonResponse}
     */
    static @NonNull ComparisonResponse buildComparisonResponse(
            @NonNull List<String> seeds, @NonNull String relationType,
            @NonNull ExplorationOptions params,
            @NonNull ComparisonResult result) {
        List<CollocateProfileEntry> collocates = result.collocates().stream()
                .map(ComparisonResponseAssembler::collocateProfileToEntry)
                .toList();
        ComparisonResult.SummaryCounts counts = result.summaryCounts();
        List<EdgeEntry> edges = buildComparisonEdges(result).stream()
                .map(e -> new EdgeEntry(e.source(), e.target(), e.weight(), e.type()))
                .toList();
        return new ComparisonResponse(
                "ok", seeds, seeds.size(),
                new ComparisonResponse.Parameters(relationType, params.topCollocates(), params.minShared(), params.logDiceThreshold()),
                collocates, collocates.size(),
                counts.fullyShared(), counts.partiallyShared(), counts.specific(),
                edges, edges.size());
    }

    private static CollocateProfileEntry collocateProfileToEntry(CollocateProfile collocate) {
        Map<String, Double> nounScores = new HashMap<>();
        for (Map.Entry<String, Double> entry : collocate.nounScores().entrySet()) {
            nounScores.put(entry.getKey(), MathUtils.round2dp(entry.getValue()));
        }
        String specificTo = collocate.isSpecific()
                ? collocate.strongestNoun().orElse(null)
                : null;
        return new CollocateProfileEntry(
                collocate.collocate(),
                collocate.presentInCount(),
                collocate.totalNouns(),
                MathUtils.round2dp(collocate.avgLogDice()),
                MathUtils.round2dp(collocate.maxLogDice()),
                MathUtils.round2dp(collocate.variance()),
                MathUtils.round2dp(collocate.commonalityScore()),
                MathUtils.round2dp(collocate.distinctivenessScore()),
                collocate.sharingCategory().label(),
                nounScores,
                specificTo);
    }
}
