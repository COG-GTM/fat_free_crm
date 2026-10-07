# frozen_string_literal: true

require "fileutils"
require "json"

namespace :ffcrm do
  namespace :migration do
    desc "Record CanCanCan visibility for the authz seed and the AB-266 contract corpus for the Spring authz tests " \
         "(OUTPUT/SQL_OUTPUT, CONTRACT_OUTPUT/CONTRACT_SQL_OUTPUT)"
    task authz_matrix: :environment do
      require "fat_free_crm/migration/authz_matrix"
      require "fat_free_crm/migration/authz_contract_corpus"
      write = lambda do |result, json_key, json_default, sql_key, sql_default|
        output = Rails.root.join(ENV.fetch(json_key, "spring/src/test/resources/authz/#{json_default}"))
        sql_output = Rails.root.join(ENV.fetch(sql_key, "spring/src/test/resources/authz/#{sql_default}"))
        FileUtils.mkdir_p(output.dirname)
        File.write(output, "#{JSON.pretty_generate(result[:matrix])}\n", encoding: "UTF-8")
        FileUtils.mkdir_p(sql_output.dirname)
        File.write(sql_output, result[:sql], encoding: "UTF-8")
        puts "Wrote #{output}"
        puts "Wrote #{sql_output}"
      end
      write.call(FatFreeCRM::Migration::AuthzMatrix.new.generate,
                 "OUTPUT", "authz_matrix.json", "SQL_OUTPUT", "authz_fixture.sql")
      write.call(FatFreeCRM::Migration::AuthzContractCorpus.new.generate,
                 "CONTRACT_OUTPUT", "contract_corpus_matrix.json", "CONTRACT_SQL_OUTPUT", "contract_corpus.sql")
    end
  end
end
