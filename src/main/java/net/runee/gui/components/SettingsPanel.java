package net.runee.gui.components;

import com.jgoodies.forms.builder.FormBuilder;
import jouvieje.bass.Bass;
import jouvieje.bass.structures.BASS_DEVICEINFO;
import net.runee.BotManager;
import net.runee.gui.renderer.PlaybackDeviceListCellRenderer;
import net.runee.gui.renderer.RecordingDeviceListCellRenderer;
import net.runee.gui.listitems.PlaybackDeviceItem;
import net.runee.misc.Utils;
import net.runee.misc.gui.SpecBuilder;
import net.runee.gui.listitems.RecordingDeviceItem;
import net.runee.model.Config;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

public class SettingsPanel extends JPanel {
    // audio
    private JButton speakEnabled;
    private JButton listenEnabled;
    private JList<RecordingDeviceItem> recordingDevices;
    private JList<PlaybackDeviceItem> playbackDevices;
    private JCheckBox speakThresholdEnabled;
    private JSlider speakThreshold;

    public SettingsPanel() {
        initComponents();
        layoutComponents();
        loadConfig();
    }

    private void initComponents() {
        // audio
        speakEnabled = new JButton();
        speakEnabled.addActionListener(e -> {
            final Config cfg = BotManager.getConfig();
            cfg.speakEnabled = !cfg.getSpeakEnabled();
            BotManager.setSpeakEnabled(cfg.getSpeakEnabled());
            updateSpeakEnabled();
            saveConfig();
        });
        listenEnabled = new JButton();
        listenEnabled.addActionListener(e -> {
            final Config cfg = BotManager.getConfig();
            cfg.listenEnabled = !cfg.getListenEnabled();
            BotManager.setListenEnabled(cfg.getListenEnabled());
            updateListenEnabled();
            saveConfig();
        });
        recordingDevices = new JList<>();
        recordingDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recordingDevices.setCellRenderer(new RecordingDeviceListCellRenderer());
        recordingDevices.addListSelectionListener(e -> {
            if (recordingDevices.getSelectedIndex() >= 0) {
                RecordingDeviceItem value = recordingDevices.getSelectedValue();
                String recordingDevice = value != null ? value.getName() : null;
                BotManager.setRecordingDevice(recordingDevice);
                BotManager.getConfig().recordingDevice = recordingDevice;
                saveConfig();
            }
        });
        playbackDevices = new JList<>();
        playbackDevices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playbackDevices.setCellRenderer(new PlaybackDeviceListCellRenderer());
        playbackDevices.addListSelectionListener(e -> {
            if (playbackDevices.getSelectedIndex() >= 0) {
                PlaybackDeviceItem value = playbackDevices.getSelectedValue();
                String playbackDevice = value != null ? value.getName() : null;
                BotManager.setPlaybackDevice(playbackDevice);
                BotManager.getConfig().playbackDevice = playbackDevice;
                saveConfig();
            }
        });
        speakThresholdEnabled = new JCheckBox();
        speakThresholdEnabled.addActionListener(e -> {
            final Config cfg = BotManager.getConfig();
            cfg.speakThresholdEnabled = !cfg.getSpeakThresholdEnabled();
            updateSpeakThresholdEnabled();
            saveConfig();
        });
        speakThreshold = new JSlider();
        speakThreshold.setMinimum(1);
        speakThreshold.setMaximum(99);
        speakThreshold.addChangeListener(e -> {
            if (!speakThreshold.getValueIsAdjusting()) {
                final Config cfg = BotManager.getConfig();
                cfg.speakThreshold = speakThreshold.getValue() * (1d/100d);
                saveConfig();
            }
        });
    }

