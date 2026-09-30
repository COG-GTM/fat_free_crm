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
      expect(Net::HTTP).to have_received(:new).with('example.com', 80, nil)
    end

    it 'pins the connection to the resolved address and bypasses environment proxies' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))

      described_class.new('https://example.com/').fetch

      expect(Net::HTTP).to have_received(:new).with('example.com', 443, nil)
      expect(http).to have_received(:ipaddr=).with(public_ip)
      expect(http).to have_received(:use_ssl=).with(true)
    end

    it 'falls back to the next vetted address when the connection fails' do
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['203.0.114.1', public_ip])
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))
      allow(http).to receive(:start) do |&block|
        raise Errno::ECONNREFUSED if http.ipaddr == '203.0.114.1'

        block.call(http)
      end
      allow(http).to receive(:ipaddr=) { |addr| allow(http).to receive(:ipaddr).and_return(addr) }

      expect(described_class.new('http://example.com/').fetch).to eq('ok')
      expect(http).to have_received(:ipaddr=).with('203.0.114.1').ordered
      expect(http).to have_received(:ipaddr=).with(public_ip).ordered
    end

    it 'returns nil when every vetted address is unreachable' do
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['203.0.114.1', public_ip])
      stub_http
      allow(http).to receive(:start).and_raise(Errno::EHOSTUNREACH)

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(http).to have_received(:ipaddr=).twice
    end

    it 'does not log the full URL on network errors' do
      allow(Net::HTTP).to receive(:new).and_raise(Net::OpenTimeout)
      allow(Rails.logger).to receive(:warn)

      described_class.new('http://example.com/callback?token=secret123').fetch

      expect(Rails.logger).to have_received(:warn).with(a_string_including('example.com').and(satisfy { |m| m.exclude?('secret123') }))
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
      expect(Net::HTTP).to have_received(:new).with('www.example.com', 443, nil)
    end

    it 'fetches the destination after the maximum number of redirects' do
      responses = Array.new(described_class::MAX_REDIRECTS) do
        build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://example.com/again' })
      end
      stub_http(*responses, build_response(Net::HTTPOK, '200', body: 'finally'))

      expect(described_class.new('http://example.com/').fetch).to eq('finally')
    end

    it 'refuses redirects to internal addresses' do
      stub_http(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://169.254.169.254/latest/meta-data/' }))

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      expect(Net::HTTP).to have_received(:new).once
    end

    it 'gives up after too many redirects' do
      responses = Array.new(described_class::MAX_REDIRECTS + 1) do
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

  def stub_http_recording(*responses)
    requests = []
    stub_http
    allow(http).to receive(:request) do |req, &block|
      requests << req
      block.call(responses.shift)
    end
    requests
  end

  describe 'input normalization' do
    it 'strips surrounding whitespace from the URL' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))

      expect(described_class.new("  http://example.com/ \n").fetch).to eq('ok')
      expect(Net::HTTP).to have_received(:new).with('example.com', 80, nil)
    end

    it 'accepts upper-case schemes and still uses TLS for HTTPS' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))

      expect(described_class.new('HTTPS://example.com/').fetch).to eq('ok')
      expect(Net::HTTP).to have_received(:new).with('example.com', 443, nil)
      expect(http).to have_received(:use_ssl=).with(true)
    end

    [nil, '', '   '].each do |url|
      it "rejects #{url.inspect} without opening a connection" do
        expect(Net::HTTP).not_to receive(:new)

        expect { described_class.new(url).fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /missing host/)
      end
    end
  end

  describe 'connection configuration' do
    it 'applies bounded timeouts, disables retries and sends a GET with the CRM user agent' do
      requests = stub_http_recording(build_response(Net::HTTPOK, '200', body: 'ok'))

      described_class.new('http://example.com/path?q=1').fetch

      expect(http).to have_received(:open_timeout=).with(described_class::OPEN_TIMEOUT)
      expect(http).to have_received(:read_timeout=).with(described_class::READ_TIMEOUT)
      expect(http).to have_received(:max_retries=).with(0)
      expect(requests.size).to eq(1)
      expect(requests.first).to be_a(Net::HTTP::Get)
      expect(requests.first.path).to eq('/path?q=1')
      expect(requests.first['User-Agent']).to eq(described_class::USER_AGENT)
    end

    it 'does not perform DNS resolution for literal addresses' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))
      expect(Resolv).not_to receive(:getaddresses)

      expect(described_class.new("http://#{public_ip}/").fetch).to eq('ok')
      expect(http).to have_received(:ipaddr=).with(public_ip)
    end
  end

  describe 'redirect handling' do
    it 'resolves relative Location headers against the current URL' do
      requests = stub_http_recording(
        build_response(Net::HTTPFound, '302', headers: { 'Location' => '/home?x=1' }),
        build_response(Net::HTTPOK, '200', body: 'home')
      )

      expect(described_class.new('https://example.com/start').fetch).to eq('home')
      expect(requests.map(&:path)).to eq(['/start', '/home?x=1'])
      expect(Net::HTTP).to have_received(:new).with('example.com', 443, nil).twice
    end

    it 'returns nil when a redirect has no Location header' do
      stub_http(build_response(Net::HTTPFound, '302'))

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(Net::HTTP).to have_received(:new).once
    end

    it 'does not read the body of a redirect response' do
      redirect = build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://example.com/next' })
      stub_http(redirect, build_response(Net::HTTPOK, '200', body: 'next'))

      expect(described_class.new('http://example.com/').fetch).to eq('next')
      expect(redirect).not_to have_received(:read_body)
    end

    %w[ftp://example.com/file file:///etc/passwd].each do |location|
      it "refuses a redirect to #{location}" do
        stub_http(build_response(Net::HTTPFound, '302', headers: { 'Location' => location }))

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /scheme not allowed/)
        expect(Net::HTTP).to have_received(:new).once
      end
    end

    it 'refuses a redirect that embeds credentials' do
      stub_http(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://user:pass@example.com/' }))

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /credentials/)
      expect(Net::HTTP).to have_received(:new).once
    end

    it 'refuses a redirect to a hostname that resolves to an internal address' do
      allow(Resolv).to receive(:getaddresses).with('internal.example.com').and_return(['10.0.0.9'])
      stub_http(build_response(Net::HTTPMovedPermanently, '301', headers: { 'Location' => 'http://internal.example.com/' }))

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address 10\.0\.0\.9/)
      expect(Net::HTTP).to have_received(:new).once
    end
  end

  describe 'address fallback' do
    let(:addresses) { %w[203.0.114.1 203.0.114.2 203.0.114.3 203.0.114.4 203.0.114.5] }

    before { allow(Resolv).to receive(:getaddresses).with('example.com').and_return(addresses) }

    it 'tries at most MAX_ADDRESSES_TRIED addresses' do
      stub_http
      allow(http).to receive(:start).and_raise(Errno::ECONNREFUSED)

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(http).to have_received(:ipaddr=).exactly(described_class::MAX_ADDRESSES_TRIED).times
    end

    it 'still rejects a host when a disallowed address appears beyond the tried addresses' do
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(addresses.first(described_class::MAX_ADDRESSES_TRIED) + ['127.0.0.1'])
      expect(Net::HTTP).not_to receive(:new)

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address 127\.0\.0\.1/)
    end

    it 'falls back to the next address on an open timeout' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'ok'))
      attempts = 0
      allow(http).to receive(:start) do |&block|
        attempts += 1
        raise Net::OpenTimeout if attempts == 1

        block.call(http)
      end

      expect(described_class.new('http://example.com/').fetch).to eq('ok')
      expect(http).to have_received(:ipaddr=).with('203.0.114.1').ordered
      expect(http).to have_received(:ipaddr=).with('203.0.114.2').ordered
    end

    it 'does not fall back when the connected server times out on read' do
      stub_http
      allow(http).to receive(:request).and_raise(Net::ReadTimeout)

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(http).to have_received(:ipaddr=).once
    end

    it 'does not fall back on a malformed response' do
      stub_http
      allow(http).to receive(:request).and_raise(Net::HTTPBadResponse)

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(http).to have_received(:ipaddr=).once
    end

    it 'treats resolver results that are not IP addresses as unresolvable' do
      allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['not-an-ip'])
      expect(Net::HTTP).not_to receive(:new)

      expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /could not resolve/)
    end
  end

  describe 'body size limits' do
    it 'accepts a body of exactly MAX_BODY_BYTES' do
      body = 'x' * described_class::MAX_BODY_BYTES
      stub_http(build_response(Net::HTTPOK, '200', body: body, headers: { 'Content-Length' => body.bytesize.to_s }))

      expect(described_class.new('http://example.com/').fetch).to eq(body)
    end

    it 'stops reading a streamed body as soon as it exceeds the limit' do
      response = Net::HTTPOK.new('1.1', '200', nil)
      chunks = ['x' * described_class::MAX_BODY_BYTES, 'y', 'z']
      yielded = 0
      allow(response).to receive(:read_body) do |&block|
        chunks.each do |chunk|
          yielded += 1
          block.call(chunk)
        end
      end
      stub_http(response)

      expect(described_class.new('http://example.com/').fetch).to be_nil
      expect(yielded).to eq(2)
    end
  end

  describe 'address classification' do
    %w[
      192.0.0.1 192.0.2.1 192.88.99.1 198.18.0.1 198.19.255.255 198.51.100.1 203.0.113.1
      224.0.0.1 239.255.255.255 240.0.0.1 255.255.255.255
      [::] [64:ff9b::a00:1] [100::1] [2001::1] [2001:db8::1] [ff02::1] [::ffff:10.0.0.1] [::ffff:169.254.169.254]
    ].each do |host|
      it "rejects literal address #{host}" do
        expect(Net::HTTP).not_to receive(:new)

        expect { described_class.new("http://#{host}/").fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address/)
      end
    end

    it 'rejects hostnames resolving to an IPv4-mapped internal IPv6 address' do
      allow(Resolv).to receive(:getaddresses).with('mapped.example.com').and_return(['::ffff:192.168.0.10'])
      expect(Net::HTTP).not_to receive(:new)

      expect { described_class.new('http://mapped.example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address/)
    end

    it 'allows and pins a public IPv6 literal' do
      stub_http(build_response(Net::HTTPOK, '200', body: 'v6'))

      expect(described_class.new('http://[2606:2800:220:1:248:1893:25c8:1946]/').fetch).to eq('v6')
      expect(http).to have_received(:ipaddr=).with('2606:2800:220:1:248:1893:25c8:1946')
    end

    it 'allows hostnames resolving only to public IPv6 addresses' do
      allow(Resolv).to receive(:getaddresses).with('v6.example.com').and_return(['2606:2800:220:1:248:1893:25c8:1946'])
      stub_http(build_response(Net::HTTPOK, '200', body: 'v6'))

      expect(described_class.new('https://v6.example.com/').fetch).to eq('v6')
      expect(Net::HTTP).to have_received(:new).with('v6.example.com', 443, nil)
      expect(http).to have_received(:ipaddr=).with('2606:2800:220:1:248:1893:25c8:1946')
    end
  end
end
