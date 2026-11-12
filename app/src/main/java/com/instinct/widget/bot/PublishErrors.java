package com.instinct.widget.bot;

/** Turns raw publish fan-out errors into a plain-language lead for the
 *  publish result dialog. Presentation only: the runtime's validation is
 *  untouched, publishing stays user-initiated, and the raw error always
 *  remains visible below as technical details. */
public final class PublishErrors {
    private PublishErrors() {}

    public static final String LATEST_XDC_URL =
        "https://github.com/irvan-putra/intodo/releases/latest";

    /** Plain-language lead for a known refusal, or null when the raw
     *  error has no friendlier explanation. The display name is the name
     *  the file picker showed for the selected file. */
    public static String friendlyLead(String rawError, String displayName) {
        if (rawError != null && rawError.contains("manifest version unresolved")) {
            String shown = (displayName == null || displayName.isEmpty())
                ? "The selected file" : "\"" + displayName + "\"";
            return shown + " has no version number, so the bot refused to publish it. "
                + "Publishing needs a versioned mini-app package: use button 6 to fetch "
                + "the latest version from GitHub, or download it yourself and pick it:\n"
                + LATEST_XDC_URL;
        }
        return null;
    }
}
