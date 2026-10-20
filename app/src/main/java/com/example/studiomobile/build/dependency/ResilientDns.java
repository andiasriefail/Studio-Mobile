package com.example.studiomobile.build.dependency;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Looks up a host name with Google DNS first, Cloudflare DNS second and, as the last resort, the DNS the phone itself uses. When all of
 * them fail the lookup stops with {@link DnsFailedException}: nothing else is tried and nothing is retried.
 *
 * <p>Google and Cloudflare are asked over HTTPS (DNS-over-HTTPS JSON API, addressed by IP, so asking needs no DNS itself): Google at
 * 8.8.8.8 and Cloudflare at 1.1.1.1. That works on networks that block port 53 and cannot be redirected by a broken "Private DNS"
 * setting. The phone's own DNS comes last because it is the least predictable (a network may block the first two, and may be the only way
 * to reach a repository on a private network), and it is only asked when the two public services could not answer.
 * Answers are kept for a few minutes so one build does not look the same host up again for every file.
 */
public final class ResilientDns {
    /** One DNS service. Returns IPv4 addresses, or throws when the service cannot answer for this host. */
    public interface Resolver {
        String name();
        List<String> lookup(String host) throws IOException;

        /** The addresses for a host. By default the IPv4 texts of {@link #lookup}; a resolver that has real addresses overrides this. */
        default List<InetAddress> lookupAddresses(String host) throws IOException {
            List<InetAddress> found = new ArrayList<>();
            for (String text : lookup(host)) {
                InetAddress parsed = parseIpv4(text);
                if (parsed != null && !found.contains(parsed)) found.add(parsed);
            }
            return found;
        }
    }

    /** Neither resolver could find the host. The build must stop; this is not a "file not found". */
    public static final class DnsFailedException extends IOException {
        public DnsFailedException(String message, Throwable cause) { super(message, cause); }
    }

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final long CACHE_MILLIS = 5 * 60 * 1000L;
    private static volatile ResilientDns standard;

    private final List<Resolver> resolvers;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    private static final class Cached {
        final List<InetAddress> addresses; final long expiresAt;
        Cached(List<InetAddress> addresses, long expiresAt) { this.addresses = addresses; this.expiresAt = expiresAt; }
    }

    public ResilientDns(List<Resolver> resolvers) { this(resolvers, System::currentTimeMillis); }

    ResilientDns(List<Resolver> resolvers, java.util.function.LongSupplier clock) {
        if (resolvers == null || resolvers.isEmpty()) throw new IllegalArgumentException("At least one DNS resolver is required.");
        this.resolvers = Collections.unmodifiableList(new ArrayList<>(resolvers));
        this.clock = clock;
    }

    /** Google DNS, then Cloudflare DNS, then the phone's own DNS. */
    public static ResilientDns standard() {
        ResilientDns local = standard;
        if (local == null) {
            local = new ResilientDns(Arrays.<Resolver>asList(
                new DohResolver("Google DNS (8.8.8.8)", "https://8.8.8.8/resolve?type=A&name="),
                new DohResolver("Cloudflare DNS (1.1.1.1)", "https://1.1.1.1/dns-query?type=A&name="),
                new SystemResolver()));
            standard = local;
        }
        return local;
    }

    /** @throws DnsFailedException when every resolver failed */
    public List<InetAddress> resolve(String host) throws DnsFailedException {
        if (host == null || host.isEmpty()) throw new DnsFailedException("No host name to look up.", null);
        String key = host.toLowerCase(Locale.ROOT);
        long now = clock.getAsLong();
        Cached hit = cache.get(key);
        if (hit != null && hit.expiresAt > now) return hit.addresses;

        List<InetAddress> literal = literalAddress(key);
        if (literal != null) return literal;

        StringBuilder failures = new StringBuilder();
        Exception last = null;
        for (Resolver resolver : resolvers) {
            try {
                List<InetAddress> found = resolver.lookupAddresses(key);
                if (found.isEmpty()) throw new IOException("no address returned for " + host);
                List<InetAddress> result = Collections.unmodifiableList(found);
                cache.put(key, new Cached(result, now + CACHE_MILLIS));
                return result;
            } catch (IOException | RuntimeException failure) {
                last = failure;
                if (failures.length() > 0) failures.append("; ");
                failures.append(resolver.name()).append(": ").append(failure.getMessage());
            }
        }
        throw new DnsFailedException("Could not find " + host + " with " + names() + " (" + failures + "). Stopping: check the internet connection or try another network.", last);
    }

