package com.bedwarsrandomizer;

import net.minecraftforge.common.ForgeConfigSpec;

public final class BwrConfig {
    public static final ForgeConfigSpec SPEC;

    // random drop settings live in RandomizerSettings (edited in game with /bwr setting randomizer)

    public static final ForgeConfigSpec.IntValue REPLAY_SECONDS;
    public static final ForgeConfigSpec.IntValue POST_DEATH_TICKS;
    public static final ForgeConfigSpec.IntValue REPLAY_ACTOR_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_REPLAYS_PER_KILLER;
    public static final ForgeConfigSpec.BooleanValue DEBUG_DUMP;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Kill replay recording.").push("replay");
        REPLAY_SECONDS = b.comment("How many seconds before a kill are kept.")
                .defineInRange("secondsBeforeKill", 5, 2, 30);
        POST_DEATH_TICKS = b.comment("Ticks recorded after the kill (death animation).")
                .defineInRange("ticksAfterKill", 20, 0, 60);
        REPLAY_ACTOR_RANGE = b.comment("Players within this many blocks of the killer or victim are included.")
                .defineInRange("actorRange", 64, 8, 256);
        MAX_REPLAYS_PER_KILLER = b.defineInRange("maxReplaysPerKiller", 5, 1, 50);
        DEBUG_DUMP = b.comment("Write every saved replay as CSV to bwr-replays/ in the server folder (for debugging).")
                .define("debugDump", true);
        b.pop();

        SPEC = b.build();
    }

    private BwrConfig() {}
}
