package me.ash.resonance.sharedlistening.model;

public class CommandPacket {
    public enum Action {
        PLAY,
        PAUSE,
        NEXT,
        PREVIOUS,
        TOGGLE_PLAY_PAUSE
    }

    public Action action;

    public CommandPacket() {}

    public CommandPacket(Action action) {
        this.action = action;
    }
}
