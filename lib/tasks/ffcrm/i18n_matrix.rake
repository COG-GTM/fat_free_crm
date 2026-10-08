# frozen_string_literal: true

require "json"
require "yaml"

# AB-273 (settings-i18n): records real Rails I18n behavior (fallback chains, plural selection,
# interpolation, missing keys) as a parity matrix replayed by Spring's RailsI18nParityTest.
I18N_MATRIX_PLURAL_KEYS = %w[zero one two few many other].freeze
I18N_MATRIX_COUNTS = [0, 1, 2, 3, 4, 5, 11, 12, 21, 22, 25, 101, 111, 1234].freeze
I18N_MATRIX_FALLBACK_TAGS = %w[en de-AT pt xx].freeze
I18N_MATRIX_PLAIN_SAMPLE = 40

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails I18n translations, plural cases and fallback chains as a parity matrix for Spring"
    task i18n_matrix: :environment do
      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/i18n/rails_i18n_matrix.json"))
      locales_dir = Rails.root.join("config/locales")

      trees = Dir.glob(locales_dir.join("fat_free_crm.*.yml").to_s).to_h do |path|
        tree = YAML.load_file(path)
        [tree.keys.first, tree.values.first]
      end
      app_locales = trees.keys
      leaf_map = trees.transform_values { |tree| i18n_matrix_leaves(tree) }
      en_us_keys = leaf_map.fetch("en-US").keys
      union_keys = leaf_map.values.flat_map(&:keys).uniq.sort
      plain_keys = union_keys.select { |key| i18n_matrix_plain_key?(leaf_map, key) }
      step = (plain_keys.size / I18N_MATRIX_PLAIN_SAMPLE.to_f).ceil
      sampled = plain_keys.each_slice(step).map(&:first)

      cases = []
      app_locales.each do |locale|
        leaves = leaf_map.fetch(locale)
        leaves.each do |key, value|
          if value.is_a?(Hash)
            I18N_MATRIX_COUNTS.each do |count|
              cases << i18n_matrix_case(locale, key, { count: count })
            end
          elsif value.is_a?(String) && value.match?(/%\{/)
            args = { name: "«name»" }
            args[:count] = 3 if value.include?("%{count}") # rubocop:disable Style/FormatStringToken -- %{count} is data
            cases << i18n_matrix_case(locale, key, args)
          end
        end
        sampled.each { |key| cases << i18n_matrix_case(locale, key, {}) }
        (en_us_keys - leaves.keys).sort.each { |key| cases << i18n_matrix_case(locale, key, {}) }
        cases << i18n_matrix_case(locale, "no.such.key", {})
        cases << i18n_matrix_case(locale, "simple_form.true", {})
      end

      result = {
        "generated_by" => "bundle exec rake ffcrm:migration:i18n_matrix " \
                          "OUTPUT=spring/src/test/resources/i18n/rails_i18n_matrix.json",
        "default_locale" => I18n.default_locale.to_s,
        "app_locales" => app_locales,
        "fallbacks" => (app_locales | I18N_MATRIX_FALLBACK_TAGS).sort.index_with do |tag|
          I18n.fallbacks[tag.to_sym].map(&:to_s)
        end,
        "load_path_census" => i18n_matrix_census,
        "cases" => cases
      }

      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output} (#{cases.size} cases)"
    end
  end
end

# Flattened dotted key -> leaf value. A leaf is anything that is not a regular nested hash:
# Strings, nil, and plural hashes (non-empty keys subset of the ICU plural set).
def i18n_matrix_leaves(tree, prefix = "")
  tree.each_with_object({}) do |(key, value), leaves|
    next unless key.is_a?(String)

    dotted = prefix.empty? ? key : "#{prefix}.#{key}"
    if value.is_a?(Hash) && (value.empty? || !(value.keys - I18N_MATRIX_PLURAL_KEYS).empty?)
      i18n_matrix_leaves(value, dotted).each { |k, v| leaves[k] = v }
    else
      leaves[dotted] = value
    end
  end
end

def i18n_matrix_plain_key?(leaf_map, key)
  leaf_map.each_value do |leaves|
    value = leaves[key]
    return true if value.is_a?(String) && !value.match?(/%\{/)
  end
  false
end

def i18n_matrix_case(locale, key, args)
  result = I18n.t(key, locale: locale.to_sym, **args)
  { "locale" => locale, "key" => key, "args" => args.transform_keys(&:to_s), "result" => result }
rescue StandardError => e
  { "locale" => locale, "key" => key, "args" => args.transform_keys(&:to_s), "error" => e.class.name }
end

def i18n_matrix_census
  buckets = { "app" => [], "rails-i18n" => [], "devise-i18n" => [], "other" => [] }
  app_dir = Rails.root.join("config/locales").to_s
  I18n.load_path.each do |path|
    bucket =
      if path.start_with?(app_dir) then "app"
      elsif path.include?("rails-i18n") then "rails-i18n"
      elsif path.include?("devise-i18n") then "devise-i18n"
      else "other"
      end
    buckets[bucket] << path
  end
  census = buckets.transform_values do |paths|
    locales = paths.filter_map do |path|
      YAML.load_file(path)&.keys&.first
    rescue StandardError
      nil
    end
    { "files" => paths.size, "locales" => locales.uniq.size }
  end
  census["available_locales"] = I18n.available_locales.size
  census
end
