# frozen_string_literal: true

require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails custom-field storage, validation, and search behaviour"
    task custom_fields_matrix: :environment do
      connection = ActiveRecord::Base.connection
      abort "ffcrm:migration:custom_fields_matrix requires PostgreSQL" unless connection.adapter_name == "PostgreSQL"

      tables = %w[accounts campaigns contacts leads opportunities tasks]
      missing = tables.reject do |table|
        connection.column_exists?(table, :custom_fields) &&
          connection.select_value(<<~SQL.squish).present?
            SELECT 1 FROM pg_trigger
            WHERE tgrelid = #{connection.quote(table)}::regclass
              AND tgname = 'ffcrm_sync_custom_fields'
              AND NOT tgisinternal
          SQL
      end
      abort "custom-fields V2 trigger/column missing on: #{missing.join(', ')}" if missing.any?

      if connection.select_value("SELECT 1 FROM accounts WHERE id BETWEEN 982701 AND 982707").present? ||
         connection.select_value("SELECT 1 FROM field_groups WHERE id = 982700").present? ||
         connection.select_value("SELECT 1 FROM fields WHERE id BETWEEN 982710 AND 982799").present? ||
         tables.any? { |table| connection.columns(table).any? { |column| column.name.start_with?("cf_ab271_") } }
        abort "custom-fields matrix fixed fixture IDs or columns already exist"
      end

      output = Rails.root.join(
        ENV.fetch("OUTPUT", "spring/src/test/resources/customfields/rails_custom_fields_matrix.json")
      )
      java_columns_path = Rails.root.join("spring/src/test/resources/customfields/java_written_columns.json")
      abort "missing Java-written column fixture: #{java_columns_path}" unless java_columns_path.file?

      result = nil
      begin
        ActiveRecord::Base.transaction(requires_new: true) do
          result = custom_fields_matrix_result(JSON.parse(java_columns_path.read))
          raise ActiveRecord::Rollback
        end
      ensure
        Account.reset_column_information
      end

      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output}"
    end
  end
end

def custom_fields_matrix_result(java_fixture)
  group = FieldGroup.create!(
    id: 982700, klass_name: "Account", label: "AB-271 matrix", position: 1,
    created_at: Time.utc(2025, 1, 1), updated_at: Time.utc(2025, 1, 1)
  )
  definitions = custom_fields_matrix_create_fields(group)
  Account.reset_column_information
  record_values = custom_fields_matrix_values(definitions)
  decimal = definitions.find { |field| field.as == "decimal" }
  check_boxes = definitions.find { |field| field.as == "check_boxes" }
  row_inputs = [
    [982701, "typical", record_values],
    [982703, "null-values", {}],
    [982704, "decimal-1000", { decimal.name => BigDecimal("1000") }],
    [982705, "decimal-small", { decimal.name => BigDecimal("0.01") }],
    [982706, "empty-check-boxes", { check_boxes.name => [] }],
    [982707, "yaml-block-marker", { check_boxes.name => ["line one\nline two"] }]
  ]
  records = row_inputs.map do |id, label, values|
    Account.create!(
      { id: id, name: "AB-271 #{label}", created_at: Time.utc(2025, 1, 1),
        updated_at: Time.utc(2025, 1, 1) }.merge(values)
    )
  end
  java_record = Account.create!(id: 982702, name: "AB-271 Java matrix")
  java_fixture.fetch("columns").each do |column, raw_value|
    next unless definitions.any? { |field| field.name == column }

    ActiveRecord::Base.connection.exec_update(
      "UPDATE accounts SET #{ActiveRecord::Base.connection.quote_column_name(column)} = " \
      "#{ActiveRecord::Base.connection.quote(raw_value)} WHERE id = 982702"
    )
  end
  java_record.reload
  java_json = java_record.as_json
  java_readback = java_fixture.fetch("columns").keys.to_h do |column|
    value = java_record.public_send(column)
    [column, {
      "value" => java_json.fetch(column),
      "ruby_class" => value.class.name,
      "inspect" => value.inspect
    }]
  end
  validation = custom_fields_matrix_validation_errors(group)
  all_definitions = definitions + validation.fetch("definitions")
  Account.reset_column_information
  row_outputs = records.map do |row|
    raw_columns = all_definitions.to_h do |field|
      [field.name, ActiveRecord::Base.connection.select_value(
        "SELECT #{ActiveRecord::Base.connection.quote_column_name(field.name)}::text " \
        "FROM accounts WHERE id = #{row.id}"
      )]
    end
    trigger_document = ActiveRecord::Base.connection.select_value(
      "SELECT custom_fields::text FROM accounts WHERE id = #{row.id}"
    )
    {
      "id" => row.id,
      "raw_columns" => raw_columns,
      "rails_json" => row.reload.as_json.slice(*all_definitions.map(&:name)),
      "custom_fields" => JSON.parse(trigger_document)
    }
  end
  column_types = all_definitions.to_h do |field|
    data_type = ActiveRecord::Base.connection.select_value(<<~SQL.squish)
      SELECT data_type FROM information_schema.columns
      WHERE table_name = 'accounts' AND column_name = #{ActiveRecord::Base.connection.quote(field.name)}
    SQL
    [field.name, data_type]
  end

  {
    "generated_by" => "bundle exec rake ffcrm:migration:custom_fields_matrix " \
                      "OUTPUT=spring/src/test/resources/customfields/rails_custom_fields_matrix.json",
    "field_group" => group.attributes.slice("id", "klass_name", "label", "position"),
    "fields" => all_definitions.map do |field|
      field.attributes.slice(
        "id", "type", "field_group_id", "position", "pair_id", "name", "label", "as", "collection",
        "disabled", "required", "maxlength", "minlength", "settings"
      )
    end,
    "column_types" => column_types,
    "rows" => row_outputs,
    "validation_errors" => validation.slice("errors", "field_names"),
    "search" => custom_fields_matrix_search(all_definitions, records.map(&:id)),
    "java_written" => {
      "columns" => java_fixture.fetch("columns"),
      "rails_values" => java_readback
    }
  }
