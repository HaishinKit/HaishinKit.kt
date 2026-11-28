require 'json'
require 'zlib'

module CameraIntervalStats
  module_function

  def distribution(values, cadence: false)
    raise 'No interval samples' if values.empty?
    sorted = values.sort
    mean = values.sum / values.size.to_f
    result = {
      'count' => values.size, 'meanMs' => mean,
      'stddevMs' => Math.sqrt(values.sum { |v| (v - mean)**2 } / values.size),
      'minMs' => sorted.first, 'maxMs' => sorted.last,
      'p50Ms' => sorted[(values.size * 0.50).ceil - 1],
      'p95Ms' => sorted[(values.size * 0.95).ceil - 1],
      'p99Ms' => sorted[(values.size * 0.99).ceil - 1],
      'nonPositiveCount' => values.count { |v| v <= 0 }
    }
    if cadence
      nominal = 1000.0 / 30
      result['rmseFromNominalMs'] = Math.sqrt(values.sum { |v| (v - nominal)**2 } / values.size)
      result['under20Count'] = values.count { |v| v < 20 }
      result['over45Count'] = values.count { |v| v > 45 }
      result['overTwoPeriodsCount'] = values.count { |v| v > nominal * 2 }
      result['within5MsPercent'] = values.count { |v| (v - nominal).abs <= 5 } * 100.0 / values.size
      result['within5MsCount'] = values.count { |v| (v - nominal).abs <= 5 }
      bounds = [-Float::INFINITY, 20, nominal - 5, nominal + 5, 45, nominal * 2, 100, Float::INFINITY]
      labels = ['<20', '20–28.333', '28.333–38.333', '38.333–45', '45–66.667', '66.667–100', '>=100']
      result['histogramCounts'] = labels.each_with_index.to_h { |label, i| [label, values.count { |v| v >= bounds[i] && v < bounds[i + 1] }] }
    end
    result
  end

  def gaps(timestamps)
    distribution(timestamps.each_cons(2).map { |a, b| (b - a) / 1e6 }, cadence: true)
  end

  def extract_trace(text)
    counts = text.match(/entries-in-buffer\/entries-written:\s+(\d+)\/(\d+)/)
    raise 'Missing trace buffer counters' unless counts
    raise 'Trace buffer wrapped; repeat with a larger buffer' unless counts[1] == counts[2]
    text.scan(/HK\.Input\.(GL|VK):(\d+):(\d+):(\d+):([01])/).map do |backend, pts, start, stop, accepted|
      [backend == 'GL' ? 'OpenGL' : 'Vulkan', start.to_i, stop.to_i, pts.to_i, accepted.to_i]
    end
  end

  def analyze_trial(trial, trace)
    first = trial.fetch('beginBootNs')
    last = trial.fetch('endBootNs')
    raise 'Invalid window' unless last > first
    camera = trial.fetch('camera')
    output = trial.fetch('output')
    raise 'Unexpected camera target range' unless camera.all? { |row| row[5..6] == [30, 30] }
    raise 'Events outside window' unless (camera + output).all? { |row| row[0] >= first && row[0] < last }
    raise 'Display not fixed at 60 Hz' unless %w[displayHzBefore displayHzAfter].all? { |key| (trial.fetch(key) - 60).abs < 0.5 }
    all = trace.select { |row| row[0] == trial.fetch('backend') }
    starts = all.select { |row| row[1] >= first && row[1] < last }.sort_by { |row| row[1] }
    stops = all.select { |row| row[2] >= first && row[2] < last }.sort_by { |row| row[2] }
    raise 'Too few Surface events; app tracing must be enabled' if starts.size < 3 || stops.size < 3
    raise 'Surface submission failed' unless (starts + stops).all? { |row| row[4] == 1 && row[2] >= row[1] }
    pts_index = all.to_h { |row| [row[3] / 1000, row] }
    matched = output.map { |row| pts_index[row[1]] || pts_index[row[1] - 1] || pts_index[row[1] + 1] }.compact
    nearby = all.select { |row| row[1] < last + 1_000_000_000 && row[2] >= first - 1_000_000_000 }
    trial['input'] = (nearby + matched).uniq.sort_by { |row| row[1] }.map { |row| row[1..4] }
    duration = (last - first) / 1e9
    trial['statistics'] = {
      'sensorIntervals' => gaps(camera.map { |row| row[1] }),
      'cameraCallbackIntervals' => gaps(camera.map { |row| row[0] }),
      'inputBeginIntervals' => gaps(starts.map { |row| row[1] }),
      'inputCompleteIntervals' => gaps(stops.map { |row| row[2] }),
      'inputPtsIntervals' => gaps(starts.map { |row| row[3] }),
      'inputApiDuration' => distribution(starts.map { |row| (row[2] - row[1]) / 1e6 }),
      'outputArrivalIntervals' => gaps(output.map { |row| row[0] }),
      'outputPtsIntervals' => gaps(output.map { |row| row[1] * 1000 }),
      'observedCameraFps' => camera.size / duration, 'inputBeginFps' => starts.size / duration,
      'inputCompleteFps' => stops.size / duration, 'outputFps' => output.size / duration,
      'matchedOutputPts' => matched.size, 'matchedOutputPercent' => matched.size * 100.0 / output.size,
      'captureFrameNumberGaps' => camera.each_cons(2).count { |a, b| b[2] != a[2] + 1 },
      'exposure' => distribution(camera.map { |row| row[3] / 1e6 }),
      'sensorFrameDuration' => distribution(camera.map { |row| row[4] / 1e6 })
    }
  end

  def load_recording(log, trace)
    raise 'Instrumentation did not pass' unless log.include?('OK (1 test)') && log.include?('INSTRUMENTATION_CODE: -1') && !log.include?('FAILURES!!!')
    headers = log.lines.filter { |s| s.start_with?('INSTRUMENTATION_STATUS: camera_benchmark_header=') }
    raise 'Expected one header' unless headers.size == 1
    document = JSON.parse(headers.first.split('camera_benchmark_header=', 2)[1])
    raise 'Incomplete recording' unless document['complete'] && log.lines.any? { |s| s.strip == 'INSTRUMENTATION_STATUS: camera_benchmark_complete=true' }
    raise 'Expected Release builds' unless %w[coreBuildType vulkanBuildType].all? { |key| document[key] == 'release' }
    rows = log.lines.filter { |s| s.start_with?('INSTRUMENTATION_STATUS: camera_benchmark_trial=') }.map { |s| JSON.parse(s.split('camera_benchmark_trial=', 2)[1]) }
    %w[OpenGL Vulkan].each do |backend|
      raise 'Incomplete trial set' unless rows.select { |row| row['backend'] == backend }.map { |row| row['repeat'] }.sort == (1..document.fetch('repetitions')).to_a
    end
    events = extract_trace(trace)
    rows.each { |row| analyze_trial(row, events) }
    document['inputColumns'] = %w[beginBootNs endBootNs ptsNs accepted]
    document['inputClock'] = 'CLOCK_BOOTTIME / elapsedRealtimeNanos; Surface API call boundaries'
    document['results'] = rows
    document
  end
end

if $PROGRAM_NAME == __FILE__
  document = CameraIntervalStats.load_recording(File.read(ARGV.fetch(0)), File.read(ARGV.fetch(1)))
  # One numeric sample per line keeps the raw arrays inspectable without expanding every cell.
  output = JSON.pretty_generate(document).gsub(/\[\n((?:\s+\d+,?\n)+)\s*\]/) { '[' + Regexp.last_match(1).scan(/\d+/).join(', ') + ']' }
  path = ARGV.fetch(2)
  if path.end_with?('.gz')
    Zlib::GzipWriter.open(path) { |file| file.mtime = 0; file.write(output + "\n") }
  else
    File.write(path, output + "\n")
  end
end
