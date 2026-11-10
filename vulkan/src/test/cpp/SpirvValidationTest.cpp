#include "../../main/cpp/Graphics/SpirvValidation.h"
#include <fstream>
#include <iostream>

namespace {
bool check(bool condition, const char *message) {
    if (!condition)
        std::cerr << message << '\n';
    return condition;
}
} // namespace

int main(int argc, char **argv) {

    const std::string valid = R"(
OpCapability Shader
OpMemoryModel Logical GLSL450
OpEntryPoint Fragment %main "main" %color
OpExecutionMode %main OriginUpperLeft
OpDecorate %color Location 0
%void = OpTypeVoid
%fn = OpTypeFunction %void
%float = OpTypeFloat 32
%vector = OpTypeVector %float 4
%pointer = OpTypePointer Output %vector
%color = OpVariable %pointer Output
%one = OpConstant %float 1
%white = OpConstantComposite %vector %one %one %one %one
%main = OpFunction %void None %fn
%entry = OpLabel
OpStore %color %white
OpReturn
OpFunctionEnd
)";
    spvtools::SpirvTools assembler(SPV_ENV_VULKAN_1_1);
    std::vector<uint32_t> code;
    std::string diagnostic;
    if (!check(assembler.Assemble(valid, &code), "assemble valid module"))
        return 1;
    if (!check(Graphics::ValidateSpirv(code, diagnostic), "validate valid module"))
        return 1;
    // Plausible size/magic alone must not let an invalid module reach the driver.
    if (!check(!Graphics::ValidateSpirv({0x07230203, 0, 0, 0, 0}, diagnostic), "reject malformed header"))
        return 1;
    auto invalid = valid;
    invalid.replace(invalid.find("OpStore %color %white"), 21, "OpStore %color %one");
    if (!check(assembler.Assemble(invalid, &code), "assemble type-mismatch module"))
        return 1;
    if (!check(!Graphics::ValidateSpirv(code, diagnostic), "reject semantic type mismatch"))
        return 1;
    if (!check(!diagnostic.empty(), "validator diagnostic"))
        return 1;
    if (!check(assembler.Assemble(valid, &code) && !code.empty(), "assemble before truncation"))
        return 1;
    code.pop_back();
    if (!check(!Graphics::ValidateSpirv(code, diagnostic), "reject truncated module"))
        return 1;
    for (int i = 1; i < argc; ++i) {
        std::ifstream file(argv[i], std::ios::binary | std::ios::ate);
        const auto length = static_cast<std::streamoff>(file.tellg());
        if (!file || length < 20 || length % 4 != 0)
            return 1;
        code.resize(static_cast<size_t>(length) / 4);
        file.seekg(0);
        file.read(reinterpret_cast<char *>(code.data()), length);
        if (!file || !Graphics::ValidateSpirv(code, diagnostic)) {
            std::cerr << argv[i] << ": " << diagnostic << '\n';
            return 1;
        }
    }
    std::cout << "SPIR-V regression cases and " << (argc - 1) << " shader assets passed\n";
}
