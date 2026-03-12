package pl.marcinmilkowski.word_sketch.viz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generate radial collocation plot (spiral/snail visualization) for a single word.
 * Produces publication-quality SVG output matching the expected format from tests.
 *
 * Features:
 * - Guide circles at 100, 200, 300px radius
 * - Center circle with word
 * - Connector lines from center to collocate circles
 * - Collocate circles sized by score
 * - Labels with proper positioning
 */
public class RadialPlot {

    // Baseline canvas size (px) used to derive all spiral/layout constants.
    // All pixel values are multiplied by scale = min(width, height) / BASELINE_CANVAS_SIZE.
    private static final double BASELINE_CANVAS_SIZE = 800.0;

    // Spiral layout parameters (at baseline 800px canvas)
    private static final double SPIRAL_START_RADIUS  = 120.0; // first collocate distance from center
    private static final double SPIRAL_RADIUS_STEP   = 7.93;  // outward step per item; keeps 30 items within ~350px radius
    private static final double CENTER_CIRCLE_RADIUS = 40.0;  // keyword circle radius
    private static final double MIN_COLLOCATE_RADIUS = 8.0;   // minimum collocate circle radius
    // Guide circle radii
    private static final double GUIDE_RADIUS_1 = 100.0;
    private static final double GUIDE_RADIUS_2 = 200.0;
    private static final double GUIDE_RADIUS_3 = 300.0;

    // Stroke-width and font-size values (at baseline 800px canvas)
    private static final double GUIDE_STROKE_WIDTH     = 0.5;
    private static final double CONNECTOR_STROKE_WIDTH = 0.8;
    private static final double CENTER_STROKE_WIDTH    = 2.0;
    private static final double COLLOCATE_STROKE_WIDTH = 1.5;
    private static final double LABEL_FONT_SIZE        = 11.0;
    private static final double CENTER_FONT_SIZE       = 14.0;
    // Label offset from collocate circle edge (above/below center)
    private static final double LABEL_ABOVE_OFFSET     = 5.0;
    private static final double LABEL_BELOW_OFFSET     = 12.0;
    // Legend circle radius
    private static final double LEGEND_CIRCLE_RADIUS   = 6.0;
    private static final double LEGEND_CIRCLE_Y_OFFSET = -4.0; // vertical offset of legend circles from baseline

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    /**
     * Lightweight item representation for server-driven radials.
     */
    public static class Item {
        public final String word;
        public final double score;
        public Item(String word, double score) {
            this.word = word;
            this.score = score;
        }
    }

    private final String centerWord;
    private final List<Collocate> collocates;
    private final int width;
    private final int height;
    private final String mode;
    private final double centerX;
    private final double centerY;
    private final double scale;

    private static class Collocate {
        String word;
        double score;
        double orbitRadius;  // distance from center in pixels
        double angle;
        double x, y;         // computed position
        double glyphRadius;  // drawn circle size based on score

        Collocate(String word, double score) {
            this.word = word;
            this.score = score;
        }
    }

    public RadialPlot(String centerWord, List<Item> items, int width, int height) {
        this(centerWord, items, width, height, null);
    }

    public RadialPlot(String centerWord, List<Item> items, int width, int height, String mode) {
        this.centerWord = centerWord;
        this.width = width;
        this.height = height;
        this.mode = mode;

        this.centerX = width / 2.0;
        this.centerY = height / 2.0;
        // Scale factor: normalize all pixel values relative to an 800px baseline
        this.scale = Math.min(width, height) / BASELINE_CANVAS_SIZE;

        // Extract and sort collocates by absolute score (descending)
        collocates = new ArrayList<>();
        for (Item item : items) {
            double scoreToUse = "signed".equals(mode) ? item.score : Math.abs(item.score);
            collocates.add(new Collocate(item.word, scoreToUse));
        }
        collocates.sort((a, b) -> Double.compare(Math.abs(b.score), Math.abs(a.score)));

        // Take top 30 for clarity
        if (collocates.size() > 30) {
            collocates.subList(30, collocates.size()).clear();
        }

        // Calculate positions: spiral pattern
        if (!collocates.isEmpty()) {
            int n = collocates.size();

            // Spiral parameters scaled to canvas size (baseline: 800px reference)
            double startRadius = SPIRAL_START_RADIUS * scale;
            double radiusStep = SPIRAL_RADIUS_STEP * scale;

            for (int i = 0; i < n; i++) {
                Collocate c = collocates.get(i);

                // Spiral: radius increases by fixed step per item
                c.orbitRadius = startRadius + (i * radiusStep);

                // Evenly distribute around circle, starting from right (angle 0)
                c.angle = i * (2 * Math.PI / n);

                // Compute position
                c.x = centerX + c.orbitRadius * Math.cos(c.angle);
                c.y = centerY + c.orbitRadius * Math.sin(c.angle);

                // Circle radius based on absolute score magnitude, scaled to canvas
                c.glyphRadius = Math.abs(c.score) * scale;
            }
        }
    }

    /**
     * Render radial plot from a list of items (server-side API version)
     */
    public static String renderFromItems(String centerWord, List<Item> items, int width, int height, String mode) {
        RadialPlot plot = new RadialPlot(centerWord, items, width, height, mode);
        return plot.toSVG();
    }

