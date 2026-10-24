package com.example.studiomobile.workspace;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ConstraintLayout for the preview. The real class reads its attributes through Studio Mobile's own resource ids, which do not match the
 * project's compiled resources, so it cannot be inflated here. This one reads the same layout_constraint* attributes by name (see
 * {@link PreviewWidgets.Reader}) and solves them: parent / sibling / guideline anchors, bias, centering, 0dp (match constraint), percent sizes,
 * dimension ratio, baseline alignment and spread / spread_inside / packed chains with weights.
 * Barrier (constraint_referenced_ids, barrierDirection, barrierMargin) and Group (visibility applied to the referenced views) are supported.
 * Flow (simple version) lays out the views it refers to: flow_wrapMode none / chain / aligned, android:orientation, flow_horizontalGap / flow_verticalGap,
 * flow_maxElementsWrap, flow_horizontalStyle / flow_verticalStyle and the biases. Views of a flow are placed by the flow, not by their own constraints.
 * Not covered: flow_horizontalAlign / flow_verticalAlign (always centered), flow_first* / flow_last* styles, circular positioning and min/max constraints;
 * such views do not move other views.
 */
final class PreviewConstraint {
    private PreviewConstraint() { }

    static final int PARENT = 0, UNSET = -1;
    private static final int NONE = Integer.MIN_VALUE + 1, WAIT = Integer.MIN_VALUE;

    // ------------------------------------------------------------------ guideline

    /** Invisible helper line; its position is computed by {@link Layout}. */
    static final class Guide extends View {
        final boolean vertical;

