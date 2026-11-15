#include "vt_session_native.h"

#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "font_manager.h"
#include "gles3_renderer.h"

#if defined(__ANDROID__)
#include <jni.h>
#include <android/log.h>
#endif

namespace termvr::vt {

namespace {

uint32_t PackRgba(uint8_t r, uint8_t g, uint8_t b, uint8_t a = 0xFFu) noexcept {
    return (static_cast<uint32_t>(r) << 24u) |
           (static_cast<uint32_t>(g) << 16u) |
           (static_cast<uint32_t>(b) << 8u) |
           static_cast<uint32_t>(a);
}

void PaletteIndexToRgb256(uint8_t idx, const PaletteColors& pal, uint32_t* out_rgba) noexcept {
    if (idx < 16u) {
        *out_rgba = pal.ansi16_rgba[idx];
        return;
    }
    if (idx < 232u) {
        uint8_t cube = static_cast<uint8_t>(idx - 16u);
        uint8_t rc = cube / 36u;
        uint8_t gc = (cube / 6u) % 6u;
        uint8_t bc = cube % 6u;
        auto scale = [](uint8_t v) -> uint8_t {
            return v == 0 ? 0 : static_cast<uint8_t>(55u + v * 40u);
        };
        *out_rgba = PackRgba(scale(rc), scale(gc), scale(bc));
        return;
    }
    uint8_t gray = static_cast<uint8_t>(8u + (idx - 232u) * 10u);
    *out_rgba = PackRgba(gray, gray, gray);
}

}  // namespace

VtSessionNative::VtSessionNative(uint16_t cols, uint16_t rows, uint32_t max_scrollback)
    : cols_(std::max<uint16_t>(1, cols)), rows_(std::max<uint16_t>(1, rows)) {
    TvGhosttyTerminalOptions opts{};
    opts.cols = cols_.load(std::memory_order_relaxed);
    opts.rows = rows_.load(std::memory_order_relaxed);
    opts.max_scrollback = max_scrollback == 0 ? kDefaultScrollbackLines : max_scrollback;

    if (tv_ghostty_terminal_new(nullptr, &terminal_, opts) != TV_GHOSTTY_SUCCESS || terminal_ == nullptr) {
        Close();
        return;
    }
    if (tv_ghostty_render_state_new(nullptr, &render_state_) != TV_GHOSTTY_SUCCESS ||
        tv_ghostty_render_state_row_iterator_new(nullptr, &row_iter_) != TV_GHOSTTY_SUCCESS ||
        tv_ghostty_render_state_cell_iterator_new(nullptr, &cell_iter_) != TV_GHOSTTY_SUCCESS ||
        tv_ghostty_key_encoder_new(nullptr, &key_encoder_) != TV_GHOSTTY_SUCCESS ||
        tv_ghostty_mouse_encoder_new(nullptr, &mouse_encoder_) != TV_GHOSTTY_SUCCESS) {
        Close();
        return;
    }
}

VtSessionNative::~VtSessionNative() {
    Close();
}

bool VtSessionNative::IsValid() const noexcept {
    return !closed_.load(std::memory_order_acquire) && terminal_ != nullptr;
}

void VtSessionNative::Close() {
    if (closed_.exchange(true, std::memory_order_acq_rel)) {
        return;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    // Deterministic reverse teardown order per Section 12.2.
    if (key_encoder_ != nullptr) {
        tv_ghostty_key_encoder_free(key_encoder_);
        key_encoder_ = nullptr;
    }
    if (mouse_encoder_ != nullptr) {
        tv_ghostty_mouse_encoder_free(mouse_encoder_);
        mouse_encoder_ = nullptr;
    }
    if (cell_iter_ != nullptr) {
        tv_ghostty_render_state_cell_iterator_free(cell_iter_);
        cell_iter_ = nullptr;
    }
    if (row_iter_ != nullptr) {
        tv_ghostty_render_state_row_iterator_free(row_iter_);
        row_iter_ = nullptr;
    }
    if (render_state_ != nullptr) {
        tv_ghostty_render_state_free(render_state_);
        render_state_ = nullptr;
    }
    if (terminal_ != nullptr) {
        tv_ghostty_terminal_free(terminal_);
        terminal_ = nullptr;
    }
}

bool VtSessionNative::ResizeGrid(uint16_t cols, uint16_t rows, uint32_t cell_width_px, uint32_t cell_height_px) {
    if (closed_.load(std::memory_order_acquire) || cols == 0 || rows == 0) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr) {
        return false;
    }
    TvGhosttyResult res = tv_ghostty_terminal_resize(terminal_, cols, rows, cell_width_px, cell_height_px);
    if (res != TV_GHOSTTY_SUCCESS) {
        return false;
    }
    cols_.store(cols, std::memory_order_release);
    rows_.store(rows, std::memory_order_release);
    cell_width_px_ = cell_width_px;
    cell_height_px_ = cell_height_px;
    grid_resized_.store(true, std::memory_order_release);
    dirty_flag_.store(true, std::memory_order_release);
    return true;
}

void VtSessionNative::WritePtyBytes(const uint8_t* data, size_t len) {
    if (closed_.load(std::memory_order_acquire) || data == nullptr || len == 0) {
        return;
    }
    size_t offset = 0;
    while (offset < len && !closed_.load(std::memory_order_acquire)) {
        size_t chunk = std::min(kMaxVtWriteChunkBytes, len - offset);
        {
            std::lock_guard<std::mutex> lock(vt_mu_);
            if (terminal_ == nullptr) {
                return;
            }
            tv_ghostty_terminal_vt_write(terminal_, data + offset, chunk);
            dirty_flag_.store(true, std::memory_order_release);
        }
        offset += chunk;
    }
}

void VtSessionNative::SetPalette(const PaletteColors& palette) {
    if (closed_.load(std::memory_order_acquire)) {
        return;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    palette_ = palette;
    grid_resized_.store(true, std::memory_order_release);
    dirty_flag_.store(true, std::memory_order_release);
}

PaletteColors VtSessionNative::GetPalette() const {
    std::lock_guard<std::mutex> lock(vt_mu_);
    return palette_;
}

void VtSessionNative::SetScrollbackOffset(uint32_t lines_from_bottom) {
    if (closed_.load(std::memory_order_acquire)) {
        return;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ != nullptr) {
        TerminalSetScrollbackOffset(terminal_, lines_from_bottom);
        dirty_flag_.store(true, std::memory_order_release);
    }
}

uint32_t VtSessionNative::GetScrollbackOffset() const {
    if (closed_.load(std::memory_order_acquire)) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ == nullptr ? 0u : TerminalGetScrollbackOffset(terminal_);
}

uint32_t VtSessionNative::GetScrollbackCount() const {
    if (closed_.load(std::memory_order_acquire)) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ == nullptr ? 0u : TerminalGetScrollbackCount(terminal_);
}

void VtSessionNative::SetSelection(bool active, int32_t start_row, int32_t start_col, int32_t end_row, int32_t end_col) {
    if (closed_.load(std::memory_order_acquire)) {
        return;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ != nullptr) {
        TerminalSetSelection(terminal_, active, start_row, start_col, end_row, end_col);
        dirty_flag_.store(true, std::memory_order_release);
    }
}

void VtSessionNative::ClearSelection() {
    if (closed_.load(std::memory_order_acquire)) {
        return;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ != nullptr) {
        TerminalClearSelection(terminal_);
        dirty_flag_.store(true, std::memory_order_release);
    }
}

bool VtSessionNative::IsMouseTrackingActive() const {
    if (closed_.load(std::memory_order_acquire)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ != nullptr && TerminalIsMouseTrackingActive(terminal_);
}

bool VtSessionNative::IsBracketedPasteActive() const {
    if (closed_.load(std::memory_order_acquire)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ != nullptr && TerminalIsBracketedPasteActive(terminal_);
}

bool VtSessionNative::IsApplicationCursorActive() const {
    if (closed_.load(std::memory_order_acquire)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ != nullptr && TerminalIsApplicationCursorActive(terminal_);
}

std::string VtSessionNative::GetWindowTitle() const {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ == nullptr ? std::string() : TerminalGetWindowTitle(terminal_);
}

TerminalCursorSnapshot VtSessionNative::GetCursorSnapshot() const {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    return terminal_ == nullptr ? TerminalCursorSnapshot{} : TerminalGetCursorSnapshot(terminal_);
}

std::vector<uint8_t> VtSessionNative::EncodeKey(const TvGhosttyKeyEvent& event) {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr || key_encoder_ == nullptr) {
        return {};
    }
    tv_ghostty_key_encoder_set_opts_from_terminal(key_encoder_, terminal_);
    std::vector<uint8_t> buf(128);
    size_t written = 0;
    TvGhosttyResult res = tv_ghostty_key_encoder_encode(key_encoder_, event, buf.data(), buf.size(), &written);
    if (res == TV_GHOSTTY_OUT_OF_SPACE && written > 0) {
        buf.resize(written);
        res = tv_ghostty_key_encoder_encode(key_encoder_, event, buf.data(), buf.size(), &written);
    }
    if (res != TV_GHOSTTY_SUCCESS) {
        return {};
    }
    buf.resize(written);
    return buf;
}

std::vector<uint8_t> VtSessionNative::EncodeMouse(const TvGhosttyMouseEvent& event) {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr || mouse_encoder_ == nullptr) {
        return {};
    }
    tv_ghostty_mouse_encoder_set_opts_from_terminal(mouse_encoder_, terminal_);
    std::vector<uint8_t> buf(64);
    size_t written = 0;
    TvGhosttyResult res = tv_ghostty_mouse_encoder_encode(mouse_encoder_, event, buf.data(), buf.size(), &written);
    if (res == TV_GHOSTTY_OUT_OF_SPACE && written > 0) {
        buf.resize(written);
        res = tv_ghostty_mouse_encoder_encode(mouse_encoder_, event, buf.data(), buf.size(), &written);
    }
    if (res != TV_GHOSTTY_SUCCESS) {
        return {};
    }
    buf.resize(written);
    return buf;
}

bool VtSessionNative::IsPasteSafe(const char* data, size_t len) {
    return tv_ghostty_paste_is_safe(data, len);
}

bool VtSessionNative::EncodePaste(const char* data, size_t len, bool allow_unsafe, std::vector<uint8_t>* out_bytes) {
    if (closed_.load(std::memory_order_acquire) || out_bytes == nullptr) {
        return false;
    }
    out_bytes->clear();
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr) {
        return false;
    }
    out_bytes->resize(len + 32u);
    size_t written = 0;
    TvGhosttyResult res = tv_ghostty_terminal_paste(
        terminal_, data, len, allow_unsafe, out_bytes->data(), out_bytes->size(), &written);
    if (res == TV_GHOSTTY_OUT_OF_SPACE && written > 0) {
        out_bytes->resize(written);
        res = tv_ghostty_terminal_paste(
            terminal_, data, len, allow_unsafe, out_bytes->data(), out_bytes->size(), &written);
    }
    if (res != TV_GHOSTTY_SUCCESS) {
        out_bytes->clear();
        return false;
    }
    out_bytes->resize(written);
    return true;
}

std::string VtSessionNative::FormatText(
    bool emit_vt,
    bool use_selection,
    int32_t start_row,
    int32_t start_col,
    int32_t end_row,
    int32_t end_col,
    bool trim_trailing_ws,
    bool unwrap_soft_wraps) {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr) {
        return {};
    }
    TvGhosttyFormatterTerminalOptions opts{};
    opts.emit = emit_vt ? TV_GHOSTTY_FORMATTER_EMIT_VT : TV_GHOSTTY_FORMATTER_EMIT_PLAIN;
    opts.trim_trailing_whitespace = trim_trailing_ws;
    opts.unwrap_soft_wraps = unwrap_soft_wraps;
    opts.use_selection = use_selection;
    opts.start_row = start_row;
    opts.start_col = start_col;
    opts.end_row = end_row;
    opts.end_col = end_col;

    TvGhosttyFormatter fmt = nullptr;
    if (tv_ghostty_formatter_terminal_new(nullptr, &fmt, terminal_, opts) != TV_GHOSTTY_SUCCESS || fmt == nullptr) {
        return {};
    }
    uint8_t* raw = nullptr;
    size_t raw_len = 0;
    std::string result;
    if (tv_ghostty_formatter_format_alloc(fmt, nullptr, &raw, &raw_len) == TV_GHOSTTY_SUCCESS && raw != nullptr) {
        result.assign(reinterpret_cast<const char*>(raw), raw_len);
        tv_ghostty_formatter_buffer_free(nullptr, raw, raw_len);
    }
    tv_ghostty_formatter_free(fmt);
    return result;
}

std::vector<SearchMatchNative> VtSessionNative::SearchScrollback(
    const std::string& query,
    bool use_regex,
    bool case_sensitive) {
    if (closed_.load(std::memory_order_acquire)) {
        return {};
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr) {
        return {};
    }
    return TerminalSearchScrollback(terminal_, query, use_regex, case_sensitive);
}

bool VtSessionNative::DetectLinkAt(uint16_t viewport_row, uint16_t viewport_col, DetectedLinkNative* out_link) {
    if (closed_.load(std::memory_order_acquire) || out_link == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr) {
        return false;
    }
    return TerminalDetectLinkAt(terminal_, viewport_row, viewport_col, out_link);
}

uint32_t VtSessionNative::ResolveColor(const TvGhosttyColorValue& c, uint32_t default_rgba) const noexcept {
    switch (c.kind) {
        case TV_GHOSTTY_COLOR_DEFAULT:
            return default_rgba;
        case TV_GHOSTTY_COLOR_PALETTE: {
            uint32_t rgba = default_rgba;
            PaletteIndexToRgb256(c.palette_index, palette_, &rgba);
            return rgba;
        }
        case TV_GHOSTTY_COLOR_RGB:
            return PackRgba(c.r, c.g, c.b);
        default:
            return default_rgba;
    }
}

bool VtSessionNative::ExtractDirtyFrame(bool force_full, ExtractedFrameSnapshot* out_snapshot) {
    if (closed_.load(std::memory_order_acquire) || out_snapshot == nullptr) {
        return false;
    }
    std::lock_guard<std::mutex> lock(vt_mu_);
    if (terminal_ == nullptr || render_state_ == nullptr || row_iter_ == nullptr || cell_iter_ == nullptr) {
        return false;
    }

    if (tv_ghostty_render_state_update(render_state_, terminal_) != TV_GHOSTTY_SUCCESS) {
        return false;
    }

    TvGhosttyRenderStateDirty dirty = tv_ghostty_render_state_get_dirty(render_state_);
    bool full = force_full || (dirty == TV_GHOSTTY_RENDER_STATE_DIRTY_FULL);

    out_snapshot->cols = cols_.load(std::memory_order_relaxed);
    out_snapshot->rows = rows_.load(std::memory_order_relaxed);
    out_snapshot->full_redraw = full;
    out_snapshot->any_dirty = full || (dirty != TV_GHOSTTY_RENDER_STATE_DIRTY_FALSE);
    out_snapshot->palette = palette_;
    out_snapshot->cursor = TerminalGetCursorSnapshot(terminal_);
    out_snapshot->dirty_rows.clear();

    uint64_t krev = TerminalGetKittyGraphicsRevision(terminal_);
    out_snapshot->kitty_revision = krev;
    if (krev != last_seen_kitty_rev_ || force_full) {
        last_seen_kitty_rev_ = krev;
        out_snapshot->kitty_placements = TerminalGetKittyPlacements(terminal_);
    }

    if (out_snapshot->any_dirty) {
        tv_ghostty_render_state_row_iterator_begin(row_iter_, render_state_, !full);
        uint16_t row_idx = 0;
        while (full ? tv_ghostty_render_state_row_iterator_next(row_iter_, &row_idx)
                    : tv_ghostty_render_state_row_iterator_next_dirty(row_iter_, &row_idx)) {
            ExtractedRow erow;
            erow.row_index = row_idx;
            erow.cells.reserve(out_snapshot->cols);

            tv_ghostty_render_state_cell_iterator_begin(cell_iter_, render_state_, row_idx);
            TvGhosttyCellSnapshot snap_cell{};
            while (tv_ghostty_render_state_cell_iterator_next(cell_iter_, &snap_cell)) {
                ExtractedCell ec;
                ec.col = snap_cell.col;
                ec.width = snap_cell.width;
                ec.is_continuation = snap_cell.is_continuation;
                ec.style_flags = snap_cell.style_flags;
                ec.fg_rgba = ResolveColor(snap_cell.fg, palette_.foreground_rgba);
                ec.bg_rgba = ResolveColor(snap_cell.bg, palette_.background_rgba);
                ec.deco_rgba = (snap_cell.underline_color.kind == TV_GHOSTTY_COLOR_DEFAULT)
                    ? 0u
                    : ResolveColor(snap_cell.underline_color, ec.fg_rgba);
                ec.primary_codepoint = snap_cell.primary_codepoint;
                if (snap_cell.grapheme_len > 0) {
                    ec.grapheme_utf8.assign(snap_cell.grapheme_utf8, snap_cell.grapheme_len);
                }
                erow.cells.push_back(std::move(ec));
            }
            out_snapshot->dirty_rows.push_back(std::move(erow));
        }
    }

    tv_ghostty_render_state_clean(render_state_);
    dirty_flag_.store(false, std::memory_order_release);
    return true;
}

}  // namespace termvr::vt

