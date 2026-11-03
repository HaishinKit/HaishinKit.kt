#version 450

precision mediump float;

layout (push_constant) uniform PushConstants {
    mat4 mvpMatrix;
} pushConstants;

layout (location = 0) in vec4 position;
layout (location = 1) in vec2 attr;
layout (location = 0) out vec2 texcoord;

void main() {
    gl_Position = pushConstants.mvpMatrix * position;
    texcoord = attr;
}
