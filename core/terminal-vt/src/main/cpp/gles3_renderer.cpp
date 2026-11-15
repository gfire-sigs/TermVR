#include "gles3_renderer.h"

#include <algorithm>
#include <cstring>

#if defined(__ANDROID__)
#include <GLES3/gl3.h>
#include <android/log.h>
#define TERMVR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "TermVR_VT", __VA_ARGS__)
#else
#define TERMVR_LOGE(...) ((void)0)
#endif

namespace termvr::vt {

namespace {

constexpr const char* kDefaultBgVert = R"(#version 300 es
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
)";

constexpr const char* kDefaultBgFrag = R"(#version 300 es
precision mediump float;

flat in vec4 vBgColor;
out vec4 fragColor;

void main() {
    if (vBgColor.a <= 0.001) {
        discard;
    }
    fragColor = vBgColor;
}
)";

constexpr const char* kDefaultGlyphVert = R"(#version 300 es
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
uniform float uBaselinePx;

out vec2 vLocalPx;
flat out vec2 vCellExtentPx;
flat out uint vAtlasPage;
flat out uint vStyleFlags;
flat out vec4 vUvPxRect;
flat out vec2 vGlyphOriginPx;
flat out vec4 vFgColor;
flat out vec4 vDecoColor;

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
        vLocalPx = vec2(0.0);
        vCellExtentPx = vec2(0.0);
        vAtlasPage = 0u;
        vStyleFlags = 0u;
        vUvPxRect = vec4(0.0);
        vGlyphOriginPx = vec2(0.0);
        vFgColor = vec4(0.0);
        vDecoColor = vec4(0.0);
        return;
    }

    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vec2 cellOrigin = vec2(float(aGridPos.x), float(aGridPos.y)) * uCellSizePx;
    vec2 cellExtent = vec2(float(spanCols) * uCellSizePx.x, uCellSizePx.y);
    vec2 px = cellOrigin + corner * cellExtent;

    vec2 ndc = (px / max(uPaneSizePx, vec2(1.0))) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);

    vLocalPx = corner * cellExtent;
    vCellExtentPx = cellExtent;
    vAtlasPage = aSpanAtlas.y;
    vStyleFlags = aStyleFlags;
    vUvPxRect = vec4(float(aUvRect.x), float(aUvRect.y), float(aUvRect.z), float(aUvRect.w));
    vGlyphOriginPx = vec2(float(aBearing.x), uBaselinePx - float(aBearing.y));
    vFgColor = unpackRgba(aColors.x);
    vDecoColor = unpackRgba(aColors.z);
}
)";

constexpr const char* kDefaultGlyphFrag = R"(#version 300 es
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
)";

[[maybe_unused]] constexpr const char* kOverlayVert = R"(#version 300 es
precision highp float;

layout(location = 0) in vec2 aPosPx;
layout(location = 1) in vec2 aUv;

uniform vec2 uViewportPx;
out vec2 vUv;

void main() {
    vec2 ndc = (aPosPx / max(uViewportPx, vec2(1.0))) * 2.0 - 1.0;
    ndc.y = -ndc.y;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
}
)";

[[maybe_unused]] constexpr const char* kOverlayFrag = R"(#version 300 es
precision mediump float;

uniform sampler2D uTex;
uniform vec4 uColor;
uniform int uSolid;

in vec2 vUv;
out vec4 fragColor;

void main() {
    if (uSolid != 0) {
        fragColor = uColor;
    } else {
        fragColor = texture(uTex, vUv) * uColor;
    }
    if (fragColor.a <= 0.001) {
        discard;
    }
}
)";

#if defined(__ANDROID__)
GLuint CompileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log_buf[512];
        glGetShaderInfoLog(shader, sizeof(log_buf), nullptr, log_buf);
        TERMVR_LOGE("Shader compile failure: %s", log_buf);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint LinkProgram(const char* vert_src, const char* frag_src) {
    GLuint vs = CompileShader(GL_VERTEX_SHADER, vert_src);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, frag_src);
    if (vs == 0 || fs == 0) {
        if (vs != 0) glDeleteShader(vs);
        if (fs != 0) glDeleteShader(fs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = GL_FALSE;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log_buf[512];
        glGetProgramInfoLog(prog, sizeof(log_buf), nullptr, log_buf);
        TERMVR_LOGE("Program link failure: %s", log_buf);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}
#endif

}  // namespace

