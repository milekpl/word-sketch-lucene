package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.marcinmilkowski.word_sketch.api.model.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExportUtils}.
 *
 * <p>Verifies that CSV and XML serialisation methods produce well-formed output containing
 * the expected data, and that helper utilities (escaping, format parsing, limit detection)
 * behave correctly for edge-case inputs.</p>
 */
@DisplayName("ExportUtils")
class ExportUtilsTest {

    // ── csvEscape ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("csvEscape — plain value returned unchanged")
    void csvEscape_plain_unchanged() {
        assertEquals("hello", ExportUtils.csvEscape("hello"));
    }

    @Test
    @DisplayName("csvEscape — null returns empty string")
    void csvEscape_null_returnsEmpty() {
        assertEquals("", ExportUtils.csvEscape(null));
    }

    @Test
    @DisplayName("csvEscape — value with comma wrapped in quotes")
    void csvEscape_valueWithComma_wrappedInQuotes() {
        assertEquals("\"a,b\"", ExportUtils.csvEscape("a,b"));
    }

    @Test
    @DisplayName("csvEscape — value with double-quote doubled and wrapped")
    void csvEscape_valueWithDoubleQuote_doubledAndWrapped() {
        // RFC 4180: wrap in double-quotes; internal double-quotes are doubled
        // Input: say "hi"  →  Output: "say ""hi"""
        assertEquals("\"say \"\"hi\"\"\"", ExportUtils.csvEscape("say \"hi\""));
    }

    @Test
    @DisplayName("csvEscape — value with newline wrapped in quotes")
    void csvEscape_valueWithNewline_wrappedInQuotes() {
        String result = ExportUtils.csvEscape("line1\nline2");
        assertTrue(result.startsWith("\"") && result.endsWith("\""));
    }

    // ── xmlAttr ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("xmlAttr — null returns empty string")
    void xmlAttr_null_returnsEmpty() {
        assertEquals("", ExportUtils.xmlAttr(null));
    }

    @Test
    @DisplayName("xmlAttr — ampersand escaped")
    void xmlAttr_ampersand_escaped() {
        assertEquals("a&amp;b", ExportUtils.xmlAttr("a&b"));
    }

    @Test
    @DisplayName("xmlAttr — double quote escaped")
    void xmlAttr_doubleQuote_escaped() {
        assertEquals("a&quot;b", ExportUtils.xmlAttr("a\"b"));
    }

    @Test
    @DisplayName("xmlAttr — angle brackets escaped")
    void xmlAttr_angleBrackets_escaped() {
        assertEquals("a&lt;b&gt;c", ExportUtils.xmlAttr("a<b>c"));
    }

    // ── parseFormat ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseFormat — absent parameter returns json")
    void parseFormat_absent_returnsJson() {
        assertEquals("json", ExportUtils.parseFormat(Map.of()));
    }

    @Test
    @DisplayName("parseFormat — 'csv' returns csv")
    void parseFormat_csv_returnsCsv() {
        assertEquals("csv", ExportUtils.parseFormat(Map.of("format", "csv")));
    }

    @Test
    @DisplayName("parseFormat — 'xml' returns xml")
    void parseFormat_xml_returnsXml() {
        assertEquals("xml", ExportUtils.parseFormat(Map.of("format", "xml")));
    }

