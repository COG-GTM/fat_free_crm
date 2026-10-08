# frozen_string_literal: true

require "json"
require "fileutils"
require "warden"

namespace :ffcrm do
  namespace :migration do
    desc "Record Rails /contacts and /leads vCard responses as a parity matrix for Spring"
    task vcard_matrix: :environment do
      abort "ffcrm:migration:vcard_matrix requires PostgreSQL" unless
        ActiveRecord::Base.connection.adapter_name == "PostgreSQL"

      output = Rails.root.join(ENV.fetch("VCARD_OUTPUT", "spring/src/test/resources/vcard/rails_vcard_matrix.json"))
      tables = %w[users accounts account_contacts contacts leads addresses]
      non_empty = tables.select do |table|
        ActiveRecord::Base.connection.select_value(
          "SELECT COUNT(*) FROM #{ActiveRecord::Base.connection.quote_table_name(table)}"
        ).to_i.positive?
      end
      abort "ffcrm:migration:vcard_matrix requires empty corpus tables; found rows in: #{non_empty.join(', ')}" unless
        non_empty.empty?

      result = nil
      ActiveRecord::Base.transaction(requires_new: true) do
        include Warden::Test::Helpers

        Warden.test_mode!
        alice = vcard_matrix_user
        vcard_matrix_seed(alice)
        corpus = vcard_matrix_corpus(tables)
        cases = vcard_matrix_cases.map do |name, path|
          session = ActionDispatch::Integration::Session.new(Rails.application)
          session.host! "localhost"
          login_as(alice, scope: :user)
          session.get path
          {
            "name" => name,
            "path" => path,
            "status" => session.response.status,
            "content_type" => session.response.headers["Content-Type"],
            "content_disposition" => session.response.headers["Content-Disposition"],
            "body" => session.response.body
          }
        end
        result = {
          "generated_by" => "bundle exec rake ffcrm:migration:vcard_matrix " \
                            "VCARD_OUTPUT=spring/src/test/resources/vcard/rails_vcard_matrix.json",
          "corpus" => corpus,
          "cases" => cases
        }
        raise ActiveRecord::Rollback
      end
      Warden.test_reset!

      FileUtils.mkdir_p(output.dirname)
      File.write(output, JSON.pretty_generate(result) + "\n")
      puts "wrote #{output}"
    end
  end
end

def vcard_matrix_user
  timestamp = Time.utc(2025, 1, 1)
  user = User.new(
    id: 9901, username: "vcard-matrix", email: "vcard@matrix.test",
    first_name: "VCard", last_name: "Matrix", admin: false,
    encrypted_password: "fixed-encrypted-vcard-matrix", password_salt: "fixed-salt-vcard-matrix",
    reset_password_token: "fixed-reset-vcard-matrix", authentication_token: "fixed-auth-vcard-matrix",
    remember_token: "fixed-remember-vcard-matrix",
    confirmed_at: timestamp, created_at: timestamp, updated_at: timestamp
  )
  user.skip_confirmation! if user.respond_to?(:skip_confirmation!)
  user.confirmed_at = timestamp if user.respond_to?(:confirmed_at=)
  user.save!(validate: false)
  user
end

