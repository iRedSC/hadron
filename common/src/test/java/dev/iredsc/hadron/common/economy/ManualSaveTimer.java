package dev.iredsc.hadron.common.economy;

import java.util.ArrayList;
import java.util.List;

/** A save timer the test runs by hand: nothing saves until {@link #elapse()}, whatever the wall clock says. */
final class ManualSaveTimer implements JsonEconomyProvider.SaveTimer {
    private final List<Runnable> waiting = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    private boolean stopped;

    @Override
    public synchronized void schedule(Runnable save, long delaySeconds) {
        waiting.add(save);
        delays.add(delaySeconds);
    }

    @Override
    public synchronized void stop() {
        stopped = true;
        waiting.clear();
    }

    @Override
    public synchronized boolean stopped() {
        return stopped;
    }

    synchronized int waiting() {
        return waiting.size();
    }

    /** Runs every save that was waiting, as if its delay had passed. Saves they schedule wait for the next call. */
    void elapse() {
        List<Runnable> due;
        synchronized (this) {
            due = new ArrayList<>(waiting);
            waiting.clear();
        }
        due.forEach(Runnable::run);
    }
}
