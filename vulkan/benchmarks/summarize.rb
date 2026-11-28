#!/usr/bin/env ruby
# Render the recorded trials without pooling frames from different runs.
require 'json'

document = JSON.parse(File.read(ARGV.fetch(0)))
abort 'Expected Release builds' unless %w[vulkanBuildType coreBuildType].all? { |key| document[key] == 'release' }
results = document.fetch('results')
repetitions = document.fetch('repetitions')
groups = results.group_by { |row| [row.fetch('width'), row.fetch('height'), row.fetch('targetFps')] }

def median(values)
  sorted = values.sort
  middle = sorted.length / 2
  sorted.length.odd? ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0
end

groups.each_value do |rows|
  %w[OpenGL Vulkan].each do |backend|
    trials = rows.select { |row| row['backend'] == backend }
    abort 'Incomplete trial set' unless trials.length == repetitions && trials.map { |row| row['repeat'] }.sort == (1..repetitions).to_a
    trials.reject { |row| row['skipped'] }.each do |row|
      abort 'Non-increasing timestamps' unless row.fetch('nonIncreasingPts').zero?
      abort 'Display must remain at 60 Hz' unless %w[displayHz displayHzAfter].all? { |key| (row.fetch(key) - 60).abs < 0.5 }
      picture = row.fetch('decodedPicture')
      abort 'Unexpected decoded dimensions' unless picture['width'] == row['width'] && picture['height'] == row['height']
      abort 'Uniform decoded image' unless picture.fetch('maxLuma') - picture.fetch('minLuma') >= 32
    end
  end
end

puts '| Composition / output | Target fps | OpenGL fps, median (min–max) | Vulkan fps, median (min–max) |'
puts '|---|---:|---:|---:|'
groups.sort.each do |(width, height, fps), rows|
  cells = %w[OpenGL Vulkan].map do |backend|
    trials = rows.select { |row| row['backend'] == backend }
    if trials.any? { |row| row['skipped'] }
      'unsupported'
    else
      values = trials.map { |row| row.fetch('outputFps') }
      format('%.2f (%.2f–%.2f)', median(values), values.min, values.max)
    end
  end
  puts "| #{width}×#{height} | #{fps} | #{cells.join(' | ')} |"
end

puts
puts '| Composition / target | OpenGL app CPU ms/s | Vulkan app CPU ms/s | OpenGL p95 output gap, ms | Vulkan p95 output gap, ms |'
puts '|---|---:|---:|---:|---:|'
groups.sort.each do |(width, height, fps), rows|
  cells = %w[appCpuMsPerSecond arrivalGapP95Ms].flat_map do |key|
    %w[OpenGL Vulkan].map do |backend|
      trials = rows.select { |row| row['backend'] == backend }
      trials.any? { |row| row['skipped'] } ? 'unsupported' : format(key == 'appCpuMsPerSecond' ? '%.0f' : '%.1f', median(trials.map { |row| row.fetch(key) }))
    end
  end
  puts "| #{width}×#{height} / #{fps} fps | #{cells.join(' | ')} |"
end
