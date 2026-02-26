package com.genymobile.scrcpy.control;

import com.genymobile.scrcpy.util.Ln;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

final class CursorOverlay {

    private static final int CURSOR_SIZE = 24;
    private static final int CURSOR_LAYER = Integer.MAX_VALUE - 100;

    private static final class Reflection {
        private final Class<?> surfaceControlClass;
        private final Constructor<?> builderConstructor;
        private final Constructor<?> transactionConstructor;

        private final Method builderSetName;
        private final Method builderSetBufferSize;
        private final Method builderSetFormat;
        private final Method builderSetOpaque;
        private final Method builderBuild;

        private final Method surfaceCopyFrom;
        private final Method surfaceControlRelease;

        private final Method transactionSetLayer;
        private final Method transactionSetPosition;
        private final Method transactionSetAlpha;
        private final Method transactionShow;
        private final Method transactionHide;
        private final Method transactionRemove;
        private final Method transactionApply;

        Reflection() throws ReflectiveOperationException {
            surfaceControlClass = Class.forName("android.view.SurfaceControl");
            Class<?> builderClass = Class.forName("android.view.SurfaceControl$Builder");
            Class<?> transactionClass = Class.forName("android.view.SurfaceControl$Transaction");

            builderConstructor = builderClass.getDeclaredConstructor();
            builderConstructor.setAccessible(true);
            transactionConstructor = transactionClass.getDeclaredConstructor();
            transactionConstructor.setAccessible(true);

            builderSetName = getMethod(builderClass, "setName", String.class);
            builderSetBufferSize = getMethod(builderClass, "setBufferSize", int.class, int.class);
            builderSetFormat = getOptionalMethod(builderClass, "setFormat", int.class);
            builderSetOpaque = getOptionalMethod(builderClass, "setOpaque", boolean.class);
            builderBuild = getMethod(builderClass, "build");

            surfaceCopyFrom = getMethod(Surface.class, "copyFrom", surfaceControlClass);
            surfaceControlRelease = getOptionalMethod(surfaceControlClass, "release");

            transactionSetLayer = getOptionalMethod(transactionClass, "setLayer", surfaceControlClass, int.class);
            transactionSetPosition = getMethod(transactionClass, "setPosition", surfaceControlClass, float.class, float.class);
            transactionSetAlpha = getOptionalMethod(transactionClass, "setAlpha", surfaceControlClass, float.class);
            transactionShow = getMethod(transactionClass, "show", surfaceControlClass);
            transactionHide = getMethod(transactionClass, "hide", surfaceControlClass);
            transactionRemove = getOptionalMethod(transactionClass, "remove", surfaceControlClass);
            transactionApply = getMethod(transactionClass, "apply");
        }

        private static Method getMethod(Class<?> clazz, String name, Class<?>... args) throws NoSuchMethodException {
            try {
                return clazz.getMethod(name, args);
            } catch (NoSuchMethodException e) {
                Method method = clazz.getDeclaredMethod(name, args);
                method.setAccessible(true);
                return method;
            }
        }

        private static Method getOptionalMethod(Class<?> clazz, String name, Class<?>... args) {
            try {
                return getMethod(clazz, name, args);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }

    private final Reflection reflection;

    private Object surfaceControl;
    private Surface surface;
    private boolean initialized;
    private boolean visible;
    private boolean disabled;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;

    CursorOverlay() {
        Reflection temp;
        try {
            temp = new Reflection();
        } catch (ReflectiveOperationException e) {
            temp = null;
            disabled = true;
            Ln.w("Cursor overlay disabled: SurfaceControl reflection unavailable", e);
        }
        reflection = temp;
    }

    public synchronized void show(int x, int y) {
        if (!ensureInitialized()) {
            return;
        }

        if (visible && lastX == x && lastY == y) {
            return;
        }

        if (!applyTransaction(x, y, true)) {
            return;
        }

        visible = true;
        lastX = x;
        lastY = y;
    }

    public synchronized void hide() {
        if (!initialized || !visible) {
            return;
        }

        if (applyTransaction(lastX, lastY, false)) {
            visible = false;
        }
    }

    public synchronized void release() {
        if (!initialized) {
            return;
        }

        hide();
        tryRemoveLayer();

        if (surface != null) {
            surface.release();
            surface = null;
        }

        if (surfaceControl != null && reflection.surfaceControlRelease != null) {
            try {
                reflection.surfaceControlRelease.invoke(surfaceControl);
            } catch (ReflectiveOperationException e) {
                Ln.w("Could not release cursor SurfaceControl", e);
            }
        }

        surfaceControl = null;
        initialized = false;
        visible = false;
        lastX = Integer.MIN_VALUE;
        lastY = Integer.MIN_VALUE;
    }

    private boolean ensureInitialized() {
        if (initialized) {
            return true;
        }

        if (disabled || reflection == null) {
            return false;
        }

        try {
            Object builder = reflection.builderConstructor.newInstance();
            reflection.builderSetName.invoke(builder, "scrcpy-cursor-overlay");
            reflection.builderSetBufferSize.invoke(builder, CURSOR_SIZE, CURSOR_SIZE);
            if (reflection.builderSetFormat != null) {
                reflection.builderSetFormat.invoke(builder, PixelFormat.RGBA_8888);
            }
            if (reflection.builderSetOpaque != null) {
                reflection.builderSetOpaque.invoke(builder, false);
            }
            surfaceControl = reflection.builderBuild.invoke(builder);

            surface = new Surface();
            reflection.surfaceCopyFrom.invoke(surface, surfaceControl);

            drawPointerOnce();
            initialized = true;
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.w("Cursor overlay disabled: could not initialize", e);
            disabled = true;
            return false;
        }
    }

    private void drawPointerOnce() {
        Canvas canvas = null;
        try {
            canvas = surface.lockCanvas(null);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.BLACK);

            Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeWidth(1.5f);
            outline.setColor(Color.WHITE);

            Path arrow = new Path();
            arrow.moveTo(2f, 2f);
            arrow.lineTo(2f, CURSOR_SIZE - 4f);
            arrow.lineTo(CURSOR_SIZE * 0.52f, CURSOR_SIZE * 0.64f);
            arrow.close();

            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, outline);
        } finally {
            if (canvas != null) {
                surface.unlockCanvasAndPost(canvas);
            }
        }
    }

    private boolean applyTransaction(int x, int y, boolean show) {
        try {
            Object transaction = reflection.transactionConstructor.newInstance();
            if (reflection.transactionSetLayer != null) {
                reflection.transactionSetLayer.invoke(transaction, surfaceControl, CURSOR_LAYER);
            }
            reflection.transactionSetPosition.invoke(transaction, surfaceControl, (float) x, (float) y);
            if (reflection.transactionSetAlpha != null) {
                reflection.transactionSetAlpha.invoke(transaction, surfaceControl, 1.0f);
            }
            if (show) {
                reflection.transactionShow.invoke(transaction, surfaceControl);
            } else {
                reflection.transactionHide.invoke(transaction, surfaceControl);
            }
            reflection.transactionApply.invoke(transaction);
            return true;
        } catch (ReflectiveOperationException e) {
            Ln.w("Cursor overlay update failed", e);
            return false;
        }
    }

    private void tryRemoveLayer() {
        if (surfaceControl == null || reflection.transactionRemove == null) {
            return;
        }

        try {
            Object transaction = reflection.transactionConstructor.newInstance();
            reflection.transactionRemove.invoke(transaction, surfaceControl);
            reflection.transactionApply.invoke(transaction);
        } catch (ReflectiveOperationException e) {
            Ln.w("Could not remove cursor overlay layer", e);
        }
    }
}
