package pl.marcinmilkowski.word_sketch.viz;

import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.Edge;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.SnowballResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Export snowball results to publication-quality vector formats (SVG) and JSON.
 * Generates network graphs and radial collocation plots suitable for academic papers.
 */
public class SnowballVisualizer {
    
    private final SnowballResult result;
    
    public SnowballVisualizer(SnowballResult result) {
        this.result = result;
    }
    
    /**
     * Export to JSON for data interchange and web visualization.
     */
    public void exportToJSON(String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        
        System.out.println("  Creating JSON export...");
        
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write("{\n");
            writer.write("  \"nodes\": [\n");
            
            // Export all unique words as nodes
            Set<String> allWords = new HashSet<>();
            allWords.addAll(result.getAllNouns());
            allWords.addAll(result.getAllAdjectives());
            
            List<String> wordList = new ArrayList<>(allWords);
            for (int i = 0; i < wordList.size(); i++) {
                String word = wordList.get(i);
                String type = result.getAllNouns().contains(word) ? "noun" : "adjective";
                writer.write(String.format("    {\"id\": \"%s\", \"type\": \"%s\"}%s\n", 
                    word, type, i < wordList.size() - 1 ? "," : ""));
            }
            
            writer.write("  ],\n");
            writer.write("  \"edges\": [\n");
            
            // Export edges
            List<Edge> edges = result.getEdges();
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                writer.write(String.format(Locale.US, "    {\"source\": \"%s\", \"target\": \"%s\", \"weight\": %.2f, \"type\": \"%s\"}%s\n",
                    e.source, e.target, e.weight, e.type, i < edges.size() - 1 ? "," : ""));
            }
            
