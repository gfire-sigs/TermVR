#version 300 es
precision mediump float;

flat in vec4 vBgColor;
out vec4 fragColor;

void main() {
    if (vBgColor.a <= 0.001) {
        discard;
    }
    fragColor = vBgColor;
}
