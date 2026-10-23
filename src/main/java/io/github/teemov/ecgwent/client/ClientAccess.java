package io.github.teemov.ecgwent.client;

/** Whether this client's player has unlocked Gwent (sent by the server). No client-only classes here: safe on servers. */
public final class ClientAccess {
    private static volatile boolean unlocked;

    private ClientAccess() {}

    public static boolean unlocked() { return unlocked; }

    public static void set(boolean v) { unlocked = v; }
}
