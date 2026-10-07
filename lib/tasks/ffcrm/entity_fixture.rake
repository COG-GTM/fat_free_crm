# frozen_string_literal: true

require "base64"
require "fileutils"
require "json"
require "time"
require "active_support/testing/time_helpers"

namespace :ffcrm do
  namespace :migration do
    desc "Generate deterministic Rails-created rows for the Spring JPA domain model"
    task entity_fixture: :environment do
      tables = %w[
        account_contacts account_opportunities accounts activities addresses avatars campaigns comments
        contact_opportunities contacts emails field_groups fields groups groups_users leads lists opportunities
        permissions preferences research_tools settings tags taggings tasks users versions
      ].sort
      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/db/rails/entity_fixture.sql"))
      serialized_output = Rails.root.join(
        ENV.fetch("SERIALIZED_FORMATS_OUTPUT", "spring/src/test/resources/db/rails/serialized_formats.json")
      )
      connection = ActiveRecord::Base.connection
      transaction_sql = nil
      serialized_formats = nil
      custom_field_callback = true
      paper_trail_enabled = PaperTrail.enabled?
      time_helpers = Object.new.extend(ActiveSupport::Testing::TimeHelpers)

      tables.each do |table|
        raise "#{table} must be empty before fixture generation" unless connection.select_value(
          "SELECT COUNT(*) FROM #{connection.quote_table_name(table)}"
        ).to_i.zero?
      end

      if connection.adapter_name == "PostgreSQL"
        tables.reject { |table| table == "groups_users" }.each do |table|
          connection.reset_pk_sequence!(table)
        end
      end

      ActiveRecord::Base.transaction(requires_new: true) do
        begin
          CustomField.skip_callback(:create, :before, :add_column)
          custom_field_callback = false
          PaperTrail.enabled = true
          time_helpers.travel_to(Time.utc(2025, 1, 2, 3, 4, 5))
          PaperTrail.request.whodunnit = "1"

          owner = User.new(
            username: "entity_owner", email: "owner@example.test", first_name: "Entity", last_name: "Owner",
            encrypted_password: "fixed-encrypted-owner", password_salt: "fixed-salt-owner",
            reset_password_token: "fixed-reset-owner", confirmation_token: "fixed-confirm-owner",
            authentication_token: "fixed-auth-owner", remember_token: "fixed-remember-owner"
          )
          owner.skip_confirmation!
          owner.save!(validate: false)
          assignee = User.new(
            username: "entity_assignee", email: "assignee@example.test", first_name: "Entity",
            last_name: "Assignee", encrypted_password: "fixed-encrypted-assignee",
            password_salt: "fixed-salt-assignee", reset_password_token: "fixed-reset-assignee",
            confirmation_token: "fixed-confirm-assignee", authentication_token: "fixed-auth-assignee",
            remember_token: "fixed-remember-assignee"
          )
          assignee.skip_confirmation!
          assignee.save!(validate: false)

          group = Group.create!(name: "Entity Fixture Group")
          owner.groups << group
          time_helpers.travel(1.second)
          campaign = Campaign.create!(
            name: "Entity Fixture Campaign", user: owner, assigned_to: assignee.id,
            subscribed_users: [assignee.id, owner.id]
          )
          lead = Lead.create!(
            first_name: "Fixture", last_name: "Lead", user: owner, assigned_to: assignee.id,
            campaign: campaign
          )
          time_helpers.travel(1.second)
          contact = Contact.create!(
            first_name: "Fixture", last_name: "Contact", user: owner, assigned_to: assignee.id,
            lead: lead, reporting_user: assignee
          )
          time_helpers.travel(1.second)
          account = Account.new(
            name: "Entity Fixture Account", user: owner, assigned_to: assignee.id,
            access: "Shared", subscribed_users: [assignee.id, owner.id]
          )
          account.save!(validate: false)
          opportunity = Opportunity.create!(
            name: "Entity Fixture Opportunity", user: owner, assigned_to: assignee.id,
            campaign: campaign
          )
          Permission.create!(user: assignee, asset: account)
          Permission.create!(group: group, asset: account)
          AccountContact.create!(account: account, contact: contact)
          AccountOpportunity.create!(account: account, opportunity: opportunity)
          ContactOpportunity.create!(contact: contact, opportunity: opportunity, role: "Decision Maker")

          [account, campaign, contact, lead, opportunity].each do |asset|
            time_helpers.travel(1.second)
            Task.create!(
              name: "Task for #{asset.class.name}", user: owner, assignee: assignee, asset: asset,
              subscribed_users: [owner.id, assignee.id]
            )
          end
          Task.create!(name: "Standalone fixture task", user: owner)
          standalone_task = Task.create!(name: "Destroy-version fixture task", user: owner)
          destroyed_task_id = standalone_task.id
          standalone_task.destroy!
          Task.create!(id: destroyed_task_id, name: "Recreated standalone fixture task", user: owner)
          Task.create!(
            name: "Completed fixture task", user: owner, completed_by: assignee,
            completed_at: Time.current, asset: contact
          )

          [account, campaign, contact, lead, opportunity, Task.first].each do |commentable|
            time_helpers.travel(1.second)
            Comment.create!(user: owner, commentable: commentable, comment: "Fixture comment")
          end
          [account, contact, lead].each do |mediator|
            time_helpers.travel(1.second)
            Email.create!(
              imap_message_id: "fixture-#{mediator.class.name.downcase}@example.test", user: owner,
              mediator: mediator, sent_from: "owner@example.test", sent_to: "assignee@example.test",
              subject: "Fixture email", body: "Rails-created email"
            )
          end
          {
            account => %w[Business Billing Shipping],
            contact => %w[Business Billing Shipping],
            lead => %w[Business Billing Shipping]
          }.each do |entity, kinds|
            kinds.each do |kind|
              time_helpers.travel(1.second)
              Address.create!(
                addressable: entity, address_type: kind, street1: "1 Fixture Street",
                city: "Fixture City", state: "CA", zipcode: "90210", country: "US"
              )
            end
          end
          Avatar.create!(user: owner, entity: owner)
          Avatar.create!(user: owner, entity: contact)

          taggable = account
          taggable.tag_list = "entity-fixture, domain-model"
          taggable.save!
          tag = Tag.find_by!(name: "entity-fixture")
          FieldGroup.create!(name: "Fixture Fields", label: "Fixture Fields", tag: tag, klass_name: "Account")
          group_for_fields = FieldGroup.find_by!(name: "Fixture Fields")
          CoreField.create!(
            field_group: group_for_fields, label: "Core fixture field", name: "fixture_core",
            as: "string", position: 1
          )
          CustomField.create!(
            field_group: group_for_fields, label: "Custom fixture field", name: "fixture_custom",
            as: "check_boxes", collection: %w[one two],
            settings: { "multiple" => true }.with_indifferent_access, position: 2
          )
          List.create!(name: "Entity Fixture List", url: "/accounts", user: owner)
          ResearchTool.create!(name: "Fixture Research", url_template: "https://example.test/%s", enabled: false)
          Setting.create!(name: "fixture_string", value: "string value")
          Setting.create!(name: "fixture_symbols", value: %i[one two])
          Setting.create!(name: "fixture_hash", value: { "nested" => ["value", 2] })

          owner.pref["fixture_short"] = "all"
          owner.pref["fixture_hash"] = { "alpha" => 1, "beta" => [true, false] }
          owner.pref["fixture_long"] = "long preference " * 12
          owner.pref["fixture_unicode"] = "café-日本語-🔐"

          Rails.application.eager_load!
          activity_class = Class.new(ActiveRecord::Base) { self.table_name = "activities" }
          activity_class.create!(user_id: owner.id, subject_type: "Account", subject_id: account.id, info: "Fixture activity")

          subscribed_type = Account.type_for_attribute("subscribed_users")
          serialized_formats = {
            generated_by: "ffcrm:migration:entity_fixture",
            subscribed_users: [[], [1], [3, 1, 2]].map do |value|
              { value: value, serialized: subscribed_type.serialize(value) }
            end,
            subscribed_users_legacy_reads: [{ serialized: "--- []\n", value: [] }],
            preferences: [
              { name: "fixture_short", value: "all" },
              { name: "fixture_hash", value: { "alpha" => 1, "beta" => [true, false] } },
              { name: "fixture_long", value: "long preference " * 12 },
              { name: "fixture_unicode", value: "café-日本語-🔐" }
            ].map do |entry|
              json = entry.fetch(:value).to_json
              { name: entry.fetch(:name), json: json, serialized: Base64.encode64(json) }
            end
          }

          # Exercise the empty-array and legacy representations in stored rows.
          Account.create!(name: "Empty subscribers fixture", user: owner, subscribed_users: [])
          legacy = Account.create!(name: "Legacy empty subscribers fixture", user: owner)
          legacy_serialized = connection.quote("--- []\n")
          account_table = connection.quote_table_name('accounts')
          connection.execute(
            "UPDATE #{account_table} SET subscribed_users = #{legacy_serialized} WHERE id = #{legacy.id}"
          )

          # Ensure every soft-deletable table has a Rails-created deleted row.
          soft_deleted = [
            account, AccountContact.first, AccountOpportunity.first, campaign, contact, ContactOpportunity.first,
            lead, opportunity, Task.first, Email.first, Address.first
          ]
          soft_deleted.each do |record|
            record.update_column(:deleted_at, Time.current)
          end

          account.update!(background_info: "Versioned fixture update")

          columns_by_table = tables.index_with { |table| connection.columns(table).map(&:name) }
          if connection.adapter_name == "PostgreSQL"
            cf_columns = connection.select_values(<<~SQL.squish)
              SELECT column_name FROM information_schema.columns
              WHERE table_schema = 'public' AND column_name LIKE 'cf\\_%'
            SQL
            raise "Unexpected custom field columns: #{cf_columns.join(', ')}" if cf_columns.any?

            transaction_sql = dump_postgresql_fixture(connection, tables)
          else
            transaction_sql = dump_generic_fixture(connection, tables, columns_by_table)
          end
        ensure
          PaperTrail.request.whodunnit = nil
          PaperTrail.enabled = paper_trail_enabled
          time_helpers.travel_back
        end
        raise ActiveRecord::Rollback
      ensure
        CustomField.set_callback(:create, :before, :add_column) unless custom_field_callback
      end

      FileUtils.mkdir_p(output.dirname)
      File.write(output, transaction_sql, encoding: "UTF-8")
      FileUtils.mkdir_p(serialized_output.dirname)
      File.write(serialized_output, "#{JSON.pretty_generate(serialized_formats)}\n", encoding: "UTF-8")
      puts "Wrote #{output}"
      puts "Wrote #{serialized_output}"
    end
  end
