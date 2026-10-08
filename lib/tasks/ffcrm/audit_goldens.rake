# frozen_string_literal: true

require "json"
require "fileutils"
require "sparql/client"
require "active_support/testing/time_helpers"

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails PaperTrail audit goldens for Spring parity"
    task audit_goldens: :environment do
      abort "ffcrm:migration:audit_goldens requires PostgreSQL" unless
        ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      tables = audit_goldens_tables
      non_empty = tables.select do |table|
        ActiveRecord::Base.connection.select_value(
          "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
        ).to_i.positive?
      end
      abort "ffcrm:migration:audit_goldens requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless
        non_empty.empty?

      output = Rails.root.join(ENV.fetch("AUDIT_GOLDENS_OUTPUT", "spring/src/test/resources/audit"))
      manifest = nil
      previous_paper_trail_enabled = PaperTrail.enabled?
      PaperTrail.enabled = true
      extend ActiveSupport::Testing::TimeHelpers

      travel_to(Time.utc(2025, 2, 1, 12))
      begin
        ActiveRecord::Base.transaction(requires_new: true) do
          PaperTrail.request(enabled: false) do
            audit_goldens_custom_fields
            audit_goldens_user
          end
          audit_goldens_reset_sequences

          manifest = {
            "generated_by" => "bundle exec rake ffcrm:migration:audit_goldens",
            "declarations" => audit_goldens_declarations,
            "cases" => audit_goldens_cases,
            "scenarios" => audit_goldens_scenarios
          }
          raise ActiveRecord::Rollback
        end
      ensure
        PaperTrail.enabled = previous_paper_trail_enabled
        travel_back
        [Account, Contact].each(&:reset_column_information)
      end

      FileUtils.mkdir_p(output)
      File.write(output.join("rails_audit_goldens.json"), JSON.pretty_generate(manifest) + "\n")
      puts "wrote #{output.join('rails_audit_goldens.json')} (#{manifest.fetch('cases').size} cases)"
    end
  end
end

def audit_goldens_tables
  %w[users preferences permissions field_groups fields accounts campaigns leads contacts opportunities tasks addresses
     account_contacts account_opportunities contact_opportunities comments emails versions]
end

def audit_goldens_models
  [Account, Campaign, Opportunity, Lead, Contact, AccountContact, AccountOpportunity, Address, Comment, Email, Task, User]
end

