package pl.marcinmilkowski.word_sketch.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HttpApiUtils.parseQueryParams")
class HttpApiUtilsTest {

    @Test
    @DisplayName("null query string returns empty map")
    void parseQueryParams_null_returnsEmpty() {
        Map<String, String> result = HttpApiUtils.parseQueryParams(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("empty query string returns empty map")
    void parseQueryParams_empty_returnsEmpty() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("single key=value pair is parsed correctly")
    void parseQueryParams_singlePair() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("seed=house");
        assertEquals(1, result.size());
        assertEquals("house", result.get("seed"));
    }

    @Test
    @DisplayName("multiple key=value pairs are all parsed")
    void parseQueryParams_multiplePairs() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("a=1&b=2&c=3");
        assertEquals(3, result.size());
        assertEquals("1", result.get("a"));
        assertEquals("2", result.get("b"));
        assertEquals("3", result.get("c"));
    }

    @Test
    @DisplayName("URL-encoded values are decoded")
    void parseQueryParams_urlEncodedValues() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("seeds=theory%2Cmodel");
        assertEquals("theory,model", result.get("seeds"));
    }

    @Test
    @DisplayName("URL-encoded space (%20) is decoded to a space")
    void parseQueryParams_encodedSpace() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("label=hello%20world");
        assertEquals("hello world", result.get("label"));
    }

    @Test
    @DisplayName("missing value after '=' produces empty string")
    void parseQueryParams_emptyValue() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("key=");
        assertEquals(1, result.size());
        assertEquals("", result.get("key"));
    }

    @Test
    @DisplayName("pairs without '=' are silently ignored")
    void parseQueryParams_pairWithoutEquals_ignored() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("noequals");
        assertTrue(result.isEmpty(), "Key-only tokens with no '=' should be ignored");
    }

    @Test
    @DisplayName("value containing '=' is kept intact (split on first '=' only)")
    void parseQueryParams_valueContainsEquals() {
        Map<String, String> result = HttpApiUtils.parseQueryParams("expr=a%3Db");
        assertEquals("a=b", result.get("expr"));
    }
}
