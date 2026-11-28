require 'json'
require 'zlib'

path = ARGV.fetch(0)
document = JSON.parse(path.end_with?('.gz') ? Zlib::GzipReader.open(path, &:read) : File.read(path))
raise 'Incomplete recording' unless document['complete']
rows = document.fetch('results')
%w[OpenGL Vulkan].each do |backend|
  raise 'Incomplete trial set' unless rows.select { |row| row['backend'] == backend }.map { |row| row['repeat'] }.sort == (1..document.fetch('repetitions')).to_a
end

def median(values)
  values = values.sort
  mid = values.size / 2
  values.size.odd? ? values[mid] : (values[mid - 1] + values[mid]) / 2.0
end

puts '| Stage / backend | p50 ms | p95 ms | p99 ms, median (min–max) | Worst interval ms |'
puts '|---|---:|---:|---:|---:|'
{
  'sensorIntervals' => 'Camera sensor',
  'cameraCallbackIntervals' => 'Camera callback',
  'inputBeginIntervals' => 'Surface call start',
  'inputCompleteIntervals' => 'Surface call return',
  'outputArrivalIntervals' => 'Encoded output received',
  'outputPtsIntervals' => 'Encoded output PTS'
}.each do |key, label|
  %w[OpenGL Vulkan].each do |backend|
    values = rows.select { |row| row['backend'] == backend }.map { |row| row.fetch('statistics').fetch(key) }
    p99 = values.map { |value| value.fetch('p99Ms') }
    puts format('| %s / %s | %.2f | %.2f | %.2f (%.2f–%.2f) | %.2f |', label, backend,
                median(values.map { |v| v['p50Ms'] }), median(values.map { |v| v['p95Ms'] }),
                median(p99), p99.min, p99.max, values.map { |v| v['maxMs'] }.max)
  end
end

puts
puts '| Stage / backend | <20 ms % | Within 33.333 ±5 ms % | >45 ms % | >66.667 ms % |'
puts '|---|---:|---:|---:|---:|'
{ 'inputCompleteIntervals' => 'Surface call return', 'outputArrivalIntervals' => 'Encoded output received' }.each do |key, label|
  %w[OpenGL Vulkan].each do |backend|
    values = rows.select { |row| row['backend'] == backend }.map { |row| row.fetch('statistics').fetch(key) }
    count = values.sum { |v| v['count'] }
    cells = %w[under20Count within5MsCount over45Count overTwoPeriodsCount].map { |field| values.sum { |v| v.fetch(field) } * 100.0 / count }
    puts format('| %s / %s | %.2f | %.2f | %.2f | %.2f |', label, backend, *cells)
  end
end

puts
puts '| Backend | Camera fps | Surface return fps | Output fps | Surface API p99 ms | Worst Surface API ms |'
puts '|---|---:|---:|---:|---:|---:|'
%w[OpenGL Vulkan].each do |backend|
  values = rows.select { |row| row['backend'] == backend }.map { |row| row.fetch('statistics') }
  puts format('| %s | %.2f | %.2f | %.2f | %.2f | %.2f |', backend,
              *%w[observedCameraFps inputCompleteFps outputFps].map { |key| median(values.map { |v| v.fetch(key) }) },
              median(values.map { |v| v['inputApiDuration']['p99Ms'] }), values.map { |v| v['inputApiDuration']['maxMs'] }.max)
end
