# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'
require 'rake'

describe 'ffcrm:generate_legacy_auth_fixture', type: :task do
  let(:task) { Rake::Task['ffcrm:generate_legacy_auth_fixture'] }

  before do
    Rails.application.load_tasks unless Rake::Task.task_defined?('ffcrm:generate_legacy_auth_fixture')
    task.reenable
    allow(ENV).to receive(:[]).and_call_original
    allow(ENV).to receive(:[]).with('FFCRM_LEGACY_FIXTURE_CONFIRM').and_return(nil)
  end

  def stub_database_name(name)
    db_config = instance_double(ActiveRecord::DatabaseConfigurations::HashConfig, database: name)
    allow(ActiveRecord::Base).to receive(:connection_db_config).and_return(db_config)
  end

  it 'refuses to run against a database that is not development or test' do
    stub_database_name('fat_free_crm_production')

    expect { task.invoke }.to raise_error(SystemExit, /Refusing database/)
  end

  it 'refuses to write fixture rows without the explicit confirm flag' do
    stub_database_name('fat_free_crm_test')

    expect { task.invoke }.to raise_error(SystemExit, /FFCRM_LEGACY_FIXTURE_CONFIRM=1/)
  end

  it 'refuses to run against a non-PostgreSQL database' do
    stub_database_name('fat_free_crm_test')
    allow(ENV).to receive(:[]).with('FFCRM_LEGACY_FIXTURE_CONFIRM').and_return('1')
    allow(ActiveRecord::Base.connection).to receive(:adapter_name).and_return('SQLite')

    expect { task.invoke }.to raise_error(SystemExit, /non-PostgreSQL/)
  end
end
