#define GHOSTTY_STATIC
#include <ghostty/vt.h>

#include "ghostty_vt_api.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <regex>
#include <string>
#include <vector>

struct TvGhosttyTerminalImpl {
    GhosttyTerminal term = nullptr;
    uint16_t cols = 80;
    uint16_t rows = 24;
    uint32_t cell_width_px = 10;
    uint32_t cell_height_px = 20;
    bool dark_color_scheme = true;
    std::vector<uint8_t> pending_pty_replies;
};

struct TvGhosttyRenderStateImpl {
    GhosttyRenderState state = nullptr;
    GhosttyRenderStateRowIterator row_iter = nullptr;
    GhosttyRenderStateRowCells row_cells = nullptr;
    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
};

struct TvGhosttyRenderStateRowIteratorImpl {
    TvGhosttyRenderState state = nullptr;
    bool dirty_only = false;
};

struct TvGhosttyRenderStateCellIteratorImpl {
    TvGhosttyRenderState state = nullptr;
    uint16_t next_col = 0;
};

struct TvGhosttyKeyEncoderImpl {
    GhosttyKeyEncoder encoder = nullptr;
    GhosttyKeyEvent event = nullptr;
    uint8_t kitty_flags = 0;
};

struct TvGhosttyMouseEncoderImpl {
    GhosttyMouseEncoder encoder = nullptr;
    GhosttyMouseEvent event = nullptr;
    uint16_t cols = 80;
    uint16_t rows = 24;
};

struct TvGhosttyFormatterImpl {
    GhosttyFormatter formatter = nullptr;
};

