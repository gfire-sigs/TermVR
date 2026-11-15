#ifndef TERMVR_GHOSTTY_VT_API_H
#define TERMVR_GHOSTTY_VT_API_H

#include <cstdbool>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "termvr_vt_types.h"

extern "C" {

typedef enum TvGhosttyResult {
    TV_GHOSTTY_SUCCESS = 0,
    TV_GHOSTTY_OUT_OF_MEMORY = 1,
    TV_GHOSTTY_INVALID_VALUE = 2,
    TV_GHOSTTY_OUT_OF_SPACE = 3,
    TV_GHOSTTY_UNSAFE_PASTE = 4,
} TvGhosttyResult;

typedef struct TvGhosttyAllocator {
    void* ctx;
} TvGhosttyAllocator;

typedef struct TvGhosttyTerminalImpl* TvGhosttyTerminal;
typedef struct TvGhosttyRenderStateImpl* TvGhosttyRenderState;
typedef struct TvGhosttyRenderStateRowIteratorImpl* TvGhosttyRenderStateRowIterator;
typedef struct TvGhosttyRenderStateCellIteratorImpl* TvGhosttyRenderStateCellIterator;
typedef struct TvGhosttyKeyEncoderImpl* TvGhosttyKeyEncoder;
typedef struct TvGhosttyMouseEncoderImpl* TvGhosttyMouseEncoder;
typedef struct TvGhosttyFormatterImpl* TvGhosttyFormatter;

typedef struct TvGhosttyTerminalOptions {
    uint16_t cols;
    uint16_t rows;
    uint32_t max_scrollback;
} TvGhosttyTerminalOptions;

typedef enum TvGhosttyRenderStateDirty {
    TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE = 0,
    TV_GHOSTTY_RENDER_STATE_DIRTY_PARTIAL = 1,
    TV_GHOSTTY_RENDER_STATE_DIRTY_FULL = 2,
} TvGhosttyRenderStateDirty;

typedef enum TvGhosttyColorKind {
    TV_GHOSTTY_COLOR_DEFAULT = 0,
    TV_GHOSTTY_COLOR_PALETTE = 1,
    TV_GHOSTTY_COLOR_RGB = 2,
} TvGhosttyColorKind;

typedef struct TvGhosttyColorValue {
    TvGhosttyColorKind kind;
    uint8_t palette_index;
    uint8_t r;
    uint8_t g;
    uint8_t b;
} TvGhosttyColorValue;

typedef struct TvGhosttyCellSnapshot {
    uint16_t col;
    uint8_t width;
    bool is_continuation;
    uint16_t style_flags;
    TvGhosttyColorValue fg;
    TvGhosttyColorValue bg;
    TvGhosttyColorValue underline_color;
    char grapheme_utf8[32];
    uint8_t grapheme_len;
    uint32_t primary_codepoint;
} TvGhosttyCellSnapshot;

typedef enum TvGhosttyKeyAction {
    TV_GHOSTTY_KEY_ACTION_PRESS = 1,
    TV_GHOSTTY_KEY_ACTION_REPEAT = 2,
    TV_GHOSTTY_KEY_ACTION_RELEASE = 3,
} TvGhosttyKeyAction;

typedef enum TvGhosttyKey {
    TV_GHOSTTY_KEY_NONE = 0,
    TV_GHOSTTY_KEY_ESCAPE = 1,
    TV_GHOSTTY_KEY_ENTER = 2,
    TV_GHOSTTY_KEY_TAB = 3,
    TV_GHOSTTY_KEY_BACKSPACE = 4,
    TV_GHOSTTY_KEY_INSERT = 5,
    TV_GHOSTTY_KEY_DELETE = 6,
    TV_GHOSTTY_KEY_LEFT = 7,
    TV_GHOSTTY_KEY_RIGHT = 8,
    TV_GHOSTTY_KEY_UP = 9,
    TV_GHOSTTY_KEY_DOWN = 10,
    TV_GHOSTTY_KEY_PAGE_UP = 11,
    TV_GHOSTTY_KEY_PAGE_DOWN = 12,
    TV_GHOSTTY_KEY_HOME = 13,
    TV_GHOSTTY_KEY_END = 14,
    TV_GHOSTTY_KEY_F1 = 15,
    TV_GHOSTTY_KEY_F2 = 16,
    TV_GHOSTTY_KEY_F3 = 17,
    TV_GHOSTTY_KEY_F4 = 18,
    TV_GHOSTTY_KEY_F5 = 19,
    TV_GHOSTTY_KEY_F6 = 20,
    TV_GHOSTTY_KEY_F7 = 21,
    TV_GHOSTTY_KEY_F8 = 22,
    TV_GHOSTTY_KEY_F9 = 23,
    TV_GHOSTTY_KEY_F10 = 24,
    TV_GHOSTTY_KEY_F11 = 25,
    TV_GHOSTTY_KEY_F12 = 26,
    TV_GHOSTTY_KEY_UNICODE = 100,
} TvGhosttyKey;

enum TvGhosttyModifierBits {
    TV_GHOSTTY_MOD_NONE = 0,
    TV_GHOSTTY_MOD_SHIFT = 1u << 0,
    TV_GHOSTTY_MOD_ALT = 1u << 1,
    TV_GHOSTTY_MOD_CTRL = 1u << 2,
    TV_GHOSTTY_MOD_SUPER = 1u << 3,
};

typedef struct TvGhosttyKeyEvent {
    TvGhosttyKey key;
    TvGhosttyKeyAction action;
    uint32_t mods;
    uint32_t unicode_codepoint;
    const char* utf8_text;
    size_t utf8_len;
} TvGhosttyKeyEvent;

typedef enum TvGhosttyMouseAction {
    TV_GHOSTTY_MOUSE_PRESS = 0,
    TV_GHOSTTY_MOUSE_RELEASE = 1,
    TV_GHOSTTY_MOUSE_MOTION = 2,
    TV_GHOSTTY_MOUSE_WHEEL_UP = 3,
    TV_GHOSTTY_MOUSE_WHEEL_DOWN = 4,
} TvGhosttyMouseAction;

typedef enum TvGhosttyMouseButton {
    TV_GHOSTTY_MOUSE_BUTTON_NONE = 0,
    TV_GHOSTTY_MOUSE_BUTTON_LEFT = 1,
    TV_GHOSTTY_MOUSE_BUTTON_MIDDLE = 2,
    TV_GHOSTTY_MOUSE_BUTTON_RIGHT = 3,
} TvGhosttyMouseButton;

typedef struct TvGhosttyMouseEvent {
    TvGhosttyMouseAction action;
    TvGhosttyMouseButton button;
    uint32_t mods;
    uint16_t col;
    uint16_t row;
    uint32_t pixel_x;
    uint32_t pixel_y;
} TvGhosttyMouseEvent;

typedef enum TvGhosttyFormatterEmit {
    TV_GHOSTTY_FORMATTER_EMIT_PLAIN = 0,
    TV_GHOSTTY_FORMATTER_EMIT_VT = 1,
} TvGhosttyFormatterEmit;

typedef struct TvGhosttyFormatterTerminalOptions {
    TvGhosttyFormatterEmit emit;
    bool trim_trailing_whitespace;
    bool unwrap_soft_wraps;
    bool use_selection;
    int32_t start_row;
    int32_t start_col;
    int32_t end_row;
    int32_t end_col;
} TvGhosttyFormatterTerminalOptions;

TvGhosttyResult tv_ghostty_terminal_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyTerminal* out_terminal,
    TvGhosttyTerminalOptions opts);

