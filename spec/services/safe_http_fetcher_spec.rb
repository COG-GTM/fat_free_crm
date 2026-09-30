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

    describe 'request configuration' do
      let(:requests) { [] }

      before do
        stub_http
        allow(http).to receive(:request) do |req, &block|
          requests << req
          block.call(build_response(Net::HTTPOK, '200', body: 'ok'))
        end
      end

      it 'applies bounded timeouts, disables retries and identifies itself' do
        described_class.new('http://example.com/').fetch

        expect(http).to have_received(:open_timeout=).with(described_class::OPEN_TIMEOUT)
        expect(http).to have_received(:read_timeout=).with(described_class::READ_TIMEOUT)
        expect(http).to have_received(:max_retries=).with(0)
        expect(http).to have_received(:use_ssl=).with(false)
        expect(requests.first['User-Agent']).to eq(described_class::USER_AGENT)
      end

      it 'preserves the port, path and query of the requested URL' do
        described_class.new('http://example.com:8080/company/about?lang=en').fetch

        expect(Net::HTTP).to have_received(:new).with('example.com', 8080, nil)
        expect(requests.first.path).to eq('/company/about?lang=en')
      end

      it 'strips surrounding whitespace from the URL' do
        expect(described_class.new("  http://example.com/  \n").fetch).to eq('ok')
      end

      it 'accepts upper-case schemes' do
        expect(described_class.new('HTTPS://example.com/').fetch).to eq('ok')
        expect(Net::HTTP).to have_received(:new).with('example.com', 443, nil)
        expect(http).to have_received(:use_ssl=).with(true)
      end

      it 'connects to a public IPv4 literal without a DNS lookup' do
        expect(Resolv).not_to receive(:getaddresses)

        expect(described_class.new("http://#{public_ip}/").fetch).to eq('ok')
        expect(Net::HTTP).to have_received(:new).with(public_ip, 80, nil)
        expect(http).to have_received(:ipaddr=).with(public_ip)
      end

      it 'connects to a public IPv6 literal and pins the unbracketed address' do
        expect(described_class.new('http://[2606:4700:4700::1111]/').fetch).to eq('ok')
        expect(Net::HTTP).to have_received(:new).with('[2606:4700:4700::1111]', 80, nil)
        expect(http).to have_received(:ipaddr=).with('2606:4700:4700::1111')
      end

      it 'accepts hosts with both public IPv4 and IPv6 records' do
        allow(Resolv).to receive(:getaddresses).with('example.com').and_return([public_ip, '2606:4700:4700::1111'])

        expect(described_class.new('http://example.com/').fetch).to eq('ok')
        expect(http).to have_received(:ipaddr=).with(public_ip)
      end

      it 'ignores resolver records that are not IP addresses' do
        allow(Resolv).to receive(:getaddresses).with('example.com').and_return(['not-an-address', public_ip])

        expect(described_class.new('http://example.com/').fetch).to eq('ok')
        expect(http).to have_received(:ipaddr=).with(public_ip).once
      end
    end

    describe 'redirect handling' do
      let(:requests) { [] }

      def stub_http_sequence(*responses)
        stub_http
        allow(http).to receive(:request) do |req, &block|
          requests << req
          block.call(responses.shift)
        end
      end

      it 'returns nil when a redirect carries no Location header' do
        stub_http_sequence(build_response(Net::HTTPFound, '302'))

        expect(described_class.new('http://example.com/').fetch).to be_nil
        expect(Net::HTTP).to have_received(:new).once
      end

      it 'resolves relative Location headers against the current URL' do
        stub_http_sequence(
          build_response(Net::HTTPFound, '302', headers: { 'Location' => 'about?lang=en' }),
          build_response(Net::HTTPOK, '200', body: 'relative')
        )

        expect(described_class.new('http://example.com/company/index').fetch).to eq('relative')
        expect(Net::HTTP).to have_received(:new).with('example.com', 80, nil).twice
        expect(requests.map(&:path)).to eq(['/company/index', '/company/about?lang=en'])
      end

      it 'resolves absolute-path Location headers against the current host' do
        stub_http_sequence(
          build_response(Net::HTTPMovedPermanently, '301', headers: { 'Location' => '/home' }),
          build_response(Net::HTTPOK, '200', body: 'home')
        )

        expect(described_class.new('http://example.com/company/index').fetch).to eq('home')
        expect(requests.last.path).to eq('/home')
      end

      it 're-pins the connection when a redirect changes the port' do
        stub_http_sequence(
          build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://example.com:8443/' }),
          build_response(Net::HTTPOK, '200', body: 'ok')
        )

        expect(described_class.new('http://example.com/').fetch).to eq('ok')
        expect(Net::HTTP).to have_received(:new).with('example.com', 8443, nil)
        expect(http).to have_received(:ipaddr=).with(public_ip).twice
      end

      %w[ftp://example.com/file file:///etc/passwd].each do |location|
        it "refuses redirects to #{location}" do
          stub_http_sequence(build_response(Net::HTTPFound, '302', headers: { 'Location' => location }))

          expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /scheme not allowed/)
          expect(Net::HTTP).to have_received(:new).once
        end
      end

      it 'refuses redirects carrying credentials' do
        stub_http_sequence(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://user:pass@example.com/' }))

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /credentials/)
        expect(Net::HTTP).to have_received(:new).once
      end

      it 'refuses redirects to hostnames that resolve to internal addresses' do
        allow(Resolv).to receive(:getaddresses).with('internal.example.com').and_return(['10.0.0.5'])
        stub_http_sequence(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://internal.example.com/' }))

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address 10\.0\.0\.5/)
        expect(Net::HTTP).to have_received(:new).once
      end

      it 'refuses redirects to hostnames that do not resolve' do
        allow(Resolv).to receive(:getaddresses).with('nowhere.invalid').and_return([])
        stub_http_sequence(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://nowhere.invalid/' }))

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /could not resolve/)
      end

      it 'refuses unparseable Location headers' do
        stub_http_sequence(build_response(Net::HTTPFound, '302', headers: { 'Location' => 'http://exa mple.com/' }))

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /invalid URL/)
      end
    end

    describe 'address fallback' do
      let(:public_addresses) { %w[203.0.114.1 203.0.114.2 203.0.114.3 203.0.114.4 203.0.114.5] }

      before do
        allow(Resolv).to receive(:getaddresses).with('example.com').and_return(public_addresses)
        stub_http
        allow(http).to receive(:ipaddr=) { |addr| allow(http).to receive(:ipaddr).and_return(addr) }
      end

      it "tries at most #{described_class::MAX_ADDRESSES_TRIED} addresses" do
        allow(http).to receive(:start).and_raise(Errno::ECONNREFUSED)

        expect(described_class.new('http://example.com/').fetch).to be_nil
        expect(http).to have_received(:ipaddr=).exactly(described_class::MAX_ADDRESSES_TRIED).times
        public_addresses.first(described_class::MAX_ADDRESSES_TRIED).each do |addr|
          expect(http).to have_received(:ipaddr=).with(addr)
        end
      end

      it 'validates every resolved address, including those beyond the retry cap' do
        allow(Resolv).to receive(:getaddresses).with('example.com').and_return(public_addresses + ['127.0.0.1'])

        expect { described_class.new('http://example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
        expect(Net::HTTP).not_to have_received(:new)
      end

      it 'does not move to another address when the request fails after connecting' do
        allow(http).to receive(:start).and_raise(Net::ReadTimeout)

        expect(described_class.new('http://example.com/').fetch).to be_nil
        expect(http).to have_received(:ipaddr=).once
      end

      it 'does not move to another address on TLS failures' do
        allow(http).to receive(:start).and_raise(OpenSSL::SSL::SSLError)

        expect(described_class.new('https://example.com/').fetch).to be_nil
        expect(http).to have_received(:ipaddr=).once
      end

      it 'does not move to another address after receiving an error response' do
        allow(http).to receive(:request) do |_req, &block|
          block.call(build_response(Net::HTTPInternalServerError, '500'))
        end

        expect(described_class.new('http://example.com/').fetch).to be_nil
        expect(http).to have_received(:ipaddr=).once
      end
    end

    describe 'body handling' do
      it 'returns a body that is exactly at the size limit' do
        body = 'x' * described_class::MAX_BODY_BYTES
        stub_http(build_response(Net::HTTPOK, '200', body: body, headers: { 'Content-Length' => body.bytesize.to_s }))

        expect(described_class.new('http://example.com/').fetch).to eq(body)
      end

      it 'accumulates a body streamed in several chunks' do
        response = build_response(Net::HTTPOK, '200')
        allow(response).to receive(:read_body).and_yield('<html>').and_yield('</html>')
        stub_http(response)

        expect(described_class.new('http://example.com/').fetch).to eq('<html></html>')
      end

      it 'aborts once streamed chunks cumulatively exceed the limit' do
        response = build_response(Net::HTTPOK, '200')
        chunk = 'x' * (described_class::MAX_BODY_BYTES / 2)
        allow(response).to receive(:read_body) do |&block|
          block.call(chunk)
          block.call(chunk)
          block.call('x')
          raise 'read past the limit'
        end
        stub_http(response)

        expect(described_class.new('http://example.com/').fetch).to be_nil
      end

      it 'returns an empty body for a successful response without content' do
        stub_http(build_response(Net::HTTPNoContent, '204'))

        expect(described_class.new('http://example.com/').fetch).to eq('')
      end

      it 'returns nil for server errors' do
        stub_http(build_response(Net::HTTPInternalServerError, '500', body: 'boom'))

        expect(described_class.new('http://example.com/').fetch).to be_nil
      end
    end

    describe 'network errors' do
      [SocketError, Net::ReadTimeout, OpenSSL::SSL::SSLError, Net::HTTPBadResponse, Errno::ECONNRESET, EOFError].each do |error|
        it "returns nil and logs a warning on #{error}" do
          stub_http
          allow(http).to receive(:start).and_raise(error)
          allow(Rails.logger).to receive(:warn)

          expect(described_class.new('http://example.com/').fetch).to be_nil
          expect(Rails.logger).to have_received(:warn).with(a_string_including(error.name).and(a_string_including('example.com')))
        end
      end
    end

    context 'with blank or hostless input' do
      before { expect(Net::HTTP).not_to receive(:new) } # rubocop:disable RSpec/ExpectInHook

      [nil, '', '   ', 'http://', 'https:///path'].each do |url|
        it "rejects #{url.inspect}" do
          expect(Resolv).not_to receive(:getaddresses)

          expect { described_class.new(url).fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
        end
      end
    end

    context 'with additional reserved destinations' do
      before { expect(Net::HTTP).not_to receive(:new) } # rubocop:disable RSpec/ExpectInHook

      %w[
        192.0.0.1 192.0.2.1 192.88.99.1 198.18.0.1 198.19.255.255 198.51.100.1 203.0.113.1
        224.0.0.1 239.255.255.255 240.0.0.1 255.255.255.255 0.0.0.1
        [::] [64:ff9b::1] [100::1] [2001::1] [2001:db8::1] [ff02::1] [::ffff:10.0.0.1] [::ffff:a9fe:a9fe]
      ].each do |host|
        it "rejects literal address #{host}" do
          expect { described_class.new("http://#{host}/").fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
        end
      end

      %w[::1 fd12::1 fe80::1 ::ffff:127.0.0.1 ::ffff:169.254.169.254 0.0.0.0].each do |address|
        it "rejects hostnames resolving to #{address}" do
          allow(Resolv).to receive(:getaddresses).with('resolved.example.com').and_return([address])

          expect { described_class.new('http://resolved.example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /disallowed address/)
        end
      end

      it 'rejects hostnames whose only records are unparseable' do
        allow(Resolv).to receive(:getaddresses).with('garbage.example.com').and_return(['not-an-address'])

        expect { described_class.new('http://garbage.example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl, /could not resolve/)
      end

      it 'rejects scheme-relative URLs' do
        expect { described_class.new('//example.com/').fetch }.to raise_error(SafeHttpFetcher::DisallowedUrl)
      end
    end
  end
end
