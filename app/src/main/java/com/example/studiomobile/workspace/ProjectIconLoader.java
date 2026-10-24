package com.example.studiomobile.workspace;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import androidx.core.graphics.PathParser;
import com.example.studiomobile.build.BuildProjectFiles;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the launcher icon of a project the way Android does, from the project's own resources:
 *
 * <ol>
 *   <li>{@code android:icon} of {@code <application>} in AndroidManifest.xml ({@code android:roundIcon} when there is no icon);
 *       when the manifest names none, a resource called {@code ic_launcher} is tried.</li>
 *   <li>{@code @mipmap/x} or {@code @drawable/x} is looked up in every {@code mipmap-*} / {@code drawable-*} folder of res.</li>
 *   <li>PNG / WebP / JPEG files win (highest density first). Without one, the XML is drawn: adaptive-icon (background + foreground),
 *       layer-list, vector (paths, groups, solid or gradient fills) and plain colours.</li>
 * </ol>
 * Returns null when nothing can be drawn, so the caller keeps its letters. Call it off the UI thread.
 */
public final class ProjectIconLoader {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String AAPT_NS = "http://schemas.android.com/aapt";
    private static final String[] RASTER = {".png", ".webp", ".jpg", ".jpeg"};
    private static final int MAX_DEPTH = 6;

    private final File resDir;
    private final Map<String, Integer> colors = new HashMap<>();
    private final Map<String, String> aliases = new HashMap<>();

    private ProjectIconLoader(File resDir) {
        this.resDir = resDir;
        readColors();
    }

