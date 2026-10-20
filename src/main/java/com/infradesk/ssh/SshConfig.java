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
 * OpenSSH 클라이언트 설정({@code ~/.ssh/config})을 읽어 서버의 SSH 설정을 추천한다.
 * 와일드카드가 있는 Host 블록, HostName, User, Port, IdentityFile을 지원하며
 * {@code ssh}처럼 "첫 번째 값이 우선"으로 처리한다. Match, Include 등 다른 지시어는 무시한다.
 */
public final class SshConfig {

    /** 호스트에 연결하는 방법에 대해 설정이 말해주는 내용. */
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

    /** 파일을 파싱한다. 파일이 없거나 읽을 수 없으면 빈 설정을 돌려준다. */
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
                // 지원하지 않음: 다음 Host까지 옵션을 건너뛴다.
                current = new Block(List.of(), new ArrayList<>());
                blocks.add(current);
            } else {
                current.options().add(new String[] {key, value});
            }
        }
        return new SshConfig(blocks, home);
    }

    /**
     * HostName(또는 별칭)이 {@code address}인 Host를 찾아 설정을 해석한다.
     * 못 찾으면 {@code serverName}과 같은 별칭으로 대체한다.
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

    /** OpenSSH 규칙: 블록을 순서대로 훑고, 각 옵션은 첫 번째 값이 우선한다. */
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
                                // 설정하지 않은 채로 둔다.
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
