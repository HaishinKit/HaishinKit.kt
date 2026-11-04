#version 300 es

uniform highp mat4 uMVPMatrix;
in highp vec4 aPosition;
in highp vec2 aTexcoord;
out highp vec2 vTexcoord;

void main() {
    gl_Position = uMVPMatrix * aPosition;
    vTexcoord = aTexcoord;
}