namespace {

TvGhosttyResult MapResult(GhosttyResult r) noexcept {
    switch (r) {
        case GHOSTTY_SUCCESS:
            return TV_GHOSTTY_SUCCESS;
        case GHOSTTY_OUT_OF_MEMORY:
            return TV_GHOSTTY_OUT_OF_MEMORY;
        case GHOSTTY_OUT_OF_SPACE:
            return TV_GHOSTTY_OUT_OF_SPACE;
        case GHOSTTY_REJECTED:
            return TV_GHOSTTY_UNSAFE_PASTE;
        default:
            return TV_GHOSTTY_INVALID_VALUE;
    }
}

GhosttyMods MapKeyMods(uint32_t tv_mods) noexcept {
    GhosttyMods out = 0;
    if (tv_mods & TV_GHOSTTY_MOD_SHIFT) out |= GHOSTTY_MODS_SHIFT;
    if (tv_mods & TV_GHOSTTY_MOD_ALT) out |= GHOSTTY_MODS_ALT;
    if (tv_mods & TV_GHOSTTY_MOD_CTRL) out |= GHOSTTY_MODS_CTRL;
    if (tv_mods & TV_GHOSTTY_MOD_SUPER) out |= GHOSTTY_MODS_SUPER;
    return out;
}

GhosttyKey MapKeyCode(TvGhosttyKey k, uint32_t cp) noexcept {
    switch (k) {
        case TV_GHOSTTY_KEY_ESCAPE: return GHOSTTY_KEY_ESCAPE;
        case TV_GHOSTTY_KEY_ENTER: return GHOSTTY_KEY_ENTER;
        case TV_GHOSTTY_KEY_TAB: return GHOSTTY_KEY_TAB;
        case TV_GHOSTTY_KEY_BACKSPACE: return GHOSTTY_KEY_BACKSPACE;
        case TV_GHOSTTY_KEY_INSERT: return GHOSTTY_KEY_INSERT;
        case TV_GHOSTTY_KEY_DELETE: return GHOSTTY_KEY_DELETE;
        case TV_GHOSTTY_KEY_LEFT: return GHOSTTY_KEY_ARROW_LEFT;
        case TV_GHOSTTY_KEY_RIGHT: return GHOSTTY_KEY_ARROW_RIGHT;
        case TV_GHOSTTY_KEY_UP: return GHOSTTY_KEY_ARROW_UP;
        case TV_GHOSTTY_KEY_DOWN: return GHOSTTY_KEY_ARROW_DOWN;
        case TV_GHOSTTY_KEY_PAGE_UP: return GHOSTTY_KEY_PAGE_UP;
        case TV_GHOSTTY_KEY_PAGE_DOWN: return GHOSTTY_KEY_PAGE_DOWN;
        case TV_GHOSTTY_KEY_HOME: return GHOSTTY_KEY_HOME;
        case TV_GHOSTTY_KEY_END: return GHOSTTY_KEY_END;
        case TV_GHOSTTY_KEY_F1: return GHOSTTY_KEY_F1;
        case TV_GHOSTTY_KEY_F2: return GHOSTTY_KEY_F2;
        case TV_GHOSTTY_KEY_F3: return GHOSTTY_KEY_F3;
        case TV_GHOSTTY_KEY_F4: return GHOSTTY_KEY_F4;
        case TV_GHOSTTY_KEY_F5: return GHOSTTY_KEY_F5;
        case TV_GHOSTTY_KEY_F6: return GHOSTTY_KEY_F6;
        case TV_GHOSTTY_KEY_F7: return GHOSTTY_KEY_F7;
        case TV_GHOSTTY_KEY_F8: return GHOSTTY_KEY_F8;
        case TV_GHOSTTY_KEY_F9: return GHOSTTY_KEY_F9;
        case TV_GHOSTTY_KEY_F10: return GHOSTTY_KEY_F10;
        case TV_GHOSTTY_KEY_F11: return GHOSTTY_KEY_F11;
        case TV_GHOSTTY_KEY_F12: return GHOSTTY_KEY_F12;
        case TV_GHOSTTY_KEY_UNICODE: {
            if (cp >= 'a' && cp <= 'z') {
                return static_cast<GhosttyKey>(GHOSTTY_KEY_A + (cp - 'a'));
            }
            if (cp >= 'A' && cp <= 'Z') {
                return static_cast<GhosttyKey>(GHOSTTY_KEY_A + (cp - 'A'));
            }
            if (cp >= '0' && cp <= '9') {
                return static_cast<GhosttyKey>(GHOSTTY_KEY_DIGIT_0 + (cp - '0'));
            }
            if (cp == ' ') return GHOSTTY_KEY_SPACE;
            switch (cp) {
                case '[':
                case '{': return GHOSTTY_KEY_BRACKET_LEFT;
                case ']':
                case '}': return GHOSTTY_KEY_BRACKET_RIGHT;
                case '\\':
                case '|': return GHOSTTY_KEY_BACKSLASH;
                case '/':
                case '?': return GHOSTTY_KEY_SLASH;
                case '-':
                case '_': return GHOSTTY_KEY_MINUS;
                case '=':
                case '+': return GHOSTTY_KEY_EQUAL;
                case ';':
                case ':': return GHOSTTY_KEY_SEMICOLON;
                case '\'':
                case '"': return GHOSTTY_KEY_QUOTE;
                case ',':
                case '<': return GHOSTTY_KEY_COMMA;
                case '.':
                case '>': return GHOSTTY_KEY_PERIOD;
                case '`':
                case '~': return GHOSTTY_KEY_BACKQUOTE;
                case '!': return GHOSTTY_KEY_DIGIT_1;
                case '@': return GHOSTTY_KEY_DIGIT_2;
                case '#': return GHOSTTY_KEY_DIGIT_3;
                case '$': return GHOSTTY_KEY_DIGIT_4;
                case '%': return GHOSTTY_KEY_DIGIT_5;
                case '^': return GHOSTTY_KEY_DIGIT_6;
                case '&': return GHOSTTY_KEY_DIGIT_7;
                case '*': return GHOSTTY_KEY_DIGIT_8;
                case '(': return GHOSTTY_KEY_DIGIT_9;
                case ')': return GHOSTTY_KEY_DIGIT_0;
                default: return GHOSTTY_KEY_UNIDENTIFIED;
            }
        }
        default:
            return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

GhosttyKeyAction MapKeyAction(TvGhosttyKeyAction a) noexcept {
    switch (a) {
        case TV_GHOSTTY_KEY_ACTION_RELEASE: return GHOSTTY_KEY_ACTION_RELEASE;
        case TV_GHOSTTY_KEY_ACTION_REPEAT: return GHOSTTY_KEY_ACTION_REPEAT;
        case TV_GHOSTTY_KEY_ACTION_PRESS:
        default:
            return GHOSTTY_KEY_ACTION_PRESS;
    }
}

void AppendCodepointUtf8(uint32_t cp, std::string* out) {
    if (cp == 0 || out == nullptr) return;
    if (cp <= 0x7Fu) {
        out->push_back(static_cast<char>(cp));
    } else if (cp <= 0x7FFu) {
        out->push_back(static_cast<char>(0xC0u | (cp >> 6u)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0xFFFFu) {
        out->push_back(static_cast<char>(0xE0u | (cp >> 12u)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 6u) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    } else if (cp <= 0x10FFFFu) {
        out->push_back(static_cast<char>(0xF0u | (cp >> 18u)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 12u) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | ((cp >> 6u) & 0x3Fu)));
        out->push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
    }
}

TvGhosttyColorValue MapStyleColor(const GhosttyStyleColor& sc) noexcept {
    TvGhosttyColorValue out{};
    switch (sc.tag) {
        case GHOSTTY_STYLE_COLOR_PALETTE:
            out.kind = TV_GHOSTTY_COLOR_PALETTE;
            out.palette_index = sc.value.palette;
            break;
        case GHOSTTY_STYLE_COLOR_RGB:
            out.kind = TV_GHOSTTY_COLOR_RGB;
            out.r = sc.value.rgb.r;
            out.g = sc.value.rgb.g;
            out.b = sc.value.rgb.b;
            break;
        default:
            out.kind = TV_GHOSTTY_COLOR_DEFAULT;
            break;
    }
    return out;
}

termvr::vt::UnderlineStyle MapUnderlineStyle(int u) noexcept {
    switch (u) {
        case GHOSTTY_SGR_UNDERLINE_SINGLE: return termvr::vt::UnderlineStyle::kSingle;
        case GHOSTTY_SGR_UNDERLINE_DOUBLE: return termvr::vt::UnderlineStyle::kDouble;
        case GHOSTTY_SGR_UNDERLINE_CURLY: return termvr::vt::UnderlineStyle::kCurly;
        case GHOSTTY_SGR_UNDERLINE_DOTTED: return termvr::vt::UnderlineStyle::kDotted;
        case GHOSTTY_SGR_UNDERLINE_DASHED: return termvr::vt::UnderlineStyle::kDashed;
        default: return termvr::vt::UnderlineStyle::kNone;
    }
}

std::string ReadHyperlinkAt(GhosttyTerminal term, uint16_t col, uint32_t row, GhosttyPointTag tag) {
    GhosttyPoint pt{};
    pt.tag = tag;
    pt.value.coordinate.x = col;
    pt.value.coordinate.y = row;
    GhosttyGridRef ref{};
    if (ghostty_terminal_grid_ref(term, pt, &ref) != GHOSTTY_SUCCESS) {
        return {};
    }
    char buf[512];
    size_t written = 0;
    GhosttyResult res = ghostty_grid_ref_hyperlink_uri(
        &ref, reinterpret_cast<uint8_t*>(buf), sizeof(buf), &written);
    if (res == GHOSTTY_SUCCESS && written > 0) {
        return std::string(buf, written);
    }
    if (res == GHOSTTY_OUT_OF_SPACE && written > 0) {
        std::string large(written, '\0');
        if (ghostty_grid_ref_hyperlink_uri(
                &ref, reinterpret_cast<uint8_t*>(large.data()), large.size(), &written) == GHOSTTY_SUCCESS) {
            large.resize(written);
            return large;
        }
    }
    return {};
}

struct LineTextMapping {
    std::string utf8;
    std::vector<uint16_t> byte_to_col;
    std::vector<uint8_t> col_widths;
};

LineTextMapping BuildLineTextMapping(GhosttyTerminal term, GhosttyPointTag tag, uint32_t row, uint16_t cols) {
    LineTextMapping map;
    map.utf8.reserve(cols);
    map.byte_to_col.reserve(cols);
    map.col_widths.assign(cols, 1);

    for (uint16_t c = 0; c < cols; ++c) {
        GhosttyPoint pt{};
        pt.tag = tag;
        pt.value.coordinate.x = c;
        pt.value.coordinate.y = row;
        GhosttyGridRef ref{};
        if (ghostty_terminal_grid_ref(term, pt, &ref) != GHOSTTY_SUCCESS) {
            continue;
        }
        GhosttyCell cell{};
        if (ghostty_grid_ref_cell(&ref, &cell) != GHOSTTY_SUCCESS) {
            continue;
        }
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        if (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL) {
            continue;
        }
        map.col_widths[c] = (wide == GHOSTTY_CELL_WIDE_WIDE) ? 2 : 1;

        bool has_text = false;
        ghostty_cell_get(cell, GHOSTTY_CELL_DATA_HAS_TEXT, &has_text);
        std::string cell_utf8;
        if (has_text) {
            uint32_t cps[16];
            size_t written = 0;
            GhosttyResult gres = ghostty_grid_ref_graphemes(&ref, cps, 16, &written);
            if (gres == GHOSTTY_SUCCESS && written > 0) {
                for (size_t i = 0; i < written; ++i) {
                    AppendCodepointUtf8(cps[i], &cell_utf8);
                }
            } else if (gres == GHOSTTY_OUT_OF_SPACE && written > 0) {
                std::vector<uint32_t> dyn_cps(written);
                if (ghostty_grid_ref_graphemes(&ref, dyn_cps.data(), dyn_cps.size(), &written) == GHOSTTY_SUCCESS) {
                    for (size_t i = 0; i < written; ++i) {
                        AppendCodepointUtf8(dyn_cps[i], &cell_utf8);
                    }
                }
            }
        }
        if (cell_utf8.empty()) {
            cell_utf8.assign(1, ' ');
        }
        for (char ch : cell_utf8) {
            map.utf8.push_back(ch);
            map.byte_to_col.push_back(c);
        }
    }
    while (!map.utf8.empty() && map.utf8.back() == ' ') {
        map.utf8.pop_back();
        map.byte_to_col.pop_back();
    }
    return map;
}

std::string ToLowerAscii(const std::string& s) {
    std::string out = s;
    for (char& ch : out) {
        if (ch >= 'A' && ch <= 'Z') {
            ch = static_cast<char>(ch - 'A' + 'a');
        }
    }
    return out;
}

}  // namespace

extern "C" {

namespace {

constexpr size_t kMaxPendingPtyReplies = 256u * 1024u;

GhosttyColorRgb RgbaToGhosttyRgb(uint32_t rgba) noexcept {
    return GhosttyColorRgb{
        static_cast<uint8_t>((rgba >> 24) & 0xFFu),
        static_cast<uint8_t>((rgba >> 16) & 0xFFu),
        static_cast<uint8_t>((rgba >> 8) & 0xFFu),
    };
}

void OnTerminalWritePty(
    GhosttyTerminal,
    void* userdata,
    const uint8_t* data,
    size_t len) {
    if (userdata == nullptr || data == nullptr || len == 0) {
        return;
    }
    auto* impl = static_cast<TvGhosttyTerminalImpl*>(userdata);
    if (impl->pending_pty_replies.size() >= kMaxPendingPtyReplies) {
        return;
    }
    size_t avail = kMaxPendingPtyReplies - impl->pending_pty_replies.size();
    size_t take = std::min(len, avail);
    impl->pending_pty_replies.insert(impl->pending_pty_replies.end(), data, data + take);
}

bool OnTerminalSize(
    GhosttyTerminal,
    void* userdata,
    GhosttySizeReportSize* out_size) {
    if (userdata == nullptr || out_size == nullptr) {
        return false;
    }
    const auto* impl = static_cast<const TvGhosttyTerminalImpl*>(userdata);
    out_size->rows = impl->rows;
    out_size->columns = impl->cols;
    out_size->cell_width = std::max<uint32_t>(1u, impl->cell_width_px);
    out_size->cell_height = std::max<uint32_t>(1u, impl->cell_height_px);
    return true;
}

bool OnTerminalColorScheme(
    GhosttyTerminal,
    void* userdata,
    GhosttyColorScheme* out_scheme) {
    if (userdata == nullptr || out_scheme == nullptr) {
        return false;
    }
    const auto* impl = static_cast<const TvGhosttyTerminalImpl*>(userdata);
    *out_scheme = impl->dark_color_scheme
        ? GHOSTTY_COLOR_SCHEME_DARK
        : GHOSTTY_COLOR_SCHEME_LIGHT;
    return true;
}

bool OnTerminalDeviceAttributes(
    GhosttyTerminal,
    void*,
    GhosttyDeviceAttributes* out_attrs) {
    if (out_attrs == nullptr) {
        return false;
    }
    out_attrs->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_VT220;
    out_attrs->primary.features[0] = GHOSTTY_DA_FEATURE_ANSI_COLOR;
    out_attrs->primary.features[1] = GHOSTTY_DA_FEATURE_CLIPBOARD;
    out_attrs->primary.num_features = 2;
    out_attrs->secondary.device_type = GHOSTTY_DA_DEVICE_TYPE_VT220;
    out_attrs->secondary.firmware_version = 100;
    out_attrs->secondary.rom_cartridge = 0;
    return true;
}

GhosttyString OnTerminalXtversion(GhosttyTerminal, void*) {
    static const uint8_t kVersion[] = "TermVR 1.0";
    return GhosttyString{kVersion, sizeof(kVersion) - 1u};
}

}  // namespace

TvGhosttyResult tv_ghostty_terminal_new(
    const TvGhosttyAllocator*,
    TvGhosttyTerminal* out_terminal,
    TvGhosttyTerminalOptions opts) {
    if (out_terminal == nullptr || opts.cols == 0 || opts.rows == 0) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto impl = std::make_unique<TvGhosttyTerminalImpl>();
    impl->cols = opts.cols;
    impl->rows = opts.rows;
    GhosttyResult res = ghostty_terminal_new(nullptr, &impl->term, opts.cols, opts.rows);
    if (res != GHOSTTY_SUCCESS || impl->term == nullptr) {
        return MapResult(res);
    }
    size_t max_bytes = SIZE_MAX;
    ghostty_terminal_set(impl->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &max_bytes);
    if (opts.max_scrollback > 0) {
        size_t max_lines = static_cast<size_t>(opts.max_scrollback);
        ghostty_terminal_set(impl->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES, &max_lines);
    }
    ghostty_terminal_set(impl->term, GHOSTTY_TERMINAL_OPT_USERDATA, impl.get());
    ghostty_terminal_set(
        impl->term, GHOSTTY_TERMINAL_OPT_WRITE_PTY, reinterpret_cast<const void*>(&OnTerminalWritePty));
    ghostty_terminal_set(
        impl->term, GHOSTTY_TERMINAL_OPT_SIZE, reinterpret_cast<const void*>(&OnTerminalSize));
    ghostty_terminal_set(
        impl->term,
        GHOSTTY_TERMINAL_OPT_COLOR_SCHEME,
        reinterpret_cast<const void*>(&OnTerminalColorScheme));
    ghostty_terminal_set(
        impl->term,
        GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES,
        reinterpret_cast<const void*>(&OnTerminalDeviceAttributes));
    ghostty_terminal_set(
        impl->term, GHOSTTY_TERMINAL_OPT_XTVERSION, reinterpret_cast<const void*>(&OnTerminalXtversion));
    static const uint8_t kTerminfoName[] = "xterm-256color";
    GhosttyString terminfo_str{kTerminfoName, sizeof(kTerminfoName) - 1u};
    ghostty_terminal_set(impl->term, GHOSTTY_TERMINAL_OPT_TERMINFO_NAME, &terminfo_str);
    *out_terminal = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_terminal_free(TvGhosttyTerminal terminal) {
    if (terminal == nullptr) return;
    if (terminal->term != nullptr) {
        ghostty_terminal_free(terminal->term);
        terminal->term = nullptr;
    }
    delete terminal;
}

TvGhosttyResult tv_ghostty_terminal_resize(
    TvGhosttyTerminal terminal,
    uint16_t cols,
    uint16_t rows,
    uint32_t cell_width_px,
    uint32_t cell_height_px) {
    if (terminal == nullptr || terminal->term == nullptr || cols == 0 || rows == 0) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    uint32_t cw = std::max<uint32_t>(1, cell_width_px);
    uint32_t ch = std::max<uint32_t>(1, cell_height_px);
    GhosttyResult res = ghostty_terminal_resize(terminal->term, cols, rows, cw, ch);
    if (res != GHOSTTY_SUCCESS) {
        return MapResult(res);
    }
    terminal->cols = cols;
    terminal->rows = rows;
    terminal->cell_width_px = cw;
    terminal->cell_height_px = ch;
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_terminal_vt_write(
    TvGhosttyTerminal terminal,
    const uint8_t* data,
    size_t len) {
    if (terminal == nullptr || terminal->term == nullptr || data == nullptr || len == 0) {
        return;
    }
    ghostty_terminal_vt_write(terminal->term, data, len);
}

TvGhosttyResult tv_ghostty_render_state_new(
    const TvGhosttyAllocator*,
    TvGhosttyRenderState* out_state) {
    if (out_state == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto impl = std::make_unique<TvGhosttyRenderStateImpl>();
    if (ghostty_render_state_new(nullptr, &impl->state) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_iterator_new(nullptr, &impl->row_iter) != GHOSTTY_SUCCESS ||
        ghostty_render_state_row_cells_new(nullptr, &impl->row_cells) != GHOSTTY_SUCCESS) {
        if (impl->row_cells) ghostty_render_state_row_cells_free(impl->row_cells);
        if (impl->row_iter) ghostty_render_state_row_iterator_free(impl->row_iter);
        if (impl->state) ghostty_render_state_free(impl->state);
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_state = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_free(TvGhosttyRenderState state) {
    if (state == nullptr) return;
    if (state->row_cells) ghostty_render_state_row_cells_free(state->row_cells);
    if (state->row_iter) ghostty_render_state_row_iterator_free(state->row_iter);
    if (state->state) ghostty_render_state_free(state->state);
    delete state;
}

TvGhosttyResult tv_ghostty_render_state_update(
    TvGhosttyRenderState state,
    TvGhosttyTerminal terminal) {
    if (state == nullptr || state->state == nullptr || terminal == nullptr || terminal->term == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    GhosttyResult res = ghostty_render_state_update(state->state, terminal->term);
    if (res != GHOSTTY_SUCCESS) {
        return MapResult(res);
    }
    state->dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    ghostty_render_state_get(state->state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &state->dirty);
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyRenderStateDirty tv_ghostty_render_state_get_dirty(TvGhosttyRenderState state) {
    if (state == nullptr) return TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    switch (state->dirty) {
        case GHOSTTY_RENDER_STATE_DIRTY_PARTIAL:
            return TV_GHOSTTY_RENDER_STATE_DIRTY_PARTIAL;
        case GHOSTTY_RENDER_STATE_DIRTY_FULL:
            return TV_GHOSTTY_RENDER_STATE_DIRTY_FULL;
        default:
            return TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    }
}

void tv_ghostty_render_state_clean(TvGhosttyRenderState state) {
    if (state == nullptr || state->state == nullptr) return;
    ghostty_render_state_clean(state->state);
    state->dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
}

TvGhosttyResult tv_ghostty_render_state_row_iterator_new(
    const TvGhosttyAllocator*,
    TvGhosttyRenderStateRowIterator* out_iter) {
    if (out_iter == nullptr) return TV_GHOSTTY_INVALID_VALUE;
    auto impl = std::make_unique<TvGhosttyRenderStateRowIteratorImpl>();
    *out_iter = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_row_iterator_free(TvGhosttyRenderStateRowIterator iter) {
    delete iter;
}

void tv_ghostty_render_state_row_iterator_begin(
    TvGhosttyRenderStateRowIterator iter,
    TvGhosttyRenderState state,
    bool dirty_only) {
    if (iter == nullptr) return;
    iter->state = state;
    iter->dirty_only = dirty_only;
    if (state != nullptr && state->state != nullptr && state->row_iter != nullptr) {
        ghostty_render_state_get(state->state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR, &state->row_iter);
    }
}

bool tv_ghostty_render_state_row_iterator_next(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row) {
    if (iter == nullptr || iter->state == nullptr || iter->state->row_iter == nullptr || out_row == nullptr) {
        return false;
    }
    while (ghostty_render_state_row_iterator_next(iter->state->row_iter)) {
        int32_t vy = 0;
        if (ghostty_render_state_row_get(
                iter->state->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &vy) == GHOSTTY_SUCCESS &&
            vy >= 0) {
            *out_row = static_cast<uint16_t>(vy);
            return true;
        }
    }
    return false;
}

bool tv_ghostty_render_state_row_iterator_next_dirty(
    TvGhosttyRenderStateRowIterator iter,
    uint16_t* out_row) {
    if (iter == nullptr || iter->state == nullptr || iter->state->row_iter == nullptr || out_row == nullptr) {
        return false;
    }
    uint16_t y = 0;
    while (ghostty_render_state_row_iterator_next_dirty(iter->state->row_iter, &y)) {
        int32_t vy = static_cast<int32_t>(y);
        ghostty_render_state_row_get(iter->state->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_VIEWPORT_Y, &vy);
        if (vy >= 0) {
            *out_row = static_cast<uint16_t>(vy);
            return true;
        }
    }
    return false;
}

TvGhosttyResult tv_ghostty_render_state_cell_iterator_new(
    const TvGhosttyAllocator*,
    TvGhosttyRenderStateCellIterator* out_iter) {
    if (out_iter == nullptr) return TV_GHOSTTY_INVALID_VALUE;
    auto impl = std::make_unique<TvGhosttyRenderStateCellIteratorImpl>();
    *out_iter = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_render_state_cell_iterator_free(TvGhosttyRenderStateCellIterator iter) {
    delete iter;
}

void tv_ghostty_render_state_cell_iterator_begin(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyRenderState state,
    uint16_t) {
    if (iter == nullptr) return;
    iter->state = state;
    iter->next_col = 0;
    if (state != nullptr && state->row_iter != nullptr && state->row_cells != nullptr) {
        ghostty_render_state_row_get(state->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS, &state->row_cells);
    }
}

bool tv_ghostty_render_state_cell_iterator_next(
    TvGhosttyRenderStateCellIterator iter,
    TvGhosttyCellSnapshot* out_cell) {
    if (iter == nullptr || iter->state == nullptr || iter->state->row_cells == nullptr || out_cell == nullptr) {
        return false;
    }
    if (!ghostty_render_state_row_cells_next(iter->state->row_cells)) {
        return false;
    }

    GhosttyRenderStateRowCells cells = iter->state->row_cells;
    TvGhosttyCellSnapshot snap{};
    snap.col = iter->next_col++;
    snap.width = 1;
    snap.primary_codepoint = 0x20u;

    GhosttyCell raw_cell{};
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw_cell) == GHOSTTY_SUCCESS) {
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &wide);
        snap.is_continuation = (wide == GHOSTTY_CELL_WIDE_SPACER_TAIL);
        snap.width = (wide == GHOSTTY_CELL_WIDE_WIDE) ? 2 : 1;

        uint32_t cp = 0;
        if (ghostty_cell_get(raw_cell, GHOSTTY_CELL_DATA_CODEPOINT, &cp) == GHOSTTY_SUCCESS && cp != 0) {
            snap.primary_codepoint = cp;
        }
    }

    GhosttyBuffer utf8_buf{};
    utf8_buf.ptr = reinterpret_cast<uint8_t*>(snap.grapheme_utf8);
    utf8_buf.cap = sizeof(snap.grapheme_utf8);
    utf8_buf.len = 0;
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8, &utf8_buf) == GHOSTTY_SUCCESS) {
        snap.grapheme_len = static_cast<uint8_t>(std::min<size_t>(utf8_buf.len, sizeof(snap.grapheme_utf8)));
    } else if (utf8_buf.len > sizeof(snap.grapheme_utf8)) {
        snap.grapheme_len = static_cast<uint8_t>(sizeof(snap.grapheme_utf8));
    }

    uint16_t style_flags = 0;
    bool has_styling = false;
    ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &has_styling);
    if (has_styling) {
        GhosttyStyle st{};
        if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &st) == GHOSTTY_SUCCESS) {
            if (st.flags & GHOSTTY_STYLE_FLAG_BOLD) style_flags |= termvr::vt::kStyleBold;
            if (st.flags & GHOSTTY_STYLE_FLAG_ITALIC) style_flags |= termvr::vt::kStyleItalic;
            if (st.flags & GHOSTTY_STYLE_FLAG_FAINT) style_flags |= termvr::vt::kStyleDim;
            if (st.flags & GHOSTTY_STYLE_FLAG_BLINK) style_flags |= termvr::vt::kStyleBlink;
            if (st.flags & GHOSTTY_STYLE_FLAG_INVERSE) style_flags |= termvr::vt::kStyleInverse;
            if (st.flags & GHOSTTY_STYLE_FLAG_STRIKETHROUGH) style_flags |= termvr::vt::kStyleStrikethrough;
            style_flags |= termvr::vt::PackUnderlineBits(MapUnderlineStyle(GHOSTTY_STYLE_UNDERLINE(st)));
            snap.underline_color = MapStyleColor(st.underline_color);
        }
    }

    bool selected = false;
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_SELECTED, &selected) == GHOSTTY_SUCCESS && selected) {
        style_flags |= termvr::vt::kStyleSelected;
    }
    snap.style_flags = style_flags;

    GhosttyColorRgb fg_rgb{};
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &fg_rgb) == GHOSTTY_SUCCESS) {
        snap.fg = {TV_GHOSTTY_COLOR_RGB, 0, fg_rgb.r, fg_rgb.g, fg_rgb.b};
    }
    GhosttyColorRgb bg_rgb{};
    if (ghostty_render_state_row_cells_get(cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &bg_rgb) == GHOSTTY_SUCCESS) {
        snap.bg = {TV_GHOSTTY_COLOR_RGB, 0, bg_rgb.r, bg_rgb.g, bg_rgb.b};
    }

    *out_cell = snap;
    return true;
}

TvGhosttyResult tv_ghostty_key_encoder_new(
    const TvGhosttyAllocator*,
    TvGhosttyKeyEncoder* out_encoder) {
    if (out_encoder == nullptr) return TV_GHOSTTY_INVALID_VALUE;
    auto impl = std::make_unique<TvGhosttyKeyEncoderImpl>();
    if (ghostty_key_encoder_new(nullptr, &impl->encoder) != GHOSTTY_SUCCESS ||
        ghostty_key_event_new(nullptr, &impl->event) != GHOSTTY_SUCCESS) {
        if (impl->event) ghostty_key_event_free(impl->event);
        if (impl->encoder) ghostty_key_encoder_free(impl->encoder);
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_encoder = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_key_encoder_free(TvGhosttyKeyEncoder encoder) {
    if (encoder == nullptr) return;
    if (encoder->event) ghostty_key_event_free(encoder->event);
    if (encoder->encoder) ghostty_key_encoder_free(encoder->encoder);
    delete encoder;
}

void tv_ghostty_key_encoder_set_opts_from_terminal(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyTerminal terminal) {
    if (encoder == nullptr || encoder->encoder == nullptr || terminal == nullptr || terminal->term == nullptr) {
        return;
    }
    ghostty_key_encoder_set_from_terminal(encoder->encoder, terminal->term);
    uint8_t kflags = 0;
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_KITTY_KEYBOARD_FLAGS, &kflags) == GHOSTTY_SUCCESS) {
        encoder->kitty_flags = kflags;
    } else {
        encoder->kitty_flags = 0;
    }
}

namespace {

bool LegacyCtrlByteFor(uint32_t cp, uint32_t mods, uint8_t* out_byte) noexcept {
    if ((mods & TV_GHOSTTY_MOD_CTRL) == 0 || (mods & TV_GHOSTTY_MOD_SUPER) != 0) {
        return false;
    }
    if (cp >= 'a' && cp <= 'z') {
        if ((mods & TV_GHOSTTY_MOD_SHIFT) != 0) return false;
        *out_byte = static_cast<uint8_t>(cp - 'a' + 1u);
        return true;
    }
    if (cp >= 'A' && cp <= 'Z') {
        if ((mods & TV_GHOSTTY_MOD_SHIFT) != 0) return false;
        *out_byte = static_cast<uint8_t>(cp - 'A' + 1u);
        return true;
    }
    switch (cp) {
        case '@':
        case ' ':
            *out_byte = 0x00u;
            return true;
        case '[':
            *out_byte = 0x1Bu;
            return true;
        case '\\':
            *out_byte = 0x1Cu;
            return true;
        case ']':
            *out_byte = 0x1Du;
            return true;
        case '^':
        case '6':
            *out_byte = 0x1Eu;
            return true;
        case '_':
        case '/':
        case '-':
            *out_byte = 0x1Fu;
            return true;
        case '?':
            *out_byte = 0x7Fu;
            return true;
        default:
            return false;
    }
}

bool IsFixtermsCsiU(const uint8_t* buf, size_t len) noexcept {
    return buf != nullptr && len >= 4u && buf[0] == 0x1Bu && buf[1] == '[' && buf[len - 1u] == 'u';
}

}  // namespace

TvGhosttyResult tv_ghostty_key_encoder_encode(
    TvGhosttyKeyEncoder encoder,
    TvGhosttyKeyEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (encoder == nullptr || encoder->encoder == nullptr || encoder->event == nullptr || out_written == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }

    uint32_t cp = event.unicode_codepoint;
    uint32_t unshifted_cp = (cp >= 'A' && cp <= 'Z') ? (cp + 32u) : cp;
    const bool ctrl_without_shift =
        (event.mods & TV_GHOSTTY_MOD_CTRL) != 0 && (event.mods & TV_GHOSTTY_MOD_SHIFT) == 0;

    uint32_t effective_cp = cp;
    if (ctrl_without_shift && cp >= 'A' && cp <= 'Z') {
        effective_cp = cp + 32u;
    }

    ghostty_key_event_set_action(encoder->event, MapKeyAction(event.action));
    ghostty_key_event_set_key(encoder->event, MapKeyCode(event.key, effective_cp));
    ghostty_key_event_set_mods(encoder->event, MapKeyMods(event.mods));
    ghostty_key_event_set_consumed_mods(encoder->event, 0);
    ghostty_key_event_set_composing(encoder->event, false);
    ghostty_key_event_set_unshifted_codepoint(encoder->event, unshifted_cp);

    std::string fallback_utf8;
    GhosttyString gstr{};
    if (ctrl_without_shift && cp >= 'A' && cp <= 'Z') {
        fallback_utf8.push_back(static_cast<char>(effective_cp));
        gstr.ptr = reinterpret_cast<const uint8_t*>(fallback_utf8.data());
        gstr.len = fallback_utf8.size();
    } else if (event.utf8_text != nullptr && event.utf8_len > 0) {
        if (ctrl_without_shift && event.utf8_len == 1 &&
            event.utf8_text[0] >= 'A' && event.utf8_text[0] <= 'Z') {
            fallback_utf8.push_back(static_cast<char>(event.utf8_text[0] + 32));
            gstr.ptr = reinterpret_cast<const uint8_t*>(fallback_utf8.data());
            gstr.len = fallback_utf8.size();
        } else {
            gstr.ptr = reinterpret_cast<const uint8_t*>(event.utf8_text);
            gstr.len = event.utf8_len;
        }
    } else if (event.key == TV_GHOSTTY_KEY_UNICODE && effective_cp >= 0x20u && effective_cp != 0x7Fu) {
        AppendCodepointUtf8(effective_cp, &fallback_utf8);
        gstr.ptr = reinterpret_cast<const uint8_t*>(fallback_utf8.data());
        gstr.len = fallback_utf8.size();
    }
    ghostty_key_event_set_utf8(encoder->event, gstr);

    GhosttyResult res = ghostty_key_encoder_encode(encoder->encoder, encoder->event, out_buf, buf_cap, out_written);
    if (res == GHOSTTY_SUCCESS &&
        encoder->kitty_flags == 0 &&
        event.action != TV_GHOSTTY_KEY_ACTION_RELEASE &&
        event.key == TV_GHOSTTY_KEY_UNICODE &&
        (*out_written == 0 || IsFixtermsCsiU(out_buf, *out_written))) {
        uint8_t c0 = 0;
        if (LegacyCtrlByteFor(effective_cp, event.mods, &c0)) {
            uint8_t seq[2];
            size_t seq_len = 0;
            if (event.mods & TV_GHOSTTY_MOD_ALT) {
                seq[seq_len++] = 0x1Bu;
            }
            seq[seq_len++] = c0;
            *out_written = seq_len;
            if (out_buf == nullptr || buf_cap < seq_len) {
                return TV_GHOSTTY_OUT_OF_SPACE;
            }
            std::memcpy(out_buf, seq, seq_len);
            return TV_GHOSTTY_SUCCESS;
        }
    }
    return MapResult(res);
}

TvGhosttyResult tv_ghostty_mouse_encoder_new(
    const TvGhosttyAllocator*,
    TvGhosttyMouseEncoder* out_encoder) {
    if (out_encoder == nullptr) return TV_GHOSTTY_INVALID_VALUE;
    auto impl = std::make_unique<TvGhosttyMouseEncoderImpl>();
    if (ghostty_mouse_encoder_new(nullptr, &impl->encoder) != GHOSTTY_SUCCESS ||
        ghostty_mouse_event_new(nullptr, &impl->event) != GHOSTTY_SUCCESS) {
        if (impl->event) ghostty_mouse_event_free(impl->event);
        if (impl->encoder) ghostty_mouse_encoder_free(impl->encoder);
        return TV_GHOSTTY_OUT_OF_MEMORY;
    }
    *out_encoder = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

void tv_ghostty_mouse_encoder_free(TvGhosttyMouseEncoder encoder) {
    if (encoder == nullptr) return;
    if (encoder->event) ghostty_mouse_event_free(encoder->event);
    if (encoder->encoder) ghostty_mouse_encoder_free(encoder->encoder);
    delete encoder;
}

void tv_ghostty_mouse_encoder_set_opts_from_terminal(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyTerminal terminal) {
    if (encoder == nullptr || encoder->encoder == nullptr || terminal == nullptr || terminal->term == nullptr) {
        return;
    }
    encoder->cols = std::max<uint16_t>(1, terminal->cols);
    encoder->rows = std::max<uint16_t>(1, terminal->rows);
    ghostty_mouse_encoder_set_from_terminal(encoder->encoder, terminal->term);
    GhosttyMouseEncoderSize sz = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    sz.screen_width = encoder->cols;
    sz.screen_height = encoder->rows;
    sz.cell_width = 1;
    sz.cell_height = 1;
    ghostty_mouse_encoder_set(encoder->encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &sz);
}

TvGhosttyResult tv_ghostty_mouse_encoder_encode(
    TvGhosttyMouseEncoder encoder,
    TvGhosttyMouseEvent event,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (encoder == nullptr || encoder->encoder == nullptr || encoder->event == nullptr || out_written == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }

    GhosttyMouseAction action = GHOSTTY_MOUSE_ACTION_PRESS;
    bool has_button = true;
    GhosttyMouseButton btn = GHOSTTY_MOUSE_BUTTON_LEFT;

    switch (event.action) {
        case TV_GHOSTTY_MOUSE_PRESS:
            action = GHOSTTY_MOUSE_ACTION_PRESS;
            break;
        case TV_GHOSTTY_MOUSE_RELEASE:
            action = GHOSTTY_MOUSE_ACTION_RELEASE;
            break;
        case TV_GHOSTTY_MOUSE_MOTION:
            action = GHOSTTY_MOUSE_ACTION_MOTION;
            break;
        case TV_GHOSTTY_MOUSE_WHEEL_UP:
            action = GHOSTTY_MOUSE_ACTION_PRESS;
            btn = GHOSTTY_MOUSE_BUTTON_FOUR;
            break;
        case TV_GHOSTTY_MOUSE_WHEEL_DOWN:
            action = GHOSTTY_MOUSE_ACTION_PRESS;
            btn = GHOSTTY_MOUSE_BUTTON_FIVE;
            break;
    }

    if (event.action != TV_GHOSTTY_MOUSE_WHEEL_UP && event.action != TV_GHOSTTY_MOUSE_WHEEL_DOWN) {
        switch (event.button) {
            case TV_GHOSTTY_MOUSE_BUTTON_LEFT: btn = GHOSTTY_MOUSE_BUTTON_LEFT; break;
            case TV_GHOSTTY_MOUSE_BUTTON_MIDDLE: btn = GHOSTTY_MOUSE_BUTTON_MIDDLE; break;
            case TV_GHOSTTY_MOUSE_BUTTON_RIGHT: btn = GHOSTTY_MOUSE_BUTTON_RIGHT; break;
            case TV_GHOSTTY_MOUSE_BUTTON_NONE:
            default:
                has_button = false;
                break;
        }
    }

    ghostty_mouse_event_set_action(encoder->event, action);
    if (has_button) {
        ghostty_mouse_event_set_button(encoder->event, btn);
    } else {
        ghostty_mouse_event_clear_button(encoder->event);
    }
    ghostty_mouse_event_set_mods(encoder->event, MapKeyMods(event.mods));
    GhosttyMousePosition pos{static_cast<float>(event.col) + 0.5f, static_cast<float>(event.row) + 0.5f};
    ghostty_mouse_event_set_position(encoder->event, pos);

    GhosttyResult res = ghostty_mouse_encoder_encode(encoder->encoder, encoder->event, out_buf, buf_cap, out_written);
    return MapResult(res);
}

TvGhosttyResult tv_ghostty_formatter_terminal_new(
    const TvGhosttyAllocator*,
    TvGhosttyFormatter* out_formatter,
    TvGhosttyTerminal terminal,
    TvGhosttyFormatterTerminalOptions opts) {
    if (out_formatter == nullptr || terminal == nullptr || terminal->term == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    auto impl = std::make_unique<TvGhosttyFormatterImpl>();
    GhosttyResult res = ghostty_formatter_terminal_new(nullptr, &impl->formatter, terminal->term);
    if (res != GHOSTTY_SUCCESS || impl->formatter == nullptr) {
        return MapResult(res);
    }
    GhosttyFormatterFormat emit =
        (opts.emit == TV_GHOSTTY_FORMATTER_EMIT_VT) ? GHOSTTY_FORMATTER_FORMAT_VT : GHOSTTY_FORMATTER_FORMAT_PLAIN;
    ghostty_formatter_set(impl->formatter, GHOSTTY_FORMATTER_OPT_EMIT, &emit);
    ghostty_formatter_set(impl->formatter, GHOSTTY_FORMATTER_OPT_TRIM, &opts.trim_trailing_whitespace);
    ghostty_formatter_set(impl->formatter, GHOSTTY_FORMATTER_OPT_UNWRAP, &opts.unwrap_soft_wraps);

    if (opts.use_selection) {
        GhosttySelection sel{};
        if (opts.start_row >= 0 && opts.start_col >= 0 && opts.end_row >= 0 && opts.end_col >= 0) {
            GhosttyPoint p_start{};
            p_start.tag = GHOSTTY_POINT_TAG_VIEWPORT;
            p_start.value.coordinate.x = static_cast<uint16_t>(opts.start_col);
            p_start.value.coordinate.y = static_cast<uint32_t>(opts.start_row);

            GhosttyPoint p_end{};
            p_end.tag = GHOSTTY_POINT_TAG_VIEWPORT;
            p_end.value.coordinate.x = static_cast<uint16_t>(opts.end_col);
            p_end.value.coordinate.y = static_cast<uint32_t>(opts.end_row);

            if (ghostty_terminal_grid_ref(terminal->term, p_start, &sel.start) == GHOSTTY_SUCCESS &&
                ghostty_terminal_grid_ref(terminal->term, p_end, &sel.end) == GHOSTTY_SUCCESS) {
                ghostty_formatter_set(impl->formatter, GHOSTTY_FORMATTER_OPT_SELECTION, &sel);
            }
        } else if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS) {
            ghostty_formatter_set(impl->formatter, GHOSTTY_FORMATTER_OPT_SELECTION, &sel);
        }
    }

    *out_formatter = impl.release();
    return TV_GHOSTTY_SUCCESS;
}

TvGhosttyResult tv_ghostty_formatter_format_alloc(
    TvGhosttyFormatter formatter,
    const TvGhosttyAllocator*,
    uint8_t** out_buf,
    size_t* out_len) {
    if (formatter == nullptr || formatter->formatter == nullptr || out_buf == nullptr || out_len == nullptr) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    return MapResult(ghostty_formatter_format_alloc(formatter->formatter, nullptr, out_buf, out_len));
}

void tv_ghostty_formatter_free(TvGhosttyFormatter formatter) {
    if (formatter == nullptr) return;
    if (formatter->formatter != nullptr) {
        ghostty_formatter_free(formatter->formatter);
        formatter->formatter = nullptr;
    }
    delete formatter;
}

void tv_ghostty_formatter_buffer_free(
    const TvGhosttyAllocator*,
    uint8_t* buf,
    size_t len) {
    if (buf != nullptr) {
        ghostty_free(nullptr, buf, len);
    }
}

}  // extern "C"

namespace {

size_t ValidUtf8SeqLen(const char* ptr, size_t remaining) noexcept {
    if (remaining == 0 || ptr == nullptr) {
        return 0;
    }
    const unsigned char b0 = static_cast<unsigned char>(ptr[0]);
    if (b0 < 0x80u) {
        return 1;
    }
    if (b0 >= 0xC2u && b0 <= 0xDFu) {
        if (remaining >= 2 && (static_cast<unsigned char>(ptr[1]) & 0xC0u) == 0x80u) {
            return 2;
        }
        return 0;
    }
    if (b0 >= 0xE0u && b0 <= 0xEFu) {
        if (remaining < 3) {
            return 0;
        }
        const unsigned char b1 = static_cast<unsigned char>(ptr[1]);
        const unsigned char b2 = static_cast<unsigned char>(ptr[2]);
        if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u) {
            return 0;
        }
        if (b0 == 0xE0u && b1 < 0xA0u) {
            return 0;
        }
        if (b0 == 0xEDu && b1 > 0x9Fu) {
            return 0;
        }
        return 3;
    }
    if (b0 >= 0xF0u && b0 <= 0xF4u) {
        if (remaining < 4) {
            return 0;
        }
        const unsigned char b1 = static_cast<unsigned char>(ptr[1]);
        const unsigned char b2 = static_cast<unsigned char>(ptr[2]);
        const unsigned char b3 = static_cast<unsigned char>(ptr[3]);
        if ((b1 & 0xC0u) != 0x80u || (b2 & 0xC0u) != 0x80u || (b3 & 0xC0u) != 0x80u) {
            return 0;
        }
        if (b0 == 0xF0u && b1 < 0x90u) {
            return 0;
        }
        if (b0 == 0xF4u && b1 > 0x8Fu) {
            return 0;
        }
        return 4;
    }
    return 0;
}

size_t MatchBracketedPasteTerminatorAt(const std::string& buf, size_t idx) noexcept {
    size_t prefix_len = 0;
    if (idx + 2 <= buf.size() && static_cast<unsigned char>(buf[idx]) == 0x1Bu && buf[idx + 1] == '[') {
        prefix_len = 2;
    } else if (idx + 2 <= buf.size() &&
               static_cast<unsigned char>(buf[idx]) == 0xC2u &&
               static_cast<unsigned char>(buf[idx + 1]) == 0x9Bu) {
        prefix_len = 2;
    } else if (idx < buf.size() &&
               static_cast<unsigned char>(buf[idx]) == 0x9Bu &&
               ValidUtf8SeqLen(buf.data() + idx, buf.size() - idx) == 0) {
        prefix_len = 1;
    } else {
        return 0;
    }

    size_t p = idx + prefix_len;
    while (p < buf.size() && buf[p] == '0') {
        ++p;
    }
    if (p + 4 <= buf.size() && buf[p] == '2' && buf[p + 1] == '0' && buf[p + 2] == '1' && buf[p + 3] == '~') {
        return (p + 4) - idx;
    }
    return 0;
}

std::string SanitizeAndNormalizePasteForGhostty(const char* data, size_t len) {
    std::string current(data, len);
    while (true) {
        std::string next;
        next.reserve(current.size());
        bool changed = false;
        size_t i = 0;
        while (i < current.size()) {
            size_t match_len = MatchBracketedPasteTerminatorAt(current, i);
            if (match_len > 0) {
                i += match_len;
                changed = true;
            } else {
                size_t cp_len = ValidUtf8SeqLen(current.data() + i, current.size() - i);
                size_t step = cp_len > 0 ? cp_len : 1u;
                next.append(current, i, step);
                i += step;
            }
        }
        current = std::move(next);
        if (!changed) {
            break;
        }
    }

    std::string body;
    body.reserve(current.size());
    size_t i = 0;
    while (i < current.size()) {
        size_t cp_len = ValidUtf8SeqLen(current.data() + i, current.size() - i);
        unsigned char ch = static_cast<unsigned char>(current[i]);
        if (cp_len == 0) {
            if (ch == 0x85u) {
                body.push_back('\n');
            } else if (ch >= 0x80u && ch <= 0x9Fu) {
                // Drop standalone raw 8-bit C1 control bytes.
            } else {
                body.push_back(current[i]);
            }
            ++i;
        } else if (cp_len == 1) {
            if (ch == '\r') {
                if (i + 1 < current.size() && static_cast<unsigned char>(current[i + 1]) == '\n') {
                    ++i;
                }
                body.push_back('\n');
            } else if (ch == '\n' || ch == '\t') {
                body.push_back(static_cast<char>(ch));
            } else if (ch >= 0x20u && ch != 0x7Fu) {
                body.push_back(current[i]);
            }
            ++i;
        } else if (cp_len == 2) {
            unsigned char b1 = static_cast<unsigned char>(current[i + 1]);
            if (ch == 0xC2u && b1 >= 0x80u && b1 <= 0x9Fu) {
                if (b1 == 0x85u) {
                    body.push_back('\n');
                }
            } else {
                body.append(current, i, 2);
            }
            i += 2;
        } else if (cp_len == 3) {
            unsigned char b1 = static_cast<unsigned char>(current[i + 1]);
            unsigned char b2 = static_cast<unsigned char>(current[i + 2]);
            if (ch == 0xE2u && b1 == 0x80u && (b2 == 0xA8u || b2 == 0xA9u)) {
                body.push_back('\n');
            } else {
                body.append(current, i, 3);
            }
            i += 3;
        } else {
            body.append(current, i, cp_len);
            i += cp_len;
        }
    }
    return body;
}

}  // namespace

extern "C" {

bool tv_ghostty_paste_is_safe(const char* data, size_t len) {
    if (data == nullptr || len == 0) {
        return true;
    }
    if (!ghostty_paste_is_safe(data, len)) {
        return false;
    }
    size_t i = 0;
    while (i < len) {
        size_t cp_len = ValidUtf8SeqLen(data + i, len - i);
        unsigned char ch = static_cast<unsigned char>(data[i]);
        if (cp_len == 0) {
            if (ch >= 0x80u && ch <= 0x9Fu) {
                return false;
            }
            ++i;
        } else if (cp_len == 1) {
            if ((ch < 0x20u && ch != '\t') || ch == 0x7Fu) {
                return false;
            }
            ++i;
        } else if (cp_len == 2) {
            unsigned char b1 = static_cast<unsigned char>(data[i + 1]);
            if (ch == 0xC2u && b1 >= 0x80u && b1 <= 0x9Fu) {
                return false;
            }
            i += 2;
        } else if (cp_len == 3) {
            unsigned char b1 = static_cast<unsigned char>(data[i + 1]);
            unsigned char b2 = static_cast<unsigned char>(data[i + 2]);
            if (ch == 0xE2u && b1 == 0x80u && (b2 == 0xA8u || b2 == 0xA9u)) {
                return false;
            }
            i += 3;
        } else {
            i += cp_len;
        }
    }
    return true;
}

TvGhosttyResult tv_ghostty_terminal_paste(
    TvGhosttyTerminal terminal,
    const char* data,
    size_t len,
    bool allow_unsafe,
    uint8_t* out_buf,
    size_t buf_cap,
    size_t* out_written) {
    if (terminal == nullptr || terminal->term == nullptr || out_written == nullptr ||
        (len > 0 && data == nullptr)) {
        return TV_GHOSTTY_INVALID_VALUE;
    }
    if (len == 0) {
        *out_written = 0;
        return TV_GHOSTTY_SUCCESS;
    }
    if (!allow_unsafe && !tv_ghostty_paste_is_safe(data, len)) {
        return TV_GHOSTTY_UNSAFE_PASTE;
    }
    bool bracketed = termvr::vt::TerminalIsBracketedPasteActive(terminal);
    std::string mutable_copy = SanitizeAndNormalizePasteForGhostty(data, len);
    GhosttyResult res = ghostty_paste_encode(
        mutable_copy.data(), mutable_copy.size(), bracketed, out_buf, buf_cap, out_written);
    return MapResult(res);
}

}  // extern "C"

namespace termvr::vt {

void TerminalSetScrollbackOffset(TvGhosttyTerminal terminal, uint32_t lines_from_bottom) {
    if (terminal == nullptr || terminal->term == nullptr) return;
    if (lines_from_bottom == 0) {
        GhosttyTerminalScrollViewport sv{};
        sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_BOTTOM;
        ghostty_terminal_scroll_viewport(terminal->term, sv);
        return;
    }
    size_t sb_rows = 0;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &sb_rows);
    size_t target_row = (sb_rows > static_cast<size_t>(lines_from_bottom))
        ? (sb_rows - static_cast<size_t>(lines_from_bottom))
        : 0u;
    GhosttyTerminalScrollViewport sv{};
    sv.tag = GHOSTTY_TERMINAL_SCROLL_VIEWPORT_ROW;
    sv.value.row = target_row;
    ghostty_terminal_scroll_viewport(terminal->term, sv);
}

uint32_t TerminalGetScrollbackOffset(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return 0u;
    size_t sb_rows = 0;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &sb_rows);
    GhosttyTerminalScrollbar bar{};
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &bar) != GHOSTTY_SUCCESS) {
        return 0u;
    }
    return sb_rows > bar.offset ? static_cast<uint32_t>(sb_rows - bar.offset) : 0u;
}

