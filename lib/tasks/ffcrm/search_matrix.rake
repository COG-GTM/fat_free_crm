# frozen_string_literal: true

require "json"
require "warden"

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails /accounts.json search+pagination behaviour as a parity matrix for the Spring API"
    task search_matrix: :environment do
      abort "ffcrm:migration:search_matrix requires PostgreSQL (ILIKE semantics)" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/search/accounts_search_matrix.json"))

      corpus_tables = %w[users accounts contacts tags taggings account_contacts]
      non_empty = corpus_tables.select do |table|
        ActiveRecord::Base.connection.select_value(
          "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
        ).to_i.positive?
      end
      abort "ffcrm:migration:search_matrix requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless non_empty.empty?

      result = nil
      ActiveRecord::Base.transaction(requires_new: true) do
        include Warden::Test::Helpers

        Warden.test_mode!

        alice = search_matrix_user(9001, "alice", "alice@search-matrix.test", admin: false)
        bob = search_matrix_user(9002, "bob", "bob@search-matrix.test", admin: false)

        search_matrix_seed(alice, bob)

        corpus = search_matrix_corpus
        cases = search_matrix_cases.map do |name, params, ordered, session_filter, preferences|
          case_options = { session_filter: session_filter, preferences: preferences || {} }
          search_matrix_run_case(alice, name, params, ordered, case_options)
        end
        result = {
          "generated_by" => "bundle exec rake ffcrm:migration:search_matrix " \
                            "OUTPUT=spring/src/test/resources/search/accounts_search_matrix.json",
          "facets" => cases.first["facets"],
          "corpus" => corpus,
          "cases" => cases.map { |entry| entry.except("facets") }
        }

        raise ActiveRecord::Rollback
      end
      Warden.test_reset!

      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output}"
      search_matrix_opportunities
    end
  end
end

def search_matrix_user(id, username, email, admin:)
  user = User.new(
    id: id, username: username, email: email, first_name: username.capitalize, last_name: "Matrix",
    admin: admin, encrypted_password: "fixed-encrypted-#{username}", password_salt: "fixed-salt-#{username}",
    reset_password_token: "fixed-reset-#{username}", confirmation_token: "fixed-confirm-#{username}",
    authentication_token: "fixed-auth-#{username}", remember_token: "fixed-remember-#{username}",
    confirmed_at: Time.utc(2025, 1, 1), created_at: Time.utc(2025, 1, 1), updated_at: Time.utc(2025, 1, 1)
  )
  user.skip_confirmation! if user.respond_to?(:skip_confirmation!)
  user.confirmed_at = Time.utc(2025, 1, 1) if user.respond_to?(:confirmed_at=)
  user.save!(validate: false)
  user
end

