#version 330 core
out vec2 uv;
uniform mat4 transform;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    uv = p;
    gl_Position = transform * vec4(p * 2.0 - 1.0, 0.0, 1.0) - vec4(0.25, -0.5, 0.0, 0.0);
}
