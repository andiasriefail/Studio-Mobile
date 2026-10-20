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
 * Not covered: barriers, groups, flow, circular positioning and min/max constraints; such views do not move other views.
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

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
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
            placeGuides();
            measureWrapWidths();
            resolveAxis(0, true);
            measureWrapHeights();
            resolveAxis(1, false);
            resolveAxis(0, false);   // widths that had to wait for a height (dimension ratio)
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
                if (done[a][i] || guideAxis[i] >= 0) continue;
                int id = getChildAt(i).getId();
                int j = indexOf(lp(i).eToS[a]);
                if (id != UNSET && j >= 0 && j != i && !done[a][j] && lp(j).sToE[a] == id) { next[i] = j; prev[j] = i; }
            }
            while (true) {
                boolean pending = false, progress = false;
                for (int i = 0; i < n; i++) {
                    if (done[a][i] || prev[i] != -1 || (skipDeferred && deferred(a, i))) continue;
                    pending = true;
                    if (next[i] != -1 ? resolveChain(a, i, next, true) : resolveSingle(a, i, true)) progress = true;
                }
                if (!pending) break;
                if (!progress) {
                    for (int i = 0; i < n; i++) {
                        if (done[a][i] || prev[i] != -1 || (skipDeferred && deferred(a, i))) continue;
                        if (next[i] != -1) resolveChain(a, i, next, false); else resolveSingle(a, i, false);
                        break;
                    }
                }
            }
            for (int i = 0; i < n; i++) if (!done[a][i] && guideAxis[i] < 0 && !(skipDeferred && deferred(a, i))) resolveSingle(a, i, false);
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
            if (guideAxis[j] >= 0 && guideAxis[j] != a) return NONE;
            if (!done[a][j]) return WAIT;
            return guideAxis[j] >= 0 || !targetEnd ? pos0[a][j] : pos1[a][j];
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
