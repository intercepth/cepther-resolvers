package com.cepther.resolvers.captcha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;

/** CLI helper: saves an image captcha to a temp file and reads the typed answer from stdin. Image type only. */
public final class ConsoleCaptchaSolver implements CaptchaSolver {
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    @Override
    public String solve(CaptchaChallenge c) throws CaptchaException {
        if (c.type() != CaptchaChallenge.Type.IMAGE || c.imageBytes() == null) return null;
        try {
            Path f = Files.createTempFile("captcha-", ".img");
            Files.write(f, c.imageBytes());
            System.err.println("Captcha image saved to " + f + " -- type the text (empty to skip):");
            String line = in.readLine();
            return line == null || line.trim().isEmpty() ? null : line.trim();
        } catch (IOException e) {
            throw new CaptchaException("console captcha failed: " + e.getMessage(), e);
        }
    }
}
