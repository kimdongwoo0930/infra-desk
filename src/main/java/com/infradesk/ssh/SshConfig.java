package com.infradesk.ssh;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads OpenSSH client config ({@code ~/.ssh/config}) to suggest SSH settings for a server.
 * Supports Host blocks with wildcards, HostName, User, Port and IdentityFile with "first value
 * wins" semantics like {@code ssh}. Match, Include and other directives are ignored.
 */
public final class SshConfig {

    /** What the config says about connecting to a host. */
    public record Suggestion(String alias, String user, Integer port, Path identityFile) {
    }

    private record Block(List<String> patterns, List<String[]> options) {
    }

    private final List<Block> blocks;
    private final Path home;

    private SshConfig(List<Block> blocks, Path home) {
        this.blocks = blocks;
        this.home = home;
    }

    /** Parses the file; a missing or unreadable file gives an empty config. */
    public static SshConfig load(Path file, Path home) {
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8), home);
        } catch (IOException e) {
            return new SshConfig(List.of(), home);
        }
    }

    public static SshConfig userDefault() {
        Path home = Path.of(System.getProperty("user.home"));
        return load(home.resolve(".ssh").resolve("config"), home);
    }

    static SshConfig parse(List<String> lines, Path home) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block(List.of("*"), new ArrayList<>());
        blocks.add(current);
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] kv = line.split("\\s*=\\s*|\\s+", 2);
            if (kv.length < 2) {
                continue;
            }
            String key = kv[0].toLowerCase(Locale.ROOT);
            String value = unquote(kv[1].strip());
            if (key.equals("host")) {
                current = new Block(List.of(value.split("\\s+")), new ArrayList<>());
                blocks.add(current);
            } else if (key.equals("match")) {
                // Unsupported: skip options until the next Host.
                current = new Block(List.of(), new ArrayList<>());
                blocks.add(current);
            } else {
                current.options().add(new String[] {key, value});
            }
        }
        return new SshConfig(blocks, home);
    }

    /**
     * Finds the Host whose HostName (or alias) is {@code address} and resolves its settings.
     * Falls back to an alias equal to {@code serverName}.
     */
    public Optional<Suggestion> suggest(String address, String serverName) {
        String alias = null;
        for (Block b : blocks) {
            for (String[] o : b.options()) {
                if (o[0].equals("hostname") && o[1].equalsIgnoreCase(address) && !b.patterns().isEmpty()
                        && isLiteral(b.patterns().getFirst())) {
                    alias = b.patterns().getFirst();
                    break;
                }
            }
            if (alias != null) {
                break;
            }
        }
        if (alias == null) {
            for (String candidate : new String[] {address, serverName}) {
                if (candidate != null && blocks.stream().anyMatch(b -> b.patterns().contains(candidate))) {
                    alias = candidate;
                    break;
                }
            }
        }
        return alias == null ? Optional.empty() : Optional.of(resolve(alias, address));
    }

    /** OpenSSH semantics: walk blocks in order, first value for each option wins. */
    private Suggestion resolve(String alias, String address) {
        String user = null;
        Integer port = null;
        List<String> identities = new ArrayList<>();
        for (Block b : blocks) {
            if (!matches(b.patterns(), alias)) {
                continue;
            }
            for (String[] o : b.options()) {
                switch (o[0]) {
                    case "user" -> user = user == null ? o[1] : user;
                    case "port" -> {
                        if (port == null) {
                            try {
                                port = Integer.parseInt(o[1]);
                            } catch (NumberFormatException ignored) {
                                // Leave unset.
                            }
                        }
                    }
                    case "identityfile" -> identities.add(o[1]);
                    default -> { }
                }
            }
        }
        String remoteUser = user == null ? System.getProperty("user.name") : user;
        Path identity = identities.stream()
                .map(p -> expand(p, alias, address, remoteUser))
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        return new Suggestion(alias, user, port, identity);
    }

    private Path expand(String path, String alias, String address, String remoteUser) {
        String p = path.replace("%%", "\u0000")
                .replace("%d", home.toString())
                .replace("%u", System.getProperty("user.name"))
                .replace("%h", address)
                .replace("%n", alias)
                .replace("%r", remoteUser)
                .replace("\u0000", "%");
        if (p.equals("~")) {
            return home;
        }
        if (p.startsWith("~/")) {
            return home.resolve(p.substring(2));
        }
        Path resolved = Path.of(p);
        return resolved.isAbsolute() ? resolved : home.resolve(p);
    }

    private static boolean matches(List<String> patterns, String alias) {
        boolean matched = false;
        for (String pattern : patterns) {
            boolean negated = pattern.startsWith("!");
            String glob = negated ? pattern.substring(1) : pattern;
            if (glob(glob).matcher(alias).matches()) {
                if (negated) {
                    return false;
                }
                matched = true;
            }
        }
        return matched;
    }

    private static Pattern glob(String glob) {
        StringBuilder re = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static boolean isLiteral(String pattern) {
        return !pattern.contains("*") && !pattern.contains("?") && !pattern.startsWith("!");
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