            writer.write("  ]\n");
            writer.write("}\n");
        }
        
        long fileSize = Files.size(path);
        System.out.println("  ✓ " + path.toAbsolutePath() + " (" + fileSize + " bytes)");
    }
    
    /**
     * Export full network as SVG using force-directed layout.
     */
    public void exportNetworkSVG(String outputPath, int width, int height) throws IOException {
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        
        System.out.println("  Creating network graph (" + width + "x" + height + ")...");
        
        NetworkLayout layout = new NetworkLayout(result, width, height);
        layout.compute(100); // 100 iterations
        
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(layout.toSVG());
        }
        
        long fileSize = Files.size(path);
        System.out.println("  ✓ " + path.toAbsolutePath() + " (" + fileSize + " bytes)");
    }
    
    /**
     * Export radial collocation plot for a specific word (publication quality).
     * Similar to the Python visualization showing collocates arranged by typicality.
     */
    public void exportRadialPlotSVG(String word, List<Edge> edges, String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        
        RadialPlot plot = new RadialPlot(word, edges, 800, 800);
        
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(plot.toSVG());
        }
        
        long fileSize = Files.size(path);
        System.out.println("  ✓ radial_" + word + ".svg (" + fileSize + " bytes)");
    }
    
    /**
     * Generate a self-contained HTML viewer with D3.js for interactive exploration.
     * Embeds JSON data directly to avoid CORS issues when opening from file system.
     */
    public void exportHTMLViewer(String outputPath) throws IOException {
        Path path = Paths.get(outputPath);
        Files.createDirectories(path.getParent());
        
        System.out.println("  Creating HTML viewer with embedded data...");
        
        // Generate JSON data inline
        StringBuilder jsonData = new StringBuilder();
        jsonData.append("{\n  \"nodes\": [\n");
        
        Set<String> allWords = new HashSet<>();
        allWords.addAll(result.getAllNouns());
        allWords.addAll(result.getAllAdjectives());
        
        List<String> wordList = new ArrayList<>(allWords);
        for (int i = 0; i < wordList.size(); i++) {
            String word = wordList.get(i);
            String type = result.getAllNouns().contains(word) ? "noun" : "adjective";
            jsonData.append(String.format("    {\"id\": \"%s\", \"type\": \"%s\"}%s\n",
                word, type, i < wordList.size() - 1 ? "," : ""));
        }
        
        jsonData.append("  ],\n  \"edges\": [\n");
        
        List<Edge> edges = result.getEdges();
        for (int i = 0; i < edges.size(); i++) {
            Edge e = edges.get(i);
            jsonData.append(String.format(Locale.US, "    {\"source\": \"%s\", \"target\": \"%s\", \"weight\": %.2f, \"type\": \"%s\"}%s\n",
                e.source, e.target, e.weight, e.type, i < edges.size() - 1 ? "," : ""));
        }
        
        jsonData.append("  ]\n}");
        
        String html = generateHTMLTemplateWithData(jsonData.toString());
        
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            writer.write(html);
        }
        
        long fileSize = Files.size(path);
        System.out.println("  ✓ " + path.toAbsolutePath() + " (" + fileSize + " bytes)");
    }
    
    /**
     * Export everything: JSON, network SVG, radial plots for top words, and HTML viewer.
     */
    public void exportAll(String outputDir) throws IOException {
        Path dir = Paths.get(outputDir).toAbsolutePath();
        Files.createDirectories(dir);
        
        System.out.println("\n╔════════════════════════════════════════════════╗");
        System.out.println("║   EXPORTING VISUALIZATIONS                   ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println("Output directory: " + dir);
        System.out.println();
        
        // Export JSON
        System.out.println("[1/4] Exporting JSON data...");
        String jsonPath = dir.resolve("snowball_data.json").toString();
        exportToJSON(jsonPath);
        
        // Export network SVG
        System.out.println("\n[2/4] Exporting network graph...");
        String networkPath = dir.resolve("snowball_network.svg").toString();
        exportNetworkSVG(networkPath, 1200, 900);
        
        // Export radial plots for top 10 most connected words
        System.out.println("\n[3/4] Exporting radial plots for top words...");
        Map<String, List<Edge>> wordEdges = new HashMap<>();
        for (Edge e : result.getEdges()) {
            wordEdges.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e);
        }
        
        List<Map.Entry<String, List<Edge>>> topWords = wordEdges.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
            .limit(10)
            .collect(Collectors.toList());
        
        System.out.println("  Top " + topWords.size() + " words by connection count:");
        for (Map.Entry<String, List<Edge>> entry : topWords) {
            String word = entry.getKey();
            String radialPath = dir.resolve("radial_" + word + ".svg").toString();
            exportRadialPlotSVG(word, entry.getValue(), radialPath);
        }
        
        // Export HTML viewer
        System.out.println("\n[4/4] Exporting HTML viewer...");
        String htmlPath = dir.resolve("viewer.html").toString();
        exportHTMLViewer(htmlPath);
        
        System.out.println("\n╔════════════════════════════════════════════════╗");
        System.out.println("║   EXPORT COMPLETE!                           ║");
        System.out.println("╚════════════════════════════════════════════════╝");
        System.out.println("\nFiles created in: " + dir);
        System.out.println("  • snowball_data.json       - Structured data");
        System.out.println("  • snowball_network.svg     - Network graph (vector)");
        System.out.println("  • radial_*.svg (x" + topWords.size() + ")       - Radial plots (vector)");
        System.out.println("  • viewer.html              - Interactive viewer");
        System.out.println("\n▶ To view: Open " + dir.resolve("viewer.html") + " in your browser");
        System.out.println("▶ For publication: Use the SVG files (vector format, scalable)");
    }
    
    private String generateHTMLTemplateWithData(String jsonData) {
        return """
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>Snowball Collocation Explorer</title>
    <script src="https://d3js.org/d3.v7.min.js"></script>
    <style>
        body {
            margin: 0;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #f5f5f5;
        }
        #container {
            display: flex;
            height: 100vh;
        }
        #sidebar {
            width: 300px;
            background: white;
            padding: 20px;
            box-shadow: 2px 0 5px rgba(0,0,0,0.1);
            overflow-y: auto;
        }
        #graph {
            flex: 1;
            background: white;
        }
        .node { cursor: pointer; }
        .node.noun { fill: #4A90E2; }
        .node.adjective { fill: #E27A4A; }
        .node.selected { stroke: #333; stroke-width: 3px; }
        .link { stroke: #999; stroke-opacity: 0.6; }
        .link.linking { stroke-dasharray: 5,5; }
        .node-label { font-size: 11px; pointer-events: none; }
        h1 { font-size: 24px; margin: 0 0 20px 0; }
        h2 { font-size: 16px; margin: 20px 0 10px 0; }
        .info { font-size: 13px; color: #666; }
        .legend { margin-top: 20px; }
        .legend-item { display: flex; align-items: center; margin: 5px 0; }
        .legend-color { width: 20px; height: 20px; margin-right: 10px; border-radius: 3px; }
    </style>
</head>
<body>
    <div id="container">
        <div id="sidebar">
            <h1>Snowball Explorer</h1>
            <div class="legend">
                <h2>Legend</h2>
                <div class="legend-item">
                    <div class="legend-color" style="background: #4A90E2;"></div>
                    <span>Nouns</span>
                </div>
                <div class="legend-item">
                    <div class="legend-color" style="background: #E27A4A;"></div>
                    <span>Adjectives</span>
                </div>
            </div>
            <div id="stats"></div>
            <div id="selected"></div>
        </div>
        <div id="graph"></div>
    </div>
    
    <script>
        // Embedded data to avoid CORS issues when opening from file system
        const data = %s;
        
        const width = window.innerWidth - 300;
        const height = window.innerHeight;
        
        const svg = d3.select("#graph")
            .append("svg")
            .attr("width", width)
            .attr("height", height);
        
        const g = svg.append("g");
        
        // Zoom behavior
        svg.call(d3.zoom()
            .scaleExtent([0.1, 4])
            .on("zoom", (event) => g.attr("transform", event.transform)));
        
        // Statistics
        const nounCount = data.nodes.filter(n => n.type === 'noun').length;
        const adjCount = data.nodes.filter(n => n.type === 'adjective').length;
        d3.select("#stats").html(`
            <h2>Statistics</h2>
            <div class="info">Nodes: ${data.nodes.length}</div>
            <div class="info">Nouns: ${nounCount}</div>
            <div class="info">Adjectives: ${adjCount}</div>
            <div class="info">Edges: ${data.edges.length}</div>
        `);
        
        // Force simulation
        const simulation = d3.forceSimulation(data.nodes)
            .force("link", d3.forceLink(data.edges).id(d => d.id).distance(100))
            .force("charge", d3.forceManyBody().strength(-300))
            .force("center", d3.forceCenter(width / 2, height / 2))
            .force("collision", d3.forceCollide().radius(30));
        
        // Links
        const link = g.append("g")
            .selectAll("line")
            .data(data.edges)
            .join("line")
            .attr("class", d => `link ${d.type}`)
            .attr("stroke-width", d => Math.sqrt(d.weight));
        
        // Nodes
        const node = g.append("g")
            .selectAll("circle")
            .data(data.nodes)
            .join("circle")
            .attr("class", d => `node ${d.type}`)
            .attr("r", 8)
            .call(d3.drag()
                .on("start", dragstarted)
                .on("drag", dragged)
                .on("end", dragended))
            .on("click", (event, d) => {
                d3.selectAll(".node").classed("selected", false);
                d3.select(event.currentTarget).classed("selected", true);
                
                const connections = data.edges.filter(e => e.source.id === d.id || e.target.id === d.id);
                d3.select("#selected").html(`
                    <h2>Selected: ${d.id}</h2>
                    <div class="info">Type: ${d.type}</div>
                    <div class="info">Connections: ${connections.length}</div>
                    <div class="info" style="margin-top: 10px;">
                        ${connections.slice(0, 10).map(e => 
                            `${e.source.id} → ${e.target.id} (${e.weight.toFixed(2)})`
                        ).join('<br>')}
                    </div>
                `);
            });
        
        // Labels
        const labels = g.append("g")
            .selectAll("text")
            .data(data.nodes)
            .join("text")
            .attr("class", "node-label")
            .text(d => d.id);
        
        simulation.on("tick", () => {
            link
                .attr("x1", d => d.source.x)
                .attr("y1", d => d.source.y)
                .attr("x2", d => d.target.x)
                .attr("y2", d => d.target.y);
            
            node
                .attr("cx", d => d.x)
                .attr("cy", d => d.y);
            
            labels
                .attr("x", d => d.x + 12)
                .attr("y", d => d.y + 4);
        });
        
        function dragstarted(event) {
            if (!event.active) simulation.alphaTarget(0.3).restart();
            event.subject.fx = event.subject.x;
            event.subject.fy = event.subject.y;
        }
        
        function dragged(event) {
            event.subject.fx = event.x;
            event.subject.fy = event.y;
        }
        
        function dragended(event) {
            if (!event.active) simulation.alphaTarget(0);
            event.subject.fx = null;
            event.subject.fy = null;
        }
    </script>
</body>
</html>
""".formatted(jsonData);
    }
}
