package com.stxaviers.app;

import android.view.View;

/**
 * ViewUtils (v1.2.3) — typed, crash-proof findViewById for NEW code.
 *
 * Why this exists: the v1.1.8 bug-report hub shipped an invisible bug for
 * four releases because findViewById's unchecked cast threw
 * ClassCastException at runtime (a FrameLayout in XML declared as a
 * LinearLayout in Java) and a silent catch ate it. The audit script that
 * cross-checks every cast against the XML layouts is the guard for EXISTING
 * code; this helper is the guard for FUTURE code — adopt it incrementally
 * in new/edited screens (no need to mass-migrate the existing activities).
 *
 * Usage:
 *   TextView tv = ViewUtils.find(contentView, R.id.title, TextView.class);
 *   if (tv != null) tv.setText(...);            // graceful: log + null
 *   or, when the screen is dead without the view:
 *   TextView tv = ViewUtils.require(this, R.id.title, TextView.class); // throws with a message a bug report will carry
 *
 * Both variants log a breadcrumb through XLog (latestlog.txt), so a layout
 * drift shows up in the bug-report hub even when nobody crashes.
 */
public final class ViewUtils {

    private static final String TAG = "ViewUtils";

    private ViewUtils() {}

    /**
     * Null-safe typed lookup. Returns null (never throws) when the id is
     * missing or the XML declares a different view class — logs a W line
     * naming both classes so the drift is diagnosable from a bug report.
     */
    public static <T extends View> T find(View root, int id, Class<T> type) {
        if (root == null) {
            XLog.w(TAG, "find: null root (id=" + id + ")");
            return null;
        }
        View v = root.findViewById(id);
        if (v == null) {
            XLog.w(TAG, "find: id " + id + " not in layout of "
                    + root.getClass().getSimpleName());
            return null;
        }
        if (!type.isInstance(v)) {
            XLog.w(TAG, "find: id " + id + " is " + v.getClass().getName()
                    + ", expected " + type.getName()
                    + " — layout drift, fix the cast or the XML");
            return null;
        }
        return type.cast(v);
    }

    /**
     * Fail-fast variant for views a screen cannot render without. Throws
     * IllegalStateException with the same diagnostic breadcrumb (the app's
     * crash-proof screens catch around their render path and surface the
     * red error card, which ships this message to the bug-report hub).
     */
    public static <T extends View> T require(View root, int id, Class<T> type) {
        T v = find(root, id, type);
        if (v == null) {
            throw new IllegalStateException(TAG + ": required view id " + id
                    + " missing or wrong type " + type.getName()
                    + " in " + (root != null ? root.getClass().getSimpleName() : "null root"));
        }
        return v;
    }
}
