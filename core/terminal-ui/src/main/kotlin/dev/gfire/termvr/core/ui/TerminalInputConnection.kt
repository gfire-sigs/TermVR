package dev.gfire.termvr.core.ui

import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

/**
 * Wraps [TerminalInputSession] inside an Android [BaseInputConnection] over a 64-space sentinel.
 */
class TerminalInputConnection(
    private val targetView: View,
    private val imm: InputMethodManager?,
    private val inputSession: TerminalInputSession,
    private val onKeyEventDispatch: (KeyEvent) -> Boolean,
    private val onCopyRequested: () -> Unit = {},
    private val onPasteRequested: () -> Unit = {},
) : BaseInputConnection(targetView, true) {

    private val virtualEditable: SpannableStringBuilder =
        SpannableStringBuilder(TerminalInputSession.SENTINEL_TEXT).also {
            Selection.setSelection(it, TerminalInputSession.SENTINEL_ANCHOR, TerminalInputSession.SENTINEL_ANCHOR)
        }

    override fun getEditable(): Editable = virtualEditable

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val ok = super.setComposingText(text ?: "", newCursorPosition)
        inputSession.setComposingText(text, newCursorPosition)
        return ok
    }

    override fun finishComposingText(): Boolean {
        val ok = inputSession.finishComposingText()
        recenterEditableAndImm()
        return ok
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val ok = inputSession.commitText(text, newCursorPosition)
        recenterEditableAndImm()
        return ok
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        if (inputSession.activePreedit.isEmpty() &&
            start == TerminalInputSession.SENTINEL_ANCHOR &&
            end == TerminalInputSession.SENTINEL_ANCHOR
        ) {
            return true
        }
        val ok = inputSession.setSelection(start, end)
        if (inputSession.activePreedit.isEmpty()) {
            recenterEditableAndImm()
        }
        return ok
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val ok = inputSession.deleteSurroundingText(beforeLength, afterLength)
        recenterEditableAndImm()
        return ok
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val ok = inputSession.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        recenterEditableAndImm()
        return ok
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (onKeyEventDispatch(event)) {
            return true
        }
        return super.sendKeyEvent(event)
    }

    override fun performContextMenuAction(id: Int): Boolean = when (id) {
        android.R.id.copy -> {
            onCopyRequested()
            true
        }
        android.R.id.paste -> {
            onPasteRequested()
            true
        }
        else -> super.performContextMenuAction(id)
    }

    fun recenterEditableAndImm() {
        removeComposingSpans(virtualEditable)
        virtualEditable.replace(0, virtualEditable.length, TerminalInputSession.SENTINEL_TEXT)
        Selection.setSelection(
            virtualEditable,
            TerminalInputSession.SENTINEL_ANCHOR,
            TerminalInputSession.SENTINEL_ANCHOR,
        )
        imm?.updateSelection(
            targetView,
            TerminalInputSession.SENTINEL_ANCHOR,
            TerminalInputSession.SENTINEL_ANCHOR,
            -1,
            -1,
        )
    }

    companion object {
        fun configureEditorInfo(outAttrs: EditorInfo) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
            outAttrs.initialSelStart = TerminalInputSession.SENTINEL_ANCHOR
            outAttrs.initialSelEnd = TerminalInputSession.SENTINEL_ANCHOR
        }
    }
}
