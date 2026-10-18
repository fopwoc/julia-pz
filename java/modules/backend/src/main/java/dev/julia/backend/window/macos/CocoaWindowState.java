package dev.julia.backend.window.macos;

import org.lwjgl.system.JNI;
import org.lwjgl.system.macosx.ObjCRuntime;

/** The Cocoa state SDL's foreign-window listener changes, captured so GLFW ownership can be restored. */
final class CocoaWindowState {
    private static final long SEND = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
    private final long window;
    private final long view;
    private final long delegate;
    private final long windowResponder;
    private final long viewResponder;
    private final boolean mouseMoved;
    private final long touchTypes;
    private final boolean releasedWhenClosed;
    private final boolean oneShot;
    private final boolean highResolution;

    CocoaWindowState(long window) {
        if (window == 0) throw new IllegalArgumentException("Cocoa window is null");
        this.window = window;
        view = object(window, "contentView");
        if (view == 0) throw new IllegalStateException("Cocoa window has no content view");
        delegate = object(window, "delegate");
        windowResponder = object(window, "nextResponder");
        viewResponder = object(view, "nextResponder");
        mouseMoved = flag(window, "acceptsMouseMovedEvents");
        touchTypes = object(view, "allowedTouchTypes");
        releasedWhenClosed = flag(window, "isReleasedWhenClosed");
        oneShot = flag(window, "isOneShot");
        highResolution = flag(view, "wantsBestResolutionOpenGLSurface");
    }

    void restore() {
        if (object(window, "contentView") != view)
            throw new IllegalStateException("Foreign wrapper replaced the GLFW content view");
        setObject(window, "setDelegate:", delegate);
        setObject(window, "setNextResponder:", windowResponder);
        setObject(view, "setNextResponder:", viewResponder);
        setFlag(window, "setAcceptsMouseMovedEvents:", mouseMoved);
        setObject(view, "setAllowedTouchTypes:", touchTypes);
        setFlag(window, "setReleasedWhenClosed:", releasedWhenClosed);
        setFlag(window, "setOneShot:", oneShot);
        setFlag(view, "setWantsBestResolutionOpenGLSurface:", highResolution);
    }

    void verify() {
        if (object(window, "contentView") != view
                || object(window, "delegate") != delegate
                || object(window, "nextResponder") != windowResponder
                || object(view, "nextResponder") != viewResponder
                || flag(window, "acceptsMouseMovedEvents") != mouseMoved
                || object(view, "allowedTouchTypes") != touchTypes
                || flag(window, "isReleasedWhenClosed") != releasedWhenClosed
                || flag(window, "isOneShot") != oneShot
                || flag(view, "wantsBestResolutionOpenGLSurface") != highResolution) {
            throw new IllegalStateException("GLFW Cocoa ownership was not restored");
        }
    }

    private static long object(long target, String selector) {
        return JNI.invokePPP(target, ObjCRuntime.sel_registerName(selector), SEND);
    }

    private static boolean flag(long target, String selector) {
        return JNI.invokePPZ(target, ObjCRuntime.sel_registerName(selector), SEND);
    }

    private static void setObject(long target, String selector, long value) {
        JNI.invokePPPV(target, ObjCRuntime.sel_registerName(selector), value, SEND);
    }

    private static void setFlag(long target, String selector, boolean value) {
        JNI.invokePPV(target, ObjCRuntime.sel_registerName(selector), value, SEND);
    }
}