def search_matrix_seed(alice, bob)
  accounts = [
    # id, name, email, category, rating, access, user, assigned_to, created_at, deleted_at, tags
    [9101, "Acme Corp", "acme@example.com", "customer", 5, "Public", bob, nil, "2025-01-01 09:00", nil, []],
    [9102, "Beta Industries", "sales@beta.test", "partner", 3, "Private", alice, nil, "2025-01-02 09:00", nil, %w[vip]],
    [9103, "Gamma LLC", "info@gamma.test", "vendor", 1, "Private", bob, nil, "2025-01-03 09:00", nil, %w[vip]],
    [9104, "Delta Co", "contact@delta.test", nil, 4, "Private", bob, alice, "2025-01-04 09:00", nil, %w[vip west]],
    [9105, "Epsilon 100% Ltd", "hello@epsilon.test", "customer", 0, "Public", bob, nil, "2025-01-05 09:00",
     "2025-02-01 00:00", %w[West]],
    [9106, "acme_widgets", "widgets@acme.test", "competitor", 2, "Public", alice, nil, "2025-01-06 09:00", nil,
     %w[east]],
    [9107, "Zeta Partners", nil, "partner", 5, "Shared", bob, nil, "2025-01-07 09:00", nil, []],
    [9108, "Eta Group", "eta@example.com", "custom_cat", 3, "Public", bob, nil, "2025-01-08 09:00", nil, %w[vip]],
    [9109, "Theta", "", "reseller", 2, "Public", alice, nil, "2025-01-09 09:00", nil, []]
  ]
  21.times do |index|
    n = index + 1
    accounts << [
      9109 + n, format("Filler %02d", n), format("filler%02d@filler.test", n), "affiliate", n % 6,
      "Public", alice, nil, (Time.utc(2025, 2, 1) + n.hours).utc.strftime("%Y-%m-%d %H:%M:%S"), nil, []
    ]
  end

  tag_ids = {}
  { "vip" => 9301, "west" => 9302, "West" => 9303, "east" => 9304 }.each do |name, tag_id|
    tag_ids[name] = tag_id
    ActsAsTaggableOn::Tag.find_or_create_by!(name: name).tap do |tag|
      tag.update_column(:id, tag_id) if tag.id != tag_id
    end
  end

  tagging_id = 9401
  accounts.each do |row|
    id, name, email, category, rating, access, user, assigned_to, created_at, deleted_at, tags = row
    account = Account.new(
      id: id, name: name, email: email, category: category, rating: rating, access: access,
      user: user, assigned_to: assigned_to&.id, created_at: created_at, updated_at: created_at,
      deleted_at: deleted_at
    )
    account.save!(validate: false)
    tags.each do |tag_name|
      ActsAsTaggableOn::Tagging.create!(
        id: tagging_id, tag_id: tag_ids.fetch(tag_name), taggable_type: "Account", taggable_id: id,
        context: "tags", created_at: created_at
      )
      tagging_id += 1
    end
  end

  contacts = [
    # id, first_name, last_name, account ids
    [9201, "John", "Smith", [9101, 9104]],
    [9202, "Jane", "Doe", [9102]],
    [9203, "John", "Doe", [9106]],
    [9204, "Hidden", "Person", [9103]]
  ]
  link_id = 9501
  contacts.each do |id, first_name, last_name, account_ids|
    Contact.create!(
      id: id, first_name: first_name, last_name: last_name, access: "Public", user: alice,
      created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
    )
    account_ids.each do |account_id|
      AccountContact.create!(
        id: link_id, account_id: account_id, contact_id: id,
        created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
      )
      link_id += 1
    end
  end
end

def search_matrix_corpus
  connection = ActiveRecord::Base.connection
  %w[users accounts contacts account_contacts tags taggings].index_with do |table|
    connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
  end
end

