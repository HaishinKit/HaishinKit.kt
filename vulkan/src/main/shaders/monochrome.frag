#version 450

layout (binding = 0) uniform sampler2D tex;
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

const vec3 monochromeScale = vec3(0.298912, 0.586611, 0.114478);

void main() {
    vec4 color = texture(tex, texcoord);
    float grayColor = dot(color.rgb, monochromeScale);
    color = vec4(vec3(grayColor), 1.0);
    uFragColor = vec4(color);
}
