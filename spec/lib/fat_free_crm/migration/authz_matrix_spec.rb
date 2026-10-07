# frozen_string_literal: true

require File.expand_path("../../../spec_helper", __dir__)
require "fat_free_crm/migration/authz_matrix"
require "fat_free_crm/migration/authz_contract_corpus"

# AB-268: the generated matrix is the Rails oracle the Spring Specifications are asserted against,
# so this spec pins the CanCanCan semantics the matrix records and the generator's safety guards.
RSpec.describe FatFreeCRM::Migration::AuthzMatrix do
  before do
    skip "authz matrix generation requires PostgreSQL" unless ActiveRecord::Base.connection.adapter_name == "PostgreSQL"
  end

  let(:connection) { ActiveRecord::Base.connection }
  let(:result) { described_class.new.generate }
  let(:matrix) { result[:matrix] }
  let(:actors) { matrix.fetch("actors") }
  let(:writes_account) { matrix.fetch("permission_writes").fetch("asset_id") }

  def visible(klass, actor)
    matrix.fetch("visible").fetch(klass).fetch(actor).fetch("ids")
  end

  def record(klass, label)
    matrix.fetch("records").fetch(klass).fetch(label)
  end

  describe "#generate guards" do
    it "refuses to run on anything but PostgreSQL" do
      other = instance_double(ActiveRecord::ConnectionAdapters::AbstractAdapter, adapter_name: "SQLite")

      expect { described_class.new(other).generate }.to raise_error(RuntimeError, /requires PostgreSQL/)
    end

    it "refuses to run when a target table already has rows" do
      create(:user)

      expect { described_class.new.generate }.to raise_error(RuntimeError, "users must be empty before authz matrix generation")
    end

    it "restores every id sequence after resetting them for deterministic ids" do
      user = create(:user)
      account = create(:account, user: user)
      before = sequence_state
      [account, user].each(&:destroy!)
      expect(Account.count).to eq(0)

      described_class.new.generate

      expect(sequence_state).to eq(before)
      expect(create(:user).id).to be > user.id
    end
  end

  describe "visibility matrix" do
    it "counts agree with the ids and every actor and type is present" do
      expect(matrix.fetch("visible").keys).to match_array(%w[Account Campaign Contact Lead Opportunity Task Comment Email User])
      matrix.fetch("visible").each_value do |by_actor|
        expect(by_actor.keys).to match_array(described_class::ACTORS)
        by_actor.each_value { |cell| expect(cell.fetch("count")).to eq(cell.fetch("ids").size) }
      end
    end

    it "applies the same Public/Private/Shared rules to every CRM entity" do
      %w[Account Campaign Contact Lead Opportunity].each do |klass|
        records = matrix.fetch("records").fetch(klass)
        # The permission_writes account ends up Public; the Contact-typed permission row written for
        # "unrelated" targets the Contact whose id matches the private Account (ids reset per table).
        extra = { "Account" => [writes_account], "Contact" => [record("Account", "private_owned")] }.fetch(klass, [])

        expect(visible(klass, "unrelated")).to eq([records.fetch("public")] + extra), klass
        expect(visible(klass, "owner")).not_to include(records.fetch("orphan_private")), klass
        expect(visible(klass, "owner")).to include(records.fetch("deleted_assigned"), records.fetch("shared_no_permissions")), klass
        expect(visible(klass, "assignee")).to include(records.fetch("private_assigned"), records.fetch("deleted_assigned")), klass
        expect(visible(klass, "assignee")).not_to include(records.fetch("private_owned")), klass
        expect(visible(klass, "group_member")).to include(records.fetch("shared_group"), records.fetch("shared_user_and_group")), klass
        expect(visible(klass, "group_member")).not_to include(records.fetch("shared_user")), klass
        expect(visible(klass, "shared_user")).not_to include(records.fetch("shared_group")), klass
        expect(visible(klass, "unrelated")).not_to include(records.fetch("inherited_access")), klass
        expect(visible(klass, "admin")).to match_array((records.values + extra).uniq), klass
      end
    end

    it "grants permission rows only for their own asset_type" do
      private_account = record("Account", "private_owned")

      expect(visible("Contact", "unrelated")).to include(private_account)
      expect(visible("Account", "unrelated")).not_to include(private_account)
      expect(visible("Task", "unrelated")).to eq([])
    end

    it "authorizes tasks by creator, assignee or completer, ignoring access and group grants" do
      expect(visible("Task", "owner")).to match_array(%w[owned_unassigned owned_assigned assigned_to_owner].map { |l| record("Task", l) })
      expect(visible("Task", "assignee")).to eq([record("Task", "owned_assigned")])
      expect(visible("Task", "completer")).to eq([record("Task", "completed_by")])
      %w[shared_user group_member stale_member unrelated].each { |actor| expect(visible("Task", actor)).to eq([]), actor }
      expect(visible("Task", "admin")).to match_array(matrix.fetch("records").fetch("Task").values)
    end

    it "authorizes comments and emails by author regardless of the parent record's access" do
      %w[Comment Email].each do |klass|
        expect(visible(klass, "unrelated")).to eq([record(klass, "by_unrelated_on_private")]), klass
        expect(visible(klass, "owner")).to eq([record(klass, "by_owner")]), klass
        expect(visible(klass, "assignee")).to eq([]), klass
        expect(visible(klass, "admin")).to match_array(matrix.fetch("records").fetch(klass).values), klass
      end
    end

    it "lets every user see only themselves and admins see everyone" do
      (described_class::ACTORS - ["admin"]).each do |actor|
        expect(visible("User", actor)).to eq([actors.fetch(actor)]), actor
      end
      expect(visible("User", "admin")).to match_array(actors.values)
    end
  end

  describe "permission writes" do
    let(:steps) { matrix.fetch("permission_writes").fetch("steps").index_by { |step| step.fetch("name") } }

    def rows(step)
      steps.fetch(step).fetch("rows").map { |row| [row["user"], row["group"]] }
    end

    it "only keeps rows while access is Shared and replaces user and group grants independently" do
      expect(rows("share_users_and_group")).to contain_exactly(["shared_user", nil], ["group_member", nil], [nil, "sales"])
      expect(rows("replace_users")).to contain_exactly(["group_member", nil], ["unrelated", nil], [nil, "sales"])
      expect(rows("clear_groups")).to contain_exactly(["group_member", nil], ["unrelated", nil])
      expect(rows("make_private")).to eq([])
      expect(rows("user_ids_while_private")).to eq([])
      expect(rows("share_again")).to eq([["assignee", nil]])
      expect(rows("make_public")).to eq([])
      expect(steps.transform_values { |s| s["result_access"] }).to include(
        "make_private" => "Private", "user_ids_while_private" => "Private", "share_again" => "Shared", "make_public" => "Public"
      )
    end

    it "stamps created_at and updated_at identically on every row" do
      steps.each_value do |step|
        expect(step.fetch("rows")).to all(
          include("asset_type" => "Account", "timestamps_present" => true, "created_equals_updated" => true)
        )
      end
    end
  end

  describe "seed SQL" do
    it "contains the stale groups_users row for the destroyed ghost group so Spring reproduces it" do
      sql = result[:sql]
      stale_member = actors.fetch("stale_member")
      sales = matrix.fetch("groups").fetch("sales")

      join_rows = sql.scan(/INSERT INTO public\."groups_users" \("group_id", "user_id"\) VALUES \('(\d+)', '(\d+)'\);/)
      expect(join_rows.map { |g, u| [g.to_i, u.to_i] }).to contain_exactly(
        [sales, actors.fetch("group_member")], [sales + 1, stale_member]
      )
      expect(sql.scan('INSERT INTO public."groups" ').size).to eq(1)
      expect(sql).to include("'Authz Sales'")
      expect(sql).not_to include("'Authz Ghost'")
      expect(sql).to include("INSERT INTO public.\"permissions\"")
      expect(sql).to include("SELECT pg_catalog.setval('public.users_id_seq', #{actors.values.max}, true);")
    end
  end

  describe FatFreeCRM::Migration::AuthzContractCorpus do
    around do |example|
      previous = ENV.fetch("CONTRACT_FIXTURES_RESET", nil)
      ENV["CONTRACT_FIXTURES_RESET"] = "1"
      example.run
    ensure
      previous.nil? ? ENV.delete("CONTRACT_FIXTURES_RESET") : ENV["CONTRACT_FIXTURES_RESET"] = previous
    end

    it "refuses to run with CONTRACT_FIXTURES_RESET set, which would truncate instead of rolling back" do
      expect { described_class.new.generate }.to raise_error(RuntimeError, /CONTRACT_FIXTURES_RESET/)
      expect(User.count).to eq(0)
    end
  end

  def sequence_state
    (described_class::TABLES - ["groups_users"]).filter_map do |table|
      sequence = connection.select_value("SELECT pg_catalog.pg_get_serial_sequence('public.#{table}', 'id')")
      next unless sequence

      [sequence, connection.select_one("SELECT last_value, is_called FROM #{sequence}")]
    end
  end
end