end

def dump_postgresql_fixture(connection, tables)
  raw = connection.raw_connection
  lines = [
    "-- Generated by ffcrm:migration:entity_fixture.",
    "-- Regenerate with: FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef bundle exec rake ffcrm:migration:entity_fixture",
    ""
  ]
  tables.each do |table|
    order = table == "groups_users" ? "group_id, user_id" : "id"
    result = raw.exec("SELECT * FROM public.#{table} ORDER BY #{order}", nil, 0, PG::TypeMapAllStrings.new)
    columns = result.fields
    rows = result.values
    rows.each do |row|
      values = row.map { |value| value.nil? ? "NULL" : "'#{value.to_s.gsub("'", "''")}'" }
      quoted_columns = columns.map { |column| connection.quote_column_name(column) }
      table_name = connection.quote_table_name(table)
      insert = "INSERT INTO public.#{table_name} (#{quoted_columns.join(', ')}) VALUES (#{values.join(', ')});"
      lines << insert
    end
    next if table == "groups_users"

    max_id = rows.filter_map { |row| row[columns.index("id")]&.to_i }.max
    has_rows = !max_id.nil?
    sequence = connection.select_value(
      "SELECT pg_catalog.pg_get_serial_sequence('public.#{table}', 'id')"
    )
    next unless sequence

    lines << "SELECT pg_catalog.setval('#{sequence}', #{max_id || 1}, #{has_rows ? 'true' : 'false'});"
  end
  "#{lines.join("\n")}\n"
end

def dump_generic_fixture(connection, tables, columns_by_table)
  lines = [
    "-- Generated by ffcrm:migration:entity_fixture.",
    "-- Regenerate with: FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef bundle exec rake ffcrm:migration:entity_fixture",
    ""
  ]
  tables.each do |table|
    columns = columns_by_table.fetch(table)
    order = table == "groups_users" ? "group_id, user_id" : "id"
    connection.select_rows("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY #{order}").each do |row|
      values = row.map { |value| connection.quote(value) }
      quoted_columns = columns.map { |column| connection.quote_column_name(column) }
      table_name = connection.quote_table_name(table)
      insert = "INSERT INTO #{table_name} (#{quoted_columns.join(', ')}) VALUES (#{values.join(', ')});"
      lines << insert
    end
  end
  "#{lines.join("\n")}\n"
end
