package com.instinct.widget;

import java.time.OffsetDateTime;

/** Feed freshness comparison on parsed epoch instants, not raw strings. JVM-testable. */
public class Freshness {
    public enum Result { NEWER, SAME, OLDER, UNKNOWN }

    public static long parseEpoch(String iso) throws Exception {
        return OffsetDateTime.parse(iso).toInstant().getEpochSecond();
    }

    public static Result compare(String genIso, String lastIso) {
        if (genIso == null || genIso.isEmpty()) return Result.UNKNOWN;
        if (lastIso == null || lastIso.isEmpty()) return Result.NEWER;
        try {
            long g = parseEpoch(genIso);
            long l = parseEpoch(lastIso);
            if (g > l) return Result.NEWER;
            if (g == l) return Result.SAME;
            return Result.OLDER;
        } catch (Exception e) {
            return Result.UNKNOWN;
        }
    }
}