const RendererShaderSources& Gles3Renderer::DefaultShaderSources() {
    static const RendererShaderSources kDefaults{
        kDefaultBgVert,
        kDefaultBgFrag,
        kDefaultGlyphVert,
        kDefaultGlyphFrag,
    };
    return kDefaults;
}

Gles3Renderer::Gles3Renderer() : shader_sources_(DefaultShaderSources()) {}

Gles3Renderer::~Gles3Renderer() {
    ReleaseGl();
}

void Gles3Renderer::SetShaderSources(const RendererShaderSources& sources) {
    if (!sources.bg_vert.empty()) shader_sources_.bg_vert = sources.bg_vert;
    if (!sources.bg_frag.empty()) shader_sources_.bg_frag = sources.bg_frag;
    if (!sources.glyph_vert.empty()) shader_sources_.glyph_vert = sources.glyph_vert;
    if (!sources.glyph_frag.empty()) shader_sources_.glyph_frag = sources.glyph_frag;
    if (gl_initialized_) {
        CompilePrograms();
    }
}

void Gles3Renderer::SetBundledFonts(BundledFontAssets assets) {
    font_manager_.SetBundledAssets(std::move(assets));
}

bool Gles3Renderer::ConfigureFont(const FontConfigNative& config) {
    bool ok = font_manager_.Configure(config);
    atlas_.MonoPage().Reset();
    atlas_.ColorPage().Reset();
    return ok;
}

void Gles3Renderer::DetachSession(int64_t session_handle) {
    auto it = pane_states_.find(session_handle);
    if (it == pane_states_.end()) {
        return;
    }
#if defined(__ANDROID__)
    if (gl_initialized_) {
        if (it->second.instance_vbo != 0) {
            GLuint vbo = it->second.instance_vbo;
            glDeleteBuffers(1, &vbo);
        }
        if (!it->second.kitty_textures.empty()) {
            glDeleteTextures(
                static_cast<GLsizei>(it->second.kitty_textures.size()),
                it->second.kitty_textures.data());
        }
    }
#endif
    pane_states_.erase(it);
}

bool Gles3Renderer::CompilePrograms() {
#if defined(__ANDROID__)
    if (bg_program_ != 0) {
        glDeleteProgram(bg_program_);
        bg_program_ = 0;
    }
    if (glyph_program_ != 0) {
        glDeleteProgram(glyph_program_);
        glyph_program_ = 0;
    }
    if (overlay_program_ != 0) {
        glDeleteProgram(overlay_program_);
        overlay_program_ = 0;
    }

    bg_program_ = LinkProgram(shader_sources_.bg_vert.c_str(), shader_sources_.bg_frag.c_str());
    glyph_program_ = LinkProgram(shader_sources_.glyph_vert.c_str(), shader_sources_.glyph_frag.c_str());
    overlay_program_ = LinkProgram(kOverlayVert, kOverlayFrag);
    return bg_program_ != 0 && glyph_program_ != 0 && overlay_program_ != 0;
#else
    return true;
#endif
}

bool Gles3Renderer::InitializeGl() {
#if defined(__ANDROID__)
    if (gl_initialized_) {
        return true;
    }
    if (!CompilePrograms()) {
        return false;
    }

    GLuint vao = 0;
    glGenVertexArrays(1, &vao);
    shared_vao_ = vao;

    GLuint ovbo = 0;
    glGenBuffers(1, &ovbo);
    overlay_vbo_ = ovbo;

    auto create_atlas_tex = [](GLenum internal_fmt, GLenum fmt) -> GLuint {
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, static_cast<GLint>(internal_fmt),
                     kAtlasWidth, kAtlasHeight, 0, fmt, GL_UNSIGNED_BYTE, nullptr);
        return tex;
    };

    mono_atlas_tex_ = create_atlas_tex(GL_R8, GL_RED);
    color_atlas_tex_ = create_atlas_tex(GL_RGBA8, GL_RGBA);
    glBindTexture(GL_TEXTURE_2D, 0);

    gl_initialized_ = true;
    return true;
#else
    gl_initialized_ = true;
    return true;
#endif
}

