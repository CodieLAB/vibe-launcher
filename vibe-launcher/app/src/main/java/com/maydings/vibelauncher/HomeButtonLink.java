package com.maydings.vibelauncher;

/**
 * What the store build's Home button service (HomeKeyService, src/store) lends the rest of the app while
 * Android has it bound. Never set in the adb build.
 */
final class HomeButtonLink {

    /** Opens the TV's own power menu, where it asks before restarting; null while the service isn't bound. */
    static volatile Runnable powerMenu;

    private HomeButtonLink() {
    }

    static boolean bound() {
        return powerMenu != null;
    }
}
