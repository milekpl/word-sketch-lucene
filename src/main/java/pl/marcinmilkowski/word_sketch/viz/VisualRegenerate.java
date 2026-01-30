package pl.marcinmilkowski.word_sketch.viz;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Regenerate visualizations from existing JSON data without re-running corpus queries.
 * Usage: java -cp ... VisualRegenerate <json-file> <output-dir>
 */
public class VisualRegenerate {
    
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: VisualRegenerate <json-file> <output-dir>");
            System.exit(1);
        }
        
        String jsonFile = args[0];
        String outputDir = args[1];
        
        System.out.println("Reading data from: " + jsonFile);
        String jsonContent = Files.readString(Paths.get(jsonFile));
        JSONObject data = JSON.parseObject(jsonContent);
        
        JSONArray nodes = data.getJSONArray("nodes");
        JSONArray edges = data.getJSONArray("edges");
        
        System.out.println("Loaded " + nodes.size() + " nodes, " + edges.size() + " edges");
        
        Path outDir = Paths.get(outputDir);
        Files.createDirectories(outDir);
        
        // Regenerate network SVG
        System.out.println("\nGenerating network visualization...");
        generateNetworkSVG(nodes, edges, outDir.resolve("network.svg").toString());
        
        // Regenerate radial plots for top words
        System.out.println("\nGenerating radial plots...");
        
        // Build type map for nodes
        Map<String, String> nodeTypes = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            nodeTypes.put(node.getString("id"), node.getString("type"));
        }
        
        // Collect edges for each word - filter by edge type based on word type
        Map<String, List<EdgeData>> wordEdges = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            String source = edge.getString("source");
            String target = edge.getString("target");
            double weight = edge.getDoubleValue("weight");
            String edgeType = edge.getString("type");
            
            String sourceType = nodeTypes.get(source);
            
            // For adjectives: show only attributive edges (adjective → noun)
            // For nouns: show only linking edges (noun → adjective via linking verb)
            boolean includeEdge = false;
            if ("adjective".equals(sourceType) && "attributive".equals(edgeType)) {
                includeEdge = true;
            } else if ("noun".equals(sourceType) && "linking".equals(edgeType)) {
                includeEdge = true;
            }
            
            if (includeEdge) {
                wordEdges.computeIfAbsent(source, k -> new ArrayList<>())
                    .add(new EdgeData(source, target, weight, edgeType));
            }
        }
        
        List<Map.Entry<String, List<EdgeData>>> topWords = wordEdges.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(20)  // Generate more radials
            .collect(Collectors.toList());
        
        System.out.println("  Generating radials for top " + topWords.size() + " words:");
        for (Map.Entry<String, List<EdgeData>> entry : topWords) {
            String word = entry.getKey();
            String type = nodeTypes.get(word);
            String outputPath = outDir.resolve("radial_" + word + ".svg").toString();
            generateRadialPlot(word, type, entry.getValue(), outputPath);
            System.out.println("    " + word + " (" + type + "): " + entry.getValue().size() + " collocates");
        }
        
        System.out.println("\n✓ Visualization regeneration complete!");
        System.out.println("  Output: " + outDir);
    }
    
    private static void generateNetworkSVG(JSONArray nodes, JSONArray edges, String outputPath) throws IOException {
        int width = 1600;
        int height = 1200;
        
        // Parse nodes
        Map<String, NodeData> nodeMap = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            String id = node.getString("id");
            String type = node.getString("type");
            nodeMap.put(id, new NodeData(id, type));
        }
        
        // Simple grid layout to avoid overlapping
        int cols = (int) Math.ceil(Math.sqrt(nodeMap.size()));
        double xSpacing = (width - 200) / (double) cols;
        double ySpacing = (height - 200) / (double) Math.ceil(nodeMap.size() / (double) cols);
        
        List<NodeData> nodeList = new ArrayList<>(nodeMap.values());
        for (int i = 0; i < nodeList.size(); i++) {
            NodeData node = nodeList.get(i);
            node.x = 100 + (i % cols) * xSpacing;
            node.y = 100 + (i / cols) * ySpacing;
        }
        
        // Build SVG
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n", 
            width, height, width, height));
        
        svg.append("  <style>\n");
        svg.append("    .link { stroke: #999; stroke-width: 0.5; stroke-opacity: 0.3; }\n");
        svg.append("    .node-noun { fill: #4A90E2; stroke: white; stroke-width: 1.5; }\n");
        svg.append("    .node-adj { fill: #E27A4A; stroke: white; stroke-width: 1.5; }\n");
        svg.append("    .label { font-family: Arial, sans-serif; font-size: 8px; fill: #333; }\n");
        svg.append("  </style>\n\n");
        
        // Draw edges
        svg.append("  <g id=\"edges\">\n");
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            String source = edge.getString("source");
            String target = edge.getString("target");
            NodeData n1 = nodeMap.get(source);
            NodeData n2 = nodeMap.get(target);
            if (n1 != null && n2 != null) {
                svg.append(String.format(Locale.US, "    <line class=\"link\" x1=\"%.2f\" y1=\"%.2f\" x2=\"%.2f\" y2=\"%.2f\"/>\n",
                    n1.x, n1.y, n2.x, n2.y));
            }
        }
        svg.append("  </g>\n\n");
        
        // Draw nodes
        svg.append("  <g id=\"nodes\">\n");
        for (NodeData node : nodeList) {
            String className = node.type.equals("noun") ? "node-noun" : "node-adj";
            svg.append(String.format(Locale.US, "    <circle class=\"%s\" cx=\"%.2f\" cy=\"%.2f\" r=\"4\"/>\n",
                className, node.x, node.y));
        }
        svg.append("  </g>\n\n");
        
        // Draw labels (only for nodes with many connections to reduce clutter)
        svg.append("  <g id=\"labels\">\n");
        Set<String> highConnectivity = new HashSet<>();
        Map<String, Integer> connectCount = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            JSONObject edge = edges.getJSONObject(i);
            String source = edge.getString("source");
            connectCount.put(source, connectCount.getOrDefault(source, 0) + 1);
        }
        int threshold = Math.max(10, connectCount.values().stream().sorted(Comparator.reverseOrder())
            .limit(50).min(Integer::compareTo).orElse(10));
        
        for (NodeData node : nodeList) {
            int count = connectCount.getOrDefault(node.id, 0);
            if (count >= threshold) {
                svg.append(String.format(Locale.US, "    <text class=\"label\" x=\"%.2f\" y=\"%.2f\">%s</text>\n",
                    node.x + 6, node.y + 3, escapeXml(node.id)));
            }
        }
        svg.append("  </g>\n");
        
        svg.append("</svg>\n");
        
        Files.writeString(Paths.get(outputPath), svg.toString());
        System.out.println("  ✓ Network SVG: " + outputPath);
    }
    
    private static void generateRadialPlot(String centerWord, String wordType, List<EdgeData> edges, String outputPath) throws IOException {
        int size = 800;
        int centerX = size / 2;
        int centerY = size / 2;
        
        // Sort edges by weight (descending)
        edges.sort((a, b) -> Double.compare(b.weight, a.weight));
        List<EdgeData> top = edges.stream().limit(30).collect(Collectors.toList());
        
        StringBuilder svg = new StringBuilder();
        svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        svg.append(String.format("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\">\n",
            size, size, size, size));
        
        svg.append("  <style>\n");
        svg.append("    .background { fill: #fafafa; }\n");
        svg.append("    .guide-circle { fill: none; stroke: #ddd; stroke-width: 0.5; }\n");
        svg.append("    .connector { stroke: #888; stroke-width: 0.8; opacity: 0.4; }\n");
        svg.append("    .center-circle { fill: #2C3E50; stroke: white; stroke-width: 3; }\n");
        svg.append("    .center-text { fill: white; font-family: Arial, sans-serif; font-size: 16px; font-weight: bold; text-anchor: middle; dominant-baseline: middle; }\n");
        svg.append("    .center-type { fill: white; font-family: Arial, sans-serif; font-size: 10px; text-anchor: middle; dominant-baseline: middle; opacity: 0.7; }\n");
        svg.append("    .collocate-circle { stroke: white; stroke-width: 1.5; opacity: 0.9; }\n");
        svg.append("    .label { font-family: Arial, sans-serif; font-size: 11px; fill: #333; text-anchor: middle; }\n");
        svg.append("  </style>\n\n");
        
        svg.append(String.format("  <rect class=\"background\" width=\"%d\" height=\"%d\"/>\n\n", size, size));
        
        // Guide circles
        svg.append("  <g id=\"guides\">\n");
        for (int r = 100; r <= 300; r += 100) {
            svg.append(String.format("    <circle class=\"guide-circle\" cx=\"%d\" cy=\"%d\" r=\"%d\"/>\n",
                centerX, centerY, r));
        }
        svg.append("  </g>\n\n");
        
        // Connectors and collocates in spiral
        svg.append("  <g id=\"connectors\">\n");
        double angleStep = 360.0 / Math.max(top.size(), 1);
        
        for (int i = 0; i < top.size(); i++) {
            EdgeData edge = top.get(i);
            double angle = Math.toRadians(i * angleStep);
            
            // Distance from center based on rank (typicality)
            // More typical (higher weight/lower rank) = closer to center
            double minRadius = 120;
            double maxRadius = 350;
            double t = i / (double) Math.max(top.size() - 1, 1);
            double radius = minRadius + t * (maxRadius - minRadius);
            
            double x = centerX + radius * Math.cos(angle);
            double y = centerY + radius * Math.sin(angle);
            
            // Connector line
            svg.append(String.format(Locale.US, "    <line class=\"connector\" x1=\"%d\" y1=\"%d\" x2=\"%.2f\" y2=\"%.2f\"/>\n",
                centerX, centerY, x, y));
            
            // Collocate circle - grayscale based on typicality (darker = more typical)
            double grayValue = 80 + (t * 150);  // 80-230 range
            String fillColor = String.format("#%02x%02x%02x", (int)grayValue, (int)grayValue, (int)grayValue);
            
            double circleRadius = 12 - (t * 6);  // Larger circles for more typical
            svg.append(String.format(Locale.US, "    <circle class=\"collocate-circle\" cx=\"%.2f\" cy=\"%.2f\" r=\"%.2f\" fill=\"%s\"/>\n",
                x, y, circleRadius, fillColor));
            
            // Label
            double labelY = y + (y > centerY ? 18 : -10);
            svg.append(String.format(Locale.US, "    <text class=\"label\" x=\"%.2f\" y=\"%.2f\">%s</text>\n",
                x, labelY, escapeXml(edge.target)));
        }
        svg.append("  </g>\n\n");
        
        // Center word with type label
        svg.append(String.format("  <circle class=\"center-circle\" cx=\"%d\" cy=\"%d\" r=\"45\"/>\n", centerX, centerY));
        svg.append(String.format("  <text class=\"center-text\" x=\"%d\" y=\"%d\">%s</text>\n", 
            centerX, centerY - 5, escapeXml(centerWord)));
        svg.append(String.format("  <text class=\"center-type\" x=\"%d\" y=\"%d\">%s</text>\n", 
            centerX, centerY + 15, wordType));
        
        svg.append("</svg>\n");
        
        Files.writeString(Paths.get(outputPath), svg.toString());
    }
    
    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }
    
    static class NodeData {
        String id;
        String type;
        double x, y;
        
        NodeData(String id, String type) {
            this.id = id;
            this.type = type;
        }
    }
    
    static class EdgeData {
        String source;
        String target;
        double weight;
        String type;
        
        EdgeData(String source, String target, double weight, String type) {
            this.source = source;
            this.target = target;
            this.weight = weight;
            this.type = type;
        }
    }
}
