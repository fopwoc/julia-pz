#version 330 core
in vec2 uv;
uniform sampler2D image;
uniform float weights[2];
out vec4 color;
void main() {
    color = texture(image, uv).bgra * (weights[0] + weights[1]);
}
