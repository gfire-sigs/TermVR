#ifndef TERMVR_GLES3_RENDERER_H
#define TERMVR_GLES3_RENDERER_H

#include <cstdint>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "font_manager.h"
#include "glyph_atlas.h"
#include "termvr_vt_types.h"
#include "vt_session_native.h"

namespace termvr::vt {

struct RendererShaderSources {
    std::string bg_vert;
    std::string bg_frag;
    std::string glyph_vert;
    std::string glyph_frag;
};

class Gles3Renderer {
public:
    Gles3Renderer();
    ~Gles3Renderer();

    Gles3Renderer(const Gles3Renderer&) = delete;
    Gles3Renderer& operator=(const Gles3Renderer&) = delete;

    void SetShaderSources(const RendererShaderSources& sources);
    bool InitializeGl();
    void ReleaseGl();
    void NotifyContextLost(uint64_t new_epoch);

    void SetBundledFonts(BundledFontAssets assets);
    bool ConfigureFont(const FontConfigNative& config);
    CellMetrics CurrentCellMetrics() const noexcept { return font_manager_.Metrics(); }

    void DetachSession(int64_t session_handle);

    // Renders all visible panes inside the single surface; returns true when any frame work ran.
    bool RenderFrame(
        int32_t surface_width_px,
        int32_t surface_height_px,
        const std::vector<PaneViewportSpec>& panes,
        const std::vector<SplitDividerSpec>& dividers,
        bool force_full_redraw,
        uint32_t surface_clear_rgba);

    uint64_t ContextEpoch() const noexcept { return egl_context_epoch_; }
    uint64_t LastDrawCallCount() const noexcept { return last_draw_calls_; }
    uint64_t LastUploadedBytes() const noexcept { return last_uploaded_bytes_; }

    static const RendererShaderSources& DefaultShaderSources();

private:
    struct PaneGpuState {
        uint16_t cols = 0;
        uint16_t rows = 0;
        uint32_t instance_vbo = 0;
        size_t instance_capacity_bytes = 0;
        std::vector<std::vector<CellGpuInstance>> row_instances;
        std::vector<std::vector<ExtractedCell>> cached_rows;
        std::vector<uint32_t> row_instance_offsets;
        std::vector<uint32_t> row_instance_counts;
        uint32_t total_instances = 0;
        PaletteColors last_palette{};
        int32_t last_cursor_row = -1;
        int32_t last_cursor_col = -1;
        bool last_cursor_drawn = false;
        CursorShape last_cursor_shape = CursorShape::kNone;
        uint64_t kitty_revision = 0;
        std::vector<uint32_t> kitty_textures;
        std::vector<KittyGraphicPlacement> kitty_placements;
    };

    FontManager font_manager_;
    GlyphAtlasManager atlas_;
    RendererShaderSources shader_sources_;
    bool gl_initialized_ = false;
    uint64_t egl_context_epoch_ = 1;

    uint32_t bg_program_ = 0;
    uint32_t glyph_program_ = 0;
    uint32_t overlay_program_ = 0;
    uint32_t shared_vao_ = 0;
    uint32_t overlay_vbo_ = 0;
    uint32_t mono_atlas_tex_ = 0;
    uint32_t color_atlas_tex_ = 0;

    std::unordered_map<int64_t, PaneGpuState> pane_states_;

    uint64_t last_draw_calls_ = 0;
    uint64_t last_uploaded_bytes_ = 0;

    bool CompilePrograms();
    void SyncAtlasTexturesToGl();
    void SyncKittyTexturesToGl(PaneGpuState* pane, const std::vector<KittyGraphicPlacement>& placements);
    void ConfigureInstanceAttributes(uint32_t vbo);
    void DrawTexturedQuad(
        int32_t pane_w,
        int32_t pane_h,
        float x0,
        float y0,
        float x1,
        float y1,
        uint32_t gl_tex,
        uint32_t tint_rgba,
        bool is_solid);
    void DrawPreeditOverlay(
        int32_t surface_w,
        int32_t surface_h,
        const PaneViewportSpec& pane,
        const CellMetrics& metrics);
};

}  // namespace termvr::vt

#endif  // TERMVR_GLES3_RENDERER_H