def search_matrix_cases
  [
    # name, params, ordered
    ["default", {}, true],
    ["page2", { "page" => "2" }, true],
    ["per5", { "per_page" => "5" }, true],
    ["per0", { "per_page" => "0" }, true],
    ["perneg", { "per_page" => "-1" }, true],
    ["per201", { "per_page" => "201" }, true],
    ["perabc", { "per_page" => "abc" }, true],
    ["page0", { "page" => "0" }, true],
    ["pageabc", { "page" => "abc" }, true],
    ["page99", { "page" => "99" }, true],
    ["q_acme", { "query" => "acme" }, true],
    ["q_example", { "query" => "example.com" }, true],
    ["q_pct", { "query" => "100%" }, true],
    ["q_under", { "query" => "acme_" }, true],
    ["tag_vip", { "query" => "#vip" }, true],
    ["tag_two", { "query" => "#VIP #west" }, true],
    ["tag_mixed", { "query" => "acme #east" }, true],
    ["tag_whole", { "query" => "#vip#" }, true],
    ["tag_none", { "query" => "#nope" }, true],
    ["tag_hash", { "query" => "#" }, true],
    ["tag_hashhash", { "query" => "##" }, true],
    ["tag_space", { "query" => "# #" }, true],
    ["tag_trailing", { "query" => "acme #" }, true],
    ["tag_comma", { "query" => "#,#" }, true],
    ["cont", { "q[name_cont]" => "co" }, false],
    ["gteq", { "q[rating_gteq]" => "4" }, false],
    ["lteq", { "q[rating_lteq]" => "1" }, false],
    ["in", { "q[rating_in][]" => %w[2 3] }, false],
    ["eq", { "q[category_eq]" => "partner" }, false],
    ["null", { "q[category_null]" => "1" }, false],
    ["blank", { "q[email_blank]" => "1" }, false],
    ["present", { "q[email_present]" => "1", "per_page" => "200" }, false],
    ["start", { "q[name_start]" => "acme" }, false],
    ["end", { "q[name_end]" => "ltd" }, false],
    ["dt_gteq", { "q[created_at_gteq]" => "2025-01-05", "per_page" => "200" }, false],
    ["dt_lteq", { "q[created_at_lteq]" => "2025-01-03" }, false],
    ["or_attr", { "q[name_or_email_cont]" => "beta" }, false],
    ["assoc", { "q[contacts_first_name_cont]" => "john" }, false],
    ["assoc_and", { "q[contacts_first_name_eq]" => "John", "q[contacts_last_name_eq]" => "Smith" }, false],
    ["assoc_null", { "q[contacts_last_name_null]" => "1", "per_page" => "200" }, false],
    ["tags_assoc", { "q[tags_name_eq]" => "vip" }, false],
    ["unknown", { "q[bogus_cont]" => "x", "per_page" => "200" }, false],
    ["sort_desc", { "q[s]" => "name desc", "per_page" => "200" }, true],
    ["sort_asc_filter", { "q[s]" => "name asc", "q[rating_gteq]" => "3" }, true],
    ["combo", { "query" => "#vip", "q[rating_gteq]" => "3", "per_page" => "1", "page" => "2",
               "q[s]" => "name asc" }, true],
    ["m_or", { "q[m]" => "or", "q[category_eq]" => "vendor", "q[rating_eq]" => "5" }, false],
    ["group", { "q[g][0][m]" => "or", "q[g][0][name_cont]" => "delta", "q[g][0][email_cont]" => "beta",
               "q[access_eq]" => "Private" }, false],
    ["filter_customer", {}, true, "customer"],
    ["filter_customer_other", {}, true, "customer,other"],
    ["filter_other", {}, true, "other"],
    ["filter_ignored_with_q", { "q[name_cont]" => "delta" }, true, "customer"],
    ["preference_per_page", {}, true, nil, { "accounts_per_page" => 2 }],
    ["preference_sort", {}, true, nil, { "accounts_sort_by" => "accounts.name ASC" }],
    ["preference_per_page_explicit", { "per_page" => "5" }, true, nil,
     { "accounts_per_page" => 2 }],
    ["preference_sort_explicit_per_page", { "per_page" => "5" }, true, nil,
     { "accounts_sort_by" => "accounts.name ASC" }]
  ]
end

def search_matrix_run_case(alice, name, params, ordered, options = {})
  session_filter = options.fetch(:session_filter, nil)
  preferences = options.fetch(:preferences, {})
  preference_names = preferences.keys.map(&:to_s)
  preferences.each { |preference, value| alice.pref[preference.to_sym] = value }
  session = ActionDispatch::Integration::Session.new(Rails.application)
  session.host! "localhost"
  login_as(alice, scope: :user)
  unless session_filter.nil?
    allow_forgery_protection = AccountsController.allow_forgery_protection
    AccountsController.allow_forgery_protection = false
    begin
      session.post "/accounts/filter", params: { category: session_filter },
        headers: { "HTTP_ACCEPT" => "text/javascript" }
    ensure
      AccountsController.allow_forgery_protection = allow_forgery_protection
    end
  end
  session.get "/accounts.json", params: params
  status = session.response.status
  body = status == 200 ? JSON.parse(session.response.body) : nil
  controller = session.request.env["action_controller.instance"]
  result = {
    "name" => name,
    "params" => params,
    "ordered" => ordered,
    "status" => status,
    "ids" => body&.pluck("id"),
    "total" => controller&.instance_variable_get(:@search_results_count),
    "facets" => controller&.instance_variable_get(:@account_category_total)
  }
  result["session_filter"] = session_filter unless session_filter.nil?
  result["preferences"] = preferences unless preferences.empty?
  result
ensure
  Preference.where(user_id: alice.id, name: preference_names).delete_all if preference_names&.any?
  preference_names&.each { |name| alice.pref.cached_prefs.delete(name) }
end

