package com.vulntriage.command;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Command pattern — bounded stack of executed {@link Command}s that supports undo.
 *
 * <p>When the stack reaches {@code maxSize} entries, the oldest command is silently
 * discarded to prevent unbounded memory growth. A typical interactive session
 * will never approach the default limit of 50 verdicts.
 */
public class CommandHistory {

    private static final int DEFAULT_MAX = 50;

    private final int maxSize;
    private final Deque<Command> stack = new ArrayDeque<>();

    public CommandHistory() {
        this(DEFAULT_MAX);
    }

    public CommandHistory(int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        this.maxSize = maxSize;
    }

    /**
     * Execute {@code cmd} and push it onto the history stack.
     * If the stack is full, the oldest entry is dropped before pushing.
     */
    public void executeAndPush(Command cmd) {
        cmd.execute();
        if (stack.size() >= maxSize) {
            // Remove the bottom (oldest) element to stay within bound
            // ArrayDeque: addFirst = push to top, removeLast = remove oldest
            stack.removeLast();
        }
        stack.addFirst(cmd);
    }

    /**
     * Undo the most recently executed command, if any.
     *
     * @return {@code true} if a command was undone, {@code false} if history was empty
     */
    public boolean undo() {
        if (stack.isEmpty()) return false;
        stack.removeFirst().undo();
        return true;
    }

    /** Number of commands currently in the history. */
    public int size() { return stack.size(); }

    /** {@code true} when there is at least one command to undo. */
    public boolean canUndo() { return !stack.isEmpty(); }

    /** Remove all history without undoing any commands. */
    public void clear() { stack.clear(); }
}
