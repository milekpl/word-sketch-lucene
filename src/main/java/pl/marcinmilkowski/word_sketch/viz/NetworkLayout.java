package pl.marcinmilkowski.word_sketch.viz;

import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.Edge;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.SnowballResult;

import java.util.*;

/**
 * Force-directed layout algorithm for network visualization.
 * Generates publication-quality SVG output.
 */
public class NetworkLayout {
    
    private static class Node {
        String id;
        String type; // "noun" or "adjective"
        double x, y;
        double vx, vy;
        
        Node(String id, String type, double x, double y) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }
    
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges;
    private final int width;
    private final int height;
    private final Random rand = new Random(42);
    
    public NetworkLayout(SnowballResult result, int width, int height) {
        this.width = width;
        this.height = height;
        this.edges = result.getEdges();
        
        // Create nodes
        Set<String> allWords = new HashSet<>();
        allWords.addAll(result.getAllNouns());
        allWords.addAll(result.getAllAdjectives());
        
        for (String word : allWords) {
            String type = result.getAllNouns().contains(word) ? "noun" : "adjective";
            nodes.put(word, new Node(word, type, 
                rand.nextDouble() * width, 
                rand.nextDouble() * height));
        }
    }
    
    /**
     * Run force-directed layout algorithm.
     */
    public void compute(int iterations) {
        double k = Math.sqrt((width * height) / nodes.size());
        
        for (int iter = 0; iter < iterations; iter++) {
            double temp = (1.0 - iter / (double) iterations) * 100;
            
            // Repulsive forces between all nodes
            for (Node n1 : nodes.values()) {
                n1.vx = 0;
                n1.vy = 0;
                for (Node n2 : nodes.values()) {
                    if (n1 == n2) continue;
                    double dx = n1.x - n2.x;
                    double dy = n1.y - n2.y;
                    double dist = Math.sqrt(dx * dx + dy * dy);
                    if (dist < 0.01) dist = 0.01;
                    
                    double force = k * k / dist;
                    n1.vx += (dx / dist) * force;
                    n1.vy += (dy / dist) * force;
                }
            }
            
            // Attractive forces along edges
            for (Edge e : edges) {
                Node source = nodes.get(e.source);
                Node target = nodes.get(e.target);
                if (source == null || target == null) continue;
                
                double dx = target.x - source.x;
                double dy = target.y - source.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 0.01) dist = 0.01;
                
                double force = (dist * dist) / k;
                double fx = (dx / dist) * force;
                double fy = (dy / dist) * force;
                
                source.vx += fx;
                source.vy += fy;
                target.vx -= fx;
                target.vy -= fy;
            }
            
            // Update positions
            for (Node n : nodes.values()) {
                double len = Math.sqrt(n.vx * n.vx + n.vy * n.vy);
                if (len > temp) {
                    n.vx = (n.vx / len) * temp;
                    n.vy = (n.vy / len) * temp;
                }
                
                n.x += n.vx;
                n.y += n.vy;
                
                // Keep in bounds with padding
                n.x = Math.max(50, Math.min(width - 50, n.x));
                n.y = Math.max(50, Math.min(height - 50, n.y));
            }
        }
    }
    
    /**
     * Export to SVG format.
     */
    public String toSVG() {
        StringBuilder svg = new StringBuilder();
        
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
            width, height, width, height));
        
        // Style definitions
        svg.append("  <defs>\n");
        svg.append("    <style>\n");
        svg.append("      .edge { stroke: #999; stroke-opacity: 0.6; fill: none; }\n");
        svg.append("      .edge.linking { stroke-dasharray: 5,5; }\n");
        svg.append("      .node.noun { fill: #4A90E2; }\n");
        svg.append("      .node.adjective { fill: #E27A4A; }\n");
        svg.append("      .node { stroke: white; stroke-width: 1.5; }\n");
        svg.append("      .label { font-family: sans-serif; font-size: 10px; fill: #333; }\n");
        svg.append("    </style>\n");
        svg.append("  </defs>\n\n");
        
        // Background
        svg.append(String.format("  <rect width=\"%d\" height=\"%d\" fill=\"#fafafa\"/>\n\n", width, height));
        
        // Draw edges
        svg.append("  <g id=\"edges\">\n");
        for (Edge e : edges) {
            Node source = nodes.get(e.source);
            Node target = nodes.get(e.target);
            if (source == null || target == null) continue;
            
            double strokeWidth = Math.sqrt(e.weight) / 2;
            svg.append(String.format("    <line class=\"edge %s\" x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\" stroke-width=\"%.2f\"/>\n",
                e.type, source.x, source.y, target.x, target.y, strokeWidth));
        }
        svg.append("  </g>\n\n");
        
        // Draw nodes
        svg.append("  <g id=\"nodes\">\n");
        for (Node n : nodes.values()) {
            svg.append(String.format("    <circle class=\"node %s\" cx=\"%.2f\" cy=\"%.2f\" r=\"6\"/>\n",
                n.type, n.x, n.y));
        }
        svg.append("  </g>\n\n");
        
        // Draw labels
        svg.append("  <g id=\"labels\">\n");
        for (Node n : nodes.values()) {
            svg.append(String.format("    <text class=\"label\" x=\"%.2f\" y=\"%.2f\" text-anchor=\"middle\">%s</text>\n",
                n.x, n.y - 10, escapeXml(n.id)));
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