def search_matrix_opportunities
  output = Rails.root.join(
    ENV.fetch("OPPORTUNITIES_OUTPUT", "spring/src/test/resources/search/opportunities_search_matrix.json")
  )
  connection = ActiveRecord::Base.connection
  corpus_tables = %w[
    users accounts contacts campaigns opportunities account_opportunities contact_opportunities tags taggings
  ]
  non_empty = corpus_tables.select do |table|
    connection.select_value(
      "SELECT COUNT(*) FROM #{connection.quote_table_name(table)}"
    ).to_i.positive?
  end
  setting_exists = Setting.exists?(name: "opportunity_stage")
  abort "opportunities search matrix requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless
    non_empty.empty? && !setting_exists

  result = nil
  ActiveRecord::Base.transaction(requires_new: true) do
    Warden.test_mode!

    alice = search_matrix_user(9001, "alice", "alice@opportunities-search-matrix.test", admin: false)
    bob = search_matrix_user(9002, "bob", "bob@opportunities-search-matrix.test", admin: false)
    search_matrix_opportunities_seed(alice, bob)

    corpus = search_matrix_opportunities_corpus
    cases = search_matrix_opportunities_cases.map do |opportunity_case|
      name, params, ordered, session_filter, preferences, settings = opportunity_case
      search_matrix_opportunities_run_case(
        alice,
        name,
        params,
        ordered,
        session_filter: session_filter,
        preferences: preferences || {},
        settings: settings
      )
    end
    result = {
      "generated_by" => "bundle exec rake ffcrm:migration:search_matrix " \
                        "OPPORTUNITIES_OUTPUT=spring/src/test/resources/search/opportunities_search_matrix.json",
      "corpus" => corpus,
      "cases" => cases,
      "shows" => search_matrix_opportunities_shows(alice)
    }
    raise ActiveRecord::Rollback
  end
  Warden.test_reset!
  Setting.clear_cache!

  File.write(output, JSON.pretty_generate(result) + "\n")
  puts "wrote #{output}"
ensure
  Warden.test_reset!
  Setting.clear_cache!
end

