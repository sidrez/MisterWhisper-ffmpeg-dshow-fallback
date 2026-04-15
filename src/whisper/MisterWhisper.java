package whisper;

import java.awt.AWTException;
import java.awt.CheckboxMenuItem;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Robot;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window.Type;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

public class MisterWhisper implements NativeKeyListener {

    private static final int MIN_AUDIO_DATA_LENGTH = (int) (16000 * 2.1);
    private static final String FFMPEG_AUDIO_DEVICE_ENV = "MISTERWHISPER_FFMPEG_DEVICE";
    private static final String FFMPEG_BIN_ENV = "MISTERWHISPER_FFMPEG_BIN";

    private Preferences prefs;

    // Whisper
    private LocalWhisperCPP w;
    private String model;
    private String remoteUrl;
    // Tray icon
    private TrayIcon trayIcon;
    private Image imageRecording;
    private Image imageTranscribing;
    private Image imageInactive;

    // Execution services
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ExecutorService audioService = Executors.newSingleThreadExecutor();

    // Audio capture
    private AudioFormat audioFormat;

    private boolean recording;
    private boolean transcribing;

    // History
    private List<String> history = new ArrayList<>();
    private List<ChangeListener> historyListeners = new ArrayList<>();

    // Hotkey for recording
    private String hotkey;
    private boolean shiftHotkey;
    private boolean ctrltHotkey;
    private long recordingStartTime = 0;
    private boolean hotkeyPressed;
    // Trigger mode
    private static final String START_STOP = "start_stop";
    private static final String PUSH_TO_TALK_DOUBLE_TAP = "push_to_talk_double_tap";
    private static final String PUSH_TO_TALK = "push_to_talk";

    protected JFrame window;
    final JButton button = new JButton("Start");

    final JLabel label = new JLabel("Idle");

    private boolean debug;

    private static final String[] ALLOWED_HOTKEYS = { "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17", "F18" };
    private static final int[] ALLOWED_HOTKEYS_CODE = { NativeKeyEvent.VC_F1, NativeKeyEvent.VC_F2, NativeKeyEvent.VC_F3, NativeKeyEvent.VC_F4, NativeKeyEvent.VC_F5, NativeKeyEvent.VC_F6,
            NativeKeyEvent.VC_F7, NativeKeyEvent.VC_F8, NativeKeyEvent.VC_F9, NativeKeyEvent.VC_F10, NativeKeyEvent.VC_F11, NativeKeyEvent.VC_F12, NativeKeyEvent.VC_F13, NativeKeyEvent.VC_F14,
            NativeKeyEvent.VC_F15, NativeKeyEvent.VC_F16, NativeKeyEvent.VC_F17, NativeKeyEvent.VC_F18 };

    // Action
    enum Action {
        COPY_TO_CLIPBOARD_AND_PASTE, TYPE_STRING, NOTHING
    }

