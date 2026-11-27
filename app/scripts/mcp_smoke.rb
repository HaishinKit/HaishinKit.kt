# frozen_string_literal: true

require 'json'
require 'base64'
require 'net/http'
require 'uri'

endpoint = URI(ARGV.fetch(0, 'http://127.0.0.1:8080/mcp'))
host_header = ARGV[1]
protocol_version = '2025-06-18'

post = lambda do |message, headers = {}|
  request = Net::HTTP::Post.new(endpoint)
  request['Authorization'] = "Bearer #{ENV.fetch('MCP_TOKEN')}"
  request['Content-Type'] = 'application/json'
  request['Accept'] = 'application/json, text/event-stream'
  request['MCP-Protocol-Version'] = protocol_version
  request['Host'] = host_header if host_header
  headers.each { |key, value| request[key] = value }
  request.body = JSON.generate(message)
  Net::HTTP.start(endpoint.host, endpoint.port, use_ssl: endpoint.scheme == 'https',
                 open_timeout: 5, read_timeout: 10) { |http| http.request(request) }
end

rpc = lambda do |id, method, params = {}|
  response = post.call({ jsonrpc: '2.0', id: id, method: method, params: params })
  raise "#{method}: HTTP #{response.code}: #{response.body}" unless response.code == '200'

  body = JSON.parse(response.body)
  raise "#{method}: #{body}" unless body['id'] == id && body.key?('result')

  body.fetch('result')
end

initialized = rpc.call(1, 'initialize', {
  protocolVersion: protocol_version,
  capabilities: {},
  clientInfo: { name: 'haishinkit-smoke', version: '1.0.0' }
})
raise "Unexpected server: #{initialized}" unless initialized.dig('serverInfo', 'name') == 'haishinkit-android'

protocol_version = initialized.fetch('protocolVersion')
notification = post.call({ jsonrpc: '2.0', method: 'notifications/initialized' })
raise "Notification: HTTP #{notification.code}" unless notification.code == '202'

tools = rpc.call(2, 'tools/list').fetch('tools')
raise "Missing hello_android: #{tools}" unless tools.any? { |tool| tool['name'] == 'hello_android' }

result = rpc.call(3, 'tools/call', { name: 'hello_android', arguments: {} })
raise "Unexpected tool result: #{result}" unless !result['isError'] &&
  result.fetch('content').any? { |item| item['type'] == 'text' && item['text'] == 'Hello from HaishinKit on Android!' }

if ENV['MCP_CAPTURE_OUTPUT'] || ENV['MCP_CAPTURE_EXPECT_UNAVAILABLE']
  raise 'Missing capture_frame' unless tools.any? { |tool| tool['name'] == 'capture_frame' }
  [{ maxWidth: 0 }, { quality: 101 }, { maxWidth: '320' }, { quality: nil }, { unexpected: 1 }].each_with_index do |arguments, i|
    invalid = rpc.call(10 + i, 'tools/call', { name: 'capture_frame', arguments: arguments })
    raise "Invalid arguments accepted: #{arguments}" unless invalid['isError']
  end
  capture = rpc.call(20, 'tools/call', {
    name: 'capture_frame', arguments: { maxWidth: Integer(ENV.fetch('MCP_CAPTURE_WIDTH', '640')), quality: 85 }
  })
  if ENV['MCP_CAPTURE_EXPECT_UNAVAILABLE']
    raise 'Expected capture to fail with Publish closed' unless capture['isError']
    puts 'PASS: capture unavailable with Publish closed'
  else
    raise "Capture failed: #{capture}" if capture['isError']
    image = capture.fetch('content').find { |item| item['type'] == 'image' }
    raise 'Expected JPEG image' unless image && image['mimeType'] == 'image/jpeg'
    bytes = Base64.strict_decode64(image.fetch('data'))
    raise 'Invalid JPEG bytes' unless bytes.start_with?("\xff\xd8".b) && bytes.end_with?("\xff\xd9".b)
    metadata = capture.fetch('structuredContent')
    raise "Unexpected dimensions: #{metadata}" unless metadata.fetch('width').between?(1, Integer(ENV.fetch('MCP_CAPTURE_WIDTH', '640'))) && metadata.fetch('height') > 0
    File.binwrite(ENV.fetch('MCP_CAPTURE_OUTPUT'), bytes)
    puts "PASS: capture_frame #{metadata}, #{bytes.bytesize} JPEG bytes"
  end
end

unauthorized = post.call({ jsonrpc: '2.0', id: 30, method: 'tools/list' }, { 'Authorization' => 'Bearer invalid' })
raise 'Invalid token accepted' unless unauthorized.code == '401'

message = { jsonrpc:
 '2.0', id: 4, method: 'tools/list', params: {} }
{ 'Origin' => 'https://example.invalid', 'Host' => 'example.invalid:8080' }.each do |header, value|
  response = post.call(message, { header => value })
  raise "Unexpected acceptance of #{header}: HTTP #{response.code}" unless response.code == '403'
end

puts 'PASS: initialize, initialized notification, tools/list, hello_android, Host/Origin checks'
