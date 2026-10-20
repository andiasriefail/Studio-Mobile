package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The preview draws AppCompat/Material widgets as their framework equivalents, which ignore the app: attributes.
 * This reads the most visible ones through the project's own resources and applies them:
 * MaterialButton (backgroundTint, cornerRadius, strokeColor/strokeWidth, icon), CardView/MaterialCardView (corner radius, elevation, color, stroke, padding),
 * and srcCompat / tint / backgroundTint on image views and any other view. Nothing here is a guess: an attribute that is not written is left alone.
 */
final class PreviewWidgets {
    private PreviewWidgets() { }

    static void style(View view, String className, AttributeSet attrs, Context context, Resources resources, String[] packages) {
        try {
            Reader r = new Reader(attrs, context, resources, packages);
            if (className.endsWith("MaterialButton") && view instanceof Button) button((Button) view, r);
            else if (className.endsWith("CardView") && view instanceof FrameLayout) card((FrameLayout) view, r);
            if (view instanceof ImageView) image((ImageView) view, r);
            ColorStateList tint = r.color("backgroundTint");
            if (tint != null && !className.endsWith("MaterialButton")) view.setBackgroundTintList(tint);
        } catch (Throwable ignored) { /* a style that cannot be read leaves the plain widget */ }
    }

    private static void button(Button button, Reader r) {
        ColorStateList tint = r.color("backgroundTint"), stroke = r.color("strokeColor"), icon = r.color("iconTint");
        float radius = r.dimension("cornerRadius", -1), strokeWidth = r.dimension("strokeWidth", 0);
        Drawable iconDrawable = r.drawable("icon");
        if (tint != null || radius >= 0 || stroke != null) {
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(tint != null ? tint : ColorStateList.valueOf(Color.TRANSPARENT));
            shape.setCornerRadius(radius >= 0 ? radius : 4 * button.getResources().getDisplayMetrics().density);
            if (stroke != null) shape.setStroke(Math.max(1, Math.round(strokeWidth > 0 ? strokeWidth : button.getResources().getDisplayMetrics().density)), stroke);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE); mask.setCornerRadius(shape.getCornerRadius());
            ColorStateList ripple = ColorStateList.valueOf(0x33000000);
            button.setBackground(new RippleDrawable(ripple, shape, mask));
        }
        if (iconDrawable != null) {
            Drawable d = iconDrawable.mutate();
            if (icon != null) d.setTintList(icon);
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
            button.setCompoundDrawablePadding(Math.round(r.dimension("iconPadding", 8 * button.getResources().getDisplayMetrics().density)));
        }
    }

    private static void card(FrameLayout card, Reader r) {
        final float radius = r.dimension("cardCornerRadius", 0), elevation = r.dimension("cardElevation", -1), padding = r.dimension("contentPadding", -1);
        ColorStateList fill = r.color("cardBackgroundColor"), stroke = r.color("strokeColor");
        float strokeWidth = r.dimension("strokeWidth", 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill != null ? fill : ColorStateList.valueOf(Color.WHITE));
        shape.setCornerRadius(radius);
        if (stroke != null && strokeWidth > 0) shape.setStroke(Math.round(strokeWidth), stroke);
        card.setBackground(shape);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) { outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        if (elevation >= 0) card.setElevation(elevation);
        if (padding >= 0) { int p = Math.round(padding); card.setPadding(p, p, p, p); }
    }

    private static void image(ImageView image, Reader r) {
        Drawable src = r.drawable("srcCompat");
        if (src != null) image.setImageDrawable(src);
        ColorStateList tint = r.color("tint");
        if (tint != null) image.setImageTintList(tint);
    }

    /** Reads one app: attribute by name through the project's compiled resources (themes, state lists and @references resolve as in the app). */
    static final class Reader {
        private final AttributeSet attrs; private final Context context; private final Resources resources; private final String[] packages;
        Reader(AttributeSet attrs, Context context, Resources resources, String[] packages) { this.attrs = attrs; this.context = context; this.resources = resources; this.packages = packages; }

        private TypedArray array(String name) {
            int id = ProjectPreview.findIdentifier(resources, name, "attr", packages);
            if (id == 0) return null;
            TypedArray a = context.obtainStyledAttributes(attrs, new int[] {id});
            if (a.hasValue(0)) return a;
            a.recycle();
            return null;
        }
        ColorStateList color(String name) { TypedArray a = array(name); if (a == null) return null; try { return a.getColorStateList(0); } finally { a.recycle(); } }
        Drawable drawable(String name) { TypedArray a = array(name); if (a == null) return null; try { return a.getDrawable(0); } finally { a.recycle(); } }
        float dimension(String name, float fallback) { TypedArray a = array(name); if (a == null) return fallback; try { return a.getDimension(0, fallback); } finally { a.recycle(); } }
        String string(String name) { TypedArray a = array(name); if (a == null) return null; try { return a.getString(0); } finally { a.recycle(); } }
        int integer(String name, int fallback) { TypedArray a = array(name); if (a == null) return fallback; try { return a.getInt(0, fallback); } finally { a.recycle(); } }
        float number(String name, float fallback) { TypedArray a = array(name); if (a == null) return fallback; try { return a.getFloat(0, fallback); } finally { a.recycle(); } }
        /** The resource id an @id/... or @layout/... value points to; {@code absent} when the attribute is not written, {@code notReference} when it holds a plain value (an enum such as "parent"). */
        int reference(String name, int absent, int notReference) { TypedArray a = array(name); if (a == null) return absent; try { return a.getResourceId(0, notReference); } finally { a.recycle(); } }
    }
}