uint32_t TerminalGetScrollbackCount(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return 0u;
    size_t sb_rows = 0;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &sb_rows);
    return static_cast<uint32_t>(sb_rows);
}

void TerminalSetSelection(
    TvGhosttyTerminal terminal,
    bool active,
    int32_t start_row,
    int32_t start_col,
    int32_t end_row,
    int32_t end_col) {
    if (terminal == nullptr || terminal->term == nullptr) return;
    if (!active) {
        ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
        return;
    }
    GhosttyPoint p0{};
    p0.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    p0.value.coordinate.x = static_cast<uint16_t>(std::max(0, start_col));
    p0.value.coordinate.y = static_cast<uint32_t>(std::max(0, start_row));

    GhosttyPoint p1{};
    p1.tag = GHOSTTY_POINT_TAG_VIEWPORT;
    p1.value.coordinate.x = static_cast<uint16_t>(std::max(0, end_col));
    p1.value.coordinate.y = static_cast<uint32_t>(std::max(0, end_row));

    GhosttySelection sel{};
    if (ghostty_terminal_grid_ref(terminal->term, p0, &sel.start) == GHOSTTY_SUCCESS &&
        ghostty_terminal_grid_ref(terminal->term, p1, &sel.end) == GHOSTTY_SUCCESS) {
        ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    }
}