#if defined(__ANDROID__)

namespace {

using termvr::vt::BundledFontAssets;
using termvr::vt::FontConfigNative;
using termvr::vt::Gles3Renderer;
using termvr::vt::PaneViewportSpec;
using termvr::vt::PaletteColors;
using termvr::vt::RendererShaderSources;
using termvr::vt::SplitDividerSpec;
using termvr::vt::VtSessionNative;

std::string JStringToUtf8(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return out;
}

std::vector<uint8_t> JByteArrayToVec(JNIEnv* env, jbyteArray arr) {
    if (arr == nullptr) {
        return {};
    }
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) {
        return {};
    }
    std::vector<uint8_t> out(static_cast<size_t>(len));
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray VecToJByteArray(JNIEnv* env, const std::vector<uint8_t>& vec) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(vec.size()));
    if (arr != nullptr && !vec.empty()) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(vec.size()),
                                reinterpret_cast<const jbyte*>(vec.data()));
    }
    return arr;
}

VtSessionNative* AsSession(jlong handle) {
    return reinterpret_cast<VtSessionNative*>(static_cast<intptr_t>(handle));
}

Gles3Renderer* AsRenderer(jlong handle) {
    return reinterpret_cast<Gles3Renderer*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeCreateSession(
    JNIEnv*, jclass, jint cols, jint rows, jint maxScrollback) {
    auto* sess = new (std::nothrow) VtSessionNative(
        static_cast<uint16_t>(std::max(1, cols)),
        static_cast<uint16_t>(std::max(1, rows)),
        static_cast<uint32_t>(std::max(0, maxScrollback)));
    if (sess == nullptr || !sess->IsValid()) {
        delete sess;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(sess));
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeDestroySession(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    if (sess != nullptr) {
        sess->Close();
        delete sess;
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeResizeGrid(
    JNIEnv*, jclass, jlong handle, jint cols, jint rows, jint cellWidthPx, jint cellHeightPx) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr || cols <= 0 || rows <= 0) {
        return JNI_FALSE;
    }
    return sess->ResizeGrid(
        static_cast<uint16_t>(cols),
        static_cast<uint16_t>(rows),
        static_cast<uint32_t>(std::max(1, cellWidthPx)),
        static_cast<uint32_t>(std::max(1, cellHeightPx))) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeWritePtyBytes(
    JNIEnv* env, jclass, jlong handle, jbyteArray data, jint offset, jint length) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr || data == nullptr || length <= 0 || offset < 0) {
        return;
    }
    jsize total = env->GetArrayLength(data);
    if (offset >= total) {
        return;
    }
    jsize clamped_len = std::min<jsize>(length, total - offset);
    jbyte* ptr = env->GetByteArrayElements(data, nullptr);
    if (ptr == nullptr) {
        return;
    }
    sess->WritePtyBytes(reinterpret_cast<const uint8_t*>(ptr + offset), static_cast<size_t>(clamped_len));
    env->ReleaseByteArrayElements(data, ptr, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeSetPalette(
    JNIEnv* env, jclass, jlong handle, jint fgRgba, jint bgRgba, jint cursorRgba, jint selectionRgba, jintArray ansi16) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr || ansi16 == nullptr || env->GetArrayLength(ansi16) < 16) {
        return;
    }
    PaletteColors pal;
    pal.foreground_rgba = static_cast<uint32_t>(fgRgba);
    pal.background_rgba = static_cast<uint32_t>(bgRgba);
    pal.cursor_rgba = static_cast<uint32_t>(cursorRgba);
    pal.selection_rgba = static_cast<uint32_t>(selectionRgba);
    jint buf[16];
    env->GetIntArrayRegion(ansi16, 0, 16, buf);
    for (int i = 0; i < 16; ++i) {
        pal.ansi16_rgba[static_cast<size_t>(i)] = static_cast<uint32_t>(buf[i]);
    }
    sess->SetPalette(pal);
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeSetScrollbackOffset(
    JNIEnv*, jclass, jlong handle, jint linesFromBottom) {
    VtSessionNative* sess = AsSession(handle);
    if (sess != nullptr) {
        sess->SetScrollbackOffset(static_cast<uint32_t>(std::max(0, linesFromBottom)));
    }
}

JNIEXPORT jint JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeGetScrollbackOffset(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return sess == nullptr ? 0 : static_cast<jint>(sess->GetScrollbackOffset());
}

JNIEXPORT jint JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeGetScrollbackCount(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return sess == nullptr ? 0 : static_cast<jint>(sess->GetScrollbackCount());
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeSetSelection(
    JNIEnv*, jclass, jlong handle, jboolean active, jint startRow, jint startCol, jint endRow, jint endCol) {
    VtSessionNative* sess = AsSession(handle);
    if (sess != nullptr) {
        sess->SetSelection(active == JNI_TRUE, startRow, startCol, endRow, endCol);
    }
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeClearSelection(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    if (sess != nullptr) {
        sess->ClearSelection();
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeIsDirty(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return (sess != nullptr && sess->IsDirty()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeIsMouseTrackingActive(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return (sess != nullptr && sess->IsMouseTrackingActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeIsBracketedPasteActive(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return (sess != nullptr && sess->IsBracketedPasteActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeIsApplicationCursorActive(
    JNIEnv*, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    return (sess != nullptr && sess->IsApplicationCursorActive()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeGetWindowTitle(
    JNIEnv* env, jclass, jlong handle) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(sess->GetWindowTitle().c_str());
}

JNIEXPORT jintArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeGetCursorState(
    JNIEnv* env, jclass, jlong handle) {
    jintArray out = env->NewIntArray(5);
    if (out == nullptr) {
        return nullptr;
    }
    jint vals[5] = {0, 0, 1, 1, 1};
    VtSessionNative* sess = AsSession(handle);
    if (sess != nullptr) {
        auto snap = sess->GetCursorSnapshot();
        vals[0] = snap.col;
        vals[1] = snap.row;
        vals[2] = snap.visible ? 1 : 0;
        vals[3] = snap.blink ? 1 : 0;
        vals[4] = static_cast<jint>(snap.shape);
    }
    env->SetIntArrayRegion(out, 0, 5, vals);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeEncodeKey(
    JNIEnv* env, jclass, jlong handle, jint keyCode, jint action, jint mods, jint codepoint, jstring utf8Text) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr) {
        return env->NewByteArray(0);
    }
    std::string text = JStringToUtf8(env, utf8Text);
    TvGhosttyKeyEvent ev{};
    ev.key = static_cast<TvGhosttyKey>(keyCode);
    ev.action = static_cast<TvGhosttyKeyAction>(action);
    ev.mods = static_cast<uint32_t>(mods);
    ev.unicode_codepoint = static_cast<uint32_t>(codepoint);
    ev.utf8_text = text.empty() ? nullptr : text.data();
    ev.utf8_len = text.size();
    return VecToJByteArray(env, sess->EncodeKey(ev));
}

JNIEXPORT jbyteArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeEncodeMouse(
    JNIEnv* env, jclass, jlong handle, jint action, jint button, jint mods, jint col, jint row, jint pixelX, jint pixelY) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr) {
        return env->NewByteArray(0);
    }
    TvGhosttyMouseEvent ev{};
    ev.action = static_cast<TvGhosttyMouseAction>(action);
    ev.button = static_cast<TvGhosttyMouseButton>(button);
    ev.mods = static_cast<uint32_t>(mods);
    ev.col = static_cast<uint16_t>(std::max(0, col));
    ev.row = static_cast<uint16_t>(std::max(0, row));
    ev.pixel_x = static_cast<uint32_t>(std::max(0, pixelX));
    ev.pixel_y = static_cast<uint32_t>(std::max(0, pixelY));
    return VecToJByteArray(env, sess->EncodeMouse(ev));
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeIsPasteSafe(
    JNIEnv* env, jclass, jstring text) {
    std::string utf8 = JStringToUtf8(env, text);
    return VtSessionNative::IsPasteSafe(utf8.data(), utf8.size()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeEncodePaste(
    JNIEnv* env, jclass, jlong handle, jstring text, jboolean allowUnsafe) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr || text == nullptr) {
        return nullptr;
    }
    std::string utf8 = JStringToUtf8(env, text);
    std::vector<uint8_t> encoded;
    if (!sess->EncodePaste(utf8.data(), utf8.size(), allowUnsafe == JNI_TRUE, &encoded)) {
        return nullptr;
    }
    return VecToJByteArray(env, encoded);
}

JNIEXPORT jstring JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeFormatText(
    JNIEnv* env, jclass, jlong handle, jboolean emitVt, jboolean useSelection,
    jint startRow, jint startCol, jint endRow, jint endCol,
    jboolean trimTrailingWhitespace, jboolean unwrapSoftWraps) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr) {
        return env->NewStringUTF("");
    }
    std::string formatted = sess->FormatText(
        emitVt == JNI_TRUE,
        useSelection == JNI_TRUE,
        startRow, startCol, endRow, endCol,
        trimTrailingWhitespace == JNI_TRUE,
        unwrapSoftWraps == JNI_TRUE);
    return env->NewStringUTF(formatted.c_str());
}

JNIEXPORT jobjectArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeSearchScrollback(
    JNIEnv* env, jclass, jlong handle, jstring query, jboolean useRegex, jboolean caseSensitive) {
    VtSessionNative* sess = AsSession(handle);
    jclass str_cls = env->FindClass("java/lang/String");
    if (sess == nullptr || query == nullptr || str_cls == nullptr) {
        return env->NewObjectArray(0, str_cls, nullptr);
    }
    std::string q = JStringToUtf8(env, query);
    auto matches = sess->SearchScrollback(q, useRegex == JNI_TRUE, caseSensitive == JNI_TRUE);
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(matches.size()), str_cls, nullptr);
    for (size_t i = 0; i < matches.size(); ++i) {
        const auto& m = matches[i];
        std::string packed = std::to_string(m.absolute_line) + "\t" +
                             std::to_string(m.viewport_row) + "\t" +
                             std::to_string(m.start_col) + "\t" +
                             std::to_string(m.end_col) + "\t" +
                             m.matched_text;
        jstring s = env->NewStringUTF(packed.c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeDetectLinkAt(
    JNIEnv* env, jclass, jlong handle, jint viewportRow, jint viewportCol) {
    VtSessionNative* sess = AsSession(handle);
    if (sess == nullptr || viewportRow < 0 || viewportCol < 0) {
        return nullptr;
    }
    termvr::vt::DetectedLinkNative link;
    if (!sess->DetectLinkAt(static_cast<uint16_t>(viewportRow), static_cast<uint16_t>(viewportCol), &link)) {
        return nullptr;
    }
    std::string packed = std::to_string(static_cast<int>(link.kind)) + "\t" +
                         std::to_string(link.start_row) + "\t" +
                         std::to_string(link.start_col) + "\t" +
                         std::to_string(link.end_row) + "\t" +
                         std::to_string(link.end_col) + "\t" +
                         link.target;
    return env->NewStringUTF(packed.c_str());
}

JNIEXPORT jlong JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeCreateRenderer(JNIEnv*, jclass) {
    auto* r = new (std::nothrow) Gles3Renderer();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(r));
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeDestroyRenderer(JNIEnv*, jclass, jlong rendererHandle) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    delete r;
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererSetShaders(
    JNIEnv* env, jclass, jlong rendererHandle, jstring bgVert, jstring bgFrag, jstring glyphVert, jstring glyphFrag) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r == nullptr) {
        return;
    }
    RendererShaderSources src;
    src.bg_vert = JStringToUtf8(env, bgVert);
    src.bg_frag = JStringToUtf8(env, bgFrag);
    src.glyph_vert = JStringToUtf8(env, glyphVert);
    src.glyph_frag = JStringToUtf8(env, glyphFrag);
    r->SetShaderSources(src);
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererInitGl(JNIEnv*, jclass, jlong rendererHandle) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    return (r != nullptr && r->InitializeGl()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererReleaseGl(JNIEnv*, jclass, jlong rendererHandle) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r != nullptr) {
        r->ReleaseGl();
    }
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererContextLost(
    JNIEnv*, jclass, jlong rendererHandle, jlong newEpoch) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r != nullptr) {
        r->NotifyContextLost(static_cast<uint64_t>(std::max<jlong>(1, newEpoch)));
    }
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererSetBundledFonts(
    JNIEnv* env, jclass, jlong rendererHandle,
    jbyteArray regular, jbyteArray bold, jbyteArray italic, jbyteArray boldItalic, jbyteArray nerdSymbols) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r == nullptr) {
        return;
    }
    BundledFontAssets assets;
    assets.jetbrains_regular = JByteArrayToVec(env, regular);
    assets.jetbrains_bold = JByteArrayToVec(env, bold);
    assets.jetbrains_italic = JByteArrayToVec(env, italic);
    assets.jetbrains_bold_italic = JByteArrayToVec(env, boldItalic);
    assets.nerd_symbols = JByteArrayToVec(env, nerdSymbols);
    r->SetBundledFonts(std::move(assets));
}

JNIEXPORT jintArray JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererConfigureFont(
    JNIEnv* env, jclass, jlong rendererHandle,
    jstring familyId, jstring customPath, jstring customSha256,
    jfloat sizeSp, jfloat cellHeightMultiplier, jfloat density,
    jboolean ligaturesEnabled, jboolean nerdSymbolsEnabled) {
    jintArray out = env->NewIntArray(3);
    jint metrics_arr[3] = {10, 20, 16};
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r != nullptr) {
        FontConfigNative cfg;
        cfg.family_id = JStringToUtf8(env, familyId);
        cfg.custom_font_path = JStringToUtf8(env, customPath);
        cfg.custom_font_sha256 = JStringToUtf8(env, customSha256);
        cfg.size_sp = sizeSp;
        cfg.cell_height_multiplier = cellHeightMultiplier;
        cfg.density = density;
        cfg.ligatures_enabled = (ligaturesEnabled == JNI_TRUE);
        cfg.nerd_font_symbols_enabled = (nerdSymbolsEnabled == JNI_TRUE);
        r->ConfigureFont(cfg);
        auto m = r->CurrentCellMetrics();
        metrics_arr[0] = static_cast<jint>(m.cell_width_px);
        metrics_arr[1] = static_cast<jint>(m.cell_height_px);
        metrics_arr[2] = static_cast<jint>(m.baseline_px);
    }
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 3, metrics_arr);
    }
    return out;
}

JNIEXPORT void JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererDetachSession(
    JNIEnv*, jclass, jlong rendererHandle, jlong sessionHandle) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r != nullptr) {
        r->DetachSession(sessionHandle);
    }
}

JNIEXPORT jboolean JNICALL
Java_dev_gfire_termvr_core_vt_JniVtBindings_nativeRendererDrawFrame(
    JNIEnv* env, jclass, jlong rendererHandle,
    jint surfaceWidthPx, jint surfaceHeightPx,
    jboolean forceFullRedraw, jint surfaceClearRgba,
    jlongArray paneSessionHandles, jintArray paneBoundsAndFlags,
    jobjectArray panePreeditTexts, jintArray panePreeditColors,
    jintArray dividerRectsAndColors) {
    Gles3Renderer* r = AsRenderer(rendererHandle);
    if (r == nullptr) {
        return JNI_FALSE;
    }

    std::vector<PaneViewportSpec> panes;
    if (paneSessionHandles != nullptr && paneBoundsAndFlags != nullptr) {
        jsize count = env->GetArrayLength(paneSessionHandles);
        jsize bounds_len = env->GetArrayLength(paneBoundsAndFlags);
        if (count > 0 && bounds_len >= count * 8) {
            std::vector<jlong> handles(static_cast<size_t>(count));
            std::vector<jint> bounds(static_cast<size_t>(bounds_len));
            env->GetLongArrayRegion(paneSessionHandles, 0, count, handles.data());
            env->GetIntArrayRegion(paneBoundsAndFlags, 0, bounds_len, bounds.data());

            std::vector<jint> pcolors;
            if (panePreeditColors != nullptr && env->GetArrayLength(panePreeditColors) >= count * 3) {
                pcolors.resize(static_cast<size_t>(count) * 3u);
                env->GetIntArrayRegion(panePreeditColors, 0, count * 3, pcolors.data());
            }

            panes.reserve(static_cast<size_t>(count));
            for (jsize i = 0; i < count; ++i) {
                size_t bi = static_cast<size_t>(i) * 8u;
                PaneViewportSpec spec;
                spec.session_handle = handles[static_cast<size_t>(i)];
                spec.x_px = bounds[bi + 0];
                spec.y_px = bounds[bi + 1];
                spec.width_px = bounds[bi + 2];
                spec.height_px = bounds[bi + 3];
                spec.focused = (bounds[bi + 4] != 0);
                spec.cursor_blink_visible = (bounds[bi + 5] != 0);
                spec.preedit.cursor_col = static_cast<uint16_t>(std::max(0, bounds[bi + 6]));
                spec.preedit.cursor_row = static_cast<uint16_t>(std::max(0, bounds[bi + 7]));

                if (panePreeditTexts != nullptr && i < env->GetArrayLength(panePreeditTexts)) {
                    auto jtxt = static_cast<jstring>(env->GetObjectArrayElement(panePreeditTexts, i));
                    if (jtxt != nullptr) {
                        spec.preedit.text_utf8 = JStringToUtf8(env, jtxt);
                        spec.preedit.active = !spec.preedit.text_utf8.empty();
                        env->DeleteLocalRef(jtxt);
                    }
                }
                if (!pcolors.empty()) {
                    size_t ci = static_cast<size_t>(i) * 3u;
                    spec.preedit.fg_rgba = static_cast<uint32_t>(pcolors[ci + 0]);
                    spec.preedit.bg_rgba = static_cast<uint32_t>(pcolors[ci + 1]);
                    spec.preedit.underline_rgba = static_cast<uint32_t>(pcolors[ci + 2]);
                }
                panes.push_back(std::move(spec));
            }
        }
    }

    std::vector<SplitDividerSpec> dividers;
    if (dividerRectsAndColors != nullptr) {
        jsize dlen = env->GetArrayLength(dividerRectsAndColors);
        if (dlen >= 5) {
            jsize dcount = dlen / 5;
            std::vector<jint> raw(static_cast<size_t>(dcount) * 5u);
            env->GetIntArrayRegion(dividerRectsAndColors, 0, dcount * 5, raw.data());
            dividers.reserve(static_cast<size_t>(dcount));
            for (jsize i = 0; i < dcount; ++i) {
                size_t di = static_cast<size_t>(i) * 5u;
                SplitDividerSpec d;
                d.x_px = raw[di + 0];
                d.y_px = raw[di + 1];
                d.width_px = raw[di + 2];
                d.height_px = raw[di + 3];
                d.rgba = static_cast<uint32_t>(raw[di + 4]);
                dividers.push_back(d);
            }
        }
    }

    return r->RenderFrame(
        surfaceWidthPx,
        surfaceHeightPx,
        panes,
        dividers,
        forceFullRedraw == JNI_TRUE,
        static_cast<uint32_t>(surfaceClearRgba)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

#endif  // __ANDROID__
