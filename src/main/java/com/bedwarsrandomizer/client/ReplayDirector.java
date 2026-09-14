package com.bedwarsrandomizer.client;

import com.bedwarsrandomizer.replay.ActorFrame;
import com.bedwarsrandomizer.replay.ReplayData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The "camera operator", modelled on broadcast replays:
 * <ul>
 *   <li>The action line (killer → victim at the kill) is fixed for the whole replay and every take films from the
 *       same side of it (180° rule), so left/right never flips between takes.</li>
 *   <li>Each take has its own scripted move around that line (angle, elevation). The camera does not swing around
 *       with the players; it only glides, with lag, to keep them centred.</li>
 *   <li>Distance is chosen so killer and victim fill a set part of the screen (group framing).</li>
 *   <li>Before a take starts, variations of its angle are tested against the world and the one that keeps both
 *       players visible (not behind walls) is used.</li>
 * </ul>
 */
final class ReplayDirector {
    record CameraPose(Vec3 pos, float yaw, float pitch, float roll, float fov) {}

    /**
     * How a take is filmed. Angles are measured around the action line: 0° is behind the killer, 90° is the side,
     * 180° is behind the victim. Elevation is in degrees above the players. {@code framing} is the share of the
     * screen height the players should fill.
     */
    private record Setup(double angleFrom, double angleTo, boolean angleInRealTime,
                         double elevationFrom, double elevationTo, double framing, float fov) {
        Setup shifted(double angle, double elevation) {
            return new Setup(Mth.clamp(angleFrom + angle, MIN_ANGLE, MAX_ANGLE), Mth.clamp(angleTo + angle, MIN_ANGLE, MAX_ANGLE), angleInRealTime,
                    elevationFrom + elevation, elevationTo + elevation, framing, fov);
        }
    }

    private record Group(Vec3 center, double radius) {}

    private static final double TOGETHER_DISTANCE = 16.0;
    /**
     * Cameras stay roughly side-on to the fight: then the killer faces one side of the screen and the victim the
     * other, so it is obvious who attacks whom. Front or back views hide that.
     */
    private static final double MIN_ANGLE = 45;
    private static final double MAX_ANGLE = 135;
    private static final double MIN_DISTANCE = 3.5;
    private static final double MAX_DISTANCE = 16.0;
    /** Real seconds the orbit take needs to sweep its full angle. */
    private static final double ORBIT_SECONDS = 7.0;
    private static final double[] ANGLE_SHIFTS = {0, -25, 25, -50, 50};
    private static final double[] ELEVATION_SHIFTS = {0, 15};
    private static final int PLAN_SAMPLES = 8;

    @Nullable private final ReplayData.Actor killer;
    @Nullable private final ReplayData.Actor victim;
    private final int deathFrame;
    private final ClientLevel level;
    private final Entity cameraEntity;
    private final Vec3 axis;
    private final Vec3 right;
    /** +1 / -1: the side of the action line all takes are filmed from. Chosen when the first take is planned. */
    private int side;

    private ReplayPlayback.Take active;
    private Setup setup;
    private Vec3 center;
    private double distance;

    ReplayDirector(ReplayData data, ClientLevel level, Entity cameraEntity) {
        this.killer = data.actor(data.killerId);
        this.victim = data.actor(data.victimId);
        this.deathFrame = data.deathFrame;
        this.level = level;
        this.cameraEntity = cameraEntity;
        this.side = (data.replayId.getLeastSignificantBits() & 1) == 0 ? 1 : -1;

        ActorFrame v = victim == null ? null : victim.nearest(deathFrame);
        ActorFrame k = killer == null ? null : killer.nearest(deathFrame);
        Vec3 line = null;
        if (v != null && k != null && position(k).distanceTo(position(v)) <= TOGETHER_DISTANCE) {
            line = horizontal(position(v).subtract(position(k)));
        }
        if (line == null) {
            line = Vec3.directionFromRotation(0, k != null ? k.yRot() : v == null ? 0 : v.yRot());
        }
        this.axis = line;
        this.right = new Vec3(-axis.z, 0, axis.x);
    }