void Gles3Renderer::ReleaseGl() {
#if defined(__ANDROID__)
    if (!gl_initialized_) {
        return;
    }
    for (auto& kv : pane_states_) {
        if (kv.second.instance_vbo != 0) {
            GLuint vbo = kv.second.instance_vbo;
            glDeleteBuffers(1, &vbo);
            kv.second.instance_vbo = 0;
        }
        if (!kv.second.kitty_textures.empty()) {
            glDeleteTextures(
                static_cast<GLsizei>(kv.second.kitty_textures.size()),
                kv.second.kitty_textures.data());
            kv.second.kitty_textures.clear();
        }
    }
    if (mono_atlas_tex_ != 0) {
        GLuint t = mono_atlas_tex_;
        glDeleteTextures(1, &t);
        mono_atlas_tex_ = 0;
    }
    if (color_atlas_tex_ != 0) {
        GLuint t = color_atlas_tex_;
        glDeleteTextures(1, &t);
        color_atlas_tex_ = 0;
    }
    if (overlay_vbo_ != 0) {
        GLuint b = overlay_vbo_;
        glDeleteBuffers(1, &b);
        overlay_vbo_ = 0;
    }
    if (shared_vao_ != 0) {
        GLuint v = shared_vao_;
        glDeleteVertexArrays(1, &v);
        shared_vao_ = 0;
    }
    if (bg_program_ != 0) {
        glDeleteProgram(bg_program_);
        bg_program_ = 0;
    }
    if (glyph_program_ != 0) {
        glDeleteProgram(glyph_program_);
        glyph_program_ = 0;
    }
    if (overlay_program_ != 0) {
        glDeleteProgram(overlay_program_);
        overlay_program_ = 0;
    }
#endif
    gl_initialized_ = false;
}

void Gles3Renderer::NotifyContextLost(uint64_t new_epoch) {
    egl_context_epoch_ = new_epoch;
    gl_initialized_ = false;
    bg_program_ = 0;
    glyph_program_ = 0;
    overlay_program_ = 0;
    shared_vao_ = 0;
    overlay_vbo_ = 0;
    mono_atlas_tex_ = 0;
    color_atlas_tex_ = 0;

    for (auto& kv : pane_states_) {
        kv.second.instance_vbo = 0;
        kv.second.instance_capacity_bytes = 0;
        kv.second.kitty_textures.clear();
        kv.second.kitty_revision = 0;
    }
    atlas_.InvalidateForContextLoss(new_epoch);
}

void Gles3Renderer::SyncAtlasTexturesToGl() {
#if defined(__ANDROID__)
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    if (atlas_.MonoPage().ConsumeClearPending()) {
        std::vector<uint8_t> zeros(kAtlasArea, 0u);
        glBindTexture(GL_TEXTURE_2D, mono_atlas_tex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kAtlasWidth, kAtlasHeight,
                        GL_RED, GL_UNSIGNED_BYTE, zeros.data());
    }
    auto mono_rects = atlas_.MonoPage().DrainPendingUploads();
    if (!mono_rects.empty()) {
        glBindTexture(GL_TEXTURE_2D, mono_atlas_tex_);
        for (const auto& r : mono_rects) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, r.x, r.y, r.w, r.h,
                            GL_RED, GL_UNSIGNED_BYTE, r.pixels.data());
            last_uploaded_bytes_ += r.pixels.size();
        }
    }

    if (atlas_.ColorPage().ConsumeClearPending()) {
        std::vector<uint8_t> zeros(static_cast<size_t>(kAtlasArea) * 4u, 0u);
        glBindTexture(GL_TEXTURE_2D, color_atlas_tex_);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, kAtlasWidth, kAtlasHeight,
                        GL_RGBA, GL_UNSIGNED_BYTE, zeros.data());
    }
    auto color_rects = atlas_.ColorPage().DrainPendingUploads();
    if (!color_rects.empty()) {
        glBindTexture(GL_TEXTURE_2D, color_atlas_tex_);
        for (const auto& r : color_rects) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, r.x, r.y, r.w, r.h,
                            GL_RGBA, GL_UNSIGNED_BYTE, r.pixels.data());
            last_uploaded_bytes_ += r.pixels.size();
        }
    }
    glBindTexture(GL_TEXTURE_2D, 0);
