package me.ash.resonance.sharedlistening.audio;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class OpusEncoderWrapper {
    private static final String TAG = "OpusEncoderWrapper";
    private static final String MIME_TYPE = "audio/opus";
    private static final int OPUS_SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BITRATE = 64000;
    private static final int FRAME_DURATION_MS = 20;
    private static final int PCM_FRAME_SIZE = (OPUS_SAMPLE_RATE * CHANNELS * 2 * FRAME_DURATION_MS) / 1000;

    public interface Callback {
        void onEncodedFrame(byte[] data);
    }

    private Callback callback;
    private MediaCodec encoder;
    private boolean isRunning = false;
    private Thread encodingThread;
    private final BlockingQueue<byte[]> pcmQueue = new ArrayBlockingQueue<>(100);
    private final ByteBuffer leftoverBuffer = ByteBuffer.allocate(PCM_FRAME_SIZE * 2).order(ByteOrder.nativeOrder());

    public OpusEncoderWrapper() {
        try {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, OPUS_SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_COMPLEXITY, 10); // Max complexity for production quality
            // Some devices might require these
            // format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_FRAME_SIZE);
            
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            isRunning = true;
            encodingThread = new Thread(this::encodingLoop, "OpusEncodingThread");
            encodingThread.start();
            Log.i(TAG, "Opus Encoder (MediaCodec) started successfully");
        } catch (IOException e) {
            Log.e(TAG, "Failed to create Opus encoder", e);
        }
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void encode(ByteBuffer pcmData, int sampleRate, int channelCount) {
        if (!isRunning) return;

        // Note: For production, we should handle sample rate conversion if it's not 48kHz.
        // But ExoPlayer usually outputs 44.1k or 48k. 
        // For simplicity, we assume 48k stereo for now or that MediaCodec handles it if configured correctly.
        // Actually, MediaCodec config defines the input it expects.
        
        while (pcmData.hasRemaining()) {
            int toCopy = Math.min(pcmData.remaining(), leftoverBuffer.remaining());
            byte[] chunk = new byte[toCopy];
            pcmData.get(chunk);
            leftoverBuffer.put(chunk);

            if (!leftoverBuffer.hasRemaining()) {
                leftoverBuffer.flip();
                byte[] frame = new byte[PCM_FRAME_SIZE];
                leftoverBuffer.get(frame);
                leftoverBuffer.compact();
                
                if (!pcmQueue.offer(frame)) {
                    Log.w(TAG, "PCM queue full, dropping frame");
                }
            }
        }
    }

    private void encodingLoop() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (isRunning) {
            try {
                byte[] pcmFrame = pcmQueue.poll(10, TimeUnit.MILLISECONDS);
                if (!isRunning) break;

                // Capture local reference to avoid null pointer if release() is called
                MediaCodec codec = encoder;
                if (codec == null) break;

                if (pcmFrame != null) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            inputBuffer.put(pcmFrame);
                            codec.queueInputBuffer(inputIndex, 0, pcmFrame.length, System.nanoTime() / 1000, 0);
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
                while (outputIndex >= 0) {
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        byte[] encodedData = new byte[bufferInfo.size];
                        outputBuffer.get(encodedData);

                        if (callback != null) {
                            callback.onEncodedFrame(encodedData);
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false);
                    if (!isRunning) break;
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IllegalStateException e) {
                // This state can occur if the codec is released from another thread while we're using it
                if (isRunning) {
                    Log.e(TAG, "MediaCodec IllegalStateException in loop", e);
                }
                break;
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Error in encoding loop", e);
                }
            }
        }
        Log.i(TAG, "Opus encoding loop finished");
    }

    public void release() {
        isRunning = false;
        if (encodingThread != null) {
            encodingThread.interrupt();
            try {
                encodingThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            encodingThread = null;
        }
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing encoder", e);
            }
            encoder = null;
        }
    }
}
