#include "../../main/cpp/Graphics/SpirvValidation.h"
#include <cassert>
#include <fstream>
#include <iostream>

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
    assert(assembler.Assemble(valid, &code));
    assert(Graphics::ValidateSpirv(code, diagnostic));
    // Plausible size/magic alone must not let an invalid module reach the driver.
    assert(!Graphics::ValidateSpirv({0x07230203, 0, 0, 0, 0}, diagnostic));
    auto invalid = valid;
    invalid.replace(invalid.find("OpStore %color %white"), 21, "OpStore %color %one");
    assert(assembler.Assemble(invalid, &code));
    assert(!Graphics::ValidateSpirv(code, diagnostic)); // Semantic type mismatch, not a header error.
    assert(!diagnostic.empty());
    assert(assembler.Assemble(valid, &code));
    code.pop_back();
    assert(!Graphics::ValidateSpirv(code, diagnostic));
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
