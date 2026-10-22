package com.saedarm.kasserine;

/**
 * The guns you can carry. You start each run with the Garand and swap at
 * supply drops. Upgrades multiply these numbers rather than replacing them.
 *
 * They differ in range, spread, piercing, how long you're stuck reloading,
 * and how fast you can move. In simulated runs they come out close, with the
 * Trench Gun the riskiest because you have to get close to use it.
 */
public enum Weapon {
    //         title          clip  rate  dmg  pellets spread  range  reload pierce move  knock  sound      gun length
    GARAND    ("M1 Garand",     8,  4.5f, 16f,  1,     0.03f,  19f,   1.2f,  1,     1.00f, 0.3f, "rifle",   0.95f,
               "8-round clip that hits hard and goes through one man. Pings when it's empty."),
    THOMPSON  ("Thompson",     30, 11.0f, 7.5f, 1,     0.07f,  14f,   1.8f,  0,     1.00f, 0.15f, "smg",    0.7f,
               "30 rounds, very fast, short range, sprays."),
    BAR       ("BAR",          20,  7.0f, 12f,  1,     0.045f, 22f,   2.7f,  1,     0.85f, 0.3f, "bar",     1.25f,
               "20 heavy rounds that go through one man. Long range. You move 15% slower."),
    TRENCH_GUN("Trench Gun",    6,  1.5f, 9.5f, 8,     0.30f,  10f,   1.8f,  0,     1.00f, 0.8f, "shotgun", 0.85f,
               "6 shells of 8 pellets. Devastating up close, useless far away. Knocks men back.");

    public final String title, blurb, sound;
    public final int clip, pellets, pierce;
    /** rate: shots per second. range: how far a bullet flies. reload: seconds. move: speed multiplier. knock: shove per hit. */
    public final float rate, damage, spread, range, reload, move, knock, gunLength;

    Weapon(String title, int clip, float rate, float damage, int pellets, float spread, float range,
           float reload, int pierce, float move, float knock, String sound, float gunLength, String blurb) {
        this.title = title; this.clip = clip; this.rate = rate; this.damage = damage; this.pellets = pellets;
        this.spread = spread; this.range = range; this.reload = reload; this.pierce = pierce; this.move = move;
        this.knock = knock; this.sound = sound; this.gunLength = gunLength; this.blurb = blurb;
    }

    public static final float BULLET_SPEED = 26f;
}
