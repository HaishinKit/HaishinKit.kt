#version 450
layout(binding = 0) uniform sampler2D tex;
layout(push_constant) uniform PushConstants {
    mat4 mvpMatrix;
    vec4 textureRect;
    vec4 parameters[3];
} state;
layout(location = 0) in vec2 texcoord;
layout(location = 0) out vec4 color;
void main() {
    vec4 source = texture(tex, texcoord);
    color = vec4(source.rgb * state.parameters[0].rgb, source.a);
}
