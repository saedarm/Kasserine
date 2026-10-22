package com.saedarm.kasserine.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.saedarm.kasserine.Kasserine;

public class Lwjgl3Launcher {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setTitle("Kasserine");
        config.setWindowedMode(1280, 590); // roughly the S23's landscape shape
        config.useVsync(true);
        config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4);
        new Lwjgl3Application(new Kasserine(), config);
    }
}
