package com.bedwarsrandomizer.replay;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;

public enum KillCause {
    MELEE("slain"),
    PROJECTILE("shot"),
    VOID("knocked into the void"),
    EXPLOSION("blown up"),
    FALL("knocked off a cliff"),
    FIRE("burned"),
    OTHER("eliminated");

    public final String verb;

    KillCause(String verb) {
        this.verb = verb;
    }

    public static KillCause of(DamageSource source, Entity killer) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) return VOID;
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return EXPLOSION;
        if (source.is(DamageTypeTags.IS_PROJECTILE)) return PROJECTILE;
        if (source.is(DamageTypeTags.IS_FALL)) return FALL;
        if (source.is(DamageTypeTags.IS_FIRE)) return FIRE;
        if (source.getEntity() == killer && source.getDirectEntity() == killer) return MELEE;
        return OTHER;
    }
}
