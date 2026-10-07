package com.fatfreecrm.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code parse_query_and_tags} and acts-as-taggable-on {@code DefaultParser} semantics. */
class SearchTextTest {

    @Test
    void blankQueryParsesToNothing() {
        SearchText parsed = SearchText.parse("   ", true);
        assertThat(parsed.text()).isNull();
        assertThat(parsed.tags()).isEmpty();
        assertThat(parsed.tagFilterRequested()).isFalse();
    }

    @Test
    void wholeStringHashPairIsOneTag() {
        SearchText parsed = SearchText.parse("#vip#", true);
        assertThat(parsed.text()).isNull();
        assertThat(parsed.tags()).containsExactly("vip");
        assertThat(parsed.tagFilterRequested()).isTrue();
    }

    @Test
    void tokensSplitIntoQueryWordsAndTags() {
        SearchText parsed = SearchText.parse("acme #east beta", true);
        assertThat(parsed.text()).isEqualTo("acme beta");
        assertThat(parsed.tags()).containsExactly("east");
    }

    @Test
    void blankTagStringsApplyNoTagFilter() {
        // "#"[1..-2] and "##"[1..-2] are ""; a blank tag string means tagged_with is not called.
        for (String raw : java.util.List.of("#", "##", "# #")) {
            SearchText parsed = SearchText.parse(raw, true);
            assertThat(parsed.tags()).as(raw).isEmpty();
            assertThat(parsed.tagFilterRequested()).as(raw).isFalse();
            assertThat(parsed.text()).as(raw).isNull();
        }
        SearchText trailing = SearchText.parse("acme #", true);
        assertThat(trailing.tags()).isEmpty();
        assertThat(trailing.tagFilterRequested()).isFalse();
        assertThat(trailing.text()).isEqualTo("acme");
    }

    @Test
    void nonBlankTagStringWithZeroTagsYieldsNone() {
        // "#,#" -> tag string "," which the DefaultParser parses to zero tags -> tagged_with none.
        SearchText parsed = SearchText.parse("#,#", true);
        assertThat(parsed.tags()).isEmpty();
        assertThat(parsed.tagFilterRequested()).isTrue();
    }

    @Test
    void tagListParserHonoursCommasQuotesAndUniq() {
        assertThat(SearchText.parseTagList("vip, west")).containsExactly("vip", "west");
        assertThat(SearchText.parseTagList("\"a, b\", c")).containsExactly("a, b", "c");
        assertThat(SearchText.parseTagList("'single', d")).containsExactly("single", "d");
        assertThat(SearchText.parseTagList("vip,, vip")).containsExactly("vip");
        assertThat(SearchText.parseTagList(" ")).isEmpty();
    }

    @Test
    void tagTokensOnNonTaggableEntityAreIgnored() {
        SearchText parsed = SearchText.parse("#vip", false);
        assertThat(parsed.tags()).isEmpty();
        assertThat(parsed.tagFilterRequested()).isFalse();
    }
}
