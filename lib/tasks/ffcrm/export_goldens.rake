# frozen_string_literal: true

require "json"
require "fileutils"
require "warden"
require "active_support/testing/time_helpers"

# AB-273 exports: drives the real Rails index controllers for every CSV/XLS export over a fixed
# corpus and writes the responses as golden files that the Spring export replay test compares
# byte-for-byte.
namespace :ffcrm do
  namespace :migration do
    desc "Record Rails CSV/XLS list exports as golden files for the Spring export replay test"
    task export_goldens: :environment do
      abort "ffcrm:migration:export_goldens requires PostgreSQL" unless
        ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      output = Rails.root.join(ENV.fetch("EXPORT_GOLDENS_OUTPUT", "spring/src/test/resources/exports"))
      non_empty = export_goldens_tables.select do |table|
        ActiveRecord::Base.connection.select_value(
          "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
        ).to_i.positive?
      end
      abort "ffcrm:migration:export_goldens requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless
        non_empty.empty?

      manifest = nil
      bodies = {}
      ActiveRecord::Base.transaction(requires_new: true) do
        include Warden::Test::Helpers
        extend ActiveSupport::Testing::TimeHelpers

        # Activity rows are seeded relative to now and rendered with absolute timestamps; a frozen clock
        # keeps the committed goldens stable for the CI drift check.
        travel_to(export_goldens_now)

        Warden.test_mode!
        # The filter case POSTs like the UI's XHR; development env would reject it without a CSRF token.
        ActionController::Base.allow_forgery_protection = false
        users = PaperTrail.request(enabled: false) { export_goldens_seed }
        corpus = export_goldens_corpus
        custom_columns = export_goldens_custom_columns
        cases = export_goldens_cases.map do |name, login, rails_path, spring_path, before|
          Time.zone = Time.zone_default
          session = ActionDispatch::Integration::Session.new(Rails.application)
          session.host! "localhost"
          if before
            login_as(users.fetch(login), scope: :user, run_callbacks: false)
            session.post before, xhr: true
          end
          login_as(users.fetch(login), scope: :user, run_callbacks: false)
          session.get rails_path
          extension = File.extname(rails_path.split("?").first)
          bodies["#{name}#{extension}"] = session.response.body
          {
            "name" => name,
            "user" => users.fetch(login).id,
            "rails_path" => rails_path,
            "spring_path" => spring_path,
            "before" => before,
            "status" => session.response.status,
            "content_type" => session.response.headers["Content-Type"],
            "content_disposition" => session.response.headers["Content-Disposition"],
            "body_file" => "#{name}#{extension}"
          }
        end
        manifest = {
          "generated_by" => "bundle exec rake ffcrm:migration:export_goldens",
          "volatile" => "versions.created_at is seeded relative to a frozen now (created_offset_seconds); " \
                        "home cases normalise 'YYYY-MM-DD HH:MM:SS UTC' timestamps",
          "custom_columns" => custom_columns,
          "corpus" => corpus,
          "cases" => cases
        }
        raise ActiveRecord::Rollback
      end
      Warden.test_reset!
      travel_back

      FileUtils.rm_rf(output)
      FileUtils.mkdir_p(output)
      File.write(output.join("rails_export_goldens.json"), JSON.pretty_generate(manifest) + "\n")
      bodies.each { |file, body| File.binwrite(output.join(file), body) }
      puts "wrote #{output} (#{bodies.size} golden bodies)"
    end
  end
end

def export_goldens_tables
  %w[users preferences permissions field_groups fields accounts campaigns leads contacts opportunities
     tasks addresses account_contacts account_opportunities contact_opportunities tags taggings versions]
end

def export_goldens_now
  Time.utc(2025, 2, 1, 12, 0, 0)
end

def export_goldens_time
  Time.utc(2025, 1, 1, 9, 30, 15)
end

