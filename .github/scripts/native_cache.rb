# Runner Ruby only: cache setup must not depend on Bundler or setup-ruby.
require 'digest'
require 'open3'

paths = %w[
  .github/actions/native-cache .github/scripts/native_cache.rb
  .github/workflows/native-cache.yml .gitmodules
  build.gradle.kts settings.gradle.kts gradle.properties gradle fastlane/Fastfile
  app/build.gradle.kts vulkan/build.gradle.kts rtc/build.gradle.kts srt/build.gradle.kts
  vulkan/CMakeLists.txt vulkan/SpirvTools.cmake vulkan/vendor
  vulkan/src/main/cpp vulkan/src/test/cpp
  rtc/src/main/cpp rtc/src/main/assets rtc/src/socketTest/cpp
  srt/src/main/cpp srt/src/socketTest/cpp
]
tree, status = Open3.capture2('git', 'ls-tree', '-r', '-z', 'HEAD', '--', *paths)
abort 'Unable to fingerprint native sources' unless status.success? && !tree.empty?

File.open(ENV.fetch('GITHUB_OUTPUT'), 'a') do |file|
  file.puts "sources=#{Digest::SHA256.hexdigest(tree)}"
  file.puts "image=#{ENV.fetch('ImageOS', 'ubuntu')}"
end
File.open(ENV.fetch('GITHUB_ENV'), 'a') do |file|
  file.puts "CCACHE_DIR=#{File.join(ENV.fetch('RUNNER_TEMP'), 'haishinkit-ccache')}"
  # Hash compiler contents, not its timestamp on a freshly provisioned runner.
  file.puts 'CCACHE_COMPILERCHECK=content'
  file.puts 'CCACHE_MAXSIZE=2G'
  file.puts 'CMAKE_C_COMPILER_LAUNCHER=ccache'
  file.puts 'CMAKE_CXX_COMPILER_LAUNCHER=ccache'
end
