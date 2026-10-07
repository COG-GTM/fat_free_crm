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

      campaigns_output = if ENV["CAMPAIGNS_OUTPUT"]
                           Rails.root.join(ENV["CAMPAIGNS_OUTPUT"])
                         elsif ENV["OUTPUT"]
                           output.sub_ext(".campaigns.json")
                         else
                           Rails.root.join("spring/src/test/resources/search/campaigns_search_matrix.json")
                         end
      search_matrix_campaigns(campaigns_output)
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