def export_goldens_user(id, username, first_name, last_name, admin)
  user = User.new(
    id: id, username: username, email: "#{username}@exports.test", first_name: first_name, last_name: last_name,
    admin: admin, encrypted_password: "fixed-encrypted-#{username}", password_salt: "fixed-salt-#{username}",
    created_at: export_goldens_time, updated_at: export_goldens_time
  )
  user.skip_confirmation! if user.respond_to?(:skip_confirmation!)
  user.confirmed_at = export_goldens_time if user.respond_to?(:confirmed_at=)
  user.save!(validate: false)
  user
end

def export_goldens_save(record)
  record.save!(validate: false)
  record
end

def export_goldens_seed
  alice = export_goldens_user(9701, "alice-export", "Alice", "Exporter", false)
  bob = export_goldens_user(9702, "bob-export", nil, "Builder", false)
  admin = export_goldens_user(9703, "admin-export", "Ada", "Admin", true)
  t = export_goldens_time

  export_goldens_custom_fields(t)
  export_goldens_accounts(alice, bob, t)
  export_goldens_campaigns(alice, bob, t)
  export_goldens_leads(alice, bob, t)
  export_goldens_contacts(alice, bob, t)
  export_goldens_opportunities(alice, bob, t)
  export_goldens_tasks(alice, bob, t)
  export_goldens_tags(t)
  export_goldens_versions(alice, bob)
  admin.pref[:accounts_sort_by] = "accounts.name ASC"
  Preference.where(user_id: admin.id, name: "accounts_sort_by").update_all(id: 9799)
  { "alice" => alice, "bob" => bob, "admin" => admin }
end

def export_goldens_custom_fields(time)
  account_group = FieldGroup.create!(id: 9790, name: "export_fields", label: "Export Fields", klass_name: "Account",
                                     position: 1, created_at: time, updated_at: time)
  contact_group = FieldGroup.create!(id: 9791, name: "export_contact", label: "Export Contact", klass_name: "Contact",
                                     position: 1, created_at: time, updated_at: time)
  [
    [9792, account_group, "cf_region", "Region", "string", 1],
    [9793, account_group, "cf_seats", "Seats", "decimal", 2],
    [9794, account_group, "cf_renewal", "Renewal", "date", 3],
    [9795, account_group, "cf_flags", "Flags", "check_boxes", 4],
    [9796, account_group, "cf_active", "Active", "boolean", 5],
    [9797, account_group, "cf_seen_at", "Seen At", "datetime", 6],
    [9798, contact_group, "cf_nickname", "Nickname", "string", 1]
  ].each do |field|
    id, group, name, label, as, position = field
    CustomField.create!(id: id, field_group: group, name: name, label: label, as: as, position: position,
                        collection: %w[alpha beta], created_at: time, updated_at: time)
  end
  [Account, Contact].each(&:reset_column_information)
end

def export_goldens_accounts(alice, bob, time)
  [
    [9711, "Acme, Inc.", alice, bob, "Public", "customer", 3, "acme@example.test", "Says \"hi\"\nTwice",
     { cf_region: "West", cf_seats: BigDecimal("12.5"), cf_renewal: Date.new(2025, 6, 30), cf_flags: %w[alpha beta],
       cf_active: true, cf_seen_at: Time.utc(2025, 2, 3, 4, 5, 6) }],
    [9712, "Beta & <Labs>", alice, nil, "Private", "vendor", 0, nil, "", { cf_active: false }],
    [9713, "Gamma Hidden", bob, nil, "Private", "customer", 1, nil, nil, {}],
    [9714, "Delta Shared", bob, nil, "Shared", "partner", 5, "delta@example.test", nil, { cf_region: "" }],
    [9715, "Zoë😀 Unicode", bob, alice, "Private", nil, 0, nil, nil, { cf_seats: BigDecimal("1000") }]
  ].each_with_index do |(id, name, owner, assignee, access, category, rating, email, background, custom), index|
    account = Account.new(
      id: id, name: name, user: owner, assigned_to: assignee&.id, access: access, category: category,
      rating: rating, email: email, background_info: background, phone: "555-01#{index}", website: "http://acme.test",
      toll_free_phone: index.even? ? "800-555" : nil, created_at: time + index.hours, updated_at: time + index.days
    )
    custom.each { |field, value| account[field] = value }
    export_goldens_save(account)
  end
  Permission.create!(id: 9780, user_id: alice.id, asset: Account.find(9714), created_at: time, updated_at: time)
  Address.create!(id: 9781, addressable_type: "Account", addressable_id: 9711, address_type: "Billing",
                  street1: "1 Main St", street2: "Suite, 5", city: "Boston", state: "MA", zipcode: "02108",
                  country: "US", full_address: "1 Main St\nBoston", created_at: time, updated_at: time)
  Address.create!(id: 9782, addressable_type: "Account", addressable_id: 9711, address_type: "Shipping",
                  street1: "Dock 9", created_at: time, updated_at: time)
