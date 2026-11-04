#version 450

precision mediump float;

layout (binding = 0) uniform sampler2D tex;
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

void main() {
    uFragColor = texture(tex, texcoord);
}