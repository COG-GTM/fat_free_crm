# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'json'

module FatFreeCRM
  module Migration
    # Census of the dynamic custom-field (+cf_*+) columns that +CustomField+ adds
    # to entity tables at runtime.
    #
    # +db/schema.rb+ does not describe any real deployment: every installation has
    # its own set of +cf_*+ columns, and columns are never dropped when a field is
    # deleted. Anything that generates a static schema (a Flyway baseline, JPA
    # entities) needs the real per-deployment column list, cross-referenced against
    # the +fields+ metadata registry.
    #
    #   census = FatFreeCRM::Migration::ColumnCensus.new
    #   census.report[:summary]  # => { custom_columns: 12, orphaned: 2, ... }
    #   puts census.to_markdown
    class ColumnCensus
      CUSTOM_COLUMN_PATTERN = /\Acf_/

      # Every model that calls +has_fields+.
      DEFAULT_KLASS_NAMES = %w[Account Campaign Contact Lead Opportunity Task].freeze

      # A column carrying custom-field data with a matching +fields+ row.
      MAPPED = 'mapped'
      # A +cf_*+ column with no +fields+ row: data left behind by a deleted field.
      ORPHANED = 'orphaned'
      # A +fields+ row whose column is absent (field created against another database).
      MISSING_COLUMN = 'missing_column'
      # A +fields+ row with no field group, so it belongs to no entity class.
      UNATTACHED_FIELD = 'unattached_field'

      attr_reader :klass_names

      def initialize(klass_names: DEFAULT_KLASS_NAMES, count_rows: true, connection: nil)
        @klass_names = Array(klass_names).map(&:to_s)
        @count_rows = count_rows
        @connection = connection
      end

      def connection
        @connection ||= ActiveRecord::Base.connection
      end

      def count_rows?
        @count_rows
      end

      def report
        @report ||= begin
          tables = klass_names.map { |klass_name| table_report(klass_name) }
          {
            generated_at: Time.now.utc.iso8601,
            adapter: connection.adapter_name,
            schema_version: schema_version,
            row_counts_included: count_rows?,
            tables: tables,
            unattached_fields: unattached_fields,
            summary: summarize(tables)
          }
        end
      end

      def to_json(*_args)
        JSON.pretty_generate(report)
      end

      def to_markdown
        MarkdownFormatter.new(report).to_s
      end

      private

      def table_report(klass_name)
        klass = klass_name.constantize
        table = klass.table_name
        columns = custom_columns(table)
        fields = custom_fields_by_name(klass_name)

        entries = columns.map do |column|
          column_entry(table, column, fields[column.name])
        end
        entries += (fields.keys - columns.map(&:name)).map do |name|
          missing_column_entry(fields[name])
        end

        {
          klass: klass_name,
          table: table,
          total_rows: total_rows(table),
          columns: entries.sort_by { |entry| entry[:name].to_s }
        }
      end

      def column_entry(table, column, field)
        {
          name: column.name,
          status: field ? MAPPED : ORPHANED,
          sql_type: column.sql_type,
          active_record_type: column.type.to_s,
          null: column.null,
          default: column.default,
          field_id: field&.id,
          field_type: field&.type,
          field_label: field&.label,
          field_as: field&.as,
          expected_column_type: field && expected_column_type(field),
          type_mismatch: field ? type_mismatch?(column, field) : nil,
          yaml_serialized: field&.as == 'check_boxes',
          populated_rows: populated_rows(table, column.name)
        }
      end

      def missing_column_entry(field)
        {
          name: field.name,
          status: MISSING_COLUMN,
          field_id: field.id,
          field_type: field.type,
          field_label: field.label,
          field_as: field.as,
          expected_column_type: expected_column_type(field),
          yaml_serialized: field.as == 'check_boxes'
        }
      end

      def custom_columns(table)
        return [] unless connection.table_exists?(table)

        connection.columns(table).select { |column| column.name.match?(CUSTOM_COLUMN_PATTERN) }
      end

      # Custom-field metadata rows for one entity class, keyed by column name.
      def custom_fields_by_name(klass_name)
        custom_field_rows
          .select { |field| field.field_group&.klass_name == klass_name }
          .index_by(&:name)
      end

      def custom_field_rows
        @custom_field_rows ||=
          if connection.table_exists?(Field.table_name)
            Field.custom_fields.includes(:field_group).to_a
          else
            []
          end
      end

      def unattached_fields
        custom_field_rows.reject(&:field_group).map do |field|
          {
            name: field.name,
            status: UNATTACHED_FIELD,
            field_id: field.id,
            field_type: field.type,
            field_label: field.label,
            field_as: field.as
          }
        end
      end

      def expected_column_type(field)
        Field.field_types.dig(field.as, :type)
      end

      def type_mismatch?(column, field)
        expected = expected_column_type(field)
        return false if expected.blank?

        # +timestamp+ surfaces as +datetime+ in ActiveRecord's abstract types.
        actual = column.type.to_s
        actual = 'timestamp' if actual == 'datetime'
        actual != expected.to_s
      end

      def total_rows(table)
        return nil unless count_rows? && connection.table_exists?(table)

        connection.select_value("SELECT COUNT(*) FROM #{connection.quote_table_name(table)}").to_i
      end

      def populated_rows(table, column_name)
        return nil unless count_rows?

        sql = "SELECT COUNT(#{connection.quote_column_name(column_name)}) " \
              "FROM #{connection.quote_table_name(table)}"
        connection.select_value(sql).to_i
      end

      def schema_version
        connection.select_value('SELECT MAX(version) FROM schema_migrations').to_s
      rescue ActiveRecord::StatementInvalid
        nil
      end

      def summarize(tables)
        entries = tables.flat_map { |table| table[:columns] }
        {
          tables: tables.size,
          custom_columns: entries.count { |e| e[:status] != MISSING_COLUMN },
          mapped: entries.count { |e| e[:status] == MAPPED },
          orphaned: entries.count { |e| e[:status] == ORPHANED },
          missing_columns: entries.count { |e| e[:status] == MISSING_COLUMN },
          unattached_fields: unattached_fields.size,
          type_mismatches: entries.count { |e| e[:type_mismatch] },
          yaml_serialized: entries.count { |e| e[:yaml_serialized] },
          by_field_as: entries.filter_map { |e| e[:field_as] }.tally,
          by_sql_type: entries.filter_map { |e| e[:sql_type] }.tally
        }
      end

      # Renders a census report as a review-friendly Markdown document.
      class MarkdownFormatter
        COLUMN_HEADERS = ['Column', 'Status', 'SQL type', 'Field type', 'Label', 'Populated'].freeze

        def initialize(report)
          @report = report
        end

        def to_s
          [header, summary, tables, unattached].compact.join("\n")
        end

        private

        attr_reader :report

        def header
          <<~MD
            # Custom field (`cf_*`) column census

            - Generated: #{report[:generated_at]}
            - Adapter: #{report[:adapter]}
            - Schema version: #{report[:schema_version] || 'unknown'}
            - Row counts included: #{report[:row_counts_included]}
          MD
        end

        def summary
          summary = report[:summary]
          rows = summary.except(:by_field_as, :by_sql_type).map { |key, value| "| #{key} | #{value} |" }
          rows += summary[:by_field_as].map { |as, count| "| field type `#{as}` | #{count} |" }
          rows += summary[:by_sql_type].map { |type, count| "| sql type `#{type}` | #{count} |" }
          ["\n## Summary\n", '| Metric | Value |', '|---|---|', *rows, ''].join("\n")
        end

        def tables
          report[:tables].map { |table| table_section(table) }.join("\n")
        end

        def table_section(table)
          heading = "\n## #{table[:klass]} (`#{table[:table]}`, #{table[:total_rows] || '?'} rows)\n"
          return "#{heading}\nNo `cf_*` columns.\n" if table[:columns].empty?

          rows = table[:columns].map { |column| column_row(column) }
          [heading, "| #{COLUMN_HEADERS.join(' | ')} |",
           "|#{'---|' * COLUMN_HEADERS.size}", *rows, ''].join("\n")
        end

        def column_row(column)
          cells = [
            "`#{column[:name]}`",
            status_cell(column),
            column[:sql_type] ? "`#{column[:sql_type]}`" : '—',
            column[:field_as] || '—',
            column[:field_label] || '—',
            column[:populated_rows] || '—'
          ]
          "| #{cells.join(' | ')} |"
        end

        def status_cell(column)
          flags = []
          flags << 'type mismatch' if column[:type_mismatch]
          flags << 'YAML' if column[:yaml_serialized]
          flags.empty? ? column[:status] : "#{column[:status]} (#{flags.join(', ')})"
        end

        def unattached
          fields = report[:unattached_fields]
          return nil if fields.empty?

          rows = fields.map { |field| "| `#{field[:name]}` | #{field[:field_type]} | #{field[:field_as]} |" }
          ["\n## Fields with no field group\n", '| Name | Type | Field type |', '|---|---|---|', *rows, ''].join("\n")
        end
      end
    end
  end
end