void TerminalClearSelection(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return;
    ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_SELECTION, nullptr);
}

bool TerminalHasSelection(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return false;
    GhosttySelection sel{};
    return ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SELECTION, &sel) == GHOSTTY_SUCCESS;
}

bool TerminalIsMouseTrackingActive(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return false;
    bool active = false;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &active);
    return active;
}

bool TerminalIsBracketedPasteActive(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return false;
    GhosttyTerminalModeConfig cfg{GHOSTTY_MODE_BRACKETED_PASTE, false};
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_MODE, &cfg) != GHOSTTY_SUCCESS) {
        return false;
    }
    return cfg.value;
}

bool TerminalIsApplicationCursorActive(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return false;
    GhosttyTerminalModeConfig cfg{GHOSTTY_MODE_CURSOR_KEYS, false};
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_MODE, &cfg) != GHOSTTY_SUCCESS) {
        return false;
    }
    return cfg.value;
}

std::string TerminalGetWindowTitle(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return {};
    GhosttyString s{};
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_TITLE, &s) != GHOSTTY_SUCCESS ||
        s.ptr == nullptr || s.len == 0) {
        return {};
    }
    return std::string(reinterpret_cast<const char*>(s.ptr), s.len);
}

TerminalCursorSnapshot TerminalGetCursorSnapshot(TvGhosttyTerminal terminal) {
    return TerminalGetCursorSnapshotWithState(terminal, nullptr);
}

