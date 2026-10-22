package com.saedarm.kasserine;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.TimeUtils;

/**
 * Loads assets/sfx/*.wav and plays them with a little random pitch so repeats
 * don't sound robotic. Sounds further from the player are quieter and panned.
 */
public class Sfx implements Disposable {

    private static final String[] NAMES = {
        "rifle", "enemy_rifle", "cannon", "blast", "hit", "clank", "dodge", "hurt", "tank_aim", "breakthrough",
    };

    private final ObjectMap<String, Sound> sounds = new ObjectMap<>();
    private final ObjectMap<String, Long> lastPlayed = new ObjectMap<>();
    public float master = 0.8f;

    public Sfx() {
        for (String n : NAMES) {
            try {
                sounds.put(n, Gdx.audio.newSound(Gdx.files.internal("sfx/" + n + ".wav")));
            } catch (Exception e) {
                // No audio device (or a missing file) shouldn't stop the game.
                Gdx.app.error("Sfx", "Couldn't load " + n + ": " + e.getMessage());
            }
        }
    }

    public void play(String name, float volume) { play(name, volume, 0, 0.06f); }

    /**
     * pan: -1 left .. 1 right. pitchJitter: random +/- pitch change.
     * The same sound can't restart within 30ms, so 20 rifles firing at once
     * don't stack into one deafening spike.
     */
    public void play(String name, float volume, float pan, float pitchJitter) {
        Sound s = sounds.get(name);
        if (s == null || volume <= 0.01f) return;
        long now = TimeUtils.millis();
        Long last = lastPlayed.get(name);
        if (last != null && now - last < 30) return;
        lastPlayed.put(name, now);
        s.play(volume * master, 1f + MathUtils.random(-pitchJitter, pitchJitter), MathUtils.clamp(pan, -1, 1));
    }

    /**
     * Plays a sound that happened at (x, z) in the world, heard by the player at (px, pz).
     * With this camera, screen right is world -x, hence the minus sign on pan.
     */
    public void playAt(String name, float volume, float x, float z, float px, float pz) {
        float dx = x - px, dz = z - pz;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        float falloff = MathUtils.clamp(1f - d / 32f, 0.15f, 1f);
        play(name, volume * falloff, -dx / 14f, 0.08f);
    }

    @Override
    public void dispose() {
        for (Sound s : sounds.values()) s.dispose();
    }
}
