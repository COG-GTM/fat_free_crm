# frozen_string_literal: true

require "fileutils"
require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Generate Rails-created legacy password rows for Spring authentication tests"
    task legacy_auth_fixture: :environment do
      scenarios = [
        { username: "legacy_plain", password: "PlainAsciiPassword42", admin: false },
        { username: "legacy_admin", password: "AdminPassword42", admin: true },
        { username: "Legacy_MixedCase", password: "MixedCasePassword42", email: "Legacy.Mixed@Example.COM" },
        { username: "legacy_unicode", password: "pässwörd-日本語-🔐" },
        { username: "legacy_long", password: "long-" * 45 },
        { username: "legacy_dollar", password: "pa$$w0rd$" },
        { username: "legacy_spaces", password: " leading and trailing " },
        { username: "legacy_suspended", password: "SuspendedPassword42", suspended: true },
        { username: "legacy_unconfirmed", password: "UnconfirmedPassword42", confirmed: false }
      ]

      fixture_users = nil
      ActiveRecord::Base.transaction do
        fixture_users = scenarios.map do |scenario|
          email_name = scenario[:username].downcase
          user = User.new(
            username: scenario[:username],
            email: scenario[:email] || "#{email_name}@example.com",
            first_name: "Legacy",
            last_name: scenario[:username],
            admin: scenario.fetch(:admin, false),
            password: scenario[:password],
            password_confirmation: scenario[:password]
          )
          if scenario.fetch(:confirmed, true)
            user.skip_confirmation!
          else
            user.skip_confirmation_notification!
          end
          user.suspended_at = Time.current if scenario[:suspended]
          user.save!
          user.update_column(:suspended_at, nil) unless scenario[:suspended]
          user.reload
          raise "Rails could not verify password for #{user.username}" unless user.valid_password?(scenario[:password])

          {
            username: user.username,
            email: user.email,
            password: scenario[:password],
            encrypted_password: user.encrypted_password,
            password_salt: user.password_salt,
            admin: user.admin,
            confirmed: user.confirmed_at.present?,
            suspended: user.suspended_at.present?,
            first_name: user.first_name,
            last_name: user.last_name
          }
        end
        raise ActiveRecord::Rollback
      end

      output = Rails.root.join(
        ENV.fetch("OUTPUT", "spring/src/test/resources/auth/rails-legacy-users.json")
      )
      fixture = {
        generated_by: "ffcrm:migration:legacy_auth_fixture",
        rails_env: Rails.env.to_s,
        encryptor: Devise.encryptor.to_s,
        stretches: User.stretches,
        users: fixture_users
      }
      FileUtils.mkdir_p(output.dirname)
      File.write(output, "#{JSON.pretty_generate(fixture)}\n", encoding: "UTF-8")
      puts "Wrote #{output}"
      puts "encryptor=#{fixture[:encryptor]} stretches=#{fixture[:stretches]}"
    end
  end
end
