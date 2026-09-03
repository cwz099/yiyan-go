package com.yiyan.go.diagnostics;

import java.io.IOException;

/** Carries a machine-readable failure code without retaining remote response bodies. */
public final class DiagnosticException extends IOException {
    private final String code;
    private final Integer httpStatus;
    private final String reason;

    public DiagnosticException(String code) { this(code, null); }

    public DiagnosticException(String code, Integer httpStatus) {
        this(code, httpStatus, "");
    }

    /** reason must be an application-generated safe explanation, never provider text. */
    public DiagnosticException(String code, Integer httpStatus, String reason) {
        super(code);
        this.code = code;
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    public String code() { return code; }
    public Integer httpStatus() { return httpStatus; }
    public String reason() { return reason; }
}
