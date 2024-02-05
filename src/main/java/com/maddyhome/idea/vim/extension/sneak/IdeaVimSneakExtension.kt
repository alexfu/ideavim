/*
 * Copyright 2003-2024 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.extension.sneak

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import com.maddyhome.idea.vim.VimProjectService
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.api.options
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.TextRange
import com.maddyhome.idea.vim.extension.ExtensionHandler
import com.maddyhome.idea.vim.extension.VimExtension
import com.maddyhome.idea.vim.extension.VimExtensionFacade
import com.maddyhome.idea.vim.extension.VimExtensionHandler
import com.maddyhome.idea.vim.helper.StrictMode
import com.maddyhome.idea.vim.newapi.ij
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


private const val DEFAULT_HIGHLIGHT_DURATION_SNEAK: Long = 300

// By [Mikhail Levchenko](https://github.com/Mishkun)
// Original repository with the plugin: https://github.com/Mishkun/ideavim-sneak
internal class IdeaVimSneakExtension : VimExtension {
  override fun getName(): String = "sneak"

  override fun init() {
    val highlightHandler = HighlightHandler()
    mapToFunctionAndProvideKeys("s", SneakHandler(highlightHandler, Direction.FORWARD))
    mapToFunctionAndProvideKeys("S", SneakHandler(highlightHandler, Direction.BACKWARD))

    // workaround to support ; and , commands
    mapToFunctionAndProvideKeys("f", SneakMemoryHandler("f"))
    mapToFunctionAndProvideKeys("F", SneakMemoryHandler("F"))
    mapToFunctionAndProvideKeys("t", SneakMemoryHandler("t"))
    mapToFunctionAndProvideKeys("T", SneakMemoryHandler("T"))

    mapToFunctionAndProvideKeys(";", SneakRepeatHandler(highlightHandler, RepeatDirection.IDENTICAL))
    mapToFunctionAndProvideKeys(",", SneakRepeatHandler(highlightHandler, RepeatDirection.REVERSE))
  }

  private class SneakHandler(
    private val highlightHandler: HighlightHandler,
    private val direction: Direction,
  ) : ExtensionHandler {
    override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
      val charone = getChar(editor) ?: return
      val chartwo = getChar(editor) ?: return
      val range = Util.jumpTo(editor, charone, chartwo, direction)
      range?.let { highlightHandler.highlightSneakRange(editor.ij, range) }
      Util.lastSymbols = "${charone}${chartwo}"
      Util.lastSDirection = direction
    }

    private fun getChar(editor: VimEditor): Char? {
      val key = VimExtensionFacade.inputKeyStroke(editor.ij)
      return when {
        key.keyChar == KeyEvent.CHAR_UNDEFINED || key.keyCode == KeyEvent.VK_ESCAPE -> null
        else -> key.keyChar
      }
    }
  }

  /**
   * This class acts as proxy for normal find commands because we need to update [Util.lastSDirection]
   */
  private class SneakMemoryHandler(private val char: String) : VimExtensionHandler {
    override fun execute(editor: Editor, context: DataContext) {
      Util.lastSDirection = null
      VimExtensionFacade.executeNormalWithoutMapping(injector.parser.parseKeys(char), editor)
    }
  }

  private class SneakRepeatHandler(
    private val highlightHandler: HighlightHandler,
    private val direction: RepeatDirection,
  ) : ExtensionHandler {
    override fun execute(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments) {
      val lastSDirection = Util.lastSDirection
      if (lastSDirection != null) {
        val (charone, chartwo) = Util.lastSymbols.toList()
        val jumpRange = Util.jumpTo(editor, charone, chartwo, direction.map(lastSDirection))
        jumpRange?.let { highlightHandler.highlightSneakRange(editor.ij, jumpRange) }
      } else {
        VimExtensionFacade.executeNormalWithoutMapping(injector.parser.parseKeys(direction.symb), editor.ij)
      }
    }
  }

  private object Util {
    var lastSDirection: Direction? = null
    var lastSymbols: String = ""
    fun jumpTo(editor: VimEditor, charone: Char, chartwo: Char, sneakDirection: Direction): TextRange? {
      val caret = editor.primaryCaret()
      val position = caret.offset.point
      val chars = editor.text()
      val foundPosition = sneakDirection.findBiChar(editor, chars, position, charone, chartwo)
      if (foundPosition != null) {
        editor.primaryCaret().moveToOffset(foundPosition)
      }
      editor.ij.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
      return foundPosition?.let { TextRange(foundPosition, foundPosition + 2) }
    }
  }

  private enum class Direction(val offset: Int) {
    FORWARD(1) {
      override fun findBiChar(
        editor: VimEditor,
        charSequence: CharSequence,
        position: Int,
        charone: Char,
        chartwo: Char
      ): Int? {
        for (i in (position + offset) until charSequence.length - 1) {
          if (matches(editor, charSequence, i, charone, chartwo)) {
            return i
          }
        }
        return null
      }
    },
    BACKWARD(-1) {
      override fun findBiChar(
        editor: VimEditor,
        charSequence: CharSequence,
        position: Int,
        charone: Char,
        chartwo: Char
      ): Int? {
        for (i in (position + offset) downTo 0) {
          if (matches(editor, charSequence, i, charone, chartwo)) {
            return i
          }
        }
        return null
      }

    };

    abstract fun findBiChar(
      editor: VimEditor,
      charSequence: CharSequence,
      position: Int,
      charone: Char,
      chartwo: Char,
    ): Int?

    fun matches(
      editor: VimEditor,
      charSequence: CharSequence,
      charPosition: Int,
      charOne: Char,
      charTwo: Char,
    ): Boolean {
      var match = charSequence[charPosition].equals(charOne, ignoreCase = injector.options(editor).ignorecase) &&
        charSequence[charPosition + 1].equals(charTwo, ignoreCase = injector.options(editor).ignorecase)

      if (injector.options(editor).ignorecase && injector.options(editor).smartcase) {
        if (charOne.isUpperCase() || charTwo.isUpperCase()) {
          match = charSequence[charPosition].equals(charOne, ignoreCase = false) &&
            charSequence[charPosition + 1].equals(charTwo, ignoreCase = false)
        }
      }
      return match
    }
  }

  private enum class RepeatDirection(val symb: String) {
    IDENTICAL(";") {
      override fun map(direction: Direction): Direction = direction
    },
    REVERSE(",") {
      override fun map(direction: Direction): Direction = when (direction) {
        Direction.FORWARD -> Direction.BACKWARD
        Direction.BACKWARD -> Direction.FORWARD
      }
    };

    abstract fun map(direction: Direction): Direction
  }

  private class HighlightHandler {
    private var editor: Editor? = null
    private val sneakHighlighters: MutableSet<RangeHighlighter> = mutableSetOf()

    fun highlightSneakRange(editor: Editor, range: TextRange) {
      clearAllSneakHighlighters()

      this.editor = editor
      val project = editor.project
      if (project != null) {
        Disposer.register(VimProjectService.getInstance(project)) {
          this.editor = null
          sneakHighlighters.clear()
        }
      }

      if (range.isMultiple) {
        for (i in 0 until range.size()) {
          highlightSingleRange(editor, range.startOffsets[i]..range.endOffsets[i])
        }
      } else {
        highlightSingleRange(editor, range.startOffset..range.endOffset)
      }
    }

    fun clearAllSneakHighlighters() {
      sneakHighlighters.forEach { highlighter ->
        editor?.markupModel?.removeHighlighter(highlighter) ?: StrictMode.fail("Highlighters without an editor")
      }

      sneakHighlighters.clear()
    }

    private fun highlightSingleRange(editor: Editor, range: ClosedRange<Int>) {
      val highlighter = editor.markupModel.addRangeHighlighter(
        range.start,
        range.endInclusive,
        HighlighterLayer.SELECTION,
        getHighlightTextAttributes(),
        HighlighterTargetArea.EXACT_RANGE
      )

      sneakHighlighters.add(highlighter)

      setClearHighlightRangeTimer(highlighter)
    }

    private fun setClearHighlightRangeTimer(highlighter: RangeHighlighter) {
      Executors.newSingleThreadScheduledExecutor().schedule({
        ApplicationManager.getApplication().invokeLater {
          editor?.markupModel?.removeHighlighter(highlighter) ?: StrictMode.fail("Highlighters without an editor")
        }
      }, DEFAULT_HIGHLIGHT_DURATION_SNEAK, TimeUnit.MILLISECONDS)
    }

    private fun getHighlightTextAttributes() = TextAttributes(
      null,
      EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES.defaultAttributes.backgroundColor,
      editor?.colorsScheme?.getColor(EditorColors.CARET_COLOR),
      EffectType.SEARCH_MATCH,
      Font.PLAIN
    )
  }
}

/**
 * Map some <Plug>(keys) command to given handler
 *  and create mapping to <Plug>(prefix)[keys]
 */
private fun VimExtension.mapToFunctionAndProvideKeys(keys: String, handler: ExtensionHandler) {
  VimExtensionFacade.putExtensionHandlerMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(command(keys)),
    owner,
    handler,
    false
  )
  VimExtensionFacade.putKeyMapping(
    MappingMode.NXO,
    injector.parser.parseKeys(keys),
    owner,
    injector.parser.parseKeys(command(keys)),
    true
  )
}

private fun command(keys: String) = "<Plug>(sneak-$keys)"
