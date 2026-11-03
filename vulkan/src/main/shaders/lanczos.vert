#version 450

precision mediump float;

layout (push_constant) uniform PushConstants {
    mat4 mvpMatrix;
} pushConstants;

layout (location = 0) in vec4 aPosition;
layout (location = 1) in vec2 aTexcoord;

layout (set = 1, binding = 0) uniform TextWidth {
    mediump float texelWidth;
};
layout (set = 1, binding = 1) uniform TexelHeight {
    mediump float texelHeight;
};

layout (location = 0) out vec2 centerTextureCoordinate;
layout (location = 1) out vec2 oneStepLeftTextureCoordinate;
layout (location = 2) out vec2 twoStepsLeftTextureCoordinate;
layout (location = 3) out vec2 threeStepsLeftTextureCoordinate;
layout (location = 4) out vec2 fourStepsLeftTextureCoordinate;
layout (location = 5) out vec2 oneStepRightTextureCoordinate;
layout (location = 6) out vec2 twoStepsRightTextureCoordinate;
layout (location = 7) out vec2 threeStepsRightTextureCoordinate;
layout (location = 8) out vec2 fourStepsRightTextureCoordinate;

void main() {
    gl_Position = pushConstants.mvpMatrix * aPosition;

    float texelWidthOffset = 1.0 / texelWidth;
    float texelHeightOffset = 1.0 / texelHeight;

    vec2 firstOffset = vec2(texelWidthOffset, texelHeightOffset);
    vec2 secondOffset = vec2(2.0 * texelWidthOffset, 2.0 * texelHeightOffset);
    vec2 thirdOffset = vec2(3.0 * texelWidthOffset, 3.0 * texelHeightOffset);
    vec2 fourthOffset = vec2(4.0 * texelWidthOffset, 4.0 * texelHeightOffset);

    centerTextureCoordinate = aTexcoord;
    oneStepLeftTextureCoordinate = aTexcoord - firstOffset;
    twoStepsLeftTextureCoordinate = aTexcoord - secondOffset;
    threeStepsLeftTextureCoordinate = aTexcoord - thirdOffset;
    fourStepsLeftTextureCoordinate = aTexcoord - fourthOffset;
    oneStepRightTextureCoordinate = aTexcoord + firstOffset;
    twoStepsRightTextureCoordinate = aTexcoord + secondOffset;
    threeStepsRightTextureCoordinate = aTexcoord + thirdOffset;
    fourStepsRightTextureCoordinate = aTexcoord + fourthOffset;
}