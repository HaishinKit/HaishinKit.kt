#version 450
#extension GL_GOOGLE_include_directive : require
#include "scene-sampling.glsl"
layout(binding = 0) uniform sampler2D tex;
layout(location = 0) in vec2 texcoord;
layout(location = 0) out vec4 color;
void main() {
    vec4 source = sampleSource(tex, texcoord);
    color = vec4(source.rgb * state.parameters[0].rgb, source.a);
}
