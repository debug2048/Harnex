package com.harnex.process;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Literal path conversion shared by file tools, command cwd and approval checks. Never executes shell expressions. */
public final class ShellPaths {
    private static final Pattern DRIVE_ALIAS = Pattern.compile("^/(?:(?:cygdrive|mnt)/)?([A-Za-z])(?:/(.*))?$");
    private ShellPaths() {}

    public static boolean isWindows() { return isWindows(System.getProperty("os.name", "")); }
    public static boolean isWindows(String os) { return os.toLowerCase(Locale.ROOT).startsWith("windows"); }

    public static Path resolve(String input, Path base, Function<String, Path> posixToWindows) {
        if (input == null) return null;
        if (input.indexOf('\0') >= 0) throw new IllegalArgumentException("A path cannot contain NUL characters");
        String slash = input.replace('\\', '/');
        if ("~".equals(slash) || slash.startsWith("~/")) {
            Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            return "~".equals(slash) ? home : home.resolve(slash.substring(2)).normalize();
        }
        if (isWindows()) {
            var drive = DRIVE_ALIAS.matcher(slash);
            if (input.startsWith("/") && drive.matches()) return Path.of(drive.group(1).toUpperCase(Locale.ROOT) + ":/"
                + (drive.group(2) == null ? "" : drive.group(2))).normalize();
            if (slash.matches("^[A-Za-z]:(?!/).*")) throw new IllegalArgumentException(
                "Drive-relative paths are ambiguous: " + input + ". Use a project-relative path or an absolute drive path such as C:/project/file.");
            Path nativePath = Path.of(input);
            if (nativePath.isAbsolute()) return nativePath.normalize();
            if (input.startsWith("\\") && !input.startsWith("\\\\")) {
                return base.toAbsolutePath().getRoot().resolve(slash.substring(1)).normalize();
            }
            if (slash.startsWith("/") && !slash.startsWith("//")) {
                if (posixToWindows != null) return posixToWindows.apply(slash).normalize();
                throw new IllegalArgumentException("POSIX absolute path is not a Windows host path: " + input
                    + ". Use a project-relative path, a native Windows path, or /d/project/file. Git Bash mount aliases require its installed cygpath utility.");
            }
        } else if (slash.matches("^[A-Za-z]:/.*") || input.startsWith("\\\\")) {
            throw new IllegalArgumentException("Windows drive/UNC paths do not identify files on this POSIX Runtime: "
                + input + ". Use a path on this Runtime or a project-relative path.");
        }
        return base.resolve(input).toAbsolutePath().normalize();
    }
}
