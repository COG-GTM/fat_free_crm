# frozen_string_literal: true

require "spec_helper"
require_relative "../../db/contract_fixtures"

RSpec.describe ContractFixtures, :truncate do
  before do
    Rack::Attack.cache.store = ActiveSupport::Cache::MemoryStore.new
    previous_reset = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
    ENV["CONTRACT_FIXTURES_RESET"] = "1"
    ContractFixtures.load!
  ensure
    ENV["CONTRACT_FIXTURES_RESET"] = previous_reset
  end

  def sign_in_as(email, password: "contract-password")
    post user_session_path, params: { user: { email: email, password: password } }
    response
  end

  def with_forgery_protection
    previous = ActionController::Base.allow_forgery_protection
    ActionController::Base.allow_forgery_protection = true
    yield
  ensure
    ActionController::Base.allow_forgery_protection = previous
  end

  describe "accounts-index-anonymous" do
    it "answers anonymous JSON requests with a 401 JSON error body" do
      get "/accounts.json"

      expect(response).to have_http_status(:unauthorized)
      expect(response.media_type).to eq("application/json")
      expect(response.parsed_body).to eq("error" => "You need to sign in or sign up before continuing.")
    end
  end

  describe "RailsSessionAuth sign-in flow" do
    it "exposes a CSRF meta token on the sign-in page" do
      with_forgery_protection do
        get new_user_session_path

        expect(response).to have_http_status(:ok)
        expect(response.body).to match(/<meta[^>]+name="csrf-token"[^>]+content="[^"]+"/)
      end
    end

    it "redirects fixture users away from the sign-in page and then serves authenticated JSON" do
      expect(sign_in_as("admin@contract.example")).to redirect_to(root_url)

      get "/accounts.json"

      expect(response).to have_http_status(:ok)
      expect(response.media_type).to eq("application/json")
      expect(response.parsed_body.pluck("id").sort).to eq((101..107).to_a)
    end

    it "sends the suspended fixture user back to the sign-in page" do
      expect(sign_in_as("sam@contract.example")).to redirect_to(new_user_session_url)

      get "/accounts.json"

      expect(response).to have_http_status(:unauthorized)
    end

    it "re-renders the sign-in form for a wrong password" do
      expect(sign_in_as("bob@contract.example", password: "wrong-password")).to have_http_status(:ok)

      get "/accounts.json"

      expect(response).to have_http_status(:unauthorized)
    end
  end

  describe "accounts-show-public" do
    it "serialises the public account with the Rails field names and fixture timestamps" do
      sign_in_as("admin@contract.example")

      get "/accounts/101.json"

      expect(response).to have_http_status(:ok)
      expect(response.parsed_body).to include(
        "id" => 101,
        "user_id" => 2,
        "assigned_to" => nil,
        "name" => "Contract Account 101",
        "access" => "Public",
        "email" => "account-101@contract.example",
        "deleted_at" => nil,
        "created_at" => "2026-01-01T09:03:21.000Z",
        "updated_at" => "2026-01-01T09:03:21.000Z"
      )
    end
  end

  describe "contacts-index-alice" do
    it "scopes the contact index to the shared visibility matrix" do
      sign_in_as("alice@contract.example")

      get "/contacts.json"

      expect(response).to have_http_status(:ok)
      expect(response.parsed_body.pluck("id").sort).to eq((201..205).to_a)
    end
  end

  describe "accounts-show-private-denied-bob" do
    it "lets bob read the public account but denies alice's private account with a 401 JSON response" do
      sign_in_as("bob@contract.example")

      get "/accounts/101.json"
      expect(response).to have_http_status(:ok)
      expect(response.parsed_body).to include("id" => 101, "access" => "Public")

      get "/accounts/102.json"
      expect(response).to have_http_status(:unauthorized)
      expect(response.media_type).to eq("application/json")
      expect(response.body).to eq("You are not authorized to take this action.")
    end

    it "returns a plain-text 404 under a JSON content type for unknown accounts" do
      sign_in_as("admin@contract.example")

      get "/accounts/999.json"

      expect(response).to have_http_status(:not_found)
      expect(response.media_type).to eq("application/json")
      expect(response.body).to eq("This account is no longer available.")
      expect { response.parsed_body }.to raise_error(JSON::ParserError)
    end
  end
end
