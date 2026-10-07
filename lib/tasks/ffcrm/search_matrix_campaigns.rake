# frozen_string_literal: true

# Helpers for the campaigns leg of ffcrm:migration:search_matrix (see search_matrix.rake).
# Mirrors the accounts helpers: same corpus-table-empty guard, rolled-back transaction,
# deterministic output, and per-case preference cleanup.

def search_matrix_campaigns(output)
  abort "ffcrm:migration:search_matrix (campaigns) requires PostgreSQL (ILIKE semantics)" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

  corpus_tables = %w[users campaigns leads opportunities permissions tags taggings]
  non_empty = corpus_tables.select do |table|
    ActiveRecord::Base.connection.select_value(
      "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
    ).to_i.positive?
  end
  abort "ffcrm:migration:search_matrix (campaigns) requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless non_empty.empty?

  result = nil
  ActiveRecord::Base.transaction(requires_new: true) do
    include Warden::Test::Helpers

    Warden.test_mode!

    alice = search_matrix_user(9001, "alice", "alice@search-matrix.test", admin: false)
    bob = search_matrix_user(9002, "bob", "bob@search-matrix.test", admin: false)

    search_matrix_campaigns_seed(alice, bob)

    corpus = search_matrix_campaigns_corpus
    cases = search_matrix_campaigns_cases.map do |name, params, ordered, session_filter, preferences|
      case_options = { session_filter: session_filter, preferences: preferences || {} }
      search_matrix_campaigns_run_case(alice, name, params, ordered, case_options)
    end
    result = {
      "generated_by" => "bundle exec rake ffcrm:migration:search_matrix " \
                        "CAMPAIGNS_OUTPUT=spring/src/test/resources/search/campaigns_search_matrix.json",
      "facets" => cases.first["facets"],
      "corpus" => corpus,
      "cases" => cases.map { |entry| entry.except("facets") }
    }

    raise ActiveRecord::Rollback
  end
  Warden.test_reset!

  File.write(output, JSON.pretty_generate(result) + "\n")
  puts "wrote #{output}"
end

