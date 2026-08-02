package com.vulntriage.command;

/**
 * Command pattern — encapsulates a reversible user action.
 *
 * Concrete implementations carry all state needed for both {@link #execute()}
 * and {@link #undo()}, so the invoker ({@link CommandHistory}) never needs to
 * know the details of the operation being undone.
 */
public interface Command {

    /** Perform the action. */
    void execute();

    /** Reverse the action, restoring the previous state. */
    void undo();
}
