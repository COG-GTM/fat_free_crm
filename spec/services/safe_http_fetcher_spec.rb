# frozen_string_literal: true

require 'spec_helper'

RSpec.describe SafeHttpFetcher do
  let(:public_ip) { '93.184.216.34' }
  let(:http) { instance_double(Net::HTTP) }

  def build_response(klass, code, body: '', headers: {})
    response = klass.new('1.1', code, nil)
    headers.each { |k, v| response[k] = v }
    allow(response).to receive(:read_body) do |&block|
      block&.call(body)
      body
    end
    response
  end

  def stub_http(*responses)
    allow(Net::HTTP).to receive(:new).and_return(http)
    allow(http).to receive(:ipaddr=)
    allow(http).to receive(:use_ssl=)
    allow(http).to receive(:open_timeout=)
    allow(http).to receive(:read_timeout=)
    allow(http).to receive(:max_retries=)
    allow(http).to receive(:start).and_yield(http)
    allow(http).to receive(:request) do |_req, &block|
      block.call(responses.shift)
    end
  end

  before do
    allow(Resolv).to receive(:getaddresses).with('example.com').and_return([public_ip])
  end

  describe '#fetch' do
    it 'returns the body of a successful response' do
      stub_http(build_response(Net::HTTPOK, '200', body: '<html>ok</html>'))

      expect(described_class.new('http://example.com/').fetch).to eq('<html>ok</html>')
    end

    it 'defaults to http when no scheme is given' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))

      expect(described_class.new('example.com').fetch).to eq('ok')
      expect(Net::HTTP).to have_received(:new).with('example.com', 80)
    end

    it 'pins the connection to the resolved address' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))

      described_class.new('https://example.com/').fetch

      expect(Net::HTTP).to have_received(:new).with('example.com', 443)
      expect(http).to have_received(:ipaddr=).with(public_ip)
      expect(http).to have_received(:use_ssl=).with(true)
    end

    it 'returns nil for non-success responses' do
      stub_http(build_response(Net::HTTPNotFound, '404'))

      expect(described_class.new('http://example.com/missing').fetch).to be_nil
    end

    it 'returns nil on network errors' do
      allow(Net::HTTP).to receive(:new).and_raise(Net::OpenTimeout)

      expect(described_class.new('http://example.com/').fetch).to be_nil
    end

    it 'returns nil when the declared content length exceeds the limit' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'x', headers: { 'Content-Length' => (described_class::MAX_BODY_BYTES + 1).to_s }))

      expect(described_class.new('http://example.com/').fetch).to be_nil
    end

    it 'returns nil when the streamed body exceeds the limit' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'x' * (described_class::MAX_BODY_BYTES + 1)))

      expect(described_class.new('http://example.com/').fetch).to be_nil
    end

    it 'follows redirects to allowed hosts' do
      allow(Resolv).to receive(:getaddresses).with('www.example.com').and_return([public_ip])
      stub_http(
        build_response(Net::HTTPMovedPermanently, '301', headers: { 'Location' => 'https://www.example.com/home' }),
        build_response(Net::HTTPOK, '200', body: 'redirected')
      )

      expect(described_class.new('http://example.com/').fetch).to eq('redirected')
      expect(Net::HTTP).to have_received(:new).with('www.example.com', 443)
    end

    it 'refuses redirects to internal addresses' do
      stub_http(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://169.254.169.254/latest/meta-data/' }))

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      expect(Net::HTTP).to have_received(:new).once
    end

    it 'gives up after too many redirects' do
      responses = Array.new(described_class::MAX_REDIRECTS) do
        build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://example.com/again' })
      end
      stub_http(*responses)

      expect(described_class.new('http://example.com/').fetch).to be_nil
    end

    context 'with disallowed destinations' do
      before { expect(Net::HTTP).not_to receive(:new) } # rubocop:disable RSpec/ExpectInHook

      %w[ftp://example.com/file file:///etc/passwd gopher://example.com javascript:alert(1)].each do |url|
        it "rejects #{url}" do
          expect { described_class.new(url).fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
        end
      end

      %w[
        127.0.0.1 0.0.0.0 10.0.0.1 172.16.0.1 192.168.1.1 169.254.169.254 100.64.0.1
        [::1] [::ffff:127.0.0.1] [fe80::1] [fd00::1]
      ].each do |host|
        it "rejects literal address #{host}" do
          expect { described_class.new("http://#{host}/").fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
        end
      end

      it 'rejects hostnames resolving to internal addresses' do
        allow(Resolv).to receive(:getaddresses).with('internal.example.com').and_return(['10.1.2.3'])

        expect { described_class.new('http://internal.example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end

      it 'rejects hostnames with any internal address among their records' do
        allow(Resolv).to receive(:getaddresses).with('mixed.example.com').and_return([public_ip, '127.0.0.1'])

        expect { described_class.new('http://mixed.example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end

      it 'rejects hostnames that do not resolve' do
        allow(Resolv).to receive(:getaddresses).with('nowhere.invalid').and_return([])

        expect { described_class.new('http://nowhere.invalid/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end

      it 'rejects URLs with credentials' do
        expect { described_class.new('http://user:pass@example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end

      it 'rejects unparseable URLs' do
        expect { described_class.new('http://exa mple.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end
    end
  end
end
