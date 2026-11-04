#version 450

precision mediump float;

layout (binding = 0) uniform sampler2D uTexture;

layout (location = 0) in vec2 centerTextureCoordinate;
layout (location = 1) in vec2 oneStepLeftTextureCoordinate;
layout (location = 2) in vec2 twoStepsLeftTextureCoordinate;
layout (location = 3) in vec2 threeStepsLeftTextureCoordinate;
layout (location = 4) in vec2 fourStepsLeftTextureCoordinate;
layout (location = 5) in vec2 oneStepRightTextureCoordinate;
layout (location = 6) in vec2 twoStepsRightTextureCoordinate;
layout (location = 7) in vec2 threeStepsRightTextureCoordinate;
layout (location = 8) in vec2 fourStepsRightTextureCoordinate;
layout (location = 0) out vec4 uFragColor;

void main() {
    vec4 fragmentColor = texture(uTexture, centerTextureCoordinate) * 0.38026;

    fragmentColor += texture(uTexture, oneStepLeftTextureCoordinate) * 0.27667;
    fragmentColor += texture(uTexture, oneStepRightTextureCoordinate) * 0.27667;

    fragmentColor += texture(uTexture, twoStepsLeftTextureCoordinate) * 0.08074;
    fragmentColor += texture(uTexture, twoStepsRightTextureCoordinate) * 0.08074;

    fragmentColor += texture(uTexture, threeStepsLeftTextureCoordinate) * -0.02612;
    fragmentColor += texture(uTexture, threeStepsRightTextureCoordinate) * -0.02612;

    fragmentColor += texture(uTexture, fourStepsLeftTextureCoordinate) * -0.02143;
    fragmentColor += texture(uTexture, fourStepsRightTextureCoordinate) * -0.02143;

    uFragColor = fragmentColor;
}
