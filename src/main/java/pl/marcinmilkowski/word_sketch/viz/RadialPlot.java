package pl.marcinmilkowski.word_sketch.viz;

import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.Edge;

import java.util.*;

/**
 * Generate radial collocation plot (spiral/snail visualization) for a single word.
 * Ported from Python visualization showing collocates arranged by typicality (logDice score).
 * Produces publication-quality SVG output.
 */
public class RadialPlot {
    
    private static class Collocate {
        String word;
        double score;
        double radiusNorm;
        double angle;
        
        Collocate(String word, double score) {
            this.word = word;
            this.score = score;
        }
    }
    
    private final String centerWord;
    private final List<Collocate> collocates;
    private final int width;
    private final int height;
    
    public RadialPlot(String centerWord, List<Edge> edges, int width, int height) {
        this.centerWord = centerWord;
        this.width = width;
        this.height = height;
        
        // Extract and sort collocates by score
        collocates = new ArrayList<>();
        for (Edge e : edges) {
            collocates.add(new Collocate(e.target, e.weight));
        }
        collocates.sort((a, b) -> Double.compare(b.score, a.score));
        
        // Take top 30 for clarity
        if (collocates.size() > 30) {
            collocates.subList(30, collocates.size()).clear();
        }
        
        // Calculate positions
        if (!collocates.isEmpty()) {
            double maxScore = collocates.get(0).score;
            double minScore = collocates.get(collocates.size() - 1).score;
            double scoreRange = maxScore - minScore;
            if (scoreRange == 0) scoreRange = 1;
            
            for (int i = 0; i < collocates.size(); i++) {
                Collocate c = collocates.get(i);
                // Distance from center: higher score = closer to center
                // Map to radius range: 0.15 to 0.75 (normalized)
                c.radiusNorm = 0.15 + (0.75 - 0.15) * (maxScore - c.score) / scoreRange;
                // Evenly distribute around circle
                c.angle = i * (2 * Math.PI / collocates.size()) - Math.PI / 2;
            }
        }
    }
    