TerminalCursorSnapshot TerminalGetCursorSnapshotWithState(
    TvGhosttyTerminal terminal,
    TvGhosttyRenderState render_state) {
    TerminalCursorSnapshot snap;
    if (terminal == nullptr || terminal->term == nullptr) return snap;

    uint16_t cx = 0;
    uint16_t cy = 0;
    bool cvis = true;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_CURSOR_X, &cx);
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &cy);
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, &cvis);

    GhosttyTerminalModeConfig blink_cfg{GHOSTTY_MODE_CURSOR_BLINKING, false};
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_MODE, &blink_cfg);

    snap.col = cx;
    snap.row = cy;
    snap.visible = cvis && (TerminalGetScrollbackOffset(terminal) == 0);
    snap.blink = blink_cfg.value;
    snap.shape = CursorShape::kBlock;

    if (render_state != nullptr && render_state->state != nullptr) {
        GhosttyCursorStyle style = GHOSTTY_CURSOR_STYLE_BLOCK;
        if (ghostty_render_state_get(
                render_state->state, GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE, &style) == GHOSTTY_SUCCESS) {
            switch (style) {
                case GHOSTTY_CURSOR_STYLE_BAR:
                    snap.shape = CursorShape::kBar;
                    break;
                case GHOSTTY_CURSOR_STYLE_UNDERLINE:
                    snap.shape = CursorShape::kUnderline;
                    break;
                case GHOSTTY_CURSOR_STYLE_BLOCK:
                case GHOSTTY_CURSOR_STYLE_BLOCK_HOLLOW:
                default:
                    snap.shape = CursorShape::kBlock;
                    break;
            }
        }
    }
    return snap;
}

