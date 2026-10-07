# frozen_string_literal: true

require "spec_helper"
require "yaml"
require_relative "../../db/contract_fixtures"

# Pins the data-integrity and guard-rail behaviour of the AB-266 contract fixture
# loader that spec/db/contract_fixtures_spec.rb does not cover: the reset guard,
# idempotent reloads, deterministic timestamps and the exact relationship rows the
# Java contract harness (spring/src/contractTest) relies on.
RSpec.describe ContractFixtures, :truncate do
  def load_with_reset!
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  def load_without_reset!
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV.delete("CONTRACT_FIXTURES_RESET")
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  describe ".load! guard rails" do
    it "aborts without touching the database when users already exist and no reset was requested" do
      existing = create(:user)

      expect { load_without_reset! }.to raise_error(SystemExit).and output(/CONTRACT_FIXTURES_RESET=1/).to_stderr

      expect(User.count).to eq(1)
      expect(User.find(existing.id)).to eq(existing)
      expect(Account.count).to eq(0)
      expect(Setting.find_by(name: "secret_token")).to be_nil
    end

    it "loads into an empty database without a reset flag" do
      expect { load_without_reset! }.not_to raise_error

      expect(User.count).to eq(5)
      expect(Account.count).to eq(7)
    end

    it "replaces pre-existing rows when a reset is requested" do
      create(:user)
      create(:account, name: "Stale account")
      Setting.secret_token = "stale-secret"

      load_with_reset!

      expect(User.count).to eq(5)
      expect(Account.where(name: "Stale account")).not_to exist
      expect(Account.count).to eq(7)
      expect(Setting.where(name: "secret_token").count).to eq(1)
      expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
    end

    it "overwrites an existing secret_token setting instead of inserting a duplicate" do
      stale_time = Time.utc(2020, 1, 1)
      Setting.insert_all!([{ name: "secret_token", value: "stale", created_at: stale_time, updated_at: stale_time }])

      load_without_reset!

      settings = Setting.where(name: "secret_token")
      expect(settings.count).to eq(1)
      expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
      expect(settings.first.created_at).to eq(ContractFixtures::BASE_TIME + 30)
      expect(settings.first.updated_at).to eq(ContractFixtures::BASE_TIME + 30)
    end

    it "is idempotent across repeated resets" do
      load_with_reset!
      first_snapshot = snapshot

      load_with_reset!

      expect(snapshot).to eq(first_snapshot)
    end

    # Rows whose ids are assigned by the database sequence are compared without the id column: PostgreSQL
    # restarts identities on TRUNCATE, but SQLite's DELETE path (used by this test database) does not.
    def snapshot
      [User, Group, Setting, Account, Contact, Lead, Opportunity, Campaign, Task, Tag, Permission, Comment, Address,
       Tagging, AccountContact, AccountOpportunity, ContactOpportunity].to_h do |model|
        columns = model.column_names - (if model.column_names.include?("asset_type") || model.name == "Setting" ||
                                        model.name.in?(%w[Comment Address Tagging AccountContact AccountOpportunity
                                                          ContactOpportunity])
                                          ["id"]
                                        else
                                          []
                                        end)
        [model.name, model.order(*columns.map(&:to_sym)).pluck(*columns.map(&:to_sym))]
      end
    end
  end

  describe "the seeded corpus" do
    before { load_with_reset! }

    let(:users_yml) do
      YAML.safe_load_file(
        Rails.root.join("spring/src/contractTest/resources/contract/users.yml")
      ).fetch("users")
    end

    it "mirrors users.yml ids, names and flags so the Java FixtureUsers loader sees the same users" do
      expect(User.pluck(:id).sort).to eq(users_yml.values.map { |attributes| attributes.fetch("id") }.sort)

      users_yml.each do |key, attributes|
        user = User.find(attributes.fetch("id"))
        expect(user.username).to eq(attributes.fetch("username"))
        expect(user.email).to eq(attributes.fetch("email"))
        expect(user.admin).to eq(attributes.fetch("admin", false))
        expect(user.suspended_at.present?).to eq(attributes.fetch("suspended", false))
        expect(user.first_name).to eq(key == "admin" ? "Contract" : key.capitalize)
        expect(user.last_name).to eq("Fixture")
      end
    end

    it "seeds exactly one admin and one suspended user with zero sign-ins" do
      expect(User.where(admin: true).pluck(:id)).to eq([1])
      expect(User.where.not(suspended_at: nil).pluck(:id)).to eq([4])
      expect(User.find(4).suspended_at).to eq(ContractFixtures::BASE_TIME + 4)
      expect(User.distinct.pluck(:sign_in_count)).to eq([0])
      expect(User.distinct.pluck(:unconfirmed_email)).to eq([""])
    end

    it "assigns group memberships exactly as the visibility matrix assumes" do
      sales = Group.find(1)
      support = Group.find(2)

      expect(sales.name).to eq("Sales")
      expect(support.name).to eq("Support")
      expect(sales.users.pluck(:id).sort).to eq([2, 4])
      expect(support.users.pluck(:id).sort).to eq([3])
      expect(User.find(5).group_ids).to be_empty
      expect(User.find(1).group_ids).to be_empty
    end

    it "seeds the same owner/assignee/access pattern for every entity type" do
      expected_pattern = [
        [2, nil, "Public"],
        [2, nil, "Private"],
        [2, nil, "Shared"],
        [2, nil, "Shared"],
        [2, 3, "Private"],
        [3, nil, "Private"],
        [1, nil, "Private"]
      ]

      { Account => 100, Contact => 200, Lead => 300, Opportunity => 400, Campaign => 500 }.each do |model, base|
        rows = model.order(:id).pluck(:id, :user_id, :assigned_to, :access, :deleted_at)
        expect(rows.map(&:first)).to eq(((base + 1)..(base + 7)).to_a)
        expect(rows.map { |row| row[1..3] }).to eq(expected_pattern)
        expect(rows.map(&:last)).to all(be_nil)
      end
    end

    it "shares the third entity with Bob directly and the fourth with the Support group" do
      { "Account" => 100, "Contact" => 200, "Lead" => 300, "Opportunity" => 400, "Campaign" => 500 }.each do |type, base|
        permissions = Permission.where(asset_type: type).order(:asset_id)
        expect(permissions.pluck(:asset_id, :user_id, :group_id)).to eq([
                                                                          [base + 3, 3, nil],
                                                                          [base + 4, nil, 2]
                                                                        ])
      end

      expect(Permission.where(asset_type: "Task")).not_to exist
      expect(Permission.count).to eq(10)
    end

    it "wires account associations and keeps counter caches consistent" do
      public_account = Account.find(101)
      expect(public_account.contacts_count).to eq(1)
      expect(public_account.opportunities_count).to eq(1)
      expect(public_account.contacts.pluck(:id)).to eq([201])
      expect(public_account.opportunities.pluck(:id)).to eq([401])
      expect(Account.where.not(id: 101).pluck(:contacts_count, :opportunities_count).flatten).to all(eq(0))
      expect(ContactOpportunity.find_by(contact_id: 201, opportunity_id: 401).role).to eq("Decision maker")
      expect(Contact.find(201).opportunities.pluck(:id)).to eq([401])
    end

    it "wires lead and campaign associations and keeps counter caches consistent" do
      expect(Contact.find(201).lead_id).to eq(301)
      expect(Contact.where.not(id: 201).pluck(:lead_id)).to all(be_nil)
      expect(Lead.find(301).campaign_id).to eq(501)
      expect(Lead.where.not(id: 301).pluck(:campaign_id)).to all(be_nil)
      expect(Campaign.find(501).leads_count).to eq(1)
      expect(Campaign.where.not(id: 501).pluck(:leads_count)).to all(eq(0))
      expect(Opportunity.distinct.pluck(:stage)).to eq(["prospecting"])
    end

    it "seeds the task corpus with owner, assignee, completion and asset links" do
      expect(Task.order(:id).pluck(:id, :user_id, :assigned_to, :completed_by, :asset_type, :asset_id)).to eq([
                                                                                                                [601, 2, nil, nil, nil, nil],
                                                                                                                [602, 2, 3, nil, nil, nil],
                                                                                                                [603, 5, nil, 3, nil, nil],
                                                                                                                [604, 2, nil, nil, "Account", 101],
                                                                                                                [605, 1, nil, nil, nil, nil]
                                                                                                              ])
      expect(Task.distinct.pluck(:bucket)).to eq(["due_later"])
      expect(Task.find(603).completed_at).to eq(ContractFixtures::BASE_TIME + 603)
      expect(Task.where.not(id: 603).pluck(:completed_at)).to all(be_nil)
      expect(Task.find(604).asset).to eq(Account.find(101))
    end

    it "attaches comments, address and tags to the documented records" do
      account_comment = Comment.find_by(commentable_type: "Account", commentable_id: 101)
      contact_comment = Comment.find_by(commentable_type: "Contact", commentable_id: 203)
      expect(account_comment.user_id).to eq(2)
      expect(account_comment.private).to be(false)
      expect(contact_comment.user_id).to eq(3)
      expect(Account.find(101).comments).to eq([account_comment])
      expect(Contact.find(203).comments).to eq([contact_comment])

      address = Account.find(101).billing_address
      expect(address.attributes.slice("street1", "city", "state", "zipcode", "country", "address_type")).to eq(
        "street1" => "1 Contract Way", "city" => "Portland", "state" => "OR", "zipcode" => "97201",
        "country" => "US", "address_type" => "Billing"
      )

      expect(Tagging.order(:id).pluck(:tag_id, :taggable_type, :taggable_id, :tagger_type, :tagger_id, :context)).to eq([
                                                                                                                          [1, "Account", 101, "User", 2, "tags"],
                                                                                                                          [2, "Contact", 201, "User", 3, "tags"]
                                                                                                                        ])
      expect(Account.find(101).all_tags_list).to eq(["contract-public"])
      expect(Contact.find(201).all_tags_list).to eq(["contract-contact"])
      expect(Account.where.not(id: 101).map(&:all_tags_list).flatten).to be_empty
      expect(Tag.order(:id).pluck(:name, :taggings_count)).to eq([["contract-public", 1], ["contract-contact", 1]])
    end

    it "uses deterministic timestamps derived from BASE_TIME for users, groups, settings and entities" do
      base = ContractFixtures::BASE_TIME
      expect(base).to eq(Time.utc(2026, 1, 1, 9))

      expect(User.find(2).created_at).to eq(base + 2)
      expect(User.find(2).confirmed_at).to eq(base + 2)
      expect(Group.find(1).created_at).to eq(base + 20)
      expect(Group.find(2).updated_at).to eq(base + 21)
      expect(Setting.find_by(name: "secret_token").created_at).to eq(base + 30)
      expect(Account.find(101).created_at).to eq(base + 201)
      expect(Campaign.find(507).updated_at).to eq(base + 607)
      expect(Task.find(601).created_at).to eq(base + 1301)
    end

    it "uses deterministic timestamps derived from BASE_TIME for permissions, joins, tags and metadata" do
      base = ContractFixtures::BASE_TIME

      expect(Permission.order(:id).first.created_at).to eq(base + 900)
      expect(AccountContact.first.created_at).to eq(base + 1000)
      expect(Tagging.order(:id).pluck(:created_at)).to eq([base + 1030, base + 1031])

      metadata = ActiveRecord::Base.connection.select_one("SELECT created_at, updated_at FROM ar_internal_metadata")
      expect(Time.zone.parse(metadata["created_at"].to_s)).to eq(base)
      expect(Time.zone.parse(metadata["updated_at"].to_s)).to eq(base)
    end

    # Campaign is deliberately excluded: the fixtures seed status "active", which is not in
    # Setting.campaign_status, so Campaign#valid? is false for every fixture row (reported separately).
    it "leaves every seeded record valid under the Rails model validations" do
      [User, Account, Contact, Lead, Opportunity, Task, Comment, Address, Permission, Group].each do |model|
        invalid = model.all.reject(&:valid?)
        expect(invalid).to be_empty, "#{model.name} rows failed validation: " \
                                     "#{invalid.map { |record| [record.id, record.errors.full_messages] }}"
      end
    end

    it "lets new records be created after the fixtures without colliding with fixture ids" do
      account = Account.create!(name: "Post-fixture account", user: User.find(2), access: "Public")
      user = User.create!(username: "post-fixture", email: "post-fixture@contract.example",
                          password: "contract-password", password_confirmation: "contract-password")
      task = Task.create!(name: "Post-fixture task", user: User.find(2), bucket: "due_later")

      expect(account.id).to be > 107
      expect(user.id).to be > 5
      expect(task.id).to be > 605
    end

    it "authenticates every fixture user with Devise and reports the suspended one as inactive" do
      users_yml.each_value do |attributes|
        user = User.find(attributes.fetch("id"))
        expect(user.valid_password?(attributes.fetch("password"))).to be(true)
        expect(user.valid_password?("wrong-password")).to be(false)
        expect(user.active_for_authentication?).to eq(!attributes.fetch("suspended", false))
      end
    end
  end
end