end

def custom_fields_matrix_create_fields(group)
  definitions = []
  next_id = 982710
  Field.field_types.each_key do |as|
    if %w[date_pair datetime_pair].include?(as)
      pair = Field.lookup_class(as).constantize.create_pair(
        "field" => { "field_group_id" => group.id, "label" => "matrix #{as}", "as" => as },
        "pair" => {
          "0" => { "id" => next_id, "name" => "cf_ab271_#{as}_start", "position" => definitions.length + 1 },
          "1" => { "id" => next_id + 1, "name" => "cf_ab271_#{as}_end", "position" => definitions.length + 2 }
        }
      )
      definitions.concat(pair)
      next_id += 2
    else
      name = "cf_ab271_#{as}"
      definitions << CustomField.create!(
        id: next_id, field_group: group, position: definitions.length + 1, label: "matrix #{as}",
        name: name, as: as, collection: %w[alpha beta], created_at: Time.utc(2025, 1, 1),
        updated_at: Time.utc(2025, 1, 1)
      )
      next_id += 1
    end
  end
  definitions
end

def custom_fields_matrix_values(definitions)
  values = {}
  definitions.each do |field|
    next if field.pair_id.present?

    values[field.name] = case field.as
                         when "check_boxes" then ["true", "123", "O'Brien", "café"]
                         when "boolean" then true
                         when "date" then Date.new(2025, 1, 2)
                         when "datetime" then Time.utc(2025, 1, 2, 3, 4, 5, 123_000)
                         when "decimal" then BigDecimal("1234.50")
                         when "integer" then 42
                         when "float" then 1.25
                         when "select", "radio_buttons" then "alpha"
                         when "text" then "line one\nline two"
                         else "matrix #{field.as}"
                         end
  end
  definitions.select { |field| %w[date_pair datetime_pair].include?(field.as) }.each_slice(2) do |pair|
    pair.each_with_index do |field, index|
      values[field.name] = field.as == "date_pair" ? Date.new(2025, 1, index + 2) : Time.utc(2025, 1, index + 2)
    end
  end
  values
end

def custom_fields_matrix_validation_errors(group)
  required = CustomField.create!(
    id: 982780, field_group: group, position: 90, label: "required", name: "cf_ab271_required",
    as: "string", required: true
  )
  minimum = CustomField.create!(
    id: 982781, field_group: group, position: 91, label: "minimum", name: "cf_ab271_minimum",
    as: "string", minlength: 3
  )
  maximum = CustomField.create!(
    id: 982782, field_group: group, position: 92, label: "maximum", name: "cf_ab271_maximum",
    as: "string", maxlength: 5
  )
  pair = CustomFieldDatePair.create_pair(
    "field" => { "field_group_id" => group.id, "label" => "date range", "as" => "date_pair" },
    "pair" => {
      "0" => { "id" => 982783, "name" => "cf_ab271_range_start", "position" => 93 },
      "1" => { "id" => 982784, "name" => "cf_ab271_range_end", "position" => 94, "pair_id" => 982783 }
    }
  )
  Account.reset_column_information
  invalid = Account.new(
    name: "validation", required.name => "", minimum.name => "a", maximum.name => "123456",
    pair.first.name => Date.new(2025, 2, 2), pair.last.name => Date.new(2025, 2, 1)
  )
  invalid.valid?
  {
    "errors" => invalid.errors.to_hash.transform_keys(&:to_s),
    "field_names" => [required.name, minimum.name, maximum.name, *pair.map(&:name)],
    "definitions" => [required, minimum, maximum, *pair]
  }
end

def custom_fields_matrix_search(definitions, account_ids)
  predicates = %w[
    cont not_cont blank present true false eq not_eq lt gt null not_null matches does_not_match
    lteq gteq in start
  ]
  definitions.to_h do |field|
    cases = predicates.to_h do |predicate|
      value = %w[blank present true false null not_null].include?(predicate) ? "1" : "alpha"
      query = { "#{field.name}_#{predicate}" => value }
      result = custom_fields_matrix_run_search(account_ids, query, [value])
      [predicate, result]
    end
    compound = %w[cont_any cont_all]
    compound.each do |predicate|
      name = "#{field.name}_#{predicate}"
      cases[predicate] =
        custom_fields_matrix_run_search(account_ids, { name => %w[alpha beta] }, %w[alpha beta])
    end
    uncoercible_value = case field.as
                        when "integer", "float", "decimal" then "not-a-number"
                        when "date", "date_pair" then "not-a-date"
                        when "datetime", "datetime_pair" then "not-a-datetime"
                        end
    uncoercible = if uncoercible_value
                    custom_fields_matrix_run_search(
                      account_ids, { "#{field.name}_eq" => uncoercible_value }, [uncoercible_value]
                    )
                  end
    [field.name, { "as" => field.as, "ids" => account_ids, "cases" => cases, "uncoercible" => uncoercible }]
  end
end

def custom_fields_matrix_run_search(account_ids, query, values)
  ActiveRecord::Base.transaction(requires_new: true) do
    {
      "values" => values,
      "ids" => Account.where(id: account_ids).ransack(query).result.pluck(:id).sort
    }
  end
rescue StandardError => e
  { "values" => values, "error" => e.class.name }
end