end

def export_goldens_campaigns(alice, bob, time)
  [
    [9721, "Spring Launch", alice, bob, "Public", "planned", BigDecimal("1000.5"), 10, 2.5, BigDecimal("5000"),
     Date.new(2025, 2, 1), nil],
    [9722, "Hidden Campaign", bob, nil, "Private", "started", nil, nil, nil, nil, nil, Date.new(2025, 12, 31)],
    [9723, "Fall, \"Quoted\"", alice, nil, "Public", nil, BigDecimal("0"), 0, 0.0, nil, nil, nil]
  ].each_with_index do |(id, name, owner, assignee, access, status, budget, target_leads, conversion, revenue, starts_on, ends_on), index|
    export_goldens_save(Campaign.new(
                          id: id, name: name, user: owner, assigned_to: assignee&.id, access: access, status: status,
                          budget: budget, target_leads: target_leads, target_conversion: conversion,
                          target_revenue: revenue, starts_on: starts_on, ends_on: ends_on,
                          objectives: "Grow\npipeline", created_at: time + index.hours, updated_at: time + index.hours
                        ))
  end
end

def export_goldens_leads(alice, bob, time)
  [
    [9731, "Peter", "Lead", alice, nil, "Public", "new", 9721, "Example, Inc.", 4, true],
    [9732, "Quinn", "Contacted", alice, bob, "Public", "contacted", nil, nil, 0, false],
    [9733, "Hidden", "Lead", bob, nil, "Private", "new", nil, nil, 1, false]
  ].each_with_index do |(id, first, last, owner, assignee, access, status, campaign_id, company, rating, dnc), index|
    export_goldens_save(Lead.new(
                          id: id, first_name: first, last_name: last, user: owner, assigned_to: assignee&.id,
                          access: access, status: status, campaign_id: campaign_id, company: company, rating: rating,
                          do_not_call: dnc, email: "#{first.downcase}@lead.test", title: "Buyer", source: "web",
                          created_at: time + index.hours, updated_at: time + index.hours
                        ))
  end
  Address.create!(id: 9783, addressable_type: "Lead", addressable_id: 9731, address_type: "Business",
                  street1: "9 Lead Rd", city: "Austin", state: "TX", created_at: time, updated_at: time)
end

def export_goldens_contacts(alice, bob, time)
  [
    [9741, "Ada", "Lovelace", alice, bob, "Public", 9731, Date.new(1990, 12, 10), false, "Ady"],
    [9742, "Grace", "Hopper", alice, nil, "Private", nil, nil, true, nil],
    [9743, "Hidden", "Contact", bob, nil, "Private", nil, nil, false, nil]
  ].each_with_index do |(id, first, last, owner, assignee, access, lead_id, born_on, dnc, nickname), index|
    contact = Contact.new(
      id: id, first_name: first, last_name: last, user: owner, assigned_to: assignee&.id, access: access,
      lead_id: lead_id, born_on: born_on, do_not_call: dnc, email: "#{first.downcase}@contact.test",
      title: "Engineer", department: "R&D", source: "referral", created_at: time + index.hours,
      updated_at: time + index.hours
    )
    contact[:cf_nickname] = nickname
    export_goldens_save(contact)
  end
  AccountContact.create!(id: 9784, account_id: 9711, contact_id: 9741, created_at: time, updated_at: time)
  Address.create!(id: 9785, addressable_type: "Contact", addressable_id: 9741, address_type: "Business",
                  street1: "10 Analytical Way", zipcode: "N1", country: "UK", created_at: time, updated_at: time)
