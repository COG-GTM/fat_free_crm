package com.fatfreecrm.domain.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.nodes.Tag;

public final class RailsYaml {

    private RailsYaml() {
    }

    public static Object read(String yaml) {
        if (yaml == null) {
            return null;
        }
        return new Yaml(new RailsSafeConstructor()).load(yaml);
    }

    @SuppressWarnings("unchecked")
    public static List<String> readStringList(String yaml) {
        Object parsed = read(yaml);
        if (parsed == null) {
            return new ArrayList<>();
        }
        if (!(parsed instanceof List<?> values)) {
            throw new IllegalArgumentException("Expected a YAML sequence");
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String item)) {
                throw new IllegalArgumentException("Expected string sequence elements");
            }
            result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readStringMap(String yaml) {
        Object parsed = read(yaml);
        if (parsed == null) {
            return new LinkedHashMap<>();
        }
        if (!(parsed instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Expected a YAML mapping");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("Expected string mapping keys");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static final class RailsSafeConstructor extends SafeConstructor {

        RailsSafeConstructor() {
            super(new LoaderOptions());
            yamlMultiConstructors.put("!ruby/", new Construct() {
                @Override
                public Object construct(Node node) {
                    if (node instanceof MappingNode mapping) {
                        return constructMapping(mapping);
                    }
                    if (node instanceof SequenceNode sequence) {
                        return constructSequence(sequence);
                    }
                    if (node instanceof ScalarNode scalar) {
                        return constructScalar(scalar);
                    }
                    throw new IllegalArgumentException("Unsupported Rails YAML node: " + node);
                }

                @Override
                public void construct2ndStep(Node node, Object object) {
                    if (node.isTwoStepsConstruction()) {
                        throw new IllegalArgumentException("Recursive Rails YAML values are unsupported");
                    }
                }
            });
            yamlConstructors.put(new Tag("!ruby/object"), new Construct() {
                @Override
                public Object construct(Node node) {
                    if (node instanceof MappingNode mapping) {
                        return constructMapping(mapping);
                    }
                    if (node instanceof SequenceNode sequence) {
                        return constructSequence(sequence);
                    }
                    return constructScalar((ScalarNode) node);
                }

                @Override
                public void construct2ndStep(Node node, Object object) {
                    if (node.isTwoStepsConstruction()) {
                        throw new IllegalArgumentException("Recursive Rails YAML values are unsupported");
                    }
                }
            });
        }
    }
}
