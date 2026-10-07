# frozen_string_literal: true

require File.expand_path("../../spec_helper", __dir__)
require "rake"

RSpec.describe Rake::Task do
  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?("ffcrm:migration:seed_fixture")
  end

  it "seeds every application table the Spring domain model maps" do
    task = Rake::Task["ffcrm:migration:seed_fixture"]
    task.reenable
    expect { task.invoke }.to output(/accounts\s+4/).to_stdout

    %w[
      account_contacts account_opportunities accounts activities addresses avatars campaigns comments
      contact_opportunities contacts emails field_groups fields groups groups_users leads lists opportunities
      permissions preferences research_tools settings taggings tags tasks users versions
    ].each do |table|
      count = ActiveRecord::Base.connection.select_value("SELECT count(*) FROM #{table}")
      expect(count).to be_positive, "#{table} is empty"
    end
    expect(Account.unscoped.where.not(deleted_at: nil).count).to be_positive
    alice, bob = User.where(username: %w[alice bob]).order(:username).pluck(:id)
    expect(Account.find_by!(name: "Acme Corp").subscribed_users).to start_with(alice, bob)
  ensure
    Rake::Task["ffcrm:migration:seed_fixture"].reenable
  end
end