std::vector<uint8_t> TerminalDrainPtyReplies(TvGhosttyTerminal terminal) {
    std::vector<uint8_t> out;
    if (terminal == nullptr) {
        return out;
    }
    out.swap(terminal->pending_pty_replies);
    return out;
}

void TerminalSetPaletteColors(TvGhosttyTerminal terminal, const PaletteColors& palette) {
    if (terminal == nullptr || terminal->term == nullptr) {
        return;
    }
    GhosttyColorRgb fg = RgbaToGhosttyRgb(palette.foreground_rgba);
    GhosttyColorRgb bg = RgbaToGhosttyRgb(palette.background_rgba);
    GhosttyColorRgb cursor = RgbaToGhosttyRgb(palette.cursor_rgba);
    ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &fg);
    ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &bg);
    ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor);

    double luma = 0.2126 * bg.r + 0.7152 * bg.g + 0.0722 * bg.b;
    terminal->dark_color_scheme = (luma < 128.0);

    GhosttyColorRgb pal[256]{};
    for (size_t i = 0; i < 16; ++i) {
        pal[i] = RgbaToGhosttyRgb(palette.ansi16_rgba[i]);
    }
    for (size_t i = 16; i < 232; ++i) {
        size_t cube = i - 16;
        uint8_t rc = static_cast<uint8_t>(cube / 36u);
        uint8_t gc = static_cast<uint8_t>((cube / 6u) % 6u);
        uint8_t bc = static_cast<uint8_t>(cube % 6u);
        auto scale = [](uint8_t v) -> uint8_t {
            return v == 0 ? 0 : static_cast<uint8_t>(55u + v * 40u);
        };
        pal[i] = GhosttyColorRgb{scale(rc), scale(gc), scale(bc)};
    }
    for (size_t i = 232; i < 256; ++i) {
        uint8_t gray = static_cast<uint8_t>(8u + (i - 232u) * 10u);
        pal[i] = GhosttyColorRgb{gray, gray, gray};
    }
    ghostty_terminal_set(terminal->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
}

