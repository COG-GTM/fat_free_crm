-- TEST-ONLY DDL.
--
-- Stand-in for the Rails-managed schema (db/schema.rb) so integration tests can run against a
-- throwaway Testcontainers PostgreSQL. It reproduces ONLY the tables this service reads in
-- phase 1 (users, groups, groups_users, permissions, accounts, contacts), with Rails' column
-- types, lengths, defaults and NOT NULL constraints. Rails `integer` => integer, `string` =>
-- character varying (255 unless a limit is given), `datetime precision: nil` => timestamp(6)
-- without time zone in PostgreSQL. The `cf_*` custom-field columns Rails adds dynamically are
-- omitted on purpose. Never run this against a real database: Rails owns the real schema.

CREATE TABLE IF NOT EXISTS users (
    id                              bigserial PRIMARY KEY,
    username                        varchar(32)  NOT NULL DEFAULT '',
    email                           varchar(254),
    first_name                      varchar(32),
    last_name                       varchar(32),
    title                           varchar(64),
    company                         varchar(64),
    alt_email                       varchar(254),
    phone                           varchar(32),
    mobile                          varchar(32),
    google                          varchar(32),
    encrypted_password              varchar(255) NOT NULL DEFAULT '',
    password_salt                   varchar(255) NOT NULL DEFAULT '',
    last_sign_in_at                 timestamp,
    current_sign_in_at              timestamp,
    last_sign_in_ip                 varchar(255),
    current_sign_in_ip              varchar(255),
    sign_in_count                   integer      NOT NULL DEFAULT 0,
    deleted_at                      timestamp,
    created_at                      timestamp,
    updated_at                      timestamp,
    admin                           boolean      NOT NULL DEFAULT false,
    suspended_at                    timestamp,
    unconfirmed_email               varchar(254),
    reset_password_token            varchar(255),
    reset_password_sent_at          timestamp,
    remember_token                  varchar(255),
    remember_created_at             timestamp,
    authentication_token            varchar(255),
    confirmation_token              varchar(255),
    confirmed_at                    timestamp(6),
    confirmation_sent_at            timestamp(6),
    subscribe_to_comment_replies    boolean      NOT NULL DEFAULT true,
    receive_assigned_notifications  boolean      NOT NULL DEFAULT true,
    zoom                            varchar(128),
    teams                           varchar(128),
    signal                          varchar(128),
    instagram                       varchar(128),
    facebook                        varchar(128),
    mastodon                        varchar(128),
    bluesky                         varchar(128),
    twitter                         varchar(128),
    linkedin                        varchar(128),
    blog                            varchar(128)
);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_authentication_token ON users (authentication_token);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_confirmation_token ON users (confirmation_token);
CREATE INDEX IF NOT EXISTS index_users_on_email ON users (email);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_remember_token ON users (remember_token);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_reset_password_token ON users (reset_password_token);
CREATE UNIQUE INDEX IF NOT EXISTS index_users_on_username_and_deleted_at ON users (username, deleted_at);

CREATE TABLE IF NOT EXISTS groups (
    id          bigserial PRIMARY KEY,
    name        varchar(255),
    created_at  timestamp,
    updated_at  timestamp
);

-- Rails `id: false`: no primary key.
CREATE TABLE IF NOT EXISTS groups_users (
    group_id  integer,
    user_id   integer
);
CREATE INDEX IF NOT EXISTS index_groups_users_on_group_id_and_user_id ON groups_users (group_id, user_id);
CREATE INDEX IF NOT EXISTS index_groups_users_on_group_id ON groups_users (group_id);
CREATE INDEX IF NOT EXISTS index_groups_users_on_user_id ON groups_users (user_id);

CREATE TABLE IF NOT EXISTS permissions (
    id          bigserial PRIMARY KEY,
    user_id     integer,
    asset_type  varchar(255),
    asset_id    integer,
    created_at  timestamp,
    updated_at  timestamp,
    group_id    integer
);
CREATE INDEX IF NOT EXISTS index_permissions_on_asset_id_and_asset_type ON permissions (asset_id, asset_type);
CREATE INDEX IF NOT EXISTS index_permissions_on_group_id ON permissions (group_id);
CREATE INDEX IF NOT EXISTS index_permissions_on_user_id ON permissions (user_id);

CREATE TABLE IF NOT EXISTS accounts (
    id                   bigserial PRIMARY KEY,
    user_id              integer,
    assigned_to          integer,
    name                 varchar(64)  NOT NULL DEFAULT '',
    access               varchar(8)   DEFAULT 'Public',
    website              varchar(64),
    toll_free_phone      varchar(32),
    phone                varchar(32),
    fax                  varchar(32),
    deleted_at           timestamp,
    created_at           timestamp,
    updated_at           timestamp,
    email                varchar(254),
    background_info      varchar(255),
    rating               integer      NOT NULL DEFAULT 0,
    category             varchar(32),
    subscribed_users     text,
    contacts_count       integer      DEFAULT 0,
    opportunities_count  integer      DEFAULT 0,
    wikidata_id          varchar(255),
    latitude             numeric(10, 6),
    longitude            numeric(10, 6),
    blog                 varchar(255),
    linkedin             varchar(255),
    facebook             varchar(255),
    twitter              varchar(255),
    bluesky              varchar(255),
    instagram            varchar(255),
    mastodon             varchar(255)
);
CREATE INDEX IF NOT EXISTS index_accounts_on_assigned_to ON accounts (assigned_to);
CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_on_user_id_and_name_and_deleted_at ON accounts (user_id, name, deleted_at);

CREATE TABLE IF NOT EXISTS contacts (
    id                bigserial PRIMARY KEY,
    user_id           integer,
    lead_id           integer,
    assigned_to       integer,
    reports_to        integer,
    first_name        varchar(64)  NOT NULL DEFAULT '',
    last_name         varchar(64)  NOT NULL DEFAULT '',
    access            varchar(8)   DEFAULT 'Public',
    title             varchar(64),
    department        varchar(64),
    source            varchar(32),
    email             varchar(254),
    alt_email         varchar(254),
    phone             varchar(32),
    mobile            varchar(32),
    fax               varchar(32),
    blog              varchar(128),
    linkedin          varchar(128),
    facebook          varchar(128),
    twitter           varchar(128),
    born_on           date,
    do_not_call       boolean      NOT NULL DEFAULT false,
    deleted_at        timestamp,
    created_at        timestamp,
    updated_at        timestamp,
    background_info   varchar(255),
    subscribed_users  text,
    zoom              varchar(128),
    teams             varchar(128),
    signal            varchar(128),
    instagram         varchar(128),
    mastodon          varchar(128),
    bluesky           varchar(128)
);
CREATE INDEX IF NOT EXISTS index_contacts_on_assigned_to ON contacts (assigned_to);
CREATE UNIQUE INDEX IF NOT EXISTS id_last_name_deleted ON contacts (user_id, last_name, deleted_at);
