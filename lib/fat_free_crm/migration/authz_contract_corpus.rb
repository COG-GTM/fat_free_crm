# frozen_string_literal: true

require "fat_free_crm/migration/authz_matrix"
require Rails.root.join("db/contract_fixtures").to_s

# AB-268: records `Klass.my(user)` on the AB-266 contract-diff corpus (db/contract_fixtures.rb), so
# the Spring Specifications are asserted on exactly the data the contract harness runs against.
module FatFreeCRM
  module Migration
    class AuthzContractCorpus < AuthzMatrix
      USERS = %w[admin alice bob sam carol].freeze
      EXTRA_SEQUENCES = %w[
        settings addresses tags taggings account_contacts account_opportunities contact_opportunities
      ].freeze

      def generate
        raise "unset CONTRACT_FIXTURES_RESET; the corpus is seeded inside a rollback" if ENV["CONTRACT_FIXTURES_RESET"]

        super
      ensure
        Setting.clear_cache!
      end

      private

      def seed
        ContractFixtures.load!
      end

      def sequence_tables
        super + EXTRA_SEQUENCES
      end

      def matrix
        users = USERS.index_with { |name| User.find_by!(username: name) }
        visible = (entities + [Task, Comment]).to_h do |klass|
          [klass.name, users.transform_values do |user|
            scope = scope_for(klass, user)
            { "ids" => scope.order(:id).pluck(:id), "count" => scope.count }
          end]
        end
        {
          "generated_by" => "ffcrm:migration:authz_matrix",
          "regenerate" => REGENERATE,
          "source" => "db/contract_fixtures.rb",
          "users" => users.transform_values(&:id),
          "visible" => visible
        }
      end
    end
  end
end