def vcard_matrix_seed(user)
  timestamp = Time.utc(2025, 1, 1)
  account = Account.new(
    id: 9910, name: "Example Company", user: user, access: "Public",
    created_at: timestamp, updated_at: timestamp
  )
  account.save!(validate: false)
  unnamed_account = Account.new(
    id: 9911, name: "", user: user, access: "Public",
    created_at: timestamp, updated_at: timestamp
  )
  unnamed_account.save!(validate: false)
  contacts = [
    [9921, "Ada", "Lovelace", "Engineer", "Research", "ada@example.test", "ada.alt@example.test",
     "555-1001", "555-2001"],
    [9922, "Grace", "Noaccount", nil, nil, nil, nil, nil, nil],
    [9923, "Alan", "Nildept", "Scientist", nil, "alan@example.test", nil, "555-1003", nil],
    [9924, "Blank", "Values", "", nil, nil, nil, "", nil],
    [9925, "Semi;comma,", "New\nLine", "Title;Here,", "Dept\nLine", "a;b,c\n@example.test",
     "alt;mail@example.test", "123,456;\n789", "789,012;345"],
    [9926, "Exact", "75", nil, nil, "e" * (75 - "EMAIL;TYPE=[\"internet\", \"work\"]:".length), nil, nil, nil],
    [9927, "Zoë😀", "Multibyte", "Research😀", nil, "é😀" * 80, nil, nil, nil],
    [9928, "Twin", "Business", nil, nil, nil, nil, nil, nil],
    [9929, "Only", "OtherAddress", nil, nil, nil, nil, nil, nil]
  ]
  contacts.each do |row|
    id, first_name, last_name, title, department, email, alt_email, phone, mobile = row
    contact = Contact.new(
      id: id, first_name: first_name, last_name: last_name, title: title, department: department,
      email: email, alt_email: alt_email, phone: phone, mobile: mobile, user: user, access: "Public",
      created_at: timestamp, updated_at: timestamp
    )
    contact.save!(validate: false)
    next unless [9921, 9928, 9923].include?(id)

    AccountContact.create!(
      id: id + 1000, account_id: id == 9923 ? unnamed_account.id : account.id, contact_id: id,
      created_at: timestamp, updated_at: timestamp
    )
  end

  leads = [
    [9951, "Peter", "Company", "Consultant", "Example Inc", "peter@example.test", "peter.alt@example.test",
     "555-3001", "555-4001"],
    [9952, "Company", "Missing", nil, nil, nil, nil, nil, nil],
    [9953, "Blank", "Lead", "", "", nil, nil, "", nil],
    [9954, "Semicolon;", "Comma,", "Title;\nNew", "Org,;Line", "lead;email,\n@example.test",
     nil, "222;\n333", nil],
    [9955, "Zoë😀", "Long", nil, "Long Co", "ñ😀" * 80, nil, nil, nil]
  ]
  leads.each do |row|
    id, first_name, last_name, title, company, email, alt_email, phone, mobile = row
    lead = Lead.new(
      id: id, first_name: first_name, last_name: last_name, title: title, company: company,
      email: email, alt_email: alt_email, phone: phone, mobile: mobile,
      user: user, access: "Public", created_at: timestamp, updated_at: timestamp
    )
    lead.save!(validate: false)
  end

  [
    [10901, 9921, "Business", "1 Main", "Suite 2", "Boston", "MA", "02108", "USA"],
    [10902, 9921, "Home", "2 Home", nil, "Cambridge", "MA", "02139", "USA"],
    [10903, 9928, "Business", "First Business", nil, nil, nil, nil, nil],
    [10904, 9928, "Business", "Second Business", nil, nil, nil, nil, nil],
    [10905, 9929, "Home", "Home Only", nil, nil, nil, nil, nil]
  ].each do |row|
    id, contact_id, address_type, street1, street2, city, state, zipcode, country = row
    address = Address.new(
      id: id, addressable_type: "Contact", addressable_id: contact_id, address_type: address_type,
      street1: street1, street2: street2, city: city, state: state, zipcode: zipcode, country: country,
      created_at: timestamp, updated_at: timestamp
    )
    address.save!(validate: false)
  end
end

def vcard_matrix_cases
  [
    ["contact_all_fields_account_business_address", "/contacts/9921.vcf"],
    ["contact_no_account", "/contacts/9922.vcf"],
    ["contact_account_nil_department", "/contacts/9923.vcf"],
    ["contact_blank_values", "/contacts/9924.vcf"],
    ["contact_raw_special_characters", "/contacts/9925.vcf"],
    ["contact_exact_75_codepoints", "/contacts/9926.vcf"],
    ["contact_long_multibyte", "/contacts/9927.vcf"],
    ["contact_lowest_id_business_address", "/contacts/9928.vcf"],
    ["contact_non_business_address_only", "/contacts/9929.vcf"],
    ["lead_company_all_fields", "/leads/9951.vcf"],
    ["lead_without_company", "/leads/9952.vcf"],
    ["lead_blank_values", "/leads/9953.vcf"],
    ["lead_raw_special_characters", "/leads/9954.vcf"],
    ["lead_long_multibyte", "/leads/9955.vcf"]
  ]
end

def vcard_matrix_corpus(tables)
  connection = ActiveRecord::Base.connection
  tables.index_with do |table|
    connection.select_all("SELECT * FROM #{connection.quote_table_name(table)} ORDER BY id").to_a
  end
end
