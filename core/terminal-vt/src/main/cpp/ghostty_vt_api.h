#ifndef TERMVR_GHOSTTY_VT_API_H
#define TERMVR_GHOSTTY_VT_API_H

#include <cstdbool>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "termvr_vt_types.h"

#if defined(TERMVR_HAS_UPSTREAM_DEPS) && TERMVR_HAS_UPSTREAM_DEPS
#include <ghostty/vt.h>
#else

extern "C" {

typedef enum GhosttyResult {
    GHOSTTY_SUCCESS = 0,
    GHOSTTY_OUT_OF_MEMORY = 1,
    GHOSTTY_INVALID_VALUE = 2,
    GHOSTTY_OUT_OF_SPACE = 3,
    GHOSTTY_UNSAFE_PASTE = 4,
} GhosttyResult;

typedef struct GhosttyAllocator {
    void* ctx;
} GhosttyAllocator;

typedef struct GhosttyTerminalImpl* GhosttyTerminal;
typedef struct GhosttyRenderStateImpl* GhosttyRenderState;
typedef struct GhosttyRenderStateRowIteratorImpl* GhosttyRenderStateRowIterator;
typedef struct GhosttyRenderStateCellIteratorImpl* GhosttyRenderStateCellIterator;
typedef struct GhosttyKeyEncoderImpl* GhosttyKeyEncoder;
typedef struct GhosttyMouseEncoderImpl* GhosttyMouseEncoder;
typedef struct GhosttyFormatterImpl* GhosttyFormatter;

typedef struct GhosttyTerminalOptions {
    uint16_t cols;
    uint16_t rows;
    uint32_t max_scrollback;
} GhosttyTerminalOptions;

typedef enum GhosttyRenderStateDirty {
    GHOSTTY_RENDER_STATE_DIRTY_FALSE = 0,
    GHOSTTY_RENDER_STATE_DIRTY_PARTIAL = 1,
    GHOSTTY_RENDER_STATE_DIRTY_FULL = 2,
} GhosttyRenderStateDirty;

typedef enum GhosttyColorKind {
    GHOSTTY_COLOR_DEFAULT = 0,
    GHOSTTY_COLOR_PALETTE = 1,
    GHOSTTY_COLOR_RGB = 2,
} GhosttyColorKind;

typedef struct GhosttyColorValue {
    GhosttyColorKind kind;
    uint8_t palette_index;
    uint8_t r;
    uint8_t g;
    uint8_t b;
} GhosttyColorValue;

typedef struct GhosttyCellSnapshot {
    uint16_t col;
    uint8_t width;
    bool is_continuation;
    uint16_t style_flags;
    GhosttyColorValue fg;
    GhosttyColorValue bg;
    GhosttyColorValue underline_color;
    char grapheme_utf8[32];
    uint8_t grapheme_len;
    uint32_t primary_codepoint;
} GhosttyCellSnapshot;

typedef enum GhosttyKeyAction {
    GHOSTTY_KEY_ACTION_PRESS = 1,
    GHOSTTY_KEY_ACTION_REPEAT = 2,
    GHOSTTY_KEY_ACTION_RELEASE = 3,
} GhosttyKeyAction;

typedef enum GhosttyKey {
    GHOSTTY_KEY_NONE = 0,
    GHOSTTY_KEY_ESCAPE = 1,
    GHOSTTY_KEY_ENTER = 2,
    GHOSTTY_KEY_TAB = 3,
    GHOSTTY_KEY_BACKSPACE = 4,
    GHOSTTY_KEY_INSERT = 5,
    GHOSTTY_KEY_DELETE = 6,
    GHOSTTY_KEY_LEFT = 7,
    GHOSTTY_KEY_RIGHT = 8,
    GHOSTTY_KEY_UP = 9,
    GHOSTTY_KEY_DOWN = 10,
    GHOSTTY_KEY_PAGE_UP = 11,
    GHOSTTY_KEY_PAGE_DOWN = 12,
    GHOSTTY_KEY_HOME = 13,
    GHOSTTY_KEY_END = 14,
    GHOSTTY_KEY_F1 = 15,
    GHOSTTY_KEY_F2 = 16,
    GHOSTTY_KEY_F3 = 17,
    GHOSTTY_KEY_F4 = 18,
    GHOSTTY_KEY_F5 = 19,
    GHOSTTY_KEY_F6 = 20,
    GHOSTTY_KEY_F7 = 21,
    GHOSTTY_KEY_F8 = 22,
    GHOSTTY_KEY_F9 = 23,
    GHOSTTY_KEY_F10 = 24,
    GHOSTTY_KEY_F11 = 25,
    GHOSTTY_KEY_F12 = 26,
    GHOSTTY_KEY_UNICODE = 100,
} GhosttyKey;

enum GhosttyModifierBits {
    GHOSTTY_MOD_NONE = 0,
    GHOSTTY_MOD_SHIFT = 1u << 0,
    GHOSTTY_MOD_ALT = 1u << 1,
    GHOSTTY_MOD_CTRL = 1u << 2,
    GHOSTTY_MOD_SUPER = 1u << 3,
};

typedef struct GhosttyKeyEvent {
    GhosttyKey key;
    GhosttyKeyAction action;
    uint32_t mods;
    uint32_t unicode_codepoint;
    const char* utf8_text;
    size_t utf8_len;
} GhosttyKeyEvent;

typedef enum GhosttyMouseAction {
    GHOSTTY_MOUSE_PRESS = 0,
    GHOSTTY_MOUSE_RELEASE = 1,
    GHOSTTY_MOUSE_MOTION = 2,
    GHOSTTY_MOUSE_WHEEL_UP = 3,
    GHOSTTY_MOUSE_WHEEL_DOWN = 4,
} GhosttyMouseAction;

typedef enum GhosttyMouseButton {
    GHOSTTY_MOUSE_BUTTON_NONE = 0,
    GHOSTTY_MOUSE_BUTTON_LEFT = 1,
    GHOSTTY_MOUSE_BUTTON_MIDDLE = 2,
    GHOSTTY_MOUSE_BUTTON_RIGHT = 3,
} GhosttyMouseButton;

typedef struct GhosttyMouseEvent {
    GhosttyMouseAction action;
    GhosttyMouseButton button;
    uint32_t mods;
    uint16_t col;
    uint16_t row;
    uint32_t pixel_x;
    uint32_t pixel_y;
} GhosttyMouseEvent;

typedef enum GhosttyFormatterEmit {
    GHOSTTY_FORMATTER_EMIT_PLAIN = 0,
    GHOSTTY_FORMATTER_EMIT_VT = 1,
} GhosttyFormatterEmit;

typedef struct GhosttyFormatterTerminalOptions {
    GhosttyFormatterEmit emit;
    bool trim_trailing_whitespace;
    bool unwrap_soft_wraps;
    bool use_selection;
    int32_t start_row;
    int32_t start_col;
    int32_t end_row;
    int32_t end_col;
} GhosttyFormatterTerminalOptions;

GhosttyResult ghostty_terminal_new(
    const GhosttyAllocator* allocator,
    GhosttyTerminal* out_terminal,
    GhosttyTerminalOptions opts);

void ghostty_terminal_free(GhosttyTerminal terminal);

GhosttyResult ghostty_terminal_resize(
    GhosttyTerminal terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width_px,
    uint32_t cell_height_px);

void ghostty_terminal_vt_write(
    GhosttyTerminal terminal,
    const uint8_t* data,
    size_t len);

GhosttyResult ghostty_render_state_new(
    const GhosttyAllocator* allocator,
    GhosttyRenderState* out_state);

void ghostty_render_state_free(GhosttyRenderState state);

GhosttyResult ghostty_render_state_update(
    GhosttyRenderState state,
    GhosttyTerminal terminal);

GhosttyRenderStateDirty ghostty_render_state_get_dirty(GhosttyRenderState state);

void ghostty_render_state_clean(GhosttyRenderState state);

GhosttyResult ghostty_render_state_row_iterator_new(
    const GhosttyAllocator* allocator,
    GhosttyRenderStateRowIterator* out_iter);

void ghostty_render_state_row_iterator_free(GhosttyRenderStateRowIterator iter);

void ghostty_render_state_row_iterator_begin(
    GhosttyRenderStateRowIterator iter,
    GhosttyRenderState state,
    bool dirty_only);

bool ghostty_render_state_row_iterator_next(
    GhosttyRenderStateRowIterator iter,
    uint16_t* out_row);

bool ghostty_render_state_row_iterator_next_dirty(
    GhosttyRenderStateRowIterator iter,
    uint16_t* out_row);

GhosttyResult ghostty_render_state_cell_iterator_new(
    const GhosttyAllocator* allocator,
    GhosttyRenderStateCellIterator* out_iter);

void ghostty_render_state_cell_iterator_free(GhosttyRenderStateCellIterator iter);

void ghostty_render_state_cell_iterator_begin(
    GhosttyRenderStateCellIterator iter,
    GhosttyRenderState state,
    uint16_t row);

bool ghostty_render_state_cell_iterator_next(
    GhosttyRenderStateCellIterator iter,
    GhosttyCellSnapshot* out_cell);

GhosttyResult ghostty_key_encoder_new(
    const GhosttyAllocator* allocator,
    GhosttyKeyEncoder* out_encoder);

void ghostty_key_encoder_free(GhosttyKeyEncoder encoder);

void ghostty_key_encoder_set_opts_from_terminal(
    GhosttyKeyEncoder encoder,
    GhosttyTerminal terminal);

GhosttyResult ghostty_key_encoder_encode(
    GhosttyKeyEncoder encoder,
    GhosttyKeyEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

GhosttyResult ghostty_mouse_encoder_new(
    const GhosttyAllocator* allocator,
    GhosttyMouseEncoder* out_encoder);

void ghostty_mouse_encoder_free(GhosttyMouseEncoder encoder);

void ghostty_mouse_encoder_set_opts_from_terminal(
    GhosttyMouseEncoder encoder,
    GhosttyTerminal terminal);

GhosttyResult ghostty_mouse_encoder_encode(
    GhosttyMouseEncoder encoder,
    GhosttyMouseEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

GhosttyResult ghostty_formatter_terminal_new(
    const GhosttyAllocator* allocator,
    GhosttyFormatter* out_formatter,
    GhosttyTerminal terminal,
    GhosttyFormatterTerminalOptions opts);

GhosttyResult ghostty_formatter_format_alloc(
    GhosttyFormatter formatter,
    const GhosttyAllocator* allocator,
    uint8_t** out_buf,
    size_t* out_len);

void ghostty_formatter_free(GhosttyFormatter formatter);

void ghostty_formatter_buffer_free(
    const GhosttyAllocator* allocator,
    uint8_t* buf,
    size_t len);

bool ghostty_paste_is_safe(const char* data, size_t len);

GhosttyResult ghostty_terminal_paste(
    GhosttyTerminal terminal,
    const char* data,
    size_t len,
    bool allow_unsafe,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

}  // extern "C"

#endif  // TERMVR_HAS_UPSTREAM_DEPS

namespace termvr::vt {

struct TerminalCursorSnapshot {
    uint16_t col = 0;
    uint16_t row = 0;
    bool visible = true;
    bool blink = true;
    CursorShape shape = CursorShape::kBlock;
};

struct ExtractedCell {
    uint16_t col = 0;
    uint8_t width = 1;
    bool is_continuation = false;
    uint16_t style_flags = 0;
    uint32_t fg_rgba = 0;
    uint32_t bg_rgba = 0;
    uint32_t deco_rgba = 0;
    std::string grapheme_utf8;
    uint32_t primary_codepoint = 0x20u;
};

struct ExtractedRow {
    uint16_t row_index = 0;
    std::vector<ExtractedCell> cells;
};

void TerminalSetScrollbackOffset(GhosttyTerminal terminal, uint32_t lines_from_bottom);
uint32_t TerminalGetScrollbackOffset(GhosttyTerminal terminal);
uint32_t TerminalGetScrollbackCount(GhosttyTerminal terminal);

void TerminalSetSelection(
    GhosttyTerminal terminal,
    bool active,
    int32_t start_row,
    int32_t start_col,
    int32_t end_row,
    int32_t end_col);

void TerminalClearSelection(GhosttyTerminal terminal);

bool TerminalHasSelection(GhosttyTerminal terminal);

bool TerminalIsMouseTrackingActive(GhosttyTerminal terminal);
bool TerminalIsBracketedPasteActive(GhosttyTerminal terminal);
bool TerminalIsApplicationCursorActive(GhosttyTerminal terminal);

std::string TerminalGetWindowTitle(GhosttyTerminal terminal);

TerminalCursorSnapshot TerminalGetCursorSnapshot(GhosttyTerminal terminal);

std::vector<SearchMatchNative> TerminalSearchScrollback(
    GhosttyTerminal terminal,
    const std::string& query,
    bool use_regex,
    bool case_sensitive);

bool TerminalDetectLinkAt(
    GhosttyTerminal terminal,
    uint16_t viewport_row,
    uint16_t viewport_col,
    DetectedLinkNative* out_link);

std::vector<KittyGraphicPlacement> TerminalGetKittyPlacements(GhosttyTerminal terminal);

uint64_t TerminalGetKittyGraphicsRevision(GhosttyTerminal terminal);

}  // namespace termvr::vt

#endif  // TERMVR_GHOSTTY_VT_API_H
