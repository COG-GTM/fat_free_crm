# frozen_string_literal: true

require "json"
require "fileutils"

namespace :ffcrm do
  desc "Generate persisted Rails legacy-auth rows for the Spring compatibility fixture"
  task generate_legacy_auth_fixture: :environment do
    abort "Refusing to generate a fixture outside development or test (RAILS_ENV=#{Rails.env})" unless Rails.env.local?

    database_name = ActiveRecord::Base.connection_db_config.database.to_s
    abort "Refusing database #{database_name.inspect}; use a database ending in _development or _test" unless database_name.match?(/_(development|test)\z/)

    abort "Refusing to write fixture rows without FFCRM_LEGACY_FIXTURE_CONFIRM=1" unless ENV["FFCRM_LEGACY_FIXTURE_CONFIRM"] == "1"

    abort "Refusing non-PostgreSQL database #{ActiveRecord::Base.connection.adapter_name.inspect}" unless ActiveRecord::Base.connection.adapter_name.casecmp("PostgreSQL").zero?

    fixture_path = Rails.root.join(
      ENV.fetch("FFCRM_LEGACY_FIXTURE_OUTPUT", "spring/src/test/resources/fixtures/rails-legacy-users.json")
    )
    passwords = [
      {
        username: "legacy_admin",
        email: "legacy_admin@example.com",
        password: "CorrectHorse!42",
        admin: true
      },
      {
        username: "legacy_user",
        email: "legacy_user@example.com",
        password: "p@ssw0rd-simple",
        admin: false
      }
    ]

    fixture = nil
    User.transaction(requires_new: true) do
      users = passwords.map do |attributes|
        User.create!(
          username: attributes[:username],
          email: attributes[:email],
          password: attributes[:password],
          password_confirmation: attributes[:password],
          admin: attributes[:admin]
        )
      end

      persisted_users = users.map { |user| User.find(user.id) }
      fixture = {
        provenance: {
          generated_at: Time.current.utc.iso8601(3),
          rails_env: Rails.env.to_s,
          database: database_name,
          source: "persisted User rows created with User.create!",
          transaction: "rolled_back_after_reading_rows"
        },
        devise: {
          encryptor: Devise.encryptor.to_s,
          stretches: Devise.stretches,
          pepper: Devise.pepper,
          devise_version: Gem.loaded_specs.fetch("devise").version.to_s,
          devise_encryptable_version: Gem.loaded_specs.fetch("devise-encryptable").version.to_s
        },
        users: persisted_users.zip(passwords).map do |user, attributes|
          {
            username: user.username,
            email: user.email,
            admin: user.admin,
            password: attributes[:password],
            encrypted_password: user.encrypted_password,
            password_salt: user.password_salt
          }
        end
      }

      raise ActiveRecord::Rollback
    end

    FileUtils.mkdir_p(fixture_path.dirname)
    fixture_path.write(JSON.pretty_generate(fixture) + "\n")
    puts "Wrote #{fixture_path}"
    puts "Generated from rolled-back persisted User rows in #{database_name} (#{Rails.env})"
  end
end
