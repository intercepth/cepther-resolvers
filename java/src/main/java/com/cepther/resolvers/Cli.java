package com.cepther.resolvers;

import com.cepther.resolvers.captcha.CaptchaSolver;
import com.cepther.resolvers.captcha.ConsoleCaptchaSolver;
import com.cepther.resolvers.captcha.TwoCaptchaSolver;
import com.cepther.resolvers.http.JdkHttp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Smoke-test tool: resolve URLs on a machine that has internet.
 * <pre>
 *   java -cp java/out com.cepther.resolvers.Cli [--manifest manifest.json] [--drive-api-key KEY]
 *        [--2captcha-key KEY] [--ask-captcha] [--depth N] URL...
 * </pre>
 */
public final class Cli {
    public static void main(String[] args) throws Exception {
        List<String> urls = new ArrayList<>();
        String manifest = null;
        String driveKey = null;
        String twoCaptcha = null;
        boolean ask = false;
        int depth = 3;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest": manifest = args[++i]; break;
                case "--drive-api-key": driveKey = args[++i]; break;
                case "--2captcha-key": twoCaptcha = args[++i]; break;
                case "--ask-captcha": ask = true; break;
                case "--depth": depth = Integer.parseInt(args[++i]); break;
                default: urls.add(args[i]);
            }
        }
        if (urls.isEmpty()) {
            System.err.println("usage: Cli [--manifest f] [--drive-api-key K] [--2captcha-key K] [--ask-captcha] [--depth N] URL...");
            System.exit(2);
        }

        JdkHttp http = new JdkHttp();
        ResolverRegistry registry = ResolverRegistry.withDefaults();
        if (manifest != null) {
            registry.addManifest(new String(Files.readAllBytes(Paths.get(manifest)), StandardCharsets.UTF_8));
        }
        List<CaptchaSolver> solvers = new ArrayList<>();
        if (twoCaptcha != null) solvers.add(new TwoCaptchaSolver(http, twoCaptcha));
        if (ask) solvers.add(new ConsoleCaptchaSolver());
        ResolveContext ctx = new ResolveContext(http)
                .captcha(CaptchaSolver.chain(solvers.toArray(new CaptchaSolver[0])))
                .log(s -> System.err.println("  " + s));
        if (driveKey != null) ctx.option("gdrive.apiKey", driveKey);

        int failures = 0;
        for (String u : urls) {
            System.out.println("== " + u);
            try {
                ResolveResult r = registry.resolveAll(u, ctx, depth);
                if (r.packageName() != null) System.out.println("package: " + r.packageName());
                for (ResolvedFile f : r.files()) System.out.println(f);
                for (String w : r.warnings()) System.out.println("warning: " + w);
            } catch (ResolveException e) {
                failures++;
                System.out.println("FAILED " + e);
            }
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