    public String toSVG() {
        StringBuilder svg = new StringBuilder();
        double centerX = width / 2.0;
        double centerY = height / 2.0;
        double radius = Math.min(width, height) / 2.0 * 0.9;
        
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
            width, height, width, height));
        
        // Style
        svg.append("  <defs>\n");
        svg.append("    <style>\n");
        svg.append("      .background { fill: #fafafa; }\n");
        svg.append("      .guide-line { stroke: #e0e0e0; stroke-width: 0.5; opacity: 0.6; }\n");
        svg.append("      .guide-circle { fill: none; stroke: #cccccc; stroke-width: 0.5; opacity: 0.5; }\n");
        svg.append("      .connector { stroke: #666666; stroke-width: 0.8; opacity: 0.5; }\n");
        svg.append("      .center-circle { fill: #2C3E50; stroke: white; stroke-width: 2; }\n");
        svg.append("      .center-text { fill: white; font-family: sans-serif; font-size: 18px; font-weight: bold; text-anchor: middle; dominant-baseline: middle; }\n");
        svg.append("      .label { font-family: sans-serif; fill: #333; }\n");
        svg.append("      .label.strong { font-weight: bold; fill: #222; }\n");
        svg.append("      .collocate-circle { stroke: white; stroke-width: 1.5; opacity: 0.9; }\n");
        svg.append("    </style>\n");
        svg.append("  </defs>\n\n");
        
        // Background
        svg.append(String.format("  <rect class=\"background\" width=\"%d\" height=\"%d\"/>\n\n", width, height));
        
        // Guide circles
        svg.append("  <g id=\"guides\">\n");
        for (double r : new double[]{0.3, 0.55}) {
            double circleRadius = r * radius;
            svg.append(String.format("    <circle class=\"guide-circle\" cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\"/>\n",
                centerX, centerY, circleRadius));
        }
        svg.append("  </g>\n\n");
        
        // Radial guide lines and connectors
        svg.append("  <g id=\"lines\">\n");
        for (Collocate c : collocates) {
            double x = centerX + c.radiusNorm * radius * Math.cos(c.angle);
            double y = centerY + c.radiusNorm * radius * Math.sin(c.angle);
            
            // Guide line from center
            svg.append(String.format("    <line class=\"guide-line\" x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"/>\n",
                centerX, centerY, x * 1.02, y * 1.02));
            
            // Connector to label
            double labelR = 0.88 * radius;
            double labelX = centerX + labelR * Math.cos(c.angle);
            double labelY = centerY + labelR * Math.sin(c.angle);
            double connX = x + 0.04 * radius * Math.cos(c.angle);
            double connY = y + 0.04 * radius * Math.sin(c.angle);
            
            svg.append(String.format("    <line class=\"connector\" x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"/>\n",
                connX, connY, labelX * 0.96, labelY * 0.96));
        }
        svg.append("  </g>\n\n");
        
        // Collocate circles
        svg.append("  <g id=\"collocates\">\n");
        if (!collocates.isEmpty()) {
            double maxScore = collocates.get(0).score;
            double minScore = collocates.get(collocates.size() - 1).score;
            double scoreRange = maxScore - minScore;
            if (scoreRange == 0) scoreRange = 1;
            
            for (Collocate c : collocates) {
                double x = centerX + c.radiusNorm * radius * Math.cos(c.angle);
                double y = centerY + c.radiusNorm * radius * Math.sin(c.angle);
                
                // Circle size based on score (more typical = darker)
                double circleR = 0.04 * radius + 0.04 * radius * (c.score - minScore) / scoreRange;
                
                // Grayscale: high score = dark, low score = light
                double gray = 0.85 - (c.score - minScore) / scoreRange * 0.65;
                String color = String.format("rgb(%d,%d,%d)", 
                    (int)(gray * 255), (int)(gray * 255), (int)(gray * 255));
                
                svg.append(String.format("    <circle class=\"collocate-circle\" cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\"/>\n",
                    x, y, circleR, color));
            }
        }
        svg.append("  </g>\n\n");
        
        // Center circle with keyword
        double centerRadius = 0.10 * radius;
        svg.append(String.format("  <circle class=\"center-circle\" cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\"/>\n",
            centerX, centerY, centerRadius));
        svg.append(String.format("  <text class=\"center-text\" x=\"%.2f\" y=\"%.2f\">%s</text>\n\n",
            centerX, centerY, escapeXml(centerWord)));
        
        // Labels
        svg.append("  <g id=\"labels\">\n");
        if (!collocates.isEmpty()) {
            double maxScore = collocates.get(0).score;
            double minScore = collocates.get(collocates.size() - 1).score;
            double scoreRange = maxScore - minScore;
            if (scoreRange == 0) scoreRange = 1;
            
            for (Collocate c : collocates) {
                double labelR = 0.88 * radius;
                double labelX = centerX + labelR * Math.cos(c.angle);
                double labelY = centerY + labelR * Math.sin(c.angle);
                
                // Text anchor based on position
                String anchor;
                double cosAngle = Math.cos(c.angle);
                if (Math.abs(cosAngle) < 0.15) {
                    anchor = "middle";
                } else if (cosAngle > 0) {
                    anchor = "start";
                } else {
                    anchor = "end";
                }
                
                // Font weight and size based on score
                boolean strong = c.score > minScore + 0.7 * scoreRange;
                double fontSize = 8 + (c.score - minScore) / scoreRange * 6;
                
                svg.append(String.format("    <text class=\"label%s\" x=\"%.2f\" y=\"%.2f\" text-anchor=\"%s\" font-size=\"%.1f\">%s</text>\n",
                    strong ? " strong" : "", labelX, labelY, anchor, fontSize, escapeXml(c.word)));
            }
        }
        svg.append("  </g>\n");
        
        svg.append("</svg>");
        return svg.toString();
    }
    
    private String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
