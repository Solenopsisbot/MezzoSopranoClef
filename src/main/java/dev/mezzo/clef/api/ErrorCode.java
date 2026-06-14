package dev.mezzo.clef.api;

/**
 * Stable, machine-readable error codes returned on the wire as {@code {"ok":false,"code":"...","error":"..."}}.
 *
 * <p>The {@code code} is the contract a client should branch on; the {@code error} string is a
 * human-readable detail that may change wording between versions. These names are part of the
 * control-plane protocol — add new ones, but don't rename or repurpose existing ones without a
 * {@link ApiSchema#PROTOCOL_VERSION} bump.</p>
 */
public enum ErrorCode {
    /** The request body was not parseable JSON. */
    INVALID_JSON("the request was not valid JSON"),
    /** The request had no {@code cmd} field. */
    MISSING_CMD("the request had no 'cmd' field"),
    /** No command is registered under that name (see the {@code help} / {@code schema} commands). */
    UNKNOWN_COMMAND("no command with that name (see 'help' / 'schema')"),
    /** Auth is required and this connection hasn't sent a valid {@code hello} yet. */
    UNAUTHORIZED("auth required — send {cmd:'hello',args:{token}} first"),
    /** The token in a {@code hello} frame did not match the configured one. */
    BAD_TOKEN("the auth token did not match"),
    /** A required argument was missing, or an argument had the wrong type. */
    BAD_ARGS("an argument was missing or had the wrong type"),
    /** The bot is not in a world yet (still authenticating / on the title screen). */
    NOT_IN_WORLD("the bot is not in a world (not spawned / on the title screen)"),
    /** The bot is not connected to a server. */
    NOT_CONNECTED("the bot is not connected to a server"),
    /** A named target (entity / item / slot / widget) could not be found. */
    NOT_FOUND("the requested target was not found"),
    /** Catch-all: the command threw an error that has no more specific code. */
    COMMAND_FAILED("the command failed with an unexpected error");

    /** Human-readable explanation, surfaced in the {@code schema} command's {@code errors} list. */
    public final String meaning;

    ErrorCode(String meaning) {
        this.meaning = meaning;
    }
}
