package dev.iredsc.hadron.selftest.world;

import static dev.iredsc.hadron.selftest.world.Console.assertServer;

import dev.iredsc.hadron.api.event.EventHandler;
import dev.iredsc.hadron.api.event.Listener;
import dev.iredsc.hadron.api.event.world.TimeSkipEvent;
import dev.iredsc.hadron.api.event.world.WeatherChangeEvent;
import dev.iredsc.hadron.test.Covers;
import dev.iredsc.hadron.test.HadronTest;
import dev.iredsc.hadron.test.TestContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * /weather and /time from the console. Weather and time belong to the overworld (test worlds share them), so each test
 * puts them back, with its listener switched off first so it can't refuse the restore.
 */
public final class WeatherTimeTests {
    private static void clear(ServerLevel overworld) {
        overworld.setWeatherParameters(24000, 0, false, false);
    }

    @HadronTest
    @Covers(WeatherChangeEvent.class)
    void weatherCommandIsAWeatherChangeByTheServer(TestContext t) {
        ServerLevel overworld = t.server().overworld();
        clear(overworld);
        List<WeatherChangeEvent> seen = new ArrayList<>();
        boolean[] on = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(WeatherChangeEvent e) {
                if (on[0]) seen.add(e);
            }
        });
        try {
            Console.run(t, "weather rain");
            t.assertTrue(overworld.getLevelData().isRaining(), "/weather rain should make it rain");
            t.assertEquals(1, seen.size(), "the number of WeatherChangeEvents for /weather rain");
            assertServer(t, seen.get(0).getActor());
            t.assertEquals(WeatherChangeEvent.Weather.CLEAR, seen.get(0).getFrom(), "the weather before");
            t.assertEquals(WeatherChangeEvent.Weather.RAIN, seen.get(0).getTo(), "the weather after");
        } finally {
            on[0] = false;
            clear(overworld);
        }
    }

    @HadronTest
    @Covers(WeatherChangeEvent.class)
    void cancelledWeatherChangeKeepsItClear(TestContext t) {
        ServerLevel overworld = t.server().overworld();
        clear(overworld);
        boolean[] on = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(WeatherChangeEvent e) {
                if (on[0]) e.setCancelled(true);
            }
        });
        try {
            Console.run(t, "weather thunder");
            t.assertFalse(overworld.getLevelData().isRaining(), "a cancelled /weather thunder should leave no rain");
            t.assertFalse(overworld.getLevelData().isThundering(), "a cancelled /weather thunder should leave no "
                    + "thunder");
        } finally {
            on[0] = false;
            clear(overworld);
        }
    }

    @HadronTest
    @Covers(TimeSkipEvent.class)
    void timeCommandIsOneTimeSkipByTheServer(TestContext t) {
        // /time touches every world, but only the overworld owns the clock, so one event.
        ServerLevel overworld = t.server().overworld();
        long before = overworld.getDayTime();
        List<TimeSkipEvent> seen = new ArrayList<>();
        boolean[] on = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(TimeSkipEvent e) {
                if (on[0]) seen.add(e);
            }
        });
        try {
            Console.run(t, "time add 1000");
            t.assertEquals(before + 1000, overworld.getDayTime(), "the time after /time add 1000");
            t.assertEquals(1, seen.size(), "the number of TimeSkipEvents for one /time add");
            assertServer(t, seen.get(0).getActor());
            t.assertEquals(before, seen.get(0).getOldTime(), "the event's old time");
            t.assertEquals(1000L, seen.get(0).getSkipAmount(), "the event's skip");
        } finally {
            on[0] = false;
            overworld.setDayTime(before);
        }
    }

    @HadronTest
    @Covers(TimeSkipEvent.class)
    void cancelledTimeSkipKeepsTheTime(TestContext t) {
        ServerLevel overworld = t.server().overworld();
        long before = overworld.getDayTime();
        boolean[] on = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(TimeSkipEvent e) {
                if (on[0]) e.setCancelled(true);
            }
        });
        try {
            Console.run(t, "time add 1000");
            t.assertEquals(before, overworld.getDayTime(), "the time after a cancelled /time add");
        } finally {
            on[0] = false;
            overworld.setDayTime(before);
        }
    }

    @HadronTest
    @Covers(TimeSkipEvent.class)
    void changedSkipAmountIsTheTimeSet(TestContext t) {
        ServerLevel overworld = t.server().overworld();
        long before = overworld.getDayTime();
        boolean[] on = {true};
        t.registerEvents(new Listener() {
            @EventHandler
            void on(TimeSkipEvent e) {
                if (on[0]) e.setSkipAmount(50);
            }
        });
        try {
            Console.run(t, "time add 1000");
            t.assertEquals(before + 50, overworld.getDayTime(), "the time after a skip changed to 50");
        } finally {
            on[0] = false;
            overworld.setDayTime(before);
        }
    }
}