def search_matrix_opportunities_seed(alice, bob)
  account = Account.new(
    id: 9611, name: "Opportunity Matrix Account", email: "matrix-account@example.test",
    access: "Public", user: alice, created_at: "2025-01-01 08:00", updated_at: "2025-01-01 08:00"
  )
  account.save!(validate: false)
  contact = Contact.new(
    id: 9621, first_name: "Matrix", last_name: "Contact", access: "Public", user: alice,
    created_at: "2025-01-01 08:00", updated_at: "2025-01-01 08:00"
  )
  contact.save!(validate: false)
  campaign = Campaign.new(
    id: 9631, name: "Opportunity Matrix Campaign", access: "Public", user: alice,
    created_at: "2025-01-01 08:00", updated_at: "2025-01-01 08:00"
  )
  campaign.save!(validate: false)

  rows = [
    [9661, "Amber Portfolio 100%", "prospecting", "Public", alice, "1500.00", "25.00", 50, "2025-04-01", nil],
    [9662, "Blue Zero Deal", "analysis", "Private", alice, "0.00", "0.00", 0, "2025-04-02", nil],
    [9663, "Cobalt Contact Deal", "presentation", "Shared", alice, "1234.50", "12.50", 100, "2025-04-03", nil],
    [9664, "Delta Null Amount", "proposal", "Public", alice, nil, nil, 50, nil, nil],
    [9665, "Emerald Maximum Deal", "negotiation", "Shared", alice, "9999999999.99", "1.25", 1, "2025-04-05", nil],
    [9666, "Fuchsia Negative Deal", "final_review", "Public", alice, "-250.00", "2.50", 50, "2025-04-06", nil],
    [9667, "Golden Won Deal", "won", "Public", alice, "3200.00", "0.00", 40, "2025-04-07", nil],
    [9668, "Indigo Lost Deal", "lost", "Public", alice, "450.00", nil, 10, "2025-04-08", nil],
    [9669, "Jade Null Stage", nil, "Public", alice, "725.00", "25.00", 25, nil, nil],
    [9670, "Khaki Custom Stage", "custom_stage", "Public", alice, "100.00", "0.00", nil, "2025-04-10", nil],
    [9671, "Weighted Product A", "prospecting", "Public", alice, "10.00", "0.00", 60, "2025-04-11", nil],
    [9672, "Weighted Product B", "analysis", "Public", alice, "20.00", "0.00", 50, "2025-04-12", nil],
    [9673, "Weighted Product C", "presentation", "Public", alice, "5.00", "0.00", 100, "2025-04-13", nil],
    [9674, "Weighted Null Product", "proposal", "Public", alice, nil, "0.00", 90, "2025-04-14", nil],
    [9675, "Private Bob Opportunity", "won", "Private", bob, "80.00", "0.00", 50, "2025-04-15", nil],
    [9676, "Public Bob Opportunity", "won", "Public", bob, "90.00", "0.00", 50, "2025-04-16", nil],
    [9677, "Shared Bob Opportunity", "lost", "Shared", bob, "70.00", "0.00", 50, "2025-04-17", nil]
  ]
  14.times do |index|
    number = index + 1
    rows << [
      9677 + number,
      (number == 1 ? "Matrix_Paging Deal 01" : format("Matrix Paging Deal %02d", number)),
      "prospecting", "Public", alice,
      format("%d.00", (number * 3) + 7), "0.00", number + 5,
      (Time.utc(2025, 5, 1) + number.days).strftime("%Y-%m-%d"), nil
    ]
  end
  rows.each do |row|
    id, name, stage, access, owner, amount, discount, probability, closes_on, campaign_id = row
    opportunity = Opportunity.new(
      id: id, name: name, stage: stage, access: access, user: owner, amount: amount, discount: discount,
      probability: probability, closes_on: closes_on, campaign_id: campaign_id,
      created_at: Time.utc(2025, 1, 1) + id.seconds, updated_at: Time.utc(2025, 1, 1) + id.seconds,
      subscribed_users: []
    )
    opportunity.save!(validate: false)
  end

  Opportunity.find(9661).update_columns(campaign_id: campaign.id)
  ActsAsTaggableOn::Tag.create!(id: 9681, name: "priority")
  ActsAsTaggableOn::Tag.create!(id: 9682, name: "focus")
  ActsAsTaggableOn::Tagging.create!(
    id: 9731, tag_id: 9681, taggable_type: "Opportunity", taggable_id: 9661, context: "tags",
    created_at: "2025-01-01 09:00"
  )
  ActsAsTaggableOn::Tagging.create!(
    id: 9732, tag_id: 9682, taggable_type: "Opportunity", taggable_id: 9663, context: "tags",
    created_at: "2025-01-01 09:00"
  )
  AccountOpportunity.create!(
    id: 9701, account_id: account.id, opportunity_id: 9661,
    created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
  )
  ContactOpportunity.create!(
    id: 9711, contact_id: contact.id, opportunity_id: 9663, role: "Decision maker",
    created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
  )
end

def search_matrix_opportunities_corpus
  connection = ActiveRecord::Base.connection
  %w[users accounts contacts campaigns opportunities account_opportunities contact_opportunities tags taggings]
    .index_with do |table|
      connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
    end
end