def audit_goldens_declarations
  audit_goldens_models.map do |model|
    file = Rails.root.glob("app/models/**/*.rb").find do |path|
      source = File.read(path)
      source.match?(/^\s*class\s+#{Regexp.escape(model.name)}\b/) &&
        source.match?(/^\s*has_paper_trail\b/)
    end
    line = file && (File.readlines(file).index { |entry| entry.match?(/^\s*has_paper_trail\b/) } + 1)
    declaration = model.paper_trail_options.to_h
    {
      "class" => model.name,
      "file" => file&.relative_path_from(Rails.root)&.to_s,
      "line" => line,
      "paper_trail_options" => audit_goldens_json_value(declaration)
    }
  end
end

def audit_goldens_custom_fields
  time = Time.utc(2025, 1, 1, 9, 30, 15)
  account_group = FieldGroup.create!(id: 68_990, name: "audit_fields", label: "Audit Fields",
                                     klass_name: "Account", position: 1, created_at: time, updated_at: time)
  contact_group = FieldGroup.create!(id: 68_991, name: "audit_contact", label: "Audit Contact",
                                     klass_name: "Contact", position: 1, created_at: time, updated_at: time)
  field_definitions = [
    [68_992, account_group, "cf_audit_string", "Audit String", "string", 1],
    [68_993, account_group, "cf_audit_decimal", "Audit Decimal", "decimal", 2],
    [68_994, account_group, "cf_audit_date", "Audit Date", "date", 3],
    [68_995, account_group, "cf_audit_datetime", "Audit Datetime", "datetime", 4],
    [68_996, account_group, "cf_audit_boolean", "Audit Boolean", "boolean", 5],
    [68_997, account_group, "cf_audit_check_boxes", "Audit Check Boxes", "check_boxes", 6],
    [68_998, contact_group, "cf_audit_contact_string", "Contact String", "string", 1],
    [68_999, contact_group, "cf_audit_contact_decimal", "Contact Decimal", "decimal", 2],
    [69_010, contact_group, "cf_audit_contact_date", "Contact Date", "date", 3],
    [69_011, contact_group, "cf_audit_contact_datetime", "Contact Datetime", "datetime", 4],
    [69_012, contact_group, "cf_audit_contact_boolean", "Contact Boolean", "boolean", 5],
    [69_013, contact_group, "cf_audit_contact_check_boxes", "Contact Check Boxes", "check_boxes", 6]
  ]
  field_definitions.each do |field|
    id, group, name, label, type, position = field
    CustomField.create!(id: id, field_group: group, name: name, label: label, as: type, position: position,
                        collection: %w[yes 123 alpha], created_at: time, updated_at: time)
  end
  ActiveRecord::Base.connection.change_column :accounts, :cf_audit_decimal, :decimal, precision: 30, scale: 9
  ActiveRecord::Base.connection.change_column :contacts, :cf_audit_contact_decimal, :decimal,
                                              precision: 30, scale: 9
  [Account, Contact].each(&:reset_column_information)
end

def audit_goldens_user
  User.new(
    id: 68_999, username: "audit-golden", email: "audit-golden@example.test",
    encrypted_password: "fixed-audit-password", password_salt: "fixed-audit-salt", admin: false,
    created_at: Time.utc(2025, 1, 1, 9, 30, 15), updated_at: Time.utc(2025, 1, 1, 9, 30, 15)
  ).save!(validate: false)
end

def audit_goldens_cases
  cases = []
  id = 69_000
  audit_goldens_models.each do |model|
    %w[create update ignored_update touch destroy].each do |operation|
      [false, true].each do |anonymous|
        id += 1
        cases << audit_goldens_case(model, operation, id, anonymous)
      end
    end
    id += 1
    cases << audit_goldens_case(model, "update_materialized", id, false)
    if model == AccountOpportunity
      id += 1
      cases << audit_goldens_case(model, "timestamp_update", id, false)
    end
  end
  cases
end

def audit_goldens_case(model, operation, id, anonymous)
  who = anonymous ? nil : "68999"
  record = nil
  PaperTrail.request(enabled: false) do
    record = audit_goldens_record(model, id)
    record.save!(validate: false) unless operation == "create"
    audit_goldens_update_support(model, id, operation)
  end

  if %w[update timestamp_update].include?(operation)
    before_attributes = model.find(id).attributes
    record = model.find(id)
  else
    before_attributes = operation == "create" ? {} : record.attributes
  end
  assigned_order = []
  PaperTrail.request(whodunnit: who, enabled: true) do
    case operation
    when "create"
      record.save!(validate: false)
    when "update", "update_materialized"
      attrs = audit_goldens_update_attributes(record, before_attributes)
      assigned_order = attrs.keys if operation == "update"
      record.update(attrs)
    when "timestamp_update"
      attrs = { updated_at: Time.utc(2025, 1, 2, 10) }
      assigned_order = attrs.keys
      record.update(attrs)
    when "ignored_update"
      ignored = audit_goldens_ignored_attributes(record)
      record.assign_attributes(ignored)
      record.save!(validate: false)
    when "touch"
      record.touch
    when "destroy"
      record.destroy!
    end
  end
  version_records = Version.where(item_type: model.name, item_id: id).order(:id).to_a
  if (version = version_records.first) && version.object
    before_attributes = version.object_deserialized
    if operation == "update"
      assigned_names = assigned_order.map(&:to_s)
      object_order = before_attributes.keys.map(&:to_s)
      changed_names = version.changeset.keys.map(&:to_s)
      ignored_names = Array(model.paper_trail_options[:ignore]).map(&:to_s)
      first_changed_assignment = object_order.index do |name|
        assigned_names.include?(name) && changed_names.include?(name)
      end
      ignored_assignment_leads = first_changed_assignment &&
                                 object_order.take(first_changed_assignment).any? do |name|
                                   assigned_names.include?(name) && ignored_names.include?(name) && changed_names.exclude?(name)
                                 end
      assigned_order = if ignored_assignment_leads
                         []
                       else
                         object_order.select { |name| assigned_names.include?(name) }
                       end
    end
  end
  after_attributes = operation == "destroy" ? before_attributes : record.reload.attributes
  if version && operation != "destroy"
    version.changeset.each do |attribute, values|
      after_attributes[attribute] = values.last
    end
  end
  before = operation == "create" ? {} : audit_goldens_dump(before_attributes)
  after = audit_goldens_dump(after_attributes)
  versions = version_records.map do |version|
    {
      "item_type" => version.item_type,
      "item_id" => version.item_id,
      "event" => version.event,
      "whodunnit" => version.whodunnit,
      "related_type" => version.related_type,
      "related_id" => version.related_id,
      "object" => version.object,
      "object_changes" => version.object_changes
    }
  end
  {
    "id" => "#{model.name}/#{operation}/#{id}/#{who || 'anonymous'}",
    "model" => model.name,
    "op" => operation,
    "whodunnit" => who,
    "assigned_order" => assigned_order,
    "before" => before,
    "after" => after,
    "versions" => versions
  }
end

def audit_goldens_record(model, id)
  time = Time.utc(2025, 1, 1, 9, 30, 15)
  values = case model.name
           when "Account"
             { name: "yes", user_id: 68_999, access: "Public", rating: 1, category: "customer",
               background_info: "Line one\nLine two", cf_audit_string: "a: b",
               cf_audit_decimal: BigDecimal("12345678901234567890.123456789"),
               cf_audit_date: Date.new(2025, 2, 3),
               cf_audit_datetime: time, cf_audit_boolean: true, cf_audit_check_boxes: %w[yes alpha] }
           when "Campaign"
             { name: "123", user_id: 68_999, access: "Public", status: "planned",
               target_conversion: 2.0, budget: BigDecimal("0"), starts_on: Date.new(2025, 2, 3) }
           when "Opportunity"
             { name: "a: b", user_id: 68_999, access: "Public", stage: "prospecting",
               amount: BigDecimal("10.25"), probability: 23 }
           when "Lead"
             { first_name: "yes", last_name: "Lead", user_id: 68_999, access: "Public", status: "new",
               do_not_call: false }
           when "Contact"
             { first_name: "Zoë😀", last_name: "Contact", user_id: 68_999, access: "Public",
               born_on: Date.new(1990, 1, 2), do_not_call: true, cf_audit_contact_string: "2025-01-01",
               cf_audit_contact_decimal: BigDecimal("-0.000000001"),
               cf_audit_contact_date: Date.new(2024, 12, 31),
               cf_audit_contact_datetime: time, cf_audit_contact_boolean: false,
               cf_audit_contact_check_boxes: %w[123 alpha] }
           when "AccountContact"
             account = Account.new(id: id + 400_000, name: "Parent #{id}", user_id: 68_999, access: "Public")
             account.save!(validate: false)
             contact = Contact.new(id: id + 500_000, first_name: "Child", last_name: "Contact",
                                   user_id: 68_999, access: "Public")
             contact.save!(validate: false)
             { account_id: account.id, contact_id: contact.id }
           when "AccountOpportunity"
             account = Account.new(id: id + 400_000, name: "Parent Opportunity #{id}",
                                   user_id: 68_999, access: "Public")
             account.save!(validate: false)
             opportunity = Opportunity.new(id: id + 600_000, name: "Child #{id}", user_id: 68_999,
                                           access: "Public", stage: "prospecting")
             opportunity.save!(validate: false)
             { account_id: account.id, opportunity_id: opportunity.id }
           when "Address"
             { addressable_type: "Account", addressable_id: 69_001, address_type: "Billing", street1: "  leading" }
           when "Comment"
             { user_id: 68_999, commentable_type: "Account", commentable_id: 69_001,
               comment: "multi\nline", private: false }
           when "Email"
             { imap_message_id: "audit-#{id}", user_id: 68_999, mediator_type: "Account", mediator_id: 69_001,
               sent_from: "alice@example.test", sent_to: "yes", subject: "123", body: "" }
           when "Task"
             { user_id: 68_999, name: "2025-01-01", asset_type: "Account", asset_id: 69_001,
               bucket: "due_asap", priority: 1, background_info: "a: b" }
           when "User"
             { username: "audit-user-#{id}", email: "audit-#{id}@example.test",
               encrypted_password: "fixed-audit-password", password_salt: "fixed-audit-salt", admin: false,
               confirmation_token: "audit-confirm-#{id}", confirmation_sent_at: time }
           end
  model.new({ id: id, created_at: time, updated_at: time }.merge(values))
end

def audit_goldens_update_support(model, id, operation)
  if model == AccountContact && %w[update update_materialized].include?(operation)
    Account.new(id: id + 800_000, name: "Updated parent #{id}", user_id: 68_999, access: "Public")
           .save!(validate: false)
  elsif model == AccountContact && operation == "ignored_update"
    Contact.new(id: id + 900_000, first_name: "Updated child", last_name: "Contact",
                user_id: 68_999, access: "Public").save!(validate: false)
  elsif model == AccountOpportunity && %w[update update_materialized].include?(operation)
    Account.new(id: id + 800_000, name: "Updated parent #{id}", user_id: 68_999, access: "Public")
           .save!(validate: false)
    Opportunity.new(id: id + 900_000, name: "Updated opportunity #{id}", user_id: 68_999,
                    access: "Public", stage: "prospecting").save!(validate: false)
  end
end

def audit_goldens_update_attributes(record, before_attributes)
  attributes = {}
  id = before_attributes.fetch("id")
  attributes[:name] = "a: b #{id}" if record.has_attribute?(:name)
  attributes[:first_name] = "leading " if record.has_attribute?(:first_name)
  attributes[:comment] = "unicode 😀" if record.has_attribute?(:comment)
  attributes[:subject] = "no" if record.has_attribute?(:subject)
  attributes[:phone] = "" if record.has_attribute?(:phone)
  attributes[:rating] = 2 if record.has_attribute?(:rating)
  attributes[:target_conversion] = 2.5 if record.has_attribute?(:target_conversion)
  attributes[:starts_on] = Date.new(2025, 3, 4) if record.has_attribute?(:starts_on)
  attributes[:updated_at] = Time.utc(2025, 1, 2, 10) if record.has_attribute?(:updated_at)
  attributes[:street2] = "yes" if record.has_attribute?(:street2)
  attributes[:account_id] = id + 800_000 if record.has_attribute?(:account_id) && before_attributes["account_id"] && (record.is_a?(AccountContact) || record.is_a?(AccountOpportunity))
  attributes[:opportunity_id] = id + 900_000 if record.has_attribute?(:opportunity_id) && before_attributes["opportunity_id"] && record.is_a?(AccountOpportunity)
  attributes[:username] = "audit-updated-#{id}" if record.is_a?(User)
  attributes[:cf_audit_string] = "multi\nline" if record.has_attribute?(:cf_audit_string)
  attributes[:cf_audit_decimal] = BigDecimal("42.125") if record.has_attribute?(:cf_audit_decimal)
  attributes
end

def audit_goldens_ignored_attributes(record)
  return { last_sign_in_at: Time.utc(2025, 1, 2) } if record.is_a?(User)
  return { subscribed_users: [68_999] } if record.has_attribute?(:subscribed_users)
  return { state: "ignored" } if record.is_a?(Comment) || record.is_a?(Email)
  return { contact_id: record.contact_id + 1 } if record.is_a?(AccountContact)

  {}
end

def audit_goldens_dump(attributes)
  references = {}.compare_by_identity
  attributes.transform_values { |value| audit_goldens_typed(value, references) }
end

def audit_goldens_typed(value, references)
  case value
  when nil then { "t" => "nil" }
  when String then { "t" => "str", "v" => value }
  when Integer then { "t" => "int", "v" => value }
  when Float then { "t" => "float", "v" => value.to_s }
  when BigDecimal then { "t" => "decimal", "v" => value.to_s }
  when true, false then { "t" => "bool", "v" => value }
  when Date then { "t" => "date", "v" => value.iso8601 }
  when Time, ActiveSupport::TimeWithZone
    identity = value.is_a?(ActiveSupport::TimeWithZone) ? value.utc : value
    ref = references[identity] ||= (references.size + 1).to_s
    { "t" => "time", "v" => value.utc.iso8601(9), "ref" => ref }
  when Array then { "t" => "array", "v" => value.map { |item| audit_goldens_typed(item, references) } }
  else
    { "t" => "str", "v" => value.to_s }
  end
end

def audit_goldens_scenarios
  sender = User.find(68_999)
  scenarios = []
  [
    ["dropbox_create_and_attach", -> { audit_goldens_dropbox_create(sender) }, sender.id.to_s],
    ["dropbox_keyword_lead", -> { audit_goldens_dropbox_keyword(sender) }, sender.id.to_s],
    ["dropbox_attach_new_lead", -> { audit_goldens_dropbox_new_lead(sender) }, sender.id.to_s],
    ["dropbox_attach_to_account", -> { audit_goldens_dropbox_account(sender) }, sender.id.to_s],
    ["comment_reply", -> { audit_goldens_comment_reply(sender) }, sender.id.to_s],
    ["account_website_job", -> { audit_goldens_website_job(sender) }, nil],
    ["wikidata_service", -> { audit_goldens_wikidata(sender) }, nil]
  ].each do |name, run, whodunnit|
    first_version_id = Version.maximum(:id).to_i
    PaperTrail.request(whodunnit: whodunnit, enabled: true) { run.call }
    rows = Version.where("id > ?", first_version_id).order(:id).map do |version|
      {
        "item_type" => version.item_type,
        "item_id" => version.item_id,
        "event" => version.event,
        "whodunnit" => version.whodunnit,
        "related_type" => version.related_type,
        "related_id" => version.related_id,
        "object" => version.object,
        "object_changes" => version.object_changes
      }
    end
    scenarios << { "name" => name, "rows" => rows }
  end
  scenarios
end

def audit_goldens_reset_sequences
  connection = ActiveRecord::Base.connection
  %w[users accounts contacts leads opportunities comments emails account_contacts account_opportunities addresses
     versions].each do |table|
    sequence = connection.select_value("SELECT pg_get_serial_sequence('#{table}', 'id')")
    connection.execute("SELECT setval(#{connection.quote(sequence)}, 300_000, false)") if sequence
  end
end

def audit_goldens_message(body = "Audit message\nDetails")
  Struct.new(:to, :cc, :from, :subject, :message_id, :date, :body) do
    def to_addrs
      to || []
    end

    def cc_addrs
      cc || []
    end

    def multipart?
      false
    end

    def charset
      "UTF-8"
    end

    def content_type
      "text/plain"
    end
  end.new(["outside@audit.test"], [], ["sender@example.test"], "Audit subject", "<audit@example.test>",
          Time.utc(2025, 1, 1, 10), body)
end

def audit_goldens_dropbox_processor(sender, settings = {})
  require "fat_free_crm/mail_processor/dropbox"
  processor = FatFreeCRM::MailProcessor::Dropbox.allocate
  processor.instance_variable_set(:@sender, sender)
  processor.instance_variable_set(:@settings, { address: "dropbox@audit.test" }.merge(settings))
  processor
end

def audit_goldens_dropbox_create(sender)
  processor = audit_goldens_dropbox_processor(sender)
  email = audit_goldens_message
  email.to.replace(["fresh@audit.test"])
  processor.send(:create_and_attach, email, "fresh@audit.test")
end

def audit_goldens_dropbox_keyword(sender)
  processor = audit_goldens_dropbox_processor(sender)
  processor.send(:find_or_create_and_attach, audit_goldens_message("Lead Audit Keyword\nDetails"),
                 { "Type" => "Lead", "Name" => "Audit Keyword Lead" })
end

def audit_goldens_dropbox_new_lead(sender)
  processor = audit_goldens_dropbox_processor(sender)
  lead = PaperTrail.request(enabled: false) do
    Lead.create!(user: sender, access: "Public", first_name: "Fresh", last_name: "Lead", status: "new")
  end
  processor.send(:attach, audit_goldens_message, lead)
end

def audit_goldens_dropbox_account(sender)
  processor = audit_goldens_dropbox_processor(sender, attach_to_account: true)
  _account, contact = PaperTrail.request(enabled: false) do
    account = Account.create!(user: sender, access: "Public", name: "Audit Contact Account")
    contact = Contact.create!(user: sender, access: "Public", first_name: "Audit", last_name: "Contact")
    AccountContact.create!(account: account, contact: contact)
    [account, contact]
  end
  processor.send(:attach, audit_goldens_message, contact)
end

def audit_goldens_comment_reply(sender)
  require "fat_free_crm/mail_processor/comment_replies"
  account = PaperTrail.request(enabled: false) do
    Account.create!(user: sender, access: "Public", name: "Audit Comment Account")
  end
  processor = FatFreeCRM::MailProcessor::CommentReplies.allocate
  processor.instance_variable_set(:@sender, sender)
  processor.send(:create_comment, audit_goldens_message("A comment reply"), "account", account.id.to_s)
end

def audit_goldens_website_job(sender)
  account = PaperTrail.request(enabled: false) do
    Account.create!(user: sender, access: "Public", name: "Audit Website Account", website: "https://audit.test")
  end
  content = {
    "@type" => "Organization",
    "telephone" => "+1 555 0100",
    "email" => "website@audit.test",
    "address" => {
      "@type" => "PostalAddress", "streetAddress" => "1 Audit Way", "addressLocality" => "Boston",
      "addressRegion" => "MA", "postalCode" => "02108", "addressCountry" => "US"
    }
  }
  AccountWebsiteJob.new.send(:process_json_ld, account, JSON.generate(content))
end

def audit_goldens_wikidata(sender)
  account = PaperTrail.request(enabled: false) do
    Account.create!(user: sender, access: "Public", name: "Audit Wikidata Account", wikidata_id: "Q123")
  end
  result = {
    description: "Wikidata description", website: "https://wikidata.audit.test",
    twitter: "audit", linkedin: "audit", instagram: "audit", mastodon: "@audit@social.test",
    facebook: "audit", bluesky: "audit.test", blog: "https://blog.audit.test"
  }
  fake_client = Object.new
  fake_client.define_singleton_method(:query) { |_query| [result] }
  client_class = SPARQL::Client
  original_new = client_class.method(:new)
  client_class.define_singleton_method(:new) { |*| fake_client }
  WikidataService.new(account).call
ensure
  client_class.define_singleton_method(:new, original_new) if client_class && original_new
end

def audit_goldens_json_value(value)
  case value
  when Hash then value.transform_keys(&:to_s).transform_values { |item| audit_goldens_json_value(item) }
  when Array then value.map { |item| audit_goldens_json_value(item) }
  when Symbol then value.to_s
  else value
  end
end
