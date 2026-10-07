package com.fatfreecrm.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class RailsSchemaRbTest {

    @Test
    void parsesTablesColumnsNullabilityAndForeignKeys() {
        RailsSchemaRb schema = RailsSchemaRb.parse(List.of(
            "ActiveRecord::Schema[8.0].define(version: 2026_04_13_041448) do",
            "  create_table \"accounts\", force: :cascade do |t|",
            "    t.integer \"user_id\"",
            "    t.string \"name\", limit: 64, default: \"\", null: false",
            "    t.index [\"user_id\"], name: \"index_accounts_on_user_id\"",
            "  end",
            "",
            "  create_table \"groups_users\", id: false, force: :cascade do |t|",
            "    t.integer \"group_id\"",
            "  end",
            "",
            "  add_foreign_key \"active_storage_attachments\", \"active_storage_blobs\", column: \"blob_id\"",
            "end"
        ));

        assertThat(schema.version()).isEqualTo("20260413041448");
        assertThat(schema.tableNames()).containsExactly("accounts", "groups_users");
        assertThat(schema.tables().get("accounts")).containsExactly(
            new RailsSchemaRb.Column("id", false),
            new RailsSchemaRb.Column("user_id", true),
            new RailsSchemaRb.Column("name", false)
        );
        assertThat(schema.tables().get("groups_users")).containsExactly(new RailsSchemaRb.Column("group_id", true));
        assertThat(schema.foreignKeys()).containsExactly(
            new RailsSchemaRb.ForeignKey("active_storage_attachments", "active_storage_blobs", "blob_id")
        );
    }

    @Test
    void readsTheRepositorySchemaFile() throws IOException {
        RailsSchemaRb schema = RailsSchemaRb.load();

        assertThat(schema.version()).isEqualTo("20260413041448");
        assertThat(schema.tableNames()).contains("accounts", "contacts", "leads", "opportunities", "users");
        assertThat(schema.tables().get("users")).contains(new RailsSchemaRb.Column("username", false));
    }
}
