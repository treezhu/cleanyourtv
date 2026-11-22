package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The set's own source stack: the {@code com.jamdeo.flow} shared library (VIDAA "FlowManager"), the same API the
 * stock launcher's source panel uses. Sets like the Hisense 65E3G publish no TvInputInfo at all, so
 * TvInputManager has nothing to list and the Inputs menu comes up empty; the HDMI ports, the AV jacks and the
 * tuner are only reachable through com.jamdeo.tv.service, whose client lives in that library.
 *
 * <p>The library is declared as a system shared library in /system/etc/permissions/com.jamdeo.flow.xml, so
 * {@code <uses-library android:name="com.jamdeo.flow" android:required="false"/>} makes it loadable here. It is
 * driven reflectively so the launcher still builds, installs and runs on sets that do not ship it.
 *
 * <p>The manager binds to com.jamdeo.tv.service in its constructor and stays bound for the process lifetime, so
 * one instance is created at startup and kept; {@link #sources()} returns nothing until the binding lands.
 */
final class VendorSources {
    private static final String TAG = "VendorSources";
    private static final String MANAGER = "com.jamdeo.tv.SourceManager";

    /** A source value packs its type in the high byte and the port index in the low one. */
    static final int TYPE_MASK = ~0xFF;
    static final int TYPE_ATV = 0x100;
    static final int TYPE_DTV = 0x200;
    static final int TYPE_COMPONENT = 0x300;
    static final int TYPE_COMPOSITE = 0x400;
    static final int TYPE_HDMI = 0x500;
    static final int TYPE_SCART = 0x600;
    static final int TYPE_SVIDEO = 0x700;
    static final int TYPE_VGA = 0x800;
    static final int TYPE_CUSTOM = 0x900;
    static final int TYPE_ANDROID = 0xA00;
    static final int TYPE_PVR = 0xB00;

    private final Object manager;
    private final Method mSources;
    private final Method mName;
    private final Method mConnected;
    private final Method mStatus;
    private final Method mSelect;
    private final Method mCurrent;
    private final Method mIsConnected;

    private VendorSources(Object manager, Method sources, Method name, Method connected, Method status,
                          Method select, Method current, Method isConnected) {
        this.manager = manager;
        this.mSources = sources;
        this.mName = name;
        this.mConnected = connected;
        this.mStatus = status;
        this.mSelect = select;
        this.mCurrent = current;
        this.mIsConnected = isConnected;
    }

    /** Null when the set has no such library, or when it cannot be driven: callers then fall back to TvInputManager. */
    static VendorSources create(Context context) {
        try {
            Class<?> cls = Class.forName(MANAGER);
            // The library's manager classes take a package-private Context constructor (the vendor's own
            // apps live in that package), so the declared one is used and opened up.
            Constructor<?> ctor = cls.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);
            Object manager = ctor.newInstance(context.getApplicationContext());
            Method sources = cls.getMethod("getInputSources");
            Method name = cls.getMethod("getInputSourceName", int.class);
            Method connected = cls.getMethod("isSourceConnected", int.class);
            Method status = cls.getMethod("getInputSourceStatus", int.class);
            Method select = cls.getMethod("setCurrentInputSource", int.class);
            Method current = cls.getMethod("getCurrentInputSource");
            Method isConnected = cls.getMethod("isConnected");
            VendorSources api = new VendorSources(manager, sources, name, connected, status, select, current,
                    isConnected);
            Log.i(TAG, "source stack available");
            return api;
        } catch (Throwable t) {
            // NoSuchMethodError, ClassNotFound, SecurityException from a missing <uses-library>: all mean the
            // set has no vendor source stack to offer, which is not an error worth reporting.
            Log.i(TAG, "no vendor source stack: " + t);
            return null;
        }
    }

    /** True once the service binding is up, so a menu opened before that knows to say it is still connecting. */
    boolean ready() {
        try {
            return Boolean.TRUE.equals(mIsConnected.invoke(manager));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Every source the set offers, in the order the vendor service lists them, or an empty list. */
    List<Integer> sources() {
        try {
            Object result = mSources.invoke(manager);
            if (!(result instanceof List)) return Collections.emptyList();
            List<?> raw = (List<?>) result;
            List<Integer> ids = new ArrayList<>(raw.size());
            for (Object o : raw) if (o instanceof Integer) ids.add((Integer) o);
            return ids;
        } catch (Throwable t) {
            Log.w(TAG, "getInputSources failed: " + t);
            return Collections.emptyList();
        }
    }

    /** The set's own name for a source ("HDMI 1", "直播电视"); "UNKNOWN" when the service cannot say. */
    String label(int id) {
        try {
            Object result = mName.invoke(manager, id);
            if (result instanceof String && !((String) result).isEmpty()) return (String) result;
        } catch (Throwable t) {
            Log.w(TAG, "getInputSourceName failed for 0x" + Integer.toHexString(id) + ": " + t);
        }
        return null;
    }

    /** A definite plugged-in / not-plugged-in answer, or null when the set does not report one. */
    Boolean connected(int id) {
        Integer state = status(id);
        // InputSourceStatus: 1 connected, 4 HDMI signal, 5 DVI signal; 2 disconnected, 6 no signal. Any other
        // value (0 unknown, 7 mute finished, ...) says nothing about the jack, so the boolean answer is asked
        // for as well before giving up: a port with a device on it reports 0 on this set.
        if (state != null) {
            if (state == 1 || state == 4 || state == 5) return Boolean.TRUE;
            if (state == 2 || state == 6) return Boolean.FALSE;
        }
        try {
            Object result = mConnected.invoke(manager, id);
            if (result instanceof Boolean) {
                Log.i(TAG, "connected 0x" + Integer.toHexString(id) + " status " + state + " -> "
                        + result + " (boolean)");
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "connected 0x" + Integer.toHexString(id) + " status " + state + " -> unknown");
        return null;
    }

    /** The raw InputSourceStatus the service reports, or null when it cannot be read (see {@link #connected}). */
    Integer status(int id) {
        try {
            Object result = mStatus.invoke(manager, id);
            if (result instanceof Integer) return (Integer) result;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Switch the set to this source; false when the service refused or is not bound yet. */
    boolean select(int id) {
        try {
            Object result = mSelect.invoke(manager, id);
            Log.i(TAG, "select 0x" + Integer.toHexString(id) + " -> " + result);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            Log.w(TAG, "setCurrentInputSource failed: " + t);
            return false;
        }
    }

    /** The source on screen now, or -1 when it cannot be read. */
    int current() {
        try {
            Object result = mCurrent.invoke(manager);
            if (result instanceof Integer) return (Integer) result;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    static int typeOf(int id) {
        return id & TYPE_MASK;
    }
}