void TerminalSetCellMetricsPx(
    TvGhosttyTerminal terminal,
    uint32_t cell_width_px,
    uint32_t cell_height_px) {
    if (terminal == nullptr) {
        return;
    }
    if (cell_width_px > 0) {
        terminal->cell_width_px = cell_width_px;
    }
    if (cell_height_px > 0) {
        terminal->cell_height_px = cell_height_px;
    }
}

std::vector<SearchMatchNative> TerminalSearchScrollback(
    TvGhosttyTerminal terminal,
    const std::string& query,
    bool use_regex,
    bool case_sensitive) {
    std::vector<SearchMatchNative> results;
    if (terminal == nullptr || terminal->term == nullptr || query.empty()) {
        return results;
    }

    size_t total_rows = 0;
    size_t sb_rows = 0;
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total_rows);
    ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &sb_rows);
    uint32_t offset_from_bottom = TerminalGetScrollbackOffset(terminal);
    int32_t viewport_top_abs = static_cast<int32_t>(sb_rows) - static_cast<int32_t>(offset_from_bottom);

    std::unique_ptr<std::regex> compiled_regex;
    if (use_regex) {
        auto flags = std::regex_constants::ECMAScript;
        if (!case_sensitive) flags |= std::regex_constants::icase;
        try {
            compiled_regex = std::make_unique<std::regex>(query, flags);
        } catch (const std::regex_error&) {
            return results;
        }
    }

    std::string needle = case_sensitive ? query : ToLowerAscii(query);
    for (size_t abs_line = 0; abs_line < total_rows; ++abs_line) {
        LineTextMapping mapping = BuildLineTextMapping(
            terminal->term, GHOSTTY_POINT_TAG_SCREEN, static_cast<uint32_t>(abs_line), terminal->cols);
        if (mapping.utf8.empty()) continue;

        int32_t vrow = (static_cast<int32_t>(abs_line) >= viewport_top_abs &&
                        static_cast<int32_t>(abs_line) < viewport_top_abs + static_cast<int32_t>(terminal->rows))
            ? (static_cast<int32_t>(abs_line) - viewport_top_abs)
            : -1;

        if (use_regex) {
            auto begin = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), *compiled_regex);
            auto end = std::sregex_iterator();
            for (auto it = begin; it != end; ++it) {
                size_t b_start = static_cast<size_t>(it->position());
                size_t b_len = static_cast<size_t>(it->length());
                if (b_len == 0 || b_start >= mapping.byte_to_col.size()) continue;
                size_t b_last = std::min(b_start + b_len - 1u, mapping.byte_to_col.size() - 1u);
                SearchMatchNative m;
                m.absolute_line = static_cast<int32_t>(abs_line);
                m.viewport_row = vrow;
                m.start_col = mapping.byte_to_col[b_start];
                uint16_t last_col = mapping.byte_to_col[b_last];
                uint8_t w = last_col < mapping.col_widths.size() ? mapping.col_widths[last_col] : 1;
                m.end_col = static_cast<uint16_t>(last_col + std::max<uint8_t>(1, w) - 1u);
                m.matched_text = it->str();
                results.push_back(std::move(m));
            }
        } else {
            std::string hay = case_sensitive ? mapping.utf8 : ToLowerAscii(mapping.utf8);
            size_t pos = 0;
            while ((pos = hay.find(needle, pos)) != std::string::npos) {
                size_t b_last = std::min(pos + needle.size() - 1u, mapping.byte_to_col.size() - 1u);
                SearchMatchNative m;
                m.absolute_line = static_cast<int32_t>(abs_line);
                m.viewport_row = vrow;
                m.start_col = mapping.byte_to_col[pos];
                uint16_t last_col = mapping.byte_to_col[b_last];
                uint8_t w = last_col < mapping.col_widths.size() ? mapping.col_widths[last_col] : 1;
                m.end_col = static_cast<uint16_t>(last_col + std::max<uint8_t>(1, w) - 1u);
                m.matched_text = mapping.utf8.substr(pos, needle.size());
                results.push_back(std::move(m));
                pos += std::max<size_t>(1u, needle.size());
            }
        }
    }
    return results;
}

