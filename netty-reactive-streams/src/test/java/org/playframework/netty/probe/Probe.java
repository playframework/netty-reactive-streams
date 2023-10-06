package org.playframework.netty.probe;

import java.util.Date;

public class Probe {

    protected final String name;
    protected final Long start;

    /**
     * Create a new probe and log that it started.
     */
    protected Probe(String name) {
        this.name = name;
        start = System.nanoTime();
        log("Probe created at " + new Date());
    }

    /**
     * Create a new probe with the start time from another probe.
     */
    protected Probe(String name, long start) {
        this.name = name;
        this.start = start;
    }

    protected void log(String message) {
        System.out.println(String.format("%10d %-5s %-15s %s", (System.nanoTime() - start) / 1000, name, Thread.currentThread().getName(), message));
    }
}
