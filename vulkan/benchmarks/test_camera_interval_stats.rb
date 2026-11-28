require_relative 'camera_interval_stats'

stats = CameraIntervalStats.distribution([33.0, 10.0, 54.0], cadence: true)
raise 'Mean' unless (stats['meanMs'] - 97.0 / 3).abs < 1e-9
raise 'Nearest-rank quantiles' unless stats.values_at('p50Ms', 'p95Ms', 'p99Ms') == [33.0, 54.0, 54.0]
raise 'Short/long intervals' unless stats.values_at('under20Count', 'over45Count') == [1, 1]
raise 'Nominal tolerance' unless (stats['within5MsPercent'] - 100.0 / 3).abs < 1e-9
raise 'Histogram' unless stats['histogramCounts'].values.sum == 3
perfect = CameraIntervalStats.gaps([0, 33_333_333, 66_666_666, 99_999_999])
raise 'Nanoseconds to milliseconds' unless (perfect['p99Ms'] - 33.333333).abs < 1e-8 && perfect['stddevMs'] < 1e-8
text = "# entries-in-buffer/entries-written: 4/4\ntracing_mark_write: B|7|HK.Input.GL:1000000:2000000:3000000:1\ntracing_mark_write: B|7|HK.Input.VK:4000000:5000000:6000000:1\n"
raise 'Trace fields' unless CameraIntervalStats.extract_trace(text) == [['OpenGL', 2_000_000, 3_000_000, 1_000_000, 1], ['Vulkan', 5_000_000, 6_000_000, 4_000_000, 1]]
begin
  CameraIntervalStats.extract_trace(text.sub('4/4', '4/5'))
  raise 'Wrapped trace accepted'
rescue RuntimeError => error
  raise unless error.message.include?('wrapped')
end
base = 1_000_000_000
ticks = [1, 33_333_334, 66_666_667]
trial = {
  'backend' => 'OpenGL', 'beginBootNs' => base, 'endBootNs' => base + 100_000_000,
  'displayHzBefore' => 60, 'displayHzAfter' => 60,
  'camera' => ticks.each_with_index.map { |tick, i| [base + tick, 500_000_000 + tick, i, 10_000_000, 33_333_333, 30, 30] },
  'output' => ticks.each_with_index.map { |tick, i| [base + tick + 2_000_000, (i + 1) * 100, 1000] }
}
events = ticks.each_with_index.map { |tick, i| ['OpenGL', base + tick, base + tick + 1_000_000, (i + 1) * 100_000, 1] }
events += [['Vulkan', base + 2, base + 3, 123, 1], ['OpenGL', base - 5_000_000, base - 4_000_000, 999, 1]]
CameraIntervalStats.analyze_trial(trial, events)
summary = trial.fetch('statistics')
raise 'Window/backend selection' unless summary['inputBeginFps'] == 30 && summary['inputBeginIntervals']['count'] == 2
raise 'PTS units and matching' unless summary['matchedOutputPercent'] == 100
raise 'Submission call duration' unless summary['inputApiDuration']['p99Ms'] == 1
puts 'Camera interval statistics checks passed'