def search_matrix_opportunities_cases
  [
    ["default", {}, true],
    ["page2", { "page" => "2" }, true],
    ["per5", { "per_page" => "5" }, true],
    ["per0", { "per_page" => "0" }, true],
    ["per201", { "per_page" => "201" }, true],
    ["perabc", { "per_page" => "abc" }, true],
    ["page99", { "page" => "99" }, true],
    ["query_text", { "query" => "Portfolio" }, true],
    ["query_numeric_id", { "query" => "9661" }, true],
    ["query_percent", { "query" => "100%" }, true],
    ["query_underscore", { "query" => "Matrix_Paging" }, true],
    ["query_tag", { "query" => "#missing" }, true],
    ["query_tag_positive", { "query" => "#priority" }, true],
    ["q_amount_gteq", { "q[amount_gteq]" => "1000" }, false],
    ["q_probability_eq", { "q[probability_eq]" => "50" }, false],
    ["q_stage_eq", { "q[stage_eq]" => "won" }, false],
    ["q_name_cont", { "q[name_cont]" => "Contact" }, false],
    ["q_closes_on_lteq", { "q[closes_on_lteq]" => "2025-04-05" }, false],
    ["q_contact_association", { "q[contacts_first_name_eq]" => "Matrix" }, false],
    ["q_account_association", { "q[account_name_cont]" => "Matrix" }, false],
    ["q_tags_name_eq", { "q[tags_name_eq]" => "priority" }, false],
    ["q_amount_sort", { "q[name_cont]" => "Weighted", "q[s]" => "amount desc" }, true],
    ["q_name_sort", { "q[name_cont]" => "Weighted", "q[s]" => "name asc" }, true],
    ["stage_won", { "stage" => "won" }, true],
    ["stage_other", { "stage" => "other" }, true],
    ["stage_won_other", { "stage" => "won,other" }, true],
    ["stage_custom", { "stage" => "custom_stage" }, true],
    ["stage_bogus", { "stage" => "bogus" }, true],
    ["stage_empty", { "stage" => "" }, true],
    ["stage_ignored_with_q", { "stage" => "won", "q[name_cont]" => "Null" }, true],
    ["session_filter_stage", {}, true, "won"],
    ["preference_per_page", { "query" => "Weighted" }, true, nil, { "opportunities_per_page" => 2 }],
    ["preference_weighted_sort", { "query" => "Weighted" }, true, nil,
     { "opportunities_sort_by" => "opportunities.amount*probability DESC" }],
    ["preference_name_sort", { "query" => "Weighted" }, true, nil,
     { "opportunities_sort_by" => "opportunities.name ASC" }],
    ["preference_per_page_explicit", { "query" => "Weighted", "per_page" => "3" }, true, nil,
     { "opportunities_per_page" => 2 }],
    ["settings_stage_symbols", {}, true, nil, {}, "---\n- :won\n- :custom_stage\n- :prospecting\n"]
  ]
end

def search_matrix_opportunities_run_case(alice, name, params, ordered, options = {})
  session_filter = options.fetch(:session_filter, nil)
  preferences = options.fetch(:preferences, {})
  settings = options.fetch(:settings, nil)
  preference_names = preferences.keys.map(&:to_s)
  preferences.each { |preference, value| alice.pref[preference.to_sym] = value }
  if settings
    Setting.create!(name: "opportunity_stage", value: YAML.safe_load(settings, permitted_classes: [Symbol]))
    Setting.clear_cache!
  end

  session = ActionDispatch::Integration::Session.new(Rails.application)
  session.host! "localhost"
  login_as(alice, scope: :user)
  unless session_filter.nil?
    allow_forgery_protection = OpportunitiesController.allow_forgery_protection
    OpportunitiesController.allow_forgery_protection = false
    begin
      session.post "/opportunities/filter", params: { stage: session_filter },
        headers: { "HTTP_ACCEPT" => "text/javascript" }
    ensure
      OpportunitiesController.allow_forgery_protection = allow_forgery_protection
    end
  end
  session.get "/opportunities.json", params: params
  status = session.response.status
  body = status == 200 ? JSON.parse(session.response.body) : nil
  controller = session.request.env["action_controller.instance"]
  result = {
    "name" => name,
    "params" => params,
    "ordered" => ordered,
    "status" => status,
    "ids" => body&.pluck("id"),
    "total" => controller&.instance_variable_get(:@search_results_count),
    "facets" => controller&.instance_variable_get(:@opportunity_stage_total)
  }
  result["session_filter"] = session_filter unless session_filter.nil?
  result["preferences"] = preferences unless preferences.empty?
  result["settings"] = { "opportunity_stage" => settings } if settings
  result
ensure
  Setting.where(name: "opportunity_stage").delete_all if settings
  Setting.clear_cache! if settings
  Preference.where(user_id: alice.id, name: preference_names).delete_all if preference_names&.any?
  preference_names&.each { |preference| alice.pref.cached_prefs.delete(preference) }
  Preference.where(user_id: alice.id, name: %w[opportunities_per_page opportunities_sort_by]).delete_all
  %w[opportunities_per_page opportunities_sort_by].each { |preference| alice.pref.cached_prefs.delete(preference) }
end

def search_matrix_opportunities_shows(alice)
  session = ActionDispatch::Integration::Session.new(Rails.application)
  session.host! "localhost"
  login_as(alice, scope: :user)
  [9661, 9662, 9663, 9664].map do |id|
    session.get "/opportunities/#{id}.json"
    {
      "id" => id,
      "status" => session.response.status,
      "body" => session.response.status == 200 ? JSON.parse(session.response.body) : nil
    }
  end
end