    private String names() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < resolvers.size(); i++) out.append(i == 0 ? "" : " or ").append(resolvers.get(i).name());
        return out.toString();
    }

    /** A host that is already an IPv4 address needs no lookup. */
    private static List<InetAddress> literalAddress(String host) {
        InetAddress parsed = parseIpv4(host);
        return parsed == null ? null : Collections.singletonList(parsed);
    }

    static InetAddress parseIpv4(String text) {
        if (text == null) return null;
        java.util.regex.Matcher matcher = IPV4.matcher(text.trim());
        if (!matcher.matches()) return null;
        byte[] octets = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(matcher.group(i + 1));
            if (value > 255) return null;
            octets[i] = (byte) value;
        }
        try { return InetAddress.getByAddress(octets); } catch (java.net.UnknownHostException impossible) { return null; }
    }

    /**
     * The DNS the phone is configured with (mobile network, Wi-Fi or Private DNS), asked through the system. Used only after Google and
     * Cloudflare failed. Loopback, wildcard and multicast answers are dropped: a repository is never on the phone itself, and such an answer
     * is what a blocking DNS returns for a name it refuses.
     */
    static final class SystemResolver implements Resolver {
        @Override public String name() { return "the phone's own DNS"; }

        @Override public List<String> lookup(String host) throws IOException {
            List<String> out = new ArrayList<>();
            for (InetAddress address : lookupAddresses(host)) out.add(address.getHostAddress());
            return out;
        }

        @Override public List<InetAddress> lookupAddresses(String host) throws IOException {
            InetAddress[] all;
            try { all = InetAddress.getAllByName(host); }
            catch (java.net.UnknownHostException unknown) { throw new IOException("host not found", unknown); }
            List<InetAddress> usable = new ArrayList<>();
            for (InetAddress address : all)
                if (!address.isLoopbackAddress() && !address.isAnyLocalAddress() && !address.isMulticastAddress() && !usable.contains(address)) usable.add(address);
            return usable;
        }
    }

    /** DNS-over-HTTPS with the JSON answer format both Google and Cloudflare serve. */
    static final class DohResolver implements Resolver {
        private final String name, urlPrefix;
        DohResolver(String name, String urlPrefix) { this.name = name; this.urlPrefix = urlPrefix; }
        @Override public String name() { return name; }

        @SuppressWarnings("unchecked")
        @Override public List<String> lookup(String host) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlPrefix + URLEncoder.encode(host, "UTF-8")).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/dns-json");
            try {
                int status = connection.getResponseCode();
                if (status != 200) throw new IOException("HTTP " + status);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048]; int count;
                try (InputStream in = connection.getInputStream()) {
                    while ((count = in.read(buffer)) != -1) { body.write(buffer, 0, count); if (body.size() > 64 * 1024) throw new IOException("answer too large"); }
                }
                Object root = MiniJson.parse(new String(body.toByteArray(), StandardCharsets.UTF_8));
                return addressesOf((Map<String, Object>) root);
            } catch (IllegalArgumentException | ClassCastException badAnswer) {
                throw new IOException("unreadable answer", badAnswer);
            } finally { connection.disconnect(); }
        }

        /** Status 0 = found; anything else (3 = host does not exist) is a failure of this resolver. Only A records (type 1) count. */
        @SuppressWarnings("unchecked")
        static List<String> addressesOf(Map<String, Object> root) throws IOException {
            Object status = root.get("Status");
            if (status != null && !"0".equals(String.valueOf(status))) throw new IOException("host not found (DNS status " + status + ")");
            List<String> out = new ArrayList<>();
            Object answers = root.get("Answer");
            if (answers instanceof List) {
                for (Object o : (List<Object>) answers) {
                    Map<String, Object> answer = (Map<String, Object>) o;
                    if ("1".equals(String.valueOf(answer.get("type"))) && answer.get("data") != null) out.add(String.valueOf(answer.get("data")));
                }
            }
            if (out.isEmpty()) throw new IOException("no IPv4 address in the answer");
            return out;
        }
    }
}
