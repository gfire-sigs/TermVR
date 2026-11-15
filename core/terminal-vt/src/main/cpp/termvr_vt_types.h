#ifndef TERMVR_VT_TYPES_H
#define TERMVR_VT_TYPES_H

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace termvr::vt {

struct alignas(16) CellGpuInstance {
    uint16_t grid_col;       // 0..cols-1
    uint16_t grid_row;       // 0..rows-1
    uint8_t  span_cols;      // 1 = narrow, 2 = CJK/emoji, 2..8 = HarfBuzz ligature run
    uint8_t  atlas_page;     // 0 = none (blank cell), 1 = R8 mono, 2 = RGBA8 color/Kitty
    uint16_t style_flags;    // Bitmask: bold, italic, dim, blink, inverse, underline(3b), strikethrough, cursor(3b)
    uint16_t uv_x;           // Normalized texel X in 2048x2048 atlas
    uint16_t uv_y;           // Normalized texel Y in 2048x2048 atlas
    uint16_t uv_w;           // Glyph bitmap width in pixels
    uint16_t uv_h;           // Glyph bitmap height in pixels
    int16_t  bearing_x;      // Horizontal FreeType glyph bearing in pixels
    int16_t  bearing_y;      // Baseline-relative vertical glyph bearing in pixels
    uint32_t fg_rgba;        // Packed RGBA8888 foreground color
    uint32_t bg_rgba;        // Packed RGBA8888 background color
    uint32_t deco_rgba;      // Packed RGBA8888 underline or cursor color
};
static_assert(sizeof(CellGpuInstance) == 32, "CellGpuInstance must remain 32 bytes");

constexpr uint8_t kAtlasPageNone = 0;
constexpr uint8_t kAtlasPageMonoR8 = 1;
constexpr uint8_t kAtlasPageColorRgba8 = 2;

constexpr uint16_t kStyleBold = 1u << 0;
constexpr uint16_t kStyleItalic = 1u << 1;
constexpr uint16_t kStyleDim = 1u << 2;
constexpr uint16_t kStyleBlink = 1u << 3;
constexpr uint16_t kStyleInverse = 1u << 4;
constexpr uint16_t kStyleUnderlineShift = 5u;
constexpr uint16_t kStyleUnderlineMask = 0x7u << kStyleUnderlineShift;
constexpr uint16_t kStyleStrikethrough = 1u << 8;
constexpr uint16_t kStyleCursorShift = 9u;
constexpr uint16_t kStyleCursorMask = 0x7u << kStyleCursorShift;
constexpr uint16_t kStyleSelected = 1u << 12;

enum class UnderlineStyle : uint8_t {
    kNone = 0,
    kSingle = 1,
    kDouble = 2,
    kCurly = 3,
    kDotted = 4,
    kDashed = 5,
};

enum class CursorShape : uint8_t {
    kNone = 0,
    kBlock = 1,
    kBar = 2,
    kHollow = 3,
    kUnderline = 4,
};

constexpr uint16_t PackUnderlineBits(UnderlineStyle style) noexcept {
    return static_cast<uint16_t>((static_cast<uint16_t>(style) & 0x7u) << kStyleUnderlineShift);
}

constexpr uint16_t PackCursorBits(CursorShape shape) noexcept {
    return static_cast<uint16_t>((static_cast<uint16_t>(shape) & 0x7u) << kStyleCursorShift);
}

struct PaletteColors {
    uint32_t foreground_rgba = 0xE6EDF3FFu;
    uint32_t background_rgba = 0x0D1117FFu;
    uint32_t cursor_rgba = 0x58A6FFFFu;
    uint32_t selection_rgba = 0x264F78B0u;
    std::array<uint32_t, 16> ansi16_rgba = {
        0x161B22FFu, 0xFF7B72FFu, 0x3FB950FFu, 0xD29922FFu,
        0x58A6FFFFu, 0xBC8CFFFFu, 0x39C5CFFFU, 0xB1BAC4FFu,
        0x484F58FFu, 0xFFA198FFu, 0x56D364FFu, 0xE3B341FFu,
        0x79C0FFFFu, 0xD2A8FFFFu, 0x56D4DDFFu, 0xF0F6FCFFu,
    };
};

struct CellMetrics {
    uint32_t cell_width_px = 10;
    uint32_t cell_height_px = 20;
    int32_t baseline_px = 16;
    float size_sp = 13.0f;
    float density = 2.0f;
};

struct KittyGraphicPlacement {
    uint32_t image_id = 0;
    uint32_t placement_id = 0;
    uint32_t pixel_width = 0;
    uint32_t pixel_height = 0;
    uint16_t grid_col = 0;
    uint16_t grid_row = 0;
    uint16_t span_cols = 1;
    uint16_t span_rows = 1;
    int32_t z_index = -1;
    uint64_t revision = 0;
    std::vector<uint8_t> rgba_pixels;
};

struct SearchMatchNative {
    int32_t absolute_line = 0;
    int32_t viewport_row = -1;
    uint16_t start_col = 0;
    uint16_t end_col = 0;
    std::string matched_text;
};

enum class DetectedLinkKind : uint8_t {
    kUrl = 0,
    kFilepath = 1,
};

struct DetectedLinkNative {
    DetectedLinkKind kind = DetectedLinkKind::kUrl;
    uint16_t start_row = 0;
    uint16_t start_col = 0;
    uint16_t end_row = 0;
    uint16_t end_col = 0;
    std::string target;
};

struct SplitDividerSpec {
    int32_t x_px = 0;
    int32_t y_px = 0;
    int32_t width_px = 1;
    int32_t height_px = 1;
    uint32_t rgba = 0x30363DFFu;
};

struct ImePreeditSpec {
    bool active = false;
    std::string text_utf8;
    uint16_t cursor_col = 0;
    uint16_t cursor_row = 0;
    uint32_t fg_rgba = 0xF0F6FCFFu;
    uint32_t bg_rgba = 0x1F2937F2u;
    uint32_t underline_rgba = 0x58A6FFFFu;
};

struct PaneViewportSpec {
    int64_t session_handle = 0;
    int32_t x_px = 0;
    int32_t y_px = 0;
    int32_t width_px = 0;
    int32_t height_px = 0;
    bool focused = true;
    bool cursor_blink_visible = true;
    ImePreeditSpec preedit;
};

}  // namespace termvr::vt

#endif  // TERMVR_VT_TYPES_H
