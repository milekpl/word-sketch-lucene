/**
 * Domain model classes for word-sketch query results and semantic-field exploration.
 *
 * <h2>Package boundary</h2>
 * <p>
 * This package contains two kinds of types:
 * </p>
 * <ul>
 *   <li><strong>Pure DTOs</strong> — records with no logic beyond field access:
 *       {@link pl.marcinmilkowski.word_sketch.model.exploration.CollocateProfile},
 *       {@link pl.marcinmilkowski.word_sketch.model.exploration.CoreCollocate},
 *       {@link pl.marcinmilkowski.word_sketch.model.exploration.DiscoveredNoun},
 *       {@link pl.marcinmilkowski.word_sketch.viz.Edge}, etc.</li>
 *   <li><strong>Model objects with derived accessors</strong> — immutable classes that expose
 *       computed views and factory methods alongside their fields:
 *       {@link pl.marcinmilkowski.word_sketch.model.exploration.ExplorationResult},
 *       {@link pl.marcinmilkowski.word_sketch.model.exploration.ComparisonResult}.
 *       These remain classes (not records) because they encapsulate construction logic or
 *       expose derived views that would otherwise leak into callers.</li>
 * </ul>
 * <p>No persistence, I/O, or HTTP concerns belong in this package.</p>
 *
 * <h2>Sub-package structure</h2>
 * <p>
 * Exploration types live in {@code model/exploration/} because they form a large, independently
 * evolving set. Word-sketch result types ({@link pl.marcinmilkowski.word_sketch.model.QueryResults})
 * remain as nested types in this root package rather than being split into the reserved
 * {@code model/sketch/} sub-package — see {@code model/sketch/package-info.java} for rationale.
 * </p>
 *
 * <h2>Naming conventions</h2>
 * <p>
 * Record types omit the {@code get} prefix per Java record conventions.
 * Traditional classes (non-records) also use un-prefixed accessors for consistency.
 * </p>
 */
package pl.marcinmilkowski.word_sketch.model;
