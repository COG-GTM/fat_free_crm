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
    void hashesOnlyParsesToEmptyTagListZeroResults() {
        SearchText parsed = SearchText.parse("# #", true);
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
