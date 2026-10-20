package com.example.studiomobile.build;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.studiomobile.build.dependency.ResilientDns;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ResilientDnsTest {
    private static final class Fake implements ResilientDns.Resolver {
        final String name; final String answer; final List<String> asked;
        Fake(String name, String answer, List<String> asked) { this.name = name; this.answer = answer; this.asked = asked; }
        @Override public String name() { return name; }
        @Override public List<String> lookup(String host) throws IOException {
            asked.add(name);
            if (answer == null) throw new IOException("no answer");
            return Collections.singletonList(answer);
        }
    }

    @Test public void phoneDnsIsAskedOnlyAfterTheTwoPublicOnesFailed() throws Exception {
        List<String> asked = new ArrayList<>();
        ResilientDns dns = new ResilientDns(Arrays.<ResilientDns.Resolver>asList(new Fake("google", null, asked), new Fake("cloudflare", null, asked), new Fake("phone", "10.1.2.3", asked)));
        List<InetAddress> found = dns.resolve("repo.example.com");
        assertEquals("10.1.2.3", found.get(0).getHostAddress());
        assertEquals(Arrays.asList("google", "cloudflare", "phone"), asked);
    }

    @Test public void phoneDnsIsNotAskedWhenAnEarlierOneAnswered() throws Exception {
        List<String> asked = new ArrayList<>();
        ResilientDns dns = new ResilientDns(Arrays.<ResilientDns.Resolver>asList(new Fake("google", "8.8.4.4", asked), new Fake("phone", "10.1.2.3", asked)));
        assertEquals("8.8.4.4", dns.resolve("jitpack.io").get(0).getHostAddress());
        assertEquals(Collections.singletonList("google"), asked);
    }

    @Test public void stopsWhenEveryResolverFailsAndNamesAllOfThem() {
        List<String> asked = new ArrayList<>();
        ResilientDns dns = new ResilientDns(Arrays.<ResilientDns.Resolver>asList(new Fake("google", null, asked), new Fake("cloudflare", null, asked), new Fake("the phone's own DNS", null, asked)));
        try { dns.resolve("nowhere.example"); fail("expected DnsFailedException"); }
        catch (ResilientDns.DnsFailedException failure) { assertTrue(failure.getMessage(), failure.getMessage().contains("the phone's own DNS")); }
        assertEquals(3, asked.size());
    }

    @Test public void standardChainEndsWithThePhonesOwnDns() throws Exception {
        // The standard chain asks Google, Cloudflare, then the system. A literal address needs no lookup at all.
        assertEquals("93.184.216.34", ResilientDns.standard().resolve("93.184.216.34").get(0).getHostAddress());
    }
}