        Guide(Context context, AttributeSet attrs) {
            super(context, attrs);
            int orientation = 0;
            TypedArray a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.orientation});
            try { orientation = a.getInt(0, 0); } finally { a.recycle(); }
            vertical = orientation == 1;
            setVisibility(GONE);
        }
    }

    // ------------------------------------------------------------------ barrier / group

    /**
     * Barrier or Group: an invisible helper that only carries the ids it refers to. A barrier is read as a line by {@link Layout#anchor};
     * a group hands its own visibility to the views it refers to. The helper itself never takes space.
     */
    static final class Helper extends View {
        // barrierDirection values of the library's attrs.xml
        private static final int LEFT = 0, RIGHT = 1, TOP = 2, BOTTOM = 3, START = 5, END = 6;
        private static final String AUTO_NS = "http://schemas.android.com/apk/res-auto";

        final boolean barrier;
        final int groupVisibility;
        int margin;
        private int direction = -1;
        private final String[] names;
        private final PreviewStandIns.Env env;
        private int[] ids;

        Helper(Context context, AttributeSet attrs, PreviewStandIns.Env env, boolean barrier) {
            super(context, attrs);
            this.env = env;
            this.barrier = barrier;
            this.groupVisibility = getVisibility();   // what a group passes on; read before the helper hides itself
            setVisibility(GONE);
            String list = null;
            try {
                PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
                list = r.string("constraint_referenced_ids");
                if (barrier) {
                    margin = Math.round(r.dimension("barrierMargin", 0f));
                    direction = direction(attrs, r);
                }
            } catch (RuntimeException ignored) { /* an attribute that cannot be read leaves the helper inert */ }
            names = list == null || list.trim().isEmpty() ? new String[0] : list.trim().split("\\s*,\\s*");
        }

        private static int direction(AttributeSet attrs, PreviewWidgets.Reader r) {
            String raw = null;
            try { raw = attrs.getAttributeValue(AUTO_NS, "barrierDirection"); } catch (RuntimeException ignored) { }
            if (raw != null) {
                switch (raw.trim()) {
                    case "left": return LEFT;
                    case "right": return RIGHT;
                    case "top": return TOP;
                    case "bottom": return BOTTOM;
                    case "start": return START;
                    case "end": return END;
                    default:
                        try { return Integer.parseInt(raw.trim()); } catch (NumberFormatException ignored) { }
                }
            }
            return r.integer("barrierDirection", -1);
        }

        /** 0 = horizontal (left / right / start / end), 1 = vertical (top / bottom), -1 = unknown direction. */
        int axis() {
            if (direction == LEFT || direction == RIGHT || direction == START || direction == END) return 0;
            if (direction == TOP || direction == BOTTOM) return 1;
            return -1;
        }

        /** True when the barrier sits after its views (right / bottom / end), false when it sits before them. */
        boolean atEnd() { return direction == RIGHT || direction == BOTTOM || direction == END; }

        int[] referencedIds() {
            if (ids == null) {
                int[] found = new int[names.length];
                int count = 0;
                for (String name : names) {
                    if (name.isEmpty()) continue;
                    int id = ProjectPreview.findIdentifier(env.resources, name, "id", env.packages);
                    if (id != 0) found[count++] = id;
                }
                ids = Arrays.copyOf(found, count);
            }
            return ids;
        }
    }

    // ------------------------------------------------------------------ flow

    /** Flow: a box that is solved like any view; {@link Layout#placeFlows} then places the views it refers to inside that box. */
    static final class FlowHelper extends View {
        static final int WRAP_NONE = 0, WRAP_CHAIN = 1, WRAP_ALIGNED = 2;
        private static final String AUTO_NS = "http://schemas.android.com/apk/res-auto";
        private static final String[] WRAP_MODES = {"none", "chain", "aligned"};
        private static final String[] STYLES = {"spread", "spread_inside", "packed"};

        int orientation;                  // 0 = horizontal, 1 = vertical
        int wrapMode = WRAP_NONE;
        int maxElements = -1;
        final int[] gap = {0, 0};         // [0] = flow_horizontalGap, [1] = flow_verticalGap
        final int[] style = {0, 0};       // 0 spread, 1 spread_inside, 2 packed
        final float[] bias = {0.5f, 0.5f};
        private final String[] names;
        private final PreviewStandIns.Env env;
        private int[] ids;

        FlowHelper(Context context, AttributeSet attrs, PreviewStandIns.Env env) {
            super(context, attrs);
            this.env = env;
            TypedArray a = context.obtainStyledAttributes(attrs, new int[] {android.R.attr.orientation});
            try { orientation = a.getInt(0, 0) == 1 ? 1 : 0; } finally { a.recycle(); }
            String list = null;
            try {
                PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
                list = r.string("constraint_referenced_ids");
                wrapMode = choice(attrs, r, "flow_wrapMode", WRAP_MODES);
                style[0] = choice(attrs, r, "flow_horizontalStyle", STYLES);
                style[1] = choice(attrs, r, "flow_verticalStyle", STYLES);
                gap[0] = Math.round(r.dimension("flow_horizontalGap", 0f));
                gap[1] = Math.round(r.dimension("flow_verticalGap", 0f));
                bias[0] = r.number("flow_horizontalBias", 0.5f);
                bias[1] = r.number("flow_verticalBias", 0.5f);
                maxElements = r.integer("flow_maxElementsWrap", -1);
            } catch (RuntimeException ignored) { /* an attribute that cannot be read is left at its default */ }
            names = list == null || list.trim().isEmpty() ? new String[0] : list.trim().split("\\s*,\\s*");
        }

        /** An enum attribute: the name as written in the XML first, else its number (the position in {@code names}), else the first entry. */
        private static int choice(AttributeSet attrs, PreviewWidgets.Reader r, String attr, String[] names) {
            String raw = null;
            try { raw = attrs.getAttributeValue(AUTO_NS, attr); } catch (RuntimeException ignored) { }
            if (raw != null) {
                String text = raw.trim();
                for (int i = 0; i < names.length; i++) if (names[i].equals(text)) return i;
                try { int number = Integer.parseInt(text); if (number >= 0 && number < names.length) return number; } catch (NumberFormatException ignored) { }
            }
            return Math.max(0, Math.min(names.length - 1, r.integer(attr, 0)));
        }

        int[] referencedIds() {
            if (ids == null) {
                int[] found = new int[names.length];
                int count = 0;
                for (String name : names) {
                    if (name.isEmpty()) continue;
                    int id = ProjectPreview.findIdentifier(env.resources, name, "id", env.packages);
                    if (id != 0) found[count++] = id;
                }
                ids = Arrays.copyOf(found, count);
            }
            return ids;
        }
    }

    // ------------------------------------------------------------------ layout params

    static final class LP extends ViewGroup.MarginLayoutParams {
        // [0] = horizontal, [1] = vertical. sToE: this start to the END of the target, and so on.
        final int[] sToS = {UNSET, UNSET}, sToE = {UNSET, UNSET}, eToS = {UNSET, UNSET}, eToE = {UNSET, UNSET};
        int baseTarget = UNSET;
        final float[] bias = {0.5f, 0.5f}, weight = {-1f, -1f}, percent = {-1f, -1f};
        final int[] chain = {0, 0};
        float ratio = -1f, guideBegin = -1f, guideEnd = -1f, guidePercent = -1f;

        LP(Context context, AttributeSet attrs, PreviewStandIns.Env env) {
            super(context, attrs);
            try {
                PreviewWidgets.Reader r = new PreviewWidgets.Reader(attrs, context, env.resources, env.packages);
                link(r, sToS, 0, "layout_constraintLeft_toLeftOf", "layout_constraintStart_toStartOf");
                link(r, sToE, 0, "layout_constraintLeft_toRightOf", "layout_constraintStart_toEndOf");
                link(r, eToS, 0, "layout_constraintRight_toLeftOf", "layout_constraintEnd_toStartOf");
                link(r, eToE, 0, "layout_constraintRight_toRightOf", "layout_constraintEnd_toEndOf");
                link(r, sToS, 1, "layout_constraintTop_toTopOf", null);
                link(r, sToE, 1, "layout_constraintTop_toBottomOf", null);
                link(r, eToS, 1, "layout_constraintBottom_toTopOf", null);
                link(r, eToE, 1, "layout_constraintBottom_toBottomOf", null);
                baseTarget = r.reference("layout_constraintBaseline_toBaselineOf", UNSET, PARENT);
                bias[0] = r.number("layout_constraintHorizontal_bias", 0.5f);
                bias[1] = r.number("layout_constraintVertical_bias", 0.5f);
                weight[0] = r.number("layout_constraintHorizontal_weight", -1f);
                weight[1] = r.number("layout_constraintVertical_weight", -1f);
                percent[0] = r.number("layout_constraintWidth_percent", -1f);
                percent[1] = r.number("layout_constraintHeight_percent", -1f);
                chain[0] = r.integer("layout_constraintHorizontal_chainStyle", 0);
                chain[1] = r.integer("layout_constraintVertical_chainStyle", 0);
                ratio = parseRatio(r.string("layout_constraintDimensionRatio"));
                guideBegin = r.dimension("layout_constraintGuide_begin", -1f);
                guideEnd = r.dimension("layout_constraintGuide_end", -1f);
                guidePercent = r.number("layout_constraintGuide_percent", -1f);
            } catch (RuntimeException ignored) { /* an attribute that cannot be read is left at its default */ }
        }

        LP(int width, int height) { super(width, height); }
        LP(ViewGroup.MarginLayoutParams source) { super(source); }
        LP(ViewGroup.LayoutParams source) { super(source); }

        private static void link(PreviewWidgets.Reader r, int[] target, int axis, String first, String second) {
            int v = r.reference(first, UNSET, PARENT);
            if (v == UNSET && second != null) v = r.reference(second, UNSET, PARENT);
            target[axis] = v;
        }
    }

    /** "16:9", "H,16:9", "W,1:1" or "1.5" as width / height; -1 when absent or unreadable. */
    static float parseRatio(String text) {
        if (text == null) return -1f;
        String s = text.trim();
        int comma = s.indexOf(',');
        if (comma >= 0) s = s.substring(comma + 1).trim();
        try {
            int colon = s.indexOf(':');
            if (colon > 0) {
                float w = Float.parseFloat(s.substring(0, colon).trim()), h = Float.parseFloat(s.substring(colon + 1).trim());
                return h > 0f && w > 0f ? w / h : -1f;
            }
            float value = Float.parseFloat(s);
            return value > 0f ? value : -1f;
        } catch (NumberFormatException e) { return -1f; }
    }

    // ------------------------------------------------------------------ layout

    static final class Layout extends ViewGroup {
        private final PreviewStandIns.Env env;
        private int pw, ph;
        private boolean heightFree;
        private int[][] pos0, pos1, size, wrap;
        private boolean[][] done;
        private int[] guideAxis;
        private int[] rects;
        private int[] flowOwner;      // per child: index of the flow that places it, else -1
        private int[][] flowItems;    // per child that is a flow: indices of the views it places, in the order of its ids

        Layout(Context context, AttributeSet attrs, PreviewStandIns.Env env) {
            super(context, attrs);
            this.env = env;
        }

        @Override public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) { return new LP(getContext(), attrs, env); }
        @Override protected ViewGroup.LayoutParams generateDefaultLayoutParams() { return new LP(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
        @Override protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
            return p instanceof ViewGroup.MarginLayoutParams ? new LP((ViewGroup.MarginLayoutParams) p) : new LP(p);
        }
        @Override protected boolean checkLayoutParams(ViewGroup.LayoutParams p) { return p instanceof LP; }

        // ---- measure / layout

        private boolean groupsApplied;

        @Override protected void onFinishInflate() {
            super.onFinishInflate();
            applyGroups();
        }

        /** Like the library, a group sets its own visibility on every view it refers to; done once, before the first layout. */
        private void applyGroups() {
            if (groupsApplied) return;
            groupsApplied = true;
            try {
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (!(v instanceof Helper) || ((Helper) v).barrier) continue;
                    for (int id : ((Helper) v).referencedIds()) {
                        int j = indexOf(id);
                        if (j < 0 || j == i) continue;
                        View target = getChildAt(j);
                        if (target instanceof Helper || target instanceof Guide) continue;
                        target.setVisibility(((Helper) v).groupVisibility);
                    }
                }
            } catch (RuntimeException ignored) { /* a group that cannot be applied leaves its views as written */ }
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            applyGroups();
            int wMode = MeasureSpec.getMode(widthSpec), hMode = MeasureSpec.getMode(heightSpec);
            int wSize = MeasureSpec.getSize(widthSpec), hSize = MeasureSpec.getSize(heightSpec);
            int width = wMode == MeasureSpec.UNSPECIFIED ? getResources().getDisplayMetrics().widthPixels : wSize;
            int height = hMode == MeasureSpec.UNSPECIFIED ? 0 : hSize;
            try {
                solve(width, height, hMode == MeasureSpec.UNSPECIFIED);
                if (wMode != MeasureSpec.EXACTLY || hMode != MeasureSpec.EXACTLY) {
                    int right = 0, bottom = 0;
                    for (int i = 0; i < getChildCount(); i++) {
                        View v = getChildAt(i);
                        if (v.getVisibility() == GONE || guideAxis[i] >= 0) continue;
                        LP lp = (LP) v.getLayoutParams();
                        right = Math.max(right, rects[i * 4 + 2] + marginEnd(0, v, lp));
                        bottom = Math.max(bottom, rects[i * 4 + 3] + marginEnd(1, v, lp));
                    }
                    int mw = width, mh = height;
                    if (wMode != MeasureSpec.EXACTLY) {
                        mw = Math.max(getSuggestedMinimumWidth(), right + getPaddingRight());
                        if (wMode == MeasureSpec.AT_MOST) mw = Math.min(mw, wSize);
                    }
                    if (hMode != MeasureSpec.EXACTLY) {
                        mh = Math.max(getSuggestedMinimumHeight(), bottom + getPaddingBottom());
                        if (hMode == MeasureSpec.AT_MOST) mh = Math.min(mh, hSize);
                    }
                    if (mw != width || mh != height) solve(mw, mh, false);   // bottom/right anchors now see the real size
                    width = mw; height = mh;
                }
            } catch (RuntimeException failure) {
                height = stack(width, height, hMode == MeasureSpec.UNSPECIFIED);
            }
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int n = getChildCount();
            if (rects == null || rects.length != n * 4) return;
            for (int i = 0; i < n; i++) getChildAt(i).layout(rects[i * 4], rects[i * 4 + 1], rects[i * 4 + 2], rects[i * 4 + 3]);
        }

        /** Last resort if the solver fails: every view at the top-left corner, so something is still visible. */
        private int stack(int width, int height, boolean heightFree) {
            int n = getChildCount();
            rects = new int[n * 4];
            guideAxis = new int[n];
            Arrays.fill(guideAxis, -1);
            int bottom = 0;
            for (int i = 0; i < n; i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, width - getPaddingLeft() - getPaddingRight()), MeasureSpec.AT_MOST),
                    heightFree ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) : MeasureSpec.makeMeasureSpec(Math.max(0, height), MeasureSpec.AT_MOST));
                rects[i * 4] = getPaddingLeft(); rects[i * 4 + 1] = getPaddingTop();
                rects[i * 4 + 2] = rects[i * 4] + v.getMeasuredWidth(); rects[i * 4 + 3] = rects[i * 4 + 1] + v.getMeasuredHeight();
                bottom = Math.max(bottom, rects[i * 4 + 3]);
            }
            return heightFree ? bottom + getPaddingBottom() : height;
        }

        // ---- solver

        private void solve(int width, int height, boolean heightFree) {
            this.pw = width; this.ph = height; this.heightFree = heightFree;
            int n = getChildCount();
            pos0 = new int[2][n]; pos1 = new int[2][n]; size = new int[2][n]; wrap = new int[2][n]; done = new boolean[2][n];
            guideAxis = new int[n];
            for (int[] row : size) Arrays.fill(row, -1);
            for (int i = 0; i < n; i++) {
                View v = getChildAt(i);
                guideAxis[i] = v instanceof Guide ? (((Guide) v).vertical ? 0 : 1) : -1;
            }
            findFlowItems();
            placeGuides();
            measureWrapWidths();
            flowWrapSizes(0);
            resolveAxis(0, true);
            measureWrapHeights();
            flowWrapSizes(1);
            resolveAxis(1, false);
            resolveAxis(0, false);   // widths that had to wait for a height (dimension ratio)
            placeFlows();
            rects = new int[n * 4];
            for (int i = 0; i < n; i++) {
                View v = getChildAt(i);
                if (guideAxis[i] >= 0) {
                    int x = guideAxis[i] == 0 ? pos0[0][i] : 0, y = guideAxis[i] == 1 ? pos0[1][i] : 0;
                    rects[i * 4] = x; rects[i * 4 + 1] = y; rects[i * 4 + 2] = x; rects[i * 4 + 3] = y;
                    continue;
                }
                int w = Math.max(0, size[0][i]), h = Math.max(0, size[1][i]);
                if (v.getVisibility() != GONE) v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                rects[i * 4] = pos0[0][i]; rects[i * 4 + 1] = pos0[1][i];
                rects[i * 4 + 2] = rects[i * 4] + w; rects[i * 4 + 3] = rects[i * 4 + 1] + h;
            }
        }

        private void placeGuides() {
            for (int i = 0; i < getChildCount(); i++) {
                int axis = guideAxis[i];
                if (axis < 0) continue;
                LP lp = lp(i);
                int span = parentSize(axis) - padStart(axis) - padEnd(axis), p;
                if (lp.guideBegin >= 0f) p = padStart(axis) + Math.round(lp.guideBegin);
                else if (lp.guideEnd >= 0f) p = parentSize(axis) - padEnd(axis) - Math.round(lp.guideEnd);
                else if (lp.guidePercent >= 0f) p = padStart(axis) + Math.round(span * lp.guidePercent);
                else p = padStart(axis);
                pos0[axis][i] = p; pos1[axis][i] = p; size[axis][i] = 0; done[axis][i] = true;
                int other = 1 - axis;
                pos0[other][i] = 0; pos1[other][i] = 0; size[other][i] = 0; done[other][i] = true;
            }
        }

        private void measureWrapWidths() {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE || guideAxis[i] >= 0) continue;
                LP lp = lp(i);
                int avail = Math.max(0, pw - getPaddingLeft() - getPaddingRight() - marginStart(0, v, lp) - marginEnd(0, v, lp));
                v.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                wrap[0][i] = v.getMeasuredWidth();
            }
        }

        private void measureWrapHeights() {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE || guideAxis[i] >= 0) continue;
                LP lp = lp(i);
                int availW = Math.max(0, pw - getPaddingLeft() - getPaddingRight() - marginStart(0, v, lp) - marginEnd(0, v, lp));
                int availH = Math.max(0, ph - getPaddingTop() - getPaddingBottom() - marginStart(1, v, lp) - marginEnd(1, v, lp));
                int ws = size[0][i] >= 0 ? MeasureSpec.makeMeasureSpec(size[0][i], MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST);
                int hs = heightFree ? MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) : MeasureSpec.makeMeasureSpec(availH, MeasureSpec.AT_MOST);
                v.measure(ws, hs);
                wrap[1][i] = v.getMeasuredHeight();
            }
        }

        /** Resolves every view along one axis: first everything whose anchors are known, then, for cycles, whatever is left. */
        private void resolveAxis(int a, boolean skipDeferred) {
            int n = getChildCount();
            int[] next = new int[n], prev = new int[n];
            Arrays.fill(next, -1); Arrays.fill(prev, -1);
            for (int i = 0; i < n; i++) {
                if (done[a][i] || guideAxis[i] >= 0 || flowOwner[i] >= 0) continue;
                int id = getChildAt(i).getId();
                int j = indexOf(lp(i).eToS[a]);
                if (id != UNSET && j >= 0 && j != i && !done[a][j] && flowOwner[j] < 0 && lp(j).sToE[a] == id) { next[i] = j; prev[j] = i; }
            }
            while (true) {
                boolean pending = false, progress = false;
                for (int i = 0; i < n; i++) {
                    if (done[a][i] || flowOwner[i] >= 0 || prev[i] != -1 || (skipDeferred && deferred(a, i))) continue;
                    pending = true;
                    if (next[i] != -1 ? resolveChain(a, i, next, true) : resolveSingle(a, i, true)) progress = true;
                }
                if (!pending) break;
                if (!progress) {
                    for (int i = 0; i < n; i++) {
                        if (done[a][i] || flowOwner[i] >= 0 || prev[i] != -1 || (skipDeferred && deferred(a, i))) continue;
                        if (next[i] != -1) resolveChain(a, i, next, false); else resolveSingle(a, i, false);
                        break;
                    }
                }
            }
            for (int i = 0; i < n; i++) if (!done[a][i] && guideAxis[i] < 0 && flowOwner[i] < 0 && !(skipDeferred && deferred(a, i))) resolveSingle(a, i, false);
        }

        /** A width given by the height (0dp + ratio, not anchored on both sides) is computed after the heights are known. */
        private boolean deferred(int a, int i) {
            if (a != 0) return false;
            LP lp = lp(i);
            if (!(lp.ratio > 0f && lp.width == 0 && lp.height != 0 && lp.percent[0] <= 0f)) return false;
            return !(sideAnchor(0, lp, true) != NONE && sideAnchor(0, lp, false) != NONE);
        }

        private boolean resolveSingle(int a, int i, boolean strict) {
            View v = getChildAt(i);
            LP lp = lp(i);
            int s = sideAnchor(a, lp, true), e = sideAnchor(a, lp, false);
            int baselineY = NONE;
            if (a == 1 && lp.baseTarget != UNSET) {
                int j = indexOf(lp.baseTarget);
                if (j >= 0 && j != i) {
                    if (!done[1][j]) { if (strict) return false; }
                    else baselineY = pos0[1][j] + Math.max(0, getChildAt(j).getBaseline()) - Math.max(0, v.getBaseline());
                }
            }
            if (strict && (s == WAIT || e == WAIT)) return false;
            if (s == WAIT) s = NONE;
            if (e == WAIT) e = NONE;
            int mS = marginStart(a, v, lp), mE = marginEnd(a, v, lp);
            int sz = sizeFor(a, i, lp, s, e, mS, mE);
            int x;
            if (baselineY != NONE) x = baselineY;
            else if (s != NONE && e != NONE) { int lo = s + mS, hi = e - mE; x = lo + Math.round((hi - lo - sz) * lp.bias[a]); }
            else if (s != NONE) x = s + mS;
            else if (e != NONE) x = e - mE - sz;
            else x = padStart(a) + mS;
            pos0[a][i] = x; pos1[a][i] = x + sz; size[a][i] = sz; done[a][i] = true;
            return true;
        }

        private boolean resolveChain(int a, int head, int[] next, boolean strict) {
            List<Integer> members = new ArrayList<>();
            for (int k = head, guard = 0; k != -1 && guard <= getChildCount(); k = next[k], guard++) members.add(k);
            int count = members.size();
            LP headLp = lp(head), tailLp = lp(members.get(count - 1));
            int s = sideAnchor(a, headLp, true), e = sideAnchor(a, tailLp, false);
            if (strict && (s == WAIT || e == WAIT)) return false;
            if (s == WAIT) s = NONE;
            if (e == WAIT) e = NONE;
            int[] sz = new int[count];
            int occupied = 0;
            float weightSum = 0f;
            for (int m = 0; m < count; m++) {
                int idx = members.get(m);
                View v = getChildAt(idx);
                LP lp = lp(idx);
                int mS = marginStart(a, v, lp), mE = marginEnd(a, v, lp);
                int dim = a == 0 ? lp.width : lp.height;
                occupied += mS + mE;
                if (v.getVisibility() == GONE) sz[m] = 0;
                else if (dim == 0 && lp.percent[a] <= 0f) { sz[m] = -1; weightSum += lp.weight[a] > 0f ? lp.weight[a] : 1f; }
                else { sz[m] = sizeFor(a, idx, lp, NONE, NONE, mS, mE); occupied += sz[m]; }
            }
            int lo = s != NONE ? s : padStart(a);
            boolean bounded = e != NONE;
            int free = bounded ? Math.max(0, (e - lo) - occupied) : 0;
            if (weightSum > 0f) {
                for (int m = 0; m < count; m++) {
                    if (sz[m] >= 0) continue;
                    LP lp = lp(members.get(m));
                    sz[m] = Math.round(free * (lp.weight[a] > 0f ? lp.weight[a] : 1f) / weightSum);
                }
                free = 0;
            } else {
                for (int m = 0; m < count; m++) if (sz[m] < 0) sz[m] = 0;
            }
            int style = headLp.chain[a];
            float start, between;
            if (!bounded) { start = 0f; between = 0f; }
            else if (style == 1 && count > 1) { start = 0f; between = free / (float) (count - 1); }
            else if (style == 1 || style == 2) { start = free * headLp.bias[a]; between = 0f; }
            else { float gap = free / (float) (count + 1); start = gap; between = gap; }
            float x = lo + start;
            for (int m = 0; m < count; m++) {
                int idx = members.get(m);
                View v = getChildAt(idx);
                LP lp = lp(idx);
                int mS = marginStart(a, v, lp), mE = marginEnd(a, v, lp);
                int p0 = Math.round(x) + mS;
                pos0[a][idx] = p0; pos1[a][idx] = p0 + sz[m]; size[a][idx] = sz[m]; done[a][idx] = true;
                x += mS + sz[m] + mE + between;
            }
            return true;
        }

        private int sizeFor(int a, int i, LP lp, int s, int e, int mS, int mE) {
            if (getChildAt(i).getVisibility() == GONE) return 0;
            int dim = a == 0 ? lp.width : lp.height;
            int inner = parentSize(a) - padStart(a) - padEnd(a);
            if (dim > 0) return dim;
            if (dim == ViewGroup.LayoutParams.WRAP_CONTENT) return wrap[a][i];
            if (dim == ViewGroup.LayoutParams.MATCH_PARENT) return Math.max(0, inner - mS - mE);
            // 0dp: match constraint
            if (lp.percent[a] > 0f) return Math.max(0, Math.round(lp.percent[a] * inner));
            if (s != NONE && e != NONE) return Math.max(0, e - s - mS - mE);
            if (lp.ratio > 0f && size[1 - a][i] >= 0) return a == 0 ? Math.round(size[1][i] * lp.ratio) : Math.round(size[0][i] / lp.ratio);
            return wrap[a][i];
        }

        // ---- flow

        /** Which views each flow places: visible views of its id list that no earlier flow took. */
        private void findFlowItems() {
            int n = getChildCount();
            flowOwner = new int[n];
            Arrays.fill(flowOwner, -1);
            flowItems = new int[n][];
            for (int f = 0; f < n; f++) {
                View fv = getChildAt(f);
                if (!(fv instanceof FlowHelper) || fv.getVisibility() == GONE) continue;
                int[] ids = ((FlowHelper) fv).referencedIds();
                int[] found = new int[ids.length];
                int count = 0;
                for (int id : ids) {
                    int k = indexOf(id);
                    if (k < 0 || k == f || flowOwner[k] >= 0) continue;
                    View v = getChildAt(k);
                    if (v.getVisibility() == GONE || v instanceof Helper || v instanceof FlowHelper || v instanceof Guide) continue;
                    flowOwner[k] = f;
                    found[count++] = k;
                }
                flowItems[f] = Arrays.copyOf(found, count);
            }
        }

        /** The content size of every flow on axis {@code a}, used when the flow is wrap_content. */
        private void flowWrapSizes(int a) {
            for (int f = 0; f < getChildCount(); f++) {
                if (flowItems[f] == null) continue;
                FlowHelper h = (FlowHelper) getChildAt(f);
                Plan p = plan(f, mainLimit(f, h.orientation));
                int content = a == h.orientation ? p.contentMain : p.contentCross;
                wrap[a][f] = content + (a == 0 ? h.getPaddingLeft() + h.getPaddingRight() : h.getPaddingTop() + h.getPaddingBottom());
            }
        }

        /** How far the views of a flow may run along its main axis before wrapping. */
        private int mainLimit(int f, int m) {
            View v = getChildAt(f);
            LP lp = lp(f);
            int pad = m == 0 ? v.getPaddingLeft() + v.getPaddingRight() : v.getPaddingTop() + v.getPaddingBottom();
            if (size[m][f] >= 0) return Math.max(0, size[m][f] - pad);
            int dim = m == 0 ? lp.width : lp.height;
            if (dim > 0) return Math.max(0, dim - pad);
            if (m == 1 && heightFree) return Integer.MAX_VALUE;
            return Math.max(0, parentSize(m) - padStart(m) - padEnd(m) - marginStart(m, v, lp) - marginEnd(m, v, lp) - pad);
        }

        /** Width and height of a view placed by a flow: its own size when fixed, else its content. */
        private int[] itemBox(int k) {
            View v = getChildAt(k);
            LP lp = lp(k);
            int availW = Math.max(0, pw - getPaddingLeft() - getPaddingRight() - marginStart(0, v, lp) - marginEnd(0, v, lp));
            v.measure(lp.width > 0 ? MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(availW, MeasureSpec.AT_MOST),
                lp.height > 0 ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY) : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return new int[] {v.getMeasuredWidth(), v.getMeasuredHeight()};
        }

        /** The lines of a flow: which views share a line and how big everything is. */
        private static final class Plan {
            int[] lineStart = {0};               // line l holds items lineStart[l] .. lineStart[l + 1] - 1
            int[] lineMain = {}, lineCross = {};
            int[] colMain;                       // wrapMode aligned: the width of each column
            int[] cm, cc, bw, bh;                // per item: cell size on the main / cross axis (with margins), size of the view itself
            int contentMain, contentCross;
            int lines() { return lineStart.length - 1; }
        }

        private Plan plan(int f, int limit) {
            FlowHelper h = (FlowHelper) getChildAt(f);
            int[] items = flowItems[f];
            int m = h.orientation, c = 1 - m, count = items.length;
            Plan p = new Plan();
            p.cm = new int[count]; p.cc = new int[count]; p.bw = new int[count]; p.bh = new int[count];
            for (int t = 0; t < count; t++) {
                View v = getChildAt(items[t]);
                LP lp = lp(items[t]);
                int[] box = itemBox(items[t]);
                p.bw[t] = box[0]; p.bh[t] = box[1];
                p.cm[t] = box[m] + marginStart(m, v, lp) + marginEnd(m, v, lp);
                p.cc[t] = box[c] + marginStart(c, v, lp) + marginEnd(c, v, lp);
            }
            if (count == 0) return p;
            int gapM = h.gap[m], gapC = h.gap[c];
            boolean bounded = limit != Integer.MAX_VALUE;
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            if (h.wrapMode == FlowHelper.WRAP_ALIGNED) {
                int cols = h.maxElements > 0 ? Math.min(count, h.maxElements) : count;
                for (; cols > 1; cols--) {
                    int[] widths = new int[cols];
                    int total = gapM * (cols - 1);
                    for (int t = 0; t < count; t++) widths[t % cols] = Math.max(widths[t % cols], p.cm[t]);
                    for (int w : widths) total += w;
                    if (!bounded || total <= limit) break;
                }
                p.colMain = new int[cols];
                for (int t = 0; t < count; t++) p.colMain[t % cols] = Math.max(p.colMain[t % cols], p.cm[t]);
                for (int t = cols; t < count; t += cols) starts.add(t);
            } else if (h.wrapMode == FlowHelper.WRAP_CHAIN) {
                int used = 0, inLine = 0;
                for (int t = 0; t < count; t++) {
                    boolean full = h.maxElements > 0 && inLine >= h.maxElements;
                    boolean overflow = bounded && inLine > 0 && used + gapM + p.cm[t] > limit;
                    if (inLine > 0 && (full || overflow)) { starts.add(t); used = 0; inLine = 0; }
                    used += (inLine > 0 ? gapM : 0) + p.cm[t];
                    inLine++;
                }
            }
            starts.add(count);
            int lines = starts.size() - 1;
            p.lineStart = new int[lines + 1];
            for (int l = 0; l <= lines; l++) p.lineStart[l] = starts.get(l);
            p.lineMain = new int[lines]; p.lineCross = new int[lines];
            for (int l = 0; l < lines; l++) {
                int s = p.lineStart[l], e = p.lineStart[l + 1], main = 0, cross = 0;
                if (p.colMain != null) { main = gapM * (p.colMain.length - 1); for (int w : p.colMain) main += w; }
                else { main = gapM * (e - s - 1); for (int t = s; t < e; t++) main += p.cm[t]; }
                for (int t = s; t < e; t++) cross = Math.max(cross, p.cc[t]);
                p.lineMain[l] = main; p.lineCross[l] = cross;
                p.contentMain = Math.max(p.contentMain, main);
                p.contentCross += cross;
            }
            p.contentCross += gapC * (lines - 1);
            return p;
        }

        /** Where the free space goes: {space before the first element, space between elements}. Same rules as a chain. */
        private static float[] distribute(int style, float bias, int free, int count) {
            float room = Math.max(0, free);
            if (count <= 0) return new float[] {0f, 0f};
            if (style == 1 && count > 1) return new float[] {0f, room / (count - 1)};
            if (style == 1 || style == 2) return new float[] {room * bias, 0f};
            float gap = room / (count + 1);
            return new float[] {gap, gap};
        }

        /** Once every flow box is solved on both axes: puts the views of each flow inside its box. */
        private void placeFlows() {
            for (int f = 0; f < getChildCount(); f++) {
                if (flowItems[f] == null) continue;
                FlowHelper h = (FlowHelper) getChildAt(f);
                int m = h.orientation, c = 1 - m;
                int[] origin = {pos0[0][f] + h.getPaddingLeft(), pos0[1][f] + h.getPaddingTop()};
                int[] inner = {Math.max(0, size[0][f] - h.getPaddingLeft() - h.getPaddingRight()), Math.max(0, size[1][f] - h.getPaddingTop() - h.getPaddingBottom())};
                Plan p = plan(f, inner[m]);
                int lines = p.lines();
                float[] across = distribute(h.style[c], h.bias[c], inner[c] - p.contentCross, lines);
                float crossAt = across[0];
                float[] colAt = null;
                if (p.colMain != null && lines > 0) {
                    float[] d = distribute(h.style[m], h.bias[m], inner[m] - p.lineMain[0], p.colMain.length);
                    colAt = new float[p.colMain.length];
                    float x = d[0];
                    for (int k = 0; k < colAt.length; k++) { colAt[k] = x; x += p.colMain[k] + h.gap[m] + d[1]; }
                }
                for (int l = 0; l < lines; l++) {
                    int s = p.lineStart[l], e = p.lineStart[l + 1];
                    float x = 0f, between = 0f;
                    if (colAt == null) { float[] d = distribute(h.style[m], h.bias[m], inner[m] - p.lineMain[l], e - s); x = d[0]; between = d[1]; }
                    for (int t = s; t < e; t++) {
                        int k = flowItems[f][t];
                        View v = getChildAt(k);
                        LP lp = lp(k);
                        float cellAt, cellSize;
                        if (colAt != null) { cellAt = colAt[t - s]; cellSize = p.colMain[t - s]; }
                        else { cellAt = x; cellSize = p.cm[t]; x += p.cm[t] + h.gap[m] + between; }
                        int[] box = {p.bw[t], p.bh[t]};
                        setItem(m, k, origin[m] + Math.round(cellAt + (cellSize - p.cm[t]) / 2f) + marginStart(m, v, lp), box[m]);
                        setItem(c, k, origin[c] + Math.round(crossAt + (p.lineCross[l] - p.cc[t]) / 2f) + marginStart(c, v, lp), box[c]);
                    }
                    crossAt += p.lineCross[l] + h.gap[c] + across[1];
                }
            }
        }

        private void setItem(int a, int k, int pos, int length) {
            pos0[a][k] = pos; pos1[a][k] = pos + length; size[a][k] = length; done[a][k] = true;
        }

        // ---- anchors

        private int sideAnchor(int a, LP lp, boolean start) {
            int[] toStart = start ? lp.sToS : lp.eToS, toEnd = start ? lp.sToE : lp.eToE;
            if (toStart[a] != UNSET) return anchor(a, toStart[a], false);
            if (toEnd[a] != UNSET) return anchor(a, toEnd[a], true);
            return NONE;
        }

        private int anchor(int a, int target, boolean targetEnd) {
            if (target == PARENT) return targetEnd ? parentSize(a) - padEnd(a) : padStart(a);
            int j = indexOf(target);
            if (j < 0) return NONE;
            View targetView = getChildAt(j);
            if (targetView instanceof Helper) return ((Helper) targetView).barrier ? barrierEdge(a, j, (Helper) targetView) : NONE;
            if (guideAxis[j] >= 0 && guideAxis[j] != a) return NONE;
            if (!done[a][j]) return WAIT;
            return guideAxis[j] >= 0 || !targetEnd ? pos0[a][j] : pos1[a][j];
        }

        /**
         * Position of a barrier along axis {@code a}: the far edge of its views (right / bottom / end) or the near edge (left / top / start),
         * moved by barrierMargin. Waits until every referenced view has its place; the cycle fallback of {@link #resolveAxis} covers a view
         * that is both referenced by the barrier and anchored to it. Hidden (gone) views are not counted.
         */
        private int barrierEdge(int a, int self, Helper barrier) {
            if (barrier.axis() != a) return NONE;
            boolean atEnd = barrier.atEnd();
            boolean any = false;
            int edge = 0;
            for (int id : barrier.referencedIds()) {
                int k = indexOf(id);
                if (k < 0 || k == self) continue;
                View v = getChildAt(k);
                if (v instanceof Helper || v.getVisibility() == GONE) continue;
                int p;
                if (guideAxis[k] >= 0) {
                    if (guideAxis[k] != a) continue;
                    p = pos0[a][k];
                } else {
                    if (!done[a][k]) return WAIT;
                    p = atEnd ? pos1[a][k] : pos0[a][k];
                }
                edge = !any ? p : atEnd ? Math.max(edge, p) : Math.min(edge, p);
                any = true;
            }
            if (!any) return NONE;
            return atEnd ? edge + barrier.margin : edge - barrier.margin;
        }

        private int indexOf(int id) {
            if (id == UNSET || id == PARENT) return -1;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getId() == id) return i;
            return -1;
        }

        private LP lp(int i) { return (LP) getChildAt(i).getLayoutParams(); }
        private int parentSize(int a) { return a == 0 ? pw : ph; }
        private int padStart(int a) { return a == 0 ? getPaddingLeft() : getPaddingTop(); }
        private int padEnd(int a) { return a == 0 ? getPaddingRight() : getPaddingBottom(); }
        private static int marginStart(int a, View v, LP lp) { return v.getVisibility() == GONE ? 0 : a == 0 ? lp.getMarginStart() : lp.topMargin; }
        private static int marginEnd(int a, View v, LP lp) { return v.getVisibility() == GONE ? 0 : a == 0 ? lp.getMarginEnd() : lp.bottomMargin; }
    }
}
