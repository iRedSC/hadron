package dev.iredsc.hadron.examples.hello;

import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import dev.iredsc.hadron.test.TestPlayer;

/** The hello plugin as a player meets it: its command and its chat rule. */
public final class HelloTests {
    @HadronTest
    void helloGreetsWhoeverRunsIt(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.command("/hello");
        t.assertChat(alice, "Hello, alice (via /hello)");
    }

    @HadronTest
    void helloGreetsTheNameGiven(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.command("hi world");
        t.assertChat(alice, "Hello, world (via /hi)");
    }

    @HadronTest
    void nobodyIsNeverGreeted(TestContext t) {
        TestPlayer alice = t.player("alice");
        alice.command("hello nobody");
        t.assertChat(alice, "Not greeting nobody");
        t.assertNoChat(alice, "Hello, nobody");
    }

    @HadronTest
    void secretsAreNeverSent(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        alice.chat("the secret is 42");
        t.assertNoChat(bob, "secret");
        t.assertNoChat(alice, "secret");
    }

    @HadronTest
    void helloIsAnsweredInTheSameBreath(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        alice.chat("hello there");
        t.assertChat(bob, "hello there (hi!)");
    }

    @HadronTest
    void otherChatPassesUnchanged(TestContext t) {
        TestPlayer alice = t.player("alice");
        TestPlayer bob = t.player("bob");
        alice.chat("good morning");
        t.assertChat(bob, "good morning");
        t.assertNoChat(bob, "(hi!)");
    }
}