    /** @param sizePx wanted width and height of the result */
    public static Bitmap load(File project, int sizePx) {
        try {
            if (project == null || sizePx <= 0) return null;
            File main = BuildProjectFiles.mainDirectory(project);
            File res = new File(main, "res");
            if (!res.isDirectory()) return null;
            ProjectIconLoader loader = new ProjectIconLoader(res);
            List<String> references = loader.manifestIconReferences(BuildProjectFiles.manifest(project));
            references.add("@mipmap/ic_launcher");
            references.add("@drawable/ic_launcher");
            for (String reference : references) {
                Bitmap bitmap = loader.resolve(reference, sizePx, 0);
                if (bitmap != null) return bitmap;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- manifest

    private List<String> manifestIconReferences(File manifest) {
        List<String> result = new ArrayList<>();
        try {
            if (!manifest.isFile()) return result;
            Document doc = parse(manifest);
            NodeList applications = doc.getElementsByTagName("application");
            if (applications.getLength() == 0) return result;
            Element application = (Element) applications.item(0);
            String icon = application.getAttributeNS(ANDROID_NS, "icon").trim();
            String round = application.getAttributeNS(ANDROID_NS, "roundIcon").trim();
            if (!icon.isEmpty()) result.add(icon);
            if (!round.isEmpty()) result.add(round);
            // an icon on the launcher activity counts too
            NodeList activities = application.getElementsByTagName("activity");
            for (int i = 0; i < activities.getLength(); i++) {
                String activityIcon = ((Element) activities.item(i)).getAttributeNS(ANDROID_NS, "icon").trim();
                if (!activityIcon.isEmpty()) result.add(activityIcon);
            }
        } catch (Exception ignored) { }
        return result;
    }

    // ---------------------------------------------------------------- resolving

    private Bitmap resolve(String reference, int size, int depth) {
        if (reference == null || depth > MAX_DEPTH) return null;
        String ref = reference.trim();
        if (ref.startsWith("#")) { Integer color = parseColor(ref); return color == null ? null : solid(color, size); }
        if (!ref.startsWith("@") || ref.startsWith("@android:")) return null;
        String body = ref.substring(1).replace("+", "");
        int slash = body.indexOf('/');
        if (slash < 0) return null;
        String type = body.substring(0, slash), name = body.substring(slash + 1);
        if (type.equals("color")) { Integer color = color(name); return color == null ? null : solid(color, size); }
        if (!type.equals("mipmap") && !type.equals("drawable")) return null;

        File raster = bestRaster(type, name);
        if (raster != null) {
            Bitmap bitmap = decode(raster, size);
            if (bitmap != null) return bitmap;
        }
        File xml = bestXml(type, name);
        if (xml != null) return drawXml(xml, size, depth + 1);
        // an icon that was declared under mipmap may physically live under drawable (and the other way round)
        String other = type.equals("mipmap") ? "drawable" : "mipmap";
        File otherRaster = bestRaster(other, name);
        if (otherRaster != null) return decode(otherRaster, size);
        File otherXml = bestXml(other, name);
        return otherXml == null ? null : drawXml(otherXml, size, depth + 1);
    }

    private static int density(String folder) {
        String f = folder.toLowerCase(Locale.ROOT);
        if (f.contains("xxxhdpi")) return 6;
        if (f.contains("xxhdpi")) return 5;
        if (f.contains("xhdpi")) return 4;
        if (f.contains("hdpi")) return 3;
        if (f.contains("mdpi")) return 2;
        if (f.contains("tvdpi")) return 2;
        if (f.contains("ldpi")) return 0;
        return 1;   // no density qualifier / nodpi / anydpi
    }

    private File[] foldersOf(String type) {
        File[] folders = resDir.listFiles(f -> f.isDirectory() && (f.getName().equals(type) || f.getName().startsWith(type + "-")));
        return folders == null ? new File[0] : folders;
    }

    private File bestRaster(String type, String name) {
        File best = null; int bestDensity = -1;
        for (File folder : foldersOf(type)) {
            for (String extension : RASTER) {
                File candidate = new File(folder, name + extension);
                if (candidate.isFile() && candidate.length() > 0) {
                    int d = density(folder.getName());
                    if (d > bestDensity) { best = candidate; bestDensity = d; }
                }
            }
        }
        return best;
    }

    private File bestXml(String type, String name) {
        File best = null; int bestRank = -1;
        for (File folder : foldersOf(type)) {
            File candidate = new File(folder, name + ".xml");
            if (!candidate.isFile()) continue;
            String f = folder.getName();
            int rank = f.contains("anydpi") ? 2 : 1;
            if (f.contains("-v26") || f.contains("-v2")) rank += 1;   // the adaptive version is the real one
            if (rank > bestRank) { best = candidate; bestRank = rank; }
        }
        return best;
    }

    private static Bitmap decode(File file, int size) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (decoded == null) return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(decoded, null, new RectF(0, 0, size, size), paint);
            if (decoded != out) decoded.recycle();
            return out;
        } catch (Throwable ignored) { return null; }
    }

    private static Bitmap solid(int color, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        out.eraseColor(color);
        return out;
    }

    // ---------------------------------------------------------------- XML drawables

    private Bitmap drawXml(File file, int size, int depth) {
        try {
            Document doc = parse(file);
            Element root = doc.getDocumentElement();
            String tag = root.getTagName();
            switch (tag) {
                case "adaptive-icon": return drawAdaptive(root, size, depth);
                case "layer-list": return drawLayerList(root, size, depth);
                case "vector": return drawVector(root, size);
                case "inset": return drawInset(root, size, depth);
                case "bitmap": return resolve(root.getAttributeNS(ANDROID_NS, "src"), size, depth);
                case "shape": return drawShape(root, size);
                default: return null;
            }
        } catch (Throwable ignored) { return null; }
    }

    /** Draws background then foreground on the 108dp canvas and keeps the 72dp that every launcher shows. */
    private Bitmap drawAdaptive(Element root, int size, int depth) {
        int canvasSize = Math.max(size * 3 / 2, 2);
        Bitmap layers = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layers);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean any = false;
        for (String layer : new String[]{"background", "foreground"}) {
            Element element = firstChild(root, layer);
            if (element == null) continue;
            Bitmap bitmap = layerBitmap(element, canvasSize, depth);
            if (bitmap != null) { canvas.drawBitmap(bitmap, 0, 0, paint); any = true; }
        }
        if (!any) return null;
        int inset = Math.round(canvasSize * 18f / 108f);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(layers, new android.graphics.Rect(inset, inset, canvasSize - inset, canvasSize - inset),
            new RectF(0, 0, size, size), paint);
        return out;
    }

    private Bitmap layerBitmap(Element layer, int size, int depth) {
        String drawable = layer.getAttributeNS(ANDROID_NS, "drawable").trim();
        if (!drawable.isEmpty()) return resolve(drawable, size, depth);
        Element inline = firstElementChild(layer);
        if (inline == null) return null;
        switch (inline.getTagName()) {
            case "vector": return drawVector(inline, size);
            case "shape": return drawShape(inline, size);
            case "layer-list": return drawLayerList(inline, size, depth + 1);
            case "inset": return drawInset(inline, size, depth + 1);
            default: return null;
        }
    }

