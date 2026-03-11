package pl.marcinmilkowski.word_sketch.viz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RadialPlotTest {

    @Test
    void renderFromItems_returnsNonEmptyStringContainingSvgTag() {
        List<RadialPlot.Item> items = List.of(
                new RadialPlot.Item("good", 8.5),
                new RadialPlot.Item("bad", 3.2));
        String svg = RadialPlot.renderFromItems("theory", items, 800, 600, null);
        assertNotNull(svg);
        assertFalse(svg.isEmpty(), "SVG output must not be empty");
        assertTrue(svg.contains("<svg"), "Output must contain <svg element");
    }

    @Test
    void renderFromItems_containsCenterWordInOutput() {
        List<RadialPlot.Item> items = List.of(new RadialPlot.Item("relevant", 7.0));
        String svg = RadialPlot.renderFromItems("concept", items, 400, 400, null);
        assertTrue(svg.contains("concept"), "SVG must mention the center word");
    }

    @Test
    void renderFromItems_emptyItemsStillProducesSvg() {
        String svg = RadialPlot.renderFromItems("word", List.of(), 800, 600, null);
        assertTrue(svg.contains("<svg"), "Empty-item radial must still produce an SVG");
    }

    @Test
    void renderFromItems_signedModeProducesLegend() {
        List<RadialPlot.Item> items = List.of(
                new RadialPlot.Item("positive", 5.0),
                new RadialPlot.Item("negative", -3.0));
        String svg = RadialPlot.renderFromItems("word", items, 800, 600, "signed");
        assertTrue(svg.contains("<svg"), "Signed mode must produce SVG");
        assertTrue(svg.contains("legend"), "Signed mode must include a legend group");
    }

    @Test
    void renderFromItems_closingTagPresent() {
        List<RadialPlot.Item> items = List.of(new RadialPlot.Item("test", 5.0));
        String svg = RadialPlot.renderFromItems("word", items, 800, 600, null);
        assertTrue(svg.trim().endsWith("</svg>"), "SVG must end with </svg>");
    }
}

