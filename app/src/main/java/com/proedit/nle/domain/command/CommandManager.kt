package com.proedit.nle.domain.command

import com.proedit.nle.domain.model.TimelineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CommandManager(
    initialState: TimelineState = TimelineState(),
    private val maxHistorySize: Int = 100
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<TimelineState> = _state.asStateFlow()

    private val _undoStack = MutableStateFlow<List<TimelineCommand>>(emptyList())
    val undoStack: StateFlow<List<TimelineCommand>> = _undoStack.asStateFlow()

    private val _redoStack = MutableStateFlow<List<TimelineCommand>>(emptyList())
    val redoStack: StateFlow<List<TimelineCommand>> = _redoStack.asStateFlow()

    val canUndo: Boolean
        get() = _undoStack.value.isNotEmpty()

    val canRedo: Boolean
        get() = _redoStack.value.isNotEmpty()

    fun execute(command: TimelineCommand): TimelineState {
        val currentState = _state.value
        val newState = command.execute(currentState)
        if (newState != currentState) {
            val updatedUndo = (_undoStack.value + command).takeLast(maxHistorySize)
            _undoStack.value = updatedUndo
            _redoStack.value = emptyList()
            _state.value = newState
        }
        return _state.value
    }

    fun undo(): TimelineState {
        val currentUndo = _undoStack.value
        if (currentUndo.isEmpty()) return _state.value

        val lastCommand = currentUndo.last()
        val restoredState = lastCommand.undo(_state.value)
        _undoStack.value = currentUndo.dropLast(1)
        _redoStack.value = (_redoStack.value + lastCommand).takeLast(maxHistorySize)
        _state.value = restoredState
        return restoredState
    }

    fun redo(): TimelineState {
        val currentRedo = _redoStack.value
        if (currentRedo.isEmpty()) return _state.value

        val nextCommand = currentRedo.last()
        val reappliedState = nextCommand.execute(_state.value)
        _redoStack.value = currentRedo.dropLast(1)
        _undoStack.value = (_undoStack.value + nextCommand).takeLast(maxHistorySize)
        _state.value = reappliedState
        return reappliedState
    }

    /**
     * Updates transient state (e.g., playhead position, zoom, active tool, selection) without
     * polluting the command undo/redo stack.
     */
    fun updateTransient(transform: (TimelineState) -> TimelineState): TimelineState {
        val updated = transform(_state.value)
        _state.value = updated
        return updated
    }

    fun reset(newState: TimelineState) {
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        _state.value = newState
    }
}
