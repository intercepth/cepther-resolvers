package com.cepther.resolvers;

/** Typed failure so the app can show a useful message (or retry / ask for a captcha). */
public class ResolveException extends Exception {
    private static final long serialVersionUID = 1L;


    public enum Reason {
        /** File/folder deleted or id wrong. */
        NOT_FOUND,
        /** Exists but not shared with "anyone with the link". */
        PRIVATE,
        /** Needs a signed-in account or a password. */
        LOGIN_REQUIRED,
        /** Host-side download quota hit; retry later. */
        QUOTA_EXCEEDED,
        /** A captcha is needed and no solver produced an answer. */
        CAPTCHA_REQUIRED,
        /** URL shape or feature not supported by any resolver. */
        UNSUPPORTED,
        /** Page/API did not look the way the resolver expects (site changed?). */
        PARSE_FAILED,
        /** I/O error talking to the host. */
        NETWORK
    }

    private final Reason reason;

    public ResolveException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ResolveException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    @Override
    public String toString() {
        return "ResolveException[" + reason + "]: " + getMessage();
    }
}
