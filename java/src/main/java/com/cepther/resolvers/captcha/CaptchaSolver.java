package com.cepther.resolvers.captcha;

import java.util.ArrayList;
import java.util.List;

/**
 * Something that can answer a captcha. Same idea as JDownloader2's solver chain: ask the user in the UI,
 * or hand it to a solving service with the user's own account. Implementations:
 * <ul>
 *   <li>the app's UI (implement this interface, show the image / embed the widget in a WebView);</li>
 *   <li>{@link TwoCaptchaSolver} (2captcha-compatible HTTP API, user supplies the key);</li>
 *   <li>{@link ConsoleCaptchaSolver} (image captchas only, for the CLI).</li>
 * </ul>
 */
@FunctionalInterface
public interface CaptchaSolver {

    /**
     * @return the answer (typed text, or the token for widget captchas); {@code null} if this solver does
     *         not handle that type or the user skipped it
     * @throws CaptchaException the attempt failed (bad key, no balance, timeout, ...)
     */
    String solve(CaptchaChallenge challenge) throws CaptchaException;

    /** Answers nothing; resolvers then raise CAPTCHA_REQUIRED so the app can handle it itself. */
    CaptchaSolver NONE = c -> null;

    /** Try each solver in order; first non-empty answer wins, failures fall through to the next. */
    static CaptchaSolver chain(CaptchaSolver... solvers) {
        final List<CaptchaSolver> list = new ArrayList<>();
        for (CaptchaSolver s : solvers) if (s != null) list.add(s);
        return c -> {
            CaptchaException last = null;
            for (CaptchaSolver s : list) {
                try {
                    String a = s.solve(c);
                    if (a != null && !a.isEmpty()) return a;
                } catch (CaptchaException e) {
                    last = e;
                }
            }
            if (last != null) throw last;
            return null;
        };
    }
}
