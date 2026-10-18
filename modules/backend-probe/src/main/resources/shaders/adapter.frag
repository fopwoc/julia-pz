#version 330 core
in vec2 uv;
uniform sampler2D image;
uniform float weights[2];
uniform vec2 shift;
uniform int channels[2];
uniform bool enabled;
out vec4 color;
void main() {
    color = texture(image, uv + shift - vec2(0.25, 0.5)).bgra * (weights[0] + weights[1]) * float(channels[0] + channels[1]) / 8.0 * (enabled ? 1.0 : 0.0);
}
