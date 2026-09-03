package com.yiyan.go.engine;

import java.io.IOException;

/** Messages must be application-authored diagnostics, never raw subprocess output. */
public final class EngineException extends IOException {
    public EngineException(String message) { super(message); }
    public EngineException(String message, Throwable cause) { super(message, cause); }
}
