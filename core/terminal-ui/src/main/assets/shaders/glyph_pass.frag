#version 300 es
precision highp float;
precision highp int;

uniform sampler2D uMonoAtlas;
uniform sampler2D uColorAtlas;
uniform float uAtlasSizePx;
uniform float uBaselinePx;

in vec2 vLocalPx;
flat in vec2 vCellExtentPx;
flat in uint vAtlasPage;
flat in uint vStyleFlags;
flat in vec4 vUvPxRect;
flat in vec2 vGlyphOriginPx;
flat in vec4 vFgColor;
flat in vec4 vDecoColor;

out vec4 fragColor;

void main() {
    vec4 outColor = vec4(0.0);

    if (vAtlasPage == 1u || vAtlasPage == 2u) {
        vec2 glyphPos = vLocalPx - vGlyphOriginPx;
        if (glyphPos.x >= 0.0 && glyphPos.y >= 0.0 &&
            glyphPos.x < vUvPxRect.z && glyphPos.y < vUvPxRect.w) {
            vec2 atlasUv = (vUvPxRect.xy + glyphPos) / uAtlasSizePx;
            if (vAtlasPage == 1u) {
                float cov = texture(uMonoAtlas, atlasUv).r;
                cov = pow(cov, 1.0 / 1.45);
                float dimMul = ((vStyleFlags & 4u) != 0u) ? 0.62 : 1.0;
                outColor = vec4(vFgColor.rgb, vFgColor.a * cov * dimMul);
            } else {
                outColor = texture(uColorAtlas, atlasUv);
            }
        }
    }

    vec4 decoCol = (vDecoColor.a > 0.01) ? vDecoColor : vFgColor;
    uint underlineStyle = (vStyleFlags >> 5u) & 7u;
    if (underlineStyle != 0u) {
        float lineH = max(1.0, floor(vCellExtentPx.y * 0.07));
        float baseU = min(vCellExtentPx.y - lineH - 1.0, uBaselinePx + lineH);
        bool hit = false;
        if (underlineStyle == 1u) {
            hit = (vLocalPx.y >= baseU && vLocalPx.y < baseU + lineH);
        } else if (underlineStyle == 2u) {
            hit = (vLocalPx.y >= baseU - lineH && vLocalPx.y < baseU) ||
                  (vLocalPx.y >= baseU + lineH && vLocalPx.y < baseU + lineH * 2.0);
        } else if (underlineStyle == 3u) {
            float waveY = baseU + sin(vLocalPx.x * 0.65) * max(1.0, lineH);
            hit = abs(vLocalPx.y - waveY) <= max(0.75, lineH * 0.6);
        } else if (underlineStyle == 4u) {
            hit = (vLocalPx.y >= baseU && vLocalPx.y < baseU + lineH) &&
                  (mod(floor(vLocalPx.x), 3.0) < 1.5);
        } else if (underlineStyle == 5u) {
            hit = (vLocalPx.y >= baseU && vLocalPx.y < baseU + lineH) &&
                  (mod(floor(vLocalPx.x), 6.0) < 3.5);
        }
        if (hit) {
            outColor = mix(outColor, vec4(decoCol.rgb, 1.0), decoCol.a);
        }
    }

    if ((vStyleFlags & 256u) != 0u) {
        float strikeH = max(1.0, floor(vCellExtentPx.y * 0.07));
        float strikeY = vCellExtentPx.y * 0.48;
        if (vLocalPx.y >= strikeY && vLocalPx.y < strikeY + strikeH) {
            outColor = mix(outColor, vec4(vFgColor.rgb, 1.0), vFgColor.a);
        }
    }

    uint cursorShape = (vStyleFlags >> 9u) & 7u;
    if (cursorShape != 0u) {
        float bw = max(1.5, floor(vCellExtentPx.y * 0.09));
        bool hitCursor = false;
        if (cursorShape == 2u) {
            hitCursor = (vLocalPx.x < max(2.0, bw));
        } else if (cursorShape == 3u) {
            hitCursor = (vLocalPx.x < bw || vLocalPx.x >= vCellExtentPx.x - bw ||
                         vLocalPx.y < bw || vLocalPx.y >= vCellExtentPx.y - bw);
        } else if (cursorShape == 4u) {
            hitCursor = (vLocalPx.y >= vCellExtentPx.y - max(2.0, bw));
        }
        if (hitCursor) {
            outColor = mix(outColor, vec4(decoCol.rgb, 1.0), decoCol.a);
        }
    }

    if (outColor.a <= 0.001) {
        discard;
    }
    fragColor = outColor;
}
