#version 300 es
precision highp float;
precision highp int;

layout(location = 0) in uvec2 aGridPos;
layout(location = 1) in uvec2 aSpanAtlas;
layout(location = 2) in uint aStyleFlags;
layout(location = 3) in uvec4 aUvRect;
layout(location = 4) in ivec2 aBearing;
layout(location = 5) in uvec3 aColors;

uniform vec2 uPaneSizePx;
uniform vec2 uCellSizePx;

flat out vec4 vBgColor;

vec4 unpackRgba(uint packed) {
    return vec4(
        float((packed >> 24u) & 0xFFu) / 255.0,
        float((packed >> 16u) & 0xFFu) / 255.0,
        float((packed >> 8u) & 0xFFu) / 255.0,
        float(packed & 0xFFu) / 255.0
    );
}

void main() {
    uint spanCols = aSpanAtlas.x;
    if (spanCols == 0u) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        vBgColor = vec4(0.0);
        return;
    }

    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 cellOrigin = vec2(float(aGridPos.x), float(aGridPos.y)) * uCellSizePx;
    vec2 cellExtent = vec2(float(spanCols) * uCellSizePx.x, uCellSizePx.y);
    vec2 px = cellOrigin + corner * cellExtent;

    vec2 ndc = (px / max(uPaneSizePx, vec2(1.0))) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vBgColor = unpackRgba(aColors.y);
}
