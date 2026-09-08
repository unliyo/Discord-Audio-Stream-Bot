package net.runee;

import jouvieje.bass.Bass;
import jouvieje.bass.defines.BASS_ERROR;
import jouvieje.bass.defines.BASS_RECORD;
import jouvieje.bass.defines.BASS_STREAM;
import jouvieje.bass.structures.HRECORD;
import jouvieje.bass.utils.Pointer;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.runee.errors.BassException;
import net.runee.misc.MemoryQueue;
import net.runee.misc.Utils;
import net.runee.model.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static jouvieje.bass.defines.BASS_ACTIVE.*;
import static jouvieje.bass.defines.BASS_ERROR.BASS_ERROR_HANDLE;

/**
 * Feeds the audio of a recording device into one discord audio connection.
 * <p>
 * All handlers using the same recording device share a single BASS recording stream: the first one opens it,
 * every sample is fanned out to each handler's queue, and the stream is only paused/freed when no handler
 * needs it anymore. This is what allows many bots (one per party) to broadcast the same microphone.
 */
public class SpeakHandler implements AudioSendHandler, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(SpeakHandler.class);
    public static final int FRAME_MILLIS = 20;
    public static final int MAX_LAG = 200;

    /** guards every BASS call and the handler bookkeeping below */
    private static final Object lock = new Object();
    private static final List<SpeakHandler> activeHandlers = new CopyOnWriteArrayList<>();

    private final Object memoryQueueLock = new Object();
    private int recordingDevice;
    private HRECORD recordingStream;
    private volatile MemoryQueue memoryQueue;
    private boolean playing;
    private final byte[] buffer;

    public SpeakHandler() {
        this.recordingDevice = -1;
        this.buffer = new byte[INPUT_FORMAT.getChannels() * (int) (INPUT_FORMAT.getSampleRate() * (FRAME_MILLIS / 1000f)) * (INPUT_FORMAT.getSampleSizeInBits() / 8)];
    }

    public void openRecordingDevice(int recordingDevice, boolean setPlaying) throws BassException {
        synchronized (lock) {
            Utils.closeQuiet(this);

            this.recordingDevice = recordingDevice;
            this.playing = setPlaying;
            memoryQueue = new MemoryQueue();

            HRECORD sharedStream = null;
            for (SpeakHandler handler : activeHandlers) {
                if (handler.recordingDevice == recordingDevice && handler.recordingStream != null) {
                    sharedStream = handler.recordingStream;
                    break;
                }
            }

            if (sharedStream != null) {
                this.recordingStream = sharedStream;
                activeHandlers.add(this);
                applyPlayState();
            } else {
                try {
                    if (!Bass.BASS_RecordInit(recordingDevice)) {
                        int error = Bass.BASS_ErrorGetCode();
                        if (error != BASS_ERROR.BASS_ERROR_ALREADY) {
                            throw new BassException(error);
                        }
                    }
                    Bass.BASS_RecordSetDevice(recordingDevice);
                    int flags = BASS_STREAM.BASS_STREAM_AUTOFREE;
                    if (!setPlaying) {
                        flags |= BASS_RECORD.BASS_RECORD_PAUSE;
                    }
                    this.recordingStream = Bass.BASS_RecordStart((int) INPUT_FORMAT.getSampleRate(), INPUT_FORMAT.getChannels(), flags, SpeakHandler::RECORDPROC, null);
                    Utils.checkBassError();
                    if (this.recordingStream == null) {
                        throw new BassException(BASS_ERROR.BASS_ERROR_UNKNOWN);
                    }
                    logger.info("Opened recording device #" + recordingDevice);
                } catch (BassException ex) {
                    this.recordingStream = null;
                    Utils.closeQuiet(this);
                    throw ex;
                }
                activeHandlers.add(this);
            }
        }
    }

    /**
     * Marks this handler as (not) needing audio. The shared stream keeps running as long as at least one
     * handler sharing it is playing.
     */
    public void setPlaying(boolean playing) throws BassException {
        synchronized (lock) {
            if (recordingStream == null) {
                throw new IllegalStateException("No open stream, call openRecordingDevice first");
            }
            this.playing = playing;
            applyPlayState();
        }
    }

    /**
     * Plays or pauses the shared stream depending on whether any handler sharing it wants audio.
     * Must be called with {@link #lock} held.
     */
    private void applyPlayState() throws BassException {
        if (recordingStream == null) {
            return;
        }
        boolean anyPlaying = false;
        for (SpeakHandler handler : getSharingHandlers(recordingStream)) {
            if (handler.playing) {
                anyPlaying = true;
                break;
            }
        }
        switch (Bass.BASS_ChannelIsActive(recordingStream.asInt())) {
            case BASS_ACTIVE_PLAYING:
            case BASS_ACTIVE_STALLED:
                if (!anyPlaying) {
                    Bass.BASS_ChannelPause(recordingStream.asInt());
                }
                break;
            case BASS_ACTIVE_PAUSED:
            case BASS_ACTIVE_STOPPED:
                if (anyPlaying) {
                    Bass.BASS_ChannelPlay(recordingStream.asInt(), false);
                }
                break;
            default:
                throw new IndexOutOfBoundsException();
        }
        try {
            Utils.checkBassError();
        } catch (BassException ex) {
            if (ex.getError() == BASS_ERROR_HANDLE) {
                // TODO invalid handle:
                // not sure why, but after moving around guilds/channels a few times (with follow-audio on), the stream handle randomly becomes invalid (probably a synchronization issue)
                // as a workaround, let's open a new stream for everyone that was sharing the broken one
                logger.warn("Workaround: Restarting recording stream", ex);
                List<SpeakHandler> sharing = getSharingHandlers(recordingStream);
                for (SpeakHandler handler : sharing) {
                    handler.recordingStream = null;
                    activeHandlers.remove(handler);
                }
                for (SpeakHandler handler : sharing) {
                    handler.openRecordingDevice(handler.recordingDevice, handler.playing);
                }
            } else {
                throw ex;
            }
        }
    }

    public float getLag() {
        MemoryQueue memoryQueue = this.memoryQueue;
        if (memoryQueue == null) {
            return 0;
        }
        return (memoryQueue.size() / (float) buffer.length) * FRAME_MILLIS;
    }

    private static boolean RECORDPROC(HRECORD handle, ByteBuffer buffer, int length, Pointer user) {
        Config cfg = BotManager.getConfig();
        List<SpeakHandler> handlers = getSharingHandlers(handle);
        if (handlers.isEmpty()) {
            return true;
        }
        boolean thresholdEnabled = cfg.getSpeakThresholdEnabled();
        double threshold = cfg.getSpeakThreshold() * Short.MAX_VALUE;
        byte[] sampleBuffer = new byte[2];
        int numSamplesToWrite = length / sampleBuffer.length;
        for (int s = 0; s < numSamplesToWrite; s++) {
            short sample = buffer.getShort();
            if (thresholdEnabled && Math.abs((double) sample) <= threshold) {
                sample = 0;
            }
            sampleBuffer[0] = (byte) ((sample >> 8) & 0xff);
            sampleBuffer[1] = (byte) (sample & 0xff);
            for (SpeakHandler handler : handlers) {
                synchronized (handler.memoryQueueLock) {
                    MemoryQueue queue = handler.memoryQueue;
                    if (queue != null) {
                        queue.enqueue(sampleBuffer, 0, sampleBuffer.length);
                    }
                }
            }
        }
        return true;
    }

    private static List<SpeakHandler> getSharingHandlers(HRECORD handle) {
        List<SpeakHandler> matchingHandlers = new ArrayList<>();
        if (handle == null) {
            return matchingHandlers;
        }
        for (SpeakHandler handler : activeHandlers) {
            if (handle.equals(handler.recordingStream)) {
                matchingHandlers.add(handler);
            }
        }
        return matchingHandlers;
    }

    @Override
    public boolean canProvide() {
        MemoryQueue memoryQueue = this.memoryQueue;
        if (memoryQueue == null) {
            return false;
        }
        float lag = getLag();
        if (lag >= MAX_LAG) {
            synchronized (memoryQueueLock) {
                memoryQueue.clear();
            }
            logger.warn("SpeakHandler is " + (int) lag + " ms behind! Clearing queue...");
        }
        return memoryQueue.size() >= buffer.length;
    }

    @Nullable
    @Override
    public ByteBuffer provide20MsAudio() {
        int numBytesRead;
        synchronized (memoryQueueLock) {
            MemoryQueue memoryQueue = this.memoryQueue;
            if (memoryQueue == null) {
                return null;
            }
            numBytesRead = memoryQueue.dequeue(buffer, 0, buffer.length);
        }
        return ByteBuffer.wrap(buffer, 0, numBytesRead);
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            activeHandlers.remove(this);
            HRECORD stream = recordingStream;
            recordingStream = null;
            synchronized (memoryQueueLock) {
                memoryQueue = null;
            }
            if (stream != null) {
                List<SpeakHandler> sharing = getSharingHandlers(stream);
                if (sharing.isEmpty()) {
                    // last user of this stream: tear it down and free the device
                    Bass.BASS_ChannelStop(stream.asInt());
                    if (recordingDevice >= 0) {
                        Bass.BASS_RecordSetDevice(recordingDevice);
                        Bass.BASS_RecordFree();
                    }
                    logger.info("Closed recording device #" + recordingDevice);
                } else {
                    // others still use it: just re-evaluate whether it should keep playing
                    sharing.get(0).applyPlayState();
                }
            }
            // TODO invalid handle:
            // not sure why, but after moving around guilds/channels a few times (with follow-audio on), the stream handle becomes invalid  (probably a synchronization issue)
            // as a workaround, keep the recording device last used
            //recordingDevice = -1;
            Utils.checkBassError();
        }
    }
}