#else
    (void)atlas_.MonoPage().ConsumeClearPending();
    (void)atlas_.ColorPage().ConsumeClearPending();
    for (const auto& r : atlas_.MonoPage().DrainPendingUploads()) {
        last_uploaded_bytes_ += r.pixels.size();
    }
    for (const auto& r : atlas_.ColorPage().DrainPendingUploads()) {
        last_uploaded_bytes_ += r.pixels.size();
    }
#endif
}

void Gles3Renderer::SyncKittyTexturesToGl(
    PaneGpuState* pane,
    const std::vector<KittyGraphicPlacement>& placements) {
#if defined(__ANDROID__)
    if (!pane->kitty_textures.empty()) {
        glDeleteTextures(static_cast<GLsizei>(pane->kitty_textures.size()), pane->kitty_textures.data());
        pane->kitty_textures.clear();
    }
    pane->kitty_placements = placements;
    for (const auto& pl : placements) {
        if (pl.pixel_width == 0 || pl.pixel_height == 0 || pl.rgba_pixels.empty()) {
            pane->kitty_textures.push_back(0);
            continue;
        }
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA8,
            static_cast<GLsizei>(pl.pixel_width),
            static_cast<GLsizei>(pl.pixel_height),
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pl.rgba_pixels.data());
        pane->kitty_textures.push_back(tex);
        last_uploaded_bytes_ += pl.rgba_pixels.size();
    }
    glBindTexture(GL_TEXTURE_2D, 0);
#else
    pane->kitty_placements = placements;
#endif
}

void Gles3Renderer::ConfigureInstanceAttributes(uint32_t vbo) {
#if defined(__ANDROID__)
    glBindVertexArray(shared_vao_);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);

    constexpr GLsizei kStride = static_cast<GLsizei>(sizeof(CellGpuInstance));
    glEnableVertexAttribArray(0);
    glVertexAttribIPointer(0, 2, GL_UNSIGNED_SHORT, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, grid_col)));
    glVertexAttribDivisor(0, 1);

    glEnableVertexAttribArray(1);
    glVertexAttribIPointer(1, 2, GL_UNSIGNED_BYTE, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, span_cols)));
    glVertexAttribDivisor(1, 1);

    glEnableVertexAttribArray(2);
    glVertexAttribIPointer(2, 1, GL_UNSIGNED_SHORT, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, style_flags)));
    glVertexAttribDivisor(2, 1);

    glEnableVertexAttribArray(3);
    glVertexAttribIPointer(3, 4, GL_UNSIGNED_SHORT, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, uv_x)));
    glVertexAttribDivisor(3, 1);

    glEnableVertexAttribArray(4);
    glVertexAttribIPointer(4, 2, GL_SHORT, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, bearing_x)));
    glVertexAttribDivisor(4, 1);

    glEnableVertexAttribArray(5);
    glVertexAttribIPointer(5, 3, GL_UNSIGNED_INT, kStride, reinterpret_cast<const void*>(offsetof(CellGpuInstance, fg_rgba)));
    glVertexAttribDivisor(5, 1);
#else
    (void)vbo;
#endif
}

