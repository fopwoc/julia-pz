package zombie.gameStates;

/** Synthetic loading-state fixture, not game source. */
public final class GameLoadingState {
    private boolean forceDone;
    public boolean assetsReady;

    public GameStateMachine.StateAction update() {
        if (!assetsReady) return GameStateMachine.StateAction.Remain;
        return forceDone ? GameStateMachine.StateAction.Continue : GameStateMachine.StateAction.Remain;
    }
}
