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

      field_ids = custom_fields_matrix_field_ids
      field_id_min, field_id_max = field_ids.minmax
      if connection.select_value(
        "SELECT 1 FROM field_groups WHERE id = #{custom_fields_matrix_group_id}"
      ).present? ||
         connection.select_value(
           "SELECT 1 FROM fields WHERE id BETWEEN #{field_id_min} AND #{field_id_max}"
         ).present? ||
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
    id: custom_fields_matrix_group_id, klass_name: "Account", label: "AB-271 matrix", position: 1,
    created_at: Time.utc(2025, 1, 1), updated_at: Time.utc(2025, 1, 1)
  )
  definitions = custom_fields_matrix_create_fields(group)
  Account.reset_column_information
  record_values = custom_fields_matrix_values(definitions)
  secondary_values = custom_fields_matrix_values(definitions, variant: 1)
  tertiary_values = custom_fields_matrix_values(definitions, variant: 2)
  decimal = definitions.find { |field| field.as == "decimal" }
  check_boxes = definitions.find { |field| field.as == "check_boxes" }
  row_inputs = [
    [982701, "typical", record_values],
    [982703, "null-values", {}],
    [982704, "decimal-1000", { decimal.name => BigDecimal("1000") }],
    [982705, "decimal-small", { decimal.name => BigDecimal("0.01") }],
    [982706, "empty-check-boxes", { check_boxes.name => [] }],
    [982707, "yaml-block-marker", { check_boxes.name => ["line one\nline two"] }],
    [982708, "typed-secondary", secondary_values],
    [982709, "typed-tertiary", tertiary_values],
    [982710, "duplicate-and-blank-check-boxes", { check_boxes.name => %w[alpha alpha] + [""] }]
  ]
  account_ids = row_inputs.map { |id, _label, _values| id } + [custom_fields_matrix_java_record_id]
  account_id_min, account_id_max = account_ids.minmax
  if ActiveRecord::Base.connection.select_value(
    "SELECT 1 FROM accounts WHERE id BETWEEN #{account_id_min} AND #{account_id_max}"
  ).present?
    raise "custom-fields matrix fixed fixture IDs or columns already exist"
  end

  records = row_inputs.map do |id, label, values|
    Account.create!(
      { id: id, name: "AB-271 #{label}", created_at: Time.utc(2025, 1, 1),
        updated_at: Time.utc(2025, 1, 1) }.merge(values)
    )
  end
  java_record = Account.create!(id: custom_fields_matrix_java_record_id, name: "AB-271 Java matrix")
  java_fixture.fetch("columns").each do |column, raw_value|
    next unless definitions.any? { |field| field.name == column }

    ActiveRecord::Base.connection.exec_update(
      "UPDATE accounts SET #{ActiveRecord::Base.connection.quote_column_name(column)} = " \
      "#{ActiveRecord::Base.connection.quote(raw_value)} " \
      "WHERE id = #{custom_fields_matrix_java_record_id}"
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
  next_id = custom_fields_matrix_first_field_id
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

def custom_fields_matrix_values(definitions, variant: 0)
  values = {}
  definitions.each do |field|
    next if field.pair_id.present?

    values[field.name] = case field.as
                         when "check_boxes"
                           [["true", "123", "O'Brien", "café"], ["alpha", "green"], ["beta", "true"]][variant]
                         when "boolean" then [true, false, true][variant]
                         when "date"
                           [Date.new(2025, 1, 2), Date.new(2024, 12, 31), Date.new(2025, 3, 1)][variant]
                         when "datetime"
                           [
                             Time.utc(2025, 1, 2, 3, 4, 5, 123_000),
                             Time.utc(2024, 12, 31, 11, 22, 33, 987_000),
                             Time.utc(2025, 1, 2, 3, 4, 5)
                           ][variant]
                         when "decimal"
                           [BigDecimal("1234.50"), BigDecimal("1000"), BigDecimal("0.01")][variant]
                         when "integer" then [42, 10, 100][variant]
                         when "float" then [1.25, -0.5, 3.0][variant]
                         when "select", "radio_buttons" then %w[alpha beta alpha][variant]
                         when "text"
                           ["line one\nline two", "AlPhA%_text-Suffix", "beta_text%_END"][variant]
                         else
                           ["matrix #{field.as}", "Alpha%_#{field.as}-Suffix", "beta_#{field.as}%_END"][variant]
                         end
  end
  definitions.select { |field| %w[date_pair datetime_pair].include?(field.as) }.each_slice(2) do |pair|
    pair_values = if pair.first.as == "date_pair"
                    [
                      [Date.new(2025, 1, 2), Date.new(2025, 1, 3)],
                      [Date.new(2024, 12, 31), Date.new(2025, 1, 1)],
                      [Date.new(2025, 3, 1), Date.new(2025, 3, 2)]
                    ]
                  else
                    [
                      [Time.utc(2025, 1, 2, 3, 4, 5, 123_000), Time.utc(2025, 1, 3, 0, 0)],
                      [Time.utc(2024, 12, 31, 11, 22, 33, 987_000), Time.utc(2025, 1, 1, 0, 0)],
                      [Time.utc(2025, 1, 2, 3, 4, 5), Time.utc(2025, 3, 1, 12, 0)]
                    ]
                  end
    pair.each_with_index do |field, index|
      values[field.name] = pair_values.fetch(variant).fetch(index)
    end
  end
  values
end

def custom_fields_matrix_validation_errors(group)
  ids = custom_fields_matrix_validation_field_ids
  required = CustomField.create!(
    id: ids[0], field_group: group, position: 90, label: "required", name: "cf_ab271_required",
    as: "string", required: true
  )
  minimum = CustomField.create!(
    id: ids[1], field_group: group, position: 91, label: "minimum", name: "cf_ab271_minimum",
    as: "string", minlength: 3
  )
  maximum = CustomField.create!(
    id: ids[2], field_group: group, position: 92, label: "maximum", name: "cf_ab271_maximum",
    as: "string", maxlength: 5
  )
  pair = CustomFieldDatePair.create_pair(
    "field" => { "field_group_id" => group.id, "label" => "date range", "as" => "date_pair" },
    "pair" => {
      "0" => { "id" => ids[3], "name" => "cf_ab271_range_start", "position" => 93 },
      "1" => { "id" => ids[4], "name" => "cf_ab271_range_end", "position" => 94, "pair_id" => ids[3] }
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

def custom_fields_matrix_group_id
  982700
end

def custom_fields_matrix_java_record_id
  982702
end

def custom_fields_matrix_first_field_id
  982710
end

def custom_fields_matrix_validation_field_ids
  (982780..982784).to_a
end

def custom_fields_matrix_field_ids
  ids = []
  next_id = custom_fields_matrix_first_field_id
  Field.field_types.each_key do |as|
    count = %w[date_pair datetime_pair].include?(as) ? 2 : 1
    ids.concat((next_id...(next_id + count)).to_a)
    next_id += count
  end
  ids.concat(custom_fields_matrix_validation_field_ids)
end

def custom_fields_matrix_search(definitions, account_ids)
  predicates = %w[
    cont not_cont blank present true false eq not_eq lt gt null not_null matches does_not_match
    lteq gteq in start
  ]
  definitions.to_h do |field|
    cases = {}
    add_case = lambda do |predicate, label, values|
      query_value = values.one? ? values.first : values
      query = { "#{field.name}_#{predicate}" => query_value }
      cases["#{predicate}:#{label}"] = custom_fields_matrix_run_search(account_ids, query, values)
    end

    predicates.each do |predicate|
      flag = %w[blank present true false null not_null].include?(predicate)
      add_case.call(predicate, flag ? "flag-1" : "alpha", [flag ? "1" : "alpha"])
    end
    %w[blank present true false null not_null].each do |predicate|
      add_case.call(predicate, "flag-0", ["0"])
    end

    case field.as
    when "integer"
      {
        %w[eq integer] => ["42"], %w[eq decimal-string] => ["42.0"],
        %w[not_eq 10] => ["10"], %w[lt 42] => ["42"], %w[lteq 42] => ["42"],
        %w[gt 42] => ["42"], %w[gteq 42] => ["42"], %w[in 10-and-42] => %w[10 42]
      }.each { |(predicate, label), values| add_case.call(predicate, label, values) }
    when "float"
      {
        %w[eq 1.25] => ["1.25"], %w[eq 3.0] => ["3.0"],
        %w[not_eq 1.25] => ["1.25"], %w[lt 1.25] => ["1.25"], %w[lteq 1.25] => ["1.25"],
        %w[gt 1.25] => ["1.25"], %w[gteq 1.25] => ["1.25"],
        %w[in negative-and-positive] => ["-0.5", "1.25"]
      }.each { |(predicate, label), values| add_case.call(predicate, label, values) }
    when "decimal"
      {
        %w[eq 1000-point-zero-zero] => ["1000.00"], %w[eq 1000] => ["1000"],
        %w[not_eq 0.01] => ["0.01"], %w[lt 1000] => ["1000"], %w[lteq 1000] => ["1000"],
        %w[gt 1000] => ["1000"], %w[gteq 1000] => ["1000"],
        %w[in decimal-values] => ["0.01", "1000", "1234.50"]
      }.each { |(predicate, label), values| add_case.call(predicate, label, values) }
    when "date", "date_pair"
      {
        %w[eq iso-date] => ["2025-01-02"], %w[not_eq iso-date] => ["2025-01-02"],
        %w[lt iso-date] => ["2025-01-02"], %w[lteq iso-date] => ["2025-01-02"],
        %w[gt iso-date] => ["2025-01-02"], %w[gteq iso-date] => ["2025-01-02"],
        %w[in december-and-january] => %w[2024-12-31 2025-01-02]
      }.each { |(predicate, label), values| add_case.call(predicate, label, values) }
    when "datetime", "datetime_pair"
      {
        %w[eq iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[eq sql-timestamp] => ["2025-01-02 03:04:05"],
        %w[not_eq iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[lt iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[lteq iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[gt iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[gteq iso-timestamp] => ["2025-01-02T03:04:05Z"],
        %w[in fractional-and-whole] =>
          ["2024-12-31T11:22:33.987Z", "2025-01-02T03:04:05Z"]
      }.each { |(predicate, label), values| add_case.call(predicate, label, values) }
    when "boolean"
      %w[true false 1 0].each do |value|
        add_case.call("eq", "value-#{value}", [value])
        add_case.call("not_eq", "value-#{value}", [value])
      end
    when "select", "radio_buttons"
      %w[alpha beta].each do |value|
        add_case.call("eq", value, [value])
        add_case.call("not_eq", value, [value])
      end
      add_case.call("in", "alpha-and-beta", %w[alpha beta])
    when "check_boxes"
      add_case.call("cont", "element-true", ["true"])
      add_case.call("not_cont", "element-green", ["green"])
      add_case.call("cont_any", "alpha-and-beta", %w[alpha beta])
      add_case.call("cont_all", "alpha-and-beta", %w[alpha beta])
    when "string", "text", "email", "url", "tel"
      eq_value = field.as == "text" ? "line one\nline two" : "matrix #{field.as}"
      add_case.call("eq", "row-one", [eq_value])
      add_case.call("lt", "m", ["m"])
      add_case.call("gt", "m", ["m"])
      add_case.call("cont", "case-insensitive-alpha", ["alpha"])
      add_case.call("cont", "literal-percent", ["%"])
      add_case.call("not_cont", "case-insensitive-alpha", ["alpha"])
      add_case.call("start", "case-insensitive-alpha", ["alpha"])
      add_case.call("end", "case-insensitive-suffix", ["suffix"])
      add_case.call("matches", "alpha-wildcard", ["alpha%"])
      add_case.call("does_not_match", "alpha-wildcard", ["alpha%"])
    end

    compound = %w[cont_any cont_all]
    compound.each do |predicate|
      next if field.as == "check_boxes"

      add_case.call(predicate, "alpha-and-beta", %w[alpha beta])
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