void Gles3Renderer::DrawTexturedQuad(
    int32_t pane_w,
    int32_t pane_h,
    float x0,
    float y0,
    float x1,
    float y1,
    uint32_t gl_tex,
    uint32_t tint_rgba,
    bool is_solid) {
#if defined(__ANDROID__)
    if (!gl_initialized_ || overlay_program_ == 0) {
        return;
    }
    const float verts[16] = {
        x0, y0, 0.0f, 0.0f,
        x1, y0, 1.0f, 0.0f,
        x0, y1, 0.0f, 1.0f,
        x1, y1, 1.0f, 1.0f,
    };
    glUseProgram(overlay_program_);
    glUniform2f(glGetUniformLocation(overlay_program_, "uViewportPx"),
                static_cast<float>(pane_w), static_cast<float>(pane_h));
    glUniform4f(glGetUniformLocation(overlay_program_, "uColor"),
                static_cast<float>((tint_rgba >> 24u) & 0xFFu) / 255.0f,
                static_cast<float>((tint_rgba >> 16u) & 0xFFu) / 255.0f,
                static_cast<float>((tint_rgba >> 8u) & 0xFFu) / 255.0f,
                static_cast<float>(tint_rgba & 0xFFu) / 255.0f);
    glUniform1i(glGetUniformLocation(overlay_program_, "uSolid"), is_solid ? 1 : 0);
    if (!is_solid && gl_tex != 0) {
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gl_tex);
        glUniform1i(glGetUniformLocation(overlay_program_, "uTex"), 0);
    }

    GLuint temp_vao = 0;
    glGenVertexArrays(1, &temp_vao);
    glBindVertexArray(temp_vao);
    glBindBuffer(GL_ARRAY_BUFFER, overlay_vbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STREAM_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<const void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<const void*>(2 * sizeof(float)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glDeleteVertexArrays(1, &temp_vao);
    ++last_draw_calls_;
#else
    (void)pane_w; (void)pane_h; (void)x0; (void)y0; (void)x1; (void)y1;
    (void)gl_tex; (void)tint_rgba; (void)is_solid;
    ++last_draw_calls_;
#endif
}

void Gles3Renderer::DrawPreeditOverlay(
    int32_t surface_w,
    int32_t surface_h,
    const PaneViewportSpec& pane,
    const CellMetrics& metrics) {
    if (!pane.preedit.active || pane.preedit.text_utf8.empty()) {
        return;
    }
    uint32_t char_cols = 0;
    for (size_t i = 0; i < pane.preedit.text_utf8.size();) {
        unsigned char b = static_cast<unsigned char>(pane.preedit.text_utf8[i]);
        size_t adv = 1;
        uint32_t cp = b;
        if ((b & 0xE0u) == 0xC0u && i + 1 < pane.preedit.text_utf8.size()) {
            adv = 2;
            cp = ((b & 0x1Fu) << 6u) | (static_cast<unsigned char>(pane.preedit.text_utf8[i + 1]) & 0x3Fu);
        } else if ((b & 0xF0u) == 0xE0u && i + 2 < pane.preedit.text_utf8.size()) {
            adv = 3;
            cp = ((b & 0x0Fu) << 12u) |
                 ((static_cast<unsigned char>(pane.preedit.text_utf8[i + 1]) & 0x3Fu) << 6u) |
                 (static_cast<unsigned char>(pane.preedit.text_utf8[i + 2]) & 0x3Fu);
        } else if ((b & 0xF8u) == 0xF0u && i + 3 < pane.preedit.text_utf8.size()) {
            adv = 4;
            cp = 0x1F600u;
        }
        char_cols += FontManager::IsCjkOrHangulWide(cp) ? 2u : 1u;
        i += adv;
    }

    float box_w = static_cast<float>(std::max<uint32_t>(1u, char_cols) * metrics.cell_width_px + 6u);
    float box_h = static_cast<float>(metrics.cell_height_px + 4u);
    float x0 = static_cast<float>(pane.x_px) + static_cast<float>(pane.preedit.cursor_col * metrics.cell_width_px);
    float y0 = static_cast<float>(pane.y_px) + static_cast<float>(pane.preedit.cursor_row * metrics.cell_height_px);
    x0 = std::min(x0, std::max(0.0f, static_cast<float>(pane.x_px + pane.width_px) - box_w));
    y0 = std::min(y0, std::max(0.0f, static_cast<float>(pane.y_px + pane.height_px) - box_h));

    DrawTexturedQuad(surface_w, surface_h, x0, y0, x0 + box_w, y0 + box_h, 0, pane.preedit.bg_rgba, true);
    DrawTexturedQuad(surface_w, surface_h, x0, y0 + box_h - 2.0f, x0 + box_w, y0 + box_h,
                     0, pane.preedit.underline_rgba, true);
}

bool Gles3Renderer::RenderFrame(
    int32_t surface_width_px,
    int32_t surface_height_px,
    const std::vector<PaneViewportSpec>& panes,
    const std::vector<SplitDividerSpec>& dividers,
    bool force_full_redraw,
    uint32_t surface_clear_rgba) {
    if (!gl_initialized_ && !InitializeGl()) {
        return false;
    }
    if (surface_width_px <= 0 || surface_height_px <= 0) {
        return false;
    }

    last_draw_calls_ = 0;
    last_uploaded_bytes_ = 0;
    atlas_.BeginFrame();
    const CellMetrics metrics = font_manager_.Metrics();

#if defined(__ANDROID__)
    glDisable(GL_DEPTH_TEST);
    glEnable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glViewport(0, 0, surface_width_px, surface_height_px);
    glScissor(0, 0, surface_width_px, surface_height_px);
    glClearColor(
        static_cast<float>((surface_clear_rgba >> 24u) & 0xFFu) / 255.0f,
        static_cast<float>((surface_clear_rgba >> 16u) & 0xFFu) / 255.0f,
        static_cast<float>((surface_clear_rgba >> 8u) & 0xFFu) / 255.0f,
        static_cast<float>(surface_clear_rgba & 0xFFu) / 255.0f);
    glClear(GL_COLOR_BUFFER_BIT);
#else
    (void)surface_clear_rgba;
#endif

    const bool atlas_rebuilt = atlas_.ConsumeNeedsRebuild();
    for (const PaneViewportSpec& pane_spec : panes) {
        if (pane_spec.session_handle == 0 || pane_spec.width_px <= 0 || pane_spec.height_px <= 0) {
            continue;
        }
        auto* session = reinterpret_cast<VtSessionNative*>(static_cast<intptr_t>(pane_spec.session_handle));
        if (!session->IsValid()) {
            continue;
        }

        PaneGpuState& gpu_state = pane_states_[pane_spec.session_handle];
        bool resized = session->ConsumeGridResized();
        bool need_init = (gpu_state.cols == 0 || gpu_state.rows == 0);
#if defined(__ANDROID__)
        need_init = need_init || (gpu_state.instance_vbo == 0);
#endif
        bool rebuild_all = force_full_redraw || resized || atlas_rebuilt || need_init;

        ExtractedFrameSnapshot snap;
        std::vector<uint16_t> uploaded_rows;
        size_t row_stride_bytes = 0;
        if (session->ExtractDirtyFrame(rebuild_all, &snap)) {
            row_stride_bytes = static_cast<size_t>(snap.cols) * sizeof(CellGpuInstance);
            size_t total_bytes = static_cast<size_t>(snap.rows) * row_stride_bytes;
            if (gpu_state.cols != snap.cols || gpu_state.rows != snap.rows ||
                gpu_state.instance_capacity_bytes < total_bytes || need_init) {
                gpu_state.cols = snap.cols;
                gpu_state.rows = snap.rows;
                gpu_state.instance_capacity_bytes = total_bytes;
                gpu_state.row_instances.assign(snap.rows, std::vector<CellGpuInstance>(snap.cols));
                gpu_state.cached_rows.assign(snap.rows, {});
                gpu_state.last_cursor_row = -1;
                gpu_state.last_cursor_col = -1;
                gpu_state.last_cursor_drawn = false;
                gpu_state.last_cursor_shape = CursorShape::kNone;
#if defined(__ANDROID__)
                if (gpu_state.instance_vbo == 0) {
                    GLuint vbo = 0;
                    glGenBuffers(1, &vbo);
                    gpu_state.instance_vbo = vbo;
                }
                glBindBuffer(GL_ARRAY_BUFFER, gpu_state.instance_vbo);
                glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(total_bytes), nullptr, GL_DYNAMIC_DRAW);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
#endif
            }

            if (snap.kitty_revision != gpu_state.kitty_revision) {
                gpu_state.kitty_revision = snap.kitty_revision;
                SyncKittyTexturesToGl(&gpu_state, snap.kitty_placements);
            }

            gpu_state.last_palette = snap.palette;
            const bool draw_cursor = pane_spec.focused && snap.cursor.visible && pane_spec.cursor_blink_visible;
            const int32_t cur_cursor_row = draw_cursor ? static_cast<int32_t>(snap.cursor.row) : -1;
            const int32_t cur_cursor_col = draw_cursor ? static_cast<int32_t>(snap.cursor.col) : -1;
            const CursorShape cur_cursor_shape = draw_cursor ? snap.cursor.shape : CursorShape::kNone;

            auto shapeAndStageRow = [&](uint16_t row_idx, const std::vector<ExtractedCell>& cells) {
                int32_t cursor_col = -1;
                CursorShape cshape = CursorShape::kNone;
                if (draw_cursor && cur_cursor_row == static_cast<int32_t>(row_idx)) {
                    cursor_col = cur_cursor_col;
                    cshape = cur_cursor_shape;
                }
                std::vector<CellGpuInstance> shaped_tmp;
                font_manager_.ShapeRow(
                    row_idx,
                    cells,
                    cursor_col,
                    cshape,
                    gpu_state.last_palette.cursor_rgba,
                    gpu_state.last_palette.selection_rgba,
                    &atlas_,
                    &shaped_tmp);

                auto& dst_row = gpu_state.row_instances[row_idx];
                std::memset(dst_row.data(), 0, dst_row.size() * sizeof(CellGpuInstance));
                size_t copy_cnt = std::min(shaped_tmp.size(), dst_row.size());
                if (copy_cnt > 0) {
                    std::memcpy(dst_row.data(), shaped_tmp.data(), copy_cnt * sizeof(CellGpuInstance));
                }
                uploaded_rows.push_back(row_idx);
            };

            std::vector<bool> row_updated(gpu_state.rows, false);
            uploaded_rows.reserve(snap.dirty_rows.size() + 2u);
            for (const ExtractedRow& erow : snap.dirty_rows) {
                if (erow.row_index >= gpu_state.rows) {
                    continue;
                }
                gpu_state.cached_rows[erow.row_index] = erow.cells;
                shapeAndStageRow(erow.row_index, erow.cells);
                row_updated[erow.row_index] = true;
            }

            const bool cursor_changed =
                draw_cursor != gpu_state.last_cursor_drawn ||
                cur_cursor_row != gpu_state.last_cursor_row ||
                cur_cursor_col != gpu_state.last_cursor_col ||
                cur_cursor_shape != gpu_state.last_cursor_shape;
            if (cursor_changed) {
                if (gpu_state.last_cursor_drawn &&
                    gpu_state.last_cursor_row >= 0 &&
                    gpu_state.last_cursor_row < static_cast<int32_t>(gpu_state.rows)) {
                    uint16_t prev_r = static_cast<uint16_t>(gpu_state.last_cursor_row);
                    if (!row_updated[prev_r] && !gpu_state.cached_rows[prev_r].empty()) {
                        shapeAndStageRow(prev_r, gpu_state.cached_rows[prev_r]);
                        row_updated[prev_r] = true;
                    }
                }
                if (draw_cursor &&
                    cur_cursor_row >= 0 &&
                    cur_cursor_row < static_cast<int32_t>(gpu_state.rows)) {
                    uint16_t cur_r = static_cast<uint16_t>(cur_cursor_row);
                    if (!row_updated[cur_r] && !gpu_state.cached_rows[cur_r].empty()) {
                        shapeAndStageRow(cur_r, gpu_state.cached_rows[cur_r]);
                        row_updated[cur_r] = true;
                    }
                }
            }
            gpu_state.last_cursor_row = cur_cursor_row;
            gpu_state.last_cursor_col = cur_cursor_col;
            gpu_state.last_cursor_drawn = draw_cursor;
            gpu_state.last_cursor_shape = cur_cursor_shape;

            SyncAtlasTexturesToGl();
        }

        if (gpu_state.cols == 0 || gpu_state.rows == 0) {
            continue;
        }

#if defined(__ANDROID__)
        if (gpu_state.instance_vbo == 0) {
            continue;
        }
        if (!uploaded_rows.empty()) {
            glBindBuffer(GL_ARRAY_BUFFER, gpu_state.instance_vbo);
            for (uint16_t r : uploaded_rows) {
                GLintptr off = static_cast<GLintptr>(static_cast<size_t>(r) * row_stride_bytes);
                glBufferSubData(GL_ARRAY_BUFFER, off, static_cast<GLsizeiptr>(row_stride_bytes),
                                gpu_state.row_instances[r].data());
                last_uploaded_bytes_ += row_stride_bytes;
            }
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }

        GLint gl_y = surface_height_px - (pane_spec.y_px + pane_spec.height_px);
        glViewport(pane_spec.x_px, gl_y, pane_spec.width_px, pane_spec.height_px);
        glScissor(pane_spec.x_px, gl_y, pane_spec.width_px, pane_spec.height_px);

        GLsizei instance_count = static_cast<GLsizei>(static_cast<size_t>(gpu_state.cols) * gpu_state.rows);
        ConfigureInstanceAttributes(gpu_state.instance_vbo);

        // Pass 1: Background quads.
        glUseProgram(bg_program_);
        glUniform2f(glGetUniformLocation(bg_program_, "uPaneSizePx"),
                    static_cast<float>(pane_spec.width_px), static_cast<float>(pane_spec.height_px));
        glUniform2f(glGetUniformLocation(bg_program_, "uCellSizePx"),
                    static_cast<float>(metrics.cell_width_px), static_cast<float>(metrics.cell_height_px));
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instance_count);
        ++last_draw_calls_;

        // Clipped Kitty inline graphics behind text (z < 0).
        for (size_t i = 0; i < gpu_state.kitty_placements.size(); ++i) {
            const auto& pl = gpu_state.kitty_placements[i];
            if (pl.z_index >= 0 || i >= gpu_state.kitty_textures.size()) {
                continue;
            }
            float kx0 = static_cast<float>(pl.grid_col * metrics.cell_width_px);
            float ky0 = static_cast<float>(pl.grid_row * metrics.cell_height_px);
            float kx1 = kx0 + static_cast<float>(pl.span_cols * metrics.cell_width_px);
            float ky1 = ky0 + static_cast<float>(pl.span_rows * metrics.cell_height_px);
            DrawTexturedQuad(pane_spec.width_px, pane_spec.height_px,
                             kx0, ky0, kx1, ky1, gpu_state.kitty_textures[i], 0xFFFFFFFFu, false);
        }

        // Pass 2: Glyphs, underlines, strikethrough, and cursor shapes.
        ConfigureInstanceAttributes(gpu_state.instance_vbo);
        glUseProgram(glyph_program_);
        glUniform2f(glGetUniformLocation(glyph_program_, "uPaneSizePx"),
                    static_cast<float>(pane_spec.width_px), static_cast<float>(pane_spec.height_px));
        glUniform2f(glGetUniformLocation(glyph_program_, "uCellSizePx"),
                    static_cast<float>(metrics.cell_width_px), static_cast<float>(metrics.cell_height_px));
        glUniform1f(glGetUniformLocation(glyph_program_, "uBaselinePx"),
                    static_cast<float>(metrics.baseline_px));
        glUniform1f(glGetUniformLocation(glyph_program_, "uAtlasSizePx"),
                    static_cast<float>(kAtlasWidth));

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, mono_atlas_tex_);
        glUniform1i(glGetUniformLocation(glyph_program_, "uMonoAtlas"), 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, color_atlas_tex_);
        glUniform1i(glGetUniformLocation(glyph_program_, "uColorAtlas"), 1);

        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instance_count);
        ++last_draw_calls_;
        glBindVertexArray(0);

        // Clipped Kitty inline graphics above text (z >= 0).
        for (size_t i = 0; i < gpu_state.kitty_placements.size(); ++i) {
            const auto& pl = gpu_state.kitty_placements[i];
            if (pl.z_index < 0 || i >= gpu_state.kitty_textures.size()) {
                continue;
            }
            float kx0 = static_cast<float>(pl.grid_col * metrics.cell_width_px);
            float ky0 = static_cast<float>(pl.grid_row * metrics.cell_height_px);
            float kx1 = kx0 + static_cast<float>(pl.span_cols * metrics.cell_width_px);
            float ky1 = ky0 + static_cast<float>(pl.span_rows * metrics.cell_height_px);
            DrawTexturedQuad(pane_spec.width_px, pane_spec.height_px,
                             kx0, ky0, kx1, ky1, gpu_state.kitty_textures[i], 0xFFFFFFFFu, false);
        }
#else
        for (uint16_t r : uploaded_rows) {
            (void)r;
            last_uploaded_bytes_ += row_stride_bytes;
        }
        last_draw_calls_ += 2;
#endif
    }

#if defined(__ANDROID__)
    glViewport(0, 0, surface_width_px, surface_height_px);
    glScissor(0, 0, surface_width_px, surface_height_px);
#endif

    for (const SplitDividerSpec& div : dividers) {
        DrawTexturedQuad(
            surface_width_px,
            surface_height_px,
            static_cast<float>(div.x_px),
            static_cast<float>(div.y_px),
            static_cast<float>(div.x_px + div.width_px),
            static_cast<float>(div.y_px + div.height_px),
            0,
            div.rgba,
            true);
    }

    for (const PaneViewportSpec& pane_spec : panes) {
        if (pane_spec.focused && pane_spec.preedit.active) {
            DrawPreeditOverlay(surface_width_px, surface_height_px, pane_spec, metrics);
        }
    }

    return true;
}

}  // namespace termvr::vt