end

def export_goldens_opportunities(alice, bob, time)
  [
    [9751, "Big Deal", alice, bob, "Public", "prospecting", 25, BigDecimal("10000"), BigDecimal("500.5"),
     Date.new(2025, 3, 1), 9721],
    [9752, "Small Deal", alice, nil, "Public", "won", nil, nil, nil, nil, nil],
    [9753, "Hidden Deal", bob, nil, "Private", "lost", 10, BigDecimal("1"), nil, nil, nil]
  ].each_with_index do |(id, name, owner, assignee, access, stage, probability, amount, discount, closes_on, campaign_id), index|
    export_goldens_save(Opportunity.new(
                          id: id, name: name, user: owner, assigned_to: assignee&.id, access: access, stage: stage,
                          probability: probability, amount: amount, discount: discount, closes_on: closes_on,
                          campaign_id: campaign_id, source: "campaign", created_at: time + index.hours,
                          updated_at: time + index.hours
                        ))
  end
  AccountOpportunity.create!(id: 9786, account_id: 9711, opportunity_id: 9751, created_at: time, updated_at: time)
end

def export_goldens_tasks(alice, bob, time)
  [
    [9761, "Call Acme, now", alice, nil, "due_asap", nil, nil, "call"],
    [9762, "Overdue \"report\"", alice, nil, "specific_time", Time.utc(2020, 1, 1, 12), nil, "email"],
    [9763, "Someday", alice, nil, "due_later", Time.utc(2099, 1, 1, 12), nil, nil],
    [9764, "Delegated", alice, bob, "due_asap", nil, nil, "follow_up"],
    [9765, "Done long ago", alice, nil, "due_asap", nil, Time.utc(2020, 1, 2), nil],
    [9766, "Bob private", bob, nil, "due_asap", nil, nil, nil]
  ].each_with_index do |(id, name, owner, assignee, bucket, due_at, completed_at, category), index|
    task = export_goldens_save(Task.new(
                                 id: id, name: name, user: owner, assigned_to: assignee&.id, bucket: bucket,
                                 category: category, background_info: index.zero? ? "Line1\nLine2" : nil,
                                 completed_at: completed_at, created_at: time + index.hours, updated_at: time + index.hours
                               ))
    task.update_columns(due_at: due_at, bucket: bucket)
  end
end

def export_goldens_tags(time)
  [[9771, "vip"], [9772, "west"]].each do |id, name|
    ActsAsTaggableOn::Tag.create!(id: id, name: name)
  end
  [
    [9773, 9772, "Account", 9711], [9774, 9771, "Account", 9711], [9775, 9771, "Contact", 9741],
    [9776, 9771, "Lead", 9731], [9777, 9772, "Opportunity", 9751], [9778, 9771, "Campaign", 9721]
  ].each do |id, tag_id, type, taggable_id|
    ActsAsTaggableOn::Tagging.create!(id: id, tag_id: tag_id, taggable_type: type, taggable_id: taggable_id,
                                      context: "tags", created_at: time)
  end
end

# Activities are filtered by `created_at >= now - duration` (app/models/polymorphic/version.rb), so their
# timestamps are seeded relative to now and recorded as offsets for the Spring replay.
def export_goldens_versions(alice, bob)
  now = Time.zone.now.change(usec: 0)
  [
    [9801, "Account", 9711, "create", alice, nil, 3600, nil, nil],
    [9802, "Account", 9711, "update", alice, "---\nname: Acme\n", 1800, "---\nname:\n- Acme\n- Acme, Inc.\n", 9721],
    [9803, "Account", 9713, "create", bob, nil, 1200, nil, nil],
    [9804, "Task", 9761, "create", alice, nil, 600, nil, nil],
    [9805, "Contact", 9741, "view", alice, nil, 300, nil, nil],
    [9806, "Lead", 9731, "destroy", bob, "---\nfirst_name: Peter\n", 7 * 86_400, nil, nil]
  ].each do |version|
    id, type, item_id, event, user, object, offset, changes, related_id = version
    Version.create!(id: id, item_type: type, item_id: item_id, event: event, whodunnit: user.id.to_s,
                    object: object, object_changes: changes, related_id: related_id,
                    related_type: related_id && "Campaign", created_at: now - offset)
  end
