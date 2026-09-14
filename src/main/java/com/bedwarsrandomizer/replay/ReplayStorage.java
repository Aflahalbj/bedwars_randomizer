package com.bedwarsrandomizer.replay;

import com.bedwarsrandomizer.BwrConfig;

import javax.annotation.Nullable;
import java.util.*;

/** In-memory kill replays, newest first per killer. Cleared when the server stops. */
public final class ReplayStorage {
    private static final Map<String, Deque<ReplayData>> BY_KILLER = new LinkedHashMap<>();

    public static void add(ReplayData data) {
        Deque<ReplayData> replays = BY_KILLER.computeIfAbsent(key(data.killerName), k -> new ArrayDeque<>());
        replays.addFirst(data);
        while (replays.size() > BwrConfig.MAX_REPLAYS_PER_KILLER.get()) {
            replays.removeLast();
        }
    }

    @Nullable
    public static ReplayData latest(String killerName) {
        Deque<ReplayData> replays = BY_KILLER.get(key(killerName));
        return replays == null ? null : replays.peekFirst();
    }

    /** Swaps a stored replay for a better version with the same id. Returns false if it is no longer stored. */
    public static boolean replace(ReplayData data) {
        Deque<ReplayData> replays = BY_KILLER.get(key(data.killerName));
        if (replays == null) return false;
        List<ReplayData> list = new ArrayList<>(replays);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).replayId.equals(data.replayId)) {
                list.set(i, data);
                found = true;
            }
        }
        if (found) {
            replays.clear();
            replays.addAll(list);
        }
        return found;
    }

    public static List<ReplayData> all() {
        List<ReplayData> list = new ArrayList<>();
        BY_KILLER.values().forEach(list::addAll);
        list.sort(Comparator.comparingLong((ReplayData d) -> d.createdAt).reversed());
        return list;
    }

    public static List<String> killerNames() {
        List<String> names = new ArrayList<>();
        for (Deque<ReplayData> replays : BY_KILLER.values()) {
            if (!replays.isEmpty()) names.add(replays.peekFirst().killerName);
        }
        return names;
    }

    public static void clear() {
        BY_KILLER.clear();
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private ReplayStorage() {}
}
