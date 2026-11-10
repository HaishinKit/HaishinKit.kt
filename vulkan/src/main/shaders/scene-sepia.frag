#version 450
#extension GL_GOOGLE_include_directive : require
#include "scene-sampling.glsl"
precision highp float;

layout (binding = 0) uniform sampler2D tex;
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

void main() {
    vec4 color = sampleSource(tex, texcoord);
    float v = color.x * 0.298912 + color.y * 0.586611 + color.z * 0.114478;
    color.x = v * 0.9;
    color.y = v * 0.7;
    color.z = v * 0.4;
    uFragColor = vec4(color);
}
