package zju.cst.aces.api.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class EnvironmentProxySelector {
    private EnvironmentProxySelector() {
    }

    static Optional<ProxySettings> select(String targetUrl, Map<String, String> env) {
        if (targetUrl == null || targetUrl.trim().isEmpty() || env == null || env.isEmpty()) {
            return Optional.empty();
        }

        URI target;
        try {
            target = URI.create(targetUrl);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String targetHost = target.getHost();
        if (targetHost == null || isNoProxyHost(targetHost, readEnv(env, "NO_PROXY", "no_proxy"))) {
            return Optional.empty();
        }

        String scheme = target.getScheme() == null ? "http" : target.getScheme().toLowerCase(Locale.ROOT);
        Optional<ProxySettings> schemeProxy = "https".equals(scheme)
                ? firstProxy(env, "HTTPS_PROXY", "https_proxy")
                : firstProxy(env, "HTTP_PROXY", "http_proxy");
        return schemeProxy.isPresent() ? schemeProxy : firstProxy(env, "ALL_PROXY", "all_proxy");
    }

    static Optional<ProxySettings> parseProxyValue(String value, ProxyKind defaultKind) {
        if (value == null || value.trim().isEmpty()) {
            return Optional.empty();
        }

        String raw = value.trim();
        String parseable = raw.contains("://") ? raw : "http://" + raw;
        URI uri;
        try {
            uri = URI.create(parseable);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port < 0) {
            return Optional.empty();
        }

        ProxyKind kind = kindForScheme(uri.getScheme(), defaultKind);
        return Optional.of(new ProxySettings(host, port, kind));
    }

    private static Optional<ProxySettings> firstProxy(Map<String, String> env, String primaryKey, String secondaryKey) {
        Optional<ProxySettings> primary = parseProxyValue(env.get(primaryKey), ProxyKind.HTTP);
        return primary.isPresent() ? primary : parseProxyValue(env.get(secondaryKey), ProxyKind.HTTP);
    }

    private static ProxyKind kindForScheme(String scheme, ProxyKind defaultKind) {
        if (scheme == null) {
            return defaultKind;
        }
        String normalized = scheme.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("socks")) {
            return ProxyKind.SOCKS;
        }
        return ProxyKind.HTTP;
    }

    private static boolean isNoProxyHost(String host, String noProxy) {
        if (noProxy == null || noProxy.trim().isEmpty()) {
            return false;
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String rawToken : noProxy.split(",")) {
            String token = normalizeNoProxyToken(rawToken);
            if (token.isEmpty()) {
                continue;
            }
            if ("*".equals(token) || normalizedHost.equals(token)) {
                return true;
            }
            if (token.startsWith(".") && normalizedHost.endsWith(token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeNoProxyToken(String rawToken) {
        String token = rawToken == null ? "" : rawToken.trim().toLowerCase(Locale.ROOT);
        if (token.startsWith("*.")) {
            token = token.substring(1);
        }
        int portSeparator = token.lastIndexOf(':');
        if (portSeparator > -1 && token.indexOf(':') == portSeparator) {
            token = token.substring(0, portSeparator);
        }
        return token;
    }

    private static String readEnv(Map<String, String> env, String primaryKey, String secondaryKey) {
        String value = env.get(primaryKey);
        return value != null ? value : env.get(secondaryKey);
    }

    enum ProxyKind {
        HTTP,
        SOCKS
    }

    static final class ProxySettings {
        private final String host;
        private final int port;
        private final ProxyKind kind;

        ProxySettings(String host, int port, ProxyKind kind) {
            this.host = host;
            this.port = port;
            this.kind = kind;
        }

        String getHost() {
            return host;
        }

        int getPort() {
            return port;
        }

        ProxyKind getKind() {
            return kind;
        }
    }
}
