package dev.mezzo.clef.api;

/**
 * An exception that carries a machine-readable {@link ErrorCode}. Command handlers throw this
 * (directly or via the {@code requireXxx} helpers on {@link CommandContext}) so the control plane
 * can return a stable {@code code} field instead of forcing clients to string-match the message.
 *
 * <p>Handlers that touch the game often throw from inside {@link CommandContext#onMain}, which
 * re-wraps the cause in a {@link RuntimeException}; use {@link #find(Throwable)} to recover the
 * original code from anywhere in the cause chain.</p>
 */
public final class ApiException extends RuntimeException {

    public final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    // ---- common factories (keep call sites terse) -----------------------------------

    public static ApiException badArgs(String message) {
        return new ApiException(ErrorCode.BAD_ARGS, message);
    }

    public static ApiException notInWorld() {
        return new ApiException(ErrorCode.NOT_IN_WORLD, "not in world");
    }

    public static ApiException notConnected() {
        return new ApiException(ErrorCode.NOT_CONNECTED, "not connected");
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    /**
     * Walks the cause chain looking for an {@link ApiException}, so a code set deep inside an
     * {@code onMain} task survives the {@link RuntimeException} re-wrap. Returns {@code null} if
     * there is no coded exception anywhere in the chain.
     */
    public static ApiException find(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ApiException ae) return ae;
            if (c.getCause() == c) break; // guard against self-referential chains
        }
        return null;
    }
}
