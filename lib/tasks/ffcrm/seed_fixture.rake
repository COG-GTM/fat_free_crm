# frozen_string_literal: true

require "base64"
require "json"

# Seeds a deterministic, hand-sized data set that touches every column of every application table so the
# Spring JPA model can be verified against rows Rails actually wrote (serialised YAML, Base64 JSON, STI type
# names, polymorphic pairs, soft-deleted rows, counter caches, PaperTrail versions, ...).
#
# Run by spring/scripts/generate-seed-fixture.sh against a throwaway PostgreSQL database; the pg_dump of that
# database becomes spring/src/test/resources/db/rails/rails_seed_data.sql.
namespace :ffcrm do
  namespace :migration do
    desc "Seed the current database with the Spring data-fidelity fixture data set"
    task seed_fixture: :environment do
      FatFreeCRM::SeedFixture.new.run
    end
  end
end

module FatFreeCRM
  # One method per table family; long by nature because it enumerates every column of every table.
  class SeedFixture # rubocop:disable Metrics/ClassLength
    include ActiveSupport::Testing::TimeHelpers

    FROZEN_AT = Time.utc(2026, 10, 7, 12, 0, 0)

    def run
      ActionMailer::Base.perform_deliveries = false
      ActiveJob::Base.queue_adapter = :test
      PaperTrail.enabled = true

      travel_to(FROZEN_AT) do
        ActiveRecord::Base.transaction do
          seed_users_and_groups
          PaperTrail.request.whodunnit = @alice.id.to_s
          seed_settings_and_preferences
          seed_campaigns
          seed_accounts
          seed_leads
          seed_contacts
          seed_opportunities
          seed_tasks
          seed_comments_emails_addresses
          seed_tags
          seed_lists_tools_avatars
          seed_activities_and_versions
          # Last: Rails runs every field in a klass's field groups through the entity validator on save.
          seed_fields
        end
      end
      report
    end

    private

    def seed_users_and_groups
      @alice = create_user!(
        "alice", admin: true, first_name: "Alice", last_name: "Admin", title: "Head of Sales",
        company: "Fat Free CRM", alt_email: "alice.alt@example.com", phone: "+1 555 0100", mobile: "+1 555 0101",
        google: "alice.google", zoom: "alice-zoom", teams: "alice-teams", signal: "+15550101", instagram: "alice",
        facebook: "alice.fb", mastodon: "@alice@example.social", bluesky: "alice.bsky.social", twitter: "alice",
        linkedin: "in/alice", blog: "https://blog.example.com/alice", receive_assigned_notifications: false
      )
      @bob = create_user!("bob", first_name: "Bob", last_name: "Builder", subscribe_to_comment_replies: false)
      @carol = create_user!("carol", first_name: "Carol", last_name: "Suspended", suspended: true)
      @dave = create_user!("dave", first_name: "Dave", last_name: "Deleted")
      @dave.update_columns(deleted_at: FROZEN_AT - 30.days)
      @alice.update_columns(
        sign_in_count: 42, last_sign_in_at: FROZEN_AT - 2.days, current_sign_in_at: FROZEN_AT - 1.hour,
        last_sign_in_ip: "10.0.0.7", current_sign_in_ip: "10.0.0.8", remember_created_at: FROZEN_AT - 3.days
      )

      @sales = Group.create!(name: "Sales", users: [@alice, @bob])
      @support = Group.create!(name: "Support", users: [@carol])
    end

    def create_user!(username, suspended: false, **attributes)
      user = User.new(
        username: username, email: "#{username}@example.com", password: "Fixture-#{username}-42",
        password_confirmation: "Fixture-#{username}-42", **attributes
      )
      user.skip_confirmation!
      user.suspended_at = FROZEN_AT - 1.day if suspended
      user.save!
      user.update_column(:suspended_at, nil) unless suspended
      user
    end

    def seed_settings_and_preferences
      Setting.company_name = "Fixture Co"
      Setting.require_unique_account_names = false
      Setting.locale_options = %w[en-US de]
      Setting.email_dropbox = { "server" => "imap.example.com", "port" => 993, "ssl" => true }

      Preference.create!(user: @alice, name: "locale", value: Base64.encode64("en-US".to_json))
      Preference.create!(user: @alice, name: "activity_asset", value: Base64.encode64(%w[accounts contacts].to_json))
      Preference.create!(user: @bob, name: "contacts_per_page", value: Base64.encode64(50.to_json))
      Preference.create!(user: nil, name: "shared", value: Base64.encode64({ "theme" => "light" }.to_json))
    end

    def seed_campaigns
      @campaign = Campaign.create!(
        user: @alice, assigned_to: @bob.id, name: "Autumn Launch", access: "Public", status: "started",
        budget: 25_000.50, target_leads: 120, target_conversion: 12.5, target_revenue: 250_000,
        revenue: 1_250.25, starts_on: Date.new(2026, 9, 1), ends_on: Date.new(2026, 12, 31),
        objectives: "Line one\nLine two", background_info: "Campaign background", subscribed_users: [@alice.id]
      )
      Campaign.create!(user: @bob, name: "Retired Campaign", access: "Private", status: "completed",
                       deleted_at: FROZEN_AT - 10.days)
    end

    def seed_accounts
      @acme = Account.create!(
        user: @alice, assigned_to: @bob.id, name: "Acme Corp", access: "Public", website: "https://acme.example",
        toll_free_phone: "1-800-555-0199", phone: "+1 555 0199", fax: "+1 555 0198", email: "info@acme.example",
        background_info: "Key account.\nSecond line with ünïcödé.", rating: 4, category: "customer",
        subscribed_users: [@alice.id, @bob.id], wikidata_id: "Q42", latitude: 37.774929, longitude: -122.419418,
        blog: "https://acme.example/blog", linkedin: "company/acme", facebook: "acme", twitter: "acme",
        bluesky: "acme.bsky.social", instagram: "acme", mastodon: "@acme@example.social"
      )
      @globex = Account.create!(user: @bob, name: "Globex", access: "Private", rating: 0, subscribed_users: [])
      @shared = Account.new(user: @alice, name: "Ünïcødé & Sons", access: "Shared", rating: 2,
                            category: "partner")
      @shared.permissions.build(user: @carol)
      @shared.permissions.build(group: @support)
      @shared.save!
      Account.create!(user: @alice, name: "Initech", access: "Public", rating: 1, deleted_at: FROZEN_AT - 5.days)
    end

    def seed_leads
      @lead = Lead.create!(
        user: @alice, assigned_to: @bob.id, campaign: @campaign, first_name: "Lena", last_name: "Lead",
        access: "Public", title: "CTO", company: "Leadify", source: "web", status: "contacted",
        referred_by: "Bob", email: "lena@leadify.example", alt_email: "lena.alt@leadify.example",
        phone: "+49 30 1234", mobile: "+49 170 1234", blog: "https://leadify.example", linkedin: "in/lena",
        facebook: "lena", twitter: "lena", rating: 3, do_not_call: true, background_info: "Met at conference",
        subscribed_users: [@alice.id], zoom: "lena-zoom", teams: "lena-teams", signal: "+491701234",
        instagram: "lena", mastodon: "@lena@example.social", bluesky: "lena.bsky.social"
      )
      @converted_lead = Lead.create!(user: @bob, first_name: "Carl", last_name: "Converted", access: "Public",
                                     status: "converted", rating: 0, campaign: @campaign)
      Lead.create!(user: @bob, first_name: "Gone", last_name: "Lead", status: "rejected", rating: 0,
                   deleted_at: FROZEN_AT - 1.day)
    end

    def seed_contacts
      @ceo = Contact.create!(
        user: @alice, assigned_to: @bob.id, first_name: "Carla", last_name: "Chief", access: "Public",
        title: "CEO", department: "Executive", source: "referral", email: "carla@acme.example",
        alt_email: "carla.alt@acme.example", phone: "+1 555 0110", mobile: "+1 555 0111", fax: "+1 555 0112",
        blog: "https://carla.example", linkedin: "in/carla", facebook: "carla", twitter: "carla",
        born_on: Date.new(1975, 2, 28), do_not_call: false, background_info: "Decision maker",
        subscribed_users: [@alice.id, @bob.id], zoom: "carla-zoom", teams: "carla-teams", signal: "+15550111",
        instagram: "carla", mastodon: "@carla@example.social", bluesky: "carla.bsky.social"
      )
      @report = Contact.create!(user: @bob, first_name: "Carl", last_name: "Converted", access: "Private",
                                lead: @converted_lead, reports_to: @ceo.id, do_not_call: true)
      Contact.create!(user: @bob, first_name: "Former", last_name: "Contact", deleted_at: FROZEN_AT - 2.days)

      AccountContact.create!(account: @acme, contact: @ceo)
      AccountContact.create!(account: @globex, contact: @report)
      AccountContact.create!(account: @globex, contact: @ceo, deleted_at: FROZEN_AT - 20.days)
    end

    def seed_opportunities
      @deal = Opportunity.create!(
        user: @alice, assigned_to: @bob.id, campaign: @campaign, name: "Acme renewal", access: "Public",
        source: "campaign", stage: "proposal", probability: 60, amount: 1_234_567.89, discount: 1_000.10,
        closes_on: Date.new(2026, 11, 30), background_info: "Renewal", subscribed_users: [@bob.id]
      )
      @small_deal = Opportunity.new(user: @bob, name: "Globex pilot", access: "Shared", stage: "prospecting",
                                    probability: 10, amount: 500)
      @small_deal.permissions.build(user: @alice)
      @small_deal.save!
      Opportunity.create!(user: @bob, name: "Lost deal", stage: "lost", deleted_at: FROZEN_AT - 3.days)

      AccountOpportunity.create!(account: @acme, opportunity: @deal)
      AccountOpportunity.create!(account: @globex, opportunity: @small_deal)
      AccountOpportunity.create!(account: @acme, opportunity: @small_deal, deleted_at: FROZEN_AT - 4.days)
      ContactOpportunity.create!(contact: @ceo, opportunity: @deal, role: "Decision maker")
      ContactOpportunity.create!(contact: @report, opportunity: @deal, role: "Influencer")
      ContactOpportunity.create!(contact: @report, opportunity: @small_deal)
      ContactOpportunity.create!(contact: @ceo, opportunity: @small_deal, deleted_at: FROZEN_AT - 4.days)
    end

    def seed_tasks
      @task = Task.create!(
        user: @alice, assigned_to: @bob.id, name: "Call Acme", asset: @acme, priority: "high",
        category: "call", bucket: "due_tomorrow", background_info: "Discuss renewal",
        subscribed_users: [@alice.id, @bob.id]
      )
      Task.create!(user: @bob, name: "Email Carla", asset: @ceo, category: "email", bucket: "due_this_week")
      Task.create!(user: @alice, name: "Standalone task", bucket: "due_asap")
      Task.create!(user: @alice, name: "Done task", asset: @deal, bucket: "due_asap",
                   completed_at: FROZEN_AT - 1.day, completed_by: @alice.id)
      Task.create!(user: @bob, name: "Deleted task", bucket: "due_asap", deleted_at: FROZEN_AT - 1.hour)
    end

    def seed_comments_emails_addresses
      Comment.create!(user: @alice, commentable: @acme, comment: "Quarterly review scheduled with @bob.",
                      private: false, title: "Review")
      Comment.create!(user: @bob, commentable: @ceo, comment: "Prefers morning calls.", private: true,
                      state: "Collapsed")
      Comment.create!(user: @bob, commentable: @task, comment: "On it.")

      Email.create!(
        imap_message_id: "<msg-1@example.com>", user: @alice, mediator: @acme, sent_from: "alice@example.com",
        sent_to: "info@acme.example", cc: "bob@example.com", bcc: "archive@example.com", subject: "Renewal terms",
        body: "Hi,\n\nPlease find the terms attached.\n", header: "X-Mailer: Fixture\nX-Priority: 1",
        sent_at: FROZEN_AT - 2.hours, received_at: FROZEN_AT - 1.hour
      )
      Email.create!(imap_message_id: "<msg-2@example.com>", user: @bob, mediator: @ceo, sent_from: "bob@example.com",
                    sent_to: "carla@acme.example", state: "Collapsed")
      Email.create!(imap_message_id: "<msg-3@example.com>", sent_from: "nobody@example.com", sent_to: "x@example.com",
                    deleted_at: FROZEN_AT - 1.day)

      Address.create!(addressable: @acme, address_type: "Business", street1: "1 Market St", street2: "Suite 100",
                      city: "San Francisco", state: "CA", zipcode: "94105", country: "US")
      Address.create!(addressable: @ceo, address_type: "Billing", full_address: "Postfach 1, 10115 Berlin, DE")
      Address.create!(addressable: @lead, address_type: "Shipping", city: "Hamburg", country: "DE")
      Address.create!(addressable: @acme, address_type: "Shipping", city: "Old", deleted_at: FROZEN_AT - 9.days)
    end

    def seed_tags
      @acme.tag_list.add("vip", "enterprise")
      @acme.save!
      @ceo.tag_list.add("vip")
      @ceo.save!
      hot = Tag.find_or_create_by!(name: "hot")
      ActsAsTaggableOn::Tagging.create!(tag: hot, taggable: @lead, tagger: @alice, context: "tags")
    end

    def seed_fields
      vip = Tag.find_by!(name: "vip")
      group = FieldGroup.create!(name: "vip_details", label: "VIP details", klass_name: "Contact", position: 2,
                                 hint: "Shown for VIP contacts", tag: vip)
      default = FieldGroup.find_or_create_by!(klass_name: "Account", name: "default") do |fg|
        fg.label = "Account details"
        fg.position = 1
      end
      email = CoreField.create!(field_group: default, name: "email", label: "Email", as: "email", position: 1,
                                hint: "Primary email", placeholder: "name@example.com", required: true,
                                maxlength: 254, minlength: 3, pattern: ".+@.+", autofocus: "autofocus",
                                autocomplete: "email", title: "Email address")
      tier = CoreField.create!(field_group: group, name: "tier", label: "Tier", as: "select", position: 1,
                               collection: %w[gold silver bronze], settings: { "allow_blank" => true }.with_indifferent_access,
                               disabled: false, list: "tiers", multiple: "multiple")
      tier.update_columns(pair_id: email.id)
    end

    def seed_lists_tools_avatars
      List.create!(name: "My hot leads", url: "/leads?q[status_eq]=contacted&sort=created_at+desc", user: @alice)
      List.create!(name: "All accounts", url: "/accounts")

      ResearchTool.create!(name: "Wikipedia", url_template: "https://en.wikipedia.org/wiki/{{name}}", enabled: true)
      ResearchTool.create!(name: "Disabled tool", url_template: "https://example.com/?q={{name}}", enabled: false)

      Avatar.create!(user: @alice, entity: @alice, image_file_name: "alice.png", image_content_type: "image/png",
                     image_file_size: 12_345)
      Avatar.create!(user: @bob, entity: @ceo, image_file_name: "carla.jpg", image_content_type: "image/jpeg",
                     image_file_size: 54_321)
    end

    def seed_activities_and_versions
      ActiveRecord::Base.connection.execute(<<~SQL.squish)
        INSERT INTO activities (user_id, subject_type, subject_id, action, info, private, created_at, updated_at)
        VALUES (#{@alice.id}, 'Account', #{@acme.id}, 'viewed', 'Legacy activity row', false,
                '2024-01-02 03:04:05', '2024-01-02 03:04:05'),
               (#{@bob.id}, 'Contact', #{@ceo.id}, 'updated', '', true, '2024-02-03 04:05:06', '2024-02-03 04:05:06')
      SQL

      @acme.update!(rating: 5, phone: "+1 555 0200")
      Version.create!(item: @acme, event: "view", whodunnit: @bob.id.to_s, related: @acme)
      Version.create!(item: @task, event: "view", whodunnit: @alice.id.to_s)
    end

    def report
      tables = ActiveRecord::Base.connection.tables.sort - %w[schema_migrations ar_internal_metadata]
      tables.each do |table|
        count = ActiveRecord::Base.connection.select_value("SELECT count(*) FROM #{table}")
        puts format("%<table>-32s %<count>5d", table: table, count: count)
      end
    end
  end
end