bool TerminalDetectLinkAt(
    TvGhosttyTerminal terminal,
    uint16_t viewport_row,
    uint16_t viewport_col,
    DetectedLinkNative* out_link) {
    if (terminal == nullptr || terminal->term == nullptr || out_link == nullptr ||
        viewport_row >= terminal->rows || viewport_col >= terminal->cols) {
        return false;
    }

    std::string uri = ReadHyperlinkAt(terminal->term, viewport_col, viewport_row, GHOSTTY_POINT_TAG_VIEWPORT);
    if (!uri.empty()) {
        uint16_t c_start = viewport_col;
        while (c_start > 0 && ReadHyperlinkAt(terminal->term, c_start - 1, viewport_row, GHOSTTY_POINT_TAG_VIEWPORT) == uri) {
            --c_start;
        }
        uint16_t c_end = viewport_col;
        while (static_cast<uint16_t>(c_end + 1) < terminal->cols &&
               ReadHyperlinkAt(terminal->term, c_end + 1, viewport_row, GHOSTTY_POINT_TAG_VIEWPORT) == uri) {
            ++c_end;
        }
        out_link->kind = DetectedLinkKind::kUrl;
        out_link->start_row = viewport_row;
        out_link->start_col = c_start;
        out_link->end_row = viewport_row;
        out_link->end_col = c_end;
        out_link->target = std::move(uri);
        return true;
    }

    LineTextMapping mapping = BuildLineTextMapping(
        terminal->term, GHOSTTY_POINT_TAG_VIEWPORT, viewport_row, terminal->cols);
    if (mapping.utf8.empty()) return false;

    static const std::regex kUrlRegex(R"(https?://[^\s<>'"`]+)");
    for (auto it = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), kUrlRegex);
         it != std::sregex_iterator(); ++it) {
        std::string matched = it->str();
        size_t len = matched.size();
        while (len > 0 && (matched[len - 1] == '.' || matched[len - 1] == ',' ||
                           matched[len - 1] == ';' || matched[len - 1] == ')')) {
            --len;
        }
        if (len == 0) continue;
        size_t b0 = static_cast<size_t>(it->position());
        size_t b1 = std::min(b0 + len - 1u, mapping.byte_to_col.size() - 1u);
        uint16_t c0 = mapping.byte_to_col[b0];
        uint16_t c1 = mapping.byte_to_col[b1];
        if (viewport_col >= c0 && viewport_col <= c1) {
            out_link->kind = DetectedLinkKind::kUrl;
            out_link->start_row = viewport_row;
            out_link->start_col = c0;
            out_link->end_row = viewport_row;
            out_link->end_col = c1;
            out_link->target = matched.substr(0, len);
            return true;
        }
    }

    static const std::regex kPathRegex(R"((?:~|/|\./|\.\./)[A-Za-z0-9._/\-]+(?::[0-9]+(?::[0-9]+)?)?)");
    for (auto it = std::sregex_iterator(mapping.utf8.begin(), mapping.utf8.end(), kPathRegex);
         it != std::sregex_iterator(); ++it) {
        size_t len = static_cast<size_t>(it->length());
        if (len < 2) continue;
        size_t b0 = static_cast<size_t>(it->position());
        size_t b1 = std::min(b0 + len - 1u, mapping.byte_to_col.size() - 1u);
        uint16_t c0 = mapping.byte_to_col[b0];
        uint16_t c1 = mapping.byte_to_col[b1];
        if (viewport_col >= c0 && viewport_col <= c1) {
            out_link->kind = DetectedLinkKind::kFilepath;
            out_link->start_row = viewport_row;
            out_link->start_col = c0;
            out_link->end_row = viewport_row;
            out_link->end_col = c1;
            out_link->target = it->str();
            return true;
        }
    }

    return false;
}

std::vector<KittyGraphicPlacement> TerminalGetKittyPlacements(TvGhosttyTerminal terminal) {
    std::vector<KittyGraphicPlacement> out;
    if (terminal == nullptr || terminal->term == nullptr) return out;

    GhosttyKittyGraphics kg = nullptr;
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &kg) != GHOSTTY_SUCCESS ||
        kg == nullptr) {
        return out;
    }
    GhosttyKittyGraphicsPlacementIterator iter = nullptr;
    if (ghostty_kitty_graphics_placement_iterator_new(nullptr, &iter) != GHOSTTY_SUCCESS || iter == nullptr) {
        return out;
    }
    if (ghostty_kitty_graphics_get(kg, GHOSTTY_KITTY_GRAPHICS_DATA_PLACEMENT_ITERATOR, &iter) != GHOSTTY_SUCCESS) {
        ghostty_kitty_graphics_placement_iterator_free(iter);
        return out;
    }

    while (ghostty_kitty_graphics_placement_next(iter)) {
        uint32_t image_id = 0;
        uint32_t placement_id = 0;
        int32_t z = -1;
        ghostty_kitty_graphics_placement_get(iter, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_IMAGE_ID, &image_id);
        ghostty_kitty_graphics_placement_get(iter, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_PLACEMENT_ID, &placement_id);
        ghostty_kitty_graphics_placement_get(iter, GHOSTTY_KITTY_GRAPHICS_PLACEMENT_DATA_Z, &z);

        GhosttyKittyGraphicsImage img = ghostty_kitty_graphics_image(kg, image_id);
        if (img == nullptr) continue;

        GhosttyKittyGraphicsPlacementRenderInfo info = GHOSTTY_INIT_SIZED(GhosttyKittyGraphicsPlacementRenderInfo);
        if (ghostty_kitty_graphics_placement_render_info(iter, img, terminal->term, &info) != GHOSTTY_SUCCESS ||
            !info.viewport_visible) {
            continue;
        }

        uint32_t w = 0;
        uint32_t h = 0;
        uint64_t gen = 0;
        GhosttyKittyImageFormat fmt = GHOSTTY_KITTY_IMAGE_FORMAT_RGBA;
        GhosttyString pixels{};
        ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_WIDTH, &w);
        ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_HEIGHT, &h);
        ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_FORMAT, &fmt);
        ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_GENERATION, &gen);
        if (ghostty_kitty_graphics_image_get(img, GHOSTTY_KITTY_GRAPHICS_IMAGE_DATA_PIXELS, &pixels) != GHOSTTY_SUCCESS ||
            pixels.ptr == nullptr || pixels.len == 0 || w == 0 || h == 0) {
            continue;
        }

        KittyGraphicPlacement p;
        p.image_id = image_id;
        p.placement_id = placement_id;
        p.pixel_width = w;
        p.pixel_height = h;
        p.grid_col = static_cast<uint16_t>(std::max<int32_t>(0, info.viewport_col));
        p.grid_row = static_cast<uint16_t>(std::max<int32_t>(0, info.viewport_row));
        p.span_cols = static_cast<uint16_t>(std::max<uint32_t>(1, info.grid_cols));
        p.span_rows = static_cast<uint16_t>(std::max<uint32_t>(1, info.grid_rows));
        p.z_index = z;
        p.revision = gen;

        const size_t pixel_count = static_cast<size_t>(w) * static_cast<size_t>(h);
        if (fmt == GHOSTTY_KITTY_IMAGE_FORMAT_RGBA && pixels.len >= pixel_count * 4u) {
            p.rgba_pixels.assign(pixels.ptr, pixels.ptr + pixel_count * 4u);
        } else if (fmt == GHOSTTY_KITTY_IMAGE_FORMAT_RGB && pixels.len >= pixel_count * 3u) {
            p.rgba_pixels.resize(pixel_count * 4u);
            for (size_t i = 0; i < pixel_count; ++i) {
                p.rgba_pixels[i * 4u + 0u] = pixels.ptr[i * 3u + 0u];
                p.rgba_pixels[i * 4u + 1u] = pixels.ptr[i * 3u + 1u];
                p.rgba_pixels[i * 4u + 2u] = pixels.ptr[i * 3u + 2u];
                p.rgba_pixels[i * 4u + 3u] = 0xFFu;
            }
        } else if (fmt == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY_ALPHA && pixels.len >= pixel_count * 2u) {
            p.rgba_pixels.resize(pixel_count * 4u);
            for (size_t i = 0; i < pixel_count; ++i) {
                uint8_t v = pixels.ptr[i * 2u + 0u];
                p.rgba_pixels[i * 4u + 0u] = v;
                p.rgba_pixels[i * 4u + 1u] = v;
                p.rgba_pixels[i * 4u + 2u] = v;
                p.rgba_pixels[i * 4u + 3u] = pixels.ptr[i * 2u + 1u];
            }
        } else if (fmt == GHOSTTY_KITTY_IMAGE_FORMAT_GRAY && pixels.len >= pixel_count) {
            p.rgba_pixels.resize(pixel_count * 4u);
            for (size_t i = 0; i < pixel_count; ++i) {
                uint8_t v = pixels.ptr[i];
                p.rgba_pixels[i * 4u + 0u] = v;
                p.rgba_pixels[i * 4u + 1u] = v;
                p.rgba_pixels[i * 4u + 2u] = v;
                p.rgba_pixels[i * 4u + 3u] = 0xFFu;
            }
        }
        if (!p.rgba_pixels.empty()) {
            out.push_back(std::move(p));
        }
    }

    ghostty_kitty_graphics_placement_iterator_free(iter);
    return out;
}

uint64_t TerminalGetKittyGraphicsRevision(TvGhosttyTerminal terminal) {
    if (terminal == nullptr || terminal->term == nullptr) return 0u;
    GhosttyKittyGraphics kg = nullptr;
    if (ghostty_terminal_get(terminal->term, GHOSTTY_TERMINAL_DATA_KITTY_GRAPHICS, &kg) != GHOSTTY_SUCCESS ||
        kg == nullptr) {
        return 0u;
    }
    uint64_t gen = 0;
    ghostty_kitty_graphics_get(kg, GHOSTTY_KITTY_GRAPHICS_DATA_GENERATION, &gen);
    return gen;
}

}  // namespace termvr::vt
