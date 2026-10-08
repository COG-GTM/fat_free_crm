# frozen_string_literal: true

require "json"

# AB-273 (settings-i18n): records how Rails resolves Setting[key] across the three tiers
# (config/settings.default.yml, an override YAML file, and the settings table) so the Spring
# SettingsService can be asserted key-by-key against real Rails behavior.
SETTINGS_MATRIX_DIGS = [
  %w[email_dropbox server],
  %w[email_dropbox port],
  %w[smtp auth method],
  %w[smtp auth retries],
  %w[absent_key anything]
].freeze

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails Setting tier precedence as a parity matrix for Spring"
    task settings_matrix: :environment do
      abort "ffcrm:migration:settings_matrix requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/settings/rails_settings_matrix.json"))
      defaults_file = Rails.root.join("config/settings.default.yml")
      override_file = Rails.root.join("spring/src/test/resources/settings/settings.override.yml")

      saved_yaml = Setting.yaml_settings
      result = nil
      ActiveRecord::Base.transaction(requires_new: true) do
        begin
          states = [
            settings_matrix_state("defaults_only", defaults_file, nil, seed_rows: false),
            settings_matrix_state("with_override_file", defaults_file, override_file, seed_rows: false),
            settings_matrix_state("db_overlay_on_defaults", defaults_file, nil, seed_rows: true),
            settings_matrix_state("db_overlay_on_override", defaults_file, override_file, seed_rows: true)
          ]
          result = {
            "generated_by" => "bundle exec rake ffcrm:migration:settings_matrix " \
                              "OUTPUT=spring/src/test/resources/settings/rails_settings_matrix.json",
            "override_fixture" => "spring/src/test/resources/settings/settings.override.yml",
            "states" => states
          }
        ensure
          Setting.yaml_settings = saved_yaml
          Setting.clear_cache!
        end
        raise ActiveRecord::Rollback
      end

      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output}"
    end
  end
end

# Builds the yaml tier for one state, optionally seeds DB rows, and dumps the resolved values.
def settings_matrix_state(name, defaults_file, override_file, seed_rows:)
  Setting.yaml_settings = {}.with_indifferent_access
  Setting.load_settings_from_yaml(defaults_file)
  Setting.load_settings_from_yaml(override_file) if override_file
  yaml_keys = Setting.yaml_settings.keys.map(&:to_s)

  Setting.delete_all
  settings_matrix_seed_rows if seed_rows
  Setting.clear_cache!

  rows = ActiveRecord::Base.connection.select_all("SELECT name, value FROM settings ORDER BY name").to_a
  keys = (yaml_keys | rows.pluck("name") | %w[absent_key]).sort
  {
    "state" => name,
    "override_file" => override_file ? File.basename(override_file) : nil,
    "rows" => rows,
    "values" => keys.index_with { |key| settings_matrix_read { Setting[key] } },
    "digs" => SETTINGS_MATRIX_DIGS.map do |path|
      { "path" => path, "result" => settings_matrix_read { Setting.dig(path.first, *path[1..]) } }
    end
  }
end

def settings_matrix_read
  settings_matrix_encode(yield)
rescue StandardError => e
  { "$error" => e.class.name }
end

# Fixture encoding contract (spring RailsSettingsParityTest mirrors this):
# Symbol -> ":name"; String -> itself; Hash -> string keys (Symbol -> name, String -> itself,
# other -> to_s) with encoded values; Array -> encoded elements; Integer/Float/true/false/nil ->
# native JSON; Date/Time -> {"$class", "iso8601"}; anything else -> {"$class", "inspect"}.
def settings_matrix_encode(value)
  case value
  when Symbol then ":#{value}"
  when Hash
    value.each_with_object({}) do |(key, item), encoded|
      encoded[settings_matrix_key(key)] = settings_matrix_encode(item)
    end
  when Array then value.map { |item| settings_matrix_encode(item) }
  when String, Integer, Float, true, false, nil then value
  when Date, Time, DateTime, ActiveSupport::TimeWithZone
    { "$class" => value.class.name, "iso8601" => value.iso8601 }
  else
    { "$class" => value.class.name, "inspect" => value.inspect }
  end
end

# Symbol -> name, String -> itself, anything else -> to_s; String#to_s is itself.
def settings_matrix_key(key)
  key.to_s
end

# Written via `Setting[name] = value` so Rails' serializer produces the exact stored bytes,
# except the rows marked raw SQL which are inserted verbatim.
def settings_matrix_seed_rows
  Setting["email_dropbox"] = { server: "imap.db.test", ssl: true }
  Setting["compound_address"] = false
  Setting["require_first_names"] = false
  Setting["comments_visible_on_dashboard"] = true
  Setting["host"] = ""
  Setting["base_url"] = "   "
  Setting["lead_status"] = []
  Setting["per_user_locale"] = nil
  Setting["campaign_status"] = %i[planned custom]
  Setting["opportunity_default_stage"] = :analysis
  Setting["default_access"] = "Private"
  Setting["db_only_integer"] = 0
  Setting["db_only_float"] = 1.5
  Setting["smtp"] = {
    "address" => "smtp.db.test", "port" => 587,
    "auth" => { "method" => :plain, "retries" => [1, 2, 3] }
  }

  connection = ActiveRecord::Base.connection
  stamp = Time.utc(2026, 1, 1)
  raw_rows = {
    "db_only_hwia" => "--- !ruby/hash:ActiveSupport::HashWithIndifferentAccess\na: 1\n",
    "db_only_json" => '{"a": [1, 2]}',
    "db_bad_class" => "--- !ruby/object:OpenStruct\ntable: {}\n"
  }
  raw_rows.each do |name, value|
    connection.exec_insert(
      "INSERT INTO settings (name, value, created_at, updated_at) VALUES ($1, $2, $3, $3)",
      "settings matrix raw row",
      [
        ActiveRecord::Relation::QueryAttribute.new("name", name, ActiveRecord::Type::String.new),
        ActiveRecord::Relation::QueryAttribute.new("value", value, ActiveRecord::Type::Text.new),
        ActiveRecord::Relation::QueryAttribute.new("stamp", stamp, ActiveRecord::Type::DateTime.new)
      ]
    )
  end
end