    @Test
    @DisplayName("parseFormat — unknown value throws IllegalArgumentException")
    void parseFormat_unknown_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportUtils.parseFormat(Map.of("format", "pdf")));
    }

    // ── parseExportLimit ─────────────────────────────────────────────────────

    @Test
    @DisplayName("parseExportLimit — absent returns 0")
    void parseExportLimit_absent_returnsZero() {
        assertEquals(0, ExportUtils.parseExportLimit(Map.of()));
    }

    @Test
    @DisplayName("parseExportLimit — '50' returns 50")
    void parseExportLimit_fifty_returns50() {
        assertEquals(50, ExportUtils.parseExportLimit(Map.of("export_limit", "50")));
    }

    @Test
    @DisplayName("parseExportLimit — negative value throws")
    void parseExportLimit_negative_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportUtils.parseExportLimit(Map.of("export_limit", "-1")));
    }

    @Test
    @DisplayName("parseExportLimit — non-integer value throws")
    void parseExportLimit_nonInteger_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ExportUtils.parseExportLimit(Map.of("export_limit", "abc")));
    }

    // ── isLimitExceeded ───────────────────────────────────────────────────────

    @Test
    @DisplayName("isLimitExceeded — zero limit never exceeded")
    void isLimitExceeded_zeroLimit_neverExceeded() {
        assertFalse(ExportUtils.isLimitExceeded(0, 1000));
    }

    @Test
    @DisplayName("isLimitExceeded — negative limit never exceeded")
    void isLimitExceeded_negativeLimit_neverExceeded() {
        assertFalse(ExportUtils.isLimitExceeded(-1, 1000));
    }

    @Test
    @DisplayName("isLimitExceeded — count equals limit is exceeded")
    void isLimitExceeded_countEqualsLimit_exceeded() {
        assertTrue(ExportUtils.isLimitExceeded(5, 5));
    }

    @Test
    @DisplayName("isLimitExceeded — count below limit is not exceeded")
    void isLimitExceeded_countBelowLimit_notExceeded() {
        assertFalse(ExportUtils.isLimitExceeded(5, 4));
    }

    // ── downloadFilename ─────────────────────────────────────────────────────

    @Test
    @DisplayName("downloadFilename — context with safe chars")
    void downloadFilename_safeContext() {
        assertEquals("theory-sketch.csv", ExportUtils.downloadFilename("theory-sketch", "csv"));
    }

    @Test
    @DisplayName("downloadFilename — context with unsafe chars sanitised")
    void downloadFilename_unsafeChars_sanitised() {
        String name = ExportUtils.downloadFilename("théorie sketch", "xml");
        assertFalse(name.contains(" "), "Spaces must be replaced");
        assertTrue(name.endsWith(".xml"));
    }

    // ── sketchToCsv ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("sketchToCsv — contains header and one collocate row")
    void sketchToCsv_containsHeaderAndRow() {
        SketchResponse response = buildSketchResponse();
        String csv = ExportUtils.sketchToCsv(response, 0);
        assertTrue(csv.contains("relation,lemma,collocate,pos,frequency,log_dice"),
                "CSV must contain the header row");
        assertTrue(csv.contains("adj_predicate"), "CSV must contain the relation id");
        assertTrue(csv.contains("empirical"), "CSV must contain the collocate lemma");
        assertTrue(csv.contains("8.3"), "CSV must contain the logDice score");
    }

    @Test
    @DisplayName("sketchToCsv — limit=1 emits only one collocate")
    void sketchToCsv_limitOne_emitsOneCollocate() {
        SketchResponse response = buildSketchResponseTwoCollocates();
        String csv = ExportUtils.sketchToCsv(response, 1);
        long rows = csv.lines().filter(l -> !l.startsWith("relation,") && !l.isBlank()).count();
        assertEquals(1, rows, "Only 1 collocate row should be present when limit=1");
    }

    @Test
    @DisplayName("sketchToCsv — null relations returns header only")
    void sketchToCsv_nullRelations_headerOnly() {
        SketchResponse response = new SketchResponse("ok", "theory", null, null, null);
        String csv = ExportUtils.sketchToCsv(response, 0);
        assertEquals("relation,lemma,collocate,pos,frequency,log_dice\n", csv);
    }

    // ── sketchToXml ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("sketchToXml — valid XML header and root element")
    void sketchToXml_validXmlHeaderAndRoot() {
        SketchResponse response = buildSketchResponse();
        String xml = ExportUtils.sketchToXml(response, 0);
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"),
                "XML must start with the declaration");
        assertTrue(xml.contains("<sketch lemma=\"theory\">"), "Root element must carry lemma attribute");
        assertTrue(xml.contains("</sketch>"), "Root element must be closed");
    }

    @Test
    @DisplayName("sketchToXml — contains relation and collocate elements")
    void sketchToXml_containsRelationAndCollocate() {
        SketchResponse response = buildSketchResponse();
        String xml = ExportUtils.sketchToXml(response, 0);
        assertTrue(xml.contains("<relation id=\"adj_predicate\""), "XML must contain relation element");
        assertTrue(xml.contains("lemma=\"empirical\""), "XML must contain collocate element");
    }

    @Test
    @DisplayName("sketchToXml — XML attribute values are escaped")
    void sketchToXml_attrValuesEscaped() {
        CollocateEntry c = new CollocateEntry("a&b", 10, 5.0, "JJ");
        RelationEntry rel = new RelationEntry("r1", "Test", "[lemma=\"test\"]", null, "SURFACE", "noun", "adj", 100, List.of(c), null);
        SketchResponse response = new SketchResponse("ok", "test<word>", null, Map.of("r1", rel), null);
        String xml = ExportUtils.sketchToXml(response, 0);
        assertTrue(xml.contains("&amp;"), "Ampersand in lemma must be escaped");
        assertTrue(xml.contains("&lt;"), "< in lemma must be escaped");
    }

    // ── examplesToCsv ────────────────────────────────────────────────────────

    @Test
    @DisplayName("examplesToCsv — contains header and example row")
    void examplesToCsv_containsHeaderAndRow() {
        ExamplesResponse response = buildExamplesResponse();
        String csv = ExportUtils.examplesToCsv(response, 0);
        assertTrue(csv.contains("seed,collocate,relation,sentence"), "CSV must contain header");
        assertTrue(csv.contains("theory"), "CSV must contain seed");
        assertTrue(csv.contains("important"), "CSV must contain collocate");
        assertTrue(csv.contains("Theories are important"), "CSV must contain example sentence");
    }

    @Test
    @DisplayName("examplesToCsv — sentence containing comma is quoted")
    void examplesToCsv_sentenceWithComma_quoted() {
        ExamplesResponse response = buildExamplesResponseWithCommaSentence();
        String csv = ExportUtils.examplesToCsv(response, 0);
        assertTrue(csv.contains("\"") , "Sentence containing comma must be CSV-quoted");
    }

    // ── examplesToXml ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("examplesToXml — valid XML header and root element")
    void examplesToXml_validXml() {
        ExamplesResponse response = buildExamplesResponse();
        String xml = ExportUtils.examplesToXml(response, 0);
        assertTrue(xml.startsWith("<?xml"), "XML must start with declaration");
        assertTrue(xml.contains("<concordance"), "Root element must be concordance");
        assertTrue(xml.contains("seed=\"theory\""), "Root must carry seed attribute");
        assertTrue(xml.contains("<![CDATA[Theories are important]]>"), "Sentences must be in CDATA");
    }

    // ── exploreToCsv ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("exploreToCsv — contains header and seed_collocate row")
    void exploreToCsv_containsHeaderAndRow() {
        ExploreResponse response = buildExploreResponse();
        String csv = ExportUtils.exploreToCsv(response, 0);
        assertTrue(csv.contains("type,word,log_dice,frequency"), "CSV must contain header");
        assertTrue(csv.contains("seed_collocate"), "CSV must contain seed_collocate type");
        assertTrue(csv.contains("empirical"), "CSV must contain collocate word");
    }

    @Test
    @DisplayName("exploreToCsv — limit restricts rows")
    void exploreToCsv_limitRestrictsRows() {
        ExploreResponse response = buildExploreResponseTwoCollocates();
        String csv = ExportUtils.exploreToCsv(response, 1);
        long dataRows = csv.lines().filter(l -> l.startsWith("seed_collocate,") || l.startsWith("core_collocate,")).count();
        assertEquals(1, dataRows, "Only 1 row should be present when limit=1");
    }

    // ── exploreToXml ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("exploreToXml — valid XML with seed_collocates section")
    void exploreToXml_validXmlWithSections() {
        ExploreResponse response = buildExploreResponse();
        String xml = ExportUtils.exploreToXml(response, 0);
        assertTrue(xml.startsWith("<?xml"), "XML must start with declaration");
        assertTrue(xml.contains("<exploration"), "Root must be exploration");
        assertTrue(xml.contains("<seed_collocates>"), "Must contain seed_collocates section");
        assertTrue(xml.contains("<core_collocates>"), "Must contain core_collocates section");
    }

    @Test
    @DisplayName("exploreToXml — multi-seed response uses joined seeds as seed attribute")
    void exploreToXml_multiSeed_joinedSeedAttr() {
        ExploreResponse response = buildMultiSeedExploreResponse();
        String xml = ExportUtils.exploreToXml(response, 0);
        assertTrue(xml.contains("theory,model"), "Multi-seed exploration must join seeds in attribute");
    }

    // ── comparisonToCsv ──────────────────────────────────────────────────────

    @Test
    @DisplayName("comparisonToCsv — contains header and collocate row")
    void comparisonToCsv_containsHeaderAndRow() {
        ComparisonResponse response = buildComparisonResponse();
        String csv = ExportUtils.comparisonToCsv(response, 0);
        assertTrue(csv.contains("word,category,present_in,total_nouns,avg_logdice,coverage"),
                "CSV must contain header");
        assertTrue(csv.contains("empirical"), "CSV must contain collocate word");
        assertTrue(csv.contains("FULLY_SHARED"), "CSV must contain category");
    }

    // ── comparisonToXml ───────────────────────────────────────────────────────

    @Test
    @DisplayName("comparisonToXml — valid XML with seeds attribute and collocate elements")
    void comparisonToXml_validXml() {
        ComparisonResponse response = buildComparisonResponse();
        String xml = ExportUtils.comparisonToXml(response, 0);
        assertTrue(xml.startsWith("<?xml"), "XML must start with declaration");
        assertTrue(xml.contains("<comparison"), "Root must be comparison");
        assertTrue(xml.contains("seeds=\"theory,model\""), "Seeds must be in attribute");
        assertTrue(xml.contains("word=\"empirical\""), "Collocate must appear as element attribute");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SketchResponse buildSketchResponse() {
        CollocateEntry c = new CollocateEntry("empirical", 500L, 8.3, "JJ");
        RelationEntry rel = new RelationEntry("adj_predicate", "Adj predicate", "[lemma=\"theory\"]",
                null, "SURFACE", "noun", "adj", 500, List.of(c), null);
        return new SketchResponse("ok", "theory", null, Map.of("adj_predicate", rel), null);
    }

    private SketchResponse buildSketchResponseTwoCollocates() {
        CollocateEntry c1 = new CollocateEntry("empirical", 500L, 8.3, "JJ");
        CollocateEntry c2 = new CollocateEntry("scientific", 400L, 7.9, "JJ");
        RelationEntry rel = new RelationEntry("adj_predicate", "Adj predicate", "[lemma=\"theory\"]",
                null, "SURFACE", "noun", "adj", 900, List.of(c1, c2), null);
        return new SketchResponse("ok", "theory", null, Map.of("adj_predicate", rel), null);
    }

    private ExamplesResponse buildExamplesResponse() {
        return new ExamplesResponse("ok", "theory", "important", "adj_predicate",
                "\"theory\" \"important\"", 10, 1, false,
                List.of(new ExampleEntry("Theories are important", "Theories are important")));
    }

    private ExamplesResponse buildExamplesResponseWithCommaSentence() {
        return new ExamplesResponse("ok", "theory", "important", "adj_predicate",
                "\"theory\" \"important\"", 10, 1, false,
                List.of(new ExampleEntry("Theories, models, and hypotheses are important", "Theories, models")));
    }

    private ExploreResponse buildExploreResponse() {
        SeedCollocateEntry sc = new SeedCollocateEntry("empirical", 8.3, 500L);
        CoreCollocateEntry cc = new CoreCollocateEntry("valid", 2, 3, 0.66, 7.5);
        return new ExploreResponse.SingleSeed(
                "ok", "theory",
                new ExploreResponse.Parameters("adj_predicate", 10, 2, 3.0, 30),
                List.of(sc), List.of(), List.of(cc), List.of());
    }

    private ExploreResponse buildExploreResponseTwoCollocates() {
        SeedCollocateEntry sc1 = new SeedCollocateEntry("empirical", 8.3, 500L);
        SeedCollocateEntry sc2 = new SeedCollocateEntry("scientific", 7.9, 400L);
        return new ExploreResponse.SingleSeed(
                "ok", "theory",
                new ExploreResponse.Parameters("adj_predicate", 10, 2, 3.0, 30),
                List.of(sc1, sc2), List.of(), List.of(), List.of());
    }

    private ExploreResponse buildMultiSeedExploreResponse() {
        SeedCollocateEntry sc = new SeedCollocateEntry("empirical", 8.3, 500L);
        return new ExploreResponse.MultiSeed(
                "ok", List.of("theory", "model"),
                new ExploreResponse.Parameters("adj_predicate", 10, 2, 3.0, null),
                List.of(sc), List.of(), List.of(), List.of());
    }

    private ComparisonResponse buildComparisonResponse() {
        CollocateProfileEntry e = new CollocateProfileEntry(
                "empirical", 2, 2, 8.0, 8.3, 0.0, 0.9, 0.1, "FULLY_SHARED", Map.of(), null);
        return new ComparisonResponse("ok", List.of("theory", "model"), 2,
                new ComparisonResponse.Parameters("cross_relational", 50, 2, 3.0),
                List.of(e), 1, 1, 0, 0, List.of(), 0);
    }
}
