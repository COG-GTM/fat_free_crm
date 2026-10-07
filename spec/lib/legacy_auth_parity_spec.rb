# frozen_string_literal: true

# Copyright (c) 2008-2013 Michael Dvorkin and contributors.
#
# Fat Free CRM is freely distributable under the terms of MIT license.
# See MIT-LICENSE file or http://www.opensource.org/licenses/mit-license.php
#------------------------------------------------------------------------------
require 'spec_helper'

# Pins the Rails/Devise behaviour that the Spring Boot authentication port
# (spring/src/main/java/com/fatfreecrm/security) claims parity with, using the
# committed fixture the Java tests consume rather than a freshly generated one.
describe User do
  let(:fixture) do
    JSON.parse(Rails.root.join('spring/src/test/resources/auth/rails-legacy-users.json').read)
  end

  before do
    allow(User).to receive(:stretches).and_return(fixture['stretches'])
  end

  def build_legacy_user(attrs)
    user = User.new(
      username: attrs['username'],
      email: attrs['email'],
      first_name: attrs['first_name'],
      last_name: attrs['last_name'],
      admin: attrs['admin']
    )
    user.encrypted_password = attrs['encrypted_password']
    user.password_salt = attrs['password_salt']
    user.confirmed_at = Time.zone.now if attrs['confirmed']
    user.suspended_at = Time.zone.now if attrs['suspended']
    user.save!(validate: false)
    user
  end

  it 'describes the production encryptor and stretch count' do
    expect(fixture['encryptor']).to eq(User.encryptor.to_s)
    expect(fixture['encryptor']).to eq('authlogic_sha512')
    expect(fixture['stretches']).to eq(20)
    expect(User.pepper).to be_nil
    expect(fixture['users'].pluck('username')).to include(
      'legacy_plain', 'legacy_admin', 'Legacy_MixedCase', 'legacy_suspended', 'legacy_unconfirmed'
    )
  end

  it 'stores digests equal to SHA-512 of (password + salt), re-hashed stretches times, in lowercase hex' do
    fixture['users'].each do |attrs|
      expected = attrs['password'] + attrs['password_salt']
      fixture['stretches'].times { expected = Digest::SHA512.hexdigest(expected) }

      expect(attrs['encrypted_password']).to eq(expected), "digest mismatch for #{attrs['username']}"
      expect(attrs['encrypted_password']).to match(/\A[0-9a-f]{128}\z/)
      expect(
        User.encryptor_class.digest(attrs['password'], fixture['stretches'], attrs['password_salt'], User.pepper)
      ).to eq(attrs['encrypted_password'])
    end
  end

  it 'accepts every fixture password and rejects wrong, blank, cased and padded variants through Devise' do
    fixture['users'].each do |attrs|
      user = build_legacy_user(attrs)

      expect(user.valid_password?(attrs['password'])).to be(true), "Rails rejected #{attrs['username']}"
      expect(user.valid_password?("#{attrs['password']}x")).to be(false)
      expect(user.valid_password?(attrs['password'].swapcase)).to be(false) if attrs['password'] =~ /[a-zA-Z]/
      expect(user.valid_password?(attrs['password'].strip)).to be(false) if attrs['password'] != attrs['password'].strip
      expect(user.valid_password?('')).to be(false)
      expect(user.valid_password?(nil)).to be(false)
    end
  end

  it 'refuses to authenticate when the stored digest is blank' do
    attrs = fixture['users'].find { |u| u['username'] == 'legacy_plain' }
    user = build_legacy_user(attrs)
    user.encrypted_password = ''

    expect(user.valid_password?(attrs['password'])).to be(false)
  end

  it 'resolves the login case-insensitively against username or email, first row by id winning' do
    plain = build_legacy_user(fixture['users'].find { |u| u['username'] == 'legacy_plain' })
    mixed = build_legacy_user(fixture['users'].find { |u| u['username'] == 'Legacy_MixedCase' })
    impostor = build_legacy_user(
      fixture['users'].find { |u| u['username'] == 'legacy_admin' }.merge('username' => 'later_row', 'email' => 'legacy_plain')
    )

    expect(User.find_for_database_authentication(email: 'legacy_plain')).to eq(plain)
    expect(User.find_for_database_authentication(email: 'LEGACY_PLAIN')).to eq(plain)
    expect(User.find_for_database_authentication(email: plain.email.upcase)).to eq(plain)
    expect(User.find_for_database_authentication(email: 'legacy_mixedcase')).to eq(mixed)
    expect(User.find_for_database_authentication(email: mixed.email.swapcase)).to eq(mixed)
    expect(User.find_for_database_authentication(email: 'later_row')).to eq(impostor)
    expect(User.find_for_database_authentication(email: 'no_such_login')).to be_nil
    # The overridden finder only downcases; Devise's strip_whitespace_keys is bypassed, so padding is not trimmed.
    expect(User.find_for_database_authentication(email: '  legacy_plain  ')).to be_nil
    expect(User.find_for_database_authentication(email: " #{plain.email} ")).to be_nil
    expect(User.find_for_database_authentication(username: 'legacy_plain')).to be_nil
    expect(impostor.id).to be > plain.id
  end

  it 'only lets confirmed, unsuspended users authenticate' do
    statuses = fixture['users'].to_h do |attrs|
      [attrs['username'], build_legacy_user(attrs).active_for_authentication?]
    end

    expect(statuses['legacy_suspended']).to be(false)
    expect(statuses['legacy_unconfirmed']).to be(false)
    expect(statuses.except('legacy_suspended', 'legacy_unconfirmed').values).to all(be(true))
  end

  it 'tracks sign-ins the way Devise Trackable does (last = previous current, count += 1)' do
    user = build_legacy_user(fixture['users'].find { |u| u['username'] == 'legacy_plain' })
    first_request = instance_double(ActionDispatch::Request, remote_ip: '203.0.113.7')
    second_request = instance_double(ActionDispatch::Request, remote_ip: '198.51.100.8')

    user.update_tracked_fields!(first_request)
    user.reload
    expect(user.sign_in_count).to eq(1)
    expect(user.current_sign_in_ip).to eq('203.0.113.7')
    expect(user.last_sign_in_ip).to eq('203.0.113.7')
    expect(user.last_sign_in_at).to eq(user.current_sign_in_at)
    first_sign_in_at = user.current_sign_in_at

    user.update_tracked_fields!(second_request)
    user.reload
    expect(user.sign_in_count).to eq(2)
    expect(user.current_sign_in_ip).to eq('198.51.100.8')
    expect(user.last_sign_in_ip).to eq('203.0.113.7')
    expect(user.last_sign_in_at).to eq(first_sign_in_at)
    expect(user.current_sign_in_at).to be >= first_sign_in_at
    expect(user.encrypted_password).to eq(fixture['users'].find { |u| u['username'] == 'legacy_plain' }['encrypted_password'])
  end
end
