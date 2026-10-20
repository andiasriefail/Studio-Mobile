package com.example.studiomobile.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class PreviewLinksTest {
    private static final Set<String> ACTIVITIES = new HashSet<>(Arrays.asList("MainActivity", "FormActivity"));

    @Test public void lambdaListenerOnVariableOpensActivity() {
        String code = "Button open = findViewById(R.id.btn_open_form);\n"
            + "open.setOnClickListener(v -> startActivity(new Intent(this, FormActivity.class)));";
        Map<String, PreviewLinks.Action> links = PreviewLinks.scan(code, "", ACTIVITIES);
        assertEquals("FormActivity", links.get("btn_open_form").activity);
    }

    @Test public void listenerOnFindViewByIdCallWithToastAndFinish() {
        String code = "findViewById(R.id.back).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {"
            + " Toast.makeText(MainActivity.this, \"Saved, ok\", Toast.LENGTH_SHORT).show(); finish(); } });";
        PreviewLinks.Action action = PreviewLinks.scan(code, "", ACTIVITIES).get("back");
        assertEquals("Saved, ok", action.toast);
        assertTrue(action.finish);
        assertNull(action.activity);
    }

    @Test public void kotlinViewBindingMatchesSnakeCaseId() {
        String code = "binding.btnOpen.setOnClickListener {\n startActivity(Intent(this, FormActivity::class.java)) }";
        Map<String, PreviewLinks.Action> links = PreviewLinks.scan(code, "<Button android:id=\"@+id/btn_open\"/>", ACTIVITIES);
        assertEquals("FormActivity", links.get("btn_open").activity);
    }

    @Test public void classThatIsNotAnActivityOfTheProjectIsIgnored() {
        String code = "b.setOnClickListener(v -> startActivity(new Intent(this, Settings.class)));";
        assertTrue(PreviewLinks.scan(code, "", ACTIVITIES).isEmpty());
    }

    @Test public void onClickAttributeIsFoundInLayoutOrderEvenWithoutId() {
        String layout = "<LinearLayout><Button android:onClick=\"onFirst\"/><Button android:id=\"@+id/x\" android:onClick=\"onSecond\"/></LinearLayout>";
        String code = "public void onFirst(View v) { Toast.makeText(this, R.string.sent, Toast.LENGTH_SHORT).show(); }\n"
            + "public void onSecond(View v) { startActivity(new Intent(this, FormActivity.class)); }";
        List<PreviewLinks.Action> actions = PreviewLinks.onClickActions(code, layout, ACTIVITIES);
        assertEquals(2, actions.size());
        assertEquals("@string/sent", actions.get(0).toast);
        assertEquals("FormActivity", actions.get(1).activity);
    }

    @Test public void methodWithNothingToShowGivesNullEntry() {
        String layout = "<Button android:onClick=\"noop\"/>";
        List<PreviewLinks.Action> actions = PreviewLinks.onClickActions("void noop(View v) { counter++; }", layout, ACTIVITIES);
        assertEquals(1, actions.size());
        assertNull(actions.get(0));
    }
          
    // ---- changes to views

    private static final String LAYOUT = "<LinearLayout><Button android:id=\"@+id/btn_show\"/><TextView android:id=\"@+id/panel\"/><TextView android:id=\"@+id/title\"/></LinearLayout>";

    @Test public void setVisibilityInClickBecomesEffect() {
        String code = "Button show = findViewById(R.id.btn_show);\nView panel = findViewById(R.id.panel);\n"
            + "show.setOnClickListener(v -> { panel.setVisibility(View.GONE); });";
        PreviewLinks.Action action = PreviewLinks.scan(code, LAYOUT, ACTIVITIES).get("btn_show");
        assertEquals(1, action.effects.size());
        assertEquals("panel", action.effects.get(0).target);
        assertEquals("visibility", action.effects.get(0).kind);
        assertEquals("gone", action.effects.get(0).value);
    }

    @Test public void kotlinPropertiesAndViewBinding() {
        String code = "binding.btnShow.setOnClickListener {\n binding.panel.visibility = View.VISIBLE\n binding.title.text = \"Halo\"\n binding.title.setTextColor(Color.parseColor(\"#ff0000\"))\n}";
        PreviewLinks.Action action = PreviewLinks.scan(code, LAYOUT, ACTIVITIES).get("btn_show");
        assertEquals(3, action.effects.size());
        assertEquals("visible", action.effects.get(0).value);
        assertEquals("Halo", action.effects.get(1).value);
        assertEquals("#FFFF0000", action.effects.get(2).value);
    }

    @Test public void ifElseVisibilitySwitchBecomesToggle() {
        String code = "View panel = findViewById(R.id.panel);\nfindViewById(R.id.btn_show).setOnClickListener(v -> {\n"
            + " if (panel.getVisibility() == View.VISIBLE) { panel.setVisibility(View.GONE); } else { panel.setVisibility(View.VISIBLE); }\n});";
        PreviewLinks.Action action = PreviewLinks.scan(code, LAYOUT, ACTIVITIES).get("btn_show");
        assertEquals(1, action.effects.size());
        assertEquals("toggle", action.effects.get(0).value);
    }

    @Test public void ternaryAndNegationAreToggles() {
        String code = "View panel = findViewById(R.id.panel);\nfindViewById(R.id.btn_show).setOnClickListener(v ->\n"
            + " panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));";
        assertEquals("toggle", PreviewLinks.scan(code, LAYOUT, ACTIVITIES).get("btn_show").effects.get(0).value);
        String kotlin = "binding.btnShow.setOnClickListener { binding.panel.isVisible = !binding.panel.isVisible }";
        assertEquals("toggle", PreviewLinks.scan(kotlin, LAYOUT, ACTIVITIES).get("btn_show").effects.get(0).value);
    }

    @Test public void conditionalEffectsInsideAClickAreNotApplied() {
        String code = "View panel = findViewById(R.id.panel);\nfindViewById(R.id.btn_show).setOnClickListener(v -> {\n"
            + " if (name.isEmpty()) { panel.setVisibility(View.VISIBLE); return; }\n startActivity(new Intent(this, FormActivity.class));\n});";
        PreviewLinks.Action action = PreviewLinks.scan(code, LAYOUT, ACTIVITIES).get("btn_show");
        assertTrue(action.effects.isEmpty());
        assertEquals("FormActivity", action.activity);
    }

    @Test public void colorsFromResourcesAndNames() {
        assertEquals("@color/brand", PreviewLinks.colorValue("ContextCompat.getColor(this, R.color.brand)"));
        assertEquals("#FF336699", PreviewLinks.colorValue("0xFF336699"));
        assertEquals("#FF0A141E", PreviewLinks.colorValue("Color.rgb(10, 20, 30)"));
        assertEquals("#FFFF0000", PreviewLinks.colorValue("Color.RED"));
        assertNull(PreviewLinks.colorValue("computeColor()"));
    }

    @Test public void startupEffectsSkipListenersAndBranches() {
        String code = "protected void onCreate(Bundle b) {\n super.onCreate(b);\n setContentView(R.layout.activity_main);\n"
            + " ProgressBar bar = findViewById(R.id.panel);\n TextView title = findViewById(R.id.title);\n"
            + " bar.setVisibility(View.GONE);\n title.setText(R.string.welcome);\n"
            + " if (user == null) { title.setText(\"Guest\"); }\n"
            + " findViewById(R.id.btn_show).setOnClickListener(v -> bar.setVisibility(View.VISIBLE));\n}\n";
        List<PreviewLinks.Effect> effects = PreviewLinks.startupEffects(code, LAYOUT);
        assertEquals(2, effects.size());
        assertEquals("panel.visibility=gone", effects.get(0).toString());
        assertEquals("title.text=@string/welcome", effects.get(1).toString());
    }

    @Test public void kotlinOnCreateWithBinding() {
        String code = "override fun onCreate(savedInstanceState: Bundle?) {\n super.onCreate(savedInstanceState)\n binding = ActivityMainBinding.inflate(layoutInflater)\n"
            + " binding.panel.visibility = View.INVISIBLE\n binding.title.text = \"Selamat\"\n binding.btnShow.setOnClickListener { binding.panel.visibility = View.VISIBLE }\n}";
        List<PreviewLinks.Effect> effects = PreviewLinks.startupEffects(code, LAYOUT);
        assertEquals(2, effects.size());
        assertEquals("invisible", effects.get(0).value);
        assertEquals("Selamat", effects.get(1).value);
    }
}
