# frozen_string_literal: true

require "active_support/testing/time_helpers"

# AB-268: records CanCanCan visibility (`Klass.my(user)` / `accessible_by(user.ability)`) for a
# deterministic seed so the Spring Specifications can be asserted against real Rails behavior.
module FatFreeCRM
  module Migration
    class AuthzMatrix # rubocop:disable Metrics/ClassLength -- one deterministic seed + dump, kept together
      TABLES = %w[
        users groups groups_users permissions accounts campaigns contacts leads opportunities tasks comments emails
      ].freeze
      ACTORS = %w[owner assignee shared_user group_member completer stale_member unrelated admin].freeze
      REGENERATE = "FFCRM_ENTITY_FIXTURE=1 SECRET_KEY_BASE=entity-fixture-only-secret-key-base-0123456789abcdef " \
                   "bundle exec rake ffcrm:migration:authz_matrix"

      def initialize(connection = ActiveRecord::Base.connection)
        @connection = connection
        @time = Object.new.extend(ActiveSupport::Testing::TimeHelpers)
      end

      def generate
        raise "ffcrm:migration:authz_matrix requires PostgreSQL" unless @connection.adapter_name == "PostgreSQL"

        TABLES.each do |table|
          count = @connection.select_value("SELECT COUNT(*) FROM #{@connection.quote_table_name(table)}").to_i
          raise "#{table} must be empty before authz matrix generation" unless count.zero?
        end
        sequences = save_sequences
        result = nil
        begin
          ActiveRecord::Base.transaction(requires_new: true) do
            TABLES.reject { |table| table == "groups_users" }.each { |table| @connection.reset_pk_sequence!(table) }
            @time.travel_to(Time.utc(2025, 2, 3, 4, 5, 6))
            begin
              seed
              result = { sql: dump_sql, matrix: matrix }
            ensure
              @time.travel_back
            end
            raise ActiveRecord::Rollback
          end
        ensure
          restore_sequences(sequences)
        end
        result
      end

      private

      def entities
        [Account, Campaign, Contact, Lead, Opportunity]
      end

      def seed
        @actors = ACTORS.index_with { |name| create_user(name, admin: name == "admin") }
        @sales = Group.create!(name: "Authz Sales")
        @sales.users << @actors["group_member"]
        ghost = Group.create!(name: "Authz Ghost")
        ghost.users << @actors["stale_member"]
        @records = {}
        entities.each { |klass| @records[klass.name] = seed_entity(klass, ghost) }
        # A destroyed group keeps its permission rows (no dependent: :destroy) and HABTM removes the
        # join rows. Re-insert an orphaned join row to prove group ids are read through `groups`.
        ghost_id = ghost.id
        ghost.destroy!
        @connection.execute(
          "INSERT INTO groups_users (group_id, user_id) VALUES (#{ghost_id}, #{@actors['stale_member'].id})"
        )
        # Permission rows only grant access to their own asset_type.
        Permission.create!(user: @actors["unrelated"], asset_type: "Contact",
                           asset_id: @records["Account"]["private_owned"])
        Permission.create!(user: @actors["unrelated"], asset_type: "Task", asset_id: 1)
        @records["Task"] = seed_tasks
        @records["Comment"] = seed_comments
        @records["Email"] = seed_emails
        @records["User"] = @actors.transform_values(&:id)
        @permission_writes = permission_writes
      end

      def create_user(name, admin: false)
        user = User.new(username: "authz_#{name}", email: "#{name}@authz.test", first_name: "Authz",
                        last_name: name.camelize, admin: admin, encrypted_password: "fixed-#{name}",
                        password_salt: "fixed-salt-#{name}")
        user.skip_confirmation!
        user.save!(validate: false)
        user
      end

      def seed_entity(klass, ghost)
        owner = @actors["owner"]
        inherited = klass == Lead ? "Campaign" : "Lead"
        {
          "public" => build(klass, "public", user: owner, access: "Public"),
          "private_owned" => build(klass, "private_owned", user: owner, access: "Private"),
          "private_assigned" => build(klass, "private_assigned", user: owner, assignee: @actors["assignee"],
                                                                 access: "Private"),
          "shared_user" => build(klass, "shared_user", user: owner, access: "Shared",
                                                       user_ids: [@actors["shared_user"].id]),
          "shared_group" => build(klass, "shared_group", user: owner, access: "Shared", group_ids: [@sales.id]),
          "shared_user_and_group" => build(klass, "shared_user_and_group", user: owner, access: "Shared",
                                                                           user_ids: [@actors["group_member"].id],
                                                                           group_ids: [@sales.id]),
          "shared_no_permissions" => build(klass, "shared_no_permissions", user: owner, access: "Shared"),
          "private_with_permission" => build(klass, "private_with_permission", user: owner, access: "Private",
                                                                               raw_user_permission: @actors["shared_user"]),
          "shared_ghost_group" => build(klass, "shared_ghost_group", user: owner, access: "Shared",
                                                                     group_ids: [ghost.id]),
          "inherited_access" => build(klass, "inherited_access", user: owner, access: inherited),
          "orphan_private" => build(klass, "orphan_private", user: nil, access: "Private"),
          "deleted_assigned" => build(klass, "deleted_assigned", user: owner, assignee: @actors["assignee"],
                                                                 access: "Private", deleted_at: Time.current)
        }
      end

      def build(klass, label, options)
        record = klass.new
        name_attributes(record, "Authz #{klass.name} #{label}")
        record.user = options[:user]
        record.assignee = options[:assignee]
        record.access = options.fetch(:access)
        record.deleted_at = options[:deleted_at]
        # Must set access before user_ids, because user_ids= depends on access (as in the controllers).
        record.user_ids = options[:user_ids] if options[:user_ids]
        record.group_ids = options[:group_ids] if options[:group_ids]
        record.save!(validate: false)
        Permission.create!(user: options[:raw_user_permission], asset: record) if options[:raw_user_permission]
        record.id
      end

      def name_attributes(record, name)
        if record.respond_to?(:first_name=)
          record.first_name = name
          record.last_name = "Fixture"
        else
          record.name = name
        end
      end

      def seed_tasks
        owner = @actors["owner"]
        admin = @actors["admin"]
        {
          "owned_unassigned" => task("owned_unassigned", user: owner),
          "owned_assigned" => task("owned_assigned", user: owner, assignee: @actors["assignee"]),
          "assigned_to_owner" => task("assigned_to_owner", user: admin, assignee: owner),
          "completed_by" => task("completed_by", user: admin, completed_by: @actors["completer"]),
          "unrelated" => task("unrelated", user: admin)
        }
      end

      def task(label, user:, assignee: nil, completed_by: nil)
        record = Task.new(name: "Authz task #{label}", bucket: "due_asap", user: user, assignee: assignee)
        if completed_by
          record.completed_by = completed_by.id
          record.completed_at = Time.current
        end
        record.save!(validate: false)
        record.id
      end

      # Ability ignores the parent: a comment or email by "unrelated" on a private account is still theirs.
      def seed_comments
        public_account = Account.find(@records["Account"]["public"])
        private_account = Account.find(@records["Account"]["private_owned"])
        {
          "by_owner" => comment(@actors["owner"], public_account, "owner note"),
          "by_admin" => comment(@actors["admin"], public_account, "admin note"),
          "by_unrelated_on_private" => comment(@actors["unrelated"], private_account, "unrelated note")
        }
      end

      def comment(user, commentable, text)
        Comment.create!(user: user, commentable: commentable, comment: text).id
      end

      def seed_emails
        public_account = Account.find(@records["Account"]["public"])
        private_account = Account.find(@records["Account"]["private_owned"])
        {
          "by_owner" => email("owner", public_account),
          "by_admin" => email("admin", public_account),
          "by_unrelated_on_private" => email("unrelated", private_account)
        }
      end

      def email(name, mediator)
        Email.create!(user: @actors[name], mediator: mediator, imap_message_id: "authz-#{name}",
                      sent_from: "#{name}@authz.test", sent_to: "crm@authz.test", subject: "Authz #{name}",
                      body: "body", state: "Expanded").id
      end

      def permission_writes
        account = Account.new(name: "Authz permission writes", user: @actors["owner"], access: "Private")
        account.save!(validate: false)
        steps = [
          ["share_users_and_group", { access: "Shared", user_ids: %w[shared_user group_member], group_ids: [@sales.id] }],
          ["replace_users", { user_ids: %w[group_member unrelated] }],
          ["clear_groups", { group_ids: [] }],
          ["make_private", { access: "Private" }],
          ["user_ids_while_private", { user_ids: %w[shared_user] }],
          ["share_again", { access: "Shared", user_ids: %w[assignee] }],
          ["make_public", { access: "Public" }]
        ]
        {
          "asset_type" => "Account",
          "asset_id" => account.id,
          "steps" => steps.map do |name, change|
            account = Account.find(account.id)
            account.access = change[:access] if change.key?(:access)
            account.user_ids = change[:user_ids].map { |actor| @actors.fetch(actor).id } if change.key?(:user_ids)
            account.group_ids = change[:group_ids] if change.key?(:group_ids)
            account.save!(validate: false)
            {
              "name" => name,
              "access" => change[:access],
              "user_ids" => change[:user_ids],
              "group_ids" => change[:group_ids]&.map { "sales" },
              "result_access" => account.reload.access,
              "rows" => permission_rows(account)
            }
          end
        }
      end

      def permission_rows(account)
        Permission.where(asset_type: "Account", asset_id: account.id).order(:user_id, :group_id).map do |row|
          {
            "user" => @actors.key(User.find_by(id: row.user_id)),
            "group" => row.group_id && (row.group_id == @sales.id ? "sales" : row.group_id),
            "asset_type" => row.asset_type,
            "timestamps_present" => !row.created_at.nil? && !row.updated_at.nil?,
            "created_equals_updated" => row.created_at == row.updated_at
          }
        end
      end

      def matrix
        visible = {}
        (entities + [Task, Comment, Email, User]).each do |klass|
          visible[klass.name] = ACTORS.index_with do |name|
            scope = scope_for(klass, User.find(@actors[name].id))
            { "ids" => scope.order(:id).pluck(:id), "count" => scope.count }
          end
        end
        {
          "generated_by" => "ffcrm:migration:authz_matrix",
          "regenerate" => REGENERATE,
          "actors" => @actors.transform_values(&:id),
          "groups" => { "sales" => @sales.id },
          "records" => @records,
          "visible" => visible,
          "permission_writes" => @permission_writes
        }
      end

      # Entities use their `scope :my` (accessible_by). Task.my is a dashboard view filter, not the
      # authorization rule, so tasks, comments, emails and users use accessible_by(ability) directly.
      def scope_for(klass, user)
        entities.include?(klass) ? klass.my(user) : klass.accessible_by(user.ability)
      end

      def dump_sql
        raw = @connection.raw_connection
        lines = ["-- Generated by ffcrm:migration:authz_matrix.", "-- Regenerate with: #{REGENERATE}", ""]
        TABLES.each do |table|
          order = table == "groups_users" ? "group_id, user_id" : "id"
          result = raw.exec("SELECT * FROM public.#{table} ORDER BY #{order}", nil, 0, PG::TypeMapAllStrings.new)
          columns = result.fields.map { |column| @connection.quote_column_name(column) }.join(", ")
          result.values.each do |row| # rubocop:disable Style/HashEachMethods -- PG::Result#values is an Array
            values = row.map { |value| value.nil? ? "NULL" : "'#{value.to_s.gsub("'", "''")}'" }.join(", ")
            lines << "INSERT INTO public.#{@connection.quote_table_name(table)} (#{columns}) VALUES (#{values});"
          end
          next if table == "groups_users"

          sequence = @connection.select_value("SELECT pg_catalog.pg_get_serial_sequence('public.#{table}', 'id')")
          max_id = result.values.map { |row| row[result.fields.index("id")].to_i }.max
          lines << "SELECT pg_catalog.setval('#{sequence}', #{max_id || 1}, #{max_id ? 'true' : 'false'});" if sequence
        end
        "#{lines.join("\n")}\n"
      end

      def sequence_tables
        TABLES - ["groups_users"]
      end

      def save_sequences
        sequence_tables.filter_map do |table|
          sequence = @connection.select_value("SELECT pg_catalog.pg_get_serial_sequence('public.#{table}', 'id')")
          next unless sequence

          [sequence, @connection.select_one("SELECT last_value, is_called FROM #{sequence}")]
        end
      end

      def restore_sequences(sequences)
        sequences.each do |sequence, state|
          called = [true, "t"].include?(state["is_called"])
          @connection.execute("SELECT pg_catalog.setval('#{sequence}', #{state['last_value']}, #{called})")
        end
      end
    end
  end
end