    CameraPose update(ReplayPlayback.Take take, double t, double takeReal, double dt) {
        Group group = group(t);
        if (take != active) {
            active = take;
            setup = plan(take);
            center = group.center();
            distance = fitDistance(group.radius(), setup);
        } else {
            // lag behind the players like a real operator, never snap; height reacts even slower
            double alongGround = 1 - Math.exp(-dt * 3.5);
            double upDown = 1 - Math.exp(-dt * 1.5);
            Vec3 target = group.center();
            center = new Vec3(Mth.lerp(alongGround, center.x, target.x), Mth.lerp(upDown, center.y, target.y),
                    Mth.lerp(alongGround, center.z, target.z));
            distance = Mth.lerp(1 - Math.exp(-dt * 2.0), distance, fitDistance(group.radius(), setup));
        }

        double progress = progress(take, t);
        double angleProgress = setup.angleInRealTime() ? Math.min(1, takeReal / ORBIT_SECONDS) : progress;
        Vec3 direction = direction(setup, side, progress, angleProgress);

        // never end up inside a wall: stop in front of the first block between players and camera
        double open = openDistance(center, center.add(direction.scale(distance)));
        Vec3 pos = center.add(direction.scale(Math.max(1.0, Math.min(distance, open))));

        Vec3 d = center.subtract(pos);
        double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, horizontal) * Mth.RAD_TO_DEG);
        return new CameraPose(pos, yaw, pitch, 0, setup.fov());
    }

    private static Setup setupFor(ReplayPlayback.TakeType type) {
        return switch (type) {
            // side-on wide shot, drifting slightly along the fight
            case WIDE -> new Setup(80, 100, false, 16, 10, 0.55, 60);
            // low angle a little towards the victim's side, rising slightly: shows the killer coming in
            case CRANE -> new Setup(115, 105, false, -4, 7, 0.6, 55);
            // slow sweep past the side of the kill, never swinging round to the front or back
            case ORBIT -> new Setup(65, 115, true, 9, 13, 0.65, 50);
        };
    }

    // ------------------------------------------------------------------------------------------
    // Planning: pick the variation of the take that keeps both players in sight

    private Setup plan(ReplayPlayback.Take take) {
        Setup base = setupFor(take.type());
        boolean chooseSide = take.type() == ReplayPlayback.TakeType.WIDE;
        List<Integer> sides = chooseSide ? List.of(side, -side) : List.of(side);

        Setup best = base;
        int bestSide = side;
        int bestBlocked = Integer.MAX_VALUE;
        for (double angleShift : ANGLE_SHIFTS) {
            for (double elevationShift : ELEVATION_SHIFTS) {
                Setup candidate = base.shifted(angleShift, elevationShift);
                for (int s : sides) {
                    int blocked = blockedSamples(take, candidate, s);
                    if (blocked < bestBlocked) {
                        best = candidate;
                        bestSide = s;
                        bestBlocked = blocked;
                    }
                    if (blocked == 0) {
                        side = s;
                        return candidate;
                    }
                }
            }
        }
        side = bestSide;
        return best;
    }

    /** How often the players would be hidden behind blocks while filming {@code take} this way; the kill counts triple. */
    private int blockedSamples(ReplayPlayback.Take take, Setup candidate, int s) {
        int blocked = 0;
        for (int i = 0; i <= PLAN_SAMPLES; i++) {
            double p = i / (double) PLAN_SAMPLES;
            double t = Mth.lerp(p, take.startFrame(), take.endFrame());
            int weight = t >= deathFrame - 10 ? 3 : 1;

            Group group = group(t);
            Vec3 direction = direction(candidate, s, p, p);
            double wanted = fitDistance(group.radius(), candidate);
            double open = openDistance(group.center(), group.center().add(direction.scale(wanted)));
            if (open < wanted * 0.6) blocked += weight;
            Vec3 camera = group.center().add(direction.scale(Math.max(1.0, Math.min(wanted, open))));

            for (Vec3 point : bodyPoints(t)) {
                if (hit(camera, point).getType() != HitResult.Type.MISS) blocked += weight;
            }
        }
        return blocked;
    }

    private List<Vec3> bodyPoints(double t) {
        List<Vec3> points = new ArrayList<>(4);
        Vec3 victimFeet = victim == null ? null : feet(victim, t);
        if (victimFeet != null) {
            points.add(victimFeet.add(0, 1.0, 0));
            points.add(victimFeet.add(0, 1.6, 0));
        }
        Vec3 killerFeet = killerFeet(t, victimFeet);
        if (killerFeet != null) {
            points.add(killerFeet.add(0, 1.0, 0));
            points.add(killerFeet.add(0, 1.6, 0));
        }
        return points;
    }

    // ------------------------------------------------------------------------------------------

    private Vec3 direction(Setup s, int filmSide, double progress, double angleProgress) {
        double angle = Math.toRadians(Mth.lerp(ease(angleProgress), s.angleFrom(), s.angleTo()));
        double elevation = Math.toRadians(Mth.lerp(ease(progress), s.elevationFrom(), s.elevationTo()));
        Vec3 around = axis.scale(-Math.cos(angle)).add(right.scale(filmSide * Math.sin(angle)));
        return around.scale(Math.cos(elevation)).add(0, Math.sin(elevation), 0);
    }

    private static double progress(ReplayPlayback.Take take, double t) {
        return Mth.clamp((t - take.startFrame()) / Math.max(1, take.endFrame() - take.startFrame()), 0, 1);
    }

    /** Box around killer and victim (feet to head); the killer only counts while close to the victim. */
    private Group group(double t) {
        Vec3 victimFeet = victim == null ? null : groundedFeet(victim, t);
        Vec3 killerFeet = killerFeet(t, victimFeet);
        if (victimFeet == null) victimFeet = killerFeet != null ? killerFeet : cameraEntity.position();

        double minX = victimFeet.x, maxX = victimFeet.x;
        double minY = victimFeet.y, maxY = victimFeet.y + 1.8;
        double minZ = victimFeet.z, maxZ = victimFeet.z;
        if (killerFeet != null) {
            minX = Math.min(minX, killerFeet.x);
            maxX = Math.max(maxX, killerFeet.x);
            minY = Math.min(minY, killerFeet.y);
            maxY = Math.max(maxY, killerFeet.y + 1.8);
            minZ = Math.min(minZ, killerFeet.z);
            maxZ = Math.max(maxZ, killerFeet.z);
        }
        Vec3 center = new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
        double radius = 0.5 * Math.sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ)) + 0.6;
        return new Group(center, radius);
    }

    @Nullable
    private Vec3 killerFeet(double t, @Nullable Vec3 victimFeet) {
        if (killer == null || frame(killer, (int) Math.round(t)) == null) return null;
        Vec3 feet = groundedFeet(killer, t);
        return feet != null && victimFeet != null && feet.distanceTo(victimFeet) > TOGETHER_DISTANCE ? null : feet;
    }

    /** Distance at which a sphere of {@code radius} fills {@code framing} of the (vertical) field of view. */
    private static double fitDistance(double radius, Setup s) {
        double halfFov = Math.toRadians(s.fov()) / 2;
        return Mth.clamp(radius / (s.framing() * Math.tan(halfFov)), MIN_DISTANCE, MAX_DISTANCE);
    }

    private double openDistance(Vec3 from, Vec3 to) {
        BlockHitResult hit = hit(from, to);
        return hit.getType() == HitResult.Type.MISS ? from.distanceTo(to) : Math.max(0, from.distanceTo(hit.getLocation()) - 0.4);
    }

    private BlockHitResult hit(Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, cameraEntity));
    }

    @Nullable
    private static Vec3 feet(ReplayData.Actor actor, double t) {
        int i = Mth.floor(t);
        ActorFrame a = frame(actor, i);
        ActorFrame b = frame(actor, i + 1);
        if (a == null && b == null) {
            ActorFrame nearest = actor.nearest(i);
            return nearest == null ? null : position(nearest);
        }
        if (a == null) a = b;
        if (b == null) b = a;
        double f = t - i;
        return new Vec3(Mth.lerp(f, a.x(), b.x()), Mth.lerp(f, a.y(), b.y()), Mth.lerp(f, a.z(), b.z()));
    }

    /**
     * Feet position with the height taken from the last time the player stood on the ground, so jumping does not
     * make the camera bob. Falling is followed, but at most 3 blocks (void kills should not drag the camera down).
     */
    @Nullable
    private static Vec3 groundedFeet(ReplayData.Actor actor, double t) {
        Vec3 feet = feet(actor, t);
        if (feet == null) return null;
        int start = Math.min(Mth.floor(t), actor.frames.length - 1);
        for (int i = start; i >= 0 && i > start - 60; i--) {
            ActorFrame f = actor.frames[i];
            if (f != null && f.hasFlag(ActorFrame.FLAG_ON_GROUND)) {
                return new Vec3(feet.x, Mth.clamp(feet.y, f.y() - 3, f.y()), feet.z);
            }
        }
        return feet;
    }

    @Nullable
    private static ActorFrame frame(ReplayData.Actor actor, int index) {
        return index >= 0 && index < actor.frames.length ? actor.frames[index] : null;
    }

    private static Vec3 position(ActorFrame frame) {
        return new Vec3(frame.x(), frame.y(), frame.z());
    }

    @Nullable
    private static Vec3 horizontal(Vec3 v) {
        double length = Math.sqrt(v.x * v.x + v.z * v.z);
        return length < 0.3 ? null : new Vec3(v.x / length, 0, v.z / length);
    }

    private static double ease(double p) {
        return p * p * (3 - 2 * p);
    }
}