void tv_ghostty_terminal_free(TvGhosttyTerminal terminal);

TvGhosttyResult tv_ghostty_terminal_resize(
    TvGhosttyTerminal terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width_px,
    uint32_t cell_height_px);

void tv_ghostty_terminal_vt_write(
    TvGhosttyTerminal terminal,
    const uint8_t* data,
    size_t len);

TvGhosttyResult tv_ghostty_render_state_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderState* out_state);

void tv_ghostty_render_state_free(TvGhosttyRenderState state);

TvGhosttyResult tv_ghostty_render_state_update(
    TvGhosttyRenderState state,
    TvGhosttyTerminal terminal);

TvGhosttyRenderStateDirty tv_ghostty_render_state_get_dirty(TvGhosttyRenderState state);

void tv_ghostty_render_state_clean(TvGhosttyRenderState state);

TvGhosttyResult tv_ghostty_render_state_row_iterator_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderStateRowIterator* out_iter);

void tv_ghostty_render_state_row_iterator_free(TvGhosttyRenderStateRowIterator iter);

void tv_ghostty_render_state_row_iterator_begin(
    TvGhosttyRenderStateRowIterator iter,
    TvGhosttyRenderState state,
    bool dirty_only);

bool tv_ghostty_render_state_row_iterator_next(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row);

bool tv_ghostty_render_state_row_iterator_next_dirty(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row);

