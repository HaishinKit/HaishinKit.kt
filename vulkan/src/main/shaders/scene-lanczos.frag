#version 450
#extension GL_GOOGLE_include_directive : require
#include "scene-sampling.glsl"
layout(binding=0) uniform sampler2D tex;
layout(location=0) in vec2 texcoord;
layout(location=0) out vec4 color;
void main() {
    vec2 stepSize = 1.0 / max(state.parameters[0].xy, vec2(0.000001));
    color = sampleSource(tex, texcoord) * 0.38026;
    color += (sampleSource(tex,texcoord-stepSize) + sampleSource(tex,texcoord+stepSize)) * 0.27667;
    color += (sampleSource(tex,texcoord-2.0*stepSize) + sampleSource(tex,texcoord+2.0*stepSize)) * 0.08074;
    color += (sampleSource(tex,texcoord-3.0*stepSize) + sampleSource(tex,texcoord+3.0*stepSize)) * -0.02612;
    color += (sampleSource(tex,texcoord-4.0*stepSize) + sampleSource(tex,texcoord+4.0*stepSize)) * -0.02143;
}
