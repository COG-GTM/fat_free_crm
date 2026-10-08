# frozen_string_literal: true

require "yaml"
require "time"

module ContractFixtures
  BASE_TIME = Time.iso8601("2026-01-01T09:00:00Z")
  PASSWORD_SALT_PREFIX = "ab266-contract-salt-"
  SECRET_TOKEN = "ab266-contract-fixture-secret-token-0123456789abcdef0123456789abcdef"
  RESET_TABLE_EXCLUSIONS = %w[schema_migrations ar_internal_metadata].freeze

  class << self
    def load!
      users = fixture_users
      abort "Contract fixtures refuse to load because users already exist; set CONTRACT_FIXTURES_RESET=1 to reset." if
        User.exists? && ENV["CONTRACT_FIXTURES_RESET"] != "1"

      reset_tables! if ENV["CONTRACT_FIXTURES_RESET"] == "1"
      ActiveRecord::Base.transaction do
        seed_users!(users)
        seed_activity_preferences!
        seed_groups!
        seed_secret_token!
        seed_entities!
        FixtureSupport.seed_relations!
        seed_versions!
        FixtureSupport.reset_sequences!
        seed_metadata!
      end
      Rails.logger.info("Loaded deterministic contract fixtures.")
    end

    private

    def fixture_users
      path = File.expand_path("../spring/src/contractTest/resources/contract/users.yml", __dir__)
      YAML.safe_load_file(path).fetch("users")
    end

    def reset_tables!
      connection = ActiveRecord::Base.connection
      tables = connection.tables - RESET_TABLE_EXCLUSIONS
      if connection.adapter_name == "PostgreSQL"
        quoted_tables = tables.map { |table| connection.quote_table_name(table) }.join(", ")
        connection.execute("TRUNCATE TABLE #{quoted_tables} RESTART IDENTITY CASCADE") unless tables.empty?
      else
        connection.disable_referential_integrity do
          tables.each { |table| connection.execute("DELETE FROM #{connection.quote_table_name(table)}") }
        end
      end
    end

    def seed_users!(fixtures)
      rows = fixtures.map do |key, attributes|
        id = attributes.fetch("id")
        password_salt = "#{PASSWORD_SALT_PREFIX}#{id}"
        timestamp = time_for(id)
        {
          id: id,
          username: attributes.fetch("username"),
          email: attributes.fetch("email"),
          first_name: key == "admin" ? "Contract" : key.capitalize,
          last_name: "Fixture",
          encrypted_password: User.encryptor_class.digest(
            attributes.fetch("password"), User.stretches, password_salt, User.pepper
          ),
          password_salt: password_salt,
          admin: attributes.fetch("admin", false),
          suspended_at: attributes["suspended"] ? timestamp : nil,
          confirmed_at: timestamp,
          confirmation_sent_at: timestamp,
          sign_in_count: 0,
          unconfirmed_email: "",
          created_at: timestamp,
          updated_at: timestamp
        }
      end
      User.insert_all!(rows)
    end

    def seed_groups!
      Group.insert_all!([
                          { id: 1, name: "Sales", created_at: time_for(20), updated_at: time_for(20) },
                          { id: 2, name: "Support", created_at: time_for(21), updated_at: time_for(21) }
                        ])
      ActiveRecord::Base.connection.execute(
        "INSERT INTO groups_users (group_id, user_id) VALUES (1, 2), (1, 4), (2, 3)"
      )
    end

    def seed_activity_preferences!
      bob = User.find_by!(username: "bob")
      bob.pref[:activity_user] = "admin@contract.example"
      bob.pref[:activity_event] = "all_events"
    end

    def seed_secret_token!
      timestamp = time_for(30)
      setting = Setting.find_by(name: "secret_token")
      if setting
        setting.update_columns(value: SECRET_TOKEN, created_at: timestamp, updated_at: timestamp)
      else
        Setting.insert_all!([
                              { name: "secret_token", value: SECRET_TOKEN, created_at: timestamp, updated_at: timestamp }
                            ])
      end
      Setting.clear_cache!
    end

    def seed_metadata!
      connection = ActiveRecord::Base.connection
      timestamp = connection.quote(BASE_TIME)
      connection.update(
        "UPDATE ar_internal_metadata SET created_at = #{timestamp}, updated_at = #{timestamp}"
      )
    end

    def seed_entities!
      %i[accounts contacts leads opportunities campaigns].each do |table|
        table.to_s.classify.constantize.insert_all!(entity_rows(table))
      end
      Task.insert_all!(FixtureSupport.task_rows)
    end

    def seed_versions!
      now = Time.current
      user = User.find(2)
      user_attributes = user.attributes.merge(
        "encrypted_password" => "fixture-encrypted-password",
        "password_salt" => "fixture-password-salt",
        "authentication_token" => "fixture-authentication-token"
      )
      rows = [{
        id: 1,
        item_type: "User",
        item_id: user.id,
        event: "update",
        whodunnit: "1",
        object: PaperTrail.serializer.dump(user_attributes),
        created_at: now - 1.minute,
        object_changes: PaperTrail.serializer.dump(
          "email" => [user.email, "alice.activity@example.test"],
          "encrypted_password" => %w[fixture-old-password fixture-new-password]
        )
      }]
      [101, 103, 104, 102].each_with_index do |account_id, index|
        account = Account.find(account_id)
        rows << {
          id: index + 2,
          item_type: "Account",
          item_id: account.id,
          event: "update",
          whodunnit: "1",
          object: PaperTrail.serializer.dump(account.attributes),
          created_at: now - (index + 2).minutes,
          object_changes: PaperTrail.serializer.dump(
            "name" => ["Before #{account.name}", account.name]
          )
        }
      end
      Version.insert_all!(rows)
    end

    def entity_rows(table)
      id_range = { accounts: 100, contacts: 200, leads: 300, opportunities: 400, campaigns: 500 }.fetch(table)
      names = access_examples
      names.each_with_index.map do |example, index|
        id = id_range + index + 1
        row = {
          id: id,
          user_id: example.fetch(:owner),
          assigned_to: example[:assignee],
          access: example.fetch(:access),
          deleted_at: nil,
          created_at: time_for(id + 100),
          updated_at: time_for(id + 100)
        }
        case table
        when :accounts
          row.merge!(name: "Contract Account #{id}", email: "account-#{id}@contract.example", rating: 0,
                     contacts_count: id == 101 ? 1 : 0, opportunities_count: id == 101 ? 1 : 0,
                     subscribed_users: [])
        when :contacts
          row.merge!(first_name: "Contact", last_name: "Fixture #{id}", email: "contact-#{id}@contract.example",
                     do_not_call: false, lead_id: nil, subscribed_users: [])
          row[:lead_id] = 301 if id == 201
        when :leads
          row.merge!(first_name: "Lead", last_name: "Fixture #{id}", rating: 0, do_not_call: false, campaign_id: nil,
                     subscribed_users: [])
          row[:campaign_id] = 501 if id == 301
        when :opportunities
          row.merge!(name: "Contract Opportunity #{id}", stage: "prospecting", subscribed_users: [])
        when :campaigns
          row.merge!(name: "Contract Campaign #{id}", status: "active", leads_count: 0, opportunities_count: 0,
                     subscribed_users: [])
          row[:leads_count] = 1 if id == 501
        end
        row
      end
    end

    def access_examples
      [
        { owner: 2, access: "Public" },
        { owner: 2, access: "Private" },
        { owner: 2, access: "Shared", permission: :user },
        { owner: 2, access: "Shared", permission: :group },
        { owner: 2, assignee: 3, access: "Private" },
        { owner: 3, access: "Private" },
        { owner: 1, access: "Private" }
      ]
    end

    def time_for(offset)
      BASE_TIME + offset
    end
  end

  module FixtureSupport
    class << self
      def task_rows
        [
          task_row(601, 2, "Alice unassigned task"),
          task_row(602, 2, "Alice task assigned to Bob", assignee: 3),
          task_row(603, 5, "Task completed by Bob", completed_by: 3, completed_at: time_for(603)),
          task_row(604, 2, "Alice account task", asset_type: "Account", asset_id: 101),
          task_row(605, 1, "Admin private task")
        ]
      end

      def seed_relations!
        permission_rows = []
        base_ids = { "Account" => 100, "Contact" => 200, "Lead" => 300, "Opportunity" => 400, "Campaign" => 500 }
        %w[Account Contact Lead Opportunity Campaign].each do |type|
          [[:user, 3], [:group, 2]].each_with_index do |(permission_type, permission_id), index|
            permission_rows << {
              user_id: permission_type == :user ? permission_id : nil,
              group_id: permission_type == :group ? permission_id : nil,
              asset_type: type,
              asset_id: base_ids.fetch(type) + 3 + index,
              created_at: time_for(900 + permission_rows.length),
              updated_at: time_for(900 + permission_rows.length)
            }
          end
        end
        Permission.insert_all!(permission_rows)

        AccountContact.insert_all!([
                                     { account_id: 101, contact_id: 201, created_at: time_for(1000), updated_at: time_for(1000) }
                                   ])
        AccountOpportunity.insert_all!([
                                         { account_id: 101, opportunity_id: 401, created_at: time_for(1001), updated_at: time_for(1001) }
                                       ])
        ContactOpportunity.insert_all!([
                                         { contact_id: 201, opportunity_id: 401, role: "Decision maker",
                                           created_at: time_for(1002), updated_at: time_for(1002) }
                                       ])

        Comment.insert_all!([
                              { user_id: 2, commentable_type: "Account", commentable_id: 101, private: false, title: "Public account",
                                comment: "Public account comment", state: "Expanded", created_at: time_for(1010),
                                updated_at: time_for(1010) },
                              { user_id: 3, commentable_type: "Contact", commentable_id: 203, private: false, title: "Shared contact",
                                comment: "Shared contact comment", state: "Expanded", created_at: time_for(1011),
                                updated_at: time_for(1011) }
                            ])
        Address.insert_all!([
                              { street1: "1 Contract Way", city: "Portland", state: "OR", zipcode: "97201", country: "US",
                                address_type: "Billing", addressable_type: "Account", addressable_id: 101,
                                created_at: time_for(1020), updated_at: time_for(1020) }
                            ])
        Tag.insert_all!([
                          { id: 1, name: "contract-public", taggings_count: 1 },
                          { id: 2, name: "contract-contact", taggings_count: 1 }
                        ])
        Tagging.insert_all!([
                              { tag_id: 1, taggable_id: 101, taggable_type: "Account", tagger_id: 2, tagger_type: "User",
                                context: "tags", created_at: time_for(1030) },
                              { tag_id: 2, taggable_id: 201, taggable_type: "Contact", tagger_id: 3, tagger_type: "User",
                                context: "tags", created_at: time_for(1031) }
                            ])
        Email.insert_all!([
                            { id: 701, imap_message_id: "<contract-701@ffcrm>", user_id: 2, mediator_type: "Account",
                              mediator_id: 101, sent_from: "alice@contract.example", sent_to: "account-101@contract.example",
                              cc: nil, bcc: nil, subject: "Contract email on account 101", body: "Alice email body",
                              header: nil, sent_at: time_for(1040), received_at: time_for(1040), deleted_at: nil,
                              state: "Expanded", created_at: time_for(1040), updated_at: time_for(1040) },
                            { id: 702, imap_message_id: "<contract-702@ffcrm>", user_id: 3, mediator_type: "Contact",
                              mediator_id: 203, sent_from: "bob@contract.example", sent_to: "contact-203@contract.example",
                              cc: nil, bcc: nil, subject: "Contract email on shared contact", body: "Bob email body",
                              header: nil, sent_at: time_for(1041), received_at: time_for(1041), deleted_at: nil,
                              state: "Expanded", created_at: time_for(1041), updated_at: time_for(1041) }
                          ])
        List.insert_all!([
                           { id: 801, user_id: 2, name: "Alice Contract List", url: "/accounts?query=alice",
                             created_at: time_for(1050), updated_at: time_for(1050) },
                           { id: 802, user_id: 3, name: "Bob Contract List", url: "/tasks?view=pending",
                             created_at: time_for(1051), updated_at: time_for(1051) },
                           { id: 803, user_id: nil, name: "Global Contract List", url: "/leads",
                             created_at: time_for(1052), updated_at: time_for(1052) }
                         ])
      end

      def reset_sequences!
        connection = ActiveRecord::Base.connection
        return unless connection.adapter_name == "PostgreSQL"

        tables = %w[users groups settings accounts contacts leads opportunities campaigns tasks permissions comments
                    emails lists addresses tags taggings versions]
        tables.each do |table|
          quoted = connection.quote_table_name(table)
          connection.execute(
            "SELECT setval(pg_get_serial_sequence(#{connection.quote(table)}, 'id'), " \
            "COALESCE(MAX(id), 1), MAX(id) IS NOT NULL) FROM #{quoted}"
          )
        end
      end

      private

      def task_row(id, owner, name, **attributes)
        {
          id: id,
          user_id: owner,
          assigned_to: attributes[:assignee],
          completed_by: attributes[:completed_by],
          name: name,
          asset_type: attributes[:asset_type],
          asset_id: attributes[:asset_id],
          bucket: "due_later",
          completed_at: attributes[:completed_at],
          deleted_at: nil,
          created_at: time_for(id + 700),
          updated_at: time_for(id + 700),
          subscribed_users: []
        }
      end

      def time_for(offset)
        ContractFixtures::BASE_TIME + offset
      end
    end
  end
end

ContractFixtures.load! if defined?(Rails::Command::RunnerCommand) &&
                          File.expand_path($PROGRAM_NAME) == File.expand_path(__FILE__)
