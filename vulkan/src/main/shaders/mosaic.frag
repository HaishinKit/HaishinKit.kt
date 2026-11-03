#version 450
precision mediump float;

layout (binding = 0) uniform sampler2D tex;
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

layout (set = 1, binding = 0) uniform MosaicScale {
    mediump float mosaicScale;
};

void main() {
    vec2 uv = texcoord;
    uv = floor(uv * mosaicScale) / mosaicScale;
    vec4 color = texture(tex, uv);
    uFragColor = vec4(color.rgb, 1.0);
}
