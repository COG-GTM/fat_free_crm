# frozen_string_literal: true

require "fileutils"
require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Record CanCanCan visibility for a deterministic seed (OUTPUT=json SQL_OUTPUT=sql) for the Spring authz tests"
    task authz_matrix: :environment do
      require "fat_free_crm/migration/authz_matrix"
      output = Rails.root.join(ENV.fetch("OUTPUT", "spring/src/test/resources/authz/authz_matrix.json"))
      sql_output = Rails.root.join(ENV.fetch("SQL_OUTPUT", "spring/src/test/resources/authz/authz_fixture.sql"))
      result = FatFreeCRM::Migration::AuthzMatrix.new.generate
      FileUtils.mkdir_p(output.dirname)
      File.write(output, "#{JSON.pretty_generate(result[:matrix])}\n", encoding: "UTF-8")
      FileUtils.mkdir_p(sql_output.dirname)
      File.write(sql_output, result[:sql], encoding: "UTF-8")
      puts "Wrote #{output}"
      puts "Wrote #{sql_output}"
    end
  end
end
