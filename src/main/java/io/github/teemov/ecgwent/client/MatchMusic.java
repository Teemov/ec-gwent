package io.github.teemov.ecgwent.client;

import io.github.teemov.ecgwent.EcGwent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The match theme. When a match starts, all other music (background music and jukeboxes) stops and a
 * random theme from the "ec_gwent:music.match" sound event loops until the match ends, then fades out.
 * New music is blocked while it plays. Follows the Music volume slider.
 */
public final class MatchMusic {
    private static final SoundEvent THEME = SoundEvent.createVariableRangeEvent(new ResourceLocation(EcGwent.MODID, "music.match"));
    private static Theme playing;

    private MatchMusic() {}

    /** Called every client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean running = ClientHooks.matchRunning() && mc.level != null;
        if (running && (playing == null || playing.isStopped())) {
            mc.getMusicManager().stopPlaying();
            mc.getSoundManager().stop(null, SoundSource.MUSIC);
            mc.getSoundManager().stop(null, SoundSource.RECORDS);
            playing = new Theme();
            mc.getSoundManager().play(playing);
        } else if (!running && playing != null) {
            playing.fadeOut();
            if (playing.isStopped()) playing = null;
        }
        if (playing != null && !playing.fading) mc.getMusicManager().stopPlaying(); // keep the world music quiet
    }

    /** True if this sound may play right now (other music is held back during a match). */
    public static boolean allow(SoundInstance sound) {
        if (playing == null || playing.fading || sound == playing) return true;
        SoundSource src = sound.getSource();
        return src != SoundSource.MUSIC && src != SoundSource.RECORDS;
    }

    public static void stopNow() {
        if (playing != null) Minecraft.getInstance().getSoundManager().stop(playing);
        playing = null;
    }

    private static final class Theme extends AbstractTickableSoundInstance {
        boolean fading;
        private float level;

        Theme() {
            super(THEME, SoundSource.MUSIC, RandomSource.create());
            looping = true;
            delay = 0;
            relative = true;
            attenuation = Attenuation.NONE;
            volume = 0.01f;
        }

        void fadeOut() { fading = true; }

        @Override
        public void tick() {
            if (fading) {
                level -= 0.04f; // about 1.25 seconds
                if (level <= 0) { stop(); return; }
            } else if (level < 1) {
                level = Math.min(1, level + 0.05f); // fade in over 1 second
            }
            volume = Math.max(0.01f, level);
        }

        @Override
        public boolean canStartSilent() { return true; }
    }
}
