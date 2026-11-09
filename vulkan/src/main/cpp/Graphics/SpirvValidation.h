#ifndef HAISHINKIT_SPIRV_VALIDATION_H
#define HAISHINKIT_SPIRV_VALIDATION_H

#include <spirv-tools/libspirv.hpp>
#include <string>
#include <vector>

namespace Graphics {
// Match Kernel's Vulkan 1.1 minimum. Validate instructions, types, IDs and the target environment,
// not only the binary header, before any untrusted module reaches vkCreateShaderModule.
inline bool ValidateSpirv(const std::vector<uint32_t> &code, std::string &diagnostic) {
    diagnostic.clear();
    spvtools::SpirvTools validator(SPV_ENV_VULKAN_1_1);
    validator.SetMessageConsumer(
        [&](spv_message_level_t, const char *, const spv_position_t &, const char *text) {
            if (diagnostic.empty())
                diagnostic = text;
        });
    return validator.Validate(code.data(), code.size());
}
} // namespace Graphics
#endif
