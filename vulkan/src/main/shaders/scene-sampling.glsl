// Included in composition/output fragment shaders; layout matches SceneRenderer::Constants.
layout(push_constant) uniform PushConstants {
    mat4 mvpMatrix;
    vec4 textureRect;
    vec4 parameters[3];
} state;

vec4 sampleSource(sampler2D source, vec2 uv) {
    // The sampler clamps to the full hardware buffer, not its cropped subregion. Clamp each
    // filter tap to valid texel centers to prevent interpolation with excluded padding pixels.
    vec2 halfTexel = 0.5 / vec2(textureSize(source, 0));
    vec2 minimum = state.textureRect.xy + halfTexel;
    vec2 maximum = max(minimum, state.textureRect.zw - halfTexel);
    return texture(source, clamp(uv, minimum, maximum));
}
