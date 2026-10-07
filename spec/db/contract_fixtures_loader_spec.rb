# frozen_string_literal: true

require "spec_helper"
require "yaml"
require_relative "../../db/contract_fixtures"

RSpec.describe ContractFixtures, :truncate do
  def load_fixtures!(reset: true)
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    if reset
      ENV["CONTRACT_FIXTURES_RESET"] = "1"
    else
      ENV.delete("CONTRACT_FIXTURES_RESET")
    end
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  let(:base_time) { ContractFixtures::BASE_TIME }

  describe "guard rails" do
    it "refuses to load over an existing user corpus unless CONTRACT_FIXTURES_RESET=1" do
      load_fixtures!
      Account.find(101).update_columns(name: "Mutated by test")

      expect do
        expect { load_fixtures!(reset: false) }.to raise_error(SystemExit)
      end.to output(/refuse to load.*CONTRACT_FIXTURES_RESET=1/).to_stderr

      expect(User.count).to eq(5)
      expect(Account.count).to eq(7)
      expect(Account.find(101).name).to eq("Mutated by test")
    end

    it "loads into an empty database without the reset flag" do
      load_fixtures!(reset: false)

      expect(User.count).to eq(5)
      expect(Account.count).to eq(7)
    end

    it "wipes and reloads an existing corpus when CONTRACT_FIXTURES_RESET=1" do
      load_fixtures!
      Account.find(101).update_columns(name: "Mutated by test")
      Account.create!(name: "Extra account", user_id: 2, access: "Public")
      Comment.create!(user_id: 2, commentable: Account.find(102), comment: "extra", title: "extra", state: "Expanded")

      load_fixtures!

      expect(Account.count).to eq(7)
      expect(Account.find(101).name).to eq("Contract Account 101")
      expect(Account.where(name: "Extra account")).not_to exist
      expect(Comment.count).to eq(2)
      expect(User.count).to eq(5)
    end

    it "updates an existing secret_token setting instead of inserting a duplicate" do
      Setting.insert_all!([{ name: "secret_token", value: "stale-token",
                             created_at: Time.current, updated_at: Time.current }])
      Setting.clear_cache!

      load_fixtures!(reset: false)

      expect(Setting.where(name: "secret_token").count).to eq(1)
      expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
      expect(Setting.find_by(name: "secret_token").updated_at).to eq(base_time + 30)
    end
  end

  describe "determinism" do
    before { load_fixtures! }

    it "assigns timestamps derived from BASE_TIME and the record id" do
      expect(User.find(1).created_at).to eq(base_time + 1)
      expect(User.find(5).confirmed_at).to eq(base_time + 5)
      expect(User.find(4).suspended_at).to eq(base_time + 4)
      expect(Group.find(1).created_at).to eq(base_time + 20)
      expect(Group.find(2).created_at).to eq(base_time + 21)
      expect(Account.find(101).created_at).to eq(base_time + 201)
      expect(Campaign.find(507).updated_at).to eq(base_time + 607)
      expect(Task.find(601).created_at).to eq(base_time + 1301)
      expect(Task.find(603).completed_at).to eq(base_time + 603)
      expect(Permission.order(:id).first.created_at).to eq(base_time + 900)
      expect(AccountContact.first.created_at).to eq(base_time + 1000)
    end

    it "produces an identical corpus when loaded twice" do
      snapshot = lambda do
        [Account, Contact, Lead, Opportunity, Campaign, Task, Permission, Comment, Address, Tag, Tagging].map do |model|
          model.order(:id).map { |record| record.attributes.except("id") }
        end
      end
      first = snapshot.call

      load_fixtures!

      expect(snapshot.call).to eq(first)
    end

    it "leaves id sequences positioned after the fixture ids" do
      new_user = User.create!(username: "after-fixtures", email: "after@contract.example",
                              password: "contract-password", password_confirmation: "contract-password")
      new_account = Account.create!(name: "After fixtures", user_id: 2, access: "Public")
      new_contact = Contact.create!(first_name: "After", last_name: "Fixtures", user_id: 2, access: "Public")
      new_task = Task.create!(name: "After fixtures", user_id: 2, bucket: "due_later")
      new_tag = Tag.create!(name: "after-fixtures")
      new_group = Group.create!(name: "After fixtures")

      expect(new_user.id).to be > 5
      expect(new_account.id).to be > 107
      expect(new_contact.id).to be > 207
      expect(new_task.id).to be > 605
      expect(new_tag.id).to be > 2
      expect(new_group.id).to be > 2
    end
  end

  describe "user corpus" do
    before { load_fixtures! }

    let(:users_yml) do
      YAML.safe_load_file(
        Rails.root.join("spring/src/contractTest/resources/contract/users.yml")
      ).fetch("users")
    end

    it "mirrors contract/users.yml for ids, usernames, emails and admin flags" do
      expect(users_yml.keys).to eq(%w[admin alice bob sam carol])
      users_yml.each do |key, attributes|
        user = User.find(attributes.fetch("id"))
        expect(user.username).to eq(attributes.fetch("username"))
        expect(user.email).to eq(attributes.fetch("email"))
        expect(user.admin).to be(attributes.fetch("admin", false))
        expect(user.suspended_at.present?).to be(attributes.fetch("suspended", false))
        expect(user.first_name).to eq(key == "admin" ? "Contract" : key.capitalize)
        expect(user.last_name).to eq("Fixture")
        expect(user.sign_in_count).to eq(0)
      end
      expect(User.where(admin: true).pluck(:id)).to eq([1])
    end

    it "places alice and sam in Sales and bob in Support" do
      expect(Group.find(1).name).to eq("Sales")
      expect(Group.find(2).name).to eq("Support")
      expect(User.find(2).groups.pluck(:name)).to eq(["Sales"])
      expect(User.find(4).groups.pluck(:name)).to eq(["Sales"])
      expect(User.find(3).groups.pluck(:name)).to eq(["Support"])
      expect(User.find(1).groups).to be_empty
      expect(User.find(5).groups).to be_empty
    end
  end

  describe "entity corpus" do
    before { load_fixtures! }

    let(:entity_models) { [Account, Contact, Lead, Opportunity, Campaign] }

    it "repeats the documented access pattern for every entity range" do
      expected = [
        { user_id: 2, assigned_to: nil, access: "Public" },
        { user_id: 2, assigned_to: nil, access: "Private" },
        { user_id: 2, assigned_to: nil, access: "Shared" },
        { user_id: 2, assigned_to: nil, access: "Shared" },
        { user_id: 2, assigned_to: 3, access: "Private" },
        { user_id: 3, assigned_to: nil, access: "Private" },
        { user_id: 1, assigned_to: nil, access: "Private" }
      ]
      { Account => 100, Contact => 200, Lead => 300, Opportunity => 400, Campaign => 500 }.each do |model, base|
        rows = model.order(:id).map { |record| record.attributes.symbolize_keys.slice(:user_id, :assigned_to, :access) }
        expect(model.order(:id).pluck(:id)).to eq(((base + 1)..(base + 7)).to_a)
        expect(rows).to eq(expected)
        expect(model.where.not(deleted_at: nil)).not_to exist
      end
    end

    it "shares suffix 3 with bob directly and suffix 4 with the Support group" do
      { "Account" => 100, "Contact" => 200, "Lead" => 300, "Opportunity" => 400, "Campaign" => 500 }.each do |type, base|
        user_share = Permission.where(asset_type: type, asset_id: base + 3)
        group_share = Permission.where(asset_type: type, asset_id: base + 4)
        expect(user_share.pluck(:user_id, :group_id)).to eq([[3, nil]])
        expect(group_share.pluck(:user_id, :group_id)).to eq([[nil, 2]])
        expect(Permission.where(asset_type: type).where.not(asset_id: [base + 3, base + 4])).not_to exist
      end
    end

    it "keeps counter caches consistent with the seeded associations" do
      account = Account.find(101)
      expect(account.contacts_count).to eq(account.contacts.count)
      expect(account.opportunities_count).to eq(account.opportunities.count)
      expect(account.contacts.pluck(:id)).to eq([201])
      expect(account.opportunities.pluck(:id)).to eq([401])
      expect(Account.where.not(id: 101).pluck(:contacts_count, :opportunities_count).uniq).to eq([[0, 0]])

      campaign = Campaign.find(501)
      expect(campaign.leads_count).to eq(campaign.leads.count)
      expect(campaign.leads.pluck(:id)).to eq([301])
      expect(Campaign.where.not(id: 501).pluck(:leads_count).uniq).to eq([0])
      expect(Lead.find(301).campaign).to eq(campaign)
      expect(Lead.where.not(id: 301).where.not(campaign_id: nil)).not_to exist
    end

    it "links lead 301 to contact 201 and opportunity 401 to account 101 and contact 201" do
      contact = Contact.find(201)
      expect(contact.lead).to eq(Lead.find(301))
      expect(Lead.find(301).contact).to eq(contact)
      expect(contact.opportunities.pluck(:id)).to eq([401])
      expect(ContactOpportunity.find_by(contact_id: 201, opportunity_id: 401).role).to eq("Decision maker")
      expect(Opportunity.find(401).account).to eq(Account.find(101))
      expect(Opportunity.distinct.pluck(:stage)).to eq(["prospecting"])
      expect(Campaign.distinct.pluck(:status)).to eq(["active"])
    end

    it "attaches comments, tags, address and tasks to the documented records" do
      expect(Account.find(101).comments.pluck(:user_id, :title)).to eq([[2, "Public account"]])
      expect(Contact.find(203).comments.pluck(:user_id, :title)).to eq([[3, "Shared contact"]])
      expect(Account.find(101).all_tags_list).to eq(["contract-public"])
      expect(Contact.find(201).all_tags_list).to eq(["contract-contact"])
      expect(Tagging.pluck(:taggable_type, :taggable_id, :tagger_id, :context)).to contain_exactly(
        ["Account", 101, 2, "tags"], ["Contact", 201, 3, "tags"]
      )
      expect(Account.find(101).billing_address.city).to eq("Portland")
      expect(Account.find(101).shipping_address).to be_nil
      expect(Account.find(101).tasks.pluck(:id)).to eq([604])
    end

    it "seeds tasks for alice, bob, carol and admin with one assigned, one completed and one attached" do
      tasks = Task.order(:id).pluck(:id, :user_id, :assigned_to, :completed_by, :asset_type, :asset_id)
      expect(tasks).to eq([
                            [601, 2, nil, nil, nil, nil],
                            [602, 2, 3, nil, nil, nil],
                            [603, 5, nil, 3, nil, nil],
                            [604, 2, nil, nil, "Account", 101],
                            [605, 1, nil, nil, nil, nil]
                          ])
      expect(Task.find(603).completed_at).to be_present
      expect(Task.where.not(id: 603).where.not(completed_at: nil)).not_to exist
      expect(Task.distinct.pluck(:bucket)).to eq(["due_later"])
    end

    it "inserts rows that still satisfy the Rails model validations" do
      (entity_models - [Campaign] + [Task, User, Group, Comment, Address, Permission]).each do |model|
        model.find_each do |record|
          expect(record).to be_valid, -> { "#{model.name}##{record.id} invalid: #{record.errors.to_hash}" }
        end
      end
    end

    it "seeds campaigns with a status Rails itself would accept" do
      pending "fixture campaigns use status 'active', which is not in Setting.campaign_status " \
              "(#{Setting.unroll(:campaign_status).map(&:last).join(', ')})"
      Campaign.find_each do |campaign|
        expect(campaign).to be_valid, -> { "Campaign##{campaign.id} invalid: #{campaign.errors.to_hash}" }
      end
    end
  end
end
