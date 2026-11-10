#version 450
layout(push_constant) uniform PushConstants { mat4 mvpMatrix; vec4 textureRect; vec4 parameters[3]; } state;
layout(location=0) out vec2 texcoord;
void main() {
    vec2 uv = vec2(gl_VertexIndex / 2, gl_VertexIndex % 2);
    gl_Position = state.mvpMatrix * vec4(uv * 2.0 - 1.0, 0.0, 1.0);
    texcoord = mix(state.textureRect.xy, state.textureRect.zw, uv);
}
