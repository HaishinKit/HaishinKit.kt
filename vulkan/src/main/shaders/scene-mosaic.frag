#version 450
#extension GL_GOOGLE_include_directive : require
#include "scene-sampling.glsl"
layout(binding=0) uniform sampler2D tex;
layout(location=0) in vec2 texcoord;
layout(location=0) out vec4 color;
void main() {
    float scale = max(state.parameters[0].x, 1.0);
    color = sampleSource(tex, floor(texcoord * scale) / scale);
}
