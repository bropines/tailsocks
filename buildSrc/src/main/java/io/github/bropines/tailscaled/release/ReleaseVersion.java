package io.github.bropines.tailscaled.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The version of a build as version.properties writes it, and the one place the rule between
 * its two lines is written down.
 *
 * A release is MAJOR.MINOR.PATCH with the code major*1000000 + minor*10000 + patch*100. Beta N
 * of that version is MAJOR.MINOR.PATCH-beta.N, N from 1 to 9, and its code is the release's
 * minus 100 plus 10*N — the end of the hundred below, which nothing else uses. Each APK adds
 * 0 to 4 for its ABI (app/build.gradle.kts), so beta 1 of 5.1.0 is 5009910..5009914, beta 9 is
 * 5009990..5009994 and 5.1.0 itself 5010000..5010004: every beta is an update over every
 * earlier release and beta, and the release an update over its own betas. The reasoning and
 * the limits are in docs/RELEASING.md.
 *
 * Three readers, one text. Gradle has this class on its build scripts' classpath (buildSrc)
 * and refuses to configure a build whose code does not follow its name. CI runs the file as a
 * program before it builds a tag (.github/workflows/android.yml). The app's unit tests compile
 * it and hold the rule to what it promises (ReleaseVersionTest). Plain Java with nothing but
 * the JDK for that reason: `java ReleaseVersion.java v5.1.0-beta.1` needs no build.
 */
public final class ReleaseVersion {
    /** A version has at most this many betas: the tenth would have the release's own code. */
    public static final int MAX_BETA = 9;
    /** The most an APK may add to the code for its ABI; the next beta starts ten further. */
    public static final int MAX_ABI_OFFSET = 4;

    private static final int MAX_MAJOR = 2099;
    private static final int MAX_MINOR = 99;
    private static final int MAX_PATCH = 99;

    private static final String NUMBER = "(0|[1-9][0-9]{0,3})";
    private static final Pattern NAME =
            Pattern.compile(NUMBER + "\\." + NUMBER + "\\." + NUMBER + "(?:-beta\\.([1-9][0-9]{0,3}))?");

    public final int major;
    public final int minor;
    public final int patch;
    /** 0 for a release, N for its beta N. */
    public final int beta;

    private ReleaseVersion(int major, int minor, int patch, int beta) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.beta = beta;
    }

    /**
     * Reads a VERSION_NAME. Anything that is not exactly a release or a beta name is refused,
     * and so is a number the code has no room for.
     */
    public static ReleaseVersion parse(String name) {
        Matcher m = NAME.matcher(name == null ? "" : name);
        if (!m.matches()) {
            throw new IllegalArgumentException("VERSION_NAME=" + name + " is neither a release (5.1.0) nor a beta (5.1.0-beta.1)");
        }
        ReleaseVersion v = new ReleaseVersion(
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                m.group(4) == null ? 0 : Integer.parseInt(m.group(4)));
        if (v.major > MAX_MAJOR || v.minor > MAX_MINOR || v.patch > MAX_PATCH) {
            throw new IllegalArgumentException("VERSION_NAME=" + name + " does not fit the code: major up to "
                    + MAX_MAJOR + ", minor and patch up to " + MAX_MINOR);
        }
        if (v.beta > MAX_BETA) {
            throw new IllegalArgumentException("VERSION_NAME=" + name + ": a version has betas 1 to " + MAX_BETA
                    + " — the next one is beta 1 of the next patch");
        }
        if (v.code() <= 0) {
            throw new IllegalArgumentException("VERSION_NAME=" + name + " has no code above zero");
        }
        return v;
    }

    public boolean isBeta() {
        return beta > 0;
    }

    /** The VERSION_CODE of this name: the universal APK's, before an ABI adds its 1 to 4. */
    public int code() {
        int release = major * 1_000_000 + minor * 10_000 + patch * 100;
        return isBeta() ? release - 100 + 10 * beta : release;
    }

    /** The VERSION_NAME, and the tag without its v. */
    public String name() {
        return major + "." + minor + "." + patch + (isBeta() ? "-beta." + beta : "");
    }

    /** What version.properties must hold; an IllegalArgumentException says what it does not. */
    public static ReleaseVersion check(String name, int code) {
        ReleaseVersion v = parse(name);
        if (code != v.code()) {
            throw new IllegalArgumentException("VERSION_CODE=" + code + " does not follow VERSION_NAME=" + name + ": "
                    + (v.isBeta()
                            ? "beta " + v.beta + " of " + v.major + "." + v.minor + "." + v.patch
                                    + " is major*1000000 + minor*10000 + patch*100 - 100 + 10*" + v.beta
                            : "a release is major*1000000 + minor*10000 + patch*100")
                    + " = " + v.code());
        }
        return v;
    }

    /** The same for a tag, which must also be the name with a v before it. */
    public static ReleaseVersion check(String tag, String name, int code) {
        if (!("v" + name).equals(tag)) {
            throw new IllegalArgumentException("Tag " + tag + " does not match VERSION_NAME=" + name + " in version.properties");
        }
        return check(name, code);
    }

    /**
     * `java ReleaseVersion.java <tag> [version.properties]`: checks the tag against the file
     * and prints what the tag is, one `key=value` a line — the form a workflow step appends to
     * its outputs. A tag that does not hold prints an error annotation and exits with 1.
     */
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: java ReleaseVersion.java <tag> [version.properties]");
            System.exit(2);
        }
        try {
            Properties file = new Properties();
            try (InputStream in = Files.newInputStream(Paths.get(args.length > 1 ? args[1] : "version.properties"))) {
                file.load(in);
            }
            String name = file.getProperty("VERSION_NAME", "").trim();
            String code = file.getProperty("VERSION_CODE", "").trim();
            if (!code.matches("[0-9]{1,9}")) {
                throw new IllegalArgumentException("VERSION_CODE=" + code + " is not a number");
            }
            ReleaseVersion v = check(args[0], name, Integer.parseInt(code));
            System.out.println("kind=" + (v.isBeta() ? "beta" : "release"));
            System.out.println("version=" + v.name());
            System.out.println("code=" + v.code());
        } catch (IllegalArgumentException e) {
            System.err.println("::error::" + e.getMessage());
            System.exit(1);
        } catch (IOException e) {
            System.err.println("::error::Cannot read version.properties: " + e);
            System.exit(1);
        }
    }
}
