# frozen_string_literal: true

require "spec_helper"
require "yaml"
require_relative "../../db/contract_fixtures"

RSpec.describe ContractFixtures, :truncate do
  let(:users_yml) do
    YAML.safe_load_file(
      Rails.root.join("spring/src/contractTest/resources/contract/users.yml")
    ).fetch("users")
  end

  before do
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  it "loads the fixture users and groups" do
    expect(User.count).to eq(9)
    expect(Group.count).to eq(2)
    expect(Setting.count).to eq(1)
    expect(Setting.secret_token).to eq(ContractFixtures::SECRET_TOKEN)
  end

  it "loads the entity corpus" do
    expect(Account.count).to eq(7)
    expect(Contact.count).to eq(7)
    expect(Lead.count).to eq(7)
    expect(Opportunity.count).to eq(7)
    expect(Campaign.count).to eq(7)
    expect(Task.count).to eq(5)
  end

  it "loads the relationship corpus" do
    expect(Permission.count).to eq(10)
    expect(AccountContact.count).to eq(1)
    expect(AccountOpportunity.count).to eq(1)
    expect(ContactOpportunity.count).to eq(1)
    expect(Comment.count).to eq(2)
    expect(Address.count).to eq(1)
    expect(Tag.count).to eq(2)
    expect(Tagging.count).to eq(2)
  end

  it "connects fixture relationships" do
    expect(AccountContact.where(account_id: 101, contact_id: 201)).to exist
    expect(AccountOpportunity.where(account_id: 101, opportunity_id: 401)).to exist
    expect(ContactOpportunity.where(contact_id: 201, opportunity_id: 401)).to exist
    expect(Comment.where(commentable_type: "Account", commentable_id: 101)).to exist
    expect(Comment.where(commentable_type: "Contact", commentable_id: 203)).to exist
    expect(Account.find(101).billing_address).to be_present
  end

  it "matches the documented entity visibility matrix" do
    alice = User.find(2)
    bob = User.find(3)
    admin = User.find(1)
    expected = {
      Account => { alice: (101..105).to_a, bob: [101, 103, 104, 105, 106], admin: (101..107).to_a },
      Contact => { alice: (201..205).to_a, bob: [201, 203, 204, 205, 206], admin: (201..207).to_a },
      Lead => { alice: (301..305).to_a, bob: [301, 303, 304, 305, 306], admin: (301..307).to_a },
      Opportunity => { alice: (401..405).to_a, bob: [401, 403, 404, 405, 406], admin: (401..407).to_a },
      Campaign => { alice: (501..505).to_a, bob: [501, 503, 504, 505, 506], admin: (501..507).to_a }
    }

    expected.each do |model, ids|
      expect(model.my(alice).pluck(:id).sort).to eq(ids.fetch(:alice))
      expect(model.my(bob).pluck(:id).sort).to eq(ids.fetch(:bob))
      expect(model.my(admin).pluck(:id).sort).to eq(ids.fetch(:admin))
    end

    expect(Task.accessible_by(alice.ability).pluck(:id).sort).to eq([601, 602, 604])
    expect(Task.accessible_by(bob.ability).pluck(:id).sort).to eq([602, 603])
    expect(Task.accessible_by(admin.ability).pluck(:id).sort).to eq((601..605).to_a)
  end

  it "keeps users confirmed, rejects the suspended fixture and validates every fixture password" do
    sam = User.find(4)
    expect(sam).to be_confirmed
    expect(sam).not_to be_active_for_authentication

    users_yml.each_value do |attributes|
      user = User.find(attributes.fetch("id"))
      expect(user).to be_confirmed
      expect(user.password_salt).to eq("#{ContractFixtures::PASSWORD_SALT_PREFIX}#{user.id}")
      expect(user.valid_password?(attributes.fetch("password"))).to be(true)
    end
  end
end