    private void loadConfig() {
        final Config cfg = BotManager.getConfig();

        // voice
        speakEnabled.setSelected(cfg.getSpeakEnabled());
        updateSpeakEnabled();
        listenEnabled.setSelected(cfg.getListenEnabled());
        updateListenEnabled();
        {
            DefaultListModel<RecordingDeviceItem> model = new DefaultListModel<>();
            //model.addElement(null);
            BASS_DEVICEINFO info = BASS_DEVICEINFO.allocate();
            for (int device = 0; Bass.BASS_RecordGetDeviceInfo(device, info); device++) {
                model.addElement(new RecordingDeviceItem(info.getName(), device));
            }
            info.release();
            recordingDevices.setModel(model);
            for (int i = 0; i < model.getSize(); i++) {
                RecordingDeviceItem recordingDevice = model.get(i);
                String recordingDeviceName = recordingDevice != null ? recordingDevice.getName() : null;
                if (Objects.equals(recordingDeviceName, cfg.recordingDevice)) {
                    recordingDevices.setSelectedIndex(i);
                    break;
                }
            }
        }
        {
            DefaultListModel<PlaybackDeviceItem> model = new DefaultListModel<>();
            //model.addElement(null);
            BASS_DEVICEINFO info = BASS_DEVICEINFO.allocate();
            for (int device = 0; Bass.BASS_GetDeviceInfo(device, info); device++) {
                model.addElement(new PlaybackDeviceItem(info.getName(), device));
            }
            info.release();
            playbackDevices.setModel(model);
            for (int i = 0; i < model.getSize(); i++) {
                PlaybackDeviceItem playbackDeviceItem = model.get(i);
                String playbackDeviceName = playbackDeviceItem != null ? playbackDeviceItem.getName() : null;
                if (Objects.equals(playbackDeviceName, cfg.playbackDevice)) {
                    playbackDevices.setSelectedIndex(i);
                    break;
                }
            }
        }
        speakThresholdEnabled.setSelected(cfg.getSpeakThresholdEnabled());
        speakThreshold.setValue((int) (cfg.getSpeakThreshold() * 100));
        updateSpeakThresholdEnabled();
    }

    private void saveConfig() {
        try {
            BotManager.saveConfig();
        } catch (IOException ex) {
            Utils.guiError(this, "Failed to save config", ex);
        }
    }

    private void layoutComponents() {
        int row = 1;
        FormBuilder
                .create()
                .columns(SpecBuilder
                        .create()
                        .add("r:p")
                        .add("f:max(p;100px)")
                        .gap("f:3dlu:g")
                        .add("r:p")
                        .add("f:max(p;100px)")
                        .build()
                )
                .rows(SpecBuilder
                        .create()
                        .add("c:p") // audio
                        .add("c:p")
                        .add("t:p")
                        .add("c:p", 4)
                        .build()
                )
                .columnGroups(new int[]{1, 5}, new int[]{2, 6})
                .panel(this)
                .border(BorderFactory.createEmptyBorder(5, 5, 5, 5))
                .addSeparator("Audio (shared by all bots)").xyw(1, row, 7)
                .add("Mute/Unmute").xy(1, row += 2)
                /**/.add(speakEnabled).xy(3, row)
                /**/.add("Deafen/Undeafen").xy(5, row)
                /**/.add(listenEnabled).xy(7, row)
                .add("Input device").xy(1, row += 2)
                /**/.add(recordingDevices).xy(3, row)
                /**/.add("Output device").xy(5, row)
                /**/.add(playbackDevices).xy(7, row)
                .add("Voice activity").xy(1, row += 2)
                /**/.add(speakThresholdEnabled).xy(3, row)
                .add("Speak threshold").xy(1, row += 2)
                /**/.add(speakThreshold).xy(3, row)
                .build();
    }

    private void updateSpeakEnabled() {
        boolean enabled = BotManager.getConfig().getSpeakEnabled();
        ImageIcon icon = Utils.getIcon("icomoon/32px/031-mic.png", 24, true);
        if (!enabled) {
            icon = new ImageIcon(Utils.overlayImage((BufferedImage) icon.getImage(), Utils.getIcon("runee/32px/strike-through.png", 24, true).getImage()));
        }
        speakEnabled.setIcon(icon);
        recordingDevices.setEnabled(enabled);
    }

    private void updateListenEnabled() {
        boolean enabled = BotManager.getConfig().getListenEnabled();
        ImageIcon icon = Utils.getIcon("icomoon/32px/017-headphones.png", 24, true);
        if (!enabled) {
            icon = new ImageIcon(Utils.overlayImage((BufferedImage) icon.getImage(), Utils.getIcon("runee/32px/strike-through.png", 24, true).getImage()));
        }
        listenEnabled.setIcon(icon);
        playbackDevices.setEnabled(enabled);
    }

    private void updateSpeakThresholdEnabled() {
        boolean enabled = BotManager.getConfig().getSpeakThresholdEnabled();
        speakThreshold.setEnabled(enabled);
    }
}
