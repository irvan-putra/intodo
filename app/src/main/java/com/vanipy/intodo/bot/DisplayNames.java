package com.vanipy.intodo.bot;

/** Presentation names only. Never use this value for remote-asset validation,
 * cache metadata, package identity, or saved-state lookup. */
public final class DisplayNames {
    private DisplayNames() {}

    public static String text(String value) {
        if (value == null) return null;
        return value.replaceAll("(?i)itdapk|itdxdc|itdweb", "intodo");
    }
}