def search_matrix_campaigns_seed(alice, bob)
  campaigns = [
    # id, name, status, access, user, assigned_to, created_at, deleted_at,
    # target_leads, target_revenue, leads_count, revenue, starts_on, ends_on, tags
    [9101, "Alpha Planned", "planned", "Public", bob, nil, "2025-01-01 09:00", nil,
     10, "100.11", 1, "200.21", "2025-03-01", "2025-04-01", %w[vip]],
    [9102, "Bravo 100% Started", "started", "Private", alice, nil, "2025-01-02 09:00", nil,
     11, "100.12", 0, "200.22", "2025-03-02", "2025-04-02", %w[vip]],
    [9103, "Charlie Completed", "completed", "Private", bob, nil, "2025-01-03 09:00", nil,
     12, "100.13", 0, "200.23", "2025-03-03", "2025-04-03", %w[vip]],
    [9104, "Delta promo_ Hold", "on_hold", "Private", bob, alice, "2025-01-04 09:00", nil,
     13, "100.14", 4, "200.24", "2025-03-04", "2025-04-04", %w[vip west]],
    [9105, "Echo promo_ CalledOff", "called_off", "Public", bob, nil, "2025-01-05 09:00",
     "2025-02-01 00:00", 14, "100.15", 5, "200.25", "2025-03-05", "2025-04-05", %w[West]],
    [9106, "Foxtrot active", "active", "Public", alice, nil, "2025-01-06 09:00", nil,
     15, "100.16", 6, "200.26", "2025-03-06", "2025-04-06", %w[east]],
    [9107, "Golf NullStatus", nil, "Shared", bob, nil, "2025-01-07 09:00", nil,
     16, "100.17", 7, "200.27", "2025-03-07", "2025-04-07", []],
    [9108, "Hotel Custom", "custom_stage", "Public", bob, nil, "2025-01-08 09:00", nil,
     17, "100.18", 8, "200.28", "2025-03-08", "2025-04-08", %w[vip]],
    [9109, "India Private Alice", "started", "Private", alice, nil, "2025-01-09 09:00", nil,
     18, "100.19", 9, "200.29", "2025-03-09", "2025-04-09", []]
  ]
  21.times do |index|
    n = index + 1
    campaigns << [
      9109 + n, format("Filler %02d", n), nil, "Public", alice, nil,
      (Time.utc(2025, 2, 1) + n.hours).utc.strftime("%Y-%m-%d %H:%M:%S"), nil,
      100 + n, format("%<whole>d.%<cents>02d", whole: 500 + n, cents: n % 100), 50 + n,
      format("%<whole>d.%<cents>02d", whole: 600 + n, cents: n % 100),
      (Date.new(2025, 6, 1) + n).to_s, (Date.new(2025, 7, 1) + n).to_s, []
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
  campaigns.each do |row|
    id, name, status, access, user, assigned_to, created_at, deleted_at,
      target_leads, target_revenue, leads_count, revenue, starts_on, ends_on, tags = row
    campaign = Campaign.new(
      id: id, name: name, status: status, access: access, user: user,
      assigned_to: assigned_to&.id, created_at: created_at, updated_at: created_at,
      deleted_at: deleted_at, target_leads: target_leads, target_revenue: target_revenue,
      leads_count: leads_count, revenue: revenue, starts_on: starts_on, ends_on: ends_on
    )
    campaign.save!(validate: false)
    tags.each do |tag_name|
      ActsAsTaggableOn::Tagging.create!(
        id: tagging_id, tag_id: tag_ids.fetch(tag_name), taggable_type: "Campaign",
        taggable_id: id, context: "tags", created_at: created_at
      )
      tagging_id += 1
    end
  end

  # Shared access for alice on 9107 through a permissions row.
  Permission.create!(
    id: 9601, user_id: alice.id, asset_type: "Campaign", asset_id: 9107,
    created_at: "2025-01-07 09:00", updated_at: "2025-01-07 09:00"
  )

  # Leads/opportunities linked to campaigns (one invisible to alice) for association search.
  # after_create callbacks increment the campaign counters; the explicit leads_count values
  # above are re-applied afterwards to keep every sortable column value unique.
  leads = [
    # id, first_name, last_name, campaign_id
    [9201, "John", "Smith", 9102],
    [9202, "Jane", "Doe", 9102],
    [9203, "Hidden", "Person", 9103]
  ]
  leads.each do |id, first_name, last_name, campaign_id|
    Lead.create!(
      id: id, first_name: first_name, last_name: last_name, campaign_id: campaign_id,
      access: "Public", user: alice, status: "new",
      created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
    )
  end
  Campaign.where(id: [9102]).update_all(leads_count: 2)
  Campaign.where(id: [9103]).update_all(leads_count: 3)

  opportunities = [
    # id, name, campaign_id
    [9701, "Delta Renewal", 9104],
    [9702, "Charlie Upsell", 9103]
  ]
  opportunities.each do |id, name, campaign_id|
    Opportunity.create!(
      id: id, name: name, campaign_id: campaign_id, access: "Public", user: alice,
      stage: "prospecting", created_at: "2025-01-01 09:00", updated_at: "2025-01-01 09:00"
    )
  end
end

def search_matrix_campaigns_corpus
  connection = ActiveRecord::Base.connection
  %w[users campaigns leads opportunities permissions tags taggings].index_with do |table|
    connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
  end
end

def search_matrix_campaigns_cases
  [
    # name, params, ordered, session_filter, preferences
    ["default", {}, true],
    ["page2", { "page" => "2" }, true],
    ["per5", { "per_page" => "5" }, true],
    ["per0", { "per_page" => "0" }, true],
    ["per201", { "per_page" => "201" }, true],
    ["perabc", { "per_page" => "abc" }, true],
    ["page0", { "page" => "0" }, true],
    ["page99", { "page" => "99" }, true],
    ["q_alpha", { "query" => "alpha" }, true],
    ["q_pct", { "query" => "100%" }, true],
    ["q_under", { "query" => "promo_" }, true],
    ["tag_vip", { "query" => "#vip" }, true],
    ["tag_two", { "query" => "#VIP #west" }, true],
    ["tag_mixed", { "query" => "promo #east" }, true],
    ["tag_none", { "query" => "#nope" }, true],
    ["cont", { "q[name_cont]" => "ta" }, false],
    ["status_eq", { "q[status_eq]" => "planned" }, false],
    ["status_null", { "q[status_null]" => "1", "per_page" => "200" }, false],
    ["status_in", { "q[status_in][]" => %w[planned started] }, false],
    ["leads_gteq", { "q[target_leads_gteq]" => "15" }, false],
    ["revenue_lteq", { "q[revenue_lteq]" => "200.24" }, false],
    ["budget_gteq", { "q[budget_gteq]" => "1.5", "per_page" => "200" }, false],
    ["starts_gteq", { "q[starts_on_gteq]" => "2025-03-05" }, false],
    ["created_lteq", { "q[created_at_lteq]" => "2025-01-04" }, false],
    ["or_attr", { "q[name_or_status_cont]" => "planned" }, false],
    ["assoc_leads", { "q[leads_first_name_cont]" => "john" }, false],
    ["assoc_opps", { "q[opportunities_name_cont]" => "renewal" }, false],
    ["tags_assoc", { "q[tags_name_eq]" => "vip" }, false],
    ["unknown", { "q[bogus_cont]" => "x", "per_page" => "200" }, false],
    ["m_or", { "q[m]" => "or", "q[status_eq]" => "planned", "q[target_leads_eq]" => "11" }, false],
    ["group", { "q[g][0][m]" => "or", "q[g][0][name_cont]" => "delta",
               "q[g][0][name_cont_1]" => "foxtrot", "q[access_eq]" => "Private" }, false],
    ["sort_name_desc", { "q[s]" => "name desc", "per_page" => "200" }, true],
    ["sort_leads_desc", { "q[s]" => "target_leads desc", "per_page" => "200" }, true],
    ["sort_revenue_desc", { "q[s]" => "revenue desc", "per_page" => "200" }, true],
    ["sort_starts_desc", { "q[s]" => "starts_on desc", "per_page" => "200" }, true],
    ["filter_planned", {}, true, "planned"],
    ["filter_planned_other", {}, true, "planned,other"],
    ["filter_other", {}, true, "other"],
    ["filter_active", {}, true, "active"],
    ["filter_ignored_with_q", { "q[name_cont]" => "delta" }, true, "planned"],
    ["filter_with_query", { "query" => "foxtrot" }, true, "planned"],
    ["preference_per_page", {}, true, nil, { "campaigns_per_page" => 2 }],
    ["preference_sort", {}, true, nil, { "campaigns_sort_by" => "campaigns.name ASC" }],
    ["preference_sort_leads", {}, true, nil, { "campaigns_sort_by" => "campaigns.target_leads DESC" }],
    ["preference_explicit_per_page", { "per_page" => "5" }, true, nil,
     { "campaigns_per_page" => 2, "campaigns_sort_by" => "campaigns.name ASC" }]
  ]
end

def search_matrix_campaigns_run_case(alice, name, params, ordered, options = {})
  session_filter = options.fetch(:session_filter, nil)
  preferences = options.fetch(:preferences, {})
  preference_names = preferences.keys.map(&:to_s)
  preferences.each { |preference, value| alice.pref[preference.to_sym] = value }
  session = ActionDispatch::Integration::Session.new(Rails.application)
  session.host! "localhost"
  login_as(alice, scope: :user)
  post_status = nil
  unless session_filter.nil?
    allow_forgery_protection = CampaignsController.allow_forgery_protection
    CampaignsController.allow_forgery_protection = false
    begin
      session.post "/campaigns/filter", params: { status: session_filter },
        headers: { "HTTP_ACCEPT" => "text/javascript" }
      post_status = session.response.status
    ensure
      CampaignsController.allow_forgery_protection = allow_forgery_protection
    end
    # The filter POST consumes the warden test-mode login; restore it for the index request.
    login_as(alice, scope: :user)
  end
  session.get "/campaigns.json", params: params
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
    "facets" => controller&.instance_variable_get(:@campaign_status_total)
  }
  unless session_filter.nil?
    result["session_filter"] = session_filter
    # Rails renders the filtered index as JS on POST; it 500s when a listed campaign
    # has a NULL status (campaigns/_status.html.haml calls status.to_sym), so the
    # session filter never persists for "other"-containing filters.
    result["post_status"] = post_status
  end
  result["preferences"] = preferences unless preferences.empty?
  result
ensure
  Preference.where(user_id: alice.id, name: preference_names).delete_all if preference_names&.any?
  preference_names&.each { |name| alice.pref.cached_prefs.delete(name) }
end
