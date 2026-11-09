# The gitlinks pin SPIRV-Tools v2026.4 and the Headers revision from its DEPS file.
# Only the core validator is linked; executables, tests and optimizer targets are excluded.
set(SPIRV_SKIP_EXECUTABLES ON CACHE BOOL "" FORCE)
set(SPIRV_SKIP_TESTS ON CACHE BOOL "" FORCE)
set(SPIRV_WERROR OFF CACHE BOOL "" FORCE)
set(SPIRV_TOOLS_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(SPIRV-Headers_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/vendor/SPIRV-Headers")
add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/vendor/SPIRV-Tools"
                 "${CMAKE_CURRENT_BINARY_DIR}/spirv-tools" EXCLUDE_FROM_ALL)
set_target_properties(SPIRV-Tools-static PROPERTIES POSITION_INDEPENDENT_CODE ON)
