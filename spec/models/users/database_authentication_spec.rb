# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require File.expand_path(File.dirname(__FILE__) + '/../../spec_helper')
require 'json'

# Pins the Rails authentication rules that the Spring Boot port (AB-264) must reproduce:
# login lookup, active-for-authentication checks, and the committed legacy password fixture.
describe "User database authentication parity" do # rubocop:disable RSpec/DescribeClass
  describe ".find_for_database_authentication" do
    let!(:user) { create(:user, username: "Parity_User", email: "Parity.User@Example.COM") }

    it "matches the username case-insensitively" do
      expect(User.find_for_database_authentication(email: "parity_user")).to eq(user)
      expect(User.find_for_database_authentication(email: "PARITY_USER")).to eq(user)
    end

    it "matches the email case-insensitively" do
      expect(User.find_for_database_authentication(email: "PARITY.USER@EXAMPLE.COM")).to eq(user)
    end

    it "does not strip whitespace at the model level (Devise strips it before the lookup)" do
      expect(User.find_for_database_authentication(email: " parity_user ")).to be_nil
    end

    it "requires an exact match rather than a prefix or wildcard" do
      expect(User.find_for_database_authentication(email: "parity_use")).to be_nil
      expect(User.find_for_database_authentication(email: "parity_user%")).to be_nil
      expect(User.find_for_database_authentication(email: "%")).to be_nil
    end

    it "returns nil when no login is given" do
      expect(User.find_for_database_authentication({})).to be_nil
      expect(User.find_for_database_authentication(username: "parity_user")).to be_nil
    end

    it "prefers the lowest id when one row matches by username and another by email" do
      other = create(:user, username: "someone_else", email: "parity_user")

      expect(user.id).to be < other.id
      expect(User.find_for_database_authentication(email: "parity_user")).to eq(user)
    end

    it "still finds suspended and unconfirmed users, leaving the rejection to active_for_authentication?" do
      user.update_columns(suspended_at: Time.current, confirmed_at: nil)

      expect(User.find_for_database_authentication(email: "parity_user")).to eq(user)
    end

    it "honours additional warden conditions" do
      expect(User.find_for_database_authentication(email: "parity_user", admin: true)).to be_nil
      expect(User.find_for_database_authentication(email: "parity_user", admin: false)).to eq(user)
    end
  end

  describe "#active_for_authentication?" do
    let(:user) { create(:user) }

    it "is true for a confirmed, unsuspended user" do
      expect(user.active_for_authentication?).to be(true)
    end

    it "is false once the user is suspended" do
      user.update_columns(suspended_at: Time.current)
      expect(user.active_for_authentication?).to be(false)
    end

    it "is false while the user is unconfirmed" do
      user.update_columns(confirmed_at: nil)
      expect(user.active_for_authentication?).to be(false)
    end

    it "is false for a suspended admin too" do
      user.update_columns(admin: true, suspended_at: Time.current)
      expect(user.active_for_authentication?).to be(false)
    end
  end

  describe "#valid_password?" do
    let(:user) { create(:user, password: "Correct Horse", password_confirmation: "Correct Horse") }

    it "uses authlogic_sha512 with a per-user salt" do
      expect(Devise.encryptor).to eq(:authlogic_sha512)
      expect(user.password_salt).to be_present
      expect(user.encrypted_password).to match(/\A[0-9a-f]{128}\z/)
    end

    it "is exact about case, whitespace and emptiness" do
      expect(user.valid_password?("Correct Horse")).to be(true)
      expect(user.valid_password?("correct horse")).to be(false)
      expect(user.valid_password?(" Correct Horse")).to be(false)
      expect(user.valid_password?("Correct Horse ")).to be(false)
      expect(user.valid_password?("")).to be(false)
      expect(user.valid_password?(nil)).to be(false)
    end

    it "fails when the stored hash is missing" do
      user.update_columns(encrypted_password: "")
      expect(user.reload.valid_password?("Correct Horse")).to be(false)
    end
  end

  describe "committed Spring fixture" do
    let(:fixture_path) { Rails.root.join("spring/src/test/resources/auth/rails-legacy-users.json") }
    let(:fixture) { JSON.parse(File.read(fixture_path, encoding: "UTF-8")) }

    it "was produced by the Rails task with production stretches" do
      expect(fixture).to include(
        "generated_by" => "ffcrm:migration:legacy_auth_fixture",
        "encryptor" => Devise.encryptor.to_s,
        "stretches" => 20
      )
    end

    it "still verifies with Rails' own password check at the recorded stretch count" do
      allow(User).to receive(:stretches).and_return(fixture.fetch("stretches"))

      fixture.fetch("users").each do |attributes|
        user = User.new(
          encrypted_password: attributes.fetch("encrypted_password"),
          password_salt: attributes.fetch("password_salt")
        )
        expect(user.valid_password?(attributes.fetch("password")))
          .to be(true), "Rails rejected the fixture password for #{attributes.fetch('username')}"
        expect(user.valid_password?(attributes.fetch("password").swapcase)).to be(false)
      end
    end

    it "covers every scenario the Spring tests depend on" do
      users = fixture.fetch("users").index_by { |attributes| attributes.fetch("username") }

      expect(users.keys).to contain_exactly(
        "legacy_plain", "legacy_admin", "Legacy_MixedCase", "legacy_unicode", "legacy_long",
        "legacy_dollar", "legacy_spaces", "legacy_suspended", "legacy_unconfirmed"
      )
      expect(users.fetch("legacy_admin")).to include("admin" => true, "confirmed" => true, "suspended" => false)
      expect(users.fetch("legacy_suspended")).to include("suspended" => true, "confirmed" => true)
      expect(users.fetch("legacy_unconfirmed")).to include("confirmed" => false, "suspended" => false)
      expect(users.fetch("Legacy_MixedCase").fetch("email")).to eq("legacy.mixed@example.com")
      expect(users.fetch("legacy_spaces").fetch("password")).to eq(" leading and trailing ")
      expect(users.fetch("legacy_long").fetch("password").length).to eq(225)
    end

    it "stores Rails emails downcased because Devise treats :email as case-insensitive" do
      fixture.fetch("users").each do |attributes|
        expect(attributes.fetch("email")).to eq(attributes.fetch("email").downcase)
      end
    end
  end
end
