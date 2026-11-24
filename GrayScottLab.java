/*
 * GrayScottLab.java
 *
 * A single-file Java Swing laboratory for the Gray–Scott reaction–diffusion
 * system: two chemicals, U and V, spreading through a dish and reacting.
 * With different feed and kill rates the same two rules produce dividing
 * "cells", coral, worms, mazes, travelling waves, holes and spirals.
 *
 *     dU/dt = Du ∇²U  −  U V²  +  F (1 − U)
 *     dV/dt = Dv ∇²V  +  U V²  −  (F + k) V
 *
 *   U   the "food", fed into the dish at rate F
 *   V   the "catalyst", which turns U into more V (U + 2V → 3V)
 *       and is removed at rate F + k
 *
 * The lab also draws the whole F–k parameter space at once (the famous
 * "map" popularised by Robert Munafo), lets you click anywhere on it to
 * visit that world, and counts the self-replicating spots as they divide.
 *
 * Run:   java GrayScottLab.java        (Java 17 or newer)
 *
 * Architect: Mario Gianota
 * Implementation: Claude (Anthropic)
 * Licence: MIT
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;

public class GrayScottLab extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new GrayScottLab().setVisible(true);
        });
    }

    // =====================================================================
    //  Presets — named regions of the F–k map
    // =====================================================================

    enum Preset {
        MITOSIS  ("Mitosis (dividing cells)", 0.0367, 0.0649, Seed.FEW,
                  "Spots grow, stretch and split in two, again and again, until the dish is full. Watch the cell count climb."),
        CORAL    ("Coral growth",             0.0545, 0.0620, Seed.FEW,
                  "Branching fronts creep outward like coral or lichen, filling the dish with a tangle of stripes."),
        WORMS    ("Worms",                    0.0460, 0.0650, Seed.MANY,
                  "Spots stretch into short wriggling worms that jostle for space, not unlike a fingerprint broken into pieces."),
        MAZE     ("Maze",                     0.0290, 0.0570, Seed.MANY,
                  "Stripes join into a labyrinth that keeps slowly rearranging itself."),
        SPOTS    ("Stable spots",             0.0300, 0.0620, Seed.MANY,
                  "Isolated spots that hold their shape and keep their distance, like a leopard's coat."),
        HOLES    ("Holes",                    0.0390, 0.0580, Seed.MANY,
                  "The pattern turns inside out: a sea of catalyst pierced by round holes."),
        WAVES    ("Travelling waves",         0.0140, 0.0410, Seed.FEW,
                  "Rings of catalyst race outward, collide and annihilate where they meet, then break up and start again."),
        CHAOS    ("Chaos",                    0.0180, 0.0510, Seed.FEW,
                  "Spots and squiggles are born, grow, split and die in a restless churn that never settles down."),
        USKATE   ("U-skate world",            0.0620, 0.0609, Seed.FEW,
                  "A delicate region where small structures glide across the dish before freezing in place."),
        MAP      ("The F–k map (all worlds)", 0.0,    0.0,    Seed.MAP,
                  "Every pixel has its own feed and kill rate: F rises up the screen, k increases to the right. All the worlds at once. Click anywhere to go and live there.");

        final String label, blurb;
        final double f, k;
        final Seed seed;
        Preset(String label, double f, double k, Seed seed, String blurb) {
            this.label = label; this.f = f; this.k = k; this.seed = seed; this.blurb = blurb;
        }
        @Override public String toString() { return label; }
    }

    enum Seed { FEW, MANY, MAP }

    enum ColourMap {
        PETRI("Petri dish"), INK("Ink"), CORAL("Coral reef"), THERMAL("Thermal");
        final String label;
        ColourMap(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    // Extent of the parameter map (after Munafo's xmorphia map)
    static final double MAP_K_MIN = 0.030, MAP_K_MAX = 0.070;
    static final double MAP_F_MIN = 0.000, MAP_F_MAX = 0.080;

    // =====================================================================
    //  Simulator
    // =====================================================================

    static final class Simulator {
        final int n;
        double[] u, v, u2, v2;
        final Random rng = new Random();

        volatile double feed = 0.0367, kill = 0.0649, diffRatio = 0.5;
        volatile boolean mapMode = false;
        long steps = 0;

        static final double DU = 1.0, DT = 1.0;

        Simulator(int n) {
            this.n = n;
            u = new double[n * n]; v = new double[n * n];
            u2 = new double[n * n]; v2 = new double[n * n];
        }

        static double mapF(int y, int n) { return MAP_F_MAX - (MAP_F_MAX - MAP_F_MIN) * (y + 0.5) / n; }
        static double mapK(int x, int n) { return MAP_K_MIN + (MAP_K_MAX - MAP_K_MIN) * (x + 0.5) / n; }

        void step() {
            final double fG = feed, kG = kill, du = DU, dv = DU * diffRatio;
            final boolean map = mapMode;
            final double[] U = u, V = v, U2 = u2, V2 = v2;
            IntStream.range(0, n).parallel().forEach(y -> {
                int ym, yp;
                if (map) { ym = Math.max(y - 1, 0); yp = Math.min(y + 1, n - 1); }     // closed edges
                else     { ym = (y - 1 + n) % n;    yp = (y + 1) % n; }               // wrap-around
                int rm = ym * n, r0 = y * n, rp = yp * n;
                double f = map ? mapF(y, n) : fG;
                for (int x = 0; x < n; x++) {
                    int xm, xp;
                    if (map) { xm = Math.max(x - 1, 0); xp = Math.min(x + 1, n - 1); }
                    else     { xm = x == 0 ? n - 1 : x - 1; xp = x == n - 1 ? 0 : x + 1; }
                    double k = map ? mapK(x, n) : kG;
                    int i = r0 + x;
                    double a = U[i], b = V[i];
                    // 9-point Laplacian: centre -1, edges 0.2, corners 0.05
                    double la = 0.2 * (U[r0 + xm] + U[r0 + xp] + U[rm + x] + U[rp + x])
                              + 0.05 * (U[rm + xm] + U[rm + xp] + U[rp + xm] + U[rp + xp]) - a;
                    double lb = 0.2 * (V[r0 + xm] + V[r0 + xp] + V[rm + x] + V[rp + x])
                              + 0.05 * (V[rm + xm] + V[rm + xp] + V[rp + xm] + V[rp + xp]) - b;
                    double abb = a * b * b;
                    double na = a + DT * (du * la - abb + f * (1 - a));
                    double nb = b + DT * (dv * lb + abb - (k + f) * b);
                    U2[i] = na < 0 ? 0 : na > 1 ? 1 : na;
                    V2[i] = nb < 0 ? 0 : nb > 1 ? 1 : nb;
                }
            });
            double[] t = u; u = u2; u2 = t;
            t = v; v = v2; v2 = t;
            steps++;
        }

        void clear() {
            Arrays.fill(u, 1.0);
            Arrays.fill(v, 0.0);
            steps = 0;
        }

        /** Drop a square of catalyst, with a little noise to break symmetry. */
        void drop(int cx, int cy, int half) {
            for (int dy = -half; dy <= half; dy++)
                for (int dx = -half; dx <= half; dx++) {
                    int x = Math.floorMod(cx + dx, n), y = Math.floorMod(cy + dy, n);
                    int i = y * n + x;
                    u[i] = 0.50 + 0.04 * (rng.nextDouble() - 0.5);
                    v[i] = 0.25 + 0.04 * (rng.nextDouble() - 0.5);
                }
        }

        void seed(Seed s) {
            clear();
            switch (s) {
                case FEW -> {
                    int count = 3 + rng.nextInt(3);
                    for (int c = 0; c < count; c++)
                        drop(n / 4 + rng.nextInt(n / 2), n / 4 + rng.nextInt(n / 2), 3 + rng.nextInt(3));
                }
                case MANY -> {
                    int count = n * n / 900;
                    for (int c = 0; c < count; c++) drop(rng.nextInt(n), rng.nextInt(n), 2 + rng.nextInt(3));
                }
                case MAP -> {
                    int count = n * n / 300;
                    for (int c = 0; c < count; c++) drop(rng.nextInt(n), rng.nextInt(n), 2 + rng.nextInt(2));
                }
            }
        }

        /** Mouse brush: add catalyst, or wash the area back to plain food. */
        void brush(double gx, double gy, double radius, boolean add) {
            int r = (int) Math.ceil(radius);
            for (int dy = -r; dy <= r; dy++)
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dy * dy > radius * radius) continue;
                    int x = Math.floorMod((int) Math.round(gx) + dx, n);
                    int y = Math.floorMod((int) Math.round(gy) + dy, n);
                    int i = y * n + x;
                    if (add) { u[i] = 0.5 + 0.04 * (rng.nextDouble() - 0.5); v[i] = 0.25 + 0.04 * (rng.nextDouble() - 0.5); }
                    else     { u[i] = 1.0; v[i] = 0.0; }
                }
        }

        void copyParamsFrom(Simulator o) {
            feed = o.feed; kill = o.kill; diffRatio = o.diffRatio; mapMode = o.mapMode;
        }
    }

    // =====================================================================
    //  Counting "cells": connected blobs of catalyst on the periodic grid
    // =====================================================================

    static int countBlobs(double[] v, int n, double threshold) {
        int[] label = new int[n * n];
        int[] stack = new int[n * n];
        int count = 0;
        for (int start = 0; start < v.length; start++) {
            if (label[start] != 0 || v[start] < threshold) continue;
            count++;
            int sp = 0;
            stack[sp++] = start;
            label[start] = count;
            while (sp > 0) {
                int i = stack[--sp];
                int x = i % n, y = i / n;
                int[] nb = {
                    y * n + (x + 1) % n, y * n + (x - 1 + n) % n,
                    ((y + 1) % n) * n + x, ((y - 1 + n) % n) * n + x };
                for (int j : nb)
                    if (label[j] == 0 && v[j] >= threshold) { label[j] = count; stack[sp++] = j; }
            }
        }
        return count;
    }

    // =====================================================================
    //  Rendering
    // =====================================================================

    static int clamp255(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }
    static int rgb(double r, double g, double b) { return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b); }

    /** Piecewise-linear colour ramp: stops are {t, r, g, b}. */
    static int ramp(double t, double[][] stops) {
        if (t <= stops[0][0]) return rgb(stops[0][1], stops[0][2], stops[0][3]);
        for (int s = 1; s < stops.length; s++) {
            if (t <= stops[s][0]) {
                double[] a = stops[s - 1], b = stops[s];
                double w = (t - a[0]) / (b[0] - a[0]);
                return rgb(a[1] + w * (b[1] - a[1]), a[2] + w * (b[2] - a[2]), a[3] + w * (b[3] - a[3]));
            }
        }
        double[] z = stops[stops.length - 1];
        return rgb(z[1], z[2], z[3]);
    }

    static final double[][] PETRI = {
        {0.00, 14, 26, 32}, {0.15, 22, 64, 74}, {0.40, 70, 170, 150}, {0.65, 235, 205, 120}, {1.00, 255, 248, 225}};
    static final double[][] CORAL = {
        {0.00, 8, 40, 82}, {0.25, 20, 110, 150}, {0.50, 240, 120, 100}, {0.75, 255, 190, 140}, {1.00, 255, 245, 230}};
    static final double[][] THERMAL = {
        {0.00, 0, 0, 10}, {0.30, 90, 10, 120}, {0.55, 220, 60, 40}, {0.80, 255, 190, 30}, {1.00, 255, 255, 220}};

    static void render(Simulator s, ColourMap map, BufferedImage img) {
        int n = s.n;
        double[] v = s.v, u = s.u;
        int[] row = new int[n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = y * n + x;
                // Catalyst concentration rarely exceeds ~0.45; stretch it to 0..1
                double t = Math.min(1.0, v[i] / 0.42);
                switch (map) {
                    case PETRI -> row[x] = ramp(t, PETRI);
                    case CORAL -> row[x] = ramp(t, CORAL);
                    case THERMAL -> row[x] = ramp(t, THERMAL);
                    case INK -> {
                        double c = 250 - 235 * Math.pow(t, 0.8) - 20 * (1 - u[i]);
                        row[x] = rgb(c, c * 0.985, c * 0.95);
                    }
                }
            }
            img.setRGB(0, y, n, 1, row, 0, n);
        }
    }

    // =====================================================================
    //  Application state
    // =====================================================================

    private volatile Simulator sim;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true, singleStep = false;
    private volatile int stepsPerFrame = 20;
    private volatile ColourMap colourMap = ColourMap.PETRI;
    private volatile boolean countCells = true;
    private volatile Preset currentPreset = Preset.MITOSIS;

    private volatile BufferedImage frame;
    private volatile int cellCount;
    private volatile double stepsPerSecond;
    private final int[] cellHistory = new int[400];
    private int cellHistCount = 0, cellHistHead = 0;
    private final Object histLock = new Object();
    private volatile double hoverF = Double.NaN, hoverK = Double.NaN;

    private final ViewPanel view = new ViewPanel();
    private final MapChart mapChart = new MapChart();
    private final PopulationPlot popPlot = new PopulationPlot();
    private final JLabel status = new JLabel(" ");
    private final JTextArea blurb = new JTextArea(4, 20);

    private final JComboBox<Preset> presetBox = new JComboBox<>(Preset.values());
    private final JComboBox<Integer> sizeBox = new JComboBox<>(new Integer[]{128, 256, 384, 512});
    private final JComboBox<ColourMap> mapBox = new JComboBox<>(ColourMap.values());
    private final ParamSlider fSlider     = new ParamSlider("Feed rate  F",       0.000, 0.100, 0.0367, "%.4f");
    private final ParamSlider kSlider     = new ParamSlider("Kill rate  k",       0.030, 0.075, 0.0649, "%.4f");
    private final ParamSlider ratioSlider = new ParamSlider("Diffusion ratio Dv/Du", 0.30, 0.70, 0.50, "%.2f");
    private final ParamSlider speedSlider = new ParamSlider("Steps per frame",      1,    80,   20,   "%.0f");
    private final JToggleButton playButton = new JToggleButton("Pause", true);
    private final JCheckBox countBox = new JCheckBox("Count cells (spots)", true);
    private boolean suppressEvents = false;

    public GrayScottLab() {
        super("Gray–Scott Lab — two chemicals, endless worlds");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        sim = new Simulator(256);
        sizeBox.setSelectedItem(256);
        buildUI();
        applyPreset(Preset.MITOSIS);

        Thread worker = new Thread(this::simulationLoop, "gray-scott-sim");
        worker.setDaemon(true);
        worker.start();

        new javax.swing.Timer(40, e -> { view.repaint(); popPlot.repaint(); mapChart.repaint(); updateStatus(); }).start();
        pack();
        setLocationRelativeTo(null);
    }

    private void simulationLoop() {
        Simulator current = null;
        BufferedImage[] buffers = new BufferedImage[2];
        int bufIndex = 0, frameNo = 0, stepsSince = 0;
        long lastTime = System.nanoTime();
        while (true) {
            long frameStart = System.nanoTime();
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
            Simulator s = sim;
            if (s != current) {
                current = s;
                buffers[0] = new BufferedImage(s.n, s.n, BufferedImage.TYPE_INT_RGB);
                buffers[1] = new BufferedImage(s.n, s.n, BufferedImage.TYPE_INT_RGB);
                resetHistory();
            }
            boolean advance = running || singleStep;
            if (advance) {
                int steps = singleStep && !running ? 1 : stepsPerFrame;
                singleStep = false;
                for (int k = 0; k < steps; k++) s.step();
                stepsSince += steps;
            }
            BufferedImage img = buffers[bufIndex];
            bufIndex ^= 1;
            render(s, colourMap, img);
            frame = img;

            if (countCells && !s.mapMode && (frameNo % 4 == 0)) {
                int c = countBlobs(s.v, s.n, 0.2);
                cellCount = c;
                if (advance) {
                    synchronized (histLock) {
                        cellHistory[cellHistHead] = c;
                        cellHistHead = (cellHistHead + 1) % cellHistory.length;
                        cellHistCount = Math.min(cellHistCount + 1, cellHistory.length);
                    }
                }
            }
            frameNo++;

            long now = System.nanoTime();
            if (now - lastTime > 500_000_000L) {
                stepsPerSecond = stepsSince * 1e9 / (now - lastTime);
                stepsSince = 0; lastTime = now;
            }
            long sleepMs = 33 - (System.nanoTime() - frameStart) / 1_000_000;
            try { Thread.sleep(Math.max(2, sleepMs)); } catch (InterruptedException ex) { return; }
        }
    }

    private void resetHistory() {
        synchronized (histLock) { cellHistCount = 0; cellHistHead = 0; }
        cellCount = 0;
    }

    // =====================================================================
    //  Presets and parameters
    // =====================================================================

    private void applyPreset(Preset p) {
        currentPreset = p;
        suppressEvents = true;
        if (p != Preset.MAP) { fSlider.setValue(p.f); kSlider.setValue(p.k); }
        ratioSlider.setValue(0.5);
        presetBox.setSelectedItem(p);
        suppressEvents = false;
        blurb.setText(p.blurb);
        boolean map = p == Preset.MAP;
        fSlider.setEnabled(!map);
        kSlider.setEnabled(!map);
        if (map && (Integer) sizeBox.getSelectedItem() < 384) sizeBox.setSelectedItem(512);   // triggers resize
        pushParams();
        reseed();
    }

    private void pushParams() {
        if (suppressEvents) return;
        Simulator s = sim;
        s.feed = fSlider.value();
        s.kill = kSlider.value();
        s.diffRatio = ratioSlider.value();
        stepsPerFrame = (int) Math.round(speedSlider.value());
    }

    private void reseed() {
        Preset p = currentPreset;
        tasks.add(() -> {
            Simulator s = sim;
            s.mapMode = p == Preset.MAP;
            s.seed(p.seed);
            resetHistory();
        });
    }

    private void changeGridSize(int n) {
        tasks.add(() -> {
            Simulator old = sim;
            if (old.n == n) return;
            Simulator s = new Simulator(n);
            s.copyParamsFrom(old);
            sim = s;
        });
        reseed();
    }

    /** Leave the map and go to live at a chosen (F, k). */
    private void visit(double f, double k) {
        suppressEvents = true;
        presetBox.setSelectedIndex(-1);
        suppressEvents = false;
        currentPreset = Preset.MITOSIS;          // only used for its FEW seeding
        fSlider.setEnabled(true);
        kSlider.setEnabled(true);
        fSlider.setValue(f);
        kSlider.setValue(k);
        blurb.setText(String.format("Custom world at F = %.4f, k = %.4f. Not every world can grow from a few drops: "
                + "if the dish stays empty, press Reset with More seeds, or paint some catalyst in with the mouse.", f, k));
        pushParams();
        tasks.add(() -> { Simulator s = sim; s.mapMode = false; s.seed(Seed.MANY); resetHistory(); });
    }

    // =====================================================================
    //  UI
    // =====================================================================

    private void buildUI() {
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(new EmptyBorder(8, 8, 8, 14));

        JPanel scenario = section("World");
        presetBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        presetBox.addActionListener(e -> {
            if (!suppressEvents && presetBox.getSelectedItem() != null) applyPreset((Preset) presetBox.getSelectedItem());
        });
        scenario.add(presetBox);
        blurb.setLineWrap(true); blurb.setWrapStyleWord(true); blurb.setEditable(false);
        blurb.setOpaque(false); blurb.setFont(blurb.getFont().deriveFont(12f));
        blurb.setBorder(new EmptyBorder(6, 2, 2, 2));
        scenario.add(blurb);
        controls.add(scenario);

        JPanel chem = section("Chemistry");
        for (ParamSlider ps : new ParamSlider[]{fSlider, kSlider, ratioSlider}) { ps.onChange(this::pushParams); chem.add(ps); }
        JLabel mapLabel = new JLabel("F–k map: click to choose a world");
        mapLabel.setFont(mapLabel.getFont().deriveFont(11f));
        mapLabel.setAlignmentX(LEFT_ALIGNMENT);
        chem.add(Box.createVerticalStrut(4));
        chem.add(mapLabel);
        mapChart.setAlignmentX(LEFT_ALIGNMENT);
        chem.add(mapChart);
        controls.add(chem);

        JPanel run = section("Simulation");
        speedSlider.onChange(this::pushParams);
        run.add(speedSlider);
        JPanel buttons = new JPanel(new GridLayout(3, 3, 4, 4));
        JButton stepBtn = new JButton("Step");
        JButton resetBtn = new JButton("Reset");
        JButton moreBtn = new JButton("More seeds");
        JButton clearBtn = new JButton("Clear");
        JButton saveBtn = new JButton("Save PNG");
        JButton helpBtn = new JButton("About");
        playButton.addActionListener(e -> {
            running = playButton.isSelected();
            playButton.setText(running ? "Pause" : "Play");
        });
        stepBtn.addActionListener(e -> singleStep = true);
        resetBtn.addActionListener(e -> { pushParams(); reseed(); });
        moreBtn.addActionListener(e -> tasks.add(() -> {
            Simulator s = sim;
            if (s.mapMode) return;
            s.seed(Seed.MANY);
            resetHistory();
        }));
        clearBtn.addActionListener(e -> tasks.add(() -> { sim.clear(); resetHistory(); }));
        saveBtn.addActionListener(e -> savePng());
        helpBtn.addActionListener(e -> showAbout());
        buttons.add(playButton); buttons.add(stepBtn); buttons.add(resetBtn);
        buttons.add(moreBtn); buttons.add(clearBtn); buttons.add(saveBtn);
        buttons.add(helpBtn);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        run.add(Box.createVerticalStrut(4));
        run.add(buttons);
        JPanel gridRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        gridRow.add(new JLabel("Grid size:  "));
        sizeBox.addActionListener(e -> changeGridSize((Integer) sizeBox.getSelectedItem()));
        gridRow.add(sizeBox);
        gridRow.setAlignmentX(LEFT_ALIGNMENT);
        run.add(gridRow);
        controls.add(run);

        JPanel disp = section("Display");
        JPanel mapRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        mapRow.add(new JLabel("Colours:  "));
        mapBox.addActionListener(e -> colourMap = (ColourMap) mapBox.getSelectedItem());
        mapRow.add(mapBox);
        mapRow.setAlignmentX(LEFT_ALIGNMENT);
        disp.add(mapRow);
        countBox.addActionListener(e -> { countCells = countBox.isSelected(); resetHistory(); });
        countBox.setAlignmentX(LEFT_ALIGNMENT);
        disp.add(countBox);
        JLabel pLabel = new JLabel("Population over time");
        pLabel.setFont(pLabel.getFont().deriveFont(11f));
        pLabel.setAlignmentX(LEFT_ALIGNMENT);
        disp.add(pLabel);
        popPlot.setAlignmentX(LEFT_ALIGNMENT);
        disp.add(popPlot);
        controls.add(disp);

        JLabel hint = new JLabel("<html><i>Left-drag: add catalyst (V)<br>Right-drag: wash back to plain food (U)</i></html>");
        hint.setBorder(new EmptyBorder(6, 4, 0, 0));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(hint);
        controls.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setPreferredSize(new Dimension(380, 800));
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        status.setBorder(new EmptyBorder(4, 10, 4, 10));
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scroll, BorderLayout.WEST);
        getContentPane().add(view, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 6, 6, 6)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private void updateStatus() {
        Simulator s = sim;
        String where;
        if (s.mapMode) {
            where = Double.isNaN(hoverF) ? "map: hover to read F and k, click to visit"
                    : String.format("map: F = %.4f  k = %.4f  (click to visit)", hoverF, hoverK);
        } else {
            where = String.format("F = %.4f  k = %.4f", s.feed, s.kill)
                    + (countCells ? String.format("   cells %d", cellCount) : "");
        }
        status.setText(String.format("steps %9d   grid %d×%d   %6.0f steps/s   %s",
                s.steps, s.n, s.n, stepsPerSecond, where));
    }

    private void savePng() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("gray-scott.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".png")) f = new File(f.getPath() + ".png");
        BufferedImage out = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        view.paintField(g, 0, 0, 1024, false);
        g.dispose();
        try { ImageIO.write(out, "png", f); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage()); }
    }

    private void showAbout() {
        String html = """
            <html><body style='width:460px; font-family:sans-serif'>
            <h2>The Gray–Scott model</h2>
            <p>Imagine a dish filled with a chemical <b>U</b> (the food). A second chemical, <b>V</b>,
            feeds on it: whenever one U meets two Vs, it turns into a third V. So V makes more of
            itself, but only where there's food. Two other things happen all the time:</p>
            <ul>
            <li>Fresh food is poured in everywhere at the <b>feed rate F</b>.</li>
            <li>V is drained away at the <b>kill rate k</b> (plus the feed rate).</li>
            </ul>
            <p>Both chemicals also spread out, and the food spreads about twice as fast as V
            (the <b>diffusion ratio</b>). That difference is the secret ingredient. A patch of V
            uses up the food around it, and because the food rushes in from further away,
            patches end up at a characteristic size and spacing.</p>
            <p>That's all there is to it. Yet small changes to F and k produce completely different
            worlds: spots that divide like cells, coral, worms, mazes, holes and restless waves.
            Pick <b>The F–k map</b> to see every world at once, side by side. The small map under
            the sliders shows where you are; click it to jump anywhere.</p>
            <p><b>Count cells</b> counts the separate blobs of V. In the <i>Mitosis</i> world, watch
            the count double again and again as spots grow and split, then level off when the
            dish is full: a population curve made of nothing but chemistry.</p>
            <p>The model was published by Peter Gray and Stephen Scott in the 1980s. In 1993 John
            Pearson showed how rich its patterns are, and Robert Munafo later mapped its worlds in
            detail. It's a close cousin of the Turing patterns behind animal markings and the
            Swift–Hohenberg patterns behind fingerprints.</p>
            <p style='color:#666'>Architect: Mario Gianota · Implementation: Claude (Anthropic) · MIT licence</p>
            </body></html>""";
        JOptionPane.showMessageDialog(this, new JLabel(html), "About Gray–Scott Lab", JOptionPane.PLAIN_MESSAGE);
    }

    // =====================================================================
    //  Widgets
    // =====================================================================

    static final class ParamSlider extends JPanel {
        private static final int TICKS = 2000;
        private final double min, max;
        private final String fmt;
        private final JSlider slider = new JSlider(0, TICKS);
        private final JLabel valueLabel = new JLabel();
        private Runnable listener = () -> { };

        ParamSlider(String name, double min, double max, double initial, String fmt) {
            this.min = min; this.max = max; this.fmt = fmt;
            setLayout(new BorderLayout());
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            JPanel top = new JPanel(new BorderLayout());
            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(12f));
            valueLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            top.add(nameLabel, BorderLayout.WEST);
            top.add(valueLabel, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(slider, BorderLayout.CENTER);
            slider.addChangeListener(e -> { refresh(); listener.run(); });
            setValue(initial);
        }

        double value() { return min + (max - min) * slider.getValue() / TICKS; }
        void setValue(double v) { slider.setValue((int) Math.round((v - min) / (max - min) * TICKS)); refresh(); }
        void onChange(Runnable r) { listener = r; }
        private void refresh() { valueLabel.setText(String.format(fmt, value())); }

        @Override public void setEnabled(boolean e) {
            super.setEnabled(e);
            slider.setEnabled(e);
            for (Component c : ((JPanel) getComponent(0)).getComponents()) c.setEnabled(e);
        }
    }

    /** Main view. */
    final class ViewPanel extends JPanel {
        ViewPanel() {
            setPreferredSize(new Dimension(800, 800));
            setBackground(new Color(0x10, 0x14, 0x16));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { handle(e, true); }
                @Override public void mouseDragged(MouseEvent e) { handle(e, false); }
                @Override public void mouseMoved(MouseEvent e) { hover(e); }
                @Override public void mouseExited(MouseEvent e) { hoverF = Double.NaN; hoverK = Double.NaN; }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        }

        private int side() { return Math.min(getWidth(), getHeight()) - 20; }

        private double[] toGrid(MouseEvent e) {
            int side = side();
            int ox = (getWidth() - side) / 2, oy = (getHeight() - side) / 2;
            Simulator s = sim;
            double gx = (e.getX() - ox) * (double) s.n / side;
            double gy = (e.getY() - oy) * (double) s.n / side;
            if (gx < 0 || gy < 0 || gx >= s.n || gy >= s.n) return null;
            return new double[]{gx, gy};
        }

        private void hover(MouseEvent e) {
            Simulator s = sim;
            double[] g = toGrid(e);
            if (!s.mapMode || g == null) { hoverF = Double.NaN; hoverK = Double.NaN; return; }
            hoverF = Simulator.mapF((int) g[1], s.n);
            hoverK = Simulator.mapK((int) g[0], s.n);
        }

        private void handle(MouseEvent e, boolean press) {
            Simulator s = sim;
            double[] g = toGrid(e);
            if (g == null) return;
            if (s.mapMode) {
                if (press) visit(Simulator.mapF((int) g[1], s.n), Simulator.mapK((int) g[0], s.n));
                return;
            }
            boolean add = !SwingUtilities.isRightMouseButton(e);
            double radius = Math.max(3, s.n / 64.0);
            tasks.add(() -> { if (sim == s) s.brush(g[0], g[1], radius, add); });
        }

        private void tag(Graphics2D g, String text, int x, int y) {
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(text) + 12, h = fm.getHeight() + 4;
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(x, y, w, h, 8, 8);
            g.setColor(Color.WHITE);
            g.drawString(text, x + 6, y + fm.getAscent() + 2);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            int side = side();
            if (side <= 0) return;
            paintField((Graphics2D) g0, (getWidth() - side) / 2, (getHeight() - side) / 2, side, true);
        }

        void paintField(Graphics2D g, int ox, int oy, int side, boolean overlays) {
            BufferedImage img = frame;
            if (img == null) return;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, ox, oy, side, side, null);
            if (overlays && sim.mapMode) {
                g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
                tag(g, String.format("F = %.3f", MAP_F_MAX), ox + 8, oy + 8);
                tag(g, String.format("F = %.3f,  k = %.3f", MAP_F_MIN, MAP_K_MIN), ox + 8, oy + side - 30);
                tag(g, String.format("k = %.3f", MAP_K_MAX), ox + side - 100, oy + side - 30);
                tag(g, "F rises ↑   k increases →", ox + side / 2 - 85, oy + side - 30);
                if (!Double.isNaN(hoverF)) {
                    String t = String.format("F %.4f  k %.4f", hoverF, hoverK);
                    g.setColor(new Color(0, 0, 0, 160));
                    g.fillRoundRect(ox + side / 2 - 80, oy + 8, 160, 22, 10, 10);
                    g.setColor(Color.WHITE);
                    g.drawString(t, ox + side / 2 - 70, oy + 24);
                }
            }
        }
    }

    /** The F–k plane with the presets marked and the current world highlighted. Click to visit. */
    final class MapChart extends JPanel {
        private static final double K0 = MAP_K_MIN, K1 = MAP_K_MAX, F0 = 0.0, F1 = 0.10;

        MapChart() {
            setPreferredSize(new Dimension(240, 190));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
            setBackground(new Color(0x1a, 0x1d, 0x1f));
            setToolTipText("");
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    double[] fk = toFK(e.getX(), e.getY());
                    if (fk != null) visit(fk[0], fk[1]);
                }
            });
        }

        private Rectangle plot() { return new Rectangle(34, 8, getWidth() - 44, getHeight() - 30); }

        private double[] toFK(int x, int y) {
            Rectangle r = plot();
            if (!r.contains(x, y)) return null;
            double k = K0 + (K1 - K0) * (x - r.x) / r.width;
            double f = F1 - (F1 - F0) * (y - r.y) / r.height;
            return new double[]{Math.max(0, f), k};
        }

        private int px(double k) { Rectangle r = plot(); return (int) (r.x + (k - K0) / (K1 - K0) * r.width); }
        private int py(double f) { Rectangle r = plot(); return (int) (r.y + (F1 - f) / (F1 - F0) * r.height); }

        @Override public String getToolTipText(MouseEvent e) {
            double[] fk = toFK(e.getX(), e.getY());
            if (fk == null) return null;
            for (Preset p : Preset.values())
                if (p != Preset.MAP && Math.abs(px(p.k) - e.getX()) < 6 && Math.abs(py(p.f) - e.getY()) < 6) return p.label;
            return String.format("F = %.4f, k = %.4f", fk[0], fk[1]);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle r = plot();
            g.setColor(new Color(0x24, 0x29, 0x2c));
            g.fill(r);

            // The saddle-node curve: above it (larger k at a given F) the plain-food state is the only one.
            // F = ... solved from k = sqrt(F)/2 - F.
            Path2D curve = new Path2D.Double();
            boolean first = true;
            for (int i = 0; i <= 200; i++) {
                double f = 0.25 * i / 200.0;
                double k = Math.sqrt(f) / 2 - f;
                if (f > F1 + 0.01) break;
                double x = px(k), y = py(f);
                if (first) { curve.moveTo(x, y); first = false; } else curve.lineTo(x, y);
            }
            Shape oldClip = g.getClip();
            g.clip(r);
            g.setColor(new Color(120, 200, 255, 120));
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{4, 4}, 0));
            g.draw(curve);
            g.setClip(oldClip);

            // Axes labels
            g.setColor(new Color(170, 170, 170));
            g.setFont(g.getFont().deriveFont(10f));
            g.drawString("k", r.x + r.width / 2, r.y + r.height + 18);
            g.drawString(String.format("%.2f", K0), r.x - 2, r.y + r.height + 12);
            g.drawString(String.format("%.2f", K1), r.x + r.width - 22, r.y + r.height + 12);
            g.drawString("F", 6, r.y + r.height / 2);
            g.drawString("0.10", 4, r.y + 8);
            g.drawString("0", 18, r.y + r.height);

            // Map extent
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(px(MAP_K_MIN), py(MAP_F_MAX), px(MAP_K_MAX) - px(MAP_K_MIN), py(MAP_F_MIN) - py(MAP_F_MAX));

            // Presets
            for (Preset p : Preset.values()) {
                if (p == Preset.MAP) continue;
                int x = px(p.k), y = py(p.f);
                g.setColor(new Color(235, 205, 120));
                g.fill(new Ellipse2D.Double(x - 3, y - 3, 6, 6));
            }

            // Current world
            Simulator s = sim;
            if (!s.mapMode) {
                int x = px(s.kill), y = py(s.feed);
                g.setColor(new Color(255, 80, 80));
                g.setStroke(new BasicStroke(2f));
                g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
                g.drawLine(x - 9, y, x - 6, y); g.drawLine(x + 6, y, x + 9, y);
                g.drawLine(x, y - 9, x, y - 6); g.drawLine(x, y + 6, x, y + 9);
            }
        }
    }

    /** Rolling population plot. */
    final class PopulationPlot extends JPanel {
        PopulationPlot() {
            setPreferredSize(new Dimension(240, 90));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
            setBackground(new Color(0x1a, 0x1d, 0x1f));
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] data;
            synchronized (histLock) {
                data = new int[cellHistCount];
                int start = (cellHistHead - cellHistCount + cellHistory.length) % cellHistory.length;
                for (int i = 0; i < cellHistCount; i++) data[i] = cellHistory[(start + i) % cellHistory.length];
            }
            int w = getWidth(), h = getHeight();
            g.setFont(g.getFont().deriveFont(10f));
            if (sim.mapMode || !countCells) {
                g.setColor(Color.GRAY);
                g.drawString(sim.mapMode ? "not counted on the map" : "counting is off", 8, h / 2);
                return;
            }
            if (data.length < 2) { g.setColor(Color.GRAY); g.drawString("waiting…", 8, h / 2); return; }
            int max = 1;
            for (int v : data) max = Math.max(max, v);
            g.setColor(new Color(110, 220, 170));
            g.setStroke(new BasicStroke(1.6f));
            int pxv = -1, pyv = -1;
            for (int i = 0; i < data.length; i++) {
                int x = (int) ((double) i / (data.length - 1) * (w - 1));
                int y = (int) (h - 4 - (double) data[i] / max * (h - 16));
                if (pxv >= 0) g.drawLine(pxv, pyv, x, y);
                pxv = x; pyv = y;
            }
            g.setColor(new Color(210, 210, 210));
            g.drawString(String.valueOf(data[data.length - 1]), w - 40, 12);
        }
    }
}
