package com.fatfreecrm.service.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code ActionController::Parameters#require}: missing or empty root → {@code ParameterMissing}. */
class RailsParamsRequireTest {

    @Test
    void missingRootRaisesParameterMissingWithRailsMessage() {
        assertThatThrownBy(() -> RailsParams.require(null, "group"))
            .isInstanceOf(RailsParameterMissing.class)
            .hasMessage("param is missing or the value is empty or invalid: group");
        assertThatThrownBy(() -> RailsParams.require(Map.of(), "tag"))
            .isInstanceOf(RailsParameterMissing.class)
            .hasMessage("param is missing or the value is empty or invalid: tag");
    }

    @Test
    void presentRootIsReturnedAsParamsEvenWhenValuesAreNull() {
        RailsParams params = RailsParams.require(Map.of("name", NullNode.getInstance()), "group");
        assertThat(params.provided("name")).isTrue();
        assertThat(params.keys()).containsExactly("name");

        RailsParams named = RailsParams.require(Map.<String, JsonNode>of("name", TextNode.valueOf("Ops")), "group");
        assertThat(named.get("name")).map(JsonNode::asText).contains("Ops");
    }
}
