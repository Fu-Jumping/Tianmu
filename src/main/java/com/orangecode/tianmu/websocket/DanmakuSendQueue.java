package com.orangecode.tianmu.websocket;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/** Bounded per-connection ordering and process-wide admission for asynchronous sends. */
@Slf4j
final class DanmakuSendQueue {
    private static final int MAX_PER_CONNECTION = 32;

    record Frame(long room, String json, long received, long started) {}
    enum Offer { START, QUEUED, REJECTED }

    static final class Send {
        private final Frame frame;
        private Runnable outcome;

        Send(Frame frame) { this.frame = frame; }
        Frame frame() { return frame; }
    }

    static final class Limits {
        private final int globalLimit;
        private final int roomLimit;
        private final int window;
        private final Map<Long, Integer> rooms = new HashMap<>();
        private int outstanding;

        Limits(int globalLimit, int roomLimit, int window) {
            if (window < 1 || window > MAX_PER_CONNECTION)
                throw new IllegalArgumentException("Danmaku async window must be between 1 and " + MAX_PER_CONNECTION);
            this.globalLimit = globalLimit;
            this.roomLimit = roomLimit;
            this.window = window;
        }

        synchronized boolean tryAcquire(long room) {
            int roomCount = rooms.getOrDefault(room, 0);
            if (outstanding >= globalLimit || roomCount >= roomLimit) return false;
            outstanding++;
            rooms.put(room, roomCount + 1);
            return true;
        }

        synchronized void release(long room) {
            int remaining = rooms.get(room) - 1;
            if (remaining == 0) rooms.remove(room);
            else rooms.put(room, remaining);
            outstanding--;
        }
    }

    private final Limits limits;
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();
    private final ArrayDeque<Send> active = new ArrayDeque<>();
    private boolean closed;
    private boolean emitting;

    DanmakuSendQueue(Limits limits) {
        this.limits = limits;
    }

    synchronized Offer offer(Frame frame) {
        if (closed || pending.size() + active.size() >= MAX_PER_CONNECTION || !limits.tryAcquire(frame.room()))
            return Offer.REJECTED;
        pending.addLast(frame);
        return active.size() < limits.window ? Offer.START : Offer.QUEUED;
    }

    synchronized Send take() {
        if (closed || active.size() >= limits.window || pending.isEmpty()) return null;
        var send = new Send(pending.removeFirst());
        active.addLast(send);
        return send;
    }

    void complete(Send send, Runnable outcome) {
        synchronized (this) {
            if (!active.contains(send) || send.outcome != null) return;
            send.outcome = outcome;
            if (emitting) return;
            emitting = true;
        }
        emitReady();
    }

    /** A callback rejected by the business executor can still complete a send off-thread. */
    private void emitReady() {
        while (true) {
            Send first;
            synchronized (this) {
                first = active.peekFirst();
                if (first == null || first.outcome == null) {
                    emitting = false;
                    return;
                }
            }
            try {
                first.outcome.run();
            } catch (RuntimeException failure) {
                log.warn("Danmaku send outcome dispatch failed, cause={}", failure.getClass().getSimpleName());
            } finally {
                synchronized (this) {
                    active.removeFirst();
                    limits.release(first.frame().room());
                }
            }
        }
    }

    synchronized void close() {
        closed = true;
        for (Frame frame : pending) limits.release(frame.room());
        pending.clear();
        // Submitted sends can still publish in order for the room after their sender leaves.
    }
}
