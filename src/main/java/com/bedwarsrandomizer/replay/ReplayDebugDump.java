package com.bedwarsrandomizer.replay;

import com.bedwarsrandomizer.BedwarsRandomizer;
import com.bedwarsrandomizer.BwrConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes a saved replay as CSV to {@code <server dir>/bwr-replays/}, one row per player per recorded tick, so the
 * raw recording can be inspected when a replay looks wrong.
 */
public final class ReplayDebugDump {

    public static void write(MinecraftServer server, ReplayData data) {
        if (!BwrConfig.DEBUG_DUMP.get()) return;

        StringBuilder csv = new StringBuilder();
        csv.append("# killer=").append(data.killerName).append(" victim=").append(data.victimName)
                .append(" cause=").append(data.cause).append(" frames=").append(data.frameCount)
                .append(" deathFrame=").append(data.deathFrame).append(" blockChanges=").append(data.blockChanges.size())
                .append(" clientRecorded=").append(data.exact)
                .append('\n');
        csv.append("frame,player,x,y,z,yRot,xRot,pose,onGround,sprinting,crouching,swingStarted,offhand,hurtTime,deathTime,health,crit,magicCrit\n");
        for (int frame = 0; frame < data.frameCount; frame++) {
            for (ReplayData.Actor actor : data.actors) {
                ActorFrame f = actor.frames[frame];
                if (f == null) continue;
                csv.append(String.format(Locale.ROOT, "%d,%s,%.4f,%.4f,%.4f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d%n",
                        frame, trackName(actor), f.x(), f.y(), f.z(), f.yRot(), f.xRot(), f.pose(),
                        bit(f, ActorFrame.FLAG_ON_GROUND), bit(f, ActorFrame.FLAG_SPRINTING), bit(f, ActorFrame.FLAG_CROUCHING),
                        bit(f, ActorFrame.FLAG_SWING), bit(f, ActorFrame.FLAG_OFFHAND_SWING),
                        f.hurtTime(), f.deathTime(), f.health(),
                        bit(f, ActorFrame.FLAG_CRIT), bit(f, ActorFrame.FLAG_MAGIC_CRIT)));
            }
        }

        String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(data.createdAt))
                + "_" + data.killerName + "_" + data.victimName + ".csv";
        Path file = server.getServerDirectory().toPath().resolve("bwr-replays").resolve(name);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, csv, StandardCharsets.UTF_8);
            BedwarsRandomizer.LOGGER.info("Replay dump written to {}", file.toAbsolutePath());
        } catch (IOException e) {
            BedwarsRandomizer.LOGGER.warn("Could not write replay dump {}", file, e);
        }
    }

    private static String trackName(ReplayData.Actor actor) {
        return actor.isPlayer() ? actor.name : BuiltInRegistries.ENTITY_TYPE.getKey(actor.type).getPath();
    }

    private static int bit(ActorFrame frame, int flag) {
        return frame.hasFlag(flag) ? 1 : 0;
    }

    private ReplayDebugDump() {}
}