    public String toSVG() {
        StringBuilder svg = new StringBuilder();

        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n", width, height, width, height));

        // CSS styles - stroke-width and font-size values scaled to canvas
        svg.append("  <style>\n");
        svg.append("    .background { fill: #fafafa; }\n");
        svg.append(String.format("    .guide-circle { fill: none; stroke: #ddd; stroke-width: %s; }\n", fmt(GUIDE_STROKE_WIDTH * scale)));
        svg.append(String.format("    .connector { stroke: #888; stroke-width: %s; opacity: 0.4; }\n", fmt(CONNECTOR_STROKE_WIDTH * scale)));
        svg.append(String.format("    .center-circle { fill: #2C3E50; stroke: white; stroke-width: %s; }\n", fmt(CENTER_STROKE_WIDTH * scale)));
        svg.append(String.format("    .collocate-circle { stroke: white; stroke-width: %s; opacity: 0.9; }\n", fmt(COLLOCATE_STROKE_WIDTH * scale)));
        svg.append(String.format("    .label { font-family: Arial, sans-serif; font-size: %spx; fill: #333; }\n", fmt(LABEL_FONT_SIZE * scale)));
        svg.append(String.format("    .center-label { font-family: Arial, sans-serif; font-size: %spx; font-weight: bold; fill: white; }\n", fmt(CENTER_FONT_SIZE * scale)));
        svg.append("  </style>\n");

        // Background
        svg.append(String.format("  <rect width=\"%d\" height=\"%d\" class=\"background\"/>\n", width, height));

        // Guides group - radii scaled to canvas
        svg.append("  <g id=\"guides\">\n");
        svg.append(String.format("    <circle class=\"guide-circle\" cx=\"%s\" cy=\"%s\" r=\"%s\"/>\n", fmt(centerX), fmt(centerY), fmt(GUIDE_RADIUS_1 * scale)));
        svg.append(String.format("    <circle class=\"guide-circle\" cx=\"%s\" cy=\"%s\" r=\"%s\"/>\n", fmt(centerX), fmt(centerY), fmt(GUIDE_RADIUS_2 * scale)));
        svg.append(String.format("    <circle class=\"guide-circle\" cx=\"%s\" cy=\"%s\" r=\"%s\"/>\n", fmt(centerX), fmt(centerY), fmt(GUIDE_RADIUS_3 * scale)));
        svg.append("  </g>\n");

        // Connectors group
        svg.append("  <g id=\"connectors\">\n");
        for (Collocate c : collocates) {
            // Draw connector line
            svg.append(String.format("    <line class=\"connector\" x1=\"%s\" y1=\"%s\" x2=\"%s\" y2=\"%s\"/>\n",
                fmt(centerX), fmt(centerY), fmt(c.x), fmt(c.y)));

            // Determine color based on mode
            String fillColor;
            if ("signed".equals(mode)) {
                if (c.score > 0) {
                    fillColor = "rgb(43,131,186)"; // Blue for positive
                } else if (c.score < 0) {
                    fillColor = "rgb(215,25,28)"; // Red for negative
                } else {
                    fillColor = "rgb(150,150,150)"; // Gray for neutral
                }
            } else {
                // Grayscale based on score
                int gray = (int) (200 - (c.score / 14.0) * 120);
                gray = Math.max(80, Math.min(200, gray));
                fillColor = String.format("rgb(%d,%d,%d)", gray, gray, gray);
            }

            // Draw collocate circle (scaled radius, min 8*scale)
            double r = Math.max(MIN_COLLOCATE_RADIUS * scale, c.glyphRadius);
            svg.append(String.format("    <circle class=\"collocate-circle\" cx=\"%s\" cy=\"%s\" r=\"%s\" fill=\"%s\"/>\n",
                fmt(c.x), fmt(c.y), fmt(r), fillColor));

            // Draw label - position above or below circle
            double labelY = c.y < centerY ? c.y - r - LABEL_ABOVE_OFFSET * scale : c.y + r + LABEL_BELOW_OFFSET * scale;
            svg.append(String.format("    <text class=\"label\" x=\"%s\" y=\"%s\" text-anchor=\"middle\">%s</text>\n",
                fmt(c.x), fmt(labelY), escapeXml(c.word)));
        }
        svg.append("  </g>\n");

        // Center circle and label - scaled to canvas
        svg.append("  <g id=\"center\">\n");
        svg.append(String.format("    <circle class=\"center-circle\" cx=\"%s\" cy=\"%s\" r=\"%s\"/>\n", fmt(centerX), fmt(centerY), fmt(CENTER_CIRCLE_RADIUS * scale)));
        svg.append(String.format("    <text class=\"center-label\" x=\"%s\" y=\"%s\" text-anchor=\"middle\" dominant-baseline=\"middle\">%s</text>\n",
            fmt(centerX), fmt(centerY), escapeXml(centerWord)));
        svg.append("  </g>\n");

        // Legend for signed mode
        if ("signed".equals(mode)) {
            svg.append(String.format("  <g id=\"legend\" transform=\"translate(20, %d)\">\n", height - 40));
            svg.append("    <text class=\"label\" x=\"0\" y=\"0\">Positive (A&gt;B)</text>\n");
            svg.append(String.format("    <circle cx=\"%s\" cy=\"%s\" r=\"%s\" fill=\"rgb(43,131,186)\"/>\n", fmt(-15.0 * scale), fmt(LEGEND_CIRCLE_Y_OFFSET * scale), fmt(LEGEND_CIRCLE_RADIUS * scale)));
            svg.append("    <text class=\"label\" x=\"120\" y=\"0\">Negative (B&gt;A)</text>\n");
            svg.append(String.format("    <circle cx=\"%s\" cy=\"%s\" r=\"%s\" fill=\"rgb(215,25,28)\"/>\n", fmt(105.0 * scale), fmt(LEGEND_CIRCLE_Y_OFFSET * scale), fmt(LEGEND_CIRCLE_RADIUS * scale)));
            svg.append("  </g>\n");
        }

        svg.append("</svg>");

        return svg.toString();
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
