# Legacy authentication fixture

`src/test/resources/fixtures/rails-legacy-users.json` is generated through the
Rails authentication path, not by calling the encryptor directly from a
script. The generator creates users with `User.create!`, reads the persisted
`encrypted_password` and `password_salt` columns back from PostgreSQL, and
rolls back the transaction before writing the JSON file.

## Regenerate

Use a PostgreSQL development database and production-shaped Devise settings
(`stretches = 20`):

```bash
FFCRM_LEGACY_FIXTURE_CONFIRM=1 \
DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:55432/fat_free_crm_development \
RAILS_ENV=development \
bundle exec rake ffcrm:generate_legacy_auth_fixture
```

The task refuses production environments, non-PostgreSQL databases, database
names that do not end in `_development` or `_test`, and runs without the
explicit `FFCRM_LEGACY_FIXTURE_CONFIRM=1` acknowledgement. Created rows are
read back inside a transaction and rolled back, so rerunning the task does not
leave fixture users in the database.

After regeneration, inspect the `provenance` and `devise` metadata in the JSON,
then run:

```bash
cd spring
./gradlew test --tests com.fatfreecrm.security.AuthlogicSha512PasswordEncoderTest
```

The test verifies the declared encryptor, stretch count, pepper, gem version,
and Rails-generation provenance before checking each digest byte-for-byte. If
it fails, existing Rails users cannot log in to Spring; the fallback is a
forced-password-reset campaign.

## If generation fails

- Check that the database is reachable and has the current Rails schema.
- Confirm the command is using `RAILS_ENV=development`; the Rails test
  environment intentionally uses one stretch for speed.
- Confirm `FFCRM_LEGACY_FIXTURE_CONFIRM=1` is present.
- Confirm the loaded `devise-encryptable` version matches the fixture metadata.
- Do not weaken the task's database guards to target a production database.
