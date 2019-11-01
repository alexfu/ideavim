/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2019 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.action.change;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.maddyhome.idea.vim.KeyHandler;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.command.Argument;
import com.maddyhome.idea.vim.command.Command;
import com.maddyhome.idea.vim.command.SelectionType;
import com.maddyhome.idea.vim.common.TextRange;
import com.maddyhome.idea.vim.group.MotionGroup;
import com.maddyhome.idea.vim.handler.VimActionHandler;
import com.maddyhome.idea.vim.helper.MessageHelper;
import com.maddyhome.idea.vim.key.OperatorFunction;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * @author vlan
 */
final public class OperatorAction extends VimActionHandler.SingleExecution {

  @Contract(pure = true)
  @NotNull
  @Override
  final public Command.Type getType() {
    return Command.Type.OTHER_SELF_SYNCHRONIZED;
  }

  @Contract(pure = true)
  @NotNull
  @Override
  final public Argument.Type getArgumentType() {
    return Argument.Type.MOTION;
  }

  @Override
  public boolean execute(@NotNull Editor editor, @NotNull DataContext context, @NotNull Command cmd) {
    final OperatorFunction operatorFunction = VimPlugin.getKey().getOperatorFunction();
    if (operatorFunction != null) {
      final Argument argument = cmd.getArgument();
      if (argument != null) {
        final Command motion = argument.getMotion();
        final TextRange range = MotionGroup
          .getMotionRange(editor, editor.getCaretModel().getPrimaryCaret(), context, cmd.getCount(), cmd.getRawCount(),
                          argument);
        if (range != null) {
          VimPlugin.getMark().setChangeMarks(editor, range);
          final SelectionType selectionType = SelectionType.fromCommandFlags(motion.getFlags());
          KeyHandler.getInstance().reset(editor);
          return operatorFunction.apply(editor, context, selectionType);
        }
      }
      return false;
    }
    VimPlugin.showMessage(MessageHelper.message("E774"));
    return false;
  }
}