end

def export_goldens_cases
  lists = %w[accounts campaigns contacts leads opportunities]
  cases = lists.flat_map do |list|
    %w[csv xls].flat_map do |format|
      [
        ["#{list}_alice_#{format}", "alice", "/#{list}.#{format}", "/#{list}", nil],
        ["#{list}_admin_#{format}", "admin", "/#{list}.#{format}", "/#{list}", nil]
      ]
    end
  end
  cases + [
    ["accounts_bob_csv", "bob", "/accounts.csv", "/accounts", nil],
    ["accounts_query_csv", "alice", "/accounts.csv?query=acme", "/accounts?query=acme", nil],
    ["accounts_tag_query_xls", "alice", "/accounts.xls?query=%23vip", "/accounts?query=%23vip", nil],
    ["accounts_ransack_csv", "admin", "/accounts.csv?q%5Bname_cont%5D=a&q%5Bs%5D=name+desc",
     "/accounts?q%5Bname_cont%5D=a&q%5Bs%5D=name+desc", nil],
    ["accounts_pagination_ignored_csv", "admin", "/accounts.csv?page=2&per_page=1",
     "/accounts?page=2&per_page=1", nil],
    ["accounts_filter_xls", "admin", "/accounts.xls", "/accounts?category=customer",
     "/accounts/filter?category=customer"],
    ["accounts_empty_csv", "alice", "/accounts.csv?query=nomatch", "/accounts?query=nomatch", nil],
    ["accounts_empty_xls", "alice", "/accounts.xls?query=nomatch", "/accounts?query=nomatch", nil],
    ["leads_query_xls", "alice", "/leads.xls?query=peter", "/leads?query=peter", nil],
    ["opportunities_ransack_xls", "alice", "/opportunities.xls?q%5Bstage_eq%5D=won",
     "/opportunities?q%5Bstage_eq%5D=won", nil],
    ["tasks_alice_csv", "alice", "/tasks.csv", "/tasks", nil],
    ["tasks_alice_xls", "alice", "/tasks.xls", "/tasks", nil],
    ["tasks_assigned_csv", "alice", "/tasks.csv?view=assigned", "/tasks?view=assigned", nil],
    ["tasks_assigned_xls", "alice", "/tasks.xls?view=assigned", "/tasks?view=assigned", nil],
    ["tasks_completed_xls", "alice", "/tasks.xls?view=completed", "/tasks?view=completed", nil],
    ["tasks_bob_csv", "bob", "/tasks.csv", "/tasks", nil],
    ["home_alice_xls", "alice", "/activities.xls", "/activities", nil],
    ["home_admin_xls", "admin", "/activities.xls", "/activities", nil],
    ["home_alice_csv", "alice", "/activities.csv", "/activities", nil]
  ]
end

def export_goldens_corpus
  connection = ActiveRecord::Base.connection
  now = Time.zone.now.change(usec: 0)
  export_goldens_tables.index_with do |table|
    rows = connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
    next rows unless table == "versions"

    rows.map do |row|
      offset = (now - Time.zone.parse("#{row['created_at']} UTC")).round
      row.merge("created_at" => nil, "created_offset_seconds" => offset)
    end
  end
end

def export_goldens_custom_columns
  ActiveRecord::Base.connection.select_all(<<~SQL.squish).to_a
    SELECT c.relname AS table, a.attname AS column, format_type(a.atttypid, a.atttypmod) AS type
    FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = current_schema() AND a.attname LIKE 'cf\\_%' AND a.attnum > 0 AND NOT a.attisdropped
    ORDER BY c.relname, a.attnum
  SQL
end
