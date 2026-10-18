#version 330 core
in vec2 uv;
uniform sampler2D image;
uniform float weights[2];
uniform vec2 shift;
out vec4 color;
void main() {
    color = texture(image, uv + shift - vec2(0.25, 0.5)).bgra * (weights[0] + weights[1]);
}