TvGhosttyResult tv_ghostty_render_state_cell_iterator_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyRenderStateCellIterator* out_iter);

void tv_ghostty_render_state_cell_iterator_free(TvGhosttyRenderStateCellIterator iter);

void tv_ghostty_render_state_cell_iterator_begin(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyRenderState state,
    uint16_t row);

bool tv_ghostty_render_state_cell_iterator_next(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyCellSnapshot* out_cell);

TvGhosttyResult tv_ghostty_key_encoder_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyKeyEncoder* out_encoder);

void tv_ghostty_key_encoder_free(TvGhosttyKeyEncoder encoder);

void tv_ghostty_key_encoder_set_opts_from_terminal(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyTerminal terminal);

TvGhosttyResult tv_ghostty_key_encoder_encode(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyKeyEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

TvGhosttyResult tv_ghostty_mouse_encoder_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyMouseEncoder* out_encoder);

void tv_ghostty_mouse_encoder_free(TvGhosttyMouseEncoder encoder);

void tv_ghostty_mouse_encoder_set_opts_from_terminal(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyTerminal terminal);

TvGhosttyResult tv_ghostty_mouse_encoder_encode(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyMouseEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

TvGhosttyResult tv_ghostty_formatter_terminal_new(
    const TvGhosttyAllocator* allocator,
    TvGhosttyFormatter* out_formatter,
    TvGhosttyTerminal terminal,
    TvGhosttyFormatterTerminalOptions opts);

TvGhosttyResult tv_ghostty_formatter_format_alloc(
    TvGhosttyFormatter formatter,
    const TvGhosttyAllocator* allocator,
    uint8_t** out_buf,
    size_t* out_len);

void tv_ghostty_formatter_free(TvGhosttyFormatter formatter);

void tv_ghostty_formatter_buffer_free(
    const TvGhosttyAllocator* allocator,
    uint8_t* buf,
    size_t len);

bool tv_ghostty_paste_is_safe(const char* data, size_t len);

TvGhosttyResult tv_ghostty_terminal_paste(
    TvGhosttyTerminal terminal,
    const char* data,
    size_t len,
    bool allow_unsafe,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written);

}  // extern "C"

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

void TerminalSetScrollbackOffset(TvGhosttyTerminal terminal, uint32_t lines_from_bottom);
uint32_t TerminalGetScrollbackOffset(TvGhosttyTerminal terminal);
uint32_t TerminalGetScrollbackCount(TvGhosttyTerminal terminal);

void TerminalSetSelection(
    TvGhosttyTerminal terminal,
    bool active,
    int32_t start_row,
    int32_t start_col,
    int32_t end_row,
    int32_t end_col);

void TerminalClearSelection(TvGhosttyTerminal terminal);

bool TerminalHasSelection(TvGhosttyTerminal terminal);

bool TerminalIsMouseTrackingActive(TvGhosttyTerminal terminal);
bool TerminalIsBracketedPasteActive(TvGhosttyTerminal terminal);
bool TerminalIsApplicationCursorActive(TvGhosttyTerminal terminal);

std::string TerminalGetWindowTitle(TvGhosttyTerminal terminal);

TerminalCursorSnapshot TerminalGetCursorSnapshot(TvGhosttyTerminal terminal);
TerminalCursorSnapshot TerminalGetCursorSnapshotWithState(
    TvGhosttyTerminal terminal,
    TvGhosttyRenderState render_state);

std::vector<uint8_t> TerminalDrainPtyReplies(TvGhosttyTerminal terminal);
void TerminalSetPaletteColors(TvGhosttyTerminal terminal, const PaletteColors& palette);
void TerminalSetCellMetricsPx(
    TvGhosttyTerminal terminal,
    uint32_t cell_width_px,
    uint32_t cell_height_px);

std::vector<SearchMatchNative> TerminalSearchScrollback(
    TvGhosttyTerminal terminal,
    const std::string& query,
    bool use_regex,
    bool case_sensitive);

bool TerminalDetectLinkAt(
    TvGhosttyTerminal terminal,
    uint16_t viewport_row,
    uint16_t viewport_col,
    DetectedLinkNative* out_link);

std::vector<KittyGraphicPlacement> TerminalGetKittyPlacements(TvGhosttyTerminal terminal);

uint64_t TerminalGetKittyGraphicsRevision(TvGhosttyTerminal terminal);

}  // namespace termvr::vt

#endif  // TERMVR_GHOSTTY_VT_API_H
