# frozen_string_literal: true

# Generates check_boxes_yaml_fixtures.json: Rails check_boxes columns store
# serialize(..., type: Array) => Psych YAML. The dual-read spike needs fixtures
# produced by real Psych, not a hand-rolled emitter.
# Run: ruby generate_yaml_fixtures.rb > check_boxes_yaml_fixtures.json

require 'yaml'
require 'json'

ARRAYS = [
  [],
  ['A'],
  ['Email', 'Events', 'Product updates'],
  ['yes', 'no', 'true', '1', '1.5', 'null', '~'],
  ['a: b', '#hash', '- dash', "O'Brien", '"quoted"', 'back\\slash'],
  ['', ' leading', 'trailing '],
  ["multi\nline"],
  ['ünïcödé', '日本'],
  ['[x]', '{y}', '*star', '&amp', '!bang', '%pct', '@at', '`tick']
].freeze

fixtures = ARRAYS.map { |values| { 'values' => values, 'yaml' => YAML.dump(values) } }
puts JSON.pretty_generate(fixtures)
