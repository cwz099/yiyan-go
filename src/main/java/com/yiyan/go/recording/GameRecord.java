package com.yiyan.go.recording;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;

import java.time.Instant;
import java.util.List;

/** A validated, read-only view of the active game line. Undone moves remain in the audit log. */
public final class GameRecord {
    public enum Status {
        IN_PROGRESS("进行中"), PAUSED("已保存 · 未完局"), RESIGNED("认输结束"),
        ESTIMATED("已停手 · 待确认结算"), SCORED("已确认结算");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Ply(Move move, List<Point> captured, String source, String motivation,
                      long elapsedMs, Instant time, BoardState position) {
        public Ply {
            captured = List.copyOf(captured);
            position = position.copy();
        }
        @Override public BoardState position() { return position.copy(); }
    }

    private final String id;
    private final Instant started;
    private final Instant updated;
    private final Stone humanColor;
    private final String opponent;
    private final BoardState initial;
    private final BoardState finalPosition;
    private final List<Ply> moves;
    private final Status status;
    private final String result;
    private final FinalScore finalScore;

    GameRecord(String id, Instant started, Instant updated, Stone humanColor, String opponent,
               BoardState initial, BoardState finalPosition, List<Ply> moves, Status status, String result, FinalScore finalScore) {
        this.id = id;
        this.started = started;
        this.updated = updated;
        this.humanColor = humanColor;
        this.opponent = opponent;
        this.initial = initial.copy();
        this.finalPosition = finalPosition.copy();
        this.moves = List.copyOf(moves);
        this.status = status;
        this.result = result;
        this.finalScore = finalScore;
    }

    public String id() { return id; }
    public Instant started() { return started; }
    public Instant updated() { return updated; }
    public Stone humanColor() { return humanColor; }
    public String opponent() { return opponent; }
    public int size() { return initial.size(); }
    public double komi() { return initial.komi(); }
    public List<Ply> moves() { return moves; }
    public Status status() { return status; }
    public String result() { return result; }
    public FinalScore finalScore() { return finalScore; }
    public BoardState finalPosition() { return finalPosition.copy(); }

    public BoardState positionAt(int ply) {
        if (ply < 0 || ply > moves.size()) throw new IllegalArgumentException("手数超出记录范围");
        return ply == 0 ? initial.copy() : moves.get(ply - 1).position();
    }
}
