#version 450
layout(binding=0) uniform sampler2D tex;
layout(push_constant) uniform PushConstants { mat4 mvpMatrix; vec4 textureRect; vec4 parameters[3]; } state;
layout(location=0) in vec2 texcoord;
layout(location=0) out vec4 color;
void main() {
    vec2 stepSize = 1.0 / max(state.parameters[0].xy, vec2(0.000001));
    color = texture(tex, texcoord) * 0.38026;
    color += (texture(tex,texcoord-stepSize) + texture(tex,texcoord+stepSize)) * 0.27667;
    color += (texture(tex,texcoord-2.0*stepSize) + texture(tex,texcoord+2.0*stepSize)) * 0.08074;
    color += (texture(tex,texcoord-3.0*stepSize) + texture(tex,texcoord+3.0*stepSize)) * -0.02612;
    color += (texture(tex,texcoord-4.0*stepSize) + texture(tex,texcoord+4.0*stepSize)) * -0.02143;
}