    private Bitmap drawLayerList(Element root, int size, int depth) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean any = false;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element) || !((Element) node).getTagName().equals("item")) continue;
            Bitmap bitmap = layerBitmap((Element) node, size, depth);
            if (bitmap != null) { canvas.drawBitmap(bitmap, 0, 0, paint); any = true; }
        }
        return any ? out : null;
    }

    private Bitmap drawInset(Element root, int size, int depth) {
        String drawable = root.getAttributeNS(ANDROID_NS, "drawable").trim();
        return drawable.isEmpty() ? layerBitmap(root, size, depth) : resolve(drawable, size, depth);
    }

    private Bitmap drawShape(Element root, int size) {
        Element solid = firstChild(root, "solid");
        if (solid == null) return null;
        Integer color = colorValue(solid.getAttributeNS(ANDROID_NS, "color"));
        return color == null ? null : solid(color, size);
    }

    // ---------------------------------------------------------------- vector drawables

    private Bitmap drawVector(Element root, int size) {
        float viewportW = floatAttr(root, "viewportWidth", 0), viewportH = floatAttr(root, "viewportHeight", 0);
        if (viewportW <= 0 || viewportH <= 0) {
            viewportW = dimension(root.getAttributeNS(ANDROID_NS, "width"), 24);
            viewportH = dimension(root.getAttributeNS(ANDROID_NS, "height"), 24);
        }
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(size / viewportW, size / viewportH);
        drawVectorChildren(canvas, root);
        return out;
    }

    private void drawVectorChildren(Canvas canvas, Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            switch (element.getTagName()) {
                case "group": {
                    canvas.save();
                    float px = floatAttr(element, "pivotX", 0), py = floatAttr(element, "pivotY", 0);
                    canvas.translate(floatAttr(element, "translateX", 0), floatAttr(element, "translateY", 0));
                    canvas.translate(px, py);
                    canvas.rotate(floatAttr(element, "rotation", 0));
                    canvas.scale(floatAttr(element, "scaleX", 1), floatAttr(element, "scaleY", 1));
                    canvas.translate(-px, -py);
                    drawVectorChildren(canvas, element);
                    canvas.restore();
                    break;
                }
                case "clip-path": {
                    Path clip = path(element.getAttributeNS(ANDROID_NS, "pathData"));
                    if (clip != null) canvas.clipPath(clip);
                    break;
                }
                case "path": drawVectorPath(canvas, element); break;
                default: break;
            }
        }
    }

    private void drawVectorPath(Canvas canvas, Element element) {
        Path path = path(element.getAttributeNS(ANDROID_NS, "pathData"));
        if (path == null) return;
        if ("evenOdd".equals(element.getAttributeNS(ANDROID_NS, "fillType"))) path.setFillType(Path.FillType.EVEN_ODD);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Paint fill = fillPaint(element, paint, "fillColor", "fillAlpha");
        if (fill != null) { fill.setStyle(Paint.Style.FILL); canvas.drawPath(path, fill); }

        Paint stroke = fillPaint(element, new Paint(Paint.ANTI_ALIAS_FLAG), "strokeColor", "strokeAlpha");
        float width = floatAttr(element, "strokeWidth", 0);
        if (stroke != null && width > 0) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(width);
            String cap = element.getAttributeNS(ANDROID_NS, "strokeLineCap");
            if (cap.equals("round")) stroke.setStrokeCap(Paint.Cap.ROUND); else if (cap.equals("square")) stroke.setStrokeCap(Paint.Cap.SQUARE);
            String join = element.getAttributeNS(ANDROID_NS, "strokeLineJoin");
            if (join.equals("round")) stroke.setStrokeJoin(Paint.Join.ROUND); else if (join.equals("bevel")) stroke.setStrokeJoin(Paint.Join.BEVEL);
            canvas.drawPath(path, stroke);
        }
    }

    /** A paint for fillColor / strokeColor: a plain colour, a colour resource or a gradient written as {@code <aapt:attr>}. */
    private Paint fillPaint(Element element, Paint paint, String attribute, String alphaAttribute) {
        float alpha = floatAttr(element, alphaAttribute, 1);
        String value = element.getAttributeNS(ANDROID_NS, attribute).trim();
        if (!value.isEmpty()) {
            Integer color = colorValue(value);
            if (color == null) return null;
            paint.setColor(color);
            paint.setAlpha(Math.round(Color.alpha(color) * alpha));
            return paint;
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (!(node instanceof Element) || !AAPT_NS.equals(node.getNamespaceURI())) continue;
            if (!("android:" + attribute).equals(((Element) node).getAttribute("name"))) continue;
            Element gradient = firstChild((Element) node, "gradient");
            if (gradient == null) return null;
            return gradientPaint(gradient, paint, alpha);
        }
        return null;
    }

    private Paint gradientPaint(Element gradient, Paint paint, float alpha) {
        List<Integer> stopColors = new ArrayList<>();
        List<Float> stopOffsets = new ArrayList<>();
        NodeList items = gradient.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            Integer color = colorValue(item.getAttributeNS(ANDROID_NS, "color"));
            if (color == null) continue;
            stopColors.add(color);
            stopOffsets.add(floatAttr(item, "offset", items.getLength() <= 1 ? 0f : (float) i / (items.getLength() - 1)));
        }
        if (stopColors.isEmpty()) {
            Integer start = colorValue(gradient.getAttributeNS(ANDROID_NS, "startColor"));
            Integer end = colorValue(gradient.getAttributeNS(ANDROID_NS, "endColor"));
            if (start != null) { stopColors.add(start); stopOffsets.add(0f); }
            if (end != null) { stopColors.add(end); stopOffsets.add(1f); }
        }
        if (stopColors.isEmpty()) return null;
        if (stopColors.size() == 1) { paint.setColor(stopColors.get(0)); paint.setAlpha(Math.round(Color.alpha(stopColors.get(0)) * alpha)); return paint; }
        int[] colorsArray = new int[stopColors.size()];
        float[] offsets = new float[stopColors.size()];
        for (int i = 0; i < colorsArray.length; i++) { colorsArray[i] = stopColors.get(i); offsets[i] = Math.max(0f, Math.min(1f, stopOffsets.get(i))); }
        String type = gradient.getAttributeNS(ANDROID_NS, "type");
        try {
            if (type.equals("radial")) {
                paint.setShader(new android.graphics.RadialGradient(floatAttr(gradient, "centerX", 0), floatAttr(gradient, "centerY", 0),
                    Math.max(floatAttr(gradient, "gradientRadius", 1), 0.001f), colorsArray, offsets, Shader.TileMode.CLAMP));
            } else if (type.equals("sweep")) {
                paint.setShader(new android.graphics.SweepGradient(floatAttr(gradient, "centerX", 0), floatAttr(gradient, "centerY", 0), colorsArray, offsets));
            } else {
                paint.setShader(new LinearGradient(floatAttr(gradient, "startX", 0), floatAttr(gradient, "startY", 0),
                    floatAttr(gradient, "endX", 0), floatAttr(gradient, "endY", 0), colorsArray, offsets, Shader.TileMode.CLAMP));
            }
        } catch (RuntimeException ignored) { paint.setColor(colorsArray[0]); }
        paint.setAlpha(Math.round(255 * alpha));
        return paint;
    }

    private static Path path(String data) {
        if (data == null || data.trim().isEmpty()) return null;
        try { return PathParser.createPathFromPathData(data); } catch (RuntimeException invalid) { return null; }
    }

    // ---------------------------------------------------------------- colours and small helpers

    private void readColors() {
        File[] files = new File(resDir, "values").listFiles(f -> f.isFile() && f.getName().endsWith(".xml"));
        if (files == null) return;
        for (File file : files) {
            try {
                NodeList nodes = parse(file).getElementsByTagName("color");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element node = (Element) nodes.item(i);
                    String name = node.getAttribute("name");
                    if (name.isEmpty() || colors.containsKey(name) || aliases.containsKey(name)) continue;
                    String text = node.getTextContent().trim();
                    if (text.startsWith("@color/")) { aliases.put(name, text.substring(7)); continue; }
                    Integer value = parseColor(text);
                    if (value != null) colors.put(name, value);
                }
            } catch (Exception ignored) { }
        }
    }

    private Integer color(String name) {
        for (int depth = 0; depth < MAX_DEPTH && name != null; depth++) {
            Integer value = colors.get(name);
            if (value != null) return value;
            name = aliases.get(name);
        }
        return null;
    }

    private Integer colorValue(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty()) return null;
        if (v.startsWith("@color/")) return color(v.substring(7));
        if (v.startsWith("@")) return null;
        return parseColor(v);
    }

    /** #RGB, #ARGB, #RRGGBB and #AARRGGBB, the formats Android accepts in XML. */
    private static Integer parseColor(String text) {
        try {
            String hex = text.trim();
            if (!hex.startsWith("#")) return null;
            hex = hex.substring(1);
            if (hex.length() == 3 || hex.length() == 4) {
                StringBuilder expanded = new StringBuilder();
                for (char c : hex.toCharArray()) expanded.append(c).append(c);
                hex = expanded.toString();
            }
            if (hex.length() == 6) return (int) (0xFF000000L | Long.parseLong(hex, 16));
            if (hex.length() == 8) return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) { }
        return null;
    }

    private static float floatAttr(Element element, String name, float fallback) {
        String value = element.getAttributeNS(ANDROID_NS, name).trim();
        if (value.isEmpty()) return fallback;
        try { return Float.parseFloat(value); } catch (NumberFormatException e) { return fallback; }
    }

    private static float dimension(String value, float fallback) {
        try {
            String digits = value.replaceAll("[^0-9.]", "");
            return digits.isEmpty() ? fallback : Float.parseFloat(digits);
        } catch (NumberFormatException e) { return fallback; }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element && ((Element) node).getTagName().equals(tag)) return (Element) node;
        }
        return null;
    }

    private static Element firstElementChild(Element parent) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) if (children.item(i) instanceof Element) return (Element) children.item(i);
        return null;
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        return factory.newDocumentBuilder().parse(file);
    }
}
