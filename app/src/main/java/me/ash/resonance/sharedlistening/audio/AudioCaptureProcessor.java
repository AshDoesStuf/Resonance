package me.ash.resonance.sharedlistening.audio;

import androidx.annotation.OptIn;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@OptIn(markerClass = UnstableApi.class)
public class AudioCaptureProcessor implements AudioProcessor {
  private AudioFormat inputAudioFormat = AudioFormat.NOT_SET;
  private boolean isActive = false;
  private ByteBuffer buffer = EMPTY_BUFFER;
  private ByteBuffer outputBuffer = EMPTY_BUFFER;
  private boolean inputEnded;

  private Listener listener;

  public void setListener(Listener listener) {
    this.listener = listener;
  }

  @Override
  public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    this.inputAudioFormat = inputAudioFormat;
    return inputAudioFormat;
  }

  @Override
  public boolean isActive() {
    return isActive && inputAudioFormat != AudioFormat.NOT_SET;
  }

  public void setActive(boolean active) {
    this.isActive = active;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    if (!inputBuffer.hasRemaining()) {
      return;
    }

    int remaining = inputBuffer.remaining();
    if (buffer.capacity() < remaining) {
      buffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
    } else {
      buffer.clear();
    }

    if (outputBuffer.capacity() < remaining) {
      outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder());
    } else {
      outputBuffer.clear();
    }

    int position = inputBuffer.position();
    outputBuffer.put(inputBuffer);
    outputBuffer.flip();
    inputBuffer.position(position);

    if (listener != null) {
      listener.onAudioData(inputBuffer, inputAudioFormat.sampleRate, inputAudioFormat.channelCount);
    }
  }

  @Override
  public void queueEndOfStream() {
    inputEnded = true;
  }

  @Override
  public ByteBuffer getOutput() {
    ByteBuffer output = outputBuffer;
    outputBuffer = EMPTY_BUFFER;
    return output;
  }

  @Override
  public boolean isEnded() {
    return inputEnded && outputBuffer == EMPTY_BUFFER;
  }

  @Override
  public void flush() {
    outputBuffer = EMPTY_BUFFER;
    inputEnded = false;
  }

  @Override
  public void reset() {
    flush();
    buffer = EMPTY_BUFFER;
    inputAudioFormat = AudioFormat.NOT_SET;
  }

  public interface Listener {
    void onAudioData(ByteBuffer data, int sampleRate, int channelCount);
  }
}