    public MisterWhisper(String remoteUrl) throws FileNotFoundException, NativeHookException {
        if (MisterWhisper.ALLOWED_HOTKEYS.length != MisterWhisper.ALLOWED_HOTKEYS_CODE.length) {
            throw new IllegalStateException("ALLOWED_HOTKEYS size mismatch");
        }

        this.prefs = Preferences.userRoot().node("mister-whisper");
        this.hotkey = this.prefs.get("hotkey", "F9");
        this.shiftHotkey = this.prefs.getBoolean("shift-hotkey", false);
        this.ctrltHotkey = this.prefs.getBoolean("ctrl-hotkey", false);
        this.model = this.prefs.get("model", "ggml-large-v3-turbo-q8_0.bin");

        GlobalScreen.registerNativeHook();
        GlobalScreen.addNativeKeyListener(this);

        // Create audio format
        float sampleRate = 16000.0F;
        int sampleSizeInBits = 16;
        int channels = 1;
        boolean signed = true;
        boolean bigEndian = false;
        this.audioFormat = new AudioFormat(sampleRate, sampleSizeInBits, channels, signed, bigEndian);

        this.remoteUrl = remoteUrl;
        if (remoteUrl == null) {

            File dir = new File("models");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            boolean hasModels = false;
            for (File f : dir.listFiles()) {
                if (f.getName().endsWith(".bin")) {
                    hasModels = true;
                }
            }
            if (!hasModels) {
                JOptionPane.showMessageDialog(null,
                        "Please download a model (.bin file) from :\nhttps://huggingface.co/ggerganov/whisper.cpp/tree/main\n\n and copy it in :\n" + dir.getAbsolutePath());
                if (Desktop.isDesktopSupported()) {
                    final Desktop desktop = Desktop.getDesktop();
                    if (desktop.isSupported(Desktop.Action.BROWSE)) {
                        try {
                            desktop.browse(new URI("https://huggingface.co/ggerganov/whisper.cpp/tree/main"));
                        } catch (IOException | URISyntaxException e) {
                            e.printStackTrace();
                        }
                    }
                    if (desktop.isSupported(Desktop.Action.OPEN)) {
                        try {
                            desktop.open(dir);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                }
                System.exit(0);
            }

            if (!new File(dir, this.model).exists()) {
                for (File f : dir.listFiles()) {
                    if (f.getName().endsWith(".bin")) {
                        this.model = f.getName();
                        setModelPref(f.getName());
                        break;
                    }
                }
            }

            this.w = new LocalWhisperCPP(new File(dir, this.model));
            System.out.println("MisterWhisper using WhisperCPP with " + this.model);
        } else {
            System.out.println("MisterWhisper using remote speech to text service : " + remoteUrl);
        }
    }

    void createTrayIcon() {
        this.imageRecording = loadTrayIconImage("recording.png");
        this.imageInactive = loadTrayIconImage("inactive.png");
        this.imageTranscribing = loadTrayIconImage("transcribing.png");
        if (this.imageInactive == null) {
            this.imageInactive = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        }
        if (this.imageRecording == null) {
            this.imageRecording = this.imageInactive;
        }
        if (this.imageTranscribing == null) {
            this.imageTranscribing = this.imageInactive;
        }

        this.trayIcon = new TrayIcon(this.imageInactive, "Press " + this.hotkey + " to record");
        this.trayIcon.setImageAutoSize(true);
        final SystemTray tray = SystemTray.getSystemTray();
        final Frame frame = new Frame("");
        frame.setUndecorated(true);
        frame.setType(Type.UTILITY);
        // Create a pop-up menu components
        final PopupMenu popup = createPopupMenu();
        this.trayIcon.setPopupMenu(popup);
        this.trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    stopRecording();
                }
            }

        });
        try {
            frame.setResizable(false);
            frame.setVisible(true);
            tray.add(this.trayIcon);
        } catch (AWTException ex) {
            System.out.println("TrayIcon could not be added.\n" + ex.getMessage());
        }

    }

    private Image loadTrayIconImage(String name) {
        URL location = this.getClass().getResource(name);
        if (location == null) {
            location = this.getClass().getResource("/whisper/" + name);
        }
        if (location != null) {
            return new ImageIcon(location).getImage();
        }

        File fromOut = new File("out\\whisper", name);
        if (fromOut.isFile()) {
            return new ImageIcon(fromOut.getAbsolutePath()).getImage();
        }

        File fromSrc = new File("src\\whisper", name);
        if (fromSrc.isFile()) {
            return new ImageIcon(fromSrc.getAbsolutePath()).getImage();
        }

        System.out.println("Tray icon not found: " + name);
        return null;
    }

    protected PopupMenu createPopupMenu() {
        final String strAction = this.prefs.get("action", "paste");

        final PopupMenu popup = new PopupMenu();

        CheckboxMenuItem autoPaste = new CheckboxMenuItem("Auto paste");
        autoPaste.setState(strAction.equals("paste"));
        popup.add(autoPaste);

        CheckboxMenuItem autoType = new CheckboxMenuItem("Auto type");
        autoType.setState(strAction.equals("type"));
        popup.add(autoType);

        final ItemListener typeListener = new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getSource().equals(autoPaste) && e.getStateChange() == ItemEvent.SELECTED) {
                    System.out.println("itemStateChanged() PASTE " + e.toString());
                    MisterWhisper.this.prefs.put("action", "paste");
                    autoType.setState(false);
                } else if (e.getSource().equals(autoType) && e.getStateChange() == ItemEvent.SELECTED) {
                    System.out.println("itemStateChanged() TYPE " + e.toString());
                    MisterWhisper.this.prefs.put("action", "type");
                    autoPaste.setState(false);
                } else {
                    MisterWhisper.this.prefs.put("action", "nothing");
                }

                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        };
        autoPaste.addItemListener(typeListener);
        autoType.addItemListener(typeListener);

        CheckboxMenuItem detectSilece = new CheckboxMenuItem("Silence detection");
        detectSilece.setState(this.prefs.getBoolean("silence-detection", false));
        detectSilece.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                MisterWhisper.this.prefs.putBoolean("silence-detection", detectSilece.getState());
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
            }
        });
        popup.add(detectSilece);
        Menu hotkeysMenu = new Menu("Keyboard shortcut");
        // Shift hotkey modifier
        final CheckboxMenuItem shiftHotkeyMenuItem = new CheckboxMenuItem("SHIFT");
        shiftHotkeyMenuItem.setState(this.prefs.getBoolean("shift-hotkey", false));
        hotkeysMenu.add(shiftHotkeyMenuItem);
        shiftHotkeyMenuItem.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {

                MisterWhisper.this.shiftHotkey = shiftHotkeyMenuItem.getState();
                MisterWhisper.this.prefs.putBoolean("shift-hotkey", MisterWhisper.this.shiftHotkey);
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
                updateToolTip();
            }
        });
        // Ctrl hotkey modifier
        final CheckboxMenuItem ctrlHotkeyMenuItem = new CheckboxMenuItem("CTRL");
        ctrlHotkeyMenuItem.setState(this.prefs.getBoolean("ctrl-hotkey", false));
        hotkeysMenu.add(ctrlHotkeyMenuItem);
        ctrlHotkeyMenuItem.addItemListener(new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {

                MisterWhisper.this.ctrltHotkey = ctrlHotkeyMenuItem.getState();
                MisterWhisper.this.prefs.putBoolean("ctrl-hotkey", MisterWhisper.this.ctrltHotkey);
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                }
                updateToolTip();
            }
        });
        hotkeysMenu.addSeparator();
        for (final String key : MisterWhisper.ALLOWED_HOTKEYS) {
            final CheckboxMenuItem hotkeyMenuItem = new CheckboxMenuItem(key);
            if (this.hotkey.equals(key)) {
                hotkeyMenuItem.setState(true);
            }
            hotkeysMenu.add(hotkeyMenuItem);
            hotkeyMenuItem.addItemListener(new ItemListener() {

                @Override
                public void itemStateChanged(ItemEvent e) {
                    if (hotkeyMenuItem.getState()) {
                        MisterWhisper.this.hotkey = key;
                        MisterWhisper.this.prefs.put("hotkey", MisterWhisper.this.hotkey);
                        try {
                            MisterWhisper.this.prefs.sync();
                        } catch (BackingStoreException e1) {
                            e1.printStackTrace();
                            JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + e1.getMessage());
                        }
                        hotkeyMenuItem.setState(false);
                        updateToolTip();

                    }
                }
            });
        }

        if (this.remoteUrl == null) {
            Menu modelMenu = new Menu("Models");

            final File dir = new File("models");
            List<CheckboxMenuItem> allModels = new ArrayList<>();
            if (new File(dir, this.model).exists()) {
                for (File f : dir.listFiles()) {
                    final String name = f.getName();
                    if (name.endsWith(".bin")) {
                        final boolean selected = this.model.equals(name);
                        String cleanName = name.replace(".bin", "");
                        cleanName = cleanName.replace(".bin", "");
                        cleanName = cleanName.replace("ggml", "");
                        cleanName = cleanName.replace("-", " ");
                        cleanName = cleanName.trim();
                        final CheckboxMenuItem modelItem = new CheckboxMenuItem(cleanName);

                        modelItem.setState(selected);

                        modelItem.addItemListener(new ItemListener() {

                            @Override
                            public void itemStateChanged(ItemEvent e) {
                                if (modelItem.getState()) {
                                    // Deselected others
                                    for (CheckboxMenuItem item : allModels) {
                                        if (item != modelItem) {
                                            item.setState(false);
                                        }
                                    }
                                    // Apply model
                                    MisterWhisper.this.model = f.getName();
                                    setModelPref(MisterWhisper.this.model);
                                    try {
                                        MisterWhisper.this.w = new LocalWhisperCPP(f);
                                    } catch (FileNotFoundException e1) {
                                        JOptionPane.showMessageDialog(null, e1.getMessage());
                                        e1.printStackTrace();
                                    }
                                }
                            }

                        });
                        allModels.add(modelItem);
                        modelMenu.add(modelItem);
                    }
                }
            }

            popup.add(modelMenu);
        }
        popup.add(hotkeysMenu);

        final Menu modeMenu = new Menu("Key trigger mode");

        final CheckboxMenuItem pushToTalkItem = new CheckboxMenuItem("Push to talk");
        final CheckboxMenuItem pushToTalkDoubleTapItem = new CheckboxMenuItem("Push to talk + double tap");
        final CheckboxMenuItem startStopItem = new CheckboxMenuItem("Start / Stop");

        String currentMode = this.prefs.get("trigger-mode", PUSH_TO_TALK);

        pushToTalkItem.setState(PUSH_TO_TALK.equals(currentMode));
        pushToTalkDoubleTapItem.setState(PUSH_TO_TALK_DOUBLE_TAP.equals(currentMode));
        startStopItem.setState(START_STOP.equals(currentMode));

        if (!pushToTalkItem.getState() && !pushToTalkDoubleTapItem.getState() && !startStopItem.getState()) {
            pushToTalkItem.setState(true);
            MisterWhisper.this.prefs.put("trigger-mode", PUSH_TO_TALK);
            try {
                MisterWhisper.this.prefs.sync();
            } catch (BackingStoreException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
            }
        }

        final ItemListener modeListener = new ItemListener() {

            @Override
            public void itemStateChanged(ItemEvent e) {
                CheckboxMenuItem source = (CheckboxMenuItem) e.getSource();
                if (source == pushToTalkItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(true);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(false);
                    MisterWhisper.this.prefs.put("trigger-mode", PUSH_TO_TALK);
                } else if (source == pushToTalkDoubleTapItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(true);
                    startStopItem.setState(false);
                    MisterWhisper.this.prefs.put("trigger-mode", PUSH_TO_TALK_DOUBLE_TAP);
                } else if (source == startStopItem && e.getStateChange() == ItemEvent.SELECTED) {
                    pushToTalkItem.setState(false);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(true);
                    MisterWhisper.this.prefs.put("trigger-mode", START_STOP);
                } else {
                    // Default to push to talk
                    pushToTalkItem.setState(true);
                    pushToTalkDoubleTapItem.setState(false);
                    startStopItem.setState(false);
                    MisterWhisper.this.prefs.put("trigger-mode", PUSH_TO_TALK);
                }
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
                }
            }
        };

        pushToTalkItem.addItemListener(modeListener);
        pushToTalkDoubleTapItem.addItemListener(modeListener);
        startStopItem.addItemListener(modeListener);

        modeMenu.add(pushToTalkItem);
        modeMenu.add(pushToTalkDoubleTapItem);
        modeMenu.add(startStopItem);

        popup.add(modeMenu);
        final Menu audioInputsItem = new Menu("Audio inputs");
        String audioDevice = this.prefs.get("audio.device", "");
        String previsouAudipDevice = this.prefs.get("audio.device.previous", "");
        // Get available audio input devices

        List<String> mixers = getInputsMixerNames();
        if (!mixers.isEmpty()) {
            String currentAudioDevice = "";
            if (!audioDevice.isEmpty() && mixers.contains(audioDevice)) {
                currentAudioDevice = audioDevice;
            } else if (!previsouAudipDevice.isEmpty() && mixers.contains(previsouAudipDevice)) {
                currentAudioDevice = previsouAudipDevice;
            } else {
                currentAudioDevice = mixers.get(0);
                this.prefs.put("audio.device", currentAudioDevice);
                try {
                    this.prefs.sync();
                } catch (BackingStoreException e1) {
                    e1.printStackTrace();
                }
            }
            Collections.sort(mixers);
            List<CheckboxMenuItem> all = new ArrayList<>();
            for (String name : mixers) {

                CheckboxMenuItem menuItem = new CheckboxMenuItem(name);
                if (currentAudioDevice.equals(name)) {
                    menuItem.setState(true);
                }
                audioInputsItem.add(menuItem);
                all.add(menuItem);
                // Add action listener to each menu item
                menuItem.addItemListener(new ItemListener() {

                    @Override
                    public void itemStateChanged(ItemEvent e) {
                        if (menuItem.getState()) {

                            for (CheckboxMenuItem m : all) {
                                final boolean selected = m.getLabel().equals(name);
                                m.setState(selected);

                            }
                            // Set preference
                            MisterWhisper.this.prefs.put("audio.device.previous", MisterWhisper.this.prefs.get("audio.device", ""));
                            MisterWhisper.this.prefs.put("audio.device", name);
                            try {
                                MisterWhisper.this.prefs.sync();
                            } catch (BackingStoreException e1) {
                                e1.printStackTrace();
                            }
                        }
                    }
                });

            }
        }
        popup.add(audioInputsItem);

        CheckboxMenuItem openWindowItem = new CheckboxMenuItem("Open Window");
        openWindowItem.setState(this.prefs.getBoolean("open-window", true));
        openWindowItem.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                boolean state = openWindowItem.getState();
                MisterWhisper.this.prefs.putBoolean("open-window", state);
                try {
                    MisterWhisper.this.prefs.sync();
                } catch (BackingStoreException ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(null, "Cannot save preferences\n" + ex.getMessage());
                }
                if (state) {
                    if (MisterWhisper.this.window == null || !MisterWhisper.this.window.isVisible()) {
                        MisterWhisper.this.openWindow();
                    }
                    if (MisterWhisper.this.window != null) {
                        MisterWhisper.this.window.toFront();
                        MisterWhisper.this.window.requestFocus();
                    }
                } else {
                    if (MisterWhisper.this.window != null && MisterWhisper.this.window.isVisible()) {
                        MisterWhisper.this.window.setVisible(false);
                    }
                }

            }
        });
        popup.add(openWindowItem);
        final MenuItem historyItem = new MenuItem("History");

        popup.add(historyItem);

        popup.addSeparator();
        MenuItem exitItem = new MenuItem("Exit");
        popup.add(exitItem);
        exitItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);

            }
        });
        historyItem.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                showHistory();

            }
        });
        return popup;
    }

    protected void updateToolTip() {
        String tooltip = "Press ";
        if (MisterWhisper.this.shiftHotkey) {
            tooltip += "Shift + ";
        }
        if (MisterWhisper.this.ctrltHotkey) {
            tooltip += "Ctrl + ";
        }
        tooltip += MisterWhisper.this.hotkey + " to record";
        if (this.trayIcon != null) {
            MisterWhisper.this.trayIcon.setToolTip(tooltip);
        }
        System.out.println(tooltip);
    }

    private List<String> getInputsMixerNames() {
        final List<String> names = new ArrayList<>();
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();
            boolean ok = false;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    ok = true;
                    break;
                }
            }
            if (ok) {

                names.add(mixerInfo.getName());
            }
        }
        return names;
    }

    private TargetDataLine getFirstTargetDataLine() {
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        // Return first
        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();

            for (Line.Info lineInfo : targetLines) {
                if (lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    try {
                        return (TargetDataLine) mixer.getLine(lineInfo);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        }
        return null;
    }

    private TargetDataLine getTargetDataLine(String audioDevice) {
        if (audioDevice == null || audioDevice.isEmpty()) {
            return null;
        }
        final Mixer.Info[] mixers = AudioSystem.getMixerInfo();

        for (Mixer.Info mixerInfo : mixers) {
            final Mixer mixer = AudioSystem.getMixer(mixerInfo);
            final Line.Info[] targetLines = mixer.getTargetLineInfo();

            Line.Info lInfo = null;
            for (Line.Info lineInfo : targetLines) {
                if (lineInfo != null && lineInfo.getLineClass().getName().contains("TargetDataLine")) {
                    lInfo = lineInfo;
                    if (mixerInfo.getName().equals(audioDevice)) {
                        try {
                            return (TargetDataLine) mixer.getLine(lineInfo);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (this.hotkeyPressed) {
            return;
        }
        int modifier = 0;
        if (this.shiftHotkey) {
            modifier += 1;
        }
        if (this.ctrltHotkey) {
            modifier += 2;
        }
        if (e.getModifiers() != modifier) {
            return;
        }
        final int length = MisterWhisper.ALLOWED_HOTKEYS_CODE.length;
        for (int i = 0; i < length; i++) {
            if (MisterWhisper.ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode() && this.hotkey.equals(MisterWhisper.ALLOWED_HOTKEYS[i])) {
                this.hotkeyPressed = true;

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        final String strAction = MisterWhisper.this.prefs.get("action", "paste");
                        Action action = Action.NOTHING;
                        if (strAction.equals("paste")) {
                            action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
                        } else if (strAction.equals("type")) {
                            action = Action.TYPE_STRING;
                        }

                        if (!isRecording()) {
                            MisterWhisper.this.recordingStartTime = System.currentTimeMillis();
                            startRecording(action);
                        } else {
                            stopRecording();
                        }
                    }
                });
                break;
            }
        }

    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {
        int modifier = 0;
        if (this.shiftHotkey) {
            modifier += 1;
        }
        if (this.ctrltHotkey) {
            modifier += 2;
        }
        if (e.getModifiers() != modifier) {
            return;
        }

        final int length = MisterWhisper.ALLOWED_HOTKEYS_CODE.length;
        for (int i = 0; i < length; i++) {
            if (MisterWhisper.ALLOWED_HOTKEYS_CODE[i] == e.getKeyCode() && this.hotkey.equals(MisterWhisper.ALLOWED_HOTKEYS[i])) {
                this.hotkeyPressed = false;

                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {

                        String currentMode = MisterWhisper.this.prefs.get("trigger-mode", PUSH_TO_TALK);
                        if (currentMode.equals(PUSH_TO_TALK)) {
                            stopRecording();
                        } else if (currentMode.equals(PUSH_TO_TALK_DOUBLE_TAP)) {
                            long delta = System.currentTimeMillis() - MisterWhisper.this.recordingStartTime;
                            if (delta > 300) {
                                stopRecording();
                            }
                        }
                    }
                });
                break;
            }
        }

    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        // Not used but required by the interface
    }

    private void startRecording(Action action) {
        System.out.println("MisterWhisper.startRecording()" + action);
        if (isRecording()) {
            // Prevent multiple recordings
            return;
        }

        setRecording(true);
        try {
            String audioDevice = this.prefs.get("audio.device", "");
            String previsouAudipDevice = this.prefs.get("audio.device.previous", "");

            // Create a thread to capture the audio data
            this.audioService.execute(new Runnable() {

                @Override
                public void run() {
                    TargetDataLine targetDataLine;
                    try {
                        targetDataLine = getTargetDataLine(audioDevice);
                        if (targetDataLine == null) {
                            targetDataLine = getTargetDataLine(previsouAudipDevice);
                            if (targetDataLine == null) {
                                targetDataLine = getFirstTargetDataLine();
                            } else {
                                System.out.println("Using previous audio device : " + previsouAudipDevice);
                            }
                            if (targetDataLine == null) {
                                if (captureWithFfmpegFallback(action, audioDevice, previsouAudipDevice)) {
                                    return;
                                }
                                JOptionPane.showMessageDialog(null, "Cannot find any input audio device");
                                setRecording(false);
                                return;
                            } else {
                                System.out.println("Using default audio device");
                            }
                        } else {
                            System.out.println("Using audio device : " + audioDevice);
                        }

                        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        try {
                            targetDataLine.open(MisterWhisper.this.audioFormat);
                            targetDataLine.start();

                            setRecording(true);

                            // 0.25s
                            byte[] data = new byte[8000];
                            boolean detectSilence = MisterWhisper.this.prefs.getBoolean("silence-detection", false);
                            if (detectSilence) {
                                while (isRecording()) {
                                    int numBytesRead = targetDataLine.read(data, 0, data.length);
                                    if (numBytesRead > 0) {
                                        boolean silence = detectSilence(data, numBytesRead, 500);

                                        if (silence) {
                                            byte[] audioData = byteArrayOutputStream.toByteArray();
                                            byteArrayOutputStream.reset();
                                            MisterWhisper.this.executorService.execute(new Runnable() {

                                                @Override
                                                public void run() {
                                                    try {
                                                        transcribe(audioData, action, false);
                                                    } catch (IOException e) {
                                                        e.printStackTrace();
                                                    }
                                                }
                                            });
                                        } else {
                                            byteArrayOutputStream.write(data, 0, numBytesRead);
                                        }

                                    }
                                }
                            } else {
                                while (isRecording()) {
                                    int numBytesRead = targetDataLine.read(data, 0, data.length);
                                    if (numBytesRead > 0) {
                                        byteArrayOutputStream.write(data, 0, numBytesRead);
                                    }
                                }
                            }

                        } catch (LineUnavailableException e) {
                            System.out.println("Audio input device not available (used by an other process?)");
                            if (captureWithFfmpegFallback(action, audioDevice, previsouAudipDevice)) {
                                return;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            try {
                                targetDataLine.stop();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            try {
                                targetDataLine.close();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        final byte[] audioData = byteArrayOutputStream.toByteArray();
                        setRecording(false);

                        MisterWhisper.this.executorService.execute(new Runnable() {

                            @Override
                            public void run() {
                                try {
                                    transcribe(audioData, action, true);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();

                    }
                    setRecording(false);
                    setTranscribing(false);

                }
            });

        } catch (Exception e) {
            setRecording(false);
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Error starting recording: " + e.getMessage());
        }
    }

    private boolean captureWithFfmpegFallback(final Action action, String audioDevice, String previousAudioDevice) {
        String device = System.getenv(FFMPEG_AUDIO_DEVICE_ENV);
        if (device == null || device.isBlank()) {
            device = this.prefs.get("ffmpeg.audio.device", "");
        }
        if (device == null || device.isBlank()) {
            device = audioDevice;
        }
        if (device == null || device.isBlank()) {
            device = previousAudioDevice;
        }
        if (device == null || device.isBlank()) {
            device = findFirstDshowAudioDevice(getFfmpegBinary());
        }
        if (device == null || device.isBlank()) {
            return false;
        }
        final String ffmpegBin = getFfmpegBinary();
        final ProcessBuilder pb = new ProcessBuilder(ffmpegBin, "-hide_banner", "-loglevel", "warning", "-f", "dshow", "-i", "audio=" + device, "-ac", "1", "-ar", "16000", "-f", "s16le", "-");
        pb.redirectErrorStream(false);
        try {
            final Process process = pb.start();
            System.out.println("Using ffmpeg dshow fallback with device: " + device);
            final Thread errThread = new Thread(() -> {
                try (InputStream err = process.getErrorStream()) {
                    byte[] b = new byte[1024];
                    while (err.read(b) != -1) {
                        // drain stderr so ffmpeg does not block
                    }
                } catch (IOException ex) {
                    // ignore stderr drain errors
                }
            }, "ffmpeg-stderr-drain");
            errThread.setDaemon(true);
            errThread.start();

            final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] data = new byte[8000];
                while (isRecording()) {
                    int numBytesRead = in.read(data, 0, data.length);
                    if (numBytesRead > 0) {
                        byteArrayOutputStream.write(data, 0, numBytesRead);
                    } else if (numBytesRead < 0) {
                        break;
                    }
                }
            } finally {
                try {
                    process.destroy();
                    process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            final byte[] audioData = byteArrayOutputStream.toByteArray();
            setRecording(false);

            MisterWhisper.this.executorService.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        transcribe(audioData, action, true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });
            return true;
        } catch (IOException e) {
            System.out.println("ffmpeg fallback failed: " + e.getMessage());
            return false;
        }
    }

    private String getFfmpegBinary() {
        String ffmpegBin = System.getenv(FFMPEG_BIN_ENV);
        if (ffmpegBin == null || ffmpegBin.isBlank()) {
            ffmpegBin = this.prefs.get("ffmpeg.bin", "ffmpeg");
        }
        if (ffmpegBin == null || ffmpegBin.isBlank()) {
            return "ffmpeg";
        }
        return ffmpegBin;
    }

    private String findFirstDshowAudioDevice(String ffmpegBin) {
        final ProcessBuilder pb = new ProcessBuilder(ffmpegBin, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy");
        pb.redirectErrorStream(true);
        try {
            final Process process = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                boolean inAudioSection = false;
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("DirectShow audio devices")) {
                        inAudioSection = true;
                        continue;
                    }
                    if (!inAudioSection) {
                        continue;
                    }
                    if (line.contains("DirectShow video devices")) {
                        inAudioSection = false;
                        continue;
                    }
                    int firstQuote = line.indexOf('"');
                    int lastQuote = line.lastIndexOf('"');
                    if (firstQuote >= 0 && lastQuote > firstQuote && !line.contains("Alternative name")) {
                        String device = line.substring(firstQuote + 1, lastQuote).trim();
                        if (!device.isEmpty()) {
                            return device;
                        }
                    }
                }
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            if (this.debug) {
                System.out.println("Cannot enumerate dshow devices via ffmpeg: " + e.getMessage());
            }
        }
        return null;
    }

    public void transcribe(byte[] audioData, final Action action, boolean isEndOfCapture) throws IOException {
        if (detectSilence(audioData, audioData.length, 100)) {
            if (this.debug) {
                System.out.println("Silence detected");
            }
            return;
        }
        if (audioData.length < MIN_AUDIO_DATA_LENGTH) {
            byte[] n = new byte[MIN_AUDIO_DATA_LENGTH];
            System.arraycopy(audioData, 0, n, 0, audioData.length);
            audioData = n;
        }

        setTranscribing(true);

        String str;
        if (MisterWhisper.this.remoteUrl == null) {
            str = this.w.transcribeRaw(audioData);
        } else {
            // Save the recorded audio to a WAV file for remote
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fileName = timestamp + ".wav";
            final File out = File.createTempFile("rec_", fileName);
            try (AudioInputStream audioInputStream = new AudioInputStream(new ByteArrayInputStream(audioData), this.audioFormat, audioData.length / this.audioFormat.getFrameSize())) {
                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, out);
                str = processRemote(out, action);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Error processing record : " + e.getMessage());
                e.printStackTrace();
                setTranscribing(false);
                return;
            } finally {
                if (this.debug) {
                    System.out.println("Audio record stored in : " + out.getAbsolutePath());
                } else {
                    boolean deleted = out.delete();
                    if (!deleted) {
                        Logger.getGlobal().warning("cannot delete " + out.getAbsolutePath());
                    }
                }
            }
        }
        str = str.replace('\n', ' ');
        str = str.replace('\r', ' ');
        str = str.replace('\t', ' ');
        str = str.trim();
        final String suffix = "Thank you.";
        if (str.endsWith(suffix)) {
            str = str.substring(0, str.length() - suffix.length());
        }

        if (!isEndOfCapture) {
            str += " ";
        }
        final String finalStr = str;

        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                if (action.equals(Action.TYPE_STRING)) {
                    try {
                        RobotTyper typer = new RobotTyper();
                        System.out.println("Typing : " + finalStr);
                        typer.typeString(finalStr, 11);
                    } catch (AWTException e) {
                        e.printStackTrace();
                    }
                } else if (action.equals(Action.COPY_TO_CLIPBOARD_AND_PASTE)) {
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    Transferable previous;
                    try {
                        previous = clipboard.getContents(null);
                    } catch (Exception e) {
                        previous = null;
                        try {
                            GlobalScreen.registerNativeHook();
                        } catch (NativeHookException e1) {
                            e1.printStackTrace();
                        }
                        System.out.println("Warning : cannot get previous clipboard content");
                    }
                    final Transferable toPaste = previous;
                    clipboard.setContents(new StringSelection(finalStr), null);
                    try {
                        Robot robot = new Robot();
                        System.out.println("Pasting : " + finalStr);
                        robot.keyPress(KeyEvent.VK_CONTROL);
                        robot.keyPress(KeyEvent.VK_V);
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        robot.keyRelease(KeyEvent.VK_V);
                        robot.keyRelease(KeyEvent.VK_CONTROL);
                        System.out.println("Pasting : " + finalStr + " DONE");

                    } catch (AWTException e) {
                        e.printStackTrace();
                    }
                    if (toPaste != null) {
                        Thread t = new Thread(new Runnable() {
                            public void run() {
                                if (toPaste != null) {
                                    try {
                                        Thread.sleep(1000);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                    System.out.println("Restoring previous clipboard content");
                                    clipboard.setContents(toPaste, null);

                                }
                            }
                        });
                        t.start();
                    }

                }
                // Invoke later to be sure paste is done
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        MisterWhisper.this.history.add(finalStr);
                        fireHistoryChanged();
                    }
                });
            }
        });

        setTranscribing(false);

    }

    protected synchronized void setTranscribing(boolean b) {
        this.transcribing = b;
        updateIcon();
    }

    public synchronized boolean isTranscribing() {
        return this.transcribing;
    }

    public synchronized boolean isRecording() {
        return this.recording;
    }

    public synchronized void setRecording(boolean b) {
        this.recording = b;
        updateIcon();
    }

    private void updateIcon() {
        SwingUtilities.invokeLater(new Runnable() {

            @Override
            public void run() {
                if (MisterWhisper.this.window != null) {
                    if (isRecording()) {
                        MisterWhisper.this.button.setText("Stop");
                        MisterWhisper.this.label.setText("Recording");
                    } else {
                        MisterWhisper.this.button.setText("Start");

                        if (isTranscribing()) {
                            MisterWhisper.this.label.setText("Transcribing");
                        } else {
                            MisterWhisper.this.label.setText("Idle");
                        }

                    }
                    if (isRecording()) {
                        MisterWhisper.this.window.setIconImage(MisterWhisper.this.imageRecording);
                    } else {
                        if (isTranscribing()) {
                            MisterWhisper.this.window.setIconImage(MisterWhisper.this.imageTranscribing);
                        } else {
                            MisterWhisper.this.window.setIconImage(MisterWhisper.this.imageInactive);
                        }

                    }

                }
                if (MisterWhisper.this.trayIcon != null) {
                    if (isRecording()) {
                        MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageRecording);
                    } else {
                        if (isTranscribing()) {
                            MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageTranscribing);
                        } else {
                            MisterWhisper.this.trayIcon.setImage(MisterWhisper.this.imageInactive);
                        }

                    }
                }

            }
        });

    }

    private String processRemote(File out, Action action) throws IOException {
        long t1 = System.currentTimeMillis();
        String string = new RemoteWhisperCPP(this.remoteUrl).transcribe(out, 0.0, 0.01);
        long t2 = System.currentTimeMillis();
        System.out.println("Response from remote whisper.cpp (" + (t2 - t1) + " ms): " + string);
        return string.trim();

    }

    private void stopRecording() {
        if (!this.isRecording()) {
            return;
        }
        setRecording(false);
    }

    public void setModelPref(String name) {

        this.prefs.put("model", name);
        try {
            this.prefs.flush();
        } catch (BackingStoreException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, "Cannot save preferences");
        }
    }

    public void addHistoryListener(ChangeListener l) {
        this.historyListeners.add(l);
    }

    public void removeHistoryListener(ChangeListener l) {
        this.historyListeners.remove(l);
    }

    public void clearHistory() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalAccessError("Must be called from EDT");
        }
        this.history.clear();
        fireHistoryChanged();
    }

    public void fireHistoryChanged() {
        for (ChangeListener l : this.historyListeners) {
            l.stateChanged(new ChangeEvent(this));
        }
    }

    public List<String> getHistory() {
        return this.history;
    }

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException e) {
                e.printStackTrace();
            }
            try {
                Boolean debug = false;
                String url = null;
                boolean forceOpenWindow = false;
                for (int i = 0; i < args.length; i++) {
                    final String arg = args[i];
                    if (!arg.startsWith("-D")) {

                        if (arg.startsWith("http")) {
                            url = arg;
                        } else if (arg.equals("--window")) {
                            forceOpenWindow = true;
                        } else if (arg.equals("--debug")) {
                            debug = true;
                        }
                    }
                }
                final MisterWhisper r = new MisterWhisper(url);
                r.debug = debug;
                boolean openWindow = r.prefs.getBoolean("open-window", true);
                if (forceOpenWindow) {
                    openWindow = true;
                }
                try {
                    r.createTrayIcon();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (openWindow) {
                    r.openWindow();
                }

            } catch (Throwable e) {
                JOptionPane.showMessageDialog(null, "Error :\n" + e.getMessage());
                e.printStackTrace();
            }
        });

    }

    private void openWindow() {
        this.window = new JFrame("MisterWhisper");
        this.window.setIconImage(this.imageInactive);
        this.window.setFocusable(false);
        this.window.setFocusableWindowState(false);
        JPanel p = new JPanel();
        p.setLayout(new FlowLayout(FlowLayout.RIGHT));
        p.add(this.label);
        p.add(this.button);

        final JButton historyButton = new JButton("History");
        p.add(historyButton);

        final JButton prefButton = new JButton("Prefs");
        p.add(prefButton);
        this.window.setContentPane(p);
        this.label.setText("Transcribing..");
        this.window.pack();
        this.label.setText("Idle");
        this.window.setResizable(false);
        this.window.setVisible(true);
        this.window.setLocationRelativeTo(null);
        this.window.setVisible(true);
        this.window.toFront();
        this.window.requestFocus();
        final PopupMenu popup = createPopupMenu();
        prefButton.add(popup);
        this.button.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                final String strAction = MisterWhisper.this.prefs.get("action", "paste");
                Action action = Action.NOTHING;
                if (strAction.equals("paste")) {
                    action = Action.COPY_TO_CLIPBOARD_AND_PASTE;
                } else if (strAction.equals("type")) {
                    action = Action.TYPE_STRING;
                }

                if (!isRecording()) {
                    MisterWhisper.this.recordingStartTime = System.currentTimeMillis();
                    startRecording(action);
                } else {
                    stopRecording();
                }

            }
        });
        historyButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                showHistory();
            }
        });

        prefButton.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {

                popup.show((Component) e.getSource(), 0, 0);

            }
        });

    }

    public void showHistory() {
        HistoryFrame f = new HistoryFrame(MisterWhisper.this);
        f.setSize(600, 800);
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    private static boolean detectSilence(byte[] buffer, int bytesRead, int threshold) {
        int maxAmplitude = 0;
        // 16-bit audio = 2 bytes per sample
        for (int i = 0; i < bytesRead; i += 2) {
            int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            maxAmplitude = Math.max(maxAmplitude, Math.abs(sample));
        }

        return maxAmplitude < threshold;
    }
}
