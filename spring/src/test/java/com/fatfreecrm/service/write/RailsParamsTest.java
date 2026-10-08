package com.fatfreecrm.service.write;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link RailsParams} to the ActiveRecord attribute casts Rails applies on mass assignment:
 * int4 ("abc" → nil, "3" → 3, 3.7 → 3), boolean (true/false/"true"/"false"/"1"/"0"), string
 * (value text) and datetime (blank/garbage → nil), plus the partial-update rule that only keys
 * present in the request object are assigned.
 */
class RailsParamsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode node(String literal) {
        try {
            return JSON.readTree(literal);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void asIntegerMirrorsRailsInt4Cast() {
        assertThat(RailsParams.asInteger(node("3"))).isEqualTo(3);
        assertThat(RailsParams.asInteger(node("\"3\""))).isEqualTo(3);
        assertThat(RailsParams.asInteger(node("\" 7 \""))).isEqualTo(7);
        assertThat(RailsParams.asInteger(node("3.7"))).isEqualTo(3);
        assertThat(RailsParams.asInteger(node("\"abc\""))).isNull();
        assertThat(RailsParams.asInteger(node("\"\""))).isNull();
        assertThat(RailsParams.asInteger(node("true"))).isNull();
        assertThat(RailsParams.asInteger(node("{\"id\":1}"))).isNull();
        assertThat(RailsParams.asInteger(NullNode.getInstance())).isNull();
        assertThat(RailsParams.asInteger(null)).isNull();
    }

    @Test
    void asBooleanAcceptsRailsTruthyAndFalsyTokens() {
        assertThat(RailsParams.asBoolean(node("true"))).isTrue();
        assertThat(RailsParams.asBoolean(node("false"))).isFalse();
        assertThat(RailsParams.asBoolean(node("\"true\""))).isTrue();
        assertThat(RailsParams.asBoolean(node("\"TRUE\""))).isTrue();
        assertThat(RailsParams.asBoolean(node("\"false\""))).isFalse();
        assertThat(RailsParams.asBoolean(node("\"1\""))).isTrue();
        assertThat(RailsParams.asBoolean(node("\"0\""))).isFalse();
        assertThat(RailsParams.asBoolean(node("1"))).isTrue();
        assertThat(RailsParams.asBoolean(node("0"))).isFalse();
        assertThat(RailsParams.asBoolean(node("2"))).isTrue();
        assertThat(RailsParams.asBoolean(node("\"maybe\""))).isNull();
        assertThat(RailsParams.asBoolean(node("\"\""))).isNull();
        assertThat(RailsParams.asBoolean(NullNode.getInstance())).isNull();
        assertThat(RailsParams.asBoolean(null)).isNull();
    }

    @Test
    void asStringTakesTextOrJsonLiteral() {
        assertThat(RailsParams.asString(node("\"Call Alice\""))).isEqualTo("Call Alice");
        assertThat(RailsParams.asString(node("\"\""))).isEmpty();
        assertThat(RailsParams.asString(node("5"))).isEqualTo("5");
        assertThat(RailsParams.asString(node("true"))).isEqualTo("true");
        assertThat(RailsParams.asString(NullNode.getInstance())).isNull();
        assertThat(RailsParams.asString(null)).isNull();
    }

    @Test
    void asInstantParsesIsoAndRailsStyleDatetimesAndNilsTheRest() {
        assertThat(RailsParams.asInstant(node("\"2030-05-04T10:00:00Z\"")))
            .isEqualTo(Instant.parse("2030-05-04T10:00:00Z"));
        assertThat(RailsParams.asInstant(node("\"2030-05-04 10:00:00\"")))
            .isEqualTo(Instant.parse("2030-05-04T10:00:00Z"));
        assertThat(RailsParams.asInstant(node("\"2030-05-04T10:00\"")))
            .isEqualTo(Instant.parse("2030-05-04T10:00:00Z"));
        assertThat(RailsParams.asInstant(node("\"\""))).isNull();
        assertThat(RailsParams.asInstant(node("\"   \""))).isNull();
        assertThat(RailsParams.asInstant(node("\"not a date\""))).isNull();
        assertThat(RailsParams.asInstant(NullNode.getInstance())).isNull();
        assertThat(RailsParams.asInstant(null)).isNull();
    }

    @Test
    void assignOnlyTouchesProvidedKeysAndExplicitNullAssignsNil() {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("name", node("\"Call Alice\""));
        values.put("priority", NullNode.getInstance());
        values.put("asset_id", node("\"12\""));
        values.put("private", node("\"1\""));
        values.put("due_at", node("\"2030-05-04 10:00:00\""));
        RailsParams params = RailsParams.of(values);

        List<String> assigned = new ArrayList<>();
        AtomicReference<String> name = new AtomicReference<>("untouched");
        AtomicReference<String> priority = new AtomicReference<>("untouched");
        AtomicReference<String> category = new AtomicReference<>("untouched");
        AtomicReference<Integer> assetId = new AtomicReference<>();
        AtomicReference<Boolean> privateFlag = new AtomicReference<>();
        AtomicReference<Instant> dueAt = new AtomicReference<>();

        params.assignString("name", value -> {
            assigned.add("name");
            name.set(value);
        });
        params.assignString("priority", value -> {
            assigned.add("priority");
            priority.set(value);
        });
        params.assignString("category", value -> {
            assigned.add("category");
            category.set(value);
        });
        params.assignInteger("asset_id", assetId::set);
        params.assignBoolean("private", privateFlag::set);
        params.assignInstant("due_at", dueAt::set);

        assertThat(assigned).containsExactly("name", "priority");
        assertThat(name.get()).isEqualTo("Call Alice");
        assertThat(priority.get()).isNull();
        assertThat(category.get()).isEqualTo("untouched");
        assertThat(assetId.get()).isEqualTo(12);
        assertThat(privateFlag.get()).isTrue();
        assertThat(dueAt.get()).isEqualTo(Instant.parse("2030-05-04T10:00:00Z"));
    }

    @Test
    void providedDistinguishesExplicitNullFromAbsentKey() {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("bucket", NullNode.getInstance());
        RailsParams params = RailsParams.of(values);

        assertThat(params.provided("bucket")).isTrue();
        assertThat(params.get("bucket")).contains(NullNode.getInstance());
        assertThat(params.provided("name")).isFalse();
        assertThat(params.get("name")).isEmpty();
    }

    @Test
    void keysKeepRequestOrderWhichDrivesPaperTrailObjectOrdering() {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("bucket", node("\"due_today\""));
        values.put("name", node("\"x\""));
        values.put("assigned_to", node("2"));

        assertThat(RailsParams.of(values).keys()).containsExactly("bucket", "name", "assigned_to");
    }

    @Test
    void missingRequestObjectBehavesAsEmptyParams() {
        RailsParams params = RailsParams.of(null);

        assertThat(params.keys()).isEmpty();
        assertThat(params.provided("name")).isFalse();
        AtomicReference<String> name = new AtomicReference<>("untouched");
        params.assignString("name", name::set);
        assertThat(name.get()).isEqualTo("untouched");
    }
}
