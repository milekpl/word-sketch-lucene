/**
 * Visualization utilities for semantic-field data.
 *
 * <p>This package contains rendering and data-preparation helpers that transform
 * domain model objects into visualization-ready structures. Domain data carriers
 * (e.g. {@link pl.marcinmilkowski.word_sketch.model.exploration.Edge},
 * {@link pl.marcinmilkowski.word_sketch.model.exploration.RelationEdgeType}) live in
 * {@code model/exploration/} so that non-visualization layers (e.g. {@code api/}) can
 * import them without creating an {@code api→viz} dependency.</p>
 *
 * <p>No HTTP, persistence, or query-execution concerns belong in this package.</p>
 */
package pl.marcinmilkowski.word_sketch.viz;
