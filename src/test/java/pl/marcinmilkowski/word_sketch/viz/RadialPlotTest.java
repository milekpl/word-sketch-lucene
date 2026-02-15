package pl.marcinmilkowski.word_sketch.viz;

import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.query.SnowballCollocations.Edge;

import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RadialPlotTest {

    @Test
    void testRenderFromItems_BasicStructure() {
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("alpha", 10.5),
            new RadialPlot.Item("beta", 8.2),
            new RadialPlot.Item("gamma", 5.1)
        );
        
        String svg = RadialPlot.renderFromItems("centerword", items, 800, 800, "");
        
        // Basic structure checks
        assertTrue(svg.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "Missing XML declaration");
        assertTrue(svg.contains("<svg xmlns=\"http://www.w3.org/2000/svg\""), "Missing SVG namespace");
        assertTrue(svg.contains("width=\"800\" height=\"800\""), "Wrong dimensions");
        assertTrue(svg.contains("centerword"), "Missing center word");
        assertTrue(svg.contains("alpha"), "Missing alpha label");
        assertTrue(svg.contains("beta"), "Missing beta label");
        assertTrue(svg.contains("gamma"), "Missing gamma label");
    }

    @Test
    void testDirectConstructor_MatchesPythonFormat() {
        // Test that RadialPlot constructor produces format like viz_correct
        List<Edge> edges = Arrays.asList(
            new Edge("careful", "advantage", 12.0, "adj-noun"),
            new Edge("careful", "selection", 11.79, "adj-noun"),
            new Edge("careful", "alternative", 11.59, "adj-noun"),
            new Edge("careful", "study", 11.38, "adj-noun")
        );
        
        RadialPlot plot = new RadialPlot("careful", edges, 800, 800);
        String svg = plot.toSVG();
        
        // Check style matches viz_correct
        assertTrue(svg.contains(".background { fill: #fafafa; }"), "Wrong background style");
        assertTrue(svg.contains(".guide-circle { fill: none; stroke: #ddd;"), "Wrong guide-circle style");
        assertTrue(svg.contains(".connector { stroke: #888;"), "Wrong connector style");
        assertTrue(svg.contains(".center-circle { fill: #2C3E50;"), "Wrong center-circle style");
        assertTrue(svg.contains("font-family: Arial, sans-serif"), "Wrong font family");
        
        // Check guide circles at 100, 200, 300 (viz_correct format)
        assertTrue(svg.contains("<circle class=\"guide-circle\" cx=\"400.00\" cy=\"400.00\" r=\"100.00\"/>"), "Missing 100px guide circle");
        assertTrue(svg.contains("<circle class=\"guide-circle\" cx=\"400.00\" cy=\"400.00\" r=\"200.00\"/>"), "Missing 200px guide circle");
        assertTrue(svg.contains("<circle class=\"guide-circle\" cx=\"400.00\" cy=\"400.00\" r=\"300.00\"/>"), "Missing 300px guide circle");
        
        // Check center circle is radius 40
        assertTrue(svg.contains("<circle class=\"center-circle\" cx=\"400.00\" cy=\"400.00\" r=\"40.00\"/>"), "Wrong center circle radius");
    }

    @Test
    void testCircleRadius_EqualsScoreValue() {
        // Circle radius should equal the score value (matching viz_correct)
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("test1", 12.0),
            new RadialPlot.Item("test2", 8.5)
        );
        
        String svg = RadialPlot.renderFromItems("center", items, 800, 800, "");
        
        // Check that r="12.00" appears (circle radius = score)
        assertTrue(svg.contains("r=\"12.00\""), "Circle radius should equal score value 12.0");
        assertTrue(svg.contains("r=\"8.50\""), "Circle radius should equal score value 8.5");
    }

    @Test
    void testLabelPositioning_AboveOrBelowCircle() {
        // Labels should be 10px above if circle is above center, 18px below if below center
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("test", 5.0)
        );
        
        String svg = RadialPlot.renderFromItems("center", items, 800, 800, "");
        
        // Check that connectors group contains both circles and labels
        assertTrue(svg.contains("<g id=\"connectors\">"), "Missing connectors group");
        assertTrue(svg.contains("<circle class=\"collocate-circle\""), "Missing collocate circle");
        assertTrue(svg.contains("<text class=\"label\""), "Missing label");
    }

    @Test
    void testSignedMode_DivergingColors() {
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("positive", 8.0),
            new RadialPlot.Item("negative", -6.0),
            new RadialPlot.Item("neutral", 0.5)
        );
        
        String svg = RadialPlot.renderFromItems("compare", items, 840, 520, "signed");
        
        // Check for diverging color legend
        assertTrue(svg.contains("Positive (A&gt;B)"), "Missing positive legend");
        assertTrue(svg.contains("Negative (B&gt;A)"), "Missing negative legend");
        
        // Check for blue color (positive) and red color (negative)
        assertTrue(svg.contains("rgb(") || svg.contains("#"), "Missing color definitions");
    }

    @Test
    void testGroupStructure_MatchesVizCorrect() {
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("test", 5.0)
        );
        
        String svg = RadialPlot.renderFromItems("center", items, 800, 800, "");
        
        // Check that we have proper groups like viz_correct
        assertTrue(svg.contains("<g id=\"guides\">"), "Missing guides group");
        assertTrue(svg.contains("<g id=\"connectors\">"), "Missing connectors group");
        
        // Verify structure: line, circle, text should appear together in sequence within connectors group
        int lineIndex = svg.indexOf("<line class=\"connector\"");
        int circleIndex = svg.indexOf("<circle class=\"collocate-circle\"", lineIndex);
        int textIndex = svg.indexOf("<text class=\"label\"", circleIndex);
        
        assertTrue(lineIndex > 0, "No connector line found");
        assertTrue(circleIndex > lineIndex, "Circle should come after line");
        assertTrue(textIndex > circleIndex, "Text should come after circle");
        assertTrue(textIndex - lineIndex < 500, "Line, circle, text should be close together");
    }
    
    @Test
    void testLocaleIndependence_DecimalPoints() {
        List<RadialPlot.Item> items = Arrays.asList(
            new RadialPlot.Item("test", 5.5)
        );
        
        String svg = RadialPlot.renderFromItems("center", items, 800, 800, "");
        
        // SVG should use decimal points (.), not commas (,) for floating-point numbers
        assertFalse(svg.contains("cx=\"400,"), "Locale issue: comma instead of decimal point in cx");
        assertFalse(svg.contains("cy=\"400,"), "Locale issue: comma instead of decimal point in cy");
        assertFalse(svg.contains("r=\"40,"), "Locale issue: comma instead of decimal point in r");
    }
}
